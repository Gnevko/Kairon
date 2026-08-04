package kairon.output;

import kairon.speech.SpeechSynthesisClient.SpeechFailureCategory;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Asynchronous output boundary used only after a model COMMENT has passed
 * validation.
 */
public interface CommentSink extends AutoCloseable {

    CompletionStage<CommentDeliveryResult> deliver(String comment);

    SpeechDescriptor speechDescriptor();

    @Override
    default void close() {
    }

    enum ConsoleOutcome {
        NOT_ATTEMPTED,
        SKIPPED,
        DELIVERED,
        FAILED
    }

    enum SpeechOutcome {
        NOT_REQUESTED,
        DISABLED,
        SYNTHESIZING,
        QUEUED_FOR_PLAYBACK,
        PLAYING,
        DELIVERED,
        SYNTHESIS_FAILED,
        PLAYBACK_FAILED,
        CANCELLED
    }

    record SpeechDescriptor(
            boolean enabled,
            String provider,
            String voiceName
    ) {

        public SpeechDescriptor {
            if (enabled) {
                requireNonBlank(provider, "provider");
                requireNonBlank(voiceName, "voiceName");
            }
        }

        public static SpeechDescriptor disabled(
                String provider,
                String voiceName
        ) {
            return new SpeechDescriptor(false, provider, voiceName);
        }
    }

    record SpeechDeliveryResult(
            SpeechOutcome outcome,
            SpeechFailureCategory failureCategory,
            Instant synthesisStartedAt,
            Instant synthesisCompletedAt,
            Instant playbackStartedAt,
            Instant playbackCompletedAt
    ) {

        public SpeechDeliveryResult {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(failureCategory, "failureCategory");
        }

        public static SpeechDeliveryResult notAttempted(
                SpeechDescriptor descriptor
        ) {
            Objects.requireNonNull(descriptor, "descriptor");
            return new SpeechDeliveryResult(
                    descriptor.enabled()
                            ? SpeechOutcome.NOT_REQUESTED
                            : SpeechOutcome.DISABLED,
                    SpeechFailureCategory.NONE,
                    null,
                    null,
                    null,
                    null
            );
        }

        public static SpeechDeliveryResult cancelled() {
            return new SpeechDeliveryResult(
                    SpeechOutcome.CANCELLED,
                    SpeechFailureCategory.CANCELLED,
                    null,
                    null,
                    null,
                    null
            );
        }
    }

    record CommentDeliveryResult(
            SpeechDescriptor speech,
            ConsoleOutcome consoleOutcome,
            SpeechDeliveryResult speechResult
    ) {

        public CommentDeliveryResult {
            Objects.requireNonNull(speech, "speech");
            Objects.requireNonNull(consoleOutcome, "consoleOutcome");
            Objects.requireNonNull(speechResult, "speechResult");
        }

        public static CommentDeliveryResult notAttempted(
                SpeechDescriptor descriptor
        ) {
            return new CommentDeliveryResult(
                    descriptor,
                    ConsoleOutcome.NOT_ATTEMPTED,
                    SpeechDeliveryResult.notAttempted(descriptor)
            );
        }

        public static CommentDeliveryResult cancelled(
                SpeechDescriptor descriptor
        ) {
            return new CommentDeliveryResult(
                    descriptor,
                    ConsoleOutcome.NOT_ATTEMPTED,
                    descriptor.enabled()
                            ? SpeechDeliveryResult.cancelled()
                            : SpeechDeliveryResult.notAttempted(descriptor)
            );
        }

        /**
         * A console-only comment is heard after a successful print. With
         * speech enabled, only completed playback is considered heard.
         */
        public boolean deliveredForHistory() {
            return speech.enabled()
                    ? speechResult.outcome() == SpeechOutcome.DELIVERED
                    : consoleOutcome == ConsoleOutcome.DELIVERED;
        }
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be nonblank");
        }
    }
}
