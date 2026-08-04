package kairon.projection;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the envelope factory lives, and which way the packages point.
 *
 * <p>The factory needs a {@code kairon.state} value and {@code kairon.semantics}
 * types at once. While it sat in {@code kairon.semantics} that was a package
 * cycle: {@code state} reads {@code semantics} for its own field and value
 * types, and {@code semantics} read {@code state} back for the applied
 * observation. It now sits beside its only production caller, where both reads
 * point away.</p>
 *
 * <p>Reflection can prove the class moved; only the source can prove the
 * dependency did not stay behind in some other file, so the direction is
 * checked by reading the package.</p>
 */
final class SemanticEnvelopeFactoryPlacementTest {

    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");

    /** The factory is a projection concern, beside the coordinator. */
    @Test
    void theFactoryLivesBesideItsOnlyCaller() {
        assertEquals(
                "kairon.projection",
                SemanticEnvelopeFactory.class.getPackageName()
        );
        assertEquals(
                ObservationProjectionCoordinator.class.getPackageName(),
                SemanticEnvelopeFactory.class.getPackageName(),
                "the factory and its caller share a package"
        );
    }

    /** It moved; it did not leave a forwarding class behind. */
    @Test
    void theSemanticsPackageHasNoEnvelopeFactory() {
        assertThrows(
                ClassNotFoundException.class,
                () -> Class.forName("kairon.semantics.SemanticEnvelopeFactory"),
                "a compatibility class would be a second way to build an "
                        + "envelope"
        );
    }

    /** One factory in production, and it is this one. */
    @Test
    void thereIsExactlyOneEnvelopeFactory() {
        assertEquals(
                List.of("kairon/projection/SemanticEnvelopeFactory.java"),
                mainSourcesNamed("SemanticEnvelopeFactory.java")
        );
    }

    /**
     * The semantics package does not read canonical state.
     *
     * <p>{@code AppliedObservation} still carries semantic types — that is the
     * direction the codebase already had. What must not come back is the
     * return read, whichever file makes it.</p>
     *
     * <p>Comments are stripped first: naming a state type in prose is how
     * {@code SemanticField} explains what its fields address, and explaining a
     * neighbour is not depending on it.</p>
     */
    @Test
    void semanticsDoesNotDependOnState() {
        assertTrue(
                readersOfState("projection")
                        .contains("SemanticEnvelopeFactory.java"),
                "the check is vacuous if it cannot see the read it moved"
        );
        assertEquals(
                List.of(),
                readersOfState("semantics"),
                "kairon.semantics must not read kairon.state"
        );
    }

    private static List<String> readersOfState(String packageName) {
        List<String> readers = new ArrayList<>();
        for (Path source : sourcesIn(MAIN_SOURCES.resolve(
                Path.of("kairon", packageName)
        ))) {
            if (codeOf(read(source)).contains("kairon.state")) {
                readers.add(source.getFileName().toString());
            }
        }
        return List.copyOf(readers);
    }

    // ------------------------------------------------------------- fixtures

    private static List<String> mainSourcesNamed(String fileName) {
        List<String> found = new ArrayList<>();
        for (Path source : sourcesIn(MAIN_SOURCES)) {
            if (source.getFileName().toString().equals(fileName)) {
                found.add(MAIN_SOURCES.relativize(source).toString()
                        .replace('\\', '/'));
            }
        }
        return List.copyOf(found);
    }

    private static List<Path> sourcesIn(Path root) {
        assertTrue(
                Files.isDirectory(root),
                "expected java sources at " + root.toAbsolutePath()
        );
        try (Stream<Path> tree = Files.walk(root)) {
            return tree
                    .filter(path -> path.getFileName().toString()
                            .endsWith(".java"))
                    .sorted()
                    .toList();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** The source with block and line comments removed. */
    private static String codeOf(String source) {
        return source
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)//.*$", " ");
    }

    private static String read(Path source) {
        try {
            return Files.readString(source, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
