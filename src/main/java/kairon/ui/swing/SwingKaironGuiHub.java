package kairon.ui.swing;

import kairon.behavior.event.BehaviorGraphEventSource;
import kairon.behavior.graph.BehaviorGraphVisualizationQuery;
import kairon.config.KaironConfiguration.UiConfiguration;
import kairon.bio.OrganicRegistry.PricedOrganism;
import kairon.system.SystemRegistrySnapshot;
import kairon.ui.KaironGuiHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Single Swing ingress, EDT marshal, update queue, and window lifecycle.
 */
public final class SwingKaironGuiHub implements KaironGuiHub {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(SwingKaironGuiHub.class);
    private static final int UPDATE_BATCH_SIZE = 128;

    private final Object queueLock = new Object();
    private final UiDispatcher dispatcher;
    private final Supplier<GuiView> viewFactory;
    private final int queueCapacity;
    private final ArrayDeque<UiUpdate> updates = new ArrayDeque<>();
    private final CompletableFuture<Void> closeRequested =
            new CompletableFuture<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    private GuiView view;
    private boolean drainScheduled;
    private long droppedUpdates;

    public SwingKaironGuiHub(UiConfiguration configuration) {
        this(
                configuration,
                new SwingEdtDispatcher(),
                () -> {
                    HudTheme.install();
                    return new KaironHudWindow(
                            configuration.maximumObservationRows(),
                            configuration.maximumTurnRows()
                    );
                }
        );
    }

    public SwingKaironGuiHub(
            UiConfiguration configuration,
            BehaviorGraphVisualizationQuery visualizationQuery,
            BehaviorGraphEventSource eventSource
    ) {
        this(
                configuration,
                new SwingEdtDispatcher(),
                () -> {
                    HudTheme.install();
                    return new KaironHudWindow(
                            configuration.maximumObservationRows(),
                            configuration.maximumTurnRows(),
                            Objects.requireNonNull(
                                    visualizationQuery,
                                    "visualizationQuery"
                            ),
                            Objects.requireNonNull(eventSource, "eventSource")
                    );
                }
        );
    }

