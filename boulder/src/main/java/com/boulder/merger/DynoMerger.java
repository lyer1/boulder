package com.boulder.merger;

import com.boulder.state.ChalkBag;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.update.UpdateSet;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DynoMerger {

    public static class WriteResult {
        public List<Integer> generatedKeys = new ArrayList<>();
        public int affectedRows = 0;
    }

    public static WriteResult interceptWrite(String sql, Map<Integer, Object> parameters, Connection conn) {
        return interceptWriteWithKeys(sql, parameters, conn);
    }

    private static Object getExpressionValue(Expression expr, Map<Integer, Object> parameters, int[] paramIndexRef) {
        if (expr instanceof net.sf.jsqlparser.expression.JdbcParameter) {
            return parameters.get(paramIndexRef[0]++);
        } else if (expr instanceof LongValue) {
            return ((LongValue) expr).getValue();
        } else if (expr instanceof StringValue) {
            return ((StringValue) expr).getValue();
        } else if (expr instanceof DoubleValue) {
            return ((DoubleValue) expr).getValue();
        }
        return null;
    }

    public static WriteResult interceptWriteWithKeys(String sql, Map<Integer, Object> parameters, Connection conn) {
        WriteResult result = new WriteResult();
        try {
            Statement stmt = CCJSqlParserUtil.parse(sql);
            if (stmt instanceof Update) {
                Update update = (Update) stmt;
                String tableName = update.getTable().getName().replace("`", "").replace("\"", "");

                String pkColumnName = "id";
                if (conn != null) {
                    try (ResultSet rs = conn.getMetaData().getPrimaryKeys(null, null, tableName.toUpperCase())) {
                        if (rs.next()) {
                            pkColumnName = rs.getString("COLUMN_NAME").toLowerCase();
                        }
                    } catch (Exception e) {}
                }

                Map<String, Object> values = new HashMap<>();
                int[] paramIndexRef = {1};

                for (UpdateSet updateSet : update.getUpdateSets()) {
                    String colName = updateSet.getColumns().get(0).getColumnName().replace("`", "").replace("\"", "");
                    Expression expr = updateSet.getExpressions().get(0);
                    Object val = getExpressionValue(expr, parameters, paramIndexRef);
                    values.put(colName.toLowerCase(), val);
                }

                Expression where = update.getWhere();
                if (where instanceof EqualsTo) {
                    EqualsTo equalsTo = (EqualsTo) where;
                    if (equalsTo.getLeftExpression() instanceof Column) {
                        String colName = ((Column) equalsTo.getLeftExpression()).getColumnName().toLowerCase().replace("`", "").replace("\"", "");
                        Object filterVal = getExpressionValue(equalsTo.getRightExpression(), parameters, paramIndexRef);
                        
                        // If it's a generic column match, we MUST treat it as a non-PK update to search the physical table,
                        // UNLESS we are 100% sure it's the specific table's primary key.
                        if (colName.equalsIgnoreCase(pkColumnName) || colName.equalsIgnoreCase("id")) {
                            if (filterVal != null) {
                                ChalkBag.get().update(tableName, filterVal.toString(), values);
                                result.affectedRows++;
                            }
                        } else {
                            // Non-PK Update: Find all matching PKs
                            result.affectedRows += resolveAndApply(tableName, colName, filterVal, values, conn, false);
                        }
                    }
                } else if (where == null) {
                   // Ignore updates without where clauses that affect whole tables, edge cases.
                }
            } else if (stmt instanceof Insert) {
                Insert insert = (Insert) stmt;
                String tableName = insert.getTable().getName().replace("`", "").replace("\"", "");

                String pkColumnName = "id";
                if (conn != null) {
                    try (ResultSet rs = conn.getMetaData().getPrimaryKeys(null, null, tableName.toUpperCase())) {
                        if (rs.next()) {
                            pkColumnName = rs.getString("COLUMN_NAME").toLowerCase();
                        }
                    } catch (Exception e) {}
                }

                Map<String, Object> values = new HashMap<>();
                int[] paramIndexRef = {1};
                String pkVal = null;

                if (insert.getColumns() != null) {
                    List<Expression> expressions = null;
                    if (insert.getItemsList() instanceof net.sf.jsqlparser.expression.operators.relational.ExpressionList) {
                        expressions = ((net.sf.jsqlparser.expression.operators.relational.ExpressionList) insert.getItemsList()).getExpressions();
                    }

                    for (int i = 0; i < insert.getColumns().size(); i++) {
                        String colName = insert.getColumns().get(i).getColumnName().replace("`", "").replace("\"", "").toLowerCase();
                        Object val = null;
                        if (expressions != null && i < expressions.size()) {
                            val = getExpressionValue(expressions.get(i), parameters, paramIndexRef);
                        }
                        values.put(colName, val);

                        if (colName.equalsIgnoreCase(pkColumnName) || colName.equalsIgnoreCase("id")) {
                            if (val != null) {
                                pkVal = val.toString();
                            }
                        } else if (colName.endsWith("id") || colName.endsWith("_id")) {
                            // If it wasn't the actual PK, see if it ends with id. This allows matching userId instead of id.
                            if (val != null && pkVal == null) {
                                pkVal = val.toString();
                            }
                        }
                    }
                }

                if (pkVal == null) {
                    int genId = ChalkBag.get().generateId();
                    pkVal = String.valueOf(genId);
                    result.generatedKeys.add(genId);
                    values.put(pkColumnName, genId);
                }

                ChalkBag.get().insert(tableName, pkVal, values);
                result.affectedRows++;

            } else if (stmt instanceof Delete) {
                Delete delete = (Delete) stmt;
                String tableName = delete.getTable().getName().replace("`", "").replace("\"", "");

                String pkColumnName = "id";
                if (conn != null) {
                    try (ResultSet rs = conn.getMetaData().getPrimaryKeys(null, null, tableName.toUpperCase())) {
                        if (rs.next()) {
                            pkColumnName = rs.getString("COLUMN_NAME").toLowerCase();
                        }
                    } catch (Exception e) {}
                }

                Expression where = delete.getWhere();
                if (where instanceof EqualsTo) {
                    EqualsTo equalsTo = (EqualsTo) where;
                    if (equalsTo.getLeftExpression() instanceof Column) {
                        String colName = ((Column) equalsTo.getLeftExpression()).getColumnName().toLowerCase().replace("`", "").replace("\"", "");
                        int[] paramIndexRef = {1};
                        Object filterVal = getExpressionValue(equalsTo.getRightExpression(), parameters, paramIndexRef);
                        
                        if (colName.equalsIgnoreCase(pkColumnName)) {
                            if (filterVal != null) {
                                ChalkBag.get().delete(tableName, filterVal.toString());
                                result.affectedRows++;
                            }
                        } else {
                            // Non-PK Delete: Find all matching PKs
                            result.affectedRows += resolveAndApply(tableName, colName, filterVal, null, conn, true);
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return result;
    }

    private static int resolveAndApply(String table, String col, Object val, Map<String, Object> values, Connection conn, boolean isDelete) {
        int affected = 0;
        // 1. Resolve physical PKs from delegate
        if (conn != null) {
            String pkColumnName = "id";
            try (ResultSet rs = conn.getMetaData().getPrimaryKeys(null, null, table.toUpperCase())) {
                if (rs.next()) {
                    pkColumnName = rs.getString("COLUMN_NAME").toLowerCase();
                }
            } catch (Exception e) {}

            String query = "SELECT " + pkColumnName + " FROM " + table + " WHERE " + col + " = ?";
            try (PreparedStatement ps = conn.prepareStatement(query)) {
                ps.setObject(1, val);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String pk = rs.getString(1);
                        if (isDelete) { ChalkBag.get().delete(table, pk); affected++; }
                        else { ChalkBag.get().update(table, pk, values); affected++; }
                    }
                }
            } catch (Exception e) {
                // table might not exist in physical DB yet
            }

            if (isDelete) {
                String dQuery = "DELETE FROM " + table + " WHERE " + col + " = ?";
                try (PreparedStatement ps = conn.prepareStatement(dQuery)) {
                    ps.setObject(1, val);
                    // We shouldn't actually execute on the physical DB since proxy is intercepting writes!
                    // Hibernate will execute it natively if we let it pass, our job is just to capture the effect in memory and return affected count.
                    // The physical db stays pure read-only from the proxy's perspective.
                    // ((java.sql.PreparedStatement) ((com.boulder.jdbc.PitonPreparedStatement) ps).unwrap(java.sql.PreparedStatement.class)).executeUpdate();
                } catch (Exception e) {}
            } else {
                // Same for update, do not execute on physical DB!
            }
        }

        // 2. Resolve virtual PKs from ChalkBag
        Map<String, Map<String, Object>> virtualTable = ChalkBag.get().getTable(table);
        if (virtualTable != null) {
            List<String> matches = new ArrayList<>();
            for (Map.Entry<String, Map<String, Object>> entry : virtualTable.entrySet()) {
                Map<String, Object> row = entry.getValue();
                if (row != null && val != null) {
                    Object rowVal = null;
                    for (String key : row.keySet()) {
                        if (key.equalsIgnoreCase(col)) {
                            rowVal = row.get(key);
                            break;
                        }
                    }
                    if (val.toString().equals(String.valueOf(rowVal))) {
                        matches.add(entry.getKey());
                    }
                }
            }
            for (String pk : matches) {
                if (isDelete) { ChalkBag.get().delete(table, pk); affected++; }
                else { ChalkBag.get().update(table, pk, values); affected++; }
            }
        }
        return affected;
    }

    public static ResultSet interceptRead(String sql, ResultSet original) {
        return new MergedResultSet(original, sql);
    }

    public static ResultSet interceptRead(String sql, ResultSet original, Map<Integer, Object> parameters) {
        return new MergedResultSet(original, sql, parameters);
    }
}
