package kairon.ui.swing;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.table.TableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.LayoutManager;

/**
 * Central HUD design tokens and Swing control factories.
 */
final class HudTheme {

    static final Color BACKGROUND = new Color(0x07, 0x10, 0x19);
    static final Color SURFACE = new Color(0x0D, 0x1B, 0x26);
    static final Color SURFACE_ALT = new Color(0x11, 0x28, 0x38);
    static final Color TEXT = new Color(0xD7, 0xED, 0xF7);
    static final Color MUTED = new Color(0x78, 0xA1, 0xB2);
    static final Color ACCENT = new Color(0x26, 0xD0, 0xCE);
    static final Color GRID = new Color(0x1E, 0x3B, 0x4A);

    private static final Border SECTION_BORDER =
            BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(GRID),
                    BorderFactory.createEmptyBorder(8, 8, 8, 8)
            );

    private HudTheme() {
    }

    static void install() {
        requireEdt();
        UIManager.put("Panel.background", BACKGROUND);
        UIManager.put("Label.foreground", TEXT);
        UIManager.put("Table.background", SURFACE);
        UIManager.put("Table.foreground", TEXT);
        UIManager.put("Table.selectionBackground", SURFACE_ALT);
        UIManager.put("Table.selectionForeground", ACCENT);
        UIManager.put("TableHeader.background", SURFACE_ALT);
        UIManager.put("TableHeader.foreground", TEXT);
        UIManager.put("TextArea.background", SURFACE);
        UIManager.put("TextArea.foreground", TEXT);
        UIManager.put("SplitPane.background", BACKGROUND);
        UIManager.put("TabbedPane.background", BACKGROUND);
        UIManager.put("TabbedPane.foreground", TEXT);
        UIManager.put("TabbedPane.selected", SURFACE_ALT);
        UIManager.put("TabbedPane.contentAreaColor", BACKGROUND);
        UIManager.put("TabbedPane.focus", ACCENT);
        UIManager.put("TabbedPane.highlight", GRID);
        UIManager.put("TabbedPane.shadow", GRID);
        UIManager.put("Kairon.Graph.background", BACKGROUND);
        UIManager.put("Kairon.Graph.border", GRID);
        UIManager.put("Kairon.Graph.muted", MUTED);
        UIManager.put("Kairon.Graph.nodeFill", SURFACE_ALT);
        UIManager.put("Kairon.Graph.nodeOutline", MUTED);
        UIManager.put("Kairon.Graph.currentNode", ACCENT);
        UIManager.put("Kairon.Graph.currentNodeOutline", TEXT);
        UIManager.put("Kairon.Graph.edge", TEXT);
        UIManager.put("Kairon.Graph.error", new Color(0xFF, 0x87, 0x66));
    }

    static JPanel panel(LayoutManager layout) {
        JPanel panel = new JPanel(layout);
        panel.setBackground(BACKGROUND);
        return panel;
    }

    static JLabel title(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(ACCENT);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 22.0f));
        return label;
    }

    static JLabel mutedLabel(String text) {
        JLabel label = new JLabel(text, SwingConstants.RIGHT);
        label.setForeground(MUTED);
        return label;
    }

    static JPanel section(String title, JComponent content) {
        JPanel section = panel(new BorderLayout(0, 8));
        section.setBorder(SECTION_BORDER);
        JLabel heading = new JLabel(title);
        heading.setForeground(ACCENT);
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 13.0f));
        section.add(heading, BorderLayout.NORTH);
        section.add(content, BorderLayout.CENTER);
        return section;
    }

    static JTable table(TableModel model) {
        JTable table = new JTable(model);
        table.setBackground(SURFACE);
        table.setForeground(TEXT);
        table.setSelectionBackground(SURFACE_ALT);
        table.setSelectionForeground(ACCENT);
        table.setGridColor(GRID);
        table.setRowHeight(24);
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setBackground(SURFACE_ALT);
        table.getTableHeader().setForeground(TEXT);
        table.getTableHeader().setReorderingAllowed(false);
        return table;
    }

    static JTextArea detailArea() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(false);
        area.setBackground(SURFACE);
        area.setForeground(TEXT);
        area.setCaretColor(ACCENT);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        return area;
    }

    static JScrollPane scroll(Component content) {
        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(BorderFactory.createLineBorder(GRID));
        scroll.getViewport().setBackground(SURFACE);
        return scroll;
    }

    static JTabbedPane tabbedPane() {
        JTabbedPane tabs = new JTabbedPane(
                SwingConstants.TOP,
                JTabbedPane.SCROLL_TAB_LAYOUT
        );
        tabs.setBackground(BACKGROUND);
        tabs.setForeground(TEXT);
        tabs.setFont(tabs.getFont().deriveFont(Font.BOLD, 13.0f));
        tabs.setBorder(BorderFactory.createLineBorder(GRID));
        return tabs;
    }

    static JSplitPane verticalSplit(
            Component top,
            Component bottom,
            double resizeWeight
    ) {
        JSplitPane split = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT,
                top,
                bottom
        );
        split.setResizeWeight(resizeWeight);
        split.setContinuousLayout(true);
        split.setBorder(null);
        split.setDividerSize(7);
        split.setBackground(BACKGROUND);
        return split;
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException(
                    "HUD controls must be created on the Swing EDT"
            );
        }
    }
}
