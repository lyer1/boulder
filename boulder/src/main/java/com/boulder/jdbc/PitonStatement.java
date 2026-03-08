package com.boulder.jdbc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;

public class PitonStatement implements Statement {

    protected final Statement delegate;
    protected final PitonConnection pitonConnection;
    protected ResultSet generatedKeysResultSet = null;

    public PitonStatement(Statement delegate, PitonConnection pitonConnection) {
        this.delegate = delegate;
        this.pitonConnection = pitonConnection;
    }

    private boolean isWriteOperation(String sql) {
        if (sql == null) return false;
        String cleanSql = sql.replaceAll("(?s)/\\*.*?\\*/", "").trim().toLowerCase();
        return cleanSql.startsWith("insert") || cleanSql.startsWith("update") || cleanSql.startsWith("delete");
    }

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        boolean needsFederation = false;
        boolean parseFailed = false;
        Set<String> queryTables = new HashSet<>();
        try {
            net.sf.jsqlparser.statement.Statement jSqlStmt = CCJSqlParserUtil.parse(sql);
            if (jSqlStmt instanceof Select) {
                TablesNamesFinder tablesNamesFinder = new TablesNamesFinder();
                List<String> tableList = tablesNamesFinder.getTableList((Select) jSqlStmt);

                if (tableList != null) {
                  for (String tableName : tableList) {
                    // clean up table name if it has quotes or schema
                    String cleanTableName = tableName.replaceAll("[\"`\\[\\]]", "").toLowerCase();
                    int dotIndex = cleanTableName.lastIndexOf('.');
                    if (dotIndex != -1) {
                        cleanTableName = cleanTableName.substring(dotIndex + 1);
                    }
                    queryTables.add(cleanTableName);
                    if (com.boulder.state.ChalkBag.get().getTable(cleanTableName) != null) {
                        needsFederation = true;
                    }
                  }
                }
            } else {
                needsFederation = true;
            }
        } catch (Exception e) {
            needsFederation = true;
            parseFailed = true;
        }

        if (!needsFederation) {
            return delegate.executeQuery(sql);
        }

