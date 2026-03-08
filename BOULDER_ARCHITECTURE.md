# Boulder Architecture & Component Guide

Boulder is a JDBC-level proxy framework designed to intercept database interactions, allowing for a "Virtual-First" architecture. It completely isolates the physical database from write operations (`INSERT`, `UPDATE`, `DELETE`) by trapping them in an in-memory state layer. For read operations (`SELECT`), it seamlessly merges the physical data with the in-memory state, providing a unified, coherent view to the application (e.g., Hibernate) as if the database was actually modified.

This guide details how each component of the Boulder framework contributes to this architecture, which now relies on a powerful **Embedded Federated SQL Engine**.

## 1. `PitonDriver` (The Entry Point)
The `PitonDriver` is a custom JDBC driver.
*   **Purpose**: Acts as the initial interception layer. It registers itself with the `DriverManager`.
*   **Mechanism**: It listens for JDBC URLs that start with the prefix `jdbc:boulder:` (e.g., `jdbc:boulder:jdbc:h2:mem:cragdb`). When a connection is requested, it strips its prefix, uses the underlying real driver (e.g., H2) to establish a physical connection, and then wraps that physical `Connection` inside a `PitonConnection`.

## 2. `PitonConnection` (The Connection & Federation Wrapper)
Wraps the standard `java.sql.Connection`. This is the core orchestrator of the new embedded engine architecture.
*   **Purpose**: Intercepts the creation of `Statement` and `PreparedStatement` objects, and manages the lifecycle of the internal Federated SQL Engine.
*   **Mechanism**: 
    *   **Federated Engine Initialization**: When `PitonConnection` is instantiated, it simultaneously spins up an isolated, in-memory H2 database connection (`jdbc:h2:mem:federated_engine_...`).
    *   **Schema Mapping**: It immediately queries the physical database's `DatabaseMetaData` to discover all user tables. It then executes `CREATE LINKED TABLE phys_[table_name]` commands inside the federated engine, essentially mounting the physical data into the in-memory database as read-only streams.
    *   **Proxy Operations**: Contains logic to detect operations like `select next value for hibernate_sequence`. If detected, it bypasses creating a physical prepared statement, knowing that the proxy will handle the ID generation in memory.

## 3. `PitonStatement` & `PitonPreparedStatement` (The Execution Interceptors)
Wraps the standard JDBC execution interfaces to route queries dynamically.
*   **Mechanism (Writes)**: If it detects an `INSERT`, `UPDATE`, or `DELETE` query, it **does not** execute it on the delegate physical statement. Instead, it passes the SQL and parameters to the `DynoMerger`.
*   **Mechanism (Reads)**: For `SELECT` queries, the execution path is completely hijacked:
    1.  It forces the `PitonConnection` to synchronize the latest memory state (`syncChalkBagToFederatedEngine` and `syncViews`).
    2.  It creates a statement against the *Federated Engine connection*, **not** the physical delegate connection.
    3.  It executes the unmodified application `SELECT` query directly against the embedded Federated Engine, letting H2's robust C/Java core natively process all joins, functions, and aggregations.
    4.  It returns a lightweight wrapper (`PitonResultSet`) passing through the federated engine's results.

## 4. `ChalkBag` (The In-Memory State Store)
The `ChalkBag` is the core memory layer that holds all uncommitted, virtual state.
*   **Purpose**: To store patches, inserts, and tombstones (deletes) in memory, isolated per thread.
*   **Mechanism**:
    *   Uses a `ThreadLocal` structure to ensure thread safety (typically binding to the lifecycle of an HTTP request or transaction).
    *   Data is stored as a nested map: `TableName -> PrimaryKey -> ColumnName -> Value`.
    *   **Tombstones**: If a row is deleted, its map value is set to `null`, acting as a "tombstone" to mask the physical row from read queries.
    *   **ID Generation**: Contains an `AtomicInteger` (starting at 1,000,000) to safely generate virtual primary keys that won't collide with existing physical IDs.

