import javax.swing.*;
import java.awt.*;
import java.sql.SQLException;
import java.time.LocalDateTime;

/** Claim requests: submit, verify (ownership confirmed onsite) or reject with a reason. */
public class ClaimsPanel extends JPanel implements Ui.Refreshable {

    private final JComboBox<String> statusFilter =
            new JComboBox<>(new String[]{"All", "Pending", "Verified", "Rejected"});
    private final JTable table = Ui.table(new javax.swing.table.DefaultTableModel());
    private final JLabel count = new JLabel(" ");

    public ClaimsPanel() {
        super(new BorderLayout(6, 6));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        statusFilter.addActionListener(e -> refresh());
        add(Ui.flow(new JLabel("Status:"), statusFilter), BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);

        JButton newClaim = new JButton("New Claim...");
        JButton verify = new JButton("Verify (ownership confirmed)");
        JButton reject = new JButton("Reject...");
        newClaim.addActionListener(e -> newClaim());
        verify.addActionListener(e -> verify());
        reject.addActionListener(e -> reject());

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(Ui.flow(newClaim, verify, reject), BorderLayout.WEST);
        bottom.add(count, BorderLayout.EAST);
        add(bottom, BorderLayout.SOUTH);

        refresh();
    }

    @Override
    public void refresh() {
        try {
            String sql =
                "SELECT c.claim_request_id AS ID, i.item_name AS Item, "
              + "CONCAT(cu.first_name, ' ', cu.last_name) AS Claimant, "
              + "CONCAT(vu.first_name, ' ', vu.last_name) AS Validator, "
              + "c.request_date AS Requested, " + Db.CLAIM_STATUS + " AS Status, "
              + "v.verification_date AS `Verified On`, u.rejection_reason AS Reason "
              + "FROM claim_request c "
              + "JOIN item i ON i.item_id = c.item_id "
              + "JOIN app_user cu ON cu.user_id = c.claimant_id "
              + "JOIN app_user vu ON vu.user_id = c.validator_id "
              + "LEFT JOIN verified_request v ON v.claim_request_id = c.claim_request_id "
              + "LEFT JOIN unverified_request u ON u.claim_request_id = c.claim_request_id ";
            if (statusFilter.getSelectedIndex() > 0) {
                sql += "WHERE " + Db.CLAIM_STATUS + " = ? ORDER BY c.request_date DESC";
                Ui.setModel(table, Db.table(sql, statusFilter.getSelectedItem()));
            } else {
                sql += "ORDER BY c.request_date DESC";
                Ui.setModel(table, Db.table(sql));
            }
            count.setText(table.getRowCount() + " claim(s)");
        } catch (SQLException e) {
            Ui.error(this, e);
        }
    }

