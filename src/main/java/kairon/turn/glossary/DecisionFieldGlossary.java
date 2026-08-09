package kairon.turn.glossary;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What every name in the request means, said once and cached.
 *
 * <p>The document was built to explain itself: the event carries an English
 * sentence, and the field names are meant to be readable. That holds for
 * {@code planetClass} and {@code presence}; it did not hold for
 * {@code occurrenceOnBody}, and on 2026-08-08 a vehicle launch carrying it —
 * the only word in the whole document containing <em>body</em> — was answered
 * "the Commander is on the surface, the descent went normally", from a document
 * that mentioned neither surface nor descent. A name that has to be guessed
 * will be guessed wrong.</p>
 *
 * <p>That field is gone now, and the glossary did not save it: with an entry in
 * place it was still read as "the sixth visit to the surface during the
 * expedition" and then, at a count of 13, as "twelve landings left" — a tally
 * of the past turned into a plan for the future. Explaining a name is worth
 * doing; it does not make every name worth sending.</p>
 *
 * <p>This is not the reading manual that was removed on 2026-08-06. That block
 * told the model how to <em>weigh</em> what it read — which fields were
 * important, what to do about them, what not to say — and it went with 447
 * words of prohibition that cost eight {@code SILENT}s out of eight. This says
 * only what each name denotes. Nothing here asks for behaviour, ranks a field
 * above another, or forbids anything; the four preference lines remain the only
 * place a field is called important.</p>
 *
 * <h2>What an absent name means</h2>
 * <p>The preamble says it, because nothing else in the contract does and the
 * model kept deciding for itself. Measured across the live session of
 * 2026-08-08, four different fields were read as negative values by being
 * missing: a landing whose body group was a name became "a planet with no
 * gravity", another became "no atmosphere", and two more became "no biological
 * or geological signals" and "no signals to collect" — none of those fields
 * travels on a landing at all. Absence is how this whole document says
 * "unknown, or not what this turn is about", and it had never been said.</p>
 *
 * <p>It is <strong>not</strong> the clause that was measured and rejected on
 * 2026-08-07. That one lived in the gravity preference line and told the model
 * what an absent field <em>meant about gravity</em>, which it duly announced:
 * "gravity has not been measured on this planet" — a report about Kairon's own
 * bookkeeping, and false as the Commander hears it. This says absence is not a
 * value, which is a fact about the shape of the document rather than an
 * instruction to mention it, and it sits where the shape is described.</p>
 *
 * <h2>Static, and complete</h2>
 * <p>It is a constant appended to the system prompt, so it is identical in
 * every turn and sits in the provider's prompt cache rather than being rebuilt
 * per request. A per-turn glossary of only the fields present would be shorter
 * and would invalidate that cache on every turn.</p>
 *
 * <p>Only names the model can actually receive are listed. A field the
 * projector drops ({@code position}, {@code marketId}, {@code subCategory},
 * {@code distanceFromArrivalLs}), a taxon level folded into {@code organism}
 * ({@code genus}, {@code species}, {@code variant}), and an adapter name the
 * projector renames on the way out ({@code isNewEntry}, {@code isPlayer},
 * {@code playerControlled}) are absent, because documenting what can never
 * arrive is prompt weight bought with nothing.</p>
 *
 * <p>Reachability is a property of the whole pipeline and it moves. Five
 * entries were removed on 2026-08-08 for that reason and only that reason:
 * {@code onPlanet} and {@code onStation} exist only on a disembark or an
 * embark, and neither opens a turn any more; {@code onFoot}, {@code inSrv} and
 * {@code docked} exist only on {@code Location}, which is
 * {@code CONTEXT_ONLY} and was never presented at all — those three were dead
 * on the day the glossary was written. <strong>A name no admitted event can
 * produce is a lie about the document</strong>, and it is worse than a missing
 * entry, because a reader has no way to find out.</p>
 *
 * <p>A description drifts the same way and is harder to catch, because the name
 * it describes is still reachable. {@code context.system} said "the system the
 * Commander is in" after the system name had already moved to
 * {@code ContextNeed.SYSTEM_NAME}, which no profile asks for: the group holds
 * {@code bodyCount} and {@code scannedCount} and identifies nothing. Nothing in
 * the suite could see it — the reachability check reconciles names, and both
 * names were reached. It now says what the group holds. <strong>An entry
 * describes what its group actually carries this week, not what the group is
 * about.</strong></p>
 *
 * <p>Staying complete is not left to discipline.
 * {@code DecisionFieldGlossaryContractTest} collects every key of every request
 * the fixture replay and the pipeline corpus produce and fails on one with no
 * entry, so a new field cannot ship undocumented; it fails the other way too,
 * on an entry no reachable name matches, so a removed field cannot leave a
 * stale line behind.</p>
 *
 * <p>It sits in {@code kairon.turn} for the same reason
 * {@code ContextOverflow} does: the prompt needs it and {@code kairon.llm} may
 * not import an observer package. The contract it describes is
 * {@code kairon.observer.decision}'s, and the test that keeps the two honest
 * imports both.</p>
 */
