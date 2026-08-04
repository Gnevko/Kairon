package kairon.observer.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kairon.projection.ProjectedObservation;
import kairon.semantics.SemanticField;
import kairon.semantics.SemanticValue;
import kairon.state.CommanderLocationMode;
import kairon.state.CurrentGameStateSemantics;
import kairon.state.CurrentGameStateSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A sequence in progress is still in progress when the Commander gets out.
 *
 * <p>Sampling is the one mechanism that spans other events. A Commander logs a
 * plant, drives back to the ship, moves and lands again — and by the time the
 * next disembark arrives, the three remembered predecessors no longer reach
 * back to the scan that started it. The events say nothing about it either,
 * because getting out of a vehicle is not a sampling event. So the situation is
 * asked.</p>
 *
 * <p>Asked in the tense a standing fact belongs in. An event says what it just
 * did — {@code stage: START} is <em>this scan started it</em> — and the context
 * says where the sequence has got to: {@code STARTED}, {@code IN_PROGRESS}.
 * Two vocabularies, deliberately, so one cannot be read as the other. There is
 * no third: finishing a sequence clears it, and a cleared sequence is absent
 * rather than described as finished.</p>
 *
 * <p>And not asked at all by the scan itself. Two vocabularies exist so that a
 * standing fact cannot be read as an event; putting both in one turn about one
 * scan is the reading they were separated to prevent.</p>
 */
