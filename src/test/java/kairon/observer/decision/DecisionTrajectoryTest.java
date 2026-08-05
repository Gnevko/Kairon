package kairon.observer.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kairon.behavior.normalize.NormalizedEventType;
import kairon.observation.ObservationDraft.ObservationCaptureMode;
import kairon.observation.ObservationDraft.ObservationSource;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalLineParser;
import kairon.observation.journal.JournalObservationAdapter;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.event.exploration.FSSAllBodiesFound;
import kairon.observation.journal.event.exploration.FSSBodySignals;
import kairon.observation.journal.event.exploration.SAAScanComplete;
import kairon.observation.journal.event.exploration.SAASignalsFound;
import kairon.observation.journal.event.exploration.Scan;
import kairon.observation.journal.event.inventory.MaterialCollected;
import kairon.observation.journal.event.combat.Interdicted;
import kairon.observation.journal.event.combat.UnderAttack;
import kairon.observation.journal.event.mission.MissionAbandoned;
import kairon.observation.journal.event.mission.MissionAccepted;
import kairon.observation.journal.event.mission.MissionCompleted;
import kairon.observation.journal.event.mission.MissionFailed;
import kairon.observation.journal.event.ship.DockSRV;
import kairon.observation.journal.event.ship.LaunchFighter;
import kairon.observation.journal.event.trade.MarketBuy;
import kairon.observation.journal.event.trade.MarketSell;
import kairon.observation.journal.event.trade.RedeemVoucher;
import kairon.observation.journal.event.travel.ApproachBody;
import kairon.observation.journal.event.travel.Disembark;
import kairon.observation.journal.event.travel.Docked;
import kairon.observation.journal.event.travel.DockingGranted;
import kairon.observation.journal.event.travel.DockingRequested;
import kairon.observation.journal.event.travel.Embark;
import kairon.observation.journal.event.travel.FSDJump;
import kairon.observation.journal.event.travel.FSDTarget;
import kairon.observation.journal.event.travel.FuelScoop;
import kairon.observation.journal.event.travel.LeaveBody;
import kairon.observation.journal.event.travel.Liftoff;
import kairon.observation.journal.event.travel.SupercruiseEntry;
import kairon.observation.journal.event.travel.SupercruiseExit;
import kairon.observation.journal.event.travel.Touchdown;
import kairon.observation.journal.event.travel.Undocked;
import kairon.observer.decision.DecisionTurnFixture.TrajectoryEntry;
import kairon.projection.ProjectedObservation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What led here and what usually follows, as the provider receives it.
 *
 * <p>The trajectory is the one part of a request Kairon remembers rather than
 * observes, so the cases here are as much about what it refuses to say — an
 * occurrence identity, a cursor, an evidence count, a normalized spelling — as
 * about the two lists it does.</p>
 */
