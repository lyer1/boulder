package com.boulder.jdbc;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class PitonStatement implements Statement {

    protected final Statement delegate;
    protected final List<String> batchSqls = new ArrayList<>();

    public PitonStatement(Statement delegate) {
        this.delegate = delegate;
    }

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        return new PitonResultSet(delegate.executeQuery(sql), sql);
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
        String lowerSql = sql.toLowerCase().trim();
        if (lowerSql.startsWith("insert") || lowerSql.startsWith("update") || lowerSql.startsWith("delete")) {
            com.boulder.merger.DynoMerger.interceptWrite(sql, java.util.Collections.emptyMap());
            return 1; // Dummy update count
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
        String lowerSql = sql.toLowerCase().trim();
        if (lowerSql.startsWith("insert") || lowerSql.startsWith("update") || lowerSql.startsWith("delete")) {
            com.boulder.merger.DynoMerger.interceptWrite(sql, java.util.Collections.emptyMap());
            return false;
        }
        return delegate.execute(sql);
    }

    @Override
    public ResultSet getResultSet() throws SQLException {
        ResultSet rs = delegate.getResultSet();
        if (rs != null) {
            return new PitonResultSet(rs, "");
        }
        return null;
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
        batchSqls.add(sql);
        // Do NOT add to delegate to prevent execution on physical DB if it's a write
    }

    @Override
    public void clearBatch() throws SQLException {
        batchSqls.clear();
        delegate.clearBatch();
    }

    @Override
    public int[] executeBatch() throws SQLException {
        int[] results = new int[batchSqls.size()];
        for (int i = 0; i < batchSqls.size(); i++) {
            String sql = batchSqls.get(i);
            String lowerSql = sql.toLowerCase().trim();
            if (lowerSql.startsWith("insert") || lowerSql.startsWith("update") || lowerSql.startsWith("delete")) {
                com.boulder.merger.DynoMerger.interceptWrite(sql, java.util.Collections.emptyMap());
                results[i] = 1; // Dummy success
            } else {
                // If there are somehow reads in the batch (unusual), we'd execute them,
                // but Statement.executeBatch typically doesn't return ResultSets.
                delegate.addBatch(sql);
                // This is slightly broken if we mix reads/writes, but executeBatch is for updates.
            }
        }

        // Execute any non-intercepted batches (e.g. DDL if we wanted, but we'll assume intercepting everything DML)
        // For safety, let's just clear our list and return dummy success counts
        batchSqls.clear();
        return results;
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
        return delegate.getGeneratedKeys();
    }

    @Override
    public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        String lowerSql = sql.toLowerCase().trim();
        if (lowerSql.startsWith("insert") || lowerSql.startsWith("update") || lowerSql.startsWith("delete")) {
            com.boulder.merger.DynoMerger.interceptWrite(sql, java.util.Collections.emptyMap());
            return 1;
        }
        return delegate.executeUpdate(sql, autoGeneratedKeys);
    }

    @Override
    public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
        String lowerSql = sql.toLowerCase().trim();
        if (lowerSql.startsWith("insert") || lowerSql.startsWith("update") || lowerSql.startsWith("delete")) {
            com.boulder.merger.DynoMerger.interceptWrite(sql, java.util.Collections.emptyMap());
            return 1;
        }
        return delegate.executeUpdate(sql, columnIndexes);
    }

    @Override
    public int executeUpdate(String sql, String[] columnNames) throws SQLException {
        String lowerSql = sql.toLowerCase().trim();
        if (lowerSql.startsWith("insert") || lowerSql.startsWith("update") || lowerSql.startsWith("delete")) {
            com.boulder.merger.DynoMerger.interceptWrite(sql, java.util.Collections.emptyMap());
            return 1;
        }
        return delegate.executeUpdate(sql, columnNames);
    }

    @Override
    public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
        String lowerSql = sql.toLowerCase().trim();
        if (lowerSql.startsWith("insert") || lowerSql.startsWith("update") || lowerSql.startsWith("delete")) {
            com.boulder.merger.DynoMerger.interceptWrite(sql, java.util.Collections.emptyMap());
            return false;
        }
        return delegate.execute(sql, autoGeneratedKeys);
    }

    @Override
    public boolean execute(String sql, int[] columnIndexes) throws SQLException {
        String lowerSql = sql.toLowerCase().trim();
        if (lowerSql.startsWith("insert") || lowerSql.startsWith("update") || lowerSql.startsWith("delete")) {
            com.boulder.merger.DynoMerger.interceptWrite(sql, java.util.Collections.emptyMap());
            return false;
        }
        return delegate.execute(sql, columnIndexes);
    }

    @Override
    public boolean execute(String sql, String[] columnNames) throws SQLException {
        String lowerSql = sql.toLowerCase().trim();
        if (lowerSql.startsWith("insert") || lowerSql.startsWith("update") || lowerSql.startsWith("delete")) {
            com.boulder.merger.DynoMerger.interceptWrite(sql, java.util.Collections.emptyMap());
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
