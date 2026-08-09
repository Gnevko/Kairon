package kairon.bio;

import kairon.bio.JsonOrganicRegistryLoader.OrganicRegistryException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The registry loads completely or not at all.
 *
 * <p>Every rejection here is the same argument: a registry that loaded most of
 * itself names most of the organisms, and the ones it dropped fall back silently
 * to whatever the journal said. Nothing downstream can tell that apart from an
 * organism the file never claimed to know, so the only place the difference can
 * be noticed is here (ADR-0028).</p>
 */
final class OrganicRegistryLoaderTest {

    @Test
    void readsEveryTaxonLevelAndAnswersInEachLanguage(@TempDir Path directory)
            throws IOException {
        OrganicRegistry registry = load(directory, VALID);

        assertEquals(3, registry.size(), "one genus, one species, one variant");
        assertEquals(
                Optional.of("Бактерии"),
                registry.name("$Codex_Ent_Bacterial_Genus_Name;", "ru")
        );
        assertEquals(
                Optional.of("Bacterium Vesicula - Green"),
                registry.name("$Codex_Ent_Bacterial_05_A_Name;", "en")
        );
    }

    /**
     * Knowing an organism and having a word for it are two different things.
     *
     * <p>Both answer empty, on purpose. The caller's fallback is the same for
     * either, and a second answer would invite a second fallback.</p>
     */
    @Test
    void answersEmptyForAnUnknownOrganismAndForAnUnknownLanguage(
            @TempDir Path directory
    ) throws IOException {
        OrganicRegistry registry = load(directory, VALID);

        assertTrue(registry.name("$Codex_Ent_Nothing_Name;", "en").isEmpty());
        assertTrue(
                registry.name("$Codex_Ent_Bacterial_05_Name;", "de").isEmpty(),
                "the species is here; German is not"
        );
        assertFalse(registry.knows("$Codex_Ent_Nothing_Name;"));
        assertTrue(registry.knows("$Codex_Ent_Bacterial_05_Name;"));
    }

    /**
     * An entry with no names at all is kept.
     *
     * <p>That is an organism the file says exists and has no word for, which is
     * what a genus known only from a journal reading looks like. It is not the
     * same as a name that is present and blank, which is a generator fault.</p>
     */
    @Test
    void keepsAnEntryThatHasNoNames(@TempDir Path directory) throws IOException {
        OrganicRegistry registry = load(directory, """
                {"schema": "kairon-organic-registry-v1",
                 "genera": [{"id": "$Codex_Ent_Bacterial_Genus_Name;"}],
                 "species": [], "variants": []}
                """);

        assertTrue(registry.knows("$Codex_Ent_Bacterial_Genus_Name;"));
        assertTrue(
                registry.name("$Codex_Ent_Bacterial_Genus_Name;", "en").isEmpty()
        );
    }

    @Test
    void refusesADuplicateIdentifier(@TempDir Path directory) {
        assertMessageContains(
                directory,
                """
                {"schema": "kairon-organic-registry-v1",
                 "genera": [{"id": "$Codex_Ent_Bacterial_Genus_Name;"},
                            {"id": "$Codex_Ent_Bacterial_Genus_Name;"}],
                 "species": [], "variants": []}
                """,
                "is declared twice"
        );
    }

    @Test
    void refusesASpeciesWhoseGenusIsNotDeclared(@TempDir Path directory) {
        assertMessageContains(
                directory,
                """
                {"schema": "kairon-organic-registry-v1",
                 "genera": [],
                 "species": [{"id": "$Codex_Ent_Bacterial_05_Name;",
                              "genus": "$Codex_Ent_Bacterial_Genus_Name;"}],
                 "variants": []}
                """,
                "which the file does not declare"
        );
    }

    @Test
    void refusesAnEntryWithNoIdentifier(@TempDir Path directory) {
        assertMessageContains(
                directory,
                """
                {"schema": "kairon-organic-registry-v1",
                 "genera": [{"names": {"en": "Bacterium"}}],
                 "species": [], "variants": []}
                """,
                "genera[0].id is missing"
        );
    }

    @Test
    void refusesABlankName(@TempDir Path directory) {
        assertMessageContains(
                directory,
                """
                {"schema": "kairon-organic-registry-v1",
                 "genera": [{"id": "$Codex_Ent_Bacterial_Genus_Name;",
                             "names": {"ru": "  "}}],
                 "species": [], "variants": []}
                """,
                "genera[0].names.ru is blank"
        );
    }

