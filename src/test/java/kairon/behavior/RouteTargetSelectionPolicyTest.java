package kairon.behavior;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.behavior.classify.RouteTargetSelectionPolicy;
import kairon.behavior.model.ContextSnapshot;
import kairon.behavior.model.EventOccurrence;
import kairon.behavior.model.EventOccurrenceId;
import kairon.behavior.model.EventOccurrenceSource;
import kairon.behavior.model.GraphId;
import kairon.behavior.model.SystemEpisodeId;
import kairon.behavior.normalize.BehaviorEventNormalizer;
import kairon.behavior.normalize.NormalizedBehaviorEvent;
import kairon.behavior.normalize.NormalizedEventType;
import kairon.observation.ObservationDraft.ObservationCaptureMode;
import kairon.observation.ObservationDraft.ObservationSource;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalLineParser;
import kairon.observation.journal.JournalLineParser.CompleteJournalRecord;
import kairon.observation.journal.JournalLineParser.ParsedJournalRecord;
import kairon.observation.journal.JournalObservationAdapter;
import kairon.state.CommanderLocationMode;
import kairon.state.FlightMode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When a route target record states something the route did not already say.
 *
 * <p>Every case runs the production normalizer over a real {@code FSDTarget}
 * record, so what is compared is the attribute map the graph would actually
 * hold rather than a hand-built one.</p>
 */
final class RouteTargetSelectionPolicyTest {

    private static final Instant WHEN =
            Instant.parse("2026-07-30T10:00:00Z");
    private static final GraphId GRAPH_ID = new GraphId("F-ROUTE", 9L);
    private static final SystemEpisodeId EPISODE_ID =
            new SystemEpisodeId("episode-route");

    private final JournalLineParser parser = new JournalLineParser();
    private final JournalObservationAdapter adapter =
            new JournalObservationAdapter(new ObservationSource(
                    "elite-journal",
                    "route-target-test"
            ));
    private final BehaviorEventNormalizer normalizer =
            new BehaviorEventNormalizer();
    private final RouteTargetSelectionPolicy policy =
            new RouteTargetSelectionPolicy();

    /** Each record needs its own source position to have its own identity. */
    private long sourceOffset;

    @Test
    void theSameAddressAtTheSameRoutePositionIsARestatement() {
        assertFalse(shouldRecord(
                """
                {"timestamp":"2026-07-30T10:00:00Z","event":"FSDTarget",
                 "Name":"Schieni GG-A c3-64","SystemAddress":23155945939738,
                 "StarClass":"M","RemainingJumpsInRoute":4}
                """,
                """
                {"timestamp":"2026-07-30T10:02:00Z","event":"FSDTarget",
                 "Name":"Schieni GG-A c3-64","SystemAddress":23155945939738,
                 "StarClass":"M","RemainingJumpsInRoute":4}
                """
        ));
    }

    @Test
    void theSameTargetOneJumpCloserIsARouteStateUpdate() {
        assertTrue(shouldRecord(
                """
                {"timestamp":"2026-07-30T10:00:00Z","event":"FSDTarget",
                 "Name":"Schieni GG-A c3-64","SystemAddress":23155945939738,
                 "StarClass":"M","RemainingJumpsInRoute":3}
                """,
                """
                {"timestamp":"2026-07-30T10:02:00Z","event":"FSDTarget",
                 "Name":"Schieni GG-A c3-64","SystemAddress":23155945939738,
                 "StarClass":"M","RemainingJumpsInRoute":2}
                """
        ));
    }

    @Test
    void aDifferentAddressIsADifferentTarget() {
        assertTrue(shouldRecord(
                """
                {"timestamp":"2026-07-30T10:00:00Z","event":"FSDTarget",
                 "Name":"Schieni GG-A c3-64","SystemAddress":23155945939738,
                 "StarClass":"M","RemainingJumpsInRoute":4}
                """,
                """
                {"timestamp":"2026-07-30T10:02:00Z","event":"FSDTarget",
                 "Name":"Colonia","SystemAddress":3238296097059,
                 "StarClass":"K","RemainingJumpsInRoute":4}
                """
        ));
    }

