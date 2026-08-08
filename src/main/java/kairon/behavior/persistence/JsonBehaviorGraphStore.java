package kairon.behavior.persistence;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import kairon.behavior.model.EventOccurrence;
import kairon.behavior.model.EventOccurrenceId;
import kairon.behavior.model.GraphCursor;
import kairon.behavior.model.GraphId;
import kairon.behavior.model.ShipBehaviorGraph;
import kairon.behavior.model.SystemEpisode;
import kairon.behavior.model.SystemEpisodeId;
import kairon.state.LastKnownShip;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Prototype Jackson store using one directory per commander and concrete ship.
 *
 * <p>Each JSON document is independently replaced through a forced temporary
 * file and an atomic move when supported by the file system.</p>
 */
public final class JsonBehaviorGraphStore implements BehaviorGraphStore {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(JsonBehaviorGraphStore.class);
    private static final String GRAPH_FILE = "graph.json";
    private static final String ACTIVE_EPISODE_FILE = "active-episode.json";
    private static final String LAST_KNOWN_SHIP_FILE = "last-known-ship.json";
    private static final String EPISODES_DIRECTORY = "episodes";
    private static final Comparator<SystemEpisode> EPISODE_ORDER =
            Comparator.comparing(SystemEpisode::startedAt)
                    .thenComparing(SystemEpisode::id);

    private final Path storageDirectory;
    private final ObjectMapper mapper;
    private final ObjectWriter writer;

    public JsonBehaviorGraphStore(Path storageDirectory) {
        this(storageDirectory, deterministicMapper());
    }

    JsonBehaviorGraphStore(
            Path storageDirectory,
            ObjectMapper mapper
    ) {
        this.storageDirectory = Objects.requireNonNull(
                storageDirectory,
                "storageDirectory"
        ).toAbsolutePath().normalize();
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.writer = stableWriter(mapper);
    }

    public Path storageDirectory() {
        return storageDirectory;
    }

    @Override
    public synchronized Optional<ShipBehaviorGraph> loadGraph(GraphId graphId) {
        Objects.requireNonNull(graphId, "graphId");
        Path path = graphFile(graphId);
        Optional<ShipBehaviorGraph> loaded = readIfPresent(
                path,
                ShipBehaviorGraph.class
        );
        loaded.ifPresent(graph -> {
            if (!graph.graphId().equals(graphId)) {
                throw new StoreException(
                        "graph identity does not match storage path: " + path
                );
            }
        });
        return loaded;
    }

    @Override
    public synchronized void saveGraph(ShipBehaviorGraph graph) {
        Objects.requireNonNull(graph, "graph");
        writeAtomically(graphFile(graph.graphId()), graph);
    }

    /**
     * One file at the storage root, not one per Commander.
     *
     * <p>The question is "which ship was the graph last active on", and the
     * caller asking it has no Commander yet either — it is asked while the
     * runtime is being wired, before a journal record has been read. A file per
     * Commander would have to be picked between, which needs a timestamp on
     * each and turns a lookup into a comparison. One file, last write wins, and
     * the record names its own Commander so a session that opens under a
     * different one is recognised and ignored rather than mis-seeded.</p>
     */
    @Override
    public synchronized Optional<LastKnownShip> lastKnownShip() {
        return readIfPresent(lastKnownShipFile(), LastKnownShip.class);
    }

    @Override
    public synchronized void recordLastKnownShip(LastKnownShip ship) {
        Objects.requireNonNull(ship, "ship");
        writeAtomically(lastKnownShipFile(), ship);
    }

    @Override
    public synchronized Optional<SystemEpisode> loadEpisode(
            SystemEpisodeId episodeId
    ) {
        Objects.requireNonNull(episodeId, "episodeId");
        SystemEpisode found = null;
        for (Path path : allEpisodeFiles()) {
            SystemEpisode candidate = read(path, SystemEpisode.class);
            if (!candidate.id().equals(episodeId)) {
                continue;
            }
            found = preferCompleted(found, candidate, episodeId);
        }
        return Optional.ofNullable(found);
    }

