package kairon.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import kairon.config.KaironConfiguration.LlmProviderType;
import kairon.config.KaironConfiguration.LlmTokenPricing;
import kairon.config.KaironConfiguration.ResolvedProviderConfiguration;
import kairon.config.KaironConfiguration.ResponseFormat;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleLlmClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void lmStudioOmitsAuthAndMistralUsesBearerWhileSharingPayloadAndSafeFailures()
            throws Exception {
        List<String> authorizations = new ArrayList<>();
        List<String> systemMessages = new ArrayList<>();
        List<String> userMessages = new ArrayList<>();
        List<String> messageRoles = new ArrayList<>();
        List<Integer> messageCounts = new ArrayList<>();
        AtomicInteger requestCount = new AtomicInteger();
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0),
                0
        );
        server.createContext("/v1/chat/completions", exchange -> {
            synchronized (authorizations) {
                authorizations.add(Optional.ofNullable(
                        exchange.getRequestHeaders().getFirst("Authorization")
                ).orElse("<absent>"));
                JsonNode envelope = JSON.readTree(exchange.getRequestBody());
                JsonNode messages = envelope.path("messages");
                messageCounts.add(messages.size());
                messageRoles.add(
                        messages.get(0).path("role").textValue()
                                + ","
                                + messages.get(1).path("role").textValue()
                );
                systemMessages.add(messages.get(0).path("content").textValue());
                userMessages.add(messages.get(1).path("content").textValue());
            }
            int currentRequest = requestCount.incrementAndGet();
            if (currentRequest == 6) {
                respond(exchange, 401, "{\"error\":\"denied\"}");
            } else if (currentRequest == 5) {
                respond(
                        exchange,
                        200,
                        """
                        {"choices":[],"usage":{"prompt_tokens":80,
                        "completion_tokens":15,"total_tokens":95,
                        "prompt_tokens_details":{"cached_tokens":32}}}
                        """
                );
            } else {
                String usage = switch (currentRequest) {
                    case 1 -> """
                        ,"usage":{"prompt_tokens":100,
                        "completion_tokens":20,"total_tokens":120}
                        """;
                    case 2 -> """
                        ,"usage":{"prompt_tokens":200,
                        "completion_tokens":30,"total_tokens":230,
                        "prompt_tokens_details":{"cached_tokens":64}}
                        """;
                    case 3 -> """
                        ,"usage":{"prompt_tokens":50,
                        "completion_tokens":10,"total_tokens":60}
                        """;
                    default -> """
                        ,"usage":{"prompt_tokens":-1,
                        "completion_tokens":10,"total_tokens":9}
                        """;
                };
                respond(
                        exchange,
                        200,
                        "{\"choices\":[{\"message\":{\"content\":"
                                + "\"{\\\"decision\\\":\\\"SILENT\\\","
                                + "\\\"comment\\\":null,"
                                + "\\\"evidenceTriggerBusSequences\\\":[]}"
                                + "\"}}]"
                                + usage
                                + '}'
                );
            }
        });
        server.start();

        String runtimeSecret = "ephemeral-" + UUID.randomUUID();
        URI baseUrl = URI.create("http://127.0.0.1:"
                + server.getAddress().getPort() + "/v1");
        ResolvedProviderConfiguration lmStudio = provider(
                "lm-studio",
                LlmProviderType.LM_STUDIO,
                baseUrl,
                Optional.empty()
        );
        ResolvedProviderConfiguration mistral = provider(
                "mistral",
                LlmProviderType.MISTRAL,
                baseUrl,
                Optional.of(runtimeSecret)
        );
        String semanticMessage =
                "{\"outputLanguage\":\"en\",\"previousComments\":[],\"events\":[]}";
        LlmClient.ModelInput modelInput = new LlmClient.ModelInput(
                DecisionPromptFactory.SYSTEM_PROMPT,
                semanticMessage
        );
        Queue<Long> rejectedResponseClock = new ConcurrentLinkedQueue<>(
                List.of(0L, 1_000_000_000L)
        );
        List<String> rejectedResponseLogs = new ArrayList<>();
        CountDownLatch rejectedResponseRecorded = new CountDownLatch(1);
        LlmRequestStatistics rejectedResponseStatistics =
                new LlmRequestStatistics(
                        Optional.of(new LlmTokenPricing(
                                "USD",
                                new BigDecimal("0.15"),
                                new BigDecimal("0.015"),
                                new BigDecimal("0.60")
                        )),
                        rejectedResponseClock::remove,
                        line -> {
                            rejectedResponseLogs.add(line);
                            rejectedResponseRecorded.countDown();
                        }
                );

        try (OpenAiCompatibleLlmClient localClient =
                     new OpenAiCompatibleLlmClient(lmStudio);
             OpenAiCompatibleLlmClient hostedClient =
                     new OpenAiCompatibleLlmClient(mistral);
             OpenAiCompatibleLlmClient hostedCacheMissClient =
                     new OpenAiCompatibleLlmClient(mistral);
             OpenAiCompatibleLlmClient invalidUsageClient =
                     new OpenAiCompatibleLlmClient(lmStudio);
             LlmClient rejectedResponseClient =
                     rejectedResponseStatistics.instrument(
                             new OpenAiCompatibleLlmClient(mistral)
                     );
             OpenAiCompatibleLlmClient failingClient =
                     new OpenAiCompatibleLlmClient(mistral)) {
            LlmClient.LlmResponse localResponse =
                    localClient.complete(modelInput).toCompletableFuture().join();
            LlmClient.LlmResponse hostedResponse =
                    hostedClient.complete(modelInput).toCompletableFuture().join();
            LlmClient.LlmResponse hostedCacheMissResponse =
                    hostedCacheMissClient.complete(modelInput)
                            .toCompletableFuture()
                            .join();
            LlmClient.LlmResponse invalidUsageResponse =
                    invalidUsageClient.complete(modelInput)
                            .toCompletableFuture()
                            .join();
            assertTrue(localResponse.content().contains("\"SILENT\""));
            assertEquals(localResponse.content(), hostedResponse.content());
            assertEquals(100L, localResponse.tokenUsage().inputTokens());
            assertEquals(20L, localResponse.tokenUsage().outputTokens());
            assertEquals(
                    LlmClient.TokenUsageStatus.PARTIAL,
                    localResponse.tokenUsage().status()
            );
            assertNull(localResponse.tokenUsage().cachedInputTokens());
            assertEquals(200L, hostedResponse.tokenUsage().inputTokens());
            assertEquals(64L, hostedResponse.tokenUsage().cachedInputTokens());
            assertEquals(136L, hostedResponse.tokenUsage().uncachedInputTokens());
            assertEquals(
                    LlmClient.TokenUsageStatus.COMPLETE,
                    hostedResponse.tokenUsage().status()
            );
            assertEquals(
                    0L,
                    hostedCacheMissResponse.tokenUsage().cachedInputTokens()
            );
            assertEquals(
                    LlmClient.TokenUsageStatus.COMPLETE,
                    hostedCacheMissResponse.tokenUsage().status()
            );
            assertEquals(
                    LlmClient.TokenUsageStatus.INVALID,
                    invalidUsageResponse.tokenUsage().status()
            );
            assertEquals(localResponse.content(), invalidUsageResponse.content());

            CompletionException rejectedResponse = assertThrows(
                    CompletionException.class,
                    () -> rejectedResponseClient.complete(modelInput)
                            .toCompletableFuture()
                            .join()
            );
            assertTrue(rejectedResponse.toString().contains(
                    "LLM_RESPONSE_CONTENT_MISSING"
            ));
            assertTrue(rejectedResponseRecorded.await(1, TimeUnit.SECONDS));
            LlmRequestStatistics.Snapshot rejectedSnapshot =
                    rejectedResponseStatistics.snapshot();
            assertEquals(1L, rejectedSnapshot.failedCalls());
            assertEquals(1L, rejectedSnapshot.completeUsageCalls());
            assertEquals(80L, rejectedSnapshot.inputTokens());
            assertEquals(32L, rejectedSnapshot.cachedInputTokens());
            assertEquals(15L, rejectedSnapshot.outputTokens());
            assertEquals(95L, rejectedSnapshot.totalTokens());
            assertEquals(1L, rejectedSnapshot.pricedCalls());
            assertEquals(
                    0,
                    new BigDecimal("0.00001668").compareTo(
                            rejectedSnapshot.estimatedCumulativeCost()
                                    .orElseThrow()
                    )
            );
            assertTrue(rejectedResponseLogs.get(0).contains(
                    "outcome=FAILURE"
            ));
            assertTrue(rejectedResponseLogs.get(0).contains(
                    "estimatedCost=0.00001668"
            ));

            CompletionException failure = assertThrows(
                    CompletionException.class,
                    () -> failingClient.complete(modelInput).toCompletableFuture().join()
            );
            assertFalse(failure.toString().contains(runtimeSecret));
            assertFalse(mistral.toString().contains(runtimeSecret));
        } finally {
            server.stop(0);
        }

        assertEquals(List.of(
                        "<absent>",
                        "Bearer " + runtimeSecret,
                        "Bearer " + runtimeSecret,
                        "<absent>",
                        "Bearer " + runtimeSecret,
                        "Bearer " + runtimeSecret
                ),
                authorizations);
        assertEquals(
                List.of(
                        "system,user",
                        "system,user",
                        "system,user",
                        "system,user",
                        "system,user",
                        "system,user"
                ),
                messageRoles
        );
        assertEquals(List.of(2, 2, 2, 2, 2, 2), messageCounts);
        assertEquals(
                List.of(
                        DecisionPromptFactory.SYSTEM_PROMPT,
                        DecisionPromptFactory.SYSTEM_PROMPT,
                        DecisionPromptFactory.SYSTEM_PROMPT,
                        DecisionPromptFactory.SYSTEM_PROMPT,
                        DecisionPromptFactory.SYSTEM_PROMPT,
                        DecisionPromptFactory.SYSTEM_PROMPT
                ),
                systemMessages
        );
        assertEquals(List.of(
                        semanticMessage,
                        semanticMessage,
                        semanticMessage,
                        semanticMessage,
                        semanticMessage,
                        semanticMessage
                ),
                userMessages);

        ControllableHttpClient controllableHttp = new ControllableHttpClient();
        try (OpenAiCompatibleLlmClient cancellableClient =
                     new OpenAiCompatibleLlmClient(
                             mistral,
                             controllableHttp,
                             JSON
                     )) {
            CompletableFuture<LlmClient.LlmResponse> cancellable =
                    cancellableClient.complete(modelInput)
                            .toCompletableFuture();
            assertTrue(cancellable.cancel(true));
            assertTrue(controllableHttp.transport.isCancelled());
        }
    }

    private static ResolvedProviderConfiguration provider(
            String profileName,
            LlmProviderType type,
            URI baseUrl,
            Optional<String> apiKey
    ) {
        return new ResolvedProviderConfiguration(
                profileName,
                type,
                baseUrl,
                "explicit-test-model",
                apiKey,
                0.2,
                256,
                Duration.ofSeconds(5),
                ResponseFormat.JSON_OBJECT,
                Optional.empty()
        );
    }

    private static void respond(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (exchange; var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static final class ControllableHttpClient extends HttpClient {

        private final CompletableFuture<HttpResponse<?>> transport =
                new CompletableFuture<>();

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            try {
                return SSLContext.getDefault();
            } catch (NoSuchAlgorithmException failure) {
                throw new IllegalStateException(failure);
            }
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> HttpResponse<T> send(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler
        ) {
            throw new UnsupportedOperationException("synchronous send");
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler
        ) {
            return (CompletableFuture) transport;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler
        ) {
            return sendAsync(request, responseBodyHandler);
        }

        @Override
        public void close() {
        }
    }
}