    /** With no stable key on either side, the name is the identity. */
    @Test
    void withoutAnAddressTheSameNameIsTheSameTarget() {
        assertFalse(shouldRecord(
                """
                {"timestamp":"2026-07-30T10:00:00Z","event":"FSDTarget",
                 "Name":"Schieni GG-A c3-64","StarClass":"M",
                 "RemainingJumpsInRoute":4}
                """,
                """
                {"timestamp":"2026-07-30T10:02:00Z","event":"FSDTarget",
                 "Name":"schieni gg-a c3-64","StarClass":"M",
                 "RemainingJumpsInRoute":4}
                """
        ));
    }

    @Test
    void withoutAnAddressADifferentNameIsADifferentTarget() {
        assertTrue(shouldRecord(
                """
                {"timestamp":"2026-07-30T10:00:00Z","event":"FSDTarget",
                 "Name":"Schieni GG-A c3-64","RemainingJumpsInRoute":4}
                """,
                """
                {"timestamp":"2026-07-30T10:02:00Z","event":"FSDTarget",
                 "Name":"Colonia","RemainingJumpsInRoute":4}
                """
        ));
    }

    /** A stable key settles it; how the name is written does not. */
    @Test
    void aCosmeticNameDifferenceUnderOneAddressIsNotANewSelection() {
        assertFalse(shouldRecord(
                """
                {"timestamp":"2026-07-30T10:00:00Z","event":"FSDTarget",
                 "Name":"Schieni GG-A c3-64","SystemAddress":23155945939738,
                 "RemainingJumpsInRoute":4}
                """,
                """
                {"timestamp":"2026-07-30T10:02:00Z","event":"FSDTarget",
                 "Name":"  SCHIENI GG-A C3-64 ","SystemAddress":23155945939738,
                 "RemainingJumpsInRoute":4}
                """
        ));
    }

    /** A star class describes the target; it does not choose one. */
    @Test
    void aCorrectedStarClassAloneIsNotANewSelection() {
        assertFalse(shouldRecord(
                """
                {"timestamp":"2026-07-30T10:00:00Z","event":"FSDTarget",
                 "Name":"Schieni GG-A c3-64","SystemAddress":23155945939738,
                 "StarClass":"M","RemainingJumpsInRoute":4}
                """,
                """
                {"timestamp":"2026-07-30T10:02:00Z","event":"FSDTarget",
                 "Name":"Schieni GG-A c3-64","SystemAddress":23155945939738,
                 "StarClass":"K","RemainingJumpsInRoute":4}
                """
        ));
    }

    /** Learning the route length is not a restatement of it. */
    @Test
    void aRoutePositionAppearingForTheFirstTimeIsAnUpdate() {
        assertTrue(shouldRecord(
                """
                {"timestamp":"2026-07-30T10:00:00Z","event":"FSDTarget",
                 "Name":"Schieni GG-A c3-64","SystemAddress":23155945939738,
                 "StarClass":"M"}
                """,
                """
                {"timestamp":"2026-07-30T10:02:00Z","event":"FSDTarget",
                 "Name":"Schieni GG-A c3-64","SystemAddress":23155945939738,
                 "StarClass":"M","RemainingJumpsInRoute":4}
                """
        ));
    }

    @Test
    void twoRecordsWithNoRoutePositionAtAllStillMatch() {
        assertFalse(shouldRecord(
                """
                {"timestamp":"2026-07-30T10:00:00Z","event":"FSDTarget",
                 "Name":"Schieni GG-A c3-64","SystemAddress":23155945939738,
                 "StarClass":"M"}
                """,
                """
                {"timestamp":"2026-07-30T10:02:00Z","event":"FSDTarget",
                 "Name":"Schieni GG-A c3-64","SystemAddress":23155945939738,
                 "StarClass":"M"}
                """
        ));
    }

    /**
     * A stable key appearing where there was none is preserved.
     *
     * <p>The names match, but the second record establishes an identity the
     * first did not have, and an occurrence already written cannot be enriched
     * in place. Keeping it is the conservative half of the choice.</p>
     */
    @Test
    void anAddressAppearingForTheFirstTimeIsPreserved() {
        assertTrue(shouldRecord(
                """
                {"timestamp":"2026-07-30T10:00:00Z","event":"FSDTarget",
                 "Name":"Schieni GG-A c3-64","RemainingJumpsInRoute":4}
                """,
                """
                {"timestamp":"2026-07-30T10:02:00Z","event":"FSDTarget",
                 "Name":"Schieni GG-A c3-64","SystemAddress":23155945939738,
                 "RemainingJumpsInRoute":4}
                """
        ));
    }

