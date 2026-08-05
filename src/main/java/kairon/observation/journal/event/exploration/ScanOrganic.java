package kairon.observation.journal.event.exploration;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;
import kairon.observation.journal.UnrecognisedEventVariant;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static kairon.observation.journal.LlmPresentableJournalEvent.displayText;
import static kairon.observation.journal.LlmPresentableJournalEvent.quoted;
import static kairon.observation.journal.LlmPresentableJournalEvent.textual;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code ScanOrganic} journal event.
 *
 * <p>One wire event, four domain events. {@code ScanType} does not say
 * <em>how</em> the tool was used — it says <em>which step of a sampling
 * sequence this is</em>, and the first step, a middle step and the final step
 * are three different things to have happened. So they are three classes, and
 * the dispatch happens once, here, at parse time.</p>
 *
 * <p>Before the split the discriminator was re-read downstream: the behaviour
 * normalizer switched on {@code ScanType} to pick a structural type, and
 * nothing else could tell the steps apart at all. A record that carries more
 * than one domain event forces every consumer to rediscover that fact, and they
 * rediscover it at different granularities.</p>
 *
 * <p>The sealed interface stays the record as far as everything that asks
 * <em>what kind of journal event is this</em> is concerned — source role,
 * structural significance, the semantic adapter, the domain kind. Those were
 * decided once when the event was researched, and one research answer does not
 * become four because the parser learned to dispatch.</p>
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 12.22</a>
 */
public sealed interface ScanOrganic extends LlmPresentableJournalEvent {

    String EVENT_TYPE = "ScanOrganic";

    /**
     * What all four steps report, until each is given its own sentence.
     *
     * <p>Deliberately one string. Splitting the class and changing what the
     * model reads are two changes, and only the first is structural: this one
     * is behaviour-preserving, and the second has to be argued per step against
     * the {@code stage} and {@code complete} fields the event already carries.
     * </p>
     */
    String SHARED_DESCRIPTION =
            "The organic sampling tool was used on an organic discovery.";

    /**
     * The domain event this record actually is.
     *
     * <p>The single dispatch. An unrecognised {@code ScanType} is a real case —
     * Frontier adds values — and it becomes {@link Unrecognised} rather than
     * being guessed into one of the researched steps.</p>
     */
    static ScanOrganic of(RawJournalData raw) {
        JournalEventObservation.requireEvent(raw, EVENT_TYPE);
        return switch (textual(raw.parsedJsonObject().get("ScanType"))
                .orElse("")) {
            case "Log" -> new Logged(raw);
            case "Sample" -> new Sampled(raw);
            case "Analyse" -> new Analysed(raw);
            default -> new Unrecognised(raw);
        };
    }

    /** The first scan of a sampling sequence. */
    record Logged(RawJournalData raw) implements ScanOrganic {

        public Logged {
            raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
        }

        @Override
        public String modelFacingDescription() {
            return SHARED_DESCRIPTION;
        }

        @Override
        public LlmEventPresentation llmPresentation() {
            return sentence(
                    "The Organic Sampling Tool logged the first scan in a "
                            + "sampling sequence for "
                            + organicSubject(raw.parsedJsonObject())
                            + "; the sequence is not yet complete."
            );
        }
    }

    /** A further scan of a sampling sequence already under way. */
    record Sampled(RawJournalData raw) implements ScanOrganic {

        public Sampled {
            raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
        }

        @Override
        public String modelFacingDescription() {
            return SHARED_DESCRIPTION;
        }

        @Override
        public LlmEventPresentation llmPresentation() {
            return sentence(
                    "The Organic Sampling Tool recorded a subsequent sample "
                            + "for "
                            + organicSubject(raw.parsedJsonObject())
                            + "; the sequence is not yet complete."
            );
        }
    }

    /** The final scan, which completes the sampling sequence. */
    record Analysed(RawJournalData raw) implements ScanOrganic {

        public Analysed {
            raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
        }

        @Override
        public String modelFacingDescription() {
            return SHARED_DESCRIPTION;
        }

        @Override
        public LlmEventPresentation llmPresentation() {
            return sentence(
                    "The Organic Sampling Tool recorded the final scan and "
                            + "completed the sampling sequence for "
                            + organicSubject(raw.parsedJsonObject())
                            + "."
            );
        }
    }

    /**
     * A {@code ScanType} this build does not recognise.
     *
     * <p>Reported as the tool having been used, without claiming which step it
     * was — the record says a step happened and Kairon does not know which.</p>
     */
    record Unrecognised(RawJournalData raw)
            implements ScanOrganic, UnrecognisedEventVariant {

        public Unrecognised {
            raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
        }

        @Override
        public String modelFacingDescription() {
            return SHARED_DESCRIPTION;
        }

        @Override
        public LlmEventPresentation llmPresentation() {
            JsonNode event = raw.parsedJsonObject();
            String subject = organicSubject(event);
            String stage = textual(event.get("ScanType")).orElse("");
            return sentence(stage.isEmpty()
                    ? "The journal recorded use of the Organic Sampling Tool "
                            + "for " + subject + "."
                    : "The journal recorded Organic Sampling Tool stage "
                            + quoted(stage) + " for " + subject + ".");
        }
    }

    // ----------------------------------------------------------- presentation

    private static LlmEventPresentation sentence(String text) {
        return new LlmEventPresentation(List.of(text));
    }

    private static String organicSubject(JsonNode event) {
        List<String> labels = new ArrayList<>();
        displayText(event, "Genus")
                .ifPresent(value -> labels.add(
                        "genus " + quoted(value)
                ));
        displayText(event, "Species")
                .ifPresent(value -> labels.add(
                        "species " + quoted(value)
                ));
        displayText(event, "Variant")
                .ifPresent(value -> labels.add(
                        "variant " + quoted(value)
                ));

        StringBuilder subject = new StringBuilder(
                labels.isEmpty()
                        ? "an organic discovery"
                        : String.join(", ", labels)
        );
        integral(event.get("Body"))
                .ifPresent(bodyId -> subject
                        .append(" on body ID ")
                        .append(bodyId));
        return subject.toString();
    }

    private static Optional<Long> integral(JsonNode value) {
        return value != null
                && value.isIntegralNumber()
                && value.canConvertToLong()
                && value.longValue() >= 0
                ? Optional.of(value.longValue())
                : Optional.empty();
    }
}
