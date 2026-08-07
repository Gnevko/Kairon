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
 * <h2>Where the line is drawn</h2>
 * <p>Two different things get called invention and only one of them is a
 * defect. <strong>A fabricated quantity is a false instrument reading</strong>
 * — {@code 0.12g}, then {@code 0.06g} an hour later for the same body, "18
 * specimens", "2 minutes" — and it is read as data because it is shaped like
 * data. That is measured and fixed, and naming the field is what fixed it.
 * <strong>A speculation about the world is her voice</strong> — a breeze that
 * might knock you over on a body whose atmosphere is thin, a bacterium with a
 * cobalt metabolism — and it is audibly a thought rather than a readout. It is
 * left alone.</p>
 *
 * <p>The distinction is a decision about what Kairon is, taken on 2026-08-07
 * and recorded so it is not relitigated. Clamping the second is what the six
 * blocks did: under them a landing carrying a body class and an uncollected
 * organism returned SILENT eight times out of eight, and seventeen consecutive
 * comments were seventeen captions of their triggering event. A companion who
 * may only restate the document is a journal being read aloud, and the whole
 * point of the model is that it is not one.</p>
 *
 * <p>Both of the last two lines name their field, and that is the correction of
 * 2026-08-07. It was bought expensively: stated as bare importance — "gravity
 * matters", "what is left is the point" — they produced nine invented
 * quantities in one evening, including 0.12g and 0.06g for the same body an
 * hour apart, "12% of the biology unexplored", "18 specimens" and "the
 * collection will finish in 2 minutes". Every one appeared in a turn where the
 * named field was not in the document at all. A line that says a fact is
 * important without saying where the fact lives asks the model to supply
 * it.</p>
 *
 * <p>The third line asks for the one fact an approach is actually for. The
 * document already carries it — {@code context.body.gravity}, banded {@code
 * LOW}, {@code NORMAL} or {@code HIGH} and sent only where the ship can put
 * down — and on the live approach of 2026-08-07 the answer was still SILENT
 * with the band right there in the request. Weight decides whether the descent
 * and the touchdown are routine or wreck the ship, and it is the Commander's
 * question at exactly the moment the ship enters a body's orbital-cruise
 * zone. It is <em>not</em> a question about walking around: on foot the pull
 * changes nothing that matters, which is why {@code PRESENCE} asks only which
 * body it is and is right to. Like the second line this one names the fact and
 * not what to say about it, and speaking or staying silent remains the
 * model's.</p>
 *
 * <p>It names the field and stops there. It briefly also said that an absent
 * field meant nothing had measured it, which was meant to buy silence and
 * bought an announcement instead: on a disembark, where the body group carries
 * a name and nothing else, the answer was "gravity has not been measured on
 * this planet" — a report about Kairon's own bookkeeping, and false as the
 * Commander hears it, since the scan that measured it was his. Absence is how
 * this whole contract says "unknown or not relevant" and it is never
 * declared.</p>
 *
 * <p>The fourth line is the strongest claim of the three, and it was bought
 * with the clearest failure. The biology inventory reaches exactly one turn —
 * the analysis that finishes a sample — and it exists to answer one question,
 * <em>what is left to collect here</em>. In the live session of 2026-08-07 it
 * arrived on that turn with one genus collected and four not, and the answer
 * was "a pleasant find for the collection": the question went unanswered with
 * its answer in the request. So this line does say what the turn is for, which
 * the other two deliberately do not. It is the one section of the document
 * whose whole reason for being sent is a question the Commander is asking at
 * that moment.</p>
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
            When approaching a planet, its gravity matters to the Commander.
            It is context.body.gravity.
            biology.remaining is what is still uncollected on this body, and
            it is the point of the turn it appears in. It appears in one turn
            only.
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
