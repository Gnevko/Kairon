package kairon.config;

import kairon.config.KaironConfiguration.ConfigurationException;
import kairon.config.KaironConfiguration.LlmProviderType;
import kairon.config.KaironConfiguration.ResolvedProviderConfiguration;
import kairon.config.KaironConfiguration.SourceMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class KaironConfigurationTest {

    private static final String TRACE_PATH = "./var/journal-observer-turns.jsonl";

    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsExactCliConfigurationAndResolvesUnauthenticatedLmStudio() throws IOException {
        Path journalDirectory = Files.createDirectory(temporaryDirectory.resolve("journals"));
        Path configurationFile = writeConfiguration(
                "lm-studio.json",
                configuration("live", journalDirectory, null, "lm-studio")
        );
        writeAuthentication(authentication(null, null, null));

        KaironConfiguration configuration = KaironConfiguration.load(new String[]{
                "--config=" + configurationFile
        });

        assertEquals(SourceMode.LIVE, configuration.source().mode());
        assertEquals(journalDirectory.toAbsolutePath().normalize(),
                configuration.source().journalDirectory());
        assertNull(configuration.source().replayFile());
        assertEquals(Path.of(TRACE_PATH).toAbsolutePath().normalize(),
                configuration.observer().traceFile());
        assertTrue(configuration.ui().enabled());
        assertEquals(1000, configuration.ui().maximumObservationRows());
        assertEquals(200, configuration.ui().maximumTurnRows());
        assertTrue(configuration.behaviorGraph().enabled());
        assertEquals(
                Path.of("./data/behavior-graphs")
                        .toAbsolutePath()
                        .normalize(),
                configuration.behaviorGraph().storageDirectory()
        );
        assertEquals(
                Duration.ofDays(30),
                configuration.behaviorGraph().weightHalfLife()
        );
        assertEquals(
                2.0,
                configuration.behaviorGraph().contextPriorStrength()
        );
        assertEquals(
                50,
                configuration.behaviorGraph()
                        .snapshotEverySignificantEvents()
        );
        assertFalse(
                configuration.behaviorGraph().storeRawJournalPayload()
        );
        assertEquals(2, configuration.llm().providers().size());
        assertThrows(
                UnsupportedOperationException.class,
                () -> configuration.llm().providers().clear()
        );

        ResolvedProviderConfiguration provider =
                configuration.resolveActiveProvider();

        assertEquals("lm-studio", provider.profileName());
        assertEquals(LlmProviderType.LM_STUDIO, provider.type());
        assertEquals("http://localhost:1234/v1", provider.baseUrl().toString());
        assertEquals("http://localhost:1234/v1/chat/completions",
                provider.chatCompletionsUri().toString());
        assertTrue(provider.apiKey().isEmpty());
        assertTrue(provider.pricing().isEmpty());
        assertEquals(Duration.ofSeconds(30), provider.requestTimeout());
        assertTrue(provider.toString().contains("apiKey=<absent>"));
    }

    /**
     * No organic registry is a way to run, not a broken configuration.
     *
     * <p>Every organism is then named by the word the journal itself carried,
     * which is what every organism was named by before ADR-0028.</p>
     */
    @Test
    void bioRegistryIsAbsentWhenTheSectionIsMissing() throws IOException {
        Path journalDirectory = Files.createDirectory(
                temporaryDirectory.resolve("bio-missing-section-journals")
        );
        writeAuthentication(authentication(null, null, null));
        KaironConfiguration configuration = KaironConfiguration.load(
                writeConfiguration(
                        "bio-missing-section.json",
                        configuration("live", journalDirectory, null, "lm-studio")
                )
        );
        assertNull(configuration.bio().registryFile());
    }

    @Test
    void bioRegistryFileIsResolvedLikeEveryOtherConfiguredPath()
            throws IOException {
        Path journalDirectory = Files.createDirectory(
                temporaryDirectory.resolve("bio-present-journals")
        );
        Path registry = temporaryDirectory.resolve("organic-registry.json");
        Files.writeString(registry, "{}");
        writeAuthentication(authentication(null, null, null));
        KaironConfiguration configuration = KaironConfiguration.load(
                writeConfiguration(
                        "bio-present.json",
                        configuration(
                                "live",
                                journalDirectory,
                                null,
                                "lm-studio",
                                bioSection(nullablePath(registry))
                        )
                )
        );
        assertEquals(registry, configuration.bio().registryFile());
    }

    /**
     * A path that is set and is not there is a typo, and typos are loud.
     *
     * <p>The alternative is a session that runs with no registry because of a
     * misspelling, names nothing, and says so nowhere.</p>
     */
    @Test
    void bioRegistryFileMustExist() throws IOException {
        Path journalDirectory = Files.createDirectory(
                temporaryDirectory.resolve("bio-absent-file-journals")
        );
        writeAuthentication(authentication(null, null, null));
        ConfigurationException failure = assertThrows(
                ConfigurationException.class,
                () -> KaironConfiguration.load(
                        writeConfiguration(
                                "bio-absent-file.json",
                                configuration(
                                        "live",
                                        journalDirectory,
                                        null,
                                        "lm-studio",
                                        bioSection("\"./no-such-registry.json\"")
                                )
                        )
                )
        );
        assertEquals("CONFIG_FILE_NOT_FOUND", failure.code());
        assertEquals("$.bio.registryFile", failure.path());
    }

    @Test
    void observationCorpusDefaultsToDisabledWhenSectionMissing() throws IOException {
        Path journalDirectory = Files.createDirectory(
                temporaryDirectory.resolve("observation-corpus-default-journals")
        );
        writeAuthentication(authentication(null, null, null));
        KaironConfiguration configuration = KaironConfiguration.load(
                writeConfiguration(
                        "observation-corpus-missing-section.json",
                        configuration("live", journalDirectory, null, "lm-studio")
                )
        );
        assertFalse(configuration.observationCorpus().enabled());
        assertNull(configuration.observationCorpus().outputFile());
    }

    @Test
    void disabledObservationCorpusDoesNotRequireOutputFile() throws IOException {
        Path journalDirectory = Files.createDirectory(
                temporaryDirectory.resolve("observation-corpus-disabled-journals")
        );
        writeAuthentication(authentication(null, null, null));
        KaironConfiguration configuration = KaironConfiguration.load(
                writeConfiguration(
                        "observation-corpus-disabled.json",
                        configuration(
                                "live",
                                journalDirectory,
                                null,
                                "lm-studio",
                                observationCorpusSection(false, null)
                        )
                )
        );
        assertFalse(configuration.observationCorpus().enabled());
        assertNull(configuration.observationCorpus().outputFile());
    }

    @Test
    void enabledObservationCorpusRequiresOutputFile() throws IOException {
        Path journalDirectory = Files.createDirectory(
                temporaryDirectory.resolve("observation-corpus-required-journals")
        );
        writeAuthentication(authentication(null, null, null));
        ConfigurationException failure = assertThrows(
                ConfigurationException.class,
                () -> KaironConfiguration.load(
                        writeConfiguration(
                                "observation-corpus-required-output-missing.json",
                                configuration(
                                        "live",
                                        journalDirectory,
                                        null,
                                        "lm-studio",
                                        observationCorpusSection(true, null)
                                )
                        )
                )
        );
        assertEquals("CONFIG_REQUIRED_VALUE_MISSING", failure.code());
        assertEquals("$.observationCorpus.outputFile", failure.path());
    }

    @Test
    void enabledObservationCorpusResolvesRelativeOutputFile() throws IOException {
        Path journalDirectory = Files.createDirectory(
                temporaryDirectory.resolve("observation-corpus-relative-journals")
        );
        writeAuthentication(authentication(null, null, null));
        String relativePath = "./var/../var/observation-corpus.jsonl";
        KaironConfiguration configuration = KaironConfiguration.load(
                writeConfiguration(
                        "observation-corpus-relative-output.json",
                        configuration(
                                "live",
                                journalDirectory,
                                null,
                                "lm-studio",
                                observationCorpusSection(true, jsonString(relativePath))
                        )
                )
        );
        assertEquals(
                Path.of(relativePath).toAbsolutePath().normalize(),
                configuration.observationCorpus().outputFile()
        );
        assertTrue(configuration.observationCorpus().enabled());
    }

    @Test
    void enabledObservationCorpusRejectsBlankOutputFile() throws IOException {
        Path journalDirectory = Files.createDirectory(
                temporaryDirectory.resolve("observation-corpus-blank-journals")
        );
        writeAuthentication(authentication(null, null, null));
        ConfigurationException failure = assertThrows(
                ConfigurationException.class,
                () -> KaironConfiguration.load(
                        writeConfiguration(
                                "observation-corpus-blank-output.json",
                                configuration(
                                        "live",
                                        journalDirectory,
                                        null,
                                        "lm-studio",
                                        observationCorpusSection(true, jsonString("   "))
                                )
                        )
                )
        );
        assertEquals("$.observationCorpus.outputFile", failure.path());
    }

    @Test
    void rejectsInvalidCliMalformedUtf8AndNonStrictJson() throws IOException {
        Path journalDirectory = Files.createDirectory(temporaryDirectory.resolve("strict-journals"));
        String valid = configuration("live", journalDirectory, null, "lm-studio");
        writeAuthentication(authentication(null, null, null));

        assertFailure(() -> KaironConfiguration.load(new String[0]), "CONFIG_ARGUMENT_INVALID");
        assertFailure(
                () -> KaironConfiguration.load(new String[]{"--config=", "--config=other"}),
                "CONFIG_ARGUMENT_INVALID"
        );
        assertFailure(
                () -> KaironConfiguration.load(new String[]{"config=" + temporaryDirectory}),
                "CONFIG_ARGUMENT_INVALID"
        );

        String unknown = "{\n  \"unexpected\": true," + valid.substring(1);
        assertJsonFailure(writeConfiguration("unknown.json", unknown));

        String duplicate = valid.replace(
                "\"mode\": \"live\",",
                "\"mode\": \"live\",\n    \"mode\": \"live\","
        );
        assertJsonFailure(writeConfiguration("duplicate.json", duplicate));
        assertJsonFailure(writeConfiguration("trailing.json", valid + "\n{}"));
        assertJsonFailure(writeConfiguration("comment.json", "// not allowed\n" + valid));

        String coerced = valid.replace(
                "\"quietPeriodMs\": 750",
                "\"quietPeriodMs\": \"750\""
        );
        assertJsonFailure(writeConfiguration("coerced.json", coerced));

        String missing = valid.replace(
                ",\n        \"responseFormat\": \"JSON_OBJECT\"",
                ""
        );
        assertJsonFailure(writeConfiguration("missing.json", missing));
        String missingPricing = valid.replace(
                ",\n        \"pricing\": null",
                ""
        );
        assertJsonFailure(writeConfiguration(
                "missing-pricing.json",
                missingPricing
        ));

        Path malformedUtf8 = temporaryDirectory.resolve("malformed-utf8.json");
        Files.write(malformedUtf8, new byte[]{'{', '"', (byte) 0xC3, 0x28, '"', '}'});
        assertJsonFailure(malformedUtf8);

        Path validConfiguration = writeConfiguration("valid.json", valid);
        Files.delete(temporaryDirectory.resolve("authentication.json"));
        assertFailure(
                () -> KaironConfiguration.load(validConfiguration),
                "AUTHENTICATION_FILE_UNREADABLE"
        );

        writeAuthentication("{\"unexpected\":true}");
        assertFailure(
                () -> KaironConfiguration.load(validConfiguration),
                "AUTHENTICATION_JSON_INVALID"
        );

        writeAuthentication("""
                {
                  "llm": {"providers": {}},
                  "speech": {"googleCloudTts": null},
                  "unexpected": true
                }
                """);
        assertFailure(
                () -> KaironConfiguration.load(validConfiguration),
                "AUTHENTICATION_JSON_INVALID"
        );
    }

    @Test
    void validatesSourceObserverProviderAndActiveSelection() throws IOException {
        Path journalDirectory = Files.createDirectory(temporaryDirectory.resolve("validation-journals"));
        Path replayFile = Files.writeString(
                temporaryDirectory.resolve("Journal.replay.log"),
                "{}\n",
                StandardCharsets.UTF_8
        );
        writeAuthentication(authentication(null, null, null));

        KaironConfiguration replay = KaironConfiguration.load(writeConfiguration(
                "replay.json",
                configuration("replay", null, replayFile, "lm-studio")
        ));
        assertEquals(SourceMode.REPLAY, replay.source().mode());
        assertNull(replay.source().journalDirectory());
        assertEquals(replayFile.toAbsolutePath().normalize(), replay.source().replayFile());

        assertFailure(
                () -> KaironConfiguration.load(writeConfiguration(
                        "source-conflict.json",
                        configuration("live", journalDirectory, replayFile, "lm-studio")
                )),
                "CONFIG_SOURCE_CONFLICT"
        );

        String invalidObserver = configuration(
                "live",
                journalDirectory,
                null,
                "lm-studio"
        ).replace("\"quietPeriodMs\": 750", "\"quietPeriodMs\": 751");
        assertFailure(
                () -> KaironConfiguration.load(writeConfiguration(
                        "invalid-observer.json",
                        invalidObserver
                )),
                "CONFIG_OBSERVER_LIMIT_INVALID"
        );

        String invalidUi = configuration(
                "live",
                journalDirectory,
                null,
                "lm-studio"
        ).replace(
                "\"maximumObservationRows\": 1000",
                "\"maximumObservationRows\": 0"
        );
        assertFailure(
                () -> KaironConfiguration.load(writeConfiguration(
                        "invalid-ui.json",
                        invalidUi
                )),
                "CONFIG_UI_LIMIT_INVALID"
        );

        String missingActive = configuration(
                "live",
                journalDirectory,
                null,
                "missing"
        );
        assertFailure(
                () -> KaironConfiguration.load(writeConfiguration(
                        "missing-active.json",
                        missingActive
                )),
                "CONFIG_ACTIVE_PROVIDER_INVALID"
        );

        String credentialInMainConfiguration = configuration(
                "live",
                journalDirectory,
                null,
                "lm-studio"
        ).replace(
                "\"model\": \"mistral-model\",",
                "\"model\": \"mistral-model\",\n"
                        + "        \"apiKey\": \"must-not-be-here\","
        );
        assertFailure(
                () -> KaironConfiguration.load(writeConfiguration(
                        "credential-in-main.json",
                        credentialInMainConfiguration
                )),
                "CONFIG_JSON_INVALID"
        );

        String credentialInUrl = configuration(
                "live",
                journalDirectory,
                null,
                "lm-studio"
        ).replace(
                "https://api.mistral.ai/v1",
                "https://user:password@api.mistral.ai/v1"
        );
        assertFailure(
                () -> KaironConfiguration.load(writeConfiguration(
                        "credential-url.json",
                        credentialInUrl
                )),
                "CONFIG_PROVIDER_BASE_URL_INVALID"
        );

        String negativePricing = configuration(
                "live",
                journalDirectory,
                null,
                "lm-studio"
        ).replace(
                "\"cachedInputPerMillionTokens\": 0.015",
                "\"cachedInputPerMillionTokens\": -0.001"
        );
        assertFailure(
                () -> KaironConfiguration.load(writeConfiguration(
                        "negative-pricing.json",
                        negativePricing
                )),
                "CONFIG_PROVIDER_PRICING_RATE_INVALID"
        );

        String invalidCurrency = configuration(
                "live",
                journalDirectory,
                null,
                "lm-studio"
        ).replace("\"currency\": \"USD\"", "\"currency\": \"usd\"");
        assertFailure(
                () -> KaironConfiguration.load(writeConfiguration(
                        "invalid-pricing-currency.json",
                        invalidCurrency
                )),
                "CONFIG_PROVIDER_PRICING_CURRENCY_INVALID"
        );

        writeAuthentication(authentication(null, null, "unknown"));
        assertFailure(
                () -> KaironConfiguration.load(writeConfiguration(
                        "unknown-auth-profile.json",
                        configuration(
                                "live",
                                journalDirectory,
                                null,
                                "lm-studio"
                        )
                )),
                "AUTHENTICATION_LLM_PROFILE_UNKNOWN"
        );
    }

    @Test
    void validatesBehaviorGraphConfiguration() throws IOException {
        Path journalDirectory = Files.createDirectory(
                temporaryDirectory.resolve("behavior-journals")
        );
        String valid = configuration(
                "live",
                journalDirectory,
                null,
                "lm-studio"
        );
        writeAuthentication(authentication(null, null, null));

        String malformedHalfLife = valid.replace(
                "\"weightHalfLife\": \"P30D\"",
                "\"weightHalfLife\": \"thirty-days\""
        );
        assertFailure(
                () -> KaironConfiguration.load(writeConfiguration(
                        "behavior-half-life-malformed.json",
                        malformedHalfLife
                )),
                "CONFIG_BEHAVIOR_GRAPH_HALF_LIFE_INVALID"
        );

        String zeroHalfLife = valid.replace(
                "\"weightHalfLife\": \"P30D\"",
                "\"weightHalfLife\": \"PT0S\""
        );
        assertFailure(
                () -> KaironConfiguration.load(writeConfiguration(
                        "behavior-half-life-zero.json",
                        zeroHalfLife
                )),
                "CONFIG_BEHAVIOR_GRAPH_HALF_LIFE_INVALID"
        );

        String invalidPrior = valid.replace(
                "\"contextPriorStrength\": 2.0",
                "\"contextPriorStrength\": 0.0"
        );
        assertFailure(
                () -> KaironConfiguration.load(writeConfiguration(
                        "behavior-prior.json",
                        invalidPrior
                )),
                "CONFIG_BEHAVIOR_GRAPH_PRIOR_STRENGTH_INVALID"
        );

        String invalidSnapshotInterval = valid.replace(
                "\"snapshotEverySignificantEvents\": 50",
                "\"snapshotEverySignificantEvents\": 0"
        );
        assertFailure(
                () -> KaironConfiguration.load(writeConfiguration(
                        "behavior-snapshot-interval.json",
                        invalidSnapshotInterval
                )),
                "CONFIG_BEHAVIOR_GRAPH_SNAPSHOT_INTERVAL_INVALID"
        );

        String rawPayloadEnabled = valid.replace(
                "\"storeRawJournalPayload\": false",
                "\"storeRawJournalPayload\": true"
        );
        assertFailure(
                () -> KaironConfiguration.load(writeConfiguration(
                        "behavior-raw-payload.json",
                        rawPayloadEnabled
                )),
                "CONFIG_BEHAVIOR_GRAPH_RAW_PAYLOAD_UNSUPPORTED"
        );

        Path storageFile = Files.writeString(
                temporaryDirectory.resolve("behavior-store-file"),
                "not a directory",
                StandardCharsets.UTF_8
        );
        String invalidStorage = valid.replace(
                "\"storageDirectory\": \"./data/behavior-graphs\"",
                "\"storageDirectory\": " + jsonString(storageFile.toString())
        );
        assertFailure(
                () -> KaironConfiguration.load(writeConfiguration(
                        "behavior-storage-file.json",
                        invalidStorage
                )),
                "CONFIG_BEHAVIOR_GRAPH_STORAGE_INVALID"
        );

        String unknownProperty = valid.replace(
                "\"behaviorGraph\": {\n    \"enabled\": true,",
                "\"behaviorGraph\": {\n"
                        + "    \"enabled\": true,\n"
                        + "    \"unexpected\": true,"
        );
        assertJsonFailure(writeConfiguration(
                "behavior-unknown-property.json",
                unknownProperty
        ));

        String missingSection = valid.replace(
                """
                  "behaviorGraph": {
                    "enabled": true,
                    "storageDirectory": "./data/behavior-graphs",
                    "weightHalfLife": "P30D",
                    "contextPriorStrength": 2.0,
                    "snapshotEverySignificantEvents": 50,
                    "storeRawJournalPayload": false
                  },
                """,
                ""
        );
        assertFailure(
                () -> KaironConfiguration.load(writeConfiguration(
                        "behavior-section-missing.json",
                        missingSection
                )),
                "CONFIG_REQUIRED_VALUE_MISSING"
        );
    }

    @Test
    void resolvesOnlyActiveMistralCredentialAndRedactsResolvedProvider() throws IOException {
        Path journalDirectory = Files.createDirectory(temporaryDirectory.resolve("auth-journals"));
        Path configurationFile = writeConfiguration(
                "mistral.json",
                configuration("live", journalDirectory, null, "mistral")
        );

        String secret = "test-" + UUID.randomUUID();
        writeAuthentication(authentication(secret, null, null));
        KaironConfiguration configuration =
                KaironConfiguration.load(configurationFile);
        ResolvedProviderConfiguration provider =
                configuration.resolveActiveProvider();

        assertEquals(LlmProviderType.MISTRAL, provider.type());
        assertEquals(secret, provider.apiKey().orElseThrow());
        assertEquals(
                new BigDecimal("0.15"),
                provider.pricing().orElseThrow().inputPerMillionTokens()
        );
        assertEquals(
                new BigDecimal("0.015"),
                provider.pricing().orElseThrow().cachedInputPerMillionTokens()
        );
        assertEquals(
                new BigDecimal("0.60"),
                provider.pricing().orElseThrow().outputPerMillionTokens()
        );
        assertTrue(provider.toString().contains("apiKey=<redacted>"));
        assertFalse(provider.toString().contains(secret));
        assertFalse(configuration.toString().contains(secret));

        writeAuthentication(authentication(
                "replace-with-mistral-api-key",
                null,
                null
        ));
        ConfigurationException placeholder = assertThrows(
                ConfigurationException.class,
                () -> KaironConfiguration.load(configurationFile)
        );
        assertEquals("AUTHENTICATION_API_KEY_INVALID", placeholder.code());
        assertFalse(placeholder.getMessage().contains(secret));

        writeAuthentication(authentication(null, null, null));
        ConfigurationException missing = assertThrows(
                ConfigurationException.class,
                () -> KaironConfiguration.load(configurationFile)
        );
        assertEquals(
                "AUTHENTICATION_ACTIVE_LLM_KEY_MISSING",
                missing.code()
        );
        assertTrue(missing.getMessage().contains("mistral"));
        assertFalse(missing.getMessage().contains(secret));
    }

    private Path writeConfiguration(String fileName, String json) throws IOException {
        return Files.writeString(
                temporaryDirectory.resolve(fileName),
                json,
                StandardCharsets.UTF_8
        );
    }

    private Path writeAuthentication(String json) throws IOException {
        return Files.writeString(
                temporaryDirectory.resolve("authentication.json"),
                json,
                StandardCharsets.UTF_8
        );
    }

    private static void assertJsonFailure(Path path) {
        assertFailure(() -> KaironConfiguration.load(path), "CONFIG_JSON_INVALID");
    }

    private static void assertFailure(ThrowingAction action, String expectedCode) {
        ConfigurationException exception = assertThrows(ConfigurationException.class, action::run);
        assertEquals(expectedCode, exception.code());
    }

    private static String configuration(
            String mode,
            Path journalDirectory,
            Path replayFile,
            String activeProvider
    ) {
        return """
                {
                  "source": {
                    "mode": %s,
                    "journalDirectory": %s,
                    "replayFile": %s
                  },
                  "observer": {
                    "outputLanguage": "ru",
                    "quietPeriodMs": 750,
                    "maximumBatchAgeMs": 2000,
                    "traceFile": %s
                  },
                  "ui": {
                    "enabled": true,
                    "maximumObservationRows": 1000,
                    "maximumTurnRows": 200
                  },
                  "speech": {
                    "enabled": false,
                    "provider": "GOOGLE_CLOUD_TTS",
                    "languageCode": "ru-RU",
                    "voiceName": "replace-with-google-voice-name",
                    "audioEncoding": "LINEAR16",
                    "speakingRate": 1.0,
                    "pitch": 0.0,
                    "volumeGainDb": 0.0,
                    "requestTimeoutMs": 15000,
                    "outputDevice": null,
                    "alsoPrintToConsole": true
                  },
                  "behaviorGraph": {
                    "enabled": true,
                    "storageDirectory": "./data/behavior-graphs",
                    "weightHalfLife": "P30D",
                    "contextPriorStrength": 2.0,
                    "snapshotEverySignificantEvents": 50,
                    "storeRawJournalPayload": false
                  },
                  "llm": {
                    "activeProvider": %s,
                    "providers": {
                      "lm-studio": {
                        "type": "LM_STUDIO",
                        "baseUrl": "http://localhost:1234/v1///",
                        "model": "local-model",
                        "temperature": 0.2,
                        "maximumOutputTokens": 256,
                        "requestTimeoutMs": 30000,
                        "responseFormat": "JSON_OBJECT",
                        "pricing": null
                      },
                      "mistral": {
                        "type": "MISTRAL",
                        "baseUrl": "https://api.mistral.ai/v1",
                        "model": "mistral-model",
                        "temperature": 0.2,
                        "maximumOutputTokens": 256,
                        "requestTimeoutMs": 30000,
                        "responseFormat": "JSON_OBJECT",
                        "pricing": {
                          "currency": "USD",
                          "inputPerMillionTokens": 0.15,
                          "cachedInputPerMillionTokens": 0.015,
                          "outputPerMillionTokens": 0.60
                        }
                      }
                    }
                  }
                }
                """.formatted(
                jsonString(mode),
                nullablePath(journalDirectory),
                nullablePath(replayFile),
                jsonString(TRACE_PATH),
                jsonString(activeProvider)
        );
    }

    private static String configuration(
            String mode,
            Path journalDirectory,
            Path replayFile,
            String activeProvider,
            String observationCorpusSection
    ) {
        String baseConfiguration = configuration(
                mode,
                journalDirectory,
                replayFile,
                activeProvider
        );
        int insertionPoint = baseConfiguration.indexOf("\"llm\": {");
        if (insertionPoint < 0) {
            throw new IllegalStateException(
                    "Unable to insert observation corpus section"
            );
        }
        return baseConfiguration.substring(0, insertionPoint)
                + observationCorpusSection
                + baseConfiguration.substring(insertionPoint);
    }

    private static String bioSection(String registryFile) {
        return "                  \"bio\": {\n"
                + "                    \"registryFile\": " + registryFile + "\n"
                + "                  },\n";
    }

    private static String observationCorpusSection(
            boolean enabled,
            String outputFile
    ) {
        StringBuilder section = new StringBuilder();
        section.append("                  \"observationCorpus\": {\n");
        section.append("                    \"enabled\": ")
                .append(enabled ? "true" : "false")
                .append(",\n");
        if (outputFile != null) {
            section.append("                    \"outputFile\": ")
                    .append(outputFile)
                    .append(",\n");
        }
        section.setLength(section.length() - 2);
        section.append("\n");
        section.append("                  },\n");
        return section.toString();
    }

    private static String authentication(
            String mistralApiKey,
            String googleCloudTtsApiKey,
            String additionalProfile
    ) {
        StringBuilder providers = new StringBuilder();
        if (mistralApiKey != null) {
            providers.append("""
                    "mistral": {"apiKey": %s}
                    """.formatted(jsonString(mistralApiKey)).strip());
        }
        if (additionalProfile != null) {
            if (!providers.isEmpty()) {
                providers.append(',');
            }
            providers.append(jsonString(additionalProfile))
                    .append(": {\"apiKey\": \"unused-test-key\"}");
        }
        return """
                {
                  "llm": {
                    "providers": {%s}
                  },
                  "speech": {
                    "googleCloudTts": %s
                  }
                }
                """.formatted(
                providers,
                googleCloudTtsApiKey == null
                        ? "null"
                        : "{\"apiKey\": "
                        + jsonString(googleCloudTtsApiKey)
                        + '}'
        );
    }

    private static String nullablePath(Path path) {
        return path == null ? "null" : jsonString(path.toString());
    }

    private static String jsonString(String value) {
        return '"'
                + value.replace("\\", "\\\\").replace("\"", "\\\"")
                + '"';
    }

    @FunctionalInterface
    private interface ThrowingAction {

        void run() throws Exception;
    }
}
