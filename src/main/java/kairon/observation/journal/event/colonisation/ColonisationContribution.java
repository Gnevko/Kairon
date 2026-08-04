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
 * {@code ColonisationContribution} journal event.
 *
 * @see <a href="https://schemas.edomh.nl/ColonisationContribution.html">
 * Pinned journal-catalogue event contract</a>
 */
public record ColonisationContribution(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "ColonisationContribution";

    public ColonisationContribution {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "Materials were contributed to a colonisation effort.";
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        StringBuilder identity = new StringBuilder(
                "The player contributed materials to a colonisation effort"
        );
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("MarketID"))
                .ifPresent(marketId -> identity
                        .append(" at construction market ID ")
                        .append(marketId));
        identity.append('.');

        List<String> sentences = new ArrayList<>();
        sentences.add(identity.toString());
        contributionList(event.get("Contributions"))
                .ifPresent(sentences::add);
        return new LlmEventPresentation(sentences);
    }

    private static Optional<String> contributionList(
            JsonNode contributions
    ) {
        if (contributions == null || !contributions.isArray()) {
            return Optional.empty();
        }
        List<String> entries = new ArrayList<>();
        for (JsonNode contribution : contributions) {
            if (!contribution.isObject()) {
                continue;
            }
            Optional<String> name = LlmPresentableJournalEvent.displayText(
                    contribution,
                    "Name"
            );
            Optional<Long> amount =
                    LlmPresentableJournalEvent.nonNegativeIntegral(
                            contribution.get("Amount")
                    );
            if (name.isPresent() && amount.isPresent()) {
                entries.add(
                        LlmPresentableJournalEvent.quoted(name.get())
                                + ": source amount "
                                + LlmPresentableJournalEvent.formattedInteger(
                                        amount.get()
                                )
                );
            }
        }
        if (entries.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
                "The contribution record lists "
                        + String.join("; ", entries)
                        + "; the catalogue does not define a unit for these "
                        + "amounts."
        );
    }
}