final class DecisionTrajectoryTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final LlmDecisionRequestFactory factory =
            new LlmDecisionRequestFactory();
    private final JacksonDecisionRequestSerializer serializer =
            new JacksonDecisionRequestSerializer();

    /** Three at most, oldest first, and never the event that is happening. */
    @Test
    void recentCarriesTheThreeEventsBeforeThisOneInOrder() {
        JsonNode request = touchdownAfter(List.of(
                NormalizedEventType.SYSTEM_ENTRY,
                NormalizedEventType.APPROACH_BODY,
                NormalizedEventType.SCAN_ORGANIC_LOG,
                NormalizedEventType.EMBARK,
                NormalizedEventType.LIFTOFF,
                NormalizedEventType.TOUCHDOWN
        ));

        assertEquals(
                List.of(
                        "The organic sampling tool logged the first scan of an unfinished sampling sequence.",
                        "The Commander, on foot, got into a ship or SRV.",
                        "A ship took off from the surface of a planet or moon."
                ),
                recent(request),
                "the three immediate predecessors, temporal order"
        );
        assertFalse(
                recent(request).contains("A ship landed on the surface of a planet or moon."),
                "the event that is happening is not also a predecessor"
        );
        assertFalse(
                recent(request).contains("A ship jumped from one star system to another."),
                "the fourth-oldest is out of range, not summarised"
        );
    }

    /** A short episode reports what it has rather than padding. */
    @Test
    void aShortHistoryIsReportedShort() {
        JsonNode request = touchdownAfter(List.of(
                NormalizedEventType.SYSTEM_ENTRY,
                NormalizedEventType.TOUCHDOWN
        ));

        assertEquals(List.of("A ship jumped from one star system to another."), recent(request));
    }

    /**
     * No event of this batch appears as its own predecessor.
     *
     * <p>Run against the real graph, because the claim is about occurrence
     * identity: both triggers committed an occurrence, both are in the final
     * trigger's remembered trajectory, and both have to be recognised as this
     * turn's own rather than as history.</p>
     */
    @Test
    void noEventOfThisBatchIsAlsoItsOwnHistory(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            pipeline.journal("""
                    {"timestamp":"2026-07-30T10:00:00Z","event":"LoadGame",
                     "FID":"F12345678","ShipID":9,"Ship":"explorer_nx",
                     "ShipName":"Wanderer"}
                    """);
            // A real arrival, so there is a predecessor to remember at all:
            // a restored session records no entry of its own.
            pipeline.journal("""
                    {"timestamp":"2026-07-30T10:00:01Z","event":"FSDJump",
                     "StarSystem":"Schieni GG-A c3-84","SystemAddress":23155}
                    """);
            pipeline.journal("""
                    {"timestamp":"2026-07-30T10:00:02Z","event":"ApproachBody",
                     "StarSystem":"Schieni GG-A c3-84","SystemAddress":23155,
                     "Body":"Schieni GG-A c3-84 4 a","BodyID":20}
                    """);
            pipeline.journal("""
                    {"timestamp":"2026-07-30T10:00:03Z","event":"Touchdown",
                     "StarSystem":"Schieni GG-A c3-84","SystemAddress":23155,
                     "Body":"Schieni GG-A c3-84 4 a","BodyID":20,
                     "PlayerControlled":true,"OnStation":false,"OnPlanet":true}
                    """);
            pipeline.settleProjection();

            List<ProjectedObservation> triggers = pipeline.capturedTriggers();
            JsonNode request = read(serializer.serialize(factory.create(
                    pipeline.inputsFor(List.of(
                            triggers.get(triggers.size() - 2),
                            triggers.getLast()
                    ))
            )));

            assertEquals(
                    List.of(
                            "A ship in supercruise came within a body's orbital-cruise zone.",
                            "A ship landed on the surface of a planet or moon."
                    ),
                    descriptions(request),
                    "both are current events"
            );
            assertEquals(
                    List.of("A ship jumped from one star system to another."),
                    recent(request),
                    "neither current event is repeated as its own predecessor"
            );
        }
    }

    /** The calculated probability arrives as calculated, under a domain name. */
    @Test
    void likelyNextCarriesADomainKindAndTheGraphsOwnProbability() {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        JsonNode request = read(serializer.serialize(factory.create(
                fixture.inputs(List.of(fixture.graphedPredicting(
                        touchdown(),
                        List.of(
                                TrajectoryEntry.journal(
                                        NormalizedEventType.SYSTEM_ENTRY
                                ),
                                TrajectoryEntry.journal(
                                        NormalizedEventType.TOUCHDOWN
                                )
                        ),
                        List.of(NormalizedEventType.DISEMBARK)
                )))
        )));

        JsonNode likelyNext = request.path("trajectory").path("likelyNext");
        assertEquals(1, likelyNext.size());
        assertEquals("The Commander stepped out of a ship or SRV.", likelyNext.get(0).path("event").textValue());
        assertEquals(
                1.0,
                likelyNext.get(0).path("probability").doubleValue(),
                0.0,
                "the transition model's own number, not a rounding of it"
        );
        assertEquals(
                List.of("event", "probability"),
                propertyNames(likelyNext.get(0)),
                "nothing that supports the number travels with it"
        );
    }

    /** More than three would be a transcript of the model, not a hint. */
    @Test
    void likelyNextIsCappedAtThreeInTheCalculationsOwnOrder() {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        JsonNode request = read(serializer.serialize(factory.create(
                fixture.inputs(List.of(fixture.graphedPredicting(
                        touchdown(),
                        List.of(
                                TrajectoryEntry.journal(
                                        NormalizedEventType.SYSTEM_ENTRY
                                ),
                                TrajectoryEntry.journal(
                                        NormalizedEventType.TOUCHDOWN
                                )
                        ),
                        // Equal shares, so the snapshot's deterministic order is
                        // the ascending one it validates against.
                        List.of(
                                NormalizedEventType.DISEMBARK,
                                NormalizedEventType.EMBARK,
                                NormalizedEventType.LEAVE_BODY,
                                NormalizedEventType.LIFTOFF
                        )
                )))
        )));

        List<String> predicted = new ArrayList<>();
        request.path("trajectory").path("likelyNext").forEach(prediction ->
                predicted.add(prediction.path("event").textValue()));
        assertEquals(
                List.of(
                        "The Commander stepped out of a ship or SRV.",
                        "The Commander, on foot, got into a ship or SRV.",
                        "A ship flying away from a body rose above its "
                                + "orbital-cruise altitude."
                ),
                predicted
        );
    }

    /** Nothing to predict is said by saying nothing. */
    @Test
    void noPredictionOmitsLikelyNextEntirely() {
        JsonNode request = touchdownAfter(List.of(
                NormalizedEventType.SYSTEM_ENTRY,
                NormalizedEventType.TOUCHDOWN
        ));

        assertFalse(request.path("trajectory").has("likelyNext"));
        assertTrue(request.path("trajectory").has("recent"));
    }

    /** With no episode there is no trajectory, not an empty one. */
    @Test
    void aTurnWithNoRememberedEpisodeSendsNoTrajectory() {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        String serialized = serializer.serialize(factory.create(
                fixture.inputs(List.of(fixture.graphDisabled(touchdown())))
        ));

        assertFalse(serialized.contains("trajectory"));
        assertFalse(serialized.contains("recent"));
    }

    /**
     * No identity, position or supporting count reaches the model.
     *
     * <p>The occurrence ids, the cursor, the episode, the graph revision and
     * the evidence counts behind a probability are all present in the capture
     * this request was built from. None of them is a thing a spoken sentence
     * can rest on.</p>
     */
    @Test
    void noInternalGraphIdentityReachesTheProvider() {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        String serialized = serializer.serialize(factory.create(
                fixture.inputs(List.of(fixture.graphedPredicting(
                        touchdown(),
                        List.of(
                                TrajectoryEntry.journal(
                                        NormalizedEventType.SYSTEM_ENTRY
                                ),
                                TrajectoryEntry.journal(
                                        NormalizedEventType.LIFTOFF
                                ),
                                TrajectoryEntry.journal(
                                        NormalizedEventType.TOUCHDOWN
                                ).at(23155L, 20L)
                        ),
                        List.of(NormalizedEventType.DISEMBARK)
                )))
        ));

        for (String internal : List.of(
                "occurrenceId",
                "occurrence-",
                "episodeSequence",
                "episodeId",
                "episode-decision",
                "cursor",
                "graphId",
                "F-DECISION",
                "source",
                "matchesFinalTrigger",
                "basis",
                "contextKey",
                "observedTransitionCount",
                "effectiveWeight",
                "omittedOccurrenceCount",
                "totalOccurrenceCount",
                "activeEventCounts",
                "currentEventType",
                "bgo1-",
                "busSequence"
        )) {
            assertFalse(
                    serialized.contains(internal),
                    internal + " reached the provider: " + serialized
            );
        }
    }

    /** No normalized spelling survives where a domain name differs from it. */
    @Test
    void noRememberedEventKeepsItsNormalizedSpelling() {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        String serialized = serializer.serialize(factory.create(
                fixture.inputs(List.of(fixture.graphedPredicting(
                        touchdown(),
                        List.of(
                                TrajectoryEntry.journal(
                                        NormalizedEventType.SYSTEM_ENTRY
                                ),
                                TrajectoryEntry.journal(
                                        NormalizedEventType.SCAN_ORGANIC_LOG
                                ),
                                TrajectoryEntry.journal(
                                        NormalizedEventType.APPROACH_BODY
                                ),
                                TrajectoryEntry.journal(
                                        NormalizedEventType
                                                .AUXILIARY_VEHICLE_LAUNCHED
                                ),
                                TrajectoryEntry.journal(
                                        NormalizedEventType.TOUCHDOWN
                                )
                        ),
                        List.of(NormalizedEventType.DISEMBARK)
                )))
        ));

        for (String spelling : List.of(
                "SYSTEM_ENTRY",
                "SCAN_ORGANIC_LOG",
                "APPROACH_BODY",
                "AUXILIARY_VEHICLE_LAUNCHED",
                "DISEMBARK\"",
                "SUPERCRUISE_ENTRY",
                "SUPERCRUISE_EXIT",
                "FUEL_SCOOPING",
                "FSS_MODE_ENTERED",
                "SAA_SIGNALS_FOUND"
        )) {
            assertFalse(
                    serialized.contains(spelling),
                    spelling + " reached the provider: " + serialized
            );
        }
    }

    /** An unresearched type is dropped rather than named by its raw spelling. */
    @Test
    void anUnnamedTypeIsLeftOutRatherThanGuessed() {
        JsonNode request = touchdownAfter(List.of(
                NormalizedEventType.SYSTEM_ENTRY,
                NormalizedEventType.unknown("ScanOrganic"),
                NormalizedEventType.LIFTOFF,
                NormalizedEventType.TOUCHDOWN
        ));

        assertEquals(List.of("A ship jumped from one star system to another.", "A ship took off from the surface of a "
                + "planet or moon."), recent(request));
        assertFalse(request.toString().contains("UNKNOWN"));
    }

    // -------------------------------------------------------- the vocabulary

    /**
     * A remembered event says what the same event says when it happens.
     *
     * <p>Two tables describe the same things — the journal classes for current
     * events, {@link DecisionTrajectoryDescriptions} for remembered and
     * predicted ones — and a model told a landing in one wording and then that
     * it was preceded by the same landing in another would be reading two
     * vocabularies.</p>
     *
     * <p>Compared against the sentence the class actually returns, built by
     * parsing a minimal record of it. That is checkable at all because a class
     * now means one domain event and its sentence is a constant; while one
     * class could mean several, there was nothing to compare against but
     * another identifier.</p>
     */
    @Test
    void everyRememberedEventSaysWhatTheEventItselfSays() {
        Map<NormalizedEventType, Class<? extends JournalEventObservation>>
                sameConcept = new LinkedHashMap<>();
        sameConcept.put(NormalizedEventType.SUPERCRUISE_ENTRY,
                SupercruiseEntry.class);
        sameConcept.put(NormalizedEventType.SUPERCRUISE_EXIT,
                SupercruiseExit.class);
        sameConcept.put(NormalizedEventType.APPROACH_BODY, ApproachBody.class);
        sameConcept.put(NormalizedEventType.LEAVE_BODY, LeaveBody.class);
        sameConcept.put(NormalizedEventType.TOUCHDOWN, Touchdown.class);
        sameConcept.put(NormalizedEventType.LIFTOFF, Liftoff.class);
        sameConcept.put(NormalizedEventType.DISEMBARK, Disembark.class);
        sameConcept.put(NormalizedEventType.EMBARK, Embark.class);
        // Both LaunchFighter and LaunchSRV normalize to this one type, and the
        // catalogued one of the two is deliberately named for a vehicle rather
        // than a fighter: the record does not establish which went out.
        sameConcept.put(NormalizedEventType.AUXILIARY_VEHICLE_LAUNCHED,
                LaunchFighter.class);
        sameConcept.put(NormalizedEventType.AUXILIARY_VEHICLE_DOCKED,
                DockSRV.class);
        sameConcept.put(NormalizedEventType.DOCKED, Docked.class);
        sameConcept.put(NormalizedEventType.UNDOCKED, Undocked.class);
        sameConcept.put(NormalizedEventType.SAA_SCAN_COMPLETE,
                SAAScanComplete.class);
        sameConcept.put(NormalizedEventType.FSS_ALL_BODIES_FOUND,
                FSSAllBodiesFound.class);
        sameConcept.put(NormalizedEventType.MARKET_BUY, MarketBuy.class);
        sameConcept.put(NormalizedEventType.MARKET_SELL, MarketSell.class);
        sameConcept.put(NormalizedEventType.REDEEM_VOUCHER,
                RedeemVoucher.class);
        sameConcept.put(NormalizedEventType.INTERDICTED, Interdicted.class);
        sameConcept.put(NormalizedEventType.UNDER_ATTACK, UnderAttack.class);
        sameConcept.put(NormalizedEventType.MISSION_ACCEPTED,
                MissionAccepted.class);
        sameConcept.put(NormalizedEventType.MISSION_COMPLETED,
                MissionCompleted.class);
        sameConcept.put(NormalizedEventType.MISSION_FAILED,
                MissionFailed.class);
        sameConcept.put(NormalizedEventType.MISSION_ABANDONED,
                MissionAbandoned.class);

        sameConcept.put(NormalizedEventType.BODY_SCANNED, Scan.class);
        sameConcept.put(NormalizedEventType.FSS_BODY_SIGNALS_FOUND,
                FSSBodySignals.class);
        sameConcept.put(NormalizedEventType.SAA_SIGNALS_FOUND,
                SAASignalsFound.class);
        sameConcept.put(NormalizedEventType.DOCKING_REQUESTED,
                DockingRequested.class);
        sameConcept.put(NormalizedEventType.DOCKING_GRANTED,
                DockingGranted.class);
        sameConcept.put(NormalizedEventType.MATERIAL_COLLECTED,
                MaterialCollected.class);
        sameConcept.put(NormalizedEventType.FUEL_SCOOPING, FuelScoop.class);
        sameConcept.put(NormalizedEventType.FSD_TARGET_SELECTED,
                FSDTarget.class);
        sameConcept.put(NormalizedEventType.SYSTEM_ENTRY, FSDJump.class);

        sameConcept.forEach((eventType, journalType) -> assertEquals(
                describes(journalType),
                DecisionTrajectoryDescriptions.descriptionOf(eventType),
                eventType + " is described differently when it is remembered"
        ));
    }

    /**
     * What a journal class says about itself, from a minimal record of it.
     *
     * <p>Minimal on purpose: the sentence is a constant of the class and must
     * not depend on any value the record supplies. A record with fields would
     * hide a sentence that read one.</p>
     */
    private static String describes(
            Class<? extends JournalEventObservation> journalType
    ) {
        String discriminator;
        try {
            discriminator = (String) journalType.getField("EVENT_TYPE")
                    .get(null);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(journalType.getName(), failure);
        }
        String rawJson = """
                {"timestamp":"2026-07-30T10:00:00Z","event":"%s"}
                """.formatted(discriminator);
        JournalLineParser.ParsedJournalRecord parsed =
                (JournalLineParser.ParsedJournalRecord) new JournalLineParser()
                        .parse(new JournalLineParser.CompleteJournalRecord(
                                "Journal.trajectory-test.log",
                                0L,
                                rawJson.strip()
                                        .getBytes(StandardCharsets.UTF_8)
                        ));
        JournalEventObservation event = new JournalObservationAdapter(
                new ObservationSource("elite-journal", "trajectory-test")
        ).adapt(
                parsed,
                ObservationCaptureMode.REPLAY,
                parsed.optionalJournalTimestamp().orElseThrow()
        ).payload();
        return ((LlmPresentableJournalEvent) event).modelFacingDescription();
    }

    /**
     * The three sampling steps refine one catalogued kind rather than replace
     * it.
     *
     * <p>A current sample carries a stage; a remembered one cannot, so the
     * position is folded into the name. Both still start from the same word.</p>
     */
    @Test
    void aRememberedSampleSaysWhichStepItWas() {
        for (NormalizedEventType step : List.of(
                NormalizedEventType.SCAN_ORGANIC_LOG,
                NormalizedEventType.SCAN_ORGANIC_SAMPLE,
                NormalizedEventType.SCAN_ORGANIC_ANALYSE
        )) {
            String said = DecisionTrajectoryDescriptions.descriptionOf(step);
            assertTrue(
                    said.startsWith("The organic sampling tool "),
                    said + " does not start from the same tool"
            );
        }
        assertEquals(
                List.of(
                        "The organic sampling tool logged the first scan of "
                                + "an unfinished sampling sequence.",
                        "The organic sampling tool recorded a subsequent scan "
                                + "of an unfinished sampling sequence.",
                        "The organic sampling tool recorded the final scan "
                                + "and completed a sampling sequence."
                ),
                List.of(
                        DecisionTrajectoryDescriptions.descriptionOf(
                                NormalizedEventType.SCAN_ORGANIC_LOG
                        ),
                        DecisionTrajectoryDescriptions.descriptionOf(
                                NormalizedEventType.SCAN_ORGANIC_SAMPLE
                        ),
                        DecisionTrajectoryDescriptions.descriptionOf(
                                NormalizedEventType.SCAN_ORGANIC_ANALYSE
                        )
                )
        );
    }

    /**
     * Every type the graph can hold says something here.
     *
     * <p>A declared type with no entry would be silently dropped from every
     * trajectory it appeared in, which reads as "nothing happened then".</p>
     */
    @Test
    void everyDeclaredNormalizedTypeSaysSomething() throws Exception {
        List<String> missing = new ArrayList<>();
        for (Field field : NormalizedEventType.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())
                    || field.getType() != NormalizedEventType.class) {
                continue;
            }
            NormalizedEventType declared =
                    (NormalizedEventType) field.get(null);
            if (DecisionTrajectoryDescriptions.descriptionOf(declared) == null) {
                missing.add(declared.value());
            }
        }
        assertEquals(List.of(), missing);
    }

    /**
     * Every entry is a sentence, and none of them is an internal spelling.
     *
     * <p>This used to require the opposite — upper snake case, which is what
     * the vocabulary was. The check is kept and inverted rather than deleted,
     * because a single entry left behind as an identifier is exactly what would
     * otherwise go unnoticed among fifty-four sentences.</p>
     */
    @Test
    void everyEntryIsASentenceRatherThanAnIdentifier() {
        DecisionTrajectoryDescriptions.descriptions()
                .forEach((eventType, said) -> {
                    assertNotNull(said);
                    assertTrue(
                            Character.isUpperCase(said.charAt(0))
                                    && said.endsWith("."),
                            eventType + " does not say a sentence: " + said
                    );
                    assertFalse(
                            said.contains("_"),
                            eventType + " says an internal spelling: " + said
                    );
                    assertTrue(
                            said.contains(" "),
                            eventType + " says one word: " + said
                    );
                });
    }

    // ------------------------------------------------------------- fixtures

    private JsonNode touchdownAfter(List<NormalizedEventType> trajectory) {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        return read(serializer.serialize(factory.create(
                fixture.inputs(List.of(
                        fixture.graphed(touchdown(), trajectory, true, 0)
                ))
        )));
    }

    private static String touchdown() {
        return """
                {"timestamp":"2026-07-30T10:00:00Z","event":"Touchdown",
                 "StarSystem":"Schieni GG-A c3-84","SystemAddress":23155,
                 "Body":"Schieni GG-A c3-84 4 a","BodyID":20,
                 "PlayerControlled":true,"OnStation":false,"OnPlanet":true}
                """;
    }

    private static List<String> recent(JsonNode request) {
        List<String> recent = new ArrayList<>();
        request.path("trajectory").path("recent")
                .forEach(kind -> recent.add(kind.textValue()));
        return List.copyOf(recent);
    }

    private static List<String> descriptions(JsonNode request) {
        List<String> descriptions = new ArrayList<>();
        request.path("events").forEach(event ->
                descriptions.add(event.path("event").textValue()));
        return List.copyOf(descriptions);
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
