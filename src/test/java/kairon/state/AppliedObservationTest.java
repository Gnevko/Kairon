package kairon.state;

import kairon.observation.ObservationDraft;
import kairon.observation.ObservationDraft.ObservationCaptureMode;
import kairon.observation.ObservationDraft.ObservationSource;
import kairon.observation.ObservationDraft.SourcePosition;
import kairon.observation.ObservationPayload;
import kairon.observation.PublishedObservation;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalLineParser;
import kairon.observation.journal.JournalLineParser.CompleteJournalRecord;
import kairon.observation.journal.JournalLineParser.ParsedJournalRecord;
import kairon.observation.journal.JournalObservationAdapter;
import kairon.observation.source.ObservationSourceSignal;
import kairon.observation.source.ObservationSourceSignal
        .ObservationSourceSignalType;
import kairon.semantics.SemanticSourceRole;
import kairon.semantics.SemanticStateChange;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One applied observation, classified once and carried whole.
 *
 * <p>The value is what a later phase will read instead of asking two layers
 * separately. These tests hold what it must be before anything depends on it:
 * immutable, complete for one post-event moment, and classified by the policies
 * that already decide these things rather than by a new opinion.</p>
 */
final class AppliedObservationTest {

    private static final ObservationSource SOURCE =
            new ObservationSource("elite-journal", "applied-test");
    private static final Instant TIME =
            Instant.parse("2026-07-30T10:00:00Z");

    private final JournalLineParser parser = new JournalLineParser();
    private final JournalObservationAdapter adapter =
            new JournalObservationAdapter(SOURCE);
    private final CurrentGameStateProjector projector =
            new CurrentGameStateProjector();

    private long offset;
    private long busSequence;

    @Test
    void oneAppliedObservationCarriesOnePostEventMoment() {
        CurrentGameStateProjection projection = apply(
                ObservationCaptureMode.REPLAY,
                jump()
        );
        AppliedObservation applied = projection.applied();

        assertEquals(projection.busSequence(), applied.busSequence());
        assertSame(projection.currentState(), applied.currentState());
        assertSame(projection.previousState(), applied.previousState());
        assertSame(
                projection.observationContext(),
                applied.observationContext()
        );
        assertEquals("FSDJump", applied.rawObservationType());
        assertNotSame(
                applied.previousState(),
                applied.currentState(),
                "a jump changed something, so the two snapshots differ"
        );
        assertEquals("Schieni", applied.currentState().systemName());
        assertTrue(
                applied.previousState().systemName() == null,
                "and the state before it knew no system"
        );
        assertTrue(applied.changedState());
    }

    @Test
    void theChangeListIsCopiedRatherThanShared() {
        PublishedObservation<JournalEventObservation> observation =
                published(ObservationCaptureMode.REPLAY, jump());
        CurrentGameStateProjection projection =
                projector.applyAndCapture(observation);
        List<SemanticStateChange> mutable =
                new ArrayList<>(projection.semanticChanges());
        AppliedObservation applied = AppliedObservation.of(
                observation,
                projection.previousState(),
                projection.currentState(),
                projection.currentState(),
                mutable
        );
        int before = applied.semanticChanges().size();
        mutable.clear();

        assertEquals(
                before,
                applied.semanticChanges().size(),
                "the value owns its changes"
        );
        assertThrows(
                UnsupportedOperationException.class,
                () -> applied.semanticChanges().clear()
        );
    }

    @Test
    void aChangeFromAnotherObservationIsRefused() {
        CurrentGameStateProjection projection = apply(
                ObservationCaptureMode.REPLAY,
                jump()
        );
        PublishedObservation<?> later =
                published(ObservationCaptureMode.REPLAY, jump());

        assertThrows(
                IllegalArgumentException.class,
                () -> AppliedObservation.of(
                        later,
                        projection.previousState(),
                        projection.currentState(),
                        projection.currentState(),
                        projection.semanticChanges()
                ),
                "a delta belongs to the observation that produced it"
        );
    }

    // ------------------------------------------------------------ fixtures

    private AppliedObservation applied(
            ObservationCaptureMode captureMode,
            String rawJson
    ) {
        return apply(captureMode, rawJson).applied();
    }

    private CurrentGameStateProjection apply(
            ObservationCaptureMode captureMode,
            String rawJson
    ) {
        return projector.applyAndCapture(published(captureMode, rawJson));
    }

