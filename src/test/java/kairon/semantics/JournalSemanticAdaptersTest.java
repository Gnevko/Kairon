package kairon.semantics;

import kairon.semantics.SemanticFact.EntityKind;
import kairon.semantics.SemanticFact.ProcessStage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mechanism-oriented structured-fact tests.
 *
 * <p>Each case asserts a semantic property of a class of events, not the
 * rendering of one replay fixture. Several encode defects that prose-only
 * meaning previously allowed.</p>
 */
class JournalSemanticAdaptersTest {

    private final SemanticJournalFixture fixture =
            new SemanticJournalFixture();

    // ---------------------------------------------------------------------
    // Independent qualifiers must not bleed into one another
    // ---------------------------------------------------------------------

    @Test
    void missingSenderDoesNotBecomeAnUnknownChannel() {
        SemanticFact fact = fixture.singleFactOf("""
                {"timestamp":"2026-07-30T14:00:00Z","event":"ReceiveText",\
                "Message":"Hello there","Channel":"local"}
                """);

        assertEquals(SemanticOperation.RECEIVED, fact.operation());
        assertFalse(
                fact.qualifiers().containsKey("sender"),
                "an absent sender must be absent, not a placeholder"
        );
        assertEquals(
                new SemanticValue.SymbolicValue("local"),
                fact.qualifiers().get("channel"),
                "the channel must survive a missing sender unchanged"
        );
    }

    @Test
    void senderAndChannelStayDistinctWhenBothPresent() {
        SemanticFact fact = fixture.singleFactOf("""
                {"timestamp":"2026-07-30T14:00:00Z","event":"ReceiveText",\
                "From":"Commander Vex","Message":"On my way","Channel":"wing"}
                """);

        assertEquals(
                new SemanticValue.TextValue("Commander Vex"),
                fact.qualifiers().get("sender")
        );
        assertEquals(
                new SemanticValue.SymbolicValue("wing"),
                fact.qualifiers().get("channel")
        );
        assertNotEquals(
                fact.qualifiers().get("sender"),
                fact.qualifiers().get("channel")
        );
    }

    // ---------------------------------------------------------------------
    // Facts are bound to the subject they are about
    // ---------------------------------------------------------------------

    @Test
    void codexCoordinatesBelongToTheCodexRecordNotToSampling() {
        SemanticObservationEnvelope envelope = fixture.envelopeOf("""
                {"timestamp":"2026-07-30T14:00:00Z","event":"CodexEntry",\
                "EntryID":2100401,"Name":"$Codex_Ent_Bacterial_01_Name;",\
                "Name_Localised":"Bacterium Aurasus",\
                "Category":"$Codex_Category_Biology;",\
                "System":"Synthetic Alpha","SystemAddress":7101,"BodyID":5,\
                "Latitude":12.5,"Longitude":-40.25,"IsNewEntry":true}
                """);

        assertEquals(1, envelope.structuredFacts().size());
        SemanticFact fact = envelope.structuredFacts().getFirst();

        assertEquals(SemanticSubject.CURRENT_BODY, fact.subject());
        assertNotEquals(
                SemanticSubject.BIOLOGICAL_SAMPLING_PROCESS,
                fact.subject(),
                "a codex record is not a sampling step"
        );
        assertEquals(EntityKind.CODEX_ENTRY, fact.object().kind());
        assertEquals(
                new SemanticValue.CoordinatesValue(12.5, -40.25),
                fact.qualifiers().get("position")
        );
    }

    @Test
    void launchingAFighterIsNotABiologicalSamplingFact() {
        SemanticObservationEnvelope envelope = fixture.envelopeOf("""
                {"timestamp":"2026-07-30T14:00:00Z","event":"LaunchFighter",\
                "Loadout":"zero","ID":13,"PlayerControlled":false}
                """);

        SemanticFact fact = envelope.structuredFacts().getFirst();
        assertEquals(SemanticSubject.ASSOCIATED_VEHICLE, fact.subject());
        assertNotEquals(
                SemanticSubject.BIOLOGICAL_SAMPLING_PROCESS,
                fact.subject()
        );
        assertEquals(SemanticOperation.LAUNCHED, fact.operation());
    }

