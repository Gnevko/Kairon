package kairon.observer.decision;

import kairon.behavior.normalize.NormalizedEventType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * What a remembered or predicted event says it is.
 *
 * <p>The trajectory is the one place a statement reaches the model without a
 * journal payload behind it: a predecessor is remembered as a normalized type,
 * and a prediction is only ever a type. Neither can be asked to describe itself
 * the way an event is asked, so this is a second table — and it exists to say
 * the same sentences the events say.</p>
 *
 * <p>It used to hold identifiers: {@code SYSTEM_ENTERED},
 * {@code BODY_SIGNALS_FOUND}. That is Kairon's own vocabulary, which
 * {@code events[*].kind} stopped carrying when every event learned to describe
 * itself, and the trajectory was the last place it survived. A name only this
 * process shares is not an answer to what happened, wherever in the document it
 * appears.</p>
 *
 * <p>Where a normalized type is produced by a journal class, the sentence here
 * <strong>is</strong> that class's {@code modelFacingDescription()}, and a
 * contract test parses a record of each and compares. That is enforceable
 * because a class now means one domain event and its sentence is a constant;
 * while one class could mean several, there was nothing to compare against.</p>
 *
 * <p>The rest are types no journal class describes: the Status-derived scanner
 * modes and landing gear, the two frame-shift charges, the system honk. Their
 * sentences are authored here, in the same register, rather than left to leak a
 * normalized spelling.</p>
 *
 * <p>A prediction reads the same sentence as a memory of the same event, in the
 * same past tense. What has not happened is said by the field it sits in —
 * {@code likelyNext}, with a probability beside it — and the prompt states it
 * outright. A second set of sentences in a forward tense would be a second
 * vocabulary to keep in step with this one, which is the shape this table exists
 * to remove.</p>
 *
 * <p>An unmapped type yields {@code null}. That is a real case: an unrecognised
 * discriminator normalizes to an {@code UNKNOWN_*} value built from the
 * journal's own event name, and passing that through would put a Frontier
 * identifier in front of the model. The occurrence is dropped from the
 * trajectory instead.</p>
 */
final class DecisionTrajectoryDescriptions {

    private static final Map<NormalizedEventType, String> DESCRIPTIONS =
            build();

    private DecisionTrajectoryDescriptions() {
    }

    /** What a normalized type says it is, or null if it has no sentence. */
    static String descriptionOf(NormalizedEventType eventType) {
        return DESCRIPTIONS.get(Objects.requireNonNull(eventType, "eventType"));
    }

    /** Every normalized type this vocabulary covers. */
    static Map<NormalizedEventType, String> descriptions() {
        return DESCRIPTIONS;
    }

