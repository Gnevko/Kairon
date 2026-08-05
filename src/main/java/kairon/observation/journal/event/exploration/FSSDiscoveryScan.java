package kairon.observation.journal.event.exploration;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code FSSDiscoveryScan} journal event.
 *
 * <p>Not a model-eligible trigger: the honk opens no turn of its own. It
 * describes itself anyway because the behaviour graph records it as
 * {@code FSS_DISCOVERY_SCAN}, and a graph vertex can reach the model as a
 * remembered predecessor. A vertex the model can be shown has to be able to say
 * what it is, and saying it here rather than in the trajectory table is what
 * lets a test compare the two.</p>
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 6.1</a>
 */
public record FSSDiscoveryScan(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "FSSDiscoveryScan";

    public FSSDiscoveryScan {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "A full spectrum system scan swept the star system.";
    }

    /**
     * What the sweep reported, from the record's own fields.
     *
     * <p>{@code Progress} is the documented fraction of the system's bodies now
     * discovered, and {@code BodyCount} and {@code NonBodyCount} are what the
     * system holds in total. Nothing is derived from them: a remaining count is
     * arithmetic the record does not do, and the record is what is reported.</p>
     */
    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        StringBuilder sentence =
                new StringBuilder("The ship ran a full spectrum system scan");
        LlmPresentableJournalEvent.displayText(event, "SystemName")
                .ifPresent(system -> sentence
                        .append(" of the system ")
                        .append(LlmPresentableJournalEvent.quoted(system)));
        sentence.append('.');

        List<String> sentences = new ArrayList<>();
        sentences.add(sentence.toString());
        reported(event).ifPresent(sentences::add);
        return new LlmEventPresentation(sentences);
    }

    private static java.util.Optional<String> reported(JsonNode event) {
        List<String> facts = new ArrayList<>();
        LlmPresentableJournalEvent.decimal(event.get("Progress"))
                .ifPresent(progress -> facts.add(
                        "a discovery progress of " + progress
                                + " on the documented 0-to-1 scale"
                ));
        LlmPresentableJournalEvent.nonNegativeIntegral(event.get("BodyCount"))
                .ifPresent(count -> facts.add(
                        "a total of " + count + " bodies in the system"
                ));
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("NonBodyCount"))
                .ifPresent(count -> facts.add(
                        "a total of " + count + " non-body signals"
                ));
        if (facts.isEmpty()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(
                "The scan reports "
                        + LlmPresentableJournalEvent.joinFacts(facts)
                        + "."
        );
    }
}
