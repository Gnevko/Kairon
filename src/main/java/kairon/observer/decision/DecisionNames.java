package kairon.observer.decision;

import kairon.semantics.BodySurveyFacts;
import kairon.semantics.SemanticFact;
import kairon.semantics.SemanticField;
import kairon.semantics.SemanticSubject;
import kairon.semantics.SemanticValue;
import kairon.semantics.UnresolvedFact;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * What the model calls Kairon's internal subjects, fields and entities.
 *
 * <p>An explicit mapping rather than a transformation of enum constants, for
 * two reasons. A change naming {@code body.planetClass} has to be findable in
 * the context under the same name, and several internal names are exactly the
 * ones that must not reach the model — an account identifier, a ship id, a raw
 * taxon key. Those map to {@code null}, which is the projection's instruction
 * to drop the field rather than rename it.</p>
 *
 * <p>The switches are exhaustive over each enum, so adding a subject, a field,
 * an entity kind or an unresolved reason fails compilation here instead of
 * reaching the model under a guessed name.</p>
 */
final class DecisionNames {

    /**
     * Frontier's message channels, each under its domain name.
     *
     * <p>Keyed in lower case and looked up case-insensitively, because the
     * journal's own casing is not something the contract should depend on. The
     * {@code npc} channel is here for completeness of the vocabulary; it never
     * reaches a request, because
     * {@link kairon.observer.LlmJournalEventSelection#admitsAsTrigger} declines
     * it before a batch is formed.</p>
     */
    private static final Map<String, String> CHANNELS = Map.ofEntries(
            Map.entry("squadron", "SQUADRON"),
            Map.entry("wing", "WING"),
            Map.entry("local", "LOCAL"),
            Map.entry("starsystem", "STARSYSTEM"),
            Map.entry("player", "PLAYER"),
            Map.entry("friend", "FRIEND"),
            Map.entry("direct", "DIRECT"),
            Map.entry("voicechat", "VOICECHAT"),
            Map.entry("npc", "NPC")
    );

    /**
     * Event fields that answer a context slot outright.
     *
     * <p>The context is dropped when a change names the same canonical field or
     * when an event carries the same value. Neither catches an event that
     * states a field about <em>its own</em> subject while the context would
     * report the same field about the situation after it: a recovery says
     * {@code vehicleKind: SLV} — which vessel came back — and the vehicle group
     * would then add {@code kind: SHIP}, the transport it came back into, under
     * the same word. Two answers to "which vehicle" in one request is what
     * produced a comment about a ship named Nomad.</p>
     *
     * <p>Keyed by event field rather than by mechanism, because a mechanism
     * declaring a field stated is a claim about changes; an embark states where
     * the Commander went without naming any vehicle class, and it still needs
     * the group.</p>
     *
     * <p>The entity names are here for the same reason read the other way. An
     * event names the system, body or ship it happened to under the entity's own
     * word — {@code system: "Schieni"} — while the canonical field for it is
     * spelled {@code name} inside its group. They are one fact under two
     * spellings, and the pairing is declared here rather than inferred from two
     * values being equal, which is what {@code occurrenceOnBody: 1} once did to
     * a biological count of one. {@code arrivalStar} is a body named under the
     * word that says which body it is, and it answers the same slot: the
     * milestone is about the star the ship arrived at, and canonical body facts
     * belong in that turn only while they are still that star's.</p>
     */
    /** Earth's pull in metres per second squared, and the two bands off it. */
    private static final double STANDARD_GRAVITY = 9.80665;
    private static final double LOW_GRAVITY_LIMIT = 0.5;
    private static final double NORMAL_GRAVITY_LIMIT = 1.5;

    private static final Map<String, String> CONTEXT_SLOTS_STATED_BY_EVENT =
            Map.of(
                    "vehicleKind", "vehicle.kind",
                    "system", "system.name",
                    "body", "body.name",
                    "arrivalStar", "body.name",
                    "ship", "ship.name"
            );

    private DecisionNames() {
    }

