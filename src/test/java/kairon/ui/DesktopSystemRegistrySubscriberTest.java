package kairon.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kairon.behavior.graph.BehaviorGraphApplyResult;
import kairon.behavior.snapshot.BehaviorSituationCaptureStatus;
import kairon.behavior.snapshot.BehaviorSituationSnapshot;
import kairon.observation.ObservationDraft.ObservationCaptureMode;
import kairon.observation.ObservationDraft.ObservationSource;
import kairon.observation.PublishedObservation;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.JournalObservationAdapter.JournalSourcePosition;
import kairon.observation.journal.UnknownJournalEvent;
import kairon.projection.ProjectedObservation;
import kairon.projection.ProjectedObservationBus;
import kairon.projection.SemanticEnvelopeFactory;
import kairon.state.CurrentGameStateProjection;
import kairon.state.CurrentGameStateProjector;
import kairon.system.BodyProfile;
import kairon.system.PlanetBody;
import kairon.system.SystemObject;
import kairon.system.SystemRegistrySnapshot;
import kairon.semantics.BodyIdentity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The registry reaches the desktop view, and only when it has something new to
 * say.
 *
 * <p>Every projection carries a snapshot, and most projections carry the same
 * one as the last: a docking request establishes nothing about a moon. The
 * hub's update queue is bounded and shared with the observation rows, so a post
 * per publication would push journal rows out of the table to redraw a system
 * that had not moved.</p>
 */
final class DesktopSystemRegistrySubscriberTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final ObservationSource SOURCE = new ObservationSource(
            "journal",
            "registry-gui-test"
    );

    @Test
    void theFirstSnapshotIsForwardedAsItIs() throws Exception {
        RecordingHub hub = new RecordingHub();
        try (ProjectedObservationBus bus = new ProjectedObservationBus()) {
            new DesktopSystemRegistrySubscriber(hub).subscribeTo(bus);
            SystemRegistrySnapshot snapshot = registry(1, 4001L, "Alpha");

            bus.publish(projected(1, snapshot));

            assertEquals(1, hub.snapshots.size());
            assertSame(
                    snapshot,
                    hub.snapshots.getFirst(),
                    "the view is handed the registry's own read model"
            );
        }
    }

    @Test
    void anUnchangedSystemIsNotPostedAgain() throws Exception {
        RecordingHub hub = new RecordingHub();
        try (ProjectedObservationBus bus = new ProjectedObservationBus()) {
            new DesktopSystemRegistrySubscriber(hub).subscribeTo(bus);

            bus.publish(projected(1, registry(1, 4001L, "Alpha")));
            bus.publish(projected(2, registry(2, 4001L, "Alpha")));
            bus.publish(projected(3, registry(3, 4001L, "Alpha")));

            assertEquals(
                    1,
                    hub.snapshots.size(),
                    "three observations, one system, one repaint"
            );
        }
    }

    @Test
    void aChangedSystemIsPostedAgain() throws Exception {
        RecordingHub hub = new RecordingHub();
        try (ProjectedObservationBus bus = new ProjectedObservationBus()) {
            new DesktopSystemRegistrySubscriber(hub).subscribeTo(bus);

            bus.publish(projected(1, registry(1, 4001L, "Alpha")));
            bus.publish(projected(2, registry(2, 4001L, "Alpha", body(5))));
            bus.publish(projected(3, registry(3, 4002L, "Beta")));

            assertEquals(3, hub.snapshots.size());
            assertTrue(hub.snapshots.get(1).objects().containsKey(5L));
            assertEquals(4002L, hub.snapshots.get(2).systemAddress());
        }
    }

    // ------------------------------------------------------------- fixtures

    private static SystemRegistrySnapshot registry(
            long busSequence,
            Long systemAddress,
            String systemName,
            SystemObject... objects
    ) {
        Map<Long, SystemObject> byId = new java.util.TreeMap<>();
        for (SystemObject object : objects) {
            byId.put(object.bodyId(), object);
        }
        return new SystemRegistrySnapshot(
                busSequence,
                true,
                systemAddress,
                systemName,
                null,
                null,
                false,
                byId
        );
    }

    private static SystemObject body(long bodyId) {
        return PlanetBody.listed(BodyProfile.listed(
                new BodyIdentity(4001L, bodyId),
                List.of()
        ));
    }

    private static ProjectedObservation projected(
            long busSequence,
            SystemRegistrySnapshot registry
    ) throws Exception {
        PublishedObservation<JournalEventObservation> observation =
                published(busSequence);
        CurrentGameStateProjection projection =
                new CurrentGameStateProjector().applyAndCapture(observation);
        BehaviorGraphApplyResult graph =
                BehaviorGraphApplyResult.disabled(busSequence);
        return new ProjectedObservation(
                observation,
                projection.applied(),
                projection.changes(),
                graph,
                BehaviorSituationSnapshot.unavailable(
                        graph,
                        BehaviorSituationCaptureStatus.GRAPH_DISABLED
                ),
                SemanticEnvelopeFactory.production().create(
                        observation,
                        projection.applied()
                ),
                registry
        );
    }

    private static PublishedObservation<JournalEventObservation> published(
            long sequence
    ) throws Exception {
        String rawJson = "{\"timestamp\":\"2026-08-06T09:00:0"
                + sequence
                + "Z\",\"event\":\"RegistryGuiTestEvent\"}";
        JsonNode parsed = JSON.readTree(rawJson);
        Instant sourceTime = Instant.parse(
                parsed.path("timestamp").textValue()
        );
        RawJournalData raw = new RawJournalData(
                rawJson,
                parsed,
                Optional.of("RegistryGuiTestEvent"),
                Optional.of(sourceTime)
        );
        return new PublishedObservation<>(
                "registry-gui-" + sequence,
                sequence,
                SOURCE,
                new JournalSourcePosition(
                        "Journal.registry-gui.log",
                        sequence * 100L
                ),
                Optional.of(sourceTime),
                Instant.parse("2026-08-06T09:01:00Z"),
                ObservationCaptureMode.REPLAY,
                JournalEventObservation.SCHEMA_VERSION,
                new UnknownJournalEvent(raw)
        );
    }

    private static final class RecordingHub implements KaironGuiHub {

        private final List<SystemRegistrySnapshot> snapshots =
                new CopyOnWriteArrayList<>();

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public void start() {
        }

        @Override
        public void postObservation(ObservationView observation) {
        }

        @Override
        public void postObservationEffect(ObservationEffectView effect) {
        }

        @Override
        public void postModelDecision(ModelDecisionView decision) {
        }

        @Override
        public void postModelCompletion(ModelCompletionView completion) {
        }

        @Override
        public void postSystemRegistry(SystemRegistrySnapshot snapshot) {
            snapshots.add(snapshot);
        }

        @Override
        public java.util.concurrent.CompletionStage<Void> closeRequested() {
            return new java.util.concurrent.CompletableFuture<>();
        }

        @Override
        public void close() {
        }
    }
}
