package kairon.ui.swing.behaviorgraph;

import kairon.behavior.graph.EventOccurrenceSummary;
import kairon.behavior.model.EventOccurrenceId;

import javax.swing.table.AbstractTableModel;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Bulk-replace, read-only table model for active-episode occurrences.
 */
final class BehaviorGraphOccurrenceTableModel extends AbstractTableModel {

    static final String[] COLUMN_NAMES = {"#", "Time", "Source Event"};

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss")
                    .withZone(ZoneOffset.UTC);

    private List<EventOccurrenceSummary> rows = List.of();

    void setRows(List<EventOccurrenceSummary> newRows) {
        rows = List.copyOf(Objects.requireNonNull(newRows, "newRows"));
        fireTableDataChanged();
    }

    List<EventOccurrenceSummary> rows() {
        return rows;
    }

    EventOccurrenceSummary row(int modelRow) {
        return rows.get(modelRow);
    }

    OptionalInt findRow(EventOccurrenceId occurrenceId) {
        Objects.requireNonNull(occurrenceId, "occurrenceId");
        for (int index = 0; index < rows.size(); index++) {
            if (rows.get(index).occurrenceId().equals(occurrenceId)) {
                return OptionalInt.of(index);
            }
        }
        return OptionalInt.empty();
    }

    @Override
    public int getRowCount() {
        return rows.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMN_NAMES.length;
    }

    @Override
    public String getColumnName(int column) {
        return COLUMN_NAMES[column];
    }

    @Override
    public Class<?> getColumnClass(int columnIndex) {
        return columnIndex == 0 ? Long.class : String.class;
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        EventOccurrenceSummary row = rows.get(rowIndex);
        return switch (columnIndex) {
            case 0 -> row.episodeSequence();
            case 1 -> TIME_FORMAT.format(row.timestamp());
            case 2 -> row.originalEventName();
            default -> throw new IndexOutOfBoundsException(
                    "unknown occurrence table column: " + columnIndex
            );
        };
    }

    @Override
    public boolean isCellEditable(int rowIndex, int columnIndex) {
        return false;
    }
}
