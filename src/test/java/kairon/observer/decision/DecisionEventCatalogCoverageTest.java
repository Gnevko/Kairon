package kairon.observer.decision;

import kairon.observation.journal.JournalEventLookup;
import kairon.observation.journal.JournalEventObservation;
import kairon.observer.LlmJournalEventSelection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every model-eligible event has a domain name, and nothing else does.
 *
 * <p>Coverage is checked in both directions on purpose. A missing rule would
 * send a real event under a generic fallback the model cannot interpret; a rule
 * for something outside the selection profile would be a name nobody can reach,
 * and a quiet claim that Kairon handles an event it never sees.</p>
 */
final class DecisionEventCatalogCoverageTest {

    @Test
    void everyModelEligibleEventTypeHasARule() {
        List<String> missing = new ArrayList<>();
        for (Class<? extends JournalEventObservation> eventType
                : LlmJournalEventSelection.TARGET_NEW_ELIGIBLE) {
            if (DecisionEventCatalog.ruleFor(eventType) == null) {
                missing.add(eventType.getSimpleName());
            }
        }
        assertEquals(
                List.of(),
                missing,
                "a model-eligible event with no rule would reach the model "
                        + "under a guessed name"
        );
    }

    /**
     * Nothing is catalogued that no admitted observation can reach.
     *
     * <p>Coverage rather than a count. The two used to be the same question —
     * one entry per admitted type, so comparing sizes proved the table held
     * nothing else. A record that dispatches to several classes broke that
     * arithmetic without breaking anything real: the profile admits the record
     * and the table may hold an entry per variant, so the sizes differ by
     * however many variants happen to need their own kind. What still has to
     * hold is that every entry is reachable, and reachable means the entry is
     * an admitted type or a variant of one.</p>
     */
    @Test
    void theCatalogueCoversNothingBeyondTheSelectionProfile() {
        Set<Class<? extends JournalEventObservation>> eligible =
                new HashSet<>(LlmJournalEventSelection.TARGET_NEW_ELIGIBLE);
        List<String> extra = new ArrayList<>();
        for (Class<? extends JournalEventObservation> covered
                : DecisionEventCatalog.coveredTypes()) {
            if (!JournalEventLookup.covers(eligible, covered)) {
                extra.add(covered.getSimpleName());
            }
        }
        assertEquals(List.of(), extra);
    }

    /**
     * A kind is a domain name, not a wire name.
     *
     * <p>Some kinds are deliberately shared — selling exploration data one body
     * at a time or all at once is the same thing happening — so kinds are not
     * required to be unique. What is required is that none of them is simply
     * the journal's own event name handed through.</p>
     */
    @Test
    void noKindIsJustTheJournalEventName() {
        List<String> passthrough = new ArrayList<>();
        for (Class<? extends JournalEventObservation> eventType
                : DecisionEventCatalog.coveredTypes()) {
            String kind = DecisionEventCatalog.ruleFor(eventType).kind();
            if (kind.equals(eventType.getSimpleName())) {
                passthrough.add(kind);
            }
        }
        assertEquals(List.of(), passthrough);
    }

    @Test
    void everyKindIsAnUpperSnakeCaseDomainName() {
        for (DecisionEventRule rule : DecisionEventCatalog.declaredRules()) {
            assertTrue(
                    rule.kind().matches("[A-Z][A-Z_]*[A-Z]"),
                    rule.kind() + " is not a stable domain-facing name"
            );
        }
    }

    /** Only mechanisms that are actually reachable are declared. */
    @Test
    void everyMechanismIsUsedByAtLeastOneEvent() {
        Set<DecisionMechanism> used = new LinkedHashSet<>();
        for (DecisionEventRule rule : DecisionEventCatalog.declaredRules()) {
            used.add(rule.mechanism());
        }
        assertEquals(
                Set.of(DecisionMechanism.values()),
                used
        );
    }