    /**
     * How heavily a body pulls, in three words, or null.
     *
     * <p>The measurement is metres per second squared and the bands are against
     * Earth's own pull: <strong>below half a g</strong> is {@code LOW},
     * <strong>up to one and a half</strong> is {@code NORMAL}, and anything
     * above that is {@code HIGH}. Earth is the anchor because it is the one
     * weight the Commander has felt, and because the landings that go wrong are
     * the ones where the ship weighs more than it was built to.</p>
     *
     * <p>A band rather than the number, because the number is not what a remark
     * rests on: {@code 0.2371} is a measurement Kairon cannot speak, and asking
     * the model to decide whether it is a lot is asking it to know what the
     * ordinary is. Deterministic code stating a band is a claim about the game —
     * these three thresholds are that claim, and they are here to be argued
     * with rather than buried in a comparison.</p>
     *
     * <p>Null when nothing measured it. Absence is unknown, exactly as it is for
     * every other body fact, and a body with no reading says nothing about how
     * heavy it is.</p>
     */
    static SemanticValue gravityBand(Double metresPerSecondSquared) {
        if (metresPerSecondSquared == null) {
            return SemanticValue.unknown();
        }
        double earthPull = metresPerSecondSquared / STANDARD_GRAVITY;
        if (earthPull < LOW_GRAVITY_LIMIT) {
            return SemanticValue.ofSymbol("LOW");
        }
        return earthPull <= NORMAL_GRAVITY_LIMIT
                ? SemanticValue.ofSymbol("NORMAL")
                : SemanticValue.ofSymbol("HIGH");
    }

    /**
     * The context slot an event field already answers, or null.
     *
     * <p>The slot is spelled {@code group.name}, matching the key the context
     * selector checks a change against.</p>
     */
    static String contextSlotStatedBy(String eventFieldName) {
        Objects.requireNonNull(eventFieldName, "eventFieldName");
        return CONTEXT_SLOTS_STATED_BY_EVENT.get(eventFieldName);
    }

    /** Where a canonical field is reported, or null when it is never sent. */
    static String slotOf(SemanticField field) {
        String name = field(field);
        return name == null ? null : subject(field.subject()) + "." + name;
    }

    /**
     * Which context group a subject belongs to.
     *
     * <p>Commander identity and commander presence share the {@code commander}
     * group. That is not a merge of separated subjects: identity contributes no
     * model-facing field at all, so the group only ever carries presence, and
     * ship, vehicle and body stay in groups of their own.</p>
     */
    static String subject(SemanticSubject subject) {
        Objects.requireNonNull(subject, "subject");
        return switch (subject) {
            case COMMANDER, COMMANDER_PRESENCE -> "commander";
            case PRIMARY_SHIP -> "ship";
            case ASSOCIATED_VEHICLE, OCCUPIED_VEHICLE -> "vehicle";
            case CURRENT_SYSTEM -> "system";
            case CURRENT_LOCATION, NAVIGATION_CONTEXT -> "navigation";
            case CURRENT_BODY -> "body";
            case BIOLOGICAL_SAMPLING_PROCESS -> "sampling";
            case UNRESOLVED_SUBJECT -> "unresolved";
        };
    }

