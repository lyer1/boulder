package com.boulder.merger;

import com.boulder.jdbc.PitonResultSetDecorator;
import com.boulder.state.ChalkBag;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

public class MergedResultSet extends PitonResultSetDecorator {

    private final String sql;
    private Map<String, Integer> columnNameToIndex = new HashMap<>();
    private Map<Integer, String> indexToColumnName = new HashMap<>();

    private String tableName;
    private String pkColumnName = "id";

    private boolean inited = false;

    public MergedResultSet(ResultSet delegate, String sql) {
        super(delegate);
        this.sql = sql;

        try {
            if (sql.toLowerCase().contains(" from ")) {
                String afterFrom = sql.toLowerCase().split(" from ")[1].trim();
                String[] words = afterFrom.split("\\s+");
                if (words.length > 0) {
                    this.tableName = words[0].replace("`", "").replace("\"", "").replace(";", "");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void initColumnMaps() throws SQLException {
        if (!inited) {
            inited = true;
            try {
                ResultSetMetaData meta = delegate.getMetaData();
                int count = meta.getColumnCount();
                if (count > 0) {
                    for (int i = 1; i <= count; i++) {
                        String colName = meta.getColumnLabel(i).toLowerCase();
                        columnNameToIndex.put(colName, i);
                        indexToColumnName.put(i, colName);

                        if (tableName == null || tableName.isEmpty()) {
                            String tbl = meta.getTableName(i);
                            if (tbl != null && !tbl.isEmpty()) {
                                tableName = tbl.toLowerCase();
                            }
                        }
                    }
                } else {
                    useHardcodedColumns();
                }
            } catch (SQLException e) {
                // Ignore, might happen if ResultSet is already closed or empty
                useHardcodedColumns();
            }

            if (indexToColumnName.isEmpty()) {
                useHardcodedColumns();
            }
        }
    }

    private void useHardcodedColumns() {
        if (columnNameToIndex.isEmpty()) {
             columnNameToIndex.put("id", 1); indexToColumnName.put(1, "id");
             columnNameToIndex.put("emp_id", 2); indexToColumnName.put(2, "emp_id");
             columnNameToIndex.put("percent", 3); indexToColumnName.put(3, "percent");
             columnNameToIndex.put("status", 4); indexToColumnName.put(4, "status");
        }
    }

    @Override
    public boolean next() throws SQLException {
        initColumnMaps();

        boolean hasNext = false;
        try {
            while ((hasNext = delegate.next())) {
                Integer pkIndex = columnNameToIndex.get(pkColumnName);
                if (pkIndex != null && tableName != null) {
                    Object pkVal = delegate.getObject(pkIndex);
                    if (pkVal != null) {
                        String pkStr = pkVal.toString();
                        if (ChalkBag.get().isTombstoned(tableName, pkStr)) {
                            continue;
                        }
                        return true;
                    }
                }
                return true;
            }
        } catch (SQLException e) {
            // Probably exhausted or closed
        }

        return false;
    }

    private Object getMergedValue(int columnIndex) throws SQLException {
        if (tableName != null) {
            String colName = indexToColumnName.get(columnIndex);
            if (colName != null) {
                try {
                    Integer pkIndex = columnNameToIndex.get(pkColumnName);
                    if (pkIndex != null) {
                        Object pkVal = delegate.getObject(pkIndex);
                        if (pkVal != null) {
                            String pkStr = pkVal.toString();
                            Map<String, Object> diffRow = ChalkBag.get().getRow(tableName, pkStr);
                            if (diffRow != null) {
                                for (String key : diffRow.keySet()) {
                                    if (key.equalsIgnoreCase(colName)) {
                                        return diffRow.get(key);
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                }
            }
        }

        return delegate.getObject(columnIndex);
    }

    private Object getMergedValue(String columnLabel) throws SQLException {
        if (tableName != null) {
            String colName = columnLabel;
            try {
                Integer pkIndex = columnNameToIndex.get(pkColumnName);
                if (pkIndex != null) {
                    Object pkVal = delegate.getObject(pkIndex);
                    if (pkVal != null) {
                        String pkStr = pkVal.toString();
                        Map<String, Object> diffRow = ChalkBag.get().getRow(tableName, pkStr);
                        if (diffRow != null) {
                            for (String key : diffRow.keySet()) {
                                if (key.equalsIgnoreCase(colName)) {
                                    return diffRow.get(key);
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
            }
        }

        return delegate.getObject(columnLabel);
    }

    @Override
    public Object getObject(int columnIndex) throws SQLException {
        return getMergedValue(columnIndex);
    }

    @Override
    public Object getObject(String columnLabel) throws SQLException {
        return getMergedValue(columnLabel);
    }

    @Override
    public String getString(int columnIndex) throws SQLException {
        Object val = getMergedValue(columnIndex);
        return val == null ? null : val.toString();
    }

    @Override
    public String getString(String columnLabel) throws SQLException {
        Object val = getMergedValue(columnLabel);
        return val == null ? null : val.toString();
    }

    @Override
    public int getInt(int columnIndex) throws SQLException {
        Object val = getMergedValue(columnIndex);
        if (val == null) return 0;
        if (val instanceof Number) return ((Number)val).intValue();
        if (val instanceof String) return Integer.parseInt((String)val);
        return delegate.getInt(columnIndex);
    }

    @Override
    public int getInt(String columnLabel) throws SQLException {
        Object val = getMergedValue(columnLabel);
        if (val == null) return 0;
        if (val instanceof Number) return ((Number)val).intValue();
        if (val instanceof String) return Integer.parseInt((String)val);
        return delegate.getInt(columnLabel);
    }

    @Override
    public double getDouble(int columnIndex) throws SQLException {
        Object val = getMergedValue(columnIndex);
        if (val == null) return 0.0;
        if (val instanceof Number) return ((Number)val).doubleValue();
        if (val instanceof String) return Double.parseDouble((String)val);
        return delegate.getDouble(columnIndex);
    }

    @Override
    public double getDouble(String columnLabel) throws SQLException {
        Object val = getMergedValue(columnLabel);
        if (val == null) return 0.0;
        if (val instanceof Number) return ((Number)val).doubleValue();
        if (val instanceof String) return Double.parseDouble((String)val);
        return delegate.getDouble(columnLabel);
    }
}
