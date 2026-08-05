package kairon.observer.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kairon.behavior.normalize.NormalizedEventType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What an automatic scan of the arrival star establishes, and what it does not.
 *
 * <p>An {@code AutoScan} is the ship noticing a body it flew past, and none of
 * them is a scan result the Commander went and got — so none of them is
 * reported as one. Exactly one of them says something no other record in the
 * visit says: the reading of the star the ship arrived at, reporting that nobody
 * had discovered it. That fact used to reach the model only as an unattributed
 * canonical change in whatever turn happened to come next, which in the measured
 * replay was a codex entry about a different star.</p>
 *
 * <p>Every case here is checked on the graph and on the provider at once. An
 * occurrence without a model-facing event would be a milestone nobody was told
 * about; an event without an occurrence would be a milestone standing in no
 * trajectory.</p>
 */
final class ArrivalStarDiscoveryTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final long ADDRESS = 3248029100228L;
    private static final String SYSTEM = "Schieni SI-B e756";

    /** 1: the arrival star's undiscovered reading is one milestone. */
    @Test
    void anUndiscoveredArrivalStarIsReportedOnce(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline = perTrigger(directory)) {
            arrived(pipeline);
            int beforeScan = pipeline.modelInputs().size();

            pipeline.journal(arrivalStar("10:01:00Z", "B", false));
            pipeline.settle();

            assertEquals(
                    beforeScan + 1,
                    pipeline.modelInputs().size(),
                    "the reading opened a turn of its own"
            );
            assertEquals(
                    1L,
                    pipeline.graphOccurrenceCount(
                            NormalizedEventType.SYSTEM_UNDISCOVERED_CONFIRMED
                    ),
                    "and it has an occurrence of its own"
            );
            assertEquals(
                    0L,
                    pipeline.graphOccurrenceCount(
                            NormalizedEventType.BODY_SCANNED
                    ),
                    "which is not a body scan"
            );
            assertEquals(
                    """
                    {"events":[{"event":"A scan reported a star \
                    as not previously discovered.",\
                    "arrivalStar":"Schieni SI-B e756",\
                    "system":"Schieni SI-B e756",\
                    "starType":"B",\
                    "previouslyDiscovered":false}],\
                    "trajectory":{"recent":["SYSTEM_ENTERED"]}}""",
                    document(pipeline),
                    "the milestone and the trajectory, and nothing else"
            );
        }
    }

    /**
     * 1b: each of the replay's three arrivals, whole.
     *
     * <p>Two K stars and a B star, the systems and classes the measured journal
     * actually contains. The document is asserted entire each time: the star's
     * other survey flags, its zero distance from the arrival point and the
     * coarse type {@code STAR} are canonical facts this reading established, and
     * none of them is what the milestone is about — the flags are not what
     * "undiscovered" turns on, the distance is zero because this star
     * <em>is</em> the arrival point, and the coarse type restates the class
     * beside it.</p>
     */
    @Test
    void everyArrivalOfTheReplayCarriesNothingButItsMilestone(
            @TempDir Path directory
    ) throws Exception {
        record Arrival(String system, long address, String starType) {
        }
        List<Arrival> replay = List.of(
                new Arrival("Schieni GG-A c3-64", 17658387800858L, "K"),
                new Arrival("Schieni GG-A c3-72", 19857411056410L, "K"),
                new Arrival("Schieni SI-B e756", 3248029100228L, "B")
        );
        for (Arrival arrival : replay) {
            try (DecisionProductionPipeline pipeline = perTrigger(directory
                    .resolve(Long.toString(arrival.address())))) {
                pipeline.journal(LOAD_GAME);
                pipeline.journal(jump(
                        "10:00:01Z",
                        arrival.system(),
                        arrival.address()
                ));
                pipeline.settle();

                pipeline.journal(scan(
                        "10:01:00Z",
                        arrival.system(),
                        arrival.system(),
                        0,
                        arrival.address(),
                        arrival.starType(),
                        false,
                        0.0
                ));
                pipeline.settle();

                assertEquals(
                        ("""
                        {"events":[{"event":"A scan reported a \
                        star as not previously discovered.",\
                        "arrivalStar":"%s","system":"%s","starType":"%s",\
                        "previouslyDiscovered":false}],\
                        "trajectory":{"recent":["SYSTEM_ENTERED"]}}""")
                                .formatted(
                                        arrival.system(),
                                        arrival.system(),
                                        arrival.starType()
                                ),
                        document(pipeline),
                        "no body facts beside the milestone for "
                                + arrival.system()
                );
                assertEquals(
                        1L,
                        milestones(pipeline),
                        "and one occurrence, unchanged"
                );
            }
        }
    }

    /**
     * 1c: an ordinary body-scoped event still gets everything it needs.
     *
     * <p>The scope rule must not cost the case it exists to serve. An approach
     * names the body it arrives at, canonical state answers for that same body,
     * and what is known about it is exactly what makes the arrival worth
     * remarking on — including the facts an earlier scan established.</p>
     */
    @Test
    void anOrdinaryBodyEventStillCarriesItsBodyFacts(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline = perTrigger(directory)) {
            arrived(pipeline);
            pipeline.journal(arrivalStar("10:01:00Z", "B", false));
            pipeline.journal(detailedPlanetScan("10:02:00Z"));
            pipeline.settle();
            int beforeApproach = pipeline.modelInputs().size();

            pipeline.journal(approachPlanet("10:03:00Z"));
            pipeline.settle();

            assertEquals(
                    beforeApproach + 1,
                    pipeline.modelInputs().size()
            );
            JsonNode request = JSON.readTree(document(pipeline));
            assertEquals(
                    "A ship in supercruise came within a body's orbital-cruise zone.",
                    request.path("events").get(0)
                            .path("event").textValue()
            );
            JsonNode body = request.path("context").path("body");
            assertTrue(
                    body.isObject(),
                    "the body being arrived at is described: "
                            + document(pipeline)
            );
            assertEquals("Icy body", body.path("planetClass").textValue());
            assertEquals(false, body.path("landable").booleanValue());
            assertEquals(
                    1081.453145,
                    body.path("distanceFromArrivalLs").doubleValue()
            );
        }
    }

    /** 2: the ship passing the same star again establishes nothing new. */
    @Test
    void aRepeatedReadingOfTheArrivalStarIsNotASecondMilestone(
            @TempDir Path directory
    ) throws Exception {
        try (DecisionProductionPipeline pipeline = perTrigger(directory)) {
            arrived(pipeline);
            pipeline.journal(arrivalStar("10:01:00Z", "B", false));
            pipeline.settle();
            int afterFirst = pipeline.modelInputs().size();

            pipeline.journal(arrivalStar("10:02:00Z", "B", false));
            pipeline.settle();

            assertEquals(
                    afterFirst,
                    pipeline.modelInputs().size(),
                    "the repeat opened no turn, empty or otherwise"
            );
            assertEquals(
                    1L,
                    pipeline.graphOccurrenceCount(
                            NormalizedEventType.SYSTEM_UNDISCOVERED_CONFIRMED
                    ),
                    "and recorded no second occurrence"
            );
        }
    }

    /** 3: a star somebody had already found is not a discovery. */
    @Test
    void anAlreadyDiscoveredArrivalStarIsNoMilestone(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline = perTrigger(directory)) {
            arrived(pipeline);
            int beforeScan = pipeline.modelInputs().size();

            pipeline.journal(arrivalStar("10:01:00Z", "B", true));
            pipeline.settle();

            assertEquals(
                    beforeScan,
                    pipeline.modelInputs().size(),
                    "nothing was established, so nothing was reported"
            );
            assertEquals(
                    0L,
                    pipeline.graphOccurrenceCount(
                            NormalizedEventType.SYSTEM_UNDISCOVERED_CONFIRMED
                    )
            );
            assertEquals(
                    "B",
                    pipeline.capturedProjections().getLast()
                            .currentState().starType(),
                    "but canonical state still learned what the star is"
            );
        }
    }

    /**
     * 4: everything else the ship notices in passing stays hidden.
     *
     * <p>A belt cluster, and a star the ship flew past that nobody had found
     * either. The second is the one that matters: it reports exactly the flag
     * the milestone is built on, and it is still not the arrival star.</p>
     */
    @Test
    void otherAutomaticScansAreStillNotReported(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline = perTrigger(directory)) {
            arrived(pipeline);
            int beforeScans = pipeline.modelInputs().size();

            pipeline.journal(beltCluster("10:01:00Z"));
            pipeline.journal(distantStar("10:01:10Z", "TTS", false));
            pipeline.settle();

            assertEquals(
                    beforeScans,
                    pipeline.modelInputs().size(),
                    "neither opened a turn"
            );
            assertEquals(
                    0L,
                    pipeline.graphOccurrenceCount(
                            NormalizedEventType.SYSTEM_UNDISCOVERED_CONFIRMED
                    ),
                    "and neither is the arrival star"
            );
            assertEquals(
                    0L,
                    pipeline.graphOccurrenceCount(
                            NormalizedEventType.BODY_SCANNED
                    ),
                    "nor a scan result"
            );
        }
    }

    /**
     * 5: a codex entry is told nothing about the star the ship is parked at.
     *
     * <p>The entry names a T Tauri star and the journal files it under body 0,
     * which the scan before it reports as a B star. Kairon does not repair that:
     * it stops treating the entry's body id as an identity, so the arrival
     * star's class and discovery flags reach neither {@code changes} nor
     * {@code context} of the entry's turn.</p>
     */
    @Test
    void aStellarCodexEntryCarriesNoOtherStarsFacts(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline = perTrigger(directory)) {
            arrived(pipeline);
            // Already discovered, so the reading is hidden and its canonical
            // effect is exactly what used to drain into the next turn.
            pipeline.journal(arrivalStar("10:01:00Z", "B", true));
            pipeline.settle();
            int beforeCodex = pipeline.modelInputs().size();

            pipeline.journal(tTauriCodexEntry("10:01:30Z"));
            pipeline.settle();

            assertEquals(
                    beforeCodex + 1,
                    pipeline.modelInputs().size(),
                    "the entry is still reported"
            );
            String document = document(pipeline);
            JsonNode parsed = JSON.readTree(document);
            assertEquals(
                    List.of("CODEX_ENTRY_RECORDED"),
                    pipeline.modelFacingKinds()
                            .subList(beforeCodex, beforeCodex + 1)
            );
            assertTrue(
                    parsed.path("context").path("body").isMissingNode(),
                    "no body was proven, so none is described: " + document
            );
            assertFalse(
                    document.contains("\"subject\":\"body\""),
                    "and none of the current body's facts changed hands: "
                            + document
            );
            assertFalse(
                    document.contains("starType"),
                    "least of all the class of a different star: " + document
            );
            assertEquals(
                    "B",
                    pipeline.capturedProjections().getLast()
                            .currentState().starType(),
                    "canonical state is untouched by any of this"
            );
        }
    }

    /**
     * 6: the graph and the observer agree, outcome by outcome.
     *
     * <p>Five readings in one visit, each checked on both sides at once. The
     * arrival star is a milestone on both; a repeat, a body the visit did not
     * arrive at, a belt cluster and a star already found are silent on both.</p>
     */
    @Test
    void theGraphAndTheObserverAgreeOnEveryReading(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline = perTrigger(directory)) {
            arrived(pipeline);
            int afterArrival = pipeline.modelInputs().size();

            pipeline.journal(arrivalStar("10:01:00Z", "B", false));
            pipeline.settle();
            assertEquals(1L, milestones(pipeline), "a milestone is structural");
            assertEquals(
                    afterArrival + 1,
                    pipeline.modelInputs().size(),
                    "and it is reported"
            );

            pipeline.journal(arrivalStar("10:02:00Z", "B", false));
            pipeline.journal(distantStar("10:02:10Z", "TTS", false));
            pipeline.journal(beltCluster("10:02:20Z"));
            pipeline.journal(distantStar("10:02:30Z", "TTS", true));
            pipeline.settle();

            assertEquals(
                    1L,
                    milestones(pipeline),
                    "nothing else established a first discovery"
            );
            assertEquals(
                    afterArrival + 1,
                    pipeline.modelInputs().size(),
                    "and nothing else opened a turn"
            );
            assertEquals(
                    List.of(
                            NormalizedEventType.SYSTEM_ENTRY,
                            NormalizedEventType.SYSTEM_UNDISCOVERED_CONFIRMED
                    ),
                    pipeline.episodeTypes(),
                    "the visit holds the arrival and the one milestone"
            );
            assertEquals(
                    List.of("SYSTEM_JUMP", "SYSTEM_UNDISCOVERED_CONFIRMED"),
                    pipeline.modelFacingKinds(),
                    "and the model was shown the same two things"
            );
        }
    }

    // ------------------------------------------------------------- fixtures

    private static DecisionProductionPipeline perTrigger(Path directory) {
        return new DecisionProductionPipeline(
                directory,
                new DecisionTurnPolicy(1, 16_000)
        );
    }

    private static long milestones(DecisionProductionPipeline pipeline) {
        return pipeline.graphOccurrenceCount(
                NormalizedEventType.SYSTEM_UNDISCOVERED_CONFIRMED
        );
    }

    private static final String LOAD_GAME = """
            {"timestamp":"2026-07-30T10:00:00Z","event":"LoadGame",
             "FID":"F12345678","ShipID":9,"Ship":"explorer_nx",
             "ShipName":"Wanderer"}
            """;

    private static void arrived(DecisionProductionPipeline pipeline)
            throws Exception {
        pipeline.journal(LOAD_GAME);
        pipeline.journal(jump("10:00:01Z", SYSTEM, ADDRESS));
        pipeline.settle();
    }

    private static String jump(String time, String system, long address) {
        return "{\"timestamp\":\"2026-07-30T" + time + "\","
                + "\"event\":\"FSDJump\",\"StarSystem\":\"" + system + "\","
                + "\"SystemAddress\":" + address + ",\"Body\":\"" + system
                + "\",\"BodyID\":0,\"BodyType\":\"Star\",\"JumpDist\":3.4,"
                + "\"FuelUsed\":0.003,\"FuelLevel\":127.9}";
    }

    /** A planet established in full, so the approach has something to carry. */
    private static String detailedPlanetScan(String time) {
        return "{\"timestamp\":\"2026-07-30T" + time + "\",\"event\":\"Scan\","
                + "\"ScanType\":\"Detailed\",\"BodyName\":\"" + SYSTEM
                + " 4 a\",\"BodyID\":20,\"StarSystem\":\"" + SYSTEM
                + "\",\"SystemAddress\":" + ADDRESS
                + ",\"PlanetClass\":\"Icy body\",\"Landable\":false,"
                + "\"WasDiscovered\":false,\"WasMapped\":false,"
                + "\"WasFootfalled\":false,"
                + "\"DistanceFromArrivalLS\":1081.453145}";
    }

    private static String approachPlanet(String time) {
        return "{\"timestamp\":\"2026-07-30T" + time
                + "\",\"event\":\"ApproachBody\",\"StarSystem\":\"" + SYSTEM
                + "\",\"SystemAddress\":" + ADDRESS + ",\"Body\":\"" + SYSTEM
                + " 4 a\",\"BodyID\":20}";
    }

    /** The body the jump arrived at, read automatically on the way in. */
    private static String arrivalStar(
            String time,
            String starType,
            boolean discovered
    ) {
        return scan(time, SYSTEM, SYSTEM, 0, ADDRESS, starType, discovered, 0.0);
    }

    /** A star the ship merely flew past, at the far side of the system. */
    private static String distantStar(
            String time,
            String starType,
            boolean discovered
    ) {
        return scan(
                time,
                SYSTEM,
                SYSTEM + " 14",
                19,
                ADDRESS,
                starType,
                discovered,
                2541.393527
        );
    }

    private static String beltCluster(String time) {
        return "{\"timestamp\":\"2026-07-30T" + time + "\",\"event\":\"Scan\","
                + "\"ScanType\":\"AutoScan\",\"BodyName\":\"" + SYSTEM
                + " A Belt Cluster 3\",\"BodyID\":4,\"StarSystem\":\"" + SYSTEM
                + "\",\"SystemAddress\":" + ADDRESS
                + ",\"DistanceFromArrivalLS\":6.045770,"
                + "\"WasDiscovered\":false,\"WasMapped\":false,"
                + "\"WasFootfalled\":false}";
    }

    private static String scan(
            String time,
            String system,
            String bodyName,
            long bodyId,
            long address,
            String starType,
            boolean discovered,
            double distance
    ) {
        return "{\"timestamp\":\"2026-07-30T" + time + "\",\"event\":\"Scan\","
                + "\"ScanType\":\"AutoScan\",\"BodyName\":\"" + bodyName
                + "\",\"BodyID\":" + bodyId + ",\"StarSystem\":\"" + system
                + "\",\"SystemAddress\":" + address
                + ",\"DistanceFromArrivalLS\":" + distance
                + ",\"StarType\":\"" + starType + "\",\"Subclass\":0,"
                + "\"StellarMass\":4.246094,\"Age_MY\":158,"
                + "\"SurfaceTemperature\":13334.0,"
                + "\"WasDiscovered\":" + discovered + ",\"WasMapped\":false,"
                + "\"WasFootfalled\":false}";
    }

    /**
     * The entry that started this, verbatim in shape.
     *
     * <p>A T Tauri star, filed by the journal under body 0 — which the scan of
     * this system reports as a B star.</p>
     */
    private static String tTauriCodexEntry(String time) {
        return "{\"timestamp\":\"2026-07-30T" + time + "\","
                + "\"event\":\"CodexEntry\",\"EntryID\":1101001,"
                + "\"Name\":\"$Codex_Ent_TTS_Type_Name;\","
                + "\"Name_Localised\":\"T Tauri Type Star\","
                + "\"SubCategory\":\"$Codex_SubCategory_Stars;\","
                + "\"SubCategory_Localised\":\"Stars\","
                + "\"Category\":\"$Codex_Category_StellarBodies;\","
                + "\"Category_Localised\":\"Astronomical Bodies\","
                + "\"Region\":\"$Codex_RegionName_9;\","
                + "\"Region_Localised\":\"Inner Scutum-Centaurus Arm\","
                + "\"System\":\"" + SYSTEM + "\",\"SystemAddress\":" + ADDRESS
                + ",\"BodyID\":0,\"IsNewEntry\":true}";
    }

    /** The exact document the provider was last given. */
    private static String document(DecisionProductionPipeline pipeline) {
        String message = pipeline.modelInputs().getLast().userMessage();
        return message.substring(message.indexOf('{'));
    }
}
