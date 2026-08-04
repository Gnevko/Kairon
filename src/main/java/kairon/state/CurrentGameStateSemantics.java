package kairon.state;

import kairon.semantics.SemanticField;
import kairon.semantics.SemanticValue;

import java.util.Objects;
import java.util.function.Function;

/**
 * Reads one canonical snapshot field as a typed semantic value.
 *
 * <p>The three non-null enum-backed fields use their {@code UNKNOWN} sentinel
 * as absence of knowledge, so a transition out of {@code UNKNOWN} is an
 * establishment and a transition back into it is a clear.</p>
 */
public final class CurrentGameStateSemantics {

    private CurrentGameStateSemantics() {
    }

    public static SemanticValue valueOf(
            SemanticField field,
            CurrentGameStateSnapshot snapshot
    ) {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(snapshot, "snapshot");
        return switch (field) {
            case COMMANDER_FID ->
                    SemanticValue.ofText(snapshot.commanderFid());
            case SHIP_ID -> SemanticValue.ofIntegral(snapshot.shipId());
            case SHIP_TYPE -> SemanticValue.ofSymbol(snapshot.shipType());
            case SHIP_NAME -> SemanticValue.ofText(snapshot.shipName());
            case LOADOUT_HASH ->
                    SemanticValue.ofText(snapshot.loadoutHash());
            case SYSTEM_ADDRESS ->
                    SemanticValue.ofIntegral(snapshot.systemAddress());
            case SYSTEM_NAME -> SemanticValue.ofText(snapshot.systemName());
            case BODY_ID -> SemanticValue.ofIntegral(snapshot.bodyId());
            case BODY_NAME -> SemanticValue.ofText(snapshot.bodyName());
            case BROAD_BODY_TYPE ->
                    SemanticValue.ofSymbol(snapshot.broadBodyType());
            case PLANET_CLASS ->
                    SemanticValue.ofSymbol(snapshot.planetClass());
            case STAR_TYPE -> SemanticValue.ofSymbol(snapshot.starType());
            case LANDABLE -> SemanticValue.ofBoolean(snapshot.landable());
            case WAS_DISCOVERED ->
                    SemanticValue.ofBoolean(snapshot.wasDiscovered());
            case WAS_MAPPED -> SemanticValue.ofBoolean(snapshot.wasMapped());
            case WAS_FOOTFALLED ->
                    SemanticValue.ofBoolean(snapshot.wasFootfalled());
            case DISTANCE_FROM_ARRIVAL_LS ->
                    SemanticValue.ofDecimal(snapshot.distanceFromArrivalLs());
            case BIOLOGICAL_SIGNAL_COUNT ->
                    SemanticValue.ofIntegral(snapshot.biologicalSignalCount());
            case GEOLOGICAL_SIGNAL_COUNT ->
                    SemanticValue.ofIntegral(snapshot.geologicalSignalCount());
            case COMMANDER_MODE -> symbolic(
                    snapshot.commanderMode().name(),
                    CommanderLocationMode.UNKNOWN.name()
            );
            case FLIGHT_MODE -> symbolic(
                    snapshot.flightMode().name(),
                    FlightMode.UNKNOWN.name()
            );
            case VEHICLE_KIND -> symbolic(
                    snapshot.vehicleKind(),
                    CurrentGameStateSnapshot.VEHICLE_UNKNOWN
            );
            case ACTIVE_VEHICLE_ID ->
                    SemanticValue.ofIntegral(snapshot.activeVehicleId());
            case BODY_HAS_BIOLOGY ->
                    SemanticValue.ofBoolean(snapshot.bodyHasBiology());
            case ACTIVE_ORGANIC_SAMPLING ->
                    SemanticValue.ofBoolean(snapshot.activeOrganicSampling());
            case ORGANIC_SAMPLING_SYSTEM_ADDRESS -> process(
                    snapshot,
                    p -> SemanticValue.ofIntegral(p.systemAddress())
            );
            case ORGANIC_SAMPLING_BODY_ID -> process(
                    snapshot,
                    p -> SemanticValue.ofIntegral(p.bodyId())
            );
            case ORGANIC_SAMPLING_GENUS ->
                    process(snapshot, p -> identifier(p.genus()));
            case ORGANIC_SAMPLING_GENUS_LABEL ->
                    process(snapshot, p -> label(p.genus()));
            case ORGANIC_SAMPLING_SPECIES ->
                    process(snapshot, p -> identifier(p.species()));
            case ORGANIC_SAMPLING_SPECIES_LABEL ->
                    process(snapshot, p -> label(p.species()));
            case ORGANIC_SAMPLING_VARIANT ->
                    process(snapshot, p -> identifier(p.variant()));
            case ORGANIC_SAMPLING_VARIANT_LABEL ->
                    process(snapshot, p -> label(p.variant()));
            case ORGANIC_SAMPLING_STAGE -> process(
                    snapshot,
                    p -> SemanticValue.ofSymbol(p.stage().name())
            );
        };
    }

    /** No active sequence means every one of its fields is unestablished. */
    private static SemanticValue process(
            CurrentGameStateSnapshot snapshot,
            Function<BiologicalSamplingProcess, SemanticValue> read
    ) {
        BiologicalSamplingProcess active = snapshot.samplingProcess();
        return active == null ? SemanticValue.unknown() : read.apply(active);
    }

    private static SemanticValue identifier(TaxonName taxon) {
        return taxon == null
                ? SemanticValue.unknown()
                : SemanticValue.ofSymbol(taxon.identifier());
    }

    private static SemanticValue label(TaxonName taxon) {
        return taxon == null
                ? SemanticValue.unknown()
                : SemanticValue.ofText(taxon.label());
    }

    private static SemanticValue symbolic(
            String value,
            String unknownSentinel
    ) {
        return unknownSentinel.equals(value)
                ? SemanticValue.unknown()
                : SemanticValue.ofSymbol(value);
    }
}
