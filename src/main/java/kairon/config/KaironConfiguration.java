package kairon.config;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Strict, immutable runtime configuration loaded from one explicitly selected
 * JSON file and its adjacent, untracked authentication file.
 */
public record KaironConfiguration(
        SourceConfiguration source,
        ObserverConfiguration observer,
        UiConfiguration ui,
        LlmConfiguration llm,
        SpeechConfiguration speech,
        BehaviorGraphConfiguration behaviorGraph,
        ObservationCorpusConfiguration observationCorpus,
        BioConfiguration bio,
        AuthenticationConfiguration authentication
) {

    private static final String CONFIG_ARGUMENT_PREFIX = "--config=";
    private static final String AUTHENTICATION_FILE_NAME =
            "authentication.json";
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .disable(JsonReadFeature.ALLOW_YAML_COMMENTS)
            .disable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .disable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
            .disable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();

    public KaironConfiguration {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(observer, "observer");
        Objects.requireNonNull(ui, "ui");
        Objects.requireNonNull(llm, "llm");
        Objects.requireNonNull(speech, "speech");
        Objects.requireNonNull(behaviorGraph, "behaviorGraph");
        Objects.requireNonNull(observationCorpus, "observationCorpus");
        Objects.requireNonNull(authentication, "authentication");
    }

    /**
     * Loads configuration from exactly one {@code --config=<path>} launcher argument.
     */
    public static KaironConfiguration load(String[] args) {
        if (args == null || args.length != 1 || args[0] == null
                || !args[0].startsWith(CONFIG_ARGUMENT_PREFIX)) {
            throw failure("CONFIG_ARGUMENT_INVALID", "--config");
        }

        String configuredPath = args[0].substring(CONFIG_ARGUMENT_PREFIX.length());
        if (configuredPath.isBlank()) {
            throw failure("CONFIG_ARGUMENT_INVALID", "--config");
        }

        try {
            return load(Path.of(configuredPath));
        } catch (InvalidPathException exception) {
            throw failure("CONFIG_ARGUMENT_INVALID", "--config");
        }
    }

    /**
     * Loads configuration from a path resolved against the process working directory.
     */
    public static KaironConfiguration load(Path path) {
        if (path == null) {
            throw failure("CONFIG_ARGUMENT_INVALID", "--config");
        }

        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        Path configurationPath = resolveAgainstWorkingDirectory(path, workingDirectory);
        if (!Files.isRegularFile(configurationPath) || !Files.isReadable(configurationPath)) {
            throw failure("CONFIG_FILE_UNREADABLE", "--config");
        }

        RawConfiguration rawConfiguration = readStrictJson(configurationPath);
        Path authenticationPath = configurationPath.resolveSibling(
                AUTHENTICATION_FILE_NAME
        );
        RawAuthenticationConfiguration rawAuthentication =
                readStrictAuthenticationJson(authenticationPath);
        return validate(
                rawConfiguration,
                rawAuthentication,
                workingDirectory
        );
    }

    /**
     * Resolves only the selected provider's optional API key.
     */
    public ResolvedProviderConfiguration resolveActiveProvider() {
        String profileName = llm.activeProvider();
        LlmProviderConfiguration profile = llm.providers().get(profileName);
        Optional<String> apiKey = authentication.llmApiKey(profileName);

        return new ResolvedProviderConfiguration(
                profileName,
                profile.type(),
                profile.baseUrl(),
                profile.model(),
                apiKey,
                profile.temperature(),
                profile.maximumOutputTokens(),
                profile.requestTimeout(),
                profile.responseFormat(),
                profile.pricing()
        );
    }

    /**
     * Returns the already validated Google Cloud TTS API key.
     */
    public String googleCloudTextToSpeechApiKey() {
        return authentication.googleCloudTextToSpeechApiKey()
                .orElseThrow(() -> failure(
                        "AUTHENTICATION_GOOGLE_TTS_KEY_MISSING",
                        "$.speech.googleCloudTts.apiKey"
                ));
    }

    private static RawConfiguration readStrictJson(Path configurationPath) {
        final byte[] bytes;
        try {
            bytes = Files.readAllBytes(configurationPath);
        } catch (IOException exception) {
            throw failure("CONFIG_FILE_UNREADABLE", "--config");
        }

        final String json;
        try {
            var decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            json = stripUtf8Bom(decoder.decode(ByteBuffer.wrap(bytes)).toString());
        } catch (CharacterCodingException exception) {
            throw failure("CONFIG_JSON_INVALID", "$", "malformed UTF-8");
        }

        try {
            RawConfiguration result = JSON.readValue(json, RawConfiguration.class);
            if (result == null) {
                throw failure("CONFIG_JSON_INVALID", "$");
            }
            return result;
        } catch (JsonProcessingException exception) {
            throw jsonFailure(exception);
        }
    }

    private static RawAuthenticationConfiguration
    readStrictAuthenticationJson(Path authenticationPath) {
        if (!Files.isRegularFile(authenticationPath)
                || !Files.isReadable(authenticationPath)) {
            throw failure(
                    "AUTHENTICATION_FILE_UNREADABLE",
                    "$authentication"
            );
        }

        final byte[] bytes;
        try {
            bytes = Files.readAllBytes(authenticationPath);
        } catch (IOException exception) {
            throw failure(
                    "AUTHENTICATION_FILE_UNREADABLE",
                    "$authentication"
            );
        }

        final String json;
        try {
            var decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            json = stripUtf8Bom(
                    decoder.decode(ByteBuffer.wrap(bytes)).toString()
            );
        } catch (CharacterCodingException exception) {
            throw failure(
                    "AUTHENTICATION_JSON_INVALID",
                    "$",
                    "malformed UTF-8"
            );
        }

        try {
            RawAuthenticationConfiguration result = JSON.readValue(
                    json,
                    RawAuthenticationConfiguration.class
            );
            if (result == null) {
                throw failure("AUTHENTICATION_JSON_INVALID", "$");
            }
            return result;
        } catch (JsonProcessingException exception) {
            throw jsonFailure("AUTHENTICATION_JSON_INVALID", exception);
        }
    }

    private static KaironConfiguration validate(
            RawConfiguration raw,
            RawAuthenticationConfiguration rawAuthentication,
            Path workingDirectory
    ) {
        if (raw.source == null) {
            throw failure("CONFIG_REQUIRED_VALUE_MISSING", "$.source");
        }
        if (raw.observer == null) {
            throw failure("CONFIG_REQUIRED_VALUE_MISSING", "$.observer");
        }
        if (raw.llm == null) {
            throw failure("CONFIG_REQUIRED_VALUE_MISSING", "$.llm");
        }
        if (raw.ui == null) {
            throw failure("CONFIG_REQUIRED_VALUE_MISSING", "$.ui");
        }
        if (raw.speech == null) {
            throw failure("CONFIG_REQUIRED_VALUE_MISSING", "$.speech");
        }
        if (raw.behaviorGraph == null) {
            throw failure(
                    "CONFIG_REQUIRED_VALUE_MISSING",
                    "$.behaviorGraph"
            );
        }

        SourceConfiguration source = validateSource(raw.source, workingDirectory);
        ObserverConfiguration observer = validateObserver(raw.observer, workingDirectory);
        UiConfiguration ui = validateUi(raw.ui);
        LlmConfiguration llm = validateLlm(
                raw.llm,
                workingDirectory
        );
        SpeechConfiguration speech = validateSpeech(raw.speech);
        BehaviorGraphConfiguration behaviorGraph =
                validateBehaviorGraph(
                        raw.behaviorGraph,
                        workingDirectory
                );
        ObservationCorpusConfiguration observationCorpus =
                validateObservationCorpus(
                        raw.observationCorpus,
                        workingDirectory
                );
        BioConfiguration bio = validateBio(raw.bio, workingDirectory);
        AuthenticationConfiguration authentication =
                validateAuthentication(rawAuthentication, llm, speech);
        return new KaironConfiguration(
                source,
                observer,
                ui,
                llm,
                speech,
                behaviorGraph,
                observationCorpus,
                bio,
                authentication
        );
    }

    /**
     * The organic registry, which is optional and empty by absence.
     *
     * <p>No {@code bio} block and no {@code registryFile} both mean the same
     * thing: run without a registry, and let every organism be named by
     * whatever the journal said. A path that is set is required to exist, so a
     * typo is a startup failure rather than a session that silently names
     * nothing.</p>
     */
    private static BioConfiguration validateBio(
            RawBioConfiguration raw,
            Path workingDirectory
    ) {
        if (raw == null) {
            return new BioConfiguration(null);
        }
        return new BioConfiguration(requiredExistingFile(
                optionalPath(raw.registryFile, "$.bio.registryFile", workingDirectory),
                "$.bio.registryFile"
        ));
    }

    private static Path requiredExistingFile(Path path, String field) {
        if (path != null && !Files.isRegularFile(path)) {
            throw failure("CONFIG_FILE_NOT_FOUND", field);
        }
        return path;
    }

    private static BehaviorGraphConfiguration validateBehaviorGraph(
            RawBehaviorGraphConfiguration raw,
            Path workingDirectory
    ) {
        if (raw.enabled() == null) {
            throw failure(
                    "CONFIG_REQUIRED_VALUE_MISSING",
                    "$.behaviorGraph.enabled"
            );
        }
        Path storageDirectory = requiredPath(
                raw.storageDirectory(),
                "$.behaviorGraph.storageDirectory",
                workingDirectory
        );
        if (!isAccessibleStorageDirectory(storageDirectory)) {
            throw failure(
                    "CONFIG_BEHAVIOR_GRAPH_STORAGE_INVALID",
                    "$.behaviorGraph.storageDirectory"
            );
        }

        String rawHalfLife = requiredText(
                raw.weightHalfLife(),
                "$.behaviorGraph.weightHalfLife"
        );
        final Duration weightHalfLife;
        try {
            weightHalfLife = Duration.parse(rawHalfLife);
        } catch (DateTimeParseException | ArithmeticException exception) {
            throw failure(
                    "CONFIG_BEHAVIOR_GRAPH_HALF_LIFE_INVALID",
                    "$.behaviorGraph.weightHalfLife"
            );
        }
        if (weightHalfLife.isZero() || weightHalfLife.isNegative()) {
            throw failure(
                    "CONFIG_BEHAVIOR_GRAPH_HALF_LIFE_INVALID",
                    "$.behaviorGraph.weightHalfLife"
            );
        }

        Double contextPriorStrength = raw.contextPriorStrength();
        if (contextPriorStrength == null) {
            throw failure(
                    "CONFIG_REQUIRED_VALUE_MISSING",
                    "$.behaviorGraph.contextPriorStrength"
            );
        }
        if (!Double.isFinite(contextPriorStrength)
                || contextPriorStrength <= 0.0) {
            throw failure(
                    "CONFIG_BEHAVIOR_GRAPH_PRIOR_STRENGTH_INVALID",
                    "$.behaviorGraph.contextPriorStrength"
            );
        }

        Integer snapshotEverySignificantEvents =
                raw.snapshotEverySignificantEvents();
        if (snapshotEverySignificantEvents == null) {
            throw failure(
                    "CONFIG_REQUIRED_VALUE_MISSING",
                    "$.behaviorGraph.snapshotEverySignificantEvents"
            );
        }
        if (snapshotEverySignificantEvents <= 0) {
            throw failure(
                    "CONFIG_BEHAVIOR_GRAPH_SNAPSHOT_INTERVAL_INVALID",
                    "$.behaviorGraph.snapshotEverySignificantEvents"
            );
        }

        if (raw.storeRawJournalPayload() == null) {
            throw failure(
                    "CONFIG_REQUIRED_VALUE_MISSING",
                    "$.behaviorGraph.storeRawJournalPayload"
            );
        }
        if (raw.storeRawJournalPayload()) {
            throw failure(
                    "CONFIG_BEHAVIOR_GRAPH_RAW_PAYLOAD_UNSUPPORTED",
                    "$.behaviorGraph.storeRawJournalPayload"
            );
        }

        return new BehaviorGraphConfiguration(
                raw.enabled(),
                storageDirectory,
                weightHalfLife,
                contextPriorStrength,
                snapshotEverySignificantEvents,
                false
        );
    }

    private static ObservationCorpusConfiguration validateObservationCorpus(
            RawObservationCorpusConfiguration raw,
            Path workingDirectory
    ) {
        if (raw == null) {
            return new ObservationCorpusConfiguration(false, null);
        }
        boolean enabled = raw.enabled != null && raw.enabled;
        Path outputFile = enabled
                ? requiredPath(
                        raw.outputFile,
                        "$.observationCorpus.outputFile",
                        workingDirectory
                )
                : optionalPath(
                        raw.outputFile,
                        "$.observationCorpus.outputFile",
                        workingDirectory
                );
        return new ObservationCorpusConfiguration(enabled, outputFile);
    }

    private static boolean isAccessibleStorageDirectory(Path path) {
        if (Files.exists(path)) {
            return Files.isDirectory(path)
                    && Files.isReadable(path)
                    && Files.isWritable(path);
        }

        Path existingAncestor = path.getParent();
        while (existingAncestor != null
                && !Files.exists(existingAncestor)) {
            existingAncestor = existingAncestor.getParent();
        }
        return existingAncestor != null
                && Files.isDirectory(existingAncestor)
                && Files.isReadable(existingAncestor)
                && Files.isWritable(existingAncestor);
    }

    private static AuthenticationConfiguration validateAuthentication(
            RawAuthenticationConfiguration raw,
            LlmConfiguration llm,
            SpeechConfiguration speech
    ) {
        if (raw.llm() == null) {
            throw failure(
                    "AUTHENTICATION_REQUIRED_VALUE_MISSING",
                    "$.llm"
            );
        }
        if (raw.speech() == null) {
            throw failure(
                    "AUTHENTICATION_REQUIRED_VALUE_MISSING",
                    "$.speech"
            );
        }
        if (raw.llm().providers() == null) {
            throw failure(
                    "AUTHENTICATION_REQUIRED_VALUE_MISSING",
                    "$.llm.providers"
            );
        }

        Map<String, String> llmApiKeys = new LinkedHashMap<>();
        for (Map.Entry<String, RawApiKeyAuthentication> entry
                : raw.llm().providers().entrySet()) {
            String profileName = entry.getKey();
            String path = authenticationProviderPath(profileName);
            if (profileName == null
                    || profileName.isBlank()
                    || !profileName.equals(profileName.strip())
                    || !llm.providers().containsKey(profileName)) {
                throw failure(
                        "AUTHENTICATION_LLM_PROFILE_UNKNOWN",
                        "$.llm.providers"
                );
            }
            RawApiKeyAuthentication value = entry.getValue();
            if (value == null) {
                throw failure(
                        "AUTHENTICATION_REQUIRED_VALUE_MISSING",
                        path
                );
            }
            llmApiKeys.put(
                    profileName,
                    requiredApiKey(value.apiKey(), path + ".apiKey")
            );
        }

        LlmProviderConfiguration active =
                llm.providers().get(llm.activeProvider());
        if (active.type() == LlmProviderType.MISTRAL
                && !llmApiKeys.containsKey(llm.activeProvider())) {
            throw failure(
                    "AUTHENTICATION_ACTIVE_LLM_KEY_MISSING",
                    authenticationProviderPath(llm.activeProvider())
                            + ".apiKey"
            );
        }

        Optional<String> googleCloudTextToSpeechApiKey = Optional.empty();
        RawApiKeyAuthentication rawGoogle =
                raw.speech().googleCloudTts();
        if (rawGoogle != null) {
            googleCloudTextToSpeechApiKey = Optional.of(requiredApiKey(
                    rawGoogle.apiKey(),
                    "$.speech.googleCloudTts.apiKey"
            ));
        }
        if (speech.enabled()
                && googleCloudTextToSpeechApiKey.isEmpty()) {
            throw failure(
                    "AUTHENTICATION_GOOGLE_TTS_KEY_MISSING",
                    "$.speech.googleCloudTts.apiKey"
            );
        }

        return new AuthenticationConfiguration(
                llmApiKeys,
                googleCloudTextToSpeechApiKey
        );
    }

    private static SourceConfiguration validateSource(
            RawSourceConfiguration raw,
            Path workingDirectory
    ) {
        SourceMode mode = switch (requiredText(raw.mode(), "$.source.mode")) {
            case "live" -> SourceMode.LIVE;
            case "replay" -> SourceMode.REPLAY;
            default -> throw failure("CONFIG_SOURCE_MODE_INVALID", "$.source.mode");
        };

        if (mode == SourceMode.LIVE) {
            Path journalDirectory = requiredPath(
                    raw.journalDirectory(),
                    "$.source.journalDirectory",
                    workingDirectory
            );
            if (!Files.isDirectory(journalDirectory) || !Files.isReadable(journalDirectory)) {
                throw failure("CONFIG_JOURNAL_DIRECTORY_INVALID", "$.source.journalDirectory");
            }
            if (raw.replayFile() != null) {
                throw failure("CONFIG_SOURCE_CONFLICT", "$.source.replayFile");
            }
            return new SourceConfiguration(mode, journalDirectory, null);
        }

        if (raw.journalDirectory() != null) {
            throw failure("CONFIG_SOURCE_CONFLICT", "$.source.journalDirectory");
        }
        Path replayFile = requiredPath(raw.replayFile(), "$.source.replayFile", workingDirectory);
        if (!Files.isRegularFile(replayFile) || !Files.isReadable(replayFile)) {
            throw failure("CONFIG_REPLAY_FILE_INVALID", "$.source.replayFile");
        }
        return new SourceConfiguration(mode, null, replayFile);
    }

    private static ObserverConfiguration validateObserver(
            RawObserverConfiguration raw,
            Path workingDirectory
    ) {
        String outputLanguage = requiredText(
                raw.outputLanguage(),
                "$.observer.outputLanguage"
        );
        requireExact(raw.quietPeriodMs(), 750L, "$.observer.quietPeriodMs");
        requireExact(raw.maximumBatchAgeMs(), 2000L, "$.observer.maximumBatchAgeMs");
        Path traceFile = requiredPath(raw.traceFile(), "$.observer.traceFile", workingDirectory);
        if (Files.exists(traceFile) && Files.isDirectory(traceFile)) {
            throw failure("CONFIG_TRACE_FILE_INVALID", "$.observer.traceFile");
        }

        return new ObserverConfiguration(
                outputLanguage,
                raw.quietPeriodMs(),
                raw.maximumBatchAgeMs(),
                traceFile
        );
    }

    private static UiConfiguration validateUi(RawUiConfiguration raw) {
        if (raw.enabled() == null) {
            throw failure(
                    "CONFIG_REQUIRED_VALUE_MISSING",
                    "$.ui.enabled"
            );
        }
        int maximumObservationRows = requiredUiLimit(
                raw.maximumObservationRows(),
                "$.ui.maximumObservationRows"
        );
        int maximumTurnRows = requiredUiLimit(
                raw.maximumTurnRows(),
                "$.ui.maximumTurnRows"
        );
        return new UiConfiguration(
                raw.enabled(),
                maximumObservationRows,
                maximumTurnRows
        );
    }

    private static int requiredUiLimit(Integer value, String path) {
        if (value == null) {
            throw failure("CONFIG_REQUIRED_VALUE_MISSING", path);
        }
        if (value < 1 || value > 100_000) {
            throw failure("CONFIG_UI_LIMIT_INVALID", path);
        }
        return value;
    }

    private static LlmConfiguration validateLlm(
            RawLlmConfiguration raw,
            Path workingDirectory
    ) {
        String activeProvider = requiredText(raw.activeProvider(), "$.llm.activeProvider");
        if (raw.providers() == null || raw.providers().isEmpty()) {
            throw failure("CONFIG_PROVIDERS_INVALID", "$.llm.providers");
        }

        Map<String, LlmProviderConfiguration> providers = new LinkedHashMap<>();
        for (Map.Entry<String, RawLlmProviderConfiguration> entry : raw.providers().entrySet()) {
            String profileName = entry.getKey();
            if (profileName == null || profileName.isBlank()
                    || !profileName.equals(profileName.strip())) {
                throw failure("CONFIG_PROVIDER_NAME_INVALID", "$.llm.providers");
            }
            if (entry.getValue() == null) {
                throw failure("CONFIG_REQUIRED_VALUE_MISSING", providerPath(profileName, null));
            }
            providers.put(profileName, validateProvider(profileName, entry.getValue()));
        }

        if (!providers.containsKey(activeProvider)) {
            throw failure("CONFIG_ACTIVE_PROVIDER_INVALID", "$.llm.activeProvider");
        }
        return new LlmConfiguration(
                activeProvider,
                providers
        );
    }

    private static SpeechConfiguration validateSpeech(
            RawSpeechConfiguration raw
    ) {
        if (raw.enabled == null) {
            throw failure("CONFIG_REQUIRED_VALUE_MISSING", "$.speech.enabled");
        }
        boolean enabled = raw.enabled;

        SpeechProvider provider = switch (requiredText(
                speechText(
                        raw.provider,
                        "GOOGLE_CLOUD_TTS",
                        enabled,
                        "$.speech.provider"
                ),
                "$.speech.provider"
        )) {
            case "GOOGLE_CLOUD_TTS" -> SpeechProvider.GOOGLE_CLOUD_TTS;
            default -> throw failure(
                    "CONFIG_SPEECH_PROVIDER_INVALID",
                    "$.speech.provider"
            );
        };
        String languageCode = speechText(
                raw.languageCode,
                "ru-RU",
                enabled,
                "$.speech.languageCode"
        );
        String voiceName = speechText(
                raw.voiceName,
                "replace-with-google-voice-name",
                enabled,
                "$.speech.voiceName"
        );
        if (enabled
                && voiceName.equals("replace-with-google-voice-name")) {
            throw failure(
                    "CONFIG_SPEECH_VOICE_PLACEHOLDER",
                    "$.speech.voiceName"
            );
        }
        SpeechAudioEncoding audioEncoding = switch (requiredText(
                speechText(
                        raw.audioEncoding,
                        "LINEAR16",
                        enabled,
                        "$.speech.audioEncoding"
                ),
                "$.speech.audioEncoding"
        )) {
            case "LINEAR16" -> SpeechAudioEncoding.LINEAR16;
            default -> throw failure(
                    "CONFIG_SPEECH_ENCODING_INVALID",
                    "$.speech.audioEncoding"
            );
        };
        double speakingRate = speechDouble(
                raw.speakingRate,
                1.0,
                enabled,
                "$.speech.speakingRate"
        );
        if (!Double.isFinite(speakingRate)
                || speakingRate < 0.25
                || speakingRate > 2.0) {
            throw failure(
                    "CONFIG_SPEECH_SPEAKING_RATE_INVALID",
                    "$.speech.speakingRate"
            );
        }
        double pitch = speechDouble(
                raw.pitch,
                0.0,
                enabled,
                "$.speech.pitch"
        );
        if (!Double.isFinite(pitch)
                || pitch < -20.0
                || pitch > 20.0) {
            throw failure("CONFIG_SPEECH_PITCH_INVALID", "$.speech.pitch");
        }
        double volumeGainDb = speechDouble(
                raw.volumeGainDb,
                0.0,
                enabled,
                "$.speech.volumeGainDb"
        );
        if (!Double.isFinite(volumeGainDb)
                || volumeGainDb < -96.0
                || volumeGainDb > 16.0) {
            throw failure(
                    "CONFIG_SPEECH_VOLUME_INVALID",
                    "$.speech.volumeGainDb"
            );
        }
        long requestTimeoutMs = speechLong(
                raw.requestTimeoutMs,
                15_000L,
                enabled,
                "$.speech.requestTimeoutMs"
        );
        if (requestTimeoutMs <= 0) {
            throw failure(
                    "CONFIG_SPEECH_TIMEOUT_INVALID",
                    "$.speech.requestTimeoutMs"
            );
        }
        if (raw.outputDevice != null && raw.outputDevice.isBlank()) {
            throw failure(
                    "CONFIG_SPEECH_OUTPUT_DEVICE_INVALID",
                    "$.speech.outputDevice"
            );
        }
        boolean alsoPrintToConsole = speechBoolean(
                raw.alsoPrintToConsole,
                true,
                enabled,
                "$.speech.alsoPrintToConsole"
        );

        return new SpeechConfiguration(
                enabled,
                provider,
                languageCode,
                voiceName,
                audioEncoding,
                speakingRate,
                pitch,
                volumeGainDb,
                Duration.ofMillis(requestTimeoutMs),
                raw.outputDevice,
                alsoPrintToConsole
        );
    }

    private static String speechText(
            String value,
            String disabledDefault,
            boolean enabled,
            String path
    ) {
        if (value == null) {
            if (enabled) {
                throw failure("CONFIG_REQUIRED_VALUE_MISSING", path);
            }
            return disabledDefault;
        }
        return requiredText(value, path);
    }

    private static double speechDouble(
            Double value,
            double disabledDefault,
            boolean enabled,
            String path
    ) {
        if (value != null) {
            return value;
        }
        if (enabled) {
            throw failure("CONFIG_REQUIRED_VALUE_MISSING", path);
        }
        return disabledDefault;
    }

    private static long speechLong(
            Long value,
            long disabledDefault,
            boolean enabled,
            String path
    ) {
        if (value != null) {
            return value;
        }
        if (enabled) {
            throw failure("CONFIG_REQUIRED_VALUE_MISSING", path);
        }
        return disabledDefault;
    }

    private static boolean speechBoolean(
            Boolean value,
            boolean disabledDefault,
            boolean enabled,
            String path
    ) {
        if (value != null) {
            return value;
        }
        if (enabled) {
            throw failure("CONFIG_REQUIRED_VALUE_MISSING", path);
        }
        return disabledDefault;
    }

    private static LlmProviderConfiguration validateProvider(
            String profileName,
            RawLlmProviderConfiguration raw
    ) {
        LlmProviderType type = switch (requiredText(
                raw.type(),
                providerPath(profileName, "type")
        )) {
            case "LM_STUDIO" -> LlmProviderType.LM_STUDIO;
            case "MISTRAL" -> LlmProviderType.MISTRAL;
            default -> throw failure(
                    "CONFIG_PROVIDER_TYPE_INVALID",
                    providerPath(profileName, "type")
            );
        };

        URI baseUrl = normalizedBaseUrl(
                raw.baseUrl(),
                providerPath(profileName, "baseUrl")
        );
        String model = requiredText(raw.model(), providerPath(profileName, "model"));
        if (!Double.isFinite(raw.temperature())
                || raw.temperature() < 0.0
                || raw.temperature() > 2.0) {
            throw failure(
                    "CONFIG_PROVIDER_TEMPERATURE_INVALID",
                    providerPath(profileName, "temperature")
            );
        }
        if (raw.maximumOutputTokens() <= 0) {
            throw failure(
                    "CONFIG_PROVIDER_OUTPUT_LIMIT_INVALID",
                    providerPath(profileName, "maximumOutputTokens")
            );
        }
        if (raw.requestTimeoutMs() <= 0) {
            throw failure(
                    "CONFIG_PROVIDER_TIMEOUT_INVALID",
                    providerPath(profileName, "requestTimeoutMs")
            );
        }
        ResponseFormat responseFormat = switch (requiredText(
                raw.responseFormat(),
                providerPath(profileName, "responseFormat")
        )) {
            case "JSON_OBJECT" -> ResponseFormat.JSON_OBJECT;
            default -> throw failure(
                    "CONFIG_PROVIDER_RESPONSE_FORMAT_INVALID",
                    providerPath(profileName, "responseFormat")
            );
        };
        Optional<LlmTokenPricing> pricing = Optional.ofNullable(raw.pricing())
                .map(value -> validatePricing(profileName, value));

        return new LlmProviderConfiguration(
                type,
                baseUrl,
                model,
                raw.temperature(),
                raw.maximumOutputTokens(),
                Duration.ofMillis(raw.requestTimeoutMs()),
                responseFormat,
                pricing
        );
    }

    private static LlmTokenPricing validatePricing(
            String profileName,
            RawLlmTokenPricing raw
    ) {
        String base = providerPath(profileName, "pricing");
        String currency = requiredText(raw.currency(), base + ".currency");
        if (!currency.equals(currency.toUpperCase(Locale.ROOT))
                || currency.length() != 3) {
            throw failure(
                    "CONFIG_PROVIDER_PRICING_CURRENCY_INVALID",
                    base + ".currency"
            );
        }
        try {
            Currency.getInstance(currency);
        } catch (IllegalArgumentException failure) {
            throw failure(
                    "CONFIG_PROVIDER_PRICING_CURRENCY_INVALID",
                    base + ".currency"
            );
        }

        BigDecimal input = nonNegativePrice(
                raw.inputPerMillionTokens(),
                base + ".inputPerMillionTokens"
        );
        BigDecimal cachedInput = nonNegativePrice(
                raw.cachedInputPerMillionTokens(),
                base + ".cachedInputPerMillionTokens"
        );
        BigDecimal output = nonNegativePrice(
                raw.outputPerMillionTokens(),
                base + ".outputPerMillionTokens"
        );
        return new LlmTokenPricing(currency, input, cachedInput, output);
    }

    private static BigDecimal nonNegativePrice(BigDecimal value, String path) {
        if (value == null || value.signum() < 0) {
            throw failure("CONFIG_PROVIDER_PRICING_RATE_INVALID", path);
        }
        return value;
    }

    private static URI normalizedBaseUrl(String rawValue, String path) {
        String value = requiredText(rawValue, path);
        final URI parsed;
        try {
            parsed = new URI(value);
        } catch (URISyntaxException exception) {
            throw failure("CONFIG_PROVIDER_BASE_URL_INVALID", path);
        }

        String scheme = parsed.getScheme();
        if (!parsed.isAbsolute()
                || scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                || parsed.getHost() == null
                || parsed.getUserInfo() != null
                || parsed.getRawQuery() != null
                || parsed.getRawFragment() != null) {
            throw failure("CONFIG_PROVIDER_BASE_URL_INVALID", path);
        }

        String normalized = value;
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        try {
            return new URI(normalized);
        } catch (URISyntaxException exception) {
            throw failure("CONFIG_PROVIDER_BASE_URL_INVALID", path);
        }
    }

    private static Path requiredPath(String rawValue, String field, Path workingDirectory) {
        String value = requiredText(rawValue, field);
        try {
            return resolveAgainstWorkingDirectory(Path.of(value), workingDirectory);
        } catch (InvalidPathException exception) {
            throw failure("CONFIG_PATH_INVALID", field);
        }
    }

    private static Path optionalPath(String rawValue, String field, Path workingDirectory) {
        if (rawValue == null) {
            return null;
        }
        return requiredPath(rawValue, field, workingDirectory);
    }

    private static Path resolveAgainstWorkingDirectory(Path path, Path workingDirectory) {
        Path resolved = path.isAbsolute() ? path : workingDirectory.resolve(path);
        return resolved.toAbsolutePath().normalize();
    }

    private static String requiredText(String value, String path) {
        if (value == null) {
            throw failure("CONFIG_REQUIRED_VALUE_MISSING", path);
        }
        if (value.isBlank()) {
            throw failure("CONFIG_TEXT_INVALID", path);
        }
        return value;
    }

    private static String requiredApiKey(String value, String path) {
        if (value == null || value.isBlank()) {
            throw failure("AUTHENTICATION_API_KEY_INVALID", path);
        }
        String normalized = value.strip();
        if (normalized.regionMatches(
                true,
                0,
                "replace-with-",
                0,
                "replace-with-".length()
        )) {
            throw failure("AUTHENTICATION_API_KEY_INVALID", path);
        }
        return normalized;
    }

    private static void requireExact(long actual, long expected, String path) {
        if (actual != expected) {
            throw failure("CONFIG_OBSERVER_LIMIT_INVALID", path);
        }
    }

    private static String stripUtf8Bom(String text) {
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            return text.substring(1);
        }
        return text;
    }

    private static ConfigurationException jsonFailure(JsonProcessingException exception) {
        return jsonFailure("CONFIG_JSON_INVALID", exception);
    }

    private static ConfigurationException jsonFailure(
            String code,
            JsonProcessingException exception
    ) {
        String path = jsonPath(exception);
        JsonLocation location = exception.getLocation();
        String safeDetail = location == null
                ? null
                : "line " + location.getLineNr() + ", column " + location.getColumnNr();
        return failure(code, path, safeDetail);
    }

    private static String jsonPath(JsonProcessingException exception) {
        if (!(exception instanceof JsonMappingException mappingException)) {
            return "$";
        }

        StringBuilder result = new StringBuilder("$");
        for (JsonMappingException.Reference reference : mappingException.getPath()) {
            if (reference.getFieldName() != null) {
                result.append('.').append(reference.getFieldName());
            } else if (reference.getIndex() >= 0) {
                result.append('[').append(reference.getIndex()).append(']');
            }
        }
        if (exception instanceof UnrecognizedPropertyException unrecognized) {
            result.append('.').append(unrecognized.getPropertyName());
        }
        return result.toString();
    }

    private static String providerPath(String profileName, String field) {
        String base = "$.llm.providers." + profileName;
        return field == null ? base : base + "." + field;
    }

    private static String authenticationProviderPath(String profileName) {
        return "$.llm.providers." + profileName;
    }

    private static ConfigurationException failure(String code, String path) {
        return new ConfigurationException(code, path, null);
    }

    private static ConfigurationException failure(String code, String path, String safeDetail) {
        return new ConfigurationException(code, path, safeDetail);
    }

    public enum SourceMode {
        LIVE,
        REPLAY
    }

    public enum LlmProviderType {
        LM_STUDIO,
        MISTRAL
    }

    public enum ResponseFormat {
        JSON_OBJECT
    }

    public enum SpeechProvider {
        GOOGLE_CLOUD_TTS
    }

    public enum SpeechAudioEncoding {
        LINEAR16
    }

    public record SourceConfiguration(
            SourceMode mode,
            Path journalDirectory,
            Path replayFile
    ) {

        public SourceConfiguration {
            Objects.requireNonNull(mode, "mode");
            if (mode == SourceMode.LIVE) {
                Objects.requireNonNull(journalDirectory, "journalDirectory");
                if (replayFile != null) {
                    throw new IllegalArgumentException("LIVE source cannot have replayFile");
                }
            } else {
                Objects.requireNonNull(replayFile, "replayFile");
                if (journalDirectory != null) {
                    throw new IllegalArgumentException("REPLAY source cannot have journalDirectory");
                }
            }
        }
    }

    public record ObserverConfiguration(
            String outputLanguage,
            long quietPeriodMs,
            long maximumBatchAgeMs,
            Path traceFile
    ) {

        public ObserverConfiguration {
            Objects.requireNonNull(outputLanguage, "outputLanguage");
            Objects.requireNonNull(traceFile, "traceFile");
        }
    }

    public record UiConfiguration(
            boolean enabled,
            int maximumObservationRows,
            int maximumTurnRows
    ) {

        public UiConfiguration {
            if (maximumObservationRows < 1
                    || maximumObservationRows > 100_000) {
                throw new IllegalArgumentException(
                        "maximumObservationRows must be between 1 and 100000"
                );
            }
            if (maximumTurnRows < 1 || maximumTurnRows > 100_000) {
                throw new IllegalArgumentException(
                        "maximumTurnRows must be between 1 and 100000"
                );
            }
        }
    }

    public record BehaviorGraphConfiguration(
            boolean enabled,
            Path storageDirectory,
            Duration weightHalfLife,
            double contextPriorStrength,
            int snapshotEverySignificantEvents,
            boolean storeRawJournalPayload
    ) {

        public BehaviorGraphConfiguration {
            Objects.requireNonNull(storageDirectory, "storageDirectory");
            Objects.requireNonNull(weightHalfLife, "weightHalfLife");
            if (weightHalfLife.isZero() || weightHalfLife.isNegative()) {
                throw new IllegalArgumentException(
                        "weightHalfLife must be positive"
                );
            }
            if (!Double.isFinite(contextPriorStrength)
                    || contextPriorStrength <= 0.0) {
                throw new IllegalArgumentException(
                        "contextPriorStrength must be positive and finite"
                );
            }
            if (snapshotEverySignificantEvents <= 0) {
                throw new IllegalArgumentException(
                        "snapshotEverySignificantEvents must be positive"
                );
            }
            if (storeRawJournalPayload) {
                throw new IllegalArgumentException(
                        "storing raw journal payload is unsupported"
                );
            }
        }
    }

    public record ObservationCorpusConfiguration(
            boolean enabled,
            Path outputFile
    ) {

        public ObservationCorpusConfiguration {
            if (enabled && outputFile == null) {
                throw new IllegalArgumentException(
                        "outputFile must be set when observation corpus is enabled"
                );
            }
        }
    }

    /**
     * Where the organic registry is read from, or {@code null} for none.
     *
     * <p>A separate file rather than a bundled resource: it is generated from
     * pinned upstream tables under their own licence, it is not compiled into
     * the jar, and it can be replaced or removed without rebuilding anything
     * (ADR-0028).</p>
     */
    public record BioConfiguration(Path registryFile) {
    }

    public record LlmConfiguration(
            String activeProvider,
            Map<String, LlmProviderConfiguration> providers
    ) {

        public LlmConfiguration {
            Objects.requireNonNull(activeProvider, "activeProvider");
            Objects.requireNonNull(providers, "providers");
            providers = Collections.unmodifiableMap(new LinkedHashMap<>(providers));
        }
    }

    public record LlmProviderConfiguration(
            LlmProviderType type,
            URI baseUrl,
            String model,
            double temperature,
            int maximumOutputTokens,
            Duration requestTimeout,
            ResponseFormat responseFormat,
            Optional<LlmTokenPricing> pricing
    ) {

        public LlmProviderConfiguration {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(baseUrl, "baseUrl");
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(requestTimeout, "requestTimeout");
            Objects.requireNonNull(responseFormat, "responseFormat");
            Objects.requireNonNull(pricing, "pricing");
        }

        public URI chatCompletionsUri() {
            return URI.create(baseUrl + "/chat/completions");
        }
    }

    /**
     * Explicit tariff used only for an estimate. Provider responses report
     * token usage, not an authoritative invoice amount.
     */
    public record LlmTokenPricing(
            String currency,
            BigDecimal inputPerMillionTokens,
            BigDecimal cachedInputPerMillionTokens,
            BigDecimal outputPerMillionTokens
    ) {

        public LlmTokenPricing {
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(inputPerMillionTokens,
                    "inputPerMillionTokens");
            Objects.requireNonNull(cachedInputPerMillionTokens,
                    "cachedInputPerMillionTokens");
            Objects.requireNonNull(outputPerMillionTokens,
                    "outputPerMillionTokens");
        }
    }

    /**
     * Secret-bearing values loaded only from the adjacent authentication
     * file. The map is never exposed and string rendering is always redacted.
     */
    public static final class AuthenticationConfiguration {

        private final Map<String, String> llmApiKeys;
        private final Optional<String> googleCloudTextToSpeechApiKey;

        private AuthenticationConfiguration(
                Map<String, String> llmApiKeys,
                Optional<String> googleCloudTextToSpeechApiKey
        ) {
            this.llmApiKeys = Collections.unmodifiableMap(
                    new LinkedHashMap<>(llmApiKeys)
            );
            this.googleCloudTextToSpeechApiKey = Objects.requireNonNull(
                    googleCloudTextToSpeechApiKey,
                    "googleCloudTextToSpeechApiKey"
            );
        }

        private Optional<String> llmApiKey(String profileName) {
            return Optional.ofNullable(llmApiKeys.get(profileName));
        }

        private Optional<String> googleCloudTextToSpeechApiKey() {
            return googleCloudTextToSpeechApiKey;
        }

        @Override
        public String toString() {
            return "AuthenticationConfiguration["
                    + "llmProfiles=" + llmApiKeys.keySet()
                    + ", googleCloudTextToSpeechApiKey="
                    + (googleCloudTextToSpeechApiKey.isPresent()
                    ? "<redacted>"
                    : "<absent>")
                    + ']';
        }
    }

    public record ResolvedProviderConfiguration(
            String profileName,
            LlmProviderType type,
            URI baseUrl,
            String model,
            Optional<String> apiKey,
            double temperature,
            int maximumOutputTokens,
            Duration requestTimeout,
            ResponseFormat responseFormat,
            Optional<LlmTokenPricing> pricing
    ) {

        public ResolvedProviderConfiguration {
            Objects.requireNonNull(profileName, "profileName");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(baseUrl, "baseUrl");
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(apiKey, "apiKey");
            Objects.requireNonNull(requestTimeout, "requestTimeout");
            Objects.requireNonNull(responseFormat, "responseFormat");
            Objects.requireNonNull(pricing, "pricing");
        }

        public URI chatCompletionsUri() {
            return URI.create(baseUrl + "/chat/completions");
        }

        @Override
        public String toString() {
            return "ResolvedProviderConfiguration["
                    + "profileName=" + profileName
                    + ", type=" + type
                    + ", baseUrl=" + baseUrl
                    + ", model=" + model
                    + ", apiKey=" + (apiKey.isPresent() ? "<redacted>" : "<absent>")
                    + ", temperature=" + temperature
                    + ", maximumOutputTokens=" + maximumOutputTokens
                    + ", pricing=" + pricing
                    + ", requestTimeout=" + requestTimeout
                    + ", responseFormat=" + responseFormat
                    + ']';
        }
    }

    public record SpeechConfiguration(
            boolean enabled,
            SpeechProvider provider,
            String languageCode,
            String voiceName,
            SpeechAudioEncoding audioEncoding,
            double speakingRate,
            double pitch,
            double volumeGainDb,
            Duration requestTimeout,
            String outputDevice,
            boolean alsoPrintToConsole
    ) {

        public SpeechConfiguration {
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(languageCode, "languageCode");
            Objects.requireNonNull(voiceName, "voiceName");
            Objects.requireNonNull(audioEncoding, "audioEncoding");
            Objects.requireNonNull(requestTimeout, "requestTimeout");
        }
    }

    public static final class ConfigurationException extends RuntimeException {

        private final String code;
        private final String path;

        private ConfigurationException(String code, String path, String safeDetail) {
            super(safeDetail == null
                    ? code + " at " + path
                    : code + " at " + path + " (" + safeDetail + ')');
            this.code = code;
            this.path = path;
        }

        public String code() {
            return code;
        }

        public String path() {
            return path;
        }
    }

    private static final class RawConfiguration {
        public RawSourceConfiguration source;
        public RawObserverConfiguration observer;
        public RawUiConfiguration ui;
        public RawLlmConfiguration llm;
        public RawSpeechConfiguration speech;
        public RawBehaviorGraphConfiguration behaviorGraph;
        public RawObservationCorpusConfiguration observationCorpus;
        public RawBioConfiguration bio;
    }

    private static final class RawBioConfiguration {
        public String registryFile;
    }

    private record RawSourceConfiguration(
            String mode,
            String journalDirectory,
            String replayFile
    ) {
    }

    private record RawObserverConfiguration(
            String outputLanguage,
            long quietPeriodMs,
            long maximumBatchAgeMs,
            String traceFile
    ) {
    }

    private record RawUiConfiguration(
            Boolean enabled,
            Integer maximumObservationRows,
            Integer maximumTurnRows
    ) {
    }

    private record RawBehaviorGraphConfiguration(
            Boolean enabled,
            String storageDirectory,
            String weightHalfLife,
            Double contextPriorStrength,
            Integer snapshotEverySignificantEvents,
            Boolean storeRawJournalPayload
    ) {
    }

    private static final class RawObservationCorpusConfiguration {
        public Boolean enabled;
        public String outputFile;
    }

    private record RawLlmConfiguration(
            String activeProvider,
            Map<String, RawLlmProviderConfiguration> providers
    ) {
    }

    private record RawLlmProviderConfiguration(
            String type,
            String baseUrl,
            String model,
            double temperature,
            int maximumOutputTokens,
            long requestTimeoutMs,
            String responseFormat,
            RawLlmTokenPricing pricing
    ) {
    }

    private record RawLlmTokenPricing(
            String currency,
            BigDecimal inputPerMillionTokens,
            BigDecimal cachedInputPerMillionTokens,
            BigDecimal outputPerMillionTokens
    ) {
    }

    private record RawAuthenticationConfiguration(
            RawLlmAuthentication llm,
            RawSpeechAuthentication speech
    ) {
    }

    private record RawLlmAuthentication(
            Map<String, RawApiKeyAuthentication> providers
    ) {
    }

    private record RawSpeechAuthentication(
            RawApiKeyAuthentication googleCloudTts
    ) {
    }

    private record RawApiKeyAuthentication(String apiKey) {
    }

    /**
     * Mutable deserialization-only value so omitted fields can retain safe
     * disabled-mode defaults while {@code enabled} itself remains required.
     */
    private static final class RawSpeechConfiguration {

        public Boolean enabled;
        public String provider;
        public String languageCode;
        public String voiceName;
        public String audioEncoding;
        public Double speakingRate;
        public Double pitch;
        public Double volumeGainDb;
        public Long requestTimeoutMs;
        public String outputDevice;
        public Boolean alsoPrintToConsole;
    }
}
