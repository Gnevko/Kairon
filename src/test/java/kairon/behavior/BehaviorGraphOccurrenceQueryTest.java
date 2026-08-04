package kairon.behavior;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kairon.behavior.graph.ActiveEpisodeNodeOccurrencesSnapshot;
import kairon.behavior.graph.BehaviorGraphQueryService;
import kairon.behavior.graph.BehaviorGraphService;
import kairon.behavior.graph.BehaviorGraphVisualizationSnapshot;
import kairon.behavior.graph.EventOccurrenceDetailsSnapshot;
import kairon.behavior.model.ContextKey;
import kairon.behavior.model.ContextSnapshot;
import kairon.behavior.model.EpisodeCompletionReason;
import kairon.behavior.model.EpisodeEntrySource;
import kairon.behavior.model.EventOccurrence;
import kairon.behavior.model.EventOccurrenceId;
import kairon.behavior.model.EventOccurrenceSource;
import kairon.behavior.model.GraphCursor;
import kairon.behavior.model.GraphId;
import kairon.behavior.model.OccurrenceTransition;
import kairon.behavior.model.ShipBehaviorGraph;
import kairon.behavior.model.SystemEpisode;
import kairon.behavior.model.SystemEpisodeId;
import kairon.behavior.model.TransitionOccurrenceId;
import kairon.behavior.normalize.NormalizedEventType;
import kairon.behavior.persistence.InMemoryBehaviorGraphStore;
import kairon.config.KaironConfiguration.BehaviorGraphConfiguration;
import kairon.state.CommanderLocationMode;
import kairon.state.CurrentGameStateProjector;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BehaviorGraphOccurrenceQueryTest {

    private static final GraphId GRAPH_A = new GraphId("F100", 9L);
    private static final GraphId GRAPH_B = new GraphId("F100", 14L);
    private static final Instant START =
            Instant.parse("2026-07-26T19:20:00Z");

    @Test
    void queryReturnsOnlySelectedTypeFromActiveEpisodeInCanonicalOrder() {
        Fixture fixture = fixture();

        ActiveEpisodeNodeOccurrencesSnapshot signals =
                fixture.query.getActiveEpisodeOccurrences(
                        GRAPH_A,
                        NormalizedEventType.SAA_SIGNALS_FOUND
                );
        ActiveEpisodeNodeOccurrencesSnapshot touchdowns =
                fixture.query.getActiveEpisodeOccurrences(
                        GRAPH_A,
                        NormalizedEventType.TOUCHDOWN
                );

        assertEquals(Optional.of(fixture.active.id()),
                signals.activeEpisodeId());
        assertEquals(
                List.of("active-signals-body-7"),
                signals.occurrences().stream()
                        .map(row -> row.occurrenceId().value())
                        .toList()
        );
        assertEquals(
                List.of(2L, 3L),
                touchdowns.occurrences().stream()
                        .map(row -> row.episodeSequence())
                        .toList()
        );
        assertEquals(
                List.of("active-touchdown-1", "active-touchdown-2"),
                touchdowns.occurrences().stream()
                        .map(row -> row.occurrenceId().value())
                        .toList()
        );
        assertEquals(
                touchdowns.occurrences().get(0).timestamp(),
                touchdowns.occurrences().get(1).timestamp(),
                "equal timestamps must retain episode sequence order"
        );
        assertTrue(touchdowns.occurrences().stream().allMatch(row ->
                row.originalEventName().equals("Touchdown")));
    }

    @Test
    void completedOccurrencesAndOtherEventTypesNeverContributeRows() {
        Fixture fixture = fixture();

        ActiveEpisodeNodeOccurrencesSnapshot disembark =
                fixture.query.getActiveEpisodeOccurrences(
                        GRAPH_A,
                        NormalizedEventType.DISEMBARK
                );
        ActiveEpisodeNodeOccurrencesSnapshot signals =
                fixture.query.getActiveEpisodeOccurrences(
                        GRAPH_A,
                        NormalizedEventType.SAA_SIGNALS_FOUND
                );

        assertTrue(disembark.occurrences().isEmpty());
        assertEquals(1, signals.occurrences().size());
        assertFalse(signals.occurrences().stream().anyMatch(row ->
                row.occurrenceId().value().startsWith("completed-")));

        BehaviorGraphVisualizationSnapshot graphSnapshot =
                fixture.query.getVisualizationSnapshot(
                        GRAPH_A,
                        START.plusSeconds(200)
                ).orElseThrow();
        assertEquals(
                activeCount(
                        graphSnapshot,
                        NormalizedEventType.SAA_SIGNALS_FOUND
                ),
                signals.occurrences().size()
        );
        assertEquals(
                activeCount(graphSnapshot, NormalizedEventType.DISEMBARK),
                disembark.occurrences().size()
        );
    }

    @Test
    void noActiveEpisodeAndAnotherShipCannotLeakRows() {
        InMemoryBehaviorGraphStore store =
                new InMemoryBehaviorGraphStore();
        SystemEpisode completed = buildCompletedEpisode(GRAPH_A);
        store.saveEpisode(completed);
        store.saveGraph(graphFromEpisodes(
                GRAPH_A,
                List.of(completed),
                null
        ));
        BehaviorGraphQueryService query = query(store);

        ActiveEpisodeNodeOccurrencesSnapshot noActive =
                query.getActiveEpisodeOccurrences(
                        GRAPH_A,
                        NormalizedEventType.TOUCHDOWN
                );
        ActiveEpisodeNodeOccurrencesSnapshot anotherGraph =
                query.getActiveEpisodeOccurrences(
                        GRAPH_B,
                        NormalizedEventType.TOUCHDOWN
                );

        assertTrue(noActive.activeEpisodeId().isEmpty());
        assertTrue(noActive.occurrences().isEmpty());
        assertEquals(0L, noActive.episodeVersion());
        assertTrue(anotherGraph.activeEpisodeId().isEmpty());
        assertTrue(anotherGraph.occurrences().isEmpty());
    }

    @Test
    void detailsAreActiveEpisodeOnlyCompleteAndDefensivelyImmutable() {
        Fixture fixture = fixture();
        EventOccurrence activeSignals = fixture.active.timeline().get(1);
        EventOccurrence completedSignals =
                fixture.completed.timeline().get(1);

        EventOccurrenceDetailsSnapshot details =
                fixture.query.getActiveEpisodeOccurrenceDetails(
                        GRAPH_A,
                        fixture.active.id(),
                        activeSignals.id()
                ).orElseThrow();

        assertEquals(activeSignals.id(), details.occurrenceId());
        assertEquals(activeSignals.eventType(), details.eventType());
        assertEquals(activeSignals.originalEventName(),
                details.originalEventName());
        assertEquals(activeSignals.timestamp(), details.timestamp());
        assertEquals(activeSignals.episodeSequence(),
                details.episodeSequence());
        assertEquals(activeSignals.sourceSequence(),
                details.sourceSequence());
        assertEquals(activeSignals.sourceId(), details.sourceId());
        assertEquals(7L, details.attributes().get("bodyId").longValue());
        assertTrue(details.attributes().containsKey("futurePayload"));
        assertEquals("System B", details.context().systemName());
        assertEquals("Body 7", details.context().bodyName());

        assertTrue(fixture.query.getActiveEpisodeOccurrenceDetails(
                GRAPH_A,
                fixture.completed.id(),
                completedSignals.id()
        ).isEmpty());
        assertTrue(fixture.query.getActiveEpisodeOccurrenceDetails(
                GRAPH_B,
                fixture.active.id(),
                activeSignals.id()
        ).isEmpty());
        assertThrows(
                UnsupportedOperationException.class,
                () -> details.attributes().put(
                        "illegal",
                        JsonNodeFactory.instance.numberNode(1)
                )
        );

        ObjectNode returned = (ObjectNode) details.attributes()
                .get("futurePayload");
        returned.put("mutated", true);
        assertFalse(details.attributes()
                .get("futurePayload")
                .has("mutated"));
    }

    @Test
    void occurrenceListSnapshotIsImmutableAndUsesEpisodeVersion() {
        Fixture fixture = fixture();

        ActiveEpisodeNodeOccurrencesSnapshot snapshot =
                fixture.query.getActiveEpisodeOccurrences(
                        GRAPH_A,
                        NormalizedEventType.TOUCHDOWN
                );

        assertEquals(fixture.active.timeline().size(),
                snapshot.episodeVersion());
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.occurrences().clear()
        );
    }

    private static Fixture fixture() {
        InMemoryBehaviorGraphStore store =
                new InMemoryBehaviorGraphStore();
        SystemEpisode completed = buildCompletedEpisode(GRAPH_A);
        SystemEpisode active = buildActiveEpisode(GRAPH_A);
        store.saveEpisode(completed);
        store.saveEpisode(active);
        EventOccurrence cursorOccurrence = active.timeline().getLast();
        GraphCursor cursor = new GraphCursor(
                GRAPH_A,
                active.id(),
                cursorOccurrence.id(),
                cursorOccurrence.eventType(),
                cursorOccurrence.timestamp()
        );
        store.saveGraph(graphFromEpisodes(
                GRAPH_A,
                List.of(completed, active),
                cursor
        ));
        return new Fixture(query(store), completed, active);
    }

    private static SystemEpisode buildCompletedEpisode(GraphId graphId) {
        SystemEpisodeId episodeId =
                new SystemEpisodeId("episode-completed");
        Instant start = START;
        SystemEpisode episode = startEpisode(
                graphId,
                episodeId,
                "System A",
                start,
                "completed-root"
        );
        episode = append(
                episode,
                occurrence(
                        graphId,
                        episodeId,
                        1,
                        NormalizedEventType.SAA_SIGNALS_FOUND,
                        "SAASignalsFound",
                        start.plusSeconds(1),
                        "completed-signals-body-2",
                        attributes(2L)
                )
        );
        episode = append(
                episode,
                occurrence(
                        graphId,
                        episodeId,
                        2,
                        NormalizedEventType.SAA_SIGNALS_FOUND,
                        "SAASignalsFound",
                        start.plusSeconds(2),
                        "completed-signals-body-5",
                        attributes(5L)
                )
        );
        episode = append(
                episode,
                occurrence(
                        graphId,
                        episodeId,
                        3,
                        NormalizedEventType.TOUCHDOWN,
                        "Touchdown",
                        start.plusSeconds(3),
                        "completed-touchdown",
                        Map.of()
                )
        );
        episode = append(
                episode,
                occurrence(
                        graphId,
                        episodeId,
                        4,
                        NormalizedEventType.DISEMBARK,
                        "Disembark",
                        start.plusSeconds(4),
                        "completed-disembark",
                        Map.of()
                )
        );
        return episode.complete(
                start.plusSeconds(5),
                EpisodeCompletionReason.NEXT_SYSTEM
        );
    }

    private static SystemEpisode buildActiveEpisode(GraphId graphId) {
        SystemEpisodeId episodeId = new SystemEpisodeId("episode-active");
        Instant start = START.plusSeconds(100);
        SystemEpisode episode = startEpisode(
                graphId,
                episodeId,
                "System B",
                start,
                "active-root"
        );
        episode = append(
                episode,
                occurrence(
                        graphId,
                        episodeId,
                        1,
                        NormalizedEventType.SAA_SIGNALS_FOUND,
                        "SAASignalsFound",
                        start.plusSeconds(1),
                        "active-signals-body-7",
                        attributes(7L)
                )
        );
        Instant equalTimestamp = start.plusSeconds(2);
        episode = append(
                episode,
                occurrence(
                        graphId,
                        episodeId,
                        2,
                        NormalizedEventType.TOUCHDOWN,
                        "Touchdown",
                        equalTimestamp,
                        "active-touchdown-1",
                        Map.of()
                )
        );
        return append(
                episode,
                occurrence(
                        graphId,
                        episodeId,
                        3,
                        NormalizedEventType.TOUCHDOWN,
                        "Touchdown",
                        equalTimestamp,
                        "active-touchdown-2",
                        Map.of()
                )
        );
    }

    private static SystemEpisode startEpisode(
            GraphId graphId,
            SystemEpisodeId episodeId,
            String systemName,
            Instant timestamp,
            String occurrenceId
    ) {
        EventOccurrence root = occurrence(
                graphId,
                episodeId,
                0,
                NormalizedEventType.SYSTEM_ENTRY,
                "FSDJump",
                timestamp,
                occurrenceId,
                Map.of()
        );
        return SystemEpisode.startWithRoot(
                episodeId,
                graphId,
                1000L + graphId.shipId(),
                systemName,
                EpisodeEntrySource.FSD_JUMP,
                root
        );
    }

    private static SystemEpisode append(
            SystemEpisode episode,
            EventOccurrence occurrence
    ) {
        EventOccurrence previous = episode.timeline().getLast();
        OccurrenceTransition transition = new OccurrenceTransition(
                new TransitionOccurrenceId(
                        episode.id().value()
                                + "-transition-"
                                + occurrence.episodeSequence()
                ),
                episode.id(),
                previous.id(),
                occurrence.id(),
                previous.eventType(),
                occurrence.eventType(),
                occurrence.timestamp(),
                ContextKey.EMPTY
        );
        return episode.appendOccurrence(occurrence, transition);
    }

    private static EventOccurrence occurrence(
            GraphId graphId,
            SystemEpisodeId episodeId,
            long episodeSequence,
            NormalizedEventType eventType,
            String originalEventName,
            Instant timestamp,
            String occurrenceId,
            Map<String, JsonNode> attributes
    ) {
        long bodyId = attributes.getOrDefault(
                "bodyId",
                JsonNodeFactory.instance.numberNode(0L)
        ).longValue();
        return new EventOccurrence(
                new EventOccurrenceId(occurrenceId),
                graphId,
                episodeId,
                episodeSequence,
                eventType,
                originalEventName,
                EventOccurrenceSource.JOURNAL,
                timestamp,
                100L + episodeSequence,
                "Journal.occurrence-query-test.log",
                attributes,
                context(
                        graphId,
                        episodeId.value().contains("active")
                                ? "System B"
                                : "System A",
                        bodyId == 0L ? null : "Body " + bodyId
                )
        );
    }

    private static Map<String, JsonNode> attributes(long bodyId) {
        ObjectNode futurePayload = JsonNodeFactory.instance.objectNode();
        futurePayload.put("source", "unknown-future-source");
        futurePayload.set(
                "nullable",
                JsonNodeFactory.instance.nullNode()
        );
        return Map.of(
                "bodyId",
                JsonNodeFactory.instance.numberNode(bodyId),
                "futurePayload",
                futurePayload
        );
    }

    private static ContextSnapshot context(
            GraphId graphId,
            String systemName,
            String bodyName
    ) {
        return new ContextSnapshot(
                graphId.commanderFid(),
                graphId.shipId(),
                "explorer_nx",
                "Caspian",
                null,
                1000L + graphId.shipId(),
                systemName,
                bodyName == null ? null : 7L,
                bodyName,
                bodyName == null ? null : "Planet",
                CommanderLocationMode.SHIP,
                null,
                null,
                null,
                null,
                bodyName == null ? null : true,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private static ShipBehaviorGraph graphFromEpisodes(
            GraphId graphId,
            List<SystemEpisode> episodes,
            GraphCursor cursor
    ) {
        ShipBehaviorGraph graph = ShipBehaviorGraph.empty(
                graphId,
                "explorer_nx",
                "Caspian",
                null
        );
        for (SystemEpisode episode : episodes) {
            for (EventOccurrence occurrence : episode.timeline()) {
                graph = graph.recordOccurrence(occurrence);
            }
            graph = graph.withEpisode(episode);
        }
        return graph.withCursor(cursor);
    }

    private static long activeCount(
            BehaviorGraphVisualizationSnapshot snapshot,
            NormalizedEventType eventType
    ) {
        return snapshot.nodes().stream()
                .filter(node -> node.eventType().equals(eventType))
                .findFirst()
                .orElseThrow()
                .activeEpisodeOccurrenceCount();
    }

    private static BehaviorGraphQueryService query(
            InMemoryBehaviorGraphStore store
    ) {
        return new BehaviorGraphQueryService(
                new BehaviorGraphService(
                        configuration(),
                        store
                )
        );
    }

    private static BehaviorGraphConfiguration configuration() {
        return new BehaviorGraphConfiguration(
                true,
                Path.of("target", "occurrence-query-test-unused"),
                Duration.ofDays(30),
                2.0,
                50,
                false
        );
    }

    private record Fixture(
            BehaviorGraphQueryService query,
            SystemEpisode completed,
            SystemEpisode active
    ) {
    }
}
