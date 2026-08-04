package kairon.semantics;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.event.exploration.CodexEntry;
import kairon.observation.journal.event.exploration.FSSBodySignals;
import kairon.observation.journal.event.exploration.SAASignalsFound;
import kairon.observation.journal.event.exploration.Scan;
import kairon.observation.journal.event.exploration.ScanOrganic;
import kairon.observation.journal.event.mission.MissionAbandoned;
import kairon.observation.journal.event.mission.MissionAccepted;
import kairon.observation.journal.event.mission.MissionCompleted;
import kairon.observation.journal.event.mission.MissionFailed;
import kairon.observation.journal.event.session.Commander;
import kairon.observation.journal.event.ship.DockSRV;
import kairon.observation.journal.event.ship.LaunchFighter;
import kairon.observation.journal.event.ship.LaunchSRV;
import kairon.observation.journal.event.social.ReceiveText;
import kairon.observation.journal.event.trade.MarketBuy;
import kairon.observation.journal.event.trade.MarketSell;
import kairon.observation.journal.event.travel.ApproachBody;
import kairon.observation.journal.event.travel.Disembark;
import kairon.observation.journal.event.travel.Docked;
import kairon.observation.journal.event.travel.DockingCancelled;
import kairon.observation.journal.event.travel.DockingDenied;
import kairon.observation.journal.event.travel.DockingGranted;
import kairon.observation.journal.event.travel.DockingRequested;
import kairon.observation.journal.event.travel.DockingTimeout;
import kairon.observation.journal.event.travel.Embark;
import kairon.observation.journal.event.travel.FSDJump;
import kairon.observation.journal.event.travel.FSDTarget;
import kairon.observation.journal.event.travel.LeaveBody;
import kairon.observation.journal.event.travel.Liftoff;
import kairon.observation.journal.event.travel.Location;
import kairon.observation.journal.event.travel.SupercruiseEntry;
import kairon.observation.journal.event.travel.SupercruiseExit;
import kairon.observation.journal.event.travel.Touchdown;
import kairon.observation.journal.event.travel.Undocked;
import kairon.semantics.SemanticFact.AssertionSource;
import kairon.semantics.SemanticFact.EntityKind;
import kairon.semantics.SemanticFact.EntityRef;
import kairon.semantics.SemanticFact.ProcessStage;

import java.util.List;

import static kairon.semantics.SemanticAdapterSupport.bodyRef;
import static kairon.semantics.SemanticAdapterSupport.fact;
import static kairon.semantics.SemanticAdapterSupport.fields;
import static kairon.semantics.SemanticAdapterSupport.negationOf;
import static kairon.semantics.SemanticAdapterSupport.vesselContextGaps;
import static kairon.semantics.SemanticAdapterSupport.withGap;

/**
 * Mechanism-oriented semantic adapters for journal events.
 *
 * <p>Each adapter describes what a class of events <em>means</em> — subject,
 * operation, identity, quantity, stage, completion, negation — rather than how
 * one replay fixture should render. None of them reads
 * {@code llmPresentation()}.</p>
 *
 * <p>Coverage is partial and deliberately honest: see the Phase B report in
 * {@code target/audit/kairon-llm-situation-v2-phase-b-report.md}. Unregistered
 * types resolve to an explicit {@code NO_SEMANTIC_ADAPTER} gap, never an
 * exception.</p>
 */
final class JournalSemanticAdapters {

    static final SemanticAdapterRegistry REGISTRY = build();

    private JournalSemanticAdapters() {
    }

