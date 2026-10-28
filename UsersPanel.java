import javax.swing.*;
import java.awt.*;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Register users and classify each as Reporter, Claimant or Validator. */
public class UsersPanel extends JPanel implements Ui.Refreshable {

    private static final Pattern EMAIL = Pattern.compile("^[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+$");
    private static final Pattern PHONE = Pattern.compile("^(\\+63|0)9\\d{9}$");

    private final JTextField search = new JTextField(16);
    private final JComboBox<String> role =
            new JComboBox<>(new String[]{"All roles", "Reporter", "Claimant", "Validator"});
    private final JTable table = Ui.table(new javax.swing.table.DefaultTableModel());
    private final JLabel count = new JLabel(" ");

    public UsersPanel() {
        super(new BorderLayout(6, 6));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JButton go = new JButton("Search");
        go.addActionListener(e -> refresh());
        search.addActionListener(e -> refresh());
        role.addActionListener(e -> refresh());
        add(Ui.flow(new JLabel("Name / email:"), search, role, go), BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);

        JButton register = new JButton("Register User...");
        register.addActionListener(e -> register());
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(Ui.flow(register), BorderLayout.WEST);
        bottom.add(count, BorderLayout.EAST);
        add(bottom, BorderLayout.SOUTH);

        refresh();
    }

    @Override
    public void refresh() {
        try {
            StringBuilder sql = new StringBuilder(
                "SELECT u.user_id AS ID, u.first_name AS `First Name`, u.last_name AS `Last Name`, "
              + "u.email AS Email, u.contact_number AS Contact, "
              + "CASE WHEN r.user_id IS NOT NULL THEN 'Reporter' WHEN c.user_id IS NOT NULL THEN 'Claimant' "
              + "WHEN v.user_id IS NOT NULL THEN 'Validator' ELSE 'Unassigned' END AS Role "
              + "FROM app_user u "
              + "LEFT JOIN reporter r ON r.user_id = u.user_id "
              + "LEFT JOIN claimant c ON c.user_id = u.user_id "
              + "LEFT JOIN validator v ON v.user_id = u.user_id WHERE 1=1");
            List<Object> p = new ArrayList<>();
            String q = search.getText().trim();
            if (!q.isEmpty()) {
                sql.append(" AND (u.first_name LIKE ? OR u.last_name LIKE ? OR u.email LIKE ?)");
                for (int i = 0; i < 3; i++) p.add("%" + q + "%");
            }
            switch (role.getSelectedIndex()) {
                case 1 -> sql.append(" AND r.user_id IS NOT NULL");
                case 2 -> sql.append(" AND c.user_id IS NOT NULL");
                case 3 -> sql.append(" AND v.user_id IS NOT NULL");
                default -> { }
            }
            sql.append(" ORDER BY u.user_id");
            Ui.setModel(table, Db.table(sql.toString(), p.toArray()));
            count.setText(table.getRowCount() + " user(s)");
        } catch (SQLException e) {
            Ui.error(this, e);
        }
    }

    private void register() {
        JTextField first = new JTextField(20);
        JTextField last = new JTextField(20);
        JTextField email = new JTextField(24);
        JTextField phone = new JTextField("+639", 16);
        JComboBox<String> roleBox = new JComboBox<>(new String[]{"Reporter", "Claimant", "Validator"});
        JPanel form = Ui.form(
            new String[]{"First name", "Last name", "Email", "Contact number", "Role"},
            new Component[]{first, last, email, phone, roleBox});

        while (true) {
            if (JOptionPane.showConfirmDialog(this, form, "Register user",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
            try {
                String fn = first.getText().trim();
                String ln = last.getText().trim();
                String em = email.getText().trim();
                String ph = phone.getText().trim();
                if (fn.isEmpty() || ln.isEmpty()) throw new IllegalArgumentException("First and last name are required.");
                if (fn.length() > 50 || ln.length() > 50) throw new IllegalArgumentException("Names must be 50 characters or fewer.");
                if (em.length() > 100 || !EMAIL.matcher(em).matches()) throw new IllegalArgumentException("Enter a valid email address.");
                if (!PHONE.matcher(ph).matches())
                    throw new IllegalArgumentException("Contact number must look like 09171234501 or +639171234501.");

                // The table name comes from this fixed list, never from user text.
                String roleTable = switch (roleBox.getSelectedIndex()) {
                    case 0 -> "reporter";
                    case 1 -> "claimant";
                    default -> "validator";
                };
                Db.tx(c -> {
                    int id = Db.insert(c,
                        "INSERT INTO app_user (first_name, last_name, email, contact_number) VALUES (?,?,?,?)",
                        fn, ln, em, ph);
                    Db.update(c, "INSERT INTO " + roleTable + " (user_id) VALUES (?)", id);
                    return id;
                });
                refresh();
                return;
            } catch (IllegalArgumentException e) {
                Ui.warn(this, e.getMessage());
            } catch (SQLException e) {
                if (e.getErrorCode() == 1062) Ui.warn(this, "That email address is already registered.");
                else { Ui.error(this, e); return; }
            }
        }
    }
}
