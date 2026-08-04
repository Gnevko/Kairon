package kairon.observer.decision;

import kairon.behavior.model.EventOccurrenceId;
import kairon.behavior.snapshot.ActiveEpisodeSituation;
import kairon.behavior.snapshot.BehaviorSituationSnapshot;
import kairon.behavior.snapshot.SituationNextEventPrediction;
import kairon.behavior.snapshot.SituationOccurrence;
import kairon.observer.decision.DecisionEventProjector.ProjectedEvent;
import kairon.projection.ProjectedObservation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What led here, and what usually follows.
 *
 * <p>Two things a list of current events cannot say on its own: whether this has
 * just happened before, and whether it is the middle of a sequence. Both are
 * already established — the episode remembers what preceded this event, and the
 * transition model has already calculated what tends to come next. Nothing is
 * recomputed here; the calculation, its weights and its probabilities are read
 * exactly as captured.</p>
 *
 * <p>What is projected is only the domain content of that. The occurrence
 * identities, cursor, episode position, provenance, evidence counts, prediction
 * basis and context bucket all stay inside Kairon: a model cannot act on them,
 * and a probability quoted next to its own supporting counts invites the model
 * to re-derive a number it was already given.</p>
 */
public final class DecisionTrajectoryProjector {

    /** Enough to recognise a repeat or a sequence; more is a transcript. */
    private static final int MAX_RECENT = 3;

    /** Enough to say what usually follows without listing the tail. */
    private static final int MAX_PREDICTIONS = 3;

    /**
     * The kinds whose meaning owes nothing to where the ship has been.
     *
     * <p>A received message says what it says, and a friend coming online
     * happens to a person somewhere else entirely. Neither is made easier or
     * harder to read by knowing that the ship jumped, landed or lifted off
     * first, and a forecast of the ship's next manoeuvre beside one of them
     * invites the model to connect two unrelated things.</p>
     *
     * <p>This is the whole policy: a named, closed set, tested against the
     * catalogue so a kind cannot be listed here under a spelling no event
     * actually uses. Adding to it is a claim about an event kind that has to be
     * defensible on its own — not a place to put anything that looks noisy.</p>
     */
    private static final Set<String> TRAJECTORY_INDEPENDENT_KINDS = Set.of(
            "MESSAGE_RECEIVED",
            "FRIEND_STATUS"
    );

    /** The kinds this projection treats as independent of the flight history. */
    static Set<String> trajectoryIndependentKinds() {
        return TRAJECTORY_INDEPENDENT_KINDS;
    }

    /**
     * The trajectory for one turn, or null when there is nothing to say.
     *
     * <p>Read from the final trigger, which is the observation whose captured
     * situation applies to the whole turn — the same one the context comes
     * from.</p>
     */
    public LlmDecisionRequest.Trajectory project(
            DecisionTurnInputs inputs,
            List<ProjectedEvent> events
    ) {
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(events, "events");
        if (onlyTrajectoryIndependent(events)) {
            return null;
        }
        BehaviorSituationSnapshot situation =
                inputs.finalTrigger().behaviorSituation();
        Optional<ActiveEpisodeSituation> captured = situation.activeEpisode();
        if (captured.isEmpty()) {
            return null;
        }
        List<String> recent = recent(
                captured.orElseThrow(),
                currentOccurrences(inputs)
        );
        List<LlmDecisionRequest.Prediction> likelyNext =
                likelyNext(situation.likelyNext());
        if (recent.isEmpty() && likelyNext.isEmpty()) {
            return null;
        }
        return new LlmDecisionRequest.Trajectory(recent, likelyNext);
    }

    /**
     * Whether nothing in this turn is about where the ship has been.
     *
     * <p>Decided on the projected kinds alone, so it is a fact about the batch
     * rather than a judgement about its content: no message text, no sender, no
     * channel, no friend name and no repetition is read, and the graph is
     * untouched either way. A batch with any other kind in it keeps its
     * trajectory, because that other event is exactly the one the history might
     * explain — one friend notification does not silence a landing.</p>
     */
    private static boolean onlyTrajectoryIndependent(
            List<ProjectedEvent> events
    ) {
        for (ProjectedEvent projected : events) {
            if (!TRAJECTORY_INDEPENDENT_KINDS.contains(
                    projected.event().kind()
            )) {
                return false;
            }
        }
        return !events.isEmpty();
    }

    /**
     * The three events before this one, oldest first.
     *
     * <p>Every occurrence this turn's own triggers committed is excluded, not
     * just the last one. The whole batch is what {@code events} already states,
     * and a predecessor list that repeats it would read as the same thing having
     * happened twice.</p>
     *
     * <p>An occurrence with no domain name is skipped rather than ending the
     * walk. It is a type Kairon has not researched, and stopping at it would
     * silently shorten the history for a reason the model cannot see.</p>
     */
    private static List<String> recent(
            ActiveEpisodeSituation episode,
            Set<EventOccurrenceId> currentOccurrences
    ) {
        List<SituationOccurrence> trajectory = episode.trajectory();
        List<String> recent = new ArrayList<>(MAX_RECENT);
        for (int index = trajectory.size() - 1;
                index >= 0 && recent.size() < MAX_RECENT;
                index--) {
            SituationOccurrence occurrence = trajectory.get(index);
            if (currentOccurrences.contains(occurrence.occurrenceId())) {
                continue;
            }
            String kind = DecisionTrajectoryNames.kindOf(
                    occurrence.eventType()
            );
            if (kind != null) {
                recent.add(kind);
            }
        }
        Collections.reverse(recent);
        return List.copyOf(recent);
    }

    /**
     * What the transition model expects next, most likely first.
     *
     * <p>The order and the probabilities are the calculation's own; this takes
     * a prefix of it. A prediction whose type has no domain name is dropped, so
     * the list can be shorter than the calculation's — never longer, and never
     * reordered.</p>
     */
    private static List<LlmDecisionRequest.Prediction> likelyNext(
            List<SituationNextEventPrediction> predictions
    ) {
        List<LlmDecisionRequest.Prediction> result =
                new ArrayList<>(MAX_PREDICTIONS);
        for (SituationNextEventPrediction prediction : predictions) {
            if (result.size() == MAX_PREDICTIONS) {
                break;
            }
            String kind = DecisionTrajectoryNames.kindOf(
                    prediction.predictedEventType()
            );
            if (kind != null) {
                result.add(new LlmDecisionRequest.Prediction(
                        kind,
                        prediction.probability()
                ));
            }
        }
        return List.copyOf(result);
    }

    /** The occurrences this turn's own triggers committed. */
    private static Set<EventOccurrenceId> currentOccurrences(
            DecisionTurnInputs inputs
    ) {
        Set<EventOccurrenceId> current = new HashSet<>();
        for (ProjectedObservation trigger : inputs.triggers()) {
            ActiveEpisodeSituation owned =
                    DecisionOccurrenceScope.ownedEpisode(trigger);
            if (owned != null) {
                current.add(owned.currentOccurrence().occurrenceId());
            }
        }
        return current;
    }
}
