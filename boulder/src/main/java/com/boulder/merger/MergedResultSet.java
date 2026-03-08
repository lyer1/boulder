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
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
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

    private boolean isAggregate = false;
    private Object aggregateResult = null;
    private boolean aggregateReturned = false;

    private boolean isSorted = false;
    private List<Map<String, Object>> sortedRows = null;
    private int sortedRowIndex = -1;

    private boolean isDistinct = false;
    private Set<String> seenRows = new HashSet<>();

    private List<SortInfo> sortInfoList = new ArrayList<>();

    private static class SortInfo {
        String table, col;
        boolean asc;
        SortInfo(String t, String c, boolean a) { table = t; col = c; asc = a; }
    }

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
                if (ps.getDistinct() != null) isDistinct = true;
                resolveTables(ps);
                resolveJoins(ps);
                mapSelects(ps);
                extractFilters(ps.getWhere());
                extractOrderBy(ps);
            }
        } catch (Exception e) {}
    }

    private String clean(String s) {
        if (s == null) return null;
        return s.replace("`", "").replace("\"", "").replace("[", "").replace("]", "").toLowerCase();
    }

    private void extractOrderBy(PlainSelect ps) {
        if (ps.getOrderByElements() != null) {
            for (net.sf.jsqlparser.statement.select.OrderByElement el : ps.getOrderByElements()) {
                if (el.getExpression() instanceof net.sf.jsqlparser.schema.Column) {
                    net.sf.jsqlparser.schema.Column col = (net.sf.jsqlparser.schema.Column) el.getExpression();
                    String tName = col.getTable() != null ? clean(col.getTable().getName()) : null;
                    if (tName != null && aliasToTable.containsKey(tName)) tName = aliasToTable.get(tName);
                    else if (tName == null) tName = aliasToTable.values().stream().findFirst().orElse(null);
                    if (tName != null) { sortInfoList.add(new SortInfo(tName, clean(col.getColumnName()), el.isAsc())); isSorted = true; }
                }
            }
        }
    }

    private void resolveTables(PlainSelect ps) {
        Map<String, String> newAliasToTable = new java.util.LinkedHashMap<>();
        if (ps.getFromItem() instanceof Table) {
            Table t = (Table) ps.getFromItem();
            String name = clean(t.getName());
            newAliasToTable.put((t.getAlias() != null ? clean(t.getAlias().getName()) : name), name);
        }
        if (ps.getJoins() != null) {
            for (Join j : ps.getJoins()) {
                if (j.getRightItem() instanceof Table) {
                    Table t = (Table) j.getRightItem();
                    String name = clean(t.getName());
                    newAliasToTable.put((t.getAlias() != null ? clean(t.getAlias().getName()) : name), name);
                }
            }
        }
        this.aliasToTable = newAliasToTable;
    }

    private void resolveJoins(PlainSelect ps) {
        if (ps.getJoins() != null) {
            for (Join j : ps.getJoins()) {
                if (j.getOnExpression() != null) extractJoinInfo(j.getOnExpression());
                else if (j.getOnExpressions() != null) for (Expression e : j.getOnExpressions()) extractJoinInfo(e);
            }
        }
    }

    private void extractJoinInfo(Expression e) {
        if (e instanceof Parenthesis) extractJoinInfo(((Parenthesis) e).getExpression());
        else if (e instanceof AndExpression) { extractJoinInfo(((AndExpression) e).getLeftExpression()); extractJoinInfo(((AndExpression) e).getRightExpression()); }
        else if (e instanceof EqualsTo) {
            EqualsTo eq = (EqualsTo) e;
            if (eq.getLeftExpression() instanceof Column && eq.getRightExpression() instanceof Column) {
                Column l = (Column) eq.getLeftExpression(), r = (Column) eq.getRightExpression();
                JoinInfo ji = new JoinInfo();
                ji.leftTable = resolveAlias(l.getTable() != null ? l.getTable().getName() : null);
                ji.leftCol = clean(l.getColumnName());
                ji.rightTable = resolveAlias(r.getTable() != null ? r.getTable().getName() : null);
                ji.rightCol = clean(r.getColumnName());
                joins.add(ji);
            }
        }
    }

    private String resolveAlias(String a) {
        if (a == null) return aliasToTable.values().stream().findFirst().orElse(null);
        String cleanedA = clean(a);
        String t = aliasToTable.get(cleanedA);
        return t != null ? t : cleanedA;
    }

    private void mapSelects(PlainSelect ps) {
        int i = 1;
        for (SelectItem item : ps.getSelectItems()) {
            if (item instanceof SelectExpressionItem) {
                Expression e = ((SelectExpressionItem) item).getExpression();
                if (e instanceof net.sf.jsqlparser.expression.Function) { if ("count".equalsIgnoreCase(((net.sf.jsqlparser.expression.Function) e).getName())) isAggregate = true; }
                else if (e instanceof Column) {
                    Column c = (Column) e;
                    indexToTable.put(i, resolveAlias(c.getTable() != null ? c.getTable().getName() : null));
                    indexToCol.put(i, clean(c.getColumnName()));
                }
            }
            i++;
        }
    }

    private void extractFilters(Expression where) {
        if (where == null) return;
        if (where instanceof Parenthesis) extractFilters(((Parenthesis) where).getExpression());
        else if (where instanceof AndExpression) { extractFilters(((AndExpression) where).getLeftExpression()); extractFilters(((AndExpression) where).getRightExpression()); }
        else if (where instanceof EqualsTo) {
            EqualsTo eq = (EqualsTo) where;
            if (eq.getLeftExpression() instanceof Column) {
                Column l = (Column) eq.getLeftExpression();
                String col = clean(l.getColumnName()), tName = l.getTable() != null ? resolveAlias(l.getTable().getName()) : null;
                String key = (tName != null) ? tName + "." + col : col;
                Object v = null; Expression r = eq.getRightExpression();
                if (r instanceof LongValue) v = ((LongValue) r).getValue();
                else if (r instanceof StringValue) v = ((StringValue) r).getValue();
                else if (r instanceof DoubleValue) v = ((DoubleValue) r).getValue();
                else if (r instanceof JdbcParameter) v = statementParameters.get(((JdbcParameter) r).getIndex() != null ? ((JdbcParameter) r).getIndex() : 1);
                else { String s = r.toString().toLowerCase(); if (s.equals("true")) v = true; else if (s.equals("false")) v = false; }
                if (v != null) whereFilters.put(key, v);
            }
        }
    }

    private void init() throws SQLException {
        if (inited) return; inited = true;
        ResultSetMetaData m = delegate.getMetaData();
        for (int i = 1; i <= m.getColumnCount(); i++) labelToIndex.put(clean(m.getColumnLabel(i)), i);
        DatabaseMetaData dbm = delegate.getStatement().getConnection().getMetaData();
        for (String t : aliasToTable.values()) { try (ResultSet rs = dbm.getPrimaryKeys(null, null, t.toUpperCase())) { if (rs.next()) tableToPkCol.put(t, clean(rs.getString("COLUMN_NAME"))); } catch (Exception e) {} }
        loadVirtual();
    }

    private void loadVirtual() throws SQLException {
        String primary = aliasToTable.values().stream().findFirst().orElse(null);
        if (primary == null) return;
        System.out.println("[DEBUG] loadVirtual() starting. Primary table: " + primary + ", aliasToTable: " + aliasToTable);
        System.out.println("[DEBUG] whereFilters: " + whereFilters);

        Set<String> primaryPKs = new HashSet<>();
        for (String t : aliasToTable.values()) {
            Map<String, Map<String, Object>> state = ChalkBag.get().getTable(t);
            if (state == null) continue;
            for (Map.Entry<String, Map<String, Object>> e : state.entrySet()) {
                if (e.getValue() == null) continue;
                Map<String, Object> fullRow = buildFullVirtualRow(t, e.getKey());
                System.out.println("[DEBUG] Initial fullRow for " + t + ":" + e.getKey() + " -> " + fullRow);
                if (matchesAllFilters(fullRow)) {
                    System.out.println("[DEBUG] matchesAllFilters passed for " + t + ":" + e.getKey());
                    if (t.equalsIgnoreCase(primary)) primaryPKs.add(e.getKey());
                    else {
                        resolvePrimaryPKsFromJoinedPK(t, e.getKey(), primary, primaryPKs);
                        System.out.println("[DEBUG] Resolved primaryPKs from joined PK: " + primaryPKs);
                    }
                } else {
                    System.out.println("[DEBUG] matchesAllFilters FAILED for " + t + ":" + e.getKey());
                }
            }
        }
        System.out.println("[DEBUG] Final primaryPKs to consider: " + primaryPKs);
        for (String pk : primaryPKs) {
            if (!ChalkBag.get().isTombstoned(primary, pk)) {
                Map<String, Object> fullRow = buildFullVirtualRow(primary, pk);
                System.out.println("[DEBUG] Final fullRow for primary " + pk + " -> " + fullRow);
                if (matchesAllFilters(fullRow)) {
                    virtualRows.add(fullRow);
                    System.out.println("[DEBUG] Added to virtualRows: " + fullRow);
                }
            }
        }
    }

    private void resolvePrimaryPKsFromJoinedPK(String joinedT, String joinedPK, String primaryT, Set<String> res) { resolvePrimaryPKsRecursive(joinedT, joinedPK, primaryT, res, new HashSet<>()); }

    private void resolvePrimaryPKsRecursive(String currentT, String currentPK, String targetT, Set<String> res, Set<String> visited) {
        if (currentT.equalsIgnoreCase(targetT)) { res.add(currentPK); return; }
        String stateKey = currentT + ":" + currentPK; if (visited.contains(stateKey)) return; visited.add(stateKey);
        for (JoinInfo ji : joins) {
            String nextT = null, currentJoinCol = null, nextJoinCol = null;
            if (currentT.equalsIgnoreCase(ji.rightTable)) { nextT = ji.leftTable; currentJoinCol = ji.rightCol; nextJoinCol = ji.leftCol; }
            else if (currentT.equalsIgnoreCase(ji.leftTable)) { nextT = ji.rightTable; currentJoinCol = ji.leftCol; nextJoinCol = ji.rightCol; }
            if (nextT != null) { Object linkVal = getRowColValue(currentT, currentPK, currentJoinCol); if (linkVal != null) for (String npk : findAllPKsMatching(nextT, nextJoinCol, linkVal)) resolvePrimaryPKsRecursive(nextT, npk, targetT, res, visited); }
        }
    }

    private Object getRowColValue(String t, String pk, String col) {
        Map<String, Object> vRow = ChalkBag.get().getRow(t, pk); if (vRow != null && vRow.containsKey(col)) return vRow.get(col);
        String pkCol = tableToPkCol.get(t); if (pkCol == null) pkCol = "id"; if (col.equalsIgnoreCase(pkCol) || col.equalsIgnoreCase("id")) return pk;
        return fetchPhys(t, pk, col);
    }

    private List<String> findAllPKsMatching(String t, String col, Object val) {
        List<String> res = new ArrayList<>(); Map<String, Map<String, Object>> state = ChalkBag.get().getTable(t);
        if (state != null) for (Map.Entry<String, Map<String, Object>> e : state.entrySet()) {
            if (e.getValue() == null) continue; Object v = e.getValue().get(col);
            if (v == null && (col.equalsIgnoreCase("id") || col.equalsIgnoreCase(tableToPkCol.get(t)))) v = e.getKey();
            if (v != null && v.toString().equals(val.toString())) res.add(e.getKey());
        }
        String pkCol = tableToPkCol.get(t); if (pkCol == null) pkCol = "id";
        try (PreparedStatement ps = delegate.getStatement().getConnection().prepareStatement("SELECT " + pkCol + " FROM " + t + " WHERE " + col + " = ?")) {
            ps.setObject(1, val); try (ResultSet rs = ps.executeQuery()) { while (rs.next()) { String pk = rs.getString(1); if (!ChalkBag.get().isTombstoned(t, pk)) res.add(pk); } }
        } catch (Exception e) {}
        return res;
    }

    private Map<String, Object> buildFullVirtualRow(String table, String pk) throws SQLException {
        Map<String, Object> row = new HashMap<>(); row.put(table + ".__pk", pk);
        for (String t : aliasToTable.values()) {
            Object pkOfT = resolvePkAcrossJoinRecursive(table, pk, t, new HashSet<>());
            if (pkOfT != null) {
                row.put(t + ".__pk", pkOfT.toString());
                Map<String, Object> p = ChalkBag.get().getRow(t, pkOfT.toString());
                if (p != null) for (Map.Entry<String, Object> ent : p.entrySet()) row.put(t + "." + ent.getKey(), ent.getValue());
                ResultSetMetaData m = delegate.getMetaData();
                for (int i = 1; i <= m.getColumnCount(); i++) if (t.equalsIgnoreCase(indexToTable.get(i))) { String c = indexToCol.get(i); if (c != null && !row.containsKey(t + "." + c)) { Object val = fetchPhys(t, pkOfT.toString(), c); if (val != null) row.put(t + "." + c, val); } }
            }
        }
        for (String fKey : whereFilters.keySet()) if (!row.containsKey(fKey)) {
            String t = fKey.contains(".") ? fKey.substring(0, fKey.indexOf('.')) : table, c = fKey.contains(".") ? fKey.substring(fKey.indexOf('.') + 1) : fKey;
            Object pkOfT = resolvePkAcrossJoinRecursive(table, pk, t, new HashSet<>());
            if (pkOfT != null) { Object val = getRowColValue(t, pkOfT.toString(), c); if (val != null) row.put(fKey, val); }
        }
        return row;
    }

    private Object resolvePkAcrossJoinRecursive(String fromT, String fromPK, String toT, Set<String> visited) {
        if (fromT.equalsIgnoreCase(toT)) return fromPK;
        String stateKey = fromT + ":" + fromPK; if (visited.contains(stateKey)) return null; visited.add(stateKey);
        for (JoinInfo ji : joins) {
            String nextT = null, currentJoinCol = null, nextJoinCol = null;
            if (fromT.equalsIgnoreCase(ji.leftTable)) { nextT = ji.rightTable; currentJoinCol = ji.leftCol; nextJoinCol = ji.rightCol; }
            else if (fromT.equalsIgnoreCase(ji.rightTable)) { nextT = ji.leftTable; currentJoinCol = ji.rightCol; nextJoinCol = ji.leftCol; }
            if (nextT != null) {
                Object linkVal = getRowColValue(fromT, fromPK, currentJoinCol);
                if (linkVal != null) {
                    for (String npk : findAllPKsMatching(nextT, nextJoinCol, linkVal)) { Object finalPK = resolvePkAcrossJoinRecursive(nextT, npk, toT, visited); if (finalPK != null) return finalPK; }
                    String nextPkCol = tableToPkCol.get(nextT); if (nextPkCol == null) nextPkCol = "id";
                    if (nextJoinCol.equalsIgnoreCase(nextPkCol) || nextJoinCol.equalsIgnoreCase("id")) { Object finalPK = resolvePkAcrossJoinRecursive(nextT, linkVal.toString(), toT, visited); if (finalPK != null) return finalPK; }
                }
            }
        }
        return null;
    }

    private Object fetchPhys(String t, String pk, String c) {
        String pkCol = tableToPkCol.get(t); if (pkCol == null) pkCol = "id";
        try (PreparedStatement ps = delegate.getStatement().getConnection().prepareStatement("SELECT " + c + " FROM " + t + " WHERE " + pkCol + " = ?")) { ps.setObject(1, pk); try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return rs.getObject(1); } } catch (Exception e) {}
        return null;
    }

    private boolean matchesAllFilters(Map<String, Object> row) {
        for (Map.Entry<String, Object> f : whereFilters.entrySet()) {
            String fKey = f.getKey(); Object v = row.get(fKey);
            if (v == null && !fKey.contains(".")) for (String t : aliasToTable.values()) { v = row.get(t + "." + fKey); if (v != null) break; }
            if (v == null && (fKey.endsWith(".id") || fKey.endsWith(".userid") || fKey.endsWith(".employeeid") || fKey.endsWith(".organizationid") || fKey.endsWith(".departmentid"))) { String t = fKey.substring(0, fKey.indexOf('.')); v = row.get(t + ".__pk"); }
            if (v == null && f.getValue() != null) return false;
            if (v != null && f.getValue() != null) { if (v instanceof Number && f.getValue() instanceof Number) { if (((Number) v).doubleValue() != ((Number) f.getValue()).doubleValue()) return false; } else if (!v.toString().trim().equalsIgnoreCase(f.getValue().toString().trim())) return false; }
        }
        return true;
    }

    private String getRowKey() throws SQLException {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= delegate.getMetaData().getColumnCount(); i++) {
            Object v = getObject(i);
            sb.append(v == null ? "NULL" : v.toString()).append("|");
        }
        return sb.toString();
    }

    @Override
    public boolean next() throws SQLException {
        init();
        if (isAggregate) {
            if (aggregateReturned) return false; long val = 0;
            if (delegate.next()) { val = ((Number)delegate.getObject(1)).longValue(); while (delegate.next()) {} }
            delegateExhausted = true; String primary = aliasToTable.values().stream().findFirst().orElse(null);
            if (primary != null) { Map<String, Map<String, Object>> state = ChalkBag.get().getTable(primary); if (state != null) for (Map.Entry<String, Map<String, Object>> e : state.entrySet()) { if (e.getValue() == null) val--; else if (matchesAllFilters(buildFullVirtualRow(primary, e.getKey())) && !seenPrimaryPKs.contains(e.getKey())) val++; } }
            aggregateResult = val; aggregateReturned = true; return true;
        }
        if (isSorted) {
            if (sortedRows == null) {
                sortedRows = new ArrayList<>();
                while (delegate.next()) {
                    boolean skip = false; for (String t : aliasToTable.values()) { Object pk = resolvePk(t); if (pk != null && ChalkBag.get().isTombstoned(t, pk.toString())) { skip = true; break; } }
                    if (skip) continue; String primary = aliasToTable.values().stream().findFirst().orElse(null); if (primary != null) { Object pk = resolvePk(primary); if (pk != null) seenPrimaryPKs.add(pk.toString()); }
                    if (checkFiltersSkip()) continue;
                    Map<String, Object> row = new HashMap<>();
                    for (int i = 1; i <= delegate.getMetaData().getColumnCount(); i++) { String t = indexToTable.get(i), c = indexToCol.get(i) != null ? indexToCol.get(i) : clean(delegate.getMetaData().getColumnLabel(i)); Object v = delegate.getObject(i); if (t != null) { Object pk = resolvePk(t); if (pk != null) { Map<String, Object> p = ChalkBag.get().getRow(t, pk.toString()); if (p != null && p.containsKey(c)) v = p.get(c); row.put(t + "." + c, v); } } row.put(c, v); }
                    sortedRows.add(row);
                }
                for (Map<String, Object> vr : virtualRows) { String primary = aliasToTable.values().stream().findFirst().orElse(null); if (!seenPrimaryPKs.contains(vr.get(primary + ".__pk"))) sortedRows.add(vr); }
                sortedRows.sort((r1, r2) -> { for (SortInfo si : sortInfoList) { Object v1 = r1.get(si.table + "." + si.col), v2 = r2.get(si.table + "." + si.col); if (v1 == null) v1 = r1.get(si.col); if (v2 == null) v2 = r2.get(si.col); if (v1 == null && v2 == null) continue; if (v1 == null) return si.asc ? -1 : 1; if (v2 == null) return si.asc ? 1 : -1; int cmp = (v1 instanceof Comparable && v2 instanceof Comparable) ? ((Comparable)v1).compareTo(v2) : v1.toString().compareTo(v2.toString()); if (cmp != 0) return si.asc ? cmp : -cmp; } return 0; });
            }
            while (true) {
                sortedRowIndex++; if (sortedRowIndex < sortedRows.size()) { currentVirtualRow = sortedRows.get(sortedRowIndex); delegateExhausted = true; if (isDistinct) { String key = getRowKey(); if (seenRows.contains(key)) continue; seenRows.add(key); } return true; } return false;
            }
        }
        if (delegateExhausted) return nextV();
        while (delegate.next()) {
            boolean skip = false; for (String t : aliasToTable.values()) { Object pk = resolvePk(t); if (pk != null && ChalkBag.get().isTombstoned(t, pk.toString())) { skip = true; break; } }
            if (skip) continue; String primary = aliasToTable.values().stream().findFirst().orElse(null); if (primary != null) { Object pk = resolvePk(primary); if (pk != null) seenPrimaryPKs.add(pk.toString()); }
            if (checkFiltersSkip()) continue; 
            if (isDistinct) { String key = getRowKey(); if (seenRows.contains(key)) continue; seenRows.add(key); }
            return true;
        }
        delegateExhausted = true; virtualRows.removeIf(r -> { String primary = aliasToTable.values().stream().findFirst().orElse(null); return seenPrimaryPKs.contains(r.get(primary + ".__pk")); }); return nextV();
    }

    private boolean checkFiltersSkip() throws SQLException {
        for (Map.Entry<String, Object> f : whereFilters.entrySet()) {
            String fKey = f.getKey(), tName = fKey.contains(".") ? fKey.substring(0, fKey.indexOf('.')) : null, colName = fKey.contains(".") ? fKey.substring(fKey.indexOf('.') + 1) : fKey;
            for (int i = 1; i <= delegate.getMetaData().getColumnCount(); i++) {
                if (colName.equalsIgnoreCase(indexToCol.get(i)) && (tName == null || tName.equalsIgnoreCase(indexToTable.get(i)))) { 
                    Object v = delegate.getObject(i);
                    Object pk = resolvePk(indexToTable.get(i)); 
                    if (pk != null) { 
                        Map<String, Object> p = ChalkBag.get().getRow(indexToTable.get(i), pk.toString()); 
                        if (p != null && p.containsKey(colName)) v = p.get(colName); 
                    } 
                    if (v == null && f.getValue() != null) return true; 
                    if (v != null && f.getValue() != null) { 
                        if (v instanceof Number && f.getValue() instanceof Number) { 
                            if (((Number) v).doubleValue() != ((Number) f.getValue()).doubleValue()) return true; 
                        } else if (!v.toString().trim().equalsIgnoreCase(f.getValue().toString().trim())) {
                            return true; 
                        }
                    } 
                }
            }
        }
        return false;
    }

    private boolean nextV() throws SQLException {
        while (true) {
            virtualRowIndex++;
            if (virtualRowIndex < virtualRows.size()) {
                currentVirtualRow = virtualRows.get(virtualRowIndex);
                if (isDistinct) { String key = getRowKey(); if (seenRows.contains(key)) continue; seenRows.add(key); }
                return true;
            }
            return false;
        }
    }

    private void resolveAllPkAcrossJoinRecursive(String fromT, String fromPK, String toT, Set<String> visited, List<Object> results) {
        if (fromT.equalsIgnoreCase(toT)) { results.add(fromPK); return; }
        String stateKey = fromT + ":" + fromPK; if (visited.contains(stateKey)) return; visited.add(stateKey);
        for (JoinInfo ji : joins) {
            String nextT = null, currentJoinCol = null, nextJoinCol = null;
            if (fromT.equalsIgnoreCase(ji.leftTable)) { nextT = ji.rightTable; currentJoinCol = ji.leftCol; nextJoinCol = ji.rightCol; }
            else if (fromT.equalsIgnoreCase(ji.rightTable)) { nextT = ji.leftTable; currentJoinCol = ji.rightCol; nextJoinCol = ji.leftCol; }
            if (nextT != null) {
                Object linkVal = getRowColValue(fromT, fromPK, currentJoinCol);
                if (linkVal != null) {
                    for (String npk : findAllPKsMatching(nextT, nextJoinCol, linkVal)) { resolveAllPkAcrossJoinRecursive(nextT, npk, toT, new HashSet<>(visited), results); }
                    String nextPkCol = tableToPkCol.get(nextT); if (nextPkCol == null) nextPkCol = "id";
                    if (nextJoinCol.equalsIgnoreCase(nextPkCol) || nextJoinCol.equalsIgnoreCase("id")) { resolveAllPkAcrossJoinRecursive(nextT, linkVal.toString(), toT, new HashSet<>(visited), results); }
                }
            }
        }
    }

    private Object resolvePk(String t) throws SQLException {
        String pkCol = tableToPkCol.get(t); if (pkCol == null) pkCol = "id";
        for (int i = 1; i <= delegate.getMetaData().getColumnCount(); i++) if (t.equalsIgnoreCase(indexToTable.get(i)) && pkCol.equalsIgnoreCase(indexToCol.get(i))) return delegate.getObject(i);
        
        String primary = aliasToTable.values().stream().findFirst().orElse(null);
        if (primary != null && !t.equalsIgnoreCase(primary)) { 
            Object pPK = resolvePk(primary); 
            if (pPK != null) { 
                List<Object> joinedPKs = new ArrayList<>();
                resolveAllPkAcrossJoinRecursive(primary, pPK.toString(), t, new HashSet<>(), joinedPKs); 
                if (!joinedPKs.isEmpty()) {
                    if (joinedPKs.size() == 1) return joinedPKs.get(0);
                    for (Object possiblePk : joinedPKs) {
                        boolean matches = true;
                        for (int i = 1; i <= delegate.getMetaData().getColumnCount(); i++) {
                            if (t.equalsIgnoreCase(indexToTable.get(i))) {
                                String colName = indexToCol.get(i);
                                if (colName == null) colName = clean(delegate.getMetaData().getColumnLabel(i));
                                Object delegateVal = delegate.getObject(i);
                                Object actualVal = getRowColValue(t, possiblePk.toString(), colName);
                                if (delegateVal != null && actualVal != null) {
                                    if (delegateVal instanceof Number && actualVal instanceof Number) {
                                        if (((Number) delegateVal).doubleValue() != ((Number) actualVal).doubleValue()) matches = false;
                                    } else if (!delegateVal.toString().trim().equalsIgnoreCase(actualVal.toString().trim())) {
                                        matches = false;
                                    }
                                }
                            }
                        }
                        if (matches) return possiblePk;
                    }
                    return joinedPKs.get(0);
                }
            } 
        }

        for (int i = 1; i <= delegate.getMetaData().getColumnCount(); i++) { String colT = indexToTable.get(i); if (colT == null || t.equalsIgnoreCase(colT)) { Object v = delegate.getObject(i); if (v != null) { String colName = indexToCol.get(i); if (colName == null) colName = clean(delegate.getMetaData().getColumnLabel(i)); if (colName != null && !colName.contains("(")) { try (PreparedStatement ps = delegate.getStatement().getConnection().prepareStatement("SELECT " + pkCol + " FROM " + t + " WHERE " + colName + " = ?")) { ps.setObject(1, v); try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return rs.getObject(1); } } catch (Exception e) {} } } } }
        return null;
    }

    @Override
    public Object getObject(int i) throws SQLException {
        if (isAggregate) return i == 1 ? aggregateResult : null;
        if (delegateExhausted) {
            if (currentVirtualRow == null) return null;
            String colName = indexToCol.get(i), t = indexToTable.get(i);
            if (currentVirtualRow.containsKey(t + "." + colName)) return currentVirtualRow.get(t + "." + colName);
            if (currentVirtualRow.containsKey(colName)) return currentVirtualRow.get(colName);
            if (colName.equalsIgnoreCase(tableToPkCol.get(t)) || colName.equalsIgnoreCase("id") || colName.endsWith("id")) { Object pkVal = currentVirtualRow.get(t + ".__pk"); if (pkVal != null) return pkVal; }
            return null;
        }
        String t = indexToTable.get(i), c = indexToCol.get(i);
        if (t != null && c != null) { Object pk = resolvePk(t); if (pk != null) { Map<String, Object> p = ChalkBag.get().getRow(t, pk.toString()); if (p != null && p.containsKey(c)) return p.get(c); } }
        return delegate.getObject(i);
    }

    @Override public Object getObject(String l) throws SQLException { if (isAggregate) return aggregateResult; Integer i = labelToIndex.get(clean(l)); return i != null ? getObject(i) : delegate.getObject(l); }
    @Override public String getString(int i) throws SQLException { Object v = getObject(i); return v == null ? null : v.toString(); }
    @Override public String getString(String l) throws SQLException { Object v = getObject(l); return v == null ? null : v.toString(); }
    @Override public boolean getBoolean(int i) throws SQLException { Object v = getObject(i); return v != null && (v instanceof Boolean ? (Boolean)v : Boolean.parseBoolean(v.toString())); }
    @Override public boolean getBoolean(String l) throws SQLException { Object v = getObject(l); return v != null && (v instanceof Boolean ? (Boolean)v : Boolean.parseBoolean(v.toString())); }
    @Override public int getInt(int i) throws SQLException { Object v = getObject(i); return v == null ? 0 : (v instanceof Number ? ((Number)v).intValue() : (int)Double.parseDouble(v.toString())); }
    @Override public int getInt(String l) throws SQLException { Object v = getObject(l); return v == null ? 0 : (v instanceof Number ? ((Number)v).intValue() : (int)Double.parseDouble(v.toString())); }
    @Override public long getLong(int i) throws SQLException { Object v = getObject(i); return v == null ? 0 : (v instanceof Number ? ((Number)v).longValue() : (long)Double.parseDouble(v.toString())); }
    @Override public long getLong(String l) throws SQLException { Object v = getObject(l); return v == null ? 0 : (v instanceof Number ? ((Number)v).longValue() : (long)Double.parseDouble(v.toString())); }
    @Override public double getDouble(int i) throws SQLException { Object v = getObject(i); return v == null ? 0 : (v instanceof Number ? ((Number)v).doubleValue() : Double.parseDouble(v.toString())); }
    @Override public double getDouble(String l) throws SQLException { Object v = getObject(l); return v == null ? 0 : (v instanceof Number ? ((Number)v).doubleValue() : Double.parseDouble(v.toString())); }
    @Override public boolean wasNull() throws SQLException { return delegateExhausted ? false : delegate.wasNull(); }
    @Override public boolean isLast() throws SQLException { return isAggregate ? aggregateReturned : super.isLast(); }
}
