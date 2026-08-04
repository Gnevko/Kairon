package kairon.state;

import kairon.observation.ObservationDraft;
import kairon.observation.ObservationDraft.ObservationCaptureMode;
import kairon.observation.ObservationDraft.ObservationSource;
import kairon.observation.PublishedObservation;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalLineParser;
import kairon.observation.journal.JournalLineParser.CompleteJournalRecord;
import kairon.observation.journal.JournalLineParser.ParsedJournalRecord;
import kairon.observation.journal.JournalObservationAdapter;
import kairon.semantics.SemanticChangeKind;
import kairon.semantics.SemanticField;
import kairon.semantics.SemanticStateChange;
import kairon.semantics.SemanticValue;
import kairon.semantics.SemanticValueOrigin;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The canonical active organic-sampling sequence.
 *
 * <p>Every case drives the real parser, adapter and projector, and reads the
 * exact field-level delta the projection boundary produced.</p>
 */
final class BiologicalSamplingProcessStateTest {

    // ---------------------------------------------------------------- setup

    /** Establishes a commander, a system and a body before any sampling. */
    private static Fixture onABodyWithBiology() {
        Fixture fixture = new Fixture();
        fixture.apply("""
                {"timestamp":"2026-07-30T10:00:00Z","event":"Commander",
                 "FID":"F100","Name":"Cmdr Test"}
                """);
        fixture.apply("""
                {"timestamp":"2026-07-30T10:00:01Z","event":"ApproachBody",
                 "StarSystem":"Alpha","SystemAddress":7101,"Body":"Alpha 1",
                 "BodyID":3}
                """);
        return fixture;
    }

    private static String scan(
            String timestamp,
            String scanType,
            String genus,
            String genusLabel
    ) {
        return """
                {"timestamp":"%s","event":"ScanOrganic","ScanType":"%s",
                 "Genus":"%s","Genus_Localised":"%s",
                 "Species":"$Codex_Bullaris_Species;",
                 "Species_Localised":"Bullaris",
                 "SystemAddress":7101,"Body":3}
                """.formatted(timestamp, scanType, genus, genusLabel);
    }

    private static final String LOG =
            scan("2026-07-30T10:01:00Z", "Log", "$Codex_Bacterial_Genus;",
                    "Бактерии");
    private static final String SAMPLE =
            scan("2026-07-30T10:02:00Z", "Sample", "$Codex_Bacterial_Genus;",
                    "Бактерии");
    private static final String SAMPLE_TWO =
            scan("2026-07-30T10:03:00Z", "Sample", "$Codex_Bacterial_Genus;",
                    "Бактерии");
    private static final String ANALYSE =
            scan("2026-07-30T10:04:00Z", "Analyse", "$Codex_Bacterial_Genus;",
                    "Бактерии");

    // ------------------------------------------------------------ lifecycle

    @Test
    void logStartsASequenceWithFullCanonicalIdentity() {
        Fixture fixture = onABodyWithBiology();

        fixture.apply(LOG);
        CurrentGameStateSnapshot state = fixture.snapshot();
        BiologicalSamplingProcess process = state.samplingProcess();

        assertEquals(Boolean.TRUE, state.activeOrganicSampling());
        assertNotNull(process);
        assertEquals(BiologicalSamplingStage.START, process.stage());
        assertEquals(7101L, process.systemAddress());
        assertEquals(3L, process.bodyId());
        assertEquals(
                "$Codex_Bacterial_Genus;",
                process.genus().identifier()
        );
        assertEquals("Бактерии", process.genus().label());
        assertEquals(
                "$Codex_Bullaris_Species;",
                process.species().identifier()
        );
        assertNull(process.variant(), "no variant is not an unknown variant");
    }

    @Test
    void sampleAdvancesTheSameSequenceToProgress() {
        Fixture fixture = onABodyWithBiology();
        fixture.apply(LOG);

        fixture.apply(SAMPLE);
        BiologicalSamplingProcess process =
                fixture.snapshot().samplingProcess();

        assertEquals(BiologicalSamplingStage.PROGRESS, process.stage());
        assertEquals(
                "$Codex_Bacterial_Genus;",
                process.genus().identifier()
        );

        List<SemanticStateChange> changes = fixture.changes();
        assertEquals(
                Optional.of(SemanticValue.ofSymbol("PROGRESS")),
                Optional.ofNullable(
                        after(changes, SemanticField.ORGANIC_SAMPLING_STAGE)
                )
        );
        assertNull(
                changeFor(changes, SemanticField.ORGANIC_SAMPLING_GENUS),
                "an unchanged identity is not a change"
        );
        assertNull(
                changeFor(changes, SemanticField.ACTIVE_ORGANIC_SAMPLING),
                "already-active sampling does not re-establish itself"
        );
    }

