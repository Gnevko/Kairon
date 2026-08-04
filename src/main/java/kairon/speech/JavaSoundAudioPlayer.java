package kairon.speech;

import kairon.speech.SpeechSynthesisClient.SpeechFailureCategory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static kairon.speech.SpeechSynthesisClient.SpeechFailureCategory.AUDIO_LINE;
import static kairon.speech.SpeechSynthesisClient.SpeechFailureCategory.CANCELLED;
import static kairon.speech.SpeechSynthesisClient.SpeechFailureCategory.OUTPUT_DEVICE;
import static kairon.speech.SpeechSynthesisClient.SpeechFailureCategory.PLAYBACK_IO;
import static kairon.speech.SpeechSynthesisClient.SpeechFailureCategory.WAV_DECODING;

/**
 * WAV decoding and synchronous completion playback through Java Sound.
 */
public final class JavaSoundAudioPlayer implements AudioPlayer {

    private static final int BUFFER_SIZE = 16 * 1024;

    private final Object lifecycleGate = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean playbackCancellationRequested =
            new AtomicBoolean();
    private final AtomicReference<SourceDataLine> activeLine =
            new AtomicReference<>();

    @Override
    public void play(byte[] wavAudio, String outputDevice)
            throws AudioPlaybackException {
        Objects.requireNonNull(wavAudio, "wavAudio");
        if (wavAudio.length == 0) {
            throw new AudioPlaybackException(WAV_DECODING);
        }
        if (closed.get()) {
            throw new AudioPlaybackException(CANCELLED);
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new AudioPlaybackException(CANCELLED);
        }

        byte[] immutableInput = Arrays.copyOf(wavAudio, wavAudio.length);
        try (AudioInputStream stream = AudioSystem.getAudioInputStream(
                new BufferedInputStream(
                        new ByteArrayInputStream(immutableInput)
                )
        )) {
            playDecoded(stream, outputDevice);
        } catch (UnsupportedAudioFileException failure) {
            throw new AudioPlaybackException(
                    cancelled() ? CANCELLED : WAV_DECODING
            );
        } catch (IOException failure) {
            throw new AudioPlaybackException(
                    cancelled() ? CANCELLED : PLAYBACK_IO
            );
        }
    }

    private void playDecoded(AudioInputStream stream, String outputDevice)
            throws AudioPlaybackException {
        AudioFormat format = stream.getFormat();
        DataLine.Info lineInfo = new DataLine.Info(
                SourceDataLine.class,
                format
        );
        SourceDataLine line = openLine(lineInfo, outputDevice);
        try {
            synchronized (lifecycleGate) {
                if (closed.get()
                        || Thread.currentThread().isInterrupted()) {
                    line.close();
                    throw new AudioPlaybackException(CANCELLED);
                }
                playbackCancellationRequested.set(false);
                if (!activeLine.compareAndSet(null, line)) {
                    line.close();
                    throw new AudioPlaybackException(AUDIO_LINE);
                }
                line.open(format);
                line.start();
            }
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                if (cancelled()) {
                    throw new AudioPlaybackException(CANCELLED);
                }
                int offset = 0;
                while (offset < read) {
                    if (cancelled()) {
                        throw new AudioPlaybackException(CANCELLED);
                    }
                    int written = line.write(
                            buffer,
                            offset,
                            read - offset
                    );
                    if (written <= 0) {
                        throw new AudioPlaybackException(
                                cancelled() ? CANCELLED : AUDIO_LINE
                        );
                    }
                    offset += written;
                }
            }
            line.drain();
            if (cancelled()) {
                throw new AudioPlaybackException(CANCELLED);
            }
        } catch (LineUnavailableException failure) {
            throw new AudioPlaybackException(
                    cancelled() ? CANCELLED : AUDIO_LINE
            );
        } catch (IOException failure) {
            throw new AudioPlaybackException(
                    cancelled() ? CANCELLED : PLAYBACK_IO
            );
        } catch (IllegalArgumentException | IllegalStateException failure) {
            throw new AudioPlaybackException(
                    cancelled() ? CANCELLED : AUDIO_LINE
            );
        } finally {
            activeLine.compareAndSet(line, null);
            attemptLineAction(line::stop);
            attemptLineAction(line::close);
        }
    }

    private static SourceDataLine openLine(
            DataLine.Info lineInfo,
            String outputDevice
    ) throws AudioPlaybackException {
        if (outputDevice == null) {
            try {
                return (SourceDataLine) AudioSystem.getLine(lineInfo);
            } catch (LineUnavailableException | IllegalArgumentException failure) {
                throw new AudioPlaybackException(AUDIO_LINE);
            }
        }

        Mixer.Info selected = Arrays.stream(AudioSystem.getMixerInfo())
                .filter(info -> info.getName().equals(outputDevice))
                .findFirst()
                .orElseThrow(() ->
                        new AudioPlaybackException(OUTPUT_DEVICE)
                );
        Mixer mixer = AudioSystem.getMixer(selected);
        if (!mixer.isLineSupported(lineInfo)) {
            throw new AudioPlaybackException(OUTPUT_DEVICE);
        }
        try {
            return (SourceDataLine) mixer.getLine(lineInfo);
        } catch (LineUnavailableException | IllegalArgumentException failure) {
            throw new AudioPlaybackException(AUDIO_LINE);
        }
    }

    @Override
    public void cancelCurrentPlayback() {
        playbackCancellationRequested.set(true);
        synchronized (lifecycleGate) {
            SourceDataLine line = activeLine.get();
            if (line != null) {
                attemptLineAction(line::stop);
                attemptLineAction(line::flush);
                attemptLineAction(line::close);
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        cancelCurrentPlayback();
    }

    private boolean cancelled() {
        return closed.get()
                || playbackCancellationRequested.get()
                || Thread.currentThread().isInterrupted();
    }

    private static void attemptLineAction(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // Continue closing the remaining line resources.
        }
    }
}
