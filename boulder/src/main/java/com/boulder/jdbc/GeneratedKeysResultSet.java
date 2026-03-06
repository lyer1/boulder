package com.boulder.jdbc;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;

public class GeneratedKeysResultSet extends PitonResultSetDecorator {

    private final List<Integer> generatedKeys;
    private int currentIndex = -1;

    public GeneratedKeysResultSet(List<Integer> generatedKeys) {
        super(null); // Delegate is null, we handle methods ourselves
        this.generatedKeys = generatedKeys;
    }

    public List<Integer> getGeneratedKeysList() {
        return this.generatedKeys;
    }

    @Override
    public boolean next() throws SQLException {
        currentIndex++;
        return currentIndex < generatedKeys.size();
    }

    @Override
    public void beforeFirst() throws SQLException {
        currentIndex = -1;
    }

    @Override
    public boolean wasNull() throws SQLException {
        return false;
    }

    @Override
    public Object getObject(int columnIndex) throws SQLException {
        if (columnIndex == 1) {
            return generatedKeys.get(currentIndex);
        }
        throw new SQLException("Invalid column index");
    }

    @Override
    public Object getObject(String columnLabel) throws SQLException {
        return generatedKeys.get(currentIndex);
    }

    @Override
    public int getInt(int columnIndex) throws SQLException {
        if (columnIndex == 1) {
            return generatedKeys.get(currentIndex);
        }
        throw new SQLException("Invalid column index");
    }

    @Override
    public int getInt(String columnLabel) throws SQLException {
        return generatedKeys.get(currentIndex);
    }

    @Override
    public long getLong(int columnIndex) throws SQLException {
        if (columnIndex == 1) {
            return generatedKeys.get(currentIndex);
        }
        throw new SQLException("Invalid column index");
    }

    @Override
    public long getLong(String columnLabel) throws SQLException {
        return generatedKeys.get(currentIndex);
    }

    @Override
    public void close() throws SQLException {
        // Nothing to close
    }

    // Dummy metadata implementation just enough for Hibernate
    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        return new DummyResultSetMetaData();
    }

    private static class DummyResultSetMetaData implements ResultSetMetaData {
        @Override public int getColumnCount() throws SQLException { return 1; }
        @Override public boolean isAutoIncrement(int column) throws SQLException { return true; }
        @Override public boolean isCaseSensitive(int column) throws SQLException { return false; }
        @Override public boolean isSearchable(int column) throws SQLException { return false; }
        @Override public boolean isCurrency(int column) throws SQLException { return false; }
        @Override public int isNullable(int column) throws SQLException { return ResultSetMetaData.columnNoNulls; }
        @Override public boolean isSigned(int column) throws SQLException { return true; }
        @Override public int getColumnDisplaySize(int column) throws SQLException { return 10; }
        @Override public String getColumnLabel(int column) throws SQLException { return "GENERATED_KEY"; }
        @Override public String getColumnName(int column) throws SQLException { return "GENERATED_KEY"; }
        @Override public String getSchemaName(int column) throws SQLException { return ""; }
        @Override public int getPrecision(int column) throws SQLException { return 10; }
        @Override public int getScale(int column) throws SQLException { return 0; }
        @Override public String getTableName(int column) throws SQLException { return ""; }
        @Override public String getCatalogName(int column) throws SQLException { return ""; }
        @Override public int getColumnType(int column) throws SQLException { return java.sql.Types.INTEGER; }
        @Override public String getColumnTypeName(int column) throws SQLException { return "INTEGER"; }
        @Override public boolean isReadOnly(int column) throws SQLException { return true; }
        @Override public boolean isWritable(int column) throws SQLException { return false; }
        @Override public boolean isDefinitelyWritable(int column) throws SQLException { return false; }
        @Override public String getColumnClassName(int column) throws SQLException { return "java.lang.Integer"; }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) throws SQLException { return false; }
    }
}
