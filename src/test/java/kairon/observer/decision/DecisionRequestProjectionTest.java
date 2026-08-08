package kairon.observer.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kairon.projection.ProjectedObservation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the provider actually receives, built through the production path.
 *
 * <p>Every case here is a claim about what a decision needs. The assertions are
 * therefore as much about what is absent as about what is present: a field that
 * survives has to earn it, and a field that was removed has to stay removed.</p>
 */
final class DecisionRequestProjectionTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final LlmDecisionRequestFactory factory =
            new LlmDecisionRequestFactory();
    private final JacksonDecisionRequestSerializer serializer =
            new JacksonDecisionRequestSerializer();

    /**
     * The opening turn of the measured replay, which used to be 818 characters
     * of mostly structure.
     */
    @Test
    void twoFriendNotificationsBecomeTwoEventsAndNothingElse() {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        JsonNode request = request(fixture, List.of(
                fixture.graphDisabled(friends("KotyaGaw")),
                fixture.graphDisabled(friends("Alysianfolly"))
        ));

        assertEquals(2, request.path("events").size());
        assertEquals(List.of("events"), propertyNames(request));

        JsonNode first = request.path("events").get(0);
        assertFalse(first.has("id"), "an event is not identified to the model");
        assertEquals(
                    "Information about a friend's status was received.",
                    first.path("event").textValue());
        assertEquals("KotyaGaw", first.path("friend").textValue());
        assertEquals(
                "ONLINE",
                first.path("status").textValue(),
                "a closed vocabulary is sent in the contract's own casing"
        );
        assertEquals(
                List.of("event", "friend", "status"),
                propertyNames(first),
                "the event reports a current status and nothing else"
        );
        assertFalse(request.path("events").get(1).has("id"));
    }

    /**
     * The event reports a status, not a transition into it.
     *
     * <p>The game emits {@code Online} at startup for a friend who was already
     * online, so Kairon cannot establish that a login just happened — and
     * records that internally. It is not sent, because the event never claimed
     * a transition: qualifying an absent claim would introduce it.</p>
     */
    @Test
    void aFriendStatusNeverQualifiesATransitionItDidNotClaim() {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        ProjectedObservation online = fixture.graphDisabled(friends("KotyaGaw"));
        String serialized = serialize(fixture, List.of(online));

        assertFalse(serialized.contains("loginTransition"));
        assertFalse(serialized.contains("newlyOnline"));
        assertFalse(serialized.contains("UNCONFIRMED"));
        assertFalse(serialized.contains("LOGIN_TRANSITION"));
        assertTrue(
                online.semanticEnvelope().unresolvedFacts().stream()
                        .anyMatch(gap -> gap.reason()
                                == kairon.semantics.UnresolvedFact.Reason
                                .LOGIN_TRANSITION_NOT_ESTABLISHED),
                "the gap is still recorded internally for diagnostics"
        );
    }

    /** A friend is a third party; the default entity name would mislead. */
    @Test
    void aFriendIsNotCalledTheCommander() {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        JsonNode request = request(fixture, List.of(
                fixture.graphDisabled(friends("KotyaGaw"))
        ));

        assertFalse(request.path("events").get(0).has("commander"));
    }

    /**
     * The event that opens a session, and the account identifier it must lose.
     *
     * <p>The journal reports which Commander took up the current session, not a
     * fresh recognition of an identity, and the only name it carries is the
     * Commander's own — so the kind says session and the field says commander.
     * A turn like this carries nothing else at all: every field it establishes
     * is being learned for the first time, which is not news.</p>
     */
    @Test
    void theSessionEventNamesTheCommanderAndNeverTheAccount() {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        String serialized = serialize(fixture, List.of(
                fixture.graphDisabled("""
                        {"timestamp":"2026-07-30T10:00:00Z",
                         "event":"Commander","FID":"F12345678",
                         "Name":"TESTCMDR"}
                        """)
        ));

        assertFalse(serialized.contains("F12345678"));
        assertFalse(serialized.contains("fid"));
        assertFalse(serialized.contains("COMMANDER_IDENTIFIED"));
        JsonNode request = read(serialized);
        JsonNode event = request.path("events").get(0);
        assertEquals(
                "The Commander came aboard, and this session began.",
                event.path("event").textValue()
        );
        assertEquals("TESTCMDR", event.path("commander").textValue());
        assertFalse(
                event.has("name"),
                "a bare name leaves the model to work out whose it is"
        );
        assertEquals(
                List.of("event", "commander"),
                propertyNames(event)
        );
        assertEquals(
                List.of("events"),
                propertyNames(request),
                "session bootstrap adds no changes and no context"
        );
    }

    /**
     * A message is understandable on its own.
     *
     * <p>The Commander has already read it. Sending the ship, the system and
     * the body alongside invites the model to weave them into a remark about a
     * line of chat.</p>
     */
    @Test
    void aReceivedMessageCarriesNoHiddenStartupState() {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        fixture.graphDisabled("""
                {"timestamp":"2026-07-30T10:00:00Z","event":"LoadGame",
                 "FID":"F12345678","ShipID":9,"Ship":"explorer_nx",
                 "ShipName":"Wanderer"}
                """);
        fixture.graphDisabled("""
                {"timestamp":"2026-07-30T10:00:01Z","event":"Location",
                 "StarSystem":"Schieni GG-A c3-84","SystemAddress":23155,
                 "Docked":false}
                """);
        JsonNode request = request(fixture, List.of(
                fixture.graphDisabled("""
                        {"timestamp":"2026-07-30T10:00:02Z",
                         "event":"ReceiveText","Channel":"squadron",
                         "From":"OLKI","Message_Localised":"Nabend CMDRs o7"}
                        """)
        ));

        JsonNode event = request.path("events").get(0);
        assertEquals(
                    "Another player sent a text message to a channel the Commander is in.",
                    event.path("event").textValue());
        assertEquals("OLKI", event.path("sender").textValue());
        assertEquals(
                "SQUADRON",
                event.path("channel").textValue(),
                "a closed vocabulary is sent in the contract's own casing"
        );
        assertEquals("Nabend CMDRs o7", event.path("message").textValue());
        assertFalse(
                request.has("context"),
                "a squadron greeting needs no state at all"
        );
        assertFalse(request.has("graphContext"));
        assertFalse(request.toString().contains("explorer_nx"));
        assertFalse(request.toString().contains("Schieni"));
    }

    /**
     * The flight mode is stated once — by whichever half actually says it.
     *
     * <p>An entry into supercruise says so in its own sentence, and the
     * navigation context repeating {@code flightMode: SUPERCRUISE} beside it was
     * the same fact twice in one document. A completed jump also leaves the ship
     * in supercruise and its sentence does not say so, which is why the claim is
     * declared per event and not on the mechanism they share: the second half of
     * this test is what makes the first half a claim about wording rather than
     * about the field.</p>
     */
    @Test
    void theFlightModeIsContextOnlyWhereTheEventDoesNotSayIt() {
        DecisionTurnFixture entered = new DecisionTurnFixture();
        JsonNode afterEntry = request(entered, List.of(
                entered.graphDisabled("""
                        {"timestamp":"2026-07-30T10:00:00Z",
                         "event":"SupercruiseEntry","StarSystem":"Schieni GG-A",
                         "SystemAddress":23155}
                        """)
        ));

        assertFalse(
                afterEntry.path("context").has("navigation"),
                "the event already said the ship is in supercruise"
        );

        DecisionTurnFixture jumped = new DecisionTurnFixture();
        JsonNode afterJump = request(jumped, List.of(
                jumped.graphDisabled("""
                        {"timestamp":"2026-07-30T10:00:00Z","event":"FSDJump",
                         "StarSystem":"Schieni GG-A c3-84","SystemAddress":23155,
                         "JumpDist":24.5,"FuelUsed":1.2,"BoostUsed":false}
                        """)
        ));

        assertEquals(
                "SUPERCRUISE",
                afterJump.path("context").path("navigation")
                        .path("flightMode").textValue(),
                "a jump leaves the ship in supercruise without saying so"
        );
    }

    /**
     * The turn that produced the previous run's one factual error.
     *
     * <p>{@code ProbesUsed} arrived as an unnamed {@code quantity: 2} beside an
     * {@code efficiencyTarget: 2}, and the model reported the target as the
     * probe count. Both numbers are now named.</p>
     */
    @Test
    void aCompletedSurfaceMapNamesTheProbesItUsed() {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        JsonNode request = request(fixture, List.of(
                fixture.graphDisabled("""
                        {"timestamp":"2026-07-30T10:00:00Z",
                         "event":"SAAScanComplete","BodyName":"Test Body 4 a",
                         "BodyID":20,"SystemAddress":23155,
                         "ProbesUsed":2,"EfficiencyTarget":3}
                        """)
        ));

        JsonNode event = request.path("events").get(0);
        assertEquals(
                "A surface area analysis scan of a body was completed.",
                event.path("event").textValue()
        );
        assertEquals("Test Body 4 a", event.path("body").textValue());
        assertEquals(2, event.path("probesUsed").intValue());
        assertEquals(3, event.path("efficiencyTarget").intValue());
        assertFalse(
                event.has("quantity"),
                "an unnamed number is what the model misread"
        );
        assertFalse(event.has("complete"));
        assertFalse(event.has("stage"));
    }

    /**
     * The sampling sequence, which is the one mechanism with real stages.
     *
     * <p>An unfinished stage and a false completion are always sent. On the
     * final scan both are sent too, because for this mechanism they are the
     * milestone rather than a constant. That pair is the whole of where the
     * sequence stands — the journal's own {@code Log}, {@code Sample} and
     * {@code Analyse} map onto it one for one, so a step field beside it would
     * be the same distinction under a second name.</p>
     */
    @Test
    void anOrganicSampleKeepsItsStageAndCompletionAtEveryStep() {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        JsonNode log = request(fixture, List.of(
                fixture.graphDisabled(scanOrganic("Log", 0))
        )).path("events").get(0);
        assertEquals(
                "The organic sampling tool logged the first scan of an "
                        + "unfinished sampling sequence.",
                log.path("event").textValue());
        assertEquals(
                List.of("event", "organism", "stage", "complete"),
                propertyNames(log),
                "where the sequence stands is stage and completion, once"
        );
        assertEquals("START", log.path("stage").textValue());
        assertFalse(log.path("complete").booleanValue());
        assertEquals(
                "Bacterium Bullaris - Red",
                log.path("organism").textValue(),
                "the variant is the name a spoken comment would use"
        );
        assertFalse(log.has("genus"));
        assertFalse(log.has("species"));
        assertFalse(log.has("variant"));

        JsonNode sample = request(fixture, List.of(
                fixture.graphDisabled(scanOrganic("Sample", 1))
        )).path("events").get(0);
        assertEquals("PROGRESS", sample.path("stage").textValue());
        assertFalse(sample.path("complete").booleanValue());

        JsonNode analyse = request(fixture, List.of(
                fixture.graphDisabled(scanOrganic("Analyse", 2))
        )).path("events").get(0);
        assertEquals("FINAL", analyse.path("stage").textValue());
        assertTrue(
                analyse.path("complete").booleanValue(),
                "a finished analysis is a completed final stage"
        );
    }

    /**
     * No stage of the sequence names its scan type.
     *
     * <p>Checked on the whole serialized request rather than on the event, so
     * that neither the Frontier token nor the past-tense vocabulary that once
     * stood in for it can come back through the context or a change.</p>
     */
    @Test
    void noSamplingStageRestatesItsPositionAsAStep() {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        int index = 0;
        for (String scanType : List.of("Log", "Sample", "Analyse")) {
            String serialized = serializer.serialize(factory.create(
                    fixture.inputs(List.of(
                            fixture.graphDisabled(scanOrganic(scanType, index++))
                    ))
            ));

            assertFalse(serialized.contains("step"), scanType + " sent a step");
            assertFalse(serialized.contains("scanType"));
            assertFalse(serialized.contains(scanType));
            for (String past : List.of("LOGGED", "SAMPLED", "ANALYSED")) {
                assertFalse(serialized.contains(past), scanType + " -> " + past);
            }
        }
    }

    /**
     * A launched vehicle is the whole of what that event says.
     *
     * <p>Nothing is named: the record has no vessel name, so the adapter falls
     * back to the journal's {@code Loadout} token and the field that would say
     * what was launched said {@code "base"}. The adapter's START is a
     * deployment beginning rather than a stage anyone can act on. And launching
     * a vehicle establishes nothing about where the Commander is, so there is
     * no positional claim for an occupancy qualifier to attach to — the context
     * answers that directly instead.</p>
     *
     * <p>The kind says {@code VEHICLE_LAUNCHED} rather than naming a fighter,
     * because the journal record does not establish which vehicle went out; see
     * {@link #aLaunchFighterRecordDoesNotProveAFighter()}.</p>
     */
    @Test
    void aLaunchedVehicleIsTheWholeOfWhatTheEventSays() {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        fixture.graphDisabled("""
                {"timestamp":"2026-07-30T10:00:00Z","event":"Location",
                 "StarSystem":"Icy System","SystemAddress":23155,
                 "Docked":false}
                """);
        // Drained into an earlier turn, so being aboard is standing background
        // by the time the fighter launches rather than news.
        fixture.inputs(List.of(fixture.graphDisabled("""
                {"timestamp":"2026-07-30T10:00:01Z","event":"SupercruiseEntry",
                 "StarSystem":"Icy System","SystemAddress":23155}
                """)));
        JsonNode request = request(fixture, List.of(
                fixture.graphDisabled("""
                        {"timestamp":"2026-07-30T10:00:02Z",
                         "event":"LaunchFighter","Loadout":"base","ID":10,
                         "PlayerControlled":true}
                        """)
        ));
        JsonNode event = request.path("events").get(0);

        assertEquals(
                    "The Commander's ship launched a vehicle it was carrying.",
                    event.path("event").textValue());
        assertFalse(
                event.has("loadout"),
                "the record has no vessel name, and \"base\" is not one"
        );
        assertTrue(event.path("commanderControlled").booleanValue());
        assertEquals(
                List.of("event", "commanderControlled"),
                propertyNames(event)
        );
        assertFalse(event.has("vehicle"));
        assertFalse(event.has("stage"));
        assertFalse(event.has("occupancy"));
        assertFalse(request.toString().contains("UNCONFIRMED"));
        assertFalse(request.toString().contains("FIGHTER_OCCUPANCY"));
        assertEquals(
                "SHIP",
                request.path("context").path("commander")
                        .path("presence").textValue(),
                "where the Commander is comes from the situation, not a gap"
        );
    }

    /**
     * Every other vehicle event is untouched.
     *
     * <p>A destroyed fighter and a recovered SRV are still whatever they were:
     * losing a fighter leaves the same unanswered question about where the
     * Commander is, and the SRV recovery still names the vehicle. This is what
     * keeps settling that gap a per-event claim — the two presence transfers
     * drop it because their own context answers it, not because the gap or the
     * mechanism was ruled uninteresting.</p>
     */
    @Test
    void otherVehicleEventsKeepWhatTheyHad() {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        JsonNode destroyed = request(fixture, List.of(
                fixture.graphDisabled("""
                        {"timestamp":"2026-07-30T10:00:00Z",
                         "event":"FighterDestroyed","ID":10}
                        """)
        )).path("events").get(0);
        assertEquals(
                    "The Commander's ship-launched fighter was destroyed.",
                    destroyed.path("event").textValue());
        assertEquals(
                "UNCONFIRMED",
                destroyed.path("occupancy").textValue(),
                "losing a fighter leaves the same question unanswered"
        );

        JsonNode recovered = request(fixture, List.of(
                fixture.graphDisabled("""
                        {"timestamp":"2026-07-30T10:00:01Z","event":"DockSRV",
                         "ID":10,"SRVType_Localised":"Nomad"}
                        """)
        )).path("events").get(0);
        assertEquals(
                    "The Commander's ship took a surface vehicle back aboard.",
                    recovered.path("event").textValue());
        assertEquals(
                "SLV",
                recovered.path("vehicleKind").textValue(),
                "a Nomad is a Ship-Launched Vessel"
        );
        assertEquals("Nomad", recovered.path("vehicleType").textValue());
        assertFalse(
                recovered.has("vehicle"),
                "one untyped label is what let a Nomad be read as a ship"
        );
        assertFalse(recovered.has("loadout"));
    }

    /**
     * The event whose prose summary was a 300-character restatement.
     *
     * <p>What is left is where it happened and what kind of place that is. The
     * occupancy gap is gone because this request answers it two lines later:
     * {@code commander.presence} is {@code ON_FOOT}, which is precisely the
     * question an {@code UNCONFIRMED} occupancy would raise.</p>
     */
    @Test
    void disembarkingIsWhereItHappenedAndWhatTheCommanderIsInNow() {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        JsonNode request = request(fixture, List.of(
                fixture.graphDisabled("""
                        {"timestamp":"2026-07-30T10:00:00Z",
                         "event":"Disembark","SRV":true,"ID":10,
                         "StarSystem":"Schieni GG-A c3-84",
                         "Body":"Schieni GG-A c3-84 4 a",
                         "OnStation":false,"OnPlanet":true}
                        """)
        ));
        JsonNode disembark = request.path("events").get(0);

        assertEquals(
                    "The Commander stepped out of a ship or SRV.",
                    disembark.path("event").textValue());
        assertEquals(
                List.of("event", "system", "onStation", "onPlanet"),
                propertyNames(disembark),
                "stepping out does not name the body it stepped onto"
        );
        assertEquals(
                "Schieni GG-A c3-84",
                disembark.path("system").textValue(),
                "the system stays: a disembark is not read against one, so "
                        + "no context group would answer for it"
        );
        assertEquals(
                "Schieni GG-A c3-84 4 a",
                request.path("context").path("body").path("name").textValue()
        );
        assertFalse(
                disembark.path("onStation").booleanValue(),
                "an explicit false is a fact, not an absence"
        );
        assertTrue(disembark.path("onPlanet").booleanValue());
        assertFalse(disembark.has("summary"));
        assertFalse(disembark.has("details"));
        assertEquals(
                "The Commander stepped out of a ship or SRV.",
                disembark.path("event").textValue(),
                "the record says what it reports, and nothing about this one");

        assertFalse(disembark.has("occupancy"));
        assertFalse(request.toString().contains("UNCONFIRMED"));
        assertEquals(
                "ON_FOOT",
                request.path("context").path("commander")
                        .path("presence").textValue(),
                "the context answers what the gap was qualifying"
        );
        assertEquals(
                "SRV",
                request.path("context").path("vehicle").path("kind").textValue(),
                "the vehicle it was left in is still named"
        );
    }

    /**
     * Boarding is the same shape, answered by the other end of the same field.
     *
     * <p>Leaving a vessel and getting into one raise the identical gap, and the
     * identical thing settles it: {@code commander.presence} says {@code SRV}
     * here where it said {@code ON_FOOT} there. What stays open is the vehicle
     * events, which raise the gap with nothing in the request to answer it.</p>
     */
    @Test
    void boardingIsWhereItHappenedAndWhatTheCommanderIsInNow() {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        JsonNode request = request(fixture, List.of(
                fixture.graphDisabled("""
                        {"timestamp":"2026-07-30T10:00:00Z",
                         "event":"Embark","SRV":true,"ID":10,
                         "StarSystem":"Schieni GG-A c3-84",
                         "Body":"Schieni GG-A c3-84 4 a",
                         "OnStation":false,"OnPlanet":true}
                        """)
        ));
        JsonNode embark = request.path("events").get(0);

        assertEquals(
                    "The Commander, on foot, got into a ship or SRV.",
                    embark.path("event").textValue());
        assertEquals(
                List.of("event", "system", "onStation", "onPlanet"),
                propertyNames(embark),
                "getting in does not name the body it happened on"
        );
        assertEquals(
                "Schieni GG-A c3-84",
                embark.path("system").textValue()
        );
        assertEquals(
                "Schieni GG-A c3-84 4 a",
                request.path("context").path("body").path("name").textValue()
        );
        assertFalse(
                embark.path("onStation").booleanValue(),
                "an explicit false is a fact, not an absence"
        );
        assertTrue(embark.path("onPlanet").booleanValue());

        assertFalse(embark.has("occupancy"));
        assertFalse(request.toString().contains("UNCONFIRMED"));
        assertEquals(
                "SRV",
                request.path("context").path("commander")
                        .path("presence").textValue(),
                "the context answers what the gap was qualifying"
        );
        assertEquals(
                "SRV",
                request.path("context").path("vehicle").path("kind").textValue(),
                "the vehicle that was boarded is still named"
        );
    }

    /**
     * Approaching a body brings what is already known about it.
     *
     * <p>The recalled survey facts arrive as {@code context.body}, because
     * they were true before this approach and are still true after it.
     * Nothing about the ship, the Commander or a sampling sequence comes with
     * them.</p>
     */
    @Test
    void approachingABodyBringsOnlyThatBodysContext() {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        fixture.graphDisabled("""
                {"timestamp":"2026-07-30T10:00:00Z","event":"LoadGame",
                 "FID":"F12345678","ShipID":9,"Ship":"explorer_nx",
                 "ShipName":"Wanderer"}
                """);
        // The system is established before the approach, as it is in a real
        // session: the jump or the supercruise entry names it, and by the time
        // a body is approached it is standing background.
        fixture.inputs(List.of(fixture.graphDisabled("""
                {"timestamp":"2026-07-30T10:00:01Z","event":"SupercruiseEntry",
                 "StarSystem":"Schieni GG-A c3-84","SystemAddress":23155}
                """)));
        fixture.graphDisabled("""
                {"timestamp":"2026-07-30T10:00:02Z","event":"Scan",
                 "SystemAddress":23155,"BodyID":20,
                 "BodyName":"Schieni GG-A c3-84 4 a","PlanetClass":"Icy body",
                 "Landable":true,"WasDiscovered":false,"WasMapped":false,
                 "DistanceFromArrivalLS":1216.6}
                """);
        JsonNode request = request(fixture, List.of(
                fixture.graphDisabled("""
                        {"timestamp":"2026-07-30T10:00:03Z",
                         "event":"ApproachBody",
                         "StarSystem":"Schieni GG-A c3-84",
                         "SystemAddress":23155,
                         "Body":"Schieni GG-A c3-84 4 a","BodyID":20}
                        """)
        ));

        assertFalse(
                request.has("changes"),
                () -> "remembering a body is not the body changing: " + request
        );
        JsonNode body = request.path("context").path("body");
        assertEquals("Icy body", body.path("planetClass").textValue());
        assertEquals(
                "Schieni GG-A c3-84 4 a",
                body.path("name").textValue(),
                "the approach leaves the naming to the situation"
        );
        assertFalse(
                body.has("distanceFromArrivalLs"),
                "the arrival distance is not model-facing"
        );
        assertFalse(
                body.has("landable"),
                "whether it can be landed on is not what the body is"
        );
        assertFalse(
                body.has("previouslyDiscovered"),
                "a survey flag is the survey's to report"
        );
        assertFalse(
                body.has("bodyId"),
                "an internal body identifier is not a fact about the body"
        );
        assertFalse(
                request.path("context").has("ship"),
                "an approach has nothing to do with which ship it is"
        );
        assertFalse(request.path("context").has("commander"));
        assertFalse(request.path("context").has("sampling"));
        assertFalse(request.toString().contains("F12345678"));
        assertFalse(request.toString().contains("23155"));
    }

    /** An inactive sampling process is absent, not reported as inactive. */
    @Test
    void aTurnWithNoRunningSequenceCarriesNoSamplingGroup() {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        JsonNode request = request(fixture, List.of(
                fixture.graphDisabled(scanOrganic("Analyse", 0)),
                fixture.graphDisabled("""
                        {"timestamp":"2026-07-30T10:00:05Z",
                         "event":"Liftoff","PlayerControlled":true,
                         "Latitude":18.7,"Longitude":-35.0}
                        """)
        ));

        assertFalse(
                request.path("context").has("sampling"),
                "the sequence ended, so there is no sequence to describe"
        );
        assertFalse(request.toString().contains("\"active\""));
    }

    /**
     * A landing, whole.
     *
     * <p>The event says what happened and who was flying. Everything else is
     * the situation it happened in — where, on what, and how the ship is now
     * sitting — and the survey flags say <em>previously</em> so that arriving
     * somewhere nobody has been cannot be read as discovering it now.</p>
     */
    @Test
    void aLandingCarriesTheEventAndTheSituationItHappenedIn() {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        fixture.graphDisabled("""
                {"timestamp":"2026-07-30T10:00:00Z","event":"Scan",
                 "SystemAddress":23155,"BodyID":20,
                 "BodyName":"Schieni GG-A c3-84 4 a","PlanetClass":"Icy body",
                 "Landable":true,"WasDiscovered":false,"WasMapped":false,
                 "WasFootfalled":false,
                 "DistanceFromArrivalLS":1081.453145}
                """);
        fixture.graphDisabled("""
                {"timestamp":"2026-07-30T10:00:01Z","event":"SAASignalsFound",
                 "SystemAddress":23155,"BodyID":20,
                 "BodyName":"Schieni GG-A c3-84 4 a",
                 "Signals":[{"Type":"$SAA_SignalType_Biological;",
                 "Type_Localised":"Biological","Count":1}]}
                """);
        // The approach selects the body in an earlier turn, so by the time the
        // ship lands the body is standing background rather than news.
        fixture.inputs(List.of(fixture.graphDisabled("""
                {"timestamp":"2026-07-30T10:00:02Z","event":"ApproachBody",
                 "StarSystem":"Schieni GG-A c3-84","SystemAddress":23155,
                 "Body":"Schieni GG-A c3-84 4 a","BodyID":20}
                """)));
        JsonNode request = request(fixture, List.of(
                fixture.graphDisabled("""
                        {"timestamp":"2026-07-30T10:00:03Z",
                         "event":"Touchdown","PlayerControlled":true,
                         "Body":"Schieni GG-A c3-84 4 a","BodyID":20,
                         "Latitude":18.767618,"Longitude":-35.084686}
                        """)
        ));

        assertEquals(List.of("events", "context"), propertyNames(request));
        JsonNode event = request.path("events").get(0);
        assertEquals(
                    "The Commander's ship landed on the surface of a planet or moon.",
                    event.path("event").textValue());
        assertTrue(event.path("commanderControlled").booleanValue());
        assertEquals(
                List.of("event", "commanderControlled"),
                propertyNames(event),
                "the landing says what happened; where it happened is the "
                        + "situation's to answer"
        );

        JsonNode context = request.path("context");
        // Nothing in this fixture established which vehicle is out, so the
        // group is absent rather than reported as unknown. A landing that does
        // know is covered by DecisionSurfaceVehicleContextTest.
        assertFalse(context.has("vehicle"));
        assertTrue(
                context.path("system").path("name").isMissingNode(),
                "a landing is not an arrival in a system"
        );
        assertFalse(
                context.has("navigation"),
                "the landing says the ship is down in its own words"
        );
        JsonNode body = context.path("body");
        assertEquals(
                List.of("name", "type", "planetClass"),
                propertyNames(body),
                "which body it is and what it is; what a survey found on it "
                        + "belongs to the survey's own turn"
        );
        assertEquals(
                "Schieni GG-A c3-84 4 a",
                body.path("name").textValue()
        );
        assertEquals("Icy body", body.path("planetClass").textValue());
        assertFalse(body.has("landable"));
        assertFalse(body.has("previouslyDiscovered"));
        assertFalse(body.has("previouslyMapped"));
        assertFalse(body.has("previouslyFootfalled"));
        assertFalse(body.has("distanceFromArrivalLs"));
        assertFalse(body.has("biologicalSignals"));
        assertFalse(body.has("geologicalSignals"));
        assertFalse(request.toString().contains("distanceLs"));
        assertFalse(request.toString().contains("\"discovered\""));
    }

    /** Surface coordinates improved no decision in either measured run. */
    @Test
    void surfaceCoordinatesAreNotSent() {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        String serialized = serialize(fixture, List.of(
                fixture.graphDisabled("""
                        {"timestamp":"2026-07-30T10:00:00Z",
                         "event":"Touchdown","PlayerControlled":true,
                         "Latitude":18.767618,"Longitude":-35.084686}
                        """)
        ));

        assertFalse(serialized.contains("18.767"));
        assertFalse(serialized.contains("position"));
        assertTrue(serialized.contains(
                "The Commander's ship landed on the surface of a planet or moon."));
    }

    /**
     * A reversal is not named at all, and the rest of the event survives.
     *
     * <p>The semantic relationship named its counterpart with Kairon's own
     * kind, which the model no longer receives for any event — so the value
     * pointed at a word it never sees. The relationship still exists on the
     * semantic fact; it simply stops being projected, and nothing about the
     * event's own fields changes with it.</p>
     */
    @Test
    void aReversalIsNotProjectedAndTheEventKeepsItsFields() {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        JsonNode left = request(fixture, List.of(
                fixture.graphDisabled("""
                        {"timestamp":"2026-07-30T10:00:00Z",
                         "event":"LeaveBody","StarSystem":"Schieni GG-A c3-84",
                         "SystemAddress":23155,"Body":"Schieni GG-A c3-84 4 a",
                         "BodyID":20}
                        """)
        )).path("events").get(0);
        assertFalse(left.has("reverses"), left.toString());
        assertFalse(left.toString().contains("BODY_APPROACHED"));
        assertFalse(left.toString().contains("ApproachBody"));
        assertEquals(
                List.of("event"),
                propertyNames(left),
                "leaving a body is read against the body and the system, so "
                        + "both are the situation's to name and neither is "
                        + "the relationship this test is about");

        JsonNode recovered = request(fixture, List.of(
                fixture.graphDisabled("""
                        {"timestamp":"2026-07-30T10:00:01Z","event":"DockSRV",
                         "ID":10,"SRVType_Localised":"Nomad"}
                        """)
        )).path("events").get(0);
        assertEquals(
                    "The Commander's ship took a surface vehicle back aboard.",
                    recovered.path("event").textValue());
        assertFalse(recovered.has("reverses"));
        assertFalse(recovered.toString().contains("LaunchSRV"));
    }

    /**
     * A change the events already state is not sent twice.
     *
     * <p>Entering supercruise changes the flight mode; saying so as a change
     * beside an event named for it is the same sentence twice.</p>
     */
    @Test
    void aMechanismDoesNotReportTheChangeItsOwnKindAlreadyStates() {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        JsonNode request = request(fixture, List.of(
                fixture.graphDisabled("""
                        {"timestamp":"2026-07-30T10:00:00Z",
                         "event":"SupercruiseEntry",
                         "StarSystem":"Schieni GG-A c3-84",
                         "SystemAddress":23155}
                        """)
        ));

        assertFalse(
                request.path("changes").toString().contains("flightMode"),
                "the event kind is the flight-mode change"
        );
    }

    // ------------------------------------------------------------- fixtures

    private static String friends(String name) {
        return """
                {"timestamp":"2026-07-30T10:00:00Z","event":"Friends",
                 "Status":"Online","Name":"%s"}
                """.formatted(name);
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

    private JsonNode request(
            DecisionTurnFixture fixture,
            List<ProjectedObservation> triggers
    ) {
        return read(serialize(fixture, triggers));
    }

    private String serialize(
            DecisionTurnFixture fixture,
            List<ProjectedObservation> triggers
    ) {
        return serializer.serialize(
                factory.create(fixture.inputs(triggers))
        );
    }

    private static JsonNode read(String serialized) {
        try {
            return JSON.readTree(serialized);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static List<String> propertyNames(JsonNode node) {
        List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return List.copyOf(names);
    }
}
