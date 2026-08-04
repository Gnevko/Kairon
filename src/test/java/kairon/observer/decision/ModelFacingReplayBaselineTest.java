package kairon.observer.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The observable output of a fixed replay, written down so a refactor can be
 * checked against it.
 *
 * <p>Not an assertion of what the output should be. It is a recording of what
 * it <em>is</em>: every model-facing document the provider would receive, the
 * triggers each turn was built from, and the structural occurrences the graph
 * recorded for the same observations. A change that alters any of them is a
 * behaviour change, whatever the unit tests say.</p>
 *
 * <p>Everything is production except the three substitutions the harness
 * already makes — the observation source, an in-memory graph store, and a
 * provider stub that answers {@code SILENT}. No provider is contacted and no
 * speech is synthesised.</p>
 *
 * <p>Two batch shapes per fixture, because they exercise different contracts.
 * {@code per-trigger} closes a batch after every record, so each admitted
 * trigger gets a turn of its own; {@code batched} closes once at the end, so a
 * multi-trigger request is expressed. The pair covers both the single-event
 * projection and the batch-wide partition of {@code events}, {@code changes}
 * and {@code context}.</p>
 */
class ModelFacingReplayBaselineTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Where the recording lands.
     *
     * <p>Under {@code target/} so it is a build output rather than a checked-in
     * expectation: the comparison is between two runs of the same generator,
     * before and after a change.</p>
     */
    private static final Path BASELINE =
            Path.of("target", "model-facing-baseline.json");

    private static final List<String> FIXTURES = List.of(
            "biological-contexts.jsonl",
            "exobiology.jsonl",
            "ship-switch.jsonl",
            "system-change.jsonl",
            "touchdown-liftoff.jsonl"
    );

    @Test
    void writesTheModelFacingBaseline(@TempDir Path directory)
            throws IOException {
        ObjectNode baseline = JSON.createObjectNode();
        ArrayNode scenarios = baseline.putArray("scenarios");
        int index = 0;
        for (String fixture : FIXTURES) {
            List<String> records = fixtureRecords(fixture);
            assertFalse(records.isEmpty(), "empty fixture " + fixture);
            scenarios.add(replay(
                    directory.resolve("scenario-" + index++),
                    fixture,
                    "per-trigger",
                    records,
                    true
            ));
            scenarios.add(replay(
                    directory.resolve("scenario-" + index++),
                    fixture,
                    "batched",
                    records,
                    false
            ));
        }
        Files.createDirectories(BASELINE.getParent());
        Files.writeString(
                BASELINE,
                JSON.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(baseline) + "\n",
                StandardCharsets.UTF_8
        );
    }

    private static ObjectNode replay(
            Path directory,
            String fixture,
            String batching,
            List<String> records,
            boolean closeEveryRecord
    ) {
        try (SemanticPipelineHarness harness =
                     SemanticPipelineHarness.create(directory)) {
            for (String record : records) {
                harness.journal(record);
                if (closeEveryRecord) {
                    harness.closeBatch();
                }
            }
            if (!closeEveryRecord) {
                harness.closeBatch();
            }
            return describe(fixture, batching, harness.trace());
        }
    }

    /**
     * One replay, reduced to what a comment could ever depend on.
     *
     * <p>Turns in order with the exact request document, the internal trigger
     * bus sequences behind them, and the graph's episodes, occurrences, edges
     * and cursor. Internal identities that a refactor may legitimately renumber
     * — occurrence ids, episode ids, turn sequences — are deliberately absent;
     * the structural types and their order are not.</p>
     */
    private static ObjectNode describe(
            String fixture,
            String batching,
            PipelineTrace trace
    ) {
        ObjectNode scenario = JSON.createObjectNode();
        scenario.put("fixture", fixture);
        scenario.put("batching", batching);
        scenario.put("turnCount", trace.turns().size());

        ArrayNode turns = scenario.putArray("turns");
        for (PipelineTrace.TurnView turn : trace.turns()) {
            ObjectNode view = turns.addObject();
            ArrayNode triggers = view.putArray("triggerBusSequences");
            turn.triggerBusSequences().forEach(triggers::add);
            view.set("document", rounded(turn.document()));
        }

        ArrayNode episodes = scenario.putArray("episodes");
        for (PipelineTrace.EpisodeView episode : trace.episodes()) {
            ObjectNode view = episodes.addObject();
            view.put("entrySource", episode.entrySource().name());
            view.put("systemAddress", episode.systemAddress());
            view.put("active", episode.active());
            ArrayNode occurrences = view.putArray("occurrences");
            for (PipelineTrace.OccurrenceView occurrence
                    : episode.occurrences()) {
                ObjectNode recorded = occurrences.addObject();
                recorded.put("type", occurrence.eventType().value());
                recorded.put("source", occurrence.source().name());
                occurrence.sourceBusSequence().ifPresent(sequence ->
                        recorded.put("busSequence", sequence));
            }
            ArrayNode transitions = view.putArray("transitions");
            for (PipelineTrace.TransitionView transition
                    : episode.transitions()) {
                transitions.add(
                        transition.from().value()
                                + "->"
                                + transition.to().value()
                );
            }
        }

        ArrayNode edges = scenario.putArray("edges");
        trace.edges().stream()
                .map(edge -> edge.from().value() + "->" + edge.to().value()
                        + "=" + edge.rawCount())
                .sorted()
                .forEach(edges::add);

        String cursor = trace.cursor()
                .map(view -> view.eventType().value())
                .orElse(null);
        if (cursor == null) {
            scenario.putNull("cursor");
        } else {
            scenario.put("cursor", cursor);
        }
        return scenario;
    }

    /**
     * The same document with transition probabilities rounded.
     *
     * <p>A probability is a decayed weight evaluated at the moment the turn ran,
     * so its last bits move between runs of the identical replay. Six decimals
     * is far finer than anything the contract or the model can act on, and
     * coarse enough that the recording is reproducible.</p>
     */
    private static JsonNode rounded(JsonNode node) {
        if (node.isObject()) {
            ObjectNode copy = JsonNodeFactory.instance.objectNode();
            node.fields().forEachRemaining(entry -> copy.set(
                    entry.getKey(),
                    "probability".equals(entry.getKey())
                            && entry.getValue().isNumber()
                            ? new DoubleNode(BigDecimal
                                    .valueOf(entry.getValue().doubleValue())
                                    .setScale(6, RoundingMode.HALF_UP)
                                    .doubleValue())
                            : rounded(entry.getValue())
            ));
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = JsonNodeFactory.instance.arrayNode();
            node.forEach(element -> copy.add(rounded(element)));
            return copy;
        }
        return node.deepCopy();
    }

    private static List<String> fixtureRecords(String name)
            throws IOException {
        String resource = "/kairon/behavior/fixtures/" + name;
        try (InputStream input =
                     ModelFacingReplayBaselineTest.class
                             .getResourceAsStream(resource)) {
            assertNotNull(input, "missing fixture " + resource);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8))) {
                List<String> records = new ArrayList<>();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        records.add(line);
                    }
                }
                return List.copyOf(records);
            }
        }
    }
}
