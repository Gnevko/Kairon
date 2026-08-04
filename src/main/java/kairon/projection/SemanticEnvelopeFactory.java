package kairon.projection;

import kairon.observation.PublishedObservation;
import kairon.observation.journal.JournalEventObservation;
import kairon.semantics.SemanticAdapterRegistry;
import kairon.semantics.SemanticEventAdapter;
import kairon.semantics.SemanticFact;
import kairon.semantics.SemanticObservationEnvelope;
import kairon.semantics.SemanticProvenance;
import kairon.semantics.SemanticSubject;
import kairon.semantics.UnresolvedFact;
import kairon.state.AppliedObservation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Builds the immutable semantic envelope for one already-projected
 * observation.
 *
 * <p>Pure with respect to its inputs: it reads the publication and the applied
 * observation and nothing else. It never consults a mutable projector or graph
 * service, so it introduces no late read.</p>
 *
 * <p>It classifies nothing. {@link kairon.state.AppliedObservation} owns the
 * result of classification and this copies it, so the envelope and the applied
 * value cannot say different things about the same observation. Structured
 * facts are still adapted here, from the payload, because they are the one
 * semantic product that is not part of applying the observation to state.</p>
 *
 * <p>It lives beside {@link ObservationProjectionCoordinator}, its only
 * production caller, because it needs a {@code kairon.state} value and
 * {@code kairon.semantics} types at once and the projection boundary is where
 * those two already meet. Putting it in either of them would have made that
 * package depend on the other in the wrong direction; here both reads point
 * away from it, and the envelope it returns still belongs to
 * {@code kairon.semantics}.</p>
 *
 * <p>Error policy: a failing or missing adapter yields empty structured facts
 * plus an explicit unresolved entry, and projection publication continues. A
 * semantic mapping gap must never strand an observation whose state and graph
 * effects have already been applied.</p>
 */
public final class SemanticEnvelopeFactory {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(SemanticEnvelopeFactory.class);

    private final SemanticAdapterRegistry registry;

    public SemanticEnvelopeFactory(SemanticAdapterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public static SemanticEnvelopeFactory production() {
        return new SemanticEnvelopeFactory(
                SemanticAdapterRegistry.production()
        );
    }

    /**
     * The semantic view of one observation that has already been applied.
     *
     * <p>Everything semantic is copied from {@code applied}: the bus sequence,
     * the observation id, the raw type, the capture mode, the source role, the
     * effect retention and the exact delta. Nothing is classified
     * again here — in particular retention is not re-derived from the capture
     * mode this envelope also carries, because two derivations of one rule are
     * two places for it to change. So the
     * envelope cannot disagree with the value that owns the classification —
     * which two independent computations of the same pure function could not
     * guarantee, only make unlikely.</p>
     *
     * <p>{@code observation} supplies what belongs to the publication rather
     * than to the applied moment — the source, the position in it, the source
     * time and the observed time — and the payload the adapters read. It is not
     * consulted for metadata.</p>
     */
    public SemanticObservationEnvelope create(
            PublishedObservation<?> observation,
            AppliedObservation applied
    ) {
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(applied, "applied");
        if (observation.busSequence() != applied.busSequence()) {
            throw new IllegalArgumentException(
                    "applied observation does not belong to this publication"
            );
        }

        SemanticProvenance provenance = new SemanticProvenance(
                applied.busSequence(),
                applied.sourceRole(),
                applied.rawObservationType(),
                applied.observationId()
        );

        List<SemanticFact> facts = List.of();
        List<UnresolvedFact> unresolved = List.of();
        if (observation.payload() instanceof JournalEventObservation event) {
            SemanticEventAdapter.Result result =
                    adaptSafely(event, provenance);
            facts = result.facts();
            unresolved = result.unresolved();
        }

        return new SemanticObservationEnvelope(
                applied.busSequence(),
                observation.source(),
                applied.sourceRole(),
                applied.captureMode(),
                applied.effectRetention(),
                observation.sourcePosition(),
                observation.sourceTime(),
                observation.observedAt(),
                applied.rawObservationType(),
                facts,
                applied.semanticChanges(),
                unresolved
        );
    }

    private SemanticEventAdapter.Result adaptSafely(
            JournalEventObservation event,
            SemanticProvenance provenance
    ) {
        try {
            return registry.adapt(event, provenance);
        } catch (RuntimeException adapterFailure) {
            LOGGER.error(
                    "SEMANTIC_ADAPTER_FAILED busSequence={} eventType={} "
                            + "category={}",
                    provenance.busSequence(),
                    provenance.rawObservationType(),
                    adapterFailure.getClass().getSimpleName(),
                    adapterFailure
            );
            List<UnresolvedFact> gap = new ArrayList<>(1);
            gap.add(new UnresolvedFact(
                    SemanticSubject.UNRESOLVED_SUBJECT,
                    UnresolvedFact.Reason.NO_SEMANTIC_ADAPTER,
                    provenance
            ));
            return new SemanticEventAdapter.Result(List.of(), gap);
        }
    }
}
