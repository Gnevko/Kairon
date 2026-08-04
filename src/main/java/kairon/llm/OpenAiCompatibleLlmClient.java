package kairon.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kairon.config.KaironConfiguration;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * The one Phase 0 transport for both LM Studio and Mistral provider profiles.
 */
public final class OpenAiCompatibleLlmClient implements LlmClient {

    private final KaironConfiguration.ResolvedProviderConfiguration configuration;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final ProviderDescriptor descriptor;

    public OpenAiCompatibleLlmClient(
            KaironConfiguration.ResolvedProviderConfiguration configuration
    ) {
        this(
                configuration,
                HttpClient.newBuilder()
                        .connectTimeout(configuration.requestTimeout())
                        .build(),
                new ObjectMapper()
        );
    }

    public OpenAiCompatibleLlmClient(
            KaironConfiguration.ResolvedProviderConfiguration configuration,
            HttpClient httpClient,
            ObjectMapper mapper
    ) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.descriptor = new ProviderDescriptor(
                configuration.profileName(),
                configuration.type().name(),
                configuration.baseUrl(),
                configuration.model()
        );
    }

    @Override
    public CompletionStage<LlmResponse> complete(ModelInput exactModelInput) {
        Objects.requireNonNull(exactModelInput, "exactModelInput");
        final HttpRequest request;
        try {
            request = buildRequest(exactModelInput);
        } catch (RuntimeException exception) {
            throw new LlmClientException("LLM_REQUEST_PREPARATION_FAILED");
        }

        long startedAt = System.nanoTime();
        CompletableFuture<HttpResponse<String>> transport =
                httpClient.sendAsync(
                        request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                );
        CompletableFuture<LlmResponse> result = new CompletableFuture<>();

        transport.whenComplete((response, failure) -> {
            if (result.isCancelled()) {
                return;
            }
            long latencyMs = Duration.ofNanos(System.nanoTime() - startedAt)
                    .toMillis();
            try {
                if (failure != null || response == null) {
                    throw new LlmClientException("LLM_TRANSPORT_FAILED");
                }
                if (response.statusCode() < 200
                        || response.statusCode() >= 300) {
                    throw new LlmClientException(
                            "LLM_HTTP_STATUS_" + response.statusCode()
                    );
                }
                ParsedResponse parsed = parseResponse(response.body());
                result.complete(new LlmResponse(
                        parsed.content(),
                        latencyMs,
                        parsed.tokenUsage()
                ));
            } catch (RuntimeException requestFailure) {
                result.completeExceptionally(requestFailure);
            }
        });
        result.whenComplete((ignoredResponse, ignoredFailure) -> {
            if (result.isCancelled()) {
                transport.cancel(true);
            }
        });
        return result;
    }

    @Override
    public ProviderDescriptor provider() {
        return descriptor;
    }

    @Override
    public void close() {
        httpClient.close();
    }

    private HttpRequest buildRequest(ModelInput modelInput) {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("model", configuration.model());
        ArrayNode messages = envelope.putArray("messages");

        ObjectNode systemMessage = messages.addObject();
        systemMessage.put("role", "system");
        systemMessage.put("content", modelInput.systemMessage());

        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        userMessage.put("content", modelInput.userMessage());

        envelope.put("temperature", configuration.temperature());
        envelope.put("max_tokens", configuration.maximumOutputTokens());
        envelope.putObject("response_format").put("type", "json_object");
        envelope.put("stream", false);

        final String requestBody;
        try {
            requestBody = mapper.writeValueAsString(envelope);
        } catch (JsonProcessingException exception) {
            throw new LlmClientException("LLM_REQUEST_SERIALIZATION_FAILED");
        }

        HttpRequest.Builder request = HttpRequest.newBuilder(configuration.chatCompletionsUri())
                .timeout(configuration.requestTimeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));
        configuration.apiKey().ifPresent(
                secret -> request.header("Authorization", "Bearer " + secret)
        );
        return request.build();
    }

    private ParsedResponse parseResponse(String responseBody) {
        final JsonNode root;
        try {
            root = mapper.readTree(responseBody);
        } catch (JsonProcessingException exception) {
            throw new LlmClientException("LLM_RESPONSE_ENVELOPE_INVALID");
        }
        if (root == null || !root.isObject()) {
            throw new LlmClientException("LLM_RESPONSE_ENVELOPE_INVALID");
        }

        LlmTokenUsage tokenUsage = parseTokenUsage(root.get("usage"));
        JsonNode choices = root.get("choices");
        JsonNode content = choices != null && choices.isArray() && !choices.isEmpty()
                ? choices.get(0).path("message").get("content")
                : null;
        if (content == null || !content.isTextual()) {
            throw new LlmClientException(
                    "LLM_RESPONSE_CONTENT_MISSING",
                    tokenUsage
            );
        }
        return new ParsedResponse(content.textValue(), tokenUsage);
    }

    private LlmTokenUsage parseTokenUsage(JsonNode usage) {
        if (usage == null || usage.isNull()) {
            return LlmTokenUsage.unavailable();
        }
        if (!usage.isObject()) {
            return LlmTokenUsage.invalid();
        }

        try {
            Long input = optionalNonNegativeLong(usage.get("prompt_tokens"));
            Long output = optionalNonNegativeLong(usage.get("completion_tokens"));
            Long total = optionalNonNegativeLong(usage.get("total_tokens"));
            Long cached = parseCachedInputTokens(
                    usage.get("prompt_tokens_details"),
                    input
            );
            if (cached != null && input == null) {
                return LlmTokenUsage.invalid();
            }
            if (input != null && output != null && total != null) {
                long minimumTotal = Math.addExact(input, output);
                if (total < minimumTotal) {
                    return LlmTokenUsage.invalid();
                }
            }
            return LlmTokenUsage.reported(input, cached, output, total);
        } catch (ArithmeticException | InvalidUsageException
                 | IllegalArgumentException ignored) {
            return LlmTokenUsage.invalid();
        }
    }

    private Long parseCachedInputTokens(JsonNode details, Long inputTokens) {
        if (details != null && !details.isNull() && !details.isObject()) {
            throw new InvalidUsageException();
        }

        Long cached = details == null || details.isNull()
                ? null
                : optionalNonNegativeLong(details.get("cached_tokens"));
        if (cached == null
                && inputTokens != null
                && configuration.type()
                == KaironConfiguration.LlmProviderType.MISTRAL) {
            // Mistral documents an omitted cached_tokens field as a cache miss.
            return 0L;
        }
        return cached;
    }

    private static Long optionalNonNegativeLong(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new InvalidUsageException();
        }
        long result = value.longValue();
        if (result < 0) {
            throw new InvalidUsageException();
        }
        return result;
    }

    private record ParsedResponse(String content, LlmTokenUsage tokenUsage) {
    }

    private static final class InvalidUsageException extends RuntimeException {

        private InvalidUsageException() {
            super(null, null, false, false);
        }
    }

    static final class LlmClientException extends RuntimeException {

        private final LlmTokenUsage tokenUsage;

        private LlmClientException(String safeCode) {
            this(safeCode, LlmTokenUsage.unavailable());
        }

        private LlmClientException(
                String safeCode,
                LlmTokenUsage tokenUsage
        ) {
            super(safeCode, null, false, false);
            this.tokenUsage = Objects.requireNonNull(
                    tokenUsage,
                    "tokenUsage"
            );
        }

        LlmTokenUsage tokenUsage() {
            return tokenUsage;
        }
    }
}
