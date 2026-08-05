package kairon.observer;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.event.carrier.*;
import kairon.observation.journal.event.colonisation.*;
import kairon.observation.journal.event.combat.*;
import kairon.observation.journal.event.engineering.*;
import kairon.observation.journal.event.exploration.*;
import kairon.observation.journal.event.inventory.*;
import kairon.observation.journal.event.mining.*;
import kairon.observation.journal.event.mission.*;
import kairon.observation.journal.event.onfoot.*;
import kairon.observation.journal.event.powerplay.*;
import kairon.observation.journal.event.session.*;
import kairon.observation.journal.event.ship.*;
import kairon.observation.journal.event.social.*;
import kairon.observation.journal.event.trade.*;
import kairon.observation.journal.event.travel.*;
import kairon.semantics.BodySurveyFacts;
import kairon.semantics.SemanticSourceRoleCatalog;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Product-scope input profiles for the Phase 0 LLM journal observer.
 *
 * <p>The broad target manifests retain the product candidates discovered
 * during replay analysis. Runtime manifests admit only event classes whose
 * official semantics have been researched and which implement
 * {@link LlmPresentableJournalEvent}. Admission is technical readiness, not a
 * COMMENT rule: the LLM alone still chooses SILENT or COMMENT.</p>
 */
public final class LlmJournalEventSelection {

    /** The {@code ReceiveText} channel carrying ambient NPC chatter. */
    private static final String NPC_CHANNEL = "npc";

    /**
     * The profile names, which no longer carry a count.
     *
     * <p>They used to: {@code BALANCED-112} pinned the number of admitted
     * types into the identity of the profile, and class initialisation refused
     * to start unless the list was exactly that long. That was a review
     * milestone frozen as an invariant — it said "these many wire types have
     * been researched" — and it stopped being answerable once a wire type could
     * dispatch to more than one class. A journal record that carries three
     * domain events is still one researched wire type and three admitted
     * classes, and no single number is both.</p>
     *
     * <p>What the number was actually protecting is kept below: a class listed
     * twice is still a defect, and that is checked structurally rather than by
     * arithmetic against a constant somebody has to remember to bump.</p>
     */
    public static final String TARGET_NEW_PROFILE_NAME = "BALANCED";
    public static final String TARGET_CONTEXT_PROFILE_NAME = "CONTEXT";

    public static final String NEW_PROFILE_NAME =
            TARGET_NEW_PROFILE_NAME;
    public static final String CONTEXT_PROFILE_NAME =
            TARGET_CONTEXT_PROFILE_NAME;

    /**
     * The researched product target, which is what the runtime profile is.
     *
     * <p>Both are the one classification held by
     * {@link SemanticSourceRoleCatalog}. They were two hand-maintained copies
     * of the same 112 class literals, checked against each other at class
     * initialisation; the check could only ever catch a divergence that a
     * single list makes impossible. What the observer still owns is everything
     * about the <em>model</em>: the profile names and counts below, the
     * presentation-readiness requirement, and {@link #admitsAsTrigger}.</p>
     */
    public static final List<Class<? extends JournalEventObservation>>
            TARGET_NEW_ELIGIBLE = SemanticSourceRoleCatalog.newEventTypes();

    public static final List<Class<? extends JournalEventObservation>>
            TARGET_CONTEXT_ONLY =
                    SemanticSourceRoleCatalog.contextOnlyEventTypes();

    public static final List<Class<? extends JournalEventObservation>>
            NEW_ELIGIBLE = TARGET_NEW_ELIGIBLE;

    public static final List<Class<? extends JournalEventObservation>>
            CONTEXT_ONLY = TARGET_CONTEXT_ONLY;

    private static final Set<Class<? extends JournalEventObservation>>
            NEW_EVENT_TYPE_SET = Set.copyOf(NEW_ELIGIBLE);
    private static final Set<Class<? extends JournalEventObservation>>
            CONTEXT_EVENT_TYPE_SET = Set.copyOf(CONTEXT_ONLY);

    /**
     * How many classes each profile admits, counted rather than declared.
     *
     * <p>Derived from the one list. A count that is read off the thing it
     * counts cannot disagree with it.</p>
     */
    public static final int NEW_EVENT_TYPE_COUNT = NEW_ELIGIBLE.size();
    public static final int CONTEXT_EVENT_TYPE_COUNT = CONTEXT_ONLY.size();
    public static final int SUBSCRIBED_EVENT_TYPE_COUNT =
            NEW_EVENT_TYPE_COUNT + CONTEXT_EVENT_TYPE_COUNT;

    static {
        requireDistinct(
                NEW_PROFILE_NAME,
                NEW_ELIGIBLE,
                NEW_EVENT_TYPE_SET
        );
        requireDistinct(
                CONTEXT_PROFILE_NAME,
                CONTEXT_ONLY,
                CONTEXT_EVENT_TYPE_SET
        );
        /*
         * Disjointness is the catalogue's own invariant and is checked where
         * the lists are declared. Repeating it here would be a second guard on
         * a single list, which is the shape this class has just stopped having.
         */
        for (Class<? extends JournalEventObservation> eventType
                : subscribedEventTypes()) {
            if (!LlmPresentableJournalEvent.class
                    .isAssignableFrom(eventType)) {
                throw new ExceptionInInitializerError(
                        "Active LLM event type lacks sourced presentation: "
                                + eventType.getName()
                );
            }
        }
    }

    private LlmJournalEventSelection() {
    }