## 5. `DynoMerger` (The Write Engine)
The `DynoMerger` is the proxy's write mutation layer. It intercepts physical data modification statements and applies them exclusively to the `ChalkBag` memory state.
*   **AST Parsing**: Relies on `JSqlParser` to convert raw SQL strings into an Abstract Syntax Tree (AST), extracting column mutation sets and resolving JDBC parameters.
*   **`INSERT` Interception**: Extracts specified columns/values. Generates a new ID via `ChalkBag` if none is provided. Inserts the map directly into memory and returns a spoofed `GeneratedKeysResultSet` to satisfy ORMs.
*   **Targeted PK `UPDATE` & `DELETE`**: Performs an immediate `O(1)` mutation in the `ChalkBag` map if the `WHERE` clause exactly matches the Primary Key.
*   **Non-PK `UPDATE` & `DELETE`**: Dynamically generates and executes a physical query (`SELECT id FROM [Table] WHERE [Condition]`) against the read-only underlying JDBC connection. It then applies patches/tombstones to all discovered physical primary keys in the `ChalkBag`.

## 6. The Federated View Engine (State Synchronization)
Instead of manually matching rows in Java, Boulder now dynamically generates SQL `VIEW`s that merge physical and virtual data. This happens inside `PitonConnection` just before any read execution.

### Deep Technical Mechanics:
1.  **Temp Table Sync (`syncChalkBagToFederatedEngine`)**:
    *   Iterates over the `ChalkBag`. For every modified table, it creates three local temporary tables in the Federated H2 Engine: `virt_ins_table`, `virt_upd_table`, and `virt_del_table`.
    *   It checks physical primary keys to route ChalkBag maps into the correct temporary table (e.g., if a PK exists physically, it's an update patch; if not, it's a pure insert). It then bulk inserts the data.
2.  **Dynamic View Generation (`syncViews`)**:
    *   Boulder dynamically generates a `CREATE OR REPLACE VIEW [table_name]` statement in the Federated Engine that shadows the exact name of the physical table.
    *   **The Merging Logic**: The generated View uses `COALESCE` to overlay updates onto linked physical rows, filters out tombstones using `NOT IN`, and appends pure inserts using `UNION ALL`.
    *   **Example Generated View**:
        ```sql
        CREATE VIEW hr_employee AS 
        -- Physical Base + Patches - Deletes
        SELECT 
            p.employeeid, 
            COALESCE(u.dept_id, p.dept_id) AS dept_id, 
            COALESCE(u.systenantid, p.systenantid) AS systenantid 
        FROM phys_hr_employee p 
        LEFT JOIN virt_upd_hr_employee u ON p.employeeid = u.employeeid 
        WHERE p.employeeid NOT IN (SELECT id FROM virt_del_hr_employee) 
        
        UNION ALL 
        
        -- Pure Virtual Inserts
        SELECT employeeid, dept_id, systenantid 
        FROM virt_ins_hr_employee;
        ```
3.  **Native Execution**:
    Because the application's query (e.g., `SELECT dept_id, COUNT(*) FROM hr_employee GROUP BY dept_id`) is executed directly against this Federated Engine, the database engine natively applies all complex SQL logic (joins, group bys, window functions) against the perfectly unified View data.

## 7. Limitations and Performance Tuning
Applying a Federated Engine architecture in a true Production environment introduces distinct performance characteristics and limits.

### Performance Limitations
*   **Data Serialization/Network Hop (Data-Pull Latency)**: Because H2 acts as a middleware engine via `LINKED TABLE`, when the application executes a query, H2 must pull the raw data over JDBC from the physical database, deserialize it into the H2 JVM memory space, execute the federated view logic, and then pass it to the application. For large tables, this double-hop serialization is significantly slower than a direct native JDBC stream.
*   **The "Predicate Pushdown" Problem**: This is the most critical performance bottleneck in any federated query engine. If the application queries `SELECT * FROM massive_table WHERE status = 'ACTIVE'`, H2 must be intelligent enough to push the `status = 'ACTIVE'` filter down to the actual physical database. 
    *   *The Risk*: Because Boulder obscures the physical table behind a `VIEW` with `COALESCE`, `UNION`, and `LEFT JOIN` operations, the H2 query optimizer might fail to recognize that it can push filters down. If pushdown fails, H2 will execute `SELECT * FROM massive_table` against the physical DB, pulling millions of rows over the network into JVM memory just to discard 99% of them in the middleware layer, causing a massive latency spike or an `OutOfMemoryError`.
