package kairon.observation.journal.event.trade;

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
 * {@code RedeemVoucher} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 8.35</a>
 */
public record RedeemVoucher(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "RedeemVoucher";

    public RedeemVoucher {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        String voucherType = LlmPresentableJournalEvent
                .textual(event.get("Type"))
                .map(RedeemVoucher::voucherDescription)
                .orElse("an unspecified voucher type");
        List<String> facts = new ArrayList<>();
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("Amount"))
                .ifPresent(value -> facts.add(
                        "net payment "
                                + LlmPresentableJournalEvent
                                .formattedInteger(value)
                                + " credits"
                ));
        LlmPresentableJournalEvent.textual(event.get("Faction"))
                .ifPresent(value -> facts.add(
                        "faction "
                                + LlmPresentableJournalEvent.quoted(value)
                ));
        LlmPresentableJournalEvent.decimal(event.get("BrokerPercentage"))
                .ifPresent(value -> facts.add(
                        "broker percentage " + value + "%"
                ));
        addFactionPayments(event.get("Factions"), facts);
        String sentence = "The player redeemed "
                + voucherType
                + " payment"
                + (facts.isEmpty()
                ? "."
                : ", with "
                        + LlmPresentableJournalEvent.joinFacts(facts)
                        + ".");
        return new LlmEventPresentation(List.of(sentence));
    }

    private static String voucherDescription(String sourceType) {
        return switch (sourceType.toLowerCase(Locale.ROOT)) {
            case "combatbond" -> "combat-bond";
            case "bounty" -> "bounty";
            case "trade" -> "trade";
            case "settlement" -> "settlement";
            case "scannable" -> "scannable";
            default -> "voucher type "
                    + LlmPresentableJournalEvent.quoted(sourceType);
        };
    }

    private static void addFactionPayments(
            JsonNode factionsNode,
            List<String> facts
    ) {
        if (factionsNode == null || !factionsNode.isArray()) {
            return;
        }
        for (JsonNode factionPayment : factionsNode) {
            if (!factionPayment.isObject()) {
                continue;
            }
            String faction = LlmPresentableJournalEvent
                    .textual(factionPayment.get("Faction"))
                    .map(LlmPresentableJournalEvent::quoted)
                    .orElse("an unnamed faction");
            LlmPresentableJournalEvent
                    .nonNegativeIntegral(factionPayment.get("Amount"))
                    .ifPresent(value -> facts.add(
                            "faction "
                                    + faction
                                    + " contributed "
                                    + LlmPresentableJournalEvent
                                    .formattedInteger(value)
                                    + " credits"
                    ));
        }
    }
}
