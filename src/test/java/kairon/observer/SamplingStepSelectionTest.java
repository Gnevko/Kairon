package kairon.observer;

import com.fasterxml.jackson.databind.ObjectMapper;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.event.exploration.ScanOrganic;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which steps of a sampling run are worth a turn.
 *
 * <p>A run is four records in the journal — {@code Log}, {@code Sample},
 * {@code Sample}, {@code Analyse} — and its two ends already say everything the
 * middle repeats. {@code Logged} names the organism; {@code Analysed} says it
 * is collected and what is still left on the body. The samples between carry
 * the same organism, the same {@code PROGRESS} stage and the same
 * {@code complete: false}.</p>
 *
 * <p><strong>Measured on the live session of 2026-08-08:</strong> sixteen
 * middle-step turns produced eleven comments, and all eleven were the same
 * sentence about an organism {@code Logged} had already named — "there are
 * still uncollected samples of X on the planet", twice verbatim.</p>
 */
final class SamplingStepSelectionTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The real sequence, copied from the journal of 2026-08-08. */
    private static final String LOG = """
            {"timestamp":"2026-08-08T13:59:27Z","event":"ScanOrganic",
             "ScanType":"Log","Genus":"$Codex_Ent_Tussocks_Genus_Name;",
             "Species":"$Codex_Ent_Tussocks_08_Name;",
             "SystemAddress":23155,"Body":20}
            """;
    private static final String SAMPLE = """
            {"timestamp":"2026-08-08T14:01:43Z","event":"ScanOrganic",
             "ScanType":"Sample","Genus":"$Codex_Ent_Tussocks_Genus_Name;",
             "Species":"$Codex_Ent_Tussocks_08_Name;",
             "SystemAddress":23155,"Body":20}
            """;
    private static final String ANALYSE = """
            {"timestamp":"2026-08-08T14:01:48Z","event":"ScanOrganic",
             "ScanType":"Analyse","Genus":"$Codex_Ent_Tussocks_Genus_Name;",
             "Species":"$Codex_Ent_Tussocks_08_Name;",
             "SystemAddress":23155,"Body":20}
            """;

    @Test
    void aMiddleSampleOpensNoTurn() {
        JournalEventObservation sample = parse(SAMPLE);

        assertInstanceOf(ScanOrganic.Sampled.class, sample);
        assertFalse(LlmJournalEventSelection.admitsAsTrigger(sample));
    }

    /** Both ends of the run still speak. */
    @Test
    void theFindAndTheResultAreStillSent() {
        assertTrue(LlmJournalEventSelection.admitsAsTrigger(parse(LOG)));
        assertTrue(LlmJournalEventSelection.admitsAsTrigger(parse(ANALYSE)));
        assertInstanceOf(ScanOrganic.Logged.class, parse(LOG));
        assertInstanceOf(ScanOrganic.Analysed.class, parse(ANALYSE));
    }

    /**
     * The two samples are one record, which is why the third is not the rule.
     *
     * <p>The journal numbers nothing: the second and third {@code Sample} of a
     * run differ in nothing a reader can see, and
     * {@code BiologicalSamplingProcess} keeps no counter for the same reason.
     * A rule about "the third" would have to wait five seconds for the
     * {@code Analyse} that follows and then unmake a turn already built.</p>
     */
    @Test
    void nothingDistinguishesTheSecondSampleFromTheThird() {
        String secondSample = SAMPLE.replace("14:01:43", "14:00:26");

        assertEquals(
                parse(SAMPLE).raw().parsedJsonObject().get("ScanType"),
                parse(secondSample).raw().parsedJsonObject().get("ScanType")
        );
        assertEquals(
                parse(SAMPLE).getClass(),
                parse(secondSample).getClass()
        );
    }

    /**
     * The type stays NEW-eligible; only these observations are declined.
     *
     * <p>A declined trigger is still parsed, projected into canonical state and
     * the behaviour graph, still carries its semantic effect into the next
     * turn, and still reaches the trace and the GUI. What stops is the model
     * turn.</p>
     */
    @Test
    void theTypeItselfRemainsNewEligible() {
        assertEquals(
                LlmJournalEventSelection.ObserverInputRole.NEW_ELIGIBLE,
                LlmJournalEventSelection.roleOf(ScanOrganic.Sampled.class)
        );
        // Listed under the wire record, not the variant: what kind of journal
        // event this is stays keyed by the record (ADR-0022), and the role is
        // reached through one level of interfaces.
        assertTrue(LlmJournalEventSelection.TARGET_NEW_ELIGIBLE
                .contains(ScanOrganic.class));
        assertFalse(LlmJournalEventSelection.TARGET_NEW_ELIGIBLE
                .contains(ScanOrganic.Sampled.class));
    }

    private static JournalEventObservation parse(String rawJson) {
        try {
            String compact = JSON.readTree(rawJson).toString();
            return ScanOrganic.of(new RawJournalData(
                    compact,
                    JSON.readTree(compact),
                    Optional.of("ScanOrganic"),
                    Optional.of(Instant.parse("2026-08-08T14:01:43Z"))
            ));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