    @Override
    public synchronized Optional<SystemEpisode> loadActiveEpisode(
            GraphId graphId
    ) {
        Objects.requireNonNull(graphId, "graphId");
        Path activePath = activeEpisodeFile(graphId);
        Optional<SystemEpisode> active = readIfPresent(
                activePath,
                SystemEpisode.class
        );
        active.ifPresent(episode -> {
            requireEpisodeGraph(episode, graphId, activePath);
            if (!episode.active()) {
                throw new StoreException(
                        "active episode path contains a completed episode: "
                                + activePath
                );
            }
        });
        return active;
    }

    @Override
    public synchronized void saveEpisode(SystemEpisode episode) {
        Objects.requireNonNull(episode, "episode");
        if (episode.active()) {
            Path activePath = activeEpisodeFile(episode.graphId());
            Optional<SystemEpisode> existing = readIfPresent(
                    activePath,
                    SystemEpisode.class
            );
            if (existing.isPresent()
                    && !existing.orElseThrow().id().equals(episode.id())) {
                throw new StoreException(
                        "another active episode already exists for graph "
                                + episode.graphId().canonicalValue()
                );
            }
            writeAtomically(activePath, episode);
            return;
        }

        writeAtomically(completedEpisodeFile(episode), episode);
        Path activePath = activeEpisodeFile(episode.graphId());
        Optional<SystemEpisode> active = readIfPresent(
                activePath,
                SystemEpisode.class
        );
        if (active.isPresent()
                && active.orElseThrow().id().equals(episode.id())) {
            deleteIfExists(activePath, "remove completed active episode");
        }
    }

    @Override
    public synchronized List<SystemEpisode> listEpisodes(GraphId graphId) {
        Objects.requireNonNull(graphId, "graphId");
        TreeMap<SystemEpisodeId, SystemEpisode> byId = new TreeMap<>();

        Path completedDirectory = episodesDirectory(graphId);
        if (Files.isDirectory(
                completedDirectory,
                LinkOption.NOFOLLOW_LINKS
        )) {
            for (Path path : jsonFiles(completedDirectory)) {
                SystemEpisode episode = read(path, SystemEpisode.class);
                requireEpisodeGraph(episode, graphId, path);
                byId.merge(
                        episode.id(),
                        episode,
                        (left, right) -> preferCompleted(
                                left,
                                right,
                                left.id()
                        )
                );
            }
        }

        Path activePath = activeEpisodeFile(graphId);
        readIfPresent(activePath, SystemEpisode.class).ifPresent(episode -> {
            requireEpisodeGraph(episode, graphId, activePath);
            byId.merge(
                    episode.id(),
                    episode,
                    (left, right) -> preferCompleted(
                            left,
                            right,
                            left.id()
                    )
            );
        });

        return byId.values().stream()
                .sorted(EPISODE_ORDER)
                .toList();
    }

    @Override
    public synchronized Optional<GraphCursor> loadActiveCursor(
            GraphId graphId
    ) {
        return loadGraph(graphId).map(ShipBehaviorGraph::cursor);
    }

    @Override
    public synchronized void saveActiveCursor(GraphCursor cursor) {
        Objects.requireNonNull(cursor, "cursor");
        ShipBehaviorGraph graph = loadGraph(cursor.graphId())
                .orElseThrow(() -> new StoreException(
                        "cannot save cursor for unknown graph: "
                                + cursor.graphId().canonicalValue()
                ));
        saveGraph(graph.withCursor(cursor));
    }

    @Override
    public synchronized boolean graphExists(GraphId graphId) {
        Objects.requireNonNull(graphId, "graphId");
        return Files.isRegularFile(
                graphFile(graphId),
                LinkOption.NOFOLLOW_LINKS
        );
    }

