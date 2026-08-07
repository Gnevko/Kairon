package kairon.probe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kairon.config.KaironConfiguration;
import kairon.config.KaironConfiguration.ResolvedProviderConfiguration;
import kairon.llm.DecisionPromptFactory;
import kairon.llm.LlmClient;
import kairon.llm.LlmClient.ModelInput;
import kairon.llm.ObserverResponseValidator;
import kairon.llm.ObserverResponseValidator.ValidatedObserverResponse;
import kairon.llm.OpenAiCompatibleLlmClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * One chosen request, asked of the real provider, so an answer can be read
 * against the exact input that produced it.
 *
 * <p>Opt-in and never part of a normal run: it spends money and reaches the
 * network. Without {@code kairon.probe.config} it is skipped, not failed.</p>
 *
 * <p>It exists because a three-hundred-record replay answers "what did she say
 * across a session" and nothing else. Asking why she said it means changing one
 * thing and asking again — a different prompt against the same document, or a
 * different document against the same prompt — and a whole replay per question
 * is both slow and noisy, since the batching moves with the provider's latency
 * and no two runs line up.</p>
 *
 * <h2>What is real here</h2>
 * <p>The production system prompt, the production client, the production
 * validator. Only the choice of what to ask is the probe's. A hand-rolled HTTP
 * call would answer questions about the script rather than about Kairon, which
 * is the mistake ADR-0010 exists to prevent.</p>
 *
 * <pre>
 * mvnw.cmd test "-Dtest=LivePromptProbeTest" ^
 *   "-Dkairon.probe.config=config/kairon.json" ^
 *   "-Dkairon.probe.trace=var/run-....jsonl" ^
 *   "-Dkairon.probe.turns=14,19,80" ^
 *   "-Dkairon.probe.prompts=current,var/prompt-candidate.txt" ^
 *   "-Dkairon.probe.repeat=3"
 * </pre>
 *
 * <p>Every combination of prompt, turn and repetition is one call. Three
 * prompts by three turns by three repetitions is twenty-seven, so the matrix is
 * chosen deliberately and never defaulted to "everything".</p>
 */
