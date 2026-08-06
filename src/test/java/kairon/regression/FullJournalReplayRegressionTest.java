package kairon.regression;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kairon.config.KaironConfiguration.BehaviorGraphConfiguration;
import kairon.config.KaironConfiguration.ObserverConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The whole journal, replayed after the refactor and compared with the trace of
 * the run before it.
 *
 * <p>Opt-in. Without both system properties the test is skipped, never failed:
 * neither the journal nor the reference trace belongs in the repository, and a
 * checkout that does not have them is not broken.</p>
 *
 * <pre>
 * mvnw.cmd test "-Dtest=FullJournalReplayRegressionTest" ^
 *   "-Dkairon.behavior.journal=&lt;absolute path to Journal...log&gt;" ^
 *   "-Dkairon.reference.trace=&lt;absolute path to turns.jsonl&gt;"
 * </pre>
 *
 * <p>Nothing about the journal is special-cased: the paths are inputs, the
 * observer timings are read from {@code -Dkairon.replay.quietPeriodMs} and
 * {@code -Dkairon.replay.maximumBatchAgeMs} (defaulting to the production
 * values), and every artefact is written under {@code target/}.</p>
 *
 * <h2>Why the provider's latency is reproduced</h2>
 * <p>A batch closes on wall-clock time — the quiet period since the last
 * trigger, or the maximum age since the first — and triggers that arrive while
 * a request is in flight wait for it. A substituted provider that answers
 * instantly therefore closes batches the original run would have kept open, and
 * the difference is the substitution's, not the refactor's. The reference trace
 * records what its provider actually took, and the stub takes the same, so the
 * only thing that differs from the original run is the answer. No production
 * timing, batching, selection or graph rule is touched.</p>
 */
final class FullJournalReplayRegressionTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String JOURNAL_PROPERTY = "kairon.behavior.journal";
    private static final String TRACE_PROPERTY = "kairon.reference.trace";

    private static final Path OUTPUT = Path.of("target");

    /**
     * The two things a {@code Scan} record can say, in the words it says them.
     *
     * <p>An event's kind stopped being a field of the document when every
     * statement became a sentence (ADR-0023), so a document is matched on what
     * it actually says. These are the constant sentences of
     * {@code Scan.UndiscoveredStar} and {@code Scan.BodyReading};
     * {@code ModelFacingEventDescriptionTest} is what keeps a class and its
     * sentence from drifting apart.</p>
     *
     * <p>{@link #MILESTONE_EVENT} also names the one difference that is not the
     * refactor's: three arrival-star milestones in the old trace carry body
     * facts the milestone is not about — the star's survey flags and its
     * distance of zero from the arrival point it <em>is</em>. They were removed
     * before this refactor began, so the reference predates the fix.
     * Recognised only for this event, and reported rather than suppressed.</p>
     */
    private static final String MILESTONE_EVENT =
            "A scan reported a star as not previously discovered.";

    private static final String BODY_SCAN_EVENT =
            "A discovery scan reported a star, planet or moon's properties.";

    @Test
    void modelFacingTurnsAreUnchangedAfterTheRefactor() throws Exception {
        Path journal = pathProperty(JOURNAL_PROPERTY);
        Path referenceTrace = pathProperty(TRACE_PROPERTY);
        assumeTrue(
                journal != null && referenceTrace != null,
                "opt-in: pass -D" + JOURNAL_PROPERTY + " and -D"
                        + TRACE_PROPERTY
        );
        assumeTrue(Files.isRegularFile(journal), "journal not found: " + journal);
        assumeTrue(
                Files.isRegularFile(referenceTrace),
                "reference trace not found: " + referenceTrace
        );

        List<ReferenceTurn> reference = readReference(referenceTrace);
        writeReference(reference);

        Path working = OUTPUT.resolve("full-replay-run");
        deleteRecursively(working);
        ObserverConfiguration observerConfiguration = new ObserverConfiguration(
                System.getProperty("kairon.replay.outputLanguage", "ru"),
                Long.getLong("kairon.replay.quietPeriodMs", 750L),
                Long.getLong("kairon.replay.maximumBatchAgeMs", 2000L),
                working.resolve("turns.jsonl")
        );
        BehaviorGraphConfiguration graphConfiguration =
                new BehaviorGraphConfiguration(
                        true,
                        working.resolve("behavior-graphs"),
                        Duration.parse("P30D"),
                        2.0,
                        50,
                        false
                );
        Files.createDirectories(working);

        ObjectNode current;
        int journalRecords;
        int projected;
        int network;
        int comments;
        int speech;
        try (FullJournalReplayRegression run = new FullJournalReplayRegression(
                working,
                observerConfiguration,
                graphConfiguration,
                FullJournalReplayRegression.ProviderLatency.recorded(
                        reference.stream()
                                .map(ReferenceTurn::occupancyMs)
                                .toList()
                )
        )) {
            run.replay(journal);
            current = run.normalized();
            run.writeNormalized(OUTPUT.resolve("full-replay-current.json"));
            journalRecords = run.completeJournalRecords();
            projected = run.projectedObservations();
            network = run.networkCalls();
            comments = run.deliveredComments();
            speech = run.speechInvocations();
        }

        assertEquals(0, network, "the substituted provider reaches no network");
        assertEquals(0, comments, "a SILENT decision delivers no comment");
        assertEquals(0, speech, "and speaks nothing");

        Report report = compare(reference, current.withArray("turns"));
        Files.writeString(
                OUTPUT.resolve("full-replay-diff.txt"),
                report.render(journalRecords, projected),
                StandardCharsets.UTF_8
        );
        System.out.println(report.render(journalRecords, projected));

        List<String> graphFindings = checkGraphContracts(
                current.path("graph"),
                current.withArray("turns")
        );
        Files.writeString(
                OUTPUT.resolve("full-replay-graph.txt"),
                String.join(System.lineSeparator(), graphFindings)
                        + System.lineSeparator(),
                StandardCharsets.UTF_8
        );
        graphFindings.forEach(System.out::println);

        assertEquals(
                List.of(),
                report.unexpected,
                "unexpected model-facing differences; see "
                        + OUTPUT.resolve("full-replay-diff.txt")
        );
        assertEquals(
                List.of(),
                graphFindings.stream()
                        .filter(line -> line.startsWith("VIOLATION"))
                        .toList(),
                "graph contract violations; see "
                        + OUTPUT.resolve("full-replay-graph.txt")
        );
    }

    /**
     * What the graph did, and the contracts that hold whatever it did.
     *
     * <p>No pre-refactor graph artefact exists — the turn trace records the
     * model-facing trajectory and nothing about episodes, occurrences or edges —
     * so this is not a diff. It is the set of assertions the graph and the
     * observer have to satisfy on any journal, evaluated on this one, plus the
     * counts a later run can be compared against.</p>
     */
    private static List<String> checkGraphContracts(
            JsonNode graph,
            ArrayNode turns
    ) {
        List<String> findings = new ArrayList<>();
        findings.add("GRAPH CONTRACTS");

        int episodes = graph.path("episodes").size();
        int occurrences = graph.path("occurrences").size();
        findings.add("  episodes: " + episodes);
        findings.add("  occurrences: " + occurrences);
        findings.add("  edges: " + graph.path("edges").size());
        findings.add("  cursor: " + graph.path("cursor"));

        for (JsonNode episode : graph.path("episodes")) {
            findings.add("  episode " + episode.path("systemName").asText()
                    + " entry=" + episode.path("entrySource").asText()
                    + " completion=" + episode.path("completionReason")
                    + " active=" + episode.path("active")
                    + " types=" + episode.path("occurrenceTypes"));
            int types = episode.path("occurrenceTypes").size();
            int transitions = episode.path("transitions").size();
            if (types > 0 && transitions != types - 1) {
                findings.add("VIOLATION: episode "
                        + episode.path("systemName").asText()
                        + " has " + types + " occurrences and " + transitions
                        + " transitions; a visit learns no edge into its "
                        + "first occurrence and none out of another visit");
            }
        }

        // The three arrival-star milestones, in the graph and in the requests.
        long graphMilestones = countOccurrences(
                graph,
                "SYSTEM_UNDISCOVERED_CONFIRMED"
        );
        long modelMilestones = countModelEvents(turns, MILESTONE_EVENT);
        findings.add("  SYSTEM_UNDISCOVERED_CONFIRMED occurrences: "
                + graphMilestones + ", model-facing events: "
                + modelMilestones);
        if (graphMilestones != modelMilestones) {
            findings.add("VIOLATION: the milestone occurrence and the "
                    + "model-facing event must belong to the same "
                    + "observations");
        }

        // No occurrence for any other shallow scan reading.
        List<String> scanTypes = new ArrayList<>();
        for (JsonNode occurrence : graph.path("occurrences")) {
            if ("Scan".equals(occurrence.path("originalEventName").asText())) {
                scanTypes.add(occurrence.path("type").asText());
            }
        }
        long scanned = scanTypes.stream()
                .filter("BODY_SCANNED"::equals)
                .count();
        long milestone = scanTypes.stream()
                .filter("SYSTEM_UNDISCOVERED_CONFIRMED"::equals)
                .count();
        findings.add("  occurrences minted by a Scan record: "
                + scanTypes.size() + " (" + scanned + " BODY_SCANNED, "
                + milestone + " milestone)");
        if (scanned + milestone != scanTypes.size()) {
            findings.add("VIOLATION: a Scan record minted an occurrence that "
                    + "is neither a body scan nor the arrival-star milestone");
        }

        // A body is scanned once per visit: no second BODY_SCANNED for one
        // body, which is also what stops a post-mapping re-emission counting.
        for (JsonNode episode : graph.path("episodes")) {
            long address = episode.path("systemAddress").asLong();
            List<String> seen = new ArrayList<>();
            for (JsonNode occurrence : graph.path("occurrences")) {
                if (occurrence.path("systemAddress").asLong() != address
                        || !"BODY_SCANNED".equals(
                                occurrence.path("type").asText())) {
                    continue;
                }
                String body = occurrence.path("bodyId").asText("<none>");
                if (seen.contains(body)) {
                    findings.add("VIOLATION: body " + body + " in system "
                            + address + " was scanned twice in one visit; a "
                            + "re-emitted scan after mapping is the same "
                            + "reading");
                }
                seen.add(body);
            }
        }

        long modelScans = countModelEvents(turns, BODY_SCAN_EVENT);
        findings.add("  BODY_SCANNED occurrences: " + scanned
                + ", model-facing events: " + modelScans);
        if (scanned != modelScans) {
            findings.add("VIOLATION: a body scan reached the model without an "
                    + "occurrence of its own, or the other way round");
        }

        findings.add(findings.stream().anyMatch(l -> l.startsWith("VIOLATION"))
                ? "GRAPH CONTRACTS FAILED"
                : "GRAPH CONTRACTS GREEN, HISTORICAL DIFF UNAVAILABLE");
        return List.copyOf(findings);
    }

    private static long countOccurrences(JsonNode graph, String type) {
        long count = 0;
        for (JsonNode occurrence : graph.path("occurrences")) {
            if (type.equals(occurrence.path("type").asText())) {
                count++;
            }
        }
        return count;
    }

    /**
     * How many times the model was told this, across every turn.
     *
     * <p>Counted by the sentence, which is what a request carries. Counting a
     * kind read nothing at all once events began describing themselves, and
     * every occurrence then looked like an occurrence with no event behind
     * it.</p>
     */
    private static long countModelEvents(ArrayNode turns, String sentence) {
        long count = 0;
        for (JsonNode turn : turns) {
            for (JsonNode event : turn.path("userMessage").path("events")) {
                if (sentence.equals(event.path("event").asText())) {
                    count++;
                }
            }
        }
        return count;
    }

    // ------------------------------------------------------------ comparison

    private static Report compare(
            List<ReferenceTurn> reference,
            ArrayNode current
    ) {
        Report report = new Report();
        report.referenceTurns = reference.size();
        report.currentTurns = current.size();
        report.perTrigger = comparePerTrigger(reference, current);
        int shared = Math.min(reference.size(), current.size());
        for (int index = 0; index < shared; index++) {
            ReferenceTurn expected = reference.get(index);
            JsonNode actual = current.get(index);
            List<Long> actualTriggers = new ArrayList<>();
            actual.path("triggerBusSequences")
                    .forEach(value -> actualTriggers.add(value.longValue()));
            String where = "turn " + (index + 1)
                    + " triggers=" + expected.triggerBusSequences();
            if (!expected.triggerBusSequences().equals(actualTriggers)) {
                report.unexpected.add(where
                        + "\n  /triggerBusSequences"
                        + "\n    reference: " + expected.triggerBusSequences()
                        + "\n    current:   " + actualTriggers);
                continue;
            }
            List<String> sentences =
                    sentencesOf(expected.userMessage());
            JsonNode referenceDocument = expected.userMessage();
            JsonNode currentDocument = actual.path("userMessage");
            if (List.of(MILESTONE_EVENT).equals(sentences)) {
                JsonNode strippedReference = withoutBodyFacts(
                        referenceDocument
                );
                JsonNode strippedCurrent = withoutBodyFacts(currentDocument);
                if (!strippedReference.equals(referenceDocument)) {
                    if (!strippedCurrent.equals(currentDocument)) {
                        report.unexpected.add(where
                                + "\n  the current request still carries the "
                                + "body facts that were removed from this "
                                + "milestone before the refactor"
                                + "\n    current: " + compact(currentDocument));
                        continue;
                    }
                    report.expected.add(where
                            + "\n  /changes[subject=body], /context/body"
                            + "\n    removed before the refactor, present in "
                            + "the reference: "
                            + compact(bodyFactsOf(referenceDocument))
                            + "\n    absent now, as intended");
                }
                referenceDocument = strippedReference;
                currentDocument = strippedCurrent;
            }
            diff(
                    report,
                    where,
                    "",
                    referenceDocument,
                    currentDocument
            );
        }
        for (int index = shared; index < reference.size(); index++) {
            report.unexpected.add("turn " + (index + 1)
                    + " present in the reference and missing now: triggers="
                    + reference.get(index).triggerBusSequences());
        }
        for (int index = shared; index < current.size(); index++) {
            report.unexpected.add("turn " + (index + 1)
                    + " present now and missing from the reference: triggers="
                    + current.get(index).path("triggerBusSequences"));
        }
        return report;
    }

    /**
     * The same events, keyed by the trigger they were projected from.
     *
     * <p>Independent of how the coordinator batched them. A batch boundary is
     * decided on wall-clock time, so a run whose provider is substituted can
     * group the same triggers differently; what the projection made of each
     * trigger cannot depend on that. The local event id is dropped, because it
     * is a position inside a batch rather than a property of the event.</p>
     */
    private static List<String> comparePerTrigger(
            List<ReferenceTurn> reference,
            ArrayNode current
    ) {
        Map<Long, JsonNode> expected = new LinkedHashMap<>();
        for (ReferenceTurn turn : reference) {
            index(expected, turn.triggerBusSequences(), turn.userMessage());
        }
        Map<Long, JsonNode> actual = new LinkedHashMap<>();
        for (JsonNode turn : current) {
            List<Long> triggers = new ArrayList<>();
            turn.path("triggerBusSequences")
                    .forEach(value -> triggers.add(value.longValue()));
            index(actual, triggers, turn.path("userMessage"));
        }
        List<String> differences = new ArrayList<>();
        List<Long> sequences = new ArrayList<>(expected.keySet());
        actual.keySet().forEach(sequence -> {
            if (!sequences.contains(sequence)) {
                sequences.add(sequence);
            }
        });
        for (Long sequence : sequences) {
            JsonNode left = expected.get(sequence);
            JsonNode right = actual.get(sequence);
            if (left == null) {
                differences.add("trigger " + sequence
                        + " produced an event now and none before: " + right);
            } else if (right == null) {
                differences.add("trigger " + sequence
                        + " produced an event before and none now: " + left);
            } else if (!left.equals(right)) {
                differences.add("trigger " + sequence
                        + System.lineSeparator() + "    reference: " + left
                        + System.lineSeparator() + "    current:   " + right);
            }
        }
        return List.copyOf(differences);
    }

    private static void index(
            Map<Long, JsonNode> into,
            List<Long> triggers,
            JsonNode document
    ) {
        ArrayNode events = (ArrayNode) document.path("events");
        for (int position = 0; position < triggers.size(); position++) {
            if (position >= events.size()) {
                break;
            }
            ObjectNode event = ((ObjectNode) events.get(position)).deepCopy();
            event.remove("id");
            into.put(triggers.get(position), event);
        }
    }

    /**
     * A structural diff, applied to documents the milestone exemption has
     * already been taken out of.
     *
     * <p>Everything that reaches it is unexpected, including any other
     * difference inside a milestone turn: the exemption is applied once, by
     * name, to the two places the removed facts lived, and nothing else is
     * softened here.</p>
     */
    private static void diff(
            Report report,
            String where,
            String pointer,
            JsonNode reference,
            JsonNode current
    ) {
        if (reference.equals(current)) {
            return;
        }
        if (reference.isObject() && current.isObject()) {
            List<String> names = new ArrayList<>();
            reference.fieldNames().forEachRemaining(names::add);
            current.fieldNames().forEachRemaining(name -> {
                if (!names.contains(name)) {
                    names.add(name);
                }
            });
            for (String name : names) {
                diff(
                        report,
                        where,
                        pointer + "/" + name,
                        reference.path(name),
                        current.path(name)
                );
            }
            return;
        }
        if (reference.isArray() && current.isArray()) {
            int shared = Math.min(reference.size(), current.size());
            for (int index = 0; index < shared; index++) {
                diff(
                        report,
                        where,
                        pointer + "/" + index,
                        reference.get(index),
                        current.get(index)
                );
            }
            if (reference.size() != current.size()) {
                report.unexpected.add(where + "\n  " + pointer
                        + "\n    reference has " + reference.size()
                        + " entries, current has " + current.size());
            }
            return;
        }
        report.unexpected.add(where + "\n  " + pointer
                + "\n    reference: " + compact(reference)
                + "\n    current:   " + compact(current));
    }

    /**
     * The same document without the body facts the milestone is not about.
     *
     * <p>Two places, and only two: change groups whose subject is the body, and
     * the body context group. A section left empty by the removal is dropped
     * rather than sent empty, which is what the contract says an empty section
     * means.</p>
     */
    private static JsonNode withoutBodyFacts(JsonNode document) {
        if (!document.isObject()) {
            return document;
        }
        ObjectNode copy = document.deepCopy();
        JsonNode changes = copy.path("changes");
        if (changes.isArray()) {
            ArrayNode kept = JSON.createArrayNode();
            changes.forEach(change -> {
                if (!"body".equals(change.path("subject").textValue())) {
                    kept.add(change);
                }
            });
            if (kept.isEmpty()) {
                copy.remove("changes");
            } else {
                copy.set("changes", kept);
            }
        }
        JsonNode context = copy.path("context");
        if (context.isObject() && context.has("body")) {
            ObjectNode reduced = ((ObjectNode) context).deepCopy();
            reduced.remove("body");
            if (reduced.isEmpty()) {
                copy.remove("context");
            } else {
                copy.set("context", reduced);
            }
        }
        return copy;
    }

    /** Exactly what the stripping removed, for the report. */
    private static JsonNode bodyFactsOf(JsonNode document) {
        ObjectNode removed = JSON.createObjectNode();
        ArrayNode changes = removed.putArray("changes");
        document.path("changes").forEach(change -> {
            if ("body".equals(change.path("subject").textValue())) {
                changes.add(change);
            }
        });
        if (document.path("context").has("body")) {
            removed.set("contextBody", document.path("context").path("body"));
        }
        return removed;
    }

    /**
     * What each event of a request says, in order.
     *
     * <p>An event with no sentence is skipped rather than added as null: a
     * document shape this comparison does not recognise must not stop the
     * comparison, and an unrecognised turn simply matches no special case.</p>
     */
    private static List<String> sentencesOf(JsonNode userMessage) {
        List<String> sentences = new ArrayList<>();
        userMessage.path("events").forEach(event -> {
            String sentence = event.path("event").textValue();
            if (sentence != null) {
                sentences.add(sentence);
            }
        });
        return List.copyOf(sentences);
    }

    private static String compact(JsonNode node) {
        return node.isMissingNode() ? "<absent>" : node.toString();
    }

    // ------------------------------------------------------------- reference

    private static List<ReferenceTurn> readReference(Path trace)
            throws IOException {
        List<ReferenceTurn> turns = new ArrayList<>();
        for (String line : Files.readAllLines(trace, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode record = JSON.readTree(line);
            JsonNode userMessage = record.path("modelInput").path("userMessage");
            if (userMessage.isMissingNode() || userMessage.isNull()) {
                continue;
            }
            List<Long> triggers = new ArrayList<>();
            record.path("triggerBusSequences")
                    .forEach(value -> triggers.add(value.longValue()));
            String raw = userMessage.textValue();
            turns.add(new ReferenceTurn(
                    List.copyOf(triggers),
                    JSON.readTree(raw.substring(raw.indexOf('{'))),
                    occupancyMillis(record)
            ));
        }
        return List.copyOf(turns);
    }

    /**
     * The reference, in the same shape as the current run's turns.
     *
     * <p>Turns only. The trace records what the graph produced for the model —
     * the trajectory — and nothing about the graph itself, so a reference graph
     * cannot be built from it and none is written. Reconstructing one from
     * {@code trajectory} would be a guess presented as evidence.</p>
     */
    private static void writeReference(List<ReferenceTurn> reference)
            throws IOException {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode turns = root.putArray("turns");
        for (ReferenceTurn turn : reference) {
            ObjectNode node = turns.addObject();
            ArrayNode triggers = node.putArray("triggerBusSequences");
            turn.triggerBusSequences().forEach(triggers::add);
            node.set("userMessage", turn.userMessage());
        }
        root.put(
                "graph",
                "UNAVAILABLE: the turn trace records model-facing trajectory "
                        + "only, never the graph's episodes, occurrences or "
                        + "edges. No reference graph is reconstructed."
        );
        Files.createDirectories(OUTPUT);
        Files.writeString(
                OUTPUT.resolve("full-replay-reference.json"),
                JSON.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(root) + "\n",
                StandardCharsets.UTF_8
        );
    }

    /**
     * How long the original turn held the coordinator, from its own record.
     *
     * <p>Not the provider's latency alone. A turn is not finished until its
     * comment has been delivered, and delivery waits for speech playback to
     * complete — so a turn that spoke occupied the coordinator for the request
     * <em>and</em> the synthesis and playback after it. Triggers that arrive in
     * that window queue and are batched together when it ends, which is what
     * the batch boundaries of the original run are made of.</p>
     *
     * <p>Read from the trace's own timestamps. Nothing is synthesised and
     * nothing is spoken; the substituted provider simply takes as long as the
     * original turn did, so the one thing that differs from that run is the
     * answer.</p>
     */
    private static long occupancyMillis(JsonNode record) {
        long latency = record.path("latencyMs").asLong(0L);
        String synthesisStarted =
                record.path("speechSynthesisStartedAt").asText(null);
        String playbackCompleted =
                record.path("speechPlaybackCompletedAt").asText(null);
        if (synthesisStarted == null || playbackCompleted == null) {
            return latency;
        }
        long spoken = Duration.between(
                Instant.parse(synthesisStarted),
                Instant.parse(playbackCompleted)
        ).toMillis();
        return spoken > 0 ? latency + spoken : latency;
    }

    private record ReferenceTurn(
            List<Long> triggerBusSequences,
            JsonNode userMessage,
            long occupancyMs
    ) {
    }

    private static final class Report {

        private final List<String> expected = new ArrayList<>();
        private final List<String> unexpected = new ArrayList<>();
        private List<String> perTrigger = List.of();
        private int referenceTurns;
        private int currentTurns;

        private String render(int journalRecords, int projected) {
            StringBuilder out = new StringBuilder()
                    .append("FULL JOURNAL REPLAY REGRESSION\n")
                    .append("  raw journal records:    ")
                    .append(journalRecords).append('\n')
                    .append("  projected observations: ")
                    .append(projected).append('\n')
                    .append("  reference turns:        ")
                    .append(referenceTurns).append('\n')
                    .append("  current turns:          ")
                    .append(currentTurns).append('\n')
                    .append("  per-trigger event differences: ")
                    .append(perTrigger.size()).append('\n');
            for (String entry : perTrigger) {
                out.append("    ").append(entry).append('\n');
            }
            out.append("  EXPECTED_PRE_REFACTOR_FIX: ")
                    .append(expected.size()).append('\n');
            for (String entry : expected) {
                out.append("    ").append(entry).append('\n');
            }
            out.append("  unexpected differences: ")
                    .append(unexpected.size()).append('\n');
            for (String entry : unexpected) {
                out.append("    ").append(entry).append('\n');
            }
            out.append(unexpected.isEmpty()
                    ? "\nZERO UNEXPECTED MODEL-FACING DIFF\n"
                    : "\nMODEL-FACING DIFF PRESENT\n");
            return out.toString();
        }
    }

    // --------------------------------------------------------------- support

    private static Path pathProperty(String name) {
        String value = System.getProperty(name);
        return value == null || value.isBlank()
                ? null
                : Path.of(value.trim()).toAbsolutePath().normalize();
    }

    private static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            Iterator<Path> reversed = paths.sorted((left, right) ->
                    right.toString().compareTo(left.toString())).iterator();
            while (reversed.hasNext()) {
                Files.deleteIfExists(reversed.next());
            }
        }
    }

    /** Kept so a locale-dependent format can never reach a written artefact. */
    static {
        Locale.setDefault(Locale.ROOT);
    }
}