final class DecisionSamplingContextTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String ORGANISM = "Bacterium Bullaris - Red";

    private final LlmDecisionRequestFactory factory =
            new LlmDecisionRequestFactory();
    private final JacksonDecisionRequestSerializer serializer =
            new JacksonDecisionRequestSerializer();

    // ------------------------------------------------ A: disembark at START

    @Test
    void aDisembarkDuringAStartedSequenceCarriesIt() {
        DecisionTurnFixture fixture = sampling("Log");
        JsonNode sampling = samplingGroup(request(fixture, disembark()));

        assertEquals(List.of("organism", "stage"), propertyNames(sampling));
        assertEquals(ORGANISM, sampling.path("organism").textValue());
        assertEquals("STARTED", sampling.path("stage").textValue());
    }

    /** The whole request, so the new group is placed rather than just present. */
    @Test
    void theDisembarkKeepsEverythingElseItAlreadyCarried() {
        DecisionTurnFixture fixture = sampling("Log");
        JsonNode request = request(fixture, disembark());

        assertEquals(
                List.of("commander", "vehicle", "sampling"),
                propertyNames(request.path("context"))
        );
        assertEquals(
                "ON_FOOT",
                request.path("context").path("commander")
                        .path("presence").textValue()
        );
        assertEquals(
                "SRV",
                request.path("context").path("vehicle").path("kind").textValue()
        );
        assertEquals(
                "DISEMBARKED",
                request.path("events").get(0).path("kind").textValue()
        );
    }

    // --------------------------------------------- B: disembark at PROGRESS

    @Test
    void aDisembarkDuringARunningSequenceSaysItIsUnderway() {
        DecisionTurnFixture fixture = sampling("Log", "Sample");
        String serialized = serialize(fixture, disembark());
        JsonNode sampling = samplingGroup(read(serialized));

        assertEquals(List.of("organism", "stage"), propertyNames(sampling));
        assertEquals(ORGANISM, sampling.path("organism").textValue());
        assertEquals("IN_PROGRESS", sampling.path("stage").textValue());
        assertEquals(
                """
                {"events":[{"id":1,"kind":"DISEMBARKED",\
                "system":"Schieni GG-A c3-84",\
                "body":"Schieni GG-A c3-84 4 a",\
                "onStation":false,"onPlanet":true}],\
                "context":{"commander":{"presence":"ON_FOOT"},\
                "vehicle":{"kind":"SRV"},\
                "sampling":{"organism":"Bacterium Bullaris - Red",\
                "stage":"IN_PROGRESS"}}}""",
                serialized
        );
    }

    // ------------------------------------------------------- C: embark back

    /** Getting back into the SRV does not hide the sequence. */
    @Test
    void anEmbarkDuringARunningSequenceCarriesItToo() {
        DecisionTurnFixture fixture = sampling("Log", "Sample");
        JsonNode request = request(fixture, """
                {"timestamp":"2026-07-30T10:02:00Z","event":"Embark",
                 "SRV":true,"ID":10,"StarSystem":"Schieni GG-A c3-84",
                 "SystemAddress":23155,"Body":"Schieni GG-A c3-84 4 a",
                 "BodyID":20,"OnStation":false,"OnPlanet":true}
                """);

        assertEquals(
                "EMBARKED",
                request.path("events").get(0).path("kind").textValue()
        );
        JsonNode sampling = samplingGroup(request);
        assertEquals(ORGANISM, sampling.path("organism").textValue());
        assertEquals("IN_PROGRESS", sampling.path("stage").textValue());
    }

    // --------------------------------------------------- D: dropship deploy

    /**
     * The mechanism-wide consequence, stated rather than tidied away.
     *
     * <p>Deploying from a shuttle shares the mechanism, so it inherits the
     * question. Suppressing it here would be a special case whose only argument
     * is that the combination looks unusual — and when the process is not
     * running the group is absent anyway, by the same rule as everywhere else.
     * </p>
     */
    @Test
    void aDropshipDeploymentInheritsTheSameQuestion() {
        JsonNode running = request(sampling("Log"), DROPSHIP);
        assertEquals(
                "DROPSHIP_DEPLOYED",
                running.path("events").get(0).path("kind").textValue()
        );
        assertEquals("STARTED", samplingGroup(running).path("stage").textValue());

        JsonNode idle = request(new DecisionTurnFixture(), DROPSHIP);
        assertFalse(idle.path("context").has("sampling"));
    }

    // ------------------------------------------------- E: nothing to report

    @Test
    void aPresenceEventWithNoSequenceCarriesNoSamplingGroup() {
        JsonNode request = request(new DecisionTurnFixture(), disembark());

        assertFalse(request.path("context").has("sampling"));
        assertFalse(request.toString().contains("sampling"));
        assertFalse(request.toString().contains("{}"));
    }

    // ------------------------------------------------------ F: it is over

    /** A finished sequence is absent, not reported as finished. */
    @Test
    void aCompletedSequenceLeavesNothingStanding() {
        DecisionTurnFixture fixture = sampling("Log", "Sample", "Analyse");
        String request = serialize(fixture, disembark());

        assertFalse(read(request).path("context").has("sampling"));
        assertFalse(request.contains("FINAL"));
        assertFalse(request.contains("COMPLETED"));
        assertFalse(request.contains("\"active\""));
    }

    // ------------------------------------------------- G and H: two tenses

    /**
     * A scan reports the sequence; the situation does not report it again.
     *
     * <p>The event carries the organism, the position it just reached and
     * whether that finished it, which is the whole of what the group would say —
     * in a second vocabulary, so {@code stage: PROGRESS} sat beside
     * {@code stage: IN_PROGRESS} and a reader had to work out they were one
     * position.</p>
     */
    @Test
    void aSamplingEventCarriesNoStandingDescriptionOfItsOwnSequence() {
        DecisionTurnFixture fixture = new DecisionTurnFixture();

        JsonNode started = request(fixture, scanOrganic("Log", 0));
        JsonNode event = started.path("events").get(0);
        assertEquals("BIOLOGICAL_SAMPLE", event.path("kind").textValue());
        assertEquals(ORGANISM, event.path("organism").textValue());
        assertEquals("START", event.path("stage").textValue());
        assertFalse(event.path("complete").booleanValue());
        assertFalse(event.has("step"));
        assertFalse(
                started.path("context").has("sampling"),
                "the event just said all of it: " + started
        );

        JsonNode advanced = request(fixture, scanOrganic("Sample", 1));
        assertEquals(
                "PROGRESS",
                advanced.path("events").get(0).path("stage").textValue()
        );
        assertFalse(
                advanced.path("context").has("sampling"),
                "and again at the second scan: " + advanced
        );

        JsonNode finished = request(fixture, scanOrganic("Analyse", 2));
        assertEquals(
                "FINAL",
                finished.path("events").get(0).path("stage").textValue()
        );
        assertTrue(
                finished.path("events").get(0).path("complete").booleanValue()
        );
        assertFalse(
                finished.path("context").has("sampling"),
                "completing it clears it, so there was never anything to send"
        );
    }

    /**
     * The whole document at each stage, so the group is absent rather than
     * merely unasserted.
     *
     * <p>{@code context.body} and {@code context.commander} are what the
     * mechanism asks for besides the sequence, and both stay: where the
     * Commander is standing and what is known about the body are not things the
     * scan says.</p>
     */
    @Test
    void theSamplingTurnKeepsTheBodyAndTheCommanderItAlsoAsksFor() {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        fixture.inputs(List.of(fixture.graphDisabled(disembark())));
        String started = serialize(fixture, scanOrganic("Log", 0));

        assertEquals(
                """
                {"events":[{"id":1,"kind":"BIOLOGICAL_SAMPLE",\
                "organism":"Bacterium Bullaris - Red",\
                "stage":"START","complete":false}],\
                "context":{"body":{"name":"Schieni GG-A c3-84 4 a"},\
                "commander":{"presence":"ON_FOOT"}}}""",
                started
        );

        String advanced = serialize(fixture, scanOrganic("Sample", 1));
        assertEquals(
                """
                {"events":[{"id":1,"kind":"BIOLOGICAL_SAMPLE",\
                "organism":"Bacterium Bullaris - Red",\
                "stage":"PROGRESS","complete":false}],\
                "context":{"body":{"name":"Schieni GG-A c3-84 4 a"},\
                "commander":{"presence":"ON_FOOT"}}}""",
                advanced
        );
    }

    /**
     * Between the stages it is still carried, which is why the group exists.
     *
     * <p>Getting out and getting back in are exactly the moves a Commander
     * makes in the middle of a sequence, and neither event says a sequence is
     * running. Suppressing the group on the scans must not cost the case it was
     * built for.</p>
     */
    @Test
    void aPresenceEventBetweenTwoScansStillCarriesTheSequence() {
        DecisionTurnFixture fixture = sampling("Log");

        JsonNode betweenStages = request(fixture, disembark());
        assertEquals(
                "DISEMBARKED",
                betweenStages.path("events").get(0).path("kind").textValue()
        );
        assertEquals(
                "STARTED",
                samplingGroup(betweenStages).path("stage").textValue(),
                "the sequence outlives the events that are not about it"
        );

        // The sequence advances, and the ride back still carries it.
        fixture.inputs(List.of(
                fixture.graphDisabled(scanOrganic("Sample", 1))
        ));
        JsonNode embarked = request(fixture, """
                {"timestamp":"2026-07-30T10:03:00Z","event":"Embark",
                 "SRV":true,"ID":10,"StarSystem":"Schieni GG-A c3-84",
                 "SystemAddress":23155,"Body":"Schieni GG-A c3-84 4 a",
                 "BodyID":20,"OnStation":false,"OnPlanet":true}
                """);
        assertEquals(
                "EMBARKED",
                embarked.path("events").get(0).path("kind").textValue()
        );
        assertEquals(
                "IN_PROGRESS",
                samplingGroup(embarked).path("stage").textValue()
        );
        assertEquals(
                ORGANISM,
                samplingGroup(embarked).path("organism").textValue()
        );
    }

    /** No canonical stage name ever reaches the model as standing state. */
    @Test
    void theContextNeverSpeaksInTheEventsTense() {
        for (JsonNode request : List.of(
                request(sampling("Log"), disembark()),
                request(sampling("Log", "Sample"), disembark())
        )) {
            String stage = samplingGroup(request).path("stage").textValue();
            assertTrue(
                    List.of("STARTED", "IN_PROGRESS").contains(stage),
                    stage
            );
        }
        assertEquals(
                SemanticValue.unknown(),
                DecisionNames.samplingContextStage(
                        SemanticValue.ofSymbol("FINAL")
                ),
                "there is no standing state for a sequence that ended"
        );
        assertEquals(
                SemanticValue.unknown(),
                DecisionNames.samplingContextStage(SemanticValue.unknown())
        );
        assertEquals(
                SemanticValue.ofSymbol("STARTED"),
                DecisionNames.samplingContextStage(
                        SemanticValue.ofSymbol("START")
                )
        );
        assertEquals(
                SemanticValue.ofSymbol("IN_PROGRESS"),
                DecisionNames.samplingContextStage(
                        SemanticValue.ofSymbol("PROGRESS")
                )
        );
    }

    /** An identified sequence with no speakable label sends no organism. */
    @Test
    void anUnnamedOrganismIsOmittedRatherThanGuessed() {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        fixture.inputs(List.of(fixture.graphDisabled("""
                {"timestamp":"2026-07-30T10:00:00Z","event":"ScanOrganic",
                 "ScanType":"Log","SystemAddress":23155,"Body":20}
                """)));
        String request = serialize(fixture, disembark());

        assertEquals(
                List.of("stage"),
                propertyNames(samplingGroup(read(request)))
        );
        assertFalse(request.contains("Codex"));
        assertFalse(request.contains("\"organism\":\"\""));
    }

    // ------------------------------------------------------- the whole path

    /**
     * The audited sequence, against the real graph and the real projector.
     *
     * <p>Two rides and a landing separate the second disembark from the scan
     * that started the sequence, which is exactly long enough for the three
     * remembered predecessors to lose it. The forecast is untouched: it is what
     * followed the last disembark, and it still is.</p>
     */
    @Test
    void theSecondDisembarkOfARunningSequenceSaysSoWhole(
            @TempDir Path directory
    ) throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            for (String line : JOURNAL) {
                pipeline.journal(line);
            }
            pipeline.settleProjection();

            List<ProjectedObservation> triggers = pipeline.capturedTriggers();
            ProjectedObservation last = triggers.getLast();
            CurrentGameStateSnapshot state = last.currentState();
            assertEquals(Boolean.TRUE, state.activeOrganicSampling());
            assertEquals(
                    SemanticValue.ofSymbol("START"),
                    CurrentGameStateSemantics.valueOf(
                            SemanticField.ORGANIC_SAMPLING_STAGE,
                            state
                    )
            );
            assertEquals(
                    SemanticValue.ofText(ORGANISM),
                    CurrentGameStateSemantics.valueOf(
                            SemanticField.ORGANIC_SAMPLING_VARIANT_LABEL,
                            state
                    )
            );
            assertEquals(CommanderLocationMode.ON_FOOT, state.commanderMode());
            assertEquals(
                    CurrentGameStateSnapshot.VEHICLE_SLV,
                    state.vehicleKind(),
                    "this journal's vehicle is the audited Nomad"
            );

            for (int index = 0; index < triggers.size() - 1; index++) {
                pipeline.inputsFor(List.of(triggers.get(index)));
            }
            String serialized = serializer.serialize(factory.create(
                    pipeline.inputsFor(List.of(last))
            ).request());
            JsonNode request = read(serialized);

            assertEquals("DISEMBARKED",
                    request.path("events").get(0).path("kind").textValue());
            assertEquals(2,
                    request.path("events").get(0)
                            .path("occurrenceOnBody").intValue());
            assertEquals(
                    """
                    {"events":[{"id":1,"kind":"DISEMBARKED",\
                    "system":"Schieni GG-A c3-84",\
                    "body":"Schieni GG-A c3-84 4 a",\
                    "onStation":false,"onPlanet":true,\
                    "occurrenceOnBody":2}],\
                    "context":{"commander":{"presence":"ON_FOOT"},\
                    "vehicle":{"kind":"SLV"},\
                    "sampling":{"organism":"Bacterium Bullaris - Red",\
                    "stage":"STARTED"}},\
                    "trajectory":{"recent":["EMBARKED","LIFTOFF","TOUCHDOWN"],\
                    "likelyNext":[{"kind":"BIOLOGICAL_SAMPLE_STARTED",\
                    "probability":1.0}]}}""",
                    serialized
            );

            assertEquals(
                    List.of("EMBARKED", "LIFTOFF", "TOUCHDOWN"),
                    texts(request.path("trajectory").path("recent"))
            );
            assertTrue(serialized.contains(
                    "\"likelyNext\":[{\"kind\":\"BIOLOGICAL_SAMPLE_STARTED\","
                            + "\"probability\":1.0}]"
            ), serialized);

            JsonNode sampling = samplingGroup(request);
            assertEquals(List.of("organism", "stage"), propertyNames(sampling));
            assertFalse(sampling.has("active"));
            assertFalse(sampling.has("complete"));
            assertFalse(sampling.has("step"));
            assertFalse(serialized.contains("\"stage\":\"START\""));
            assertFalse(serialized.contains("\"stage\":\"PROGRESS\""));
        }
    }

    // ------------------------------------------------------------- fixtures

    /** A fixture whose canonical sequence has been advanced by these scans. */
    private static DecisionTurnFixture sampling(String... scanTypes) {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        for (int index = 0; index < scanTypes.length; index++) {
            // Each scan was its own turn in the runtime, so its effects are
            // drained here too: an undrained change would name sampling.stage
            // and the context would drop what this test is about.
            fixture.inputs(List.of(
                    fixture.graphDisabled(scanOrganic(scanTypes[index], index))
            ));
        }
        return fixture;
    }

    private static String scanOrganic(String scanType, int index) {
        return """
                {"timestamp":"2026-07-30T10:00:%02dZ","event":"ScanOrganic",
                 "ScanType":"%s","Genus":"$Codex_Ent_Bacterial_Genus_Name;",
                 "Genus_Localised":"Bacteria",
                 "Species":"$Codex_Ent_Bacterial_01_Name;",
                 "Species_Localised":"Bacterium Bullaris",
                 "Variant":"$Codex_Ent_Bacterial_01_F_Name;",
                 "Variant_Localised":"Bacterium Bullaris - Red",
                 "SystemAddress":23155,"Body":20}
                """.formatted(index, scanType);
    }

    private static String disembark() {
        return """
                {"timestamp":"2026-07-30T10:01:00Z","event":"Disembark",
                 "SRV":true,"ID":10,"StarSystem":"Schieni GG-A c3-84",
                 "SystemAddress":23155,"Body":"Schieni GG-A c3-84 4 a",
                 "BodyID":20,"OnStation":false,"OnPlanet":true}
                """;
    }

    private static final String DROPSHIP = """
            {"timestamp":"2026-07-30T10:01:00Z","event":"DropshipDeploy",
             "StarSystem":"Schieni GG-A c3-84","SystemAddress":23155,
             "Body":"Schieni GG-A c3-84 4 a","BodyID":20,
             "OnStation":false,"OnPlanet":true}
            """;

    private static final List<String> JOURNAL = List.of(
            """
            {"timestamp":"2026-07-24T16:40:00Z","event":"LoadGame",
             "FID":"F12345678","ShipID":9,"Ship":"explorer_nx",
             "ShipName":"Wanderer"}
            """,
            """
            {"timestamp":"2026-07-24T16:40:01Z","event":"Location",
             "StarSystem":"Schieni GG-A c3-84",
             "SystemAddress":23155945939738,"Docked":false}
            """,
            """
            {"timestamp":"2026-07-24T16:41:00Z","event":"SupercruiseEntry",
             "StarSystem":"Schieni GG-A c3-84",
             "SystemAddress":23155945939738}
            """,
            """
            {"timestamp":"2026-07-24T16:42:00Z","event":"SAAScanComplete",
             "BodyName":"Schieni GG-A c3-84 4 a",
             "SystemAddress":23155945939738,"BodyID":20,
             "ProbesUsed":2,"EfficiencyTarget":2}
            """,
            """
            {"timestamp":"2026-07-24T16:42:01Z","event":"SAASignalsFound",
             "SystemAddress":23155945939738,"BodyID":20,
             "BodyName":"Schieni GG-A c3-84 4 a",
             "Signals":[{"Type":"$SAA_SignalType_Biological;",
             "Type_Localised":"Biological","Count":1}]}
            """,
            """
            {"timestamp":"2026-07-24T16:42:02Z","event":"Scan",
             "ScanType":"Detailed","SystemAddress":23155945939738,
             "BodyID":20,"BodyName":"Schieni GG-A c3-84 4 a",
             "PlanetClass":"Icy body","Landable":true,
             "WasDiscovered":false,"WasMapped":false,
             "WasFootfalled":false,"DistanceFromArrivalLS":1081.453145}
            """,
            """
            {"timestamp":"2026-07-24T16:43:00Z","event":"ApproachBody",
             "StarSystem":"Schieni GG-A c3-84",
             "SystemAddress":23155945939738,
             "Body":"Schieni GG-A c3-84 4 a","BodyID":20}
            """,
            """
            {"timestamp":"2026-07-24T16:44:00Z","event":"SupercruiseExit",
             "StarSystem":"Schieni GG-A c3-84",
             "SystemAddress":23155945939738,
             "Body":"Schieni GG-A c3-84 4 a","BodyID":20,
             "BodyType":"Planet"}
            """,
            """
            {"timestamp":"2026-07-24T16:48:45Z","event":"LaunchFighter",
             "Loadout":"base","ID":10,"PlayerControlled":true}
            """,
            """
            {"timestamp":"2026-07-24T16:48:51Z","event":"Cargo",
             "Vessel":"SRV","Count":0,"Inventory":[]}
            """,
            """
            {"timestamp":"2026-07-24T16:49:37Z","event":"Touchdown",
             "PlayerControlled":true,
             "StarSystem":"Schieni GG-A c3-84",
             "SystemAddress":23155945939738,
             "Body":"Schieni GG-A c3-84 4 a","BodyID":20,
             "OnStation":false,"OnPlanet":true}
            """,
            """
            {"timestamp":"2026-07-24T16:50:00Z","event":"Disembark",
             "SRV":true,"Taxi":false,"Multicrew":false,"ID":10,
             "StarSystem":"Schieni GG-A c3-84",
             "SystemAddress":23155945939738,
             "Body":"Schieni GG-A c3-84 4 a","BodyID":20,
             "OnStation":false,"OnPlanet":true}
            """,
            """
            {"timestamp":"2026-07-24T16:50:30Z","event":"CodexEntry",
             "EntryID":2100701,"Name":"$Codex_Ent_Bacterial_01_F_Name;",
             "Name_Localised":"Bacterium Bullaris - Red",
             "SubCategory":"$Codex_SubCategory_Organic_Structures;",
             "Category":"$Codex_Category_Biology;",
             "Region":"$Codex_RegionName_18;",
             "System":"Schieni GG-A c3-84",
             "SystemAddress":23155945939738,"BodyID":20,
             "IsNewEntry":true}
            """,
            """
            {"timestamp":"2026-07-24T16:50:31Z","event":"ScanOrganic",
             "ScanType":"Log","Genus":"$Codex_Ent_Bacterial_Genus_Name;",
             "Genus_Localised":"Bacteria",
             "Species":"$Codex_Ent_Bacterial_01_Name;",
             "Species_Localised":"Bacterium Bullaris",
             "Variant":"$Codex_Ent_Bacterial_01_F_Name;",
             "Variant_Localised":"Bacterium Bullaris - Red",
             "SystemAddress":23155945939738,"Body":20}
            """,
            """
            {"timestamp":"2026-07-24T16:52:00Z","event":"Embark",
             "SRV":true,"Taxi":false,"Multicrew":false,"ID":10,
             "StarSystem":"Schieni GG-A c3-84",
             "SystemAddress":23155945939738,
             "Body":"Schieni GG-A c3-84 4 a","BodyID":20,
             "OnStation":false,"OnPlanet":true}
            """,
            """
            {"timestamp":"2026-07-24T16:53:00Z","event":"Liftoff",
             "PlayerControlled":true,
             "StarSystem":"Schieni GG-A c3-84",
             "SystemAddress":23155945939738,
             "Body":"Schieni GG-A c3-84 4 a","BodyID":20,
             "OnStation":false,"OnPlanet":true}
            """,
            """
            {"timestamp":"2026-07-24T16:54:00Z","event":"Touchdown",
             "PlayerControlled":true,
             "StarSystem":"Schieni GG-A c3-84",
             "SystemAddress":23155945939738,
             "Body":"Schieni GG-A c3-84 4 a","BodyID":20,
             "OnStation":false,"OnPlanet":true}
            """,
            """
            {"timestamp":"2026-07-24T16:55:00Z","event":"Disembark",
             "SRV":true,"Taxi":false,"Multicrew":false,"ID":10,
             "StarSystem":"Schieni GG-A c3-84",
             "SystemAddress":23155945939738,
             "Body":"Schieni GG-A c3-84 4 a","BodyID":20,
             "OnStation":false,"OnPlanet":true}
            """
    );

    // -------------------------------------------------------------- reading

    private JsonNode request(DecisionTurnFixture fixture, String rawJson) {
        return read(serialize(fixture, rawJson));
    }

    private String serialize(DecisionTurnFixture fixture, String rawJson) {
        return serializer.serialize(factory.create(fixture.inputs(
                List.of(fixture.graphDisabled(rawJson))
        )).request());
    }

    private static JsonNode samplingGroup(JsonNode request) {
        JsonNode sampling = request.path("context").path("sampling");
        assertTrue(sampling.isObject(), request.toString());
        return sampling;
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.textValue()));
        return List.copyOf(values);
    }

    private static List<String> propertyNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return List.copyOf(names);
    }

    private static JsonNode read(String serialized) {
        try {
            return JSON.readTree(serialized);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
