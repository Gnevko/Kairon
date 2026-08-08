package kairon.state;

import kairon.observation.ObservationDraft;
import kairon.observation.ObservationDraft.ObservationCaptureMode;
import kairon.observation.ObservationDraft.ObservationSource;
import kairon.observation.PublishedObservation;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalLineParser;
import kairon.observation.journal.JournalLineParser.CompleteJournalRecord;
import kairon.observation.journal.JournalLineParser.ParsedJournalRecord;
import kairon.observation.journal.JournalObservationAdapter;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * What a session opening says about the Commander's ship, and what it does not.
 *
 * <p>{@code LoadGame} reports whatever the Commander is sitting in. That is
 * their ship whenever the session opens in it, and something else whenever it
 * does not — an SRV, or a suit when they log in on foot. Every record below is
 * copied from this project's own journals rather than invented, because the
 * whole claim is about what the game actually writes.</p>
 */
final class SessionOpeningShipIdentityTest {

    /** 2026-08-07, the Commander logs in aboard the Mandalay. */
    private static final String LOGGED_IN_ABOARD_THE_SHIP = """
            {"timestamp":"2026-08-07T17:29:22Z","event":"LoadGame",
             "FID":"F12155965","Commander":"GNEVKO","Horizons":true,
             "Odyssey":true,"Ship":"Explorer_NX","ShipID":9,
             "ShipName":"","ShipIdent":"","FuelLevel":126.856537,
             "FuelCapacity":128.0,"GameMode":"Open","Credits":3027365196,
             "Loan":0}
            """;

    /** 2026-08-08, the Commander logs in sitting in the Nomad. */
    private static final String LOGGED_IN_INSIDE_THE_SRV = """
            {"timestamp":"2026-08-08T12:17:31Z","event":"LoadGame",
             "FID":"F12155965","Commander":"GNEVKO","Horizons":true,
             "Odyssey":true,"Ship":"Lander01","Ship_Localised":"Nomad",
             "ShipID":10,"ShipName":"","ShipIdent":"","FuelLevel":0.0,
             "FuelCapacity":0.0,"StartLanded":true,"GameMode":"Open",
             "Credits":3027365196,"Loan":0}
            """;

    /** 2026-07-26, the Commander logs in on foot. */
    private static final String LOGGED_IN_ON_FOOT = """
            {"timestamp":"2026-07-26T09:42:12Z","event":"LoadGame",
             "FID":"F12155965","Commander":"GNEVKO","Horizons":true,
             "Odyssey":true,"Ship":"ExplorationSuit_Class5",
             "ShipID":4293000003,"ShipName":"","ShipIdent":"",
             "FuelLevel":0.923796,"FuelCapacity":1.0,"GameMode":"Open",
             "Credits":1923649086,"Loan":0}
            """;

    private static final LastKnownShip THE_MANDALAY =
            new LastKnownShip("F12155965", 9L, "explorer_nx", "Caspian");

    @Test
    void openingAboardTheShipNamesIt() {
        Fixture fixture = new Fixture(null);

        fixture.apply(LOGGED_IN_ABOARD_THE_SHIP);

        assertEquals(9L, fixture.snapshot().shipId());
        assertEquals("Explorer_NX", fixture.snapshot().shipType());
    }

    /**
     * The SRV and the suit are not the Commander's ship.
     *
     * <p>Both were taken at face value until 2026-08-08, and both mint a graph
     * of their own when they are: the Nomad's session was recorded against ship
     * 10 while ship 9 held every episode of the visit. Twenty-two of the 340
     * {@code LoadGame} records in these journals are one of these two.</p>
     */
    @Test
    void openingInsideAnSrvOrOnFootNamesNoShipAtAll() {
        Fixture srv = new Fixture(null);
        srv.apply(LOGGED_IN_INSIDE_THE_SRV);
        assertEquals("F12155965", srv.snapshot().commanderFid());
        assertNull(srv.snapshot().shipId(), "the Nomad is not a ship");
        assertNull(srv.snapshot().shipType());

        Fixture onFoot = new Fixture(null);
        onFoot.apply(LOGGED_IN_ON_FOOT);
        assertEquals("F12155965", onFoot.snapshot().commanderFid());
        assertNull(onFoot.snapshot().shipId(), "a suit is not a ship");
    }

