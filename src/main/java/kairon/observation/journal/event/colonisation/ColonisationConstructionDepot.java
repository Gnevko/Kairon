package kairon.observation.journal.event.colonisation;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code ColonisationConstructionDepot} journal event.
 *
 * @see <a href="https://schemas.edomh.nl/ColonisationConstructionDepot.html">
 * Pinned journal-catalogue event contract</a>
 */
public record ColonisationConstructionDepot(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "ColonisationConstructionDepot";

    public ColonisationConstructionDepot {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        List<String> sentences = new ArrayList<>();

        StringBuilder identity = new StringBuilder(
                "The journal reported a periodic status snapshot while the "
                        + "player was docked at a colonisation construction "
                        + "depot"
        );
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("MarketID"))
                .ifPresent(marketId -> identity
                        .append(" with market ID ")
                        .append(marketId));
        identity.append('.');
        sentences.add(identity.toString());

        List<String> stateFacts = new ArrayList<>();
        LlmPresentableJournalEvent.decimal(
                        event.get("ConstructionProgress")
                )
                .ifPresent(progress -> stateFacts.add(
                        "construction progress source value " + progress
                ));
        LlmPresentableJournalEvent.booleanValue(
                        event.get("ConstructionComplete")
                )
                .ifPresent(complete -> stateFacts.add(
                        complete
                                ? "construction marked complete"
                                : "construction not marked complete"
                ));
        LlmPresentableJournalEvent.booleanValue(
                        event.get("ConstructionFailed")
                )
                .ifPresent(failed -> stateFacts.add(
                        failed
                                ? "construction marked failed"
                                : "construction not marked failed"
                ));
        if (!stateFacts.isEmpty()) {
            sentences.add(
                    "The snapshot reports "
                            + LlmPresentableJournalEvent.joinFacts(stateFacts)
                            + "; the catalogue does not define a unit or "
                            + "scale for the progress number."
            );
        }

        resourceStatus(event.get("ResourcesRequired"))
                .ifPresent(sentences::add);
        return new LlmEventPresentation(sentences);
    }

    private static Optional<String> resourceStatus(JsonNode resources) {
        if (resources == null || !resources.isArray()) {
            return Optional.empty();
        }
        List<String> entries = new ArrayList<>();
        for (JsonNode resource : resources) {
            if (!resource.isObject()) {
                continue;
            }
            Optional<String> name = LlmPresentableJournalEvent.displayText(
                    resource,
                    "Name"
            );
            if (name.isEmpty()) {
                continue;
            }
            List<String> facts = new ArrayList<>();
            LlmPresentableJournalEvent
                    .nonNegativeIntegral(resource.get("ProvidedAmount"))
                    .ifPresent(provided -> facts.add(
                            LlmPresentableJournalEvent
                                    .formattedInteger(provided)
                                    + " provided"
                    ));
            LlmPresentableJournalEvent
                    .nonNegativeIntegral(resource.get("RequiredAmount"))
                    .ifPresent(required -> facts.add(
                            LlmPresentableJournalEvent
                                    .formattedInteger(required)
                                    + " required"
                    ));
            LlmPresentableJournalEvent
                    .nonNegativeIntegral(resource.get("Payment"))
                    .ifPresent(payment -> facts.add(
                            "listed payment source value "
                                    + LlmPresentableJournalEvent
                                            .formattedInteger(payment)
                    ));
            if (!facts.isEmpty()) {
                entries.add(
                        LlmPresentableJournalEvent.quoted(name.get())
                                + ": "
                                + LlmPresentableJournalEvent.joinFacts(facts)
                );
            }
        }
        if (entries.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
                "Required-resource status: "
                        + String.join("; ", entries)
                        + "."
        );
    }
}
