package com.boulder.merger;

import com.boulder.state.ChalkBag;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.delete.Delete;

import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;

public class DynoMerger {

    public static void interceptWrite(String sql, Map<Integer, Object> parameters) {
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

                Object pkVal = parameters.get(paramIndex);

                if (pkVal != null) {
                    ChalkBag.get().update(tableName, pkVal.toString(), values);
                }
            } else if (stmt instanceof Delete) {
                Delete delete = (Delete) stmt;
                String tableName = delete.getTable().getName().replace("`", "").replace("\"", "");

                // Fallback to literal value if param map is empty
                String pkValStr = null;

                if (parameters.isEmpty()) {
                     String deleteSql = sql.toLowerCase();
                     if (deleteSql.contains("id = ")) {
                          String idPart = deleteSql.substring(deleteSql.indexOf("id = ") + 5).trim();
                          if (idPart.contains(" ")) idPart = idPart.substring(0, idPart.indexOf(" "));
                          if (idPart.contains(";")) idPart = idPart.replace(";", "");
                          if (!idPart.equals("?")) {
                              pkValStr = idPart;
                          }
                     } else if (deleteSql.contains("id=")) {
                          String idPart = deleteSql.substring(deleteSql.indexOf("id=") + 3).trim();
                          if (idPart.contains(" ")) idPart = idPart.substring(0, idPart.indexOf(" "));
                          if (idPart.contains(";")) idPart = idPart.replace(";", "");
                          if (!idPart.equals("?")) {
                              pkValStr = idPart;
                          }
                     }
                } else {
                     // Assuming parameter 1 is id
                     Object val = parameters.get(1);
                     if (val != null) {
                         pkValStr = val.toString();
                     }
                }

                if (pkValStr != null) {
                    ChalkBag.get().delete(tableName, pkValStr);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static ResultSet interceptRead(String sql, ResultSet original) {
        return new MergedResultSet(original, sql);
    }
}
