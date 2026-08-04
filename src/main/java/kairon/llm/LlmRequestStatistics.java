package kairon.llm;

import kairon.config.KaironConfiguration.LlmTokenPricing;
import kairon.llm.LlmClient.LlmResponse;
import kairon.llm.LlmClient.LlmTokenUsage;
import kairon.llm.LlmClient.ProviderDescriptor;
import kairon.llm.LlmClient.TokenUsageStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Provider-neutral, process-local accounting for physical LLM calls.
 *
 * <p>Its instrumenting wrapper forwards model input and output unchanged; the
 * accumulator and logger retain only provider descriptors, terminal outcome,
 * timing, token usage, and configured non-secret pricing. They never retain
 * prompt text, response text, API keys, or HTTP authorization metadata. The
 * component logs one terminal measurement per call, maintains an immutable
 * cumulative snapshot, and is deliberately unrelated to
 * {@code ObservationBus}.</p>
 */
public final class LlmRequestStatistics implements AutoCloseable {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(LlmRequestStatistics.class);
    private static final BigDecimal ONE_MILLION =
            BigDecimal.valueOf(1_000_000L);

    private final Optional<LlmTokenPricing> pricing;
    private final LongSupplier nanoTime;
    private final Consumer<String> logSink;
    private final AtomicLong nextCallSequence = new AtomicLong();
    private final AtomicLong inFlightCalls = new AtomicLong();
    private final AtomicBoolean instrumented = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean summaryEmitted = new AtomicBoolean();
    private final Object stateLock = new Object();
    private final Object lifecycleLock = new Object();

    private ProviderDescriptor provider;
    private long completedCalls;
    private long successfulCalls;
    private long failedCalls;
    private long cancelledCalls;
    private long completeUsageCalls;
    private long partialUsageCalls;
    private long unavailableUsageCalls;
    private long invalidUsageCalls;
    private long cacheUsageKnownCalls;
    private long pricedCalls;
    private long inputTokens;
    private long cachedInputTokens;
    private long outputTokens;
    private long totalTokens;
    private long cacheComparableInputTokens;
    private long totalLatencyNanos;
    private long successfulLatencyNanos;
    private long throughputLatencyNanos;
    private long throughputOutputTokens;
    private BigDecimal estimatedCumulativeCost = BigDecimal.ZERO;

    public LlmRequestStatistics(Optional<LlmTokenPricing> pricing) {
        this(pricing, System::nanoTime, message -> LOGGER.info(message));
    }

    LlmRequestStatistics(
            Optional<LlmTokenPricing> pricing,
            LongSupplier nanoTime,
            Consumer<String> logSink
    ) {
        this.pricing = Objects.requireNonNull(pricing, "pricing");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.logSink = Objects.requireNonNull(logSink, "logSink");
    }

    /**
     * Instruments exactly one client without changing the returned completion
     * stage. Cancellation and all success/failure semantics remain owned by
     * the delegate.
     */
    public LlmClient instrument(LlmClient delegate) {
        Objects.requireNonNull(delegate, "delegate");
        synchronized (lifecycleLock) {
            if (closed.get()) {
                throw new IllegalStateException("LLM_STATISTICS_CLOSED");
            }
            if (!instrumented.compareAndSet(false, true)) {
                throw new IllegalStateException(
                        "LLM_STATISTICS_ALREADY_INSTRUMENTED"
                );
            }
            provider = Objects.requireNonNull(
                    delegate.provider(),
                    "delegate.provider()"
            );
        }
        return new InstrumentedLlmClient(delegate);
    }

    public Snapshot snapshot() {
        synchronized (stateLock) {
            return snapshotLocked();
        }
    }

