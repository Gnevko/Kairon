# ADR-0013: The LLM decision interface

## Status

Accepted and implemented as `kairon.observer.decision`. `kairon-llm-decision-v1`
is the only production model input; `kairon-llm-situation-v2.1` is deleted from
source. The turn trace moved to `kairon-turn-trace-v5`.

## Context

The model input had been designed from the inside out. It carried Kairon's own
structures — a schema version, a turn counter, bus sequences, absolute
timestamps, event-selection roles, journal wire event names, behaviour-graph
node names, an account identifier and a full canonical state snapshot — on the
implicit theory that more true information cannot hurt.

The first-100 replay on `kairon-llm-situation-v2.1` measured what it did cost:
168 992 characters of dense context became 58 809 sparse, and the sparse
document was still mostly Kairon. Across 29 turns and 32 facts:

- `processStage: FINAL` appeared on 27 facts and `completion: true` on 27. On an
  atomic action neither can hold any other value.
- `graphContext.recentBeforeCursor[].normalizedEventType` was the single largest
  field path in the document, at 70 entries, and no comment in either measured
  run rested on it.
- `currentState.commander.fid` appeared in 28 of 29 turns. It is an account
  identifier that can never be spoken aloud.
- `currentState.primaryShip.shipType` appeared in 27 turns, most of which had
  nothing to do with the ship.
- `biologicalSamplingProcess.active: false` appeared in 13 turns that had nothing
  to do with sampling — a declaration of absence in a contract whose whole rule
  is that absence needs no declaration.
- Surface coordinates appeared on 14 facts; no comment referenced one.

And one field did active harm. `SAAScanComplete` sent the probe count as an
unnamed `quantity: 2` beside a named `efficiencyTarget: 2`. The model reported
the target as the probe count — the run's one factual error, produced by a
number with no name.

Meanwhile `evidenceTriggerBusSequences` required the model to echo internal
transport ordinals back, which is both a leak and an invitation to invent one.

## Decision

**One rule governs the contract.** A model-facing field must have a concrete
answer to: *which decision or which sentence does this field improve?* Without a
demonstrated answer it stays inside Kairon and inside the trace. The
field-by-field record of that judgement is
`target/audit/kairon-llm-provider-field-audit.csv`.

**The provider sees the game, never Kairon.** The request has at most four
members: `events`, `changes`, `context`, `contextIncomplete`. No schema version,
turn, bus sequence, timestamp, selection role, wire event name, graph vocabulary
or account identifier appears anywhere.

**Evidence identity is local.** One trigger becomes exactly one event, numbered
`1..n` within a single request. `DecisionEvidence` holds the mapping to trigger
bus sequences; the validator refuses an unknown id and translates the rest
before anything downstream sees them. This makes the current-trigger-only
evidence rule structural rather than enforced: a hidden observation, a context
fact and a previous comment have no id at all.

**Projection is organised by mechanism, not by event.** Eighteen mechanisms
cover the 109 model-eligible types. A mechanism decides two things an individual
event cannot: which parts of the situation may be sent with it, and which
canonical fields the event already states — so a supercruise entry does not also
report that the flight mode became supercruise. `DecisionEventCatalog` holds one
rule per type; coverage is asserted in both directions, so a catalogued event
cannot reach a generic fallback.

**Named values only.** Every quantity is sent under a name that says what it
measures. **A quantity with no name is dropped rather than sent** — the failure
mode being corrected is precisely an unnamed number.

**Stage and completion are sent where they are information.** `START`,
`PROGRESS` and `complete: false` always; `FINAL` with `complete: true` only for
the four genuinely multi-step mechanisms.

**Uncertainty is stated in domain terms, and only where the event made the
claim.** `occupancy: "UNCONFIRMED"` rather than an internal subject slot and a
reason code. A gap about a claim the event never made has no representation at
all: a `Friends` event reports a friend's current status and never asserts a
transition into it, so `LOGIN_TRANSITION_NOT_ESTABLISHED` is recorded
internally and never sent — qualifying an absent claim would introduce it.

**Changes are selected, not dumped.** The full exact delta still reaches the
trace and diagnostics. A change is sent only when it adds novelty the events do
not already carry, or when a hidden observation touched a subject one of this
turn's mechanisms needs.

**The behaviour graph reaches the model only as domain content.** Calculation,
normalization, persistence, predictions, weights, the UI and diagnostics are
unchanged. What is sent is `trajectory` — up to three named predecessors and up
to three named predictions with their calculated probabilities — plus
`occurrenceOnBody` on the event itself. See the amendment below.

**The response is minimal.** `{"decision":"SILENT"}` or
`{"decision":"COMMENT","comment":"…","evidence":[1,2]}`.

## Consequences

- The provider input is a domain document. A reader who knows Elite Dangerous
  and nothing about Kairon can read it.
- Prose event summaries no longer reach the model. ADR-0010's
  `llmPresentation()` remains the authority for diagnostics, the GUI and the
  observation corpus; it is simply no longer model input. Where it existed to
  carry an unprovable caveat, a typed uncertainty field carries it instead.