    @Test
    void ownVesselIdentifierKeepsAKnownEntityKind() {
        SemanticFact fact = fixture.singleFactOf("""
                {"timestamp":"2026-07-30T14:00:00Z","event":"LaunchFighter",\
                "Loadout":"starter","ID":42,"PlayerControlled":true}
                """);

        assertEquals(EntityKind.AUXILIARY_VEHICLE, fact.object().kind());
        assertNotEquals(
                EntityKind.UNRESOLVED,
                fact.object().kind(),
                "a fighter ID is a fighter, not an unattributed number"
        );
        SemanticValue.IdentityValue identity = assertInstanceOf(
                SemanticValue.IdentityValue.class,
                fact.identity()
        );
        assertEquals("FighterID", identity.kind());
        assertEquals("42", identity.value());
    }

    @Test
    void fighterControlDoesNotEstablishCommanderPresence() {
        SemanticObservationEnvelope envelope = fixture.envelopeOf("""
                {"timestamp":"2026-07-30T14:00:00Z","event":"LaunchFighter",\
                "Loadout":"starter","ID":42,"PlayerControlled":true}
                """);

        assertTrue(
                envelope.unresolvedFacts().stream().anyMatch(gap ->
                        gap.reason() == UnresolvedFact.Reason
                                .FIGHTER_OCCUPANCY_NOT_ESTABLISHED),
                "fighter occupancy must be recorded as unresolved"
        );
        assertTrue(
                envelope.structuredFacts().stream().noneMatch(fact ->
                        fact.subject()
                                == SemanticSubject.COMMANDER_PRESENCE),
                "controlling a fighter says nothing about physical presence"
        );
    }

    // ---------------------------------------------------------------------
    // Process stage and completion are structural
    // ---------------------------------------------------------------------

    @Test
    void intermediateOrganicScanIsNotCompleted() {
        for (String scanType : new String[]{"Log", "Sample"}) {
            SemanticFact fact = fixture.singleFactOf("""
                    {"timestamp":"2026-07-30T14:00:00Z","event":"ScanOrganic",\
                    "ScanType":"%s","Genus":"$Codex_Ent_Bacterial_Genus_Name;",\
                    "Genus_Localised":"Bacterium","SystemAddress":7101,\
                    "Body":5}
                    """.formatted(scanType));

            assertEquals(
                    SemanticSubject.BIOLOGICAL_SAMPLING_PROCESS,
                    fact.subject()
            );
            assertEquals(Boolean.FALSE, fact.completion(),
                    scanType + " must not read as a completed sequence");
            assertNotEquals(ProcessStage.FINAL, fact.processStage());
        }
    }

    @Test
    void finalOrganicScanIsCompleted() {
        SemanticFact fact = fixture.singleFactOf("""
                {"timestamp":"2026-07-30T14:00:00Z","event":"ScanOrganic",\
                "ScanType":"Analyse",\
                "Genus":"$Codex_Ent_Bacterial_Genus_Name;",\
                "Genus_Localised":"Bacterium","SystemAddress":7101,"Body":5}
                """);

        assertEquals(ProcessStage.FINAL, fact.processStage());
        assertEquals(Boolean.TRUE, fact.completion());
    }

    // ---------------------------------------------------------------------
    // Q-02: negation is an explicit negative assertion, never a reversal
    // ---------------------------------------------------------------------

    @Test
    void leavingABodyIsACompletedActionNotANegatedOne() {
        SemanticFact fact = fixture.singleFactOf("""
                {"timestamp":"2026-07-30T14:00:00Z","event":"LeaveBody",\
                "StarSystem":"Alpha","SystemAddress":7101,"Body":"Alpha 1",\
                "BodyID":3}
                """);

        assertEquals(SemanticOperation.LEFT, fact.operation());
        assertEquals(Boolean.TRUE, fact.completion());
        assertNull(
                fact.negation(),
                "leaving a body asserts nothing false; it completes an action"
        );
        assertEquals("reverses ApproachBody", fact.relationship());
    }