*   **Synchronous State Overhead (`syncChalkBagToFederatedEngine`)**: Currently, every single `SELECT` query triggers a complete teardown and rebuild of the `virt_ins_`, `virt_upd_`, and `virt_del_` temporary tables and recreates the `VIEW`s. If a transaction performs many small reads mixed with writes, this DDL/DML synchronization overhead will dominate the execution time.

### How to Overcome These Limitations
If you intend to run this at scale, you must move beyond the naive "Sync everything on every Read" approach.

1.  **Lazy Synchronization (Dirty Flagging)**:
    *   **The Fix**: Implement a `dirty` flag in the `ChalkBag` per table. When `PitonPreparedStatement.executeQuery()` is called, Boulder should only drop, recreate, and repopulate the temporary tables for the specific tables that have been modified since the last sync. The `VIEW` definitions should be created once upon connection initialization and only altered if the physical schema changes.
2.  **Bypass the Engine for Clean Reads**:
    *   **The Fix**: If the application executes `SELECT * FROM system_config`, and the `ChalkBag` contains zero patches for `system_config`, Boulder should completely bypass the Federated Engine. It should route the query directly to the underlying physical `Connection` and return the physical `ResultSet`, eliminating the network hop and serialization overhead entirely.
3.  **Guarantee Predicate Pushdown (AST Rewriting)**:
    *   **The Fix**: Instead of relying on H2's internal query optimizer to push filters through a complex `VIEW`, Boulder can use `JSqlParser` to intelligently rewrite the intercepted SQL query on the fly. 
    *   If the query is `SELECT * FROM users WHERE status = 'ACTIVE'`, Boulder can inject the physical query with the exact filter *before* it gets to the federated engine. 
4.  **Replace H2 with DuckDB**:
    *   **The Fix**: H2 is an excellent, lightweight operational database, but it is not optimized for large-scale data federation. Switching the internal embedded engine to DuckDB (using its JDBC driver and PostgreSQL/MySQL scanner extensions) would provide an engine specifically designed to stream, filter, and federate massive datasets with vectorized execution, drastically reducing the memory footprint and CPU overhead of the `COALESCE` and `UNION` operations.

---

## Glossary

*   **Boulder**: The overarching JDBC proxy framework that intercepts and virtualizes database interactions.
*   **ChalkBag**: The thread-local, in-memory data store used to house all virtual inserts, updates (patches), and deletes (tombstones).
*   **DynoMerger**: The write-interception component responsible for parsing `INSERT`, `UPDATE`, and `DELETE` SQL commands and applying them exclusively to the `ChalkBag`.
*   **Federated Engine**: The embedded, in-memory database (H2) initialized within the proxy that acts as a powerful middleware SQL router.
*   **Linked Table**: A mechanism within the Federated Engine that mounts the external, physical database tables as read-only streams.
*   **Virtual Row**: A database row that exists either entirely or partially within the `ChalkBag` memory layer and is seamlessly injected into the application's read stream.
*   **Tombstone**: A `null` marker stored in the `ChalkBag` for a specific Primary Key, indicating to the proxy that the physical row has been "deleted" and should be filtered out by the View's `NOT IN` clause.
*   **Patch-to-Include (Pull-through)**: A proxy scenario where a physical row fails the application's `WHERE` clause, but a virtual `COALESCE` patch modifies the row such that the Federated Engine now includes it in the results.
*   **Patch-to-Exclude**: A proxy scenario where a physical row successfully passes the application's `WHERE` clause, but a virtual `COALESCE` patch modifies the row such that the Federated Engine now natively filters it out.