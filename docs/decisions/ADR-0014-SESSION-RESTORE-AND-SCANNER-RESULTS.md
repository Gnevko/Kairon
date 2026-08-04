# ADR-0014: Session restore is not an arrival, and a scanner result is an event

## Status

Accepted and implemented. Amends [ADR-0011](ADR-0011-BEHAVIOR-GRAPH.md) on
episode roots and structural admission, and
[ADR-0013](ADR-0013-LLM-DECISION-INTERFACE.md) on which journal types reach the
model. `SystemEpisode` moves to schema `kairon.system-episode/v3`. No migration
is provided; existing development graphs are incompatible with the new
admission and must be cleared before the next live or replay verification.

## Context

A read-only audit of a 300-record paced replay against a graph built from
nothing established three separate facts from the produced artefacts.

**A startup `Location` was recorded as an arrival.** `BehaviorGraphService`
routed it through the same `startJournalRoot` a completed hyperspace jump uses
and minted a `SYSTEM_ENTRY` occurrence with `EventOccurrenceSource.JOURNAL`.
Only `SystemEpisode.entrySource` distinguished the two, and that field is read
by nothing — not the node, not the edge, not the model-facing trajectory. In
the produced graph the `SYSTEM_ENTRY` node carried `rawOccurrenceCount = 4`:
three real jumps and one session restart, indistinguishable. The next
structural event of the session then took an ordinary transition from that
root, so the graph learned the edge
`SYSTEM_ENTRY → SUPERCRUISE_JUMP_STARTED` (`rawCount = 1`,
`decayedValue = 1.0`) — a behavioural claim that entering a system tends to be
followed by a supercruise jump, derived entirely from the Commander having
started the game. The first real `FSDJump` turn was then given
`likelyNext: [{"kind":"SUPERCRUISE_JUMP_STARTED","probability":1.0}]`, and
`SYSTEM_ENTERED` appeared in the remembered trajectory of eight turns.

**A completed jump reported the wrong flight mode.** `CurrentGameStateProjector`
set `flightMode = NORMAL_SPACE` in the `FSDJump` branch. `NORMAL_SPACE` is what
`SupercruiseExit`, `Liftoff` and `Undocked` mean; a jump ends at the arrival
star with the FSD still running. No Status field feeds `flightMode` — every
value comes from an explicit journal branch — so the error did not
self-correct in live capture either.

**Exploration reached the model as nothing.** The same 300 records contained
49 `Scan`, 9 `FSSBodySignals` and 11 `ScanBaryCentre`. They produced zero
structural occurrences and zero model turns. `Scan` and `FSSBodySignals` were
`CONTEXT` for the graph and `CONTEXT_ONLY` for the observer; a whole system's
worth of surveying was invisible to the model, which was given landings and
route selections and nothing about what was being explored. A dead
`NormalizedEventType.FSS_BODY_SIGNALS_FOUND` and a dead trajectory name for it
had existed the whole time, and a test asserted the node must not exist.

## Decision

### Restoring a session records nothing

A session-restoring `Location` opens a `SystemEpisode` with no root occurrence
and an empty timeline (`SystemEpisode.startRestored`), and clears the graph
cursor. The first structural event of that visit becomes occurrence zero and
takes **no** `OccurrenceTransition`, so no edge is learned into it.

The empty restored episode is a real state rather than a broken one, and the
model says so: `rootOccurrenceId` is nullable exactly when
`entrySource == LOCATION_RESTORE`, `awaitingFirstOccurrence()` names the
condition, and `ActiveEpisodeSituation` accepts a trajectory that begins with
something other than `SYSTEM_ENTRY` provided it does not begin before the visit
did. A capture taken while the visit is still empty reports
`NO_ACTIVE_EPISODE` rather than inventing a position.

A real `FSDJump` is unchanged: its own `SYSTEM_ENTRY` root, counted and
predicted from as before, and still with no edge across the episode boundary.

The alternative — keeping a synthetic root and marking it — was rejected. Every
consumer of the graph would then have to remember to exclude it, and the first
one that forgot would reintroduce exactly this defect.

### A jump ends in supercruise

