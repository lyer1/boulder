package com.boulder.merger;

import com.boulder.jdbc.PitonResultSetDecorator;
import com.boulder.state.ChalkBag;

import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SelectExpressionItem;

public class MergedResultSet extends PitonResultSetDecorator {

    private final String sql;
    private final Map<Integer, Object> statementParameters;

    private Map<String, String> aliasToTable = new HashMap<>();
    private Map<String, String> tableToPkCol = new HashMap<>();
    
    private Map<Integer, String> indexToTable = new HashMap<>();
    private Map<Integer, String> indexToCol = new HashMap<>();
    private Map<String, Integer> labelToIndex = new HashMap<>();

    private List<JoinInfo> joins = new ArrayList<>();

    private boolean inited = false;
    private boolean delegateExhausted = false;
    private List<Map<String, Object>> virtualRows = new ArrayList<>();
    private int virtualRowIndex = -1;
    private Map<String, Object> currentVirtualRow = null;
    
    private Map<String, Object> whereFilters = new HashMap<>();
    private Set<String> seenPrimaryPKs = new HashSet<>();

    private static class JoinInfo {
        String leftTable, leftCol, rightTable, rightCol;
    }

    public MergedResultSet(ResultSet delegate, String sql) {
        this(delegate, sql, new HashMap<>());
    }

    public MergedResultSet(ResultSet delegate, String sql, Map<Integer, Object> params) {
        super(delegate);
        this.sql = sql;
        this.statementParameters = params;
        parseSql();
    }

    private void parseSql() {
        try {
            Statement stmt = CCJSqlParserUtil.parse(sql);
            if (stmt instanceof Select) {
                PlainSelect ps = (PlainSelect) ((Select) stmt).getSelectBody();
                resolveTables(ps);
                resolveJoins(ps);
                mapSelects(ps);
                extractFilters(ps.getWhere());
            }
        } catch (Exception e) {}
    }

    private void resolveTables(PlainSelect ps) {
        if (ps.getFromItem() instanceof Table) {
            Table t = (Table) ps.getFromItem();
            String name = t.getName().toLowerCase();
            aliasToTable.put((t.getAlias() != null ? t.getAlias().getName().toLowerCase() : name), name);
        }
        if (ps.getJoins() != null) {
            for (Join j : ps.getJoins()) {
                if (j.getRightItem() instanceof Table) {
                    Table t = (Table) j.getRightItem();
                    String name = t.getName().toLowerCase();
                    aliasToTable.put((t.getAlias() != null ? t.getAlias().getName().toLowerCase() : name), name);
                }
            }
        }
    }

    private void resolveJoins(PlainSelect ps) {
        if (ps.getJoins() != null) {
            for (Join j : ps.getJoins()) {
                if (j.getOnExpressions() == null) continue;
                for (Expression e : j.getOnExpressions()) {
                    if (e instanceof EqualsTo) {
                        EqualsTo eq = (EqualsTo) e;
                        if (eq.getLeftExpression() instanceof Column && eq.getRightExpression() instanceof Column) {
                            Column l = (Column) eq.getLeftExpression();
                            Column r = (Column) eq.getRightExpression();
                            JoinInfo ji = new JoinInfo();
                            ji.leftTable = resolveAlias(l.getTable() != null ? l.getTable().getName() : null);
                            ji.leftCol = l.getColumnName().toLowerCase();
                            ji.rightTable = resolveAlias(r.getTable() != null ? r.getTable().getName() : null);
                            ji.rightCol = r.getColumnName().toLowerCase();
                            joins.add(ji);
                        }
                    }
                }
            }
        }
    }

    private String resolveAlias(String a) {
        if (a == null) return aliasToTable.values().stream().findFirst().orElse(null);
        String t = aliasToTable.get(a.toLowerCase());
        return t != null ? t : a.toLowerCase();
    }

    private void mapSelects(PlainSelect ps) {
        int i = 1;
        for (SelectItem item : ps.getSelectItems()) {
            if (item instanceof SelectExpressionItem) {
                Expression e = ((SelectExpressionItem) item).getExpression();
                if (e instanceof Column) {
                    Column c = (Column) e;
                    indexToTable.put(i, resolveAlias(c.getTable() != null ? c.getTable().getName() : null));
                    indexToCol.put(i, c.getColumnName().toLowerCase());
                }
            }
            i++;
        }
    }

