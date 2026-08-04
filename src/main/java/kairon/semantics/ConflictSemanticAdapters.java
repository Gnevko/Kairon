package kairon.semantics;

import kairon.observation.journal.event.combat.Bounty;
import kairon.observation.journal.event.combat.CockpitBreached;
import kairon.observation.journal.event.combat.CommitCrime;
import kairon.observation.journal.event.combat.Died;
import kairon.observation.journal.event.combat.EscapeInterdiction;
import kairon.observation.journal.event.combat.HeatDamage;
import kairon.observation.journal.event.combat.HullDamage;
import kairon.observation.journal.event.combat.Interdicted;
import kairon.observation.journal.event.combat.Interdiction;
import kairon.observation.journal.event.combat.PVPKill;
import kairon.observation.journal.event.combat.SelfDestruct;
import kairon.observation.journal.event.combat.SystemsShutdown;
import kairon.observation.journal.event.combat.UnderAttack;
import kairon.observation.journal.event.ship.FighterDestroyed;
import kairon.observation.journal.event.ship.SRVDestroyed;
import kairon.observation.journal.event.travel.JetConeBoost;
import kairon.observation.journal.event.travel.JetConeDamage;
import kairon.semantics.SemanticFact.EntityKind;
import kairon.semantics.SemanticFact.EntityRef;
import kairon.semantics.SemanticFact.ProcessStage;

import java.util.List;

import static kairon.semantics.SemanticAdapterSupport.fact;
import static kairon.semantics.SemanticAdapterSupport.fields;
import static kairon.semantics.SemanticAdapterSupport.shipOutcome;

/**
 * Threat, damage, destruction and crime mechanisms.
 *
 * <p>Outcome polarity lives in Java class identity today — {@code Interdicted}
 * versus {@code EscapeInterdiction} — so each adapter lifts it into an explicit
 * operation plus {@code completion} and {@code negation}.</p>
 *
 * <p>Hull health, heat and module damage are not projected into canonical
 * state, so the structured fact is the only place a measured amount can
 * survive.</p>
 */
final class ConflictSemanticAdapters {

    private ConflictSemanticAdapters() {
    }

    static void register(SemanticAdapterRegistry.Builder builder) {
        registerThreat(builder);
        registerDamage(builder);
        registerCrime(builder);
        registerAuxiliaryLoss(builder);
        registerTravelHazard(builder);
    }

    // ---------------------------------------------------------------------
    // Threat. Success is a reported boolean, never inferred.
    // ---------------------------------------------------------------------