    @Test
    void recoveringAnSrvIsACompletedActionNotANegatedOne() {
        SemanticFact fact = fixture.singleFactOf("""
                {"timestamp":"2026-07-30T14:00:00Z","event":"DockSRV",\
                "SRVID":42,"SRVType":"testbuggy"}
                """);

        assertEquals(SemanticOperation.RECOVERED, fact.operation());
        assertEquals(Boolean.TRUE, fact.completion());
        assertNull(
                fact.negation(),
                "recovering an SRV asserts nothing false"
        );
        assertEquals("reverses LaunchSRV", fact.relationship());
    }

    @Test
    void undockingIsACompletedActionNotANegatedOne() {
        SemanticFact fact = fixture.singleFactOf("""
                {"timestamp":"2026-07-30T14:00:00Z","event":"Undocked",\
                "MarketID":3229,"StationName":"Vega Hub",\
                "StationType":"Coriolis"}
                """);

        assertEquals(SemanticOperation.UNDOCKED, fact.operation());
        assertEquals(Boolean.TRUE, fact.completion());
        assertNull(fact.negation());
        assertEquals("reverses Docked", fact.relationship());
    }

    /**
     * The Q-02 invariant, stated at the level it actually holds: a lifecycle
     * reversal expresses itself through {@code operation} and
     * {@code relationship}, never through {@code negation}.
     *
     * <p>This is deliberately <strong>not</strong> the broader claim that
     * {@code completion: true} and {@code negation: true} cannot coexist.
     * {@code completion} and {@code negation} are independent dimensions, and
     * {@link #aCompletedActionMayStillCarryAnExplicitFieldNegation()} pins a
     * real event where both are true and both are correct.</p>
     */
    @Test
    void reverseOperationsExpressReversalThroughRelationshipNotNegation() {
        List<String> reversals = List.of(
                """
                {"timestamp":"2026-07-30T14:00:00Z","event":"LeaveBody",\
                "StarSystem":"Alpha","SystemAddress":7101,"Body":"Alpha 1",\
                "BodyID":3}
                """,
                """
                {"timestamp":"2026-07-30T14:00:01Z","event":"DockSRV",\
                "SRVID":42,"SRVType":"testbuggy"}
                """,
                """
                {"timestamp":"2026-07-30T14:00:02Z","event":"Undocked",\
                "MarketID":3229,"StationName":"Vega Hub"}
                """,
                """
                {"timestamp":"2026-07-30T14:00:03Z","event":"EscapeInterdiction",\
                "Interdictor":"Hostile","IsPlayer":false}
                """,
                """
                {"timestamp":"2026-07-30T14:00:04Z","event":"SRVDestroyed",\
                "SRVID":42,"SRVType":"testbuggy"}
                """,
                """
                {"timestamp":"2026-07-30T14:00:05Z","event":"WingLeave"}
                """,
                """
                {"timestamp":"2026-07-30T14:00:06Z","event":"LeftSquadron",\
                "SquadronName":"Test Wing"}
                """,
                """
                {"timestamp":"2026-07-30T14:00:07Z","event":"PowerplayLeave",\
                "Power":"Test Power"}
                """
        );

        for (String rawJson : reversals) {
            for (SemanticFact fact
                    : fixture.envelopeOf(rawJson).structuredFacts()) {
                assertNull(
                        fact.negation(),
                        "a lifecycle reversal asserts no named proposition "
                                + "false: " + fact.operation()
                                + " in " + rawJson.strip()
                );
                assertNotNull(
                        fact.relationship(),
                        "and it must still say what it reverses: "
                                + fact.operation() + " in " + rawJson.strip()
                );
            }
        }
    }

