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

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;

public class MergedResultSet extends PitonResultSetDecorator {

    private final String sql;
    private final Map<Integer, Object> statementParameters;
    private Map<String, Integer> columnNameToIndex = new HashMap<>();
    private Map<Integer, String> indexToColumnName = new HashMap<>();

    private String tableName;
    private String pkColumnName = "id";

    private boolean inited = false;
    private boolean delegateExhausted = false;
    private List<Map<String, Object>> appendedRows = new ArrayList<>();
    private int appendedRowIndex = -1;
    private Map<String, Object> currentVirtualRow = null;

    private Map<String, Object> equalityFilters = new HashMap<>();
    private List<String> physicalPKs = new ArrayList<>();

    public MergedResultSet(ResultSet delegate, String sql) {
        this(delegate, sql, new HashMap<>());
    }

    public MergedResultSet(ResultSet delegate, String sql, Map<Integer, Object> statementParameters) {
        super(delegate);
        this.sql = sql;
        this.statementParameters = statementParameters;

        try {
            Statement stmt = CCJSqlParserUtil.parse(sql);
            if (stmt instanceof Select) {
                Select select = (Select) stmt;
                TablesNamesFinder tablesNamesFinder = new TablesNamesFinder();
                List<String> tableList = tablesNamesFinder.getTableList(select);
                if (tableList != null && !tableList.isEmpty()) {
                    this.tableName = tableList.get(0).replace("`", "").replace("\"", "");
                }

                if (select.getSelectBody() instanceof PlainSelect) {
                    PlainSelect ps = (PlainSelect) select.getSelectBody();
                    extractEqualityFilters(ps.getWhere());
                }
            }
        } catch (Exception e) {
            try {
                String lowerSql = sql.toLowerCase();
                if (lowerSql.contains(" from ")) {
                    String afterFrom = lowerSql.split(" from ")[1].trim();
                    String[] words = afterFrom.split("\\s+");
                    if (words.length > 0) {
                        this.tableName = words[0].replace("`", "").replace("\"", "").replace(";", "");
                    }
                }
            } catch (Exception ex) {}
        }
    }

    private void extractEqualityFilters(Expression where) {
        if (where instanceof EqualsTo) {
            EqualsTo eq = (EqualsTo) where;
            if (eq.getLeftExpression() instanceof Column) {
                String col = ((Column) eq.getLeftExpression()).getColumnName().toLowerCase();
                Expression right = eq.getRightExpression();
                if (right instanceof LongValue) {
                    equalityFilters.put(col, ((LongValue) right).getValue());
                } else if (right instanceof StringValue) {
                    equalityFilters.put(col, ((StringValue) right).getValue());
                } else if (right instanceof DoubleValue) {
                    equalityFilters.put(col, ((DoubleValue) right).getValue());
                } else if (right instanceof JdbcParameter) {
                    JdbcParameter param = (JdbcParameter) right;
                    int index = param.getIndex();
                    // JSqlParser indices might be 1-based or 0-based depending on version and presence of '?'
                    // Usually for '?' it is null index and we have to count.
                    // If it's just '?', param.getIndex() might be null or 1.
                    // Let's try 1 if it's null, or the index itself.
                    int pIdx = (param.getIndex() != null) ? param.getIndex() : 1;
                    Object val = statementParameters.get(pIdx);
                    if (val != null) {
                        equalityFilters.put(col, val);
                    }
                }
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
            } catch (SQLException e) {}

            if (tableName != null) {
                Map<String, Map<String, Object>> tableState = ChalkBag.get().getTable(tableName);
                if (tableState != null) {
                    for (Map.Entry<String, Map<String, Object>> entry : tableState.entrySet()) {
                        if (entry.getValue() != null && !ChalkBag.get().isTombstoned(tableName, entry.getKey())) {
                            
                            // Apply filters
                            boolean matches = true;
                            for (Map.Entry<String, Object> filter : equalityFilters.entrySet()) {
                                Object rowVal = null;
                                for (String key : entry.getValue().keySet()) {
                                    if (key.equalsIgnoreCase(filter.getKey())) {
                                        rowVal = entry.getValue().get(key);
                                        break;
                                    }
                                }
                                if (rowVal == null || !rowVal.toString().equals(filter.getValue().toString())) {
                                    matches = false;
                                    break;
                                }
                            }

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
        } catch (SQLException e) {}

        delegateExhausted = true;

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
                } catch (Exception e) {}
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
            } catch (Exception e) {}
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
