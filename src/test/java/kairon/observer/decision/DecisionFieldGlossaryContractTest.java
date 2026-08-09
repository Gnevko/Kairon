package kairon.observer.decision;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.bio.JsonOrganicRegistryLoader;
import kairon.projection.ProjectedObservation;
import kairon.turn.glossary.DecisionFieldGlossary;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static kairon.observer.decision.RequestJson.read;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The glossary and the document say the same thing, or the build fails.
 *
 * <p>A dictionary maintained by discipline is a dictionary that drifts. This
 * builds requests through the production path from the checked-in fixtures,
 * collects every JSON name any of them contains, and reconciles that set with
 * the glossary in both directions: a name with no entry means a field shipped
 * undocumented, and an entry no name reaches means a field was removed and its
 * line was left behind.</p>
 *
 * <p>Coverage is the fixtures' coverage, which is the standard the rest of this
 * suite is held to — a whole recorded journal per scenario. A field only a
 * combat or trade session produces is not reached here, and for those the
 * glossary is checked the other way only: it may describe them, and the
 * unreached-entry check names them explicitly so the list stays deliberate
 * rather than accumulating.</p>
 */
final class DecisionFieldGlossaryContractTest {

    private static final List<String> FIXTURES = List.of(
            "biological-contexts.jsonl",
            "exobiology.jsonl",
            "ship-switch.jsonl",
            "system-change.jsonl",
            "system-survey.jsonl",
            "touchdown-liftoff.jsonl"
    );

    /**
     * Names the fixtures cannot reach, kept as an explicit list.
     *
     * <p>Each is a field of a session this project has no recorded journal for
     * — combat, trade, missions, powerplay, engineering, squadrons. They are
     * documented because the model can receive them; they are listed here
     * because nothing in the suite produces one, and an unexamined "allowed to
     * be unreached" rule would let a stale entry hide among them.</p>
     *
     * <p><strong>"Not exercised here" and "cannot arrive" are different, and
     * this list is only for the first.</strong> On 2026-08-08 it was hiding
     * five of the second: {@code onPlanet} and {@code onStation} come only
     * from a disembark or an embark, which stopped opening turns that day, and
     * {@code onFoot}, {@code inSrv} and {@code docked} come only from
     * {@code Location}, which is {@code CONTEXT_ONLY} and had never been
     * presented at all. Before adding a name here, check that some admitted
     * event could still produce it in a session this project simply has not
     * recorded.</p>
     */
    private static final Set<String> NOT_REACHED_BY_THE_FIXTURES = Set.of(
            "contextIncomplete", "negated", "derived",
            "station", "ship", "entry", "mission", "commodity", "message",
            "carrier", "site", "faction", "power", "squadron", "wing", "crew",
            "engineer", "blueprint", "suit", "weapon", "material",
            "signalSource", "rank", "friend", "victim", "target",
            "terraformState", "playerPilot",
            "player", "fighter", "boostUsed", "remainingJumpsInRoute",
            "fromSystem", "destinationSystem", "destinationBody",
            "newDestinationSystem", "newDestinationStation", "stationType",
            "shipName", "shipIdentifier",
            "channel", "sender", "status", "category", "region", "newEntry",
            "reason", "oldRank", "fromPower", "powerBefore", "powerAfter",
            "level", "quality", "module", "package", "telepresence",
            "abandoned", "stolen", "crimeType", "cause", "killerShip",
            "killerRank",
            "credits", "units", "tonnes", "totalQuantity", "fraction",
            "multiplier", "price", "unitPrice", "sellPrice", "cost",
            "totalCost", "totalSale", "baseValue", "bonus", "reward",
            "bounty", "fine",
            "details", "identifiedObject", "taxi", "multicrew", "occupancy",
            "context.ship", "context.sampling", "active",
            "before",
            // No fixture drives an SRV, though the live session of 2026-08-08
            // produced all three. "previouslyFootfalled" and "firstFootfall"
            // were here too until the exobiology fixture gained a Scan of the
            // body it samples (ADR-0030): a footfall flag needs a scan to state
            // it, and nothing had ever scanned anything.
            "vehicle", "vehicleKind", "vehicleType",
            // context.vehicle was reachable through a landing until landings
            // stopped opening turns on 2026-08-08. It is still reachable in a
            // real session through a vehicle launch, which is admitted and
            // which the live run of that evening produced carrying
            // {"kind":"SLV"}; no fixture drives one.
            "context.vehicle"
    );

    @Test
    void everyNameTheDocumentCanCarryIsDescribed() {
        Set<String> undocumented = new TreeSet<>(namesInEveryFixtureRequest());
        undocumented.removeAll(DecisionFieldGlossary.describedNames());

        assertTrue(
                undocumented.isEmpty(),
                "the model is sent names the glossary does not explain: "
                        + undocumented
        );
    }

    @Test
    void noEntryDescribesAFieldThatIsGone() {
        Set<String> unreached =
                new TreeSet<>(DecisionFieldGlossary.describedNames());
        unreached.removeAll(namesInEveryFixtureRequest());
        unreached.removeAll(NOT_REACHED_BY_THE_FIXTURES);

        assertTrue(
                unreached.isEmpty(),
                "the glossary explains names nothing produces: " + unreached
        );
    }

