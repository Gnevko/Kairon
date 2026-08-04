# ADR-0015: A change is what is true now, and a finding is new once per visit

## Status

Accepted and implemented. Amends
[ADR-0013](ADR-0013-LLM-DECISION-INTERFACE.md) on change selection and
[ADR-0014](ADR-0014-SESSION-RESTORE-AND-SCANNER-RESULTS.md) on scanner-result
admission. `BALANCED-111`/`CONTEXT-3` becomes `BALANCED-112`/`CONTEXT-2`. No
persistence format changes and no migration is provided.

The last two consequences below are superseded by
[ADR-0016](ADR-0016-HISTORICAL-FINDINGS-AND-POSITIVE-COUNTS.md): a `BOOTSTRAP`
scanner result no longer creates a graph occurrence, and an explicitly reported
`Count: 0` no longer replaces a known count. Everything else here stands.

## Context

A read-only follow-up audit of the ADR-0014 implementation reproduced two
defects on a clean provider-free pipeline.

**A hidden observation's value outlived its replacement.** A restoring
`Location` establishes `flightMode = NORMAL_SPACE`; the effect waits in
`SemanticEffectAccumulator` until a turn closes over it. In between, `StartJump`
moved the mode to `SUPERCRUISE` — and that change was dropped for being
`DIAGNOSTIC_ONLY`, while the restore's was kept. The first turn of the session
therefore said:

```json
"changes":[{"subject":"navigation","kind":"ESTABLISHED",
            "fields":{"flightMode":{"after":"NORMAL_SPACE"}}}]
```

with canonical state already at `SUPERCRUISE`. Worse, `DecisionContextSelector`
treats a change as a statement of its field, so the correct
`context.navigation.flightMode = SUPERCRUISE` was suppressed: the only thing the
document said about the flight mode was the wrong thing. The audit reproduced
the same shape for `vehicle.kind`, so it is neither startup-specific nor
flight-mode-specific.

**A finding reached the graph and stopped there.** `SAASignalsFound` was
structural for the behaviour graph and `CONTEXT_ONLY` for the observer. A body
first read by the surface scanner produced an occurrence and no event; a changed
set after a system scan produced a second occurrence and no second turn. The new
signals reached the model only as a background `context.body` count on some
later unrelated turn, beside two indistinguishable `BODY_SIGNALS_FOUND` entries
in the trajectory — and if no later eligible event occurred, not at all.

Two smaller findings came with them. The observer's novelty memory was keyed by
system address for the life of the process while the graph deduplicated inside
one `SystemEpisode`, so returning to a system produced an occurrence the graph
considered new and a turn the observer considered a repeat. And the
`CONTEXT_ONLY` path that recorded scanner signatures had no `BOOTSTRAP` check,
though the trigger path did — a historical result the model never saw could
silence the live reading that repeated it.

## Decision

### A change is reconciled against the state the turn closed on

`DecisionChangeSelector.stale` drops a change whose `after` is no longer the
final canonical value of its field. Compared as typed `SemanticValue` through
`CurrentGameStateSemantics.valueOf` against
`inputs.finalTrigger().currentState()` — the same reader the projector uses to
compute the delta and the same snapshot the context is selected from. No second
field mapping, no serialized-text comparison, no re-derivation.

The rule applies **only** where no event of the request caused the change. A
trigger-owned change is attributed by its `eventId`, and an event of a batch
really can report a step a later event of the same batch moved on from; deleting
it would make the batch's own history unreadable. A hidden change that is still
current is still sent, without an `eventId`, exactly as before.

A clearing is unaffected: its `after` is unknown, and a field genuinely absent
from the final state reads unknown too, so reconciliation never fires on one.
What happens to clearings is settled by the existing rule.

`SemanticEffectAccumulator` is untouched. Coalescing every effect on drain would
have produced the right value but lost the provenance the accumulator exists to
keep, and the accumulator is not the component that decides what a decision
needs.

### Both scanners report findings

`SAASignalsFound` becomes `NEW_ELIGIBLE` in `LlmJournalEventSelection` — the
single selection profile, not a promotion inside the subscriber — and gains a
`DecisionEventCatalog` rule for the kind it already shares:
`BODY_SIGNALS_FOUND`. It reuses the existing semantic adapter, the existing
`BodySurveyFacts` signature and the existing projection; there is no second
signal path.

The shared model-facing name stays. What the Commander learned is what is on the
body; which instrument reported it is Kairon's bookkeeping, and naming the
instrument would put a Frontier subsystem in front of the model. Two
`BODY_SIGNALS_FOUND` in a row are legitimate when two different sets were
found — and what differs is in `events[].signals`, which is where a factual
difference belongs.

### A finding is new once per visit

`BodySurveyNoveltyGuard` scopes its memory to one visit rather than to a system
address, and derives the boundary itself: a completed `FSDJump`, a change of
commander or ship, a `Location` naming a different system or arriving with no
visit in progress, and a `Shutdown` ending one. Those are the observations the
graph opens and closes an episode on, so the two owners now agree on where a
visit starts without either reading the other. The visit is an internal
monotonic counter — never published, never serialized, never model-facing — and
nothing here touches the persisted graph, so deduplication keeps working with
the graph feature disabled.

### What the model was never shown is not remembered

The `BOOTSTRAP` check moves ahead of every novelty update. The only path that
recorded a signature without it was the `CONTEXT_ONLY` branch that existed for
`SAASignalsFound`; with that type now `NEW`, no context-only type is a scanner
result and the branch is removed rather than patched. Visit boundaries are still
followed during bootstrap: where the Commander is is true whatever the capture
mode, and a guard that only learned about jumps it could comment on would carry
one visit's findings into the next.

## Consequences

- More turns during exploration, because a surface survey that finds something
  now says so. Whether any of them is worth a comment remains the model's
  decision; nothing here scores or ranks an event.
- A `BOOTSTRAP` scanner result still creates a graph occurrence, and the live
  reading repeating it is deduplicated against that occurrence while opening a
  turn of its own. One finding, one occurrence, one event — recorded at
  bootstrap, reported when live.
- An explicitly reported `Count: 0` still replaces a known count, while a
  category a reading omits does not. Silence is not a retraction; an instrument
  answering "none" is an answer. Both are asserted.
- No migration and no backward compatibility. Existing development graph data
  must be cleared or replaced before the next replay verification.
