# ADR-0017: One sequence, every layer, one test

## Status

Accepted and implemented as Phase 0 of the semantic-pipeline plan. Test
architecture only: no production type, no production behaviour and no
model-facing contract changes. It records how later phases will be verified, and
what four target contracts they have to satisfy.

## Context

A read-only architectural audit reproduced four live disagreements between
layers, and every historical defect it re-examined had the same shape: one layer
was right, another was wrong, and each layer's own tests were green.

- A restoring `Location` created a real `SYSTEM_ENTRY` and a learnable edge,
  while the observer correctly treated it as context.
- `SAASignalsFound` created a graph occurrence while remaining `CONTEXT_ONLY`
  for the observer, so a finding existed structurally and was never reported.
- A `BOOTSTRAP` scanner result stopped opening a turn and stopped entering the
  novelty memory, and for a while kept creating the occurrence the live reading
  was then deduplicated against.
- The graph and the observer scoped novelty differently — one episode, one
  system address — so returning to a system produced an occurrence the graph
  considered new and a turn the observer considered a repeat.

None of these is a coding error inside a layer. Each is a contract that spans
layers and was written down nowhere. The tests that existed could not have
caught them: they asserted an occurrence without a provider call, a provider call
without a graph check, a single JSON field without the document, or a canonical
accessor without distinguishing unknown from zero.

Two further problems were mechanical. There was no way to run the decision
pipeline with the behaviour graph disabled, so a dependency on the graph could be
introduced without anything going red. And batches were closed either by ending
the replay — which also completes the `SystemEpisode`, confounding "the observer
declined" with "there was no visit" — or by `DecisionTurnPolicy(1, …)`, which
makes a multi-trigger batch impossible to express.

## Decision

### One harness over the production pipeline

`SemanticPipelineHarness` composes `DecisionProductionPipeline`, which is the
real bus, projection coordinator, behaviour graph, semantic envelope, observer
subscriber, turn coordinator, request factory and serializer. Three things are
substituted and none of them can change what the model is sent: the source of
observations, the graph store (in-memory, per instance) and the provider (a stub
answering `SILENT`).

It is deliberately not a second pipeline. Nothing about projection, selection,
recording, batching or serialization is reimplemented; the harness adds
observation and a deterministic boundary, and nothing else.

### One immutable trace

`PipelineTrace` records what every layer produced for one sequence: canonical
state per observation, the semantic effects from each observation's own envelope,
episodes with their entry source and occurrences, transitions, the cursor,
trigger admission with bus sequences, provider calls, and the exact serialized
`userMessage` with its events, changes, context and trajectory parsed.

Occurrence ownership is derived the way `DecisionOccurrenceScope` derives it —
the id the graph would mint for an observation is recomputed and compared with
the ids it holds — never from adjacency, a timestamp, or an `APPLIED` status.

Every assertion prints the whole trace on failure. A cross-layer failure is
unreadable without the other layers.

### Ten contracts, stated once

`SemanticPipelineAssertions` states what no single layer can: restore-only, new
structural trigger, duplicate suppressed, no provider turn, occurrence/event
agreement, no stale changes, unknown not materialized, source order,
changes/context partition, graph-disabled parity.

Several small assertions rather than one large one. A single "everything is
consistent" check reports the first thing it notices and hides the rest, and
which of these fails *is* the shape of the defect.

### The batch boundary is the production one, shortened

`closeBatch()` waits for the projection to go idle — so every observation has
been projected and every observer command posted — and then for the observer,
which completes once the scheduled turn has run. The batch still closes through
production batching on the quiet period, the maximum batch age or the trigger
cap; the harness only shortens the configured quiet period. Ending the replay
and `DecisionTurnPolicy(1, …)` are both avoided, and this is the whole
workaround.

### A known defect is a disabled target contract

`SemanticPipelineKnownInvalidContractTest` states, on the exact audit scenario,
what the pipeline should do — and is disabled because it does something else.
Each test names the phase that will fix it, and each fails when activated. A
disabled test asserting today's behaviour would certify the defect, which is
worse than no test at all.

Four are recorded:

| Target contract | Violation when recorded | Phase | Status |
|---|---|---|---|
| historical effects are not live background changes | `captureMode` was dropped on the way to the effects, so bootstrap effects arrived in a live turn without an `eventId` | 2 | **enabled and passing** ([ADR-0020](ADR-0020-EFFECT-RETENTION.md)) |
| an unmeasured signal category stays unknown | `CurrentGameStateProjector.updateBodySignals` writes a known zero for a category the reading never mentioned | 0.5 | **enabled and passing** ([ADR-0018](ADR-0018-FIELD-AWARE-STATEMENTS-AND-UNKNOWN-COUNTS.md)) |
| a field's section is causality, not arithmetic | `ProjectedEvent.states` compares by `SemanticValue` alone, so `occurrenceOnBody: 1` suppresses a change whose value is `1` | 0.5 | **enabled and passing** ([ADR-0018](ADR-0018-FIELD-AWARE-STATEMENTS-AND-UNKNOWN-COUNTS.md)) |
| a repeated attack has one cross-layer answer | the graph suppresses a continuing `UNDER_ATTACK`; the observer opens a turn per record — one occurrence, three turns | open decision | still disabled |

The fourth is not a defect with a known fix. It is an unratified product
question, and the test records both options and says which one it encodes.

## Consequences

- A change that makes the graph and the observer disagree now fails a test that
  names the disagreement, instead of passing two green suites.
- The decision pipeline is exercised with the graph disabled for the first time.
  What the graph contributes is exactly three things — the trajectory, its
  predictions and the per-body occurrence count — and the parity test normalises
  those and nothing else.
- Each closed batch costs the shortened quiet period. The contract suite trades
  a few seconds of wall clock for boundaries that do not depend on replay
  exhaustion.
- Nothing about production changed. `AppliedObservation` is not implemented, the
  split brain is not resolved, and no defect is fixed here.
