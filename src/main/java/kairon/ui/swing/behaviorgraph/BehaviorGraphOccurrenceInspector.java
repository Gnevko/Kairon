package kairon.ui.swing.behaviorgraph;

import kairon.behavior.graph.ActiveEpisodeNodeOccurrencesSnapshot;
import kairon.behavior.graph.EventOccurrenceDetailsSnapshot;
import kairon.behavior.graph.EventOccurrenceSummary;
import kairon.behavior.model.EventOccurrenceId;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Consumer;

/**
 * Read-only active-episode occurrence list and details surface.
 */
final class BehaviorGraphOccurrenceInspector extends JPanel {

    static final String PANEL_TITLE = "Event Occurrences";
    static final String DETAILS_TITLE = "Occurrence Details";
    static final String INITIAL_MESSAGE =
            "Select a graph node to view its occurrences.";
    static final String NO_ACTIVE_EPISODE_MESSAGE =
            "No active system episode.";
    static final String NO_OCCURRENCES_MESSAGE =
            "No occurrences of this event in the active system.";
    static final String NO_SELECTION_MESSAGE =
            "Select an occurrence to view details.";
    static final String LOAD_ERROR_MESSAGE =
            "Unable to load event occurrences.";
    static final String LOADING_MESSAGE =
            "Loading event occurrences...";

    private static final String CARD_TABLE = "table";
    private static final String CARD_MESSAGE = "message";

    private final BehaviorGraphOccurrenceTableModel tableModel =
            new BehaviorGraphOccurrenceTableModel();
    private final JTable occurrenceTable = new JTable(tableModel);
    private final JLabel header = new JLabel(" ");
    private final JLabel errorLabel = new JLabel(" ");
    private final CardLayout listCards = new CardLayout();
    private final JPanel listContent = new JPanel(listCards);
    private final JLabel listMessage = new JLabel(
            INITIAL_MESSAGE,
            SwingConstants.CENTER
    );
    private final JTextArea details = new JTextArea(NO_SELECTION_MESSAGE);
    private final JSplitPane verticalSplit;
    private final EventOccurrenceDetailsFormatter detailsFormatter =
            new EventOccurrenceDetailsFormatter();

    private Consumer<Optional<EventOccurrenceId>> selectionListener =
            ignored -> {
            };
    private boolean changingSelection;

