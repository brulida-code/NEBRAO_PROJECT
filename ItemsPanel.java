import javax.swing.*;
import java.awt.*;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Search/filter items by category, date, location and description; report new items. */
public class ItemsPanel extends JPanel implements Ui.Refreshable {

    private static final String ALL_CATEGORIES = "All categories";
    private static final String ALL_LOCATIONS = "All locations";

    private final JTextField keyword = new JTextField(14);
    private final JTextField from = new JTextField(8);
    private final JTextField to = new JTextField(8);
    private final JComboBox<String> category = new JComboBox<>();
    private final JComboBox<Object> location = new JComboBox<>();
    private final JComboBox<String> type = new JComboBox<>(new String[]{"All types", "Found", "Lost"});
    private final JComboBox<String> status = new JComboBox<>(new String[]{"All status", "Open", "Released"});
    private final JTable table = Ui.table(new javax.swing.table.DefaultTableModel());
    private final JLabel count = new JLabel(" ");
    private boolean loadingFilters;

    public ItemsPanel() {
        super(new BorderLayout(6, 6));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JButton search = new JButton("Search");
        JButton clear = new JButton("Clear");
        search.addActionListener(e -> search());
        clear.addActionListener(e -> clearFilters());
        keyword.addActionListener(e -> search());

        JPanel filters = new JPanel(new GridLayout(2, 1));
        filters.add(Ui.flow(new JLabel("Search:"), keyword, category, location, type, status));
        filters.add(Ui.flow(new JLabel("From (yyyy-MM-dd):"), from, new JLabel("To:"), to, search, clear));
        add(filters, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);

        JButton report = new JButton("Report Item...");
        JButton details = new JButton("View Details");
        report.addActionListener(e -> reportItem());
        details.addActionListener(e -> showDetails());
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) showDetails();
            }
        });
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(Ui.flow(report, details), BorderLayout.WEST);
        bottom.add(count, BorderLayout.EAST);
        add(bottom, BorderLayout.SOUTH);

        refresh();
    }

    @Override
    public void refresh() {
        loadFilters();
        search();
    }

    private void loadFilters() {
        loadingFilters = true;
        try {
            Object oldCat = category.getSelectedItem();
            Object oldLoc = location.getSelectedItem();
            category.removeAllItems();
            category.addItem(ALL_CATEGORIES);
            for (String c : Db.strings("SELECT DISTINCT item_category FROM item ORDER BY item_category")) category.addItem(c);
            if (oldCat != null) category.setSelectedItem(oldCat);

            location.removeAllItems();
            location.addItem(ALL_LOCATIONS);
            for (Db.Choice c : Db.choices("SELECT location_id, location_name FROM location ORDER BY location_name")) location.addItem(c);
            if (oldLoc != null) location.setSelectedItem(oldLoc);
        } catch (SQLException e) {
            Ui.error(this, e);
        } finally {
            loadingFilters = false;
        }
    }

    private void clearFilters() {
        keyword.setText("");
        from.setText("");
        to.setText("");
        category.setSelectedIndex(0);
        location.setSelectedIndex(0);
        type.setSelectedIndex(0);
        status.setSelectedIndex(0);
        search();
    }

    private void search() {
        if (loadingFilters) return;
        try {
            StringBuilder where = new StringBuilder(" WHERE 1=1");
            List<Object> p = new ArrayList<>();

            String kw = keyword.getText().trim();
            if (!kw.isEmpty()) {
                where.append(" AND (i.item_name LIKE ? OR i.item_description LIKE ?)");
                p.add("%" + kw + "%");
                p.add("%" + kw + "%");
            }
            if (category.getSelectedIndex() > 0) {
                where.append(" AND i.item_category = ?");
                p.add(category.getSelectedItem());
            }
            if (location.getSelectedItem() instanceof Db.Choice loc) {
                where.append(" AND i.location_id = ?");
                p.add(loc.id());
            }
            if (type.getSelectedIndex() == 1) where.append(" AND f.item_id IS NOT NULL");
            if (type.getSelectedIndex() == 2) where.append(" AND ls.item_id IS NOT NULL");
            if (status.getSelectedIndex() == 1) where.append(" AND NOT ").append(Db.ITEM_RELEASED);
            if (status.getSelectedIndex() == 2) where.append(" AND ").append(Db.ITEM_RELEASED);
            if (!from.getText().isBlank()) {
                LocalDate d = Ui.parseDate(from.getText());
                where.append(" AND COALESCE(f.found_at, ls.lost_at) >= ?");
                p.add(d.atStartOfDay());
            }
            if (!to.getText().isBlank()) {
                LocalDate d = Ui.parseDate(to.getText());
                where.append(" AND COALESCE(f.found_at, ls.lost_at) < ?");
                p.add(d.plusDays(1).atStartOfDay());
            }

            String sql =
                "SELECT i.item_id AS ID, i.item_name AS Item, "
              + "CASE WHEN f.item_id IS NOT NULL THEN 'Found' ELSE 'Lost' END AS Type, "
              + "i.item_category AS Category, l.location_name AS Location, "
              + "COALESCE(f.found_at, ls.lost_at) AS `Date & Time`, "
              + "CONCAT(u.first_name, ' ', u.last_name) AS Reporter, "
              + "CASE WHEN " + Db.ITEM_RELEASED + " THEN 'Released' ELSE 'Open' END AS Status "
              + "FROM item i "
              + "JOIN location l ON l.location_id = i.location_id "
              + "JOIN app_user u ON u.user_id = i.reporter_id "
              + "LEFT JOIN found_item f ON f.item_id = i.item_id "
              + "LEFT JOIN lost_item ls ON ls.item_id = i.item_id"
              + where
              + " ORDER BY COALESCE(f.found_at, ls.lost_at) DESC";

            Ui.setModel(table, Db.table(sql, p.toArray()));
            count.setText(table.getRowCount() + " item(s)");
        } catch (IllegalArgumentException e) {
            Ui.warn(this, e.getMessage());
        } catch (SQLException e) {
            Ui.error(this, e);
        }
    }

    private void reportItem() {
        ReportItemDialog dlg = new ReportItemDialog(SwingUtilities.getWindowAncestor(this));
        dlg.setVisible(true);
        if (dlg.saved()) refresh();
    }

    private void showDetails() {
        int id = Ui.selectedId(table);
        if (id < 0) {
            Ui.warn(this, "Select an item first.");
            return;
        }
        try {
            StringBuilder sb = new StringBuilder();
            Db.Result item = Db.query(
                "SELECT i.item_name, IF(f.item_id IS NOT NULL, 'Found', 'Lost'), i.item_category, l.location_name, "
              + "COALESCE(f.found_at, ls.lost_at), CONCAT(u.first_name, ' ', u.last_name), u.email, u.contact_number, "
              + "i.item_description, IF(" + Db.ITEM_RELEASED + ", 'Released to verified claimant', 'Open') "
              + "FROM item i JOIN location l ON l.location_id = i.location_id "
              + "JOIN app_user u ON u.user_id = i.reporter_id "
              + "LEFT JOIN found_item f ON f.item_id = i.item_id "
              + "LEFT JOIN lost_item ls ON ls.item_id = i.item_id WHERE i.item_id = ?", id);
            Object[] r = item.rows().get(0);
            sb.append("Item #").append(id).append(": ").append(r[0]).append('\n')
              .append("Type:        ").append(r[1]).append('\n')
              .append("Category:    ").append(r[2]).append('\n')
              .append("Location:    ").append(r[3]).append('\n')
              .append("Date & time: ").append(r[4]).append('\n')
              .append("Reporter:    ").append(r[5]).append("  <").append(r[6]).append(">  ").append(r[7]).append('\n')
              .append("Status:      ").append(r[9]).append("\n\n")
              .append("Description:\n").append(r[8]).append("\n\n")
              .append("Photos:\n");
            for (String url : Db.strings("SELECT photo_url FROM item_photo WHERE item_id = ? ORDER BY item_photo_id", id)) {
                sb.append("  - ").append(url).append('\n');
            }
            sb.append("\nClaim requests:\n");
            Db.Result claims = Db.query(
                "SELECT c.claim_request_id, CONCAT(cu.first_name, ' ', cu.last_name), c.request_date, " + Db.CLAIM_STATUS
              + ", u.rejection_reason FROM claim_request c "
              + "JOIN app_user cu ON cu.user_id = c.claimant_id "
              + "LEFT JOIN verified_request v ON v.claim_request_id = c.claim_request_id "
              + "LEFT JOIN unverified_request u ON u.claim_request_id = c.claim_request_id "
              + "WHERE c.item_id = ? ORDER BY c.request_date", id);
            if (claims.rows().isEmpty()) sb.append("  (none)\n");
            for (Object[] c : claims.rows()) {
                sb.append("  #").append(c[0]).append("  ").append(c[1]).append("  ").append(c[2]).append("  ").append(c[3]);
                if (!String.valueOf(c[4]).isEmpty()) sb.append("  - ").append(c[4]);
                sb.append('\n');
            }

            JTextArea area = new JTextArea(sb.toString(), 22, 60);
            area.setEditable(false);
            area.setLineWrap(true);
            area.setWrapStyleWord(true);
            area.setCaretPosition(0);
            JOptionPane.showMessageDialog(this, new JScrollPane(area), "Item details", JOptionPane.PLAIN_MESSAGE);
        } catch (SQLException e) {
            Ui.error(this, e);
        }
    }
}
