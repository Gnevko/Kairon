package kairon.observer.decision;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static kairon.observer.decision.SemanticPipelineAssertions
        .assertChangesAndContextPartition;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One fact is said once, whatever its type and whichever section could say it.
 *
 * <p>{@code events}, {@code changes} and {@code context} used to answer "has
 * this already been said?" with different machinery. The change selector
 * compared canonical fields and values; the context selector compared slot names
 * for changes and <em>rendered strings</em> for events, so a boolean or a number
 * an event had stated outright was never suppressed there. Both now read one
 * {@link StatedFacts}, built once from the turn's projected events.</p>
 *
 * <p>Every case below is checked on the document the provider actually
 * received, not on a selector in isolation: the contract is about the request,
 * and a selector agreeing with itself is what let the two drift.</p>
 */
final class StatedFactsContractTest {

    /**
     * A boolean an event states is not repeated as context.
     *
     * <p>The case the rendered-string comparison could never catch. An arrival
     * in an undiscovered system says {@code previouslyDiscovered: false}, and a
     * body group beside it saying the same flag is the same claim twice.</p>
     */
    @Test
    void aBooleanStatedByAnEventIsNotRepeated(@TempDir Path directory) {
        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory)) {
            harness.journal(loadGame())
                    .journal(jump("10:00:01Z", 23155, "Schieni"))
                    .closeBatch()
                    .journal(arrivalStarScan("10:00:02Z", 23155, "Schieni"))
                    .closeBatch();
            PipelineTrace trace = harness.trace();

            PipelineTrace.TurnView turn = turnWith(
                    trace,
                    "SYSTEM_UNDISCOVERED_CONFIRMED"
            );
            JsonNode event = eventOfKind(turn, "SYSTEM_UNDISCOVERED_CONFIRMED");
            assertFalse(
                    event.path("previouslyDiscovered").asBoolean(true),
                    "the event states the flag: " + turn.userMessage()
            );
            assertFalse(
                    turn.context().path("body").has("previouslyDiscovered"),
                    "and the context does not repeat it: "
                            + turn.userMessage()
            );
            assertChangesAndContextPartition(trace);
        }
    }

    /**
     * A number an event states is not repeated either.
     *
     * <p>A reported signal set states the canonical counts its categories are
     * declared to carry, so the body group does not restate them — and it does
     * not suppress an unrelated field whose value happens to be the same
     * integer, which is the other half of the same rule.</p>
     */
    @Test
    void aNumberStatedByAnEventIsNotRepeated(@TempDir Path directory) {
        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory)) {
            harness.journal(loadGame())
                    .journal(jump("10:00:01Z", 23155, "Schieni"))
                    .closeBatch()
                    .journal(signals("10:00:02Z", 23155, "Schieni", 1, 2))
                    .closeBatch();
            PipelineTrace trace = harness.trace();

            PipelineTrace.TurnView turn = turnWith(
                    trace,
                    "BODY_SIGNALS_FOUND"
            );
            assertTrue(
                    eventOfKind(turn, "BODY_SIGNALS_FOUND")
                            .has("biologicalSignals"),
                    "the finding is reported: " + turn.userMessage()
            );
            assertFalse(
                    turn.context().path("body").has("biologicalSignals"),
                    "and the count it states is not repeated: "
                            + turn.userMessage()
            );
            assertFalse(
                    turn.context().path("body").has("geologicalSignals"),
                    "for either category: " + turn.userMessage()
            );
            assertChangesAndContextPartition(trace);
        }
    }

    /**
     * A string an event states under a canonical slot is not repeated.
     *
     * <p>An approach names the body it approached, and the body group would
     * name the same body under {@code name}. The pairing is declared rather
     * than found by the two values being equal.</p>
     */
    @Test
    void aStringStatedUnderASlotIsNotRepeated(@TempDir Path directory) {
        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory)) {
            harness.journal(loadGame())
                    .journal(jump("10:00:01Z", 23155, "Schieni"))
                    .closeBatch()
                    .journal(approach("10:00:02Z", 23155, "Schieni"))
                    .closeBatch();
            PipelineTrace trace = harness.trace();

            PipelineTrace.TurnView turn = turnWith(trace, "BODY_APPROACHED");
            assertEquals(
                    "Schieni 4 a",
                    eventOfKind(turn, "BODY_APPROACHED").path("body")
                            .textValue()
            );
            assertFalse(
                    turn.context().path("body").has("name"),
                    "the body is named once: " + turn.userMessage()
            );
            assertFalse(
                    turn.context().path("system").has("name"),
                    "and so is the system: " + turn.userMessage()
            );
            assertChangesAndContextPartition(trace);
        }
    }

    /**
     * A pairing declared by field holds at the value, not by the field alone.
     *
     * <p>A jump's {@code system} answers the body's name, because the arrival
     * star is named after its system. When canonical state still knows a
     * different body — the moon of the system just left — the jump has said
     * nothing about it, and the context reports it.</p>
     */
    @Test
    void aDeclaredPairingSuppressesOnlyTheValueItStated(
            @TempDir Path directory
    ) {
        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory)) {
            harness.journal(loadGame())
                    .journal(jump("10:00:01Z", 23155, "Schieni"))
                    .closeBatch()
                    .journal(approach("10:00:02Z", 23155, "Schieni"))
                    .closeBatch()
                    .journal(jumpNamingNoBody("10:00:03Z", 99001, "Elsewhere"))
                    .closeBatch();
            PipelineTrace trace = harness.trace();

            PipelineTrace.TurnView second = trace.turns().getLast();
            assertEquals(List.of("SYSTEM_JUMP"), second.eventKinds());
            assertEquals(
                    "Schieni 4 a",
                    second.context().path("body").path("name").textValue(),
                    "the canonical body is still the last system's, and the "
                            + "jump said nothing about it: "
                            + second.userMessage()
            );
            assertChangesAndContextPartition(trace);
        }
    }

    /**
     * Several events in one batch all contribute to what has been said.
     *
     * <p>The facts are gathered from every projected event of the turn, not
     * from the one that caused a change.</p>
     */
    @Test
    void everyEventOfTheBatchContributes(@TempDir Path directory) {
        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory)) {
            harness.journal(loadGame())
                    .journal(jump("10:00:01Z", 23155, "Schieni"))
                    .journal(approach("10:00:02Z", 23155, "Schieni"))
                    .journal(signals("10:00:03Z", 23155, "Schieni", 1, 2))
                    .closeBatch();
            PipelineTrace trace = harness.trace();

            PipelineTrace.TurnView turn = trace.turns().getLast();
            assertTrue(
                    turn.eventKinds().size() > 1,
                    "one turn carried several events: " + turn.eventKinds()
            );
            assertFalse(
                    turn.context().path("body").has("biologicalSignals"),
                    "a count one event states is not repeated because "
                            + "another event caused the change: "
                            + turn.userMessage()
            );
            assertFalse(
                    turn.context().path("body").has("name"),
                    "nor is the body another event named: "
                            + turn.userMessage()
            );
            assertChangesAndContextPartition(trace);
        }
    }

    /**
     * A hidden observation's change is attributed to nothing and still counts.
     *
     * <p>The context does not repeat a field a change already names, whether
     * that change belongs to one of the turn's events or to an observation the
     * model is not being shown.</p>
     */
    @Test
    void aHiddenChangeStillOccupiesItsSlot(@TempDir Path directory) {
        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory)) {
            harness.journal(loadGame())
                    .journal(jump("10:00:01Z", 23155, "Schieni"))
                    .closeBatch()
                    .journal(approach("10:00:02Z", 23155, "Schieni"))
                    .closeBatch();
            PipelineTrace trace = harness.trace();

            for (PipelineTrace.TurnView turn : trace.turns()) {
                for (String subject : List.of(
                        "system",
                        "body",
                        "navigation",
                        "commander",
                        "ship",
                        "vehicle",
                        "sampling"
                )) {
                    for (String name : changedFields(turn, subject)) {
                        assertFalse(
                                turn.context().path(subject).has(name),
                                subject + "." + name
                                        + " is a change and context at once: "
                                        + turn.userMessage()
                        );
                    }
                }
            }
            assertChangesAndContextPartition(trace);
        }
    }

    /**
     * Different subjects are not confused for one another.
     *
     * <p>A body's name and a system's name are both spelled {@code name} inside
     * their groups; the identity compared is the slot, so one never suppresses
     * the other.</p>
     */
    @Test
    void oneSubjectDoesNotSuppressAnother(@TempDir Path directory) {
        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory)) {
            harness.journal(loadGame())
                    .journal(jump("10:00:01Z", 23155, "Schieni"))
                    .closeBatch()
                    .journal(approach("10:00:02Z", 23155, "Schieni"))
                    .closeBatch()
                    .journal(touchdown("10:00:03Z", 23155, "Schieni"))
                    .closeBatch();
            PipelineTrace trace = harness.trace();

            PipelineTrace.TurnView turn = turnWith(trace, "TOUCHDOWN");
            assertEquals(
                    "Schieni",
                    turn.context().path("system").path("name").textValue(),
                    "the system is still reported: " + turn.userMessage()
            );
            assertFalse(
                    turn.context().path("body").has("name"),
                    "while the body the event named is not: "
                            + turn.userMessage()
            );
            assertChangesAndContextPartition(trace);
        }
    }

    /**
     * A fact out of scope is absent for a different reason, and stays absent.
     *
     * <p>A codex entry names no body it can be checked against, so no body
     * group is built at all. That is scope, not a statement — and the two must
     * not be confused, because a scope exclusion is not evidence that anything
     * was said.</p>
     */
    @Test
    void aFactOutOfScopeIsAbsentWithoutBeingStated(@TempDir Path directory) {
        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory)) {
            harness.journal(loadGame())
                    .journal(jump("10:00:01Z", 23155, "Schieni"))
                    .closeBatch()
                    .journal(approach("10:00:02Z", 23155, "Schieni"))
                    .closeBatch()
                    .journal(codexEntry("10:00:03Z", 23155, "Schieni"))
                    .closeBatch();
            PipelineTrace trace = harness.trace();

            PipelineTrace.TurnView turn =
                    turnWith(trace, "CODEX_ENTRY_RECORDED");
            assertFalse(
                    turn.context().has("body"),
                    "a codex entry reads no body at all: "
                            + turn.userMessage()
            );
            assertTrue(
                    turn.context().path("system").has("name")
                            || eventOfKind(turn, "CODEX_ENTRY_RECORDED")
                                    .has("system"),
                    "the system it is in is still established: "
                            + turn.userMessage()
            );
            assertChangesAndContextPartition(trace);
        }
    }

    /**
     * A sampling scan states the sequence, so the sequence is not restated.
     *
     * <p>And a presence event during the same sequence still gets it: the rule
     * is about what the turn's events say, not about the sampling group being
     * unwelcome.</p>
     */
    @Test
    void aSamplingScanStatesItsOwnSequence(@TempDir Path directory) {
        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory)) {
            harness.journal(loadGame())
                    .journal(jump("10:00:01Z", 23155, "Schieni"))
                    .closeBatch()
                    .journal(approach("10:00:02Z", 23155, "Schieni"))
                    .closeBatch()
                    .journal(touchdown("10:00:03Z", 23155, "Schieni"))
                    .closeBatch()
                    .journal(disembark("10:00:04Z", 23155, "Schieni"))
                    .closeBatch()
                    .journal(scanOrganic("10:00:05Z", 23155, "Log"))
                    .closeBatch();
            PipelineTrace trace = harness.trace();

            PipelineTrace.TurnView sampling =
                    turnWith(trace, "BIOLOGICAL_SAMPLE");
            assertFalse(
                    sampling.context().has("sampling"),
                    "the scan is the sequence's own step: "
                            + sampling.userMessage()
            );
            assertChangesAndContextPartition(trace);

            harness.journal(embark("10:00:06Z", 23155, "Schieni"))
                    .closeBatch();
            PipelineTrace after = harness.trace();
            PipelineTrace.TurnView presence = turnWith(after, "EMBARKED");
            assertTrue(
                    presence.context().has("sampling"),
                    "but a presence event during one still carries it: "
                            + presence.userMessage()
            );
        }
    }

    // ------------------------------------------------------------- fixtures

    private static PipelineTrace.TurnView turnWith(
            PipelineTrace trace,
            String kind
    ) {
        for (int index = trace.turns().size() - 1; index >= 0; index--) {
            PipelineTrace.TurnView turn = trace.turns().get(index);
            if (turn.eventKinds().contains(kind)) {
                return turn;
            }
        }
        throw new AssertionError(
                "no turn carried " + kind + "\n" + trace.describe()
        );
    }

    /**
     * The event of one kind, found by position rather than by reading a name.
     *
     * <p>The request no longer names an event Kairon's way — it carries the
     * literal description the record supplied — so the kind comes from the
     * payload the pipeline observed and the position it holds in the turn.</p>
     */
    private static JsonNode eventOfKind(
            PipelineTrace.TurnView turn,
            String kind
    ) {
        int position = turn.eventKinds().indexOf(kind);
        if (position < 0) {
            throw new AssertionError(
                    "no " + kind + " in " + turn.userMessage()
            );
        }
        return turn.events().get(position);
    }

    private static List<String> changedFields(
            PipelineTrace.TurnView turn,
            String subject
    ) {
        List<String> names = new ArrayList<>();
        turn.changes().forEach(change -> {
            if (subject.equals(change.path("subject").textValue())) {
                change.path("fields").fieldNames()
                        .forEachRemaining(names::add);
            }
        });
        return List.copyOf(names);
    }

    private static String loadGame() {
        return """
                {"timestamp":"2026-07-30T10:00:00Z","event":"LoadGame",
                 "FID":"F12345678","ShipID":9,"Ship":"explorer_nx",
                 "ShipName":"Wanderer"}
                """;
    }

    private static String jump(String time, long address, String system) {
        return "{\"timestamp\":\"2026-07-30T" + time
                + "\",\"event\":\"FSDJump\",\"StarSystem\":\"" + system
                + "\",\"SystemAddress\":" + address
                + ",\"BodyID\":0,\"Body\":\"" + system
                + "\",\"JumpDist\":8.5,\"FuelUsed\":0.4,\"FuelLevel\":30.2}";
    }

    /** A jump whose record names no body, so the last one stays canonical. */
    private static String jumpNamingNoBody(
            String time,
            long address,
            String system
    ) {
        return "{\"timestamp\":\"2026-07-30T" + time
                + "\",\"event\":\"FSDJump\",\"StarSystem\":\"" + system
                + "\",\"SystemAddress\":" + address
                + ",\"JumpDist\":8.5,\"FuelUsed\":0.4,\"FuelLevel\":30.2}";
    }

    private static String approach(String time, long address, String system) {
        return "{\"timestamp\":\"2026-07-30T" + time
                + "\",\"event\":\"ApproachBody\",\"StarSystem\":\"" + system
                + "\",\"SystemAddress\":" + address
                + ",\"Body\":\"" + system + " 4 a\",\"BodyID\":20}";
    }

    private static String touchdown(String time, long address, String system) {
        return "{\"timestamp\":\"2026-07-30T" + time
                + "\",\"event\":\"Touchdown\",\"StarSystem\":\"" + system
                + "\",\"SystemAddress\":" + address
                + ",\"Body\":\"" + system + " 4 a\",\"BodyID\":20,"
                + "\"PlayerControlled\":true,\"Latitude\":1.0,"
                + "\"Longitude\":2.0}";
    }

    private static String disembark(String time, long address, String system) {
        return "{\"timestamp\":\"2026-07-30T" + time
                + "\",\"event\":\"Disembark\",\"StarSystem\":\"" + system
                + "\",\"SystemAddress\":" + address
                + ",\"Body\":\"" + system + " 4 a\",\"BodyID\":20,"
                + "\"OnPlanet\":true,\"OnStation\":false,\"SRV\":false,"
                + "\"Taxi\":false,\"Multicrew\":false}";
    }

    private static String embark(String time, long address, String system) {
        return "{\"timestamp\":\"2026-07-30T" + time
                + "\",\"event\":\"Embark\",\"StarSystem\":\"" + system
                + "\",\"SystemAddress\":" + address
                + ",\"Body\":\"" + system + " 4 a\",\"BodyID\":20,"
                + "\"OnPlanet\":true,\"OnStation\":false,\"SRV\":false,"
                + "\"Taxi\":false,\"Multicrew\":false}";
    }

    private static String scanOrganic(
            String time,
            long address,
            String scanType
    ) {
        return "{\"timestamp\":\"2026-07-30T" + time
                + "\",\"event\":\"ScanOrganic\",\"ScanType\":\"" + scanType
                + "\",\"SystemAddress\":" + address + ",\"Body\":20,"
                + "\"Genus\":\"$Codex_Ent_Bacterial_Genus_Name;\","
                + "\"Genus_Localised\":\"Bacterium\","
                + "\"Species\":\"$Codex_Ent_Bacterial_01_Name;\","
                + "\"Species_Localised\":\"Bacterium Aurasus\","
                + "\"Variant\":\"$Codex_Ent_Bacterial_01_M_Name;\","
                + "\"Variant_Localised\":\"Bacterium Aurasus - Green\"}";
    }

    private static String codexEntry(
            String time,
            long address,
            String system
    ) {
        return "{\"timestamp\":\"2026-07-30T" + time
                + "\",\"event\":\"CodexEntry\",\"EntryID\":2100201,"
                + "\"Name\":\"$Codex_Ent_TRF_Name;\","
                + "\"Name_Localised\":\"Sudarsky Class I Gas Giant\","
                + "\"Category\":\"$Codex_Category_StellarBodies;\","
                + "\"Category_Localised\":\"Stellar bodies\","
                + "\"SubCategory\":\"$Codex_SubCategory_Gas_Giants;\","
                + "\"SubCategory_Localised\":\"Gas giants\","
                + "\"Region\":\"$Codex_RegionName_18;\","
                + "\"Region_Localised\":\"Inner Orion Spur\","
                + "\"System\":\"" + system + "\",\"SystemAddress\":"
                + address + ",\"BodyID\":0,\"IsNewEntry\":true}";
    }

    private static String arrivalStarScan(
            String time,
            long address,
            String system
    ) {
        return "{\"timestamp\":\"2026-07-30T" + time
                + "\",\"event\":\"Scan\",\"ScanType\":\"AutoScan\","
                + "\"StarSystem\":\"" + system + "\",\"SystemAddress\":"
                + address + ",\"BodyID\":0,\"BodyName\":\"" + system
                + "\",\"StarType\":\"K\",\"WasDiscovered\":false,"
                + "\"WasMapped\":false}";
    }

    private static String signals(
            String time,
            long address,
            String system,
            int biological,
            int geological
    ) {
        return "{\"timestamp\":\"2026-07-30T" + time
                + "\",\"event\":\"FSSBodySignals\",\"StarSystem\":\"" + system
                + "\",\"SystemAddress\":" + address
                + ",\"BodyID\":20,\"BodyName\":\"" + system + " 4 a\","
                + "\"Signals\":[{\"Type\":\"$SAA_SignalType_Biological;\","
                + "\"Count\":" + biological + "},"
                + "{\"Type\":\"$SAA_SignalType_Geological;\","
                + "\"Count\":" + geological + "}]}";
    }
}
