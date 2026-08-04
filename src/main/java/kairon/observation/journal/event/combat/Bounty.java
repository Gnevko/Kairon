package kairon.observation.journal.event.combat;

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
 * {@code Bounty} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 5.1</a>
 */
public record Bounty(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "Bounty";

    public Bounty {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "The Commander was awarded a bounty for a kill.";
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        List<String> sentences = new ArrayList<>();

        StringBuilder award = new StringBuilder(
                "The player was awarded a bounty for a kill"
        );
        LlmPresentableJournalEvent.displayText(event, "Target")
                .ifPresent(target -> award
                        .append(" of target type ")
                        .append(LlmPresentableJournalEvent.quoted(target)));
        LlmPresentableJournalEvent.textual(event.get("VictimFaction"))
                .ifPresent(faction -> award
                        .append(" affiliated with ")
                        .append(LlmPresentableJournalEvent.quoted(faction)));
        award.append('.');
        sentences.add(award.toString());

        Optional<Long> total =
                LlmPresentableJournalEvent.nonNegativeIntegral(
                        event.get("TotalReward")
                );
        if (total.isEmpty()) {
            total = LlmPresentableJournalEvent.nonNegativeIntegral(
                    event.get("Reward")
            );
        }
        total.ifPresent(reward -> sentences.add(
                "The reported total bounty reward is "
                        + LlmPresentableJournalEvent.formattedInteger(reward)
                        + " credits."
        ));

        rewardSources(event).ifPresent(sentences::add);
        LlmPresentableJournalEvent
                .nonNegativeIntegral(event.get("SharedWithOthers"))
                .ifPresent(others -> sentences.add(
                        "Kill credit was shared with "
                                + LlmPresentableJournalEvent
                                        .formattedInteger(others)
                                + " other player"
                                + (others == 1 ? "." : "s.")
                ));
        return new LlmEventPresentation(sentences);
    }

    private static Optional<String> rewardSources(JsonNode event) {
        List<String> sources = new ArrayList<>();
        JsonNode rewards = event.get("Rewards");
        if (rewards != null && rewards.isArray()) {
            for (JsonNode reward : rewards) {
                addRewardSource(reward, sources);
            }
        }
        if (sources.isEmpty()) {
            LlmPresentableJournalEvent.textual(event.get("Faction"))
                    .ifPresent(faction -> sources.add(
                            LlmPresentableJournalEvent.quoted(faction)
                    ));
        }
        if (sources.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
                "The listed bounty issuer"
                        + (sources.size() == 1 ? " is " : "s are ")
                        + LlmPresentableJournalEvent.joinFacts(sources)
                        + "."
        );
    }

    private static void addRewardSource(
            JsonNode reward,
            List<String> sources
    ) {
        if (!reward.isObject()) {
            return;
        }
        Optional<String> faction = LlmPresentableJournalEvent.textual(
                reward.get("Faction")
        );
        Optional<Long> amount =
                LlmPresentableJournalEvent.nonNegativeIntegral(
                        reward.get("Reward")
                );
        if (faction.isEmpty()) {
            return;
        }
        StringBuilder source = new StringBuilder(
                LlmPresentableJournalEvent.quoted(faction.get())
        );
        amount.ifPresent(value -> source
                .append(" paying ")
                .append(LlmPresentableJournalEvent.formattedInteger(value))
                .append(" credits"));
        sources.add(source.toString());
    }
}