- The evidence contract is now schema-independent in a stronger sense than
  before: the validator takes a mapping, not a document, and the citable set has
  no members that were never offered.
- `kairon.observer.context` no longer exists. `DeliveredModelComment` moved
  unchanged; the policy lost its three graph-related bounds because the sections
  they bounded no longer reach the model.
- The trace version had to move: `localEvidence` is required, and
  `validatedDecision` gained `evidence` beside the resolved bus sequences.
- The compaction ladder shrank to one rung. Events and their selected changes
  are mandatory, so `CONTEXT_TOO_LARGE` remains reachable and still fails
  closed.
- A guard test now reads serialized requests rather than source text. A comment
  can promise a field is gone; only the bytes the provider would receive prove
  it.

## Rejected alternatives

- **Keep the fields and instruct the model to ignore them.** The previous
  contract already did that, in a prompt block explaining that the graph is
  temporal and non-causal. Instructions do not recover the tokens, and the one
  factual error came from a field the prompt never warned about.
- **Scope the occurrence count to the system visit rather than the body.** Two
  landings on two different moons of one system would then read as a repeat,
  which is the exact sentence the count exists to make safe.
- **Derive the body from the occurrence's body name.** Names are display text,
  arrive localised for some events, and are absent from others. The graph already
  recorded an identity when it accepted the occurrence; guessing beside it would
  be a second, worse answer to a question already settled.
- **One projection class per event type.** 109 classes to express what eighteen
  mechanisms and a name table express, with 109 places for the shared rules to
  drift apart.
- **A relevance or importance gate before the model.** Forbidden by the project's
  core invariant: the LLM owns interpretation and comment-worthiness. What is
  implemented here is not a judgement about whether an event matters — every
  trigger becomes an event — but about whether a *field* has ever been shown to
  change an answer.
- **Send internal identifiers so the model can correlate objects.** No
  catalogued mechanism was found where an id is the only way to tell two objects
  apart within one request, and correlation across a request is exactly what the
  local event ids are for.
- **Keep `evidenceTriggerBusSequences` and add local ids beside it.** Two
  identity domains in one contract, with the internal one still leaked.

## Amendment — domain-facing trajectory

The original decision removed the behaviour graph from provider input entirely,
on the ground that returning a distilled history needed separate evidence. The
distillation is now defined, and it is a different object from what was removed.

What was removed was Kairon's own structure: `graphContext` with a cursor,
occurrence ids, episode sequences, provenance, omitted-occurrence counts, a
prediction basis, a context bucket and the transition counters behind every
probability — all of it spelled in normalized event types the model had no other
vocabulary for. What is added is two lists of the same domain names the events
already use, and one integer.

```json
"trajectory": {
  "recent": ["BIOLOGICAL_SAMPLE_STARTED", "EMBARKED", "LIFTOFF"],
  "likelyNext": [{"kind": "DISEMBARKED", "probability": 1.0}]
}
```

**`recent` is fact, `likelyNext` is not, and the prompt says which is which.**
The model is told in as many words that a predicted event has not happened and
must never be spoken of as though it had. Neither list is citable evidence: they
carry no local ids, so the evidence rule stays structural rather than instructed.

**Occurrence count is scoped to the body, never to all time.** `occurrenceOnBody`
counts that event type at that exact body within the current system episode. The
all-time counters the graph also keeps are not sent: "the second landing here" is
something a Commander recognises, and a running lifetime total is not.

**One piece of projection metadata was added.** `SituationOccurrence` gained a
`body` of (`systemAddress`, `bodyId`), carried from the `ContextSnapshot` the
occurrence was already accepted with. Nothing is inferred from a name, and a body
id alone would merge the fourth body of one system with the fourth body of the
next. Graph ownership, calculation, weights, probabilities and the store schema
are untouched.

**Vocabulary agreement is tested, not asserted.** `DecisionTrajectoryNames` maps
every declared `NormalizedEventType` to a domain name, and a test requires that
where a type corresponds to a catalogued journal event the two tables produce the
same word. An unmapped type is dropped rather than passed through, because the
only unmapped values are `UNKNOWN_*` names built from the journal's own wire
event name.

The trajectory is mandatory content for the budget rather than a compaction rung:
it is six items at most, so it cannot be why a turn overflows, and dropping it
would buy nothing against the loss of a repeat being recognisable.

## Relevant implementation references

`kairon/observer/decision/` — the request, the evidence mapping, the catalogue,
the mechanisms, the three projections, the serializer and the compactor.

`kairon/llm/DecisionPromptFactory.java` and
`kairon/llm/ObserverResponseValidator.java` — the prompt and the response
contract.

`kairon/trace/JsonLinesTurnTraceWriter.java` — `kairon-turn-trace-v5`.

Design: `docs/design/kairon-llm-decision-interface.md`. Audit:
`target/audit/kairon-llm-provider-field-audit.csv`,
`target/audit/kairon-llm-decision-v1-report.md`,
`target/audit/kairon-llm-decision-v1-first-100-comparison.json`.