`FSDJump` sets `flightMode = SUPERCRUISE`.

### A scanner result is structural, and is recorded once

`Scan` and `FSSBodySignals` become `SIGNIFICANT`, normalize to `BODY_SCANNED`
and `FSS_BODY_SIGNALS_FOUND`, and become `NEW_ELIGIBLE`
(`BALANCED-111` / `CONTEXT-3`). `ScanBaryCentre` is untouched: it is orbital
arithmetic about a point in space, and remains `NOISE` / `DIAGNOSTIC_ONLY`.

Admission is decided per record, not per type, because the type cannot express
either question that matters:

- **Did this establish anything?** Only `ScanType = Detailed` carries the
  classification, the flags and the measurements; an automatic scan is the ship
  noticing a body it flew past. A record with no `(SystemAddress, BodyID)` can
  be attributed to no body. A signal reading with no positive count found
  nothing.
- **Is this the result we already have?** Compared on the exact facts —
  `BodySurveyFacts.scanSignature` and `signalSignature` — against the last
  reading of the same body in this visit. Never on timestamps, adjacency or raw
  JSON: a result restated three events later is still the same result, and a
  changed result is new however quickly it followed. Signal readings compare
  across instruments, so a surface survey confirming what the system scan
  already reported is one finding, and a survey that reports more is two.

Two owners apply that one rule. `BodySurveySelectionPolicy` decides structural
admission against the episode timeline; `BodySurveyNoveltyGuard`, owned by
`LlmJournalObserverSubscriber`, decides trigger admission against its own
per-visit memory. They are deliberately not one component: whether an
observation reaches the model must not depend on whether the behaviour graph is
enabled, and the graph's classification governs its own granularity only
(ADR-0011). The guard also records `CONTEXT_ONLY` `SAASignalsFound` readings,
so the order the two instruments report in does not change the answer.

`FSS_BODY_SIGNALS_FOUND` and `SAA_SIGNALS_FOUND` share the model-facing name
`BODY_SIGNALS_FOUND`. What the Commander learned is what is on the body; which
instrument said so first is Kairon's bookkeeping. The shared name is safe
precisely because the deduplication above means one reading is only ever
recorded once per body per visit.

Neither new kind carries `occurrenceOnBody`
(`DecisionEventRule.uncountedOnBody`): a repeat is never recorded, so the count
could only ever be one, and a field whose only possible value is one says
nothing.

### The reading travels whole

`SemanticValue` gains one compound variant, `SignalCountsValue`, because the
fact is compound: "two geological and one biological" is a single reading.
Categories outside the closed set — the game adds them — are reported as
`OTHER` with the localised label when the game supplies one, never as the
`$SAA_SignalType_*;` identifier.

`BodyContext` keeps the whole normalized reading as its source of truth, and
the two published counts are read out of it. A reading that does not mention a
category is silence about it rather than a retraction of it; only the first
reading of a body defaults the two published categories to zero. This reverses
`CurrentGameStateSemanticDeltaTest.signalCountZeroingIsAnUpdateNotAClear`,
which pinned the opposite: the game has no contract for withdrawing a reported
signal, and treating omission as withdrawal erased what an earlier instrument
had established.

## Consequences

- Existing behaviour graphs hold synthetic `SYSTEM_ENTRY` occurrences and the
  edges learned from them, and hold no `BODY_SCANNED` or
  `FSS_BODY_SIGNALS_FOUND` occurrences at all. No migration is implemented and
  no persisted file is rewritten: the schema version is raised, development
  graphs are treated as incompatible, and verification uses a clean graph.
  Nothing is deleted automatically at startup, because a real graph mixes real
  history with the synthetic entries and automatic deletion would destroy the
  first to remove the second.
- Model turns increase in exploration. That is the point of the change, and it
  remains the model's decision whether any of them is worth a comment: nothing
  here scores, ranks or prioritises an event.
- One test that encoded the previous product decision — `FSSBodySignals` must
  not create a node — is replaced rather than deleted, by coverage of the new
  semantics: a changed reading records, an identical one does not, an empty one
  is not a reading, a barycentre is not a body scan, and a confirming survey is
  not a second finding.
