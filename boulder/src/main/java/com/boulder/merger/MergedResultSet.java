package com.boulder.merger;

import com.boulder.jdbc.PitonResultSetDecorator;
import com.boulder.state.ChalkBag;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;

public class MergedResultSet extends PitonResultSetDecorator {

    private final String sql;
    private final Map<Integer, Object> statementParameters;

    // Table Meta Info
    private Map<String, String> aliasToTableName = new HashMap<>();
    private Map<String, Integer> tablePkColumnIndex = new HashMap<>();
    
    // Column Index Mappings
    private Map<String, Integer> columnLabelToIndex = new HashMap<>();
    private Map<Integer, String> indexToColumnLabel = new HashMap<>();
    private Map<Integer, String> indexToTableName = new HashMap<>();

    private boolean inited = false;
    private boolean delegateExhausted = false;

    // New Rows (Inserts)
    private List<Map<String, Object>> appendedRows = new ArrayList<>();
    private int appendedRowIndex = -1;
    private Map<String, Object> currentVirtualRow = null;

    // Filters for newly inserted rows
    private Map<String, Object> equalityFilters = new HashMap<>();
    
    // Seen PKs to avoid duplicates when appending new rows
    private Set<String> seenPhysicalPKs = new HashSet<>();

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
                if (select.getSelectBody() instanceof PlainSelect) {
                    PlainSelect ps = (PlainSelect) select.getSelectBody();
                    
                    // 1. Resolve Tables and Aliases
                    resolveTableAliases(ps);
                    
                    // 2. Extract filters for virtual row selection
                    extractEqualityFilters(ps.getWhere());
                }
            }
        } catch (Exception e) {
            // Fallback for simple single table
            String lowerSql = sql.toLowerCase();
            if (lowerSql.contains(" from ")) {
                String afterFrom = lowerSql.split(" from ")[1].trim();
                String[] words = afterFrom.split("\\s+");
                if (words.length > 0) {
                    String tbl = words[0].replace("`", "").replace("\"", "").replace(";", "");
                    aliasToTableName.put(tbl, tbl);
                }
            }
        }
    }

    private void resolveTableAliases(PlainSelect ps) {
        if (ps.getFromItem() instanceof Table) {
            Table t = (Table) ps.getFromItem();
            String name = t.getName().toLowerCase();
            String alias = (t.getAlias() != null) ? t.getAlias().getName().toLowerCase() : name;
            aliasToTableName.put(alias, name);
        }
        if (ps.getJoins() != null) {
            for (Join join : ps.getJoins()) {
                if (join.getRightItem() instanceof Table) {
                    Table t = (Table) join.getRightItem();
                    String name = t.getName().toLowerCase();
                    String alias = (t.getAlias() != null) ? t.getAlias().getName().toLowerCase() : name;
                    aliasToTableName.put(alias, name);
                }
            }
        }
    }

    private void extractEqualityFilters(Expression where) {
        if (where instanceof EqualsTo) {
            EqualsTo eq = (EqualsTo) where;
            if (eq.getLeftExpression() instanceof Column) {
                String col = ((Column) eq.getLeftExpression()).getColumnName().toLowerCase();
                Expression right = eq.getRightExpression();
                Object val = null;
                if (right instanceof LongValue) val = ((LongValue) right).getValue();
                else if (right instanceof StringValue) val = ((StringValue) right).getValue();
                else if (right instanceof DoubleValue) val = ((DoubleValue) right).getValue();
                else if (right instanceof JdbcParameter) {
                    int pIdx = (((JdbcParameter) right).getIndex() != null) ? ((JdbcParameter) right).getIndex() : 1;
                    val = statementParameters.get(pIdx);
                }
                if (val != null) equalityFilters.put(col, val);
            }
        }
    }

    private void initMetadata() throws SQLException {
        if (!inited) {
            inited = true;
            ResultSetMetaData meta = delegate.getMetaData();
            int count = meta.getColumnCount();
            for (int i = 1; i <= count; i++) {
                String label = meta.getColumnLabel(i).toLowerCase();
                String tblName = meta.getTableName(i).toLowerCase();
                
                columnLabelToIndex.put(label, i);
                indexToColumnLabel.put(i, label);

                // Hibernate often doesn't populate getTableName() in JDBC. 
                // We use column label patterns (like id, id1_0_) to find PKs.
                if (label.equals("id") || label.endsWith("_id") || label.contains("id")) {
                    // This is naive, ideally we'd use aliases mapped during SQL parsing
                }
                
                // Track which table this column belongs to if possible
                if (tblName != null && !tblName.isEmpty()) {
                    indexToTableName.put(i, tblName);
                    if (label.equals("id")) {
                        tablePkColumnIndex.put(tblName, i);
                    }
                } else {
                    // Heuristic for Hibernate aliases: employee0_.id -> employee0_
                    // We try to match with our aliases
                    for (String alias : aliasToTableName.keySet()) {
                        if (label.startsWith(alias + ".")) {
                             indexToTableName.put(i, aliasToTableName.get(alias));
                             if (label.endsWith(".id")) tablePkColumnIndex.put(aliasToTableName.get(alias), i);
                        }
                    }
                }
            }
            
            // Fallback for primary table if only one PK found
            if (tablePkColumnIndex.size() == 0 && columnLabelToIndex.containsKey("id")) {
                String mainTable = aliasToTableName.values().stream().findFirst().orElse(null);
                if (mainTable != null) tablePkColumnIndex.put(mainTable, columnLabelToIndex.get("id"));
            }

            // Load Virtual rows for "Append" phase
            loadAppendedRows();
        }
    }

    private void loadAppendedRows() {
        // Only append for the "Primary" table in a query for simplicity in this proxy.
        String primaryTable = aliasToTableName.values().stream().findFirst().orElse(null);
        if (primaryTable != null) {
            Map<String, Map<String, Object>> tableData = ChalkBag.get().getTable(primaryTable);
            if (tableData != null) {
                for (Map.Entry<String, Map<String, Object>> entry : tableData.entrySet()) {
                    if (entry.getValue() != null && !ChalkBag.get().isTombstoned(primaryTable, entry.getKey())) {
                        
                        // Simple Filter
                        boolean matches = true;
                        for (Map.Entry<String, Object> f : equalityFilters.entrySet()) {
                            Object rv = entry.getValue().get(f.getKey());
                            if (rv == null || !rv.toString().equals(f.getValue().toString())) {
                                matches = false; break;
                            }
                        }
                        
                        if (matches) {
                            Map<String, Object> row = new HashMap<>(entry.getValue());
                            row.put("__boulder_pk", entry.getKey());
                            appendedRows.add(row);
                        }
                    }
                }
            }
        }
    }

    @Override
    public boolean next() throws SQLException {
        initMetadata();
        
        if (delegateExhausted) {
            return nextVirtualRow();
        }

        while (delegate.next()) {
            boolean tombstoned = false;
            // Check all tables involved in this row for tombstones
            for (Map.Entry<String, Integer> entry : tablePkColumnIndex.entrySet()) {
                Object pk = delegate.getObject(entry.getValue());
                if (pk != null) {
                    String pkStr = pk.toString();
                    seenPhysicalPKs.add(pkStr);
                    if (ChalkBag.get().isTombstoned(entry.getKey(), pkStr)) {
                        tombstoned = true;
                        break;
                    }
                }
            }
            if (!tombstoned) return true;
        }

        delegateExhausted = true;
        
        // Remove virtual rows that we already saw physically
        appendedRows.removeIf(row -> seenPhysicalPKs.contains(row.get("__boulder_pk")));
        
        return nextVirtualRow();
    }

    private boolean nextVirtualRow() {
        appendedRowIndex++;
        if (appendedRowIndex < appendedRows.size()) {
            currentVirtualRow = appendedRows.get(appendedRowIndex);
            return true;
        }
        return false;
    }

    @Override
    public Object getObject(int columnIndex) throws SQLException {
        if (delegateExhausted) {
            if (currentVirtualRow == null) return null;
            String label = indexToColumnLabel.get(columnIndex);
            return currentVirtualRow.get(label);
        }

        // Overlay logic: Check if this column belongs to a table that has a patch
        String table = indexToTableName.get(columnIndex);
        if (table != null) {
            Integer pkIdx = tablePkColumnIndex.get(table);
            if (pkIdx != null) {
                Object pk = delegate.getObject(pkIdx);
                if (pk != null) {
                    Map<String, Object> patch = ChalkBag.get().getRow(table, pk.toString());
                    if (patch != null) {
                        String label = indexToColumnLabel.get(columnIndex);
                        if (patch.containsKey(label)) return patch.get(label);
                    }
                }
            }
        }
        
        return delegate.getObject(columnIndex);
    }

    @Override
    public Object getObject(String columnLabel) throws SQLException {
        Integer idx = columnLabelToIndex.get(columnLabel.toLowerCase());
        if (idx != null) return getObject(idx);
        return delegate.getObject(columnLabel);
    }

    @Override
    public String getString(int columnIndex) throws SQLException {
        Object val = getObject(columnIndex);
        return (val == null) ? null : val.toString();
    }

    @Override
    public String getString(String columnLabel) throws SQLException {
        Object val = getObject(columnLabel);
        return (val == null) ? null : val.toString();
    }

    @Override
    public int getInt(int columnIndex) throws SQLException {
        Object val = getObject(columnIndex);
        if (val instanceof Number) return ((Number) val).intValue();
        return (val == null) ? 0 : Integer.parseInt(val.toString());
    }

    @Override
    public int getInt(String columnLabel) throws SQLException {
        Object val = getObject(columnLabel);
        if (val instanceof Number) return ((Number) val).intValue();
        return (val == null) ? 0 : Integer.parseInt(val.toString());
    }

    @Override
    public double getDouble(int columnIndex) throws SQLException {
        Object val = getObject(columnIndex);
        if (val instanceof Number) return ((Number) val).doubleValue();
        return (val == null) ? 0.0 : Double.parseDouble(val.toString());
    }

    @Override
    public double getDouble(String columnLabel) throws SQLException {
        Object val = getObject(columnLabel);
        if (val instanceof Number) return ((Number) val).doubleValue();
        return (val == null) ? 0.0 : Double.parseDouble(val.toString());
    }

    @Override
    public long getLong(int columnIndex) throws SQLException {
        Object val = getObject(columnIndex);
        if (val instanceof Number) return ((Number) val).longValue();
        return (val == null) ? 0L : Long.parseLong(val.toString());
    }

    @Override
    public long getLong(String columnLabel) throws SQLException {
        Object val = getObject(columnLabel);
        if (val instanceof Number) return ((Number) val).longValue();
        return (val == null) ? 0L : Long.parseLong(val.toString());
    }
}
