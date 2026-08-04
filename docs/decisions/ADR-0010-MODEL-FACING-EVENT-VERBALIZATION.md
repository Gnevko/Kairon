# ADR-0010: Event-owned LLM verbalization

## Status

Accepted and implemented for all 114 event types in the active
`BALANCED-109` and `CONTEXT-5` profiles.

Amended by ADR-0013: `llmPresentation()` is no longer model input. It remains
the authority for diagnostics, the desktop GUI and the observation corpus, and
it remains the researched interpretation of each event's source fields — but the
provider now receives domain-facing structured events, and the two cases the
prose was retained for are covered by the event kind and by a typed uncertainty
field. The evidence-first evaluation rule below is unchanged and applies to the
structured projection exactly as it applied to the prose.

## Context

Exact Elite Dangerous Journal JSON is authoritative evidence, but a general
LLM is not guaranteed to know the Journal API, opaque identifiers, field
semantics, scan stages, or the missing comparison baselines.

Earlier qualitative model claims were evaluated from raw inputs that did not
explain all required domain semantics. Treating those results as model failure
before examining the supplied knowledge was therefore unjustified.

## Decision

Preserve exact `RawJournalData` as the authority for observations,
diagnostics, the desktop GUI, and aggregate turn traces.

An event type may enter the active LLM observer only when its concrete event
class implements `LlmPresentableJournalEvent`. The class owns researched
interpretation of its source fields and returns deterministic, complete
English factual sentences. Shared helpers may normalize and quote values but
do not own event-specific meaning.

A presentation must:

- be based on authoritative event documentation;
- preserve relevant exact values and source-provided human labels;
- express uncertainty or a missing comparison baseline where necessary;
- never infer importance, rarity, value, abundance category, emotion, intent,
  danger, or comment-worthiness.

Documented numerical fields may be accompanied by deterministic
human-readable unit conversions. For example, `Scan` supplies surface
temperature in kelvins and degrees Celsius, gravity in `m/s²` and `g`,
pressure in pascals and kilopascals, and radius in kilometres. Such conversion
does not label a value high, low, rare, safe, or significant.

`ObserverPromptFactory` owns only the common envelope: output language,
previous delivered comments, ordering, turn-local alias, `CONTEXT`/`NEW`,
model-facing time, instructions, and response contract. It sends the
event-owned presentations, not raw JSON.

The common system envelope is one well-formed XML document. It supplies a
compact game overview, a female in-universe Kairon persona with a short
flight-operations and survey biography, the observer purpose, grounding and
style rules, the output contract, and a small set of calibration examples.
Persona and examples shape voice and model judgement; they neither reinterpret
event fields nor assign deterministic importance. The current prompt persona
is not a durable memory or a general conversational subsystem.

The observer also asks the model to compare a proposed comment's core meaning
with recently delivered comments. An intermediate step in the same continuing
activity is not a new conversational point merely because it is a new event,
uses different wording, or can be phrased as a question. A materially
different fact, milestone, result, failure, threat, or discovery may justify a
new comment in that episode. This semantic novelty decision remains with the
model. `CommentNoveltyGuard` provides only a conservative output boundary: it
rejects normalized exact duplicates and strongly overlapping lexical
near-repeats. It does not establish general semantic equivalence or decide
whether an observation deserves a comment.

Unknown and unselected events continue unchanged through `ObservationBus`,
diagnostics, and the GUI. A future addition cannot enter an active LLM profile
until its presentation is researched, implemented, and tested. The original
111-type migration advanced in reviewed batches of five; later
reviewed additions, currently `Commander`, `Friends`, and `ApproachBody`, use
the same gate.

## Evidence-first model diagnosis

Before classifying behavior as an LLM failure:

1. inspect the exact traced model input and event window;
2. verify that every required fact and relationship was actually supplied;
3. verify field meanings against an authoritative source;
4. verify that any required comparison baseline or domain definition existed;
5. verify that the instruction was clear and non-contradictory.

Missing semantics, context, terminology, or baselines are first an
application-input defect. Only a remaining error with sufficient supplied
evidence may be attributed to model grounding or instruction following.

## Consequences

- The model receives self-contained facts without needing implicit Journal API
  knowledge.
- Raw auditability and forward compatibility remain intact.
- Selected event records gain an explicit presentation responsibility.
- The current 109 NEW and five CONTEXT profile types have complete
  presentation coverage.
- Presentation tests become executable semantic-contract tests.
- No deterministic `COMMENT` rule is introduced.

## Rejected alternatives

- Raw JSON as the only semantic model input.
- Assuming model pretraining includes the Elite Dangerous Journal contract.
- One central event-name switch or reflective field dump.
- An extra LLM call that summarizes events.
- Unsourced narrative or importance-producing deterministic summaries.
- Silently admitting unresearched event types with a raw fallback.

## Relevant implementation references

- [`LlmPresentableJournalEvent.java`](../../src/main/java/kairon/observation/journal/LlmPresentableJournalEvent.java)
- [`event/`](../../src/main/java/kairon/observation/journal/event/)
- [`ObserverPromptFactory.java`](../../src/main/java/kairon/llm/ObserverPromptFactory.java)
- [`CommentNoveltyGuard.java`](../../src/main/java/kairon/llm/CommentNoveltyGuard.java)
- [`LlmJournalEventSelection.java`](../../src/main/java/kairon/observer/LlmJournalEventSelection.java)
- [`JournalEventLlmPresentationTest.java`](../../src/test/java/kairon/observation/journal/JournalEventLlmPresentationTest.java)
- [`ObserverPipelineTest.java`](../../src/test/java/kairon/observer/ObserverPipelineTest.java)
- [Frontier Player Journal Manual v37](https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf)
