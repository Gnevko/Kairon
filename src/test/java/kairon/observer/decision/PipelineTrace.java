package kairon.observer.decision;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.behavior.model.EpisodeEntrySource;
import kairon.behavior.model.EventOccurrenceId;
import kairon.behavior.model.EventOccurrenceSource;
import kairon.behavior.model.SystemEpisodeId;
import kairon.behavior.normalize.NormalizedEventType;
import kairon.observation.ObservationDraft.ObservationCaptureMode;
import kairon.semantics.EffectRetention;
import kairon.semantics.SemanticSourceRole;
import kairon.semantics.SemanticStateChange;
import kairon.state.CurrentGameStateSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One observation sequence, as every layer saw it.
 *
 * <p>Immutable and read-only. It exists because a contract that spans layers
 * cannot be asserted one layer at a time: an occurrence without a provider call,
 * or a provider call without an occurrence, is exactly the shape of every defect
 * this project has had to fix twice. Nothing here is recomputed — every field is
 * copied from what the production components actually produced.</p>
 *
 * <p>No mutable production service is exposed. A caller that wants to know
 * something not recorded here must extend the recording, not reach past it.</p>
 */
record PipelineTrace(
        boolean graphEnabled,
        List<ObservationRecord> observations,
        List<EpisodeView> episodes,
        List<EdgeView> edges,
        Optional<CursorView> cursor,
        List<TurnView> turns,
        Optional<CurrentGameStateSnapshot> finalState
) {

    PipelineTrace {
        observations = List.copyOf(
                Objects.requireNonNull(observations, "observations")
        );
        episodes = List.copyOf(Objects.requireNonNull(episodes, "episodes"));
        edges = List.copyOf(Objects.requireNonNull(edges, "edges"));
        cursor = Objects.requireNonNull(cursor, "cursor");
        turns = List.copyOf(Objects.requireNonNull(turns, "turns"));
        finalState = Objects.requireNonNull(finalState, "finalState");
    }

    /** How many times the provider was actually asked anything. */
    int providerCalls() {
        return turns.size();
    }

    /** Every model-facing event kind the provider was shown, in order. */
    List<String> modelFacingKinds() {
        List<String> kinds = new ArrayList<>();
        for (TurnView turn : turns) {
            kinds.addAll(turn.eventKinds());
        }
        return List.copyOf(kinds);
    }

    /** Every occurrence of every episode, oldest episode first. */
    List<OccurrenceView> occurrences() {
        List<OccurrenceView> all = new ArrayList<>();
        for (EpisodeView episode : episodes) {
            all.addAll(episode.occurrences());
        }
        return List.copyOf(all);
    }

    /** The occurrence this observation minted, or empty when it minted none. */
    Optional<OccurrenceView> occurrenceOf(long busSequence) {
        return occurrences().stream()
                .filter(occurrence -> occurrence.sourceBusSequence()
                        .filter(sequence -> sequence == busSequence)
                        .isPresent())
                .findFirst();
    }

    /** The recorded observation with this bus sequence. */
    ObservationRecord observation(long busSequence) {
        return observations.stream()
                .filter(record -> record.busSequence() == busSequence)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no observation with busSequence " + busSequence
                                + "\n" + describe()
                ));
    }

    /** The last recorded observation whose raw journal type is this one. */
    ObservationRecord lastObservationOfType(String rawObservationType) {
        for (int index = observations.size() - 1; index >= 0; index--) {
            ObservationRecord record = observations.get(index);
            if (record.rawObservationType().equals(rawObservationType)) {
                return record;
            }
        }
        throw new AssertionError(
                "no observation of type " + rawObservationType
                        + "\n" + describe()
        );
    }

    /** The turn that carried this trigger, or empty when none did. */
    Optional<TurnView> turnCarrying(long triggerBusSequence) {
        return turns.stream()
                .filter(turn ->
                        turn.triggerBusSequences().contains(triggerBusSequence))
                .findFirst();
    }

    /**
     * The whole trace, laid out for a failure message.
     *
     * <p>Every assertion in this package appends this. A cross-layer failure is
     * unreadable without seeing what the other layers did.</p>
     */
    String describe() {
        StringBuilder out = new StringBuilder("PipelineTrace(graph=")
                .append(graphEnabled ? "enabled" : "disabled")
                .append(")\n  observations:\n");
        for (ObservationRecord record : observations) {
            out.append("    #").append(record.busSequence())
                    .append(' ').append(record.rawObservationType())
                    .append(" capture=").append(record.captureMode())
                    .append(" role=").append(record.sourceRole())
                    .append(" retention=").append(record.effectRetention())
                    .append(" changes=").append(record.stateChanges().size())
                    .append(record.occurrenceId()
                            .map(id -> " occurrence=yes")
                            .orElse(""))
                    .append('\n');
        }
        out.append("  episodes:\n");
        for (EpisodeView episode : episodes) {
            out.append("    ").append(episode.entrySource())
                    .append(" address=").append(episode.systemAddress())
                    .append(" active=").append(episode.active())
                    .append(" occurrences=")
                    .append(episode.occurrences().stream()
                            .map(occurrence ->
                                    occurrence.eventType().toString())
                            .toList())
                    .append('\n');
        }
        out.append("  edges: ").append(edges.stream()
                        .map(edge -> edge.from() + "->" + edge.to()
                                + "(" + edge.rawCount() + ")")
                        .toList())
                .append('\n');
        out.append("  cursor: ")
                .append(cursor.map(view -> view.eventType().toString())
                        .orElse("<none>"))
                .append('\n');
        out.append("  turns: ").append(turns.size()).append('\n');
        for (TurnView turn : turns) {
            out.append("    turn ").append(turn.turnSequence())
                    .append(" triggers=").append(turn.triggerBusSequences())
                    .append(" ids=").append(turn.eventIds())
                    .append(" kinds=").append(turn.eventKinds())
                    .append(" said=").append(turn.eventDescriptions())
                    .append("\n      ").append(turn.userMessage())
                    .append('\n');
        }
        return out.toString();
    }

    /**
     * One observation, after every layer inside the projection boundary.
     *
     * @param stateChanges the exact semantic delta this observation produced,
     *                     copied from its immutable envelope
     * @param occurrenceId the occurrence the graph minted for this observation,
     *                     or empty — derived by comparing the id the graph would
     *                     mint with the ids it actually holds, never by adjacency
     */
    record ObservationRecord(
            long busSequence,
            String observationId,
            ObservationCaptureMode captureMode,
            String rawObservationType,
            SemanticSourceRole sourceRole,
            EffectRetention effectRetention,
            CurrentGameStateSnapshot currentState,
            List<SemanticStateChange> stateChanges,
            int structuredFactCount,
            Optional<EventOccurrenceId> occurrenceId
    ) {

        ObservationRecord {
            observationId = Objects.requireNonNull(
                    observationId,
                    "observationId"
            );
            captureMode = Objects.requireNonNull(captureMode, "captureMode");
            rawObservationType = Objects.requireNonNull(
                    rawObservationType,
                    "rawObservationType"
            );
            sourceRole = Objects.requireNonNull(sourceRole, "sourceRole");
            effectRetention = Objects.requireNonNull(
                    effectRetention,
                    "effectRetention"
            );
            currentState = Objects.requireNonNull(
                    currentState,
                    "currentState"
            );
            stateChanges = List.copyOf(Objects.requireNonNull(
                    stateChanges,
                    "stateChanges"
            ));
            occurrenceId = Objects.requireNonNull(
                    occurrenceId,
                    "occurrenceId"
            );
        }
    }

    record EpisodeView(
            SystemEpisodeId id,
            EpisodeEntrySource entrySource,
            long systemAddress,
            boolean active,
            List<OccurrenceView> occurrences,
            List<TransitionView> transitions
    ) {

        EpisodeView {
            occurrences = List.copyOf(occurrences);
            transitions = List.copyOf(transitions);
        }

        List<NormalizedEventType> occurrenceTypes() {
            return occurrences.stream()
                    .map(OccurrenceView::eventType)
                    .toList();
        }
    }

    record OccurrenceView(
            EventOccurrenceId id,
            NormalizedEventType eventType,
            long episodeSequence,
            EventOccurrenceSource source,
            Optional<Long> sourceBusSequence
    ) {
    }

    record TransitionView(
            NormalizedEventType from,
            NormalizedEventType to
    ) {
    }

    record EdgeView(
            NormalizedEventType from,
            NormalizedEventType to,
            long rawCount
    ) {
    }

    record CursorView(
            NormalizedEventType eventType,
            EventOccurrenceId occurrenceId
    ) {
    }

    /**
     * One provider call, and everything the request said.
     *
     * @param document          the parsed request, for semantic comparison
     * @param userMessage       the exact bytes the provider received
     * @param request           the same request as the object it was before
     *                          serialization, proved byte-identical to
     *                          {@code userMessage}. For contracts about what
     *                          the document deliberately does not carry — a
     *                          change's {@code eventId} above all
     * @param eventDescriptions what the provider was actually told each event
     *                          is, read from the request itself
     * @param eventKinds        Kairon's own name for each of those events,
     *                          resolved from the observed payload rather than
     *                          from the request — the request no longer carries
     *                          one, and reversing a name out of a description
     *                          would make every kind assertion a tautology
     * @param triggerBusSequences the observations the coordinator bound to this
     *                          turn, read from the production listener port
     */
    record TurnView(
            long turnSequence,
            String userMessage,
            JsonNode document,
            LlmDecisionRequest request,
            List<Integer> eventIds,
            List<String> eventDescriptions,
            List<String> eventKinds,
            List<Long> triggerBusSequences
    ) {

        TurnView {
            userMessage = Objects.requireNonNull(userMessage, "userMessage");
            document = Objects.requireNonNull(document, "document");
            request = Objects.requireNonNull(request, "request");
            eventIds = List.copyOf(eventIds);
            eventDescriptions = List.copyOf(eventDescriptions);
            eventKinds = List.copyOf(eventKinds);
            triggerBusSequences = List.copyOf(triggerBusSequences);
        }

        JsonNode events() {
            return document.path("events");
        }

        JsonNode changes() {
            return document.path("changes");
        }

        JsonNode context() {
            return document.path("context");
        }

        List<String> recent() {
            List<String> recent = new ArrayList<>();
            document.path("trajectory").path("recent")
                    .forEach(name -> recent.add(name.textValue()));
            return List.copyOf(recent);
        }

        List<String> likelyNext() {
            List<String> predictions = new ArrayList<>();
            document.path("trajectory").path("likelyNext")
                    .forEach(prediction ->
                            predictions.add(prediction.path("event")
                                    .textValue()));
            return List.copyOf(predictions);
        }

        boolean hasTrajectory() {
            return document.has("trajectory");
        }

        boolean contextIncomplete() {
            return document.path("contextIncomplete").asBoolean(false);
        }
    }
}
