package kairon.semantics;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.event.session.Shutdown;
import kairon.observation.journal.event.travel.FSDJump;
import kairon.observation.journal.event.travel.Location;

import java.util.Objects;

/**
 * When a visit to a system begins, continues and ends.
 *
 * <p>Pure and stateless. The behaviour graph and the observer's novelty memory
 * both ask it, and neither reads the other: two independent memories of one
 * visit are correct, two independent definitions of when that visit starts are
 * not. They were two — the graph's episode boundaries and the guard's
 * {@code trackVisitBoundary} — and a disagreement between them is exactly how a
 * finding the graph had just recorded came to be silenced by a memory that had
 * outlived its visit.</p>
 *
 * <p>No state ownership moves here. The graph keeps its {@code SystemEpisode},
 * its timeline and its persistence; the observer keeps the scanner results it
 * has already been told about. What is shared is the answer, not the memory.</p>
 */
public final class SystemVisitPolicy {

    private SystemVisitPolicy() {
    }

    /**
     * What this journal observation does to the visit in progress.
     *
     * <p>The order of the rules is the whole of the policy. A shutdown ends the
     * visit whatever else the record is. A completed jump is next, and it is the
     * one arrival that carries an arrival body — a jump that also changes vessel
     * is still an arrival, and calling it anything else would lose the star it
     * arrived at. A different Commander or ship then starts a new visit whether
     * or not the system changed, because a different vessel is a different run
     * of findings; that is what makes a restore into a different ship a boundary
     * even in the system already in progress. A restore otherwise starts a visit
     * only when there is none in progress or it names a different system —
     * restating the system already in progress is not a second look at it — and
     * it is deferred entirely until the Commander and ship are known, because a
     * visit with no identity belongs to no graph and would be reopened the
     * moment the identity arrived.</p>
     */
    public static SystemVisitTransition of(
            JournalEventObservation event,
            SystemVisitState current
    ) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(current, "current");
        if (event instanceof Shutdown) {
            return new SystemVisitTransition(
                    SystemVisitTransition.Kind.END,
                    null,
                    null,
                    SystemVisitTransition.Reason.SESSION_ENDED
            );
        }
        if (event instanceof FSDJump) {
            return new SystemVisitTransition(
                    SystemVisitTransition.Kind.BEGIN,
                    current.observedSystemAddress(),
                    arrivalBodyOf(event),
                    SystemVisitTransition.Reason.HYPERSPACE_ARRIVAL
            );
        }
        if (current.vesselChanged()) {
            return new SystemVisitTransition(
                    SystemVisitTransition.Kind.BEGIN,
                    current.observedSystemAddress(),
                    null,
                    SystemVisitTransition.Reason.VESSEL_CHANGED
            );
        }
        if (event instanceof Location) {
            if (!current.identityEstablished()) {
                return new SystemVisitTransition(
                        SystemVisitTransition.Kind.CONTINUE,
                        current.observedSystemAddress(),
                        null,
                        SystemVisitTransition.Reason.IDENTITY_PENDING
                );
            }
            return current.restoresAnotherSystem()
                    ? new SystemVisitTransition(
                            SystemVisitTransition.Kind.BEGIN,
                            current.observedSystemAddress(),
                            null,
                            SystemVisitTransition.Reason.SESSION_RESTORED
                    )
                    : new SystemVisitTransition(
                            SystemVisitTransition.Kind.CONTINUE,
                            current.observedSystemAddress(),
                            null,
                            SystemVisitTransition.Reason.LOCATION_RESTATED
                    );
        }
        return new SystemVisitTransition(
                SystemVisitTransition.Kind.CONTINUE,
                current.observedSystemAddress(),
                null,
                SystemVisitTransition.Reason.ORDINARY_EVENT
        );
    }

    /** The replay ran out of records; the visit it was in is over. */
    public static SystemVisitTransition replayCompleted() {
        return new SystemVisitTransition(
                SystemVisitTransition.Kind.END,
                null,
                null,
                SystemVisitTransition.Reason.REPLAY_COMPLETED
        );
    }

    /** The source was closed; the visit it was in is over. */
    public static SystemVisitTransition sourceClosed() {
        return new SystemVisitTransition(
                SystemVisitTransition.Kind.END,
                null,
                null,
                SystemVisitTransition.Reason.SOURCE_CLOSED
        );
    }

    /**
     * The body an arrival arrived at, or null.
     *
     * <p>Only a completed jump has one. Read from the record's own address and
     * body id through {@link BodySurveyFacts#bodyIdentity}, which is the same
     * reader a scanner result is resolved by — so "the body this visit arrived
     * at" and "the body this reading is about" are comparable values rather than
     * two derivations that happen to agree.</p>
     */
    public static BodyIdentity arrivalBodyOf(JournalEventObservation event) {
        Objects.requireNonNull(event, "event");
        return event instanceof FSDJump
                ? BodySurveyFacts.bodyIdentity(event.raw().parsedJsonObject())
                : null;
    }

    /**
     * The body a recorded arrival arrived at, read from its own attributes.
     *
     * <p>For a caller holding the arrival as stored data rather than as the
     * original observation — the graph's episode root. Same reader, so the two
     * cannot answer differently for one arrival.</p>
     */
    public static BodyIdentity arrivalBodyOf(JsonNode arrivalRecord) {
        return BodySurveyFacts.bodyIdentity(arrivalRecord);
    }

    /**
     * Whether this reading is the visit's one arrival-star discovery.
     *
     * <p>Three conditions, and each is a fact rather than a judgement: the
     * record is a star reading reporting no prior discovery, it is filed under
     * the body this visit arrived at, and the visit has not been told yet. A
     * visit that was not opened by an arrival has no arrival body and admits
     * none — which is the fail-closed answer, and the reason a restored session
     * never mints the milestone.</p>
     *
     * <p>One rule for both layers. The graph reads its own episode to answer
     * "already reported"; the observer reads its own flag. Neither owns the
     * rule, and a reading one admits is a reading the other records.</p>
     */
    public static boolean isVisitArrivalStarReading(
            BodyIdentity arrivalBody,
            JsonNode reading,
            boolean alreadyReported
    ) {
        return !alreadyReported
                && arrivalBody != null
                && BodySurveyFacts.undiscoveredStarReading(reading)
                && arrivalBody.equals(BodySurveyFacts.bodyIdentity(reading));
    }

    /**
     * The caller's visit, as the policy needs to see it.
     *
     * <p>Four facts and no behaviour. {@code observedCommanderFid} and
     * {@code observedShipId} are what canonical state says now; the two
     * {@code visit*} fields are what the caller's own memory says the visit in
     * progress belongs to. A vessel change is the two disagreeing, and it needs
     * both halves of the current identity to be known — an identity that has not
     * arrived yet is not a change of vessel.</p>
     *
     * @param inProgress            whether the caller has a visit open at all
     * @param visitSystemAddress    the system that visit is of, or null
     * @param visitCommanderFid     the Commander that visit belongs to, or null
     * @param visitShipId           the ship that visit belongs to, or null
     * @param observedSystemAddress the system canonical state reports now
     * @param observedCommanderFid  the Commander canonical state reports now
     * @param observedShipId        the ship canonical state reports now
     */
    public record SystemVisitState(
            boolean inProgress,
            Long visitSystemAddress,
            String visitCommanderFid,
            Long visitShipId,
            Long observedSystemAddress,
            String observedCommanderFid,
            Long observedShipId
    ) {

        /** Whether the Commander and ship are both established. */
        public boolean identityEstablished() {
            return observedCommanderFid != null && observedShipId != null;
        }

        /** Whether the established identity differs from the visit's own. */
        public boolean vesselChanged() {
            return identityEstablished()
                    && (!observedCommanderFid.equals(visitCommanderFid)
                    || !observedShipId.equals(visitShipId));
        }

        /**
         * Whether a restore describes a system this visit is not of.
         *
         * <p>True with no visit in progress: there is nothing for the restore
         * to be a restatement of. An address the record does not establish is
         * never a difference — silence is not a second system.</p>
         */
        public boolean restoresAnotherSystem() {
            return !inProgress
                    || observedSystemAddress != null
                    && !observedSystemAddress.equals(visitSystemAddress);
        }
    }
}