    private static Map<NormalizedEventType, String> build() {
        Map<NormalizedEventType, String> said = new LinkedHashMap<>();

        // The episode root. Only a real jump mints one — a restored session
        // opens an episode with no root occurrence at all — so this is the
        // jump's own sentence.
        said.put(NormalizedEventType.SYSTEM_ENTRY,
                "A ship jumped from one star system to another.");
        // Authored: StartJump carries no presentation of its own.
        said.put(NormalizedEventType.HYPERSPACE_JUMP_STARTED,
                "A frame shift drive began charging for a jump to another "
                        + "star system.");
        said.put(NormalizedEventType.SUPERCRUISE_JUMP_STARTED,
                "A frame shift drive began charging for supercruise.");
        said.put(NormalizedEventType.FSD_TARGET_SELECTED,
                "A star system was selected to jump to.");
        said.put(NormalizedEventType.SUPERCRUISE_ENTRY,
                "A ship entered supercruise from normal space.");
        said.put(NormalizedEventType.SUPERCRUISE_EXIT,
                "A ship dropped out of supercruise into normal space.");
        said.put(NormalizedEventType.APPROACH_BODY,
                "A ship in supercruise came within a body's orbital-cruise "
                        + "zone.");
        said.put(NormalizedEventType.LEAVE_BODY,
                "A ship flying away from a body rose above its orbital-cruise "
                        + "altitude.");
        said.put(NormalizedEventType.TOUCHDOWN,
                "A ship landed on the surface of a planet or moon.");
        said.put(NormalizedEventType.LIFTOFF,
                "A ship took off from the surface of a planet or moon.");

        said.put(NormalizedEventType.DISEMBARK,
                "The Commander stepped out of a ship or SRV.");
        said.put(NormalizedEventType.EMBARK,
                "The Commander, on foot, got into a ship or SRV.");

        said.put(NormalizedEventType.AUXILIARY_VEHICLE_LAUNCHED,
                "A vehicle was launched from the ship.");
        said.put(NormalizedEventType.AUXILIARY_VEHICLE_DOCKED,
                "A surface vehicle was brought back aboard the ship.");

        // Authored: FSSDiscoveryScan carries no presentation of its own.
        said.put(NormalizedEventType.FSS_DISCOVERY_SCAN,
                "A full spectrum system scan swept the star system.");
        said.put(NormalizedEventType.FSS_ALL_BODIES_FOUND,
                "All bodies in the star system have been identified.");
        said.put(NormalizedEventType.BODY_SCANNED,
                "A discovery scan reported a star, planet or moon's "
                        + "properties.");
        said.put(NormalizedEventType.SYSTEM_UNDISCOVERED_CONFIRMED,
                "A scan reported a star as not previously discovered.");
        // Two structural types, two instruments, two sentences. Which scanner
        // reported the signals is not Kairon's bookkeeping any more once each
        // record says which one it was; a reading is still only recorded once
        // per body per visit.
        said.put(NormalizedEventType.FSS_BODY_SIGNALS_FOUND,
                "A full spectrum system scan reported signal data for a body.");
        said.put(NormalizedEventType.SAA_SIGNALS_FOUND,
                "A surface area analysis scan reported signal data for a "
                        + "planet or rings.");
        said.put(NormalizedEventType.SAA_SCAN_COMPLETE,
                "A surface area analysis scan of a body was completed.");

        // The three sampling steps. A remembered sample carries no stage, and
        // it no longer needs one: each step's own sentence says where it was.
        said.put(NormalizedEventType.SCAN_ORGANIC_LOG,
                "The organic sampling tool logged the first scan of an "
                        + "unfinished sampling sequence.");
        said.put(NormalizedEventType.SCAN_ORGANIC_SAMPLE,
                "The organic sampling tool recorded a subsequent scan of an "
                        + "unfinished sampling sequence.");
        said.put(NormalizedEventType.SCAN_ORGANIC_ANALYSE,
                "The organic sampling tool recorded the final scan and "
                        + "completed a sampling sequence.");

        // Authored: Status-derived, so no journal class exists to borrow from.
        said.put(NormalizedEventType.FSS_MODE_ENTERED,
                "The full spectrum system scanner was opened.");
        said.put(NormalizedEventType.FSS_MODE_EXITED,
                "The full spectrum system scanner was closed.");
        said.put(NormalizedEventType.SAA_MODE_ENTERED,
                "The surface area analysis scanner was opened.");
        said.put(NormalizedEventType.SAA_MODE_EXITED,
                "The surface area analysis scanner was closed.");
        said.put(NormalizedEventType.GLIDE_ENTERED,
                "A ship began an unpowered glide towards a surface.");
        said.put(NormalizedEventType.GLIDE_EXITED,
                "A ship came out of its glide towards a surface.");
        said.put(NormalizedEventType.LANDING_GEAR_DEPLOYED,
                "The landing gear was deployed.");
        said.put(NormalizedEventType.LANDING_GEAR_RETRACTED,
                "The landing gear was retracted.");

        said.put(NormalizedEventType.DOCKING_REQUESTED,
                "Permission to dock was requested.");
        said.put(NormalizedEventType.DOCKING_GRANTED,
                "A docking request was granted.");
        said.put(NormalizedEventType.DOCKED,
                "A ship docked at a station, outpost or settlement.");
        said.put(NormalizedEventType.UNDOCKED,
                "A ship lifted off from a landing pad at a station, outpost "
                        + "or settlement.");

        said.put(NormalizedEventType.LIMPET_LAUNCHED,
                "A drone or limpet was launched.");
        said.put(NormalizedEventType.HATCH_BREAKER_LIMPET_LAUNCHED,
                "A hatch-breaker limpet was launched.");
        said.put(NormalizedEventType.FUEL_TRANSFER_LIMPET_LAUNCHED,
                "A fuel-transfer limpet was launched.");
        said.put(NormalizedEventType.COLLECTION_LIMPET_LAUNCHED,
                "A collector limpet was launched.");
        said.put(NormalizedEventType.PROSPECTOR_LIMPET_LAUNCHED,
                "A prospector limpet was launched.");
        said.put(NormalizedEventType.REPAIR_LIMPET_LAUNCHED,
                "A repair limpet was launched.");
        said.put(NormalizedEventType.RESEARCH_LIMPET_LAUNCHED,
                "A research limpet was launched.");
        said.put(NormalizedEventType.DECONTAMINATION_LIMPET_LAUNCHED,
                "A decontamination limpet was launched.");
        said.put(NormalizedEventType.RECON_LIMPET_LAUNCHED,
                "A recon limpet was launched.");

        said.put(NormalizedEventType.INTERDICTED,
                "The Commander was interdicted by another pilot or an NPC.");
        said.put(NormalizedEventType.UNDER_ATTACK,
                "A vessel is being fired upon.");

        said.put(NormalizedEventType.MATERIAL_COLLECTED,
                "A material was picked up.");
        said.put(NormalizedEventType.FUEL_SCOOPING,
                "Fuel was scooped from a star.");

        said.put(NormalizedEventType.MARKET_BUY,
                "Goods were purchased in the market.");
        said.put(NormalizedEventType.MARKET_SELL,
                "Goods were sold in the market.");
        said.put(NormalizedEventType.REDEEM_VOUCHER,
                "A voucher was claimed for payment.");

        said.put(NormalizedEventType.MISSION_ACCEPTED,
                "A mission was accepted.");
        said.put(NormalizedEventType.MISSION_COMPLETED,
                "A mission was completed.");
        said.put(NormalizedEventType.MISSION_FAILED,
                "A mission failed.");
        said.put(NormalizedEventType.MISSION_ABANDONED,
                "A mission was abandoned.");

        return Map.copyOf(said);
    }
}