    @Test
    void repeatedSampleProducesNoDelta() {
        Fixture fixture = onABodyWithBiology();
        fixture.apply(LOG);
        fixture.apply(SAMPLE);

        fixture.apply(SAMPLE_TWO);

        assertTrue(
                fixture.changes().isEmpty(),
                "a second Sample of the same sequence changes nothing"
        );
        assertEquals(
                BiologicalSamplingStage.PROGRESS,
                fixture.snapshot().samplingProcess().stage()
        );
    }

    @Test
    void analyseEndsTheSequenceAndClearsItsIdentity() {
        Fixture fixture = onABodyWithBiology();
        fixture.apply(LOG);
        fixture.apply(SAMPLE);

        fixture.apply(ANALYSE);
        CurrentGameStateSnapshot state = fixture.snapshot();

        assertEquals(Boolean.FALSE, state.activeOrganicSampling());
        assertNull(state.samplingProcess());

        List<SemanticStateChange> changes = fixture.changes();
        assertEquals(
                SemanticChangeKind.UPDATED,
                changeFor(changes, SemanticField.ACTIVE_ORGANIC_SAMPLING)
                        .changeKind()
        );
        for (SemanticField field : List.of(
                SemanticField.ORGANIC_SAMPLING_SYSTEM_ADDRESS,
                SemanticField.ORGANIC_SAMPLING_BODY_ID,
                SemanticField.ORGANIC_SAMPLING_GENUS,
                SemanticField.ORGANIC_SAMPLING_GENUS_LABEL,
                SemanticField.ORGANIC_SAMPLING_SPECIES,
                SemanticField.ORGANIC_SAMPLING_STAGE
        )) {
            SemanticStateChange change = changeFor(changes, field);
            assertNotNull(change, field + " must be cleared explicitly");
            assertEquals(SemanticChangeKind.CLEARED, change.changeKind());
            assertFalse(change.after().known());
        }
    }

    // ------------------------------------------------------------- identity

    @Test
    void theSameBodyIdInAnotherSystemIsAnotherBody() {
        BiologicalSamplingProcess here = new BiologicalSamplingProcess(
                7101L, 3L, TaxonName.of("g", null), null, null,
                BiologicalSamplingStage.START
        );
        BiologicalSamplingProcess elsewhere = new BiologicalSamplingProcess(
                7102L, 3L, TaxonName.of("g", null), null, null,
                BiologicalSamplingStage.START
        );

        assertFalse(here.sameSequenceAs(elsewhere));
    }

    @Test
    void rawIdentifierNotTheLocalisedLabelDecidesIdentity() {
        TaxonName russian = TaxonName.of("$Codex_Bacterial_Genus;", "Бактерии");
        TaxonName english = TaxonName.of("$Codex_Bacterial_Genus;", "Bacteria");
        TaxonName other = TaxonName.of("$Codex_Fungoida_Genus;", "Бактерии");

        assertTrue(TaxonName.sameIdentity(russian, english));
        assertFalse(TaxonName.sameIdentity(russian, other));
    }

    @Test
    void aLocalisedLabelAloneEstablishesNoTaxon() {
        assertNull(TaxonName.of(null, "Бактерии"));
        assertNull(TaxonName.of("  ", "Бактерии"));
    }

    @Test
    void aMissingLabelDoesNotEraseAKnownOneForTheSameIdentity() {
        Fixture fixture = onABodyWithBiology();
        fixture.apply(LOG);

        fixture.apply("""
                {"timestamp":"2026-07-30T10:02:00Z","event":"ScanOrganic",
                 "ScanType":"Sample","Genus":"$Codex_Bacterial_Genus;",
                 "Species":"$Codex_Bullaris_Species;",
                 "SystemAddress":7101,"Body":3}
                """);

        assertEquals(
                "Бактерии",
                fixture.snapshot().samplingProcess().genus().label(),
                "an absent _Localised field is not a retraction"
        );
    }