    @Test
    void refusesAnotherSchema(@TempDir Path directory) {
        assertMessageContains(
                directory,
                """
                {"schema": "kairon-organic-registry-v2",
                 "genera": [], "species": [], "variants": []}
                """,
                "expected kairon-organic-registry-v1"
        );
    }

    @Test
    void refusesAMissingSection(@TempDir Path directory) {
        assertMessageContains(
                directory,
                """
                {"schema": "kairon-organic-registry-v1", "genera": []}
                """,
                "has no species array"
        );
    }

    @Test
    void refusesAFileThatIsNotThere(@TempDir Path directory) {
        OrganicRegistryException refusal = assertThrows(
                OrganicRegistryException.class,
                () -> JsonOrganicRegistryLoader.load(directory.resolve("absent.json"))
        );
        assertTrue(
                refusal.getMessage().contains("could not be read"),
                refusal.getMessage()
        );
    }

    /**
     * The file this repository ships loads, and says what it claims to.
     *
     * <p>It is generated, committed and read by the application at startup, so
     * the one thing worth asserting about it here is that all three of those
     * still line up. A file that stopped loading would take the application
     * down on the next run and nothing else would have noticed.</p>
     */
    @Test
    void theShippedRegistryLoads() {
        Path shipped = Path.of("config", "organic-registry.json");
        assertTrue(
                Files.isRegularFile(shipped),
                "the generated registry is committed: " + shipped.toAbsolutePath()
        );

        OrganicRegistry registry = JsonOrganicRegistryLoader.load(shipped);

        assertTrue(registry.size() > 900, "1 003 organisms as generated");
        assertEquals(
                Optional.of("Bacterium"),
                registry.name("$Codex_Ent_Bacterial_Genus_Name;", "en"),
                "the name the symbol stem got wrong"
        );
        assertEquals(
                Optional.of("Frutexa"),
                registry.name("$Codex_Ent_Shrubs_Genus_Name;", "en"),
                "and the one it got furthest wrong"
        );
        assertEquals(
                Optional.of("Бактерии"),
                registry.name("$Codex_Ent_Bacterial_Genus_Name;", "ru")
        );
    }

    @Test
    void theEmptyRegistryKnowsNothing() {
        assertTrue(OrganicRegistry.EMPTY.isEmpty());
        assertEquals(0, OrganicRegistry.EMPTY.size());
        assertTrue(
                OrganicRegistry.EMPTY
                        .name("$Codex_Ent_Bacterial_Genus_Name;", "en")
                        .isEmpty()
        );
    }

    // ----------------------------------------------------------------- setup

    private static OrganicRegistry load(Path directory, String document)
            throws IOException {
        Path file = directory.resolve("organic-registry.json");
        Files.writeString(file, document, StandardCharsets.UTF_8);
        return JsonOrganicRegistryLoader.load(file);
    }

    private static void assertMessageContains(
            Path directory,
            String document,
            String expected
    ) {
        OrganicRegistryException refusal = assertThrows(
                OrganicRegistryException.class,
                () -> load(directory, document)
        );
        assertTrue(
                refusal.getMessage().contains(expected),
                "expected the refusal to say what was wrong: "
                        + refusal.getMessage()
        );
    }

    private static final String VALID = """
            {
              "schema": "kairon-organic-registry-v1",
              "meta": {"generatedOn": "2026-08-08"},
              "genera": [
                {"id": "$Codex_Ent_Bacterial_Genus_Name;",
                 "names": {"en": "Bacterium", "ru": "Бактерии"},
                 "colonyDistanceM": 500,
                 "multipleSpecies": false}
              ],
              "species": [
                {"id": "$Codex_Ent_Bacterial_05_Name;",
                 "genus": "$Codex_Ent_Bacterial_Genus_Name;",
                 "names": {"en": "Bacterium Vesicula", "ru": "Бактерия Vesicula"},
                 "valueCr": 1000000}
              ],
              "variants": [
                {"id": "$Codex_Ent_Bacterial_05_A_Name;",
                 "species": "$Codex_Ent_Bacterial_05_Name;",
                 "names": {"en": "Bacterium Vesicula - Green"}}
              ]
            }
            """;
}
