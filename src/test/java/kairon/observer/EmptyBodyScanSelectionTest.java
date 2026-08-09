package kairon.observer;

import com.fasterxml.jackson.databind.ObjectMapper;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.event.exploration.CodexEntry;
import kairon.observation.journal.event.exploration.FSSBodySignals;
import kairon.observation.journal.event.exploration.Scan;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A scan of a body with nothing on it opens no turn.
 *
 * <p>The Commander asked for this after the live session of 2026-08-08, where
 * bare body readings produced five silences and six comments — and both of that
 * evening's fabrications. A body reading names a rock and says nothing about
 * what is on it; the signals records say that, and four detailed scans in five
 * are of a body no signals record ever mentioned (175 of 836 in these
 * journals).</p>
 *
 * <p>The one exception is the Commander's own: looking at an empty rock is still
 * worth a word when it entered something new into the codex.</p>
 *
 * <p>Stateful, so it is the guard's decision rather than
 * {@link LlmJournalEventSelection#admitsAsTrigger}'s — the {@code Scan} record
 * carries no signal of its own, and asking it would be asking a record a
 * question it cannot answer.</p>
 */
final class EmptyBodyScanSelectionTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A rocky moon with nothing reported on it. */
    private static final String SCAN = """
            {"timestamp":"2026-08-08T20:42:03Z","event":"Scan",
             "ScanType":"Detailed","BodyName":"Ogaicy XF-C c27-156 3",
             "BodyID":12,"StarSystem":"Ogaicy XF-C c27-156",
             "SystemAddress":42951384474346,"PlanetClass":"Icy body",
             "Landable":false,"TerraformState":"","Atmosphere":"","Volcanism":"",
             "SurfaceGravity":0.812345,"SurfaceTemperature":54.2,
             "WasDiscovered":false,"WasMapped":false}
            """;

    /** The same body, with the system scanner reporting something on it. */
    private static final String SIGNALS = """
            {"timestamp":"2026-08-08T20:42:02Z","event":"FSSBodySignals",
             "BodyName":"Ogaicy XF-C c27-156 3","BodyID":12,
             "SystemAddress":42951384474346,
             "Signals":[{"Type":"$SAA_SignalType_Biological;","Count":3}]}
            """;

    /** Copied from the journal: the codex never names the body it came from. */
    private static final String NEW_CODEX_ENTRY = """
            {"timestamp":"2026-08-08T20:42:02Z","event":"CodexEntry",
             "EntryID":1200801,
             "Name":"$Codex_Ent_Standard_Sudarsky_Class_IV_Name;",
             "SubCategory":"$Codex_SubCategory_Gas_Giants;",
             "Category":"$Codex_Category_StellarBodies;",
             "Region":"$Codex_RegionName_4;",
             "System":"Ogaicy XF-C c27-156","SystemAddress":42951384474346,
             "BodyID":0,"IsNewEntry":true}
            """;

    @Test
    void aBodyNothingWasReportedOnOpensNoTurn() {
        BodySurveyNoveltyGuard guard = new BodySurveyNoveltyGuard();

        assertFalse(
                guard.admits(scan(SCAN)),
                "a rock with nothing on it is not news"
        );
    }

    @Test
    void aBodyTheScannerFoundSomethingOnStillOpensOne() {
        BodySurveyNoveltyGuard guard = new BodySurveyNoveltyGuard();

        assertTrue(guard.admits(signals()), "the finding itself is news");
        assertTrue(
                guard.admits(scan(SCAN)),
                "and so is what the body it is on turns out to be"
        );
    }

    /**
     * The signals record always arrives first, so the order is not a hope.
     *
     * <p>Measured over 836 detailed scans in these journals: in all 175 cases
     * where the body had a signals record, that record came before the scan —
     * 172 of them immediately before.</p>
     */
    @Test
    void theOrderTheJournalWritesThemInIsTheOrderThisNeeds() {
        BodySurveyNoveltyGuard guard = new BodySurveyNoveltyGuard();
        guard.admits(signals());

        assertTrue(guard.admits(scan(SCAN)));
    }

    /**
     * A reading declined for being empty is not remembered as told.
     *
     * <p>It was declined for what the body is, not for having been said, so a
     * later reading of the same body — once something has been found on it —
     * still opens its own turn. Recording it would silence the one reading that
     * matters.</p>
     */
    @Test
    void anEmptyReadingIsDeclinedWithoutBeingRemembered() {
        BodySurveyNoveltyGuard guard = new BodySurveyNoveltyGuard();

        assertFalse(guard.admits(scan(SCAN)));
        assertTrue(guard.admits(signals()));
        assertTrue(
                guard.admits(scan(SCAN)),
                "the same reading, now that the body is known to bear something"
        );
    }

    /** The Commander's exception: it entered something new in the codex. */
    @Test
    void aReadingThatEnteredSomethingNewInTheCodexIsStillSent() {
        BodySurveyNoveltyGuard guard = new BodySurveyNoveltyGuard();

        assertTrue(guard.admits(codexEntry(NEW_CODEX_ENTRY)));
        assertTrue(
                guard.admits(scan(SCAN)),
                "an empty rock is worth a word when looking at it was a first"
        );
    }

    /**
     * An entry the codex already held is not the exception.
     *
     * <p>162 of the 168 codex records in these journals say {@code IsNewEntry};
     * the six that do not are not discoveries, and the exception is for
     * discoveries.</p>
     */
    @Test
    void anEntryTheCodexAlreadyHeldIsNotTheException() {
        BodySurveyNoveltyGuard guard = new BodySurveyNoveltyGuard();

        assertTrue(guard.admits(codexEntry(
                NEW_CODEX_ENTRY.replace("\"IsNewEntry\":true",
                        "\"IsNewEntry\":false")
        )));
        assertFalse(guard.admits(scan(SCAN)));
    }

    /** One step of lookback, and only one. */
    @Test
    void theExceptionDoesNotOutliveTheReadingAfterIt() {
        BodySurveyNoveltyGuard guard = new BodySurveyNoveltyGuard();
        guard.admits(codexEntry(NEW_CODEX_ENTRY));
        assertTrue(guard.admits(scan(SCAN)));

        String anotherBody = SCAN
                .replace("\"BodyID\":12", "\"BodyID\":13")
                .replace("c27-156 3", "c27-156 4");
        assertFalse(
                guard.admits(scan(anotherBody)),
                "the codex entry was about one look, not about the rest of them"
        );
    }

    private static JournalEventObservation scan(String rawJson) {
        return Scan.of(raw(rawJson, "Scan"));
    }

    private static JournalEventObservation signals() {
        return new FSSBodySignals(raw(SIGNALS, "FSSBodySignals"));
    }

    private static JournalEventObservation codexEntry(String rawJson) {
        return new CodexEntry(raw(rawJson, "CodexEntry"));
    }

    private static RawJournalData raw(String rawJson, String eventType) {
        try {
            String compact = JSON.readTree(rawJson).toString();
            return new RawJournalData(
                    compact,
                    JSON.readTree(compact),
                    Optional.of(eventType),
                    Optional.of(Instant.parse("2026-08-08T20:42:03Z"))
            );
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
