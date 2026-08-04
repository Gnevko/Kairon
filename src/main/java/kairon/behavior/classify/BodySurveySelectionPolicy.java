package kairon.behavior.classify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kairon.behavior.model.EventOccurrence;
import kairon.behavior.normalize.NormalizedBehaviorEvent;
import kairon.behavior.normalize.NormalizedEventType;
import kairon.observation.ObservationDraft.ObservationCaptureMode;
import kairon.semantics.BodyIdentity;
import kairon.semantics.BodySurveyFacts;
import kairon.semantics.SystemVisitPolicy;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Whether a scanner record is a distinct result or the same one restated.
 *
 * <p>Three journal records report what a scanner established about a body, and
 * the game emits each of them more freely than the Commander gets a result.
 * A {@code Scan} arrives at every depth, including the automatic one the ship
 * performs while flying past. A signal set is reported by the system scanner
 * and then confirmed, unchanged, by the surface one. None of that is a second
 * finding.</p>
 *
 * <p>Three admissions are refused, and none is about interest:</p>
 *
 * <ul>
 *   <li>a scan that established nothing — not detailed, or filed under no
 *       body. The one exception is the reading of the body this visit arrived
 *       at that reports no prior discovery: it establishes that nobody had been
 *       here, which no other record in the visit says;</li>
 *   <li>a reading identical to the last reading of the same body in this
 *       visit, whichever scanner produced it;</li>
 *   <li>a reading captured historically, before Kairon was watching.</li>
 * </ul>
 *
 * <p>Compared on the exact facts, never on timestamps, adjacency or raw JSON:
 * a result restated after three other events is still the same result, and a
 * result that changed is a new one however quickly it followed.</p>
 *
 * <h2>Why historical capture is refused here and only here</h2>
 * <p>A scanner finding is the one kind of structural event whose recording
 * decides whether a <em>later</em> observation is a finding at all. Record a
 * {@code BOOTSTRAP} result and the live reading repeating it is deduplicated
 * against an occurrence nobody was ever told about: the model is given an event
 * with no occurrence of its own, standing after a predecessor in the trajectory
 * that is the same finding under another name. So the structural occurrence and
 * the model-facing event belong to the same observation, or to neither.</p>
 *
 * <p>This is not a general rule about historical capture, and nothing else
 * changes: an ordinary structural event recorded during bootstrap is recorded
 * exactly as before. Canonical state is not affected either — it is projected
 * before the graph is consulted, so a historical reading still restores what it
 * established about the body.</p>
 */
public final class BodySurveySelectionPolicy {

    private static final Set<NormalizedEventType> SIGNAL_TYPES = Set.of(
            NormalizedEventType.FSS_BODY_SIGNALS_FOUND,
            NormalizedEventType.SAA_SIGNALS_FOUND
    );

    /**
     * Whether the candidate should create a structural occurrence.
     *
     * <p>{@code episodeTimeline} is this visit's occurrences in order and
     * {@code captureMode} is the mode the observation was captured under; every
     * type this policy does not own is admitted unchanged, whatever its capture
     * mode.</p>
     */
    public boolean shouldRecord(
            List<EventOccurrence> episodeTimeline,
            NormalizedBehaviorEvent candidate,
            ObservationCaptureMode captureMode
    ) {
        Objects.requireNonNull(episodeTimeline, "episodeTimeline");
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(captureMode, "captureMode");
        NormalizedEventType eventType = candidate.eventType();
        if (NormalizedEventType.BODY_SCANNED.equals(eventType)) {
            return !historical(captureMode)
                    && admits(
                            episodeTimeline,
                            candidate,
                            Set.of(NormalizedEventType.BODY_SCANNED),
                            true
                    );
        }
        if (SIGNAL_TYPES.contains(eventType)) {
            return !historical(captureMode)
                    && admits(episodeTimeline, candidate, SIGNAL_TYPES, false);
        }
        if (NormalizedEventType.SYSTEM_UNDISCOVERED_CONFIRMED
                .equals(eventType)) {
            return !historical(captureMode)
                    && firstArrivalStarReading(episodeTimeline, candidate);
        }
        return true;
    }

