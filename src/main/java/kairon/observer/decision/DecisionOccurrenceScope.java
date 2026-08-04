package kairon.observer.decision;

import kairon.behavior.graph.BehaviorGraphIds;
import kairon.behavior.model.EventOccurrenceId;
import kairon.behavior.snapshot.ActiveEpisodeSituation;
import kairon.behavior.snapshot.SituationOccurrence;
import kairon.projection.ProjectedObservation;
import kairon.semantics.BodyIdentity;

import java.util.Objects;
import java.util.Optional;

/**
 * Whether a trigger has an occurrence of its own, and how often it has happened.
 *
 * <p>Ownership is derived, not believed. An apply reporting {@code APPLIED}
 * only says the graph changed — an owner switch, an episode switch or a bare
 * revision bump all report it, and in each of those the cursor still points at
 * an occurrence some earlier observation committed. So the occurrence id the
 * graph would mint for <em>this</em> observation is recomputed here and compared
 * with the cursor. Anything else would attribute another observation's position
 * in the episode to this event.</p>
 *
 * <p>Everything read here comes from the immutable situation captured with the
 * trigger. No graph service is consulted, so the same inputs always give the
 * same answer.</p>
 */
final class DecisionOccurrenceScope {

    private DecisionOccurrenceScope() {
    }

    /**
     * The episode whose current occurrence this trigger committed, or null.
     *
     * <p>Null covers every case where the question cannot be answered from the
     * capture: the graph was off, the situation was unavailable, or the cursor
     * belongs to a different observation.</p>
     */
    static ActiveEpisodeSituation ownedEpisode(ProjectedObservation trigger) {
        Objects.requireNonNull(trigger, "trigger");
        Optional<ActiveEpisodeSituation> captured =
                trigger.behaviorSituation().activeEpisode();
        if (captured.isEmpty()) {
            return null;
        }
        ActiveEpisodeSituation episode = captured.orElseThrow();
        EventOccurrenceId minted = BehaviorGraphIds.journalOccurrence(
                episode.graphId(),
                trigger.trigger().observationId()
        );
        return minted.equals(episode.currentOccurrence().occurrenceId())
                ? episode
                : null;
    }

    /**
     * How many times this has now happened at this body, this visit.
     *
     * <p>Counted over the active episode's own trajectory, which is one visit to
     * one system, and narrowed to the occurrences that share both the event type
     * and the exact body of the current one. The all-time count the graph also
     * keeps is deliberately not used: "the second landing here" is something a
     * Commander can recognise, and "the ninetieth landing since Kairon started
     * watching" is not.</p>
     *
     * <p>Null when this trigger owns no occurrence, or when the graph had not
     * established a body for it — an event that happened nowhere in particular
     * has no count that would mean anything.</p>
     */
    static Integer occurrenceOnBody(ProjectedObservation trigger) {
        ActiveEpisodeSituation episode = ownedEpisode(trigger);
        if (episode == null) {
            return null;
        }
        SituationOccurrence current = episode.currentOccurrence();
        BodyIdentity body = current.body();
        if (body == null) {
            return null;
        }
        int count = 0;
        for (SituationOccurrence occurrence : episode.trajectory()) {
            if (occurrence.eventType().equals(current.eventType())
                    && body.equals(occurrence.body())) {
                count++;
            }
        }
        return count;
    }
}
