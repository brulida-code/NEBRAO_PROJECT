import javax.swing.table.DefaultTableModel;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** All JDBC plumbing lives here so the panels stay small. */
public final class Db {

    private static String url;
    private static String user;
    private static String pass;

    private Db() {}

    // ---- reusable SQL fragments -------------------------------------------------

    /** A claim is Verified, Rejected (has a reason) or Pending (unverified, no reason yet). */
    public static final String CLAIM_STATUS =
            "CASE WHEN v.claim_request_id IS NOT NULL THEN 'Verified' "
          + "WHEN u.rejection_reason IS NOT NULL THEN 'Rejected' ELSE 'Pending' END";

    /** True when the item already has a verified claim, i.e. it was released to its owner. */
    public static final String ITEM_RELEASED =
            "EXISTS (SELECT 1 FROM claim_request rc JOIN verified_request rv "
          + "ON rv.claim_request_id = rc.claim_request_id WHERE rc.item_id = i.item_id)";

    // ---- connection ---------------------------------------------------------------

    public static void configure(String host, String port, String database, String dbUser, String dbPass) {
        url = "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useUnicode=true&characterEncoding=utf8&allowPublicKeyRetrieval=true";
        user = dbUser;
        pass = dbPass;
    }

    public static Connection open() throws SQLException {
        return DriverManager.getConnection(url, user, pass);
    }

    // ---- small value types ----------------------------------------------------------

    /** An id + label pair for combo boxes. */
    public record Choice(int id, String label) {
        @Override public String toString() { return label; }
    }

    public record Result(String[] columns, List<Object[]> rows) {}

    @FunctionalInterface
    public interface Work<T> { T run(Connection c) throws SQLException; }

    // ---- transactions -----------------------------------------------------------------

    /** Runs the work in one transaction: commit on success, rollback on any failure. */
    public static <T> T tx(Work<T> work) throws SQLException {
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
    }

    // ---- queries ------------------------------------------------------------------------

    private static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static Object display(Object v) {
        if (v == null) return "";
        if (v instanceof Timestamp t) return t.toLocalDateTime().format(DT);
        if (v instanceof LocalDateTime t) return t.format(DT);
        return v;
    }

    public static Result query(String sql, Object... params) throws SQLException {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                int n = md.getColumnCount();
                String[] cols = new String[n];
                for (int i = 0; i < n; i++) cols[i] = md.getColumnLabel(i + 1);
                List<Object[]> rows = new ArrayList<>();
                while (rs.next()) {
                    Object[] row = new Object[n];
                    for (int i = 0; i < n; i++) row[i] = display(rs.getObject(i + 1));
                    rows.add(row);
                }
                return new Result(cols, rows);
            }
        }
    }

    /** Result as a read-only table model. */
    public static DefaultTableModel table(String sql, Object... params) throws SQLException {
        Result r = query(sql, params);
        DefaultTableModel m = new DefaultTableModel(r.columns(), 0) {
            @Override public boolean isCellEditable(int row, int col) { return false; }
        };
        for (Object[] row : r.rows()) m.addRow(row);
        return m;
    }

    /** First column = id, second column = label. */
    public static List<Choice> choices(String sql, Object... params) throws SQLException {
        List<Choice> out = new ArrayList<>();
        for (Object[] row : query(sql, params).rows()) {
            out.add(new Choice(((Number) row[0]).intValue(), String.valueOf(row[1])));
        }
        return out;
    }

    public static List<String> strings(String sql, Object... params) throws SQLException {
        List<String> out = new ArrayList<>();
        for (Object[] row : query(sql, params).rows()) out.add(String.valueOf(row[0]));
        return out;
    }

    /** First column of the first row, or null. */
    public static Object scalar(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getObject(1) : null;
            }
        }
    }

    public static Object scalar(String sql, Object... params) throws SQLException {
        try (Connection c = open()) {
            return scalar(c, sql, params);
        }
    }

    // ---- writes (inside a transaction) -----------------------------------------------------

    public static int update(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        }
    }

    /** Executes an INSERT and returns the generated auto-increment id. */
    public static int insert(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, params);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("No generated key returned.");
                return keys.getInt(1);
            }
        }
    }
}
