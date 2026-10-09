import java.sql.*;

/**
 * Restores pre-existing rows that a verification run soft-deleted.
 *
 * Why this exists: an earlier version of verify-4a.ps1 asserted "ADMIN deletes own private space"
 * and by doing so soft-deleted `adminspace`, which is pre-existing fixture data that verify-4b.ps1
 * depends on. The harness no longer does that; this helper undoes the damage once.
 *
 * Only touches the one well-known row, and only flips isDelete back to 0.
 */
public class DbRestore {
    public static void main(String[] args) throws Exception {
        String url = "jdbc:mysql://localhost:3306/database1?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
        try (Connection c = DriverManager.getConnection(url, "root", "123456")) {
            try (PreparedStatement ps = c.prepareStatement(
                    "update space set isDelete = 0 where id = 2056680093672083458 and spaceName = 'adminspace' and isDelete = 1")) {
                System.out.println("restored adminspace rows = " + ps.executeUpdate());
            }
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                     "select id, spaceName, isDelete, totalSize, totalCount from space "
                   + "where id in (2056680093672083458, 2057258595425259521, 2100208978036293634)")) {
                while (rs.next()) {
                    System.out.printf("id=%s name=%s isDelete=%s size=%s count=%s%n",
                            rs.getString("id"), rs.getString("spaceName"), rs.getString("isDelete"),
                            rs.getString("totalSize"), rs.getString("totalCount"));
                }
            }
        }
    }
}
