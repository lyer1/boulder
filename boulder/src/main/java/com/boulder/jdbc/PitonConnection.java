package com.boulder.jdbc;

import java.sql.*;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executor;

public class PitonConnection implements Connection {

    private final Connection delegate;
    private final Connection federatedConnection;
    private final Set<String> initializedTables = new HashSet<>();
    private final String realUrl;
    private final Properties info;

    public PitonConnection(Connection delegate, String realUrl, Properties info) throws SQLException {
        this.delegate = delegate;
        this.realUrl = realUrl;
        this.info = info;

        // Initialize the Federated Engine (H2 in-memory)
        this.federatedConnection = DriverManager.getConnection("jdbc:h2:mem:federated_engine_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
    }

    public void lazyInitAllTables() throws SQLException {
        try (Statement fedStmt = federatedConnection.createStatement()) {
            DatabaseMetaData metaData = delegate.getMetaData();
            try (ResultSet tables = metaData.getTables(null, null, "%", new String[] {"TABLE"})) {
                while (tables.next()) {
                    String tableName = tables.getString("TABLE_NAME").toLowerCase();
                    // We only want to map user tables, might have to filter
                    if (tableName.startsWith("system_") || tableName.startsWith("trace_") || tables.getString("TABLE_SCHEM").equalsIgnoreCase("INFORMATION_SCHEMA")) {
                        continue;
                    }

                    lazyInitTable(tableName);
                }
            }
        }
    }

    public void lazyInitTable(String rawTableName) throws SQLException {
        String tableName = rawTableName.toLowerCase();
        if (initializedTables.contains(tableName)) {
            return;
        }
        initializedTables.add(tableName);

        try (Statement fedStmt = federatedConnection.createStatement()) {
            String user = info != null && info.getProperty("user") != null ? info.getProperty("user") : "sa";
            String password = info != null && info.getProperty("password") != null ? info.getProperty("password") : "";

            String createLinkedTableSql = String.format(
                    "CREATE LINKED TABLE IF NOT EXISTS phys_%s ('', '%s', '%s', '%s', '(%s)')",
                    tableName, realUrl, user, password, "SELECT * FROM " + tableName
            );

            try {
                fedStmt.execute(createLinkedTableSql);
            } catch (SQLException e) {
                try {
                    String createLinkedTableSqlFallback = String.format(
                            "CREATE LINKED TABLE IF NOT EXISTS phys_%s ('', '%s', '%s', '%s', '%s')",
                            tableName, realUrl, user, password, tableName
                    );
                    fedStmt.execute(createLinkedTableSqlFallback);
                } catch (SQLException e2) {
                     e2.printStackTrace();
                }
            }

            // Create virt tables
            try { fedStmt.execute("CREATE LOCAL TEMPORARY TABLE virt_ins_" + tableName + " AS SELECT * FROM phys_" + tableName + " WHERE 1=0"); } catch (Exception e) {}
            try { fedStmt.execute("CREATE LOCAL TEMPORARY TABLE virt_upd_" + tableName + " AS SELECT * FROM phys_" + tableName + " WHERE 1=0"); } catch (Exception e) {}
            try { fedStmt.execute("CREATE LOCAL TEMPORARY TABLE virt_del_" + tableName + " (id VARCHAR(255))"); } catch (Exception e) {}

            // Create Views
            DatabaseMetaData metaData = delegate.getMetaData();
            String pkColumnName = "id";
            try (ResultSet rs = metaData.getPrimaryKeys(null, null, tableName.toUpperCase())) {
                if (rs.next()) {
                    pkColumnName = rs.getString("COLUMN_NAME").toLowerCase();
                }
            } catch (Exception e) {}

            StringBuilder selectCols = new StringBuilder();
            StringBuilder selectInsCols = new StringBuilder();
            try (ResultSet cols = metaData.getColumns(null, null, tableName.toUpperCase(), "%")) {
                boolean first = true;
                while (cols.next()) {
                    String colName = cols.getString("COLUMN_NAME").toLowerCase();
                    if (!first) {
                        selectCols.append(", ");
                        selectInsCols.append(", ");
                    }
                    first = false;

                    if (colName.equals(pkColumnName)) {
                        selectCols.append("p.").append(colName);
                        selectInsCols.append(colName);
                    } else {
                        selectCols.append("COALESCE(u.").append(colName).append(", p.").append(colName).append(") AS ").append(colName);
                        selectInsCols.append(colName);
                    }
                }
            }

            if (selectCols.length() == 0) {
                selectCols.append("p.*");
                selectInsCols.append("*");
            }

            String viewSql1 = "CREATE OR REPLACE VIEW " + tableName + " AS " +
                             "SELECT " + selectCols.toString() + " FROM phys_" + tableName + " p " +
                             "LEFT JOIN virt_upd_" + tableName + " u ON p." + pkColumnName + " = u." + pkColumnName + " " +
                             "WHERE p." + pkColumnName + " NOT IN (SELECT id FROM virt_del_" + tableName + ") " +
                             "UNION ALL " +
                             "SELECT " + selectInsCols.toString() + " FROM virt_ins_" + tableName;

            String viewSql2 = "CREATE OR REPLACE VIEW " + tableName + " AS " +
                             "SELECT " + selectCols.toString() + " FROM phys_" + tableName + " p " +
                             "LEFT JOIN virt_upd_" + tableName + " u ON p." + pkColumnName + " = u." + pkColumnName + " " +
                             "WHERE CAST(p." + pkColumnName + " AS VARCHAR(255)) NOT IN (SELECT CAST(id AS VARCHAR(255)) FROM virt_del_" + tableName + ") " +
                             "UNION ALL " +
                             "SELECT " + selectInsCols.toString() + " FROM virt_ins_" + tableName;

            String viewSql3 = "CREATE OR REPLACE VIEW " + tableName + " AS " +
                             "SELECT " + selectCols.toString() + " FROM phys_" + tableName + " p " +
                             "LEFT JOIN virt_upd_" + tableName + " u ON p." + pkColumnName + " = u." + pkColumnName + " " +
                             "WHERE CAST(p." + pkColumnName + " AS VARCHAR(255)) NOT IN (SELECT CAST(id AS VARCHAR(255)) FROM virt_del_" + tableName + ")";

            String viewSql4 = "CREATE OR REPLACE VIEW " + tableName + " AS SELECT * FROM phys_" + tableName;

            try {
                fedStmt.execute(viewSql1);
            } catch (Exception e1) {
                try {
                    fedStmt.execute(viewSql2);
                } catch (Exception e2) {
                    try {
                        fedStmt.execute(viewSql3);
                    } catch (Exception e3) {
                        try {
                            fedStmt.execute(viewSql4);
                        } catch (Exception e4) {
                        }
                    }
                }
            }
        }
    }

    public Connection getFederatedConnection() {
        return federatedConnection;
    }

    public Connection getPhysicalConnection() {
        return delegate;
    }

    public void syncChalkBagToFederatedEngine() throws SQLException {
        com.boulder.state.ChalkBag bag = com.boulder.state.ChalkBag.get();
        Map<String, Map<String, Map<String, Object>>> state = bag.getAllState();

        if (state.isEmpty()) {
            return;
        }

        try (Statement fedStmt = federatedConnection.createStatement()) {
            DatabaseMetaData metaData = delegate.getMetaData();

            for (String tableName : state.keySet()) {
                lazyInitTable(tableName);

                try { fedStmt.execute("TRUNCATE TABLE virt_ins_" + tableName); } catch (Exception e2) {}
                try { fedStmt.execute("TRUNCATE TABLE virt_upd_" + tableName); } catch (Exception e2) {}
                try { fedStmt.execute("TRUNCATE TABLE virt_del_" + tableName); } catch (Exception e2) {}

                Map<String, Map<String, Object>> tableState = state.get(tableName);
                if (tableState == null) continue;

                String pkColumnName = "id";
                try (ResultSet rs = metaData.getPrimaryKeys(null, null, tableName.toUpperCase())) {
                    if (rs.next()) {
                        pkColumnName = rs.getString("COLUMN_NAME").toLowerCase();
                    }
                } catch (Exception e) {}

                // Find all PKs in the physical database to avoid N+1 queries
                java.util.Set<String> existingPks = new java.util.HashSet<>();
                try (PreparedStatement ps = delegate.prepareStatement("SELECT " + pkColumnName + " FROM " + tableName);
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        existingPks.add(rs.getString(1));
                    }
                } catch (Exception e) {

                }

                for (Map.Entry<String, Map<String, Object>> entry : tableState.entrySet()) {
                    String pk = entry.getKey();
                    Map<String, Object> values = entry.getValue();

                    if (values == null) {
                        try (PreparedStatement ps = federatedConnection.prepareStatement("INSERT INTO virt_del_" + tableName + " (id) VALUES (?)")) {
                            ps.setObject(1, pk);
                            ps.executeUpdate();
                        } catch (SQLException e) {

                        }
                    } else {
                        boolean existsInPhys = existingPks.contains(pk);

                        String tempTable = existsInPhys ? "virt_upd_" : "virt_ins_";

                        StringBuilder cols = new StringBuilder(pkColumnName);
                        StringBuilder vals = new StringBuilder("?");
                        List<Object> paramValues = new java.util.ArrayList<>();
                        paramValues.add(pk);

                        for (Map.Entry<String, Object> valEntry : values.entrySet()) {
                            String colName = valEntry.getKey();
                            if (!colName.equalsIgnoreCase(pkColumnName)) {
                                cols.append(", ").append(colName);
                                vals.append(", ?");
                                paramValues.add(valEntry.getValue());
                            }
                        }

                        String sql = "INSERT INTO " + tempTable + tableName + " (" + cols.toString() + ") VALUES (" + vals.toString() + ")";
                        try (PreparedStatement ps = federatedConnection.prepareStatement(sql)) {
                            for (int i = 0; i < paramValues.size(); i++) {
                                ps.setObject(i + 1, paramValues.get(i));
                            }
                            ps.executeUpdate();
                        } catch (SQLException e) {

                        }
                    }
                }
            }
        }
    }