    public static List<Class<? extends JournalEventObservation>>
            newEventTypes() {
        return NEW_ELIGIBLE;
    }

    public static List<Class<? extends JournalEventObservation>>
            contextEventTypes() {
        return CONTEXT_ONLY;
    }

    public static List<Class<? extends JournalEventObservation>>
            subscribedEventTypes() {
        return java.util.stream.Stream.concat(
                        NEW_ELIGIBLE.stream(),
                        CONTEXT_ONLY.stream()
                )
                .toList();
    }

    public static List<Class<? extends JournalEventObservation>>
            targetNewEventTypes() {
        return TARGET_NEW_ELIGIBLE;
    }

    public static List<Class<? extends JournalEventObservation>>
            targetContextEventTypes() {
        return TARGET_CONTEXT_ONLY;
    }

    /**
     * The observer's name for a type's semantic source role.
     *
     * <p>A translation, not a second classification:
     * {@link SemanticSourceRoleCatalog} owns the answer and this spells it in
     * the observer's own vocabulary. The two cannot disagree, because there is
     * only one of them.</p>
     */
    public static ObserverInputRole roleOf(
            Class<? extends JournalEventObservation> eventType
    ) {
        Objects.requireNonNull(eventType, "eventType");
        return switch (SemanticSourceRoleCatalog.roleOf(eventType)) {
            case NEW -> ObserverInputRole.NEW_ELIGIBLE;
            case CONTEXT_ONLY -> ObserverInputRole.CONTEXT_ONLY;
            case DIAGNOSTIC_ONLY, STATUS, CONTROL ->
                    ObserverInputRole.DIAGNOSTIC_ONLY;
        };
    }

    /**
     * The admission rules a type alone cannot express.
     *
     * <p>{@link #roleOf} classifies by type and remains the only source-role
     * classifier. This is a narrower question asked afterwards, and only of a
     * type that is already {@code NEW_ELIGIBLE}: does <em>this</em> observation
     * belong in a model batch?</p>
     *
     * <p>Three cases say no, and each is a property of the record rather than a
     * judgement about it.</p>
     *
     * <p>A {@code ReceiveText} on the {@code npc} channel is station and
     * traffic chatter addressed to nobody in particular; it is not something
     * the Commander is waiting to be told about, and the measured replay's only
     * NPC message was a system-name announcement the Commander was already
     * looking at. The decision is made on the {@code Channel} field alone —
     * never on the message text and never on a localised rendering, either of
     * which would make admission depend on language.</p>
     *
     * <p>A {@code Scan.BodyReading} that is not the detailed one established
     * nothing: an automatic scan is the ship noticing a body while flying past,
     * and a basic one is a name and a distance. A scan filed under no body
     * established nothing that can be attributed either. One shallow reading is
     * kept, and it is the one the parser gave its own class:
     * {@code Scan.UndiscoveredStar}, a star reporting
     * {@code WasDiscovered: false}, which is the only record that ever says
     * nobody had been to this system. Whether that star is the one this visit
     * arrived at is not something the record can answer, so it is decided
     * afterwards by {@link BodySurveyNoveltyGuard} against the arrival this
     * subscriber saw.</p>
     *
     * <p>A signal record — from either scanner — reporting no positive count
     * of anything is the instrument saying it found nothing, on a body it
     * cannot even name. It is not a finding, and it is explicitly not evidence
     * that a previously reported signal is gone.</p>
     *
     * <p>Excluded here means excluded from model input only. The observation is
     * still parsed, still published, still projected into canonical state and
     * the behaviour graph, still carries its semantic effect into the next
     * turn, and still reaches the trace, the corpus and the GUI.</p>
     */
    public static boolean admitsAsTrigger(JournalEventObservation event) {
        Objects.requireNonNull(event, "event");
        if (event instanceof ReceiveText message) {
            JsonNode channel = message.raw().parsedJsonObject().get("Channel");
            return channel == null
                    || !channel.isTextual()
                    || !NPC_CHANNEL.equalsIgnoreCase(
                            channel.textValue().strip()
                    );
        }
        if (event instanceof Scan scan) {
            // The milestone is admitted by being what it is: which of the two
            // readings this record is was decided by the parser, from these
            // same fields, and asking again here is how the two answers drift.
            return scan instanceof Scan.UndiscoveredStar
                    || BodySurveyFacts.scanSignature(
                            scan.raw().parsedJsonObject()
                    ) != null;
        }
        if (event instanceof FSSBodySignals || event instanceof SAASignalsFound) {
            return BodySurveyFacts.signalSignature(
                    event.raw().parsedJsonObject()
            ) != null;
        }
        return true;
    }

    /**
     * A profile admits each class once.
     *
     * <p>What the removed count check was really for. A class listed twice
     * would be admitted twice and reviewed once, and no arithmetic against a
     * remembered constant is needed to notice that.</p>
     */
    private static void requireDistinct(
            String profileName,
            List<Class<? extends JournalEventObservation>> eventTypes,
            Set<Class<? extends JournalEventObservation>> eventTypeSet
    ) {
        if (eventTypes.isEmpty()) {
            throw new ExceptionInInitializerError(
                    profileName + " admits nothing"
            );
        }
        if (eventTypes.size() != eventTypeSet.size()) {
            throw new ExceptionInInitializerError(
                    profileName + " lists an event type more than once"
            );
        }
    }

    public enum ObserverInputRole {
        NEW_ELIGIBLE,
        CONTEXT_ONLY,
        DIAGNOSTIC_ONLY
    }
}
