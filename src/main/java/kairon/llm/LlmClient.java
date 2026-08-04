package kairon.llm;

import java.net.URI;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

public interface LlmClient extends AutoCloseable {

    CompletionStage<LlmResponse> complete(ModelInput exactModelInput);

    ProviderDescriptor provider();

    @Override
    default void close() {
    }

    record ModelInput(String systemMessage, String userMessage) {

        public ModelInput {
            requireNonBlank(systemMessage, "systemMessage");
            requireNonBlank(userMessage, "userMessage");
        }
    }

    record LlmResponse(
            String content,
            long latencyMs,
            LlmTokenUsage tokenUsage
    ) {

        public LlmResponse {
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(tokenUsage, "tokenUsage");
            if (latencyMs < 0) {
                throw new IllegalArgumentException("latencyMs must be non-negative");
            }
        }

        /**
         * Convenience constructor for fakes and providers that cannot report
         * token usage.
         */
        public LlmResponse(String content, long latencyMs) {
            this(content, latencyMs, LlmTokenUsage.unavailable());
        }
    }

    /**
     * Provider-neutral token accounting. A {@code null} token count means
     * that the provider did not report that particular value.
     */
    record LlmTokenUsage(
            Long inputTokens,
            Long cachedInputTokens,
            Long outputTokens,
            Long totalTokens,
            TokenUsageStatus status
    ) {

        public LlmTokenUsage {
            Objects.requireNonNull(status, "status");
            requireNonNegative(inputTokens, "inputTokens");
            requireNonNegative(cachedInputTokens, "cachedInputTokens");
            requireNonNegative(outputTokens, "outputTokens");
            requireNonNegative(totalTokens, "totalTokens");
            if (cachedInputTokens != null && inputTokens == null) {
                throw new IllegalArgumentException(
                        "cachedInputTokens require inputTokens"
                );
            }
            if (cachedInputTokens != null && cachedInputTokens > inputTokens) {
                throw new IllegalArgumentException(
                        "cachedInputTokens must not exceed inputTokens"
                );
            }
            if (inputTokens != null
                    && outputTokens != null
                    && totalTokens != null) {
                long minimumTotal;
                try {
                    minimumTotal = Math.addExact(inputTokens, outputTokens);
                } catch (ArithmeticException overflow) {
                    throw new IllegalArgumentException(
                            "inputTokens plus outputTokens overflow",
                            overflow
                    );
                }
                if (totalTokens < minimumTotal) {
                    throw new IllegalArgumentException(
                            "totalTokens must include inputTokens and outputTokens"
                    );
                }
            }

            int reportedCount = (inputTokens == null ? 0 : 1)
                    + (cachedInputTokens == null ? 0 : 1)
                    + (outputTokens == null ? 0 : 1)
                    + (totalTokens == null ? 0 : 1);
            switch (status) {
                case COMPLETE -> {
                    if (reportedCount != 4) {
                        throw new IllegalArgumentException(
                                "COMPLETE token usage requires all counts"
                        );
                    }
                }
                case PARTIAL -> {
                    if (reportedCount == 0 || reportedCount == 4) {
                        throw new IllegalArgumentException(
                                "PARTIAL token usage requires some counts"
                        );
                    }
                }
                case UNAVAILABLE, INVALID -> {
                    if (reportedCount != 0) {
                        throw new IllegalArgumentException(
                                status + " token usage cannot contain counts"
                        );
                    }
                }
            }
        }

        public static LlmTokenUsage unavailable() {
            return new LlmTokenUsage(
                    null,
                    null,
                    null,
                    null,
                    TokenUsageStatus.UNAVAILABLE
            );
        }

        public static LlmTokenUsage invalid() {
            return new LlmTokenUsage(
                    null,
                    null,
                    null,
                    null,
                    TokenUsageStatus.INVALID
            );
        }

        public static LlmTokenUsage reported(
                Long inputTokens,
                Long cachedInputTokens,
                Long outputTokens,
                Long totalTokens
        ) {
            if (inputTokens == null
                    && cachedInputTokens == null
                    && outputTokens == null
                    && totalTokens == null) {
                return unavailable();
            }
            TokenUsageStatus status =
                    inputTokens != null
                            && cachedInputTokens != null
                            && outputTokens != null
                            && totalTokens != null
                            ? TokenUsageStatus.COMPLETE
                            : TokenUsageStatus.PARTIAL;
            return new LlmTokenUsage(
                    inputTokens,
                    cachedInputTokens,
                    outputTokens,
                    totalTokens,
                    status
            );
        }

        public Long uncachedInputTokens() {
            return inputTokens == null || cachedInputTokens == null
                    ? null
                    : inputTokens - cachedInputTokens;
        }
    }

    enum TokenUsageStatus {
        COMPLETE,
        PARTIAL,
        UNAVAILABLE,
        INVALID
    }

    record ProviderDescriptor(
            String profileName,
            String providerType,
            URI baseUrl,
            String model
    ) {

        public ProviderDescriptor {
            requireNonBlank(profileName, "profileName");
            requireNonBlank(providerType, "providerType");
            Objects.requireNonNull(baseUrl, "baseUrl");
            requireNonBlank(model, "model");
        }
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be nonblank");
        }
    }

    private static void requireNonNegative(Long value, String name) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }
}
