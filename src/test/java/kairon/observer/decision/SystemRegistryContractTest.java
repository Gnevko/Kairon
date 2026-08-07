package kairon.observer.decision;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.behavior.context.BodyDetail;
import kairon.observation.ObservationDraft.ObservationCaptureMode;
import kairon.system.BodyKnowledgeLevel;
import kairon.system.ParentKind;
import kairon.system.PlanetBody;
import kairon.system.StarBody;
import kairon.system.SystemObject;
import kairon.system.SystemObjectKind;
import kairon.system.SystemRegistrySnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the current-system registry holds, asserted beside what the model was
 * told.
 *
 * <p>The registry and the turn are two answers to the same observation, and a
 * defect in either is invisible from inside the other. A registry assertion
 * without a provider assertion would not notice a recording that quietly opened
 * a turn; a provider assertion without a registry assertion would not notice a
 * turn whose facts were never kept. So every test here asserts both.</p>
 */
final class SystemRegistryContractTest {

    /**
     * One scan of a moon establishes the moon, its planet and their star.
     *
     * <p>The parent chain is the journal's own, so what follows a parent in the
     * list is what that parent orbits: the planet and the star are recorded with
     * their own places and with nothing established about them, which is exactly
     * what is true of bodies nobody has scanned yet.</p>
     */
    @Test
    void aScanRecordsTheBodyItsParentsAndTheTurnThatReportedIt(
            @TempDir Path directory
    ) {
        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory)) {
            arrive(harness);
            harness.journal(MOON_SCAN).closeBatch();

            PipelineTrace trace = harness.trace();
            SystemRegistrySnapshot registry =
                    trace.finalRegistry().orElseThrow();

            assertEquals(4001L, registry.systemAddress());
            assertEquals(
                    Set.of(0L, 1L, 4L, 5L),
                    registry.objects().keySet(),
                    "the scanned moon, its planet, their star and the "
                            + "barycentre both of them orbit"
            );
            assertEquals(
                    SystemObjectKind.STAR,
                    registry.object(1).kind(),
                    "the arrival star, named by the jump and by the chain"
            );

            SystemObject moon = registry.object(5);
            assertInstanceOf(PlanetBody.class, moon);
            assertEquals(BodyKnowledgeLevel.SCANNED, moon.knowledge());
            assertEquals("Icy body", ((PlanetBody) moon).planetClass());
            assertTrue(((PlanetBody) moon).isMoon());
            assertEquals(
                    List.of(ParentKind.PLANET, ParentKind.STAR,
                            ParentKind.BARYCENTRE),
                    moon.parents().stream().map(parent -> parent.kind())
                            .toList()
            );

            SystemObject planet = registry.object(4);
            assertInstanceOf(PlanetBody.class, planet);
            assertEquals(
                    BodyKnowledgeLevel.LISTED,
                    planet.knowledge(),
                    "named in a chain, scanned by nobody"
            );
            assertNull(((PlanetBody) planet).planetClass());
            assertFalse(((PlanetBody) planet).isMoon());

            SystemObject star = registry.object(1);
            assertInstanceOf(StarBody.class, star);
            assertEquals(BodyKnowledgeLevel.LISTED, star.knowledge());
            assertEquals(
                    SystemObjectKind.BARYCENTRE,
                    registry.object(0).kind()
            );
            assertEquals(
                    1,
                    registry.scannedCount(),
                    "three of the four are known to be there and no more"
            );

            assertEquals(
                    List.of("SYSTEM_JUMP", "BODY_SCANNED"),
                    trace.modelFacingKinds(),
                    "and the scan really did reach the model"
            );
        }
    }

    /**
     * A visit is one look at one system, and the registry lasts exactly that
     * long.
     */
    @Test
    void leavingTheSystemEmptiesTheRegistry(@TempDir Path directory) {
        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory)) {
            arrive(harness);
            harness.journal(MOON_SCAN).closeBatch();
            harness.journal("""
                    {"timestamp":"2026-07-30T10:05:00Z","event":"FSDJump",
                     "StarSystem":"Survey Beta","SystemAddress":4002,
                     "Body":"Survey Beta A","BodyID":1,"BodyType":"Star",
                     "JumpDist":12.4,"FuelUsed":0.9,"FuelLevel":27.1}
                    """).closeBatch();

            PipelineTrace trace = harness.trace();
            SystemRegistrySnapshot registry =
                    trace.finalRegistry().orElseThrow();

            assertEquals(4002L, registry.systemAddress());
            assertEquals(
                    Set.of(1L),
                    registry.objects().keySet(),
                    "nothing of the previous system survives the jump, and "
                            + "the only thing in the new one is the star the "
                            + "jump itself named"
            );
            assertEquals(
                    SystemObjectKind.STAR,
                    registry.object(1).kind(),
                    "which the jump also said is a star"
            );
            assertEquals(
                    List.of("SYSTEM_JUMP", "BODY_SCANNED", "SYSTEM_JUMP"),
                    trace.modelFacingKinds()
            );
        }
    }

    /**
     * A historical reading is recorded and is never news.
     *
     * <p>Being known and being news are different questions. Canonical state is
     * already built from bootstrap capture, and so is this — which is what lets
     * a Kairon started in the middle of a session know what is already
     * established. What bootstrap must not do is open a turn about it.</p>
     */
    @Test
    void aBootstrapScanIsRecordedAndNeverReachesTheModel(
            @TempDir Path directory
    ) {
        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory)) {
            harness.journal(ObservationCaptureMode.BOOTSTRAP, LOAD_GAME)
                    .journal(ObservationCaptureMode.BOOTSTRAP, ARRIVAL)
                    .journal(ObservationCaptureMode.BOOTSTRAP, MOON_SCAN)
                    .closeBatch();

            PipelineTrace trace = harness.trace();
            SystemObject moon =
                    trace.finalRegistry().orElseThrow().object(5);

            assertNotNull(moon, "the body is known");
            assertEquals(BodyKnowledgeLevel.SCANNED, moon.knowledge());
            assertEquals(
                    0,
                    trace.providerCalls(),
                    "and nothing about it was news"
            );
        }
    }

    /**
     * The survey names what grows there and a finished sequence marks one of
     * them collected.
     *
     * <p>The two facts come from records that never meet: only
     * {@code SAASignalsFound} names the genera, and only {@code ScanOrganic}
     * says a sequence finished. Keeping them is the whole reason the registry
     * can answer what is left.</p>
     */
    @Test
    void theSurveyNamesTheGeneraAndSamplingMarksOneCollected(
            @TempDir Path directory
    ) {
        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory)) {
            arrive(harness);
            harness.journal(MOON_SCAN).closeBatch();
            harness.journal("""
                    {"timestamp":"2026-07-30T10:02:00Z",
                     "event":"FSSBodySignals","SystemAddress":4001,"BodyID":5,
                     "BodyName":"Survey Alpha A 2 a",
                     "Signals":[{"Type":"$SAA_SignalType_Biological;",
                                 "Count":3}]}
                    """).closeBatch();
            harness.journal("""
                    {"timestamp":"2026-07-30T10:03:00Z",
                     "event":"SAASignalsFound","SystemAddress":4001,"BodyID":5,
                     "BodyName":"Survey Alpha A 2 a",
                     "Signals":[{"Type":"$SAA_SignalType_Biological;",
                                 "Count":3}],
                     "Genuses":[
                       {"Genus":"$Codex_Ent_Bacterial_Genus_Name;",
                        "Genus_Localised":"Bacterium"},
                       {"Genus":"$Codex_Ent_Tussocks_Genus_Name;",
                        "Genus_Localised":"Tussock"},
                       {"Genus":"$Codex_Ent_Fonticulus_Genus_Name;",
                        "Genus_Localised":"Fonticulua"}]}
                    """).closeBatch();
            harness.journal("""
                    {"timestamp":"2026-07-30T10:04:00Z","event":"ScanOrganic",
                     "ScanType":"Analyse",
                     "Genus":"$Codex_Ent_Bacterial_Genus_Name;",
                     "Genus_Localised":"Bacterium",
                     "Species":"$Codex_Ent_Bacterial_05_Name;",
                     "Variant":"$Codex_Ent_Bacterial_05_A_Name;",
                     "SystemAddress":4001,"Body":5}
                    """).closeBatch();

            PipelineTrace trace = harness.trace();
            SystemObject moon =
                    trace.finalRegistry().orElseThrow().object(5);

            assertEquals(
                    Set.of(
                            "$Codex_Ent_Bacterial_Genus_Name;",
                            "$Codex_Ent_Fonticulus_Genus_Name;",
                            "$Codex_Ent_Tussocks_Genus_Name;"
                    ),
                    moon.biology().genera().keySet(),
                    "the survey is the only record that names them"
            );
            assertEquals(
                    Set.of("$Codex_Ent_Bacterial_Genus_Name;"),
                    moon.biology().completed()
            );
            assertEquals(
                    Set.of(
                            "$Codex_Ent_Fonticulus_Genus_Name;",
                            "$Codex_Ent_Tussocks_Genus_Name;"
                    ),
                    moon.biology().remaining()
            );
            assertEquals(
                    BodyKnowledgeLevel.MAPPED,
                    moon.knowledge(),
                    "a completed survey is the top of the ladder"
            );
            assertEquals(
                    3,
                    moon.profile().signalCounts().get("BIOLOGICAL"),
                    "and the count the system scanner reported is kept"
            );

            assertTrue(
                    trace.modelFacingKinds().contains("BODY_SIGNALS_FOUND"),
                    "the finding reached the model"
            );
            assertTrue(
                    trace.modelFacingKinds().contains("BIOLOGICAL_SAMPLE"),
                    "and so did the completed sequence"
            );
        }
    }

    /**
     * The instrument that names what it found says what it found.
     *
     * <p>Firing probes at a body is how the Commander learns which organisms are
     * down there, and the reading carries their names. The system scanner counts
     * signals from across the system and names nothing, so the same kind of event
     * lists nothing there — which is why this is declared per instrument rather
     * than per kind. The names are the words in the genus identities, the same
     * spelling {@code context.biology} uses, so one organism reads as one
     * organism wherever the document mentions it.</p>
     */
    @Test
    void aSurfaceScanListsTheOrganismsItNamed(@TempDir Path directory) {
        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory)) {
            arrive(harness);
            harness.journal(MOON_SCAN).closeBatch();
            harness.journal(SAA_SIGNALS).closeBatch();

            PipelineTrace trace = harness.trace();
            PipelineTrace.TurnView turn = trace.turns().getLast();
            JsonNode found = turn.events().get(0);

            assertEquals(List.of("BODY_SIGNALS_FOUND"), turn.eventKinds());
            assertEquals(
                    List.of("Bacterial", "Fonticulus", "Tussocks"),
                    values(found.path("organisms")),
                    "the probes named three, and all three are listed: "
                            + turn.userMessage()
            );
            assertEquals(
                    3,
                    found.path("biologicalSignals").intValue(),
                    "beside the count, which is a different fact"
            );
        }

        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory.resolve("fss"))) {
            arrive(harness);
            harness.journal(MOON_SCAN).closeBatch();
            harness.journal(FSS_SIGNALS).closeBatch();

            PipelineTrace trace = harness.trace();
            PipelineTrace.TurnView turn = trace.turns().getLast();
            JsonNode found = turn.events().get(0);

            assertEquals(List.of("BODY_SIGNALS_FOUND"), turn.eventKinds());
            assertTrue(
                    found.path("organisms").isMissingNode(),
                    "the system scanner named nothing, so it lists nothing: "
                            + turn.userMessage()
            );
        }
    }

    private static List<String> values(JsonNode array) {
        List<String> values = new java.util.ArrayList<>();
        array.forEach(value -> values.add(value.textValue()));
        return List.copyOf(values);
    }

    /**
     * What grows here reaches the model, and what has been collected of it.
     *
     * <p>The turn that names the genera is not the turn that reports them: the
     * survey restated counts the system scanner already gave, so it opened no
     * turn at all. Everything the model is told about the organisms therefore
     * comes from the registry, which is the reason this section exists.</p>
     */
    @Test
    void theBodysOrganismsReachTheModelBesideWhatIsCollected(
            @TempDir Path directory
    ) {
        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory)) {
            surveyAndLand(harness);
            harness.journal(ANALYSED).closeBatch();

            PipelineTrace trace = harness.trace();
            PipelineTrace.TurnView completion = trace.turns().getLast();

            assertEquals(
                    List.of("BIOLOGICAL_SAMPLE"),
                    completion.eventKinds(),
                    "the turn is the finished sequence"
            );
            JsonNode biology = completion.context().path("biology");
            assertEquals(
                    "COLLECTED",
                    biology.path("Bacterial").textValue()
            );
            assertEquals(
                    "NOT_COLLECTED",
                    biology.path("Tussocks").textValue()
            );
            assertEquals(
                    "NOT_COLLECTED",
                    biology.path("Fonticulus").textValue(),
                    "and what is still out there is named beside it"
            );
            assertEquals(
                    Set.of("$Codex_Ent_Bacterial_Genus_Name;"),
                    trace.finalRegistry().orElseThrow().object(5)
                            .biology().completed(),
                    "which is what the registry recorded"
            );
        }
    }

    /**
     * A count of signals is not a list of organisms.
     *
     * <p>Before the surface survey the game says how many biological signals a
     * body carries and never which. A group naming nothing would be an empty
     * object; a group listing three unnamed organisms would be an invention.</p>
     */
    @Test
    void signalsWithoutASurveyCarryNoBiologyAtAll(@TempDir Path directory) {
        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory)) {
            arrive(harness);
            harness.journal(MOON_SCAN).closeBatch();
            harness.journal(FSS_SIGNALS).closeBatch();
            harness.journal(APPROACH).closeBatch();
            harness.journal(SUPERCRUISE_EXIT).closeBatch();

            PipelineTrace trace = harness.trace();
            PipelineTrace.TurnView arrival = trace.turns().getLast();

            assertTrue(
                    arrival.context().path("body").has("biologicalSignals"),
                    "the count is standing background and still reaches it"
            );
            assertTrue(
                    arrival.context().path("biology").isMissingNode(),
                    "but nothing names an organism yet"
            );
        }
    }

    /**
     * A genus the game has no word for is not spelled as its own symbol.
     *
     * <p>{@code $Codex_Ent_…} is the game's internal identifier. It is what the
     * registry compares readings on and it is not a name anything shows — the
     * same rule every other model-facing label obeys. The organism is still
     * recorded; it simply cannot be named.</p>
     */
    @Test
    void anUnnamedGenusIsRecordedAndNotSpelledOut(@TempDir Path directory) {
        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory)) {
            arrive(harness);
            harness.journal(MOON_SCAN).closeBatch();
            harness.journal("""
                    {"timestamp":"2026-07-30T10:03:00Z",
                     "event":"SAASignalsFound","SystemAddress":4001,"BodyID":5,
                     "BodyName":"Survey Alpha A 2 a",
                     "Signals":[{"Type":"$SAA_SignalType_Biological;",
                                 "Count":2}],
                     "Genuses":[
                       {"Genus":"$Codex_Ent_Tussocks_Genus_Name;",
                        "Genus_Localised":"Tussock"},
                       {"Genus":"$Codex_Ent_Unresearched_Genus_Name;"}]}
                    """).closeBatch();
            harness.journal(APPROACH).closeBatch();
            harness.journal(SUPERCRUISE_EXIT).closeBatch();
            // The inventory travels with the analysis that finishes a sample
            // and with nothing else, so that is the turn to read it off.
            harness.journal(ANALYSED).closeBatch();

            PipelineTrace trace = harness.trace();
            JsonNode biology =
                    trace.turns().getLast().context().path("biology");

            assertEquals(
                    List.of("Tussocks"),
                    fieldNames(biology),
                    "only the organism the game has a word for, named by the "
                            + "word in its own identity"
            );
            assertEquals(
                    Set.of(
                            "$Codex_Ent_Tussocks_Genus_Name;",
                            "$Codex_Ent_Unresearched_Genus_Name;"
                    ),
                    trace.finalRegistry().orElseThrow().object(5)
                            .biology().genera().keySet(),
                    "and both are recorded all the same"
            );
        }
    }

    /** A reading of another system is not a reading of this one. */
    @Test
    void aRecordAboutAnotherSystemChangesNothing(@TempDir Path directory) {
        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory)) {
            arrive(harness);
            harness.journal("""
                    {"timestamp":"2026-07-30T10:02:00Z","event":"Scan",
                     "ScanType":"Detailed","BodyName":"Elsewhere 1","BodyID":3,
                     "Parents":[{"Star":0}],"StarSystem":"Elsewhere",
                     "SystemAddress":9999,"PlanetClass":"Rocky body",
                     "WasDiscovered":false,"WasMapped":false}
                    """).closeBatch();

            PipelineTrace trace = harness.trace();
            SystemRegistrySnapshot registry =
                    trace.finalRegistry().orElseThrow();

            assertEquals(4001L, registry.systemAddress());
            assertEquals(
                    Set.of(1L),
                    registry.objects().keySet(),
                    "only the arrival star this visit named; a reading filed "
                            + "under the wrong system is worse than a reading "
                            + "dropped"
            );
            assertEquals(
                    List.of("SYSTEM_JUMP", "BODY_SCANNED"),
                    trace.modelFacingKinds(),
                    "the observer still reports it; only the registry "
                            + "declines to file it here"
            );
        }
    }

    /**
     * One source for what a body is, read by the model and by the graph.
     *
     * <p>The registry holds the class, the landability and the distance; the
     * turn reports them as {@code context.body}; the graph's own context is
     * built from the same answer. Canonical state answers which body the ship
     * is at and nothing about it, so none of this can arrive as a
     * <em>change</em> — a body Kairon flew to is not a body that changed.</p>
     *
     * <p>Every layer at once on purpose. Held in two places these three facts
     * disagreed silently: canonical state kept them per body and served them
     * again whenever a body was reselected, which is the shape the removed
     * value-origin flag existed to explain away.</p>
     */
    @Test
    void oneSourceAnswersWhatTheBodyIsForTheModelAndTheGraph(
            @TempDir Path directory
    ) {
        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory)) {
            arrive(harness);
            harness.journal(MOON_SCAN).closeBatch();
            harness.journal(APPROACH).closeBatch();

            PipelineTrace trace = harness.trace();
            SystemObject moon =
                    trace.finalRegistry().orElseThrow().object(5);
            assertEquals("Icy body", ((PlanetBody) moon).planetClass());

            PipelineTrace.TurnView turn = trace.turns().getLast();
            assertEquals(List.of("BODY_APPROACHED"), turn.eventKinds());
            JsonNode body = turn.context().path("body");
            assertEquals("PLANET", body.path("type").textValue());
            assertEquals(
                    "Icy body",
                    body.path("planetClass").textValue(),
                    "what the model is told is what the registry holds: "
                            + turn.userMessage()
            );
            assertTrue(body.path("landable").booleanValue());
            assertFalse(
                    body.has("distanceFromArrivalLs"),
                    "the arrival distance is not model-facing"
            );

            BodyDetail graphSees = trace.finalBody(4001L, 5L);
            assertEquals("Icy body", graphSees.planetClass());
            assertEquals(Boolean.TRUE, graphSees.landable());
            assertEquals(476.481077, graphSees.distanceFromArrivalLs());

            assertEquals(
                    5L,
                    trace.finalState().orElseThrow().bodyId(),
                    "canonical state answers which body, and only that"
            );
            assertFalse(
                    turn.userMessage().contains("\"changes\""),
                    "what is standing background is never a change: "
                            + turn.userMessage()
            );
        }
    }

    /**
     * How much of the system has been read reaches the model, and only as a
     * fraction.
     *
     * <p>A measured run had Kairon call the eleventh body of a system "the
     * first planet discovered here", and nothing in the request could
     * contradict it. The registry counts what it holds; the discovery scan
     * states the total; the model is now told both.</p>
     *
     * <p>Both or neither, which is what the first half asserts. Before the
     * discovery scan there is no total, and the arrival star's own milestone
     * turn would otherwise carry {@code scannedCount: 1} — the reading that
     * turn is about, handed back to it as background.</p>
     */
    @Test
    void howMuchOfTheSystemIsReadReachesTheModelOnlyAgainstATotal(
            @TempDir Path directory
    ) {
        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory)) {
            arrive(harness);
            harness.journal(ARRIVAL_STAR).closeBatch();

            PipelineTrace.TurnView milestone =
                    harness.trace().turns().getLast();
            assertEquals(
                    List.of("SYSTEM_UNDISCOVERED_CONFIRMED"),
                    milestone.eventKinds()
            );
            assertFalse(
                    milestone.userMessage().contains("scannedCount"),
                    "no total has been stated, so there is no progress to "
                            + "report: " + milestone.userMessage()
            );
            assertFalse(milestone.userMessage().contains("bodyCount"));

            harness.journal(DISCOVERY_SCAN).closeBatch();
            harness.journal(MOON_SCAN).closeBatch();
            harness.journal(APPROACH).closeBatch();

            PipelineTrace trace = harness.trace();
            SystemRegistrySnapshot registry =
                    trace.finalRegistry().orElseThrow();
            assertEquals(6, registry.bodyCount());
            assertEquals(
                    2,
                    registry.scannedBodyCount(),
                    "the arrival star and the scanned moon; the planet and "
                            + "the barycentre their chain named are listed, "
                            + "not read"
            );

            JsonNode system = trace.turns().getLast().context().path("system");
            assertEquals(6, system.path("bodyCount").intValue());
            assertEquals(
                    2,
                    system.path("scannedCount").intValue(),
                    "what the model is told is what the registry counted: "
                            + trace.turns().getLast().userMessage()
            );
        }
    }

    // ------------------------------------------------------------- fixtures

    /**
     * The arrival star, reported as nobody's discovery.
     *
     * <p>A shallower-than-detailed star reading whose {@code WasDiscovered} is
     * explicitly false, which is the shape the parser reads as the milestone.
     * </p>
     */
    private static final String ARRIVAL_STAR = """
            {"timestamp":"2026-07-30T10:00:20Z","event":"Scan",
             "ScanType":"AutoScan","BodyName":"Survey Alpha A","BodyID":1,
             "StarSystem":"Survey Alpha","SystemAddress":4001,
             "StarType":"K","Subclass":4,"WasDiscovered":false,
             "WasMapped":false,"DistanceFromArrivalLS":0.0}
            """;

    /** The honk: the one record that states how many bodies there are. */
    private static final String DISCOVERY_SCAN = """
            {"timestamp":"2026-07-30T10:00:30Z","event":"FSSDiscoveryScan",
             "Progress":1.0,"BodyCount":6,"NonBodyCount":2,
             "SystemName":"Survey Alpha","SystemAddress":4001}
            """;

    private static final String LOAD_GAME = """
            {"timestamp":"2026-07-30T10:00:00Z","event":"LoadGame",
             "FID":"F12345678","ShipID":9,"Ship":"explorer_nx",
             "ShipName":"Wanderer"}
            """;

    private static final String ARRIVAL = """
            {"timestamp":"2026-07-30T10:00:01Z","event":"FSDJump",
             "StarSystem":"Survey Alpha","SystemAddress":4001,
             "Body":"Survey Alpha A","BodyID":1,"BodyType":"Star",
             "JumpDist":8.5,"FuelUsed":0.4,"FuelLevel":30.2}
            """;

    /**
     * A moon three links deep: its planet, their star, and the barycentre the
     * star itself orbits.
     */
    private static final String MOON_SCAN = """
            {"timestamp":"2026-07-30T10:01:00Z","event":"Scan",
             "ScanType":"Detailed","BodyName":"Survey Alpha A 2 a","BodyID":5,
             "Parents":[{"Planet":4},{"Star":1},{"Null":0}],
             "StarSystem":"Survey Alpha","SystemAddress":4001,
             "PlanetClass":"Icy body","Landable":true,"TidalLock":true,
             "SurfaceGravity":1.065823,"SurfaceTemperature":48.9,
             "DistanceFromArrivalLS":476.481077,
             "WasDiscovered":false,"WasMapped":false}
            """;

    private static final String FSS_SIGNALS = """
            {"timestamp":"2026-07-30T10:02:00Z","event":"FSSBodySignals",
             "SystemAddress":4001,"BodyID":5,
             "BodyName":"Survey Alpha A 2 a",
             "Signals":[{"Type":"$SAA_SignalType_Biological;","Count":3}]}
            """;

    private static final String SAA_SIGNALS = """
            {"timestamp":"2026-07-30T10:03:00Z","event":"SAASignalsFound",
             "SystemAddress":4001,"BodyID":5,
             "BodyName":"Survey Alpha A 2 a",
             "Signals":[{"Type":"$SAA_SignalType_Biological;","Count":3}],
             "Genuses":[
               {"Genus":"$Codex_Ent_Bacterial_Genus_Name;",
                "Genus_Localised":"Bacterium"},
               {"Genus":"$Codex_Ent_Tussocks_Genus_Name;",
                "Genus_Localised":"Tussock"},
               {"Genus":"$Codex_Ent_Fonticulus_Genus_Name;",
                "Genus_Localised":"Fonticulua"}]}
            """;

    private static final String APPROACH = """
            {"timestamp":"2026-07-30T10:03:30Z","event":"ApproachBody",
             "StarSystem":"Survey Alpha","SystemAddress":4001,
             "Body":"Survey Alpha A 2 a","BodyID":5}
            """;

    private static final String SUPERCRUISE_EXIT = """
            {"timestamp":"2026-07-30T10:03:40Z","event":"SupercruiseExit",
             "StarSystem":"Survey Alpha","SystemAddress":4001,
             "Body":"Survey Alpha A 2 a","BodyID":5,"BodyType":"Planet"}
            """;

    private static final String ANALYSED = """
            {"timestamp":"2026-07-30T10:04:00Z","event":"ScanOrganic",
             "ScanType":"Analyse",
             "Genus":"$Codex_Ent_Bacterial_Genus_Name;",
             "Genus_Localised":"Bacterium",
             "Species":"$Codex_Ent_Bacterial_05_Name;",
             "Variant":"$Codex_Ent_Bacterial_05_A_Name;",
             "SystemAddress":4001,"Body":5}
            """;

    private static void arrive(SemanticPipelineHarness harness) {
        harness.journal(LOAD_GAME).journal(ARRIVAL).closeBatch();
    }

    /** Arrival, the body scanned and surveyed, and the ship put down on it. */
    private static void surveyAndLand(SemanticPipelineHarness harness) {
        arrive(harness);
        harness.journal(MOON_SCAN).closeBatch();
        harness.journal(FSS_SIGNALS).closeBatch();
        harness.journal(SAA_SIGNALS).closeBatch();
        harness.journal(APPROACH).closeBatch();
        harness.journal(SUPERCRUISE_EXIT).closeBatch();
    }

    private static List<String> fieldNames(JsonNode object) {
        List<String> names = new java.util.ArrayList<>();
        object.fieldNames().forEachRemaining(names::add);
        return List.copyOf(names);
    }
}
