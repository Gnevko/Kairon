package kairon.ui.swing.behaviorgraph;

import kairon.behavior.normalize.BehaviorEventNormalizer;
import kairon.behavior.normalize.NormalizedEventType;
import kairon.observation.journal.JournalEventObservation;
import kairon.observer.LlmJournalEventSelection;

import java.util.List;
import java.util.Objects;

/**
 * Whether observations behind a graph node ever open a model turn.
 *
 * <p>The graph and the model are two different consumers of the same
 * observations, and they disagree about most of them: the graph records
 * everything structural, while the model is shown a narrower set decided by
 * {@link LlmJournalEventSelection}. Looking at the graph, there was no way to
 * tell which is which — every node looked the same, and a node the model never
 * hears about looked exactly like the one it comments on.</p>
 *
 * <p>Three answers, because there are three cases and flattening them would
 * lie. {@link #ALWAYS} is a type every observation of which becomes a trigger.
 * {@link #SOMETIMES} is a type whose observations are judged one at a time by
 * {@code admitsAsTrigger} — a chat message by its channel, a scan by its depth,
 * a signal report by whether it counted anything, a sampling step by which step
 * it is. {@link #NEVER} covers both a {@code CONTEXT_ONLY} type and the six
 * {@code Status.json} occurrences, which no journal contains and which
 * therefore have no journal class at all.</p>
 *
 * <p>This is a presentation question and lives with the presentation. It reads
 * the normalizer's rule table and the observer's selection, which are the two
 * authorities on the two halves; nothing here decides anything, so the picture
 * cannot drift from the runtime by being maintained separately.</p>
 */
public enum NodeModelReach {

    /** Every observation of this type opens a turn. */
    ALWAYS,

    /** Admitted one observation at a time. */
    SOMETIMES,

    /** Recorded by the graph, never shown to the model. */
    NEVER;

    /**
     * Journal classes whose admission is decided per observation.
     *
     * <p>Named by class rather than asked of {@code admitsAsTrigger}, because
     * that method answers about an observation and this question is about a
     * type. The list is the set of classes that method branches on, and
     * {@code NodeModelReachContractTest} fails if it stops matching.</p>
     */
    private static final List<String> JUDGED_PER_OBSERVATION = List.of(
            "ReceiveText",
            "Scan",
            "FSSBodySignals",
            "SAASignalsFound",
            "ScanOrganic"
    );

    public static NodeModelReach of(NormalizedEventType eventType) {
        Objects.requireNonNull(eventType, "eventType");
        List<Class<? extends JournalEventObservation>> journalTypes =
                BehaviorEventNormalizer.journalTypesOf(eventType);
        if (journalTypes.isEmpty()) {
            // Derived from Status.json, or projected by a rule that names no
            // journal class: either way the model is never shown one.
            return NEVER;
        }
        NodeModelReach reach = NEVER;
        for (Class<? extends JournalEventObservation> journalType
                : journalTypes) {
            NodeModelReach one = of(journalType);
            if (one == ALWAYS) {
                return ALWAYS;
            }
            if (one == SOMETIMES) {
                reach = SOMETIMES;
            }
        }
        return reach;
    }

    private static NodeModelReach of(
            Class<? extends JournalEventObservation> journalType
    ) {
        if (LlmJournalEventSelection.roleOf(journalType)
                != LlmJournalEventSelection.ObserverInputRole.NEW_ELIGIBLE) {
            return NEVER;
        }
        return judgedPerObservation(journalType) ? SOMETIMES : ALWAYS;
    }

    private static boolean judgedPerObservation(
            Class<? extends JournalEventObservation> journalType
    ) {
        for (Class<?> candidate = journalType;
                candidate != null;
                candidate = candidate.getEnclosingClass()) {
            if (JUDGED_PER_OBSERVATION.contains(candidate.getSimpleName())) {
                return true;
            }
        }
        return false;
    }

    /** What the legend calls it. */
    public String label() {
        return switch (this) {
            case ALWAYS -> "sent to the model";
            case SOMETIMES -> "sometimes sent";
            case NEVER -> "never sent";
        };
    }
}
