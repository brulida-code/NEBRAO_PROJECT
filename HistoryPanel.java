import javax.swing.*;
import java.awt.*;
import java.sql.SQLException;

/** Traceable history of reports, claims and validations, per user and per item. */
public class HistoryPanel extends JPanel implements Ui.Refreshable {

    private final JComboBox<Db.Choice> userBox = new JComboBox<>();
    private final JComboBox<Db.Choice> itemBox = new JComboBox<>();
    private final JTable userTable = Ui.table(new javax.swing.table.DefaultTableModel());
    private final JTable itemTable = Ui.table(new javax.swing.table.DefaultTableModel());
    private boolean loading;

    public HistoryPanel() {
        super(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel byUser = new JPanel(new BorderLayout(6, 6));
        byUser.add(Ui.flow(new JLabel("User:"), userBox), BorderLayout.NORTH);
        byUser.add(new JScrollPane(userTable), BorderLayout.CENTER);

        JPanel byItem = new JPanel(new BorderLayout(6, 6));
        byItem.add(Ui.flow(new JLabel("Item:"), itemBox), BorderLayout.NORTH);
        byItem.add(new JScrollPane(itemTable), BorderLayout.CENTER);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("By user", byUser);
        tabs.addTab("By item (report to resolution)", byItem);
        add(tabs, BorderLayout.CENTER);

        userBox.addActionListener(e -> loadUser());
        itemBox.addActionListener(e -> loadItem());
        refresh();
    }

    @Override
    public void refresh() {
        loading = true;
        try {
            Object oldUser = userBox.getSelectedItem();
            Object oldItem = itemBox.getSelectedItem();
            userBox.removeAllItems();
            for (Db.Choice c : Db.choices(
                    "SELECT u.user_id, CONCAT(u.first_name, ' ', u.last_name, ' (', "
                  + "CASE WHEN r.user_id IS NOT NULL THEN 'Reporter' WHEN c.user_id IS NOT NULL THEN 'Claimant' ELSE 'Validator' END, ')') "
                  + "FROM app_user u LEFT JOIN reporter r ON r.user_id = u.user_id "
                  + "LEFT JOIN claimant c ON c.user_id = u.user_id ORDER BY u.last_name, u.first_name")) {
                userBox.addItem(c);
                if (oldUser instanceof Db.Choice o && o.id() == c.id()) userBox.setSelectedItem(c);
            }
            itemBox.removeAllItems();
            for (Db.Choice c : Db.choices(
                    "SELECT item_id, CONCAT('#', item_id, ' ', item_name) FROM item ORDER BY item_id")) {
                itemBox.addItem(c);
                if (oldItem instanceof Db.Choice o && o.id() == c.id()) itemBox.setSelectedItem(c);
            }
        } catch (SQLException e) {
            Ui.error(this, e);
        } finally {
            loading = false;
        }
        loadUser();
        loadItem();
    }

    private void loadUser() {
        if (loading || !(userBox.getSelectedItem() instanceof Db.Choice u)) return;
        String claimFrom =
            "FROM claim_request c JOIN item i ON i.item_id = c.item_id "
          + "LEFT JOIN verified_request v ON v.claim_request_id = c.claim_request_id "
          + "LEFT JOIN unverified_request u ON u.claim_request_id = c.claim_request_id ";
        String sql =
            "SELECT ts AS `Date & Time`, ev AS Event, itm AS Item, detail AS Details FROM ("
          + " SELECT COALESCE(f.found_at, ls.lost_at) AS ts, "
          + "  IF(f.item_id IS NOT NULL, 'Reported found item', 'Reported lost item') AS ev, "
          + "  i.item_name AS itm, CONCAT('Location: ', l.location_name) AS detail "
          + "  FROM item i JOIN location l ON l.location_id = i.location_id "
          + "  LEFT JOIN found_item f ON f.item_id = i.item_id LEFT JOIN lost_item ls ON ls.item_id = i.item_id "
          + "  WHERE i.reporter_id = ? "
          + " UNION ALL "
          + " SELECT c.request_date, 'Submitted claim', i.item_name, CONCAT('Claim #', c.claim_request_id, ' - ', " + Db.CLAIM_STATUS + ") "
          + claimFrom + " WHERE c.claimant_id = ? "
          + " UNION ALL "
          + " SELECT c.request_date, 'Assigned to validate claim', i.item_name, CONCAT('Claim #', c.claim_request_id, ' - ', " + Db.CLAIM_STATUS + ") "
          + claimFrom + " WHERE c.validator_id = ? "
          + ") t ORDER BY ts DESC";
        try {
            Ui.setModel(userTable, Db.table(sql, u.id(), u.id(), u.id()));
        } catch (SQLException e) {
            Ui.error(this, e);
        }
    }

    private void loadItem() {
        if (loading || !(itemBox.getSelectedItem() instanceof Db.Choice it)) return;
        String sql =
            "SELECT ts AS `Date & Time`, ev AS Event, who AS `By`, detail AS Details FROM ("
          + " SELECT COALESCE(f.found_at, ls.lost_at) AS ts, 1 AS ord, "
          + "  IF(f.item_id IS NOT NULL, 'Reported as found', 'Reported as lost') AS ev, "
          + "  CONCAT(u.first_name, ' ', u.last_name) AS who, CONCAT('Location: ', l.location_name) AS detail "
          + "  FROM item i JOIN app_user u ON u.user_id = i.reporter_id JOIN location l ON l.location_id = i.location_id "
          + "  LEFT JOIN found_item f ON f.item_id = i.item_id LEFT JOIN lost_item ls ON ls.item_id = i.item_id "
          + "  WHERE i.item_id = ? "
          + " UNION ALL "
          + " SELECT c.request_date, 2, 'Claim submitted', CONCAT(cu.first_name, ' ', cu.last_name), "
          + "  CONCAT('Claim #', c.claim_request_id, ', validator: ', vu.first_name, ' ', vu.last_name) "
          + "  FROM claim_request c JOIN app_user cu ON cu.user_id = c.claimant_id "
          + "  JOIN app_user vu ON vu.user_id = c.validator_id WHERE c.item_id = ? "
          + " UNION ALL "
          + " SELECT v.verification_date, 3, 'Claim verified', CONCAT(vu.first_name, ' ', vu.last_name), "
          + "  CONCAT('Claim #', c.claim_request_id, ' verified onsite; item released to ', cu.first_name, ' ', cu.last_name) "
          + "  FROM claim_request c JOIN verified_request v ON v.claim_request_id = c.claim_request_id "
          + "  JOIN app_user cu ON cu.user_id = c.claimant_id JOIN app_user vu ON vu.user_id = c.validator_id "
          + "  WHERE c.item_id = ? "
          + " UNION ALL "
          + " SELECT c.request_date, 3, 'Claim rejected', CONCAT(vu.first_name, ' ', vu.last_name), "
          + "  CONCAT('Claim #', c.claim_request_id, ': ', u.rejection_reason) "
          + "  FROM claim_request c JOIN unverified_request u ON u.claim_request_id = c.claim_request_id "
          + "  JOIN app_user vu ON vu.user_id = c.validator_id "
          + "  WHERE c.item_id = ? AND u.rejection_reason IS NOT NULL "
          + ") t ORDER BY ts, ord";
        try {
            Ui.setModel(itemTable, Db.table(sql, it.id(), it.id(), it.id(), it.id()));
        } catch (SQLException e) {
            Ui.error(this, e);
        }
    }
}
