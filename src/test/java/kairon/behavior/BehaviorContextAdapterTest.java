package kairon.behavior;

import kairon.behavior.context.BehaviorContextAdapter;
import kairon.behavior.context.BodyDetail;
import kairon.behavior.context.BodyDetailLookup;
import kairon.behavior.model.ContextSnapshot;
import kairon.behavior.model.GraphId;
import kairon.state.CommanderLocationMode;
import kairon.state.CurrentGameStateSnapshot;
import kairon.state.FlightMode;
import org.junit.jupiter.api.Test;

import java.util.Objects;
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
                CommanderLocationMode.SRV,
                FlightMode.LANDED,
                CurrentGameStateSnapshot.VEHICLE_NOMAD,
                101L,
                true,
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

        assertEquals(
                expected,
                adapter.toContextSnapshot(state, only(1001L, 2L, new BodyDetail(
                        null,
                        "Rocky body",
                        null,
                        true,
                        true,
                        false,
                        false,
                        42.5,
                        4,
                        2
                )))
        );
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
        assertEquals(
                "Icy body",
                bodyTypeOf(body("PLANET", "Icy body", null))
        );
    }

    @Test
    void projectsStarTypeWhenBroadStarAndStarClassKnown() {
        assertEquals("K", bodyTypeOf(body("STAR", null, "K")));
    }

    @Test
    void keepsBodyTypeCategoryForNonPlanetAndNonStarBroadValue() {
        assertEquals(
                "BARYCENTRE",
                bodyTypeOf(body("BARYCENTRE", "Icy body", null))
        );
    }

    @Test
    void returnsNullLegacyBodyTypeWhenBroadAndBothDetailsPresentWithoutBroad() {
        assertNull(bodyTypeOf(body(null, "Icy body", "K")));
    }

    /**
     * A body detail is about a body, and the body is in a system.
     *
     * <p>A lookup describing another system answers nothing, and the context
     * then carries where the Commander is and nothing about what is there.
     * Body ids repeat across systems, so the alternative is a moon of the
     * previous system described as this one's.</p>
     */
    @Test
    void takesNoBodyDetailWhenTheLookupDescribesAnotherSystem() {
        ContextSnapshot context = adapter.toContextSnapshot(
                state("F100", 9L),
                only(4242L, 2L, body("PLANET", "Icy body", null))
        );
        assertNull(context.bodyType());
        assertNull(context.landable());
        assertNull(context.biologicalSignalCount());
        assertNull(context.bodyHasBiology());
    }

    @Test
    void readsHasBiologyFromTheBiologicalCountAlone() {
        assertEquals(
                Boolean.TRUE,
                adapter.toContextSnapshot(
                        located(),
                        only(1001L, 2L, new BodyDetail(
                                null, null, null, null, null, null, null,
                                null, 3, null
                        ))
                ).bodyHasBiology()
        );
        assertNull(
                adapter.toContextSnapshot(
                        located(),
                        BodyDetailLookup.NONE
                ).bodyHasBiology()
        );
    }

    private String bodyTypeOf(BodyDetail detail) {
        return adapter
                .toContextSnapshot(located(), only(1001L, 2L, detail))
                .bodyType();
    }

    private static BodyDetail body(
            String broadBodyType,
            String planetClass,
            String starType
    ) {
        return new BodyDetail(
                broadBodyType,
                planetClass,
                starType,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    /** A lookup that answers for exactly one body and nothing else. */
    private static BodyDetailLookup only(
            Long systemAddress,
            Long bodyId,
            BodyDetail detail
    ) {
        return (askedSystem, askedBody) ->
                Objects.equals(systemAddress, askedSystem)
                        && Objects.equals(bodyId, askedBody)
                        ? detail
                        : BodyDetail.UNKNOWN;
    }

    private static CurrentGameStateSnapshot located() {
        return new CurrentGameStateSnapshot(
                "F100",
                9L,
                "krait_mkii",
                "Caspian",
                "lo1-hash",
                1001L,
                "Test System",
                2L,
                "Test System 2",
                CommanderLocationMode.UNKNOWN,
                FlightMode.UNKNOWN,
                CurrentGameStateSnapshot.VEHICLE_UNKNOWN,
                null,
                null,
                null
        );
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
                1001L,
                "Test System",
                2L,
                "Test System 2",
                CommanderLocationMode.UNKNOWN,
                FlightMode.UNKNOWN,
                CurrentGameStateSnapshot.VEHICLE_UNKNOWN,
                null,
                null,
                null
        );
    }
}