    @Override
    public synchronized void deleteGraph(GraphId graphId) {
        Objects.requireNonNull(graphId, "graphId");
        Path graphDirectory = graphDirectory(graphId);
        if (!Files.exists(graphDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (!graphDirectory.startsWith(storageDirectory)
                || graphDirectory.equals(storageDirectory)) {
            throw new StoreException(
                    "refusing to delete outside behavior graph storage"
            );
        }
        try (Stream<Path> paths = Files.walk(graphDirectory)) {
            List<Path> deletionOrder = paths
                    .sorted(Comparator.reverseOrder())
                    .toList();
            for (Path path : deletionOrder) {
                Files.deleteIfExists(path);
            }
        } catch (IOException failure) {
            throw ioFailure("delete graph", graphDirectory, failure);
        }
    }

    @Override
    public synchronized Optional<EventOccurrence> findOccurrence(
            EventOccurrenceId occurrenceId
    ) {
        Objects.requireNonNull(occurrenceId, "occurrenceId");
        EventOccurrence found = null;
        for (Path path : allEpisodeFiles()) {
            SystemEpisode episode = read(path, SystemEpisode.class);
            for (EventOccurrence occurrence : episode.timeline()) {
                if (!occurrence.id().equals(occurrenceId)) {
                    continue;
                }
                if (found != null && !found.equals(occurrence)) {
                    throw new StoreException(
                            "occurrence ID collision: " + occurrenceId
                    );
                }
                found = occurrence;
            }
        }
        return Optional.ofNullable(found);
    }

    private Path graphDirectory(GraphId graphId) {
        String commander = safeSegment(
                graphId.commanderFid(),
                "commanderFid"
        );
        return storageDirectory
                .resolve(commander)
                .resolve(Long.toString(graphId.shipId()))
                .normalize();
    }

    private Path graphFile(GraphId graphId) {
        return graphDirectory(graphId).resolve(GRAPH_FILE);
    }

    private Path lastKnownShipFile() {
        return storageDirectory.resolve(LAST_KNOWN_SHIP_FILE).normalize();
    }

    private Path activeEpisodeFile(GraphId graphId) {
        return graphDirectory(graphId).resolve(ACTIVE_EPISODE_FILE);
    }

    private Path episodesDirectory(GraphId graphId) {
        return graphDirectory(graphId).resolve(EPISODES_DIRECTORY);
    }

    private Path completedEpisodeFile(SystemEpisode episode) {
        return episodesDirectory(episode.graphId()).resolve(
                safeSegment(episode.id().value(), "episodeId") + ".json"
        );
    }

    private List<Path> allEpisodeFiles() {
        if (!Files.isDirectory(storageDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.find(
                storageDirectory,
                4,
                (path, attributes) -> attributes.isRegularFile()
                        && (path.getFileName().toString()
                                .equals(ACTIVE_EPISODE_FILE)
                        || path.getFileName().toString().endsWith(".json")
                        && path.getParent() != null
                        && path.getParent().getFileName().toString()
                                .equals(EPISODES_DIRECTORY))
        )) {
            return paths.sorted().toList();
        } catch (IOException failure) {
            throw ioFailure(
                    "scan episode files",
                    storageDirectory,
                    failure
            );
        }
    }

    private List<Path> jsonFiles(Path directory) {
        try (Stream<Path> paths = Files.list(directory)) {
            return paths
                    .filter(path -> Files.isRegularFile(
                            path,
                            LinkOption.NOFOLLOW_LINKS
                    ))
                    .filter(path -> path.getFileName().toString()
                            .endsWith(".json"))
                    .sorted()
                    .toList();
        } catch (IOException failure) {
            throw ioFailure("list episode files", directory, failure);
        }
    }

    private <T> Optional<T> readIfPresent(Path path, Class<T> type) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new StoreException(
                    "behavior graph path is not a regular file: " + path
            );
        }
        return Optional.of(read(path, type));
    }

    private <T> T read(Path path, Class<T> type) {
        try {
            return mapper.readValue(path.toFile(), type);
        } catch (IOException | RuntimeException failure) {
            throw new StoreException(
                    "cannot read behavior graph JSON: " + path,
                    failure
            );
        }
    }

    private void writeAtomically(Path target, Object value) {
        Path parent = target.getParent();
        try {
            Files.createDirectories(parent);
        } catch (IOException failure) {
            throw ioFailure("create graph directory", parent, failure);
        }

        final byte[] json;
        try {
            json = writer.writeValueAsBytes(value);
        } catch (IOException | RuntimeException failure) {
            throw new StoreException(
                    "cannot serialize behavior graph JSON: " + target,
                    failure
            );
        }

        Path temporary = null;
        try {
            temporary = Files.createTempFile(
                    parent,
                    "." + target.getFileName() + ".",
                    ".tmp"
            );
            try (FileChannel channel = FileChannel.open(
                    temporary,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
            )) {
                ByteBuffer buffer = ByteBuffer.wrap(json);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            moveReplacing(temporary, target);
            temporary = null;
        } catch (IOException failure) {
            throw ioFailure("write behavior graph JSON", target, failure);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException cleanupFailure) {
                    LOGGER.warn(
                            "BEHAVIOR_GRAPH_TEMP_DELETE_FAILED path={}",
                            temporary,
                            cleanupFailure
                    );
                }
            }
        }
    }