    /**
     * The behaviour graph contributes nothing to the document.
     *
     * <p>{@code occurrenceOnBody} was its last model-facing output and was
     * removed on 2026-08-08, after {@code trajectory.recent} and
     * {@code trajectory.likelyNext} went the same way in ADR-0026. Measured
     * over 158 live turns that carried it, a thirteenth landing was commented
     * on as readily as a first — 28% silence on a repeat against 36% on a first
     * occurrence — so the field bought no restraint, and all three of its
     * observed readings were wrong: "the Commander is on the surface", "the
     * sixth visit during the expedition", and at a count of 13, "twelve
     * landings left". The graph goes on recording; it simply says nothing
     * here.</p>
     */
    @Test
    void noNameInTheDocumentComesFromTheBehaviourGraph() {
        Set<String> names = namesInEveryFixtureRequest();

        assertFalse(names.contains("occurrenceOnBody"), "" + names);
        assertFalse(names.contains("trajectory"));
        assertFalse(names.contains("likelyNext"));
        assertFalse(names.contains("recent"));
    }

    /** The list of unreachable-here names stays a list, not a loophole. */
    @Test
    void everyNameExemptedFromTheReachabilityCheckIsStillDescribed() {
        Set<String> orphaned = new TreeSet<>(NOT_REACHED_BY_THE_FIXTURES);
        orphaned.removeAll(DecisionFieldGlossary.describedNames());

        assertEquals(
                Set.of(),
                orphaned,
                "exempted from the check but described nowhere"
        );
    }

    /** The block goes to the provider, once, in every turn. */
    @Test
    void theGlossaryTravelsWithThePrompt() {
        String system = new kairon.llm.DecisionPromptFactory()
                .create("ru", "{\"events\":[]}")
                .systemMessage();

        assertTrue(system.contains("<fields>"), system);
        assertTrue(
                system.contains("planetClass - what kind of planet"),
                "a name is explained where it is sent"
        );
        assertFalse(
                system.contains("occurrenceOnBody"),
                "and a field that is no longer sent is explained nowhere"
        );
        assertFalse(
                system.contains("important")
                        || system.contains("must ")
                        || system.contains("never "),
                "a glossary says what a name denotes and asks for nothing"
        );
    }

    // ------------------------------------------------------------- fixtures

    private static Set<String> names;

    /**
     * Every fixture, replayed once through the production pipeline.
     *
     * <p>The real graph and the real projector, because two of the names only
     * exist with them: {@code occurrenceOnBody} is the graph's single
     * model-facing output, and the vehicle group needs the launch and recovery
     * to have been projected in order.</p>
     */
    private Set<String> namesInEveryFixtureRequest() {
        if (names != null) {
            return names;
        }
        Set<String> collected = new TreeSet<>();
        for (String fixture : FIXTURES) {
            collectFrom(fixture, collected);
        }
        assertFalse(collected.isEmpty(), "the fixtures produced no request");
        names = Set.copyOf(collected);
        return names;
    }

    private void collectFrom(String fixture, Set<String> into) {
        JacksonDecisionRequestSerializer serializer =
                new JacksonDecisionRequestSerializer();
        // The shipped registry, because it is what production reads and it is
        // what makes an organism's price reachable at all: valueMCr comes from
        // the registry or from nowhere, and exempting it as "no fixture drives
        // one" would be exempting a name the very next live turn produces.
        DecisionOrganicNames naming = new DecisionOrganicNames(
                JsonOrganicRegistryLoader.load(
                        Path.of("config", "organic-registry.json")
                ),
                DecisionOrganicNames.CANONICAL_LANGUAGE
        );
        LlmDecisionRequestFactory factory = new LlmDecisionRequestFactory(naming);
        try {
            Path directory = Files.createTempDirectory("glossary-" + fixture);
            try (DecisionProductionPipeline pipeline =
                         new DecisionProductionPipeline(
                                 directory,
                                 DecisionProductionPipeline.Options.production()
                                         .withOrganicNames(naming)
                         )) {
                fixtureRecords(fixture).forEach(pipeline::journal);
                pipeline.settleProjection();
                for (ProjectedObservation trigger
                        : pipeline.capturedTriggers()) {
                    // What the model receives, not what the projector could
                    // build: an observation the observer declines never
                    // becomes a turn, and its fields are not names the
                    // glossary owes an entry for.
                    if (trigger.trigger().payload()
                            instanceof kairon.observation.journal
                                    .JournalEventObservation journal
                            && !kairon.observer.LlmJournalEventSelection
                                    .admitsAsTrigger(journal)) {
                        continue;
                    }
                    collect(
                            read(serializer.serialize(factory.create(
                                    pipeline.inputsFor(List.of(trigger))
                            ))),
                            "",
                            into
                    );
                }
            }
        } catch (Exception failure) {
            throw new IllegalStateException(fixture, failure);
        }
    }

    private static List<String> fixtureRecords(String fixture) {
        String resource = "/kairon/behavior/fixtures/" + fixture;
        try (InputStream stream =
                     DecisionFieldGlossaryContractTest.class
                             .getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("missing fixture " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty())
                    .toList();
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /**
     * Every name, with a context group reported under its path.
     *
     * <p>{@code system} is a group of the situation and also the name of a
     * thing an event acted on, and the two are different claims. The group is
     * collected as {@code context.system} so the glossary can say each once.
     * </p>
     */
    private static void collect(
            JsonNode node,
            String trail,
            Set<String> names
    ) {
        if (node.isArray()) {
            node.forEach(item -> collect(item, trail, names));
            return;
        }
        if (!node.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> properties = node.fields();
        while (properties.hasNext()) {
            Map.Entry<String, JsonNode> property = properties.next();
            String name = property.getKey();
            names.add("context".equals(trail) ? "context." + name : name);
            collect(property.getValue(), name, names);
        }
    }
}