    /**
     * What a canonical field is called, or null when it must not be sent.
     *
     * <p>Null covers three groups: account and vessel identifiers, address-style
     * keys that duplicate a name the model already has, and raw taxon keys whose
     * localised label is the speakable form. A derived convenience flag is null
     * too when a counted field beside it says the same thing.</p>
     *
     * <p>The survey flags say <em>previously</em> out loud. {@code discovered}
     * beside an arrival reads as something that just happened; what the field
     * actually records is whether anyone had been here before, and a name that
     * has to be explained is a name that will be misread.</p>
     */
    static String field(SemanticField field) {
        Objects.requireNonNull(field, "field");
        return switch (field) {
            case COMMANDER_FID -> null;
            case SHIP_ID -> null;
            case SHIP_TYPE -> "type";
            case SHIP_NAME -> "name";
            case LOADOUT_HASH -> null;
            case SYSTEM_ADDRESS -> null;
            case SYSTEM_NAME -> "name";
            // The same word the completed-survey event says it in, so the two
            // are one fact under one spelling and the context drops it in the
            // turn that reports it.
            case SYSTEM_BODY_COUNT -> "bodyCount";
            case SYSTEM_SCANNED_COUNT -> "scannedCount";
            case BODY_ID -> null;
            case BODY_NAME -> "name";
            case BROAD_BODY_TYPE -> "type";
            case PLANET_CLASS -> "planetClass";
            case STAR_TYPE -> "starType";
            case LANDABLE -> "landable";
            case WAS_DISCOVERED -> "previouslyDiscovered";
            case WAS_MAPPED -> "previouslyMapped";
            case WAS_FOOTFALLED -> "previouslyFootfalled";
            // A distance in light seconds to eleven significant figures, which
            // no decision has been shown to rest on and no comment can speak.
            // Withdrawn rather than rounded: what it would be for is not
            // settled, and a number sent without that answer invites a remark
            // built on it.
            case DISTANCE_FROM_ARRIVAL_LS -> null;
            case SURFACE_GRAVITY -> "gravity";
            case BIOLOGICAL_SIGNAL_COUNT -> "biologicalSignals";
            case GEOLOGICAL_SIGNAL_COUNT -> "geologicalSignals";
            case COMMANDER_MODE -> "presence";
            case FLIGHT_MODE -> "flightMode";
            case VEHICLE_KIND -> "kind";
            case ACTIVE_VEHICLE_ID -> null;
            case BODY_HAS_BIOLOGY -> null;
            case ACTIVE_ORGANIC_SAMPLING -> "active";
            case ORGANIC_SAMPLING_SYSTEM_ADDRESS -> null;
            case ORGANIC_SAMPLING_BODY_ID -> null;
            case ORGANIC_SAMPLING_GENUS -> null;
            case ORGANIC_SAMPLING_GENUS_LABEL -> null;
            case ORGANIC_SAMPLING_SPECIES -> null;
            case ORGANIC_SAMPLING_SPECIES_LABEL -> null;
            case ORGANIC_SAMPLING_VARIANT -> null;
            case ORGANIC_SAMPLING_VARIANT_LABEL -> "organism";
            case ORGANIC_SAMPLING_STAGE -> "stage";
        };
    }

    /**
     * What to call the thing an event acted on, from its kind.
     *
     * <p>A rule may override this where the default would mislead. Null means
     * the kind is not something a name can be attached to.</p>
     */
    static String entity(SemanticFact.EntityKind kind) {
        Objects.requireNonNull(kind, "kind");
        return switch (kind) {
            case SYSTEM -> "system";
            case BODY -> "body";
            case STATION -> "station";
            case SHIP -> "ship";
            case AUXILIARY_VEHICLE -> "vehicle";
            case COMMANDER -> "commander";
            case ORGANIC -> "organism";
            case CODEX_ENTRY -> "entry";
            case MISSION -> "mission";
            case COMMODITY -> "commodity";
            case MESSAGE -> "message";
            case FLEET_CARRIER -> "carrier";
            case CONSTRUCTION_SITE -> "site";
            case FACTION -> "faction";
            case POWER -> "power";
            case SQUADRON -> "squadron";
            case WING -> "wing";
            case CREW_MEMBER -> "crew";
            case ENGINEER -> "engineer";
            case BLUEPRINT -> "blueprint";
            case SUIT -> "suit";
            case WEAPON -> "weapon";
            case MATERIAL -> "material";
            case SIGNAL_SOURCE -> "signalSource";
            case RANK -> "rank";
            case UNRESOLVED -> null;
        };
    }

