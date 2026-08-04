package kairon.behavior;

import kairon.behavior.classify.EventSignificancePolicy;
import kairon.behavior.classify.EventSignificancePolicy.EventSignificance;
import kairon.behavior.normalize.BehaviorEventNormalizer;
import kairon.behavior.normalize.NormalizedBehaviorEvent;
import kairon.behavior.normalize.NormalizedEventType;
import kairon.observation.ObservationDraft.ObservationCaptureMode;
import kairon.observation.ObservationDraft.ObservationSource;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalLineParser;
import kairon.observation.journal.JournalLineParser.CompleteJournalRecord;
import kairon.observation.journal.JournalLineParser.ParsedJournalRecord;
import kairon.observation.journal.JournalObservationAdapter;
import kairon.observation.journal.event.exploration.FSSBodySignals;
import kairon.observation.journal.event.exploration.SAAScanComplete;
import kairon.observation.journal.event.exploration.SAASignalsFound;
import kairon.observation.journal.event.exploration.Scan;
import kairon.observation.journal.event.session.Music;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * What the graph calls a completed surface survey, and what it ignores.
 *
 * <p>Normalization and admission are two separate decisions, and this event
 * needed both. A rule without an admission would never run; an admission
 * without a rule would record the survey under a name derived from Frontier's
 * own wire event.</p>
 */
final class BehaviorEventNormalizerTest {

    private static final Instant TIME =
            Instant.parse("2026-07-30T10:00:04Z");

    private final JournalLineParser parser = new JournalLineParser();
    private final JournalObservationAdapter adapter =
            new JournalObservationAdapter(new ObservationSource(
                    "elite-journal",
                    "normalizer-test"
            ));
    private final BehaviorEventNormalizer normalizer =
            new BehaviorEventNormalizer();
    private final EventSignificancePolicy significance =
            new EventSignificancePolicy();

    @Test
    void aCompletedSurveyNormalizesToItsOwnDeclaredType() {
        NormalizedBehaviorEvent normalized = normalizer.normalize(
                parse("""
                        {"timestamp":"2026-07-30T10:00:04Z",
                         "event":"SAAScanComplete",
                         "BodyName":"Schieni GG-A c3-84 4 a",
                         "SystemAddress":23155945939738,"BodyID":20,
                         "ProbesUsed":2,"EfficiencyTarget":2}
                        """),
                TIME
        );

        assertEquals(
                NormalizedEventType.SAA_SCAN_COMPLETE,
                normalized.eventType()
        );
        assertFalse(
                normalized.eventType().value().startsWith("UNKNOWN_"),
                "a fallback name would carry the journal's own event name"
        );
        assertEquals("SAAScanComplete", normalized.originalEventName());
        assertEquals(TIME, normalized.timestamp());
    }

    /** The attributes that tell an efficient survey from a wasteful one. */
    @Test
    void theSurveyCarriesItsBodyAndItsProbeCounts() {
        NormalizedBehaviorEvent normalized = normalizer.normalize(
                parse("""
                        {"timestamp":"2026-07-30T10:00:04Z",
                         "event":"SAAScanComplete",
                         "BodyName":"Schieni GG-A c3-84 4 a",
                         "SystemAddress":23155945939738,"BodyID":20,
                         "ProbesUsed":2,"EfficiencyTarget":2}
                        """),
                TIME
        );

        assertEquals(
                23155945939738L,
                normalized.attributes().get("SystemAddress").longValue()
        );
        assertEquals(20, normalized.attributes().get("BodyID").intValue());
        assertEquals(
                "Schieni GG-A c3-84 4 a",
                normalized.attributes().get("BodyName").textValue()
        );
        assertEquals(2, normalized.attributes().get("ProbesUsed").intValue());
        assertEquals(
                2,
                normalized.attributes().get("EfficiencyTarget").intValue()
        );
    }

    /**
     * Admission, which is the decision that actually changed.
     *
     * <p>Completing a survey is a deliberate multi-step action and is now
     * structural. What follows it is a separate structural result. The detailed
     * scan beside them is still a body fact rather than something the Commander
     * did, and the music track is still noise.</p>
     */
    @Test
    void surveyResultsAreStructuralAndAmbienceIsNot() {
        assertEquals(
                EventSignificance.SIGNIFICANT,
                significance.classify(SAAScanComplete.class)
        );
        assertEquals(
                EventSignificance.SIGNIFICANT,
                significance.classify(SAASignalsFound.class)
        );
        // A detailed body scan is a result the Commander went and got, so the
        // type is admitted; which individual record is a distinct result is
        // decided per occurrence, not here.
        assertEquals(
                EventSignificance.SIGNIFICANT,
                significance.classify(Scan.class)
        );
        assertEquals(
                EventSignificance.SIGNIFICANT,
                significance.classify(FSSBodySignals.class)
        );
        assertEquals(
                EventSignificance.NOISE,
                significance.classify(Music.class)
        );
    }

    private JournalEventObservation parse(String rawJson) {
        ParsedJournalRecord parsed = assertInstanceOf(
                ParsedJournalRecord.class,
                parser.parse(new CompleteJournalRecord(
                        "Journal.normalizer-test.log",
                        0L,
                        rawJson.strip().getBytes(StandardCharsets.UTF_8)
                ))
        );
        return adapter.adapt(
                parsed,
                ObservationCaptureMode.REPLAY,
                parsed.optionalJournalTimestamp().orElseThrow()
        ).payload();
    }
}
