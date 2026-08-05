package kairon.ui.swing;

import kairon.behavior.event.BehaviorGraphEventSource;
import kairon.behavior.graph.ActiveEpisodeNodeOccurrencesSnapshot;
import kairon.behavior.graph.BehaviorGraphDisplayNameResolver;
import kairon.behavior.graph.EventOccurrenceDetailsSnapshot;
import kairon.behavior.graph.BehaviorGraphVisualizationQuery;
import kairon.behavior.graph.BehaviorGraphVisualizationSnapshot;
import kairon.behavior.model.EventOccurrenceId;
import kairon.behavior.model.GraphId;
import kairon.behavior.model.SystemEpisodeId;
import kairon.behavior.normalize.NormalizedEventType;
import kairon.ui.KaironGuiHub.ModelCompletionView;
import kairon.ui.KaironGuiHub.ModelDecisionView;
import kairon.ui.KaironGuiHub.ObservationEffectView;
import kairon.ui.KaironGuiHub.ObservationView;
import kairon.ui.swing.behaviorgraph.BehaviorGraphTab;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One main Swing shell. All controls are composed through {@link HudTheme}.
 */
final class KaironHudWindow implements SwingKaironGuiHub.GuiView {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(KaironHudWindow.class);

    private final JFrame frame = new JFrame("Kairon");
    private final ObservationTableModel observationModel;
    private final ModelTurnTableModel modelTurnModel;
    private final JTable observationTable;
    private final JTable modelTurnTable;
    private final JTextArea observationDetail = HudTheme.detailArea();
    private final JTextArea modelTurnDetail = HudTheme.detailArea();
    private final JLabel status = HudTheme.mutedLabel("Running");
    private final JTabbedPane tabs = HudTheme.tabbedPane();
    private final BehaviorGraphTab behaviorGraphTab;
    private final ChangeListener tabSelectionListener =
            this::tabSelectionChanged;

    private boolean closeHandlerInstalled;
    private boolean stopping;
    private boolean disposed;

    KaironHudWindow(
            int maximumObservationRows,
            int maximumTurnRows
    ) {
        this(
                maximumObservationRows,
                maximumTurnRows,
                UnavailableVisualizationQuery.INSTANCE,
                UnavailableEventSource.INSTANCE
        );
    }

    KaironHudWindow(
            int maximumObservationRows,
            int maximumTurnRows,
            BehaviorGraphVisualizationQuery visualizationQuery,
            BehaviorGraphEventSource eventSource
    ) {
        requireEdt();
        observationModel = new ObservationTableModel(
                maximumObservationRows
        );
        modelTurnModel = new ModelTurnTableModel(maximumTurnRows);
        observationTable = HudTheme.table(observationModel);
        modelTurnTable = HudTheme.table(modelTurnModel);
        behaviorGraphTab = new BehaviorGraphTab(
                Objects.requireNonNull(
                        visualizationQuery,
                        "visualizationQuery"
                ),
                Objects.requireNonNull(eventSource, "eventSource"),
                frame
        );
        configureFrame();
    }

