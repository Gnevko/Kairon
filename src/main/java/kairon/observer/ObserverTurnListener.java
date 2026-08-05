package kairon.observer;

import kairon.llm.ObserverResponseValidator.ValidatedObserverResponse;
import kairon.output.CommentSink.CommentDeliveryResult;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Read-only monitoring port for observer activity.
 *
 * <p>Implementations must be handoff-only. Listener failure is isolated by
 * {@link ObserverTurnCoordinator} and cannot change model, output, trace, or
 * queue semantics.</p>
 */
public interface ObserverTurnListener {

    default void onObservationEffectChanged(
            ObservationEffectChanged change
    ) {
    }

    default void onDecisionResolved(DecisionResolved decision) {
    }

    default void onTurnCompleted(TurnCompleted completed) {
    }

    static ObserverTurnListener noOp() {
        return NoOp.INSTANCE;
    }

    /**
     * Latest observer-owned effect for one immutable shared observation.
     *
     * <p>Effects describe observer processing only. They are not written back
     * to the {@code PublishedObservation} and are not published through the
     * external observation bus.</p>
     */
    record ObservationEffectChanged(
            String observationId,
            long busSequence,
            Instant changedAt,
            ObservationEffect effect,
            Long turnSequence
    ) {

        public ObservationEffectChanged {
            observationId = requireNonBlank(
                    observationId,
                    "observationId"
            );
            if (busSequence < 1) {
                throw new IllegalArgumentException(
                        "busSequence must be positive"
                );
            }
            changedAt = Objects.requireNonNull(changedAt, "changedAt");
            effect = Objects.requireNonNull(effect, "effect");
            if (turnSequence != null && turnSequence < 1) {
                throw new IllegalArgumentException(
                        "turnSequence must be positive"
                );
            }
            if (effect.requiresTurnBinding() != (turnSequence != null)) {
                throw new IllegalArgumentException(
                        effect + " turn binding presence is invalid"
                );
            }
        }
    }

    enum ObservationEffect {
        NEW_QUEUED(false),
        NEW_IN_FLIGHT(true),
        NEW_PROCESSED(true),
        NEW_FAILED(true),
        NEW_DISCARDED(false);

        private final boolean requiresTurnBinding;

        ObservationEffect(boolean requiresTurnBinding) {
            this.requiresTurnBinding = requiresTurnBinding;
        }

        boolean requiresTurnBinding() {
            return requiresTurnBinding;
        }
    }

    /**
     * @param triggerBusSequences the observations this turn was built from, in
     *                            bus order. A fact about the batch, computed by
     *                            the coordinator — the response says nothing
     *                            about which observations it answered, and is
     *                            given no way to.
     */
    record DecisionResolved(
            long turnSequence,
            Instant resolvedAt,
            int eventCount,
            List<Long> triggerBusSequences,
            ValidatedObserverResponse validatedResponse,
            String rawModelOutput,
            long latencyMs
    ) {

        public DecisionResolved {
            if (turnSequence < 1) {
                throw new IllegalArgumentException(
                        "turnSequence must be positive"
                );
            }
            resolvedAt = Objects.requireNonNull(resolvedAt, "resolvedAt");
            if (eventCount < 1) {
                throw new IllegalArgumentException(
                        "eventCount must be positive"
                );
            }
            triggerBusSequences = List.copyOf(Objects.requireNonNull(
                    triggerBusSequences,
                    "triggerBusSequences"
            ));
            validatedResponse = Objects.requireNonNull(
                    validatedResponse,
                    "validatedResponse"
            );
            if (latencyMs < 0) {
                throw new IllegalArgumentException(
                        "latencyMs must be non-negative"
                );
            }
        }
    }

    record TurnCompleted(
            long turnSequence,
            Instant completedAt,
            CommentDeliveryResult delivery,
            String deliveredComment
    ) {

        public TurnCompleted {
            if (turnSequence < 1) {
                throw new IllegalArgumentException(
                        "turnSequence must be positive"
                );
            }
            completedAt = Objects.requireNonNull(completedAt, "completedAt");
            delivery = Objects.requireNonNull(delivery, "delivery");
        }
    }

    enum NoOp implements ObserverTurnListener {
        INSTANCE
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must be nonblank");
        }
        return value;
    }
}
