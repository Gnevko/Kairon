package kairon.observer.decision;

import kairon.observation.ObservationDraft;
import kairon.observation.ObservationDraft.ObservationCaptureMode;
import kairon.observation.ObservationDraft.ObservationSource;
import kairon.observation.PublishedObservation;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalLineParser;
import kairon.observation.journal.JournalLineParser.CompleteJournalRecord;
import kairon.observation.journal.JournalLineParser.ParsedJournalRecord;
import kairon.observation.journal.JournalObservationAdapter;
import kairon.semantics.EffectRetention;
import kairon.semantics.ObservationSemantics;
import kairon.projection.SemanticEnvelopeFactory;
import kairon.semantics.SemanticObservationEnvelope;
import kairon.semantics.SemanticSourceRole;
import kairon.state.AppliedObservation;
import kairon.state.CurrentGameStateProjector;
import kairon.state.CurrentGameStateSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static kairon.observer.decision.Journal.loadGame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * What an observation is reaches the place its effects are held.
 *
 * <p>Capture mode used to stop at the publication: {@code SemanticEnvelopeFactory}
 * read the role and dropped everything else, so neither the effect accumulator
 * nor the change selector could tell a historical effect from a live one. The
 * envelope now carries the capture mode, the source role and the effect
 * retention beside the effects they belong to.</p>
 *
 * <p>Only what has a reader is carried. Two further classifications — an
 * application mode and a model visibility — were computed, propagated and
 * asserted here while nothing in production consulted either; they are gone
 * rather than wired up, because a classification kept alive by its own tests is
 * a second answer waiting to disagree with the one in force.</p>
 */
final class SemanticMetadataPropagationTest {

    /** Every capture mode survives to where the effects are held. */
    @Test
    void captureModeReachesTheSemanticEnvelope(@TempDir Path directory) {
        for (ObservationCaptureMode captureMode
                : ObservationCaptureMode.values()) {
            try (SemanticPipelineHarness harness = SemanticPipelineHarness
                    .create(directory.resolve(captureMode.name()))) {
                harness.journal(captureMode, loadGame())
                        .journal(captureMode, jump())
                        .closeBatch();
                PipelineTrace trace = harness.trace();

                for (PipelineTrace.ObservationRecord record
                        : trace.observations()) {
                    assertEquals(
                            captureMode,
                            record.captureMode(),
                            record.rawObservationType()
                                    + " lost its capture mode\n"
                                    + trace.describe()
                    );
                }
            }
        }
    }

    /**
     * The graph contributes nothing to what an observation means.
     *
     * <p>Semantic metadata is produced inside the projection boundary, before
     * the graph is consulted and whether or not one exists.</p>
     */
    @Test
    void semanticMetadataIsTheSameWithoutAGraph(@TempDir Path directory) {
        try (SemanticPipelineHarness withGraph = SemanticPipelineHarness
                .create(directory.resolve("with-graph"));
                SemanticPipelineHarness withoutGraph = SemanticPipelineHarness
                        .create(
                                directory.resolve("without-graph"),
                                SemanticPipelineHarness.HarnessOptions
                                        .withoutGraph()
                        )) {
            run(withGraph);
            run(withoutGraph);

            assertEquals(
                    metadata(withGraph.trace()),
                    metadata(withoutGraph.trace()),
                    "with graph:\n" + withGraph.trace().describe()
                            + "\nwithout graph:\n"
                            + withoutGraph.trace().describe()
            );
        }
    }

