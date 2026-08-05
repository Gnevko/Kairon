package kairon.observer.decision;

import kairon.observation.journal.event.exploration.CodexEntry;
import kairon.observation.journal.event.exploration.Scan;
import kairon.observation.journal.event.travel.ApproachBody;
import kairon.semantics.SemanticField;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A family of game event and a slice of the situation are two things.
 *
 * <p>They were one enum, and the cost was two mechanisms that existed to remove
 * body context rather than to name a family: a codex entry and an arrival in an
 * undiscovered system are both exploration, and both simply read a narrower
 * slice. Narrowing one event's scope then meant inventing a constant that
 * everything keyed on a mechanism had to be checked against.</p>
 */
final class DecisionContextProfileContractTest {

    /** The two concepts are separate types, and neither implies the other. */
    @Test
    void aMechanismNamesAFamilyAndAProfileNamesASlice() {
        assertTrue(
                DecisionMechanism.TRAVEL.states(SemanticField.FLIGHT_MODE),
                "a mechanism claims what an event of its family states"
        );
        assertTrue(
                DecisionContextProfile.SYSTEM_AND_BODY_DETAIL.asksAboutABody(),
                "a profile claims what has to travel with it"
        );

        List<String> profileMethods = methodNames(DecisionMechanism.class);
        assertFalse(
                profileMethods.contains("contextNeeds"),
                "a mechanism no longer answers what to send: " + profileMethods
        );
        assertFalse(
                profileMethods.contains("subjectsInScope"),
                "nor which subjects are in scope: " + profileMethods
        );
    }

    /** No mechanism exists only to take a body out of the context. */
    @Test
    void noMechanismExistsOnlyToNarrowTheContext() {
        List<String> names = Arrays.stream(DecisionMechanism.values())
                .map(Enum::name)
                .toList();

        assertFalse(
                names.contains("CODEX"),
                "a codex entry is exploration read against the system"
        );
        assertFalse(
                names.contains("ARRIVAL_DISCOVERY"),
                "and so is arriving in a system nobody had discovered"
        );

        assertEquals(
                DecisionMechanism.EXPLORATION,
                DecisionEventCatalog.ruleFor(CodexEntry.class).mechanism()
        );
        assertEquals(
                DecisionContextProfile.SYSTEM_ONLY,
                DecisionEventCatalog.ruleFor(CodexEntry.class).contextProfile(),
                "the narrowing is the rule's own claim"
        );
        assertEquals(
                DecisionMechanism.EXPLORATION,
                DecisionEventCatalog.ruleFor(Scan.UndiscoveredStar.class)
                        .mechanism()
        );
        assertEquals(
                DecisionContextProfile.SYSTEM_ONLY,
                DecisionEventCatalog.ruleFor(Scan.UndiscoveredStar.class)
                        .contextProfile()
        );
    }

    /** Without an override, the mechanism's own profile is what is read. */
    @Test
    void aRuleWithoutAnOverrideReadsItsMechanismsProfile() {
        DecisionEventRule bodyScan = DecisionEventCatalog.ruleFor(Scan.class);
        DecisionEventRule approach =
                DecisionEventCatalog.ruleFor(ApproachBody.class);

        assertNull(bodyScan.readAs(), "no override declared");
        assertEquals(
                DecisionMechanism.EXPLORATION.contextProfile(),
                bodyScan.contextProfile()
        );
        assertEquals(
                DecisionMechanism.BODY_TRANSIT.contextProfile(),
                approach.contextProfile()
        );
        assertTrue(
                approach.contextProfile().asksAboutABody(),
                "an approach really is read against the body"
        );
    }

    /**
     * Changing one event's scope needs no new mechanism.
     *
     * <p>The whole point of the split, stated as the operation it enables: the
     * same family, a different slice, and nothing else in the catalogue
     * touched.</p>
     */
    @Test
    void oneRuleCanNarrowItsScopeWithoutANewMechanism() {
        int mechanismCount = DecisionMechanism.values().length;
        DecisionEventRule narrowed = DecisionEventRule
                .of("BODY_SCANNED", DecisionMechanism.EXPLORATION)
                .reading(DecisionContextProfile.SYSTEM_ONLY);

        assertEquals(DecisionMechanism.EXPLORATION, narrowed.mechanism());
        assertEquals(
                DecisionContextProfile.SYSTEM_ONLY,
                narrowed.contextProfile()
        );
        assertFalse(narrowed.contextProfile().asksAboutABody());
        assertNotEquals(
                DecisionMechanism.EXPLORATION.contextProfile(),
                narrowed.contextProfile()
        );
        assertEquals(
                mechanismCount,
                DecisionMechanism.values().length,
                "and the mechanism set did not grow"
        );
    }

    /** An override that restates the default says nothing and is refused. */
    @Test
    void anOverrideThatRestatesTheDefaultIsRefused() {
        assertThrows(
                IllegalArgumentException.class,
                () -> DecisionEventRule
                        .of("BODY_SCANNED", DecisionMechanism.EXPLORATION)
                        .reading(
                                DecisionMechanism.EXPLORATION.contextProfile()
                        )
        );
    }

    /** Every mechanism's default profile is a real one, and every profile is
     * reachable from some rule or some mechanism. */
    @Test
    void everyMechanismHasAProfileAndEveryProfileIsReachable() {
        List<DecisionContextProfile> reachable = new ArrayList<>();
        for (DecisionMechanism mechanism : DecisionMechanism.values()) {
            DecisionContextProfile profile = mechanism.contextProfile();
            assertTrue(
                    profile != null,
                    mechanism + " must name a profile"
            );
            if (!reachable.contains(profile)) {
                reachable.add(profile);
            }
        }
        for (DecisionEventRule rule : DecisionEventCatalog.declaredRules()) {
            DecisionContextProfile profile = rule.contextProfile();
            if (!reachable.contains(profile)) {
                reachable.add(profile);
            }
        }
        List<DecisionContextProfile> unreachable =
                new ArrayList<>(Arrays.asList(
                        DecisionContextProfile.values()
                ));
        unreachable.removeAll(reachable);
        assertEquals(
                List.of(),
                unreachable,
                "a profile nothing reads is a slice nobody asked for"
        );
    }

    private static List<String> methodNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName)
                .toList();
    }
}
