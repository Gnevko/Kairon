package kairon.observer.decision;

/**
 * The journal records every decision test needs before it can say anything.
 *
 * <p>A test in this package is about one record — the scan, the survey, the
 * landing. Everything before it is scaffolding: somebody has to be logged in,
 * flying something, somewhere. That scaffolding had been written out in each
 * class, and by 2026-08-08 thirteen classes each held a private
 * {@code loadGame()} producing the same JSON to the byte, four held the same
 * {@code jump(time, system, address)}, and three held the same arrival jump.
 * They are here instead, and the call sites did not change: these are imported
 * statically, so a test still reads {@code loadGame()}.</p>
 *
 * <p><strong>Only what is genuinely one record belongs here.</strong> A builder
 * lives in its own test class when that class varies it — the survey readings,
 * the scans with their own bodies and distances, the approaches to particular
 * bodies are all a test's subject rather than its scaffolding, and moving a
 * subject out of sight of its assertions would cost more than the lines it
 * saves. What moved here was proved identical first, by comparing the produced
 * JSON rather than the wording of the method.</p>
 */
final class Journal {

    /** The one Commander these tests are flown by. */
    static final String COMMANDER = "F12345678";

    /** Their ship: an id, a type and a name, all three established at once. */
    static final long SHIP_ID = 9L;
    static final String SHIP_TYPE = "explorer_nx";
    static final String SHIP_NAME = "Wanderer";

    /** The day. Every record in this package is stamped on it. */
    static final String DATE = "2026-07-30T";

    private Journal() {
    }

    /** The session opens, at the hour every fixture starts on. */
    static String loadGame() {
        return loadGame("10:00:00Z");
    }

    /** The session opens in the Commander's own ship. */
    static String loadGame(String time) {
        return loadGameOnShip(time, SHIP_ID);
    }

    /**
     * The session opens in a named vessel.
     *
     * <p>The id is a parameter because a session can open in something that is
     * not the ship at all, and telling those apart is its own contract — see
     * {@code SessionOpeningShipIdentityTest}.</p>
     */
    static String loadGameOnShip(String time, long shipId) {
        return "{\"timestamp\":\"" + DATE + time
                + "\",\"event\":\"LoadGame\",\"FID\":\"" + COMMANDER + "\","
                + "\"ShipID\":" + shipId + ",\"Ship\":\"" + SHIP_TYPE + "\","
                + "\"ShipName\":\"" + SHIP_NAME + "\"}";
    }

    /**
     * A jump that names the system it arrived in and nothing else.
     *
     * <p>No arrival body. A jump record carries one when the client wrote one,
     * and a test that is about the arrival star wants {@link #arrivalJump} —
     * the difference decides whether the star is established, so it is a choice
     * a test makes rather than a default.</p>
     *
     * <p>The address comes before the name here, and in every method below it,
     * because that is the order the call sites in this package were already
     * written in. Matching them meant nothing had to be touched but the
     * declaration.</p>
     */
    static String jump(String time, long address, String system) {
        return "{\"timestamp\":\"" + DATE + time
                + "\",\"event\":\"FSDJump\",\"StarSystem\":\"" + system
                + "\",\"SystemAddress\":" + address
                + ",\"JumpDist\":8.5,\"FuelUsed\":0.4,\"FuelLevel\":30.2}";
    }

    /** A jump that also names the star it arrived at, as the client does. */
    static String arrivalJump(String time, long address, String system) {
        return "{\"timestamp\":\"" + DATE + time
                + "\",\"event\":\"FSDJump\",\"StarSystem\":\"" + system
                + "\",\"SystemAddress\":" + address
                + ",\"Body\":\"" + system + "\",\"BodyID\":0,"
                + "\"BodyType\":\"Star\",\"JumpDist\":2.839,"
                + "\"FuelUsed\":0.001857,\"FuelLevel\":123.360168}";
    }

    /** Where the ship is, reported without having travelled to get there. */
    static String location(String time, long address, String system) {
        return "{\"timestamp\":\"" + DATE + time
                + "\",\"event\":\"Location\",\"StarSystem\":\"" + system
                + "\",\"SystemAddress\":" + address + ",\"Docked\":false}";
    }

    /**
     * The system scanner reporting that a body has something on it.
     *
     * <p>Scaffolding since 2026-08-08, because a body reading of a body nothing
     * was reported to be on no longer opens a turn. In a real journal this
     * record always comes before the scan — 175 times out of 175 in this
     * Commander's own — so a test whose subject is the scan writes it first and
     * then gets on with the scan.</p>
     *
     * <p>One biological signal, deliberately the least a reading can establish:
     * a test that varies what was found is a test about signals, and that
     * belongs beside its own assertions rather than here.</p>
     */
    static String bodyBearsSignals(
            String time,
            long address,
            int bodyId,
            String bodyName
    ) {
        return "{\"timestamp\":\"" + DATE + time
                + "\",\"event\":\"FSSBodySignals\",\"BodyName\":\"" + bodyName
                + "\",\"BodyID\":" + bodyId
                + ",\"SystemAddress\":" + address
                + ",\"Signals\":[{\"Type\":\"$SAA_SignalType_Biological;\","
                + "\"Count\":1}]}";
    }
}
