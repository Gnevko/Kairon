package kairon.ui.swing;

import kairon.system.BodyKnowledgeLevel;
import kairon.system.BodyParent;
import kairon.system.PlanetBody;
import kairon.system.StarBody;
import kairon.system.SystemObject;
import kairon.system.SystemRegistrySnapshot;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.event.ListSelectionEvent;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The star system the Commander is in, as the registry currently holds it.
 *
 * <p>A view and only a view. It never mutates the snapshot it is handed and
 * never computes a fact the registry did not record: everything below is either
 * a field of a {@link SystemObject} or the ordering the parent chains already
 * state. A blank cell is a fact nobody has established, which is the difference
 * this whole subsystem exists to keep.</p>
 */
final class SystemRegistryTab extends JPanel {

    static final String TITLE = "System Registry";

    private final SystemRegistryTableModel model =
            new SystemRegistryTableModel();
    private final JTable table = HudTheme.table(model);
    private final JTextArea detail = HudTheme.detailArea();
    private final JLabel summary = HudTheme.mutedLabel("No system");

    SystemRegistryTab() {
        super(new BorderLayout(0, 8));
        requireEdt();
        setOpaque(true);
        setBackground(HudTheme.BACKGROUND);
        setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel header = HudTheme.panel(new BorderLayout());
        header.add(summary, BorderLayout.WEST);
        add(header, BorderLayout.NORTH);
        add(HudTheme.section(
                "BODIES",
                HudTheme.verticalSplit(
                        HudTheme.scroll(table),
                        HudTheme.scroll(detail),
                        0.62
                )
        ), BorderLayout.CENTER);

        table.getSelectionModel().addListSelectionListener(
                this::selectionChanged
        );
    }

    /**
     * Shows a snapshot, keeping the selected body selected where it survived.
     *
     * <p>The registry is rebuilt on every observation, so a selection held by
     * row index would wander down the table as bodies are discovered. It is
     * held by body id instead, and simply lost when that body is not in the new
     * system.</p>
     */
    void apply(SystemRegistrySnapshot snapshot) {
        requireEdt();
        Objects.requireNonNull(snapshot, "snapshot");
        Long selected = selectedBodyId();
        model.apply(snapshot);
        summary.setText(describe(snapshot));
        int row = selected == null ? -1 : model.rowOf(selected);
        if (row >= 0) {
            table.setRowSelectionInterval(row, row);
        }
        showSelected();
    }

    private Long selectedBodyId() {
        int row = table.getSelectedRow();
        return row < 0 || row >= model.getRowCount()
                ? null
                : model.objectAt(row).bodyId();
    }

    private static String describe(SystemRegistrySnapshot snapshot) {
        if (!snapshot.available()) {
            return "Registry unavailable";
        }
        if (snapshot.systemAddress() == null) {
            return "No system";
        }
        StringBuilder text = new StringBuilder(
                snapshot.systemName() == null
                        ? "System " + snapshot.systemAddress()
                        : snapshot.systemName()
        );
        text.append("   ").append(snapshot.scannedCount())
                .append(" scanned of ").append(snapshot.objects().size())
                .append(" known");
        if (snapshot.bodyCount() != null) {
            text.append(", ").append(snapshot.bodyCount())
                    .append(" reported by the discovery scan");
        }
        if (snapshot.allBodiesFound()) {
            text.append(", all bodies found");
        }
        return text.toString();
    }

    private void selectionChanged(ListSelectionEvent event) {
        if (!event.getValueIsAdjusting()) {
            showSelected();
        }
    }