    /**
     * Whether this is the first reading of the body this visit arrived at.
     *
     * <p>The rule is {@link SystemVisitPolicy#isVisitArrivalStarReading}, which
     * the observer's novelty guard asks too. Only what each layer answers
     * "already reported" with differs: this episode's own occurrences here, a
     * flag there. Neither reads the other, and a reading one admits is a
     * reading the other records.</p>
     *
     * <p>The arrival body is the one the episode root names, and nothing else:
     * the root is the completed jump that opened this visit, so its
     * {@code SystemAddress} and {@code BodyID} are what "the arrival star"
     * means here. It is read through the same
     * {@link SystemVisitPolicy#arrivalBodyOf} the guard's own arrival came
     * from, so the two cannot resolve one arrival differently. A candidate
     * filed under any other body is a star the ship flew past — a system can
     * hold a dozen of them, each undiscovered — and it is not what the
     * milestone claims.</p>
     *
     * <p>An episode with no root takes no reading at all. A restored session is
     * not an arrival, so it has no arrival body, and deriving one from a
     * {@code Location} would mint the milestone on a session restart in a
     * system the Commander may have been sitting in for an hour.</p>
     *
     * <p>Recorded once per visit. A second automatic scan of the same star
     * establishes the same thing it established the first time, and a second
     * occurrence would give the model a milestone that never happened twice.</p>
     */
    private static boolean firstArrivalStarReading(
            List<EventOccurrence> episodeTimeline,
            NormalizedBehaviorEvent candidate
    ) {
        return SystemVisitPolicy.isVisitArrivalStarReading(
                arrivalBodyOf(episodeTimeline),
                asJson(candidate.attributes()),
                alreadyReported(episodeTimeline)
        );
    }

    /** The body this episode's root arrived at, or null when it has none. */
    private static BodyIdentity arrivalBodyOf(
            List<EventOccurrence> episodeTimeline
    ) {
        if (episodeTimeline.isEmpty()) {
            return null;
        }
        EventOccurrence root = episodeTimeline.getFirst();
        return NormalizedEventType.SYSTEM_ENTRY.equals(root.eventType())
                ? SystemVisitPolicy.arrivalBodyOf(asJson(root.attributes()))
                : null;
    }

    /** Whether this visit has already recorded the milestone. */
    private static boolean alreadyReported(
            List<EventOccurrence> episodeTimeline
    ) {
        for (EventOccurrence occurrence : episodeTimeline) {
            if (NormalizedEventType.SYSTEM_UNDISCOVERED_CONFIRMED
                    .equals(occurrence.eventType())) {
                return true;
            }
        }
        return false;
    }

    /** Whether this observation was captured before Kairon was watching. */
    private static boolean historical(ObservationCaptureMode captureMode) {
        return captureMode == ObservationCaptureMode.BOOTSTRAP;
    }

    private static boolean admits(
            List<EventOccurrence> episodeTimeline,
            NormalizedBehaviorEvent candidate,
            Set<NormalizedEventType> comparableTypes,
            boolean scan
    ) {
        JsonNode raw = asJson(candidate.attributes());
        String signature = scan
                ? BodySurveyFacts.scanSignature(raw)
                : BodySurveyFacts.signalSignature(raw);
        if (signature == null) {
            return false;
        }
        BodyIdentity body = BodySurveyFacts.bodyIdentity(raw);
        for (int index = episodeTimeline.size() - 1; index >= 0; index--) {
            EventOccurrence occurrence = episodeTimeline.get(index);
            if (!comparableTypes.contains(occurrence.eventType())) {
                continue;
            }
            JsonNode previous = asJson(occurrence.attributes());
            if (!body.equals(BodySurveyFacts.bodyIdentity(previous))) {
                continue;
            }
            String previousSignature = scan
                    ? BodySurveyFacts.scanSignature(previous)
                    : BodySurveyFacts.signalSignature(previous);
            return !signature.equals(previousSignature);
        }
        return true;
    }

    /**
     * The selected attributes as one object, so the shared reader can be used.
     *
     * <p>An occurrence stores exactly the attributes the normalizer selected,
     * which is the same set the signature is built from. Rebuilding an object
     * around them keeps one implementation of the rule rather than two.</p>
     */
    private static JsonNode asJson(Map<String, JsonNode> attributes) {
        ObjectNode object = JsonNodeFactory.instance.objectNode();
        attributes.forEach(object::set);
        return object;
    }
}
