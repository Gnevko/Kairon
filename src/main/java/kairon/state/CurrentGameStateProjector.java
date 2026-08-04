package kairon.state;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.semantics.BodyIdentity;
import kairon.observation.PublishedObservation;
import kairon.observation.journal.JournalEventObservation;
import kairon.semantics.AuxiliaryVehicleTypes;
import kairon.semantics.SemanticChangeKind;
import kairon.semantics.SemanticField;
import kairon.semantics.SemanticProvenance;
import kairon.semantics.SemanticSourceRoles;
import kairon.semantics.SemanticStateChange;
import kairon.semantics.SemanticValue;
import kairon.semantics.SemanticValueOrigin;
import kairon.observation.journal.event.exploration.FSSBodySignals;
import kairon.observation.journal.event.exploration.SAASignalsFound;
import kairon.observation.journal.event.exploration.Scan;
import kairon.observation.journal.event.inventory.Cargo;
import kairon.observation.journal.event.exploration.ScanOrganic;
import kairon.observation.journal.event.session.Commander;
import kairon.observation.journal.event.session.LoadGame;
import kairon.observation.journal.event.ship.DockSRV;
import kairon.observation.journal.event.ship.LaunchFighter;
import kairon.observation.journal.event.ship.LaunchSRV;
import kairon.observation.journal.event.ship.Loadout;
import kairon.observation.journal.event.travel.ApproachBody;
import kairon.observation.journal.event.travel.Disembark;
import kairon.observation.journal.event.travel.Docked;
import kairon.observation.journal.event.travel.Embark;
import kairon.observation.journal.event.travel.FSDJump;
import kairon.observation.journal.event.travel.LeaveBody;
import kairon.observation.journal.event.travel.Liftoff;
import kairon.observation.journal.event.travel.Location;
import kairon.observation.journal.event.travel.StartJump;
import kairon.observation.journal.event.travel.SupercruiseEntry;
import kairon.observation.journal.event.travel.SupercruiseExit;
import kairon.observation.journal.event.travel.Touchdown;
import kairon.observation.journal.event.travel.Undocked;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

import kairon.semantics.BodySurveyFacts;

import static kairon.state.CurrentGameStateSnapshot.VEHICLE_SHIP;
import static kairon.state.CurrentGameStateSnapshot.VEHICLE_SLV;
import static kairon.state.CurrentGameStateSnapshot.VEHICLE_SRV;
import static kairon.state.CurrentGameStateSnapshot.VEHICLE_UNKNOWN;

/**
 * The single mutable projection of the current game state.
 *
 * <p>All mutation and read operations are synchronized. The projection
 * coordinator is the production writer; readers on other executors receive a
 * complete immutable snapshot.</p>
 */
