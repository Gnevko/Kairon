package kairon.speech;

/**
 * Blocking synthesis boundary. Callers must invoke it away from observation
 * and coordinator threads.
 */
public interface SpeechSynthesisClient extends AutoCloseable {

    byte[] synthesize(String text) throws SpeechSynthesisException;

    /**
     * Requests cancellation of the synthesis currently owned by this client.
     * It is a no-op when no synthesis is active and must leave the client
     * reusable for a later request.
     */
    void cancelCurrentSynthesis();

    @Override
    default void close() {
    }

    enum SpeechFailureCategory {
        NONE,
        CLIENT_INITIALIZATION,
        SYNTHESIS_REQUEST,
        SYNTHESIS_RESPONSE,
        WAV_DECODING,
        OUTPUT_DEVICE,
        AUDIO_LINE,
        PLAYBACK_IO,
        CANCELLED,
        INTERNAL
    }

    final class SpeechSynthesisException extends Exception {

        private final SpeechFailureCategory category;

        public SpeechSynthesisException(SpeechFailureCategory category) {
            super("speech synthesis failed");
            this.category = category;
        }

        public SpeechFailureCategory category() {
            return category;
        }
    }
}
