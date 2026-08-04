package kairon.observer.decision;

import kairon.observation.ObservationDraft.ObservationCaptureMode;
import kairon.observation.ObservationDraft.ObservationSource;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalLineParser;
import kairon.observation.journal.JournalLineParser.CompleteJournalRecord;
import kairon.observation.journal.JournalLineParser.ParsedJournalRecord;
import kairon.observation.journal.JournalObservationAdapter;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two extension points of the catalogue, and what keeps them honest.
 *
 * <p>A rule is keyed by journal class or earned by a record's own fields. Both
 * are enumerable, both are in {@code declaredRules()}, and the record-keyed set
 * has one rule the class-keyed set cannot express — which used to be a single
 * {@code if} inside the lookup, invisible to any property asserted of "every
 * rule" and with a precedence decided by whichever branch was written first.</p>
 */
final class DecisionRecordRuleTest {

    /**
     * Journal records covering the shapes a record rule could mistake.
     *
     * <p>Both scan depths, a star and a planet, discovered and not, plus two
     * other exploration records filed under the same body. If a predicate is
     * looser than it claims, one of these is where it shows.</p>
     */
    private static final List<String> CORPUS = List.of(
            // The arrival star, undiscovered: the one record that earns a rule.
            """
            {"timestamp":"2026-07-30T10:00:00Z","event":"Scan",
             "ScanType":"AutoScan","SystemAddress":23155,"BodyID":0,
             "BodyName":"Schieni","StarType":"K","WasDiscovered":false}
            """,
            // The same star, already found by somebody.
            """
            {"timestamp":"2026-07-30T10:00:01Z","event":"Scan",
             "ScanType":"AutoScan","SystemAddress":23155,"BodyID":0,
             "BodyName":"Schieni","StarType":"K","WasDiscovered":true}
            """,
            // A detailed body scan: catalogued by type.
            """
            {"timestamp":"2026-07-30T10:00:02Z","event":"Scan",
             "ScanType":"Detailed","SystemAddress":23155,"BodyID":20,
             "BodyName":"Schieni 4 a","PlanetClass":"Icy body",
             "Landable":true,"WasDiscovered":false,"WasMapped":false}
            """,
            // A shallow planet reading, which establishes nothing.
            """
            {"timestamp":"2026-07-30T10:00:03Z","event":"Scan",
             "ScanType":"Basic","SystemAddress":23155,"BodyID":20,
             "BodyName":"Schieni 4 a","PlanetClass":"Icy body",
             "WasDiscovered":false}
            """,
            """
            {"timestamp":"2026-07-30T10:00:04Z","event":"FSSBodySignals",
             "SystemAddress":23155,"BodyID":20,"BodyName":"Schieni 4 a",
             "Signals":[{"Type":"$SAA_SignalType_Biological;","Count":1}]}
            """,
            """
            {"timestamp":"2026-07-30T10:00:05Z","event":"FSDJump",
             "StarSystem":"Schieni","SystemAddress":23155,"BodyID":0,
             "Body":"Schieni","JumpDist":8.5}
            """,
            """
            {"timestamp":"2026-07-30T10:00:06Z","event":"ApproachBody",
             "StarSystem":"Schieni","SystemAddress":23155,"BodyID":20,
             "Body":"Schieni 4 a"}
            """
    );

    /** Both extension points exist, and neither is empty by accident. */
    @Test
    void bothRuleSetsAreEnumerable() {
        assertFalse(DecisionEventCatalog.coveredTypes().isEmpty());
        assertFalse(DecisionEventCatalog.recordRules().isEmpty());
        assertEquals(
                1,
                DecisionEventCatalog.recordRules().size(),
                "one record rule today; adding a second is a declaration, "
                        + "not another branch"
        );
    }

    /** {@code declaredRules()} is the union of both, with nothing invented. */
    @Test
    void declaredRulesIsExactlyTheUnionOfBothSets() {
        List<DecisionEventRule> declared = DecisionEventCatalog.declaredRules();

        assertEquals(
                DecisionEventCatalog.coveredTypes().size()
                        + DecisionEventCatalog.recordRules().size(),
                declared.size(),
                "every rule from both sets, and no others"
        );
        for (Class<? extends JournalEventObservation> eventType
                : DecisionEventCatalog.coveredTypes()) {
            assertTrue(
                    declared.contains(
                            DecisionEventCatalog.ruleFor(eventType)
                    ),
                    eventType.getSimpleName() + " is reachable"
            );
        }
        for (RecordDecisionRule recordRule
                : DecisionEventCatalog.recordRules()) {
            assertTrue(
                    declared.contains(recordRule.rule()),
                    recordRule.name() + " is reachable"
            );
        }
    }