    /**
     * The correction to an over-broad reading of Q-02.
     *
     * <p>A completed action may carry an explicit negation when the source
     * reports a distinct named field false. Here the jump completed and the
     * event separately states {@code BoostUsed: false}. Forbidding the
     * combination outright would delete a true assertion.</p>
     */
    @Test
    void aCompletedActionMayStillCarryAnExplicitFieldNegation() {
        SemanticFact unboosted = fixture.singleFactOf("""
                {"timestamp":"2026-07-30T14:00:10Z","event":"FSDJump",\
                "StarSystem":"Beta","SystemAddress":7102,"JumpDist":8.25,\
                "BoostUsed":false}
                """);

        assertEquals(SemanticOperation.ENTERED, unboosted.operation());
        assertEquals(Boolean.TRUE, unboosted.completion());
        assertEquals(Boolean.TRUE, unboosted.negation());
        assertNull(
                unboosted.relationship(),
                "an asserted-false field is not a reversal of a paired "
                        + "operation and must not claim one"
        );
    }

    // ---------------------------------------------------------------------
    // Negation survives independently of prose
    // ---------------------------------------------------------------------

    /**
     * F1.1: when {@code negation} is true the negated target must be readable
     * from the structured fact, never inferred from prose.
     */
    @Test
    void boostUsedNegationNamesItsTargetAsATypedQualifier() {
        SemanticFact boosted = fixture.singleFactOf("""
                {"timestamp":"2026-07-30T14:00:00Z","event":"FSDJump",\
                "StarSystem":"Alpha","SystemAddress":7101,"JumpDist":12.5,\
                "BoostUsed":true}
                """);
        SemanticFact unboosted = fixture.singleFactOf("""
                {"timestamp":"2026-07-30T14:00:10Z","event":"FSDJump",\
                "StarSystem":"Beta","SystemAddress":7102,"JumpDist":8.25,\
                "BoostUsed":false}
                """);
        SemanticFact unstated = fixture.singleFactOf("""
                {"timestamp":"2026-07-30T14:00:20Z","event":"FSDJump",\
                "StarSystem":"Gamma","SystemAddress":7103,"JumpDist":4.0}
                """);

        assertEquals(
                new SemanticValue.BooleanValue(true),
                boosted.qualifiers().get("boostUsed")
        );
        assertEquals(Boolean.FALSE, boosted.negation());

        assertEquals(
                new SemanticValue.BooleanValue(false),
                unboosted.qualifiers().get("boostUsed"),
                "negation=true must be readable against a named qualifier"
        );
        assertEquals(Boolean.TRUE, unboosted.negation());

        assertFalse(
                unstated.qualifiers().containsKey("boostUsed"),
                "an unstated field is absent, never guessed"
        );
        assertNull(unstated.negation());
    }

    @Test
    void isNewEntryNegationNamesItsTargetAsATypedQualifier() {
        SemanticFact fresh = fixture.singleFactOf("""
                {"timestamp":"2026-07-30T14:00:00Z","event":"CodexEntry",\
                "Name":"$Codex_Ent_Bacterial_01_Name;","Category":"Biology",\
                "System":"Alpha","SystemAddress":7101,"BodyID":3,\
                "IsNewEntry":true}
                """);
        SemanticFact repeat = fixture.singleFactOf("""
                {"timestamp":"2026-07-30T14:00:10Z","event":"CodexEntry",\
                "Name":"$Codex_Ent_Bacterial_01_Name;","Category":"Biology",\
                "System":"Alpha","SystemAddress":7101,"BodyID":3,\
                "IsNewEntry":false}
                """);
        SemanticFact unstated = fixture.singleFactOf("""
                {"timestamp":"2026-07-30T14:00:20Z","event":"CodexEntry",\
                "Name":"$Codex_Ent_Bacterial_01_Name;","Category":"Biology",\
                "System":"Alpha","SystemAddress":7101,"BodyID":3}
                """);

        assertEquals(
                new SemanticValue.BooleanValue(true),
                fresh.qualifiers().get("isNewEntry")
        );
        assertEquals(Boolean.FALSE, fresh.negation());

        assertEquals(
                new SemanticValue.BooleanValue(false),
                repeat.qualifiers().get("isNewEntry")
        );
        assertEquals(Boolean.TRUE, repeat.negation());

        assertFalse(unstated.qualifiers().containsKey("isNewEntry"));
        assertNull(unstated.negation());
    }

