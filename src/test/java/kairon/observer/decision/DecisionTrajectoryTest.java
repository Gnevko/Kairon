package kairon.observer.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kairon.behavior.normalize.NormalizedEventType;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.event.exploration.FSSAllBodiesFound;
import kairon.observation.journal.event.exploration.SAAScanComplete;
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
import kairon.observation.journal.event.travel.Embark;
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
                        "BIOLOGICAL_SAMPLE_STARTED",
                        "EMBARKED",
                        "LIFTOFF"
                ),
                recent(request),
                "the three immediate predecessors, temporal order"
        );
        assertFalse(
                recent(request).contains("TOUCHDOWN"),
                "the event that is happening is not also a predecessor"
        );
        assertFalse(
                recent(request).contains("SYSTEM_ENTERED"),
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

        assertEquals(List.of("SYSTEM_ENTERED"), recent(request));
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
            ).request()));

            assertEquals(
                    List.of(
                            "A ship in supercruise came within a body's orbital-cruise zone.",
                            "A ship landed on the surface of a planet or moon."
                    ),
                    descriptions(request),
                    "both are current events"
            );
            assertEquals(
                    List.of("SYSTEM_ENTERED"),
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
        ).request()));

        JsonNode likelyNext = request.path("trajectory").path("likelyNext");
        assertEquals(1, likelyNext.size());
        assertEquals("DISEMBARKED", likelyNext.get(0).path("kind").textValue());
        assertEquals(
                1.0,
                likelyNext.get(0).path("probability").doubleValue(),
                0.0,
                "the transition model's own number, not a rounding of it"
        );
        assertEquals(
                List.of("kind", "probability"),
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
        ).request()));

        List<String> predicted = new ArrayList<>();
        request.path("trajectory").path("likelyNext").forEach(prediction ->
                predicted.add(prediction.path("kind").textValue()));
        assertEquals(
                List.of("DISEMBARKED", "EMBARKED", "BODY_LEFT"),
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
        ).request());

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
        ).request());

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
        ).request());

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

        assertEquals(List.of("SYSTEM_ENTERED", "LIFTOFF"), recent(request));
        assertFalse(request.toString().contains("UNKNOWN"));
    }

    // -------------------------------------------------------- the vocabulary

    /**
     * A remembered event is called what the same event is called when it
     * happens.
     *
     * <p>Two tables name the same things — one keyed by journal class for
     * current events, one by normalized type for remembered ones — and a model
     * told a landing is a {@code TOUCHDOWN} and then that it was preceded by
     * something else spelled differently would be reading two vocabularies.</p>
     */
    @Test
    void everyRememberedEventUsesTheSameNameTheEventItselfUses() {
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

        sameConcept.forEach((eventType, journalType) -> assertEquals(
                DecisionEventCatalog.ruleFor(journalType).kind(),
                DecisionTrajectoryNames.kindOf(eventType),
                eventType + " is named differently when it is remembered"
        ));
    }

    /**
     * The three sampling steps refine one catalogued kind rather than replace
     * it.
     *
     * <p>A current sample carries a stage; a remembered one cannot, so the
     * position is folded into the name. Both still start from the same word.</p>
     */
    @Test
    void aRememberedSampleKeepsTheKindAndFoldsInTheStage() {
        String kind = "BIOLOGICAL_SAMPLE";
        for (NormalizedEventType step : List.of(
                NormalizedEventType.SCAN_ORGANIC_LOG,
                NormalizedEventType.SCAN_ORGANIC_SAMPLE,
                NormalizedEventType.SCAN_ORGANIC_ANALYSE
        )) {
            String name = DecisionTrajectoryNames.kindOf(step);
            assertTrue(name.startsWith(kind + "_"), name);
        }
        assertEquals(
                List.of(
                        "BIOLOGICAL_SAMPLE_STARTED",
                        "BIOLOGICAL_SAMPLE_CONTINUED",
                        "BIOLOGICAL_SAMPLE_COMPLETED"
                ),
                List.of(
                        DecisionTrajectoryNames.kindOf(
                                NormalizedEventType.SCAN_ORGANIC_LOG
                        ),
                        DecisionTrajectoryNames.kindOf(
                                NormalizedEventType.SCAN_ORGANIC_SAMPLE
                        ),
                        DecisionTrajectoryNames.kindOf(
                                NormalizedEventType.SCAN_ORGANIC_ANALYSE
                        )
                )
        );
    }

    /**
     * Every type the graph can hold has a name here.
     *
     * <p>A declared type with no entry would be silently dropped from every
     * trajectory it appeared in, which reads as "nothing happened then".</p>
     */
    @Test
    void everyDeclaredNormalizedTypeHasADomainName() throws Exception {
        List<String> missing = new ArrayList<>();
        for (Field field : NormalizedEventType.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())
                    || field.getType() != NormalizedEventType.class) {
                continue;
            }
            NormalizedEventType declared =
                    (NormalizedEventType) field.get(null);
            if (DecisionTrajectoryNames.kindOf(declared) == null) {
                missing.add(declared.value());
            }
        }
        assertEquals(List.of(), missing);
    }

    /** Every name is a domain name, in the casing the contract uses. */
    @Test
    void everyDomainNameIsUpperSnakeCase() {
        DecisionTrajectoryNames.names().forEach((eventType, kind) -> {
            assertNotNull(kind);
            assertTrue(
                    kind.matches("[A-Z][A-Z_]*[A-Z]"),
                    kind + " is not a stable domain-facing name"
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
        ).request()));
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