    /** No record can be claimed by two record rules. */
    @Test
    void recordPredicatesAreMutuallyExclusive() {
        List<String> ambiguous = new ArrayList<>();
        for (String rawJson : CORPUS) {
            JournalEventObservation event = event(rawJson);
            List<String> matched = DecisionEventCatalog.recordRules().stream()
                    .filter(rule -> rule.matches(event))
                    .map(RecordDecisionRule::name)
                    .toList();
            if (matched.size() > 1) {
                ambiguous.add(event.getClass().getSimpleName() + " " + matched);
            }
        }
        assertEquals(List.of(), ambiguous);
    }

    /**
     * An ambiguous record fails rather than picking a winner.
     *
     * <p>Asserted in both declaration orders, because "the first one wins" and
     * "this is an error" are indistinguishable when only one order is
     * tried.</p>
     */
    @Test
    void anAmbiguousRecordFailsFastInEitherOrder() {
        JournalEventObservation arrivalStar = event(CORPUS.getFirst());
        RecordDecisionRule first = new RecordDecisionRule(
                "everything",
                event -> true,
                DecisionEventRule.of(
                        "FIRST_CLAIM",
                        DecisionMechanism.EXPLORATION
                )
        );
        RecordDecisionRule second = new RecordDecisionRule(
                "everything again",
                event -> true,
                DecisionEventRule.of(
                        "SECOND_CLAIM",
                        DecisionMechanism.EXPLORATION
                )
        );

        assertThrows(
                IllegalStateException.class,
                () -> DecisionEventCatalog.ruleFor(
                        arrivalStar,
                        List.of(first, second)
                )
        );
        assertThrows(
                IllegalStateException.class,
                () -> DecisionEventCatalog.ruleFor(
                        arrivalStar,
                        List.of(second, first)
                )
        );
    }

    /** With one match the answer is that rule, whatever else is declared. */
    @Test
    void oneMatchIsAnsweredAndTheTypeRuleIsTheFallback() {
        JournalEventObservation arrivalStar = event(CORPUS.getFirst());
        JournalEventObservation detailedScan = event(CORPUS.get(2));
        RecordDecisionRule neverMatches = new RecordDecisionRule(
                "nothing",
                event -> false,
                DecisionEventRule.of(
                        "NEVER",
                        DecisionMechanism.EXPLORATION
                )
        );
        RecordDecisionRule earned =
                DecisionEventCatalog.recordRules().getFirst();

        assertSame(
                earned.rule(),
                DecisionEventCatalog.ruleFor(
                        arrivalStar,
                        List.of(neverMatches, earned)
                )
        );
        assertSame(
                earned.rule(),
                DecisionEventCatalog.ruleFor(
                        arrivalStar,
                        List.of(earned, neverMatches)
                ),
                "and the answer does not depend on where it was declared"
        );
        assertSame(
                DecisionEventCatalog.ruleFor(detailedScan.getClass()),
                DecisionEventCatalog.ruleFor(
                        detailedScan,
                        List.of(neverMatches, earned)
                ),
                "a record earning nothing falls back to its type's rule"
        );
    }

    /**
     * The milestone the record rule carries is unchanged.
     *
     * <p>Its kind is what the model reads, and the four claims beside it are
     * what the projection acts on: the object it names, the count it must not
     * carry, and the attributes it keeps from the scan it was derived from.</p>
     */
    @Test
    void theArrivalStarMilestoneKeepsItsKindAndItsClaims() {
        DecisionEventRule rule =
                DecisionEventCatalog.recordRules().getFirst().rule();

        assertEquals("SYSTEM_UNDISCOVERED_CONFIRMED", rule.kind());
        assertEquals(
                DecisionMechanism.EXPLORATION,
                rule.mechanism(),
                "an arrival in an undiscovered system is exploration"
        );
        assertEquals(
                DecisionContextProfile.SYSTEM_ONLY,
                rule.contextProfile(),
                "read against the system alone, by its own claim rather than "
                        + "by being a family of its own"
        );
        assertEquals("arrivalStar", rule.objectName());
        assertTrue(rule.uncountedOnBody());
        assertEquals(
                Set.of("system", "starType", "previouslyDiscovered"),
                rule.retainedQualifiers()
        );
        assertSame(
                rule,
                DecisionEventCatalog.ruleFor(event(CORPUS.getFirst())),
                "and the record that earns it still earns it"
        );
        assertEquals(
                "BODY_SCANNED",
                DecisionEventCatalog.ruleFor(event(CORPUS.get(2))).kind(),
                "while a detailed scan is still a body scan"
        );
    }

    // ------------------------------------------------------------- fixtures

    private static JournalEventObservation event(String rawJson) {
        ParsedJournalRecord parsed = (ParsedJournalRecord) new JournalLineParser()
                .parse(new CompleteJournalRecord(
                        "Journal.record-rule-test.log",
                        0L,
                        rawJson.strip().getBytes(StandardCharsets.UTF_8)
                ));
        return new JournalObservationAdapter(
                new ObservationSource("elite-journal", "record-rule-test")
        ).adapt(
                parsed,
                ObservationCaptureMode.REPLAY,
                parsed.optionalJournalTimestamp().orElseThrow()
        ).payload();
    }
}
