package kairon.behavior;

import kairon.behavior.bus.BehaviorGraphObservationProcessor;
import kairon.behavior.classify.EventSignificancePolicy;
import kairon.behavior.context.TransitionContextKeyFactory;
import kairon.behavior.event.BehaviorGraphListener;
import kairon.behavior.export.BehaviorGraphExporter;
import kairon.behavior.graph.BehaviorGraphQueryService;
import kairon.behavior.graph.BehaviorGraphService;
import kairon.behavior.model.GraphId;
import kairon.behavior.model.ShipBehaviorGraph;
import kairon.behavior.model.SystemEpisode;
import kairon.behavior.normalize.BehaviorEventNormalizer;
import kairon.behavior.persistence.JsonBehaviorGraphStore;
import kairon.config.KaironConfiguration.BehaviorGraphConfiguration;
import kairon.observation.ObservationDraft;
import kairon.observation.ObservationDraft.ObservationCaptureMode;
import kairon.observation.ObservationDraft.ObservationSource;
import kairon.observation.bus.InProcessObservationBus;
import kairon.observation.bus.ObservationBus.PublishReceipt;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalLineParser;
import kairon.observation.journal.JournalLineParser.CompleteJournalRecord;
import kairon.observation.journal.JournalLineParser.JournalParseFailure;
import kairon.observation.journal.JournalLineParser.ParsedJournalRecord;
import kairon.observation.journal.JournalObservationAdapter;
import kairon.observation.journal.JournalObservationAdapter.JournalSourcePosition;
import kairon.observation.journal.JournalReplaySource;
import kairon.state.CurrentGameStateProjector;
import kairon.observation.source.ObservationSourceSignal;
import kairon.observation.source.ObservationSourceSignal.ObservationSourceSignalType;
import kairon.projection.ObservationProjectionCoordinator;
import kairon.projection.ObservationProjectionSubscriber;
import kairon.projection.ProjectedObservationBus;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in, rapid, behavior-only replay for a locally supplied full journal.
 *
 * <p>Run with:
 * {@code -Dkairon.behavior.journal=<journal-path>}. The optional
 * {@code -Dkairon.behavior.output=<target-subdirectory>} selects the retained
 * output directory. Both deterministic runs intentionally bypass journal-time
 * sleeps while retaining the production parser, adapter, bus, subscriber,
 * graph service, Jackson store, and replay-exhaustion signal.</p>
 */
final class BehaviorGraphManualReplayTest {

    private static final String JOURNAL_PROPERTY =
            "kairon.behavior.journal";
    private static final String OUTPUT_PROPERTY =
            "kairon.behavior.output";
    private static final Duration HALF_LIFE = Duration.ofDays(30);

    @Test
    void suppliedJournalProducesDeterministicGraphAndEpisodeExports()
            throws Exception {
        String configuredJournal = System.getProperty(JOURNAL_PROPERTY);
        Assumptions.assumeTrue(
                configuredJournal != null && !configuredJournal.isBlank(),
                () -> "set -D" + JOURNAL_PROPERTY
                        + "=<path> to run the manual behavior replay"
        );

        Path journal = Path.of(configuredJournal)
                .toAbsolutePath()
                .normalize();
        assertTrue(Files.isRegularFile(journal), "journal must be a file");
        assertTrue(Files.isReadable(journal), "journal must be readable");

        Path outputRoot = checkedOutputRoot();
        recreateDirectory(outputRoot);

        ReplayResult first = replay(journal, outputRoot.resolve("run-a"));
        ReplayResult second = replay(journal, outputRoot.resolve("run-b"));

        assertFalse(first.exports().isEmpty());
        assertEquals(first.exports(), second.exports());
        assertEquals(first.statistics(), second.statistics());
        assertTrue(first.statistics().graphCount() > 0);
        assertTrue(first.statistics().episodeCount() > 0);

        String report = """
                journal=%s
                completeRecords=%d
                graphs=%d
                episodes=%d
                nodes=%d
                edges=%d
                occurrences=%d
                occurrenceTransitions=%d
                firstExports=%s
                secondExports=%s
                """.formatted(
                journal,
                first.statistics().completeRecordCount(),
                first.statistics().graphCount(),
                first.statistics().episodeCount(),
                first.statistics().nodeCount(),
                first.statistics().edgeCount(),
                first.statistics().occurrenceCount(),
                first.statistics().transitionCount(),
                outputRoot.resolve("run-a").resolve("exports"),
                outputRoot.resolve("run-b").resolve("exports")
        );
        Files.writeString(
                outputRoot.resolve("summary.txt"),
                report,
                StandardCharsets.UTF_8
        );
        System.out.print("BEHAVIOR_MANUAL_REPLAY\n" + report);
    }

