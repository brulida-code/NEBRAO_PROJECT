import javax.swing.*;
import java.awt.*;
import java.sql.Connection;
import java.sql.SQLException;

/** FoundIt: Online Lost and Found System (OSA). Entry point. */
public class FoundItApp {

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                    if ("Nimbus".equals(info.getName())) {
                        UIManager.setLookAndFeel(info.getClassName());
                        break;
                    }
                }
            } catch (Exception ignored) {
                // fall back to the default look and feel
            }
            if (!connect()) System.exit(0);
            buildWindow();
        });
    }

    /** Asks for MySQL connection details until a connection works or the user cancels. */
    private static boolean connect() {
        JTextField host = new JTextField("localhost", 16);
        JTextField port = new JTextField("3306", 6);
        JTextField database = new JTextField("foundit", 16);
        JTextField user = new JTextField("root", 16);
        JPasswordField pass = new JPasswordField(16);
        JPanel form = Ui.form(
            new String[]{"Host", "Port", "Database", "User", "Password"},
            new Component[]{host, port, database, user, pass});

        while (true) {
            if (JOptionPane.showConfirmDialog(null, form, "FoundIt - connect to MySQL",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return false;
            Db.configure(host.getText().trim(), port.getText().trim(), database.getText().trim(),
                    user.getText().trim(), new String(pass.getPassword()));
            try (Connection c = Db.open()) {
                return true;
            } catch (SQLException e) {
                String msg = e.getMessage();
                if (msg != null && msg.contains("No suitable driver")) {
                    msg = "MySQL JDBC driver not found.\nPut mysql-connector-j-*.jar in the lib folder and start the app with run.sh / run.bat.";
                }
                JOptionPane.showMessageDialog(null, msg, "Connection failed", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private static void buildWindow() {
        JFrame frame = new JFrame("FoundIt - Online Lost and Found System");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JLabel header = new JLabel("  FoundIt  |  Office of Student Affairs Lost & Found");
        header.setFont(header.getFont().deriveFont(Font.BOLD, 16f));
        header.setBorder(BorderFactory.createEmptyBorder(8, 6, 8, 6));

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Items", new ItemsPanel());
        tabs.addTab("Claims", new ClaimsPanel());
        tabs.addTab("Users", new UsersPanel());
        tabs.addTab("History", new HistoryPanel());
        tabs.addChangeListener(e -> {
            Component c = tabs.getSelectedComponent();
            if (c instanceof Ui.Refreshable r) r.refresh();
        });

        frame.setLayout(new BorderLayout());
        frame.add(header, BorderLayout.NORTH);
        frame.add(tabs, BorderLayout.CENTER);
        frame.setSize(1100, 650);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }
}
