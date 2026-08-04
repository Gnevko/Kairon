package kairon.observation.journal.event.ship;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;

import java.util.ArrayList;
import java.util.List;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code RebootRepair} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 13.35</a>
 */
public record RebootRepair(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "RebootRepair";

    public RebootRepair {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode modules = raw.parsedJsonObject().get("Modules");
        List<String> repaired = new ArrayList<>();
        if (modules != null && modules.isArray()) {
            for (JsonNode module : modules) {
                LlmPresentableJournalEvent.textual(module).ifPresent(value ->
                        repaired.add(LlmPresentableJournalEvent.quoted(value))
                );
            }
        }
        String sentence = repaired.isEmpty()
                ? "The player used the ship's reboot-and-repair function; "
                        + "no repaired module names were reported."
                : "The player used the ship's reboot-and-repair function, "
                        + "which repaired modules "
                        + String.join("; ", repaired)
                        + ".";
        return new LlmEventPresentation(List.of(sentence));
    }
}