    /** The first target of an episode has nothing to be a repeat of. */
    @Test
    void theFirstSelectionIsAlwaysRecorded() {
        assertTrue(policy.shouldRecord(null, normalize("""
                {"timestamp":"2026-07-30T10:00:00Z","event":"FSDTarget",
                 "Name":"Schieni GG-A c3-64","SystemAddress":23155945939738,
                 "RemainingJumpsInRoute":4}
                """)));
    }

    /** The policy speaks about route targets and about nothing else. */
    @Test
    void anotherEventTypeIsNotThisPolicysBusiness() {
        NormalizedBehaviorEvent touchdown = normalize("""
                {"timestamp":"2026-07-30T10:02:00Z","event":"Touchdown",
                 "StarSystem":"Schieni GG-A c3-84","SystemAddress":23155,
                 "Body":"Schieni GG-A c3-84 4 a","BodyID":20,
                 "PlayerControlled":true}
                """);
        assertTrue(policy.shouldRecord(
                occurrenceOf(touchdown),
                touchdown
        ));
    }

    // ------------------------------------------------------------- fixtures

    private boolean shouldRecord(String previousRecord, String currentRecord) {
        return policy.shouldRecord(
                occurrenceOf(normalize(previousRecord)),
                normalize(currentRecord)
        );
    }

    private NormalizedBehaviorEvent normalize(String rawJson) {
        byte[] bytes = rawJson.strip().getBytes(StandardCharsets.UTF_8);
        ParsedJournalRecord parsed = (ParsedJournalRecord) parser.parse(
                new CompleteJournalRecord(
                        "Journal.route-test.log",
                        sourceOffset,
                        bytes
                )
        );
        sourceOffset += bytes.length + 1L;
        JournalEventObservation event = adapter.adapt(
                parsed,
                ObservationCaptureMode.REPLAY,
                parsed.optionalJournalTimestamp().orElse(WHEN)
        ).payload();
        return normalizer.normalize(event, WHEN);
    }

    private static EventOccurrence occurrenceOf(
            NormalizedBehaviorEvent normalized
    ) {
        return new EventOccurrence(
                new EventOccurrenceId("occurrence-previous"),
                GRAPH_ID,
                EPISODE_ID,
                0L,
                normalized.eventType(),
                normalized.originalEventName(),
                EventOccurrenceSource.JOURNAL,
                normalized.timestamp(),
                1L,
                "Journal.route-test.log",
                normalized.attributes(),
                context()
        );
    }

    private static ContextSnapshot context() {
        return new ContextSnapshot(
                "F-ROUTE",
                9L,
                "explorer_nx",
                "Wanderer",
                null,
                23155945939738L,
                "Schieni GG-A c3-64",
                null,
                null,
                null,
                CommanderLocationMode.SHIP,
                FlightMode.SUPERCRUISE,
                "SHIP",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    /** Guards the attribute names the comparison depends on. */
    @Test
    void theNormalizerStillCarriesTheFieldsThisPolicyReads() {
        Map<String, JsonNode> attributes = normalize("""
                {"timestamp":"2026-07-30T10:00:00Z","event":"FSDTarget",
                 "Name":"Schieni GG-A c3-64","SystemAddress":23155945939738,
                 "StarClass":"M","RemainingJumpsInRoute":4}
                """).attributes();

        assertTrue(attributes.containsKey("SystemAddress"));
        assertTrue(attributes.containsKey("Name"));
        assertTrue(attributes.containsKey("RemainingJumpsInRoute"));
        assertTrue(attributes.containsKey("StarClass"));
        assertTrue(NormalizedEventType.FSD_TARGET_SELECTED.equals(
                normalize("""
                        {"timestamp":"2026-07-30T10:00:00Z",
                         "event":"FSDTarget","Name":"Schieni GG-A c3-64"}
                        """).eventType()
        ));
    }
}
