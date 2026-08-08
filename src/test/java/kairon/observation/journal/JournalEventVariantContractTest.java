package kairon.observation.journal;

import com.fasterxml.jackson.databind.ObjectMapper;
import kairon.behavior.normalize.BehaviorEventNormalizer;
import kairon.behavior.normalize.NormalizedEventType;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.event.exploration.Scan;
import kairon.observer.decision.DecisionEventCatalog;
import kairon.observer.decision.DecisionEventRule;
import kairon.observer.decision.DecisionMechanism;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One class is one domain event, asked of every layer that names one.
 *
 * <p>This is the architecture the variant split exists for, stated as a test.
 * A journal record is a transport identity; a wire event name is not a unit of
 * meaning. {@code ScanOrganic} is one name and four domain events,
 * {@code LaunchDrone} is nine, and a {@code Scan} is two — and while that was
 * true of the class, every layer that needed to tell them apart had to
 * rediscover it from the record: the behaviour normalizer with a switch, the
 * decision catalogue with a predicate, the description with a ternary. Three
 * readings of one discriminator, each of which could have been the one that
 * drifted, and each layer's own tests green while they disagreed.</p>
 *
 * <p>So the dispatch happens once, in the parser, and what is asserted here is
 * that nothing downstream dispatches again: for one parsed class there is one
 * structural type, one domain kind and one description, whatever the record
 * said. A layer that re-read the record would answer two things for one class
 * and fail here.</p>
 *
 * <p>The corpus deliberately varies exactly the fields the discriminators are
 * read from, including values this build does not recognise. A corpus that
 * produced one class per wire event would pass every assertion below without
 * testing anything, so the split is asserted too.</p>
 */
