package kairon.semantics;

import com.fasterxml.jackson.databind.ObjectMapper;
import kairon.behavior.snapshot.SituationOccurrence;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One body identity, used by every layer that means "this body".
 *
 * <p>There were four records of the same two fields — a scanner reading's, the
 * canonical registry's, an occurrence's and the visit's arrival body — in four
 * packages. Each was correct; together they meant that "is this the same body?"
 * had four implementations, and only the tests of each layer separately could
 * see any of them.</p>
 */
final class BodyIdentityContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");

    /** Equality is the pair, and nothing that a second reading could change. */
    @Test
    void identityIsTheAddressAndTheBodyAndNothingElse() {
        BodyIdentity first = new BodyIdentity(23155L, 20L);
        BodyIdentity same = new BodyIdentity(23155L, 20L);
        BodyIdentity otherBody = new BodyIdentity(23155L, 21L);
        BodyIdentity otherSystem = new BodyIdentity(23156L, 20L);

        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        assertNotEquals(first, otherBody);
        assertNotEquals(
                first,
                otherSystem,
                "body four of one system is not body four of another"
        );

        TreeMap<BodyIdentity, String> ordered = new TreeMap<>();
        ordered.put(otherSystem, "c");
        ordered.put(otherBody, "b");
        ordered.put(first, "a");
        assertEquals(
                List.of("a", "b", "c"),
                List.copyOf(ordered.values()),
                "the natural order sorts by system, then by body"
        );
    }

    /**
     * The same reading yields the same identity to every layer that reads it.
     *
     * <p>The observer's novelty memory, the graph's survey policy and the
     * canonical projection all resolve a body through
     * {@link BodySurveyFacts#bodyIdentity}, so this is the value all three
     * compare. A record that names no body resolves to nothing rather than to a
     * body identified by a name.</p>
     */
    @Test
    void oneReadingResolvesToOneIdentity() {
        BodyIdentity fromScan = BodySurveyFacts.bodyIdentity(parse("""
                {"timestamp":"2026-07-30T10:00:00Z","event":"Scan",
                 "ScanType":"Detailed","SystemAddress":23155,"BodyID":20,
                 "BodyName":"Schieni 4 a","PlanetClass":"Icy body"}
                """));
        BodyIdentity fromSignals = BodySurveyFacts.bodyIdentity(parse("""
                {"timestamp":"2026-07-30T10:00:01Z","event":"FSSBodySignals",
                 "SystemAddress":23155,"BodyID":20,"BodyName":"Schieni 4 a",
                 "Signals":[{"Type":"$SAA_SignalType_Biological;","Count":1}]}
                """));

        assertEquals(
                fromScan,
                fromSignals,
                "two instruments reporting the same body agree on which it is"
        );
        assertEquals(new BodyIdentity(23155L, 20L), fromScan);
        assertEquals(
                null,
                BodySurveyFacts.bodyIdentity(parse("""
                        {"timestamp":"2026-07-30T10:00:02Z","event":"Scan",
                         "ScanType":"Detailed","BodyName":"Schieni 4 a"}
                        """)),
                "a record with no address and no id names no body"
        );
    }

    /** Each layer that holds a body identity holds this type. */
    @Test
    void everyLayerDeclaresTheSharedIdentity() throws Exception {
        assertSame(
                BodyIdentity.class,
                BodySurveyFacts.class
                        .getMethod("bodyIdentity", com.fasterxml.jackson
                                .databind.JsonNode.class)
                        .getReturnType(),
                "a scanner reading"
        );
        assertSame(
                BodyIdentity.class,
                SituationOccurrence.class.getMethod("body").getReturnType(),
                "the body an occurrence happened at"
        );
        assertSame(
                BodyIdentity.class,
                declaredField(
                        "kairon.observer.BodySurveyNoveltyGuard",
                        "visitArrivalBody"
                ).getType(),
                "the body a visit arrived at"
        );
        assertSame(
                BodyIdentity.class,
                declaredField(
                        "kairon.system.BodyProfile",
                        "identity"
                ).getType(),
                "the identity of a body in the current system"
        );
    }

    /**
     * No layer has quietly grown its own copy back.
     *
     * <p>A source scan rather than a reflection one, because the defect being
     * prevented is a <em>new</em> declaration somewhere this test does not
     * already know to look. Any record of exactly these two fields is either
     * this one or a second definition of it.</p>
     */
    @Test
    void noSecondRecordDeclaresTheSamePair() throws IOException {
        Pattern pair = Pattern.compile(
                "record\\s+(\\w+)\\s*\\(\\s*long\\s+systemAddress\\s*,"
                        + "\\s*long\\s+bodyId\\s*\\)"
        );
        List<String> declarations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            for (Path path : files
                    .filter(file -> file.toString().endsWith(".java"))
                    .toList()) {
                Matcher matcher = pair.matcher(read(path));
                while (matcher.find()) {
                    declarations.add(
                            MAIN_SOURCES.relativize(path).toString()
                                    .replace('\\', '/')
                                    + ": " + matcher.group(1)
                    );
                }
            }
        }
        assertEquals(
                List.of("kairon/semantics/BodyIdentity.java: BodyIdentity"),
                declarations,
                "a second (systemAddress, bodyId) record is the defect"
        );
    }

    // ------------------------------------------------------------- fixtures

    private static Field declaredField(String className, String fieldName)
            throws Exception {
        Field field = Class.forName(className).getDeclaredField(fieldName);
        assertTrue(
                field.getName().equals(fieldName),
                "the field exists under the name this contract names it by"
        );
        return field;
    }

    private static com.fasterxml.jackson.databind.JsonNode parse(
            String rawJson
    ) {
        try {
            return JSON.readTree(rawJson);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
