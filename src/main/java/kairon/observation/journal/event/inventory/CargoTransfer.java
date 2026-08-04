package kairon.observation.journal.event.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code CargoTransfer} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 13.52</a>
 */
public record CargoTransfer(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "CargoTransfer";

    public CargoTransfer {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "Cargo was transferred between the ship and a fleet carrier or SRV.";
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode transfers = raw.parsedJsonObject().get("Transfers");
        List<String> entries = new ArrayList<>();
        if (transfers != null && transfers.isArray()) {
            for (JsonNode transfer : transfers) {
                if (!transfer.isObject()) {
                    continue;
                }
                var type = LlmPresentableJournalEvent.displayText(
                        transfer,
                        "Type"
                );
                var count = LlmPresentableJournalEvent.nonNegativeIntegral(
                        transfer.get("Count")
                );
                var direction = LlmPresentableJournalEvent.textual(
                        transfer.get("Direction")
                );
                if (type.isEmpty() || count.isEmpty()) {
                    continue;
                }
                StringBuilder entry = new StringBuilder()
                        .append(LlmPresentableJournalEvent
                                .formattedInteger(count.get()))
                        .append(" unit")
                        .append(count.get() == 1 ? "" : "s")
                        .append(" of ")
                        .append(LlmPresentableJournalEvent.quoted(type.get()));
                direction.ifPresent(value -> entry
                        .append(' ')
                        .append(directionDescription(value)));
                entries.add(entry.toString());
            }
        }
        if (entries.isEmpty()) {
            return new LlmEventPresentation(List.of(
                    "The journal recorded a cargo transfer between the ship "
                            + "and a fleet carrier or SRV, but supplied no "
                            + "usable transfer entries."
            ));
        }
        return new LlmEventPresentation(List.of(
                "The player transferred cargo: "
                        + String.join("; ", entries)
                        + "."
        ));
    }

    private static String directionDescription(String direction) {
        return switch (direction.toLowerCase(Locale.ROOT)) {
            case "tocarrier" -> "from the ship to a fleet carrier";
            case "toship" -> "to the ship";
            case "tosrv" -> "from the ship to an SRV";
            default -> "in source direction "
                    + LlmPresentableJournalEvent.quoted(direction);
        };
    }
}
