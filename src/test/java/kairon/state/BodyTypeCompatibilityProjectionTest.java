package kairon.state;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class BodyTypeCompatibilityProjectionTest {

    @Test
    void returnsPlanetClassWhenPlanetHasSpecificClassification() {
        assertEquals(
                "Icy body",
                BodyTypeCompatibilityProjection.compatibleBodyType(
                        "Planet",
                        "Icy body",
                        null
                )
        );
    }

    @Test
    void returnsBroadPlanetWhenNoPlanetClass() {
        assertEquals(
                "Planet",
                BodyTypeCompatibilityProjection.compatibleBodyType(
                        "Planet",
                        null,
                        null
                )
        );
    }

    @Test
    void returnsStarTypeWhenStarHasSpecificClassification() {
        assertEquals(
                "K",
                BodyTypeCompatibilityProjection.compatibleBodyType(
                        "Star",
                        null,
                        "K"
                )
        );
    }

    @Test
    void returnsBroadStarWhenNoStarType() {
        assertEquals(
                "Star",
                BodyTypeCompatibilityProjection.compatibleBodyType(
                        "Star",
                        null,
                        null
                )
        );
    }

    @Test
    void ignoresPlanetDetailForStationCategory() {
        assertEquals(
                "Station",
                BodyTypeCompatibilityProjection.compatibleBodyType(
                        "Station",
                        "Icy body",
                        null
                )
        );
    }

    @Test
    void ignoresStarTypeForStationCategory() {
        assertEquals(
                "Station",
                BodyTypeCompatibilityProjection.compatibleBodyType(
                        "Station",
                        null,
                        "K"
                )
        );
    }

    @Test
    void usesPlanetDetailWhenBroadIsAbsent() {
        assertEquals(
                "Icy body",
                BodyTypeCompatibilityProjection.compatibleBodyType(
                        null,
                        "Icy body",
                        null
                )
        );
    }

    @Test
    void usesStarTypeWhenBroadIsAbsent() {
        assertEquals(
                "K",
                BodyTypeCompatibilityProjection.compatibleBodyType(
                        null,
                        null,
                        "K"
                )
        );
    }

    @Test
    void returnsNullWhenNoBroadAndBothDetailsPresent() {
        assertEquals(
                null,
                BodyTypeCompatibilityProjection.compatibleBodyType(
                        null,
                        "Icy body",
                        "K"
                )
        );
    }

    @Test
    void returnsNullWhenNoValuesKnown() {
        assertEquals(
                null,
                BodyTypeCompatibilityProjection.compatibleBodyType(
                        null,
                        null,
                        null
                )
        );
    }

    @Test
    void returnsNullWhenOnlyBlankValuesKnown() {
        assertEquals(
                null,
                BodyTypeCompatibilityProjection.compatibleBodyType(
                        " ",
                        " ",
                        " "
                )
        );
    }

}
