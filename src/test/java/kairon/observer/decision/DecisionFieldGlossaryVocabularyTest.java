package kairon.observer.decision;

import kairon.semantics.AuxiliaryVehicleTypes;
import kairon.semantics.SemanticChangeKind;
import kairon.state.CommanderLocationMode;
import kairon.state.FlightMode;
import kairon.turn.glossary.DecisionFieldGlossary;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A glossary entry that lists values lists the right ones.
 *
 * <p>The completeness test asks whether a name has an entry. It cannot ask
 * whether the entry is true, and an untrue one is worse than none: on
 * 2026-08-08 the {@code status} entry said "ONLINE or OFFLINE", the journal
 * sent {@code REQUESTED} and then {@code ADDED} for a friend request and its
 * acceptance, and both correct answers were read as fabrications — by the
 * author of the entry, not by the model. Four of the seven value-listing
 * entries were wrong the same way: {@code channel} said {@code SYSTEM} for
 * {@code STARSYSTEM} and omitted three channels, {@code kind} omitted
 * {@code CLEARED}, {@code vehicleKind} listed {@code ON_FOOT} (which is a
 * presence, not a vehicle) and {@code presence} listed {@code STATION}, which
 * does not exist.</p>
 *
 * <p>So where the vocabulary is closed in code, this reads it from the code and
 * compares. {@code UNKNOWN} is excluded on purpose: it is how canonical state
 * says nothing is established, and an unknown value is never serialized, so
 * naming it in the prompt would describe a value the model cannot receive.</p>
 *
 * <p>{@code status} is deliberately absent from the checked set. Its values are
 * the journal's own words passed through as symbols, with no enum behind them,
 * so there is nothing here to compare against — which is exactly why it was the
 * one that went wrong, and why its entry now says it is the journal's word.</p>
 */
final class DecisionFieldGlossaryVocabularyTest {

    private static final Pattern UPPERCASE_TOKEN =
            Pattern.compile("\\b[A-Z][A-Z_]{2,}\\b");

    private static Map<String, Set<String>> closedVocabularies() {
        Map<String, Set<String>> expected = new LinkedHashMap<>();
        expected.put("presence", withoutUnknown(CommanderLocationMode.values()));
        expected.put("flightMode", withoutUnknown(FlightMode.values()));
        expected.put("kind", names(SemanticChangeKind.values()));
        expected.put("vehicleKind", Set.of(
                AuxiliaryVehicleTypes.SHIP,
                AuxiliaryVehicleTypes.SRV,
                AuxiliaryVehicleTypes.SLV
        ));
        expected.put("gravity", Set.of("LOW", "NORMAL", "HIGH"));
        return expected;
    }

    @Test
    void everyValueAnEntryListsIsOneTheModelCanReceive() {
        Map<String, String> entries = DecisionFieldGlossary.entries();

        closedVocabularies().forEach((name, expected) -> {
            String description = entries.get(name);
            assertTrue(
                    description != null,
                    name + " lost its entry; the vocabulary check is now "
                            + "checking nothing"
            );
            assertEquals(
                    new TreeSet<>(expected),
                    new TreeSet<>(valuesIn(description)),
                    name + " lists the wrong values: " + description
            );
        });
    }

    /**
     * The channel list is the reachable half of the channel vocabulary.
     *
     * <p>{@code npc} and {@code squadron} are declined by
     * {@code LlmJournalEventSelection.admitsAsTrigger}, and an event only ever
     * reaches the model as a trigger — so those two are the one case where a
     * value exists in code and is deliberately not described.</p>
     */
    @Test
    void theChannelEntryNamesEveryChannelThatCanReachTheModel() {
        assertEquals(
                new TreeSet<>(Set.of(
                        "PLAYER", "FRIEND", "WING", "LOCAL",
                        "STARSYSTEM", "DIRECT", "VOICECHAT"
                )),
                new TreeSet<>(valuesIn(
                        DecisionFieldGlossary.entries().get("channel")
                )),
                "npc and squadron never open a turn, and nothing else is left "
                        + "out"
        );
    }

    /** The one open vocabulary says it is open. */
    @Test
    void theFriendStatusEntrySaysWhoseWordItIs() {
        String description = DecisionFieldGlossary.entries().get("status");

        assertTrue(
                description.contains("journal"),
                "an open vocabulary must say where its words come from: "
                        + description
        );
        assertTrue(
                valuesIn(description).containsAll(Set.of(
                        "ONLINE", "OFFLINE", "REQUESTED", "ADDED"
                )),
                "the four seen in one live session at least: " + description
        );
    }

    private static Set<String> valuesIn(String description) {
        Set<String> values = new TreeSet<>();
        Matcher tokens = UPPERCASE_TOKEN.matcher(description);
        while (tokens.find()) {
            values.add(tokens.group());
        }
        return values;
    }

    private static Set<String> withoutUnknown(Enum<?>[] constants) {
        Set<String> names = new TreeSet<>(names(constants));
        names.remove("UNKNOWN");
        return names;
    }

    private static Set<String> names(Enum<?>[] constants) {
        return Arrays.stream(constants)
                .map(Enum::name)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
    }
}
