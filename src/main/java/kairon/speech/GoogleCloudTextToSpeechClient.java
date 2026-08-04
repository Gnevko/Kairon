package kairon.speech;

import com.google.api.core.ApiFuture;
import com.google.cloud.texttospeech.v1.AudioConfig;
import com.google.cloud.texttospeech.v1.AudioEncoding;
import com.google.cloud.texttospeech.v1.SynthesisInput;
import com.google.cloud.texttospeech.v1.SynthesizeSpeechRequest;
import com.google.cloud.texttospeech.v1.SynthesizeSpeechResponse;
import com.google.cloud.texttospeech.v1.TextToSpeechClient;
import com.google.cloud.texttospeech.v1.TextToSpeechSettings;
import com.google.cloud.texttospeech.v1.VoiceSelectionParams;
import kairon.config.KaironConfiguration.SpeechConfiguration;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static kairon.speech.SpeechSynthesisClient.SpeechFailureCategory.CLIENT_INITIALIZATION;
import static kairon.speech.SpeechSynthesisClient.SpeechFailureCategory.SYNTHESIS_REQUEST;
import static kairon.speech.SpeechSynthesisClient.SpeechFailureCategory.SYNTHESIS_RESPONSE;

/**
 * Official Google Cloud Text-to-Speech client using the API key loaded from
 * the adjacent authentication file. No credential value crosses diagnostics.
 */
public final class GoogleCloudTextToSpeechClient
        implements SpeechSynthesisClient {

    private final TextToSpeechClient client;
    private final VoiceSelectionParams voice;
    private final AudioConfig audio;
    private final AtomicReference<ApiFuture<SynthesizeSpeechResponse>>
            activeSynthesis = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public GoogleCloudTextToSpeechClient(
            SpeechConfiguration configuration,
            String apiKey
    ) {
        Objects.requireNonNull(configuration, "configuration");
        if (!configuration.enabled()) {
            throw new IllegalArgumentException(
                    "Google speech client requires enabled speech"
            );
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException(
                    "Google speech client requires an API key"
            );
        }

        this.voice = VoiceSelectionParams.newBuilder()
                .setLanguageCode(configuration.languageCode())
                .setName(configuration.voiceName())
                .build();
        this.audio = AudioConfig.newBuilder()
                .setAudioEncoding(AudioEncoding.LINEAR16)
                .setSpeakingRate(configuration.speakingRate())
                .setPitch(configuration.pitch())
                .setVolumeGainDb(configuration.volumeGainDb())
                .build();

        try {
            TextToSpeechSettings.Builder settings =
                    TextToSpeechSettings.newBuilder()
                            .setApiKey(apiKey);
            settings.synthesizeSpeechSettings()
                    .setSimpleTimeoutNoRetriesDuration(
                            configuration.requestTimeout()
                    );
            this.client = TextToSpeechClient.create(settings.build());
        } catch (IOException | RuntimeException failure) {
            throw new SpeechClientInitializationException(
                    CLIENT_INITIALIZATION
            );
        }
    }

    @Override
    public byte[] synthesize(String text) throws SpeechSynthesisException {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must be nonblank");
        }
        if (closed.get()) {
            throw new SpeechSynthesisException(SYNTHESIS_REQUEST);
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new SpeechSynthesisException(
                    SpeechFailureCategory.CANCELLED
            );
        }

        SynthesizeSpeechRequest request =
                SynthesizeSpeechRequest.newBuilder()
                        .setInput(SynthesisInput.newBuilder().setText(text))
                        .setVoice(voice)
                        .setAudioConfig(audio)
                        .build();
        final ApiFuture<SynthesizeSpeechResponse> call;
        try {
            call = client.synthesizeSpeechCallable().futureCall(request);
        } catch (RuntimeException failure) {
            throw new SpeechSynthesisException(SYNTHESIS_REQUEST);
        }
        if (!activeSynthesis.compareAndSet(null, call)) {
            call.cancel(true);
            throw new SpeechSynthesisException(SYNTHESIS_REQUEST);
        }
        if (closed.get() || Thread.currentThread().isInterrupted()) {
            call.cancel(true);
        }

        final SynthesizeSpeechResponse response;
        try {
            response = call.get();
        } catch (CancellationException failure) {
            throw new SpeechSynthesisException(
                    SpeechFailureCategory.CANCELLED
            );
        } catch (InterruptedException failure) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            throw new SpeechSynthesisException(
                    SpeechFailureCategory.CANCELLED
            );
        } catch (ExecutionException | RuntimeException failure) {
            throw new SpeechSynthesisException(SYNTHESIS_REQUEST);
        } finally {
            activeSynthesis.compareAndSet(call, null);
        }

        byte[] wavAudio = response.getAudioContent().toByteArray();
        if (wavAudio.length == 0) {
            throw new SpeechSynthesisException(SYNTHESIS_RESPONSE);
        }
        return wavAudio;
    }

    @Override
    public void cancelCurrentSynthesis() {
        ApiFuture<SynthesizeSpeechResponse> call = activeSynthesis.get();
        if (call != null) {
            call.cancel(true);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        cancelCurrentSynthesis();
        try {
            client.close();
        } catch (RuntimeException ignored) {
            // Do not expose Google/Auth exception text or credential paths.
        }
    }

    public static final class SpeechClientInitializationException
            extends IllegalStateException {

        private final SpeechFailureCategory category;

        private SpeechClientInitializationException(
                SpeechFailureCategory category
        ) {
            super("GOOGLE_CLOUD_TTS_CLIENT_INITIALIZATION_FAILED");
            this.category = category;
        }

        public SpeechFailureCategory category() {
            return category;
        }
    }
}