    private void newClaim() {
        try {
            JComboBox<Db.Choice> item = new JComboBox<>(Db.choices(
                "SELECT i.item_id, CONCAT('#', i.item_id, ' ', i.item_name, ' (', l.location_name, ')') "
              + "FROM item i JOIN found_item f ON f.item_id = i.item_id "
              + "JOIN location l ON l.location_id = i.location_id "
              + "WHERE NOT " + Db.ITEM_RELEASED + " ORDER BY i.item_name").toArray(new Db.Choice[0]));
            JComboBox<Db.Choice> claimant = new JComboBox<>(Db.choices(
                "SELECT u.user_id, CONCAT(u.first_name, ' ', u.last_name, ' <', u.email, '>') "
              + "FROM app_user u JOIN claimant c ON c.user_id = u.user_id ORDER BY u.last_name").toArray(new Db.Choice[0]));
            JComboBox<Db.Choice> validator = new JComboBox<>(Db.choices(
                "SELECT u.user_id, CONCAT(u.first_name, ' ', u.last_name) "
              + "FROM app_user u JOIN validator v ON v.user_id = u.user_id ORDER BY u.last_name").toArray(new Db.Choice[0]));

            if (item.getItemCount() == 0) { Ui.warn(this, "There are no unreleased found items to claim."); return; }
            if (claimant.getItemCount() == 0) { Ui.warn(this, "Register a user with the Claimant role first."); return; }
            if (validator.getItemCount() == 0) { Ui.warn(this, "Register a user with the Validator role first."); return; }

            JPanel form = Ui.form(
                new String[]{"Found item", "Claimant", "Assigned validator"},
                new Component[]{item, claimant, validator});
            form.add(new JLabel("Ownership is confirmed onsite by OSA using the item description."),
                    new GridBagConstraints(0, 3, 2, 1, 1, 0, GridBagConstraints.WEST, GridBagConstraints.NONE, new Insets(8, 6, 0, 6), 0, 0));

            if (JOptionPane.showConfirmDialog(this, form, "New claim request",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;

            Db.Choice it = (Db.Choice) item.getSelectedItem();
            Db.Choice cl = (Db.Choice) claimant.getSelectedItem();
            Db.Choice va = (Db.Choice) validator.getSelectedItem();

            Db.tx(c -> {
                Number open = (Number) Db.scalar(c,
                    "SELECT COUNT(*) FROM claim_request c JOIN unverified_request u ON u.claim_request_id = c.claim_request_id "
                  + "WHERE c.item_id = ? AND c.claimant_id = ? AND u.rejection_reason IS NULL", it.id(), cl.id());
                if (open.intValue() > 0)
                    throw new IllegalStateException("This claimant already has a pending claim for that item.");
                int id = Db.insert(c,
                    "INSERT INTO claim_request (item_id, claimant_id, validator_id, request_date) VALUES (?,?,?,?)",
                    it.id(), cl.id(), va.id(), LocalDateTime.now());
                Db.update(c, "INSERT INTO unverified_request (claim_request_id, rejection_reason) VALUES (?, NULL)", id);
                return id;
            });
            refresh();
        } catch (IllegalStateException e) {
            Ui.warn(this, e.getMessage());
        } catch (SQLException e) {
            Ui.error(this, e);
        }
    }

    /** Returns the selected pending claim id, or -1 after telling the user why not. */
    private int selectedPending() {
        int id = Ui.selectedId(table);
        if (id < 0) {
            Ui.warn(this, "Select a claim request first.");
            return -1;
        }
        String st = Ui.selectedCell(table, "Status");
        if (!"Pending".equals(st)) {
            Ui.warn(this, "Only pending claims can be verified or rejected (this one is " + st + ").");
            return -1;
        }
        return id;
    }

    private void verify() {
        int id = selectedPending();
        if (id < 0) return;
        String who = Ui.selectedCell(table, "Claimant");
        String what = Ui.selectedCell(table, "Item");
        if (JOptionPane.showConfirmDialog(this,
                "Confirm that OSA verified onsite that " + who + " owns \"" + what + "\"?\nThe item will be released to this claimant.",
                "Verify claim #" + id, JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        try {
            Db.tx(c -> {
                int removed = Db.update(c,
                    "DELETE FROM unverified_request WHERE claim_request_id = ? AND rejection_reason IS NULL", id);
                if (removed == 0) throw new IllegalStateException("This claim is no longer pending.");
                Number released = (Number) Db.scalar(c,
                    "SELECT COUNT(*) FROM claim_request c JOIN verified_request v ON v.claim_request_id = c.claim_request_id "
                  + "WHERE c.item_id = (SELECT item_id FROM claim_request WHERE claim_request_id = ?)", id);
                if (released.intValue() > 0)
                    throw new IllegalStateException("This item was already released to another verified claimant.\nReject this claim instead.");
                Db.update(c, "INSERT INTO verified_request (claim_request_id, verification_date) VALUES (?,?)",
                        id, LocalDateTime.now());
                return id;
            });
            refresh();
        } catch (IllegalStateException e) {
            Ui.warn(this, e.getMessage());
            refresh();
        } catch (SQLException e) {
            Ui.error(this, e);
        }
    }

    private void reject() {
        int id = selectedPending();
        if (id < 0) return;
        String reason = JOptionPane.showInputDialog(this, "Reason for rejecting claim #" + id + ":",
                "Reject claim", JOptionPane.PLAIN_MESSAGE);
        if (reason == null) return;
        if (reason.isBlank()) {
            Ui.warn(this, "A rejection reason is required.");
            return;
        }
        try {
            int n = Db.tx(c -> Db.update(c,
                "UPDATE unverified_request SET rejection_reason = ? WHERE claim_request_id = ? AND rejection_reason IS NULL",
                reason.trim(), id));
            if (n == 0) Ui.warn(this, "This claim is no longer pending.");
            refresh();
        } catch (SQLException e) {
            Ui.error(this, e);
        }
    }
}
