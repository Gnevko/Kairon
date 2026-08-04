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
     * Bumped from {@code v4} at the decision-contract cutover.
     *
     * <p>Two shape changes, either of which would break a {@code v4} reader.
     * {@code localEvidence} is new and required, because the provider input now
     * identifies events by ids that are local to one request and a reader
     * cannot interpret a citation without the mapping. And
     * {@code validatedDecision} gained {@code evidence}, the ids the model
     * actually returned, beside the bus sequences they resolved to.</p>
     */
    public static final String TRACE_SCHEMA_VERSION =
            "kairon-turn-trace-v5";

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
     */
    public record TurnTrace(
            String traceSchemaVersion,
            String contextSchema,
            String turnOutcome,
            List<Long> triggerBusSequences,
            List<LocalEvidenceTrace> localEvidence,
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
            localEvidence = List.copyOf(Objects.requireNonNull(
                    localEvidence,
                    "localEvidence"
            ));
            if (localEvidence.isEmpty() != (situationTurn == null)) {
                throw new IllegalArgumentException(
                        "a request and its local evidence appear together"
                );
            }
            if (!localEvidence.isEmpty()
                    && !localEvidence.stream()
                    .map(LocalEvidenceTrace::busSequence)
                    .toList()
                    .equals(triggerBusSequences)) {
                throw new IllegalArgumentException(
                        "local evidence must map ids 1..n onto the triggers"
                );
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

    /**
     * One local event id and the observation it stood for.
     *
     * <p>The model never sees a bus sequence and Kairon never keys anything on
     * a local id. This is the only record of the translation, and it is written
     * for every turn that reached the provider.</p>
     */
    public record LocalEvidenceTrace(int localId, long busSequence) {

        public LocalEvidenceTrace {
            if (localId < 1 || busSequence < 1) {
                throw new IllegalArgumentException(
                        "local evidence identities must be positive"
                );
            }
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
