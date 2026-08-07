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
 *
 * <p>A field canonical state does not answer reads unknown here, which is what
 * is true of it: {@link SemanticField#answeredByCanonicalState} is where the
 * two kinds are told apart, and nothing asks this for the others.</p>
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
            // Body detail is the current system's, not the ship's position:
            // canonical state names the body and answers nothing about it
            // (ADR-0025). The field identities remain because the model-facing
            // contract is keyed by them — what a scan states and what the
            // context reports are one fact under one identity — and the
            // registry is what answers them.
            case SYSTEM_BODY_COUNT,
                 SYSTEM_SCANNED_COUNT,
                 BROAD_BODY_TYPE,
                 PLANET_CLASS,
                 STAR_TYPE,
                 LANDABLE,
                 WAS_DISCOVERED,
                 WAS_MAPPED,
                 WAS_FOOTFALLED,
                 DISTANCE_FROM_ARRIVAL_LS,
                 SURFACE_GRAVITY,
                 BIOLOGICAL_SIGNAL_COUNT,
                 GEOLOGICAL_SIGNAL_COUNT,
                 BODY_HAS_BIOLOGY -> SemanticValue.unknown();
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
