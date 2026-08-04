package kairon.semantics;

import kairon.observation.ObservationDraft;
import kairon.observation.ObservationDraft.ObservationCaptureMode;
import kairon.observation.ObservationDraft.ObservationSource;
import kairon.observation.PublishedObservation;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalLineParser;
import kairon.observation.journal.JournalLineParser.CompleteJournalRecord;
import kairon.observation.journal.JournalLineParser.ParsedJournalRecord;
import kairon.observation.journal.JournalObservationAdapter;
import kairon.projection.SemanticEnvelopeFactory;
import kairon.state.CurrentGameStateProjector;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Builds real published journal observations from raw JSON.
 *
 * <p>Uses the production parser and adapter so semantic tests exercise the
 * same payloads the runtime sees.</p>
 */
final class SemanticJournalFixture {

    private static final ObservationSource SOURCE =
            new ObservationSource("elite-journal", "semantics-test");

    private final JournalLineParser parser = new JournalLineParser();
    private final JournalObservationAdapter adapter =
            new JournalObservationAdapter(SOURCE);

    private long sourceOffset;
    private long busSequence;

    PublishedObservation<JournalEventObservation> publish(String rawJson) {
        byte[] bytes = rawJson.strip().getBytes(StandardCharsets.UTF_8);
        ParsedJournalRecord parsed = assertInstanceOf(
                ParsedJournalRecord.class,
                parser.parse(new CompleteJournalRecord(
                        "Journal.semantics-test.log",
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

    /** The structured facts a single raw event produces. */
    SemanticObservationEnvelope envelopeOf(String rawJson) {
        PublishedObservation<JournalEventObservation> observation =
                publish(rawJson);
        return SemanticEnvelopeFactory.production().create(
                observation,
                new CurrentGameStateProjector()
                        .applyAndCapture(observation)
                        .applied()
        );
    }

    SemanticFact singleFactOf(String rawJson) {
        SemanticObservationEnvelope envelope = envelopeOf(rawJson);
        if (envelope.structuredFacts().size() != 1) {
            throw new AssertionError(
                    "expected exactly one structured fact but got "
                            + envelope.structuredFacts().size()
            );
        }
        return envelope.structuredFacts().getFirst();
    }
}
