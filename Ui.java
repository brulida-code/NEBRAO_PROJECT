import javax.swing.*;
import javax.swing.table.TableModel;
import java.awt.*;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** Shared Swing helpers. */
public final class Ui {

    public static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    public static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private Ui() {}

    public static JTable table(TableModel model) {
        JTable t = new JTable(model);
        t.setAutoCreateRowSorter(true);
        t.setFillsViewportHeight(true);
        t.setRowHeight(24);
        t.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        t.getTableHeader().setReorderingAllowed(false);
        return t;
    }

    /** Swaps in a new model and keeps the columns readable. */
    public static void setModel(JTable t, TableModel model) {
        t.setModel(model);
        for (int i = 0; i < t.getColumnCount(); i++) {
            String name = t.getColumnName(i);
            int w = name.equals("ID") ? 50 : 130;
            if (name.equals("Details") || name.equals("Reason") || name.equals("Detail")) w = 320;
            t.getColumnModel().getColumn(i).setPreferredWidth(w);
        }
    }

    /** ID stored in column 0 of the selected row, or -1 when nothing is selected. */
    public static int selectedId(JTable t) {
        int row = t.getSelectedRow();
        if (row < 0) return -1;
        Object v = t.getModel().getValueAt(t.convertRowIndexToModel(row), 0);
        return ((Number) v).intValue();
    }

    public static String selectedCell(JTable t, String column) {
        int row = t.getSelectedRow();
        if (row < 0) return null;
        int col = t.getColumnModel().getColumnIndex(column);
        return String.valueOf(t.getValueAt(row, col));
    }

    public static void warn(Component parent, String message) {
        JOptionPane.showMessageDialog(parent, message, "FoundIt", JOptionPane.WARNING_MESSAGE);
    }

    public static void info(Component parent, String message) {
        JOptionPane.showMessageDialog(parent, message, "FoundIt", JOptionPane.INFORMATION_MESSAGE);
    }

    public static void error(Component parent, Exception e) {
        String msg = e.getMessage();
        if (e instanceof SQLException s) {
            if (s.getErrorCode() == 1062) msg = "A record with the same unique value already exists.";
            else if (s.getErrorCode() == 1451 || s.getErrorCode() == 1452)
                msg = "This change conflicts with related records.\n" + s.getMessage();
        }
        e.printStackTrace();
        JOptionPane.showMessageDialog(parent, msg, "Database error", JOptionPane.ERROR_MESSAGE);
    }

    public static LocalDate parseDate(String text) {
        try {
            return LocalDate.parse(text.trim(), DATE);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Date must look like 2026-10-28 (yyyy-MM-dd).");
        }
    }

    public static LocalDateTime parseDateTime(String text) {
        try {
            return LocalDateTime.parse(text.trim(), DATE_TIME);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Date and time must look like 2026-10-28 14:30 (yyyy-MM-dd HH:mm).");
        }
    }

    /** Two-column label/field form. */
    public static JPanel form(String[] labels, Component[] fields) {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(4, 6, 4, 6);
        g.anchor = GridBagConstraints.NORTHWEST;
        for (int i = 0; i < labels.length; i++) {
            g.gridx = 0; g.gridy = i; g.weightx = 0; g.fill = GridBagConstraints.NONE;
            p.add(new JLabel(labels[i]), g);
            g.gridx = 1; g.weightx = 1; g.fill = GridBagConstraints.HORIZONTAL;
            p.add(fields[i], g);
        }
        return p;
    }

    public static JPanel flow(Component... parts) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        for (Component c : parts) p.add(c);
        return p;
    }

    /** Implemented by tab panels so the main window can reload them when shown. */
    public interface Refreshable { void refresh(); }
}