    private PublishedObservation<JournalEventObservation> published(
            ObservationCaptureMode captureMode,
            String rawJson
    ) {
        byte[] bytes = rawJson.strip().getBytes(StandardCharsets.UTF_8);
        ParsedJournalRecord parsed = assertInstanceOf(
                ParsedJournalRecord.class,
                parser.parse(new CompleteJournalRecord(
                        "Journal.applied-test.log",
                        offset,
                        bytes
                ))
        );
        offset += bytes.length + 1L;
        ObservationDraft<JournalEventObservation> draft = adapter.adapt(
                parsed,
                captureMode,
                parsed.optionalJournalTimestamp().orElse(TIME)
        );
        return publish(draft);
    }

    private PublishedObservation<ObservationSourceSignal> replayExhausted() {
        return publish(new ObservationDraft<>(
                "replay-source-exhausted",
                SOURCE,
                new TestSourcePosition(offset + 1),
                Optional.empty(),
                TIME,
                ObservationCaptureMode.REPLAY,
                ObservationSourceSignal.SCHEMA_VERSION,
                new ObservationSourceSignal(
                        ObservationSourceSignalType.REPLAY_SOURCE_EXHAUSTED
                )
        ));
    }

    private <T extends ObservationPayload> PublishedObservation<T> publish(
            ObservationDraft<T> draft
    ) {
        return new PublishedObservation<>(
                draft.observationId(),
                ++busSequence,
                draft.source(),
                draft.sourcePosition(),
                draft.sourceTime(),
                draft.observedAt(),
                draft.captureMode(),
                draft.schemaVersion(),
                draft.payload()
        );
    }

    private static String jump() {
        return """
                {"timestamp":"2026-07-30T10:00:01Z","event":"FSDJump",
                 "StarSystem":"Schieni","SystemAddress":23155,"JumpDist":8.5,
                 "FuelUsed":0.4,"FuelLevel":30.2}
                """;
    }

    private static String startJump() {
        return """
                {"timestamp":"2026-07-30T10:00:02Z","event":"StartJump",
                 "JumpType":"Supercruise"}
                """;
    }

    private static String location() {
        return """
                {"timestamp":"2026-07-30T10:00:03Z","event":"Location",
                 "StarSystem":"Schieni","SystemAddress":23155,"Docked":false}
                """;
    }

    private static String loadGame() {
        return """
                {"timestamp":"2026-07-30T10:00:04Z","event":"LoadGame",
                 "FID":"F12345678","ShipID":9,"Ship":"explorer_nx",
                 "ShipName":"Wanderer"}
                """;
    }

    private static String commander() {
        return """
                {"timestamp":"2026-07-30T10:00:05Z","event":"Commander",
                 "FID":"F12345678","Name":"Ana"}
                """;
    }

    private static String cargo() {
        return """
                {"timestamp":"2026-07-30T10:00:06Z","event":"Cargo",
                 "Vessel":"Ship","Count":0,"Inventory":[]}
                """;
    }

    private static String shutdown() {
        return """
                {"timestamp":"2026-07-30T10:00:07Z","event":"Shutdown"}
                """;
    }

    private static String scanBaryCentre() {
        return """
                {"timestamp":"2026-07-30T10:00:08Z","event":"ScanBaryCentre",
                 "StarSystem":"Schieni","SystemAddress":23155,"BodyID":7,
                 "SemiMajorAxis":1.0,"Eccentricity":0.1}
                """;
    }

    private static String touchdown() {
        return """
                {"timestamp":"2026-07-30T10:00:09Z","event":"Touchdown",
                 "StarSystem":"Schieni","SystemAddress":23155,
                 "Body":"Schieni 4 a","BodyID":20,"PlayerControlled":true,
                 "Latitude":1.0,"Longitude":2.0}
                """;
    }

    private static String bodySignals() {
        return """
                {"timestamp":"2026-07-30T10:00:10Z","event":"FSSBodySignals",
                 "StarSystem":"Schieni","SystemAddress":23155,"BodyID":20,
                 "BodyName":"Schieni 4 a",
                 "Signals":[{"Type":"$SAA_SignalType_Biological;","Count":1}]}
                """;
    }

    private static String detailedScan() {
        return """
                {"timestamp":"2026-07-30T10:00:11Z","event":"Scan",
                 "ScanType":"Detailed","StarSystem":"Schieni",
                 "SystemAddress":23155,"BodyID":20,
                 "BodyName":"Schieni 4 a","PlanetClass":"Icy body",
                 "Landable":true,"WasDiscovered":false,"WasMapped":false}
                """;
    }

    private static String receiveText() {
        return """
                {"timestamp":"2026-07-30T10:00:12Z","event":"ReceiveText",
                 "Channel":"player","From":"Ana","Message":"see you there",
                 "Message_Localised":"see you there"}
                """;
    }

    private record TestSourcePosition(long sequence)
            implements SourcePosition {
    }
}