        if (parseFailed) {
            pitonConnection.lazyInitAllTables();
        } else {
            for (String t : queryTables) {
                pitonConnection.lazyInitTable(t);
            }
        }
        pitonConnection.syncChalkBagToFederatedEngine();
        Statement fedStmt = pitonConnection.getFederatedConnection().createStatement();
        ResultSet rs = fedStmt.executeQuery(sql);
        return new PitonResultSet(rs, sql); // Kept PitonResultSet wrapper just in case
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
        if (isWriteOperation(sql)) {
            com.boulder.merger.DynoMerger.WriteResult result = com.boulder.merger.DynoMerger.interceptWriteWithKeys(sql, java.util.Collections.emptyMap(), delegate.getConnection());
            if (result.generatedKeys != null && !result.generatedKeys.isEmpty()) {
                this.generatedKeysResultSet = new GeneratedKeysResultSet(result.generatedKeys);
            }
            return result.affectedRows;
        }
        return delegate.executeUpdate(sql);
    }

    @Override
    public void close() throws SQLException {
        delegate.close();
    }

    @Override
    public int getMaxFieldSize() throws SQLException {
        return delegate.getMaxFieldSize();
    }

    @Override
    public void setMaxFieldSize(int max) throws SQLException {
        delegate.setMaxFieldSize(max);
    }

    @Override
    public int getMaxRows() throws SQLException {
        return delegate.getMaxRows();
    }

    @Override
    public void setMaxRows(int max) throws SQLException {
        delegate.setMaxRows(max);
    }

    @Override
    public void setEscapeProcessing(boolean enable) throws SQLException {
        delegate.setEscapeProcessing(enable);
    }

    @Override
    public int getQueryTimeout() throws SQLException {
        return delegate.getQueryTimeout();
    }

    @Override
    public void setQueryTimeout(int seconds) throws SQLException {
        delegate.setQueryTimeout(seconds);
    }

    @Override
    public void cancel() throws SQLException {
        delegate.cancel();
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
    public void setCursorName(String name) throws SQLException {
        delegate.setCursorName(name);
    }

    @Override
    public boolean execute(String sql) throws SQLException {
        if (isWriteOperation(sql)) {
            com.boulder.merger.DynoMerger.WriteResult result = com.boulder.merger.DynoMerger.interceptWriteWithKeys(sql, java.util.Collections.emptyMap(), delegate.getConnection());
            if (result.generatedKeys != null && !result.generatedKeys.isEmpty()) {
                this.generatedKeysResultSet = new GeneratedKeysResultSet(result.generatedKeys);
            }
            return false;
        }
        return delegate.execute(sql);
    }

    @Override
    public ResultSet getResultSet() throws SQLException {
        return delegate.getResultSet();
    }

    @Override
    public int getUpdateCount() throws SQLException {
        return delegate.getUpdateCount();
    }

    @Override
    public boolean getMoreResults() throws SQLException {
        return delegate.getMoreResults();
    }

    @Override
    public void setFetchDirection(int direction) throws SQLException {
        delegate.setFetchDirection(direction);
    }

    @Override
    public int getFetchDirection() throws SQLException {
        return delegate.getFetchDirection();
    }

    @Override
    public void setFetchSize(int rows) throws SQLException {
        delegate.setFetchSize(rows);
    }

    @Override
    public int getFetchSize() throws SQLException {
        return delegate.getFetchSize();
    }

    @Override
    public int getResultSetConcurrency() throws SQLException {
        return delegate.getResultSetConcurrency();
    }

    @Override
    public int getResultSetType() throws SQLException {
        return delegate.getResultSetType();
    }

    @Override
    public void addBatch(String sql) throws SQLException {
        if (isWriteOperation(sql)) {
             com.boulder.merger.DynoMerger.interceptWrite(sql, java.util.Collections.emptyMap(), delegate.getConnection());
        } else {
            delegate.addBatch(sql);
        }
    }

    @Override
    public void clearBatch() throws SQLException {
        delegate.clearBatch();
    }

    @Override
    public int[] executeBatch() throws SQLException {
        return delegate.executeBatch();
    }

    @Override
    public Connection getConnection() throws SQLException {
        return delegate.getConnection();
    }

    @Override
    public boolean getMoreResults(int current) throws SQLException {
        return delegate.getMoreResults(current);
    }

    @Override
    public ResultSet getGeneratedKeys() throws SQLException {
        if (generatedKeysResultSet != null) {
            if (generatedKeysResultSet instanceof GeneratedKeysResultSet) {
                return new GeneratedKeysResultSet(((GeneratedKeysResultSet)generatedKeysResultSet).getGeneratedKeysList());
            }
            return generatedKeysResultSet;
        }
        return new GeneratedKeysResultSet(new ArrayList<>());
    }

    @Override
    public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        if (isWriteOperation(sql)) {
            com.boulder.merger.DynoMerger.WriteResult result = com.boulder.merger.DynoMerger.interceptWriteWithKeys(sql, java.util.Collections.emptyMap(), delegate.getConnection());
            if (result.generatedKeys != null && !result.generatedKeys.isEmpty()) {
                this.generatedKeysResultSet = new GeneratedKeysResultSet(result.generatedKeys);
            }
            return result.affectedRows;
        }
        return delegate.executeUpdate(sql, autoGeneratedKeys);
    }

    @Override
    public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
        if (isWriteOperation(sql)) {
            com.boulder.merger.DynoMerger.WriteResult result = com.boulder.merger.DynoMerger.interceptWriteWithKeys(sql, java.util.Collections.emptyMap(), delegate.getConnection());
            if (result.generatedKeys != null && !result.generatedKeys.isEmpty()) {
                this.generatedKeysResultSet = new GeneratedKeysResultSet(result.generatedKeys);
            }
            return result.affectedRows;
        }
        return delegate.executeUpdate(sql, columnIndexes);
    }

    @Override
    public int executeUpdate(String sql, String[] columnNames) throws SQLException {
        if (isWriteOperation(sql)) {
            com.boulder.merger.DynoMerger.WriteResult result = com.boulder.merger.DynoMerger.interceptWriteWithKeys(sql, java.util.Collections.emptyMap(), delegate.getConnection());
            if (result.generatedKeys != null && !result.generatedKeys.isEmpty()) {
                this.generatedKeysResultSet = new GeneratedKeysResultSet(result.generatedKeys);
            }
            return result.affectedRows;
        }
        return delegate.executeUpdate(sql, columnNames);
    }

    @Override
    public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
        if (isWriteOperation(sql)) {
            com.boulder.merger.DynoMerger.WriteResult result = com.boulder.merger.DynoMerger.interceptWriteWithKeys(sql, java.util.Collections.emptyMap(), delegate.getConnection());
            if (result.generatedKeys != null && !result.generatedKeys.isEmpty()) {
                this.generatedKeysResultSet = new GeneratedKeysResultSet(result.generatedKeys);
            }
            return false;
        }
        return delegate.execute(sql, autoGeneratedKeys);
    }

    @Override
    public boolean execute(String sql, int[] columnIndexes) throws SQLException {
        if (isWriteOperation(sql)) {
            com.boulder.merger.DynoMerger.WriteResult result = com.boulder.merger.DynoMerger.interceptWriteWithKeys(sql, java.util.Collections.emptyMap(), delegate.getConnection());
            if (result.generatedKeys != null && !result.generatedKeys.isEmpty()) {
                this.generatedKeysResultSet = new GeneratedKeysResultSet(result.generatedKeys);
            }
            return false;
        }
        return delegate.execute(sql, columnIndexes);
    }

    @Override
    public boolean execute(String sql, String[] columnNames) throws SQLException {
        if (isWriteOperation(sql)) {
            com.boulder.merger.DynoMerger.WriteResult result = com.boulder.merger.DynoMerger.interceptWriteWithKeys(sql, java.util.Collections.emptyMap(), delegate.getConnection());
            if (result.generatedKeys != null && !result.generatedKeys.isEmpty()) {
                this.generatedKeysResultSet = new GeneratedKeysResultSet(result.generatedKeys);
            }
            return false;
        }
        return delegate.execute(sql, columnNames);
    }

    @Override
    public int getResultSetHoldability() throws SQLException {
        return delegate.getResultSetHoldability();
    }

    @Override
    public boolean isClosed() throws SQLException {
        return delegate.isClosed();
    }

    @Override
    public void setPoolable(boolean poolable) throws SQLException {
        delegate.setPoolable(poolable);
    }

    @Override
    public boolean isPoolable() throws SQLException {
        return delegate.isPoolable();
    }

    @Override
    public void closeOnCompletion() throws SQLException {
        delegate.closeOnCompletion();
    }

    @Override
    public boolean isCloseOnCompletion() throws SQLException {
        return delegate.isCloseOnCompletion();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return delegate.isWrapperFor(iface);
    }
}