    private void extractFilters(Expression where) {
        if (where instanceof net.sf.jsqlparser.expression.operators.conditional.AndExpression) {
            net.sf.jsqlparser.expression.operators.conditional.AndExpression and = (net.sf.jsqlparser.expression.operators.conditional.AndExpression) where;
            extractFilters(and.getLeftExpression());
            extractFilters(and.getRightExpression());
        } else if (where instanceof EqualsTo) {
            EqualsTo eq = (EqualsTo) where;
            if (eq.getLeftExpression() instanceof Column) {
                String col = ((Column) eq.getLeftExpression()).getColumnName().toLowerCase();
                Expression r = eq.getRightExpression();
                Object v = null;
                if (r instanceof LongValue) v = ((LongValue) r).getValue();
                else if (r instanceof StringValue) v = ((StringValue) r).getValue();
                else if (r instanceof DoubleValue) v = ((DoubleValue) r).getValue();
                else if (r instanceof JdbcParameter) {
                    int idx = ((JdbcParameter) r).getIndex() != null ? ((JdbcParameter) r).getIndex() : 1;
                    v = statementParameters.get(idx);
                } else {
                    String s = r.toString().toLowerCase();
                    if (s.equals("true")) v = true; else if (s.equals("false")) v = false;
                }
                if (v != null) whereFilters.put(col, v);
            }
        }
    }

    private void init() throws SQLException {
        if (inited) return;
        inited = true;
        ResultSetMetaData m = delegate.getMetaData();
        for (int i = 1; i <= m.getColumnCount(); i++) {
            labelToIndex.put(m.getColumnLabel(i).toLowerCase(), i);
        }
        DatabaseMetaData dbm = delegate.getStatement().getConnection().getMetaData();
        for (String t : aliasToTable.values()) {
            try (ResultSet rs = dbm.getPrimaryKeys(null, null, t.toUpperCase())) {
                if (rs.next()) tableToPkCol.put(t, rs.getString("COLUMN_NAME").toLowerCase());
            } catch (Exception e) {}
        }
        loadVirtual();
    }

    private void loadVirtual() throws SQLException {
        String primary = aliasToTable.values().stream().findFirst().orElse(null);
        if (primary == null) return;

        Set<String> primaryPKs = new HashSet<>();
        for (String t : aliasToTable.values()) {
            Map<String, Map<String, Object>> state = ChalkBag.get().getTable(t);
            if (state == null) continue;
            for (Map.Entry<String, Map<String, Object>> e : state.entrySet()) {
                if (e.getValue() == null) continue;
                boolean patchMatches = true;
                for (Map.Entry<String, Object> filter : whereFilters.entrySet()) {
                    if (e.getValue().containsKey(filter.getKey())) {
                        if (!e.getValue().get(filter.getKey()).toString().equalsIgnoreCase(filter.getValue().toString())) {
                            patchMatches = false; break;
                        }
                    }
                }
                if (patchMatches) {
                    if (t.equalsIgnoreCase(primary)) primaryPKs.add(e.getKey());
                    else resolvePrimaryPKsFromJoinedPK(t, e.getKey(), primary, primaryPKs);
                }
            }
        }

        for (String pk : primaryPKs) {
            if (ChalkBag.get().isTombstoned(primary, pk)) continue;
            Map<String, Object> fullRow = buildFullVirtualRow(primary, pk);
            if (matchesAllFilters(fullRow)) {
                fullRow.put("__pk", pk);
                virtualRows.add(fullRow);
            }
        }
    }

