package com.boulder.merger;

import com.boulder.state.ChalkBag;
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

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DynoMerger {

    public static void interceptWrite(String sql, Map<Integer, Object> parameters) {
        interceptWriteWithKeys(sql, parameters);
    }

    public static List<Integer> interceptWriteWithKeys(String sql, Map<Integer, Object> parameters) {
        List<Integer> generatedKeys = new ArrayList<>();
        try {
            Statement stmt = CCJSqlParserUtil.parse(sql);
            if (stmt instanceof Update) {
                Update update = (Update) stmt;
                String tableName = update.getTable().getName().replace("`", "").replace("\"", "");

                Map<String, Object> values = new HashMap<>();
                int paramIndex = 1;

                for (int i = 0; i < update.getUpdateSets().size(); i++) {
                    String colName = update.getUpdateSets().get(i).getColumns().get(0).getColumnName().replace("`", "").replace("\"", "");
                    Object val = parameters.get(paramIndex++);
                    values.put(colName.toLowerCase(), val);
                }

                // Extremely simple PK extraction from WHERE clause for standard Hibernate by-ID updates
                Object pkVal = null;
                Expression where = update.getWhere();
                if (where instanceof EqualsTo) {
                    EqualsTo equalsTo = (EqualsTo) where;
                    // Usually "WHERE id = ?"
                    if (equalsTo.getRightExpression() instanceof net.sf.jsqlparser.expression.JdbcParameter) {
                        pkVal = parameters.get(paramIndex);
                    } else if (equalsTo.getRightExpression() instanceof LongValue) {
                        pkVal = ((LongValue) equalsTo.getRightExpression()).getValue();
                    } else if (equalsTo.getRightExpression() instanceof StringValue) {
                        pkVal = ((StringValue) equalsTo.getRightExpression()).getValue();
                    }
                } else {
                    // Fallback just in case
                    pkVal = parameters.get(paramIndex);
                }

                if (pkVal != null) {
                    ChalkBag.get().update(tableName, pkVal.toString(), values);
                }
            } else if (stmt instanceof Insert) {
                Insert insert = (Insert) stmt;
                String tableName = insert.getTable().getName().replace("`", "").replace("\"", "");

                Map<String, Object> values = new HashMap<>();
                int paramIndex = 1;
                String pkVal = null;

                if (insert.getColumns() != null) {
                    for (int i = 0; i < insert.getColumns().size(); i++) {
                        String colName = insert.getColumns().get(i).getColumnName().replace("`", "").replace("\"", "").toLowerCase();

                        Object val = null;
                        // Grab from parameters
                        val = parameters.get(paramIndex++);
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
                    values.put("id", genId); // Ensure ID is in the values map for appending
                }

                ChalkBag.get().insert(tableName, pkVal, values);

            } else if (stmt instanceof Delete) {
                Delete delete = (Delete) stmt;
                String tableName = delete.getTable().getName().replace("`", "").replace("\"", "");

                String pkValStr = null;
                Expression where = delete.getWhere();

                if (where instanceof EqualsTo) {
                    EqualsTo equalsTo = (EqualsTo) where;
                    if (equalsTo.getLeftExpression() instanceof Column) {
                        String colName = ((Column) equalsTo.getLeftExpression()).getColumnName().toLowerCase();
                        if (colName.contains("id")) {
                            if (equalsTo.getRightExpression() instanceof net.sf.jsqlparser.expression.JdbcParameter) {
                                Object val = parameters.get(1);
                                if (val != null) {
                                    pkValStr = val.toString();
                                }
                            } else if (equalsTo.getRightExpression() instanceof LongValue) {
                                pkValStr = String.valueOf(((LongValue) equalsTo.getRightExpression()).getValue());
                            } else if (equalsTo.getRightExpression() instanceof StringValue) {
                                pkValStr = ((StringValue) equalsTo.getRightExpression()).getValue();
                            }
                        }
                    }
                }

                if (pkValStr != null) {
                    ChalkBag.get().delete(tableName, pkValStr);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return generatedKeys;
    }

    public static ResultSet interceptRead(String sql, ResultSet original) {
        return new MergedResultSet(original, sql);
    }
}
