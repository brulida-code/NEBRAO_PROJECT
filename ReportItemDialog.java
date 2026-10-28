import javax.swing.*;
import java.awt.*;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Report a found or lost item: name, description, category, location, date/time, photos. */
public class ReportItemDialog extends JDialog {

    private static final String[] DEFAULT_CATEGORIES =
            {"Electronics", "Bottle", "Clothing", "Keys", "Wallet", "Stationery", "Personal Accessory", "Other"};

    private final JComboBox<Db.Choice> reporter = new JComboBox<>();
    private final JRadioButton found = new JRadioButton("Found item", true);
    private final JRadioButton lost = new JRadioButton("Lost item");
    private final JCheckBox surrendered = new JCheckBox("Item was physically surrendered to OSA (confirmed by OSA personnel)");
    private final JTextField name = new JTextField(24);
    private final JComboBox<String> category = new JComboBox<>();
    private final JComboBox<Db.Choice> location = new JComboBox<>();
    private final JTextField when = new JTextField(LocalDateTime.now().format(Ui.DATE_TIME), 16);
    private final JTextArea description = new JTextArea(4, 24);
    private final JTextArea photos = new JTextArea(3, 24);
    private boolean saved;

    public ReportItemDialog(Window owner) {
        super(owner, "Report Item", ModalityType.APPLICATION_MODAL);

        ButtonGroup g = new ButtonGroup();
        g.add(found);
        g.add(lost);
        found.addActionListener(e -> toggleType());
        lost.addActionListener(e -> toggleType());
        category.setEditable(true);
        description.setLineWrap(true);
        description.setWrapStyleWord(true);

        try {
            for (Db.Choice c : Db.choices(
                    "SELECT u.user_id, CONCAT(u.first_name, ' ', u.last_name, ' <', u.email, '>') "
                  + "FROM app_user u JOIN reporter r ON r.user_id = u.user_id ORDER BY u.last_name, u.first_name")) {
                reporter.addItem(c);
            }
            Set<String> cats = new LinkedHashSet<>(List.of(DEFAULT_CATEGORIES));
            cats.addAll(Db.strings("SELECT DISTINCT item_category FROM item ORDER BY item_category"));
            for (String c : cats) category.addItem(c);
            category.setSelectedIndex(0);
            reloadLocations(null);
        } catch (SQLException e) {
            Ui.error(this, e);
        }

        JButton newLoc = new JButton("+");
        newLoc.setToolTipText("Add a new location");
        newLoc.addActionListener(e -> addLocation());
        JPanel locRow = new JPanel(new BorderLayout(4, 0));
        locRow.add(location, BorderLayout.CENTER);
        locRow.add(newLoc, BorderLayout.EAST);

        JPanel typeRow = Ui.flow(found, lost);
        JPanel photoBox = new JPanel(new BorderLayout());
        photoBox.add(new JScrollPane(photos), BorderLayout.CENTER);
        photoBox.add(new JLabel("One photo URL/path per line (at least one)"), BorderLayout.SOUTH);

        JPanel form = Ui.form(
            new String[]{"Reporter", "Type", "", "Item name", "Category", "Location", "Date & time (yyyy-MM-dd HH:mm)", "Description", "Photos"},
            new Component[]{reporter, typeRow, surrendered, name, category, locRow, when, new JScrollPane(description), photoBox});

        JButton save = new JButton("Save");
        JButton cancel = new JButton("Cancel");
        save.addActionListener(e -> save());
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(save);
        buttons.add(cancel);

        setLayout(new BorderLayout());
        add(form, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(save);
        pack();
        setLocationRelativeTo(owner);
    }

    public boolean saved() { return saved; }

    private void toggleType() {
        surrendered.setEnabled(found.isSelected());
        if (!found.isSelected()) surrendered.setSelected(false);
    }

    private void reloadLocations(Integer selectId) throws SQLException {
        location.removeAllItems();
        for (Db.Choice c : Db.choices("SELECT location_id, location_name FROM location ORDER BY location_name")) {
            location.addItem(c);
            if (selectId != null && c.id() == selectId) location.setSelectedItem(c);
        }
    }

    private void addLocation() {
        String n = JOptionPane.showInputDialog(this, "New location name:", "Add location", JOptionPane.PLAIN_MESSAGE);
        if (n == null || n.isBlank()) return;
        n = n.trim();
        if (n.length() > 100) {
            Ui.warn(this, "Location name must be 100 characters or fewer.");
            return;
        }
        final String locName = n;
        try {
            int id = Db.tx(c -> Db.insert(c, "INSERT INTO location (location_name) VALUES (?)", locName));
            reloadLocations(id);
        } catch (SQLException e) {
            if (e.getErrorCode() == 1062) Ui.warn(this, "That location already exists.");
            else Ui.error(this, e);
        }
    }

    private void save() {
        try {
            Db.Choice rep = (Db.Choice) reporter.getSelectedItem();
            Db.Choice loc = (Db.Choice) location.getSelectedItem();
            if (rep == null) throw new IllegalArgumentException("No reporters exist yet. Register a user with the Reporter role first.");
            if (loc == null) throw new IllegalArgumentException("Choose or add a location.");

            String itemName = name.getText().trim();
            String cat = String.valueOf(category.getEditor().getItem()).trim();
            String desc = description.getText().trim();
            if (itemName.isEmpty()) throw new IllegalArgumentException("Item name is required.");
            if (itemName.length() > 100) throw new IllegalArgumentException("Item name must be 100 characters or fewer.");
            if (cat.isEmpty()) throw new IllegalArgumentException("Category is required.");
            if (cat.length() > 50) throw new IllegalArgumentException("Category must be 50 characters or fewer.");
            if (desc.isEmpty()) throw new IllegalArgumentException("Description is required (OSA uses it to verify ownership).");

            LocalDateTime at = Ui.parseDateTime(when.getText());
            if (at.isAfter(LocalDateTime.now())) throw new IllegalArgumentException("Date and time cannot be in the future.");

            List<String> urls = new ArrayList<>();
            for (String line : photos.getText().split("\\R")) {
                if (!line.isBlank()) urls.add(line.trim());
            }
            if (urls.isEmpty()) throw new IllegalArgumentException("Add at least one photo URL or path.");

            boolean isFound = found.isSelected();
            if (isFound && !surrendered.isSelected())
                throw new IllegalArgumentException("A found item can only be recorded after OSA confirms it was physically surrendered.");

            Db.tx(c -> {
                int id = Db.insert(c,
                    "INSERT INTO item (location_id, reporter_id, item_name, item_description, item_category) VALUES (?,?,?,?,?)",
                    loc.id(), rep.id(), itemName, desc, cat);
                if (isFound) Db.update(c, "INSERT INTO found_item (item_id, found_at) VALUES (?,?)", id, at);
                else Db.update(c, "INSERT INTO lost_item (item_id, lost_at) VALUES (?,?)", id, at);
                for (String url : urls) Db.update(c, "INSERT INTO item_photo (item_id, photo_url) VALUES (?,?)", id, url);
                return id;
            });
            saved = true;
            dispose();
        } catch (IllegalArgumentException e) {
            Ui.warn(this, e.getMessage());
        } catch (SQLException e) {
            Ui.error(this, e);
        }
    }
}
