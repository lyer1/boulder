package com.boulder.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.boulder.merger.DynoMerger;

public class PitonPreparedStatement extends PitonStatement implements PreparedStatement {

    private final PreparedStatement delegatePreparedStatement;
    private final String sql;
    private final Connection connection;
    private final Map<Integer, Object> parameters = new HashMap<>();
    private final List<Map<Integer, Object>> batchParameters = new ArrayList<>();

    private ResultSet generatedKeysResultSet = null;

    public PitonPreparedStatement(PreparedStatement delegate, String sql, Connection connection) {
        super(delegate);
        this.delegatePreparedStatement = delegate;
        this.sql = sql;
        this.connection = connection;
    }

    private boolean isWriteOperation(String sql) {
        if (sql == null) return false;
        String cleanSql = sql.replaceAll("(?s)/\\*.*?\\*/", "").trim().toLowerCase();
        return cleanSql.startsWith("insert") || cleanSql.startsWith("update") || cleanSql.startsWith("delete");
    }

    @Override
    public ResultSet executeQuery() throws SQLException {
        String cleanSql = sql.replaceAll("(?s)/\\*.*?\\*/", "").trim().toLowerCase();
        if (cleanSql.startsWith("select next value for") || cleanSql.startsWith("call next value for")) {
             List<Integer> keys = new ArrayList<>();
             keys.add(com.boulder.state.ChalkBag.get().generateId());
             return new GeneratedKeysResultSet(keys);
        }

        if (delegatePreparedStatement == null) throw new SQLException("Proxy-only statement cannot execute physical query: " + sql);
        ResultSet rs = delegatePreparedStatement.executeQuery();
        return new PitonResultSet(rs, sql, new HashMap<>(parameters));
    }