public final class CurrentGameStateProjector
        implements CurrentGameStateProjectionWriter {

    private final Map<BodyIdentity, BodyContext> bodies = new TreeMap<>();
    private final Map<Long, String> vehicleKindsById = new TreeMap<>();

    private String commanderFid;
    private Long shipId;
    private String shipType;
    private String shipName;
    private String loadoutHash;

    private Long systemAddress;
    private String systemName;
    private Long bodyId;
    private String bodyName;
    private String broadBodyType;

    private CommanderLocationMode commanderMode =
            CommanderLocationMode.UNKNOWN;
    private FlightMode flightMode = FlightMode.UNKNOWN;
    private String vehicleKind = VEHICLE_UNKNOWN;
    private Long activeVehicleId;

    /**
     * Whether the active auxiliary vehicle went out through the fighter
     * channel without saying what it was.
     *
     * <p>Internal, never published: the snapshot carries no trace of it and no
     * model-facing field is derived from it. It exists because "an active
     * vehicle of unknown class" is reachable by more than one path — a
     * {@code Disembark} carrying an id while the class is unknown reaches it
     * too — and only the ambiguous launch makes a later {@code Cargo} tagged
     * {@code SRV} mean a Ship-Launched Vessel rather than an SRV.</p>
     */
    private boolean activeVehicleFromAmbiguousLaunch;

    private Boolean activeOrganicSampling;
    private BiologicalSamplingProcess samplingProcess;

    /**
     * Write-path marker for the observation currently being applied.
     *
     * <p>Holds the body key this observation wrote into {@link #bodies}, or
     * {@code null} when it wrote none. This is the only way to distinguish a
     * fact newly learned from this observation from one merely re-activated
     * out of storage: a re-visited body can carry a stored value identical to
     * a freshly observed one, so value comparison cannot tell them apart.</p>
     */
    private BodyIdentity bodyRegistryWriteKey;

    @Override
    public synchronized CurrentGameStateProjection applyAndCapture(
            PublishedObservation<?> observation
    ) {
        Objects.requireNonNull(observation, "observation");
        bodyRegistryWriteKey = null;
        CurrentGameStateSnapshot previousState = snapshot();
        JournalEventObservation journalEvent =
                observation.payload() instanceof JournalEventObservation event
                        ? event
                        : null;
        if (journalEvent != null) {
            applyEvent(journalEvent);
        }
        CurrentGameStateSnapshot currentState = snapshot();
        CurrentGameStateSnapshot observationContext =
                journalEvent == null
                        ? currentState
                        : snapshotFor(journalEvent, currentState);
        return new CurrentGameStateProjection(
                AppliedObservation.of(
                        observation,
                        previousState,
                        currentState,
                        observationContext,
                        semanticChanges(
                                previousState,
                                currentState,
                                provenanceOf(observation)
                        )
                ),
                CurrentGameStateChangeSet.between(
                        previousState,
                        currentState
                )
        );
    }

    /**
     * The exact field-level delta for this observation.
     *
     * <p>Pure with respect to the two snapshots plus the write-path marker.
     * Computed here because {@code previousState} exists only inside this
     * boundary; nothing downstream can reconstruct it.</p>
     */
    private List<SemanticStateChange> semanticChanges(
            CurrentGameStateSnapshot previousState,
            CurrentGameStateSnapshot currentState,
            SemanticProvenance provenance
    ) {
        boolean registryWrittenForCurrentBody =
                registryWriteAppliesToCurrentBody();
        boolean bodyNameHeldDirectly =
                bodyName != null && !bodyName.isBlank();
        List<SemanticStateChange> changes = new ArrayList<>();
        for (SemanticField field : SemanticField.values()) {
            SemanticValue before = CurrentGameStateSemantics.valueOf(
                    field,
                    previousState
            );
            SemanticValue after = CurrentGameStateSemantics.valueOf(
                    field,
                    currentState
            );
            if (before.equals(after)) {
                continue;
            }
            SemanticValueOrigin origin = originOf(
                    field,
                    registryWrittenForCurrentBody,
                    bodyNameHeldDirectly
            );
            changes.add(new SemanticStateChange(
                    field,
                    before,
                    after,
                    changeKindOf(field, before, after, origin),
                    origin,
                    provenance
            ));
        }
        return List.copyOf(changes);
    }

    private static SemanticValueOrigin originOf(
            SemanticField field,
            boolean registryWrittenForCurrentBody,
            boolean bodyNameHeldDirectly
    ) {
        if (!field.bodyRegistryDerived()) {
            return SemanticValueOrigin.OBSERVATION;
        }
        if (field == SemanticField.BODY_NAME && bodyNameHeldDirectly) {
            return SemanticValueOrigin.OBSERVATION;
        }
        return registryWrittenForCurrentBody
                ? SemanticValueOrigin.OBSERVATION
                : SemanticValueOrigin.STORED_CONTEXT;
    }

    private static SemanticChangeKind changeKindOf(
            SemanticField field,
            SemanticValue before,
            SemanticValue after,
            SemanticValueOrigin origin
    ) {
        if (!after.known()) {
            return SemanticChangeKind.CLEARED;
        }
        if (field.bodyRegistryDerived()
                && origin == SemanticValueOrigin.STORED_CONTEXT) {
            return SemanticChangeKind.ACTIVATED_FROM_CONTEXT;
        }
        return before.known()
                ? SemanticChangeKind.UPDATED
                : SemanticChangeKind.ESTABLISHED;
    }

    private boolean registryWriteAppliesToCurrentBody() {
        if (bodyRegistryWriteKey == null
                || systemAddress == null
                || bodyId == null) {
            return false;
        }
        return bodyRegistryWriteKey.equals(
                new BodyIdentity(systemAddress, bodyId)
        );
    }

    private static SemanticProvenance provenanceOf(
            PublishedObservation<?> observation
    ) {
        return new SemanticProvenance(
                observation.busSequence(),
                SemanticSourceRoles.roleOf(observation.payload()),
                SemanticSourceRoles.rawObservationTypeOf(
                        observation.payload()
                ),
                observation.observationId()
        );
    }

    private void applyEvent(JournalEventObservation event) {
        Objects.requireNonNull(event, "event");
        JsonNode raw = event.raw().parsedJsonObject();

        updateIdentity(event, raw);
        if (event instanceof FSDJump) {
            enterSystem(raw);
            // A completed hyperspace jump drops the ship into supercruise at
            // the arrival star. NORMAL_SPACE is what SupercruiseExit, Liftoff
            // and Undocked mean, and none of those has happened here: the FSD
            // is still running and the Commander is still travelling.
            flightMode = FlightMode.SUPERCRUISE;
            activeOrganicSampling = false;
            samplingProcess = null;
        } else if (event instanceof Location) {
            updateLocation(raw);
        } else if (event instanceof Scan) {
            updateBodyFromScan(raw);
        } else if (event instanceof FSSBodySignals
                || event instanceof SAASignalsFound) {
            updateBodySignals(raw);
        } else if (event instanceof ApproachBody) {
            selectBody(raw);
        } else if (event instanceof SupercruiseEntry) {
            updateSystem(raw);
            clearSelectedBody();
            flightMode = FlightMode.SUPERCRUISE;
        } else if (event instanceof SupercruiseExit) {
            selectBody(raw);
            flightMode = FlightMode.NORMAL_SPACE;
        } else if (event instanceof StartJump) {
            updateStartJump(raw);
        } else if (event instanceof LaunchSRV) {
            // The SRV channel names its own vessel, so nothing here is
            // ambiguous: an unnamed one is still a conventional SRV.
            updateVehicleLaunch(raw, VEHICLE_SRV, false);
        } else if (event instanceof LaunchFighter) {
            updateVehicleLaunch(raw, VEHICLE_UNKNOWN, true);
        } else if (event instanceof Cargo cargo) {
            refineVehicleKindFromCargo(cargo);
        } else if (event instanceof Touchdown) {
            selectBody(raw);
            flightMode = FlightMode.LANDED;
        } else if (event instanceof Disembark) {
            selectBody(raw);
            updateDisembark(raw);
        } else if (event instanceof ScanOrganic) {
            updateOrganicSampling(raw);
        } else if (event instanceof Embark) {
            selectBody(raw);
            updateEmbark(raw);
        } else if (event instanceof Liftoff) {
            selectBody(raw);
            flightMode = FlightMode.NORMAL_SPACE;
        } else if (event instanceof LeaveBody) {
            updateSystem(raw);
            clearSelectedBody();
            flightMode = FlightMode.SUPERCRUISE;
        } else if (event instanceof DockSRV) {
            updateVehicleDock(raw);
        } else if (event instanceof Docked) {
            updateSystem(raw);
            flightMode = FlightMode.DOCKED;
            commanderMode = CommanderLocationMode.SHIP;
            vehicleKind = VEHICLE_SHIP;
        } else if (event instanceof Undocked) {
            flightMode = FlightMode.NORMAL_SPACE;
            commanderMode = CommanderLocationMode.SHIP;
            vehicleKind = VEHICLE_SHIP;
        }
    }

    @Override
    public synchronized CurrentGameStateSnapshot currentSnapshot() {
        return snapshot();
    }

    private CurrentGameStateSnapshot snapshot() {
        BodyContext body = currentBodyContext().orElse(null);
        Integer biologicalSignals =
                body == null ? null : body.biologicalSignalCount();
        return new CurrentGameStateSnapshot(
                commanderFid,
                shipId,
                shipType,
                shipName,
                loadoutHash,
                systemAddress,
                systemName,
                bodyId,
                firstNonBlank(
                        bodyName,
                        body == null ? null : body.bodyName()
                ),
                firstNonBlank(broadBodyType, null),
                body == null ? null : body.planetClass(),
                body == null ? null : body.starType(),
                commanderMode,
                flightMode,
                vehicleKind,
                activeVehicleId,
                biologicalSignals,
                body == null ? null : body.geologicalSignalCount(),
                body == null ? null : body.landable(),
                body == null ? null : body.wasDiscovered(),
                body == null ? null : body.wasMapped(),
                body == null ? null : body.wasFootfalled(),
                body == null ? null : body.distanceFromArrivalLs(),
                biologicalSignals == null
                        ? null
                        : biologicalSignals > 0,
                activeOrganicSampling,
                samplingProcess
        );
    }

    /**
     * Captures the technical context of the journal fact itself without
     * incorrectly changing the commander's physical current-body selection.
     */
    private CurrentGameStateSnapshot snapshotFor(
            JournalEventObservation event,
            CurrentGameStateSnapshot base
    ) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(base, "base");
        if (!(event instanceof FSSBodySignals)
                && !(event instanceof SAASignalsFound)
                && !(event instanceof LeaveBody)) {
            return base;
        }
        JsonNode raw = event.raw().parsedJsonObject();
        Optional<Long> address = positiveOrZeroLong(raw, "SystemAddress");
        Optional<Long> id = positiveOrZeroLong(raw, "BodyID");
        if (address.isEmpty() || id.isEmpty()) {
            return base;
        }

        BodyContext body = bodies.get(new BodyIdentity(
                address.orElseThrow(),
                id.orElseThrow()
        ));
        Integer biologicalSignals =
                body == null ? null : body.biologicalSignalCount();
        return new CurrentGameStateSnapshot(
                base.commanderFid(),
                base.shipId(),
                base.shipType(),
                base.shipName(),
                base.loadoutHash(),
                address.orElseThrow(),
                base.systemName(),
                id.orElseThrow(),
                textual(raw, "BodyName")
                        .or(() -> textual(raw, "Body"))
                        .orElse(body == null ? null : body.bodyName()),
                body == null ? null : body.bodyType(),
                body == null ? null : body.planetClass(),
                body == null ? null : body.starType(),
                base.commanderMode(),
                base.flightMode(),
                base.vehicleKind(),
                base.activeVehicleId(),
                biologicalSignals,
                body == null ? null : body.geologicalSignalCount(),
                body == null ? null : body.landable(),
                body == null ? null : body.wasDiscovered(),
                body == null ? null : body.wasMapped(),
                body == null ? null : body.wasFootfalled(),
                body == null ? null : body.distanceFromArrivalLs(),
                biologicalSignals == null
                        ? null
                        : biologicalSignals > 0,
                base.activeOrganicSampling(),
                base.samplingProcess()
        );
    }

    private void updateIdentity(
            JournalEventObservation event,
            JsonNode raw
    ) {
        if (event instanceof Commander) {
            textual(raw, "FID").ifPresent(this::setCommanderFid);
            return;
        }
        if (event instanceof LoadGame) {
            textual(raw, "FID").ifPresent(this::setCommanderFid);
            updateShipIdentity(raw);
            return;
        }
        if (event instanceof Loadout) {
            updateShipIdentity(raw);
            JsonNode modules = raw.get("Modules");
            if (modules != null && modules.isArray()) {
                loadoutHash = LoadoutHasher.hash(raw.get("Ship"), modules);
            }
        }
    }

    private void setCommanderFid(String value) {
        if (commanderFid != null && !commanderFid.equals(value)) {
            shipId = null;
            shipType = null;
            shipName = null;
            loadoutHash = null;
            systemAddress = null;
            systemName = null;
            clearSelectedBody();
            bodies.clear();
            vehicleKindsById.clear();
            activeVehicleId = null;
            activeVehicleFromAmbiguousLaunch = false;
            commanderMode = CommanderLocationMode.UNKNOWN;
            flightMode = FlightMode.UNKNOWN;
            vehicleKind = VEHICLE_UNKNOWN;
            activeOrganicSampling = null;
            samplingProcess = null;
        }
        commanderFid = value;
    }

    private void updateShipIdentity(JsonNode raw) {
        positiveLong(raw, "ShipID").ifPresent(value -> {
            if (shipId != null && !shipId.equals(value)) {
                activeVehicleId = null;
                activeVehicleFromAmbiguousLaunch = false;
                vehicleKind = VEHICLE_UNKNOWN;
                commanderMode = CommanderLocationMode.UNKNOWN;
                loadoutHash = null;
            }
            shipId = value;
        });
        textual(raw, "Ship").ifPresent(value -> shipType = value);
        textual(raw, "ShipName").ifPresent(value -> shipName = value);
    }

    private void enterSystem(JsonNode raw) {
        // The system is updated by selectBody, which reads the same record and
        // needs the previous one to tell whether the body actually changed.
        selectBody(raw);
        commanderMode = CommanderLocationMode.SHIP;
        vehicleKind = VEHICLE_SHIP;
        activeVehicleId = null;
        activeVehicleFromAmbiguousLaunch = false;
    }

    private void updateLocation(JsonNode raw) {
        selectBody(raw);
        if (booleanValue(raw, "OnFoot").orElse(false)) {
            commanderMode = CommanderLocationMode.ON_FOOT;
            vehicleKind = VEHICLE_UNKNOWN;
        } else if (booleanValue(raw, "InSRV").orElse(false)) {
            commanderMode = CommanderLocationMode.SRV;
            vehicleKind = VEHICLE_SRV;
        } else {
            commanderMode = CommanderLocationMode.SHIP;
            vehicleKind = VEHICLE_SHIP;
        }
        flightMode = booleanValue(raw, "Docked").orElse(false)
                ? FlightMode.DOCKED
                : FlightMode.NORMAL_SPACE;
    }

    private void updateSystem(JsonNode raw) {
        positiveOrZeroLong(raw, "SystemAddress")
                .ifPresent(value -> systemAddress = value);
        textual(raw, "StarSystem").ifPresent(value -> systemName = value);
        textual(raw, "SystemName").ifPresent(value -> systemName = value);
    }

    /**
     * Selects the body a record names as the Commander's current one.
     *
     * <p>The coarse type belongs to the body it was reported for. Only records
     * that select a body carry {@code BodyType} at all — a jump reports
     * {@code Star}, a supercruise exit reports {@code Planet} — and an approach
     * or a landing reports none, so holding the field until something overwrote
     * it left the previous body's type standing beside the new body's class and
     * signals. A star and a planet were described as one body.</p>
     *
     * <p>So a real change of body drops it: what is then known about the new
     * body is whatever was established for that body before, and nothing if
     * nothing was. Re-selecting the same body keeps what is known, because
     * approaching a body twice does not unlearn its type, and a record that
     * does report a type establishes it for the body it just named.</p>
     */
    private void selectBody(JsonNode raw) {
        Long previousSystemAddress = systemAddress;
        Long previousBodyId = bodyId;
        String previousBodyName = bodyName;
        updateSystem(raw);
        positiveOrZeroLong(raw, "BodyID").ifPresent(value -> bodyId = value);
        textual(raw, "Body").ifPresent(value -> bodyName = value);
        textual(raw, "BodyName").ifPresent(value -> bodyName = value);
        boolean differentBody =
                !Objects.equals(previousSystemAddress, systemAddress)
                        || !Objects.equals(previousBodyId, bodyId)
                        || !Objects.equals(previousBodyName, bodyName);
        if (differentBody) {
            broadBodyType = currentBodyContext()
                    .map(BodyContext::bodyType)
                    .orElse(null);
        }
        textual(raw, "BodyType").ifPresent(this::establishBodyType);
    }

    /**
     * Keeps the coarse type a record reported for the body it selected.
     *
     * <p>Kept per body so that returning to it later recovers what was already
     * established rather than starting from nothing. It deliberately does not
     * mark the registry as written for this observation: that marker says the
     * body facts this snapshot publishes came from the record rather than from
     * storage, and this record <em>is</em> the direct source of the type it
     * reports. Nothing else about the body is touched.</p>
     */
    private void establishBodyType(String reportedType) {
        broadBodyType = reportedType;
        if (systemAddress == null || bodyId == null) {
            return;
        }
        BodyIdentity key = new BodyIdentity(systemAddress, bodyId);
        BodyContext previous = bodies.get(key);
        bodies.put(key, new BodyContext(
                previous == null ? Map.of() : previous.signalCounts(),
                value(previous, BodyContext::landable),
                value(previous, BodyContext::wasDiscovered),
                value(previous, BodyContext::wasMapped),
                value(previous, BodyContext::wasFootfalled),
                value(previous, BodyContext::distanceFromArrivalLs),
                value(previous, BodyContext::bodyName),
                reportedType,
                value(previous, BodyContext::planetClass),
                value(previous, BodyContext::starType)
        ));
    }

    private void clearSelectedBody() {
        bodyId = null;
        bodyName = null;
        broadBodyType = null;
        activeOrganicSampling = false;
        samplingProcess = null;
    }

    private void updateBodyFromScan(JsonNode raw) {
        Optional<Long> address = positiveOrZeroLong(raw, "SystemAddress");
        Optional<Long> id = positiveOrZeroLong(raw, "BodyID");
        if (address.isEmpty() || id.isEmpty()) {
            return;
        }
        BodyIdentity key = new BodyIdentity(address.orElseThrow(), id.orElseThrow());
        BodyContext previous = bodies.get(key);
        BodyContext updated = new BodyContext(
                previous == null ? Map.of() : previous.signalCounts(),
                booleanValue(raw, "Landable")
                        .orElse(value(previous, BodyContext::landable)),
                booleanValue(raw, "WasDiscovered")
                        .orElse(value(previous, BodyContext::wasDiscovered)),
                booleanValue(raw, "WasMapped")
                        .orElse(value(previous, BodyContext::wasMapped)),
                booleanValue(raw, "WasFootfalled")
                        .orElse(value(previous, BodyContext::wasFootfalled)),
                finiteDouble(raw, "DistanceFromArrivalLS")
                        .orElse(value(
                                previous,
                                BodyContext::distanceFromArrivalLs
                        )),
                textual(raw, "BodyName")
                        .orElse(value(previous, BodyContext::bodyName)),
                textual(raw, "BodyType")
                        .orElse(value(previous, BodyContext::bodyType)),
                textual(raw, "PlanetClass")
                        .orElse(value(previous, BodyContext::planetClass)),
                textual(raw, "StarType")
                        .orElse(value(previous, BodyContext::starType))
        );
        bodies.put(key, updated);
        bodyRegistryWriteKey = key;
    }

    /**
     * Records everything a scanner reported about one body.
     *
     * <p>Every category is retained, not only the two the snapshot publishes.
     * A reading that reports nothing clears nothing: the game does not say a
     * signal is gone by omitting it, and treating silence as a retraction
     * would erase what an earlier scanner established. Neither does a category
     * reported at zero or below — {@link BodySurveyFacts#normalizedSignalCounts}
     * keeps only what a reading positively established, so what merges here is
     * additions and corrections upward and never a deletion.</p>
     *
     * <p>And nothing is invented. A category no reading has counted stays out
     * of the map, so it reads as unknown everywhere downstream: absence is how
     * this contract says "nobody has looked", and a zero would say "somebody
     * looked and there are none".</p>
     */
    private void updateBodySignals(JsonNode raw) {
        Optional<Long> address = positiveOrZeroLong(raw, "SystemAddress");
        Optional<Long> id = positiveOrZeroLong(raw, "BodyID");
        if (address.isEmpty() || id.isEmpty()) {
            return;
        }
        BodyIdentity key = new BodyIdentity(address.orElseThrow(), id.orElseThrow());
        BodyContext previous = bodies.get(key);
        Map<String, Integer> reported =
                BodySurveyFacts.normalizedSignalCounts(raw);
        Map<String, Integer> merged = new TreeMap<>(
                previous == null ? Map.of() : previous.signalCounts()
        );
        // Only what a reading positively established. A category the reading
        // never mentions, and a category it lists at zero, are the same thing:
        // nothing was established there. Writing a zero for either would turn
        // "nobody has counted" into "counted, and there are none" — a claim no
        // source made, and one the model cannot tell from a measurement.
        merged.putAll(reported);
        bodies.put(
                key,
                new BodyContext(
                        merged,
                        value(previous, BodyContext::landable),
                        value(previous, BodyContext::wasDiscovered),
                        value(previous, BodyContext::wasMapped),
                        value(previous, BodyContext::wasFootfalled),
                        value(previous, BodyContext::distanceFromArrivalLs),
                        textual(raw, "BodyName")
                                .orElse(value(
                                        previous,
                                        BodyContext::bodyName
                                )),
                        value(previous, BodyContext::bodyType),
                        value(previous, BodyContext::planetClass),
                        value(previous, BodyContext::starType)
                )
        );
        bodyRegistryWriteKey = key;
    }

    private void updateStartJump(JsonNode raw) {
        switch (textual(raw, "JumpType").orElse("")) {
            case "Hyperspace" -> flightMode = FlightMode.HYPERSPACE;
            case "Supercruise" -> flightMode = FlightMode.SUPERCRUISE;
            default -> flightMode = FlightMode.UNKNOWN;
        }
    }

    /**
     * A cargo snapshot narrowing an unknown vehicle, by two pieces of evidence
     * together.
     *
     * <p>Neither observation classifies anything alone. A {@code LaunchFighter}
     * that establishes no type says only that something went out; a cargo
     * snapshot tagged {@code SRV} says only whose hold is being described. Put
     * together — a vehicle launched through the fighter channel without naming
     * itself, and that same vehicle reporting its hold through the SRV channel
     * — they identify a Ship-Launched Vessel, because that is the lifecycle a
     * Nomad has in the current game: launched as a fighter, held as an SRV,
     * recovered by {@code DockSRV}. A conventional SRV names itself at launch
     * and never reaches this rule.</p>
     *
     * <p>Without that launch behind it, the tag means what it always meant. An
     * unknown class with no ambiguous launch is narrowed to {@code SRV} exactly
     * as before: it is the tag's own word for itself, and inventing an SLV from
     * it would classify every arbitrary cargo snapshot as the rarer thing.</p>
     *
     * <p>It is not enough for anything more. The tag says whose hold this is,
     * not who is sitting in it, so {@code commanderMode} is untouched — being
     * aboard is established by {@code Disembark} and {@code Embark}, which say
     * so directly. No vehicle id is invented either: the snapshot names no
     * vessel instance.</p>
     *
     * <p>Supporting evidence never overwrites a stronger fact. A kind that is
     * already concrete stays, whatever a later cargo snapshot is tagged with:
     * a snapshot arriving while a vehicle is out is not a vehicle switch, and
     * treating it as one would let an incidental inventory event rewrite what
     * a deployment event established.</p>
     */
    private void refineVehicleKindFromCargo(Cargo cargo) {
        if (!VEHICLE_UNKNOWN.equals(vehicleKind)
                || !cargo.optionalSrvVessel().orElse(false)) {
            return;
        }
        vehicleKind = activeVehicleId != null && activeVehicleFromAmbiguousLaunch
                ? VEHICLE_SLV
                : VEHICLE_SRV;
        if (activeVehicleId != null) {
            vehicleKindsById.put(activeVehicleId, vehicleKind);
        }
        // The class is settled now, so the launch that left it open is spent.
        activeVehicleFromAmbiguousLaunch = false;
    }

    /**
     * A vehicle going out, through whichever channel the journal used.
     *
     * @param ambiguousChannel whether this was the fighter channel, which has
     *                         been observed carrying vessels that are not
     *                         fighters. When it leaves the class unknown, the
     *                         launch is remembered as ambiguous so that the
     *                         next piece of evidence can finish the job; see
     *                         {@link #refineVehicleKindFromCargo}.
     */
    private void updateVehicleLaunch(
            JsonNode raw,
            String defaultKind,
            boolean ambiguousChannel
    ) {
        activeVehicleId = positiveOrZeroLong(raw, "ID").orElse(null);
        String knownKind = activeVehicleId == null
                ? null
                : vehicleKindsById.get(activeVehicleId);
        String reportedKind = vehicleKind(raw).orElse(defaultKind);
        vehicleKind = knownKind != null ? knownKind : reportedKind;
        activeVehicleFromAmbiguousLaunch = ambiguousChannel
                && activeVehicleId != null
                && VEHICLE_UNKNOWN.equals(vehicleKind);
        if (activeVehicleId != null && !VEHICLE_UNKNOWN.equals(vehicleKind)) {
            vehicleKindsById.put(activeVehicleId, vehicleKind);
        }
        if (booleanValue(raw, "PlayerControlled").orElse(false)) {
            commanderMode = aboardMode(vehicleKind, commanderMode);
        }
    }

    private void updateDisembark(JsonNode raw) {
        activeVehicleId = positiveOrZeroLong(raw, "ID")
                .orElse(activeVehicleId);
        if (booleanValue(raw, "SRV").orElse(false)) {
            vehicleKind = boardedVehicleKind();
            activeVehicleFromAmbiguousLaunch = false;
        }
        commanderMode = CommanderLocationMode.ON_FOOT;
    }

    private void updateEmbark(JsonNode raw) {
        activeVehicleId = positiveOrZeroLong(raw, "ID")
                .orElse(activeVehicleId);
        if (booleanValue(raw, "SRV").orElse(false)) {
            vehicleKind = boardedVehicleKind();
            activeVehicleFromAmbiguousLaunch = false;
            commanderMode = aboardMode(
                    vehicleKind,
                    CommanderLocationMode.SRV
            );
        } else {
            commanderMode = CommanderLocationMode.SHIP;
            vehicleKind = VEHICLE_SHIP;
            activeVehicleFromAmbiguousLaunch = false;
        }
    }

    /**
     * Which vehicle the {@code SRV=true} flag is about.
     *
     * <p>The flag names the event form, not the vessel: a Ship-Launched Vessel
     * is boarded and left through the same records an SRV is. So the runtime
     * identity decides, and only an identity Kairon knows nothing about falls
     * back to the flag's own word — an established class is never downgraded by
     * a later event that merely used the SRV channel.</p>
     */
    private String boardedVehicleKind() {
        String known = activeVehicleId == null
                ? null
                : vehicleKindsById.get(activeVehicleId);
        return known == null ? VEHICLE_SRV : known;
    }

    /**
     * Being aboard, said in the same words as the vehicle.
     *
     * <p>Sitting in a Ship-Launched Vessel is not sitting in an SRV, and the
     * presence field is the only place the model learns which. A class that
     * establishes nothing leaves presence as it was rather than guessing.</p>
     */
    private static CommanderLocationMode aboardMode(
            String kind,
            CommanderLocationMode unchanged
    ) {
        if (VEHICLE_SLV.equals(kind)) {
            return CommanderLocationMode.SLV;
        }
        return VEHICLE_SRV.equals(kind)
                ? CommanderLocationMode.SRV
                : unchanged;
    }

    // ---------------------------------------------------------------------
    // Organic sampling lifecycle, whole
    // ---------------------------------------------------------------------

    /**
     * {@code Log} starts a sequence, {@code Sample} advances it, {@code Analyse}
     * ends it.
     *
     * <p>Any other {@code ScanType}, including an absent one, leaves the
     * process exactly as it was. The repository establishes no transition for
     * it, and guessing one would invent a stage.</p>
     */
    private void updateOrganicSampling(JsonNode raw) {
        positiveOrZeroLong(raw, "SystemAddress")
                .ifPresent(value -> systemAddress = value);
        positiveOrZeroLong(raw, "Body").ifPresent(value -> bodyId = value);
        switch (textual(raw, "ScanType").orElse("")) {
            case "Log" -> advanceSampling(
                    raw,
                    BiologicalSamplingStage.START
            );
            case "Sample" -> advanceSampling(
                    raw,
                    BiologicalSamplingStage.PROGRESS
            );
            case "Analyse" -> endSampling();
            default -> {
            }
        }
    }

    private void advanceSampling(
            JsonNode raw,
            BiologicalSamplingStage stage
    ) {
        activeOrganicSampling = true;
        BiologicalSamplingProcess observed = observedSampling(raw, stage);
        if (observed == null) {
            // The body could not be identified, so which sequence is active is
            // unestablished. Nothing is asserted either way.
            return;
        }
        samplingProcess = samplingProcess == null
                ? observed
                : samplingProcess.continuedAs(observed);
    }

    /**
     * The sequence this observation describes, or {@code null} when the body it
     * belongs to cannot be identified.
     */
    private BiologicalSamplingProcess observedSampling(
            JsonNode raw,
            BiologicalSamplingStage stage
    ) {
        if (systemAddress == null || bodyId == null) {
            return null;
        }
        return new BiologicalSamplingProcess(
                systemAddress,
                bodyId,
                taxon(raw, "Genus"),
                taxon(raw, "Species"),
                taxon(raw, "Variant"),
                stage
        );
    }

    private static TaxonName taxon(JsonNode raw, String field) {
        return TaxonName.of(
                textual(raw, field).orElse(null),
                textual(raw, field + "_Localised").orElse(null)
        );
    }

    /** The sequence is over: it is no longer active and no longer identified. */
    private void endSampling() {
        activeOrganicSampling = false;
        samplingProcess = null;
    }

    /**
     * A vehicle coming back aboard.
     *
     * <p>The recovery record is the one place the journal states the vessel's
     * own type, so it is also where an ambiguous lifecycle is finally settled:
     * a Nomad recovered here is recorded against its runtime id as an
     * {@code SLV}, and any other named vessel as an {@code SRV}. What the
     * Commander is now in is the ship, which is a different question and
     * answered separately.</p>
     */
    private void updateVehicleDock(JsonNode raw) {
        Long vehicleId = positiveOrZeroLong(raw, "ID").orElse(null);
        String knownKind = vehicleId == null
                ? null
                : vehicleKindsById.get(vehicleId);
        String confirmedKind = vehicleKind(raw).orElse(
                knownKind == null ? VEHICLE_SRV : knownKind
        );
        if (vehicleId != null) {
            vehicleKindsById.put(vehicleId, confirmedKind);
        }
        if (Objects.equals(activeVehicleId, vehicleId)) {
            activeVehicleId = null;
        }
        activeVehicleFromAmbiguousLaunch = false;
        vehicleKind = VEHICLE_SHIP;
        commanderMode = CommanderLocationMode.SHIP;
    }

    private Optional<BodyContext> currentBodyContext() {
        if (systemAddress == null || bodyId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(
                bodies.get(new BodyIdentity(systemAddress, bodyId))
        );
    }

    /**
     * The class a record's own type fields establish, if they establish one.
     *
     * <p>Both fields are read through {@link AuxiliaryVehicleTypes}, which is
     * the single place that knows a Nomad is a Ship-Launched Vessel rather than
     * an SRV. The model this returns is not kept here: the class is what
     * canonical state is about, and the model belongs to the event that named
     * it.</p>
     */
    private static Optional<String> vehicleKind(JsonNode raw) {
        AuxiliaryVehicleTypes.Classification classification =
                AuxiliaryVehicleTypes.classify(
                        textual(raw, "SRVType").orElse(null),
                        textual(raw, "SRVType_Localised").orElse(null)
                );
        return Optional.ofNullable(
                classification == null ? null : classification.kind()
        );
    }

    private static Optional<String> textual(JsonNode raw, String name) {
        JsonNode value = raw.get(name);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value.textValue());
    }

    private static Optional<Boolean> booleanValue(JsonNode raw, String name) {
        JsonNode value = raw.get(name);
        return value != null && value.isBoolean()
                ? Optional.of(value.booleanValue())
                : Optional.empty();
    }

    private static Optional<Long> positiveLong(JsonNode raw, String name) {
        return positiveOrZeroLong(raw, name).filter(value -> value > 0);
    }

    private static Optional<Long> positiveOrZeroLong(
            JsonNode raw,
            String name
    ) {
        JsonNode value = raw.get(name);
        return value != null
                && value.isIntegralNumber()
                && value.canConvertToLong()
                && value.longValue() >= 0
                ? Optional.of(value.longValue())
                : Optional.empty();
    }

    private static Optional<Integer> nonNegativeInt(
            JsonNode raw,
            String name
    ) {
        JsonNode value = raw.get(name);
        return value != null
                && value.isIntegralNumber()
                && value.canConvertToInt()
                && value.intValue() >= 0
                ? Optional.of(value.intValue())
                : Optional.empty();
    }

    private static Optional<Double> finiteDouble(
            JsonNode raw,
            String name
    ) {
        JsonNode value = raw.get(name);
        if (value == null || !value.isNumber()) {
            return Optional.empty();
        }
        double number = value.doubleValue();
        return Double.isFinite(number) && number >= 0.0
                ? Optional.of(number)
                : Optional.empty();
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private static <T> T value(
            BodyContext context,
            java.util.function.Function<BodyContext, T> getter
    ) {
        return context == null ? null : getter.apply(context);
    }

}