public final class DecisionFieldGlossary {

    private DecisionFieldGlossary() {
    }

    /** The shape of the document itself. */
    private static final Map<String, String> DOCUMENT = entries(
            "events", "what just happened, in order",
            "changes", "what is different now from before",
            "context", "the situation these events happened in",
            "contextIncomplete", "some context did not fit and was left out"
    );

    /** The parts every event has, whatever it reports. */
    private static final Map<String, String> EVENT_SHAPE = entries(
            "event", "one sentence saying what happened",
            "stage", "where the action stands in a multi-step process",
            "complete", "whether the action finished",
            "negated", "the event reports that this did not happen",
            "derived", "Kairon worked this out; the journal did not say it"
    );

    /** What an event acted on, named by what kind of thing it is. */
    private static final Map<String, String> SUBJECTS = entries(
            "system", "a star system, by name",
            "body", "a planet, moon or star, by name",
            "station", "a station, outpost or settlement",
            "ship", "a ship",
            "vehicle", "an SRV or ship-launched fighter",
            "commander", "the Commander, by name",
            "organism", "a life form, by its full name",
            "entry", "a codex entry",
            "mission", "a mission",
            "commodity", "a traded good",
            "message", "the text of a message",
            "carrier", "a fleet carrier",
            "site", "a colonisation construction site",
            "faction", "a minor faction",
            "power", "a galactic power",
            "squadron", "a squadron",
            "wing", "a wing",
            "crew", "a crew member",
            "engineer", "an engineer",
            "blueprint", "an engineering blueprint",
            "suit", "an on-foot suit",
            "weapon", "an on-foot weapon",
            "material", "an engineering material",
            "signalSource", "an unidentified signal source",
            "rank", "a rank",
            "arrivalStar", "the star this system was entered at",
            "friend", "another Commander on the friends list",
            "victim", "who was killed",
            "target", "who or what a mission is against"
    );

    /** What a survey, scan or system report says. */
    private static final Map<String, String> SURVEY = entries(
            "scanType", "how thorough the scan was",
            "planetClass", "what kind of planet or moon it is",
            "starType", "the star's spectral class",
            "bodyType", "star, planet or moon — sent only where the reading "
                    + "itself decides which",
            "atmosphere", "what it is wrapped in",
            "volcanism", "what erupts there",
            "terraformState", "whether it could be terraformed",
            "landable", "whether a ship can put down on it",
            "biologicalSignals", "how many biological signals were counted",
            "geologicalSignals", "how many geological signals were counted",
            "organisms", "the life forms named by the surface scanner",
            "previouslyDiscovered", "somebody had already found it",
            "previouslyMapped", "somebody had already mapped it",
            "previouslyFootfalled", "somebody had already walked on it",
            "firstFootfall", "nobody had walked on this body before, so the "
                    + "sample pays five times over",
            "efficiencyTarget", "how many probes the mapping allows for a "
                    + "bonus",
            "probesUsed", "how many probes were actually fired",
            "bodyCount", "how many bodies the system holds",
            "scannedCount", "how many of them have been scanned"
    );

