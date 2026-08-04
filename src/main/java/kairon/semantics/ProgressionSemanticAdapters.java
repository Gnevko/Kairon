package kairon.semantics;

import kairon.observation.journal.event.engineering.EngineerContribution;
import kairon.observation.journal.event.engineering.EngineerCraft;
import kairon.observation.journal.event.engineering.EngineerLegacyConvert;
import kairon.observation.journal.event.engineering.TechnologyBroker;
import kairon.observation.journal.event.exploration.FSSAllBodiesFound;
import kairon.observation.journal.event.exploration.MultiSellExplorationData;
import kairon.observation.journal.event.exploration.SAAScanComplete;
import kairon.observation.journal.event.exploration.SellExplorationData;
import kairon.observation.journal.event.exploration.SellOrganicData;
import kairon.observation.journal.event.inventory.CargoTransfer;
import kairon.observation.journal.event.inventory.CollectCargo;
import kairon.observation.journal.event.inventory.EjectCargo;
import kairon.observation.journal.event.inventory.MaterialDiscovered;
import kairon.observation.journal.event.mining.AsteroidCracked;
import kairon.observation.journal.event.mission.CommunityGoalJoin;
import kairon.observation.journal.event.mission.CommunityGoalReward;
import kairon.observation.journal.event.mission.MissionRedirected;
import kairon.observation.journal.event.onfoot.HoloscreenHacked;
import kairon.observation.journal.event.onfoot.UpgradeSuit;
import kairon.observation.journal.event.onfoot.UpgradeWeapon;
import kairon.observation.journal.event.powerplay.PowerplayDefect;
import kairon.observation.journal.event.powerplay.PowerplayJoin;
import kairon.observation.journal.event.powerplay.PowerplayLeave;
import kairon.observation.journal.event.powerplay.PowerplayRank;
import kairon.observation.journal.event.session.NewCommander;
import kairon.observation.journal.event.session.Promotion;
import kairon.observation.journal.event.social.CrewFire;
import kairon.observation.journal.event.social.CrewHire;
import kairon.observation.journal.event.social.CrewMemberJoins;
import kairon.observation.journal.event.social.CrewMemberQuits;
import kairon.observation.journal.event.social.Friends;
import kairon.observation.journal.event.social.JoinedSquadron;
import kairon.observation.journal.event.social.KickedFromSquadron;
import kairon.observation.journal.event.social.LeftSquadron;
import kairon.observation.journal.event.social.NpcCrewRank;
import kairon.observation.journal.event.social.SquadronCreated;
import kairon.observation.journal.event.social.SquadronDemotion;
import kairon.observation.journal.event.social.SquadronPromotion;
import kairon.observation.journal.event.social.WingJoin;
import kairon.observation.journal.event.social.WingLeave;
import kairon.observation.journal.event.travel.DropshipDeploy;
import kairon.observation.journal.event.travel.USSDrop;
import kairon.semantics.SemanticFact.EntityKind;
import kairon.semantics.SemanticFact.EntityRef;
import kairon.semantics.SemanticFact.ProcessStage;

import java.util.List;

import static kairon.semantics.SemanticAdapterSupport.fact;
import static kairon.semantics.SemanticAdapterSupport.fields;
import static kairon.semantics.SemanticAdapterSupport.vesselContextGaps;
import static kairon.semantics.SemanticAdapterSupport.withGap;

/**
 * Progression, allegiance, social, exploration and inventory mechanisms.
 *
 * <p>Membership events carry explicit polarity: a leave negates the matching
 * join. Telepresence is recorded as an unresolved gap rather than as physical
 * presence.</p>
 */
final class ProgressionSemanticAdapters {

    private ProgressionSemanticAdapters() {
    }

    static void register(SemanticAdapterRegistry.Builder builder) {
        registerEngineering(builder);
        registerAllegiance(builder);
        registerMilestones(builder);
        registerOnFoot(builder);
        registerCrewAndSquadron(builder);
        registerCommunityGoals(builder);
        registerExploration(builder);
        registerInventory(builder);
        registerArrival(builder);
    }

    // ---------------------------------------------------------------------