    @Override
    public void close() {
        boolean quiescent;
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            quiescent = inFlightCalls.get() == 0;
        }
        if (quiescent) {
            emitSummaryWhenQuiescent();
        }
    }

    private void emitSummaryWhenQuiescent() {
        if (!closed.get()
                || inFlightCalls.get() != 0
                || !summaryEmitted.compareAndSet(false, true)) {
            return;
        }
        Snapshot snapshot = snapshot();
        if (snapshot.completedCalls() == 0) {
            return;
        }
        ProviderDescriptor currentProvider = provider;
        String prefix = currentProvider == null
                ? ""
                : " profile=" + currentProvider.profileName()
                + " providerType=" + currentProvider.providerType()
                + " model=" + currentProvider.model();
        emit("LLM_REQUEST_STATISTICS_SUMMARY"
                + prefix
                + cumulativeFields(snapshot));
    }

    private void recordTerminal(
            long callSequence,
            long elapsedNanos,
            LlmResponse response,
            Throwable failure
    ) {
        try {
            TerminalMeasurement measurement = terminalMeasurement(
                    elapsedNanos,
                    response,
                    failure
            );
            final Snapshot snapshot;
            synchronized (stateLock) {
                applyLocked(measurement);
                snapshot = snapshotLocked();
            }
            emit(formatCall(callSequence, measurement, snapshot));
        } catch (RuntimeException statisticsFailure) {
            // Metrics must never change an LLM result.
            LOGGER.warn(
                    "LLM_REQUEST_STATISTICS_RECORD_FAILED category={}",
                    statisticsFailure.getClass().getSimpleName()
            );
        }
    }

    private TerminalMeasurement terminalMeasurement(
            long elapsedNanos,
            LlmResponse response,
            Throwable failure
    ) {
        long safeElapsedNanos = Math.max(0L, elapsedNanos);
        if (failure != null) {
            Throwable root = unwrap(failure);
            if (root instanceof CancellationException) {
                return TerminalMeasurement.cancelled(safeElapsedNanos);
            }
            LlmTokenUsage usage =
                    root instanceof OpenAiCompatibleLlmClient.LlmClientException
                            llmFailure
                            ? llmFailure.tokenUsage()
                            : LlmTokenUsage.unavailable();
            return TerminalMeasurement.failed(
                    safeElapsedNanos,
                    failureCategory(root),
                    usage,
                    estimateCost(usage)
            );
        }
        if (response == null) {
            return TerminalMeasurement.failed(
                    safeElapsedNanos,
                    "LLM_RESPONSE_MISSING",
                    LlmTokenUsage.unavailable(),
                    Optional.empty()
            );
        }
        LlmTokenUsage usage = response.tokenUsage();
        Optional<BigDecimal> cost = estimateCost(usage);
        return TerminalMeasurement.success(safeElapsedNanos, usage, cost);
    }

    private void applyLocked(TerminalMeasurement measurement) {
        completedCalls = saturatedIncrement(completedCalls);
        totalLatencyNanos = saturatedAdd(
                totalLatencyNanos,
                measurement.elapsedNanos()
        );
        applyUsageLocked(measurement.usage(), measurement.elapsedNanos());
        measurement.estimatedCost().ifPresent(cost -> {
            pricedCalls = saturatedIncrement(pricedCalls);
            estimatedCumulativeCost = estimatedCumulativeCost.add(cost);
        });

        switch (measurement.outcome()) {
            case SUCCESS -> {
                successfulCalls = saturatedIncrement(successfulCalls);
                successfulLatencyNanos = saturatedAdd(
                        successfulLatencyNanos,
                        measurement.elapsedNanos()
                );
            }
            case FAILURE -> failedCalls = saturatedIncrement(failedCalls);
            case CANCELLED -> cancelledCalls = saturatedIncrement(cancelledCalls);
        }
    }

    private void applyUsageLocked(LlmTokenUsage usage, long elapsedNanos) {
        switch (usage.status()) {
            case COMPLETE -> completeUsageCalls =
                    saturatedIncrement(completeUsageCalls);
            case PARTIAL -> partialUsageCalls =
                    saturatedIncrement(partialUsageCalls);
            case UNAVAILABLE -> unavailableUsageCalls =
                    saturatedIncrement(unavailableUsageCalls);
            case INVALID -> invalidUsageCalls =
                    saturatedIncrement(invalidUsageCalls);
        }

        inputTokens = addIfReported(inputTokens, usage.inputTokens());
        cachedInputTokens = addIfReported(
                cachedInputTokens,
                usage.cachedInputTokens()
        );
        outputTokens = addIfReported(outputTokens, usage.outputTokens());
        totalTokens = addIfReported(totalTokens, usage.totalTokens());

        if (usage.inputTokens() != null && usage.cachedInputTokens() != null) {
            cacheUsageKnownCalls = saturatedIncrement(cacheUsageKnownCalls);
            cacheComparableInputTokens = saturatedAdd(
                    cacheComparableInputTokens,
                    usage.inputTokens()
            );
        }
        if (usage.outputTokens() != null && elapsedNanos > 0) {
            throughputOutputTokens = saturatedAdd(
                    throughputOutputTokens,
                    usage.outputTokens()
            );
            throughputLatencyNanos = saturatedAdd(
                    throughputLatencyNanos,
                    elapsedNanos
            );
        }
    }

    private Snapshot snapshotLocked() {
        Optional<BigDecimal> cumulativeCost = pricedCalls == 0
                ? Optional.empty()
                : Optional.of(estimatedCumulativeCost);
        Optional<BigDecimal> averageCost = pricedCalls == 0
                ? Optional.empty()
                : Optional.of(estimatedCumulativeCost.divide(
                        BigDecimal.valueOf(pricedCalls),
                        12,
                        RoundingMode.HALF_UP
                ));
        return new Snapshot(
                completedCalls,
                successfulCalls,
                failedCalls,
                cancelledCalls,
                completeUsageCalls,
                partialUsageCalls,
                unavailableUsageCalls,
                invalidUsageCalls,
                cacheUsageKnownCalls,
                pricedCalls,
                inputTokens,
                cachedInputTokens,
                outputTokens,
                totalTokens,
                averageMilliseconds(totalLatencyNanos, completedCalls),
                averageMilliseconds(
                        successfulLatencyNanos,
                        successfulCalls
                ),
                tokensPerSecond(
                        throughputOutputTokens,
                        throughputLatencyNanos
                ),
                cachePercent(
                        cachedInputTokens,
                        cacheComparableInputTokens
                ),
                cumulativeCost,
                averageCost
        );
    }

    private String formatCall(
            long callSequence,
            TerminalMeasurement measurement,
            Snapshot snapshot
    ) {
        LlmTokenUsage usage = measurement.usage();
        Long uncached = usage.uncachedInputTokens();
        OptionalDouble throughput = usage.outputTokens() != null
                ? tokensPerSecond(
                        usage.outputTokens(),
                        measurement.elapsedNanos()
                )
                : OptionalDouble.empty();
        OptionalDouble currentCachePercent = usage.inputTokens() != null
                && usage.cachedInputTokens() != null
                ? cachePercent(
                        usage.cachedInputTokens(),
                        usage.inputTokens()
                )
                : OptionalDouble.empty();
        ProviderDescriptor currentProvider = provider;

        return "LLM_REQUEST_STATISTICS"
                + " callSequence=" + callSequence
                + " profile=" + currentProvider.profileName()
                + " providerType=" + currentProvider.providerType()
                + " model=" + currentProvider.model()
                + " outcome=" + measurement.outcome()
                + " failureCategory=" + measurement.failureCategory()
                + " usageStatus=" + usage.status()
                + " inputTokens=" + reported(usage.inputTokens())
                + " cachedInputTokens=" + reported(usage.cachedInputTokens())
                + " uncachedInputTokens=" + reported(uncached)
                + " outputTokens=" + reported(usage.outputTokens())
                + " totalTokens=" + reported(usage.totalTokens())
                + " cacheHitPercent=" + decimal(currentCachePercent)
                + " latencyMs=" + milliseconds(measurement.elapsedNanos())
                + " endToEndOutputTokensPerSecond=" + decimal(throughput)
                + " estimatedCost="
                + money(measurement.estimatedCost())
                + cumulativeFields(snapshot);
    }

    private String cumulativeFields(Snapshot snapshot) {
        return " completedCalls=" + snapshot.completedCalls()
                + " successfulCalls=" + snapshot.successfulCalls()
                + " failedCalls=" + snapshot.failedCalls()
                + " cancelledCalls=" + snapshot.cancelledCalls()
                + " completeUsageCalls=" + snapshot.completeUsageCalls()
                + " partialUsageCalls=" + snapshot.partialUsageCalls()
                + " unavailableUsageCalls=" + snapshot.unavailableUsageCalls()
                + " invalidUsageCalls=" + snapshot.invalidUsageCalls()
                + " cacheUsageKnownCalls=" + snapshot.cacheUsageKnownCalls()
                + " pricedCalls=" + snapshot.pricedCalls()
                + " cumulativeInputTokens=" + snapshot.inputTokens()
                + " cumulativeCachedInputTokens="
                + snapshot.cachedInputTokens()
                + " cumulativeOutputTokens=" + snapshot.outputTokens()
                + " cumulativeTotalTokens=" + snapshot.totalTokens()
                + " cumulativeCacheHitPercent="
                + decimal(snapshot.cacheHitPercent())
                + " averageLatencyMs="
                + decimal(snapshot.averageLatencyMs())
                + " averageSuccessfulLatencyMs="
                + decimal(snapshot.averageSuccessfulLatencyMs())
                + " averageEndToEndOutputTokensPerSecond="
                + decimal(
                        snapshot.averageEndToEndOutputTokensPerSecond()
                )
                + " estimatedCumulativeCost="
                + money(snapshot.estimatedCumulativeCost())
                + " averageEstimatedCost="
                + money(snapshot.averageEstimatedCost())
                + pricingFields();
    }

    private Optional<BigDecimal> estimateCost(LlmTokenUsage usage) {
        if (pricing.isEmpty()
                || usage.inputTokens() == null
                || usage.cachedInputTokens() == null
                || usage.outputTokens() == null) {
            return Optional.empty();
        }

        LlmTokenPricing tariff = pricing.orElseThrow();
        long uncachedInput = usage.inputTokens() - usage.cachedInputTokens();
        BigDecimal uncachedCost = tariff.inputPerMillionTokens()
                .multiply(BigDecimal.valueOf(uncachedInput));
        BigDecimal cachedCost = tariff.cachedInputPerMillionTokens()
                .multiply(BigDecimal.valueOf(usage.cachedInputTokens()));
        BigDecimal outputCost = tariff.outputPerMillionTokens()
                .multiply(BigDecimal.valueOf(usage.outputTokens()));
        return Optional.of(
                uncachedCost.add(cachedCost).add(outputCost)
                        .divide(ONE_MILLION)
        );
    }

    private String pricingFields() {
        if (pricing.isEmpty()) {
            return " currency=unavailable"
                    + " inputRatePerMillionTokens=unavailable"
                    + " cachedInputRatePerMillionTokens=unavailable"
                    + " outputRatePerMillionTokens=unavailable";
        }
        LlmTokenPricing tariff = pricing.orElseThrow();
        return " currency=" + tariff.currency()
                + " inputRatePerMillionTokens="
                + money(tariff.inputPerMillionTokens())
                + " cachedInputRatePerMillionTokens="
                + money(tariff.cachedInputPerMillionTokens())
                + " outputRatePerMillionTokens="
                + money(tariff.outputPerMillionTokens());
    }

    private void emit(String line) {
        try {
            logSink.accept(line);
        } catch (RuntimeException loggingFailure) {
            LOGGER.warn(
                    "LLM_REQUEST_STATISTICS_LOG_FAILED category={}",
                    loggingFailure.getClass().getSimpleName()
            );
        }
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = Objects.requireNonNull(failure, "failure");
        while ((current instanceof CompletionException
                || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String failureCategory(Throwable failure) {
        if (failure instanceof OpenAiCompatibleLlmClient.LlmClientException
                && failure.getMessage() != null
                && !failure.getMessage().isBlank()) {
            return failure.getMessage();
        }
        return "UNCLASSIFIED";
    }

    private static long elapsed(long startedAt, long completedAt) {
        long result = completedAt - startedAt;
        return result < 0 ? 0 : result;
    }

    private static long saturatedIncrement(long value) {
        return value == Long.MAX_VALUE ? value : value + 1;
    }

    private static long saturatedAdd(long left, long right) {
        if (right <= 0) {
            return left;
        }
        return left > Long.MAX_VALUE - right
                ? Long.MAX_VALUE
                : left + right;
    }

    private static long addIfReported(long total, Long value) {
        return value == null ? total : saturatedAdd(total, value);
    }

    private static OptionalDouble averageMilliseconds(long nanos, long count) {
        return count == 0
                ? OptionalDouble.empty()
                : OptionalDouble.of((nanos / 1_000_000.0) / count);
    }

    private static OptionalDouble tokensPerSecond(long tokens, long nanos) {
        if (nanos <= 0) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(tokens * 1_000_000_000.0 / nanos);
    }

    private static OptionalDouble cachePercent(long cached, long input) {
        if (input == 0) {
            return OptionalDouble.of(0.0);
        }
        return OptionalDouble.of(cached * 100.0 / input);
    }

    private static String reported(Long value) {
        return value == null ? "unavailable" : value.toString();
    }

    private static String milliseconds(long nanos) {
        return decimal(nanos / 1_000_000.0);
    }

    private static String decimal(OptionalDouble value) {
        return value.isPresent() ? decimal(value.getAsDouble()) : "unavailable";
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String money(Optional<BigDecimal> value) {
        return value.map(LlmRequestStatistics::money)
                .orElse("unavailable");
    }

    private static String money(BigDecimal value) {
        return value.stripTrailingZeros()
                .toPlainString();
    }

    public record Snapshot(
            long completedCalls,
            long successfulCalls,
            long failedCalls,
            long cancelledCalls,
            long completeUsageCalls,
            long partialUsageCalls,
            long unavailableUsageCalls,
            long invalidUsageCalls,
            long cacheUsageKnownCalls,
            long pricedCalls,
            long inputTokens,
            long cachedInputTokens,
            long outputTokens,
            long totalTokens,
            OptionalDouble averageLatencyMs,
            OptionalDouble averageSuccessfulLatencyMs,
            OptionalDouble averageEndToEndOutputTokensPerSecond,
            OptionalDouble cacheHitPercent,
            Optional<BigDecimal> estimatedCumulativeCost,
            Optional<BigDecimal> averageEstimatedCost
    ) {

        public Snapshot {
            Objects.requireNonNull(averageLatencyMs, "averageLatencyMs");
            Objects.requireNonNull(
                    averageSuccessfulLatencyMs,
                    "averageSuccessfulLatencyMs"
            );
            Objects.requireNonNull(
                    averageEndToEndOutputTokensPerSecond,
                    "averageEndToEndOutputTokensPerSecond"
            );
            Objects.requireNonNull(cacheHitPercent, "cacheHitPercent");
            Objects.requireNonNull(
                    estimatedCumulativeCost,
                    "estimatedCumulativeCost"
            );
            Objects.requireNonNull(
                    averageEstimatedCost,
                    "averageEstimatedCost"
            );
        }
    }

    private enum Outcome {
        SUCCESS,
        FAILURE,
        CANCELLED
    }

    private record TerminalMeasurement(
            Outcome outcome,
            String failureCategory,
            long elapsedNanos,
            LlmTokenUsage usage,
            Optional<BigDecimal> estimatedCost
    ) {

        private TerminalMeasurement {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(failureCategory, "failureCategory");
            Objects.requireNonNull(usage, "usage");
            Objects.requireNonNull(estimatedCost, "estimatedCost");
        }

        private static TerminalMeasurement success(
                long elapsedNanos,
                LlmTokenUsage usage,
                Optional<BigDecimal> estimatedCost
        ) {
            return new TerminalMeasurement(
                    Outcome.SUCCESS,
                    "none",
                    elapsedNanos,
                    usage,
                    estimatedCost
            );
        }

        private static TerminalMeasurement failed(
                long elapsedNanos,
                String category,
                LlmTokenUsage usage,
                Optional<BigDecimal> estimatedCost
        ) {
            return new TerminalMeasurement(
                    Outcome.FAILURE,
                    category,
                    elapsedNanos,
                    usage,
                    estimatedCost
            );
        }

        private static TerminalMeasurement cancelled(long elapsedNanos) {
            return new TerminalMeasurement(
                    Outcome.CANCELLED,
                    "LLM_REQUEST_CANCELLED",
                    elapsedNanos,
                    LlmTokenUsage.unavailable(),
                    Optional.empty()
            );
        }
    }

    private final class InstrumentedLlmClient implements LlmClient {

        private final LlmClient delegate;
        private final AtomicBoolean delegateClosed = new AtomicBoolean();

        private InstrumentedLlmClient(LlmClient delegate) {
            this.delegate = delegate;
        }

        @Override
        public CompletionStage<LlmResponse> complete(ModelInput exactModelInput) {
            beginCall();
            long callSequence = nextCallSequence.incrementAndGet();
            long startedAt = nanoTime.getAsLong();
            final CompletionStage<LlmResponse> stage;
            try {
                stage = delegate.complete(exactModelInput);
            } catch (RuntimeException failure) {
                try {
                    recordTerminal(
                            callSequence,
                            elapsed(startedAt, nanoTime.getAsLong()),
                            null,
                            failure
                    );
                } finally {
                    callFinished();
                }
                throw failure;
            }

            if (stage == null) {
                try {
                    recordTerminal(
                            callSequence,
                            elapsed(startedAt, nanoTime.getAsLong()),
                            null,
                            null
                    );
                } finally {
                    callFinished();
                }
                return null;
            }

            try {
                stage.whenComplete((response, failure) -> {
                    try {
                        recordTerminal(
                                callSequence,
                                elapsed(startedAt, nanoTime.getAsLong()),
                                response,
                                failure
                        );
                    } finally {
                        callFinished();
                    }
                });
            } catch (RuntimeException registrationFailure) {
                try {
                    recordTerminal(
                            callSequence,
                            elapsed(startedAt, nanoTime.getAsLong()),
                            null,
                            registrationFailure
                    );
                } finally {
                    callFinished();
                }
                throw registrationFailure;
            }
            return stage;
        }

        private void callFinished() {
            boolean quiescent;
            synchronized (lifecycleLock) {
                long remaining = inFlightCalls.decrementAndGet();
                if (remaining < 0) {
                    inFlightCalls.set(0);
                    LOGGER.warn("LLM_REQUEST_STATISTICS_IN_FLIGHT_UNDERFLOW");
                    remaining = 0;
                }
                quiescent = closed.get() && remaining == 0;
            }
            if (quiescent) {
                emitSummaryWhenQuiescent();
            }
        }

        private void beginCall() {
            synchronized (lifecycleLock) {
                if (closed.get()) {
                    throw new IllegalStateException("LLM_STATISTICS_CLOSED");
                }
                inFlightCalls.incrementAndGet();
            }
        }

        @Override
        public ProviderDescriptor provider() {
            return delegate.provider();
        }

        @Override
        public void close() {
            if (!delegateClosed.compareAndSet(false, true)) {
                return;
            }
            // Close statistics first so no new call can cross into a delegate
            // whose transport is already closing. Accepted calls still emit
            // their terminal measurement and the deferred summary.
            LlmRequestStatistics.this.close();
            delegate.close();
        }
    }
}