    private static ReplayResult replay(Path journal, Path runDirectory)
            throws Exception {
        Path storeDirectory = runDirectory.resolve("store");
        Path exportDirectory = runDirectory.resolve("exports");
        Files.createDirectories(exportDirectory);

        Set<GraphId> graphIds = new ConcurrentSkipListSet<>();
        BehaviorGraphListener graphCollector =
                event -> graphIds.add(event.graphId());
        BehaviorGraphConfiguration configuration =
                new BehaviorGraphConfiguration(
                        true,
                        storeDirectory,
                        HALF_LIFE,
                        2.0,
                        50,
                        false
                );

        JsonBehaviorGraphStore store =
                new JsonBehaviorGraphStore(storeDirectory);
        BehaviorGraphService graphService = new BehaviorGraphService(
                configuration,
                store,
                new EventSignificancePolicy(),
                new BehaviorEventNormalizer(),
                new TransitionContextKeyFactory(),
                graphCollector
        );
        InProcessObservationBus bus = new InProcessObservationBus();
        ProjectedObservationBus projectedBus =
                new ProjectedObservationBus();
        ObservationProjectionCoordinator projectionCoordinator =
                new ObservationProjectionCoordinator(
                        new CurrentGameStateProjector(),
                        Optional.of(
                                new BehaviorGraphObservationProcessor(
                                        graphService
                                )
                        ),
                        Optional.of(
                                new BehaviorGraphQueryService(graphService)
                        ),
                        projectedBus
                );
        ObservationProjectionSubscriber.Subscription subscription =
                new ObservationProjectionSubscriber(
                        projectionCoordinator
                ).subscribeTo(bus);

        PublishedJournal published;
        try {
            published = publishJournalRapidly(journal, bus);
            publishReplayExhausted(journal, published, bus);
            projectionCoordinator.awaitIdle()
                    .toCompletableFuture()
                    .get(30, TimeUnit.SECONDS);
        } finally {
            bus.drainAndClose()
                    .toCompletableFuture()
                    .get(30, TimeUnit.SECONDS);
            projectionCoordinator.shutdown()
                    .toCompletableFuture()
                    .get(30, TimeUnit.SECONDS);
            subscription.close();
            store.close();
        }

        assertFalse(graphIds.isEmpty(), "journal produced no ship graph");
        return export(
                storeDirectory,
                exportDirectory,
                graphIds,
                published.completeRecordCount(),
                published.evaluationTime()
        );
    }

    private static PublishedJournal publishJournalRapidly(
            Path journal,
            InProcessObservationBus bus
    ) throws Exception {
        byte[] fileBytes = Files.readAllBytes(journal);
        String basename = journal.getFileName().toString();
        ObservationSource source = sourceFor(basename);
        JournalLineParser parser = new JournalLineParser();
        JournalObservationAdapter adapter =
                new JournalObservationAdapter(source);

        int completeRecords = 0;
        Instant evaluationTime = Instant.EPOCH;
        int lineStart = 0;
        for (int index = 0; index <= fileBytes.length; index++) {
            boolean endOfLine = index < fileBytes.length
                    && fileBytes[index] == '\n';
            boolean endOfFile = index == fileBytes.length;
            if (!endOfLine && !endOfFile) {
                continue;
            }

            int contentEnd = index;
            if (contentEnd > lineStart
                    && fileBytes[contentEnd - 1] == '\r') {
                contentEnd--;
            }
            if (contentEnd > lineStart) {
                byte[] line = Arrays.copyOfRange(
                        fileBytes,
                        lineStart,
                        contentEnd
                );
                JournalLineParser.JournalParseResult parseResult =
                        parser.parse(new CompleteJournalRecord(
                                basename,
                                lineStart,
                                line
                        ));
                if (parseResult instanceof JournalParseFailure failure) {
                    throw new AssertionError(
                            "journal parse failure at offset "
                                    + failure.zeroBasedSourceByteOffset()
                                    + ": " + failure.kind()
                    );
                }
                ParsedJournalRecord parsed = assertInstanceOf(
                        ParsedJournalRecord.class,
                        parseResult
                );
                Instant observedAt = parsed.optionalJournalTimestamp()
                        .orElse(Instant.EPOCH);
                ObservationDraft<JournalEventObservation> draft =
                        adapter.adapt(
                                parsed,
                                ObservationCaptureMode.REPLAY,
                                observedAt
                        );
                PublishReceipt receipt = bus.publish(draft)
                        .toCompletableFuture()
                        .get(30, TimeUnit.SECONDS);
                assertEquals(
                        List.of(
                                ObservationProjectionSubscriber.SUBSCRIBER_ID
                        ),
                        receipt.matchedSubscriberIds()
                );
                assertTrue(receipt.failedSubscriberIds().isEmpty());
                adapter.commit(draft.observationId());
                completeRecords++;
                if (observedAt.isAfter(evaluationTime)) {
                    evaluationTime = observedAt;
                }
            }
            lineStart = index + 1;
        }
        return new PublishedJournal(
                source,
                fileBytes.length,
                completeRecords,
                evaluationTime
        );
    }