    @Test
    void explicitNegativeBooleanBecomesStructuralNegation() {
        SemanticFact boosted = fixture.singleFactOf("""
                {"timestamp":"2026-07-30T14:00:00Z","event":"FSDJump",\
                "StarSystem":"Alpha","SystemAddress":7101,"JumpDist":12.5,\
                "BoostUsed":true}
                """);
        SemanticFact unboosted = fixture.singleFactOf("""
                {"timestamp":"2026-07-30T14:00:10Z","event":"FSDJump",\
                "StarSystem":"Beta","SystemAddress":7102,"JumpDist":8.25,\
                "BoostUsed":false}
                """);
        SemanticFact unstated = fixture.singleFactOf("""
                {"timestamp":"2026-07-30T14:00:20Z","event":"FSDJump",\
                "StarSystem":"Gamma","SystemAddress":7103,"JumpDist":4.0}
                """);

        assertEquals(Boolean.FALSE, boosted.negation());
        assertEquals(Boolean.TRUE, unboosted.negation());
        assertNull(
                unstated.negation(),
                "an unstated fact is not a negative one"
        );
        assertEquals(
                new SemanticValue.QuantityValue(12.5, "LIGHT_YEARS"),
                boosted.quantity()
        );
    }

    @Test
    void missionOutcomePolarityIsStructuralNotClassIdentityOnly() {
        SemanticFact completed = fixture.singleFactOf("""
                {"timestamp":"2026-07-30T14:00:00Z","event":"MissionCompleted",\
                "Name":"Mission_Courier","MissionID":901,"Faction":"Union",\
                "Reward":45000}
                """);
        SemanticFact failed = fixture.singleFactOf("""
                {"timestamp":"2026-07-30T14:05:00Z","event":"MissionFailed",\
                "Name":"Mission_Courier","MissionID":902}
                """);

        assertEquals(SemanticOperation.COMPLETED, completed.operation());
        assertEquals(Boolean.TRUE, completed.completion());
        assertEquals(Boolean.FALSE, completed.negation());
        assertEquals(
                new SemanticValue.QuantityValue(45000.0, "CREDITS"),
                completed.quantity()
        );

        assertEquals(SemanticOperation.FAILED, failed.operation());
        assertEquals(Boolean.FALSE, failed.completion());
        assertEquals(Boolean.TRUE, failed.negation());
    }

    @Test
    void dockingDenialKeepsItsReasonAsASymbolNotProse() {
        SemanticFact fact = fixture.singleFactOf("""
                {"timestamp":"2026-07-30T14:00:00Z","event":"DockingDenied",\
                "Reason":"NoSpace","MarketID":3229,"StationName":"Vega Hub"}
                """);

        assertEquals(SemanticOperation.DOCKING_DENIED, fact.operation());
        assertEquals(Boolean.FALSE, fact.completion());
        assertEquals(Boolean.TRUE, fact.negation());
        assertEquals(
                new SemanticValue.SymbolicValue("NoSpace"),
                fact.qualifiers().get("reason")
        );
    }

    // ---------------------------------------------------------------------
    // Unmodelled vessel context stays unresolved
    // ---------------------------------------------------------------------

    @Test
    void taxiTransferIsNotTreatedAsThePrimaryShip() {
        SemanticObservationEnvelope envelope = fixture.envelopeOf("""
                {"timestamp":"2026-07-30T14:00:00Z","event":"Embark",\
                "SRV":false,"Taxi":true,"Multicrew":false,"ID":77,\
                "StarSystem":"Synthetic Alpha","SystemAddress":7101}
                """);

        SemanticFact fact = envelope.structuredFacts().getFirst();
        assertEquals(SemanticSubject.COMMANDER_PRESENCE, fact.subject());
        assertEquals(
                EntityKind.UNRESOLVED,
                fact.object().kind(),
                "a taxi must never be bound to the commander's own ship"
        );
        assertNotEquals(EntityKind.SHIP, fact.object().kind());
        assertTrue(envelope.unresolvedFacts().stream().anyMatch(gap ->
                gap.reason()
                        == UnresolvedFact.Reason.TAXI_CONTEXT_NOT_MODELLED));
    }

