package kairon.behavior;

import kairon.behavior.context.BehaviorContextAdapter;
import kairon.behavior.model.ContextSnapshot;
import kairon.behavior.model.GraphId;
import kairon.state.CommanderLocationMode;
import kairon.state.CurrentGameStateSnapshot;
import kairon.state.FlightMode;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class BehaviorContextAdapterTest {

    private final BehaviorContextAdapter adapter =
            new BehaviorContextAdapter();

    @Test
    void convertsEveryPersistedContextFieldWithoutChangingSemantics() {
        CurrentGameStateSnapshot state = new CurrentGameStateSnapshot(
                "F100",
                9L,
                "krait_mkii",
                "Caspian",
                "lo1-hash",
                1001L,
                "Test System",
                2L,
                "Test System 2",
                null,
                "Rocky body",
                null,
                CommanderLocationMode.SRV,
                FlightMode.LANDED,
                CurrentGameStateSnapshot.VEHICLE_NOMAD,
                101L,
                4,
                2,
                true,
                true,
                false,
                false,
                42.5,
                true,
                true
        ,
        null
    );

        ContextSnapshot expected = new ContextSnapshot(
                "F100",
                9L,
                "krait_mkii",
                "Caspian",
                "lo1-hash",
                1001L,
                "Test System",
                2L,
                "Test System 2",
                "Rocky body",
                CommanderLocationMode.SRV,
                FlightMode.LANDED,
                CurrentGameStateSnapshot.VEHICLE_NOMAD,
                4,
                2,
                true,
                true,
                false,
                false,
                42.5,
                true,
                true
        );

        assertEquals(expected, adapter.toContextSnapshot(state));
    }

    @Test
    void derivesGraphIdOnlyFromValidCanonicalIdentity() {
        assertEquals(
                Optional.of(new GraphId("F100", 9L)),
                adapter.graphId(state("F100", 9L))
        );
        assertEquals(Optional.empty(), adapter.graphId(state(null, 9L)));
        assertEquals(Optional.empty(), adapter.graphId(state("F100", null)));
        assertEquals(Optional.empty(), adapter.graphId(state("F100", 0L)));
    }

    @Test
    void projectsMostSpecificPlanetBodyType() {
        CurrentGameStateSnapshot state = new CurrentGameStateSnapshot(
                "F100",
                9L,
                "krait_mkii",
                "Caspian",
                "lo1-hash",
                1001L,
                "Test System",
                2L,
                "Test System 2",
                "Planet",
                "Icy body",
                null,
                CommanderLocationMode.SRV,
                FlightMode.LANDED,
                CurrentGameStateSnapshot.VEHICLE_NOMAD,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        ,
        null
    );

        ContextSnapshot context = adapter.toContextSnapshot(state);
        assertEquals("Icy body", context.bodyType());
    }

    @Test
    void projectsStarTypeWhenBroadStarAndStarClassKnown() {
        CurrentGameStateSnapshot state = new CurrentGameStateSnapshot(
                "F100",
                9L,
                "krait_mkii",
                "Caspian",
                "lo1-hash",
                1001L,
                "Test System",
                2L,
                "Test System 2",
                "Star",
                null,
                "K",
                CommanderLocationMode.SRV,
                FlightMode.LANDED,
                CurrentGameStateSnapshot.VEHICLE_NOMAD,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        ,
        null
    );
        assertEquals("K", adapter.toContextSnapshot(state).bodyType());
    }

    @Test
    void keepsBodyTypeCategoryForNonPlanetAndNonStarBroadValue() {
        CurrentGameStateSnapshot state = state(
                "F100",
                9L,
                "Station",
                "Icy body"
        );
        assertEquals("Station", adapter.toContextSnapshot(state).bodyType());
    }

    @Test
    void returnsNullLegacyBodyTypeWhenBroadAndBothDetailsPresentWithoutBroad() {
        CurrentGameStateSnapshot state =
                new CurrentGameStateSnapshot(
                        "F100",
                        9L,
                        "krait_mkii",
                        "Caspian",
                        "lo1-hash",
                        1001L,
                        "Test System",
                        2L,
                        "Test System 2",
                        null,
                        "Icy body",
                        "K",
                        CommanderLocationMode.SRV,
                        FlightMode.LANDED,
                        CurrentGameStateSnapshot.VEHICLE_NOMAD,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                ,
                null
            );
        assertNull(adapter.toContextSnapshot(state).bodyType());
    }

    private static CurrentGameStateSnapshot state(
            String commanderFid,
            Long shipId
    ) {
        return new CurrentGameStateSnapshot(
                commanderFid,
                shipId,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                CommanderLocationMode.UNKNOWN,
                FlightMode.UNKNOWN,
                CurrentGameStateSnapshot.VEHICLE_UNKNOWN,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        ,
        null
    );
    }

    private static CurrentGameStateSnapshot state(
            String commanderFid,
            Long shipId,
            String broadBodyType,
            String detail
    ) {
        return new CurrentGameStateSnapshot(
                commanderFid,
                shipId,
                "krait_mkii",
                "Caspian",
                "lo1-hash",
                1001L,
                "Test System",
                2L,
                "Test System 2",
                broadBodyType,
                detail,
                null,
                CommanderLocationMode.UNKNOWN,
                FlightMode.UNKNOWN,
                CurrentGameStateSnapshot.VEHICLE_UNKNOWN,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        ,
        null
    );
    }
}
