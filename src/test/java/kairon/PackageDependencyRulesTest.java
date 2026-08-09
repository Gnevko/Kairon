package kairon;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which package may know about which, checked against the source.
 *
 * <p>No framework and no bytecode: the rules are stated as package prefixes and
 * verified by reading the {@code import} statements of {@code src/main/java}.
 * That is enough, because a Java package cannot reach another one without
 * naming it — either in an import or in a fully qualified reference, and both
 * are checked.</p>
 *
 * <h2>Why these rules and not a general acyclicity check</h2>
 * <p>A whole-graph rule would either pass vacuously or fail on layers that are
 * deliberately intertwined. Each rule below is a specific defect that was
 * present and has been removed, so each one fails loudly if it comes back.</p>
 */
final class PackageDependencyRulesTest {

    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");

    /**
     * The forbidden directions, and why each is forbidden.
     *
     * <p>Every entry is a real cycle or inversion this repository had. The
     * reverse direction of each is not merely allowed but required — the
     * observer reads the semantic classification, the observer writes traces,
     * the observer calls the provider — which is precisely what makes the
     * forbidden direction a cycle rather than a preference.</p>
     */
    private static final List<Rule> RULES = List.of(
            new Rule(
                    "kairon.semantics",
                    "kairon.observer",
                    "model-independent meaning cannot be defined by the one "
                            + "consumer that reads it: a source role must not "
                            + "move because an LLM selection profile changed"
            ),
            new Rule(
                    "kairon.bio",
                    "kairon.observer",
                    "the organic registry is reference data: what the game "
                            + "calls an organism cannot depend on what Kairon "
                            + "decides to say about it"
            ),
            new Rule(
                    "kairon.bio",
                    "kairon.semantics",
                    "a name is not a meaning; the registry answers one "
                            + "question from one file and reads no observation"
            ),
            new Rule(
                    "kairon.bio",
                    "kairon.system",
                    "same, for the projection that records which organisms a "
                            + "body carries"
            ),
            new Rule(
                    "kairon.bio",
                    "kairon.state",
                    "same, for canonical state"
            ),
            new Rule(
                    "kairon.semantics",
                    "kairon.behavior",
                    "the behaviour graph is a subscriber-owned projection; "
                            + "what an observation means cannot depend on the "
                            + "graph's classification, or on the graph running "
                            + "at all"
            ),
            new Rule(
                    "kairon.trace",
                    "kairon.observer",
                    "the observer writes the trace, so the trace writer "
                            + "cannot know the observer; the overflow it "
                            + "records is a neutral value in kairon.turn"
            ),
            new Rule(
                    "kairon.llm",
                    "kairon.observer",
                    "the observer calls the provider and validates its "
                            + "answer, so the provider layer cannot know the "
                            + "observer; the evidence it validates against is "
                            + "a neutral value in kairon.turn"
            ),
            new Rule(
                    "kairon.turn",
                    "kairon.observer",
                    "a shared turn contract that imports one of its own "
                            + "consumers is the cycle it was extracted to "
                            + "break"
            ),
            new Rule(
                    "kairon.turn",
                    "kairon.llm",
                    "same, for the other consumer"
            ),
            new Rule(
                    "kairon.turn",
                    "kairon.trace",
                    "same, for the third"
            ),
            new Rule(
                    "kairon.semantics",
                    "kairon.system",
                    "the current-system registry is a projection; what an "
                            + "observation means cannot depend on it, or on it "
                            + "running at all"
            ),
            new Rule(
                    "kairon.system",
                    "kairon.observer",
                    "the registry cannot know the consumer that reads it, for "
                            + "the same reason the semantic layer cannot"
            ),
            new Rule(
                    "kairon.system",
                    "kairon.behavior",
                    "two peer projections that read each other are two "
                            + "projections that drift; both ask the shared "
                            + "visit policy instead"
            ),
            new Rule(
                    "kairon.behavior",
                    "kairon.system",
                    "same, in the other direction"
            ),
            new Rule(
                    "kairon.system",
                    "kairon.state",
                    "canonical body facts are to be read out of the registry "
                            + "rather than kept beside it, so the registry must "
                            + "not import the projection it will feed; what it "
                            + "needs of canonical state arrives as VisitIdentity"
            ),
            new Rule(
                    "kairon.observation",
                    "kairon.semantics",
                    "the semantic layer reads journal records, so a record "
                            + "that reads the semantic layer back is a cycle; "
                            + "a record describing itself must answer from "
                            + "its own fields, and the shared predicate lives "
                            + "on the record with kairon.semantics delegating "
                            + "to it"
            )
    );

    @Test
    void everyForbiddenPackageDirectionIsAbsent() throws IOException {
        List<String> violations = new ArrayList<>();
        for (SourceFile source : mainSources()) {
            for (Rule rule : RULES) {
                if (!source.packageName().equals(rule.from())
                        && !source.packageName()
                                .startsWith(rule.from() + ".")) {
                    continue;
                }
                for (String referenced : source.referencedPackages()) {
                    if (referenced.equals(rule.to())
                            || referenced.startsWith(rule.to() + ".")) {
                        violations.add(source.path()
                                + " references " + referenced
                                + "\n    forbidden: " + rule.from()
                                + " -> " + rule.to()
                                + "\n    because: " + rule.reason());
                    }
                }
            }
        }
        assertEquals(
                List.of(),
                violations,
                "forbidden package dependencies:\n"
                        + String.join("\n", violations)
        );
    }

