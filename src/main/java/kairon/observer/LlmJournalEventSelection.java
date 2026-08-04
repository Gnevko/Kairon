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

    public static final String TARGET_NEW_PROFILE_NAME = "BALANCED-112";
    public static final String TARGET_CONTEXT_PROFILE_NAME = "CONTEXT-2";
    public static final int TARGET_NEW_EVENT_TYPE_COUNT = 112;
    public static final int TARGET_CONTEXT_EVENT_TYPE_COUNT = 2;

    public static final String NEW_PROFILE_NAME =
            TARGET_NEW_PROFILE_NAME;
    public static final String CONTEXT_PROFILE_NAME =
            TARGET_CONTEXT_PROFILE_NAME;
    public static final int NEW_EVENT_TYPE_COUNT =
            TARGET_NEW_EVENT_TYPE_COUNT;
    public static final int CONTEXT_EVENT_TYPE_COUNT =
            TARGET_CONTEXT_EVENT_TYPE_COUNT;
    public static final int SUBSCRIBED_EVENT_TYPE_COUNT =
            NEW_EVENT_TYPE_COUNT + CONTEXT_EVENT_TYPE_COUNT;

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

    static {
        requireProfile(
                NEW_PROFILE_NAME,
                NEW_ELIGIBLE,
                NEW_EVENT_TYPE_SET,
                NEW_EVENT_TYPE_COUNT
        );
        requireProfile(
                CONTEXT_PROFILE_NAME,
                CONTEXT_ONLY,
                CONTEXT_EVENT_TYPE_SET,
                CONTEXT_EVENT_TYPE_COUNT
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
     * <p>A {@code Scan} that is not the detailed one established nothing: an
     * automatic scan is the ship noticing a body while flying past, and a basic
     * one is a name and a distance. A scan filed under no body established
     * nothing that can be attributed either. One shallow reading is kept: a star
     * reporting {@code WasDiscovered: false}, which is the only record that ever
     * says nobody had been to this system. Whether that star is the one this
     * visit arrived at is not something the record can answer, so it is decided
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
            JsonNode raw = scan.raw().parsedJsonObject();
            return BodySurveyFacts.scanSignature(raw) != null
                    || BodySurveyFacts.undiscoveredStarReading(raw);
        }
        if (event instanceof FSSBodySignals || event instanceof SAASignalsFound) {
            return BodySurveyFacts.signalSignature(
                    event.raw().parsedJsonObject()
            ) != null;
        }
        return true;
    }

    private static void requireProfile(
            String profileName,
            List<Class<? extends JournalEventObservation>> eventTypes,
            Set<Class<? extends JournalEventObservation>> eventTypeSet,
            int expectedCount
    ) {
        if (eventTypes.size() != expectedCount
                || eventTypeSet.size() != expectedCount) {
            throw new ExceptionInInitializerError(
                    profileName + " must contain exactly "
                            + expectedCount + " distinct event types"
            );
        }
    }

    public enum ObserverInputRole {
        NEW_ELIGIBLE,
        CONTEXT_ONLY,
        DIAGNOSTIC_ONLY
    }
}
