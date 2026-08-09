package kairon.ui.swing;

import kairon.bio.OrganicRegistry.PricedOrganism;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * What every organism is worth, and which one was just collected.
 *
 * <p>The registry's price table, as it stands for the whole session — it is
 * read from a file at startup and never changes, so this is filled once. What
 * moves is the highlight: the row of the species whose sampling sequence the
 * Commander last finished, which is the one row worth finding without
 * scrolling.</p>
 *
 * <p>A view and only a view. It computes no price, applies no bonus and knows
 * nothing about first footfall — that claim belongs to the turn where it is
 * made, not to a table of what the game publishes.</p>
 */
final class OrganicRegistryTab extends JPanel {

    static final String TITLE = "Exobiology";

    private final OrganicPriceTableModel model = new OrganicPriceTableModel();
    private final JTable table = HudTheme.table(model);
    private final JLabel summary = HudTheme.mutedLabel("No registry");
    private final JLabel collected = HudTheme.mutedLabel(" ");

    OrganicRegistryTab() {
        super(new BorderLayout(0, 8));
        requireEdt();
        setOpaque(true);
        setBackground(HudTheme.BACKGROUND);
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel header = HudTheme.panel(new BorderLayout());
        header.add(summary, BorderLayout.WEST);
        header.add(collected, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        table.setDefaultRenderer(Object.class, new HighlightRenderer(
                table.getDefaultRenderer(Object.class)
        ));
        add(
                HudTheme.section("ORGANISMS", HudTheme.scroll(table)),
                BorderLayout.CENTER
        );
    }

    /** The whole price table, which arrives once and does not change. */
    void apply(List<PricedOrganism> organisms) {
        requireEdt();
        Objects.requireNonNull(organisms, "organisms");
        model.replaceWith(organisms);
        summary.setText(organisms.isEmpty()
                ? "No registry"
                : organisms.size() + " organisms priced");
    }

    /**
     * The species whose sequence was just analysed.
     *
     * <p>Held by identifier rather than by row, because the identifier is what
     * the game states and a row number is an accident of sorting. An organism
     * the registry does not price has no row to mark, and the previous mark is
     * cleared rather than left pointing at something older than the news.</p>
     */
    void highlight(String speciesIdentifier) {
        requireEdt();
        model.highlight(speciesIdentifier);
        int row = model.highlightedRow();
        if (row < 0) {
            collected.setText(" ");
            table.clearSelection();
            return;
        }
        collected.setText("Collected: " + model.nameAt(row));
        table.setRowSelectionInterval(row, row);
        Rectangle cell = table.getCellRect(row, 0, true);
        table.scrollRectToVisible(cell);
        table.repaint();
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException(
                    "the exobiology tab is only touched on the EDT"
            );
        }
    }

    /**
     * Paints the just-collected row in the accent colour.
     *
     * <p>Wraps the theme's own renderer rather than replacing it, so the table
     * keeps every other thing the theme decided about it.</p>
     */
    private final class HighlightRenderer implements TableCellRenderer {

        private final TableCellRenderer delegate;

        private HighlightRenderer(TableCellRenderer delegate) {
            this.delegate = delegate == null
                    ? new DefaultTableCellRenderer()
                    : delegate;
        }

        @Override
        public Component getTableCellRendererComponent(
                JTable table,
                Object value,
                boolean selected,
                boolean focused,
                int row,
                int column
        ) {
            Component component = delegate.getTableCellRendererComponent(
                    table, value, selected, focused, row, column
            );
            boolean marked = model.highlightedRow()
                    == table.convertRowIndexToModel(row);
            if (component instanceof JComponent painted) {
                // The theme's renderer draws nothing of its own background, so
                // a colour set on a transparent component is a colour nobody
                // sees. The marked row is the one row that paints itself.
                painted.setOpaque(marked);
            }
            component.setForeground(marked ? HudTheme.BACKGROUND : HudTheme.TEXT);
            component.setBackground(marked ? HudTheme.ACCENT : HudTheme.SURFACE);
            return component;
        }
    }

    /** Two columns: what it is and what it pays. */
    private static final class OrganicPriceTableModel extends AbstractTableModel {

        private static final String[] COLUMNS = {"Organism", "Value (Cr)"};

        private final List<PricedOrganism> rows = new ArrayList<>();

        private String highlighted;

        void replaceWith(List<PricedOrganism> organisms) {
            rows.clear();
            rows.addAll(organisms);
            fireTableDataChanged();
        }

        void highlight(String speciesIdentifier) {
            highlighted = speciesIdentifier;
            fireTableDataChanged();
        }

        int highlightedRow() {
            if (highlighted == null) {
                return -1;
            }
            for (int row = 0; row < rows.size(); row++) {
                if (highlighted.equals(rows.get(row).identifier())) {
                    return row;
                }
            }
            return -1;
        }

        String nameAt(int row) {
            return rows.get(row).name();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            PricedOrganism organism = rows.get(row);
            return column == 0
                    ? organism.name()
                    : String.format(Locale.ROOT, "%,d", organism.valueCr());
        }
    }
}
