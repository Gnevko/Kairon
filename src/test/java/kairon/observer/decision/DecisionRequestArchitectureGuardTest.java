package kairon.observer.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kairon.behavior.normalize.NormalizedEventType;
import kairon.llm.DecisionPromptFactory;
import kairon.projection.ProjectedObservation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Names that must never reach the model, checked against real output.
 *
 * <p>The guard reads serialized requests rather than source text. A comment can
 * promise that a field is gone; only the bytes the provider would receive can
 * prove it, and only for inputs a real replay actually produces — so the
 * fixtures below run the production factory over production-parsed journal
 * events covering every mechanism that has ever contributed one of these
 * names.</p>
 */
final class DecisionRequestArchitectureGuardTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Kairon's own vocabulary, in the exact spelling it used to be sent under.
     *
     * <p>Each entry was a real property of the previous contract. Several are
     * bus and graph identities, several are pipeline classification, and one is
     * an account identifier that is never speakable.</p>
     */
    private static final Set<String> BANNED_PROPERTIES = Set.of(
            "schemaVersion",
            "turn",
            "turnSequence",
            "triggerCount",
            "busSequence",
            "firstTriggerBusSequence",
            "finalTriggerBusSequence",
            "finalTriggerTimestamp",
            "sourceRole",
            "rawEventType",
            "normalizedEventType",
            "graphContext",
            "fid",
            // The remembered history is projected, so the machinery that
            // remembers it must not come along.
            "cursor",
            "occurrenceId",
            "episodeSequence",
            "graphId",
            "basis",
            "contextKey",
            "observedTransitionCount",
            "contextObservedTransitionCount",
            // The event id, the pointer at it, and the citation both existed
            // for. All three were real properties of this contract; none is
            // sent now. Checked at every depth, so a change cannot reintroduce
            // the pointer while the events stay clean.
            "id",
            "eventId",
            "evidence",
            "evidenceIds",
            "omittedOccurrenceCount",
            "totalOccurrenceCount",
            "activeEventCounts",
            "currentEventType",
            "matchesFinalTrigger"
    );

    private final LlmDecisionRequestFactory factory =
            new LlmDecisionRequestFactory();
    private final JacksonDecisionRequestSerializer serializer =
            new JacksonDecisionRequestSerializer();

    /**
     * Every current event says what it is, and never Kairon's name for it.
     *
     * <p>Both halves are the contract. A missing description would leave the
     * model with named fields and nothing to attach them to; a {@code kind}
     * beside the description would answer one question twice, and once in a
     * vocabulary that means nothing outside this process. The internal kind
     * still exists — selection, the graph, the tests and the diagnostics all
     * use it — it simply stops being sent.</p>
     */
    @Test
    void everyCurrentEventDescribesItselfAndCarriesNoKind() {
        for (Fixture fixture : fixtures()) {
            JsonNode events = read(fixture.json()).path("events");
            assertTrue(events.size() >= 1, fixture.name());
            for (JsonNode event : events) {
                String description = event.path("event").textValue();
                assertNotNull(
                        description,
                        () -> fixture.name() + " sent an event with nothing "
                                + "to say: " + fixture.json()
                );
                assertFalse(
                        description.isBlank(),
                        () -> fixture.name() + " sent a blank description"
                );
                assertFalse(
                        description.matches("[A-Z][A-Z0-9_]*"),
                        () -> fixture.name() + " sent the internal spelling "
                                + description
                );
                assertFalse(
                        event.has("kind"),
                        () -> fixture.name() + " sent Kairon's own name for "
                                + "the event: " + fixture.json()
                );
            }
        }
    }

    /**
     * No value anywhere in a current event is one of Kairon's own kinds.
     *
     * <p>Checking that the {@code kind} property is gone is not enough: the
     * same vocabulary reached the model under {@code reverses}, which named a
     * counterpart action with the catalogue's spelling. Once the kind stopped
     * being sent, that value pointed at a word the model never sees. The guard
     * therefore walks every string in the event — property values, nested
     * objects and arrays alike — rather than one property name, so a future
     * field cannot reintroduce the leak under a third spelling.</p>
     */
    @Test
    void noValueInACurrentEventIsAnInternalKind() {
        Set<String> kinds = new LinkedHashSet<>();
        for (DecisionEventRule rule : DecisionEventCatalog.declaredRules()) {
            kinds.add(rule.kind());
        }
        assertFalse(kinds.isEmpty(), "the catalogue declared no kinds");

        for (Fixture fixture : fixtures()) {
            for (JsonNode event : read(fixture.json()).path("events")) {
                List<String> values = new ArrayList<>();
                collectStrings(event, values);
                for (String value : values) {
                    assertFalse(
                            kinds.contains(value),
                            () -> fixture.name() + " sent the internal kind "
                                    + value + " inside an event: "
                                    + fixture.json()
                    );
                }
            }
        }
    }

    /** The relationship field is gone; nothing replaced it. */
    @Test
    void noCurrentEventNamesACounterpartAction() {
        for (Fixture fixture : fixtures()) {
            for (JsonNode event : read(fixture.json()).path("events")) {
                assertFalse(
                        event.has("reverses"),
                        () -> fixture.name() + " still names a counterpart: "
                                + fixture.json()
                );
            }
        }
    }

    @Test
    void noRequestCarriesAnInternalPropertyName() {
        for (Fixture fixture : fixtures()) {
            Set<String> properties = propertyNames(read(fixture.json()));
            for (String banned : BANNED_PROPERTIES) {
                assertFalse(
                        properties.contains(banned),
                        () -> fixture.name() + " sent the internal property "
                                + banned + ": " + fixture.json()
                );
            }
        }
    }

    @Test
    void noRequestLeaksAnAccountIdentifierOrABusIdentity() {
        for (Fixture fixture : fixtures()) {
            assertFalse(
                    fixture.json().contains("F12345678"),
                    () -> fixture.name() + " leaked the account identifier"
            );
            assertFalse(
                    fixture.json().contains("busSequence"),
                    () -> fixture.name() + " leaked a bus identity"
            );
            assertFalse(
                    fixture.json().contains("kairon-llm"),
                    () -> fixture.name() + " leaked a contract version"
            );
            for (String retired : List.of(
                    "loginTransition",
                    "newlyOnline",
                    "LOGIN_TRANSITION"
            )) {
                assertFalse(
                        fixture.json().contains(retired),
                        () -> fixture.name() + " reintroduced " + retired
                                + ": a friend status reports a status, not a "
                                + "transition, so there is nothing to qualify"
                );
            }
            for (String normalized : List.of(
                    "SYSTEM_ENTRY",
                    "SCAN_ORGANIC_ANALYSE",
                    "AUXILIARY_VEHICLE_LAUNCHED",
                    "bgo1-"
            )) {
                assertFalse(
                        fixture.json().contains(normalized),
                        () -> fixture.name() + " leaked the internal spelling "
                                + normalized + ": a remembered event is named "
                                + "the same way a current one is"
                );
            }
        }
    }

    /**
     * No request identifies an event to the model.
     *
     * <p>The projection still numbers its events {@code 1..n} — the trace, the
     * change attribution and the turn's own bookkeeping all key on that — but
     * the number is not written. A model handed an identity it cannot verify
     * can only cite it, and there is nothing on its side of the exchange to
     * check a citation against.</p>
     *
     * <p>Checked against every spelling the number could come back under, not
     * just {@code id}: the guard walks the whole event object, so a future
     * field cannot reintroduce the identity as {@code eventId} or
     * {@code index}.</p>
     */
    @Test
    void noRequestIdentifiesAnEventToTheModel() {
        for (Fixture fixture : fixtures()) {
            JsonNode events = read(fixture.json()).path("events");
            assertTrue(
                    events.size() >= 1,
                    () -> fixture.name() + " sent no events"
            );
            for (JsonNode event : events) {
                for (String identity
                        : List.of("id", "eventId", "index", "position")) {
                    assertFalse(
                            event.has(identity),
                            () -> fixture.name() + " identifies an event as "
                                    + identity + ": " + fixture.json()
                    );
                }
            }
        }
    }

    /** The events section is exactly the descriptions and their fields. */
    @Test
    void everyEventStartsWithWhatHappened() {
        for (Fixture fixture : fixtures()) {
            for (JsonNode event : read(fixture.json()).path("events")) {
                List<String> names = new ArrayList<>();
                event.fieldNames().forEachRemaining(names::add);
                assertEquals(
                        "event",
                        names.getFirst(),
                        () -> fixture.name() + " leads with something other "
                                + "than what happened: " + fixture.json()
                );
            }
        }
    }

    /** Absence is the contract: nothing is ever sent as null or as empty. */
    @Test
    void noRequestSerializesANullOrAnEmptyContainer() {
        for (Fixture fixture : fixtures()) {
            String json = fixture.json();
            assertFalse(json.contains("null"), fixture.name());
            assertFalse(json.contains("[]"), fixture.name());
            assertFalse(json.contains("{}"), fixture.name());
        }
    }

    /**
     * The prompt pays for its own indentation.
     *
     * <p>A Java text block strips only the indentation common to every line, so
     * one line closed at column zero re-indents the whole block. That silently
     * cost 765 characters of leading whitespace in a measured run, which is a
     * third of the prompt.</p>
     */
    @Test
    void thePromptCarriesNoIncidentalIndentation() {
        for (String line : DecisionPromptFactory.SYSTEM_PROMPT.split("\n")) {
            assertFalse(
                    line.startsWith(" "),
                    "the prompt must not ship leading whitespace: " + line
            );
        }
    }

    @Test
    void thePromptNamesNoneOfKaironsInternals() {
        String prompt = DecisionPromptFactory.SYSTEM_PROMPT;
        for (String internal : List.of(
                "busSequence",
                "schema",
                "graph",
                "projection",
                "sourceRole",
                "diagnostic",
                "currentState",
                "stateChanges",
                "structuredFacts",
                "observation bus"
        )) {
            assertFalse(
                    prompt.contains(internal),
                    "the prompt must not name " + internal
            );
        }
    }

    /**
     * The prompt asks for no identity, because it offers none.
     *
     * <p>The instructions and the document have to agree: an output contract
     * that asks the model to name the events it used would be asking for
     * numbers the request stopped carrying, and there would be nothing to check
     * the answer against. Both halves went at once.</p>
     *
     * <p>What is banned is the mechanism, never the vocabulary. "Evidence" and
     * "cite" are ordinary English words and a prompt is free to use them; a
     * guard that failed on the bare words would be forcing the prompt to write
     * around a test. It did once, which is why every entry below is a shape
     * only the removed contract produces rather than a word.</p>
     *
     * <p>So every entry below is a shape only the removed contract produces:
     * the response property in its JSON spelling, its bracket, its old
     * alternative name, an instruction to cite events, and the three phrases
     * that named the ids themselves.</p>
     */
    @Test
    void thePromptAsksTheModelToCiteNothing() {
        String prompt = DecisionPromptFactory.SYSTEM_PROMPT.toLowerCase();
        for (String citation : List.of(
                // Quoted: a JSON property name, never the English word. The
                // prose "as evidence that this is the first time" carries no
                // quotes and is not what this is looking for.
                "\"evidence\"",
                "\"evidence\":[",
                "evidenceids",
                // "cite" alone also matches "recite", which is a different word
                // in a rule that has nothing to do with citation. What a
                // citation instruction actually looks like is a verb with an
                // object.
                "cite event",
                "cite events",
                "event id",
                "event ids",
                "id values",
                "unique and ascending"
        )) {
            assertFalse(
                    prompt.contains(citation.toLowerCase()),
                    "the prompt still states a citation contract: " + citation
            );
        }
    }

    /**
     * The prompt says who she is, and that is one of its three remaining jobs.
     *
     * <p>It carried six blocks and now carries three: the role, three lines of
     * preference, and the answer contract. This is the half a guard can state
     * positively — the other half is stated by
     * {@link #thePromptStatesTheCurrentOutputContract} — so that reducing it to
     * nothing at all fails here rather than silently.</p>
     */
    @Test
    void thePromptStillSaysWhoSheIs() {
        String prompt = DecisionPromptFactory.SYSTEM_PROMPT;
        assertTrue(
                prompt.contains("You are Kairon"),
                "the role is what the prompt is for"
        );
        assertTrue(
                prompt.contains("shipboard companion"),
                "and what kind of thing she is to the Commander"
        );
    }

    /** The prompt states both response shapes, and only those two. */
    @Test
    void thePromptStatesTheCurrentOutputContract() {
        String prompt = DecisionPromptFactory.SYSTEM_PROMPT;
        assertTrue(
                prompt.contains("{\"decision\":\"SILENT\"}"),
                "the silent form is stated"
        );
        assertTrue(
                prompt.contains(
                        "{\"decision\":\"COMMENT\",\"comment\":\"...\"}"
                ),
                "the comment form is a decision and a sentence, and no more"
        );
        assertEquals(
                2,
                countOccurrences(prompt, "{\"decision\":"),
                "two shapes are offered, and no third one"
        );
        for (String property : List.of("decision", "comment")) {
            assertTrue(
                    prompt.contains("\"" + property + "\""),
                    "the contract names " + property
            );
        }
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int from = text.indexOf(needle);
        while (from >= 0) {
            count++;
            from = text.indexOf(needle, from + needle.length());
        }
        return count;
    }

    // ------------------------------------------------------------- fixtures

    private List<Fixture> fixtures() {
        List<Fixture> fixtures = new ArrayList<>();
        fixtures.add(one("friends", new String[]{
                """
                {"timestamp":"2026-07-30T10:00:00Z","event":"Friends",
                 "Status":"Online","Name":"KotyaGaw"}
                """
        }));
        fixtures.add(one("commander-bootstrap", new String[]{
                """
                {"timestamp":"2026-07-30T10:00:00Z","event":"Commander",
                 "FID":"F12345678","Name":"TESTCMDR"}
                """
        }));
        fixtures.add(withHidden(
                "jump-after-load",
                new String[]{
                        """
                        {"timestamp":"2026-07-30T10:00:00Z","event":"LoadGame",
                         "FID":"F12345678","ShipID":9,"Ship":"explorer_nx",
                         "ShipName":"Wanderer"}
                        """
                },
                new String[]{
                        """
                        {"timestamp":"2026-07-30T10:00:01Z","event":"FSDJump",
                         "StarSystem":"Schieni GG-A c3-84",
                         "SystemAddress":23155,"JumpDist":24.5,
                         "FuelUsed":1.2,"BoostUsed":false}
                        """
                }
        ));
        fixtures.add(withHidden(
                "approach-mapped-body",
                new String[]{
                        """
                        {"timestamp":"2026-07-30T10:00:00Z","event":"LoadGame",
                         "FID":"F12345678","ShipID":9,"Ship":"explorer_nx",
                         "ShipName":"Wanderer"}
                        """,
                        """
                        {"timestamp":"2026-07-30T10:00:01Z","event":"Scan",
                         "SystemAddress":23155,"BodyID":20,
                         "BodyName":"Schieni GG-A c3-84 4 a",
                         "PlanetClass":"Icy body","Landable":true,
                         "WasDiscovered":false,"WasMapped":false,
                         "DistanceFromArrivalLS":1216.6}
                        """
                },
                new String[]{
                        """
                        {"timestamp":"2026-07-30T10:00:02Z",
                         "event":"ApproachBody",
                         "StarSystem":"Schieni GG-A c3-84",
                         "SystemAddress":23155,
                         "Body":"Schieni GG-A c3-84 4 a","BodyID":20}
                        """,
                        """
                        {"timestamp":"2026-07-30T10:00:03Z",
                         "event":"SupercruiseExit",
                         "StarSystem":"Schieni GG-A c3-84",
                         "SystemAddress":23155,
                         "Body":"Schieni GG-A c3-84 4 a","BodyID":20,
                         "BodyType":"Planet"}
                        """
                }
        ));
        fixtures.add(one("sampling", new String[]{
                """
                {"timestamp":"2026-07-30T10:00:00Z","event":"ScanOrganic",
                 "ScanType":"Sample","Genus":"$Codex_Ent_Bacterial_Genus_Name;",
                 "Genus_Localised":"Bacteria",
                 "Variant":"$Codex_Ent_Bacterial_01_F_Name;",
                 "Variant_Localised":"Bacterium Bullaris - Red",
                 "SystemAddress":23155,"Body":20}
                """
        }));
        fixtures.add(one("vehicle-and-presence", new String[]{
                """
                {"timestamp":"2026-07-30T10:00:00Z","event":"LaunchFighter",
                 "Loadout":"base","ID":10,"PlayerControlled":true}
                """,
                """
                {"timestamp":"2026-07-30T10:00:01Z","event":"Disembark",
                 "SRV":true,"ID":10,"StarSystem":"Schieni GG-A c3-84",
                 "Body":"Schieni GG-A c3-84 4 a","OnStation":false,
                 "OnPlanet":true}
                """
        }));
        fixtures.add(one("commerce", new String[]{
                """
                {"timestamp":"2026-07-30T10:00:00Z","event":"MarketSell",
                 "MarketID":128,"Type":"gold","Type_Localised":"Gold",
                 "Count":4,"SellPrice":9000,"TotalSale":36000,
                 "AvgPricePaid":8000}
                """
        }));
        // Two events whose semantic fact names a counterpart action. Both
        // used to send that counterpart as Kairon's own kind, which is the
        // leak the value guard exists for; without them it would pass on
        // fixtures that never had a relationship to leak.
        fixtures.add(one("counterpart-relationships", new String[]{
                """
                {"timestamp":"2026-07-30T10:00:00Z","event":"LeaveBody",
                 "StarSystem":"Schieni GG-A c3-84","SystemAddress":23155,
                 "Body":"Schieni GG-A c3-84 4 a","BodyID":20}
                """,
                """
                {"timestamp":"2026-07-30T10:00:01Z","event":"Undocked",
                 "StationName":"Jameson Memorial","StationType":"Orbis",
                 "MarketID":128}
                """
        }));
        // A graph-backed turn: the graph advanced, and none of it is sent.
        fixtures.add(new Fixture("graphed-touchdown", graphedTouchdown()));
        return List.copyOf(fixtures);
    }

    private Fixture one(String name, String[] triggers) {
        return withHidden(name, new String[0], triggers);
    }

    private Fixture withHidden(
            String name,
            String[] hidden,
            String[] triggers
    ) {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        for (String rawJson : hidden) {
            fixture.graphDisabled(rawJson);
        }
        List<ProjectedObservation> projected = new ArrayList<>();
        for (String rawJson : triggers) {
            projected.add(fixture.graphDisabled(rawJson));
        }
        return new Fixture(
                name,
                serializer.serialize(
                        factory.create(fixture.inputs(projected))
                )
        );
    }

    /** A turn that carries a remembered history and a forecast with it. */
    private String graphedTouchdown() {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        ProjectedObservation touchdown = fixture.graphedPredicting(
                """
                {"timestamp":"2026-07-30T10:00:00Z","event":"Touchdown",
                 "PlayerControlled":true,"Latitude":18.7,"Longitude":-35.0}
                """,
                List.of(
                        DecisionTurnFixture.TrajectoryEntry.journal(
                                NormalizedEventType.SYSTEM_ENTRY
                        ),
                        DecisionTurnFixture.TrajectoryEntry.journal(
                                NormalizedEventType.SCAN_ORGANIC_ANALYSE
                        ),
                        DecisionTurnFixture.TrajectoryEntry.journal(
                                NormalizedEventType.AUXILIARY_VEHICLE_LAUNCHED
                        ),
                        DecisionTurnFixture.TrajectoryEntry.journal(
                                NormalizedEventType.TOUCHDOWN
                        ).at(23155L, 20L)
                ),
                List.of(NormalizedEventType.DISEMBARK)
        );
        return serializer.serialize(
                factory.create(fixture.inputs(List.of(touchdown)))
        );
    }

    /** Every string this node carries, at any depth. */
    private static void collectStrings(JsonNode node, List<String> into) {
        if (node.isTextual()) {
            into.add(node.textValue());
            return;
        }
        if (node.isArray() || node.isObject()) {
            node.forEach(child -> collectStrings(child, into));
        }
    }

    private static Set<String> propertyNames(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        collect(node, names);
        return names;
    }

    private static void collect(JsonNode node, Set<String> names) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                names.add(entry.getKey());
                collect(entry.getValue(), names);
            });
        } else if (node.isArray()) {
            node.forEach(item -> collect(item, names));
        }
    }

    private static JsonNode read(String serialized) {
        try {
            return JSON.readTree(serialized);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private record Fixture(String name, String json) {
    }
}
