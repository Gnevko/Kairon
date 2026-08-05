package kairon.trace;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import kairon.llm.LlmClient;
import kairon.llm.LlmClient.ModelInput;
import kairon.llm.ObserverResponseValidator;
import kairon.turn.overflow.ContextOverflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;

/**
 * Best-effort JSONL trace of the exact snapshot context sent to the model.
 */
public final class JsonLinesTurnTraceWriter implements AutoCloseable {

    /**
     * Bumped from {@code v5} when event ids stopped being sent.
     *
     * <p>Everything the word "evidence" named is gone, because none of it was
     * evidence any more. {@code validatedDecision} no longer carries the ids the
     * model returned, nor the bus sequences they resolved to: the response
     * contract has no citation, so {@code validatedDecision} now describes the
     * answer and only the answer.</p>
     *
     * <p>{@code localEvidence} is gone too, and nothing replaced it. It mapped
     * event position {@code 1..n} onto a trigger bus sequence, and
     * {@code triggerBusSequences} is that same list in that same order — the
     * {@code v5} record even asserted the two were equal. One list is the
     * mapping; a second copy of it under a name that no longer meant anything
     * was a field nothing read.</p>
     *
     * <p>So a reader still answers "which observation was the request's third
     * event?" the way it always could: {@code triggerBusSequences[2]}.</p>
     */
    public static final String TRACE_SCHEMA_VERSION =
            "kairon-turn-trace-v6";

    private static final Logger LOGGER = LoggerFactory.getLogger(
            JsonLinesTurnTraceWriter.class
    );

    private final Path traceFile;
    private final ObjectMapper mapper;

    public JsonLinesTurnTraceWriter(Path traceFile) {
        this.traceFile = Objects.requireNonNull(
                traceFile,
                "traceFile"
        ).toAbsolutePath().normalize();
        this.mapper = JsonMapper.builder()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .build();
    }

    /**
     * Best-effort startup writability probe. An empty file is not a turn
     * record; failure is reported and later turns still retry append.
     */
    public synchronized boolean probe() {
        try {
            prepareParentDirectory();
            Files.writeString(
                    traceFile,
                    "",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND
            );
            return true;
        } catch (IOException | RuntimeException failure) {
            reportFailure(failure);
            return false;
        }
    }

    public synchronized boolean append(TurnTrace trace) {
        Objects.requireNonNull(trace, "trace");
        try {
            prepareParentDirectory();
            String line = mapper.writeValueAsString(trace) + "\n";
            Files.writeString(
                    traceFile,
                    line,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND
            );
            return true;
        } catch (IOException | RuntimeException failure) {
            reportFailure(failure);
            return false;
        }
    }