    @Override
    public int executeUpdate() throws SQLException {
        if (isWriteOperation(sql)) {
            DynoMerger.WriteResult result = DynoMerger.interceptWriteWithKeys(sql, parameters, connection);
            if (result.generatedKeys != null && !result.generatedKeys.isEmpty()) {
                 this.generatedKeysResultSet = new GeneratedKeysResultSet(result.generatedKeys);
            }
            return result.affectedRows;
        }
        if (delegatePreparedStatement == null) return 0;
        return delegatePreparedStatement.executeUpdate();
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
    public void setNull(int parameterIndex, int sqlType) throws SQLException {
        parameters.put(parameterIndex, null);
        if (delegatePreparedStatement != null) delegatePreparedStatement.setNull(parameterIndex, sqlType);
    }

    @Override
    public void setBoolean(int parameterIndex, boolean x) throws SQLException {
        parameters.put(parameterIndex, x);
        if (delegatePreparedStatement != null) delegatePreparedStatement.setBoolean(parameterIndex, x);
    }

    @Override
    public void setByte(int parameterIndex, byte x) throws SQLException {
        parameters.put(parameterIndex, x);
        if (delegatePreparedStatement != null) delegatePreparedStatement.setByte(parameterIndex, x);
    }

    @Override
    public void setShort(int parameterIndex, short x) throws SQLException {
        parameters.put(parameterIndex, x);
        if (delegatePreparedStatement != null) delegatePreparedStatement.setShort(parameterIndex, x);
    }

    @Override
    public void setInt(int parameterIndex, int x) throws SQLException {
        parameters.put(parameterIndex, x);
        if (delegatePreparedStatement != null) delegatePreparedStatement.setInt(parameterIndex, x);
    }

    @Override
    public void setLong(int parameterIndex, long x) throws SQLException {
        parameters.put(parameterIndex, x);
        if (delegatePreparedStatement != null) delegatePreparedStatement.setLong(parameterIndex, x);
    }

    @Override
    public void setFloat(int parameterIndex, float x) throws SQLException {
        parameters.put(parameterIndex, x);
        if (delegatePreparedStatement != null) delegatePreparedStatement.setFloat(parameterIndex, x);
    }

    @Override
    public void setDouble(int parameterIndex, double x) throws SQLException {
        parameters.put(parameterIndex, x);
        if (delegatePreparedStatement != null) delegatePreparedStatement.setDouble(parameterIndex, x);
    }

    @Override
    public void setBigDecimal(int parameterIndex, java.math.BigDecimal x) throws SQLException {
        parameters.put(parameterIndex, x);
        if (delegatePreparedStatement != null) delegatePreparedStatement.setBigDecimal(parameterIndex, x);
    }

    @Override
    public void setString(int parameterIndex, String x) throws SQLException {
        parameters.put(parameterIndex, x);
        if (delegatePreparedStatement != null) delegatePreparedStatement.setString(parameterIndex, x);
    }

    @Override
    public void setBytes(int parameterIndex, byte[] x) throws SQLException {
        parameters.put(parameterIndex, x);
        if (delegatePreparedStatement != null) delegatePreparedStatement.setBytes(parameterIndex, x);
    }

    @Override
    public void setDate(int parameterIndex, java.sql.Date x) throws SQLException {
        parameters.put(parameterIndex, x);
        if (delegatePreparedStatement != null) delegatePreparedStatement.setDate(parameterIndex, x);
    }

    @Override
    public void setTime(int parameterIndex, java.sql.Time x) throws SQLException {
        parameters.put(parameterIndex, x);
        if (delegatePreparedStatement != null) delegatePreparedStatement.setTime(parameterIndex, x);
    }

    @Override
    public void setTimestamp(int parameterIndex, java.sql.Timestamp x) throws SQLException {
        parameters.put(parameterIndex, x);
        if (delegatePreparedStatement != null) delegatePreparedStatement.setTimestamp(parameterIndex, x);
    }

    @Override
    public void setAsciiStream(int parameterIndex, java.io.InputStream x, int length) throws SQLException {
        if (delegatePreparedStatement != null) delegatePreparedStatement.setAsciiStream(parameterIndex, x, length);
    }

    @Override
    public void setUnicodeStream(int parameterIndex, java.io.InputStream x, int length) throws SQLException {
        if (delegatePreparedStatement != null) delegatePreparedStatement.setUnicodeStream(parameterIndex, x, length);
    }

    @Override
    public void setBinaryStream(int parameterIndex, java.io.InputStream x, int length) throws SQLException {
        if (delegatePreparedStatement != null) delegatePreparedStatement.setBinaryStream(parameterIndex, x, length);
    }

    @Override
    public void clearParameters() throws SQLException {
        parameters.clear();
        if (delegatePreparedStatement != null) delegatePreparedStatement.clearParameters();
    }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {
        parameters.put(parameterIndex, x);
        if (delegatePreparedStatement != null) delegatePreparedStatement.setObject(parameterIndex, x, targetSqlType);
    }

    @Override
    public void setObject(int parameterIndex, Object x) throws SQLException {
        parameters.put(parameterIndex, x);
        if (delegatePreparedStatement != null) delegatePreparedStatement.setObject(parameterIndex, x);
    }

    @Override
    public boolean execute() throws SQLException {
        String cleanSql = sql.replaceAll("(?s)/\\*.*?\\*/", "").trim().toLowerCase();
        if (cleanSql.startsWith("select next value for") || cleanSql.startsWith("call next value for")) {
             List<Integer> keys = new ArrayList<>();
             keys.add(com.boulder.state.ChalkBag.get().generateId());
             this.generatedKeysResultSet = new GeneratedKeysResultSet(keys);
             return true;
        }
        if (isWriteOperation(sql)) {
            DynoMerger.WriteResult result = DynoMerger.interceptWriteWithKeys(sql, parameters, connection);
            if (result.generatedKeys != null && !result.generatedKeys.isEmpty()) {
                 this.generatedKeysResultSet = new GeneratedKeysResultSet(result.generatedKeys);
            }
            return false;
        }
        if (delegatePreparedStatement == null) return false;
        return delegatePreparedStatement.execute();
    }

    @Override
    public void addBatch() throws SQLException {
        if (isWriteOperation(sql)) {
            batchParameters.add(new HashMap<>(parameters));
        } else {
            if (delegatePreparedStatement != null) delegatePreparedStatement.addBatch();
        }
    }

    @Override
    public void clearBatch() throws SQLException {
        batchParameters.clear();
        if (delegatePreparedStatement != null) delegatePreparedStatement.clearBatch();
    }

    @Override
    public int[] executeBatch() throws SQLException {
        if (isWriteOperation(sql)) {
            int[] results = new int[batchParameters.size()];
            for (int i = 0; i < batchParameters.size(); i++) {
                DynoMerger.WriteResult result = DynoMerger.interceptWriteWithKeys(sql, batchParameters.get(i), connection);
                if (result.generatedKeys != null && !result.generatedKeys.isEmpty()) {
                     this.generatedKeysResultSet = new GeneratedKeysResultSet(result.generatedKeys);
                }
                results[i] = result.affectedRows;
            }
            batchParameters.clear();
            return results;
        }
        if (delegatePreparedStatement == null) return new int[0];
        return delegatePreparedStatement.executeBatch();
    }

    @Override
    public void setCharacterStream(int parameterIndex, java.io.Reader reader, int length) throws SQLException {
        if (delegatePreparedStatement != null) delegatePreparedStatement.setCharacterStream(parameterIndex, reader, length);
    }

    @Override
    public void setRef(int parameterIndex, java.sql.Ref x) throws SQLException {
        if (delegatePreparedStatement != null) delegatePreparedStatement.setRef(parameterIndex, x);
    }

    @Override
    public void setBlob(int parameterIndex, java.sql.Blob x) throws SQLException {
        if (delegatePreparedStatement != null) delegatePreparedStatement.setBlob(parameterIndex, x);
    }

    @Override
    public void setClob(int parameterIndex, java.sql.Clob x) throws SQLException {
        if (delegatePreparedStatement != null) delegatePreparedStatement.setClob(parameterIndex, x);
    }

    @Override
    public void setArray(int parameterIndex, java.sql.Array x) throws SQLException {
        if (delegatePreparedStatement != null) delegatePreparedStatement.setArray(parameterIndex, x);
    }

    @Override
    public java.sql.ResultSetMetaData getMetaData() throws SQLException {
        if (delegatePreparedStatement == null) return null;
        return delegatePreparedStatement.getMetaData();
    }

    @Override
    public void setDate(int parameterIndex, java.sql.Date x, java.util.Calendar cal) throws SQLException {
        parameters.put(parameterIndex, x);
        if (delegatePreparedStatement != null) delegatePreparedStatement.setDate(parameterIndex, x, cal);
    }

    @Override
    public void setTime(int parameterIndex, java.sql.Time x, java.util.Calendar cal) throws SQLException {
        parameters.put(parameterIndex, x);
        if (delegatePreparedStatement != null) delegatePreparedStatement.setTime(parameterIndex, x, cal);
    }

    @Override
    public void setTimestamp(int parameterIndex, java.sql.Timestamp x, java.util.Calendar cal) throws SQLException {
        parameters.put(parameterIndex, x);
        if (delegatePreparedStatement != null) delegatePreparedStatement.setTimestamp(parameterIndex, x, cal);
    }

    @Override
    public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException {
        parameters.put(parameterIndex, null);
        if (delegatePreparedStatement != null) delegatePreparedStatement.setNull(parameterIndex, sqlType, typeName);
    }

    @Override
    public void setURL(int parameterIndex, java.net.URL x) throws SQLException {
        parameters.put(parameterIndex, x);
        if (delegatePreparedStatement != null) delegatePreparedStatement.setURL(parameterIndex, x);
    }

    @Override
    public java.sql.ParameterMetaData getParameterMetaData() throws SQLException {
        if (delegatePreparedStatement == null) return null;
        return delegatePreparedStatement.getParameterMetaData();
    }

    @Override
    public void setRowId(int parameterIndex, java.sql.RowId x) throws SQLException {
        parameters.put(parameterIndex, x);
        if (delegatePreparedStatement != null) delegatePreparedStatement.setRowId(parameterIndex, x);
    }

    @Override
    public void setNString(int parameterIndex, String value) throws SQLException {
        parameters.put(parameterIndex, value);
        if (delegatePreparedStatement != null) delegatePreparedStatement.setNString(parameterIndex, value);
    }

    @Override
    public void setNCharacterStream(int parameterIndex, java.io.Reader value, long length) throws SQLException {
        if (delegatePreparedStatement != null) delegatePreparedStatement.setNCharacterStream(parameterIndex, value, length);
    }

    @Override
    public void setNClob(int parameterIndex, java.sql.NClob value) throws SQLException {
        if (delegatePreparedStatement != null) delegatePreparedStatement.setNClob(parameterIndex, value);
    }

    @Override
    public void setClob(int parameterIndex, java.io.Reader reader, long length) throws SQLException {
        if (delegatePreparedStatement != null) delegatePreparedStatement.setClob(parameterIndex, reader, length);
    }

    @Override
    public void setBlob(int parameterIndex, java.io.InputStream inputStream, long length) throws SQLException {
        if (delegatePreparedStatement != null) delegatePreparedStatement.setBlob(parameterIndex, inputStream, length);
    }

    @Override
    public void setNClob(int parameterIndex, java.io.Reader reader, long length) throws SQLException {
        if (delegatePreparedStatement != null) delegatePreparedStatement.setNClob(parameterIndex, reader, length);
    }

    @Override
    public void setSQLXML(int parameterIndex, java.sql.SQLXML xmlObject) throws SQLException {
        if (delegatePreparedStatement != null) delegatePreparedStatement.setSQLXML(parameterIndex, xmlObject);
    }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength) throws SQLException {
        parameters.put(parameterIndex, x);
        if (delegatePreparedStatement != null) delegatePreparedStatement.setObject(parameterIndex, x, targetSqlType, scaleOrLength);
    }