    /**
     * A gap, stated in the terms of the thing that is unknown.
     *
     * <p>The model is told what Kairon cannot establish, not which internal
     * slot failed to establish it. Each reason becomes one named field whose
     * value is an explicit unknown marker, so an absent field still means "not
     * relevant" and never "unknown".</p>
     *
     * <p>Null means the gap has no model-facing representation at all. That is
     * reserved for a gap about a claim the event never makes: the journal
     * reports a friend's current status, not a transition into it, so there is
     * no asserted transition for the contract to qualify. Stating the absence
     * of an unproven transition would invent the very claim it disclaims. The
     * reason itself is unchanged in {@link UnresolvedFact} and still reaches
     * diagnostics.</p>
     */
    static Uncertainty uncertainty(UnresolvedFact.Reason reason) {
        Objects.requireNonNull(reason, "reason");
        return switch (reason) {
            case NO_SEMANTIC_ADAPTER,
                 AUTHORITATIVE_SEMANTICS_NOT_ESTABLISHED ->
                    new Uncertainty("details", "UNAVAILABLE");
            case TAXI_CONTEXT_NOT_MODELLED ->
                    new Uncertainty("taxi", "UNCONFIRMED");
            case MULTICREW_CONTEXT_NOT_MODELLED ->
                    new Uncertainty("multicrew", "UNCONFIRMED");
            case FIGHTER_OCCUPANCY_NOT_ESTABLISHED,
                 VEHICLE_OCCUPANCY_NOT_ESTABLISHED ->
                    new Uncertainty("occupancy", "UNCONFIRMED");
            case LOGIN_TRANSITION_NOT_ESTABLISHED -> null;
            case IDENTIFIER_KIND_NOT_ESTABLISHED ->
                    new Uncertainty("identifiedObject", "UNCONFIRMED");
        };
    }

    /**
     * Which channel a message arrived on, as a closed domain value.
     *
     * <p>The journal spells these in its own lower case. Sending them through
     * unchanged would put a second casing convention in a contract where every
     * other closed vocabulary — {@code stage}, {@code presence},
     * {@code flightMode}, a friend's {@code status} — is upper snake case, and
     * a model reading two conventions has to work out that they are the same
     * kind of thing.</p>
     *
     * <p>An explicit table rather than a mechanical uppercase, because these are
     * Frontier's channel names and each one is a decision about what to call it.
     * A channel outside the table still gets the contract's casing rather than
     * the journal's, through {@link #closedToken}: an unrecognised channel is a
     * game feature Kairon has not researched, and dropping the field would be a
     * worse answer than naming it consistently.</p>
     */
    static SemanticValue messageChannel(SemanticValue value) {
        if (!(value instanceof SemanticValue.SymbolicValue symbol)) {
            return value;
        }
        String domain = CHANNELS.get(
                symbol.symbol().strip().toLowerCase(Locale.ROOT)
        );
        return domain == null
                ? closedToken(value)
                : SemanticValue.ofSymbol(domain);
    }


    /**
     * A closed game vocabulary, in the casing the rest of the contract uses.
     *
     * <p>Most closed vocabularies the model sees — {@code stage}, {@code step},
     * {@code presence}, {@code flightMode} — are upper snake case because they
     * come from Java enums. The few that arrive as mixed-case Frontier tokens,
     * a friend's status and a body's coarse type, are brought into the same
     * shape here: {@code Online} to {@code ONLINE}, {@code PlanetaryRing} to
     * {@code PLANETARY_RING}. Only the spelling changes, and only for values
     * the semantic layer already declared symbolic rather than free text.</p>
     */
    static SemanticValue closedToken(SemanticValue value) {
        if (!(value instanceof SemanticValue.SymbolicValue symbol)) {
            return value;
        }
        String token = symbol.symbol();
        StringBuilder result = new StringBuilder(token.length() + 4);
        for (int index = 0; index < token.length(); index++) {
            char character = token.charAt(index);
            if (index > 0
                    && Character.isUpperCase(character)
                    && !Character.isUpperCase(token.charAt(index - 1))) {
                result.append('_');
            }
            result.append(Character.toUpperCase(character));
        }
        return SemanticValue.ofSymbol(result.toString());
    }

    /** One named gap and the marker that stands for it. */
    record Uncertainty(String name, String marker) {
    }
}