    private void showSelected() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= model.getRowCount()) {
            detail.setText("");
            return;
        }
        detail.setText(details(model.objectAt(row)));
    }

    /**
     * Everything recorded about one object, absent facts included as absent.
     *
     * <p>{@code <unknown>} rather than a blank or a zero: the registry
     * distinguishes "nobody has established this" from every value, and a view
     * that rendered the two alike would undo the distinction on screen.</p>
     */
    private static String details(SystemObject object) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("bodyId", object.bodyId());
        fields.put("name", object.name());
        fields.put("kind", object.kind());
        fields.put("knowledge", object.knowledge());
        fields.put("source", object.profile().source());
        fields.put("orbits", describeParents(object.parents()));
        fields.put(
                "distanceFromArrivalLs",
                object.profile().distanceFromArrivalLs()
        );
        fields.put("wasDiscovered", object.profile().wasDiscovered());
        fields.put("wasMapped", object.profile().wasMapped());
        fields.put("wasFootfalled", object.profile().wasFootfalled());
        if (object instanceof StarBody star) {
            fields.put("starType", star.starType());
            fields.put("subclass", star.subclass());
            fields.put("luminosity", star.luminosity());
            fields.put("stellarMass", star.stellarMass());
            fields.put("ageMillionYears", star.ageMillionYears());
            fields.put("surfaceTemperature", star.surfaceTemperature());
        }
        if (object instanceof PlanetBody planet) {
            fields.put("planetClass", planet.planetClass());
            fields.put("moon", planet.isMoon());
            fields.put("landable", planet.landable());
            fields.put("atmosphere", planet.atmosphere());
            fields.put("volcanism", planet.volcanism());
            fields.put("terraformState", planet.terraformState());
            fields.put("surfaceGravity", planet.surfaceGravity());
            fields.put("surfaceTemperature", planet.surfaceTemperature());
            fields.put("surfacePressure", planet.surfacePressure());
        }
        object.profile().signalCounts().forEach((category, count) ->
                fields.put("signal " + category, count));
        if (!object.biology().isEmpty()) {
            object.biology().genera().forEach((identifier, label) ->
                    fields.put(
                            // The identifier when there is no word for it. This
                            // is a developer's view of what was recorded, not
                            // the model's, so the raw symbol is the honest
                            // fallback here and never on the wire.
                            "genus " + (label == null ? identifier : label),
                            object.biology().completed().contains(identifier)
                                    ? "collected"
                                    : "not collected"
                    ));
            object.biology().completed().stream()
                    .filter(identifier ->
                            !object.biology().genera()
                                    .containsKey(identifier))
                    .forEach(identifier -> fields.put(
                            "genus " + identifier,
                            "collected, not reported by any survey"
                    ));
        }

        StringBuilder text = new StringBuilder();
        fields.forEach((name, value) -> text
                .append(name)
                .append(": ")
                .append(value == null || "".equals(value)
                        ? "<unknown>"
                        : value)
                .append('\n'));
        return text.toString();
    }

    private static String describeParents(List<BodyParent> parents) {
        if (parents.isEmpty()) {
            return null;
        }
        List<String> links = new ArrayList<>(parents.size());
        for (BodyParent parent : parents) {
            links.add(parent.kind() + " " + parent.bodyId());
        }
        return String.join(" -> ", links);
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException(
                    "System registry view changes must run on the EDT"
            );
        }
    }

    /**
     * The registry flattened into rows, children under their parents.
     *
     * <p>The order is the one the parent chains state, and the indentation is
     * the depth of the chain. Nothing is sorted by name, and no tree is built:
     * a body whose chain has not been stated is a root here, which is exactly
     * what is known about it.</p>
     */
    static final class SystemRegistryTableModel extends AbstractTableModel {

        private static final String[] COLUMNS = {
                "BODY", "KIND", "KNOWN", "DISTANCE LS", "SIGNALS", "BIOLOGY"
        };

        private final List<Row> rows = new ArrayList<>();

        void apply(SystemRegistrySnapshot snapshot) {
            rows.clear();
            Set<Long> placed = new HashSet<>();
            appendChildren(snapshot, null, 0, placed);
            fireTableDataChanged();
        }

        int rowOf(long bodyId) {
            for (int index = 0; index < rows.size(); index++) {
                if (rows.get(index).object.bodyId() == bodyId) {
                    return index;
                }
            }
            return -1;
        }

        SystemObject objectAt(int row) {
            return rows.get(row).object;
        }

        /**
         * The objects under one parent, then their own children.
         *
         * <p>A body whose stated parent is not in the snapshot is placed at the
         * top rather than dropped. That is the honest rendering — its place is
         * known and the thing it names is not recorded — and a view that
         * silently omitted it would be showing fewer bodies than the registry
         * holds, which is the one thing this table must never do. {@code
         * placed} guards the same property against a chain that somehow names
         * an ancestor twice.</p>
         */
        private void appendChildren(
                SystemRegistrySnapshot snapshot,
                Long parentId,
                int depth,
                Set<Long> placed
        ) {
            snapshot.objects().values().stream()
                    .filter(object -> !placed.contains(object.bodyId()))
                    .filter(object -> Objects.equals(
                            parentId,
                            attachmentOf(snapshot, object)
                    ))
                    .sorted(Comparator.comparingLong(SystemObject::bodyId))
                    .toList()
                    .forEach(object -> {
                        placed.add(object.bodyId());
                        rows.add(new Row(depth, object));
                        appendChildren(
                                snapshot,
                                object.bodyId(),
                                depth + 1,
                                placed
                        );
                    });
        }

        /** The body this one is shown under, or null when it is a root here. */
        private static Long attachmentOf(
                SystemRegistrySnapshot snapshot,
                SystemObject object
        ) {
            BodyParent parent = object.immediateParent();
            if (parent == null
                    || snapshot.object(parent.bodyId()) == null) {
                return null;
            }
            return parent.bodyId();
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
        public Object getValueAt(int rowIndex, int columnIndex) {
            Row row = rows.get(rowIndex);
            SystemObject object = row.object;
            return switch (columnIndex) {
                case 0 -> "   ".repeat(row.depth)
                        + (object.name() == null
                                ? "body " + object.bodyId()
                                : object.name());
                case 1 -> object.kind();
                case 2 -> object.knowledge() == BodyKnowledgeLevel.LISTED
                        ? ""
                        : object.knowledge();
                case 3 -> object.profile().distanceFromArrivalLs();
                case 4 -> describeSignals(object);
                case 5 -> describeBiology(object);
                default -> "";
            };
        }

        private static String describeSignals(SystemObject object) {
            if (object.profile().signalCounts().isEmpty()) {
                return "";
            }
            List<String> counts = new ArrayList<>();
            object.profile().signalCounts().forEach((category, count) ->
                    counts.add(category.charAt(0) + ":" + count));
            return String.join(" ", counts);
        }

        /**
         * How much of what grows there has been collected.
         *
         * <p>Blank when no survey has named anything, because the count of
         * biological signals alone says how many there are and not which — and
         * a "0 of 3" against no names would read as three known things nobody
         * has collected.</p>
         */
        private static String describeBiology(SystemObject object) {
            if (object.biology().genera().isEmpty()) {
                return object.biology().completed().isEmpty()
                        ? ""
                        : object.biology().completed().size() + " collected";
            }
            return object.biology().completed().size()
                    + " of "
                    + object.biology().genera().size()
                    + " collected";
        }

        private record Row(int depth, SystemObject object) {
        }
    }
}