    @Test
    void multicrewTransferIsRecordedAsUnresolvedContext() {
        SemanticObservationEnvelope envelope = fixture.envelopeOf("""
                {"timestamp":"2026-07-30T14:00:00Z","event":"Disembark",\
                "SRV":false,"Taxi":false,"Multicrew":true,"ID":91,\
                "StarSystem":"Synthetic Alpha","SystemAddress":7101}
                """);

        assertEquals(
                EntityKind.UNRESOLVED,
                envelope.structuredFacts().getFirst().object().kind()
        );
        assertTrue(envelope.unresolvedFacts().stream().anyMatch(gap ->
                gap.reason() == UnresolvedFact.Reason
                        .MULTICREW_CONTEXT_NOT_MODELLED));
    }

    @Test
    void ownShipEmbarkKeepsTheShipEntityKind() {
        SemanticObservationEnvelope envelope = fixture.envelopeOf("""
                {"timestamp":"2026-07-30T14:00:00Z","event":"Embark",\
                "SRV":false,"Taxi":false,"Multicrew":false,"ID":5,\
                "StarSystem":"Synthetic Alpha","SystemAddress":7101}
                """);

        assertEquals(
                EntityKind.SHIP,
                envelope.structuredFacts().getFirst().object().kind()
        );
        assertTrue(
                envelope.unresolvedFacts().isEmpty(),
                "an unambiguous own-ship transfer has no unresolved context"
        );
    }

    @Test
    void srvDisembarkLeavesOccupancyUnresolved() {
        SemanticObservationEnvelope envelope = fixture.envelopeOf("""
                {"timestamp":"2026-07-30T14:00:00Z","event":"Disembark",\
                "SRV":true,"Taxi":false,"Multicrew":false,"ID":8,\
                "StarSystem":"Synthetic Alpha","SystemAddress":7101}
                """);

        assertEquals(
                EntityKind.AUXILIARY_VEHICLE,
                envelope.structuredFacts().getFirst().object().kind()
        );
        assertTrue(envelope.unresolvedFacts().stream().anyMatch(gap ->
                gap.subject() == SemanticSubject.OCCUPIED_VEHICLE
                        && gap.reason() == UnresolvedFact.Reason
                        .VEHICLE_OCCUPANCY_NOT_ESTABLISHED));
    }

    // ---------------------------------------------------------------------
    // Provenance
    // ---------------------------------------------------------------------

    @Test
    void everyFactCarriesItsOwnObservationProvenance() {
        SemanticObservationEnvelope envelope = fixture.envelopeOf("""
                {"timestamp":"2026-07-30T14:00:00Z","event":"SupercruiseExit",\
                "StarSystem":"Synthetic Alpha","SystemAddress":7101,\
                "Body":"Synthetic 1","BodyID":3,"BodyType":"Planet"}
                """);

        SemanticFact fact = envelope.structuredFacts().getFirst();
        assertEquals(envelope.busSequence(), fact.provenance().busSequence());
        assertEquals(SemanticSourceRole.NEW, fact.provenance().sourceRole());
        assertEquals(
                "SupercruiseExit",
                fact.provenance().rawObservationType()
        );
        assertEquals(SemanticSubject.NAVIGATION_CONTEXT, fact.subject());
    }

    @Test
    void contextOnlyEventKeepsItsContextOnlyRole() {
        SemanticObservationEnvelope envelope = fixture.envelopeOf("""
                {"timestamp":"2026-07-30T14:00:00Z","event":"FSDTarget",\
                "Name":"Synthetic Target","SystemAddress":7101,\
                "RemainingJumpsInRoute":2}
                """);

        assertEquals(
                SemanticSourceRole.CONTEXT_ONLY,
                envelope.sourceRole()
        );
        assertEquals(
                SemanticSourceRole.CONTEXT_ONLY,
                envelope.structuredFacts().getFirst()
                        .provenance().sourceRole()
        );
    }
}
