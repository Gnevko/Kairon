package kairon.observation.journal;

import com.fasterxml.jackson.databind.ObjectMapper;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.event.exploration.Scan;
import kairon.observer.LlmJournalEventSelection;
import kairon.semantics.BodySurveyFacts;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every event the model can be shown says, in its own words, what it reports.
 *
 * <p>The contract is on the event, so this is where it is checked: each record
 * is built from a minimal journal line and asked. There is no table to compare
 * against — a table is what this design removed — so what can be asserted is
 * the shape of the answer and, for the events a comment actually rests on, the
 * answer itself.</p>
 *
 * <p>The forbidden vocabulary is a guard and not a proof. It catches a sentence
 * that drifted into judgement; it cannot tell whether a sentence is true. The
 * named events below are the proof, and they are stated explicitly.</p>
 */
final class ModelFacingDescriptionContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Long enough for a clause with a qualifier, short enough to be read. */
    private static final int MAX_CHARACTERS = 100;

    /**
     * Words that judge, predict, explain a motive or tell the Commander what
     * to do. None of them can appear in a statement of what an event reports.
     */
    private static final List<String> FORBIDDEN = List.of(
            "rare", "notable", "important", "significant", "valuable",
            "dangerous", "impressive", "unusual", "remarkable", "worth",
            "should", "must ", "recommend", "advis", "suggest",
            "probably", "likely", "expect", "next step", "in order to",
            "wants", "intend", "trying to", "hoping"
    );

    @Test
    void everyModelEligibleEventDescribesItself() {
        List<String> missing = new ArrayList<>();
        described().forEach((eventType, description) -> {
            if (description == null || description.isBlank()) {
                missing.add(eventType);
            }
        });
        assertEquals(List.of(), missing, "an event with nothing to say");
        assertEquals(
                LlmJournalEventSelection.NEW_EVENT_TYPE_COUNT,
                described().size(),
                "every model-eligible type was asked"
        );
    }

    /**
     * A description is a sentence, not a token.
     *
     * <p>The whole point is that the model stops receiving Kairon's spelling of
     * an event, so a description that is a kind, a class name or an enum
     * constant would defeat the change while passing every other check.</p>
     */
    @Test
    void noDescriptionIsAnInternalName() {
        described().forEach((eventType, description) -> {
            assertNotEquals(eventType, description, eventType);
            assertFalse(
                    description.matches("[A-Z][A-Z0-9_]*"),
                    eventType + " answered with a symbol: " + description
            );
            assertFalse(
                    description.contains("_"),
                    eventType + " answered with an internal spelling: "
                            + description
            );
            assertTrue(
                    description.contains(" "),
                    eventType + " answered with one word: " + description
            );
        });
    }

    @Test
    void everyDescriptionIsOneShortSentence() {
        described().forEach((eventType, description) -> {
            assertTrue(
                    description.length() <= MAX_CHARACTERS,
                    eventType + " needs " + description.length()
                            + " characters: " + description
            );
            assertTrue(
                    Character.isUpperCase(description.charAt(0)),
                    eventType + " does not begin a sentence: " + description
            );
            assertTrue(
                    description.endsWith("."),
                    eventType + " does not end a sentence: " + description
            );
            assertEquals(
                    1,
                    description.chars().filter(c -> c == '.').count(),
                    eventType + " is more than one sentence: " + description
            );
            assertEquals(
                    description.strip(),
                    description,
                    eventType + " is not stripped"
            );
        });
    }

    @Test
    void noDescriptionJudgesPredictsOrPrescribes() {
        described().forEach((eventType, description) -> {
            String lower = description.toLowerCase(Locale.ROOT);
            for (String forbidden : FORBIDDEN) {
                assertFalse(
                        lower.contains(forbidden),
                        eventType + " says \"" + forbidden + "\": "
                                + description
                );
            }
        });
    }

    /**
     * Two events a comment rests on, stated outright.
     *
     * <p>An approach is not a landing and a supercruise exit is not a landing;
     * both were read as one in a measured run, and a guard vocabulary cannot
     * catch that. Only naming the sentence can.</p>
     */
    @Test
    void theEventsThatWereMisreadNowSayWhatTheyAre() {
        Map<String, String> described = described();
        assertEquals(
                "A ship landed on the surface of a planet or moon.",
                described.get("Touchdown")
        );
        assertEquals(
                "A ship in supercruise came within a body's orbital-cruise "
                        + "zone.",
                described.get("ApproachBody")
        );
        assertEquals(
                "A ship dropped out of supercruise into normal space.",
                described.get("SupercruiseExit")
        );
        for (String notALanding : List.of("ApproachBody", "SupercruiseExit")) {
            assertFalse(
                    described.get(notALanding)
                            .toLowerCase(Locale.ROOT)
                            .contains("land"),
                    notALanding + " reports no landing"
            );
        }
    }

    /**
     * Each scanner speaks for itself rather than through a shared name.
     *
     * <p>The two records reach the model under one kind, and the kind is not
     * what the model is shown any more — so neither borrows the other's
     * sentence, and neither reconstructs one from the kind they share.</p>
     */
    @Test
    void eachScannerDescribesItsOwnInstrument() {
        Map<String, String> described = described();
        assertEquals(
                "A full spectrum system scan reported signal data for "
                        + "a body.",
                described.get("FSSBodySignals")
        );
        assertEquals(
                "A surface area analysis scan reported signal data for "
                        + "a planet or rings.",
                described.get("SAASignalsFound")
        );
        assertNotEquals(
                described.get("FSSBodySignals"),
                described.get("SAASignalsFound")
        );
    }

    /**
     * One wire event, two assertions, two classes.
     *
     * <p>{@code Scan} reports two different things and the parser decides which
     * from the record's own fields, so each phrase is a constant on its own
     * class rather than a branch inside one description. Nothing is
     * interpolated, and the arrival-star one says the star had not been
     * discovered rather than anything about its class.</p>
     */
    @Test
    void aScanIsTwoClassesWithTwoFixedPhrases() {
        String detailed = description("""
                {"timestamp":"2026-07-30T10:00:00Z","event":"Scan",
                 "ScanType":"Detailed","SystemAddress":1,"BodyID":4,
                 "BodyName":"Schieni 4","PlanetClass":"Icy body",
                 "WasDiscovered":false}
                """);
        String arrival = description("""
                {"timestamp":"2026-07-30T10:00:00Z","event":"Scan",
                 "ScanType":"AutoScan","SystemAddress":1,"BodyID":0,
                 "BodyName":"Schieni","StarType":"K","WasDiscovered":false}
                """);
        assertEquals(
                "A discovery scan reported a star, planet or moon's properties.",
                detailed
        );
        assertEquals(
                "A scan reported a star as not previously discovered.",
                arrival
        );
        assertNotEquals(detailed, arrival);
        assertFalse(
                arrival.contains("K"),
                "the milestone reports no spectral class: " + arrival
        );
        assertFalse(
                arrival.toLowerCase(Locale.ROOT).contains("arrival"),
                "the record cannot say which body a visit arrived at: "
                        + arrival
        );
    }

    /**
     * Both readings of a Scan answer to one underlying predicate.
     *
     * <p>{@code Scan.reportsUndiscoveredStar} is what the parser dispatches on,
     * and the semantic layer asks the record rather than keeping a second copy —
     * the layers that track visits hold a stored record rather than the typed
     * observation, so they still need the predicate. Two implementations of it
     * would drift, and the drift would be a milestone reported as an ordinary
     * scan or the reverse, which is exactly what the observer's admission and
     * the graph's episode policy are keyed on.</p>
     */
    @Test
    void bothScanReadingsFollowOneSharedPredicate() {
        List<String> readings = List.of("""
                {"timestamp":"2026-07-30T10:00:00Z","event":"Scan",
                 "ScanType":"AutoScan","SystemAddress":1,"BodyID":0,
                 "BodyName":"Schieni","StarType":"K","WasDiscovered":false}
                """, """
                {"timestamp":"2026-07-30T10:00:00Z","event":"Scan",
                 "ScanType":"Detailed","SystemAddress":1,"BodyID":0,
                 "BodyName":"Schieni","StarType":"K","WasDiscovered":false}
                """, """
                {"timestamp":"2026-07-30T10:00:00Z","event":"Scan",
                 "ScanType":"AutoScan","SystemAddress":1,"BodyID":0,
                 "BodyName":"Schieni","StarType":"K","WasDiscovered":true}
                """, """
                {"timestamp":"2026-07-30T10:00:00Z","event":"Scan",
                 "ScanType":"AutoScan","SystemAddress":1,"BodyID":0,
                 "BodyName":"Schieni","StarType":"K"}
                """, """
                {"timestamp":"2026-07-30T10:00:00Z","event":"Scan",
                 "ScanType":"AutoScan","BodyName":"Schieni","StarType":"K",
                 "WasDiscovered":false}
                """, """
                {"timestamp":"2026-07-30T10:00:00Z","event":"Scan",
                 "ScanType":"AutoScan","SystemAddress":1,"BodyID":4,
                 "BodyName":"Schieni 4","PlanetClass":"Icy body",
                 "WasDiscovered":false}
                """);
        for (String reading : readings) {
            RawJournalData raw = raw(reading);
            boolean record = Scan.reportsUndiscoveredStar(raw.parsedJsonObject());
            assertEquals(
                    record,
                    BodySurveyFacts.undiscoveredStarReading(
                            raw.parsedJsonObject()),
                    "the semantic layer answers from the record: " + reading
            );
            assertEquals(
                    record
                            ? "A scan reported a star as not previously "
                                    + "discovered."
                            : "A discovery scan reported a star, planet or "
                                    + "moon's properties.",
                    description(reading),
                    "the sentence follows the same predicate: " + reading
            );
        }
    }

    /**
     * Two classes, told apart by a flag the request never sends.
     *
     * <p>{@code IsPreview} separates the game showing what a conversion would
     * do from the conversion itself. The semantic adapter does not emit the
     * flag, so a single sentence asserting a conversion would report a preview
     * as a completed one. The parser dispatches on it; both phrases are
     * constants, and neither reads a value out of the record.</p>
     */
    @Test
    void aLegacyConversionIsTwoClassesWithTwoFixedPhrases() {
        String previewed = description("""
                {"timestamp":"2026-07-30T10:00:00Z",
                 "event":"EngineerLegacyConvert","IsPreview":true,
                 "Module":"int_hyperdrive_size5_class5",
                 "Module_Localised":"Frame Shift Drive",
                 "BlueprintName":"FSD_LongRange","Level":4,"Quality":0.7}
                """);
        String converted = description("""
                {"timestamp":"2026-07-30T10:00:01Z",
                 "event":"EngineerLegacyConvert","IsPreview":false,
                 "Module":"int_powerplant_size3_class5",
                 "Module_Localised":"Power Plant",
                 "BlueprintName":"PowerPlant_Armoured","Level":3,
                 "Quality":0.2}
                """);

        assertEquals(
                "A conversion of a legacy engineered module was previewed.",
                previewed
        );
        assertEquals(
                "A legacy engineered module was converted to the current "
                        + "format.",
                converted
        );
        assertNotEquals(previewed, converted);
        for (String phrase : List.of(previewed, converted)) {
            for (String value : List.of(
                    "Frame Shift Drive", "Power Plant", "FSD_LongRange",
                    "PowerPlant_Armoured", "int_hyperdrive", "0.7", "4")) {
                assertFalse(
                        phrase.contains(value),
                        "the record's own values stay in the fields: " + phrase
                );
            }
        }
    }

    /**
     * A description is a property of the type, not of the record.
     *
     * <p>Two different landings say the same thing; only their fields differ.
     * This is what keeps the sentence a definition rather than a rendering, and
     * it is checked on the events that carry the most values.</p>
     */
    @Test
    void twoRecordsOfOneTypeSayExactlyTheSameThing() {
        String first = description("""
                {"timestamp":"2026-07-30T10:00:00Z","event":"Touchdown",
                 "PlayerControlled":true,"StarSystem":"Schieni",
                 "Body":"Schieni 4 a","Latitude":18.7,"Longitude":-35.0}
                """);
        String second = description("""
                {"timestamp":"2026-07-30T11:00:00Z","event":"Touchdown",
                 "PlayerControlled":false,"StarSystem":"Sol","Body":"Mars"}
                """);
        assertEquals(first, second);
        assertFalse(first.contains("Schieni"));
        assertFalse(first.contains("18.7"));
    }

    // ------------------------------------------------------------- fixtures

    /** Every model-eligible discriminator with what its record says it is. */
    private static Map<String, String> described() {
        Map<String, String> described = new LinkedHashMap<>();
        for (Class<? extends JournalEventObservation> eventType
                : LlmJournalEventSelection.TARGET_NEW_ELIGIBLE) {
            String discriminator = discriminatorOf(eventType);
            described.put(discriminator, description("""
                    {"timestamp":"2026-07-30T10:00:00Z","event":"%s"}
                    """.formatted(discriminator)));
        }
        return described;
    }

    private static String description(String rawJson) {
        JournalEventObservation event = JournalEventCatalog.create(raw(rawJson));
        assertTrue(
                event instanceof LlmPresentableJournalEvent,
                event.getClass().getName() + " cannot describe itself"
        );
        return ((LlmPresentableJournalEvent) event).modelFacingDescription();
    }

    private static RawJournalData raw(String rawJson) {
        try {
            String compact = JSON.readTree(rawJson).toString();
            return new RawJournalData(
                    compact,
                    JSON.readTree(compact),
                    Optional.ofNullable(
                            JSON.readTree(compact).path("event").textValue()
                    ),
                    Optional.of(Instant.parse("2026-07-30T10:00:00Z"))
            );
        } catch (Exception failure) {
            throw new IllegalStateException(rawJson, failure);
        }
    }

    private static String discriminatorOf(
            Class<? extends JournalEventObservation> eventType
    ) {
        try {
            Field discriminator = eventType.getField("EVENT_TYPE");
            return (String) discriminator.get(null);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(eventType.getName(), failure);
        }
    }
}
