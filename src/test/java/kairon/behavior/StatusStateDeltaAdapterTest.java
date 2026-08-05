package kairon.behavior;

import kairon.behavior.normalize.NormalizedEventType;
import kairon.behavior.status.StatusStateDeltaAdapter;
import kairon.behavior.status.StatusStateDeltaAdapter.StatusDeltaBatch;
import kairon.observation.ObservationDraft;
import kairon.observation.ObservationDraft.ObservationCaptureMode;
import kairon.observation.ObservationDraft.ObservationSource;
import kairon.observation.PublishedObservation;
import kairon.observation.status.StatusObservationAdapter;
import kairon.observation.status.StatusSnapshotObservation;
import kairon.observation.status.StatusSnapshotParser;
import kairon.observation.status.StatusSnapshotParser.ParsedStatusSnapshot;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class StatusStateDeltaAdapterTest {

    private final StatusStateDeltaAdapter deltaAdapter =
            new StatusStateDeltaAdapter();
    private final StatusSnapshotParser parser = new StatusSnapshotParser();
    private final StatusObservationAdapter observationAdapter =
            new StatusObservationAdapter(
                    new ObservationSource(
                            "elite-dangerous-status",
                            "behavior-status-test"
                    ),
                    "Status.json"
            );

    @Test
    void firstKnownFieldsAreBaselinesAndMissingFieldsRetainKnownState() {
        assertEquals(0, adapt(0, 1, """
                {"timestamp":"2026-07-29T10:00:00Z",
                 "event":"Status","Flags":0}
                """).deltas().size());
        assertEquals(0, adapt(1, 2, """
                {"timestamp":"2026-07-29T10:00:01Z",
                 "event":"Status","GuiFocus":9}
                """).deltas().size());

        StatusDeltaBatch gear = adapt(2, 3, """
                {"timestamp":"2026-07-29T10:00:02Z",
                 "event":"Status","Flags":4}
                """);
        assertEquals(
                NormalizedEventType.LANDING_GEAR_DEPLOYED,
                gear.deltas().getFirst().eventType()
        );

        assertEquals(0, adapt(3, 4, """
                {"timestamp":"2026-07-29T10:00:03Z",
                 "event":"Status"}
                """).deltas().size());
        StatusDeltaBatch leaveFss = adapt(4, 5, """
                {"timestamp":"2026-07-29T10:00:04Z",
                 "event":"Status","GuiFocus":0}
                """);
        assertEquals(
                NormalizedEventType.FSS_MODE_EXITED,
                leaveFss.deltas().getFirst().eventType()
        );
    }

    @Test
    void scannerSwitchEmitsExitsBeforeEntersThenLandingGearChange() {
        assertEquals(0, adapt(0, 1, """
                {"timestamp":"2026-07-29T11:00:00Z",
                 "event":"Status","Flags":0,"GuiFocus":9}
                """).deltas().size());

        StatusDeltaBatch changes = adapt(1, 2, """
                {"timestamp":"2026-07-29T11:00:01Z",
                 "event":"Status","Flags":4,"GuiFocus":10}
                """);

        assertEquals(
                java.util.List.of(
                        NormalizedEventType.FSS_MODE_EXITED,
                        NormalizedEventType.SAA_MODE_ENTERED,
                        NormalizedEventType.LANDING_GEAR_DEPLOYED
                ),
                changes.deltas().stream()
                        .map(StatusStateDeltaAdapter.StatusStateDelta::eventType)
                        .toList()
        );
        assertEquals(
                java.util.List.of(0, 1, 2),
                changes.deltas().stream()
                        .map(StatusStateDeltaAdapter.StatusStateDelta::ordinal)
                        .toList()
        );
    }

    /**
     * A glide is a status flag and nothing else.
     *
     * <p>The unpowered descent between orbital cruise and the surface has no
     * journal event of its own — {@code ApproachBody} and {@code LeaveBody}
     * report the orbital-cruise zone, not the glide inside it — so bit 12 of
     * {@code Flags2} is the only source there is. Like every other flag, the
     * first snapshot is a baseline and only a transition is a delta.</p>
     */
    @Test
    void glideEntryAndExitAreDerivedFromFlags2() {
        assertEquals(0, adapt(0, 1, """
                {"timestamp":"2026-07-29T13:00:00Z",
                 "event":"Status","Flags2":0}
                """).deltas().size());

        StatusDeltaBatch entered = adapt(1, 2, """
                {"timestamp":"2026-07-29T13:00:01Z",
                 "event":"Status","Flags2":4096}
                """);
        assertEquals(
                NormalizedEventType.GLIDE_ENTERED,
                entered.deltas().getFirst().eventType()
        );

        assertEquals(
                0,
                adapt(2, 3, """
                        {"timestamp":"2026-07-29T13:00:02Z",
                         "event":"Status","Flags2":4097}
                        """).deltas().size(),
                "another bit moving is not a glide transition"
        );

        StatusDeltaBatch exited = adapt(3, 4, """
                {"timestamp":"2026-07-29T13:00:03Z",
                 "event":"Status","Flags2":1}
                """);
        assertEquals(
                NormalizedEventType.GLIDE_EXITED,
                exited.deltas().getFirst().eventType()
        );

        assertEquals(
                0,
                adapt(4, 5, """
                        {"timestamp":"2026-07-29T13:00:04Z",
                         "event":"Status","Flags":0}
                        """).deltas().size(),
                "a snapshot without Flags2 retains what was known"
        );
    }

    @Test
    void exactDuplicateIsIdempotentAndOtherOutOfOrderSnapshotIsRejected() {
        String baseline = """
                {"timestamp":"2026-07-29T12:00:00Z",
                 "event":"Status","Flags":0,"GuiFocus":0}
                """;
        assertEquals(0, adapt(0, 1, baseline).deltas().size());
        assertEquals(0, adapt(0, 2, baseline).deltas().size());

        assertThrows(IllegalStateException.class, () -> adapt(0, 3, """
                {"timestamp":"2026-07-29T12:00:01Z",
                 "event":"Status","Flags":4,"GuiFocus":0}
                """));
    }

    private StatusDeltaBatch adapt(
            long snapshotSequence,
            long busSequence,
            String json
    ) {
        StatusSnapshotObservation snapshot = assertInstanceOf(
                ParsedStatusSnapshot.class,
                parser.parse(json.strip().getBytes(StandardCharsets.UTF_8))
        ).observation();
        Instant observedAt = snapshot.optionalStatusTimestamp()
                .orElseThrow();
        ObservationDraft<StatusSnapshotObservation> draft =
                observationAdapter.adapt(
                        snapshot,
                        snapshotSequence,
                        ObservationCaptureMode.LIVE,
                        observedAt
                );
        PublishedObservation<StatusSnapshotObservation> published =
                new PublishedObservation<>(
                        draft.observationId(),
                        busSequence,
                        draft.source(),
                        draft.sourcePosition(),
                        draft.sourceTime(),
                        draft.observedAt(),
                        draft.captureMode(),
                        draft.schemaVersion(),
                        draft.payload()
                );
        return deltaAdapter.adapt(published);
    }
}