final class JournalEventVariantContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final BehaviorEventNormalizer NORMALIZER =
            new BehaviorEventNormalizer();

    /**
     * Records whose wire event dispatches to more than one class, plus
     * controls.
     *
     * <p>Every discriminator value each split record recognises, an
     * unrecognised value for each vocabulary Frontier can extend, and two
     * records of one unsplit type — a type that means one thing has to answer
     * these questions identically as well.</p>
     */
    private static final List<String> CORPUS = List.of(
            // Scan: the arrival-star milestone, then four readings that are
            // not it — already discovered, no discovery claim at all, a
            // detailed reading, and a planet.
            """
            {"timestamp":"2026-07-30T10:00:00Z","event":"Scan",
             "ScanType":"AutoScan","SystemAddress":23155,"BodyID":0,
             "BodyName":"Schieni","StarType":"K","WasDiscovered":false}
            """,
            """
            {"timestamp":"2026-07-30T10:00:01Z","event":"Scan",
             "ScanType":"AutoScan","SystemAddress":23155,"BodyID":0,
             "BodyName":"Schieni","StarType":"K","WasDiscovered":true}
            """,
            """
            {"timestamp":"2026-07-30T10:00:02Z","event":"Scan",
             "ScanType":"AutoScan","SystemAddress":23155,"BodyID":0,
             "BodyName":"Schieni","StarType":"K"}
            """,
            """
            {"timestamp":"2026-07-30T10:00:03Z","event":"Scan",
             "ScanType":"Detailed","SystemAddress":23155,"BodyID":0,
             "BodyName":"Schieni","StarType":"K","WasDiscovered":false}
            """,
            """
            {"timestamp":"2026-07-30T10:00:04Z","event":"Scan",
             "ScanType":"Basic","SystemAddress":23155,"BodyID":20,
             "BodyName":"Schieni 4 a","PlanetClass":"Icy body",
             "WasDiscovered":false}
            """,
            // ScanOrganic: the three researched steps and one this build does
            // not recognise.
            """
            {"timestamp":"2026-07-30T10:01:00Z","event":"ScanOrganic",
             "ScanType":"Log","SystemAddress":23155,"Body":20,
             "Genus":"$Codex_Ent_Bacterial_Genus_Name;"}
            """,
            """
            {"timestamp":"2026-07-30T10:01:01Z","event":"ScanOrganic",
             "ScanType":"Sample","SystemAddress":23155,"Body":20,
             "Genus":"$Codex_Ent_Bacterial_Genus_Name;"}
            """,
            """
            {"timestamp":"2026-07-30T10:01:02Z","event":"ScanOrganic",
             "ScanType":"Analyse","SystemAddress":23155,"Body":20,
             "Genus":"$Codex_Ent_Bacterial_Genus_Name;"}
            """,
            """
            {"timestamp":"2026-07-30T10:01:03Z","event":"ScanOrganic",
             "ScanType":"Resample","SystemAddress":23155,"Body":20}
            """,
            // StartJump: both charges and an unrecognised one.
            """
            {"timestamp":"2026-07-30T10:02:00Z","event":"StartJump",
             "JumpType":"Hyperspace","StarSystem":"Schieni",
             "SystemAddress":23155,"StarClass":"K"}
            """,
            """
            {"timestamp":"2026-07-30T10:02:01Z","event":"StartJump",
             "JumpType":"Supercruise"}
            """,
            """
            {"timestamp":"2026-07-30T10:02:02Z","event":"StartJump",
             "JumpType":"Taxi"}
            """,
            // LaunchDrone: the eight researched limpets, a launch the journal
            // did not name canonically, and a name in another case.
            """
            {"timestamp":"2026-07-30T10:03:00Z","event":"LaunchDrone",
             "Type":"Hatchbreaker"}
            """,
            """
            {"timestamp":"2026-07-30T10:03:01Z","event":"LaunchDrone",
             "Type":"FuelTransfer"}
            """,
            """
            {"timestamp":"2026-07-30T10:03:02Z","event":"LaunchDrone",
             "Type":"Collection"}
            """,
            """
            {"timestamp":"2026-07-30T10:03:03Z","event":"LaunchDrone",
             "Type":"Prospector"}
            """,
            """
            {"timestamp":"2026-07-30T10:03:04Z","event":"LaunchDrone",
             "Type":"Repair"}
            """,
            """
            {"timestamp":"2026-07-30T10:03:05Z","event":"LaunchDrone",
             "Type":"Research"}
            """,
            """
            {"timestamp":"2026-07-30T10:03:06Z","event":"LaunchDrone",
             "Type":"Decontamination"}
            """,
            """
            {"timestamp":"2026-07-30T10:03:07Z","event":"LaunchDrone",
             "Type":"Recon"}
            """,
            """
            {"timestamp":"2026-07-30T10:03:08Z","event":"LaunchDrone",
             "Type":"Mining"}
            """,
            """
            {"timestamp":"2026-07-30T10:03:09Z","event":"LaunchDrone",
             "Type":"collection"}
            """,
            // EngineerLegacyConvert: the preview, the conversion, and a record
            // that does not say which.
            """
            {"timestamp":"2026-07-30T10:04:00Z","event":"EngineerLegacyConvert",
             "IsPreview":true,"Module":"int_hyperdrive_size5_class5",
             "BlueprintName":"FSD_LongRange","Level":4}
            """,
            """
            {"timestamp":"2026-07-30T10:04:01Z","event":"EngineerLegacyConvert",
             "IsPreview":false,"Module":"int_hyperdrive_size5_class5",
             "BlueprintName":"FSD_LongRange","Level":4}
            """,
            """
            {"timestamp":"2026-07-30T10:04:02Z","event":"EngineerLegacyConvert",
             "Module":"int_hyperdrive_size5_class5"}
            """,
            // Controls: one type that means one thing, told twice with
            // different values.
            """
            {"timestamp":"2026-07-30T10:05:00Z","event":"Touchdown",
             "PlayerControlled":true,"StarSystem":"Schieni",
             "Body":"Schieni 4 a","Latitude":18.7,"Longitude":-35.0}
            """,
            """
            {"timestamp":"2026-07-30T10:05:01Z","event":"Touchdown",
             "PlayerControlled":false,"StarSystem":"Sol","Body":"Mars"}
            """,
            """
            {"timestamp":"2026-07-30T10:05:02Z","event":"FSSBodySignals",
             "SystemAddress":23155,"BodyID":20,"BodyName":"Schieni 4 a",
             "Signals":[{"Type":"$SAA_SignalType_Biological;","Count":1}]}
            """
    );

    /** A class is one structural event, whatever the record it came from. */
    @Test
    void oneClassNormalizesToOneStructuralType() {
        assertOneAnswerPerClass(
                "structural type",
                event -> NORMALIZER
                        .normalize(event, Instant.parse("2026-07-30T10:00:00Z"))
                        .eventType()
                        .value()
        );
    }

    /** A class is one domain kind, and the catalogue never reads the record. */
    @Test
    void oneClassIsOneDomainKind() {
        assertOneAnswerPerClass("domain kind", event -> {
            DecisionEventRule rule = DecisionEventCatalog.ruleFor(event);
            assertSame(
                    DecisionEventCatalog.ruleFor(event.getClass()),
                    rule,
                    "the rule for a record is the rule for its class: "
                            + event.getClass().getName()
            );
            return rule == null ? null : rule.kind();
        });
    }

    /**
     * A class is one sentence.
     *
     * <p>The description used to be allowed to choose between fixed phrases by
     * reading its own fields, and two records did. Both are split now, so the
     * sentence is a property of the class and nothing else.</p>
     */
    @Test
    void oneClassSaysOneThing() {
        assertOneAnswerPerClass(
                "description",
                event -> event instanceof LlmPresentableJournalEvent presentable
                        ? presentable.modelFacingDescription()
                        : null
        );
    }

    /**
     * The corpus really does split, so the assertions above have something to
     * be true of.
     *
     * <p>Seven wire event names, twenty-three classes. If a split were undone
     * the counts would converge and every assertion above would still pass
     * while testing nothing.</p>
     */
    @Test
    void theCorpusReachesMoreClassesThanWireEvents() {
        Set<String> wireEvents = new LinkedHashSet<>();
        Set<Class<?>> classes = new LinkedHashSet<>();
        for (String rawJson : CORPUS) {
            JournalEventObservation event = event(rawJson);
            wireEvents.add(event.raw().optionalEventType().orElseThrow());
            classes.add(event.getClass());
        }
        assertEquals(7, wireEvents.size(), "seven wire events");
        assertEquals(
                23,
                classes.size(),
                "and twenty-three domain events among them"
        );
    }

    /**
     * A scan's two readings stay two things all the way down.
     *
     * <p>The one split whose variants differ in every answer, which is why it
     * was the one the layers disagreed about. Asserted across all three layers
     * at once: a milestone filed as a body scan, or a body scan filed as a
     * milestone, fails here rather than in whichever layer noticed second.</p>
     */
    @Test
    void aScansTwoReadingsAreTwoEventsInEveryLayer() {
        JournalEventObservation milestone = event(CORPUS.getFirst());
        JournalEventObservation reading = event(CORPUS.get(3));

        assertTrue(milestone instanceof Scan.UndiscoveredStar);
        assertTrue(reading instanceof Scan.BodyReading);

        assertEquals(
                NormalizedEventType.SYSTEM_UNDISCOVERED_CONFIRMED,
                normalizedTypeOf(milestone)
        );
        assertEquals(
                NormalizedEventType.BODY_SCANNED,
                normalizedTypeOf(reading)
        );
        assertEquals(
                "SYSTEM_UNDISCOVERED_CONFIRMED",
                DecisionEventCatalog.ruleFor(milestone).kind()
        );
        assertEquals(
                "BODY_SCANNED",
                DecisionEventCatalog.ruleFor(reading).kind()
        );
        assertNotEquals(
                describe(milestone),
                describe(reading),
                "and neither borrows the other's sentence"
        );
    }

    /**
     * What the milestone claims, now that it is a class rather than a
     * predicate.
     *
     * <p>Its kind is what the model reads, and the claims beside it are what
     * the projection acts on: the object it names, the count it must not carry,
     * and the attributes it keeps from the record it was derived from. The
     * slice it is read against is asserted where the profiles are, in
     * {@code DecisionContextProfileContractTest}.</p>
     */
    @Test
    void theArrivalStarMilestoneKeepsItsKindAndItsClaims() {
        DecisionEventRule rule =
                DecisionEventCatalog.ruleFor(Scan.UndiscoveredStar.class);

        assertEquals("SYSTEM_UNDISCOVERED_CONFIRMED", rule.kind());
        assertEquals(
                DecisionMechanism.EXPLORATION,
                rule.mechanism(),
                "an arrival in an undiscovered system is exploration"
        );
        assertEquals("arrivalStar", rule.objectName());
        assertEquals(
                Set.of("system", "starType", "previouslyDiscovered"),
                rule.retainedQualifiers()
        );
    }

    /**
     * A variant reaches the record's rule where the two share a kind.
     *
     * <p>The other half of the keying: a wire event whose steps mean one thing
     * is catalogued once, under the record, and every step resolves to that one
     * rule rather than needing a line each.</p>
     */
    @Test
    void aVariantSharingItsRecordsKindIsCataloguedOnce() {
        DecisionEventRule byRecord = DecisionEventCatalog.ruleFor(Scan.class);

        assertEquals("BODY_SCANNED", byRecord.kind());
        assertSame(
                byRecord,
                DecisionEventCatalog.ruleFor(Scan.BodyReading.class),
                "the reading answers through the record it belongs to"
        );
        assertNotEquals(
                byRecord,
                DecisionEventCatalog.ruleFor(Scan.UndiscoveredStar.class),
                "while the variant with its own rule keeps it"
        );
    }

    // ------------------------------------------------------------- fixtures

    /**
     * Ask one question of every record, grouped by the class it parsed to.
     *
     * <p>A layer that re-read the record would answer differently for two
     * records of one class, and the failure names the class and both
     * answers.</p>
     */
    private static void assertOneAnswerPerClass(
            String question,
            java.util.function.Function<JournalEventObservation, String> ask
    ) {
        Map<Class<?>, Set<String>> answers = new LinkedHashMap<>();
        for (String rawJson : CORPUS) {
            JournalEventObservation event = event(rawJson);
            String answer = ask.apply(event);
            if (answer == null) {
                continue;
            }
            answers.computeIfAbsent(
                    event.getClass(),
                    ignored -> new LinkedHashSet<>()
            ).add(answer);
        }
        List<String> divided = new ArrayList<>();
        answers.forEach((eventType, distinct) -> {
            if (distinct.size() > 1) {
                divided.add(eventType.getName() + " " + distinct);
            }
        });
        assertEquals(
                List.of(),
                divided,
                "one class, one " + question
        );
    }

    private static NormalizedEventType normalizedTypeOf(
            JournalEventObservation event
    ) {
        return NORMALIZER
                .normalize(event, Instant.parse("2026-07-30T10:00:00Z"))
                .eventType();
    }

    private static String describe(JournalEventObservation event) {
        return ((LlmPresentableJournalEvent) event).modelFacingDescription();
    }

    private static JournalEventObservation event(String rawJson) {
        return JournalEventCatalog.create(raw(rawJson));
    }

    private static RawJournalData raw(String rawJson) {
        try {
            String compact = JSON.readTree(rawJson).toString();
            return new RawJournalData(
                    compact,
                    JSON.readTree(compact),
                    Optional.ofNullable(
                            JSON.readTree(compact).path("event").textValue()
                    ),
                    Optional.of(Instant.parse("2026-07-30T10:00:00Z"))
            );
        } catch (Exception failure) {
            throw new IllegalStateException(rawJson, failure);
        }
    }
}
