# ADR-0020: A restored fact is background, not news

## Status

Accepted and implemented as Phase 2 of the semantic-pipeline plan. It is the
first phase that changes model-facing output: a turn that used to open with
historical background changes no longer does. The behaviour it fixes is the
first of the two target contracts from
[ADR-0017](ADR-0017-CROSS-LAYER-CONTRACT-TESTS.md), which is now enabled and
passing; the repeated-`UNDER_ATTACK` one stays disabled and is still an open
product question.

## Context

Bootstrap is how Kairon catches up on a session already in progress: the
historical suffix of the journal is read, canonical state is established from it,
and the model is told nothing, because none of it just happened.

The state part worked. The effects part did not. Every observation's semantic
delta was recorded in `SemanticEffectAccumulator` regardless of how it was
captured, and the first live event to close a batch over the accumulated effects
presented all of them as background `changes` with no `eventId`. Audit
reproduction R7: a bootstrap arrival, approach and scanner reading, then a live
`Touchdown`, and the Commander is told that the system became Schieni and the
ship became a ship — at the moment they landed.

[ADR-0019](ADR-0019-APPLIED-OBSERVATION.md) made this addressable by carrying
capture mode to where the effects are held. It deliberately did not act on it.

## Decision

### `EffectRetention`, and why it is not model visibility

> **Amendment (ADR-0021).** `ModelVisibility` no longer exists — it was removed
> for having no reader. The argument below is unchanged and is the reason
> retention is derived from capture mode alone: whether the model is told about a
> record and whether its effects are news are two questions, and a rule keyed on
> the first answers the second wrongly.


`RETAIN_FOR_TURN` or `RESTORE_ONLY`, on capture mode alone: bootstrap restores,
everything else happened while Kairon was listening.

The tempting shortcut is to reuse the visibility already on the envelope —
model-silent observations are the ones producing unwanted background, so drop
their effects. That is wrong, and wrong in the direction that loses data. A live
`CONTEXT_ONLY` record is `MODEL_SILENT` and its effects are precisely what the
accumulator exists to keep: the model never hears about the record, and the next
turn is still entitled to say that the flight mode changed. "The model may not be
told about this record" and "this record's effects are not news" are two
questions, and folding them into one enum answers the second with the first.

So retention is its own value, derived from capture mode and not from the
payload at all. `ObservationSemantics.retentionOf(captureMode)` is the whole
rule, and its signature is the argument: it cannot accidentally come to depend on
what an event is.

### One owner, one reader

`AppliedObservation` owns it, beside the two modes, computed once where the
observation is applied. `SemanticEnvelopeFactory` copies it onto
`SemanticObservationEnvelope` — it does not re-derive it from the capture mode
sitting next to it, because two derivations of one rule are two places for it to
diverge.

`SemanticEffectAccumulator.record` is the only reader: a `RESTORE_ONLY` envelope
is not retained, whatever its role and whatever it changed. It still advances the
bus-order check, because declining to keep an effect is not the same as never
having seen the observation.

Everything else is untouched. The role-based rule stands exactly as it was —
`DIAGNOSTIC_ONLY` is kept only when it changed canonical state, and every other
role is kept including when it changed nothing.

### What bootstrap still does

All of it, except leaving effects behind. It projects canonical state, restores
the body, system, ship and vehicle facts, is applied to the behaviour graph under
the existing rules, stays model-silent, and reaches the trace and the GUI.

The facts it established are not lost from the model's view either. They are
canonical, so they arrive as `context` — which is what they are: standing
background rather than something that just happened. In the R7 scenario the
landing's turn now carries `body.biologicalSignals` in `context`, and no changes
at all.

## Consequences

- The one live turn in that scenario reports the landing and nothing else. Zero
  extra provider calls, unchanged graph, unchanged batching, unchanged
  trajectory.
- A fact established by a bootstrap reading can no longer appear in `changes` in
  any scenario. Two tests that used a bootstrap prefix to manufacture a change
  had to be rewritten or re-pointed; one now establishes its fact with a live
  automatic scan, which is a truer fixture than the one it replaced.
- The remaining route by which a body fact reaches `changes` is a live
  observation that is not itself a model-facing event — the same route that
  already existed, now the only one.
- `ObservationSemantics` is no longer purely observed. One of its three answers
  is consulted; the other two still are not, and moving either remains its own
  decision with its own evidence.