    private static void registerEngineering(
            SemanticAdapterRegistry.Builder builder
    ) {
        builder.register(EngineerCraft.class, (event, provenance) ->
                SemanticEventAdapter.Result.of(
                        engineering(
                                fields(event),
                                provenance,
                                SemanticOperation.CRAFTED
                        ).build()
                ));

        builder.register(EngineerLegacyConvert.class, (event, provenance) ->
                SemanticEventAdapter.Result.of(
                        engineering(
                                fields(event),
                                provenance,
                                SemanticOperation.CONVERTED
                        ).build()
                ));

        builder.register(EngineerContribution.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    engineering(
                            raw,
                            provenance,
                            SemanticOperation.CONTRIBUTED
                    )
                            .quantity(raw.integral("Quantity"))
                            .qualifier(
                                    "totalQuantity",
                                    raw.integral("TotalQuantity")
                            )
                            .qualifier(
                                    "commodity",
                                    raw.displayText("Commodity")
                            )
                            .processStage(ProcessStage.PROGRESS)
                            .completion(null)
                            .build()
            );
        });

        builder.register(TechnologyBroker.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    fact(
                            SemanticSubject.COMMANDER,
                            SemanticOperation.UNLOCKED,
                            provenance
                    )
                            .object(new EntityRef(
                                    EntityKind.BLUEPRINT,
                                    raw.identity("MarketID", "MarketID"),
                                    raw.text("BrokerType").orElse(null)
                            ))
                            .identity(raw.identity("MarketID", "MarketID"))
                            .processStage(ProcessStage.FINAL)
                            .completion(Boolean.TRUE)
                            .build()
            );
        });
    }

    private static SemanticFact.Builder engineering(
            RawFields raw,
            SemanticProvenance provenance,
            SemanticOperation operation
    ) {
        SemanticValue identity = raw.identity("EngineerID", "EngineerID");
        return fact(SemanticSubject.PRIMARY_SHIP, operation, provenance)
                .actor(SemanticSubject.COMMANDER)
                .object(new EntityRef(
                        EntityKind.ENGINEER,
                        identity,
                        raw.text("Engineer").orElse(null)
                ))
                .identity(identity)
                .qualifier("blueprint", raw.symbol("BlueprintName"))
                .qualifier("level", raw.integral("Level"))
                .qualifier("quality", raw.decimal("Quality"))
                .qualifier("module", raw.displayText("Module"))
                .processStage(ProcessStage.FINAL)
                .completion(Boolean.TRUE);
    }

    // ---------------------------------------------------------------------

    private static void registerAllegiance(
            SemanticAdapterRegistry.Builder builder
    ) {
        builder.register(PowerplayJoin.class, (event, provenance) ->
                SemanticEventAdapter.Result.of(
                        power(
                                fields(event),
                                provenance,
                                SemanticOperation.JOINED,
                                "Power"
                        ).build()
                ));

        builder.register(PowerplayLeave.class, (event, provenance) ->
                SemanticEventAdapter.Result.of(
                        power(
                                fields(event),
                                provenance,
                                SemanticOperation.LEFT,
                                "Power"
                        )
                                .relationship("negates PowerplayJoin")
                                .build()
                ));

        builder.register(PowerplayDefect.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    power(raw, provenance, SemanticOperation.DEFECTED, "ToPower")
                            .qualifier("fromPower", raw.textValue("FromPower"))
                            .relationship("negates the previous allegiance")
                            .build()
            );
        });

        builder.register(PowerplayRank.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    power(raw, provenance, SemanticOperation.PROMOTED, "Power")
                            .quantity(raw.integral("Rank"))
                            .build()
            );
        });
    }

    private static SemanticFact.Builder power(
            RawFields raw,
            SemanticProvenance provenance,
            SemanticOperation operation,
            String powerField
    ) {
        return fact(SemanticSubject.COMMANDER, operation, provenance)
                .object(EntityRef.named(
                        EntityKind.POWER,
                        raw.text(powerField).orElse(null)
                ))
                .processStage(ProcessStage.FINAL)
                .completion(Boolean.TRUE);
    }

    // ---------------------------------------------------------------------

    private static void registerMilestones(
            SemanticAdapterRegistry.Builder builder
    ) {
        builder.register(NewCommander.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    fact(
                            SemanticSubject.COMMANDER,
                            SemanticOperation.CREATED,
                            provenance
                    )
                            .identity(raw.identity("FID", "FID"))
                            .qualifier("name", raw.textValue("Name"))
                            .qualifier("package", raw.symbol("Package"))
                            .processStage(ProcessStage.START)
                            .completion(Boolean.TRUE)
                            .build()
            );
        });

        builder.register(Promotion.class, (event, provenance) -> {
            RawFields raw = fields(event);
            // Exactly one ladder is present per event; whichever is reported
            // becomes the quantity, and the ladder becomes the object.
            SemanticFact.Builder promotion = fact(
                    SemanticSubject.COMMANDER,
                    SemanticOperation.PROMOTED,
                    provenance
            )
                    .processStage(ProcessStage.FINAL)
                    .completion(Boolean.TRUE);
            for (String ladder : new String[]{
                    "Combat", "Trade", "Explore", "Soldier", "Exobiologist",
                    "CQC", "Federation", "Empire"
            }) {
                SemanticValue rank = raw.integral(ladder);
                if (rank.known()) {
                    return SemanticEventAdapter.Result.of(
                            promotion
                                    .object(EntityRef.named(
                                            EntityKind.RANK,
                                            ladder
                                    ))
                                    .quantity(rank)
                                    .build()
                    );
                }
            }
            return SemanticEventAdapter.Result.of(promotion.build());
        });
    }

    // ---------------------------------------------------------------------

    private static void registerOnFoot(
            SemanticAdapterRegistry.Builder builder
    ) {
        builder.register(UpgradeSuit.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    equipmentUpgrade(
                            raw,
                            provenance,
                            EntityKind.SUIT,
                            raw.identity("SuitID", "SuitID")
                    ).build()
            );
        });

        builder.register(UpgradeWeapon.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    equipmentUpgrade(
                            raw,
                            provenance,
                            EntityKind.WEAPON,
                            raw.identity("SuitModuleID", "SuitModuleID")
                    ).build()
            );
        });

        builder.register(HoloscreenHacked.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    fact(
                            SemanticSubject.COMMANDER,
                            SemanticOperation.HACKED,
                            provenance
                    )
                            .qualifier(
                                    "powerBefore",
                                    raw.textValue("PowerBefore")
                            )
                            .qualifier(
                                    "powerAfter",
                                    raw.textValue("PowerAfter")
                            )
                            .processStage(ProcessStage.FINAL)
                            .completion(Boolean.TRUE)
                            .build()
            );
        });
    }

    private static SemanticFact.Builder equipmentUpgrade(
            RawFields raw,
            SemanticProvenance provenance,
            EntityKind kind,
            SemanticValue identity
    ) {
        return fact(
                SemanticSubject.COMMANDER,
                SemanticOperation.UPGRADED,
                provenance
        )
                .object(new EntityRef(
                        kind,
                        identity,
                        raw.text("Name_Localised")
                                .or(() -> raw.text("Name"))
                                .orElse(null)
                ))
                .identity(identity)
                .quantity(raw.integral("Class"))
                .qualifier("cost", raw.quantity("Cost", "CREDITS"))
                .processStage(ProcessStage.FINAL)
                .completion(Boolean.TRUE);
    }

    // ---------------------------------------------------------------------
    // Crew, squadron and wing membership.
    // ---------------------------------------------------------------------

    private static void registerCrewAndSquadron(
            SemanticAdapterRegistry.Builder builder
    ) {
        builder.register(CrewHire.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    crewMember(raw, provenance, SemanticOperation.HIRED)
                            .quantity(raw.quantity("Cost", "CREDITS"))
                            .qualifier("faction", raw.textValue("Faction"))
                            .build()
            );
        });

        builder.register(CrewFire.class, (event, provenance) ->
                SemanticEventAdapter.Result.of(
                        crewMember(
                                fields(event),
                                provenance,
                                SemanticOperation.FIRED
                        )
                                .relationship("negates CrewHire")
                                .build()
                ));

        builder.register(NpcCrewRank.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    fact(
                            SemanticSubject.COMMANDER,
                            SemanticOperation.PROMOTED,
                            provenance
                    )
                            .object(new EntityRef(
                                    EntityKind.CREW_MEMBER,
                                    raw.identity("CrewID", "NpcCrewId"),
                                    raw.text("NpcCrewName").orElse(null)
                            ))
                            .quantity(raw.integral("RankCombat"))
                            .processStage(ProcessStage.FINAL)
                            .completion(Boolean.TRUE)
                            .build()
            );
        });

        // Telepresence never establishes where anyone physically is.
        builder.register(CrewMemberJoins.class, (event, provenance) ->
                multicrewMembership(
                        event,
                        provenance,
                        SemanticOperation.JOINED,
                        null
                ));

        builder.register(CrewMemberQuits.class, (event, provenance) ->
                multicrewMembership(
                        event,
                        provenance,
                        SemanticOperation.LEFT,
                        "negates CrewMemberJoins"
                ));

        builder.register(JoinedSquadron.class, (event, provenance) ->
                SemanticEventAdapter.Result.of(
                        squadron(
                                fields(event),
                                provenance,
                                SemanticOperation.JOINED
                        ).build()
                ));

        builder.register(LeftSquadron.class, (event, provenance) ->
                SemanticEventAdapter.Result.of(
                        squadron(
                                fields(event),
                                provenance,
                                SemanticOperation.LEFT
                        )
                                .relationship("negates JoinedSquadron")
                                .build()
                ));

        builder.register(KickedFromSquadron.class, (event, provenance) ->
                SemanticEventAdapter.Result.of(
                        squadron(
                                fields(event),
                                provenance,
                                SemanticOperation.EXPELLED
                        )
                                .relationship("negates JoinedSquadron")
                                .build()
                ));

        builder.register(SquadronCreated.class, (event, provenance) ->
                SemanticEventAdapter.Result.of(
                        squadron(
                                fields(event),
                                provenance,
                                SemanticOperation.CREATED
                        ).build()
                ));

        builder.register(SquadronPromotion.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    squadron(raw, provenance, SemanticOperation.PROMOTED)
                            .quantity(raw.integral("NewRank"))
                            .qualifier("oldRank", raw.integral("OldRank"))
                            .build()
            );
        });

        builder.register(SquadronDemotion.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    squadron(raw, provenance, SemanticOperation.DEMOTED)
                            .quantity(raw.integral("NewRank"))
                            .qualifier("oldRank", raw.integral("OldRank"))
                            .relationship("reverses SquadronPromotion")
                            .build()
            );
        });

        builder.register(WingJoin.class, (event, provenance) ->
                SemanticEventAdapter.Result.of(
                        wing(provenance, SemanticOperation.JOINED, null)
                ));

        builder.register(WingLeave.class, (event, provenance) ->
                SemanticEventAdapter.Result.of(
                        wing(
                                provenance,
                                SemanticOperation.LEFT,
                                "negates WingJoin"
                        )
                ));

        builder.register(Friends.class, (event, provenance) -> {
            RawFields raw = fields(event);
            SemanticFact status = fact(
                    SemanticSubject.COMMANDER,
                    SemanticOperation.RECEIVED,
                    provenance
            )
                    .object(EntityRef.named(
                            EntityKind.COMMANDER,
                            raw.text("Name").orElse(null)
                    ))
                    .qualifier("status", raw.symbol("Status"))
                    .processStage(ProcessStage.FINAL)
                    .completion(Boolean.TRUE)
                    .build();
            // An Online status is also emitted at startup for a friend who
            // was already online. That limit belongs in a typed gap, not in
            // prose the contract ranks below the structured facts.
            boolean online = raw.text("Status")
                    .filter("Online"::equals)
                    .isPresent();
            return online
                    ? SemanticEventAdapter.Result.of(
                            List.of(status),
                            List.of(new UnresolvedFact(
                                    SemanticSubject.COMMANDER,
                                    UnresolvedFact.Reason
                                            .LOGIN_TRANSITION_NOT_ESTABLISHED,
                                    provenance
                            ))
                    )
                    : SemanticEventAdapter.Result.of(status);
        });
    }

    private static SemanticEventAdapter.Result multicrewMembership(
            kairon.observation.journal.JournalEventObservation event,
            SemanticProvenance provenance,
            SemanticOperation operation,
            String relationship
    ) {
        RawFields raw = fields(event);
        SemanticFact membership = fact(
                SemanticSubject.COMMANDER,
                operation,
                provenance
        )
                .object(EntityRef.named(
                        EntityKind.CREW_MEMBER,
                        raw.text("Crew").orElse(null)
                ))
                .qualifier("telepresence", raw.booleanValue("Telepresence"))
                .processStage(ProcessStage.FINAL)
                .completion(Boolean.TRUE)
                .relationship(relationship)
                .build();
        List<UnresolvedFact> gaps = List.of(new UnresolvedFact(
                SemanticSubject.COMMANDER_PRESENCE,
                UnresolvedFact.Reason.MULTICREW_CONTEXT_NOT_MODELLED,
                provenance
        ));
        return SemanticEventAdapter.Result.of(List.of(membership), gaps);
    }

    private static SemanticFact.Builder crewMember(
            RawFields raw,
            SemanticProvenance provenance,
            SemanticOperation operation
    ) {
        SemanticValue identity = raw.identity("CrewID", "CrewID");
        return fact(SemanticSubject.COMMANDER, operation, provenance)
                .object(new EntityRef(
                        EntityKind.CREW_MEMBER,
                        identity,
                        raw.text("Name").orElse(null)
                ))
                .identity(identity)
                .processStage(ProcessStage.FINAL)
                .completion(Boolean.TRUE);
    }

    private static SemanticFact.Builder squadron(
            RawFields raw,
            SemanticProvenance provenance,
            SemanticOperation operation
    ) {
        return fact(SemanticSubject.COMMANDER, operation, provenance)
                .object(EntityRef.named(
                        EntityKind.SQUADRON,
                        raw.text("SquadronName").orElse(null)
                ))
                .processStage(ProcessStage.FINAL)
                .completion(Boolean.TRUE);
    }

    private static SemanticFact wing(
            SemanticProvenance provenance,
            SemanticOperation operation,
            String relationship
    ) {
        return fact(SemanticSubject.COMMANDER, operation, provenance)
                .object(new EntityRef(
                        EntityKind.WING,
                        SemanticValue.unknown(),
                        null
                ))
                .processStage(ProcessStage.FINAL)
                .completion(Boolean.TRUE)
                .relationship(relationship)
                .build();
    }

    // ---------------------------------------------------------------------

    private static void registerCommunityGoals(
            SemanticAdapterRegistry.Builder builder
    ) {
        builder.register(CommunityGoalJoin.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    communityGoal(raw, provenance, SemanticOperation.JOINED)
                            .processStage(ProcessStage.START)
                            .completion(null)
                            .build()
            );
        });

        builder.register(CommunityGoalReward.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    communityGoal(raw, provenance, SemanticOperation.REWARDED)
                            .quantity(raw.quantity("Reward", "CREDITS"))
                            .build()
            );
        });

        builder.register(MissionRedirected.class, (event, provenance) -> {
            RawFields raw = fields(event);
            SemanticValue identity = raw.identity("MissionID", "MissionID");
            return SemanticEventAdapter.Result.of(
                    fact(
                            SemanticSubject.COMMANDER,
                            SemanticOperation.REDIRECTED,
                            provenance
                    )
                            .object(new EntityRef(
                                    EntityKind.MISSION,
                                    identity,
                                    raw.text("LocalisedName")
                                            .or(() -> raw.text("Name"))
                                            .orElse(null)
                            ))
                            .identity(identity)
                            .qualifier(
                                    "newDestinationSystem",
                                    raw.textValue("NewDestinationSystem")
                            )
                            .qualifier(
                                    "newDestinationStation",
                                    raw.textValue("NewDestinationStation")
                            )
                            .processStage(ProcessStage.PROGRESS)
                            .completion(Boolean.FALSE)
                            .build()
            );
        });
    }

    private static SemanticFact.Builder communityGoal(
            RawFields raw,
            SemanticProvenance provenance,
            SemanticOperation operation
    ) {
        SemanticValue identity = raw.identity("CommunityGoalID", "CGID");
        return fact(SemanticSubject.COMMANDER, operation, provenance)
                .object(new EntityRef(
                        EntityKind.MISSION,
                        identity,
                        raw.text("Name").orElse(null)
                ))
                .identity(identity)
                .qualifier("system", raw.textValue("System"))
                .processStage(ProcessStage.FINAL)
                .completion(Boolean.TRUE);
    }

    // ---------------------------------------------------------------------

    private static void registerExploration(
            SemanticAdapterRegistry.Builder builder
    ) {
        builder.register(FSSAllBodiesFound.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    fact(
                            SemanticSubject.CURRENT_SYSTEM,
                            SemanticOperation.SURVEY_COMPLETED,
                            provenance
                    )
                            .actor(SemanticSubject.COMMANDER)
                            .object(new EntityRef(
                                    EntityKind.SYSTEM,
                                    raw.identity(
                                            "SystemAddress",
                                            "SystemAddress"
                                    ),
                                    raw.text("SystemName").orElse(null)
                            ))
                            .quantity(raw.integral("Count"))
                            .processStage(ProcessStage.FINAL)
                            .completion(Boolean.TRUE)
                            .build()
            );
        });

        builder.register(SAAScanComplete.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    fact(
                            SemanticSubject.CURRENT_BODY,
                            SemanticOperation.MAPPED,
                            provenance
                    )
                            .actor(SemanticSubject.COMMANDER)
                            .object(SemanticAdapterSupport.bodyRef(raw))
                            .identity(raw.identity("BodyID", "BodyID"))
                            .quantity(raw.integral("ProbesUsed"))
                            .qualifier(
                                    "efficiencyTarget",
                                    raw.integral("EfficiencyTarget")
                            )
                            .processStage(ProcessStage.FINAL)
                            .completion(Boolean.TRUE)
                            .build()
            );
        });

        builder.register(SellExplorationData.class, (event, provenance) ->
                SemanticEventAdapter.Result.of(
                        explorationSale(fields(event), provenance)
                ));

        builder.register(MultiSellExplorationData.class, (event, provenance) ->
                SemanticEventAdapter.Result.of(
                        explorationSale(fields(event), provenance)
                ));

        builder.register(SellOrganicData.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    fact(
                            SemanticSubject.COMMANDER,
                            SemanticOperation.SOLD,
                            provenance
                    )
                            .object(new EntityRef(
                                    EntityKind.ORGANIC,
                                    raw.identity("MarketID", "MarketID"),
                                    null
                            ))
                            .identity(raw.identity("MarketID", "MarketID"))
                            .processStage(ProcessStage.FINAL)
                            .completion(Boolean.TRUE)
                            .build()
            );
        });
    }

    private static SemanticFact explorationSale(
            RawFields raw,
            SemanticProvenance provenance
    ) {
        return fact(
                SemanticSubject.COMMANDER,
                SemanticOperation.SOLD,
                provenance
        )
                .object(new EntityRef(
                        EntityKind.SIGNAL_SOURCE,
                        SemanticValue.unknown(),
                        "exploration data"
                ))
                .quantity(raw.quantity("TotalEarnings", "CREDITS"))
                .qualifier("baseValue", raw.quantity("BaseValue", "CREDITS"))
                .qualifier("bonus", raw.quantity("Bonus", "CREDITS"))
                .processStage(ProcessStage.FINAL)
                .completion(Boolean.TRUE)
                .build();
    }

    // ---------------------------------------------------------------------

    private static void registerInventory(
            SemanticAdapterRegistry.Builder builder
    ) {
        builder.register(CollectCargo.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    cargo(raw, provenance, SemanticOperation.COLLECTED)
                            .qualifier("stolen", raw.booleanValue("Stolen"))
                            .build()
            );
        });

        builder.register(EjectCargo.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    cargo(raw, provenance, SemanticOperation.EJECTED)
                            .quantity(raw.integral("Count"))
                            .qualifier(
                                    "abandoned",
                                    raw.booleanValue("Abandoned")
                            )
                            .relationship("inverse of CollectCargo")
                            .build()
            );
        });

        builder.register(CargoTransfer.class, (event, provenance) ->
                SemanticEventAdapter.Result.of(
                        fact(
                                SemanticSubject.COMMANDER,
                                SemanticOperation.TRANSFERRED,
                                provenance
                        )
                                .object(new EntityRef(
                                        EntityKind.COMMODITY,
                                        SemanticValue.unknown(),
                                        null
                                ))
                                .processStage(ProcessStage.FINAL)
                                .completion(Boolean.TRUE)
                                .build()
                ));

        builder.register(MaterialDiscovered.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    fact(
                            SemanticSubject.COMMANDER,
                            SemanticOperation.DISCOVERED,
                            provenance
                    )
                            .object(new EntityRef(
                                    EntityKind.MATERIAL,
                                    SemanticValue.unknown(),
                                    raw.text("Name_Localised")
                                            .or(() -> raw.text("Name"))
                                            .orElse(null)
                            ))
                            .quantity(raw.integral("DiscoveryNumber"))
                            .qualifier("category", raw.symbol("Category"))
                            .processStage(ProcessStage.FINAL)
                            .completion(Boolean.TRUE)
                            .build()
            );
        });

        builder.register(AsteroidCracked.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    fact(
                            SemanticSubject.CURRENT_BODY,
                            SemanticOperation.CRACKED,
                            provenance
                    )
                            .actor(SemanticSubject.COMMANDER)
                            .object(EntityRef.named(
                                    EntityKind.BODY,
                                    raw.text("Body").orElse(null)
                            ))
                            .processStage(ProcessStage.FINAL)
                            .completion(Boolean.TRUE)
                            .build()
            );
        });
    }

    private static SemanticFact.Builder cargo(
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
                .processStage(ProcessStage.FINAL)
                .completion(Boolean.TRUE);
    }

    // ---------------------------------------------------------------------

    private static void registerArrival(
            SemanticAdapterRegistry.Builder builder
    ) {
        builder.register(USSDrop.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    fact(
                            SemanticSubject.NAVIGATION_CONTEXT,
                            SemanticOperation.DROPPED,
                            provenance
                    )
                            .actor(SemanticSubject.COMMANDER)
                            .object(new EntityRef(
                                    EntityKind.SIGNAL_SOURCE,
                                    SemanticValue.unknown(),
                                    raw.text("USSType_Localised")
                                            .or(() -> raw.text("USSType"))
                                            .orElse(null)
                            ))
                            .quantity(raw.integral("USSThreat"))
                            .processStage(ProcessStage.FINAL)
                            .completion(Boolean.TRUE)
                            .build()
            );
        });

        builder.register(DropshipDeploy.class, (event, provenance) -> {
            RawFields raw = fields(event);
            // A dropship is not the commander's own ship; the projection does
            // not model it, so the vessel relationship stays unresolved.
            return SemanticEventAdapter.Result.of(
                    List.of(fact(
                            SemanticSubject.COMMANDER_PRESENCE,
                            SemanticOperation.DEPLOYED,
                            provenance
                    )
                            .actor(SemanticSubject.COMMANDER)
                            .object(new EntityRef(
                                    EntityKind.UNRESOLVED,
                                    SemanticValue.unknown(),
                                    null
                            ))
                            .qualifier("system", raw.textValue("StarSystem"))
                            .qualifier("body", raw.textValue("Body"))
                            .processStage(ProcessStage.FINAL)
                            .completion(Boolean.TRUE)
                            .build()),
                    withGap(
                            vesselContextGaps(raw, provenance),
                            new UnresolvedFact(
                                    SemanticSubject.OCCUPIED_VEHICLE,
                                    UnresolvedFact.Reason
                                            .VEHICLE_OCCUPANCY_NOT_ESTABLISHED,
                                    provenance
                            )
                    )
            );
        });
    }
}
