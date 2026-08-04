package kairon.behavior.model;

import kairon.behavior.normalize.NormalizedEventType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Exact occurrence path for one visit to one star system by one ship graph.
 *
 * <p>Two shapes, and the difference is what opened the visit.</p>
 *
 * <p>An <strong>entered</strong> episode was opened by something that actually
 * happened — a hyperspace jump, a ship switch. It has a root occurrence of type
 * {@link NormalizedEventType#SYSTEM_ENTRY}, that occurrence is first in the
 * timeline, and {@code startedAt} is its timestamp.</p>
 *
 * <p>A <strong>restored</strong> episode was opened by a {@code Location}
 * record stating where the Commander already is. Nothing happened, so nothing
 * is recorded: {@code rootOccurrenceId} is null and the timeline is empty until
 * the first real structural event arrives. That event becomes the first
 * occurrence and takes no incoming transition, because there is no predecessor
 * for it to have followed — the Commander was simply already here.</p>
 */
public record SystemEpisode(
        String schemaVersion,
        SystemEpisodeId id,
        GraphId graphId,
        long systemAddress,
        String systemName,
        Instant startedAt,
        Instant completedAt,
        EpisodeEntrySource entrySource,
        EpisodeCompletionReason completionReason,
        EventOccurrenceId rootOccurrenceId,
        List<EventOccurrence> timeline,
        Map<NormalizedEventType, List<EventOccurrenceId>> occurrencesByEventType,
        List<OccurrenceTransition> occurrenceTransitions
) {

    public static final String SCHEMA_VERSION = "kairon.system-episode/v3";

    public SystemEpisode {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported SystemEpisode schemaVersion"
            );
        }
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(graphId, "graphId");
        systemName = requireNonBlank(systemName, "systemName");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(entrySource, "entrySource");
        if ((completedAt == null) != (completionReason == null)) {
            throw new IllegalArgumentException(
                    "completion time and reason must both be present or absent"
            );
        }

        timeline = sortedTimeline(timeline);
        boolean restored = rootOccurrenceId == null;
        if (restored
                && entrySource != EpisodeEntrySource.LOCATION_RESTORE) {
            throw new IllegalArgumentException(
                    "only a restored episode begins without a root occurrence"
            );
        }
        if (!restored && timeline.isEmpty()) {
            throw new IllegalArgumentException(
                    "episode timeline must not be empty"
            );
        }
        for (EventOccurrence occurrence : timeline) {
            if (!occurrence.graphId().equals(graphId)
                    || !occurrence.episodeId().equals(id)) {
                throw new IllegalArgumentException(
                        "timeline occurrence belongs to another graph or episode"
                );
            }
        }
        for (int index = 0; index < timeline.size(); index++) {
            if (timeline.get(index).episodeSequence() != index) {
                throw new IllegalArgumentException(
                        "episodeSequence must be contiguous from zero"
                );
            }
        }
        if (restored) {
            if (!timeline.isEmpty()
                    && timeline.getFirst().timestamp().isBefore(startedAt)) {
                throw new IllegalArgumentException(
                        "a restored episode cannot hold an earlier occurrence"
                );
            }
        } else {
            EventOccurrence root = timeline.stream()
                    .filter(occurrence ->
                            occurrence.id().equals(rootOccurrenceId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "root occurrence is missing from timeline"
                    ));
            if (!root.graphId().equals(graphId)
                    || !root.episodeId().equals(id)
                    || !root.eventType().equals(
                            NormalizedEventType.SYSTEM_ENTRY
                    )) {
                throw new IllegalArgumentException(
                        "root occurrence must be this episode's SYSTEM_ENTRY"
                );
            }
            if (!timeline.getFirst().id().equals(rootOccurrenceId)) {
                throw new IllegalArgumentException(
                        "SYSTEM_ENTRY root must be the first occurrence"
                );
            }
            if (!startedAt.equals(root.timestamp())) {
                throw new IllegalArgumentException(
                        "startedAt must equal the SYSTEM_ENTRY timestamp"
                );
            }
        }
        if (completedAt != null && completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException(
                    "completedAt must not precede startedAt"
            );
        }
        if (completedAt != null
                && !timeline.isEmpty()
                && completedAt.isBefore(timeline.getLast().timestamp())) {
            throw new IllegalArgumentException(
                    "completedAt must not precede the last occurrence"
            );
        }

        Map<NormalizedEventType, List<EventOccurrenceId>> derivedIndex =
                index(timeline);
        Map<NormalizedEventType, List<EventOccurrenceId>> suppliedIndex =
                immutableIndex(occurrencesByEventType);
        if (!derivedIndex.equals(suppliedIndex)) {
            throw new IllegalArgumentException(
                    "occurrencesByEventType must match timeline"
            );
        }
        occurrencesByEventType = suppliedIndex;
        occurrenceTransitions = sortedTransitions(
                occurrenceTransitions,
                timeline
        );
        validateTransitions(timeline, occurrenceTransitions, id);
    }

    /**
     * An episode opened by something that happened, with its own root.
     *
     * <p>The root is a real {@code SYSTEM_ENTRY}: the Commander arrived, and
     * the arrival is a fact the graph counts and predicts from.</p>
     */
    public static SystemEpisode startWithRoot(
            SystemEpisodeId id,
            GraphId graphId,
            long systemAddress,
            String systemName,
            EpisodeEntrySource entrySource,
            EventOccurrence root
    ) {
        Objects.requireNonNull(root, "root");
        return new SystemEpisode(
                SCHEMA_VERSION,
                id,
                graphId,
                systemAddress,
                systemName,
                root.timestamp(),
                null,
                entrySource,
                null,
                root.id(),
                List.of(root),
                index(List.of(root)),
                List.of()
        );
    }

    /**
     * An episode opened by a restored session, with nothing recorded yet.
     *
     * <p>{@code Location} states where the Commander already is. That is not an
     * arrival and not an action, so it mints no occurrence and no root: the
     * episode exists so the visit has somewhere to accumulate, and stays empty
     * until something actually happens.</p>
     */
    public static SystemEpisode startRestored(
            SystemEpisodeId id,
            GraphId graphId,
            long systemAddress,
            String systemName,
            Instant restoredAt
    ) {
        Objects.requireNonNull(restoredAt, "restoredAt");
        return new SystemEpisode(
                SCHEMA_VERSION,
                id,
                graphId,
                systemAddress,
                systemName,
                restoredAt,
                null,
                EpisodeEntrySource.LOCATION_RESTORE,
                null,
                null,
                List.of(),
                Map.of(),
                List.of()
        );
    }

    /**
     * Appends one occurrence, with the transition that led to it.
     *
     * <p>{@code transition} is null exactly when this is the first occurrence
     * of a restored episode: there is no predecessor in this visit, so there is
     * no transition to record and no edge for the graph to learn from.</p>
     */
    public SystemEpisode appendOccurrence(
            EventOccurrence occurrence,
            OccurrenceTransition transition
    ) {
        requireActive();
        Objects.requireNonNull(occurrence, "occurrence");
        if (!occurrence.graphId().equals(graphId)
                || !occurrence.episodeId().equals(id)) {
            throw new IllegalArgumentException(
                    "occurrence belongs to another graph or episode"
            );
        }
        if (occurrence.episodeSequence() != timeline.size()) {
            throw new IllegalArgumentException(
                    "appended occurrence must use the next episodeSequence"
            );
        }
        if (timeline.stream().anyMatch(existing ->
                existing.id().equals(occurrence.id()))) {
            return this;
        }
        if (timeline.isEmpty()) {
            if (transition != null) {
                throw new IllegalArgumentException(
                        "the first occurrence of a restored episode follows "
                                + "nothing and takes no transition"
                );
            }
            if (occurrence.timestamp().isBefore(startedAt)) {
                throw new IllegalArgumentException(
                        "a restored episode cannot hold an earlier occurrence"
                );
            }
            return new SystemEpisode(
                    schemaVersion,
                    id,
                    graphId,
                    systemAddress,
                    systemName,
                    startedAt,
                    null,
                    entrySource,
                    null,
                    rootOccurrenceId,
                    List.of(occurrence),
                    index(List.of(occurrence)),
                    List.of()
            );
        }
        Objects.requireNonNull(transition, "transition");
        EventOccurrence previous = timeline.getLast();
        if (!transition.episodeId().equals(id)
                || !transition.fromOccurrenceId().equals(previous.id())
                || !transition.toOccurrenceId().equals(occurrence.id())
                || !transition.fromEventType().equals(previous.eventType())
                || !transition.toEventType().equals(occurrence.eventType())
                || !transition.observedAt().equals(occurrence.timestamp())) {
            throw new IllegalArgumentException(
                    "transition must connect the previous occurrence to the appended one"
            );
        }
        List<EventOccurrence> updated = new ArrayList<>(timeline);
        updated.add(occurrence);
        updated.sort(EventOccurrence.EPISODE_ORDER);
        if (!updated.getLast().id().equals(occurrence.id())) {
            throw new IllegalArgumentException(
                    "appended occurrence is out of episode source order"
            );
        }
        List<OccurrenceTransition> updatedTransitions =
                new ArrayList<>(occurrenceTransitions);
        updatedTransitions.add(transition);
        return new SystemEpisode(
                schemaVersion,
                id,
                graphId,
                systemAddress,
                systemName,
                startedAt,
                null,
                entrySource,
                null,
                rootOccurrenceId,
                updated,
                index(updated),
                updatedTransitions
        );
    }

    public SystemEpisode complete(
            Instant completionTime,
            EpisodeCompletionReason reason
    ) {
        Objects.requireNonNull(completionTime, "completionTime");
        Objects.requireNonNull(reason, "reason");
        if (completedAt != null) {
            return this;
        }
        Instant effectiveCompletion = completionTime.isBefore(startedAt)
                ? startedAt
                : completionTime;
        return new SystemEpisode(
                schemaVersion,
                id,
                graphId,
                systemAddress,
                systemName,
                startedAt,
                effectiveCompletion,
                entrySource,
                reason,
                rootOccurrenceId,
                timeline,
                occurrencesByEventType,
                occurrenceTransitions
        );
    }

    public boolean active() {
        return completedAt == null;
    }

    /**
     * Whether this episode is a restored visit that has recorded nothing yet.
     *
     * <p>True only between a session-restoring {@code Location} and the first
     * structural event of that visit. It is a real state, not a broken one: the
     * graph has no cursor while it holds.</p>
     */
    public boolean awaitingFirstOccurrence() {
        return timeline.isEmpty();
    }

    public EventOccurrence occurrence(EventOccurrenceId occurrenceId) {
        return timeline.stream()
                .filter(occurrence -> occurrence.id().equals(occurrenceId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown occurrence: " + occurrenceId
                ));
    }

    private void requireActive() {
        if (!active()) {
            throw new IllegalStateException("episode is already completed");
        }
    }

    private static List<EventOccurrence> sortedTimeline(
            List<EventOccurrence> occurrences
    ) {
        Objects.requireNonNull(occurrences, "timeline");
        List<EventOccurrence> copy = new ArrayList<>(occurrences);
        copy.sort(EventOccurrence.EPISODE_ORDER);
        for (int index = 1; index < copy.size(); index++) {
            if (copy.get(index - 1).id().equals(copy.get(index).id())) {
                throw new IllegalArgumentException(
                        "timeline occurrence IDs must be unique"
                );
            }
        }
        return List.copyOf(copy);
    }

    private static Map<NormalizedEventType, List<EventOccurrenceId>> index(
            List<EventOccurrence> timeline
    ) {
        TreeMap<NormalizedEventType, List<EventOccurrenceId>> mutable =
                new TreeMap<>();
        for (EventOccurrence occurrence : timeline) {
            mutable.computeIfAbsent(
                    occurrence.eventType(),
                    ignored -> new ArrayList<>()
            ).add(occurrence.id());
        }
        TreeMap<NormalizedEventType, List<EventOccurrenceId>> immutable =
                new TreeMap<>();
        mutable.forEach((type, ids) -> immutable.put(type, List.copyOf(ids)));
        return Collections.unmodifiableMap(immutable);
    }

    private static Map<NormalizedEventType, List<EventOccurrenceId>>
    immutableIndex(
            Map<NormalizedEventType, List<EventOccurrenceId>> index
    ) {
        Objects.requireNonNull(index, "occurrencesByEventType");
        TreeMap<NormalizedEventType, List<EventOccurrenceId>> copy =
                new TreeMap<>();
        index.forEach((type, ids) -> copy.put(
                Objects.requireNonNull(type, "event type"),
                List.copyOf(Objects.requireNonNull(ids, "occurrence IDs"))
        ));
        return Collections.unmodifiableMap(copy);
    }

    private static List<OccurrenceTransition> sortedTransitions(
            List<OccurrenceTransition> transitions,
            List<EventOccurrence> timeline
    ) {
        Objects.requireNonNull(transitions, "occurrenceTransitions");
        Objects.requireNonNull(timeline, "timeline");
        Map<EventOccurrenceId, Integer> episodeOrder = new TreeMap<>();
        for (int index = 0; index < timeline.size(); index++) {
            episodeOrder.put(timeline.get(index).id(), index);
        }
        List<OccurrenceTransition> copy = new ArrayList<>(transitions);
        for (OccurrenceTransition transition : copy) {
            if (!episodeOrder.containsKey(transition.fromOccurrenceId())
                    || !episodeOrder.containsKey(
                            transition.toOccurrenceId()
                    )) {
                throw new IllegalArgumentException(
                        "transition endpoints must exist in the timeline"
                );
            }
        }
        copy.sort(
                Comparator.comparingInt(
                                (OccurrenceTransition transition) ->
                                        episodeOrder.get(
                                                transition.toOccurrenceId()
                                        )
                        )
                        .thenComparing(OccurrenceTransition::id)
        );
        for (int index = 1; index < copy.size(); index++) {
            if (copy.get(index - 1).id().equals(copy.get(index).id())) {
                throw new IllegalArgumentException(
                        "transition occurrence IDs must be unique"
                );
            }
        }
        return List.copyOf(copy);
    }

    private static void validateTransitions(
            List<EventOccurrence> timeline,
            List<OccurrenceTransition> transitions,
            SystemEpisodeId episodeId
    ) {
        var ids = timeline.stream()
                .map(EventOccurrence::id)
                .collect(java.util.stream.Collectors.toSet());
        if (transitions.size() != Math.max(0, timeline.size() - 1)) {
            throw new IllegalArgumentException(
                    "episode must contain exactly one transition per adjacent occurrence pair"
            );
        }
        for (OccurrenceTransition transition : transitions) {
            if (!transition.episodeId().equals(episodeId)
                    || !ids.contains(transition.fromOccurrenceId())
                    || !ids.contains(transition.toOccurrenceId())) {
                throw new IllegalArgumentException(
                        "transition endpoints must exist in this episode"
                );
            }
            int fromIndex = indexOf(
                    timeline,
                    transition.fromOccurrenceId()
            );
            int toIndex = indexOf(timeline, transition.toOccurrenceId());
            EventOccurrence from = timeline.get(fromIndex);
            EventOccurrence to = timeline.get(toIndex);
            if (toIndex != fromIndex + 1
                    || !from.eventType().equals(
                            transition.fromEventType()
                    )
                    || !to.eventType().equals(transition.toEventType())
                    || !to.timestamp().equals(transition.observedAt())) {
                throw new IllegalArgumentException(
                        "transition must describe adjacent timeline occurrences"
                );
            }
        }
        for (int index = 1; index < timeline.size(); index++) {
            EventOccurrence previous = timeline.get(index - 1);
            EventOccurrence current = timeline.get(index);
            long matching = transitions.stream()
                    .filter(transition ->
                            transition.fromOccurrenceId()
                                    .equals(previous.id())
                                    && transition.toOccurrenceId()
                                    .equals(current.id()))
                    .count();
            if (matching != 1) {
                throw new IllegalArgumentException(
                        "each adjacent occurrence pair must have one transition"
                );
            }
        }
    }

    private static int indexOf(
            List<EventOccurrence> timeline,
            EventOccurrenceId occurrenceId
    ) {
        for (int index = 0; index < timeline.size(); index++) {
            if (timeline.get(index).id().equals(occurrenceId)) {
                return index;
            }
        }
        return -1;
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