    private void prepareParentDirectory() throws IOException {
        Path parent = traceFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private void reportFailure(Throwable failure) {
        String message = "TURN_TRACE_WRITE_FAILED path=" + traceFile
                + " cause=" + failure.getClass().getSimpleName();
        LOGGER.error(message);
        System.err.println(message);
    }

    public Path traceFile() {
        return traceFile;
    }

    @Override
    public void close() {
    }

    public record ProviderTrace(
            String profileName,
            String type,
            String baseUrl,
            String model
    ) {

        public static ProviderTrace from(
                LlmClient.ProviderDescriptor descriptor
        ) {
            Objects.requireNonNull(descriptor, "descriptor");
            return new ProviderTrace(
                    descriptor.profileName(),
                    descriptor.providerType(),
                    descriptor.baseUrl().toASCIIString(),
                    descriptor.model()
            );
        }
    }

    /**
     * One completed turn.
     *
     * <p>{@code situationTurn} and {@code modelInput} are null exactly when the
     * turn produced no model request — today only {@code CONTEXT_TOO_LARGE}.
     * The invocation flags are recorded rather than inferred, so a reader never
     * has to deduce from an absent response whether the provider was
     * called.</p>
     *
     * <p>{@code triggerBusSequences} is the turn's batch in bus order, and it
     * is also the request's internal event numbering: the nth event was
     * projected from the nth entry. It is what a delivered comment is
     * attributed to, and it is written for every turn, including one that never
     * reached the provider.</p>
     */
    public record TurnTrace(
            String traceSchemaVersion,
            String contextSchema,
            String turnOutcome,
            List<Long> triggerBusSequences,
            String situationTurn,
            int situationCharacterCount,
            ContextOverflowTrace contextOverflow,
            boolean providerInvoked,
            boolean commentDelivered,
            boolean speechInvoked,
            ProviderTrace provider,
            ModelInput modelInput,
            String rawModelResponse,
            ObserverResponseValidator.ValidatedObserverResponse
                    validatedDecision,
            LlmClient.LlmTokenUsage tokenUsage,
            long latencyMs,
            String consoleOutcome,
            boolean speechEnabled,
            String speechProvider,
            String speechVoiceName,
            String speechSynthesisStartedAt,
            String speechSynthesisCompletedAt,
            String speechPlaybackStartedAt,
            String speechPlaybackCompletedAt,
            String speechOutcome,
            String speechFailureCategory,
            String deliveredComment
    ) {

        public TurnTrace {
            traceSchemaVersion = requireNonBlank(
                    traceSchemaVersion,
                    "traceSchemaVersion"
            );
            contextSchema = requireNonBlank(
                    contextSchema,
                    "contextSchema"
            );
            turnOutcome = requireNonBlank(turnOutcome, "turnOutcome");
            triggerBusSequences = List.copyOf(Objects.requireNonNull(
                    triggerBusSequences,
                    "triggerBusSequences"
            ));
            if (triggerBusSequences.isEmpty()
                    || triggerBusSequences.stream()
                    .anyMatch(triggerBusSequence ->
                            triggerBusSequence == null
                                    || triggerBusSequence < 1)) {
                throw new IllegalArgumentException(
                        "triggerBusSequences must be positive and nonempty"
                );
            }
            long previousTrigger = 0L;
            for (Long triggerBusSequence : triggerBusSequences) {
                if (triggerBusSequence <= previousTrigger) {
                    throw new IllegalArgumentException(
                            "triggerBusSequences must be unique and ascending"
                    );
                }
                previousTrigger = triggerBusSequence;
            }
            if (situationTurn != null && situationTurn.isBlank()) {
                throw new IllegalArgumentException(
                        "situationTurn must not be blank when present"
                );
            }
            if ((situationTurn == null) != (modelInput == null)) {
                throw new IllegalArgumentException(
                        "a context and a model input appear together"
                );
            }
            if (situationTurn == null && providerInvoked) {
                throw new IllegalArgumentException(
                        "the provider cannot be invoked without a context"
                );
            }
            if (situationCharacterCount
                    != (situationTurn == null ? 0 : situationTurn.length())) {
                throw new IllegalArgumentException(
                        "situationCharacterCount must match the context"
                );
            }
            if ((contextOverflow != null) != (situationTurn == null)) {
                throw new IllegalArgumentException(
                        "overflow detail appears exactly when no context does"
                );
            }
            if (commentDelivered && !providerInvoked) {
                throw new IllegalArgumentException(
                        "a comment requires a provider call"
                );
            }
            if (speechInvoked && !commentDelivered) {
                throw new IllegalArgumentException(
                        "speech requires a delivered comment"
                );
            }
            provider = Objects.requireNonNull(provider, "provider");
            validatedDecision = Objects.requireNonNull(
                    validatedDecision,
                    "validatedDecision"
            );
            tokenUsage = Objects.requireNonNull(
                    tokenUsage,
                    "tokenUsage"
            );
            consoleOutcome = requireNonBlank(
                    consoleOutcome,
                    "consoleOutcome"
            );
            speechOutcome = requireNonBlank(
                    speechOutcome,
                    "speechOutcome"
            );
            speechFailureCategory = requireNonBlank(
                    speechFailureCategory,
                    "speechFailureCategory"
            );
            if (latencyMs < 0) {
                throw new IllegalArgumentException(
                        "latencyMs must be non-negative"
                );
            }
        }

        private static String requireNonBlank(
                String value,
                String name
        ) {
            Objects.requireNonNull(value, name);
            if (value.isBlank()) {
                throw new IllegalArgumentException(
                        name + " must be nonblank"
                );
            }
            return value;
        }
    }


    /** Why no model request was made, and by how much. */
    public record ContextOverflowTrace(
            long turnSequence,
            long firstTriggerBusSequence,
            long finalTriggerBusSequence,
            int mandatoryCharacterCount,
            int configuredCharacterBudget,
            int originalCharacterCount,
            int overshootCharacters,
            List<SectionWeightTrace> largestMandatorySections
    ) {

        public ContextOverflowTrace {
            largestMandatorySections = List.copyOf(Objects.requireNonNull(
                    largestMandatorySections,
                    "largestMandatorySections"
            ));
        }

        public static ContextOverflowTrace from(
                ContextOverflow overflow
        ) {
            Objects.requireNonNull(overflow, "overflow");
            return new ContextOverflowTrace(
                    overflow.turnSequence(),
                    overflow.firstTriggerBusSequence(),
                    overflow.finalTriggerBusSequence(),
                    overflow.mandatoryCharacterCount(),
                    overflow.configuredCharacterBudget(),
                    overflow.originalCharacterCount(),
                    overflow.overshootCharacters(),
                    overflow.largestMandatorySections().stream()
                            .map(section -> new SectionWeightTrace(
                                    section.section(),
                                    section.characterCount()
                            ))
                            .toList()
            );
        }
    }

    public record SectionWeightTrace(String section, int characterCount) {
    }
}
