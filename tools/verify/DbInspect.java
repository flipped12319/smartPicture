import java.sql.*;

/**
 * Helper for the verification harnesses: dumps users / spaces / space_user / pictures so the
 * assertions can be written against real ids instead of guesses.
 *
 * Build & run (JDK 11, mysql-connector-j on the classpath):
 *   javac -encoding UTF-8 DbInspect.java
 *   java -Dfile.encoding=UTF-8 -cp ".;<mysql-connector-j-8.0.31.jar>" DbInspect
 */
public class DbInspect {

    private static final String URL =
            "jdbc:mysql://localhost:3306/database1?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
    private static final String USER = System.getProperty("db.user", "root");
    private static final String PASSWORD = System.getProperty("db.password", "123456");

    public static void main(String[] args) throws Exception {
        try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD)) {
            System.out.println("=== users (isDelete=1 means LOGICALLY DELETED: its token must be rejected) ===");
            dump(c, "select id, userAccount, userName, userRole, isDelete from user order by id");

            System.out.println("=== spaces (isDelete=0 is what the services can see) ===");
            dump(c, "select id, spaceName, spaceType, spaceLevel, totalSize, totalCount, maxSize, maxCount, "
                    + "userId, isDelete from space order by isDelete, id");

            System.out.println("=== space_user (physical delete; only real memberships) ===");
            dump(c, "select id, spaceId, userId, spaceRole, status, inviterId from space_user order by id");

            System.out.println("=== public gallery pictures (spaceId IS NULL) ===");
            dump(c, "select id, name, spaceId, userId, reviewStatus from picture "
                    + "where spaceId is null and isDelete = 0 order by id desc limit 20");

            System.out.println("=== private/team space pictures ===");
            dump(c, "select id, name, spaceId, userId, reviewStatus from picture "
                    + "where spaceId is not null and isDelete = 0 order by spaceId, id");
        }
    }

    private static void dump(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData m = rs.getMetaData();
            while (rs.next()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i <= m.getColumnCount(); i++) {
                    sb.append(m.getColumnLabel(i)).append('=').append(rs.getString(i)).append("  ");
                }
                System.out.println("  " + sb);
            }
        }
    }
}
