package kairon.speech;

import kairon.speech.SpeechSynthesisClient.SpeechFailureCategory;

/**
 * Blocking local-audio boundary. Successful return means audible playback
 * reached its completion barrier.
 */
public interface AudioPlayer extends AutoCloseable {

    void play(byte[] wavAudio, String outputDevice)
            throws AudioPlaybackException;

    /**
     * Stops only the current playback, if any, while keeping the player
     * reusable for the next queued request.
     */
    void cancelCurrentPlayback();

    @Override
    default void close() {
    }

    final class AudioPlaybackException extends Exception {

        private final SpeechFailureCategory category;

        public AudioPlaybackException(SpeechFailureCategory category) {
            super("audio playback failed");
            this.category = category;
        }

        public SpeechFailureCategory category() {
            return category;
        }
    }
}
