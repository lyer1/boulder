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

    public static void interceptWrite(String sql, Map<Integer, Object> parameters, Connection conn) {
        interceptWriteWithKeys(sql, parameters, conn);
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

    public static List<Integer> interceptWriteWithKeys(String sql, Map<Integer, Object> parameters, Connection conn) {
        List<Integer> generatedKeys = new ArrayList<>();
        try {
            Statement stmt = CCJSqlParserUtil.parse(sql);
            if (stmt instanceof Update) {
                Update update = (Update) stmt;
                String tableName = update.getTable().getName().replace("`", "").replace("\"", "");

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
                        
                        if (colName.equals("id")) {
                            if (filterVal != null) {
                                ChalkBag.get().update(tableName, filterVal.toString(), values);
                            }
                        } else {
                            // Non-PK Update: Find all matching PKs
                            resolveAndApply(tableName, colName, filterVal, values, conn, false);
                        }
                    }
                }
            } else if (stmt instanceof Insert) {
                Insert insert = (Insert) stmt;
                String tableName = insert.getTable().getName().replace("`", "").replace("\"", "");

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

                        if (colName.equals("id")) {
                            if (val != null) {
                                pkVal = val.toString();
                            }
                        }
                    }
                }

                if (pkVal == null) {
                    int genId = ChalkBag.get().generateId();
                    pkVal = String.valueOf(genId);
                    generatedKeys.add(genId);
                    values.put("id", genId); 
                }

                ChalkBag.get().insert(tableName, pkVal, values);

            } else if (stmt instanceof Delete) {
                Delete delete = (Delete) stmt;
                String tableName = delete.getTable().getName().replace("`", "").replace("\"", "");

                Expression where = delete.getWhere();
                if (where instanceof EqualsTo) {
                    EqualsTo equalsTo = (EqualsTo) where;
                    if (equalsTo.getLeftExpression() instanceof Column) {
                        String colName = ((Column) equalsTo.getLeftExpression()).getColumnName().toLowerCase().replace("`", "").replace("\"", "");
                        int[] paramIndexRef = {1};
                        Object filterVal = getExpressionValue(equalsTo.getRightExpression(), parameters, paramIndexRef);
                        
                        if (colName.equals("id")) {
                            if (filterVal != null) {
                                ChalkBag.get().delete(tableName, filterVal.toString());
                            }
                        } else {
                            // Non-PK Delete: Find all matching PKs
                            resolveAndApply(tableName, colName, filterVal, null, conn, true);
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return generatedKeys;
    }

    private static void resolveAndApply(String table, String col, Object val, Map<String, Object> values, Connection conn, boolean isDelete) {
        // 1. Resolve physical PKs from delegate
        if (conn != null) {
            String query = "SELECT id FROM " + table + " WHERE " + col + " = ?";
            try (PreparedStatement ps = conn.prepareStatement(query)) {
                ps.setObject(1, val);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String pk = rs.getString(1);
                        if (isDelete) ChalkBag.get().delete(table, pk);
                        else ChalkBag.get().update(table, pk, values);
                    }
                }
            } catch (Exception e) {
                // table might not exist in physical DB yet
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
                if (isDelete) ChalkBag.get().delete(table, pk);
                else ChalkBag.get().update(table, pk, values);
            }
        }
    }

    public static ResultSet interceptRead(String sql, ResultSet original) {
        return new MergedResultSet(original, sql);
    }

    public static ResultSet interceptRead(String sql, ResultSet original, Map<Integer, Object> parameters) {
        return new MergedResultSet(original, sql, parameters);
    }
}