    @Override
    public void show(Runnable closeAction) {
        requireEdt();
        Objects.requireNonNull(closeAction, "closeAction");
        if (!closeHandlerInstalled) {
            frame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent event) {
                    closeAction.run();
                }
            });
            closeHandlerInstalled = true;
        }
        frame.setVisible(true);
    }

    @Override
    public void appendObservation(ObservationView observation) {
        requireEdt();
        int previousRowCount = observationModel.getRowCount();
        boolean followTail = observationTable.getSelectedRow() < 0
                || observationTable.getSelectedRow()
                == previousRowCount - 1;
        observationModel.append(observation);
        if (followTail && observationModel.getRowCount() > 0) {
            int lastRow = observationModel.getRowCount() - 1;
            observationTable.setRowSelectionInterval(
                    lastRow,
                    lastRow
            );
            observationTable.scrollRectToVisible(
                    observationTable.getCellRect(lastRow, 0, true)
            );
        }
    }

    @Override
    public void updateObservationEffect(ObservationEffectView effect) {
        requireEdt();
        int changedRow = observationModel.applyEffect(effect);
        if (changedRow >= 0
                && observationTable.getSelectedRow() == changedRow) {
            showSelectedObservation();
        }
    }

    @Override
    public void upsertModelDecision(ModelDecisionView decision) {
        requireEdt();
        int previousRowCount = modelTurnModel.getRowCount();
        boolean followTail = modelTurnTable.getSelectedRow() < 0
                || modelTurnTable.getSelectedRow() == previousRowCount - 1;
        int row = modelTurnModel.upsertDecision(decision);
        if (followTail) {
            modelTurnTable.setRowSelectionInterval(row, row);
            modelTurnTable.scrollRectToVisible(
                    modelTurnTable.getCellRect(row, 0, true)
            );
        }
    }

    @Override
    public void completeModelTurn(ModelCompletionView completion) {
        requireEdt();
        int row = modelTurnModel.complete(completion);
        if (modelTurnTable.getSelectedRow() == row) {
            showSelectedModelTurn();
        }
    }

    @Override
    public void updateDroppedCount(long droppedCount) {
        requireEdt();
        if (stopping) {
            return;
        }
        status.setText(droppedCount == 0L
                ? "Running"
                : "Running | dropped GUI updates: " + droppedCount);
    }

    @Override
    public void showStopping() {
        requireEdt();
        stopping = true;
        status.setText("Stopping...");
    }

    @Override
    public void dispose() {
        requireEdt();
        if (disposed) {
            return;
        }
        disposed = true;
        tabs.removeChangeListener(tabSelectionListener);
        behaviorGraphTab.dispose();
        frame.dispose();
    }

    private void configureFrame() {
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.setMinimumSize(new Dimension(960, 640));
        frame.setSize(1320, 820);
        frame.setLocationByPlatform(true);

        JPanel root = HudTheme.panel(new BorderLayout(0, 10));
        root.setBorder(javax.swing.BorderFactory.createEmptyBorder(
                10,
                10,
                10,
                10
        ));

        JPanel header = HudTheme.panel(new BorderLayout());
        header.add(HudTheme.title("KAIRON // JOURNAL OBSERVER"),
                BorderLayout.WEST);
        header.add(status, BorderLayout.EAST);
        root.add(header, BorderLayout.NORTH);

        JSplitPane observations = HudTheme.verticalSplit(
                HudTheme.scroll(observationTable),
                HudTheme.scroll(observationDetail),
                0.65
        );
        JSplitPane turns = HudTheme.verticalSplit(
                HudTheme.scroll(modelTurnTable),
                HudTheme.scroll(modelTurnDetail),
                0.58
        );
        JSplitPane main = HudTheme.verticalSplit(
                HudTheme.section(
                        "INCOMING JOURNAL OBSERVATIONS",
                        observations
                ),
                HudTheme.section("LLM TURNS", turns),
                0.56
        );
        tabs.addTab("Journal Observer", main);
        tabs.addTab(BehaviorGraphTab.TITLE, behaviorGraphTab);
        tabs.addChangeListener(tabSelectionListener);
        behaviorGraphTab.setTabSelected(
                tabs.getSelectedComponent() == behaviorGraphTab
        );
        root.add(tabs, BorderLayout.CENTER);
        frame.setContentPane(root);
        LOGGER.info("BEHAVIOR_GRAPH_TAB_REGISTERED");

        observationTable.getSelectionModel().addListSelectionListener(
                this::observationSelectionChanged
        );
        modelTurnTable.getSelectionModel().addListSelectionListener(
                this::modelTurnSelectionChanged
        );
    }

    private void tabSelectionChanged(ChangeEvent event) {
        requireEdt();
        behaviorGraphTab.setTabSelected(
                tabs.getSelectedComponent() == behaviorGraphTab
        );
    }

    private void observationSelectionChanged(ListSelectionEvent event) {
        if (!event.getValueIsAdjusting()) {
            showSelectedObservation();
        }
    }

    private void modelTurnSelectionChanged(ListSelectionEvent event) {
        if (!event.getValueIsAdjusting()) {
            showSelectedModelTurn();
        }
    }

    private void showSelectedObservation() {
        int row = observationTable.getSelectedRow();
        if (row < 0 || row >= observationModel.getRowCount()) {
            observationDetail.setText("");
            return;
        }
        ObservationRow value = observationModel.row(row);
        ObservationView observation = value.observation();
        observationDetail.setText("""
                event: %s
                payloadType: %s
                captureMode: %s
                busSequence: %d
                observationId: %s
                source: %s
                sourcePosition: %s
                observedAt: %s
                sourceTime: %s
                latestObserverEffect: %s
                observerEffectHistory:
                %s

                %s
                """.formatted(
                observation.eventType(),
                observation.payloadType(),
                observation.captureMode(),
                observation.busSequence(),
                observation.observationId(),
                observation.source(),
                observation.sourcePosition(),
                observation.observedAt(),
                observation.sourceTime().map(Instant::toString)
                        .orElse("<unavailable>"),
                value.latestEffectText(),
                value.effectHistoryText(),
                observation.rawJson()
        ));
        observationDetail.setCaretPosition(0);
    }

    private void showSelectedModelTurn() {
        int row = modelTurnTable.getSelectedRow();
        if (row < 0 || row >= modelTurnModel.getRowCount()) {
            modelTurnDetail.setText("");
            return;
        }
        TurnRow value = modelTurnModel.row(row);
        ModelDecisionView decision = value.decision;
        ModelCompletionView completion = value.completion;
        modelTurnDetail.setText("""
                turnSequence: %d
                status: %s
                decision: %s
                eventCount: %s
                latencyMs: %s
                Triggers: %s
                violations: %s
                failure: %s
                consoleOutcome: %s
                speechOutcome: %s
                deliveredForHistory: %s
                deliveredComment: %s

                rawModelOutput:
                %s
                """.formatted(
                value.turnSequence,
                decision == null ? "<pending>" : decision.status(),
                decision == null || decision.decision() == null
                        ? "<none>"
                        : decision.decision(),
                decision == null ? "<pending>" : decision.eventCount(),
                decision == null ? "<pending>" : decision.latencyMs(),
                decision == null
                        ? List.of()
                        : decision.triggerBusSequences(),
                decision == null ? List.of() : decision.violations(),
                decision == null || decision.failure() == null
                        ? "<none>"
                        : decision.failure(),
                completion == null
                        ? "<pending>"
                        : completion.consoleOutcome(),
                completion == null
                        ? "<pending>"
                        : completion.speechOutcome(),
                completion == null
                        ? "<pending>"
                        : completion.deliveredForHistory(),
                completion == null
                        || completion.deliveredComment() == null
                        ? "<none>"
                        : completion.deliveredComment(),
                decision == null || decision.rawModelOutput() == null
                        ? "<unavailable>"
                        : decision.rawModelOutput()
        ));
        modelTurnDetail.setCaretPosition(0);
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException(
                    "Swing window mutation must run on the EDT"
            );
        }
    }

    static Instant observationDisplayTime(ObservationView observation) {
        Objects.requireNonNull(observation, "observation");
        if ("REPLAY".equals(observation.captureMode())) {
            return observation.observedAt();
        }
        return observation.sourceTime().orElse(observation.observedAt());
    }

    static final class ObservationTableModel
            extends AbstractTableModel {

        private static final int MAX_EFFECT_HISTORY = 32;
        private static final String[] COLUMNS = {
                "SEQ",
                "TIME",
                "MODE",
                "EVENT",
                "OBSERVER EFFECT",
                "SOURCE POSITION"
        };

        private final int maximumRows;
        private final List<ObservationRow> rows = new ArrayList<>();
        private final LinkedHashMap<String, List<ObservationEffectView>>
                pendingEffects = new LinkedHashMap<>();

        ObservationTableModel(int maximumRows) {
            if (maximumRows < 1) {
                throw new IllegalArgumentException(
                        "maximumRows must be positive"
                );
            }
            this.maximumRows = maximumRows;
        }

        void append(ObservationView observation) {
            Objects.requireNonNull(observation, "observation");
            if (rows.size() == maximumRows) {
                rows.removeFirst();
                fireTableRowsDeleted(0, 0);
            }
            int row = rows.size();
            ObservationRow added = new ObservationRow(observation);
            List<ObservationEffectView> pending =
                    pendingEffects.remove(observation.observationId());
            if (pending != null) {
                for (ObservationEffectView effect : pending) {
                    added.apply(effect);
                }
            }
            rows.add(added);
            fireTableRowsInserted(row, row);
        }

        int applyEffect(ObservationEffectView effect) {
            Objects.requireNonNull(effect, "effect");
            for (int index = rows.size() - 1; index >= 0; index--) {
                ObservationRow row = rows.get(index);
                if (row.observation().observationId()
                        .equals(effect.observationId())) {
                    if (!row.apply(effect)) {
                        return -1;
                    }
                    fireTableRowsUpdated(index, index);
                    return index;
                }
            }

            List<ObservationEffectView> pending = pendingEffects.get(
                    effect.observationId()
            );
            if (pending == null) {
                if (pendingEffects.size() == maximumRows) {
                    String oldestId = pendingEffects.keySet()
                            .iterator()
                            .next();
                    pendingEffects.remove(oldestId);
                }
                pending = new ArrayList<>();
                pendingEffects.put(effect.observationId(), pending);
            }
            appendBounded(pending, effect);
            return -1;
        }

        ObservationRow row(int index) {
            return rows.get(index);
        }

        int pendingEffectCount() {
            return pendingEffects.size();
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
            ObservationRow row = rows.get(rowIndex);
            ObservationView observation = row.observation();
            return switch (columnIndex) {
                case 0 -> observation.busSequence();
                case 1 -> observationDisplayTime(observation);
                case 2 -> observation.captureMode();
                case 3 -> observation.eventType();
                case 4 -> row.latestEffectText();
                case 5 -> observation.sourcePosition();
                default -> throw new IndexOutOfBoundsException(
                        "columnIndex=" + columnIndex
                );
            };
        }

        private static void appendBounded(
                List<ObservationEffectView> effects,
                ObservationEffectView effect
        ) {
            if (effects.size() == MAX_EFFECT_HISTORY) {
                effects.removeFirst();
            }
            effects.add(effect);
        }
    }

    static final class ObservationRow {

        private final ObservationView observation;
        private final List<ObservationEffectView> effects =
                new ArrayList<>();

        private ObservationRow(ObservationView observation) {
            this.observation = Objects.requireNonNull(
                    observation,
                    "observation"
            );
        }

        ObservationView observation() {
            return observation;
        }

        List<ObservationEffectView> effects() {
            return List.copyOf(effects);
        }

        String latestEffectText() {
            if (effects.isEmpty()) {
                return "OCCURRED_ONLY";
            }
            return effectText(effects.getLast());
        }

        String effectHistoryText() {
            if (effects.isEmpty()) {
                return "  OCCURRED_ONLY — no observer-owned reaction reported";
            }
            return effects.stream()
                    .map(effect -> "  "
                            + effect.changedAt()
                            + " | "
                            + effectText(effect))
                    .collect(java.util.stream.Collectors.joining(
                            System.lineSeparator()
                    ));
        }

        private boolean apply(ObservationEffectView effect) {
            if (effect.busSequence() != observation.busSequence()) {
                return false;
            }
            ObservationTableModel.appendBounded(effects, effect);
            return true;
        }

        private static String effectText(ObservationEffectView effect) {
            if (effect.turnSequence() == null) {
                return effect.effect();
            }
            return effect.effect()
                    + " | turn="
                    + effect.turnSequence();
        }
    }

    private static final class ModelTurnTableModel
            extends AbstractTableModel {

        private static final String[] COLUMNS = {
                "TURN", "TIME", "STATUS", "DECISION",
                "LATENCY MS", "DELIVERY", "TEXT"
        };

        private final int maximumRows;
        private final List<TurnRow> rows = new ArrayList<>();

        private ModelTurnTableModel(int maximumRows) {
            this.maximumRows = maximumRows;
        }

        private int upsertDecision(ModelDecisionView decision) {
            int existing = indexOf(decision.turnSequence());
            if (existing >= 0) {
                rows.get(existing).decision = decision;
                fireTableRowsUpdated(existing, existing);
                return existing;
            }
            return append(new TurnRow(decision.turnSequence(), decision));
        }

        private int complete(ModelCompletionView completion) {
            int existing = indexOf(completion.turnSequence());
            if (existing < 0) {
                existing = append(new TurnRow(
                        completion.turnSequence(),
                        null
                ));
            }
            rows.get(existing).completion = completion;
            fireTableRowsUpdated(existing, existing);
            return existing;
        }

        private int append(TurnRow row) {
            if (rows.size() == maximumRows) {
                rows.removeFirst();
                fireTableRowsDeleted(0, 0);
            }
            int index = rows.size();
            rows.add(row);
            fireTableRowsInserted(index, index);
            return index;
        }

        private int indexOf(long turnSequence) {
            for (int index = 0; index < rows.size(); index++) {
                if (rows.get(index).turnSequence == turnSequence) {
                    return index;
                }
            }
            return -1;
        }

        private TurnRow row(int index) {
            return rows.get(index);
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
            TurnRow row = rows.get(rowIndex);
            ModelDecisionView decision = row.decision;
            ModelCompletionView completion = row.completion;
            return switch (columnIndex) {
                case 0 -> row.turnSequence;
                case 1 -> decision == null
                        ? completion.completedAt()
                        : decision.resolvedAt();
                case 2 -> decision == null
                        ? "<pending>"
                        : decision.status();
                case 3 -> decision == null || decision.decision() == null
                        ? "<none>"
                        : decision.decision();
                case 4 -> decision == null
                        ? "<pending>"
                        : decision.latencyMs();
                case 5 -> deliveryText(decision, completion);
                case 6 -> decision == null || decision.text() == null
                        ? ""
                        : decision.text();
                default -> throw new IndexOutOfBoundsException(
                        "columnIndex=" + columnIndex
                );
            };
        }

        private static String deliveryText(
                ModelDecisionView decision,
                ModelCompletionView completion
        ) {
            if (completion == null) {
                return decision != null
                        && "COMMENT".equals(decision.decision())
                        ? "<pending>"
                        : "NOT_REQUESTED";
            }
            if (completion.deliveredForHistory()) {
                return "DELIVERED";
            }
            if (!"DISABLED".equals(completion.speechOutcome())
                    && !"NOT_REQUESTED".equals(
                            completion.speechOutcome()
                    )) {
                return completion.speechOutcome();
            }
            return completion.consoleOutcome();
        }
    }

    private static final class TurnRow {

        private final long turnSequence;
        private ModelDecisionView decision;
        private ModelCompletionView completion;

        private TurnRow(
                long turnSequence,
                ModelDecisionView decision
        ) {
            this.turnSequence = turnSequence;
            this.decision = decision;
        }
    }

    private enum UnavailableVisualizationQuery
            implements BehaviorGraphVisualizationQuery {
        INSTANCE;

        @Override
        public Optional<GraphId> getActiveGraphId() {
            return Optional.empty();
        }

        @Override
        public Optional<BehaviorGraphVisualizationSnapshot>
                getVisualizationSnapshot(
                        GraphId graphId,
                        Instant evaluationTime
                ) {
            Objects.requireNonNull(graphId, "graphId");
            Objects.requireNonNull(evaluationTime, "evaluationTime");
            return Optional.empty();
        }

        @Override
        public ActiveEpisodeNodeOccurrencesSnapshot
                getActiveEpisodeOccurrences(
                        GraphId graphId,
                        NormalizedEventType eventType
                ) {
            Objects.requireNonNull(graphId, "graphId");
            Objects.requireNonNull(eventType, "eventType");
            return new ActiveEpisodeNodeOccurrencesSnapshot(
                    graphId,
                    Optional.empty(),
                    eventType,
                    new BehaviorGraphDisplayNameResolver().resolve(eventType),
                    0L,
                    0L,
                    List.of()
            );
        }

        @Override
        public Optional<EventOccurrenceDetailsSnapshot>
                getActiveEpisodeOccurrenceDetails(
                        GraphId graphId,
                        SystemEpisodeId episodeId,
                        EventOccurrenceId occurrenceId
                ) {
            Objects.requireNonNull(graphId, "graphId");
            Objects.requireNonNull(episodeId, "episodeId");
            Objects.requireNonNull(occurrenceId, "occurrenceId");
            return Optional.empty();
        }
    }

    private enum UnavailableEventSource
            implements BehaviorGraphEventSource {
        INSTANCE;

        @Override
        public Subscription subscribe(
                kairon.behavior.event.BehaviorGraphListener listener
        ) {
            Objects.requireNonNull(listener, "listener");
            return InactiveSubscription.INSTANCE;
        }
    }

    private enum InactiveSubscription
            implements BehaviorGraphEventSource.Subscription {
        INSTANCE;

        @Override
        public boolean isActive() {
            return false;
        }

        @Override
        public void close() {
        }
    }
}
