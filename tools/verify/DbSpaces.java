import java.sql.*;

/** Lists every space (including logically deleted ones) with timestamps, newest first. */
public class DbSpaces {
    public static void main(String[] args) throws Exception {
        String url = "jdbc:mysql://localhost:3306/database1?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
        try (Connection c = DriverManager.getConnection(url, "root", "123456")) {
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                     "select id, spaceName, spaceType, spaceLevel, totalSize, totalCount, userId, isDelete, createTime "
                   + "from space order by createTime desc, id desc")) {
                System.out.println("id | name | type | level | size | count | userId | isDelete | createTime");
                while (rs.next()) {
                    System.out.printf("%s | %s | %s | %s | %s | %s | %s | %s | %s%n",
                            rs.getString("id"), rs.getString("spaceName"), rs.getString("spaceType"),
                            rs.getString("spaceLevel"), rs.getString("totalSize"), rs.getString("totalCount"),
                            rs.getString("userId"), rs.getString("isDelete"), rs.getString("createTime"));
                }
            }
            System.out.println();
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("select id, spaceId, userId, spaceRole, status from space_user order by id")) {
                System.out.println("space_user: id | spaceId | userId | role | status");
                while (rs.next()) {
                    System.out.printf("%s | %s | %s | %s | %s%n", rs.getString("id"), rs.getString("spaceId"),
                            rs.getString("userId"), rs.getString("spaceRole"), rs.getString("status"));
                }
            }
        }
    }
}
