package kairon.observer.decision;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.bio.JsonOrganicRegistryLoader;
import kairon.observer.decision.SemanticPipelineHarness.HarnessOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who decides what an organism is called, asserted through the whole pipeline.
 *
 * <p>Before ADR-0028 the answer was "the game's language setting" for a sampling
 * event and "the middle of the game's internal symbol" for a genus list, in the
 * same turn. This asserts the one answer that replaced both: the registry, in
 * the language Kairon is configured to speak, with the journal's own word as
 * the fallback and nothing below that.</p>
 *
 * <p>Both runs drive the same journal through the same production pipeline and
 * differ only in whether a registry is configured, so the difference in the two
 * documents is the registry and nothing else.</p>
 */
final class OrganicNamingContractTest {

    /**
     * The registry names it, in the configured language.
     *
     * <p>The journal here is written in English — {@code "Bacterium Vesicula -
     * Green"} — and the registry says the Russian for it is different in both
     * the word and the colour. What reaches the model is the registry's, which
     * is the whole point: the game's language setting stops deciding.</p>
     */
    @Test
    void aConfiguredRegistryNamesEveryOrganismInTheOutputLanguage(
            @TempDir Path directory
    ) throws IOException {
        try (SemanticPipelineHarness harness = withRegistry(directory, "ru")) {
            survey(harness);

            PipelineTrace trace = harness.trace();
            assertEquals(
                    "Бактерия Vesicula - лайм",
                    organismOf(trace),
                    "the sampling event carries the registry's Russian name, "
                            + "not the journal's English one"
            );

            JsonNode biology = trace.turns().getLast().context().path("biology");
            assertEquals(
                    List.of("Бактерии"),
                    values(biology.path("collected")),
                    "and so does the inventory beside it: one organism reads "
                            + "as one organism wherever it is named"
            );
            assertEquals(
                    List.of("Mystery", "Tussock"),
                    values(biology.path("remaining")),
                    "an organism the registry has never heard of keeps the "
                            + "word the journal used, and one it knows only in "
                            + "English is named in English rather than not at "
                            + "all"
            );
        }
    }

