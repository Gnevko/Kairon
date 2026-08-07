package kairon.llm;

import kairon.llm.LlmClient.ModelInput;

import java.util.Objects;

/**
 * Who she is, and what an answer looks like.
 *
 * <p>The user message is the serialized decision request and nothing else: no
 * heading, no prose wrapper, no second rendering. What the provider receives is
 * byte-for-byte what the trace records.</p>
 *
 * <p>The prompt is deliberately three blocks. It used to be six — a role, an
 * objective naming what is worth saying, a field-by-field reading manual, a
 * process-safety section, a grounding section and this output contract — 583
 * words of which 447 said what must not be done.</p>
 *
 * <h2>What that cost, measured</h2>
 * <p>On a landing whose request carried the body's class and an uncollected
 * organism, the six-block prompt returned SILENT eight times out of eight; the
 * two-block prompt used both facts in three answers out of four. Across a
 * measured session under the long prompt, seventeen comments were seventeen
 * captions of the triggering event: not one leaned on the trajectory, the
 * standing context or what the Commander was in the middle of. Adding
 * permission to use them changed nothing, and requiring it manufactured a claim
 * the request could not support.</p>
 *
 * <p>The removal is not free and the cost was measured too. Without the
 * grounding block a bacterium acquires motives; without the process-safety
 * block the next step gets recommended; without the objective's silence list
 * she greets the Commander, which she never did before. The two that carried
 * the reduction are the ones nothing works without: the role, because it is the
 * only thing said about her at all, and the output contract, because a prompt
 * carrying only the role returned unparseable answers nine times out of
 * nine.</p>
 *
 * <p>The third block is preferences, not prohibitions, and it is the one thing
 * here that is asked for rather than forbidden. The greeting the objective's
 * silence list used to suppress turned out to be wanted; the 300-observation
 * replay of 2026-08-06 then showed she no longer offers it unasked — on the
 * session-load turn that names the Commander, with an empty context and nothing
 * else to say, she returned SILENT. So it is stated. A block that asks for
 * behaviour is not the block that was removed, and nothing about what may not be
 * said comes back with it.</p>
 *
 * <p>Its second line is the only place the prompt names a field of the request,
 * and it is deliberate rather than the reading manual creeping back. Measured on
 * the same replay: a request carrying {@code biologicalSignals: 1} beside an ice
 * body's class, atmosphere and three survey flags produced a remark about the
 * atmosphere. Life on a frozen world was the one fact in the document that only
 * that document had, and it went unsaid. Two field names are stated as the key
 * finding of a scan; nothing is said about what to do with them, and the
 * decision to speak or stay silent is untouched.</p>
 *
 * <p>The response asks for a decision and, when it is a comment, the sentence
 * itself — nothing more. There was once a third property naming the events the
 * comment rested on by their local ids; both halves of that contract are gone.
 * The ids are no longer sent, so a citation could not be checked against
 * anything the model was shown, and nothing downstream ever branched on which
 * subset came back. Attribution is Kairon's to make from the batch it built.</p>
 */
public final class DecisionPromptFactory {

    public static final String SYSTEM_PROMPT = """
            <kairon_observer>
            <role>
            You are Kairon, a female in-world shipboard companion to one human
            Commander. Speak in the configured output language. Be calm,
            observant, warm, restrained, and occasionally dryly humorous. Never
            describe yourself as an AI, model, program, or assistant.
            </role>

            <preferences>
            Greet the Commander when a session begins.
            In a scan result, biologicalSignals and geologicalSignals are the
            key finding.
            </preferences>

            <output>
            Return one JSON object, no surrounding text and no extra fields.

            For silence:
            {"decision":"SILENT"}

            For a comment:
            {"decision":"COMMENT","comment":"..."}

            When no worthwhile fully supported comment exists, return SILENT.
            </output>
            </kairon_observer>
            """;

    public ModelInput create(
            String outputLanguage,
            String serializedDecisionRequest
    ) {
        Objects.requireNonNull(outputLanguage, "outputLanguage");
        Objects.requireNonNull(
                serializedDecisionRequest,
                "serializedDecisionRequest"
        );
        if (serializedDecisionRequest.isBlank()) {
            throw new IllegalArgumentException(
                    "the decision request must not be blank"
            );
        }
        return new ModelInput(
                SYSTEM_PROMPT
                        + "\n<output_language>"
                        + outputLanguage
                        + "</output_language>\n",
                serializedDecisionRequest
        );
    }
}