    private static void publishReplayExhausted(
            Path journal,
            PublishedJournal published,
            InProcessObservationBus bus
    ) throws Exception {
        String basename = journal.getFileName().toString();
        ObservationDraft<ObservationSourceSignal> exhausted =
                new ObservationDraft<>(
                        JournalReplaySource.replayExhaustedObservationId(
                                published.source().sourceInstanceId(),
                                basename,
                                published.fileSize()
                        ),
                        published.source(),
                        new JournalSourcePosition(
                                basename,
                                published.fileSize()
                        ),
                        Optional.of(published.evaluationTime()),
                        published.evaluationTime(),
                        ObservationCaptureMode.REPLAY,
                        ObservationSourceSignal.SCHEMA_VERSION,
                        new ObservationSourceSignal(
                                ObservationSourceSignalType
                                        .REPLAY_SOURCE_EXHAUSTED
                        )
                );
        PublishReceipt receipt = bus.publish(exhausted)
                .toCompletableFuture()
                .get(30, TimeUnit.SECONDS);
        assertEquals(
                List.of(
                        ObservationProjectionSubscriber.SUBSCRIBER_ID
                ),
                receipt.matchedSubscriberIds()
        );
        assertTrue(receipt.failedSubscriberIds().isEmpty());
    }

    private static ReplayResult export(
            Path storeDirectory,
            Path exportDirectory,
            Set<GraphId> graphIds,
            int completeRecordCount,
            Instant evaluationTime
    ) throws IOException {
        BehaviorGraphExporter exporter = new BehaviorGraphExporter();
        Map<String, String> exports = new TreeMap<>();
        int episodeCount = 0;
        int nodeCount = 0;
        int edgeCount = 0;
        int occurrenceCount = 0;
        int transitionCount = 0;

        try (JsonBehaviorGraphStore stored =
                     new JsonBehaviorGraphStore(storeDirectory)) {
            for (GraphId graphId : graphIds) {
                ShipBehaviorGraph graph =
                        stored.loadGraph(graphId).orElseThrow();
                String graphName = safeGraphName(graphId);
                String graphJson = exporter.exportGraph(
                        graph,
                        evaluationTime,
                        HALF_LIFE
                );
                putExport(
                        exports,
                        exportDirectory,
                        "graphs/" + graphName + ".json",
                        graphJson
                );
                putExport(
                        exports,
                        exportDirectory,
                        "graphs/" + graphName + ".dot",
                        exporter.exportDot(
                                graph,
                                evaluationTime,
                                HALF_LIFE
                        )
                );
                nodeCount += graph.nodes().size();
                edgeCount += graph.edges().size();

                for (SystemEpisode episode : stored.listEpisodes(graphId)) {
                    String episodePath = "episodes/"
                            + graphName
                            + "/"
                            + safeFileName(episode.id().value())
                            + ".json";
                    putExport(
                            exports,
                            exportDirectory,
                            episodePath,
                            exporter.exportEpisode(episode)
                    );
                    episodeCount++;
                    occurrenceCount += episode.timeline().size();
                    transitionCount +=
                            episode.occurrenceTransitions().size();
                }
            }
        }

        ReplayStatistics statistics = new ReplayStatistics(
                completeRecordCount,
                graphIds.size(),
                episodeCount,
                nodeCount,
                edgeCount,
                occurrenceCount,
                transitionCount
        );
        return new ReplayResult(Map.copyOf(exports), statistics);
    }

    private static void putExport(
            Map<String, String> exports,
            Path exportDirectory,
            String relativePath,
            String contents
    ) throws IOException {
        Path output = exportDirectory.resolve(relativePath);
        Files.createDirectories(output.getParent());
        Files.writeString(output, contents, StandardCharsets.UTF_8);
        exports.put(relativePath, contents);
    }

    private static ObservationSource sourceFor(String basename) {
        return new ObservationSource(
                "elite-journal",
                "behavior-manual-replay:" + basename
        );
    }

    private static Path checkedOutputRoot() {
        Path target = Path.of("target").toAbsolutePath().normalize();
        String configured = System.getProperty(
                OUTPUT_PROPERTY,
                "target/behavior-manual-replay"
        );
        Path output = Path.of(configured).toAbsolutePath().normalize();
        if (output.equals(target) || !output.startsWith(target)) {
            throw new IllegalArgumentException(
                    OUTPUT_PROPERTY
                            + " must name a dedicated directory below target"
            );
        }
        return output;
    }

    private static void recreateDirectory(Path directory) throws IOException {
        if (Files.exists(directory)) {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder())
                        .toList()) {
                    Files.delete(path);
                }
            }
        }
        Files.createDirectories(directory);
    }

    private static String safeGraphName(GraphId graphId) {
        return safeFileName(
                graphId.commanderFid() + "-" + graphId.shipId()
        );
    }

    private static String safeFileName(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private record PublishedJournal(
            ObservationSource source,
            long fileSize,
            int completeRecordCount,
            Instant evaluationTime
    ) {
    }

    private record ReplayResult(
            Map<String, String> exports,
            ReplayStatistics statistics
    ) {
    }

    private record ReplayStatistics(
            int completeRecordCount,
            int graphCount,
            int episodeCount,
            int nodeCount,
            int edgeCount,
            int occurrenceCount,
            int transitionCount
    ) {
    }
}
