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

    private boolean isAggregate = false;
    private Object aggregateResult = null;
    private boolean aggregateReturned = false;

    private boolean isSorted = false;
    private List<Map<String, Object>> sortedRows = null;
    private int sortedRowIndex = -1;

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
                resolveTables(ps);
                resolveJoins(ps);
                mapSelects(ps);
                extractFilters(ps.getWhere());
                extractOrderBy(ps);
            }
        } catch (Exception e) {}
    }

    private void extractOrderBy(PlainSelect ps) {
        if (ps.getOrderByElements() != null) {
            for (net.sf.jsqlparser.statement.select.OrderByElement el : ps.getOrderByElements()) {
                if (el.getExpression() instanceof net.sf.jsqlparser.schema.Column) {
                    net.sf.jsqlparser.schema.Column col = (net.sf.jsqlparser.schema.Column) el.getExpression();
                    String tName = col.getTable() != null ? col.getTable().getName() : null;
                    if (tName != null && aliasToTable.containsKey(tName.toLowerCase())) {
                        tName = aliasToTable.get(tName.toLowerCase());
                    } else if (tName != null && aliasToTable.containsValue(tName.toLowerCase())) {
                        tName = tName.toLowerCase();
                    } else {
                        tName = aliasToTable.values().stream().findFirst().orElse(null);
                    }
                    if (tName != null) {
                        sortInfoList.add(new SortInfo(tName, col.getColumnName().toLowerCase(), el.isAsc()));
                        isSorted = true;
                    }
                }
            }
        }
    }

    private void resolveTables(PlainSelect ps) {
        // LinkedHashMap keeps insertion order, ensuring primary table is first
        Map<String, String> newAliasToTable = new java.util.LinkedHashMap<>();
        if (ps.getFromItem() instanceof Table) {
            Table t = (Table) ps.getFromItem();
            String name = t.getName().toLowerCase();
            newAliasToTable.put((t.getAlias() != null ? t.getAlias().getName().toLowerCase() : name), name);
        }
        if (ps.getJoins() != null) {
            for (Join j : ps.getJoins()) {
                if (j.getRightItem() instanceof Table) {
                    Table t = (Table) j.getRightItem();
                    String name = t.getName().toLowerCase();
                    newAliasToTable.put((t.getAlias() != null ? t.getAlias().getName().toLowerCase() : name), name);
                }
            }
        }
        this.aliasToTable = newAliasToTable;
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
                if (e instanceof net.sf.jsqlparser.expression.Function) {
                    String funcName = ((net.sf.jsqlparser.expression.Function) e).getName();
                    if ("count".equalsIgnoreCase(funcName) || "sum".equalsIgnoreCase(funcName)) {
                        isAggregate = true;
                    }
                } else if (e instanceof Column) {
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
                    // if filter is on PK (e.g. userid=500), we must check the PK as well
                    String pkCol = tableToPkCol.get(t);
                    if (pkCol == null) pkCol = "id";
                    boolean matchedInColumns = false;
                    if (e.getValue().containsKey(filter.getKey()) || e.getValue().containsKey(filter.getKey().toLowerCase())) {
                        Object val = e.getValue().get(filter.getKey());
                        if (val == null) val = e.getValue().get(filter.getKey().toLowerCase());

                        Object filterVal = filter.getValue();
                        boolean valsMatch = false;

                        if (val != null && filterVal != null) {
                            if (val instanceof Number && filterVal instanceof Number) {
                                valsMatch = ((Number)val).doubleValue() == ((Number)filterVal).doubleValue();
                            } else if (val instanceof String && filterVal instanceof Number) {
                                try { valsMatch = Long.parseLong(val.toString().trim()) == ((Number)filterVal).longValue(); } catch (Exception ex) {}
                            } else if (val instanceof Number && filterVal instanceof String) {
                                try { valsMatch = ((Number)val).longValue() == Long.parseLong(filterVal.toString().trim()); } catch (Exception ex) {}
                            } else {
                                valsMatch = val.toString().trim().equalsIgnoreCase(filterVal.toString().trim());
                            }
                        }

                        if (valsMatch) {
                            matchedInColumns = true;
                        } else {
                            patchMatches = false; break;
                        }
                    }

                    if (!matchedInColumns && (filter.getKey().equalsIgnoreCase(pkCol) || filter.getKey().equalsIgnoreCase("id") || filter.getKey().endsWith("id") || filter.getKey().endsWith("_id"))) {
                         // Fallback check if it's the primary key but didn't match the inner row map
                         if (!e.getKey().equalsIgnoreCase(filter.getValue().toString())) {
                             patchMatches = false; break;
                         }
                    }
                }

                Map<String, Object> fullRow = buildFullVirtualRow(t, e.getKey());
                String tablePkCol = tableToPkCol.get(t);

                // Ensure __pk is set directly for ID resolution when no specific ID column exists
                fullRow.put("__pk", e.getKey());

                // Only inject __pk as "id" if we don't already have the id column explicitly
                if (!fullRow.containsKey(tablePkCol != null ? tablePkCol : "id")) {
                    fullRow.put(tablePkCol != null ? tablePkCol : "id", e.getKey()); // Make sure PK is in fullRow for matchesAllFilters to see
                }

                // For purely virtual rows (inserts where the DB is unaware), we shouldn't rely on `patchMatches` which binds purely to the ChalkBag Key.
                // We should strictly rely on whether the full combined row matches all WHERE filters.
                if (patchMatches || matchesAllFilters(fullRow)) {
                    if (t.equalsIgnoreCase(primary)) {
                        primaryPKs.add(e.getKey());
                    } else {
                        resolvePrimaryPKsFromJoinedPK(t, e.getKey(), primary, primaryPKs);
                    }
                }
            }
        }

        for (String pk : primaryPKs) {
            if (ChalkBag.get().isTombstoned(primary, pk)) continue;

            Map<String, Object> fullRow = buildFullVirtualRow(primary, pk);

            String tablePkCol = tableToPkCol.get(primary);

            // Ensure __pk is set directly for ID resolution when no specific ID column exists
            fullRow.put("__pk", pk);

            if (!fullRow.containsKey(tablePkCol != null ? tablePkCol : "id")) {
                fullRow.put(tablePkCol != null ? tablePkCol : "id", pk); // Make sure PK is in fullRow for matchesAllFilters to see
            }

            // Final step: ensure it matches ALL filters using standard matchesAllFilters logic.
            // If the row is purely virtual and only has a sequence id in the map, matchesAllFilters will use the id column fallbacks.
            // If the row lacks columns entirely, matchesAllFilters correctly fails it.
            if (matchesAllFilters(fullRow)) {
                virtualRows.add(fullRow);
            }
        }
    }

    private void resolvePrimaryPKsFromJoinedPK(String joinedT, String joinedPK, String primaryT, Set<String> res) {
        for (JoinInfo ji : joins) {
            if (joinedT.equalsIgnoreCase(ji.rightTable) && primaryT.equalsIgnoreCase(ji.leftTable)) {
                String pkCol = tableToPkCol.get(primaryT);
                if (pkCol == null) pkCol = "id";

                Map<String, Map<String, Object>> primaryState = ChalkBag.get().getTable(primaryT);
                if (primaryState != null) {
                    for (Map.Entry<String, Map<String, Object>> e : primaryState.entrySet()) {
                        if (e.getValue() != null && e.getValue().containsKey(ji.leftCol)) {
                            if (e.getValue().get(ji.leftCol).toString().equals(joinedPK)) {
                                res.add(e.getKey());
                            }
                        }
                    }
                }

                try (PreparedStatement ps = delegate.getStatement().getConnection().prepareStatement(
                        "SELECT " + pkCol + " FROM " + primaryT + " WHERE " + ji.leftCol + " = ?")) {
                    ps.setObject(1, joinedPK);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) res.add(rs.getString(1));
                    }
                } catch (Exception e) { e.printStackTrace(); }
            } else if (joinedT.equalsIgnoreCase(ji.leftTable) && primaryT.equalsIgnoreCase(ji.rightTable)) {
                String pkCol = tableToPkCol.get(primaryT);
                if (pkCol == null) pkCol = "id";

                Map<String, Map<String, Object>> primaryState = ChalkBag.get().getTable(primaryT);
                if (primaryState != null) {
                    for (Map.Entry<String, Map<String, Object>> e : primaryState.entrySet()) {
                        if (e.getValue() != null && e.getValue().containsKey(ji.rightCol)) {
                            if (e.getValue().get(ji.rightCol).toString().equals(joinedPK)) {
                                res.add(e.getKey());
                            }
                        }
                    }
                }

                try (PreparedStatement ps = delegate.getStatement().getConnection().prepareStatement(
                        "SELECT " + pkCol + " FROM " + primaryT + " WHERE " + ji.rightCol + " = ?")) {
                    ps.setObject(1, joinedPK);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) res.add(rs.getString(1));
                    }
                } catch (Exception e) { e.printStackTrace(); }
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

        // Also fetch columns needed by filters so matchesAllFilters doesn't fail on missing keys
        for (String t : aliasToTable.values()) {
            Object pkOfT = t.equalsIgnoreCase(table) ? pk : resolvePkAcrossJoin(table, pk, t);
            if (pkOfT != null) {
                for (String fCol : whereFilters.keySet()) {
                    if (!row.containsKey(fCol)) {
                        Object val = fetchPhys(t, pkOfT.toString(), fCol);
                        if (val != null) row.put(fCol, val);
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
            if (v == null) v = row.get(f.getKey().toLowerCase());
            if (v == null) {
                for (String key : row.keySet()) {
                    if (key.equalsIgnoreCase(f.getKey())) {
                        v = row.get(key);
                        break;
                    }
                }
            }

            // If the row doesn't contain the filter key, try to handle pure virtual inserts by checking id columns
            if (v == null) {
                if (f.getKey().equalsIgnoreCase("id") || f.getKey().endsWith("id") || f.getKey().endsWith("_id")) {
                    v = row.get("__pk");
                    if (v == null) v = row.get("id");
                }
            }

            // if missing entirely from fullRow, default to failing this check since the DB wouldn't return it
            if (v == null && f.getValue() != null) return false;

            if (v != null && f.getValue() != null) {
                try {
                    if (v instanceof Number && f.getValue() instanceof Number) {
                       if (((Number) v).doubleValue() != ((Number) f.getValue()).doubleValue()) return false;
                    } else if (v instanceof String && f.getValue() instanceof Number) {
                       try { if (Long.parseLong(v.toString().trim()) != ((Number)f.getValue()).longValue()) return false; } catch(Exception e) { return false; }
                    } else if (v instanceof Number && f.getValue() instanceof String) {
                       try { if (((Number)v).longValue() != Long.parseLong(f.getValue().toString().trim())) return false; } catch(Exception e) { return false; }
                    } else if (!v.toString().trim().equalsIgnoreCase(f.getValue().toString().trim())) {
                        return false;
                    }
                } catch (Exception e) {
                    if (!v.toString().trim().equalsIgnoreCase(f.getValue().toString().trim())) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    @Override
    public boolean next() throws SQLException {
        boolean wasInited = inited;
        init();

        if (isAggregate) {
            if (aggregateReturned) return false;
            long val = 0;

            // For aggregates we ALWAYS just consume one row from delegate and discard others if proxy is intercepting.
            // Because proxy must return exactly ONE row.
            if (delegate.next()) {
                Object physVal = delegate.getObject(1);
                val = physVal == null ? 0 : ((Number)physVal).longValue();

                // Keep consuming
                while (delegate.next()) {}
            }

            delegateExhausted = true;
            String primary = aliasToTable.values().stream().findFirst().orElse(null);
            if (primary != null) {
                Map<String, Map<String, Object>> state = ChalkBag.get().getTable(primary);
                if (state != null) {
                    for (Map.Entry<String, Map<String, Object>> e : state.entrySet()) {
                        if (e.getValue() == null) {
                            // Tombstone
                            val--;
                        } else {
                            // Insert/Update
                            if (matchesAllFilters(e.getValue())) {
                                // Check if it's a new insert
                                if (!seenPrimaryPKs.contains(e.getKey())) {
                                    val++;
                                }
                            }
                        }
                    }
                }
            }
            aggregateResult = val;
            aggregateReturned = true;

            return true;
        }

        if (isSorted) {
            if (sortedRows == null) {
                sortedRows = new ArrayList<>();
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

                    if (checkFiltersSkip()) continue;

                    Map<String, Object> row = new HashMap<>();
                    for (int i = 1; i <= delegate.getMetaData().getColumnCount(); i++) {
                        String t = indexToTable.get(i);
                        String c = indexToCol.get(i) != null ? indexToCol.get(i) : delegate.getMetaData().getColumnLabel(i).toLowerCase();
                        Object v = delegate.getObject(i);
                        if (t != null) {
                            Object pk = resolvePk(t);
                            if (pk != null) {
                                Map<String, Object> p = ChalkBag.get().getRow(t, pk.toString());
                                if (p != null && p.containsKey(c)) v = p.get(c);
                            }
                        }
                        row.put(c, v);
                    }
                    sortedRows.add(row);
                }

                if (!wasInited) {
                    virtualRows.clear();
                    loadVirtual();
                }
                for (Map<String, Object> vr : virtualRows) {
                    if (!seenPrimaryPKs.contains(vr.get("__pk"))) {
                        sortedRows.add(vr);
                    }
                }

                sortedRows.sort((r1, r2) -> {
                    for (SortInfo si : sortInfoList) {
                        Object v1 = r1.get(si.col);
                        Object v2 = r2.get(si.col);
                        if (v1 == null && v2 == null) continue;
                        if (v1 == null) return si.asc ? -1 : 1;
                        if (v2 == null) return si.asc ? 1 : -1;
                        int cmp = 0;
                        if (v1 instanceof Comparable && v2 instanceof Comparable) {
                            try {
                                cmp = ((Comparable)v1).compareTo(v2);
                            } catch (Exception e) {
                                cmp = v1.toString().compareTo(v2.toString());
                            }
                        } else {
                            cmp = v1.toString().compareTo(v2.toString());
                        }
                        if (cmp != 0) return si.asc ? cmp : -cmp;
                    }
                    return 0;
                });
            }

            sortedRowIndex++;
            if (sortedRowIndex < sortedRows.size()) {
                currentVirtualRow = sortedRows.get(sortedRowIndex);
                delegateExhausted = true; // ensure getObject reads from currentVirtualRow
                return true;
            }
            return false;
        }

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

            if (checkFiltersSkip()) continue;

            return true;
        }
        if (!delegateExhausted) {
            delegateExhausted = true;
            if (!wasInited) {
                virtualRows.clear();
                loadVirtual();
            }
            virtualRows.removeIf(r -> seenPrimaryPKs.contains(r.get("__pk")));
        }
        return nextV();
    }

    private boolean checkFiltersSkip() throws SQLException {
        boolean skip = false;
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
                    if (v == null && f.getValue() != null) { skip = true; break; }
                    if (v != null && f.getValue() != null) {
                        try {
                            if (v instanceof Number && f.getValue() instanceof Number) {
                               if (((Number) v).doubleValue() != ((Number) f.getValue()).doubleValue()) { skip = true; break; }
                            } else if (v instanceof String && f.getValue() instanceof Number) {
                               try { if (Long.parseLong(v.toString().trim()) != ((Number)f.getValue()).longValue()) { skip = true; break; } } catch(Exception e) { skip = true; break; }
                            } else if (v instanceof Number && f.getValue() instanceof String) {
                               try { if (((Number)v).longValue() != Long.parseLong(f.getValue().toString().trim())) { skip = true; break; } } catch(Exception e) { skip = true; break; }
                            } else if (!v.toString().trim().equalsIgnoreCase(f.getValue().toString().trim())) {
                                { skip = true; break; }
                            }
                        } catch (Exception e) {
                            if (!v.toString().trim().equalsIgnoreCase(f.getValue().toString().trim())) {
                                { skip = true; break; }
                            }
                        }
                    }
                }
            }
            if (skip) break;
        }
        return skip;
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
                // If the from table is virtual, try ChalkBag first!
                Map<String, Object> pRow = ChalkBag.get().getRow(fromT, fromPK);
                if (pRow != null && pRow.containsKey(ji.leftCol)) {
                    Object val = pRow.get(ji.leftCol);
                    if (val != null) return val;
                }

                // Fallback: If both are virtual, we might need to search the other table
                Map<String, Map<String, Object>> toState = ChalkBag.get().getTable(toT);
                if (toState != null) {
                    for (Map.Entry<String, Map<String, Object>> e : toState.entrySet()) {
                        if (e.getValue() != null && e.getValue().containsKey(ji.rightCol)) {
                            Object toVal = e.getValue().get(ji.rightCol);
                            if (toVal != null && toVal.toString().equals(fromPK)) return e.getKey();
                        }
                    }
                }
                return fetchPhys(fromT, fromPK, ji.leftCol);
            } else if (fromT.equalsIgnoreCase(ji.rightTable) && toT.equalsIgnoreCase(ji.leftTable)) {
                // If the from table is virtual, try ChalkBag first!
                Map<String, Object> pRow = ChalkBag.get().getRow(fromT, fromPK);
                if (pRow != null && pRow.containsKey(ji.rightCol)) {
                    Object val = pRow.get(ji.rightCol);
                    if (val != null) return val;
                }

                // Fallback: If both are virtual, we might need to search the other table
                Map<String, Map<String, Object>> toState = ChalkBag.get().getTable(toT);
                if (toState != null) {
                    for (Map.Entry<String, Map<String, Object>> e : toState.entrySet()) {
                        if (e.getValue() != null && e.getValue().containsKey(ji.leftCol)) {
                            Object toVal = e.getValue().get(ji.leftCol);
                            if (toVal != null && toVal.toString().equals(fromPK)) return e.getKey();
                        }
                    }
                }
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
        if (isAggregate) {
            if (i == 1) return aggregateResult;
            return null;
        }
        if (delegateExhausted) {
            if (currentVirtualRow == null) return null;
            String colName = indexToCol.get(i);
            if (colName == null) colName = delegate.getMetaData().getColumnLabel(i).toLowerCase();
            if (currentVirtualRow.containsKey(colName)) return currentVirtualRow.get(colName);

            // If the map doesn't contain the column by explicit name, sometimes Hibernate asks for the auto generated sequence
            // For purely virtual rows fetched without physical match, we must provide the id
            String t = indexToTable.get(i);
            if (t != null) {
                String pkCol = tableToPkCol.get(t);
                if (pkCol == null) pkCol = "id";
                if (colName.equalsIgnoreCase(pkCol) || colName.equalsIgnoreCase("id") || colName.endsWith("id") || colName.endsWith("_id")) {
                    // Try to get from real mapped __pk or id first
                    Object pkVal = currentVirtualRow.get("__pk");
                    if (pkVal == null) pkVal = currentVirtualRow.get("id");
                    if (pkVal != null) return pkVal;
                }
            }

            // Try fallback
            for (String key : currentVirtualRow.keySet()) {
                if (key.equalsIgnoreCase(colName)) return currentVirtualRow.get(key);
            }

            return null;
        }
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
        if (isAggregate) return aggregateResult;
        Integer i = labelToIndex.get(l.toLowerCase());
        return i != null ? getObject(i) : delegate.getObject(l);
    }

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
