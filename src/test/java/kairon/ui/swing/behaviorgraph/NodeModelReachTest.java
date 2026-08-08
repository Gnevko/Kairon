package kairon.ui.swing.behaviorgraph;

import kairon.behavior.normalize.NormalizedEventType;
import kairon.observer.LlmJournalEventSelection;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the graph shows about which of its nodes the model ever hears.
 *
 * <p>The graph records more than the model is shown, and until now the picture
 * said nothing about the difference: a node the model never hears about was
 * drawn exactly like the one it comments on. The three answers are read from
 * the two authorities — the normalizer's rule table for which journal classes a
 * node came from, and {@link LlmJournalEventSelection} for what happens to
 * them — so the drawing cannot drift from the runtime by being maintained
 * separately.</p>
 */
final class NodeModelReachTest {

    /** A landing is a landing: every one of them opens a turn. */
    @Test
    void anOrdinaryJournalNodeIsAlwaysSent() {
        assertEquals(
                NodeModelReach.ALWAYS,
                NodeModelReach.of(NormalizedEventType.TOUCHDOWN)
        );
        assertEquals(
                NodeModelReach.ALWAYS,
                NodeModelReach.of(NormalizedEventType.SYSTEM_ENTRY),
                "the root of an episode: an FSDJump opens a turn, and a "
                        + "Location restore is CONTEXT_ONLY — one of the two "
                        + "is heard, so the node is"
        );
    }

    /**
     * A sampling step is judged one observation at a time.
     *
     * <p>{@code Logged} and {@code Analysed} open turns and the samples between
     * them do not, and all three are {@code ScanOrganic}. The node cannot
     * promise either way, and saying "always" would be the drawing lying about
     * the middle step.</p>
     */
    @Test
    void aTypeWhoseObservationsAreJudgedOneAtATimeSaysSometimes() {
        assertEquals(
                NodeModelReach.SOMETIMES,
                NodeModelReach.of(NormalizedEventType.SCAN_ORGANIC_SAMPLE)
        );
        assertEquals(
                NodeModelReach.SOMETIMES,
                NodeModelReach.of(NormalizedEventType.BODY_SCANNED),
                "a scan is admitted by its depth"
        );
        assertEquals(
                NodeModelReach.SOMETIMES,
                NodeModelReach.of(NormalizedEventType.SAA_SIGNALS_FOUND),
                "a signal report is admitted by having counted something"
        );
    }

    /**
     * The six Status.json occurrences are the graph's alone.
     *
     * <p>No journal contains them, so they have no journal class behind them at
     * all, and the model has never been shown one. They are also the reason the
     * distinction is worth drawing: a graph full of them looks like a graph
     * full of things Kairon talked about.</p>
     */
    @Test
    void aStatusDerivedNodeIsNeverSent() {
        for (NormalizedEventType statusDerived : Set.of(
                NormalizedEventType.FSS_MODE_ENTERED,
                NormalizedEventType.FSS_MODE_EXITED,
                NormalizedEventType.SAA_MODE_ENTERED,
                NormalizedEventType.SAA_MODE_EXITED
        )) {
            assertEquals(
                    NodeModelReach.NEVER,
                    NodeModelReach.of(statusDerived),
                    statusDerived.value()
            );
        }
    }

    /** An unresearched type answers rather than throwing. */
    @Test
    void anUnknownTypeIsNeverSentRatherThanUnanswered() {
        assertEquals(
                NodeModelReach.NEVER,
                NodeModelReach.of(NormalizedEventType.unknown("NoSuchEvent"))
        );
    }

    /** Three answers, three distinct words for them. */
    @Test
    void eachAnswerReadsDifferentlyInTheLegend() {
        Set<String> labels = new LinkedHashSet<>();
        for (NodeModelReach reach : NodeModelReach.values()) {
            assertNotNull(reach.label());
            labels.add(reach.label());
        }
        assertEquals(
                NodeModelReach.values().length,
                labels.size(),
                "a legend with two identical rows explains nothing"
        );
        assertTrue(labels.stream().allMatch(label -> !label.isBlank()));
    }
}
