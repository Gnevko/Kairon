package kairon.observation.journal.event.ship;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.JournalEventObservation.RawJournalData;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.LlmPresentableJournalEvent.LlmEventPresentation;

import java.util.List;
import java.util.Locale;

/**
 * Typed identity and sourced LLM presentation for the Elite Dangerous
 * {@code LaunchDrone} journal event.
 *
 * <p>One wire event, nine domain events. A prospector limpet and a repair
 * limpet are different things to have launched, and the behaviour graph has
 * always recorded them as different structural types — it just had to re-read
 * {@code Type} itself to find that out. The dispatch happens once, here.</p>
 *
 * <p>{@code Unspecified} is not an unrecognised variant. A limpet whose type
 * the journal does not name is still a limpet launch, and the graph has a
 * researched type for exactly that; nothing here is a gap in Kairon's
 * knowledge.</p>
 *
 * <p>The dispatch matches {@code Type} case-sensitively, which is what the
 * behaviour graph always did. The presentation is more forgiving and names a
 * limpet whatever the casing — so a lower-cased {@code "hatchbreaker"} still
 * lands in {@code Unspecified} and is still described as a hatch-breaker, as it
 * was before the split. The two readings disagree, and unifying them is a
 * separate claim about the journal rather than about this class.</p>
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 13.25</a>
 */
public sealed interface LaunchDrone extends LlmPresentableJournalEvent {

    String EVENT_TYPE = "LaunchDrone";

    /**
     * What a launch reports when the journal did not say which limpet it was.
     *
     * <p>The eight researched kinds each say their own. This one is what is
     * left when {@code Type} names nothing Kairon knows — a launch happened,
     * and which limpet is a question the record did not answer.</p>
     */
    String UNSPECIFIED_DESCRIPTION = "A drone or limpet was launched.";

    /** This event never reports the outcome, whichever limpet it was. */
    String OUTCOME_UNKNOWN =
            "This event does not report whether the limpet or drone "
                    + "completed its task successfully.";

    /** The domain event this record actually is. */
    static LaunchDrone of(RawJournalData raw) {
        JournalEventObservation.requireEvent(raw, EVENT_TYPE);
        return switch (LlmPresentableJournalEvent
                .textual(raw.parsedJsonObject().get("Type")).orElse("")) {
            case "Hatchbreaker" -> new HatchBreaker(raw);
            case "FuelTransfer" -> new FuelTransfer(raw);
            case "Collection" -> new Collection(raw);
            case "Prospector" -> new Prospector(raw);
            case "Repair" -> new Repair(raw);
            case "Research" -> new Research(raw);
            case "Decontamination" -> new Decontamination(raw);
            case "Recon" -> new Recon(raw);
            default -> new Unspecified(raw);
        };
    }

    record HatchBreaker(RawJournalData raw) implements LaunchDrone {
        public HatchBreaker {
            raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
        }

        @Override
        public String modelFacingDescription() {
            return "A hatch-breaker limpet was launched.";
        }

        @Override
        public LlmEventPresentation llmPresentation() {
            return launched("a hatch-breaker limpet");
        }
    }

    record FuelTransfer(RawJournalData raw) implements LaunchDrone {
        public FuelTransfer {
            raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
        }

        @Override
        public String modelFacingDescription() {
            return "A fuel-transfer limpet was launched.";
        }

        @Override
        public LlmEventPresentation llmPresentation() {
            return launched("a fuel-transfer limpet");
        }
    }

    record Collection(RawJournalData raw) implements LaunchDrone {
        public Collection {
            raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
        }

        @Override
        public String modelFacingDescription() {
            return "A collector limpet was launched.";
        }

        @Override
        public LlmEventPresentation llmPresentation() {
            return launched("a collector limpet");
        }
    }

    record Prospector(RawJournalData raw) implements LaunchDrone {
        public Prospector {
            raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
        }

        @Override
        public String modelFacingDescription() {
            return "A prospector limpet was launched.";
        }

        @Override
        public LlmEventPresentation llmPresentation() {
            return launched("a prospector limpet");
        }
    }

    record Repair(RawJournalData raw) implements LaunchDrone {
        public Repair {
            raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
        }

        @Override
        public String modelFacingDescription() {
            return "A repair limpet was launched.";
        }

        @Override
        public LlmEventPresentation llmPresentation() {
            return launched("a repair limpet");
        }
    }

    record Research(RawJournalData raw) implements LaunchDrone {
        public Research {
            raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
        }

        @Override
        public String modelFacingDescription() {
            return "A research limpet was launched.";
        }

        @Override
        public LlmEventPresentation llmPresentation() {
            return launched("a research limpet");
        }
    }

    record Decontamination(RawJournalData raw) implements LaunchDrone {
        public Decontamination {
            raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
        }

        @Override
        public String modelFacingDescription() {
            return "A decontamination limpet was launched.";
        }

        @Override
        public LlmEventPresentation llmPresentation() {
            return launched("a decontamination limpet");
        }
    }

    record Recon(RawJournalData raw) implements LaunchDrone {
        public Recon {
            raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
        }

        @Override
        public String modelFacingDescription() {
            return "A recon limpet was launched.";
        }

        @Override
        public LlmEventPresentation llmPresentation() {
            return launched("a recon limpet");
        }
    }

    /** A limpet launch whose type the journal did not name canonically. */
    record Unspecified(RawJournalData raw) implements LaunchDrone {
        public Unspecified {
            raw = JournalEventObservation.requireEvent(raw, EVENT_TYPE);
        }

        @Override
        public String modelFacingDescription() {
            return UNSPECIFIED_DESCRIPTION;
        }

        @Override
        public LlmEventPresentation llmPresentation() {
            JsonNode event = raw.parsedJsonObject();
            return new LlmEventPresentation(List.of(
                    LlmPresentableJournalEvent.textual(event.get("Type"))
                            .map(type -> "The player launched "
                                    + describe(type)
                                    + ".")
                            .orElse("The player launched a limpet or drone "
                                    + "whose type is not reported."),
                    OUTCOME_UNKNOWN
            ));
        }
    }

    private static LlmEventPresentation launched(String limpet) {
        return new LlmEventPresentation(List.of(
                "The player launched " + limpet + ".",
                OUTCOME_UNKNOWN
        ));
    }

    private static String describe(String sourceType) {
        return switch (sourceType.toLowerCase(Locale.ROOT)) {
            case "hatchbreaker" -> "a hatch-breaker limpet";
            case "fueltransfer" -> "a fuel-transfer limpet";
            case "collection" -> "a collector limpet";
            case "prospector" -> "a prospector limpet";
            case "repair" -> "a repair limpet";
            case "research" -> "a research limpet";
            case "decontamination" -> "a decontamination limpet";
            case "recon" -> "a recon limpet";
            default -> "a limpet or drone identified by the journal as type "
                    + LlmPresentableJournalEvent.quoted(sourceType);
        };
    }
}