    private static SemanticAdapterRegistry build() {
        SemanticAdapterRegistry.Builder builder =
                SemanticAdapterRegistry.builder();

        // --- Commander identity ------------------------------------------
        builder.register(Commander.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    fact(SemanticSubject.COMMANDER,
                            SemanticOperation.IDENTIFIED, provenance)
                            .identity(raw.identity("FID", "FID"))
                            .qualifier("name", raw.textValue("Name"))
                            .build()
            );
        });

        // --- System transition -------------------------------------------
        builder.register(FSDJump.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    fact(SemanticSubject.CURRENT_SYSTEM,
                            SemanticOperation.ENTERED, provenance)
                            .actor(SemanticSubject.COMMANDER)
                            .object(new EntityRef(
                                    EntityKind.SYSTEM,
                                    raw.identity(
                                            "SystemAddress",
                                            "SystemAddress"
                                    ),
                                    raw.text("StarSystem").orElse(null)
                            ))
                            .identity(raw.identity(
                                    "SystemAddress",
                                    "SystemAddress"
                            ))
                            .quantity(raw.quantity(
                                    "JumpDist",
                                    "LIGHT_YEARS"
                            ))
                            .qualifier("fuelUsed", raw.quantity(
                                    "FuelUsed",
                                    "TONNES"
                            ))
                            // Names the proposition the negation is about.
                            // Without it the fact reads "completed, negated"
                            // with no identifiable target.
                            .qualifier("boostUsed", raw.booleanValue(
                                    "BoostUsed"
                            ))
                            .processStage(ProcessStage.FINAL)
                            .completion(Boolean.TRUE)
                            .negation(negationOf(raw, "BoostUsed"))
                            .build()
            );
        });

        builder.register(SupercruiseEntry.class, (event, provenance) ->
                SemanticEventAdapter.Result.of(
                        navigationFact(
                                event,
                                provenance,
                                SemanticOperation.ENTERED
                        )
                ));

        builder.register(SupercruiseExit.class, (event, provenance) -> {
            RawFields raw = fields(event);
            List<UnresolvedFact> gaps = vesselContextGaps(raw, provenance);
            return SemanticEventAdapter.Result.of(
                    List.of(fact(SemanticSubject.NAVIGATION_CONTEXT,
                            SemanticOperation.EXITED, provenance)
                            .actor(SemanticSubject.COMMANDER)
                            .object(bodyRef(raw))
                            .identity(raw.identity("BodyID", "BodyID"))
                            .qualifier("system", raw.textValue("StarSystem"))
                            .qualifier("bodyType", raw.symbol("BodyType"))
                            .processStage(ProcessStage.FINAL)
                            .completion(Boolean.TRUE)
                            .build()),
                    gaps
            );
        });

        builder.register(FSDTarget.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    fact(SemanticSubject.NAVIGATION_CONTEXT,
                            SemanticOperation.TARGETED, provenance)
                            .actor(SemanticSubject.COMMANDER)
                            .object(new EntityRef(
                                    EntityKind.SYSTEM,
                                    raw.identity(
                                            "SystemAddress",
                                            "SystemAddress"
                                    ),
                                    raw.text("Name").orElse(null)
                            ))
                            .qualifier(
                                    "remainingJumpsInRoute",
                                    raw.integral("RemainingJumpsInRoute")
                            )
                            .processStage(ProcessStage.START)
                            .build()
            );
        });

        builder.register(Location.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    List.of(fact(SemanticSubject.CURRENT_SYSTEM,
                            SemanticOperation.ARRIVED, provenance)
                            .actor(SemanticSubject.COMMANDER)
                            .object(new EntityRef(
                                    EntityKind.SYSTEM,
                                    raw.identity(
                                            "SystemAddress",
                                            "SystemAddress"
                                    ),
                                    raw.text("StarSystem").orElse(null)
                            ))
                            .qualifier("docked", raw.booleanValue("Docked"))
                            .qualifier("onFoot", raw.booleanValue("OnFoot"))
                            .qualifier("inSrv", raw.booleanValue("InSRV"))
                            .assertionSource(AssertionSource.REPORTED)
                            .build()),
                    vesselContextGaps(raw, provenance)
            );
        });

        // --- Body approach and departure ---------------------------------
        builder.register(ApproachBody.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    fact(SemanticSubject.CURRENT_BODY,
                            SemanticOperation.APPROACHED, provenance)
                            .actor(SemanticSubject.COMMANDER)
                            .object(bodyRef(raw))
                            .identity(raw.identity("BodyID", "BodyID"))
                            .qualifier("system", raw.textValue("StarSystem"))
                            .processStage(ProcessStage.FINAL)
                            .completion(Boolean.TRUE)
                            .build()
            );
        });

        builder.register(LeaveBody.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    fact(SemanticSubject.CURRENT_BODY,
                            SemanticOperation.LEFT, provenance)
                            .actor(SemanticSubject.COMMANDER)
                            .object(bodyRef(raw))
                            .identity(raw.identity("BodyID", "BodyID"))
                            .qualifier("system", raw.textValue("StarSystem"))
                            .processStage(ProcessStage.FINAL)
                            .completion(Boolean.TRUE)
                            .relationship("reverses ApproachBody")
                            .build()
            );
        });

        // --- Surface -------------------------------------------------------
        builder.register(Touchdown.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    fact(SemanticSubject.NAVIGATION_CONTEXT,
                            SemanticOperation.LANDED, provenance)
                            .actor(SemanticSubject.COMMANDER)
                            .object(bodyRef(raw))
                            .identity(raw.identity("BodyID", "BodyID"))
                            .qualifier("position", raw.coordinates(
                                    "Latitude",
                                    "Longitude"
                            ))
                            .qualifier(
                                    "playerControlled",
                                    raw.booleanValue("PlayerControlled")
                            )
                            .processStage(ProcessStage.FINAL)
                            .completion(Boolean.TRUE)
                            .build()
            );
        });

        builder.register(Liftoff.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    fact(SemanticSubject.NAVIGATION_CONTEXT,
                            SemanticOperation.LIFTED_OFF, provenance)
                            .actor(SemanticSubject.COMMANDER)
                            .object(bodyRef(raw))
                            .identity(raw.identity("BodyID", "BodyID"))
                            .qualifier("position", raw.coordinates(
                                    "Latitude",
                                    "Longitude"
                            ))
                            .qualifier(
                                    "playerControlled",
                                    raw.booleanValue("PlayerControlled")
                            )
                            .processStage(ProcessStage.FINAL)
                            .completion(Boolean.TRUE)
                            .build()
            );
        });

        // --- Docking, with polarity carried structurally -------------------
        builder.register(Docked.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    List.of(dockingFact(
                            raw,
                            provenance,
                            SemanticOperation.DOCKED,
                            ProcessStage.FINAL,
                            Boolean.TRUE,
                            null
                    )),
                    vesselContextGaps(raw, provenance)
            );
        });

        builder.register(Undocked.class, (event, provenance) ->
                SemanticEventAdapter.Result.of(dockingFactBuilder(
                        fields(event),
                        provenance,
                        SemanticOperation.UNDOCKED,
                        ProcessStage.FINAL,
                        Boolean.TRUE,
                        null
                )
                        .relationship("reverses Docked")
                        .build()));

        builder.register(DockingRequested.class, (event, provenance) ->
                SemanticEventAdapter.Result.of(dockingFact(
                        fields(event),
                        provenance,
                        SemanticOperation.DOCKING_REQUESTED,
                        ProcessStage.START,
                        null,
                        null
                )));

        builder.register(DockingGranted.class, (event, provenance) ->
                SemanticEventAdapter.Result.of(dockingFact(
                        fields(event),
                        provenance,
                        SemanticOperation.DOCKING_GRANTED,
                        ProcessStage.PROGRESS,
                        null,
                        Boolean.FALSE
                )));

        builder.register(DockingDenied.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    dockingFactBuilder(
                            raw,
                            provenance,
                            SemanticOperation.DOCKING_DENIED,
                            ProcessStage.FINAL,
                            Boolean.FALSE,
                            Boolean.TRUE
                    )
                            .qualifier("reason", raw.symbol("Reason"))
                            .build()
            );
        });

        builder.register(DockingCancelled.class, (event, provenance) ->
                SemanticEventAdapter.Result.of(dockingFact(
                        fields(event),
                        provenance,
                        SemanticOperation.DOCKING_CANCELLED,
                        ProcessStage.FINAL,
                        Boolean.FALSE,
                        Boolean.TRUE
                )));

        builder.register(DockingTimeout.class, (event, provenance) ->
                SemanticEventAdapter.Result.of(dockingFact(
                        fields(event),
                        provenance,
                        SemanticOperation.DOCKING_TIMED_OUT,
                        ProcessStage.FINAL,
                        Boolean.FALSE,
                        Boolean.TRUE
                )));

        // --- Presence transfer ---------------------------------------------
        builder.register(Disembark.class, (event, provenance) ->
                presenceTransfer(
                        event,
                        provenance,
                        SemanticOperation.DISEMBARKED
                ));

        builder.register(Embark.class, (event, provenance) ->
                presenceTransfer(
                        event,
                        provenance,
                        SemanticOperation.BOARDED
                ));

        // --- Auxiliary vehicles --------------------------------------------
        builder.register(LaunchSRV.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    List.of(fact(SemanticSubject.ASSOCIATED_VEHICLE,
                            SemanticOperation.LAUNCHED, provenance)
                            .actor(SemanticSubject.COMMANDER)
                            .object(new EntityRef(
                                    EntityKind.AUXILIARY_VEHICLE,
                                    raw.identity("SRVID", "ID"),
                                    raw.text("SRVType_Localised")
                                            .or(() -> raw.text("SRVType"))
                                            .orElse(null)
                            ))
                            .identity(raw.identity("SRVID", "ID"))
                            .qualifier(
                                    "playerControlled",
                                    raw.booleanValue("PlayerControlled")
                            )
                            .processStage(ProcessStage.START)
                            .build()),
                    List.of(new UnresolvedFact(
                            SemanticSubject.OCCUPIED_VEHICLE,
                            UnresolvedFact.Reason
                                    .VEHICLE_OCCUPANCY_NOT_ESTABLISHED,
                            provenance
                    ))
            );
        });

        builder.register(LaunchFighter.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    List.of(fact(SemanticSubject.ASSOCIATED_VEHICLE,
                            SemanticOperation.LAUNCHED, provenance)
                            .actor(SemanticSubject.COMMANDER)
                            .object(new EntityRef(
                                    EntityKind.AUXILIARY_VEHICLE,
                                    raw.identity("FighterID", "ID"),
                                    raw.text("Loadout").orElse(null)
                            ))
                            .identity(raw.identity("FighterID", "ID"))
                            .qualifier(
                                    "playerControlled",
                                    raw.booleanValue("PlayerControlled")
                            )
                            .processStage(ProcessStage.START)
                            .build()),
                    List.of(new UnresolvedFact(
                            SemanticSubject.OCCUPIED_VEHICLE,
                            UnresolvedFact.Reason
                                    .FIGHTER_OCCUPANCY_NOT_ESTABLISHED,
                            provenance
                    ))
            );
        });

        // The one record that states the recovered vessel's own type, so the
        // one place a class and a model can both be established. They are two
        // facts and are carried as two: "Nomad" is which model came back, and
        // the class beside it is what sort of vessel that is. A single label
        // under one unqualified name is what let a Nomad be read as a ship.
        builder.register(DockSRV.class, (event, provenance) -> {
            RawFields raw = fields(event);
            AuxiliaryVehicleTypes.Classification recovered =
                    AuxiliaryVehicleTypes.classify(
                            raw.text("SRVType").orElse(null),
                            raw.text("SRVType_Localised").orElse(null)
                    );
            return SemanticEventAdapter.Result.of(
                    fact(SemanticSubject.ASSOCIATED_VEHICLE,
                            SemanticOperation.RECOVERED, provenance)
                            .actor(SemanticSubject.COMMANDER)
                            .object(new EntityRef(
                                    EntityKind.AUXILIARY_VEHICLE,
                                    raw.identity("SRVID", "ID"),
                                    null
                            ))
                            .identity(raw.identity("SRVID", "ID"))
                            .qualifier(
                                    "vehicleKind",
                                    recovered == null
                                            ? SemanticValue.unknown()
                                            : SemanticValue.ofSymbol(
                                                    recovered.kind()
                                            )
                            )
                            .qualifier(
                                    "vehicleType",
                                    recovered == null
                                            ? SemanticValue.unknown()
                                            : SemanticValue.ofText(
                                                    recovered.type()
                                            )
                            )
                            .processStage(ProcessStage.FINAL)
                            .completion(Boolean.TRUE)
                            .relationship("reverses LaunchSRV")
                            .build()
            );
        });

        // --- Exploration ----------------------------------------------------
        /*
         * What the scan established about the body, and only that.
         *
         * The record also carries mass, radius, temperature, orbital elements,
         * rings, bulk composition, atmospheric composition and material
         * percentages. None of them is here: no measured comment in either
         * replay referenced one, and a body scan that arrives as forty numbers
         * is a body scan the model has to summarise before it can decide
         * anything. The scan depth is a closed token because only one depth
         * reaches the model at all; the flags keep the same names the context
         * uses for them, so the event and what was already known read alike.
         */
        builder.register(Scan.class, (event, provenance) -> {
            RawFields raw = fields(event);
            JsonNode json = event.raw().parsedJsonObject();
            String bodyKind = BodySurveyFacts.bodyKind(json);
            boolean star = "STAR".equals(bodyKind);
            return SemanticEventAdapter.Result.of(
                    fact(SemanticSubject.CURRENT_BODY,
                            SemanticOperation.SCANNED, provenance)
                            .actor(SemanticSubject.COMMANDER)
                            .object(bodyRef(raw))
                            .identity(raw.identity("BodyID", "BodyID"))
                            .qualifier("system", raw.textValue("StarSystem"))
                            .qualifier(
                                    "scanType",
                                    SemanticValue.ofSymbol(
                                            BodySurveyFacts.scanDepth(json)
                                    )
                            )
                            .qualifier(
                                    "bodyType",
                                    SemanticValue.ofSymbol(bodyKind)
                            )
                            // Never both: the classification the scan supplied
                            // is what says which kind of body this is.
                            .qualifier(
                                    "planetClass",
                                    star
                                            ? SemanticValue.unknown()
                                            : raw.symbol("PlanetClass")
                            )
                            .qualifier(
                                    "starType",
                                    star
                                            ? raw.symbol("StarType")
                                            : SemanticValue.unknown()
                            )
                            .qualifier("landable", raw.booleanValue("Landable"))
                            .qualifier(
                                    "terraformState",
                                    raw.textValue("TerraformState")
                            )
                            .qualifier(
                                    "atmosphere",
                                    raw.displayText("Atmosphere")
                            )
                            .qualifier(
                                    "volcanism",
                                    raw.displayText("Volcanism")
                            )
                            .qualifier(
                                    "previouslyDiscovered",
                                    raw.booleanValue("WasDiscovered")
                            )
                            .qualifier(
                                    "previouslyMapped",
                                    raw.booleanValue("WasMapped")
                            )
                            .qualifier(
                                    "previouslyFootfalled",
                                    raw.booleanValue("WasFootfalled")
                            )
                            .qualifier(
                                    "distanceFromArrivalLs",
                                    raw.decimal("DistanceFromArrivalLS")
                            )
                            .processStage(ProcessStage.FINAL)
                            .completion(Boolean.TRUE)
                            .build()
            );
        });

        builder.register(FSSBodySignals.class, (event, provenance) ->
                SemanticEventAdapter.Result.of(signalSurveyFact(
                        fields(event),
                        event.raw().parsedJsonObject(),
                        provenance
                )));

        builder.register(SAASignalsFound.class, (event, provenance) ->
                SemanticEventAdapter.Result.of(signalSurveyFact(
                        fields(event),
                        event.raw().parsedJsonObject(),
                        provenance
                )));

        builder.register(CodexEntry.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    fact(SemanticSubject.CURRENT_BODY,
                            SemanticOperation.RECORDED, provenance)
                            .actor(SemanticSubject.COMMANDER)
                            .object(new EntityRef(
                                    EntityKind.CODEX_ENTRY,
                                    raw.identity("EntryID", "EntryID"),
                                    raw.text("Name_Localised")
                                            .or(() -> raw.text("Name"))
                                            .orElse(null)
                            ))
                            .identity(raw.identity("BodyID", "BodyID"))
                            .qualifier("category", raw.displayText("Category"))
                            .qualifier(
                                    "subCategory",
                                    raw.displayText("SubCategory")
                            )
                            .qualifier("region", raw.displayText("Region"))
                            .qualifier("system", raw.textValue("System"))
                            .qualifier("position", raw.coordinates(
                                    "Latitude",
                                    "Longitude"
                            ))
                            // Names the proposition the negation is about.
                            .qualifier("isNewEntry", raw.booleanValue(
                                    "IsNewEntry"
                            ))
                            .processStage(ProcessStage.FINAL)
                            .completion(Boolean.TRUE)
                            .negation(negationOf(raw, "IsNewEntry"))
                            .build()
            );
        });

        builder.register(ScanOrganic.class, (event, provenance) -> {
            RawFields raw = fields(event);
            String scanType = raw.text("ScanType").orElse("");
            ProcessStage stage = switch (scanType) {
                case "Log" -> ProcessStage.START;
                case "Sample" -> ProcessStage.PROGRESS;
                case "Analyse" -> ProcessStage.FINAL;
                default -> ProcessStage.NOT_APPLICABLE;
            };
            Boolean completion = switch (scanType) {
                case "Log", "Sample" -> Boolean.FALSE;
                case "Analyse" -> Boolean.TRUE;
                default -> null;
            };
            return SemanticEventAdapter.Result.of(
                    fact(SemanticSubject.BIOLOGICAL_SAMPLING_PROCESS,
                            SemanticOperation.SAMPLED, provenance)
                            .actor(SemanticSubject.COMMANDER)
                            .object(new EntityRef(
                                    EntityKind.ORGANIC,
                                    SemanticValue.unknown(),
                                    raw.text("Species_Localised")
                                            .or(() -> raw.text("Genus_Localised"))
                                            .orElse(null)
                            ))
                            .identity(raw.identity("BodyID", "Body"))
                            .qualifier("genus", raw.displayText("Genus"))
                            .qualifier("species", raw.displayText("Species"))
                            .qualifier("variant", raw.displayText("Variant"))
                            .qualifier("scanType", raw.symbol("ScanType"))
                            .processStage(stage)
                            .completion(completion)
                            .build()
            );
        });

        // --- Missions, polarity from class identity -------------------------
        builder.register(MissionAccepted.class, (event, provenance) ->
                SemanticEventAdapter.Result.of(missionFact(
                        fields(event),
                        provenance,
                        SemanticOperation.ACCEPTED,
                        ProcessStage.START,
                        null,
                        null
                )));

        builder.register(MissionCompleted.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    missionFactBuilder(
                            raw,
                            provenance,
                            SemanticOperation.COMPLETED,
                            ProcessStage.FINAL,
                            Boolean.TRUE,
                            Boolean.FALSE
                    )
                            .quantity(raw.quantity("Reward", "CREDITS"))
                            .build()
            );
        });

        builder.register(MissionFailed.class, (event, provenance) ->
                SemanticEventAdapter.Result.of(missionFact(
                        fields(event),
                        provenance,
                        SemanticOperation.FAILED,
                        ProcessStage.FINAL,
                        Boolean.FALSE,
                        Boolean.TRUE
                )));

        builder.register(MissionAbandoned.class, (event, provenance) ->
                SemanticEventAdapter.Result.of(missionFact(
                        fields(event),
                        provenance,
                        SemanticOperation.ABANDONED,
                        ProcessStage.FINAL,
                        Boolean.FALSE,
                        Boolean.TRUE
                )));

        // --- Market ---------------------------------------------------------
        builder.register(MarketBuy.class, (event, provenance) ->
                SemanticEventAdapter.Result.of(marketFact(
                        fields(event),
                        provenance,
                        SemanticOperation.BOUGHT
                )));

        builder.register(MarketSell.class, (event, provenance) ->
                SemanticEventAdapter.Result.of(marketFact(
                        fields(event),
                        provenance,
                        SemanticOperation.SOLD
                )));

        // --- Social ---------------------------------------------------------
        builder.register(ReceiveText.class, (event, provenance) -> {
            RawFields raw = fields(event);
            // Sender and channel are independent. A missing sender must never
            // be allowed to stand in for the channel, or the other way round.
            return SemanticEventAdapter.Result.of(
                    fact(SemanticSubject.COMMANDER,
                            SemanticOperation.RECEIVED, provenance)
                            .object(new EntityRef(
                                    EntityKind.MESSAGE,
                                    SemanticValue.unknown(),
                                    null
                            ))
                            .qualifier("sender", raw.displayText("From"))
                            .qualifier("channel", raw.symbol("Channel"))
                            .qualifier("message", raw.displayText("Message"))
                            .processStage(ProcessStage.FINAL)
                            .completion(Boolean.TRUE)
                            .build()
            );
        });

        CommerceSemanticAdapters.register(builder);
        ConflictSemanticAdapters.register(builder);
        ProgressionSemanticAdapters.register(builder);

        return builder.build();
    }


    // ---------------------------------------------------------------------
    // Mechanisms local to this group
    // ---------------------------------------------------------------------

    private static SemanticFact navigationFact(
            JournalEventObservation event,
            SemanticProvenance provenance,
            SemanticOperation operation
    ) {
        RawFields raw = fields(event);
        return fact(SemanticSubject.NAVIGATION_CONTEXT, operation, provenance)
                .actor(SemanticSubject.COMMANDER)
                .qualifier("system", raw.textValue("StarSystem"))
                .identity(raw.identity("SystemAddress", "SystemAddress"))
                .processStage(ProcessStage.FINAL)
                .completion(Boolean.TRUE)
                .build();
    }

    private static SemanticFact.Builder dockingFactBuilder(
            RawFields raw,
            SemanticProvenance provenance,
            SemanticOperation operation,
            ProcessStage stage,
            Boolean completion,
            Boolean negation
    ) {
        return fact(SemanticSubject.NAVIGATION_CONTEXT, operation, provenance)
                .actor(SemanticSubject.COMMANDER)
                .object(new EntityRef(
                        EntityKind.STATION,
                        raw.identity("MarketID", "MarketID"),
                        raw.text("StationName").orElse(null)
                ))
                .identity(raw.identity("MarketID", "MarketID"))
                .qualifier("stationType", raw.symbol("StationType"))
                .qualifier("system", raw.textValue("StarSystem"))
                .processStage(stage)
                .completion(completion)
                .negation(negation);
    }

    private static SemanticFact dockingFact(
            RawFields raw,
            SemanticProvenance provenance,
            SemanticOperation operation,
            ProcessStage stage,
            Boolean completion,
            Boolean negation
    ) {
        return dockingFactBuilder(
                raw,
                provenance,
                operation,
                stage,
                completion,
                negation
        ).build();
    }

    /**
     * What a scanner reported about one body, whichever scanner reported it.
     *
     * <p>The categories and counts travel as one value rather than as a field
     * per category: the game adds categories, and a reading is what it found,
     * not a fixed form with blanks.</p>
     */
    private static SemanticFact signalSurveyFact(
            RawFields raw,
            JsonNode json,
            SemanticProvenance provenance
    ) {
        return fact(SemanticSubject.CURRENT_BODY,
                SemanticOperation.SURVEYED, provenance)
                .actor(SemanticSubject.COMMANDER)
                .object(bodyRef(raw))
                .identity(raw.identity("BodyID", "BodyID"))
                .qualifier("system", raw.textValue("StarSystem"))
                .qualifier("signals", BodySurveyFacts.signals(json))
                .processStage(ProcessStage.FINAL)
                .completion(Boolean.TRUE)
                .build();
    }

    private static SemanticFact.Builder missionFactBuilder(
            RawFields raw,
            SemanticProvenance provenance,
            SemanticOperation operation,
            ProcessStage stage,
            Boolean completion,
            Boolean negation
    ) {
        return fact(SemanticSubject.COMMANDER, operation, provenance)
                .object(new EntityRef(
                        EntityKind.MISSION,
                        raw.identity("MissionID", "MissionID"),
                        raw.text("LocalisedName")
                                .or(() -> raw.text("Name"))
                                .orElse(null)
                ))
                .identity(raw.identity("MissionID", "MissionID"))
                .qualifier("faction", raw.textValue("Faction"))
                .processStage(stage)
                .completion(completion)
                .negation(negation);
    }

    private static SemanticFact missionFact(
            RawFields raw,
            SemanticProvenance provenance,
            SemanticOperation operation,
            ProcessStage stage,
            Boolean completion,
            Boolean negation
    ) {
        return missionFactBuilder(
                raw,
                provenance,
                operation,
                stage,
                completion,
                negation
        ).build();
    }

    private static SemanticFact marketFact(
            RawFields raw,
            SemanticProvenance provenance,
            SemanticOperation operation
    ) {
        return fact(SemanticSubject.COMMANDER, operation, provenance)
                .object(new EntityRef(
                        EntityKind.COMMODITY,
                        SemanticValue.unknown(),
                        raw.text("Type_Localised")
                                .or(() -> raw.text("Type"))
                                .orElse(null)
                ))
                .identity(raw.identity("MarketID", "MarketID"))
                .quantity(raw.quantity("Count", "UNITS"))
                .qualifier("unitPrice", raw.quantity("BuyPrice", "CREDITS"))
                .qualifier("sellPrice", raw.quantity("SellPrice", "CREDITS"))
                .qualifier("totalCost", raw.quantity("TotalCost", "CREDITS"))
                .qualifier("totalSale", raw.quantity("TotalSale", "CREDITS"))
                .processStage(ProcessStage.FINAL)
                .completion(Boolean.TRUE)
                .build();
    }

    /**
     * Presence transfer between the commander and some vessel.
     *
     * <p>The vessel identifier is only bound to a concrete entity kind when
     * the event says which kind it is. A taxi or a multicrew vessel is never
     * silently treated as the ship the commander owns.</p>
     */
    private static SemanticEventAdapter.Result presenceTransfer(
            JournalEventObservation event,
            SemanticProvenance provenance,
            SemanticOperation operation
    ) {
        RawFields raw = fields(event);
        boolean srv = raw.flag("SRV");
        boolean taxi = raw.flag("Taxi");
        boolean multicrew = raw.flag("Multicrew");

        EntityRef vessel;
        if (srv) {
            vessel = new EntityRef(
                    EntityKind.AUXILIARY_VEHICLE,
                    raw.identity("SRVID", "ID"),
                    null
            );
        } else if (taxi || multicrew) {
            vessel = new EntityRef(
                    EntityKind.UNRESOLVED,
                    SemanticValue.unknown(),
                    null
            );
        } else {
            vessel = new EntityRef(
                    EntityKind.SHIP,
                    raw.identity("ShipID", "ID"),
                    null
            );
        }

        List<UnresolvedFact> gaps = vesselContextGaps(raw, provenance);
        if (srv) {
            gaps = withGap(
                    gaps,
                    new UnresolvedFact(
                            SemanticSubject.OCCUPIED_VEHICLE,
                            UnresolvedFact.Reason
                                    .VEHICLE_OCCUPANCY_NOT_ESTABLISHED,
                            provenance
                    )
            );
        }
        if (taxi || multicrew) {
            gaps = withGap(
                    gaps,
                    new UnresolvedFact(
                            SemanticSubject.UNRESOLVED_SUBJECT,
                            UnresolvedFact.Reason
                                    .IDENTIFIER_KIND_NOT_ESTABLISHED,
                            provenance
                    )
            );
        }

        return SemanticEventAdapter.Result.of(
                List.of(fact(SemanticSubject.COMMANDER_PRESENCE,
                        operation, provenance)
                        .actor(SemanticSubject.COMMANDER)
                        .object(vessel)
                        .qualifier("system", raw.textValue("StarSystem"))
                        .qualifier("body", raw.textValue("Body"))
                        .qualifier("onStation", raw.booleanValue("OnStation"))
                        .qualifier("onPlanet", raw.booleanValue("OnPlanet"))
                        .processStage(ProcessStage.FINAL)
                        .completion(Boolean.TRUE)
                        .build()),
                gaps
        );
    }
}
