package kairon.ui.swing.behaviorgraph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kairon.behavior.graph.ActiveEpisodeNodeOccurrencesSnapshot;
import kairon.behavior.graph.EventOccurrenceDetailsSnapshot;
import kairon.behavior.graph.EventOccurrenceSummary;
import kairon.behavior.model.ContextSnapshot;
import kairon.behavior.model.EventOccurrenceId;
import kairon.behavior.model.GraphId;
import kairon.behavior.model.SystemEpisodeId;
import kairon.behavior.normalize.NormalizedEventType;
import kairon.state.CommanderLocationMode;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BehaviorGraphOccurrenceInspectorTest {

    private static final GraphId GRAPH_ID = new GraphId("F100", 9L);
    private static final SystemEpisodeId EPISODE_ID =
            new SystemEpisodeId("episode-active");
    private static final Instant TIME =
            Instant.parse("2026-07-26T19:20:09Z");

    @Test
    void inspectorUsesRequiredEnglishStatesAndReadOnlyColumns()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            BehaviorGraphOccurrenceInspector inspector =
                    new BehaviorGraphOccurrenceInspector();
            inspector.verticalSplit().setDividerLocation(210);

            assertEquals(
                    "Select a graph node to view its occurrences.",
                    inspector.listMessageText()
            );
            inspector.showSelectedNodePending(
                    "Touchdown",
                    0L,
                    false
            );
            assertEquals(
                    "No active system episode.",
                    inspector.listMessageText()
            );

            inspector.applyOccurrences(
                    snapshot(
                            NormalizedEventType.DISEMBARK,
                            "Disembark",
                            List.of()
                    ),
                    Optional.empty(),
                    true
            );
            assertEquals(
                    "Disembark \u2014 0 occurrences",
                    inspector.headerText()
            );
            assertEquals(
                    "No occurrences of this event in the active system.",
                    inspector.listMessageText()
            );

            ActiveEpisodeNodeOccurrencesSnapshot one = snapshot(
                    NormalizedEventType.TOUCHDOWN,
                    "Touchdown",
                    List.of(summary("occurrence-1", 4L))
            );
            Optional<EventOccurrenceId> selected =
                    inspector.applyOccurrences(
                            one,
                            Optional.empty(),
                            true
                    );
            assertEquals(
                    "Touchdown \u2014 1 occurrence",
                    inspector.headerText()
            );
            assertEquals(
                    Optional.of(new EventOccurrenceId("occurrence-1")),
                    selected
            );
            assertEquals(3, inspector.tableModel().getColumnCount());
            assertEquals("#", inspector.tableModel().getColumnName(0));
            assertEquals("Time", inspector.tableModel().getColumnName(1));
            assertEquals(
                    "Source Event",
                    inspector.tableModel().getColumnName(2)
            );
            assertEquals(4L, inspector.tableModel().getValueAt(0, 0));
            assertEquals("19:20:09",
                    inspector.tableModel().getValueAt(0, 1));
            assertEquals("Touchdown",
                    inspector.tableModel().getValueAt(0, 2));
            assertFalse(inspector.tableModel().isCellEditable(0, 0));

            AtomicReference<Optional<EventOccurrenceId>> selection =
                    new AtomicReference<>(Optional.empty());
            inspector.setSelectionListener(selection::set);
            inspector.occurrenceTable().clearSelection();
            assertTrue(selection.get().isEmpty());
            inspector.showNoOccurrenceSelected();
            assertEquals(
                    "Select an occurrence to view details.",
                    inspector.detailsText()
            );

            inspector.showLoadError();
            assertTrue(inspector.loadErrorVisible());
            assertEquals(
                    210,
                    inspector.verticalSplit().getDividerLocation()
            );
        });
    }

    @Test
    void detailsFormattingIsDeterministicAndHandlesNestedAndNullValues()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            BehaviorGraphOccurrenceInspector inspector =
                    new BehaviorGraphOccurrenceInspector();
            inspector.showDetails(details());
            String text = inspector.detailsText();

            assertTrue(text.startsWith("Identity\n\n"));
            assertTrue(text.contains(
                    "Event type: SAA_SIGNALS_FOUND"
            ));
            assertTrue(text.contains("Source event: SAASignalsFound"));
            assertTrue(text.contains(
                    "Timestamp: 2026-07-26T19:20:09Z"
            ));
            assertTrue(text.contains("Episode sequence: 14"));
            assertTrue(text.contains("Source sequence: 184"));
            assertTrue(text.contains("Occurrence ID: occurrence-details"));
            assertTrue(text.contains("Episode ID: episode-active"));
            assertTrue(text.contains("Graph ID: F100/9"));
            assertTrue(text.contains("nullable: null"));
            assertTrue(text.contains("nested: {\n  alpha: 1"));
            assertTrue(text.contains("zeta: 2"));
            assertTrue(text.contains("systemName: Test System"));
            assertTrue(text.contains("bodyName: Test Body"));

            int nested = text.indexOf("\nnested:");
            int nullable = text.indexOf("\nnullable:");
            int zeta = text.lastIndexOf("\nzeta:");
            assertTrue(nested < nullable);
            assertTrue(nullable < zeta);

            EventOccurrenceDetailsFormatter formatter =
                    new EventOccurrenceDetailsFormatter();
            assertEquals(formatter.format(details()),
                    formatter.format(details()));
        });
    }

    private static ActiveEpisodeNodeOccurrencesSnapshot snapshot(
            NormalizedEventType type,
            String displayName,
            List<EventOccurrenceSummary> rows
    ) {
        return new ActiveEpisodeNodeOccurrencesSnapshot(
                GRAPH_ID,
                Optional.of(EPISODE_ID),
                type,
                displayName,
                7L,
                5L,
                rows
        );
    }

    private static EventOccurrenceSummary summary(
            String occurrenceId,
            long episodeSequence
    ) {
        return new EventOccurrenceSummary(
                new EventOccurrenceId(occurrenceId),
                TIME,
                episodeSequence,
                184L,
                "Touchdown"
        );
    }

    private static EventOccurrenceDetailsSnapshot details() {
        ObjectNode nested = JsonNodeFactory.instance.objectNode();
        nested.put("zeta", 2);
        nested.put("alpha", 1);
        Map<String, JsonNode> attributes = new LinkedHashMap<>();
        attributes.put("zeta", JsonNodeFactory.instance.numberNode(2));
        attributes.put("nested", nested);
        attributes.put("nullable", JsonNodeFactory.instance.nullNode());
        return new EventOccurrenceDetailsSnapshot(
                GRAPH_ID,
                EPISODE_ID,
                new EventOccurrenceId("occurrence-details"),
                NormalizedEventType.SAA_SIGNALS_FOUND,
                "SAASignalsFound",
                TIME,
                14L,
                184L,
                "Journal.test.log",
                attributes,
                context()
        );
    }

    private static ContextSnapshot context() {
        return new ContextSnapshot(
                "F100",
                9L,
                "explorer_nx",
                "Caspian",
                null,
                1001L,
                "Test System",
                57L,
                "Test Body",
                "Planet",
                CommanderLocationMode.SHIP,
                null,
                null,
                4,
                null,
                true,
                null,
                null,
                null,
                null,
                true,
                false
        );
    }
}
