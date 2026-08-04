package kairon.observer.decision;

import kairon.observer.decision.DecisionEventProjector.ProjectedEvent;
import kairon.semantics.SemanticField;
import kairon.semantics.SemanticValue;
import kairon.state.CurrentGameStateSnapshot;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Whether canonical body facts describe the body this turn is about.
 *
 * <p>Two different bodies can be in one request, and only one of them is
 * labelled. An event names the body its record names — the one that was
 * scanned, approached or landed on. Canonical state answers for the body the
 * ship is at, which a remote scan deliberately does not move: reading a planet
 * across a system is not arriving at it. Both reach the document under the
 * subject {@code body}.</p>
 *
 * <p>So a survey of a system produced turns whose event described a planet and
 * whose {@code context.body} described the arrival star — its type, its class,
 * how far from arrival it is, whether anyone had been there. Nothing in the
 * document said they were different bodies, because the star is named after its
 * system and the event's own {@code system} field suppressed the name that
 * would have shown it.</p>
 *
 * <p>The rule is scope, not repair. When the events name a body, canonical body
 * facts are only sent if they are that body's; otherwise they belong to a body
 * this turn is not about and are dropped, from both {@code changes} and
 * {@code context}. When no event names a body, the body the ship is at is the
 * only one in play and answers for itself.</p>
 *
 * <p>Compared as canonical identities rather than as strings: the value an
 * event stated under {@code body.name}, against the same field read from the
 * snapshot. A body is never inferred from a name here — two statements of the
 * same canonical field are checked for agreement, which is what every other
 * suppression in this layer already does.</p>
 */
final class DecisionBodyScope {

    private DecisionBodyScope() {
    }

    /**
     * Whether the snapshot's body facts belong in a turn about these events.
     *
     * <p>False when no mechanism in the turn asks about a body at all, when the
     * events name a body the snapshot is not answering for, and when they name
     * more than one — no single canonical body can be all of them, and a survey
     * batch routinely reports several.</p>
     */
    static boolean canonicalBodyIsInScope(
            List<ProjectedEvent> events,
            CurrentGameStateSnapshot state
    ) {
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(state, "state");
        if (events.stream().noneMatch(DecisionBodyScope::asksAboutABody)) {
            return false;
        }
        String slot = DecisionNames.slotOf(SemanticField.BODY_NAME);
        Set<SemanticValue> named = new LinkedHashSet<>();
        for (ProjectedEvent event : events) {
            SemanticValue stated = event.statedFacts().get(slot);
            if (stated != null && stated.known()) {
                named.add(stated);
            }
        }
        if (named.isEmpty()) {
            return true;
        }
        if (named.size() > 1) {
            return false;
        }
        SemanticValue current = SemanticValue.ofText(state.bodyName());
        return current.known() && named.contains(current);
    }

    /**
     * Whether any mechanism in this turn has business with a body at all.
     *
     * <p>The same question the context already asks itself, applied to changes
     * too. A mechanism that asks the situation for no body is a mechanism a body
     * fact has no reader in — and a change is a body fact. The two halves used
     * to disagree for a change one of the turn's own events caused: an arrival
     * in an undiscovered system asks for the system, so no {@code context.body}
     * was built, while the same reading's {@code previouslyMapped},
     * {@code previouslyFootfalled} and {@code distanceFromArrivalLs} still
     * arrived as changes under the subject {@code body}.</p>
     *
     * <p>Only the context profile decides it, so nothing here is a claim about
     * a kind. An event read against a body — a landing, a scan, a sample, an
     * approach — is untouched, and what it is then sent is settled by the
     * identity comparison below exactly as before.</p>
     */
    private static boolean asksAboutABody(ProjectedEvent event) {
        return event.contextProfile().asksAboutABody();
    }

    /** Whether this field is one of the body's. */
    static boolean isBodyField(SemanticField field) {
        Objects.requireNonNull(field, "field");
        return "body".equals(DecisionNames.subject(field.subject()));
    }
}