    /**
     * The envelope copies the classification; it does not repeat it.
     *
     * <p>Proved by handing the factory an applied observation whose retention
     * the classifier would never produce — bootstrap capture is
     * {@code RESTORE_ONLY}. If the factory classified again it would answer
     * {@code RESTORE_ONLY} and disagree with the value that owns the answer.</p>
     */
    @Test
    void theEnvelopeCopiesTheClassificationRatherThanRepeatingIt() {
        SemanticJournalPublication publication = publishJump();
        AppliedObservation claimed = new AppliedObservation(
                publication.observation().busSequence(),
                publication.observation().observationId(),
                "FSDJump",
                ObservationCaptureMode.BOOTSTRAP,
                SemanticSourceRole.CONTEXT_ONLY,
                EffectRetention.RETAIN_FOR_TURN,
                CurrentGameStateSnapshot.unknown(),
                CurrentGameStateSnapshot.unknown(),
                CurrentGameStateSnapshot.unknown(),
                List.of()
        );

        SemanticObservationEnvelope envelope =
                SemanticEnvelopeFactory.production()
                        .create(publication.observation(), claimed);

        assertEquals(
                ObservationCaptureMode.BOOTSTRAP,
                envelope.captureMode()
        );
        assertEquals(
                SemanticSourceRole.CONTEXT_ONLY,
                envelope.sourceRole(),
                "the role comes from the applied observation too"
        );
        assertEquals(
                EffectRetention.RETAIN_FOR_TURN,
                envelope.effectRetention(),
                "retention is copied too, not re-derived from the capture "
                        + "mode beside it"
        );
        assertNotEquals(
                ObservationSemantics.retentionOf(
                        ObservationCaptureMode.BOOTSTRAP
                ),
                envelope.effectRetention(),
                "and it must really contradict the retention rule"
        );
    }

    /** Everything semantic on the envelope came from the same value. */
    @Test
    void everyEnvelopeFieldMatchesItsAppliedObservation(
            @TempDir Path directory
    ) {
        for (ObservationCaptureMode captureMode
                : ObservationCaptureMode.values()) {
            SemanticJournalPublication publication = publishJump();
            AppliedObservation applied = new CurrentGameStateProjector()
                    .applyAndCapture(publication.observation())
                    .applied();
            SemanticObservationEnvelope envelope =
                    SemanticEnvelopeFactory.production()
                            .create(publication.observation(), applied);

            assertEquals(applied.busSequence(), envelope.busSequence());
            assertEquals(applied.sourceRole(), envelope.sourceRole());
            assertEquals(applied.captureMode(), envelope.captureMode());
            assertEquals(
                    applied.effectRetention(),
                    envelope.effectRetention()
            );
            assertEquals(
                    applied.rawObservationType(),
                    envelope.rawObservationType()
            );
            assertEquals(
                    applied.semanticChanges(),
                    envelope.stateChanges(),
                    "the delta is the applied one, not a second copy"
            );
        }
    }

