package com.boulder.jdbc;

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
    private final Map<Integer, Object> parameters = new HashMap<>();
    private final List<Map<Integer, Object>> batchParameters = new ArrayList<>();

    private ResultSet generatedKeysResultSet = null;

    public PitonPreparedStatement(PreparedStatement delegate, String sql) {
        super(delegate);
        this.delegatePreparedStatement = delegate;
        this.sql = sql;
    }

    @Override
    public ResultSet executeQuery() throws SQLException {
        String lowerSql = sql.toLowerCase().trim();
        if (lowerSql.startsWith("select next value for") || lowerSql.startsWith("call next value for")) {
             // Mocking Hibernate Sequence Generator if it uses sequences
             List<Integer> keys = new ArrayList<>();
             keys.add(com.boulder.state.ChalkBag.get().generateId());
             return new GeneratedKeysResultSet(keys);
        }

        System.out.println("PitonPreparedStatement.executeQuery() called with sql: " + sql + ", parameters: " + parameters);
        ResultSet rs = delegatePreparedStatement.executeQuery();
        return new PitonResultSet(rs, sql);
    }

    @Override
    public int executeUpdate() throws SQLException {
        String lowerSql = sql.toLowerCase().trim();
        System.out.println("PitonPreparedStatement.executeUpdate() called with sql: " + sql + ", parameters: " + parameters);
        if (lowerSql.startsWith("insert") || lowerSql.startsWith("update") || lowerSql.startsWith("delete")) {
            List<Integer> keys = DynoMerger.interceptWriteWithKeys(sql, parameters);
            if (keys != null && !keys.isEmpty()) {
                 this.generatedKeysResultSet = new GeneratedKeysResultSet(keys);
            }
            return 1; // Do NOT execute on delegate
        }
        return delegatePreparedStatement.executeUpdate();
    }

    @Override
    public ResultSet getGeneratedKeys() throws SQLException {
        if (generatedKeysResultSet != null) {
            // Need to create a new instance because Hibernate reads and closes it,
            // or we must properly reset. Creating new is safer.
            // generatedKeysResultSet.beforeFirst() doesn't seem to reset for hibernate correctly if it expects a pristine RS
            // We should just return the generatedKeysResultSet if it hasn't been closed, or if we track keys.
            // For now, let's just create a new one to be absolutely safe
            if (generatedKeysResultSet instanceof GeneratedKeysResultSet) {
                return new GeneratedKeysResultSet(((GeneratedKeysResultSet)generatedKeysResultSet).getGeneratedKeysList());
            }
            return generatedKeysResultSet;
        }

        // Sometimes Hibernate calls this even if generatedKeysResultSet wasn't populated explicitly.
        // It expects at least an empty result set instead of null.
        return new GeneratedKeysResultSet(new ArrayList<>());
    }

    @Override
    public void setNull(int parameterIndex, int sqlType) throws SQLException {
        parameters.put(parameterIndex, null);
        delegatePreparedStatement.setNull(parameterIndex, sqlType);
    }

    @Override
    public void setBoolean(int parameterIndex, boolean x) throws SQLException {
        parameters.put(parameterIndex, x);
        delegatePreparedStatement.setBoolean(parameterIndex, x);
    }

    @Override
    public void setByte(int parameterIndex, byte x) throws SQLException {
        parameters.put(parameterIndex, x);
        delegatePreparedStatement.setByte(parameterIndex, x);
    }

    @Override
    public void setShort(int parameterIndex, short x) throws SQLException {
        parameters.put(parameterIndex, x);
        delegatePreparedStatement.setShort(parameterIndex, x);
    }

    @Override
    public void setInt(int parameterIndex, int x) throws SQLException {
        System.out.println("setInt called: index=" + parameterIndex + ", val=" + x);
        parameters.put(parameterIndex, x);
        delegatePreparedStatement.setInt(parameterIndex, x);
    }

    @Override
    public void setLong(int parameterIndex, long x) throws SQLException {
        parameters.put(parameterIndex, x);
        delegatePreparedStatement.setLong(parameterIndex, x);
    }

    @Override
    public void setFloat(int parameterIndex, float x) throws SQLException {
        parameters.put(parameterIndex, x);
        delegatePreparedStatement.setFloat(parameterIndex, x);
    }

    @Override
    public void setDouble(int parameterIndex, double x) throws SQLException {
        parameters.put(parameterIndex, x);
        delegatePreparedStatement.setDouble(parameterIndex, x);
    }

    @Override
    public void setBigDecimal(int parameterIndex, java.math.BigDecimal x) throws SQLException {
        parameters.put(parameterIndex, x);
        delegatePreparedStatement.setBigDecimal(parameterIndex, x);
    }

    @Override
    public void setString(int parameterIndex, String x) throws SQLException {
        parameters.put(parameterIndex, x);
        delegatePreparedStatement.setString(parameterIndex, x);
    }

    @Override
    public void setBytes(int parameterIndex, byte[] x) throws SQLException {
        parameters.put(parameterIndex, x);
        delegatePreparedStatement.setBytes(parameterIndex, x);
    }

    @Override
    public void setDate(int parameterIndex, java.sql.Date x) throws SQLException {
        parameters.put(parameterIndex, x);
        delegatePreparedStatement.setDate(parameterIndex, x);
    }

    @Override
    public void setTime(int parameterIndex, java.sql.Time x) throws SQLException {
        parameters.put(parameterIndex, x);
        delegatePreparedStatement.setTime(parameterIndex, x);
    }

    @Override
    public void setTimestamp(int parameterIndex, java.sql.Timestamp x) throws SQLException {
        parameters.put(parameterIndex, x);
        delegatePreparedStatement.setTimestamp(parameterIndex, x);
    }

    @Override
    public void setAsciiStream(int parameterIndex, java.io.InputStream x, int length) throws SQLException {
        delegatePreparedStatement.setAsciiStream(parameterIndex, x, length);
    }

    @Override
    public void setUnicodeStream(int parameterIndex, java.io.InputStream x, int length) throws SQLException {
        delegatePreparedStatement.setUnicodeStream(parameterIndex, x, length);
    }

    @Override
    public void setBinaryStream(int parameterIndex, java.io.InputStream x, int length) throws SQLException {
        delegatePreparedStatement.setBinaryStream(parameterIndex, x, length);
    }

    @Override
    public void clearParameters() throws SQLException {
        parameters.clear();
        delegatePreparedStatement.clearParameters();
    }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {
        parameters.put(parameterIndex, x);
        delegatePreparedStatement.setObject(parameterIndex, x, targetSqlType);
    }

    @Override
    public void setObject(int parameterIndex, Object x) throws SQLException {
        parameters.put(parameterIndex, x);
        delegatePreparedStatement.setObject(parameterIndex, x);
    }

    @Override
    public boolean execute() throws SQLException {
        System.out.println("PitonPreparedStatement.execute() called with sql: " + sql + ", parameters: " + parameters);
        String lowerSql = sql.toLowerCase().trim();
        if (lowerSql.startsWith("insert") || lowerSql.startsWith("update") || lowerSql.startsWith("delete")) {
            List<Integer> keys = DynoMerger.interceptWriteWithKeys(sql, parameters);
            if (keys != null && !keys.isEmpty()) {
                 this.generatedKeysResultSet = new GeneratedKeysResultSet(keys);
            }
            return false;
        }
        return delegatePreparedStatement.execute();
    }

    @Override
    public void addBatch() throws SQLException {
        String lowerSql = sql.toLowerCase().trim();
        if (lowerSql.startsWith("insert") || lowerSql.startsWith("update") || lowerSql.startsWith("delete")) {
            batchParameters.add(new HashMap<>(parameters));
        } else {
            delegatePreparedStatement.addBatch();
        }
    }

    @Override
    public void clearBatch() throws SQLException {
        batchParameters.clear();
        delegatePreparedStatement.clearBatch();
    }

    @Override
    public int[] executeBatch() throws SQLException {
        System.out.println("PitonPreparedStatement.executeBatch() called with sql: " + sql);
        String lowerSql = sql.toLowerCase().trim();
        if (lowerSql.startsWith("insert") || lowerSql.startsWith("update") || lowerSql.startsWith("delete")) {
            int[] results = new int[batchParameters.size()];
            for (int i = 0; i < batchParameters.size(); i++) {
                List<Integer> keys = DynoMerger.interceptWriteWithKeys(sql, batchParameters.get(i));
                if (keys != null && !keys.isEmpty()) {
                     this.generatedKeysResultSet = new GeneratedKeysResultSet(keys);
                }
                results[i] = 1;
            }
            batchParameters.clear();
            return results;
        }
        return delegatePreparedStatement.executeBatch();
    }

    @Override
    public void setCharacterStream(int parameterIndex, java.io.Reader reader, int length) throws SQLException {
        delegatePreparedStatement.setCharacterStream(parameterIndex, reader, length);
    }

    @Override
    public void setRef(int parameterIndex, java.sql.Ref x) throws SQLException {
        delegatePreparedStatement.setRef(parameterIndex, x);
    }

    @Override
    public void setBlob(int parameterIndex, java.sql.Blob x) throws SQLException {
        delegatePreparedStatement.setBlob(parameterIndex, x);
    }

    @Override
    public void setClob(int parameterIndex, java.sql.Clob x) throws SQLException {
        delegatePreparedStatement.setClob(parameterIndex, x);
    }

    @Override
    public void setArray(int parameterIndex, java.sql.Array x) throws SQLException {
        delegatePreparedStatement.setArray(parameterIndex, x);
    }

    @Override
    public java.sql.ResultSetMetaData getMetaData() throws SQLException {
        return delegatePreparedStatement.getMetaData();
    }

    @Override
    public void setDate(int parameterIndex, java.sql.Date x, java.util.Calendar cal) throws SQLException {
        parameters.put(parameterIndex, x);
        delegatePreparedStatement.setDate(parameterIndex, x, cal);
    }

    @Override
    public void setTime(int parameterIndex, java.sql.Time x, java.util.Calendar cal) throws SQLException {
        parameters.put(parameterIndex, x);
        delegatePreparedStatement.setTime(parameterIndex, x, cal);
    }

    @Override
    public void setTimestamp(int parameterIndex, java.sql.Timestamp x, java.util.Calendar cal) throws SQLException {
        parameters.put(parameterIndex, x);
        delegatePreparedStatement.setTimestamp(parameterIndex, x, cal);
    }

    @Override
    public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException {
        parameters.put(parameterIndex, null);
        delegatePreparedStatement.setNull(parameterIndex, sqlType, typeName);
    }

    @Override
    public void setURL(int parameterIndex, java.net.URL x) throws SQLException {
        parameters.put(parameterIndex, x);
        delegatePreparedStatement.setURL(parameterIndex, x);
    }

    @Override
    public java.sql.ParameterMetaData getParameterMetaData() throws SQLException {
        return delegatePreparedStatement.getParameterMetaData();
    }

    @Override
    public void setRowId(int parameterIndex, java.sql.RowId x) throws SQLException {
        parameters.put(parameterIndex, x);
        delegatePreparedStatement.setRowId(parameterIndex, x);
    }

    @Override
    public void setNString(int parameterIndex, String value) throws SQLException {
        parameters.put(parameterIndex, value);
        delegatePreparedStatement.setNString(parameterIndex, value);
    }

    @Override
    public void setNCharacterStream(int parameterIndex, java.io.Reader value, long length) throws SQLException {
        delegatePreparedStatement.setNCharacterStream(parameterIndex, value, length);
    }

    @Override
    public void setNClob(int parameterIndex, java.sql.NClob value) throws SQLException {
        delegatePreparedStatement.setNClob(parameterIndex, value);
    }

    @Override
    public void setClob(int parameterIndex, java.io.Reader reader, long length) throws SQLException {
        delegatePreparedStatement.setClob(parameterIndex, reader, length);
    }

    @Override
    public void setBlob(int parameterIndex, java.io.InputStream inputStream, long length) throws SQLException {
        delegatePreparedStatement.setBlob(parameterIndex, inputStream, length);
    }

    @Override
    public void setNClob(int parameterIndex, java.io.Reader reader, long length) throws SQLException {
        delegatePreparedStatement.setNClob(parameterIndex, reader, length);
    }

    @Override
    public void setSQLXML(int parameterIndex, java.sql.SQLXML xmlObject) throws SQLException {
        delegatePreparedStatement.setSQLXML(parameterIndex, xmlObject);
    }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength) throws SQLException {
        parameters.put(parameterIndex, x);
        delegatePreparedStatement.setObject(parameterIndex, x, targetSqlType, scaleOrLength);
    }

    @Override
    public void setAsciiStream(int parameterIndex, java.io.InputStream x, long length) throws SQLException {
        delegatePreparedStatement.setAsciiStream(parameterIndex, x, length);
    }

    @Override
    public void setBinaryStream(int parameterIndex, java.io.InputStream x, long length) throws SQLException {
        delegatePreparedStatement.setBinaryStream(parameterIndex, x, length);
    }

    @Override
    public void setCharacterStream(int parameterIndex, java.io.Reader reader, long length) throws SQLException {
        delegatePreparedStatement.setCharacterStream(parameterIndex, reader, length);
    }

    @Override
    public void setAsciiStream(int parameterIndex, java.io.InputStream x) throws SQLException {
        delegatePreparedStatement.setAsciiStream(parameterIndex, x);
    }

    @Override
    public void setBinaryStream(int parameterIndex, java.io.InputStream x) throws SQLException {
        delegatePreparedStatement.setBinaryStream(parameterIndex, x);
    }

    @Override
    public void setCharacterStream(int parameterIndex, java.io.Reader reader) throws SQLException {
        delegatePreparedStatement.setCharacterStream(parameterIndex, reader);
    }

    @Override
    public void setNCharacterStream(int parameterIndex, java.io.Reader value) throws SQLException {
        delegatePreparedStatement.setNCharacterStream(parameterIndex, value);
    }

    @Override
    public void setClob(int parameterIndex, java.io.Reader reader) throws SQLException {
        delegatePreparedStatement.setClob(parameterIndex, reader);
    }

    @Override
    public void setBlob(int parameterIndex, java.io.InputStream inputStream) throws SQLException {
        delegatePreparedStatement.setBlob(parameterIndex, inputStream);
    }

    @Override
    public void setNClob(int parameterIndex, java.io.Reader reader) throws SQLException {
        delegatePreparedStatement.setNClob(parameterIndex, reader);
    }
}