    @Override
    public void setAsciiStream(int parameterIndex, java.io.InputStream x, long length) throws SQLException {
        if (delegatePreparedStatement != null) delegatePreparedStatement.setAsciiStream(parameterIndex, x, length);
    }

    @Override
    public void setBinaryStream(int parameterIndex, java.io.InputStream x, long length) throws SQLException {
        if (delegatePreparedStatement != null) delegatePreparedStatement.setBinaryStream(parameterIndex, x, length);
    }

    @Override
    public void setCharacterStream(int parameterIndex, java.io.Reader reader, long length) throws SQLException {
        if (delegatePreparedStatement != null) delegatePreparedStatement.setCharacterStream(parameterIndex, reader, length);
    }

    @Override
    public void setAsciiStream(int parameterIndex, java.io.InputStream x) throws SQLException {
        if (delegatePreparedStatement != null) delegatePreparedStatement.setAsciiStream(parameterIndex, x);
    }

    @Override
    public void setBinaryStream(int parameterIndex, java.io.InputStream x) throws SQLException {
        if (delegatePreparedStatement != null) delegatePreparedStatement.setBinaryStream(parameterIndex, x);
    }

    @Override
    public void setCharacterStream(int parameterIndex, java.io.Reader reader) throws SQLException {
        if (delegatePreparedStatement != null) delegatePreparedStatement.setCharacterStream(parameterIndex, reader);
    }

    @Override
    public void setNCharacterStream(int parameterIndex, java.io.Reader value) throws SQLException {
        if (delegatePreparedStatement != null) delegatePreparedStatement.setNCharacterStream(parameterIndex, value);
    }

    @Override
    public void setClob(int parameterIndex, java.io.Reader reader) throws SQLException {
        if (delegatePreparedStatement != null) delegatePreparedStatement.setClob(parameterIndex, reader);
    }

    @Override
    public void setBlob(int parameterIndex, java.io.InputStream inputStream) throws SQLException {
        if (delegatePreparedStatement != null) delegatePreparedStatement.setBlob(parameterIndex, inputStream);
    }

    @Override
    public void setNClob(int parameterIndex, java.io.Reader reader) throws SQLException {
        if (delegatePreparedStatement != null) delegatePreparedStatement.setNClob(parameterIndex, reader);
    }
}