    /**
     * The rule table itself is checked, not just applied.
     *
     * <p>A rule naming a package that does not exist would pass silently and
     * protect nothing. Both ends of every rule must be real source packages.</p>
     */
    @Test
    void everyRuleNamesPackagesThatExist() throws IOException {
        List<String> packages = mainSources().stream()
                .map(SourceFile::packageName)
                .distinct()
                .toList();
        for (Rule rule : RULES) {
            assertTrue(
                    packages.stream().anyMatch(name ->
                            name.equals(rule.from())
                                    || name.startsWith(rule.from() + ".")),
                    "rule source package does not exist: " + rule.from()
            );
            assertTrue(
                    packages.stream().anyMatch(name ->
                            name.equals(rule.to())
                                    || name.startsWith(rule.to() + ".")),
                    "rule target package does not exist: " + rule.to()
            );
        }
    }

    /** The reader itself works, proved on a file whose imports are known. */
    @Test
    void theSourceReaderFindsBothImportsAndQualifiedReferences()
            throws IOException {
        SourceFile catalog = mainSources().stream()
                .filter(source -> source.path().endsWith(
                        "SemanticSourceRoleCatalog.java"
                ))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "the semantic source-role catalogue is missing"
                ));

        assertEquals("kairon.semantics", catalog.packageName());
        assertTrue(
                catalog.referencedPackages()
                        .contains("kairon.observation.journal"),
                "the catalogue really does import journal event types"
        );
        assertFalse(
                catalog.referencedPackages().contains("kairon.observer"),
                "and really does not import the observer"
        );
    }

    // -------------------------------------------------------------- reading

    private static List<SourceFile> mainSources() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            return files
                    .filter(path -> path.toString().endsWith(".java"))
                    .map(PackageDependencyRulesTest::read)
                    .toList();
        }
    }

    private static SourceFile read(Path path) {
        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        Matcher declaration = Pattern
                .compile("^package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE)
                .matcher(text);
        if (!declaration.find()) {
            throw new AssertionError("no package declaration in " + path);
        }
        return new SourceFile(
                MAIN_SOURCES.relativize(path).toString().replace('\\', '/'),
                declaration.group(1),
                referencedPackages(text)
        );
    }

    /**
     * Every {@code kairon} package this file names, however it names it.
     *
     * <p>Imports and fully qualified references both count. A star import
     * contributes its package; a qualified type contributes the prefix up to
     * the first capitalised segment, which is where the package ends and the
     * type begins. Javadoc {@literal @link} targets count too — a link is
     * resolved against the classpath, so it is a real reference.</p>
     *
     * <p>Inline {@literal @code} and {@literal @literal} spans are removed
     * first. Their contents are typeset text that resolves to nothing, and a
     * package named in prose to explain <em>why</em> it must not be imported is
     * the opposite of importing it.</p>
     *
     * <p>String literals go too. A Java package cannot be reached through one —
     * nothing here uses reflection — and a value that merely looks like a
     * package name is not a dependency: the graph's schema version
     * {@code "kairon.system-episode/v3"} would otherwise read as the behaviour
     * graph importing the current-system registry.</p>
     */
    private static List<String> referencedPackages(String text) {
        List<String> referenced = new ArrayList<>();
        Matcher matcher = Pattern
                .compile("\\bkairon(?:\\.[A-Za-z_$][\\w$]*)+")
                .matcher(withoutStringLiterals(withoutTypesetProse(text)));
        while (matcher.find()) {
            String packageName = packagePrefixOf(matcher.group());
            if (packageName != null && !referenced.contains(packageName)) {
                referenced.add(packageName);
            }
        }
        return List.copyOf(referenced);
    }

    /** The source with inline literal-text javadoc tags removed. */
    private static String withoutTypesetProse(String text) {
        return Pattern
                .compile("\\{@(?:code|literal)\\s[^{}]*\\}")
                .matcher(text)
                .replaceAll(" ");
    }

    /** The source with text blocks and string literals removed. */
    private static String withoutStringLiterals(String text) {
        String withoutBlocks = Pattern
                .compile("\"\"\"[\\s\\S]*?\"\"\"")
                .matcher(text)
                .replaceAll(" ");
        return Pattern
                .compile("\"(?:\\\\.|[^\"\\\\])*\"")
                .matcher(withoutBlocks)
                .replaceAll(" ");
    }

    private static String packagePrefixOf(String qualifiedName) {
        String[] segments = qualifiedName.split("\\.");
        StringBuilder packageName = new StringBuilder(segments[0]);
        for (int index = 1; index < segments.length; index++) {
            String segment = segments[index];
            if (!segment.isEmpty()
                    && Character.isUpperCase(segment.charAt(0))) {
                break;
            }
            if ("*".equals(segment)) {
                break;
            }
            packageName.append('.').append(segment);
        }
        String result = packageName.toString();
        return result.equals("kairon")
                || !result.equals(result.toLowerCase(Locale.ROOT))
                ? null
                : result;
    }

    /** One forbidden direction between two package trees. */
    private record Rule(String from, String to, String reason) {

        private Rule {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** One main source file, reduced to what a dependency rule reads. */
    private record SourceFile(
            String path,
            String packageName,
            List<String> referencedPackages
    ) {

        private SourceFile {
            referencedPackages = List.copyOf(referencedPackages);
        }
    }
}