    /** Where things are and how they are moving. */
    private static final Map<String, String> MOVEMENT = entries(
            "commanderControlled", "the Commander was flying it, rather than "
                    + "it acting on its own",
            "playerPilot", "a person was flying it, rather than the ship's "
                    + "computer",
            "player", "another Commander rather than an NPC",
            "fighter", "the vessel involved is a ship-launched fighter",
            "vehicleKind", "what sort of vehicle: SHIP, SRV or SLV",
            "vehicleType", "the vehicle's model",
            "fuelUsed", "fuel spent on the jump",
            "boostUsed", "a neutron or jet-cone boost was used",
            "distanceLy", "distance in light years",
            "remainingJumpsInRoute", "jumps left on the plotted route",
            "fromSystem", "the system left behind",
            "destinationSystem", "the system being travelled to",
            "destinationBody", "the body being travelled to",
            "newDestinationSystem", "the system a redirected mission now "
                    + "points at",
            "newDestinationStation", "the station it now points at",
            "stationType", "what kind of station it is",
            "shipName", "the Commander's name for a ship",
            "shipIdentifier", "the ship's registry lettering"
    );

    /** Messages, ranks, and the game's own rubrics. */
    private static final Map<String, String> WORLD = entries(
            "channel", "who the message went to: PLAYER, FRIEND, WING, LOCAL, "
                    + "STARSYSTEM, DIRECT or VOICECHAT",
            "sender", "who sent it",
            "name", "what the thing is called",
            "status", "what the friend's standing now is, in the journal's own word: ONLINE, OFFLINE, REQUESTED, ADDED, DECLINED or LOST",
            "category", "the game's own classification for a codex entry",
            "region", "the region of the galaxy the entry was filed under",
            "newEntry", "nobody had filed this codex entry before",
            "reason", "why it happened, in the game's words",
            "oldRank", "the rank held before this one",
            "fromPower", "the power left behind",
            "powerBefore", "standing before the change",
            "powerAfter", "standing after it",
            "level", "which grade of a blueprint or rank",
            "quality", "how well an engineering roll came out, 0 to 1",
            "module", "the ship module involved",
            "package", "the named set an upgrade belongs to",
            "telepresence", "the crew member joined remotely",
            "abandoned", "the mission was given up rather than failed",
            "stolen", "the goods were stolen",
            "crimeType", "what the crime was",
            "cause", "what did the damage",
            "killerShip", "the ship that made the kill",
            "killerRank", "its pilot's combat rank"
    );

    /** Money and amounts, each under the unit it is counted in. */
    private static final Map<String, String> AMOUNTS = entries(
            "valueMCr", "what the finished sample pays at Vista Genomics, in "
                    + "millions of credits",
            "bodyTotalMCr", "what every sample collected on this body pays in "
                    + "all, in millions of credits",
            "atLeastMCr", "the lowest total one sample of each organism named "
                    + "here could pay, in millions of credits",
            "credits", "an amount of credits",
            "units", "a count of things",
            "tonnes", "a mass of cargo",
            "totalQuantity", "how many are now held in all",
            "fraction", "a proportion between 0 and 1",
            "multiplier", "how many times over",
            "price", "price paid or asked",
            "unitPrice", "price for one",
            "sellPrice", "price received",
            "cost", "what it cost",
            "totalCost", "what it cost in all",
            "totalSale", "what the sale came to in all",
            "baseValue", "the value before any bonus",
            "bonus", "what was added on top",
            "reward", "what was paid for it",
            "bounty", "a bounty amount",
            "fine", "a fine amount"
    );

    /** What Kairon could not establish, said in the terms of the gap. */
    private static final Map<String, String> UNCERTAINTY = entries(
            "details", "the record was not understood in detail",
            "identifiedObject", "what was acted on could not be identified",
            "taxi", "whether the vessel was a taxi is unconfirmed",
            "multicrew", "whether another Commander's ship was involved is "
                    + "unconfirmed",
            "occupancy", "whether anyone was aboard the vehicle is unconfirmed"
    );