final class LivePromptProbeTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path OUTPUT = Path.of("target");

    /** The prompt Kairon ships with, named so a matrix can ask for it. */
    private static final String CURRENT = "current";

    @Test
    void oneRequestAtATime() throws Exception {
        Path config = pathProperty("kairon.probe.config");
        Path trace = pathProperty("kairon.probe.trace");
        assumeTrue(
                config != null && trace != null,
                "opt-in: pass -Dkairon.probe.config and -Dkairon.probe.trace"
        );
        assumeTrue(Files.isRegularFile(config), "no config: " + config);
        assumeTrue(Files.isRegularFile(trace), "no trace: " + trace);

        List<Integer> turns = turnNumbers();
        assumeTrue(!turns.isEmpty(), "pass -Dkairon.probe.turns=1,2,3");
        List<String> prompts = List.of(
                System.getProperty("kairon.probe.prompts", CURRENT).split(",")
        );
        int repeat = Integer.getInteger("kairon.probe.repeat", 1);
        long pauseMs = Long.getLong("kairon.probe.pauseMs", 1500L);
        String language = System.getProperty("kairon.probe.language", "ru");

        List<String> documents = documentsOf(trace);
        ResolvedProviderConfiguration provider = overridden(
                KaironConfiguration.load(config).resolveActiveProvider()
        );
        requireFaithfulComposition(language, documents.get(turns.get(0) - 1));
        System.out.println("model=" + provider.model()
                + " temperature=" + provider.temperature());

        List<Probe> results = new ArrayList<>();
        try (LlmClient client = new OpenAiCompatibleLlmClient(provider)) {
            for (String prompt : prompts) {
                String systemMessage = systemMessage(prompt.strip(), language);
                for (int turn : turns) {
                    String document = documents.get(turn - 1);
                    for (int attempt = 1; attempt <= repeat; attempt++) {
                        results.add(ask(
                                client,
                                prompt.strip(),
                                turn,
                                attempt,
                                systemMessage,
                                document
                        ));
                        Thread.sleep(pauseMs);
                    }
                }
            }
        }

        Path report = OUTPUT.resolve("probe-report.txt");
        Files.createDirectories(OUTPUT);
        Files.writeString(report, render(results, documents, turns),
                StandardCharsets.UTF_8);
        System.out.println(render(results, documents, turns));
        System.out.println("written: " + report.toAbsolutePath());
    }

    /**
     * One question and its answer.
     *
     * <p>A failed call is recorded rather than thrown: a rate limit in the
     * middle of a matrix must not throw away the answers already collected.</p>
     */
    private static Probe ask(
            LlmClient client,
            String prompt,
            int turn,
            int attempt,
            String systemMessage,
            String document
    ) {
        ObserverResponseValidator validator = new ObserverResponseValidator();
        try {
            LlmClient.LlmResponse response = client
                    .complete(new ModelInput(systemMessage, document))
                    .toCompletableFuture()
                    .join();
            ValidatedObserverResponse validated =
                    validator.validate(response.content(), List.of());
            return new Probe(
                    prompt,
                    turn,
                    attempt,
                    validated.status().name(),
                    validated.decision() == null
                            ? null
                            : validated.decision().name(),
                    validated.comment(),
                    response.latencyMs(),
                    null
            );
        } catch (CompletionException failure) {
            Throwable cause = failure.getCause() == null
                    ? failure
                    : failure.getCause();
            return new Probe(prompt, turn, attempt, "CALL_FAILED", null, null,
                    0L, cause.getClass().getSimpleName()
                            + ": " + cause.getMessage());
        }
    }

    /**
     * The system message, composed exactly as the runtime composes it.
     *
     * <p>{@link #requireFaithfulComposition} checks that against
     * {@link DecisionPromptFactory} before a single call is made, so a probe
     * cannot quietly measure a prompt the runtime would never send.</p>
     */
    private static String systemMessage(String prompt, String language)
            throws IOException {
        String body = CURRENT.equals(prompt)
                ? DecisionPromptFactory.SYSTEM_PROMPT
                : Files.readString(Path.of(prompt), StandardCharsets.UTF_8);
        return body
                + "\n<output_language>"
                + language
                + "</output_language>\n";
    }

    private static void requireFaithfulComposition(
            String language,
            String document
    ) throws IOException {
        assertEquals(
                new DecisionPromptFactory().create(language, document),
                new ModelInput(systemMessage(CURRENT, language), document),
                "the probe must send what the runtime would send"
        );
    }

    /**
     * The configured provider, with the model or temperature this probe asks
     * for.
     *
     * <p>Which of "the prompt is wrong" and "the model cannot" is true is not
     * answerable without changing one of them, and the account's own
     * configuration is the honest starting point for both. Everything else —
     * base URL, key, timeout, response format — stays exactly as the runtime
     * has it.</p>
     */
    private static ResolvedProviderConfiguration overridden(
            ResolvedProviderConfiguration provider
    ) {
        String model = System.getProperty("kairon.probe.model");
        String temperature = System.getProperty("kairon.probe.temperature");
        if (model == null && temperature == null) {
            return provider;
        }
        return new ResolvedProviderConfiguration(
                provider.profileName(),
                provider.type(),
                provider.baseUrl(),
                model == null ? provider.model() : model.strip(),
                provider.apiKey(),
                temperature == null
                        ? provider.temperature()
                        : Double.parseDouble(temperature.strip()),
                provider.maximumOutputTokens(),
                provider.requestTimeout(),
                provider.responseFormat(),
                provider.pricing()
        );
    }

    /** Every turn's request document, in trace order, index 0 = turn 1. */
    private static List<String> documentsOf(Path trace) throws IOException {
        List<String> documents = new ArrayList<>();
        for (String line : Files.readAllLines(trace, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode row = JSON.readTree(line);
            documents.add(
                    row.path("modelInput").path("userMessage").textValue()
            );
        }
        return List.copyOf(documents);
    }

    private static List<Integer> turnNumbers() {
        String value = System.getProperty("kairon.probe.turns", "");
        List<Integer> turns = new ArrayList<>();
        for (String token : value.split(",")) {
            if (!token.isBlank()) {
                turns.add(Integer.parseInt(token.strip()));
            }
        }
        return List.copyOf(turns);
    }

    private static Path pathProperty(String name) {
        String value = System.getProperty(name);
        return value == null || value.isBlank()
                ? null
                : Path.of(value.strip()).toAbsolutePath().normalize();
    }

    private static String render(
            List<Probe> results,
            List<String> documents,
            List<Integer> turns
    ) {
        StringBuilder out = new StringBuilder("LIVE PROMPT PROBE\n\n");
        for (int turn : turns) {
            out.append("=".repeat(78)).append("\nTURN ").append(turn)
                    .append("\n").append(documents.get(turn - 1)).append("\n");
            for (Probe probe : results) {
                if (probe.turn() == turn) {
                    out.append("  [").append(probe.prompt()).append(" #")
                            .append(probe.attempt()).append("] ")
                            .append(probe.status());
                    if (probe.decision() != null) {
                        out.append(" ").append(probe.decision());
                    }
                    if (probe.comment() != null) {
                        out.append("  «").append(probe.comment()).append("»");
                    }
                    if (probe.failure() != null) {
                        out.append("  ").append(probe.failure());
                    }
                    out.append("\n");
                }
            }
        }
        long spoke = results.stream()
                .filter(probe -> "COMMENT".equals(probe.decision()))
                .count();
        out.append("\ncalls=").append(results.size())
                .append(" commented=").append(spoke)
                .append(" failed=").append(results.stream()
                        .filter(probe -> probe.failure() != null).count())
                .append("\n");
        return out.toString();
    }

    private record Probe(
            String prompt,
            int turn,
            int attempt,
            String status,
            String decision,
            String comment,
            long latencyMs,
            String failure
    ) {
    }
}
