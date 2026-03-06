import java.sql.*;
public class test_db {
  public static void main(String[] args) throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:cragdb;DB_CLOSE_DELAY=-1", "sa", "")) {
      try (Statement stmt = conn.createStatement()) {
        stmt.execute("CREATE TABLE IF NOT EXISTS sys_user (userId BIGINT PRIMARY KEY, username VARCHAR(255), enabled BOOLEAN)");
      }
      DatabaseMetaData dbm = conn.getMetaData();
      ResultSet rs = dbm.getPrimaryKeys(null, null, "SYS_USER");
      if (rs.next()) {
        System.out.println("PK: " + rs.getString("COLUMN_NAME"));
      } else {
        System.out.println("No PK found for SYS_USER");
      }
    }
  }
}