    @Test
    void aNewSequenceInheritsNothingFromTheOldOne() {
        Fixture fixture = onABodyWithBiology();
        fixture.apply("""
                {"timestamp":"2026-07-30T10:01:00Z","event":"ScanOrganic",
                 "ScanType":"Log","Genus":"$Codex_Bacterial_Genus;",
                 "Species":"$Codex_Bullaris_Species;",
                 "Variant":"$Codex_Bullaris_Red;",
                 "Variant_Localised":"красный",
                 "SystemAddress":7101,"Body":3}
                """);

        fixture.apply("""
                {"timestamp":"2026-07-30T10:05:00Z","event":"ScanOrganic",
                 "ScanType":"Log","Genus":"$Codex_Fungoida_Genus;",
                 "Species":"$Codex_Setisis_Species;",
                 "SystemAddress":7101,"Body":3}
                """);
        BiologicalSamplingProcess process =
                fixture.snapshot().samplingProcess();

        assertEquals(
                "$Codex_Fungoida_Genus;",
                process.genus().identifier()
        );
        assertNull(
                process.variant(),
                "the previous sequence's variant must not follow the new one"
        );
        assertEquals(BiologicalSamplingStage.START, process.stage());
    }

    // ---------------------------------------------------------- preservation

    @Test
    void routineMovementPreservesTheActiveSequence() {
        Fixture fixture = onABodyWithBiology();
        fixture.apply(LOG);

        fixture.apply("""
                {"timestamp":"2026-07-30T10:01:10Z","event":"Embark",
                 "StarSystem":"Alpha","SystemAddress":7101,"Body":"Alpha 1",
                 "BodyID":3,"SRV":true,"ID":10}
                """);
        fixture.apply("""
                {"timestamp":"2026-07-30T10:01:20Z","event":"Liftoff",
                 "StarSystem":"Alpha","SystemAddress":7101,"Body":"Alpha 1",
                 "BodyID":3,"PlayerControlled":true}
                """);
        fixture.apply("""
                {"timestamp":"2026-07-30T10:01:30Z","event":"Touchdown",
                 "StarSystem":"Alpha","SystemAddress":7101,"Body":"Alpha 1",
                 "BodyID":3,"PlayerControlled":true}
                """);
        fixture.apply("""
                {"timestamp":"2026-07-30T10:01:40Z","event":"Disembark",
                 "StarSystem":"Alpha","SystemAddress":7101,"Body":"Alpha 1",
                 "BodyID":3,"OnPlanet":true,"SRV":false}
                """);

        CurrentGameStateSnapshot state = fixture.snapshot();
        assertEquals(Boolean.TRUE, state.activeOrganicSampling());
        assertNotNull(
                state.samplingProcess(),
                "moving around a body does not end a sampling sequence"
        );
        assertEquals(
                BiologicalSamplingStage.START,
                state.samplingProcess().stage()
        );

        fixture.apply(SAMPLE);
        assertEquals(
                BiologicalSamplingStage.PROGRESS,
                fixture.snapshot().samplingProcess().stage()
        );
    }

    // ---------------------------------------------------------------- reset

    @Test
    void leavingTheBodyClearsTheSequence() {
        assertClearedBy("""
                {"timestamp":"2026-07-30T10:06:00Z","event":"LeaveBody",
                 "StarSystem":"Alpha","SystemAddress":7101,"Body":"Alpha 1",
                 "BodyID":3}
                """);
    }

    @Test
    void enteringSupercruiseClearsTheSequence() {
        assertClearedBy("""
                {"timestamp":"2026-07-30T10:06:00Z","event":"SupercruiseEntry",
                 "StarSystem":"Alpha","SystemAddress":7101}
                """);
    }

    @Test
    void jumpingClearsTheSequence() {
        assertClearedBy("""
                {"timestamp":"2026-07-30T10:06:00Z","event":"FSDJump",
                 "StarSystem":"Beta","SystemAddress":7102,"JumpDist":10.0}
                """);
    }

    @Test
    void anotherCommanderClearsTheSequence() {
        Fixture fixture = onABodyWithBiology();
        fixture.apply(LOG);

        fixture.apply("""
                {"timestamp":"2026-07-30T10:06:00Z","event":"Commander",
                 "FID":"F200","Name":"Cmdr Other"}
                """);
        CurrentGameStateSnapshot state = fixture.snapshot();

        assertNull(state.samplingProcess());
        assertNull(
                state.activeOrganicSampling(),
                "a different commander makes sampling unknown, not inactive"
        );
    }

    private static void assertClearedBy(String rawJson) {
        Fixture fixture = onABodyWithBiology();
        fixture.apply(LOG);

        fixture.apply(rawJson);
        CurrentGameStateSnapshot state = fixture.snapshot();

        assertNull(state.samplingProcess());
        assertEquals(Boolean.FALSE, state.activeOrganicSampling());

        SemanticStateChange cleared = changeFor(
                fixture.changes(),
                SemanticField.ORGANIC_SAMPLING_GENUS
        );
        assertNotNull(cleared, "clearing is an exact delta, not a silent drop");
        assertEquals(SemanticChangeKind.CLEARED, cleared.changeKind());
    }