    /** How a change is stated. */
    private static final Map<String, String> CHANGE_SHAPE = entries(
            "subject", "what changed: commander, ship, vehicle, system, body, "
                    + "navigation or sampling",
            "kind", "ESTABLISHED for a first value, UPDATED for a replacement, CLEARED for a value that is gone",
            "fields", "the fields that moved",
            "before", "the value it held",
            "after", "the value it holds now"
    );

    /** The situation, group by group. */
    private static final Map<String, String> CONTEXT = entries(
            "context.system", "how much of the system around them has been "
                    + "surveyed",
            "context.body", "the body they are at, and what it is",
            "context.commander", "where the Commander physically is",
            "context.ship", "their ship",
            "context.vehicle", "the vehicle in use",
            "context.navigation", "how the ship is travelling",
            "context.sampling", "a sampling run in progress",
            "context.biology", "what grows on this body",
            "presence", "where the Commander is sitting: SHIP, SRV, SLV or ON_FOOT",
            "flightMode", "SUPERCRUISE, NORMAL_SPACE, LANDED, DOCKED or "
                    + "HYPERSPACE",
            "gravity", "how heavily the body pulls: LOW, NORMAL or HIGH",
            "type", "the broad kind of thing",
            "active", "a sampling run is under way",
            "allCollected", "every organism the survey named here has been "
                    + "collected",
            "collected", "organisms already sampled here",
            "remaining", "organisms here that are still uncollected"
    );

    private static final List<Section> SECTIONS = List.of(
            new Section("the document", DOCUMENT),
            new Section("every event", EVENT_SHAPE),
            new Section("what an event acted on", SUBJECTS),
            new Section("scans and surveys", SURVEY),
            new Section("place and movement", MOVEMENT),
            new Section("people, ranks and rubrics", WORLD),
            new Section("amounts", AMOUNTS),
            new Section("what could not be established", UNCERTAINTY),
            new Section("a change", CHANGE_SHAPE),
            new Section("the context", CONTEXT)
    );

    /** The block as it appears in the system prompt. */
    public static final String TEXT = render();

    /**
     * Every entry, name to meaning, in reading order.
     *
     * <p>For the tests that check the entries are true rather than merely
     * present — a value-listing entry is compared against the enum it claims
     * to enumerate.</p>
     */
    public static Map<String, String> entries() {
        Map<String, String> all = new LinkedHashMap<>();
        SECTIONS.forEach(section -> all.putAll(section.entries()));
        return Collections.unmodifiableMap(all);
    }

    /** Every name this glossary describes. */
    public static Set<String> describedNames() {
        Set<String> names = new LinkedHashSet<>();
        SECTIONS.forEach(section -> names.addAll(section.entries().keySet()));
        return Set.copyOf(names);
    }

    private static String render() {
        StringBuilder text = new StringBuilder();
        text.append("<fields>\n")
                .append("Every name the request can contain. A name not here ")
                .append("does not appear.\n")
                .append("A name absent from a request was not established, or ")
                .append("does not bear on this\nturn. Absence is not a ")
                .append("value.\n");
        for (Section section : SECTIONS) {
            text.append('\n').append(section.title()).append('\n');
            section.entries().forEach((name, meaning) ->
                    text.append("  ").append(name)
                            .append(" - ").append(meaning).append('\n'));
        }
        return text.append("</fields>\n").toString();
    }

    private static Map<String, String> entries(String... pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("a name without a meaning");
        }
        Map<String, String> entries = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            if (entries.put(pairs[index], pairs[index + 1]) != null) {
                throw new IllegalArgumentException(
                        "described twice: " + pairs[index]
                );
            }
        }
        // Insertion order is the reading order; Map.copyOf would scramble it.
        return Collections.unmodifiableMap(entries);
    }

    private record Section(String title, Map<String, String> entries) {
    }
}
