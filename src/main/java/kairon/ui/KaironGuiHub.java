package kairon.ui;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Central, UI-toolkit-neutral presentation ingress.
 *
 * <p>The hub is a read-only monitor. It is neither an external observation
 * bus nor a comment-delivery channel.</p>
 */
public interface KaironGuiHub extends AutoCloseable {

    boolean enabled();

    void start();

    void postObservation(ObservationView observation);

    void postObservationEffect(ObservationEffectView effect);

    void postModelDecision(ModelDecisionView decision);

    void postModelCompletion(ModelCompletionView completion);

    CompletionStage<Void> closeRequested();

    @Override
    void close();

    static KaironGuiHub disabled() {
        return DisabledGuiHub.INSTANCE;
    }

    record ObservationView(
            String observationId,
            long busSequence,
            Instant observedAt,
            Optional<Instant> sourceTime,
            String source,
            String sourcePosition,
            String captureMode,
            String eventType,
            String payloadType,
            String rawJson
    ) {

        public ObservationView {
            observationId = requireNonBlank(
                    observationId,
                    "observationId"
            );
            if (busSequence < 1) {
                throw new IllegalArgumentException(
                        "busSequence must be positive"
                );
            }
            observedAt = Objects.requireNonNull(observedAt, "observedAt");
            sourceTime = Objects.requireNonNull(sourceTime, "sourceTime");
            source = requireNonBlank(source, "source");
            sourcePosition = requireNonBlank(
                    sourcePosition,
                    "sourcePosition"
            );
            captureMode = requireNonBlank(captureMode, "captureMode");
            eventType = requireNonBlank(eventType, "eventType");
            payloadType = requireNonBlank(payloadType, "payloadType");
            rawJson = Objects.requireNonNull(rawJson, "rawJson");
        }
    }

    record ModelDecisionView(
            long turnSequence,
            Instant resolvedAt,
            int eventCount,
            String status,
            String decision,
            String text,
            List<Long> triggerBusSequences,
            List<String> violations,
            String failure,
            String rawModelOutput,
            long latencyMs
    ) {

        public ModelDecisionView {
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
            status = requireNonBlank(status, "status");
            triggerBusSequences = List.copyOf(
                    Objects.requireNonNull(
                            triggerBusSequences,
                            "triggerBusSequences"
                    )
            );
            violations = List.copyOf(
                    Objects.requireNonNull(violations, "violations")
            );
            if (latencyMs < 0) {
                throw new IllegalArgumentException(
                        "latencyMs must be non-negative"
                );
            }
        }
    }

    record ObservationEffectView(
            String observationId,
            long busSequence,
            Instant changedAt,
            String effect,
            Long turnSequence
    ) {

        public ObservationEffectView {
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
            effect = requireNonBlank(effect, "effect");
            if (turnSequence != null && turnSequence < 1) {
                throw new IllegalArgumentException(
                        "turnSequence must be positive"
                );
            }
        }
    }

    record ModelCompletionView(
            long turnSequence,
            Instant completedAt,
            String consoleOutcome,
            String speechOutcome,
            boolean deliveredForHistory,
            String deliveredComment
    ) {

        public ModelCompletionView {
            if (turnSequence < 1) {
                throw new IllegalArgumentException(
                        "turnSequence must be positive"
                );
            }
            completedAt = Objects.requireNonNull(
                    completedAt,
                    "completedAt"
            );
            consoleOutcome = requireNonBlank(
                    consoleOutcome,
                    "consoleOutcome"
            );
            speechOutcome = requireNonBlank(
                    speechOutcome,
                    "speechOutcome"
            );
        }
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must be nonblank");
        }
        return value;
    }

    final class DisabledGuiHub implements KaironGuiHub {

        private static final DisabledGuiHub INSTANCE = new DisabledGuiHub();
        private final CompletableFuture<Void> closeRequested =
                new CompletableFuture<>();

        private DisabledGuiHub() {
        }

        @Override
        public boolean enabled() {
            return false;
        }

        @Override
        public void start() {
        }

        @Override
        public void postObservation(ObservationView observation) {
            Objects.requireNonNull(observation, "observation");
        }

        @Override
        public void postObservationEffect(ObservationEffectView effect) {
            Objects.requireNonNull(effect, "effect");
        }

        @Override
        public void postModelDecision(ModelDecisionView decision) {
            Objects.requireNonNull(decision, "decision");
        }

        @Override
        public void postModelCompletion(ModelCompletionView completion) {
            Objects.requireNonNull(completion, "completion");
        }

        @Override
        public CompletionStage<Void> closeRequested() {
            return closeRequested;
        }

        @Override
        public void close() {
        }
    }
}
