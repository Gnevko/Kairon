package kairon.semantics;

import kairon.observation.journal.event.carrier.CarrierBuy;
import kairon.observation.journal.event.carrier.CarrierCancelDecommission;
import kairon.observation.journal.event.carrier.CarrierDecommission;
import kairon.observation.journal.event.carrier.CarrierJump;
import kairon.observation.journal.event.carrier.CarrierJumpCancelled;
import kairon.observation.journal.event.carrier.CarrierJumpRequest;
import kairon.observation.journal.event.carrier.CarrierNameChange;
import kairon.observation.journal.event.colonisation.ColonisationBeaconDeployed;
import kairon.observation.journal.event.colonisation.ColonisationConstructionDepot;
import kairon.observation.journal.event.colonisation.ColonisationContribution;
import kairon.observation.journal.event.colonisation.ColonisationSystemClaim;
import kairon.observation.journal.event.colonisation.ColonisationSystemClaimRelease;
import kairon.observation.journal.event.colonisation.CompleteConstruction;
import kairon.observation.journal.event.ship.RebootRepair;
import kairon.observation.journal.event.ship.SellShipOnRebuy;
import kairon.observation.journal.event.ship.SetUserShipName;
import kairon.observation.journal.event.ship.ShipRedeemed;
import kairon.observation.journal.event.ship.ShipyardBuy;
import kairon.observation.journal.event.ship.ShipyardNew;
import kairon.observation.journal.event.ship.ShipyardSell;
import kairon.observation.journal.event.ship.ShipyardSwap;
import kairon.observation.journal.event.ship.ShipyardTransfer;
import kairon.observation.journal.event.trade.RedeemVoucher;
import kairon.observation.journal.event.trade.SearchAndRescue;
import kairon.semantics.SemanticFact.EntityKind;
import kairon.semantics.SemanticFact.EntityRef;
import kairon.semantics.SemanticFact.ProcessStage;

import static kairon.semantics.SemanticAdapterSupport.commanderOutcome;
import static kairon.semantics.SemanticAdapterSupport.fact;
import static kairon.semantics.SemanticAdapterSupport.fields;
import static kairon.semantics.SemanticAdapterSupport.shipOutcome;

/**
 * Ownership, construction and commerce mechanisms.
 *
 * <p>Fleet carriers, colonisation, shipyard transactions and voucher
 * redemption. None of these is projected into canonical state, so the
 * structured fact is the only place their meaning can survive.</p>
 */
final class CommerceSemanticAdapters {

    private CommerceSemanticAdapters() {
    }

    static void register(SemanticAdapterRegistry.Builder builder) {
        registerCarrier(builder);
        registerColonisation(builder);
        registerShipOwnership(builder);
        registerTrade(builder);
    }

    // ---------------------------------------------------------------------
    // Fleet carriers. Identity is always the carrier, never the ship.
    // ---------------------------------------------------------------------