    /**
     * The factory asks for an applied observation, and only for that.
     *
     * <p>One entry point, so a caller cannot reach a version that would have to
     * classify for itself.</p>
     */
    @Test
    void theFactoryTakesTheAppliedObservation() throws Exception {
        assertNotNull(
                SemanticEnvelopeFactory.class.getMethod(
                        "create",
                        PublishedObservation.class,
                        AppliedObservation.class
                )
        );
        List<String> entryPoints = new ArrayList<>();
        for (Method method
                : SemanticEnvelopeFactory.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers())
                    && "create".equals(method.getName())) {
                entryPoints.add(Arrays.toString(method.getParameterTypes()));
            }
        }
        assertEquals(
                List.of("[class " + PublishedObservation.class.getName()
                        + ", class " + AppliedObservation.class.getName()
                        + "]"),
                entryPoints,
                "there is one way to build an envelope"
        );
    }

    /** An applied observation from another publication is refused. */
    @Test
    void anAppliedObservationFromAnotherPublicationIsRefused() {
        SemanticJournalPublication first = publishJump();
        SemanticJournalPublication second = publishJump();
        AppliedObservation applied = new CurrentGameStateProjector()
                .applyAndCapture(first.observation())
                .applied();

        assertThrows(
                IllegalArgumentException.class,
                () -> SemanticEnvelopeFactory.production()
                        .create(second.observation(), applied)
        );
    }

    // ------------------------------------------------------------- fixtures

    private static void run(SemanticPipelineHarness harness) {
        harness.journal(loadGame())
                .journal(ObservationCaptureMode.BOOTSTRAP, jump())
                .journal(location())
                .journal(startJump())
                .journal(ObservationCaptureMode.LIVE, receiveText())
                .closeBatch();
    }

    /** Every observation's semantic metadata, in bus order, as plain text. */
    private static List<String> metadata(PipelineTrace trace) {
        List<String> lines = new ArrayList<>();
        for (PipelineTrace.ObservationRecord record : trace.observations()) {
            lines.add(record.busSequence()
                    + " " + record.rawObservationType()
                    + " " + record.captureMode()
                    + " " + record.sourceRole()
                    + " " + record.effectRetention());
        }
        return List.copyOf(lines);
    }

    /** One published journal record, built by the production adapter. */
    private record SemanticJournalPublication(
            PublishedObservation<JournalEventObservation> observation
    ) {
    }

    private long publishedBusSequence;
    private long publishedOffset;

    private SemanticJournalPublication publishJump() {
        byte[] bytes = jump().strip().getBytes(StandardCharsets.UTF_8);
        ParsedJournalRecord parsed = (ParsedJournalRecord) new JournalLineParser()
                .parse(new CompleteJournalRecord(
                        "Journal.metadata-test.log",
                        publishedOffset,
                        bytes
                ));
        publishedOffset += bytes.length + 1L;
        ObservationDraft<JournalEventObservation> draft =
                new JournalObservationAdapter(
                        new ObservationSource(
                                "elite-journal",
                                "metadata-test"
                        )
                ).adapt(
                        parsed,
                        ObservationCaptureMode.REPLAY,
                        parsed.optionalJournalTimestamp().orElseThrow()
                );
        return new SemanticJournalPublication(new PublishedObservation<>(
                draft.observationId(),
                ++publishedBusSequence,
                draft.source(),
                draft.sourcePosition(),
                draft.sourceTime(),
                draft.observedAt(),
                draft.captureMode(),
                draft.schemaVersion(),
                draft.payload()
        ));
    }
    private static String jump() {
        return """
                {"timestamp":"2026-07-30T10:00:01Z","event":"FSDJump",
                 "StarSystem":"Schieni","SystemAddress":23155,"JumpDist":8.5,
                 "FuelUsed":0.4,"FuelLevel":30.2}
                """;
    }

    private static String location() {
        return """
                {"timestamp":"2026-07-30T10:00:02Z","event":"Location",
                 "StarSystem":"Schieni","SystemAddress":23155,"Docked":false}
                """;
    }

    private static String startJump() {
        return """
                {"timestamp":"2026-07-30T10:00:03Z","event":"StartJump",
                 "JumpType":"Supercruise"}
                """;
    }

    private static String cargo() {
        return """
                {"timestamp":"2026-07-30T10:00:04Z","event":"Cargo",
                 "Vessel":"Ship","Count":0,"Inventory":[]}
                """;
    }

    private static String barycentre() {
        return """
                {"timestamp":"2026-07-30T10:00:05Z","event":"ScanBaryCentre",
                 "StarSystem":"Schieni","SystemAddress":23155,"BodyID":7,
                 "SemiMajorAxis":1.0,"Eccentricity":0.1}
                """;
    }

    private static String approach() {
        return """
                {"timestamp":"2026-07-30T10:00:06Z","event":"ApproachBody",
                 "StarSystem":"Schieni","SystemAddress":23155,
                 "Body":"Schieni 4 a","BodyID":20}
                """;
    }

    private static String bodySignals() {
        return """
                {"timestamp":"2026-07-30T10:00:07Z","event":"FSSBodySignals",
                 "StarSystem":"Schieni","SystemAddress":23155,"BodyID":20,
                 "BodyName":"Schieni 4 a",
                 "Signals":[{"Type":"$SAA_SignalType_Biological;","Count":1}]}
                """;
    }

    private static String receiveText() {
        return """
                {"timestamp":"2026-07-30T10:00:08Z","event":"ReceiveText",
                 "Channel":"player","From":"Ana","Message":"see you there",
                 "Message_Localised":"see you there"}
                """;
    }
}