    BehaviorGraphOccurrenceInspector() {
        super(new BorderLayout(0, 6));
        requireEdt();
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        header.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        add(header, BorderLayout.NORTH);

        occurrenceTable.setSelectionMode(
                ListSelectionModel.SINGLE_SELECTION
        );
        occurrenceTable.setAutoCreateRowSorter(false);
        occurrenceTable.getTableHeader().setReorderingAllowed(false);
        occurrenceTable.setFillsViewportHeight(true);
        occurrenceTable.getColumnModel().getColumn(0)
                .setPreferredWidth(55);
        occurrenceTable.getColumnModel().getColumn(1)
                .setPreferredWidth(85);
        occurrenceTable.getColumnModel().getColumn(2)
                .setPreferredWidth(170);
        occurrenceTable.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && !changingSelection) {
                selectionListener.accept(selectedOccurrenceId());
            }
        });
        installEscapeAction();

        JScrollPane tableScroll = new JScrollPane(occurrenceTable);
        listContent.add(tableScroll, CARD_TABLE);
        listMessage.setBorder(
                BorderFactory.createEmptyBorder(12, 12, 12, 12)
        );
        listContent.add(listMessage, CARD_MESSAGE);

        errorLabel.setHorizontalAlignment(SwingConstants.CENTER);
        errorLabel.setVisible(false);
        JPanel listPanel = new JPanel(new BorderLayout());
        listPanel.add(listContent, BorderLayout.CENTER);
        listPanel.add(errorLabel, BorderLayout.SOUTH);

        details.setEditable(false);
        details.setLineWrap(false);
        details.setCaretPosition(0);
        JScrollPane detailsScroll = new JScrollPane(details);
        detailsScroll.setBorder(
                BorderFactory.createTitledBorder(DETAILS_TITLE)
        );

        verticalSplit = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT,
                listPanel,
                detailsScroll
        );
        verticalSplit.setResizeWeight(0.45);
        verticalSplit.setContinuousLayout(true);
        verticalSplit.setDividerLocation(0.45);
        add(verticalSplit, BorderLayout.CENTER);

        getAccessibleContext().setAccessibleName(PANEL_TITLE);
        getAccessibleContext().setAccessibleDescription(
                "Active system episode event occurrences and details."
        );
        showInitial();
    }

    void setSelectionListener(
            Consumer<Optional<EventOccurrenceId>> listener
    ) {
        requireEdt();
        selectionListener = Objects.requireNonNull(listener, "listener");
    }

    void showInitial() {
        requireEdt();
        header.setText(" ");
        replaceRows(java.util.List.of());
        showListMessage(INITIAL_MESSAGE);
        showNoOccurrenceSelected();
        clearError();
    }

    void showSelectedNodePending(
            String displayName,
            long activeOccurrenceCount,
            boolean activeEpisodePresent
    ) {
        requireEdt();
        Objects.requireNonNull(displayName, "displayName");
        if (activeOccurrenceCount < 0) {
            throw new IllegalArgumentException(
                    "activeOccurrenceCount must be nonnegative"
            );
        }
        header.setText(headerText(displayName, activeOccurrenceCount));
        replaceRows(java.util.List.of());
        if (!activeEpisodePresent) {
            showListMessage(NO_ACTIVE_EPISODE_MESSAGE);
        } else if (activeOccurrenceCount == 0) {
            showListMessage(NO_OCCURRENCES_MESSAGE);
        } else {
            showListMessage(LOADING_MESSAGE);
        }
        showNoOccurrenceSelected();
        clearError();
    }

    Optional<EventOccurrenceId> applyOccurrences(
            ActiveEpisodeNodeOccurrencesSnapshot snapshot,
            Optional<EventOccurrenceId> occurrenceToPreserve,
            boolean selectLatest
    ) {
        requireEdt();
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(
                occurrenceToPreserve,
                "occurrenceToPreserve"
        );
        int count = snapshot.occurrences().size();
        header.setText(headerText(snapshot.displayName(), count));
        clearError();
        replaceRows(snapshot.occurrences());

        if (snapshot.activeEpisodeId().isEmpty()) {
            showListMessage(NO_ACTIVE_EPISODE_MESSAGE);
            showNoOccurrenceSelected();
            return Optional.empty();
        }
        if (snapshot.occurrences().isEmpty()) {
            showListMessage(NO_OCCURRENCES_MESSAGE);
            showNoOccurrenceSelected();
            return Optional.empty();
        }

        listCards.show(listContent, CARD_TABLE);
        OptionalInt preservedRow = occurrenceToPreserve
                .map(tableModel::findRow)
                .orElseGet(OptionalInt::empty);
        int selectedModelRow = preservedRow.orElse(
                selectLatest ? tableModel.getRowCount() - 1 : -1
        );
        if (selectedModelRow >= 0) {
            selectModelRow(selectedModelRow);
            return Optional.of(
                    tableModel.row(selectedModelRow).occurrenceId()
            );
        }
        showNoOccurrenceSelected();
        return Optional.empty();
    }

    void showDetails(EventOccurrenceDetailsSnapshot snapshot) {
        requireEdt();
        details.setText(detailsFormatter.format(
                Objects.requireNonNull(snapshot, "snapshot")
        ));
        details.setCaretPosition(0);
        clearError();
    }

    void showNoOccurrenceSelected() {
        requireEdt();
        details.setText(NO_SELECTION_MESSAGE);
        details.setCaretPosition(0);
    }

    void showLoadError() {
        requireEdt();
        errorLabel.setText(LOAD_ERROR_MESSAGE);
        errorLabel.setVisible(true);
        if (tableModel.getRowCount() == 0) {
            showListMessage(LOAD_ERROR_MESSAGE);
        }
    }

    void clearOccurrenceSelection() {
        requireEdt();
        changingSelection = true;
        try {
            occurrenceTable.clearSelection();
        } finally {
            changingSelection = false;
        }
        showNoOccurrenceSelected();
    }

    Optional<EventOccurrenceId> selectedOccurrenceId() {
        requireEdt();
        int viewRow = occurrenceTable.getSelectedRow();
        if (viewRow < 0) {
            return Optional.empty();
        }
        int modelRow = occurrenceTable.convertRowIndexToModel(viewRow);
        return Optional.of(tableModel.row(modelRow).occurrenceId());
    }

    BehaviorGraphOccurrenceTableModel tableModel() {
        return tableModel;
    }

    JTable occurrenceTable() {
        return occurrenceTable;
    }

    JSplitPane verticalSplit() {
        return verticalSplit;
    }

    String headerText() {
        return header.getText();
    }

    String listMessageText() {
        return listMessage.getText();
    }

    String detailsText() {
        return details.getText();
    }

    boolean loadErrorVisible() {
        return errorLabel.isVisible();
    }

    private void replaceRows(
            java.util.List<EventOccurrenceSummary> rows
    ) {
        changingSelection = true;
        try {
            occurrenceTable.clearSelection();
            tableModel.setRows(rows);
        } finally {
            changingSelection = false;
        }
    }

    private void selectModelRow(int modelRow) {
        changingSelection = true;
        try {
            int viewRow = occurrenceTable.convertRowIndexToView(modelRow);
            occurrenceTable.setRowSelectionInterval(viewRow, viewRow);
            occurrenceTable.scrollRectToVisible(
                    occurrenceTable.getCellRect(viewRow, 0, true)
            );
        } finally {
            changingSelection = false;
        }
    }

    private void showListMessage(String message) {
        listMessage.setText(message);
        listCards.show(listContent, CARD_MESSAGE);
    }

    private void clearError() {
        errorLabel.setText(" ");
        errorLabel.setVisible(false);
    }

    private static String headerText(String displayName, long count) {
        return displayName
                + " \u2014 "
                + count
                + (count == 1 ? " occurrence" : " occurrences");
    }

    private void installEscapeAction() {
        occurrenceTable.getInputMap(
                JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT
        ).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                "clearOccurrenceSelection"
        );
        occurrenceTable.getActionMap().put(
                "clearOccurrenceSelection",
                new AbstractAction() {
                    @Override
                    public void actionPerformed(ActionEvent event) {
                        clearOccurrenceSelection();
                        selectionListener.accept(Optional.empty());
                    }
                }
        );
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException(
                    "Occurrence inspector changes must run on the EDT"
            );
        }
    }
}
