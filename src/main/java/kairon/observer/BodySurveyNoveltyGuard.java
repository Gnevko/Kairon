package kairon.observer;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.event.exploration.CodexEntry;
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
 * result when the same instrument says the same thing about the same body. The
 * memory of what has been seen is this subscriber's own, and it lasts exactly
 * one visit.</p>
 *
 * <h2>Why per instrument</h2>
 * <p>The two scanners answer different questions. The system scanner counts the
 * signals a body carries; the surface scanner fires probes at it and names the
 * organisms. Compared by count alone, a survey confirming what the system scan
 * already said looked like the same result — so the one reading that says which
 * organisms are down there never opened a turn, and the names reached the model
 * only later, if at all. A second reading by the <em>same</em> instrument is
 * still the same result and is still declined.</p>
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
 * <h2>Why the empty-body rule is here too</h2>
 * <p>Since 2026-08-08 this also declines a body reading of a body nothing was
 * ever reported to be on, unless looking at it entered something new in the
 * codex. That is a different question from novelty — not "has this been said"
 * but "is there anything to say" — and it lives here because the answer is the
 * memory this class already keeps. Which bodies bore signals this visit is
 * exactly {@code systemSignalResults} and {@code surfaceSignalResults}; a
 * second class asking the same question would keep a second per-visit memory
 * of the same records, and two memories of one thing are how they come to
 * disagree.</p>
 *
 * <p>Declining here is not a comment-worthiness judgement. The result is
 * unchanged, so there is no second event to report; what the model does with
 * the first one is entirely its own decision. The empty-body rule is the one
 * thing here that comes close, and it is drawn on what the game reported about
 * the body rather than on any reading of what the reading is worth.</p>
 */
final class BodySurveyNoveltyGuard {

    private final Map<BodyIdentity, String> scanResults = new TreeMap<>();
    private final Map<BodyIdentity, String> systemSignalResults =
            new TreeMap<>();
    private final Map<BodyIdentity, String> surfaceSignalResults =
            new TreeMap<>();

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
     * Whether the trigger before this one entered something new in the codex.
     *
     * <p>One step of lookback and no more. It is the whole of the exception to
     * the signals rule, and it is kept here rather than derived from a batch
     * because this guard is asked about triggers one at a time, in bus order,
     * which is the order the journal wrote them in.</p>
     */
    private boolean precededByNewCodexEntry;

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
     *
     * <p>Two questions, both answered from the same one visit's memory. Whether
     * the model has already been told this result, and — since 2026-08-08 —
     * whether there is anything on the body to tell it about.</p>
     */
    boolean admits(JournalEventObservation event) {
        Objects.requireNonNull(event, "event");
        boolean afterCodexDiscovery = precededByNewCodexEntry;
        precededByNewCodexEntry = isNewCodexEntry(event);
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
        // A reading is compared against readings from the same instrument. The
        // two scanners answer different questions — the system scanner counts
        // the signals a body carries, the surface scanner fires probes and
        // names the organisms — so a survey repeating the count the system scan
        // gave is not the same result told twice. Compared by count alone, the
        // one reading that names them never opened a turn.
        Map<BodyIdentity, String> seen = scan
                ? scanResults
                : (event instanceof SAASignalsFound
                        ? surfaceSignalResults
                        : systemSignalResults);
        if (signature.equals(seen.get(body))) {
            return false;
        }
        if (scan && !afterCodexDiscovery && !bearsSignals(body)) {
            // Nothing is on it and nothing about it was new to the codex, so
            // there is nothing to say. Deliberately not recorded: the reading
            // was declined for what the body is, not for having been told, and
            // a scanner result that arrives after the signals do must still be
            // able to open its own turn.
            return false;
        }
        seen.put(body, signature);
        return true;
    }

    /**
     * Whether anything was ever reported as being on this body, this visit.
     *
     * <p>A body reading names a rock and says nothing about what is on it; the
     * signals records are what say that, and they are the same records this
     * guard already remembers per instrument. Measured over 836 detailed scans
     * in this Commander's journals, 175 were of a body with a signals record —
     * so four scans in five are of a body with nothing on it, and those are the
     * turns this declines.</p>
     *
     * <p>The order is not a guess: in all 175 the signals record came
     * <em>before</em> the scan, 172 of them immediately before. So by the time a
     * scan is asked about, whether the body bears anything is already known.</p>
     *
     * <p>A body no signals record ever mentioned is therefore an empty one, and
     * the failure direction is silence — a body whose signals were established
     * on an earlier visit and never restated is declined. That is the quiet way
     * to be wrong, and it is the one this project prefers.</p>
     */
    private boolean bearsSignals(BodyIdentity body) {
        return systemSignalResults.containsKey(body)
                || surfaceSignalResults.containsKey(body);
    }

    /**
     * Whether this record is a discovery the codex had not held before.
     *
     * <p>The exception to the rule above, and the Commander's own: a body with
     * nothing on it is still worth a word when looking at it entered something
     * new into the codex. The codex record does not name the body it came from
     * — its {@code BodyID} is 0 in every one of these journals — so the link is
     * that it is the trigger immediately before, which it was in all 60 cases
     * where a new codex entry was followed by a scan at all.</p>
     */
    private static boolean isNewCodexEntry(JournalEventObservation event) {
        if (!(event instanceof CodexEntry entry)) {
            return false;
        }
        JsonNode stated = entry.raw().parsedJsonObject().get("IsNewEntry");
        return stated != null && stated.asBoolean(false);
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
        precededByNewCodexEntry = false;
        scanResults.clear();
        systemSignalResults.clear();
        surfaceSignalResults.clear();
    }

    private void endVisit() {
        visitInProgress = false;
        visitSystemAddress = null;
        visitArrivalBody = null;
        arrivalDiscoveryReported = false;
        precededByNewCodexEntry = false;
        scanResults.clear();
        systemSignalResults.clear();
        surfaceSignalResults.clear();
    }
}
