package kairon.llm;

import kairon.llm.LlmClient.ModelInput;
import kairon.turn.glossary.DecisionFieldGlossary;

import java.util.Objects;

/**
 * Who she is, and what an answer looks like.
 *
 * <p>The user message is the serialized decision request and nothing else: no
 * heading, no prose wrapper, no second rendering. What the provider receives is
 * byte-for-byte what the trace records.</p>
 *
 * <p>The prompt is deliberately four blocks. It used to be six — a role, an
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
 * <h2>The signal-count line, removed the same evening as the gravity one</h2>
 * <p>There was a line naming {@code biologicalSignals} and
 * {@code geologicalSignals} as the key finding of a scan result. It was added
 * for a measured reason: on the 2026-08-06 replay a request carrying
 * {@code biologicalSignals: 1} beside an ice body's class, atmosphere and three
 * survey flags produced a remark about the atmosphere, and life on a frozen
 * world was the one fact only that document had.</p>
 *
 * <p>It failed in the way the gravity line did, one step later. Once gravity
 * stopped being the named important thing, the invention moved to the named
 * important thing that was left: on turns carrying no counts at all, a landing
 * became "no signs of biological or geological activity", a disembark became
 * "9 biological signals detected on the planet" — a figure from nowhere — and
 * another became "there are still biological signals here". A line that says a
 * field is the key finding is heard on turns that have no such field.</p>
 *
 * <p>What it was buying had meanwhile been paid for twice over. The surface
 * scanner's {@code organisms} listing reaches the model, and on the live scan
 * of 2026-08-08 she named all five genera from it unprompted — a field the line
 * never mentioned. The glossary now describes both counts in their own words.
 * So the line is gone, and if scan results go quiet the trade has to be
 * measured again rather than assumed.</p>
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
 * <h2>The fourth block, and the wrong version of it that came first</h2>
 * <p>Naming the field was not enough. On 2026-08-08 a live disembark whose
 * whole document was
 * {@code {"body":{"name":"Ogaicy KX-B d13-9339 5 b"}}} — no gravity in it at
 * all, because a walk is not an approach and {@code PRESENCE} never asks for
 * the pull — was answered "gravity on the planet is 0.24g". That was the tenth
 * fabricated quantity, and every one of the ten appeared in a turn where the
 * named field was absent. A line saying where a fact lives stops the model
 * inventing a value beside a field it was handed; it says nothing about a turn
 * where it was handed nothing.</p>
 *
 * <p><strong>The first attempt at the block made it worse, and how it failed
 * is the reason the current one is shaped as it is.</strong> It was called
 * {@code <numbers>} and it was three prohibitions: every number must come from
 * the request, say nothing about a quantity that is not there, and — fatally —
 * "a word like LOW or HIGH is the whole measurement; never turn one into a
 * figure". Measured over the four gravity-eligible turns that followed the
 * restart: {@code 0.24g} became "gravity LOW", then "gravity — HIGH" on the
 * same body an hour later, then "gravity LOW" again, and a lift-off acquired
 * "there are biological samples left on the planet" with no {@code biology}
 * group in the request at all. The figure stopped and the fabrication did not:
 * the prohibition named the very vocabulary it was banning, and the model
 * reached for the words it had just been shown.</p>
 *
 * <p>That is the documented failure mode of negative instruction — the model
 * must invert the rule to obey it, and the token it is told to avoid is a token
 * it has now seen. The replacement is positive and names no field, no unit and
 * no value. Its first sentence is the grounding restriction every vendor guide
 * gives ("use only the information provided"). Its second is this project's own
 * decision of 2026-08-07 turned into an instruction rather than a ban: an added
 * remark is allowed and must sound like a thought, which is what separates the
 * bacterium's cobalt metabolism from a false readout. Its third is the
 * permission to not know — the single technique Anthropic's own guidance puts
 * first — in the form this contract can use, since Kairon does not announce
 * uncertainty ("gravity has not been measured on this planet" was measured and
 * rejected); she leaves it unsaid.</p>
 *
 * <p>The name {@code <grounding>} is back on purpose. The block that was
 * removed carried that name and 447 words of prohibition across several
 * sections; this is three sentences, two of which grant rather than forbid. The
 * cost has to be watched all the same — the removal is documented above at
 * eight {@code SILENT}s out of eight — and if the voice goes quiet, this is the
 * block to suspect.</p>
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
 * <h2>The payout line, added on the evidence of one turn</h2>
 * <p>The third line names {@code valueMCr} and {@code firstFootfall}, and it
 * exists because the fields arrived and were ignored. On the live run of
 * 2026-08-08 the first analysis to carry them read
 * {@code "valueMCr":38.9,"firstFootfall":true} — thirty-nine million credits
 * and a body nobody had walked on — and the answer was "the last sample of
 * Frutexa Acus is collected; bacteria, tussock, stratum and tubus are still
 * uncollected here". She used {@code organism} and {@code biology.remaining},
 * both of which a preference line names, and passed over the two facts that
 * had no line. That is not proof the line is what did it, but it is the one
 * difference between the fields she used and the fields she did not.</p>
 *
 * <p>The wording copies the {@code biology.remaining} line exactly, because
 * that is the shape that has survived: it opens with the field being in the
 * request, says what the field <em>is</em>, and stops. It describes no
 * situation — nothing about detours being worth making or samples being worth
 * collecting — and it says nothing about what absence means.</p>
 *
 * <p><strong>The risk is stated rather than hoped away.</strong> The rule this
 * project paid for twice is that a line calling a field the point of the turn
 * is heard on turns that have no such field. The signal-count line failed
 * exactly so. What makes this one a different bet is that {@code valueMCr}
 * appears in one turn only — the analysis that finishes a sample — which is the
 * property {@code biology.remaining} has and the signal counts did not; and
 * that the model already volunteers value talk without any line at all, having
 * answered a scan carrying only {@code previouslyFootfalled: false} with
 * "nobody has set foot here, so the samples will be especially valuable".
 * <strong>What to watch:</strong> a credit figure on a turn with no
 * {@code valueMCr} — a log, an approach, a landing. One is enough to withdraw
 * the line, and withdrawing it is deleting three lines.</p>
 *
 * <h2>The gravity line, and why there is no longer one</h2>
 * <p>There was a third preference line, about {@code context.body.gravity}, and
 * it was removed on 2026-08-08 after five wordings failed the same way. It is
 * recorded here rather than deleted, because the field is still sent and the
 * question it was added for — an approach returning SILENT with the band in
 * the request — is still a real one.</p>
 *
 * <p><strong>A preference line may name a field. It may not describe a
 * situation.</strong> The line said what weight is <em>for</em> — that it
 * decides whether the descent is routine or wrecks the ship — which is true,
 * and which the model then applied to every turn where a ship was near the
 * ground. Each rewriting narrowed the words and left the count alone:</p>
 *
 * <ul>
 *   <li>bare importance, no field named: invented figures — {@code 0.24g},
 *   {@code 0.12g}, {@code 0.17g}, {@code 0.32g}, three of them for one
 *   body;</li>
 *   <li>a {@code <numbers>} block forbidding invented quantities: invented
 *   bands instead, LOW and then HIGH for the same body an hour apart;</li>
 *   <li>conditioned on the field being present: "gravity is normal",
 *   "probably higher than standard", "a planet with no gravity", and on a
 *   disembark carrying only a body name, "still low gravity here" — a claim of
 *   continuity with a turn the model was never shown;</li>
 *   <li>the reason clause cut, address only: disembarks and embarks went
 *   quiet, lift-offs did not — three out of three;</li>
 *   <li>a 145-entry field glossary added alongside: two consecutive turns on
 *   one body reported HIGH and then LOW.</li>
 * </ul>
 *
 * <p>Twenty-one consecutive approach, touchdown, lift-off, disembark and embark
 * turns produced twenty-one remarks about gravity, and the field was in the
 * document for one of them. The condition "when it is in the request" was
 * checked honestly and lost to the clause behind it: a situation the model
 * recognises beats a field it can look up, because the situation is what the
 * sentence says the fact is <em>about</em>. Nothing that narrows the sentence
 * removes the situation from it.</p>
 *
 * <p>So the line is gone and the experiment it makes possible is the point:
 * the field still arrives on an approach and only there, and it is now
 * described in the glossary, which is new — before that, removing the line
 * meant the model had no way to know what {@code gravity} was. If approaches go
 * quiet, the line was buying something and its cost has to be weighed against
 * that; if they do not, it was buying nothing. Do not restore it without
 * measuring which.</p>
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
            When biology.remaining is in the request, what is still uncollected
            here is the point of the turn.
            When valueMCr is in the request, what the sample just collected
            pays is the point of the turn, and firstFootfall beside it means
            nobody had walked here before.
            When bodyTotalMCr is in the request, this body is finished and what
            it paid in all is the point of the turn.
            When atLeastMCr is in the request, the least this body's organisms
            could pay is the point of the turn.
            </preferences>

            <grounding>
            State as fact only what this request contains. Anything you add
            is your own thought and reads as one.
            A detail that is not here is one you do not have. Leave it
            unsaid.
            </grounding>

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
                        + "\n"
                        + DecisionFieldGlossary.TEXT
                        + "\n<output_language>"
                        + outputLanguage
                        + "</output_language>\n",
                serializedDecisionRequest
        );
    }
}
