package kairon.semantics;

import kairon.observation.ObservationDraft.ObservationCaptureMode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.event.session.LoadGame;
import kairon.observation.journal.event.session.Music;
import kairon.observation.journal.event.ship.Loadout;
import kairon.observation.journal.event.travel.StartJump;
import kairon.observer.LlmJournalEventSelection;
import kairon.observer.LlmJournalEventSelection.ObserverInputRole;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * One classification, read by the layers that used to define it.
 *
 * <p>The semantic source role and the observer's input profile were two lists
 * of the same 112 class literals, and the semantic layer read the observer's.
 * They are now one list in the semantic layer, and this states the three things
 * that has to keep being true.</p>
 */
final class SemanticSourceRoleCatalogTest {

    /**
     * Journal types deliberately outside both profile lists.
     *
     * <p>One of each kind the classification has to keep apart: a structural
     * event the graph records but the model is never handed
     * ({@code StartJump}), two standing-state snapshots ({@code LoadGame},
     * {@code Loadout}) and one the pipeline keeps only for the corpus
     * ({@code Music}).</p>
     */
    private static final List<Class<? extends JournalEventObservation>>
            OUTSIDE = List.of(
                    StartJump.class,
                    LoadGame.class,
                    Loadout.class,
                    Music.class
            );

    /** Every catalogued journal type resolves to exactly one role. */
    @Test
    void theCatalogueClassifiesEveryJournalTypeExactlyOnce() {
        List<Class<? extends JournalEventObservation>> newTypes =
                SemanticSourceRoleCatalog.newEventTypes();
        List<Class<? extends JournalEventObservation>> contextTypes =
                SemanticSourceRoleCatalog.contextOnlyEventTypes();

        for (Class<? extends JournalEventObservation> eventType : newTypes) {
            assertEquals(
                    SemanticSourceRole.NEW,
                    SemanticSourceRoleCatalog.roleOf(eventType),
                    eventType.getSimpleName()
            );
        }
        for (Class<? extends JournalEventObservation> eventType
                : contextTypes) {
            assertEquals(
                    SemanticSourceRole.CONTEXT_ONLY,
                    SemanticSourceRoleCatalog.roleOf(eventType),
                    eventType.getSimpleName()
            );
        }

        List<String> misclassified = new ArrayList<>();
        for (Class<? extends JournalEventObservation> outside : OUTSIDE) {
            if (newTypes.contains(outside) || contextTypes.contains(outside)) {
                misclassified.add(outside.getSimpleName()
                        + " is in a profile list after all");
            } else if (SemanticSourceRoleCatalog.roleOf(outside)
                    != SemanticSourceRole.DIAGNOSTIC_ONLY) {
                misclassified.add(outside.getSimpleName()
                        + " is outside both lists and not diagnostic");
            }
        }
        assertEquals(
                List.of(),
                misclassified,
                "everything outside the two lists is diagnostic only"
        );
    }

    /**
     * The observer reads the catalogue rather than holding a copy of it.
     *
     * <p>Identity, not equality. Two equal lists are two lists, and the defect
     * being prevented is exactly the pair of lists that drifted apart.</p>
     */
    @Test
    void theObserverProfileIsTheCatalogueItself() {
        assertSame(
                SemanticSourceRoleCatalog.newEventTypes(),
                LlmJournalEventSelection.NEW_ELIGIBLE,
                "the runtime NEW profile is the catalogue's own list"
        );
        assertSame(
                SemanticSourceRoleCatalog.contextOnlyEventTypes(),
                LlmJournalEventSelection.CONTEXT_ONLY
        );
        assertSame(
                LlmJournalEventSelection.NEW_ELIGIBLE,
                LlmJournalEventSelection.TARGET_NEW_ELIGIBLE,
                "and the researched target is the same list, not a second copy"
        );

        List<Class<? extends JournalEventObservation>> sampled =
                new ArrayList<>(OUTSIDE);
        sampled.addAll(SemanticSourceRoleCatalog.newEventTypes());
        sampled.addAll(SemanticSourceRoleCatalog.contextOnlyEventTypes());
        for (Class<? extends JournalEventObservation> eventType : sampled) {
            ObserverInputRole expected =
                    switch (SemanticSourceRoleCatalog.roleOf(eventType)) {
                        case NEW -> ObserverInputRole.NEW_ELIGIBLE;
                        case CONTEXT_ONLY -> ObserverInputRole.CONTEXT_ONLY;
                        default -> ObserverInputRole.DIAGNOSTIC_ONLY;
                    };
            assertEquals(
                    expected,
                    LlmJournalEventSelection.roleOf(eventType),
                    eventType.getSimpleName()
                            + " must be spelled from the same answer"
            );
        }
    }

    /**
     * Retention is keyed on capture mode, and on nothing that could move.
     *
     * <p>Stated as a property over every role rather than as an example:
     * reclassifying a type from {@code NEW} to {@code CONTEXT_ONLY} must not
     * change which effects survive to a later turn, because the two questions
     * have different answers for the same observation.</p>
     */
    @Test
    void retentionDoesNotDependOnTheSourceRole() {
        for (ObservationCaptureMode captureMode
                : ObservationCaptureMode.values()) {
            EffectRetention expected =
                    captureMode == ObservationCaptureMode.BOOTSTRAP
                            ? EffectRetention.RESTORE_ONLY
                            : EffectRetention.RETAIN_FOR_TURN;
            assertEquals(
                    expected,
                    ObservationSemantics.retentionOf(captureMode),
                    captureMode + " decides retention on its own"
            );
        }
        assertNotSame(
                ObservationSemantics.retentionOf(
                        ObservationCaptureMode.BOOTSTRAP
                ),
                ObservationSemantics.retentionOf(ObservationCaptureMode.LIVE),
                "the two capture modes really do differ"
        );
    }
}