    private static void registerCarrier(
            SemanticAdapterRegistry.Builder builder
    ) {
        builder.register(CarrierBuy.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    carrier(raw, provenance, SemanticOperation.ACQUIRED)
                            .quantity(raw.quantity("Price", "CREDITS"))
                            .qualifier("system", raw.textValue("Location"))
                            .qualifier("variant", raw.symbol("Variant"))
                            .build()
            );
        });

        builder.register(CarrierNameChange.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    carrier(raw, provenance, SemanticOperation.RENAMED)
                            .qualifier("name", raw.textValue("Name"))
                            .build()
            );
        });

        builder.register(CarrierDecommission.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    carrier(raw, provenance, SemanticOperation.DECOMMISSIONED)
                            .quantity(raw.quantity("ScrapRefund", "CREDITS"))
                            .processStage(ProcessStage.START)
                            .completion(null)
                            .build()
            );
        });

        builder.register(
                CarrierCancelDecommission.class,
                (event, provenance) -> SemanticEventAdapter.Result.of(
                        carrier(
                                fields(event),
                                provenance,
                                SemanticOperation.CANCELLED
                        )
                                .relationship("cancels CarrierDecommission")
                                .build()
                )
        );

        builder.register(CarrierJumpRequest.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    carrier(raw, provenance, SemanticOperation.SCHEDULED)
                            .qualifier(
                                    "destinationSystem",
                                    raw.textValue("SystemName")
                            )
                            .qualifier("destinationBody", raw.textValue("Body"))
                            .processStage(ProcessStage.START)
                            .completion(null)
                            .build()
            );
        });

        builder.register(
                CarrierJumpCancelled.class,
                (event, provenance) -> SemanticEventAdapter.Result.of(
                        carrier(
                                fields(event),
                                provenance,
                                SemanticOperation.CANCELLED
                        )
                                .relationship("cancels CarrierJumpRequest")
                                .build()
                )
        );

        builder.register(CarrierJump.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    carrier(raw, provenance, SemanticOperation.ARRIVED)
                            .qualifier("system", raw.textValue("StarSystem"))
                            .qualifier("body", raw.textValue("Body"))
                            .build()
            );
        });
    }

    private static SemanticFact.Builder carrier(
            RawFields raw,
            SemanticProvenance provenance,
            SemanticOperation operation
    ) {
        SemanticValue identity = raw.identity("CarrierID", "CarrierID");
        return fact(SemanticSubject.COMMANDER, operation, provenance)
                .object(new EntityRef(
                        EntityKind.FLEET_CARRIER,
                        identity,
                        raw.text("Callsign").orElse(null)
                ))
                .identity(identity)
                .processStage(ProcessStage.FINAL)
                .completion(Boolean.TRUE);
    }

    // ---------------------------------------------------------------------
    // Colonisation: a staged process with an explicit release counterpart.
    // ---------------------------------------------------------------------

    private static void registerColonisation(
            SemanticAdapterRegistry.Builder builder
    ) {
        builder.register(ColonisationSystemClaim.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    systemClaim(raw, provenance, SemanticOperation.CLAIMED)
                            .processStage(ProcessStage.START)
                            .completion(null)
                            .build()
            );
        });

        builder.register(
                ColonisationSystemClaimRelease.class,
                (event, provenance) -> SemanticEventAdapter.Result.of(
                        systemClaim(
                                fields(event),
                                provenance,
                                SemanticOperation.RELEASED
                        )
                                .relationship("releases ColonisationSystemClaim")
                                .build()
                )
        );

        builder.register(
                ColonisationBeaconDeployed.class,
                (event, provenance) -> SemanticEventAdapter.Result.of(
                        systemClaim(
                                fields(event),
                                provenance,
                                SemanticOperation.DEPLOYED
                        ).build()
                )
        );

        builder.register(
                ColonisationContribution.class,
                (event, provenance) -> {
                    RawFields raw = fields(event);
                    return SemanticEventAdapter.Result.of(
                            fact(
                                    SemanticSubject.COMMANDER,
                                    SemanticOperation.CONTRIBUTED,
                                    provenance
                            )
                                    .object(new EntityRef(
                                            EntityKind.CONSTRUCTION_SITE,
                                            raw.identity(
                                                    "MarketID",
                                                    "MarketID"
                                            ),
                                            null
                                    ))
                                    .identity(raw.identity(
                                            "MarketID",
                                            "MarketID"
                                    ))
                                    .processStage(ProcessStage.PROGRESS)
                                    .build()
                    );
                }
        );

        builder.register(
                ColonisationConstructionDepot.class,
                (event, provenance) -> {
                    RawFields raw = fields(event);
                    // Completion and failure are separate reported booleans;
                    // neither is inferred from the progress value.
                    Boolean complete = raw.booleanValue("ConstructionComplete")
                            instanceof SemanticValue.BooleanValue done
                            ? done.value()
                            : null;
                    Boolean failed = raw.booleanValue("ConstructionFailed")
                            instanceof SemanticValue.BooleanValue broken
                            ? broken.value()
                            : null;
                    return SemanticEventAdapter.Result.of(
                            fact(
                                    SemanticSubject.COMMANDER,
                                    SemanticOperation.CONTRIBUTED,
                                    provenance
                            )
                                    .object(new EntityRef(
                                            EntityKind.CONSTRUCTION_SITE,
                                            raw.identity(
                                                    "MarketID",
                                                    "MarketID"
                                            ),
                                            null
                                    ))
                                    .identity(raw.identity(
                                            "MarketID",
                                            "MarketID"
                                    ))
                                    .quantity(raw.decimal(
                                            "ConstructionProgress"
                                    ))
                                    .processStage(
                                            Boolean.TRUE.equals(complete)
                                                    ? ProcessStage.FINAL
                                                    : ProcessStage.PROGRESS
                                    )
                                    .completion(complete)
                                    .negation(failed)
                                    .build()
                    );
                }
        );

        builder.register(CompleteConstruction.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    fact(
                            SemanticSubject.COMMANDER,
                            SemanticOperation.COMPLETED,
                            provenance
                    )
                            .object(new EntityRef(
                                    EntityKind.CONSTRUCTION_SITE,
                                    raw.identity("MarketID", "MarketID"),
                                    raw.text("SystemName").orElse(null)
                            ))
                            .identity(raw.identity("MarketID", "MarketID"))
                            .processStage(ProcessStage.FINAL)
                            .completion(Boolean.TRUE)
                            .build()
            );
        });
    }

    private static SemanticFact.Builder systemClaim(
            RawFields raw,
            SemanticProvenance provenance,
            SemanticOperation operation
    ) {
        SemanticValue identity =
                raw.identity("SystemAddress", "SystemAddress");
        return fact(SemanticSubject.CURRENT_SYSTEM, operation, provenance)
                .actor(SemanticSubject.COMMANDER)
                .object(new EntityRef(
                        EntityKind.SYSTEM,
                        identity,
                        raw.text("SystemName")
                                .or(() -> raw.text("StarSystem"))
                                .orElse(null)
                ))
                .identity(identity)
                .processStage(ProcessStage.FINAL)
                .completion(Boolean.TRUE);
    }

    // ---------------------------------------------------------------------
    // Ship ownership. The ship identifier is always explicitly kinded.
    // ---------------------------------------------------------------------

    private static void registerShipOwnership(
            SemanticAdapterRegistry.Builder builder
    ) {
        builder.register(ShipyardBuy.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    shipTransaction(raw, provenance, SemanticOperation.BOUGHT)
                            .quantity(raw.quantity("ShipPrice", "CREDITS"))
                            .build()
            );
        });

        builder.register(ShipyardSell.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    shipTransaction(raw, provenance, SemanticOperation.SOLD)
                            .quantity(raw.quantity("ShipPrice", "CREDITS"))
                            .identity(raw.identity("ShipID", "SellShipID"))
                            .build()
            );
        });

        builder.register(ShipyardSwap.class, (event, provenance) ->
                SemanticEventAdapter.Result.of(
                        shipTransaction(
                                fields(event),
                                provenance,
                                SemanticOperation.SWITCHED_TO
                        ).build()
                ));

        builder.register(ShipyardNew.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    shipTransaction(raw, provenance, SemanticOperation.ACQUIRED)
                            .identity(raw.identity("ShipID", "NewShipID"))
                            .build()
            );
        });

        builder.register(ShipyardTransfer.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    shipTransaction(
                            raw,
                            provenance,
                            SemanticOperation.TRANSFERRED
                    )
                            .quantity(raw.quantity(
                                    "Distance",
                                    "LIGHT_YEARS"
                            ))
                            .qualifier("fromSystem", raw.textValue("System"))
                            .qualifier("price", raw.quantity(
                                    "TransferPrice",
                                    "CREDITS"
                            ))
                            .processStage(ProcessStage.START)
                            .completion(Boolean.FALSE)
                            .build()
            );
        });

        builder.register(SellShipOnRebuy.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    shipTransaction(raw, provenance, SemanticOperation.SOLD)
                            .identity(raw.identity("ShipID", "SellShipId"))
                            .quantity(raw.quantity("ShipPrice", "CREDITS"))
                            .qualifier("system", raw.textValue("System"))
                            .build()
            );
        });

        builder.register(ShipRedeemed.class, (event, provenance) ->
                SemanticEventAdapter.Result.of(
                        shipTransaction(
                                fields(event),
                                provenance,
                                SemanticOperation.REDEEMED
                        ).build()
                ));

        builder.register(SetUserShipName.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    shipTransaction(raw, provenance, SemanticOperation.RENAMED)
                            .qualifier(
                                    "shipName",
                                    raw.textValue("UserShipName")
                            )
                            .qualifier(
                                    "shipIdentifier",
                                    raw.textValue("UserShipId")
                            )
                            .build()
            );
        });

        builder.register(RebootRepair.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    shipOutcome(provenance, SemanticOperation.REPAIRED)
                            .quantity(raw.quantity("Cost", "CREDITS"))
                            .build()
            );
        });
    }

    private static SemanticFact.Builder shipTransaction(
            RawFields raw,
            SemanticProvenance provenance,
            SemanticOperation operation
    ) {
        SemanticValue identity = raw.identity("ShipID", "ShipID");
        return fact(SemanticSubject.PRIMARY_SHIP, operation, provenance)
                .actor(SemanticSubject.COMMANDER)
                .object(new EntityRef(
                        EntityKind.SHIP,
                        identity,
                        raw.text("ShipType_Localised")
                                .or(() -> raw.text("ShipType"))
                                .orElse(null)
                ))
                .identity(identity)
                .qualifier("marketId", raw.identity("MarketID", "MarketID"))
                .processStage(ProcessStage.FINAL)
                .completion(Boolean.TRUE);
    }

    // ---------------------------------------------------------------------
    // Vouchers and rescue payouts.
    // ---------------------------------------------------------------------

    private static void registerTrade(
            SemanticAdapterRegistry.Builder builder
    ) {
        builder.register(RedeemVoucher.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    fact(
                            SemanticSubject.COMMANDER,
                            SemanticOperation.REDEEMED,
                            provenance
                    )
                            .object(new EntityRef(
                                    EntityKind.SIGNAL_SOURCE,
                                    SemanticValue.unknown(),
                                    raw.text("Type").orElse(null)
                            ))
                            .quantity(raw.quantity("Amount", "CREDITS"))
                            .qualifier("faction", raw.textValue("Faction"))
                            .processStage(ProcessStage.FINAL)
                            .completion(Boolean.TRUE)
                            .build()
            );
        });

        builder.register(SearchAndRescue.class, (event, provenance) -> {
            RawFields raw = fields(event);
            return SemanticEventAdapter.Result.of(
                    commanderOutcome(
                            provenance,
                            SemanticOperation.SOLD,
                            EntityKind.COMMODITY,
                            raw,
                            "Name",
                            raw.identity("MarketID", "MarketID")
                    )
                            .quantity(raw.quantity("Count", "UNITS"))
                            .qualifier("reward", raw.quantity(
                                    "Reward",
                                    "CREDITS"
                            ))
                            .build()
            );
        });
    }
}
