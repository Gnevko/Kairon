package kairon.observation.journal;

import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.JournalLineParser.CompleteJournalRecord;
import kairon.observation.journal.JournalLineParser.ParsedJournalRecord;
import kairon.observation.journal.event.inventory.MaterialCollected;
import kairon.observation.journal.event.ship.LaunchDrone;
import kairon.observation.journal.event.travel.DockingGranted;
import kairon.observation.journal.event.travel.DockingRequested;
import kairon.observation.journal.event.travel.FuelScoop;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JournalEventOperationalLlmPresentationTest {

    private final JournalLineParser parser = new JournalLineParser();

    @Test
    void dockingRequestedReportsPadCountsWithoutPromisingAvailability() {
        DockingRequested event = new DockingRequested(rawData("""
                {"timestamp":"2025-10-24T16:00:00Z","event":"DockingRequested","StationName":"Hutton Orbital","StationType":"Outpost","MarketID":128678535,"LandingPads":{"Small":2,"Medium":1,"Large":0}}
                """.strip()));

        String presentation = event.llmPresentation().text();

        assertTrue(presentation.contains(
                "requested docking at \u201cHutton Orbital\u201d"
        ));
        assertTrue(presentation.contains("station type \u201cOutpost\u201d"));
        assertTrue(presentation.contains("market ID 128678535"));
        assertTrue(presentation.contains(
                "landing-pad counts of small 2, medium 1, and large 0"
        ));
        assertTrue(presentation.contains(
                "do not guarantee that a pad is currently available"
        ));
    }

    @Test
    void dockingGrantedReportsAssignedPadAsSourceFact() {
        DockingGranted event = new DockingGranted(rawData("""
                {"timestamp":"2025-10-24T16:00:05Z","event":"DockingGranted","StationName":"Hutton Orbital","StationType":"Outpost","MarketID":128678535,"LandingPad":3}
                """.strip()));

        String presentation = event.llmPresentation().text();

        assertTrue(presentation.contains("granted the player's docking request"));
        assertTrue(presentation.contains("assigned landing pad 3"));
        assertTrue(presentation.contains("station type \u201cOutpost\u201d"));
        assertTrue(presentation.contains("market ID 128678535"));
    }

    @Test
    void launchDronePreservesUnknownSourceTypeAndClaimsNoOutcome() {
        LaunchDrone event = LaunchDrone.of(rawData("""
                {"timestamp":"2025-10-24T18:38:21Z","event":"LaunchDrone","Type":"Recon"}
                """.strip()));

        String presentation = event.llmPresentation().text();

        assertTrue(presentation.contains("launched a recon limpet"));
        assertTrue(presentation.contains(
                "does not report whether the limpet or drone completed its task"
        ));
        assertFalse(presentation.contains("succeeded"));
    }

    @Test
    void materialCollectedReportsExactNameCountAndCategory() {
        MaterialCollected event = new MaterialCollected(rawData("""
                {"timestamp":"2025-10-24T18:38:22Z","event":"MaterialCollected","Category":"Encoded","Name":"ancientbiologicaldata","Name_Localised":"Ancient Biological Data","Count":3}
                """.strip()));

        String presentation = event.llmPresentation().text();

        assertTrue(presentation.contains(
                "collected 3 units of material "
                        + "\u201cAncient Biological Data\u201d"
        ));
        assertTrue(presentation.contains(
                "journal category \u201cEncoded\u201d"
        ));
        assertFalse(presentation.contains("rare"));
    }

    @Test
    void fuelScoopReportsOneUpdateWithoutClaimingSessionCompletion() {
        FuelScoop event = new FuelScoop(rawData("""
                {"timestamp":"2025-10-24T18:12:03Z","event":"FuelScoop","Scooped":0.4520,"Total":25.630281}
                """.strip()));

        String presentation = event.llmPresentation().text();

        assertTrue(presentation.contains("one fuel-scooping update"));
        assertTrue(presentation.contains(
                "0.452 tonnes scooped in this update"
        ));
        assertTrue(presentation.contains(
                "total ship fuel 25.630281 tonnes"
        ));
        assertTrue(presentation.contains(
                "does not state that the complete fuel-scooping session has finished"
        ));
    }

    private RawJournalData rawData(String rawJson) {
        ParsedJournalRecord parsed = assertInstanceOf(
                ParsedJournalRecord.class,
                parser.parse(new CompleteJournalRecord(
                        "Journal.test.log",
                        0L,
                        rawJson.getBytes(StandardCharsets.UTF_8)
                ))
        );
        return new RawJournalData(
                parsed.rawJson(),
                parsed.parsedJsonObject(),
                parsed.optionalEventType(),
                parsed.optionalJournalTimestamp()
        );
    }
}