    private static void registerThreat(
            SemanticAdapterRegistry.Builder builder
    ) {
        builder.register(Interdicted.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    threat(
                            raw,
                            provenance,
                            SemanticOperation.INTERDICTED,
                            "Interdictor"
                    )
                            .completion(reportedSuccess(raw))
                            .negation(negatedSuccess(raw))
                            .build()
            );
        });

        builder.register(Interdiction.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    threat(
                            raw,
                            provenance,
                            SemanticOperation.INTERDICTED,
                            "Interdicted"
                    )
                            .actor(SemanticSubject.COMMANDER)
                            .completion(reportedSuccess(raw))
                            .negation(negatedSuccess(raw))
                            .build()
            );
        });

        builder.register(EscapeInterdiction.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    threat(
                            raw,
                            provenance,
                            SemanticOperation.ESCAPED,
                            "Interdictor"
                    )
                            .completion(Boolean.TRUE)
                            .relationship("negative outcome of Interdicted")
                            .build()
            );
        });

        builder.register(UnderAttack.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    fact(
                            SemanticSubject.PRIMARY_SHIP,
                            SemanticOperation.ATTACKED,
                            provenance
                    )
                            .qualifier("target", raw.symbol("Target"))
                            .processStage(ProcessStage.PROGRESS)
                            .build()
            );
        });

        builder.register(PVPKill.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    fact(
                            SemanticSubject.COMMANDER,
                            SemanticOperation.KILLED,
                            provenance
                    )
                            .object(EntityRef.named(
                                    EntityKind.COMMANDER,
                                    raw.text("Victim").orElse(null)
                            ))
                            .quantity(raw.integral("CombatRank"))
                            .processStage(ProcessStage.FINAL)
                            .completion(Boolean.TRUE)
                            .build()
            );
        });
    }

    private static SemanticFact.Builder threat(
            RawFields raw,
            SemanticProvenance provenance,
            SemanticOperation operation,
            String otherPartyField
    ) {
        return fact(SemanticSubject.COMMANDER, operation, provenance)
                .object(EntityRef.named(
                        EntityKind.COMMANDER,
                        raw.text(otherPartyField).orElse(null)
                ))
                .qualifier("isPlayer", raw.booleanValue("IsPlayer"))
                .qualifier("faction", raw.textValue("Faction"))
                .processStage(ProcessStage.FINAL);
    }

    private static Boolean reportedSuccess(RawFields raw) {
        return raw.booleanValue("Success")
                instanceof SemanticValue.BooleanValue success
                ? success.value()
                : null;
    }

    private static Boolean negatedSuccess(RawFields raw) {
        Boolean success = reportedSuccess(raw);
        return success == null ? null : !success;
    }

    // ---------------------------------------------------------------------
    // Damage and destruction.
    // ---------------------------------------------------------------------

    private static void registerDamage(
            SemanticAdapterRegistry.Builder builder
    ) {
        builder.register(HullDamage.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    fact(
                            SemanticSubject.PRIMARY_SHIP,
                            SemanticOperation.DAMAGED,
                            provenance
                    )
                            .quantity(raw.quantity("Health", "FRACTION"))
                            .qualifier(
                                    "playerPilot",
                                    raw.booleanValue("PlayerPilot")
                            )
                            .qualifier("fighter", raw.booleanValue("Fighter"))
                            .processStage(ProcessStage.PROGRESS)
                            .build()
            );
        });

        builder.register(HeatDamage.class, (event, provenance) ->
                SemanticEventAdapter.Result.of(
                        fact(
                                SemanticSubject.PRIMARY_SHIP,
                                SemanticOperation.DAMAGED,
                                provenance
                        )
                                .qualifier(
                                        "cause",
                                        SemanticValue.ofSymbol("HEAT")
                                )
                                .processStage(ProcessStage.PROGRESS)
                                .build()
                ));

        builder.register(CockpitBreached.class, (event, provenance) ->
                SemanticEventAdapter.Result.of(
                        shipOutcome(provenance, SemanticOperation.BREACHED)
                                .build()
                ));

        builder.register(SystemsShutdown.class, (event, provenance) ->
                SemanticEventAdapter.Result.of(
                        shipOutcome(provenance, SemanticOperation.SHUT_DOWN)
                                .build()
                ));

        builder.register(SelfDestruct.class, (event, provenance) ->
                SemanticEventAdapter.Result.of(
                        shipOutcome(provenance, SemanticOperation.DESTROYED)
                                .qualifier(
                                        "cause",
                                        SemanticValue.ofSymbol("SELF_DESTRUCT")
                                )
                                .build()
                ));

        builder.register(Died.class, (event, provenance) -> {
            RawFields raw = fields(event);
            // A wing kill reports Killers[] instead of a single KillerName;
            // when neither is present the killer is genuinely not stated.
            return SemanticEventAdapter.Result.of(
                    fact(
                            SemanticSubject.COMMANDER,
                            SemanticOperation.DIED,
                            provenance
                    )
                            .object(EntityRef.named(
                                    EntityKind.COMMANDER,
                                    raw.text("KillerName").orElse(null)
                            ))
                            .qualifier("killerShip", raw.symbol("KillerShip"))
                            .qualifier("killerRank", raw.symbol("KillerRank"))
                            .processStage(ProcessStage.FINAL)
                            .completion(Boolean.TRUE)
                            .build()
            );
        });
    }

    // ---------------------------------------------------------------------
    // Crime and bounty.
    // ---------------------------------------------------------------------

    private static void registerCrime(
            SemanticAdapterRegistry.Builder builder
    ) {
        builder.register(Bounty.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    fact(
                            SemanticSubject.COMMANDER,
                            SemanticOperation.REWARDED,
                            provenance
                    )
                            .object(EntityRef.named(
                                    EntityKind.FACTION,
                                    raw.text("VictimFaction").orElse(null)
                            ))
                            .quantity(raw.quantity("TotalReward", "CREDITS"))
                            .qualifier("target", raw.symbol("Target"))
                            .processStage(ProcessStage.FINAL)
                            .completion(Boolean.TRUE)
                            .build()
            );
        });

        builder.register(CommitCrime.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    fact(
                            SemanticSubject.COMMANDER,
                            SemanticOperation.COMMITTED,
                            provenance
                    )
                            .object(EntityRef.named(
                                    EntityKind.FACTION,
                                    raw.text("Faction").orElse(null)
                            ))
                            .qualifier("crimeType", raw.symbol("CrimeType"))
                            .qualifier("victim", raw.textValue("Victim"))
                            .qualifier("fine", raw.quantity("Fine", "CREDITS"))
                            .qualifier(
                                    "bounty",
                                    raw.quantity("Bounty", "CREDITS")
                            )
                            .processStage(ProcessStage.FINAL)
                            .completion(Boolean.TRUE)
                            .build()
            );
        });
    }

    // ---------------------------------------------------------------------
    // Auxiliary vehicle loss. Occupancy stays unresolved.
    // ---------------------------------------------------------------------

    private static void registerAuxiliaryLoss(
            SemanticAdapterRegistry.Builder builder
    ) {
        builder.register(FighterDestroyed.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    List.of(auxiliaryLoss(
                            raw,
                            provenance,
                            "FighterID",
                            "ends the deployment started by LaunchFighter"
                    )),
                    List.of(new UnresolvedFact(
                            SemanticSubject.OCCUPIED_VEHICLE,
                            UnresolvedFact.Reason
                                    .FIGHTER_OCCUPANCY_NOT_ESTABLISHED,
                            provenance
                    ))
            );
        });

        builder.register(SRVDestroyed.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    List.of(auxiliaryLoss(
                            raw,
                            provenance,
                            "SRVID",
                            "ends the deployment started by LaunchSRV"
                    )),
                    List.of(new UnresolvedFact(
                            SemanticSubject.OCCUPIED_VEHICLE,
                            UnresolvedFact.Reason
                                    .VEHICLE_OCCUPANCY_NOT_ESTABLISHED,
                            provenance
                    ))
            );
        });
    }

    /**
     * Loss of an auxiliary vehicle: a positively completed destruction.
     *
     * <p>It carries no negation. Nothing in the event asserts a field false;
     * what it does is end the deployment a paired launch began, and that
     * belongs in {@code relationship}.</p>
     */
    private static SemanticFact auxiliaryLoss(
            RawFields raw,
            SemanticProvenance provenance,
            String identityKind,
            String relationship
    ) {
        SemanticValue identity = raw.identity(identityKind, "ID");
        return fact(
                SemanticSubject.ASSOCIATED_VEHICLE,
                SemanticOperation.DESTROYED,
                provenance
        )
                .object(new EntityRef(
                        EntityKind.AUXILIARY_VEHICLE,
                        identity,
                        raw.text("SRVType_Localised")
                                .or(() -> raw.text("SRVType"))
                                .orElse(null)
                ))
                .identity(identity)
                .processStage(ProcessStage.FINAL)
                .completion(Boolean.TRUE)
                .relationship(relationship)
                .build();
    }

    // ---------------------------------------------------------------------
    // Travel hazards.
    // ---------------------------------------------------------------------

    private static void registerTravelHazard(
            SemanticAdapterRegistry.Builder builder
    ) {
        builder.register(JetConeBoost.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    fact(
                            SemanticSubject.NAVIGATION_CONTEXT,
                            SemanticOperation.BOOSTED,
                            provenance
                    )
                            .actor(SemanticSubject.COMMANDER)
                            .quantity(raw.quantity("BoostValue", "MULTIPLIER"))
                            .processStage(ProcessStage.FINAL)
                            .completion(Boolean.TRUE)
                            .build()
            );
        });

        builder.register(JetConeDamage.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    fact(
                            SemanticSubject.PRIMARY_SHIP,
                            SemanticOperation.DAMAGED,
                            provenance
                    )
                            .qualifier("module", raw.displayText("Module"))
                            .qualifier(
                                    "cause",
                                    SemanticValue.ofSymbol("JET_CONE")
                            )
                            .processStage(ProcessStage.FINAL)
                            .completion(Boolean.TRUE)
                            .build()
            );
        });
    }
}