    SwingKaironGuiHub(
            UiConfiguration configuration,
            UiDispatcher dispatcher,
            Supplier<GuiView> viewFactory
    ) {
        Objects.requireNonNull(configuration, "configuration");
        if (!configuration.enabled()) {
            throw new IllegalArgumentException(
                    "Swing GUI requires enabled UI configuration"
            );
        }
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.viewFactory = Objects.requireNonNull(
                viewFactory,
                "viewFactory"
        );
        this.queueCapacity = Math.addExact(
                configuration.maximumObservationRows(),
                configuration.maximumTurnRows()
        );
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public void start() {
        if (closed.get()) {
            throw new IllegalStateException("GUI hub is closed");
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }
        try {
            dispatcher.executeAndWait(() -> {
                view = Objects.requireNonNull(
                        viewFactory.get(),
                        "viewFactory result"
                );
                view.show(this::requestClose);
            });
        } catch (RuntimeException failure) {
            closed.set(true);
            try {
                dispatcher.executeAndWait(() -> {
                    if (view != null) {
                        view.dispose();
                        view = null;
                    }
                });
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw new GuiInitializationException(failure);
        }
        scheduleDrainIfNeeded();
    }

    @Override
    public void postObservation(ObservationView observation) {
        offer(new ObservationUpdate(
                Objects.requireNonNull(observation, "observation")
        ));
    }

    @Override
    public void postObservationEffect(ObservationEffectView effect) {
        offer(new ObservationEffectUpdate(
                Objects.requireNonNull(effect, "effect")
        ));
    }

    @Override
    public void postModelDecision(ModelDecisionView decision) {
        offer(new ModelDecisionUpdate(
                Objects.requireNonNull(decision, "decision")
        ));
    }

    @Override
    public void postModelCompletion(ModelCompletionView completion) {
        offer(new ModelCompletionUpdate(
                Objects.requireNonNull(completion, "completion")
        ));
    }

    @Override
    public void postSystemRegistry(SystemRegistrySnapshot snapshot) {
        offer(new SystemRegistryUpdate(
                Objects.requireNonNull(snapshot, "snapshot")
        ));
    }

    @Override
    public void postOrganicRegistry(List<PricedOrganism> organisms) {
        offer(new OrganicRegistryUpdate(
                List.copyOf(Objects.requireNonNull(organisms, "organisms"))
        ));
    }

    @Override
    public void postOrganicSample(String speciesIdentifier) {
        offer(new OrganicSampleUpdate(
                Objects.requireNonNull(speciesIdentifier, "speciesIdentifier")
        ));
    }

    @Override
    public CompletionStage<Void> closeRequested() {
        return closeRequested;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        synchronized (queueLock) {
            updates.clear();
            drainScheduled = false;
        }
        if (started.get()) {
            dispatcher.executeAndWait(() -> {
                if (view != null) {
                    view.dispose();
                    view = null;
                }
            });
        }
    }

    private void requestClose() {
        GuiView current = view;
        if (current != null) {
            current.showStopping();
        }
        closeRequested.complete(null);
    }

    private void offer(UiUpdate update) {
        boolean shouldSchedule = false;
        long dropped = 0L;
        synchronized (queueLock) {
            if (closed.get()) {
                return;
            }
            if (updates.size() == queueCapacity) {
                updates.removeFirst();
                droppedUpdates++;
                dropped = droppedUpdates;
            }
            updates.addLast(update);
            if (started.get() && !drainScheduled) {
                drainScheduled = true;
                shouldSchedule = true;
            }
        }
        if (dropped > 0L && isDiagnosticThreshold(dropped)) {
            LOGGER.warn("GUI_UPDATE_DROPPED total={}", dropped);
        }
        if (shouldSchedule) {
            dispatcher.execute(this::drainOnEdt);
        }
    }

    private void scheduleDrainIfNeeded() {
        boolean shouldSchedule = false;
        synchronized (queueLock) {
            if (!closed.get() && !updates.isEmpty() && !drainScheduled) {
                drainScheduled = true;
                shouldSchedule = true;
            }
        }
        if (shouldSchedule) {
            dispatcher.execute(this::drainOnEdt);
        }
    }

    private void drainOnEdt() {
        if (!dispatcher.isDispatchThread()) {
            throw new IllegalStateException(
                    "GUI updates must drain on the Swing EDT"
            );
        }

        List<UiUpdate> batch = new ArrayList<>(UPDATE_BATCH_SIZE);
        boolean more;
        long dropped;
        synchronized (queueLock) {
            while (batch.size() < UPDATE_BATCH_SIZE
                    && !updates.isEmpty()) {
                batch.add(updates.removeFirst());
            }
            more = !updates.isEmpty();
            if (!more) {
                drainScheduled = false;
            }
            dropped = droppedUpdates;
        }

        GuiView current = view;
        if (!closed.get() && current != null) {
            for (UiUpdate update : batch) {
                try {
                    update.apply(current);
                } catch (RuntimeException failure) {
                    LOGGER.warn(
                            "GUI_VIEW_UPDATE_FAILED category={}",
                            failure.getClass().getSimpleName()
                    );
                }
            }
            try {
                current.updateDroppedCount(dropped);
            } catch (RuntimeException failure) {
                LOGGER.warn(
                        "GUI_VIEW_UPDATE_FAILED category={}",
                        failure.getClass().getSimpleName()
                );
            }
        }

        if (more && !closed.get()) {
            dispatcher.execute(this::drainOnEdt);
        }
    }

    private static boolean isDiagnosticThreshold(long count) {
        return count == 1L || (count & (count - 1L)) == 0L;
    }

    interface GuiView {

        void show(Runnable closeAction);

        void appendObservation(ObservationView observation);

        void updateObservationEffect(ObservationEffectView effect);

        void upsertModelDecision(ModelDecisionView decision);

        void completeModelTurn(ModelCompletionView completion);

        void updateSystemRegistry(SystemRegistrySnapshot snapshot);

        void updateOrganicRegistry(List<PricedOrganism> organisms);

        void markOrganicSample(String speciesIdentifier);

        void updateDroppedCount(long droppedCount);

        void showStopping();

        void dispose();
    }

    interface UiDispatcher {

        void execute(Runnable action);

        void executeAndWait(Runnable action);

        boolean isDispatchThread();
    }

    public static final class GuiInitializationException
            extends IllegalStateException {

        private GuiInitializationException(Throwable cause) {
            super("GUI_INITIALIZATION_FAILED", cause);
        }
    }

    private sealed interface UiUpdate permits
            ObservationUpdate,
            ObservationEffectUpdate,
            ModelDecisionUpdate,
            ModelCompletionUpdate,
            SystemRegistryUpdate,
            OrganicRegistryUpdate,
            OrganicSampleUpdate {

        void apply(GuiView view);
    }

    private record ObservationUpdate(ObservationView observation)
            implements UiUpdate {

        @Override
        public void apply(GuiView view) {
            view.appendObservation(observation);
        }
    }

    private record ObservationEffectUpdate(ObservationEffectView effect)
            implements UiUpdate {

        @Override
        public void apply(GuiView view) {
            view.updateObservationEffect(effect);
        }
    }

    private record ModelDecisionUpdate(ModelDecisionView decision)
            implements UiUpdate {

        @Override
        public void apply(GuiView view) {
            view.upsertModelDecision(decision);
        }
    }

    private record ModelCompletionUpdate(ModelCompletionView completion)
            implements UiUpdate {

        @Override
        public void apply(GuiView view) {
            view.completeModelTurn(completion);
        }
    }

    private record SystemRegistryUpdate(SystemRegistrySnapshot snapshot)
            implements UiUpdate {

        @Override
        public void apply(GuiView view) {
            view.updateSystemRegistry(snapshot);
        }
    }

    private record OrganicRegistryUpdate(List<PricedOrganism> organisms)
            implements UiUpdate {

        @Override
        public void apply(GuiView view) {
            view.updateOrganicRegistry(organisms);
        }
    }

    private record OrganicSampleUpdate(String speciesIdentifier)
            implements UiUpdate {

        @Override
        public void apply(GuiView view) {
            view.markOrganicSample(speciesIdentifier);
        }
    }

    static final class SwingEdtDispatcher implements UiDispatcher {

        @Override
        public void execute(Runnable action) {
            SwingUtilities.invokeLater(action);
        }

        @Override
        public void executeAndWait(Runnable action) {
            if (SwingUtilities.isEventDispatchThread()) {
                action.run();
                return;
            }
            try {
                SwingUtilities.invokeAndWait(action);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "interrupted while waiting for Swing EDT",
                        interrupted
                );
            } catch (InvocationTargetException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof RuntimeException runtimeFailure) {
                    throw runtimeFailure;
                }
                throw new IllegalStateException(
                        "Swing EDT action failed",
                        cause
                );
            }
        }

        @Override
        public boolean isDispatchThread() {
            return SwingUtilities.isEventDispatchThread();
        }
    }
}
