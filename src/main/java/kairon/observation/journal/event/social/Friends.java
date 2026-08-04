package kairon.observation.journal.event.social;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code Friends} journal event.
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 13.20</a>
 */
public record Friends(RawJournalData raw)
        implements LlmPresentableJournalEvent {

    public static final String EVENT_TYPE = "Friends";

    public Friends {
        raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
    }

    @Override
    public String modelFacingDescription() {
        return "Information about a friend's status was received.";
    }

    @Override
    public LlmEventPresentation llmPresentation() {
        JsonNode event = raw.parsedJsonObject();
        String commander = LlmPresentableJournalEvent
                .textual(event.get("Name"))
                .map(value -> "Commander "
                        + LlmPresentableJournalEvent.quoted(value))
                .orElse("an unnamed commander");
        Optional<String> status =
                LlmPresentableJournalEvent.textual(event.get("Status"));

        List<String> sentences = new ArrayList<>();
        sentences.add(status
                .map(value -> describeStatus(value, commander))
                .orElse(
                        "The journal reported a change in the friend status "
                                + "associated with "
                                + commander
                                + "."
                ));
        status.filter(value -> value.equalsIgnoreCase("Online"))
                .ifPresent(ignored -> sentences.add(
                        "An online status can also be emitted at game startup "
                                + "for a friend who was already online, so "
                                + "this event alone does not prove a new "
                                + "login."
                ));
        return new LlmEventPresentation(sentences);
    }

    private static String describeStatus(
            String status,
            String commander
    ) {
        return switch (status.toLowerCase(Locale.ROOT)) {
            case "requested" ->
                    "The journal reports a pending friend request involving "
                            + commander
                            + "; it does not identify which commander "
                            + "initiated the request.";
            case "declined" ->
                    "A friend request involving "
                            + commander
                            + " was declined; the journal does not identify "
                            + "which commander declined it.";
            case "added" ->
                    commander
                            + " was added to the player's friends list.";
            case "lost" ->
                    "The player's recorded friend relationship with "
                            + commander
                            + " was lost.";
            case "offline" ->
                    commander
                            + " is currently offline.";
            case "online" ->
                    commander
                            + " is currently online.";
            default ->
                    "The journal reported friend status "
                            + LlmPresentableJournalEvent.quoted(status)
                            + " for "
                            + commander
                            + ".";
        };
    }
}