    private void resolvePrimaryPKsFromJoinedPK(String joinedT, String joinedPK, String primaryT, Set<String> res) {
        for (JoinInfo ji : joins) {
            if (joinedT.equalsIgnoreCase(ji.rightTable) && primaryT.equalsIgnoreCase(ji.leftTable)) {
                String pkCol = tableToPkCol.get(primaryT);
                if (pkCol == null) pkCol = "id";
                try (PreparedStatement ps = delegate.getStatement().getConnection().prepareStatement(
                        "SELECT " + pkCol + " FROM " + primaryT + " WHERE " + ji.leftCol + " = ?")) {
                    ps.setObject(1, joinedPK);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) res.add(rs.getString(1));
                    }
                } catch (Exception e) {}
            }
        }
    }

    private Map<String, Object> buildFullVirtualRow(String table, String pk) throws SQLException {
        Map<String, Object> row = new HashMap<>();
        for (String t : aliasToTable.values()) {
            Object pkOfT = t.equalsIgnoreCase(table) ? pk : resolvePkAcrossJoin(table, pk, t);
            if (pkOfT != null) {
                Map<String, Object> p = ChalkBag.get().getRow(t, pkOfT.toString());
                if (p != null) row.putAll(p);
                ResultSetMetaData m = delegate.getMetaData();
                for (int i = 1; i <= m.getColumnCount(); i++) {
                    String colT = indexToTable.get(i);
                    if (t.equalsIgnoreCase(colT)) {
                        String c = indexToCol.get(i);
                        if (c != null && !row.containsKey(c)) {
                            Object val = fetchPhys(t, pkOfT.toString(), c);
                            if (val != null) row.put(c, val);
                        }
                    }
                }
            }
        }
        return row;
    }

    private Object fetchPhys(String t, String pk, String c) {
        String pkCol = tableToPkCol.get(t);
        if (pkCol == null) pkCol = "id";
        try (PreparedStatement ps = delegate.getStatement().getConnection().prepareStatement(
                "SELECT " + c + " FROM " + t + " WHERE " + pkCol + " = ?")) {
            ps.setObject(1, pk);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return rs.getObject(1); }
        } catch (Exception e) {}
        return null;
    }

    private boolean matchesAllFilters(Map<String, Object> row) {
        for (Map.Entry<String, Object> f : whereFilters.entrySet()) {
            Object v = row.get(f.getKey());
            if (v == null || !v.toString().equalsIgnoreCase(f.getValue().toString())) return false;
        }
        return true;
    }

    @Override
    public boolean next() throws SQLException {
        init();
        if (delegateExhausted) return nextV();
        while (delegate.next()) {
            boolean skip = false;
            for (String t : aliasToTable.values()) {
                Object pk = resolvePk(t);
                if (pk != null && ChalkBag.get().isTombstoned(t, pk.toString())) { skip = true; break; }
            }
            if (skip) continue;
            
            String primary = aliasToTable.values().stream().findFirst().orElse(null);
            if (primary != null) {
                Object pk = resolvePk(primary);
                if (pk != null) seenPrimaryPKs.add(pk.toString());
            }

            for (Map.Entry<String, Object> f : whereFilters.entrySet()) {
                String colName = f.getKey();
                for (int i = 1; i <= delegate.getMetaData().getColumnCount(); i++) {
                    if (colName.equalsIgnoreCase(indexToCol.get(i))) {
                        String t = indexToTable.get(i);
                        Object v = delegate.getObject(i);
                        Object pk = resolvePk(t);
                        if (pk != null) {
                            Map<String, Object> p = ChalkBag.get().getRow(t, pk.toString());
                            if (p != null && p.containsKey(colName)) v = p.get(colName);
                        }
                        if (v == null || !v.toString().equalsIgnoreCase(f.getValue().toString())) { skip = true; break; }
                    }
                }
                if (skip) break;
            }
            if (!skip) return true;
        }
        delegateExhausted = true;
        virtualRows.removeIf(r -> seenPrimaryPKs.contains(r.get("__pk")));
        return nextV();
    }

    private boolean nextV() {
        virtualRowIndex++;
        if (virtualRowIndex < virtualRows.size()) {
            currentVirtualRow = virtualRows.get(virtualRowIndex);
            return true;
        }
        return false;
    }

    private Object resolvePkAcrossJoin(String fromT, String fromPK, String toT) {
        for (JoinInfo ji : joins) {
            if (fromT.equalsIgnoreCase(ji.leftTable) && toT.equalsIgnoreCase(ji.rightTable)) {
                return fetchPhys(fromT, fromPK, ji.leftCol);
            } else if (fromT.equalsIgnoreCase(ji.rightTable) && toT.equalsIgnoreCase(ji.leftTable)) {
                return fetchPhys(fromT, fromPK, ji.rightCol);
            }
        }
        return null;
    }

    private Object resolvePk(String t) throws SQLException {
        String pkCol = tableToPkCol.get(t);
        if (pkCol == null) pkCol = "id";
        for (int i = 1; i <= delegate.getMetaData().getColumnCount(); i++) {
            if (t.equalsIgnoreCase(indexToTable.get(i)) && pkCol.equalsIgnoreCase(indexToCol.get(i))) return delegate.getObject(i);
        }
        // Join traversal
        String primary = aliasToTable.values().stream().findFirst().orElse(null);
        if (primary != null && !t.equalsIgnoreCase(primary)) {
            Object pPK = resolvePk(primary);
            if (pPK != null) {
                Object joinedPK = resolvePkAcrossJoin(primary, pPK.toString(), t);
                if (joinedPK != null) return joinedPK;
            }
        }
        // Brute force
        for (int i = 1; i <= delegate.getMetaData().getColumnCount(); i++) {
            String colT = indexToTable.get(i);
            if (colT == null || t.equalsIgnoreCase(colT)) {
                Object v = delegate.getObject(i);
                if (v != null) {
                    String colName = indexToCol.get(i);
                    if (colName == null) colName = delegate.getMetaData().getColumnLabel(i);
                    if (colName != null && !colName.contains("(")) {
                        try (PreparedStatement ps = delegate.getStatement().getConnection().prepareStatement(
                                "SELECT " + pkCol + " FROM " + t + " WHERE " + colName + " = ?")) {
                            ps.setObject(1, v);
                            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return rs.getObject(1); }
                        } catch (Exception e) {}
                    }
                }
            }
        }
        return null;
    }

    @Override
    public Object getObject(int i) throws SQLException {
        if (delegateExhausted) return currentVirtualRow != null ? currentVirtualRow.get(indexToCol.get(i) != null ? indexToCol.get(i) : delegate.getMetaData().getColumnLabel(i).toLowerCase()) : null;
        String t = indexToTable.get(i), c = indexToCol.get(i);
        if (t != null && c != null) {
            Object pk = resolvePk(t);
            if (pk != null) {
                Map<String, Object> p = ChalkBag.get().getRow(t, pk.toString());
                if (p != null && p.containsKey(c)) return p.get(c);
            }
        }
        return delegate.getObject(i);
    }

    @Override
    public Object getObject(String l) throws SQLException {
        Integer i = labelToIndex.get(l.toLowerCase());
        return i != null ? getObject(i) : delegate.getObject(l);
    }

    @Override public String getString(int i) throws SQLException { Object v = getObject(i); return v == null ? null : v.toString(); }
    @Override public String getString(String l) throws SQLException { Object v = getObject(l); return v == null ? null : v.toString(); }
    @Override public boolean getBoolean(int i) throws SQLException { Object v = getObject(i); return v != null && (v instanceof Boolean ? (Boolean)v : Boolean.parseBoolean(v.toString())); }
    @Override public boolean getBoolean(String l) throws SQLException { Object v = getObject(l); return v != null && (v instanceof Boolean ? (Boolean)v : Boolean.parseBoolean(v.toString())); }
    @Override public int getInt(int i) throws SQLException { Object v = getObject(i); return v == null ? 0 : ((Number)v).intValue(); }
    @Override public int getInt(String l) throws SQLException { Object v = getObject(l); return v == null ? 0 : ((Number)v).intValue(); }
    @Override public long getLong(int i) throws SQLException { Object v = getObject(i); return v == null ? 0 : ((Number)v).longValue(); }
    @Override public long getLong(String l) throws SQLException { Object v = getObject(l); return v == null ? 0 : ((Number)v).longValue(); }
    @Override public double getDouble(int i) throws SQLException { Object v = getObject(i); return v == null ? 0 : ((Number)v).doubleValue(); }
    @Override public double getDouble(String l) throws SQLException { Object v = getObject(l); return v == null ? 0 : ((Number)v).doubleValue(); }
    @Override public boolean wasNull() throws SQLException { return delegateExhausted ? false : delegate.wasNull(); }
}