    /**
     * Rejection is on positive evidence, so an older record is still a ship.
     *
     * <p>Every fixture written before any of this mattered omits
     * {@code FuelCapacity} entirely. Silence is not a claim that there is no
     * fuel tank, and reading it as one would have made the whole suite go
     * shipless.</p>
     */
    @Test
    void aRecordThatSaysNothingAboutFuelIsStillAShip() {
        Fixture fixture = new Fixture(null);

        fixture.apply("""
                {"timestamp":"2026-07-30T10:00:01Z","event":"LoadGame",
                 "FID":"F100","ShipID":9,"Ship":"krait_mkii",
                 "ShipName":"Caspian"}
                """);

        assertEquals(9L, fixture.snapshot().shipId());
    }

    /**
     * A session with no ship of its own starts from the last one known.
     *
     * <p>This is the whole point of the seed. The Nomad's session emits no
     * {@code Loadout} at all — the Commander never boards the ship — so without
     * this it would have no ship for its entire length, and the graph nowhere
     * to write.</p>
     */
    @Test
    void aSessionOpeningInsideAnSrvStartsFromTheShipItIsAttachedTo() {
        Fixture fixture = new Fixture(THE_MANDALAY);

        fixture.apply(LOGGED_IN_INSIDE_THE_SRV);

        assertEquals(9L, fixture.snapshot().shipId());
        assertEquals("explorer_nx", fixture.snapshot().shipType());
        assertEquals("Caspian", fixture.snapshot().shipName());
    }

    /** The seed is where a run starts, not what it believes. */
    @Test
    void theFirstLoadoutReplacesTheSeed() {
        Fixture fixture = new Fixture(THE_MANDALAY);
        fixture.apply(LOGGED_IN_INSIDE_THE_SRV);

        fixture.apply("""
                {"timestamp":"2026-08-08T12:30:00Z","event":"Loadout",
                 "ShipID":7,"Ship":"lakonminer","ShipName":"Second",
                 "Modules":[
                   {"Slot":"MainEngines","Item":"int_engine_size6_class5"}
                 ]}
                """);

        assertEquals(7L, fixture.snapshot().shipId());
        assertEquals("lakonminer", fixture.snapshot().shipType());
    }

    /** Another Commander's run is not seeded from this one's ship. */
    @Test
    void theSeedBelongsToOneCommander() {
        Fixture fixture = new Fixture(THE_MANDALAY);

        fixture.apply("""
                {"timestamp":"2026-08-08T12:17:31Z","event":"LoadGame",
                 "FID":"F999","Commander":"OTHER","Ship":"Lander01",
                 "ShipID":10,"FuelLevel":0.0,"FuelCapacity":0.0}
                """);

        assertEquals("F999", fixture.snapshot().commanderFid());
        assertNull(fixture.snapshot().shipId());
    }

    // ------------------------------------------------------------- fixtures

    private static final class Fixture {

        private static final ObservationSource SOURCE =
                new ObservationSource("elite-journal", "ship-identity-test");
        private final JournalLineParser parser = new JournalLineParser();
        private final JournalObservationAdapter adapter =
                new JournalObservationAdapter(SOURCE);
        private final CurrentGameStateProjector projector;
        private long sourceOffset;
        private long busSequence;

        private Fixture(LastKnownShip lastKnownShip) {
            projector = new CurrentGameStateProjector(lastKnownShip);
        }

        private CurrentGameStateSnapshot snapshot() {
            return projector.currentSnapshot();
        }

        private void apply(String rawJson) {
            byte[] bytes = rawJson.strip().getBytes(StandardCharsets.UTF_8);
            ParsedJournalRecord parsed = assertInstanceOf(
                    ParsedJournalRecord.class,
                    parser.parse(new CompleteJournalRecord(
                            "Journal.ship-identity-test.log",
                            sourceOffset,
                            bytes
                    ))
            );
            sourceOffset += bytes.length + 1L;
            ObservationDraft<JournalEventObservation> draft = adapter.adapt(
                    parsed,
                    ObservationCaptureMode.REPLAY,
                    parsed.optionalJournalTimestamp().orElse(Instant.EPOCH)
            );
            projector.applyAndCapture(new PublishedObservation<>(
                    draft.observationId(),
                    ++busSequence,
                    draft.source(),
                    draft.sourcePosition(),
                    draft.sourceTime(),
                    draft.observedAt(),
                    draft.captureMode(),
                    draft.schemaVersion(),
                    draft.payload()
            ));
        }
    }
}
