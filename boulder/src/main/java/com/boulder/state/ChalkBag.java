package com.boulder.state;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class ChalkBag {

    private static final ThreadLocal<ChalkBag> INSTANCE = ThreadLocal.withInitial(ChalkBag::new);

    public static ChalkBag get() {
        return INSTANCE.get();
    }

    public static void clear() {
        INSTANCE.remove();
    }

    // TableName -> PrimaryKey -> ColumnName -> Value
    private final Map<String, Map<String, Map<String, Object>>> state = new HashMap<>();

    // Auto-increment counter for generated keys (starting high to avoid physical db conflicts)
    private final AtomicInteger idGenerator = new AtomicInteger(1000000);

    public static final Object TOMBSTONE = new Object();

    public int generateId() {
        return idGenerator.incrementAndGet();
    }

    public void insert(String table, String pk, Map<String, Object> values) {
        table = table.toLowerCase();
        state.computeIfAbsent(table, k -> new HashMap<>())
             .put(pk, new HashMap<>(values));
    }

    public void update(String table, String pk, Map<String, Object> values) {
        table = table.toLowerCase();
        Map<String, Object> row = state.computeIfAbsent(table, k -> new HashMap<>())
             .computeIfAbsent(pk, k -> new HashMap<>());

        if (row != null && row != TOMBSTONE) {
            row.putAll(values);
        }
    }

    public void delete(String table, String pk) {
        table = table.toLowerCase();
        state.computeIfAbsent(table, k -> new HashMap<>())
             .put(pk, null); // null indicates tombstone
    }

    public Map<String, Object> getRow(String table, String pk) {
        table = table.toLowerCase();
        Map<String, Map<String, Object>> tableState = state.get(table);
        if (tableState != null) {
            return tableState.get(pk);
        }
        return null;
    }

    public boolean isTombstoned(String table, String pk) {
        table = table.toLowerCase();
        Map<String, Map<String, Object>> tableState = state.get(table);
        if (tableState != null) {
            if (tableState.containsKey(pk) && tableState.get(pk) == null) {
                return true;
            }
            if (pk.endsWith(".0")) {
                String altPk = pk.substring(0, pk.length() - 2);
                if (tableState.containsKey(altPk) && tableState.get(altPk) == null) {
                    return true;
                }
            } else {
                String altPk = pk + ".0";
                if (tableState.containsKey(altPk) && tableState.get(altPk) == null) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean hasDeletes(String table) {
        Map<String, Map<String, Object>> tableState = state.get(table.toLowerCase());
        if (tableState != null) {
            for (Map.Entry<String, Map<String, Object>> entry : tableState.entrySet()) {
                if (entry.getValue() == null) return true;
            }
        }
        return false;
    }

    public Map<String, Map<String, Object>> getTable(String table) {
        return state.get(table.toLowerCase());
    }

    public Map<String, Map<String, Map<String, Object>>> getAllState() {
        return state;
    }
}
