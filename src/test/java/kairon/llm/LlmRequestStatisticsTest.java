package kairon.llm;

import kairon.config.KaironConfiguration.LlmTokenPricing;
import kairon.llm.LlmClient.LlmResponse;
import kairon.llm.LlmClient.LlmTokenUsage;
import kairon.llm.LlmClient.ModelInput;
import kairon.llm.LlmClient.ProviderDescriptor;
import kairon.llm.LlmClient.TokenUsageStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LlmRequestStatisticsTest {

    @Test
    void recordsTerminalCallsTokenCacheCostLatencyAndWeightedThroughput() {
        Queue<Long> clock = new ArrayDeque<>(List.of(
                0L,
                1_000_000_000L,
                2_000_000_000L,
                4_000_000_000L,
                5_000_000_000L,
                5_500_000_000L,
                6_000_000_000L,
                6_250_000_000L
        ));
        List<String> logs = new ArrayList<>();
        LlmTokenPricing pricing = new LlmTokenPricing(
                "USD",
                new BigDecimal("0.15"),
                new BigDecimal("0.015"),
                new BigDecimal("0.60")
        );
        LlmRequestStatistics statistics = new LlmRequestStatistics(
                Optional.of(pricing),
                () -> clock.remove(),
                logs::add
        );

        CompletableFuture<LlmResponse> cancelled = new CompletableFuture<>();
        cancelled.cancel(false);
        CompletableFuture<LlmResponse> firstResponse =
                CompletableFuture.completedFuture(response(
                        "SECRET_MODEL_OUTPUT_ONE",
                        100L,
                        20L,
                        20L,
                        120L
                ));
        SequenceLlmClient delegate = new SequenceLlmClient(List.of(
                firstResponse,
                CompletableFuture.completedFuture(response(
                        "SECRET_MODEL_OUTPUT_TWO",
                        200L,
                        100L,
                        40L,
                        240L
                )),
                CompletableFuture.failedFuture(
                        new IllegalStateException("SECRET_PROVIDER_FAILURE")
                ),
                cancelled
        ));
        LlmClient client = statistics.instrument(delegate);
        ModelInput input = new ModelInput(
                "SECRET_SYSTEM_PROMPT",
                "SECRET_USER_PROMPT"
        );

        var returnedFirstStage = client.complete(input);
        assertSame(firstResponse, returnedFirstStage);
        returnedFirstStage.toCompletableFuture().join();
        client.complete(input).toCompletableFuture().join();
        assertThrows(
                CompletionException.class,
                () -> client.complete(input).toCompletableFuture().join()
        );
        var returnedCancelledStage = client.complete(input);
        assertSame(cancelled, returnedCancelledStage);
        assertThrows(
                java.util.concurrent.CancellationException.class,
                () -> returnedCancelledStage.toCompletableFuture().join()
        );

        LlmRequestStatistics.Snapshot snapshot = statistics.snapshot();
        assertEquals(4L, snapshot.completedCalls());
        assertEquals(2L, snapshot.successfulCalls());
        assertEquals(1L, snapshot.failedCalls());
        assertEquals(1L, snapshot.cancelledCalls());
        assertEquals(2L, snapshot.completeUsageCalls());
        assertEquals(2L, snapshot.unavailableUsageCalls());
        assertEquals(2L, snapshot.cacheUsageKnownCalls());
        assertEquals(2L, snapshot.pricedCalls());
        assertEquals(300L, snapshot.inputTokens());
        assertEquals(120L, snapshot.cachedInputTokens());
        assertEquals(60L, snapshot.outputTokens());
        assertEquals(360L, snapshot.totalTokens());
        assertEquals(
                937.5,
                snapshot.averageLatencyMs().orElseThrow(),
                0.000_001
        );
        assertEquals(
                1_500.0,
                snapshot.averageSuccessfulLatencyMs().orElseThrow(),
                0.000_001
        );
        assertEquals(
                20.0,
                snapshot.averageEndToEndOutputTokensPerSecond().orElseThrow(),
                0.000_001
        );
        assertEquals(
                40.0,
                snapshot.cacheHitPercent().orElseThrow(),
                0.000_001
        );
        assertEquals(
                0,
                new BigDecimal("0.0000648").compareTo(
                        snapshot.estimatedCumulativeCost().orElseThrow()
                )
        );
        assertEquals(
                0,
                new BigDecimal("0.0000324").compareTo(
                        snapshot.averageEstimatedCost().orElseThrow()
                )
        );

        assertEquals(4, logs.size());
        assertTrue(logs.get(0).contains("estimatedCost=0.0000243"));
        assertTrue(logs.get(0).contains(
                "inputRatePerMillionTokens=0.15"
        ));
        assertTrue(logs.get(1).contains("estimatedCost=0.0000405"));
        assertTrue(logs.get(2).contains("outcome=FAILURE"));
        assertTrue(logs.get(3).contains("outcome=CANCELLED"));
        String callLogs = String.join("\n", logs);
        assertFalse(callLogs.contains("SECRET_SYSTEM_PROMPT"));
        assertFalse(callLogs.contains("SECRET_USER_PROMPT"));
        assertFalse(callLogs.contains("SECRET_MODEL_OUTPUT"));
        assertFalse(callLogs.contains("SECRET_PROVIDER_FAILURE"));

        client.close();
        assertEquals(5, logs.size());
        assertTrue(logs.get(4).startsWith(
                "LLM_REQUEST_STATISTICS_SUMMARY"
        ));
        assertTrue(delegate.closed);

        Queue<Long> fallbackClock = new ArrayDeque<>(
                List.of(10L, 1_000_000_010L)
        );
        LlmRequestStatistics failingLogger = new LlmRequestStatistics(
                Optional.empty(),
                fallbackClock::remove,
                line -> {
                    throw new IllegalStateException("test log sink failure");
                }
        );
        LlmClient unaffected = failingLogger.instrument(
                new SequenceLlmClient(List.of(
                        CompletableFuture.completedFuture(
                                new LlmResponse("still returned", 1L)
                        )
                ))
        );
        assertEquals(
                "still returned",
                unaffected.complete(input).toCompletableFuture().join().content()
        );
        assertEquals(1L, failingLogger.snapshot().successfulCalls());
        assertEquals(1L, failingLogger.snapshot().unavailableUsageCalls());
        assertTrue(
                failingLogger.snapshot().estimatedCumulativeCost().isEmpty()
        );
        unaffected.close();

        Queue<Long> pendingClock = new ArrayDeque<>(
                List.of(20L, 1_000_000_020L)
        );
        List<String> pendingLogs = new ArrayList<>();
        CompletableFuture<LlmResponse> pending = new CompletableFuture<>();
        LlmRequestStatistics pendingStatistics = new LlmRequestStatistics(
                Optional.empty(),
                pendingClock::remove,
                pendingLogs::add
        );
        LlmClient pendingClient = pendingStatistics.instrument(
                new SequenceLlmClient(List.of(pending))
        );
        assertSame(pending, pendingClient.complete(input));
        pendingClient.close();
        assertTrue(pendingLogs.isEmpty());
        pending.complete(response("late completion", 10L, 0L, 2L, 12L));
        assertEquals(2, pendingLogs.size());
        assertTrue(pendingLogs.get(0).startsWith(
                "LLM_REQUEST_STATISTICS callSequence=1"
        ));
        assertTrue(pendingLogs.get(1).startsWith(
                "LLM_REQUEST_STATISTICS_SUMMARY"
        ));
        assertThrows(
                IllegalStateException.class,
                () -> pendingClient.complete(input)
        );
        assertEquals(
                TokenUsageStatus.UNAVAILABLE,
                LlmTokenUsage.reported(null, null, null, null).status()
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new LlmTokenUsage(
                        null,
                        1L,
                        null,
                        null,
                        TokenUsageStatus.PARTIAL
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new LlmTokenUsage(
                        1L,
                        null,
                        null,
                        null,
                        TokenUsageStatus.INVALID
                )
        );

        CloseBlockingLlmClient closingDelegate =
                new CloseBlockingLlmClient();
        LlmClient closingClient = new LlmRequestStatistics(
                Optional.empty()
        ).instrument(closingDelegate);
        CompletableFuture<Void> closing =
                CompletableFuture.runAsync(closingClient::close);
        closingDelegate.closeEntered.join();
        try {
            assertThrows(
                    IllegalStateException.class,
                    () -> closingClient.complete(input)
            );
            assertEquals(0, closingDelegate.completeCalls.get());
        } finally {
            closingDelegate.allowClose.complete(null);
            closing.join();
        }
    }

    private static LlmResponse response(
            String content,
            long input,
            long cached,
            long output,
            long total
    ) {
        return new LlmResponse(
                content,
                1L,
                LlmTokenUsage.reported(input, cached, output, total)
        );
    }

    private static final class SequenceLlmClient implements LlmClient {

        private final Queue<CompletableFuture<LlmResponse>> responses;
        private final ProviderDescriptor provider = new ProviderDescriptor(
                "mistral",
                "MISTRAL",
                URI.create("https://api.mistral.ai/v1"),
                "mistral-small-2603"
        );
        private boolean closed;

        private SequenceLlmClient(
                List<CompletableFuture<LlmResponse>> responses
        ) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public CompletableFuture<LlmResponse> complete(
                ModelInput exactModelInput
        ) {
            return responses.remove();
        }

        @Override
        public ProviderDescriptor provider() {
            return provider;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class CloseBlockingLlmClient implements LlmClient {

        private final CompletableFuture<Void> closeEntered =
                new CompletableFuture<>();
        private final CompletableFuture<Void> allowClose =
                new CompletableFuture<>();
        private final AtomicInteger completeCalls = new AtomicInteger();
        private final ProviderDescriptor provider = new ProviderDescriptor(
                "lm-studio",
                "LM_STUDIO",
                URI.create("http://localhost:1234/v1"),
                "test-model"
        );

        @Override
        public CompletableFuture<LlmResponse> complete(
                ModelInput exactModelInput
        ) {
            completeCalls.incrementAndGet();
            return CompletableFuture.completedFuture(
                    new LlmResponse("unexpected", 0L)
            );
        }

        @Override
        public ProviderDescriptor provider() {
            return provider;
        }

        @Override
        public void close() {
            closeEntered.complete(null);
            allowClose.join();
        }
    }
}