    /**
     * Only genuinely multi-step mechanisms declare stages.
     *
     * <p>A stage on an atomic action is a constant, and the previous contract
     * sent one on twenty-seven of thirty-two facts.</p>
     */
    @Test
    void multiStageIsDeclaredOnlyWhereThereAreRealStages() {
        Set<String> staged = new LinkedHashSet<>();
        for (Class<? extends JournalEventObservation> eventType
                : DecisionEventCatalog.coveredTypes()) {
            DecisionEventRule rule = DecisionEventCatalog.ruleFor(eventType);
            if (rule.multiStage()) {
                staged.add(rule.kind());
            }
        }
        assertEquals(
                Set.of(
                        "BIOLOGICAL_SAMPLE",
                        "SHIP_TRANSFER_SCHEDULED",
                        "CARRIER_JUMP_SCHEDULED",
                        "CONSTRUCTION_PROGRESS"
                ),
                staged
        );
    }

    /**
     * A kind may claim to be the whole action only where that is defensible.
     *
     * <p>One event does today. A vehicle launch names nothing it acted on, has
     * no process position anyone can act on, and asserts nothing about where the
     * Commander is — so there is nothing further for a stage or an uncertainty
     * to describe.</p>
     */
    @Test
    void wholeActionIsDeclaredOnlyWhereTheKindSettlesEverything() {
        Set<String> whole = new LinkedHashSet<>();
        for (Class<? extends JournalEventObservation> eventType
                : DecisionEventCatalog.coveredTypes()) {
            DecisionEventRule rule = DecisionEventCatalog.ruleFor(eventType);
            if (rule.wholeAction()) {
                whole.add(rule.kind());
            }
        }
        assertEquals(Set.of("VEHICLE_LAUNCHED"), whole);
    }

    /**
     * A gap may be dropped only where the request itself answers it.
     *
     * <p>The two presence transfers do. Getting into a vessel and getting out
     * of one both leave {@code context.commander.presence} saying exactly what
     * an occupancy gap would be asking about, so the gap qualifies a settled
     * question. The vehicle events raise the same gap with nothing in the
     * request to answer it and keep it — which is why this is pinned as an
     * exact set rather than as a property of the gap or the mechanism.</p>
     */
    @Test
    void aSettledGapIsDeclaredOnlyWhereTheRequestAnswersIt() {
        Set<String> settled = new LinkedHashSet<>();
        for (Class<? extends JournalEventObservation> eventType
                : DecisionEventCatalog.coveredTypes()) {
            DecisionEventRule rule = DecisionEventCatalog.ruleFor(eventType);
            if (rule.settledGap() != null) {
                settled.add(rule.kind() + ":" + rule.settledGap());
            }
        }
        assertEquals(
                Set.of("EMBARKED:occupancy", "DISEMBARKED:occupancy"),
                settled
        );
    }

    /**
     * The occurrence count is dropped only where it would count a stage.
     *
     * <p>One kind spans three structural graph types today: a log, a sample and
     * an analysis are counted separately, while the model reads one
     * {@code BIOLOGICAL_SAMPLE}, so a body-scoped count under that name would be
     * true of something the model cannot see. The other staged kinds — a ship
     * transfer, a carrier jump, a construction depot — are one type each and
     * keep the field. Pinned as an exact set, because this is a claim about a
     * kind rather than a property of being multi-stage.</p>
     */
    @Test
    void theOccurrenceCountIsDroppedOnlyWhereTheGraphCountsStages() {
        Set<String> perStage = new LinkedHashSet<>();
        for (Class<? extends JournalEventObservation> eventType
                : DecisionEventCatalog.coveredTypes()) {
            DecisionEventRule rule = DecisionEventCatalog.ruleFor(eventType);
            if (rule.stageSpecificOccurrences()) {
                perStage.add(rule.kind());
            }
        }
        assertEquals(Set.of("BIOLOGICAL_SAMPLE"), perStage);
    }

    @Test
    void anUncataloguedPayloadHasNoRuleRatherThanAGuessedOne() {
        assertNotNull(DecisionEventCatalog.ruleFor(
                kairon.observation.journal.event.social.Friends.class
        ));
        assertEquals(
                null,
                DecisionEventCatalog.ruleFor(
                        kairon.observation.journal.UnknownJournalEvent.class
                )
        );
    }
}
