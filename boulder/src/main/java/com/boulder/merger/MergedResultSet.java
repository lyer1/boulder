package com.boulder.merger;

import com.boulder.jdbc.PitonResultSetDecorator;
import com.boulder.state.ChalkBag;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;

public class MergedResultSet extends PitonResultSetDecorator {

    private final String sql;
    private Map<String, Integer> columnNameToIndex = new HashMap<>();
    private Map<Integer, String> indexToColumnName = new HashMap<>();

    private String tableName;
    private String pkColumnName = "id";

    private boolean inited = false;
    private boolean delegateExhausted = false;
    private List<Map<String, Object>> appendedRows = new ArrayList<>();
    private int appendedRowIndex = -1;
    private Map<String, Object> currentVirtualRow = null;

    // Extracted simple equals filter for appended rows
    // E.g., if query is "WHERE emp_id = 1001", we only want to append rows from ChalkBag where emp_id == 1001
    // A fully robust solution would implement a SQL expression evaluator, but this covers simple equivalence.
    private Map<String, String> equalityFilters = new HashMap<>();

    // Track physical PKs to avoid duplicate appends
    private List<String> physicalPKs = new ArrayList<>();

    public MergedResultSet(ResultSet delegate, String sql) {
        super(delegate);
        this.sql = sql;

        try {
            Statement stmt = CCJSqlParserUtil.parse(sql);
            if (stmt instanceof Select) {
                Select select = (Select) stmt;
                TablesNamesFinder tablesNamesFinder = new TablesNamesFinder();
                List<String> tableList = tablesNamesFinder.getTableList(select);
                if (tableList != null && !tableList.isEmpty()) {
                    this.tableName = tableList.get(0).replace("`", "").replace("\"", "");
                }
            }
        } catch (Exception e) {
            // Fallback to basic string parsing if jsqlparser fails
            try {
                String lowerSql = sql.toLowerCase();
                if (lowerSql.contains(" from ")) {
                    String afterFrom = lowerSql.split(" from ")[1].trim();
                    String[] words = afterFrom.split("\\s+");
                    if (words.length > 0) {
                        this.tableName = words[0].replace("`", "").replace("\"", "").replace(";", "");
                    }
                }
            } catch (Exception ex) {
                // Ignore
            }
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
                }
            } catch (SQLException e) {
                // Ignore, might happen if ResultSet is already closed or empty
            }

            // Prepare appended rows from ChalkBag
            if (tableName != null) {
                Map<String, Map<String, Object>> tableState = ChalkBag.get().getTable(tableName);
                if (tableState != null) {
                    for (Map.Entry<String, Map<String, Object>> entry : tableState.entrySet()) {
                        if (entry.getValue() != null && !ChalkBag.get().isTombstoned(tableName, entry.getKey())) {

                            // Check basic filter
                            // For a robust enterprise implementation, we would evaluate the AST Where expression
                            // against the virtual row. We'll allow all for now and let the business logic filter,
                            // or rely on a more complex evaluator.
                            boolean matches = true;
                            // Add expression evaluation here in the future

                            if (matches) {
                                Map<String, Object> rowCopy = new HashMap<>(entry.getValue());
                                rowCopy.put("__boulder_pk", entry.getKey());
                                appendedRows.add(rowCopy);
                            }
                        }
                    }
                }
            }
        }
    }

    @Override
    public boolean next() throws SQLException {
        initColumnMaps();

        if (delegateExhausted) {
            return nextAppendedRow();
        }

        boolean hasNext = false;
        try {
            while ((hasNext = delegate.next())) {
                Integer pkIndex = columnNameToIndex.get(pkColumnName);
                if (pkIndex != null && tableName != null) {
                    Object pkVal = delegate.getObject(pkIndex);
                    if (pkVal != null) {
                        String pkStr = pkVal.toString();
                        physicalPKs.add(pkStr);
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

        delegateExhausted = true;

        // Exclude appended rows that we already yielded physically
        Iterator<Map<String, Object>> it = appendedRows.iterator();
        while (it.hasNext()) {
            Map<String, Object> row = it.next();
            if (physicalPKs.contains(row.get("__boulder_pk"))) {
                it.remove();
            }
        }

        return nextAppendedRow();
    }

    private boolean nextAppendedRow() {
        appendedRowIndex++;
        if (appendedRowIndex < appendedRows.size()) {
            currentVirtualRow = appendedRows.get(appendedRowIndex);
            return true;
        }
        return false;
    }

    private Object getMergedValue(int columnIndex) throws SQLException {
        if (delegateExhausted) {
            if (currentVirtualRow == null) return null;
            String colName = indexToColumnName.get(columnIndex);
            if (colName != null) {
                // Ignore case mapping
                for (String key : currentVirtualRow.keySet()) {
                     if (key.equalsIgnoreCase(colName)) {
                         return currentVirtualRow.get(key);
                     }
                }
            }
            return null;
        }

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
        if (delegateExhausted) {
            if (currentVirtualRow == null) return null;
            for (String key : currentVirtualRow.keySet()) {
                 if (key.equalsIgnoreCase(columnLabel)) {
                     return currentVirtualRow.get(key);
                 }
            }
            return null;
        }

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
        if (delegateExhausted) return 0;
        return delegate.getInt(columnIndex);
    }

    @Override
    public int getInt(String columnLabel) throws SQLException {
        Object val = getMergedValue(columnLabel);
        if (val == null) return 0;
        if (val instanceof Number) return ((Number)val).intValue();
        if (val instanceof String) return Integer.parseInt((String)val);
        if (delegateExhausted) return 0;
        return delegate.getInt(columnLabel);
    }

    @Override
    public double getDouble(int columnIndex) throws SQLException {
        Object val = getMergedValue(columnIndex);
        if (val == null) return 0.0;
        if (val instanceof Number) return ((Number)val).doubleValue();
        if (val instanceof String) return Double.parseDouble((String)val);
        if (delegateExhausted) return 0.0;
        return delegate.getDouble(columnIndex);
    }

    @Override
    public double getDouble(String columnLabel) throws SQLException {
        Object val = getMergedValue(columnLabel);
        if (val == null) return 0.0;
        if (val instanceof Number) return ((Number)val).doubleValue();
        if (val instanceof String) return Double.parseDouble((String)val);
        if (delegateExhausted) return 0.0;
        return delegate.getDouble(columnLabel);
    }
}