    /**
     * With no registry, the journal's own word — which is what it always was.
     *
     * <p>{@code bio.registryFile: null} is a supported way to run, not a
     * degraded one, and this is what it produces. It is also the reason the
     * genus that the registry knew only in English reads differently between
     * the two runs: here nothing supplies a third rung.</p>
     */
    @Test
    void withoutARegistryEveryOrganismKeepsTheJournalsWord(
            @TempDir Path directory
    ) {
        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory)) {
            survey(harness);

            PipelineTrace trace = harness.trace();
            assertEquals(
                    "Bacterium Vesicula - Green",
                    organismOf(trace),
                    "the journal rendered it, so the journal names it"
            );

            JsonNode biology = trace.turns().getLast().context().path("biology");
            assertEquals(
                    List.of("Bacterium"),
                    values(biology.path("collected"))
            );
            assertEquals(
                    List.of("Mystery"),
                    values(biology.path("remaining")),
                    "and the genus the journal never rendered is named by "
                            + "nothing: a symbol is not a word"
            );
        }
    }

    /**
     * A finished sample says what it pays, and why it pays that much.
     *
     * <p>Nobody had walked on this body, so the data is undiscovered and Vista
     * Genomics pays five times the published price. One number, already
     * multiplied, so there is no arithmetic for the model to get wrong, and the
     * flag beside it says why it is large (ADR-0029).</p>
     */
    @Test
    void aFirstFootfallSampleIsWorthFiveTimesThePublishedPrice(
            @TempDir Path directory
    ) throws IOException {
        try (SemanticPipelineHarness harness = withRegistry(directory, "ru")) {
            survey(harness, moonScan(false));

            JsonNode analysis = analysisOf(harness.trace());
            assertEquals(
                    5.0,
                    analysis.path("valueMCr").doubleValue(),
                    0.0001,
                    "1 000 000 published, five times over, said the way it "
                            + "will be spoken: "
                            + harness.trace().turns().getLast().userMessage()
            );
            assertTrue(
                    analysis.path("firstFootfall").booleanValue(),
                    "and the flag says why"
            );
        }
    }

    /**
     * On a body somebody had already walked on, the published price and no flag.
     *
     * <p>The bonus is for being first. Somebody having been here is the game
     * saying this is not that, and the document says nothing about a bonus
     * rather than saying there is none.</p>
     */
    @Test
    void anAlreadyFootfalledBodyPaysThePublishedPrice(@TempDir Path directory)
            throws IOException {
        try (SemanticPipelineHarness harness = withRegistry(directory, "ru")) {
            survey(harness, moonScan(true));

            JsonNode analysis = analysisOf(harness.trace());
            assertEquals(1.0, analysis.path("valueMCr").doubleValue(), 0.0001);
            assertTrue(
                    analysis.path("firstFootfall").isMissingNode(),
                    "absence is how this document says a thing is not so"
            );
        }
    }

    /**
     * With no registry there is no price, and the turn says nothing about one.
     *
     * <p>The value comes from the registry or from nowhere. A sampling turn
     * without one is exactly the turn Kairon produced before ADR-0029.</p>
     */
    @Test
    void withoutARegistryTheSampleHasNoPrice(@TempDir Path directory) {
        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory)) {
            survey(harness, moonScan(false));

            JsonNode analysis = analysisOf(harness.trace());
            assertTrue(analysis.path("valueMCr").isMissingNode());
            assertTrue(analysis.path("firstFootfall").isMissingNode());
        }
    }

    /**
     * Finishing a body says what the body paid, not what the sample did.
     *
     * <p>One money figure per turn, always. The last sample's own price is
     * inside the total, and two numbers side by side are the arithmetic
     * ADR-0029 keeps out of the model's hands. {@code allCollected} says the
     * body is finished as a fact, because the absence of {@code remaining} was
     * read as its opposite twice on the live run of 2026-08-08.</p>
     */
    @Test
    void finishingABodyReportsTheTotalItPaidAndSaysItIsFinished(
            @TempDir Path directory
    ) throws IOException {
        try (SemanticPipelineHarness harness = withRegistry(directory, "ru")) {
            harness.journal(LOAD_GAME).journal(ARRIVAL).closeBatch();
            harness.journal(moonScan(false)).closeBatch();
            harness.journal(ONE_GENUS_SIGNALS).closeBatch();
            harness.journal(APPROACH).closeBatch();
            harness.journal(SUPERCRUISE_EXIT).closeBatch();
            harness.journal(LOGGED).closeBatch();
            harness.journal(ANALYSED).closeBatch();

            JsonNode analysis = analysisOf(harness.trace());
            assertEquals(
                    5.0,
                    analysis.path("bodyTotalMCr").doubleValue(),
                    0.0001,
                    "one species at a million, five times over for the first "
                            + "footfall: " + harness.trace().turns().getLast()
                            .userMessage()
            );
            assertTrue(
                    analysis.path("valueMCr").isMissingNode(),
                    "and the sample's own price is not sent beside it"
            );

            JsonNode biology = harness.trace().turns().getLast()
                    .context().path("biology");
            assertTrue(
                    biology.path("allCollected").booleanValue(),
                    "the body is finished, said rather than left to inference"
            );
            assertTrue(
                    biology.path("remaining").isMissingNode(),
                    "nothing is left, so no list of what is left"
            );
        }
    }

    /**
     * The turn that names the genera says what they are worth at least.
     *
     * <p>The moon is icy, cold, still and light — conditions that admit
     * Bacterium Vesicula at a million and refuse Bacterium Alcyoneum at three
     * hundred thousand. The cheaper species is in the registry precisely so
     * that a filter which did nothing would be caught: it would answer 1.5,
     * not 5.0 (ADR-0030).</p>
     */
    @Test
    void aSurveyedBodySaysWhatItsOrganismsAreWorthAtLeast(
            @TempDir Path directory
    ) throws IOException {
        try (SemanticPipelineHarness harness = withRegistry(directory, "ru")) {
            harness.journal(LOAD_GAME).journal(ARRIVAL).closeBatch();
            harness.journal(moonScan(false)).closeBatch();
            harness.journal(ONE_GENUS_SIGNALS).closeBatch();

            JsonNode survey = surveyOf(harness.trace());
            assertEquals(
                    5.0,
                    survey.path("atLeastMCr").doubleValue(),
                    0.0001,
                    "the cheapest species these conditions allow, five times "
                            + "over because nobody had walked here: "
                            + harness.trace().turns().getLast().userMessage()
            );
            assertTrue(
                    survey.path("firstFootfall").booleanValue(),
                    "and the flag says why it is large"
            );
        }
    }

    /**
     * A body somebody has walked on gets the floor without the multiple.
     *
     * <p>Same conditions, same candidate, same reasoning as the sample's own
     * price: the bonus is for being first, and the game saying somebody was
     * here is the game saying this is not that.</p>
     */
    @Test
    void anAlreadyFootfalledBodyIsWorthTheFloorAndNoMore(
            @TempDir Path directory
    ) throws IOException {
        try (SemanticPipelineHarness harness = withRegistry(directory, "ru")) {
            harness.journal(LOAD_GAME).journal(ARRIVAL).closeBatch();
            harness.journal(moonScan(true)).closeBatch();
            harness.journal(ONE_GENUS_SIGNALS).closeBatch();

            JsonNode survey = surveyOf(harness.trace());
            assertEquals(1.0, survey.path("atLeastMCr").doubleValue(), 0.0001);
            assertTrue(survey.path("firstFootfall").isMissingNode());
        }
    }

    /**
     * With no registry there is no floor, and the survey turn says nothing.
     *
     * <p>The rules come from the file or from nowhere. This is the turn every
     * survey produced before ADR-0030, and it is what a Commander who
     * configures no registry keeps.</p>
     */
    @Test
    void withoutARegistryTheSurveyPredictsNothing(@TempDir Path directory) {
        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory)) {
            harness.journal(LOAD_GAME).journal(ARRIVAL).closeBatch();
            harness.journal(moonScan(false)).closeBatch();
            harness.journal(ONE_GENUS_SIGNALS).closeBatch();

            JsonNode survey = surveyOf(harness.trace());
            assertTrue(
                    survey.path("atLeastMCr").isMissingNode(),
                    "no rules, no claim: "
                            + harness.trace().turns().getLast().userMessage()
            );
        }
    }

    // ------------------------------------------------------------- the runs

    private static SemanticPipelineHarness withRegistry(
            Path directory,
            String language
    ) throws IOException {
        Path file = directory.resolve("organic-registry.json");
        Files.writeString(file, REGISTRY, StandardCharsets.UTF_8);
        return SemanticPipelineHarness.create(
                directory.resolve("run"),
                HarnessOptions.standard().naming(new DecisionOrganicNames(
                        JsonOrganicRegistryLoader.load(file),
                        language
                ))
        );
    }

    /**
     * Arrive, map the moon, walk up to something and finish a sample of it.
     *
     * <p>The last turn is the analysis, because that is the turn the inventory
     * travels with; the sampling event before it is where {@code organism} is
     * named.</p>
     */
    private static void survey(SemanticPipelineHarness harness) {
        survey(harness, MOON_SCAN);
    }

    private static void survey(SemanticPipelineHarness harness, String scan) {
        harness.journal(LOAD_GAME).journal(ARRIVAL).closeBatch();
        harness.journal(scan).closeBatch();
        harness.journal(SAA_SIGNALS).closeBatch();
        harness.journal(APPROACH).closeBatch();
        harness.journal(SUPERCRUISE_EXIT).closeBatch();
        harness.journal(LOGGED).closeBatch();
        harness.journal(ANALYSED).closeBatch();
    }

    /** The event of the turn that finished the sample. */
    private static JsonNode analysisOf(PipelineTrace trace) {
        for (PipelineTrace.TurnView turn : trace.turns()) {
            for (JsonNode event : turn.events()) {
                if ("BIOLOGICAL_SAMPLE".equals(event.path("event").asText(null))
                        || event.path("valueMCr").isNumber()) {
                    return event;
                }
            }
        }
        return trace.turns().getLast().events().get(0);
    }

    /** The event of the turn that reported what the surface scanner found. */
    private static JsonNode surveyOf(PipelineTrace trace) {
        for (PipelineTrace.TurnView turn : trace.turns()) {
            for (JsonNode event : turn.events()) {
                if (!event.path("organisms").isMissingNode()
                        || event.path("atLeastMCr").isNumber()) {
                    return event;
                }
            }
        }
        return trace.turns().getLast().events().get(0);
    }

    /** The {@code organism} of the sampling turn, wherever it was stated. */
    private static String organismOf(PipelineTrace trace) {
        for (PipelineTrace.TurnView turn : trace.turns()) {
            for (JsonNode event : turn.events()) {
                JsonNode organism = event.path("organism");
                if (organism.isTextual()) {
                    return organism.textValue();
                }
            }
        }
        return null;
    }

    private static List<String> values(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.textValue()));
        return List.copyOf(values);
    }

    // ---------------------------------------------------------- the fixtures

    /**
     * Three organisms, told apart by how much of them the registry knows.
     *
     * <p>Bacterium is known in both languages, Tussock only in English, and the
     * genus the survey also named is not here at all. One rung each.</p>
     */
    private static final String REGISTRY = """
            {
              "schema": "kairon-organic-registry-v1",
              "genera": [
                {"id": "$Codex_Ent_Bacterial_Genus_Name;",
                 "names": {"en": "Bacterium", "ru": "Бактерии"}},
                {"id": "$Codex_Ent_Tussocks_Genus_Name;",
                 "names": {"en": "Tussock"}}
              ],
              "species": [
                {"id": "$Codex_Ent_Bacterial_05_Name;",
                 "genus": "$Codex_Ent_Bacterial_Genus_Name;",
                 "names": {"en": "Bacterium Vesicula", "ru": "Бактерия Vesicula"},
                 "valueCr": 1000000,
                 "rulesets": [
                   {"bodyTypes": ["Icy body"],
                    "minGravity": 0.05, "maxGravity": 0.5,
                    "maxTemperature": 100.0,
                    "volcanism": "None"}
                 ]},
                {"id": "$Codex_Ent_Bacterial_06_Name;",
                 "genus": "$Codex_Ent_Bacterial_Genus_Name;",
                 "names": {"en": "Bacterium Alcyoneum"},
                 "valueCr": 300000,
                 "rulesets": [
                   {"bodyTypes": ["Rocky body"],
                    "atmospheres": ["Ammonia"]}
                 ]}
              ],
              "variants": [
                {"id": "$Codex_Ent_Bacterial_05_A_Name;",
                 "species": "$Codex_Ent_Bacterial_05_Name;",
                 "names": {"en": "Bacterium Vesicula - Green",
                           "ru": "Бактерия Vesicula - лайм"}}
              ]
            }
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

    private static final String MOON_SCAN = moonScan(false);

    /** The same moon, with the game saying whether anybody had walked on it. */
    private static String moonScan(boolean footfalled) {
        return """
                {"timestamp":"2026-07-30T10:01:00Z","event":"Scan",
                 "ScanType":"Detailed","BodyName":"Survey Alpha A 2 a","BodyID":5,
                 "Parents":[{"Planet":4},{"Star":1},{"Null":0}],
                 "StarSystem":"Survey Alpha","SystemAddress":4001,
                 "PlanetClass":"Icy body","Landable":true,"TidalLock":true,
                 "SurfaceGravity":1.065823,"SurfaceTemperature":48.9,
                 "DistanceFromArrivalLS":476.481077,
                 "WasDiscovered":false,"WasMapped":false,"WasFootfalled":%s}
                """.formatted(footfalled);
    }

    /**
     * One genus the registry knows in both languages, one it knows only in
     * English and the journal did not render, and one nothing has a word for
     * but the journal.
     */
    private static final String SAA_SIGNALS = """
            {"timestamp":"2026-07-30T10:02:00Z","event":"SAASignalsFound",
             "SystemAddress":4001,"BodyID":5,
             "BodyName":"Survey Alpha A 2 a",
             "Signals":[{"Type":"$SAA_SignalType_Biological;","Count":3}],
             "Genuses":[
               {"Genus":"$Codex_Ent_Bacterial_Genus_Name;",
                "Genus_Localised":"Bacterium"},
               {"Genus":"$Codex_Ent_Tussocks_Genus_Name;"},
               {"Genus":"$Codex_Ent_Unresearched_Genus_Name;",
                "Genus_Localised":"Mystery"}]}
            """;

    /** A survey naming one genus, so collecting it finishes the body. */
    private static final String ONE_GENUS_SIGNALS = """
            {"timestamp":"2026-07-30T10:02:00Z","event":"SAASignalsFound",
             "SystemAddress":4001,"BodyID":5,
             "BodyName":"Survey Alpha A 2 a",
             "Signals":[{"Type":"$SAA_SignalType_Biological;","Count":1}],
             "Genuses":[
               {"Genus":"$Codex_Ent_Bacterial_Genus_Name;",
                "Genus_Localised":"Bacterium"}]}
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

    private static final String LOGGED = """
            {"timestamp":"2026-07-30T10:03:50Z","event":"ScanOrganic",
             "ScanType":"Log",
             "Genus":"$Codex_Ent_Bacterial_Genus_Name;",
             "Genus_Localised":"Bacterium",
             "Species":"$Codex_Ent_Bacterial_05_Name;",
             "Species_Localised":"Bacterium Vesicula",
             "Variant":"$Codex_Ent_Bacterial_05_A_Name;",
             "Variant_Localised":"Bacterium Vesicula - Green",
             "SystemAddress":4001,"Body":5}
            """;

    private static final String ANALYSED = """
            {"timestamp":"2026-07-30T10:04:00Z","event":"ScanOrganic",
             "ScanType":"Analyse",
             "Genus":"$Codex_Ent_Bacterial_Genus_Name;",
             "Genus_Localised":"Bacterium",
             "Species":"$Codex_Ent_Bacterial_05_Name;",
             "Species_Localised":"Bacterium Vesicula",
             "Variant":"$Codex_Ent_Bacterial_05_A_Name;",
             "Variant_Localised":"Bacterium Vesicula - Green",
             "SystemAddress":4001,"Body":5}
            """;
}