final class LoadoutHasher {

    private static final byte[] DOMAIN =
            "kairon-loadout-v1\0".getBytes(StandardCharsets.UTF_8);

    private LoadoutHasher() {
    }

    static String hash(JsonNode ship, JsonNode modules) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is required by Java",
                    exception
            );
        }
        digest.update(DOMAIN);
        update(digest, ship);
        update(digest, modules);
        return "lo1-" + Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(digest.digest());
    }

    private static void update(MessageDigest digest, JsonNode value) {
        if (value == null || value.isNull()) {
            byteValue(digest, 0);
        } else if (value.isObject()) {
            byteValue(digest, 1);
            List<Map.Entry<String, JsonNode>> fields = new ArrayList<>();
            fields.addAll(value.properties());
            fields.sort(Comparator.comparing(Map.Entry::getKey));
            for (Map.Entry<String, JsonNode> field : fields) {
                stringValue(digest, field.getKey());
                update(digest, field.getValue());
            }
        } else if (value.isArray()) {
            byteValue(digest, 2);
            for (JsonNode element : value) {
                update(digest, element);
            }
        } else {
            byteValue(digest, 3);
            stringValue(digest, value.toString());
        }
    }

    private static void stringValue(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(new byte[]{
                (byte) (bytes.length >>> 24),
                (byte) (bytes.length >>> 16),
                (byte) (bytes.length >>> 8),
                (byte) bytes.length
        });
        digest.update(bytes);
    }

    private static void byteValue(MessageDigest digest, int value) {
        digest.update((byte) value);
    }
}