    private boolean isProxyOperation(String sql) {
        if (sql == null) return false;
        String cleanSql = sql.replaceAll("(?s)/\\*.*?\\*/", "").trim().toLowerCase();
        return cleanSql.startsWith("select next value for") || cleanSql.startsWith("call next value for");
    }

    @Override
    public Statement createStatement() throws SQLException {
        return new PitonStatement(delegate.createStatement(), this);
    }

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        if (isProxyOperation(sql)) return new PitonPreparedStatement(null, sql, this);
        return new PitonPreparedStatement(delegate.prepareStatement(sql), sql, this);
    }

    @Override
    public CallableStatement prepareCall(String sql) throws SQLException {
        return delegate.prepareCall(sql);
    }

    @Override
    public String nativeSQL(String sql) throws SQLException {
        return delegate.nativeSQL(sql);
    }

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        delegate.setAutoCommit(autoCommit);
    }

    @Override
    public boolean getAutoCommit() throws SQLException {
        return delegate.getAutoCommit();
    }

    @Override
    public void commit() throws SQLException {
        delegate.commit();
    }

    @Override
    public void rollback() throws SQLException {
        delegate.rollback();
    }

    @Override
    public void close() throws SQLException {
        if (federatedConnection != null && !federatedConnection.isClosed()) {
            federatedConnection.close();
        }
        delegate.close();
    }

    @Override
    public boolean isClosed() throws SQLException {
        return delegate.isClosed();
    }

    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
        return delegate.getMetaData();
    }

    @Override
    public void setReadOnly(boolean readOnly) throws SQLException {
        delegate.setReadOnly(readOnly);
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        return delegate.isReadOnly();
    }

    @Override
    public void setCatalog(String catalog) throws SQLException {
        delegate.setCatalog(catalog);
    }

    @Override
    public String getCatalog() throws SQLException {
        return delegate.getCatalog();
    }

    @Override
    public void setTransactionIsolation(int level) throws SQLException {
        delegate.setTransactionIsolation(level);
    }

    @Override
    public int getTransactionIsolation() throws SQLException {
        return delegate.getTransactionIsolation();
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        return delegate.getWarnings();
    }

    @Override
    public void clearWarnings() throws SQLException {
        delegate.clearWarnings();
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
        return new PitonStatement(delegate.createStatement(resultSetType, resultSetConcurrency), this);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        if (isProxyOperation(sql)) return new PitonPreparedStatement(null, sql, this);
        return new PitonPreparedStatement(delegate.prepareStatement(sql, resultSetType, resultSetConcurrency), sql, this);
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        return delegate.prepareCall(sql, resultSetType, resultSetConcurrency);
    }

    @Override
    public Map<String, Class<?>> getTypeMap() throws SQLException {
        return delegate.getTypeMap();
    }

    @Override
    public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
        delegate.setTypeMap(map);
    }

    @Override
    public void setHoldability(int holdability) throws SQLException {
        delegate.setHoldability(holdability);
    }

    @Override
    public int getHoldability() throws SQLException {
        return delegate.getHoldability();
    }

    @Override
    public Savepoint setSavepoint() throws SQLException {
        return delegate.setSavepoint();
    }

    @Override
    public Savepoint setSavepoint(String name) throws SQLException {
        return delegate.setSavepoint(name);
    }

    @Override
    public void rollback(Savepoint savepoint) throws SQLException {
        delegate.rollback(savepoint);
    }

    @Override
    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        delegate.releaseSavepoint(savepoint);
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        return new PitonStatement(delegate.createStatement(resultSetType, resultSetConcurrency, resultSetHoldability), this);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        if (isProxyOperation(sql)) return new PitonPreparedStatement(null, sql, this);
        return new PitonPreparedStatement(delegate.prepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability), sql, this);
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        return delegate.prepareCall(sql, resultSetType, resultSetConcurrency, resultSetHoldability);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
        if (isProxyOperation(sql)) return new PitonPreparedStatement(null, sql, this);
        return new PitonPreparedStatement(delegate.prepareStatement(sql, autoGeneratedKeys), sql, this);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
        if (isProxyOperation(sql)) return new PitonPreparedStatement(null, sql, this);
        return new PitonPreparedStatement(delegate.prepareStatement(sql, columnIndexes), sql, this);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
        if (isProxyOperation(sql)) return new PitonPreparedStatement(null, sql, this);
        return new PitonPreparedStatement(delegate.prepareStatement(sql, columnNames), sql, this);
    }

    @Override
    public Clob createClob() throws SQLException {
        return delegate.createClob();
    }

    @Override
    public Blob createBlob() throws SQLException {
        return delegate.createBlob();
    }

    @Override
    public NClob createNClob() throws SQLException {
        return delegate.createNClob();
    }

    @Override
    public SQLXML createSQLXML() throws SQLException {
        return delegate.createSQLXML();
    }

    @Override
    public boolean isValid(int timeout) throws SQLException {
        return delegate.isValid(timeout);
    }

    @Override
    public void setClientInfo(String name, String value) throws SQLClientInfoException {
        delegate.setClientInfo(name, value);
    }

    @Override
    public void setClientInfo(Properties properties) throws SQLClientInfoException {
        delegate.setClientInfo(properties);
    }

    @Override
    public String getClientInfo(String name) throws SQLException {
        return delegate.getClientInfo(name);
    }

    @Override
    public Properties getClientInfo() throws SQLException {
        return delegate.getClientInfo();
    }

    @Override
    public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
        return delegate.createArrayOf(typeName, elements);
    }

    @Override
    public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
        return delegate.createStruct(typeName, attributes);
    }

    @Override
    public void setSchema(String schema) throws SQLException {
        delegate.setSchema(schema);
    }

    @Override
    public String getSchema() throws SQLException {
        return delegate.getSchema();
    }

    @Override
    public void abort(Executor executor) throws SQLException {
        delegate.abort(executor);
    }

    @Override
    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
        delegate.setNetworkTimeout(executor, milliseconds);
    }

    @Override
    public int getNetworkTimeout() throws SQLException {
        return delegate.getNetworkTimeout();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return (T) this;
        }
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || delegate.isWrapperFor(iface);
    }
}
