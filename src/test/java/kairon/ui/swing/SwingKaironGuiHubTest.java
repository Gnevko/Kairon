package kairon.ui.swing;

import kairon.config.KaironConfiguration.UiConfiguration;
import kairon.ui.KaironGuiHub.ModelCompletionView;
import kairon.ui.KaironGuiHub.ModelDecisionView;
import kairon.ui.KaironGuiHub.ObservationEffectView;
import kairon.ui.KaironGuiHub.ObservationView;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SwingKaironGuiHubTest {

    @Test
    void centralHubMarshalsEveryViewMutationToSwingEdt() throws Exception {
        RecordingView view = new RecordingView(4);
        SwingKaironGuiHub hub = new SwingKaironGuiHub(
                new UiConfiguration(true, 10, 10),
                new SwingKaironGuiHub.SwingEdtDispatcher(),
                () -> view
        );
        try {
            hub.start();
            hub.postObservation(observation(1));
            hub.postObservationEffect(effect(1));
            hub.postModelDecision(decision(1));
            hub.postModelCompletion(completion(1));

            assertTrue(view.updates.await(2, TimeUnit.SECONDS));
            assertTrue(view.allMutationsOnEdt.get());
            assertNotNull(view.closeAction.get());
            assertEquals(
                    List.of("NEW_IN_FLIGHT"),
                    view.observationEffects
            );

            SwingUtilities.invokeAndWait(view.closeAction.get());
            hub.closeRequested().toCompletableFuture()
                    .get(2, TimeUnit.SECONDS);
        } finally {
            hub.close();
        }

        assertTrue(view.disposed.await(2, TimeUnit.SECONDS));
        assertTrue(view.allMutationsOnEdt.get());
    }

    @Test
    void boundedIngressDropsOldestPresentationUpdateAndCoalescesDrain() {
        ControlledDispatcher dispatcher = new ControlledDispatcher();
        RecordingView view = new RecordingView(2);
        SwingKaironGuiHub hub = new SwingKaironGuiHub(
                new UiConfiguration(true, 1, 1),
                dispatcher,
                () -> view
        );
        try {
            hub.start();
            hub.postObservation(observation(1));
            hub.postObservation(observation(2));
            hub.postObservation(observation(3));

            assertEquals(1, dispatcher.queuedCount());
            dispatcher.runNext();

            assertEquals(
                    List.of("observation-2", "observation-3"),
                    view.observationIds
            );
            assertEquals(1L, view.droppedCount);
        } finally {
            hub.close();
        }
    }

    private static ObservationView observation(long sequence) {
        return new ObservationView(
                "observation-" + sequence,
                sequence,
                Instant.parse("2026-07-29T09:00:00Z"),
                Optional.empty(),
                "journal/test",
                "Journal.test.log:" + sequence,
                "REPLAY",
                "Music",
                "kairon.observation.journal.event.session.Music",
                "{\"event\":\"Music\"}"
        );
    }

    private static ModelDecisionView decision(long sequence) {
        return new ModelDecisionView(
                sequence,
                Instant.parse("2026-07-29T09:00:01Z"),
                1,
                "VALID",
                "SILENT",
                null,
                List.of(),
                List.of(),
                null,
                "{\"decision\":\"SILENT\",\"comment\":null,"
                        + "\"evidenceTriggerBusSequences\":[]}",
                10L
        );
    }

    private static ObservationEffectView effect(long sequence) {
        return new ObservationEffectView(
                "observation-" + sequence,
                sequence,
                Instant.parse("2026-07-29T09:00:00.500Z"),
                "NEW_IN_FLIGHT",
                1L
        );
    }

    private static ModelCompletionView completion(long sequence) {
        return new ModelCompletionView(
                sequence,
                Instant.parse("2026-07-29T09:00:02Z"),
                "NOT_ATTEMPTED",
                "DISABLED",
                false,
                null
        );
    }

    private static final class RecordingView
            implements SwingKaironGuiHub.GuiView {

        private final CountDownLatch updates;
        private final CountDownLatch disposed = new CountDownLatch(1);
        private final AtomicBoolean allMutationsOnEdt =
                new AtomicBoolean(true);
        private final AtomicReference<Runnable> closeAction =
                new AtomicReference<>();
        private final List<String> observationIds = new ArrayList<>();
        private final List<String> observationEffects = new ArrayList<>();
        private long droppedCount;

        private RecordingView(int expectedUpdates) {
            updates = new CountDownLatch(expectedUpdates);
        }

        @Override
        public void show(Runnable closeAction) {
            recordThread();
            this.closeAction.set(closeAction);
        }

        @Override
        public void appendObservation(ObservationView observation) {
            recordThread();
            observationIds.add(observation.observationId());
            updates.countDown();
        }

        @Override
        public void updateObservationEffect(ObservationEffectView effect) {
            recordThread();
            observationEffects.add(effect.effect());
            updates.countDown();
        }

        @Override
        public void upsertModelDecision(ModelDecisionView decision) {
            recordThread();
            updates.countDown();
        }

        @Override
        public void completeModelTurn(ModelCompletionView completion) {
            recordThread();
            updates.countDown();
        }

        @Override
        public void updateDroppedCount(long droppedCount) {
            recordThread();
            this.droppedCount = droppedCount;
        }

        @Override
        public void showStopping() {
            recordThread();
        }

        @Override
        public void dispose() {
            recordThread();
            disposed.countDown();
        }

        private void recordThread() {
            allMutationsOnEdt.compareAndSet(
                    true,
                    SwingUtilities.isEventDispatchThread()
            );
        }
    }

    private static final class ControlledDispatcher
            implements SwingKaironGuiHub.UiDispatcher {

        private final ArrayDeque<Runnable> queued = new ArrayDeque<>();
        private boolean dispatchThread;

        @Override
        public void execute(Runnable action) {
            queued.addLast(action);
        }

        @Override
        public void executeAndWait(Runnable action) {
            runAsDispatchThread(action);
        }

        @Override
        public boolean isDispatchThread() {
            return dispatchThread;
        }

        private int queuedCount() {
            return queued.size();
        }

        private void runNext() {
            runAsDispatchThread(queued.removeFirst());
        }

        private void runAsDispatchThread(Runnable action) {
            boolean previous = dispatchThread;
            dispatchThread = true;
            try {
                action.run();
            } finally {
                dispatchThread = previous;
            }
        }
    }
}