    // --------------------------------------------------------- unknown stage

    @Test
    void anUnknownScanTypeLeavesTheSequenceExactlyAsItWas() {
        Fixture fixture = onABodyWithBiology();
        fixture.apply(LOG);

        fixture.apply("""
                {"timestamp":"2026-07-30T10:02:00Z","event":"ScanOrganic",
                 "ScanType":"Rumour","Genus":"$Codex_Bacterial_Genus;",
                 "SystemAddress":7101,"Body":3}
                """);

        assertTrue(
                fixture.changes().isEmpty(),
                "no repository evidence establishes another transition"
        );
        assertEquals(
                BiologicalSamplingStage.START,
                fixture.snapshot().samplingProcess().stage()
        );
    }

    // ----------------------------------------------------------------- delta

    @Test
    void theDeltaCarriesExactProvenanceAndNeverActivatedFromContext() {
        Fixture fixture = onABodyWithBiology();

        PublishedObservation<JournalEventObservation> log = fixture.apply(LOG);
        SemanticStateChange stage = changeFor(
                fixture.changes(),
                SemanticField.ORGANIC_SAMPLING_STAGE
        );

        assertNotNull(stage);
        assertEquals(
                log.busSequence(),
                stage.provenance().busSequence()
        );
        assertEquals("ScanOrganic", stage.provenance().rawObservationType());
        assertEquals(
                SemanticField.ORGANIC_SAMPLING_STAGE.subject(),
                stage.field().subject()
        );
        assertEquals(SemanticChangeKind.ESTABLISHED, stage.changeKind());
        assertEquals(SemanticValueOrigin.OBSERVATION, stage.origin());
        assertFalse(stage.before().known());
        assertEquals(SemanticValue.ofSymbol("START"), stage.after());

        for (SemanticStateChange change : fixture.changes()) {
            assertFalse(
                    change.changeKind()
                            == SemanticChangeKind.ACTIVATED_FROM_CONTEXT,
                    "a sampling write is never a stored-context activation"
            );
        }
    }

    @Test
    void anInactiveSnapshotCannotRetainAProcess() {
        CurrentGameStateSnapshot unknown = CurrentGameStateSnapshot.unknown();

        assertNull(unknown.samplingProcess());
        assertNull(unknown.activeOrganicSampling());
    }

    // --------------------------------------------------------------- helpers

    private static SemanticStateChange changeFor(
            List<SemanticStateChange> changes,
            SemanticField field
    ) {
        return changes.stream()
                .filter(change -> change.field() == field)
                .findFirst()
                .orElse(null);
    }

    private static SemanticValue after(
            List<SemanticStateChange> changes,
            SemanticField field
    ) {
        SemanticStateChange change = changeFor(changes, field);
        return change == null ? null : change.after();
    }

    private static final class Fixture {

        private static final ObservationSource SOURCE =
                new ObservationSource("elite-journal", "sampling-test");

        private final JournalLineParser parser = new JournalLineParser();
        private final JournalObservationAdapter adapter =
                new JournalObservationAdapter(SOURCE);
        private final CurrentGameStateProjector projector =
                new CurrentGameStateProjector();
        private CurrentGameStateProjection lastProjection;
        private long sourceOffset;
        private long busSequence;

        private PublishedObservation<JournalEventObservation> apply(
                String rawJson
        ) {
            byte[] bytes = rawJson.strip().getBytes(StandardCharsets.UTF_8);
            ParsedJournalRecord parsed = assertInstanceOf(
                    ParsedJournalRecord.class,
                    parser.parse(new CompleteJournalRecord(
                            "Journal.sampling-test.log",
                            sourceOffset,
                            bytes
                    ))
            );
            sourceOffset += bytes.length + 1L;
            ObservationDraft<JournalEventObservation> draft = adapter.adapt(
                    parsed,
                    ObservationCaptureMode.REPLAY,
                    parsed.optionalJournalTimestamp().orElse(Instant.EPOCH)
            );
            PublishedObservation<JournalEventObservation> observation =
                    new PublishedObservation<>(
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
            lastProjection = projector.applyAndCapture(observation);
            return observation;
        }

        private CurrentGameStateSnapshot snapshot() {
            return projector.currentSnapshot();
        }

        private List<SemanticStateChange> changes() {
            return lastProjection.semanticChanges();
        }
    }
}
