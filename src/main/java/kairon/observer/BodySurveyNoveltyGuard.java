package kairon.observer;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.event.exploration.FSSBodySignals;
import kairon.observation.journal.event.exploration.SAASignalsFound;
import kairon.observation.journal.event.exploration.Scan;
import kairon.semantics.BodyIdentity;
import kairon.semantics.BodySurveyFacts;
import kairon.semantics.SystemVisitPolicy;
import kairon.semantics.SystemVisitPolicy.SystemVisitState;
import kairon.semantics.SystemVisitTransition;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Subscriber-owned memory of which scanner results have already opened a turn.
 *
 * <p>The rule it applies is {@link BodySurveyFacts}: a result is the same
 * result when it says the same thing about the same body. The memory of what
 * has been seen is this subscriber's own, and it lasts exactly one visit.</p>
 *
 * <h2>Why a visit and not a system</h2>
 * <p>Coming back to a system is looking at its bodies again, and the first
 * reading of that second look is the first reading of that look. The behaviour
 * graph already scopes its own deduplication to one {@code SystemEpisode}; a
 * memory keyed by system address alone would outlive the visit and silence a
 * finding the graph had just recorded. The two begin and end a visit on the
 * same observations because both ask {@link SystemVisitPolicy}.</p>
 *
 * <h2>Why not simply read the graph</h2>
 * <p>Deliberately not a reading of the behaviour graph's decision. Whether an
 * observation reaches the model must not depend on whether the graph is
 * enabled, and the graph's classification governs its own granularity only.
 * Both consult the same rule and the same boundaries; neither obeys the other,
 * and this guard reads nothing that a persisted graph owns.</p>
 *
 * <p>Declining here is not a comment-worthiness judgement. The result is
 * unchanged, so there is no second event to report; what the model does with
 * the first one is entirely its own decision.</p>
 */
final class BodySurveyNoveltyGuard {

    private final Map<BodyIdentity, String> scanResults = new TreeMap<>();
    private final Map<BodyIdentity, String> signalResults = new TreeMap<>();

    /**
     * Which visit the memory belongs to.
     *
     * <p>Internal and monotonic. Never published, never serialized, never
     * shown to the model: it exists only so that "this visit" is a thing the
     * guard can compare against rather than a thing it has to infer.</p>
     */
    private long visitSequence;
    private boolean visitInProgress;
    private Long visitSystemAddress;
    private String visitCommanderFid;
    private Long visitShipId;

    /**
     * The body this visit arrived at, or null when the visit was not an
     * arrival.
     *
     * <p>Supplied by the shared policy, which reads it from the completed jump
     * that opened the visit and from nothing else. A restored session is not an
     * arrival: it names the body the ship happens to be sitting at, which may
     * have been reached an hour earlier, and the behaviour graph refuses to
     * treat it as an episode root for the same reason. Null therefore means no
     * arrival-star milestone can be admitted this visit, which is the
     * fail-closed answer.</p>
     */
    private BodyIdentity visitArrivalBody;
    private boolean arrivalDiscoveryReported;

    /**
     * Follows the observations that begin a visit, whatever their capture mode.
     *
     * <p>Called for every journal observation, including historical
     * {@code BOOTSTRAP} capture: where the Commander is is true regardless of
     * whether the model was told about getting there, and a guard that only
     * learned about jumps it was allowed to comment on would carry one visit's
     * findings into the next.</p>
     *
     * <p>What each observation means for the visit is
     * {@link SystemVisitPolicy}'s answer, not this class's. All this owns is
     * the memory the answer applies to.</p>
     */
    void trackVisitBoundary(
            JournalEventObservation event,
            String commanderFid,
            Long shipId,
            Long systemAddress
    ) {
        Objects.requireNonNull(event, "event");
        apply(
                SystemVisitPolicy.of(
                        event,
                        new SystemVisitState(
                                visitInProgress,
                                visitSystemAddress,
                                visitCommanderFid,
                                visitShipId,
                                systemAddress,
                                commanderFid,
                                shipId
                        )
                ),
                commanderFid,
                shipId
        );
    }

    /**
     * The visit ends with the source that was feeding it.
     *
     * <p>The graph completes its episode on replay exhaustion and on close, and
     * a memory that survived either would carry one run's findings into the
     * next. The transition is the policy's, so what counts as the end is not
     * decided here.</p>
     */
    void endVisit(SystemVisitTransition transition) {
        apply(
                Objects.requireNonNull(transition, "transition"),
                visitCommanderFid,
                visitShipId
        );
    }

    private void apply(
            SystemVisitTransition transition,
            String commanderFid,
            Long shipId
    ) {
        if (transition.ends()) {
            endVisit();
            return;
        }
        if (transition.begins()) {
            beginVisit(
                    commanderFid,
                    shipId,
                    transition.systemAddress(),
                    transition.arrivalBody()
            );
        }
    }

    /**
     * Whether this scanner result is one the model has not been told yet.
     *
     * <p>Records this guard does not own are admitted unchanged. Admitting a
     * result records it, so the same result cannot be admitted twice; a record
     * that establishes nothing is declined and records nothing.</p>
     */
    boolean admits(JournalEventObservation event) {
        Objects.requireNonNull(event, "event");
        boolean scan = event instanceof Scan;
        boolean signals = event instanceof FSSBodySignals
                || event instanceof SAASignalsFound;
        if (!scan && !signals) {
            return true;
        }
        JsonNode raw = event.raw().parsedJsonObject();
        String signature = scan
                ? BodySurveyFacts.scanSignature(raw)
                : BodySurveyFacts.signalSignature(raw);
        if (signature == null) {
            return scan && admitsArrivalDiscovery(raw);
        }
        BodyIdentity body = BodySurveyFacts.bodyIdentity(raw);
        Map<BodyIdentity, String> seen = scan ? scanResults : signalResults;
        if (signature.equals(seen.get(body))) {
            return false;
        }
        seen.put(body, signature);
        return true;
    }

    /**
     * Whether this reading is the visit's one arrival-star discovery.
     *
     * <p>The rule is {@link SystemVisitPolicy#isVisitArrivalStarReading} and
     * the graph's survey policy asks the same one. Only the memory of whether
     * this visit has already been told differs — a flag here, the episode's own
     * occurrences there. Neither reads the other, and a reading one admits is a
     * reading the other records.</p>
     */
    private boolean admitsArrivalDiscovery(JsonNode raw) {
        if (!SystemVisitPolicy.isVisitArrivalStarReading(
                visitArrivalBody,
                raw,
                arrivalDiscoveryReported
        )) {
            return false;
        }
        arrivalDiscoveryReported = true;
        return true;
    }

    /** The current visit, for diagnostics and tests. Never model-facing. */
    long visitSequence() {
        return visitSequence;
    }

    private void beginVisit(
            String commanderFid,
            Long shipId,
            Long systemAddress,
            BodyIdentity arrivalBody
    ) {
        visitSequence++;
        visitInProgress = true;
        visitCommanderFid = commanderFid;
        visitShipId = shipId;
        visitSystemAddress = systemAddress;
        visitArrivalBody = arrivalBody;
        arrivalDiscoveryReported = false;
        scanResults.clear();
        signalResults.clear();
    }

    private void endVisit() {
        visitInProgress = false;
        visitSystemAddress = null;
        visitArrivalBody = null;
        arrivalDiscoveryReported = false;
        scanResults.clear();
        signalResults.clear();
    }
}