    private static void moveReplacing(Path temporary, Path target)
            throws IOException {
        try {
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    private static void deleteIfExists(Path path, String operation) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException failure) {
            throw ioFailure(operation, path, failure);
        }
    }

    private static SystemEpisode preferCompleted(
            SystemEpisode left,
            SystemEpisode right,
            SystemEpisodeId episodeId
    ) {
        if (left == null) {
            return right;
        }
        if (!left.graphId().equals(right.graphId())) {
            throw new StoreException(
                    "episode ID belongs to multiple graphs: " + episodeId
            );
        }
        if (!left.active() && right.active()) {
            return left;
        }
        if (left.active() && !right.active()) {
            return right;
        }
        if (!left.equals(right)) {
            throw new StoreException(
                    "conflicting episode documents: " + episodeId
            );
        }
        return left;
    }

    private static void requireEpisodeGraph(
            SystemEpisode episode,
            GraphId graphId,
            Path path
    ) {
        if (!episode.graphId().equals(graphId)) {
            throw new StoreException(
                    "episode graph identity does not match storage path: "
                            + path
            );
        }
    }

    private static String safeSegment(String value, String name) {
        Objects.requireNonNull(value, name);
        StringBuilder encoded = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character >= 'a' && character <= 'z'
                    || character >= 'A' && character <= 'Z'
                    || character >= '0' && character <= '9'
                    || character == '-'
                    || character == '_'
                    || character == '.') {
                encoded.append(character);
            } else {
                encoded.append('%')
                        .append(String.format(
                                Locale.ROOT,
                                "%04X",
                                (int) character
                        ));
            }
        }
        if (encoded.isEmpty()
                || encoded.toString().equals(".")
                || encoded.toString().equals("..")) {
            throw new StoreException(name + " cannot form a safe path segment");
        }
        return encoded.toString();
    }

    private static ObjectMapper deterministicMapper() {
        JsonFactory factory = JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        return JsonMapper.builder(factory)
                .addModule(new JavaTimeModule())
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build();
    }

    private static ObjectWriter stableWriter(ObjectMapper mapper) {
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        printer.indentObjectsWith(indenter);
        printer.indentArraysWith(indenter);
        return mapper.writer(printer);
    }

    private static StoreException ioFailure(
            String operation,
            Path path,
            IOException cause
    ) {
        return new StoreException(
                operation + " failed: " + path,
                cause
        );
    }
}
