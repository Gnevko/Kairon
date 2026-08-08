# kairon-llm-situation-v2 — evidence-based design

> **Superseded in part.** Everything below about `ACTIVATED_FROM_CONTEXT`,
> `SemanticValueOrigin` and the projector's per-body `BodyContext` map — §6.2,
> §6.3, the wire tables and the tests derived from them — describes a design
> that existed and has been removed. Body detail is the current-system
> registry's ([ADR-0025](../decisions/ADR-0025-THE-CURRENT-SYSTEM-IS-A-REGISTRY.md)),
> canonical state answers only which body the Commander is at, and a body fact
> is therefore not a canonical delta at all. Everything below that describes
> `llmPresentation()` — the L3 finding, OQ-9, and the two notes that no adapter
> reads it — describes a method that was removed by
> [ADR-0027](../decisions/ADR-0027-THE-RESEARCHED-PROSE-IS-NOT-KEPT.md), along
> with the test named there. Read
> [`CURRENT_STATE.md`](../CURRENT_STATE.md) for what is true now.

Phase A design-only audit. **No production code, test, or configuration was changed.**

Evidence labels used throughout:

| Label | Meaning |
| --- | --- |
| `VERIFIED_CURRENT_BEHAVIOR` | Read directly from current `src/main`, path + member cited. |
| `VERIFIED_AVAILABLE_DATA` | The datum provably exists at a boundary today, whether or not it is used. |
| `DESIGN_PROPOSAL` | Proposed for v2. Does not exist. |
| `INFERENCE` | Derived from verified facts, not directly stated anywhere. |
| `OPEN_QUESTION` | Needs a decision or a fact the repository does not contain. |
| `UNRESOLVED` | Could not be established from repository evidence. |

---

## 1. Executive verdict

**`DESIGN_READY_WITH_EXPLICIT_OPEN_QUESTIONS`**

The exact v2 contract is specified (§13), the hidden-source provenance owner is chosen and justified (§7), and the structured-fact boundary is fixed (§8). `DESIGN_READY` is deliberately **not** claimed, for reasons that are defects in the *current world model*, not gaps in the contract:

1. **The vehicle model cannot express fighter or SRV occupancy.** `CurrentGameStateSnapshot` has exactly one `activeVehicleId` + one `vehicleKind`, and `DockFighter` / `VehicleSwitch` are not projected at all. v2 can only represent this as `null` / `UNRESOLVED` (§9, OQ-3, OQ-4).
2. **`CLAUDE.md:78` is contradicted by the code.** Status-derived occurrences *do* reach the model through the graph situation (§10.4). Which side is wrong is a decision the repository cannot make (OQ-1).
3. **The graph cursor may not describe the current trigger**, and today nothing labels that (§10.3). v2 fixes the labelling, but the underlying semantics need confirmation (OQ-2).
4. **Three of the nine historical audit cardinalities cannot be verified** because every prior audit artifact is absent (§3.2, OQ-8).

None of these blocks writing the v2 contract. All of them would be silently mis-encoded if v2 were implemented without an explicit decision, which is precisely why they are listed rather than guessed.

---

## 2. Scope and non-goals

### In scope

A model-facing semantic decision context for exactly one judgement: `SILENT` / `COMMENT`. Concretely: novelty, exact canonical state changes, provenance, subjects, structured trigger semantics, non-causal graph context, prediction support, previous-output safety, diagnostic separation.

### Explicit non-goals

- **Completing the Elite Dangerous ontology.** Taxi, multicrew and fighter semantics stay `null` / `UNKNOWN` / unresolved (§9).
- Changing TTS, batching, event selection, the response contract, graph persistence schema, or the trace writer's identity guarantee.
- Wiring `ObservationCorpusJsonlSubscriber` into the runtime. `VERIFIED_CURRENT_BEHAVIOR`: it is referenced only by its own source and its test; `KaironApplication` never instantiates it.
- Re-introducing the legacy 30-event context. `VERIFIED_CURRENT_BEHAVIOR`: `ObserverPipelineTest` pins its absence (`assertFalse(system.contains("last 30"))`).
- Any permanent dual v1/v2 path. The migration has a defined deletion point (§16).

### Invariants preserved

One `busSequence` remains the correlation identity; state, graph and LLM context remain one immutable post-projection moment; no late reads; `null`/`UNKNOWN` are never guessed; adjacency is never causation; predictions are never guarantees; previous model output is never ground truth; response evidence may cite only current-turn triggers; the decision stays `SILENT` | `COMMENT`.

---

## 3. Evidence methodology

### 3.1 Method

Every claim below was taken from current `src/main` (priority 1) and current `src/test` (priority 2). A now-deleted handover note was used **only** for navigation. Git was not used as evidence, per instruction and because the workspace reports no usable repository from the shell.

Census numbers were re-derived mechanically rather than trusted. Reproduction (Git Bash, repo root):

```bash
grep -c '^        register($' src/main/java/kairon/observation/journal/JournalEventCatalog.java
find src/main/java/kairon/observation/journal/event -name '*.java' -type f | wc -l
grep -rlE 'implements[^{]*LlmPresentableJournalEvent' --include='*.java' src/main/java/kairon/observation/journal/event | wc -l
```

The selection, significance, normalized-type and normalizer-rule sets were extracted by parsing the `List.of(...)` / `Set.of(...)` / `register(...)` literals directly out of the four owning source files.

### 3.2 Prior audit artifacts — all absent

`target/audit/` **did not exist** when this audit began. `find . -type d -name audit` returned nothing. Every one of the 20 artifacts listed for consultation is therefore in the same state:

| Artifact (all under `target/audit/`) | Existed | Read | Conclusions used |
| --- | --- | --- | --- |
| `llm-situation-v1-semantic-audit.md` | **No** | No | none |
| `llm-situation-v1-field-catalog.csv` | **No** | No | none |
| `llm-situation-v1-turn-size-analysis.json` | **No** | No | none |
| `llm-situation-v1-issues.json` | **No** | No | none |
| `kairon-event-semantics-audit.md` | **No** | No | none |
| `kairon-event-semantics-catalog.csv` / `.json` | **No** | No | none |
| `kairon-semantic-transition-patterns.json` | **No** | No | none |
| `kairon-v2-semantic-coverage-matrix.csv` | **No** | No | none |
| `kairon-event-coverage-summary.json` | **No** | No | none |
| `kairon-event-semantics-issues.json` | **No** | No | none |
| `kairon-authoritative-semantics-verification.md` | **No** | No | none |
| `kairon-authoritative-semantics-ledger.json` / `.md` | **No** | No | none |
| `kairon-authoritative-source-evidence.json` | **No** | No | none |
| `kairon-id-correlation.csv` | **No** | No | none |
| `kairon-body-classification-evidence.csv` | **No** | No | none |
| `kairon-presence-semantics-matrix.csv` | **No** | No | none |

Nothing was reconstructed from memory. A focused re-audit was run from code instead (§3.3). No conclusion in this document depends on a missing artifact.

### 3.3 Historical cardinalities — verified vs unverifiable

| Claimed | Status | Evidence |
| --- | --- | --- |
| 272 catalog event names | **VERIFIED** | `JournalEventCatalog` has 272 `register(...)` calls and self-asserts `EXPECTED_KNOWN_EVENT_COUNT = 272`. |
| 272 concrete event classes | **VERIFIED** | 272 `.java` files under `observation/journal/event/**`, all `record`. Cross-checked by `JournalSourceTest:143`. |
| 109 `NEW_ELIGIBLE` | **VERIFIED** | `LlmJournalEventSelection.NEW_ELIGIBLE`, `NEW_EVENT_TYPE_COUNT = 109`, profile `BALANCED-109`. |
| 5 `CONTEXT_ONLY` | **VERIFIED** | `LlmJournalEventSelection.CONTEXT_ONLY` — `Scan`, `FSSBodySignals`, `SAASignalsFound`, `FSDTarget`, `Location`. |
| 158 `DIAGNOSTIC_ONLY` | **VERIFIED (derived)** | Not an enumerated list; it is the `roleOf` fallback. 272 − 109 − 5 = 158. |
| 504 semantic inventory entries | **UNRESOLVED** | No artifact, no code construct of this cardinality. Not reconstructable. |
| 36 semantic mechanisms | **UNRESOLVED** | Same. §8.4 defines a *new*, code-derived mechanism taxonomy instead. |
| 158 transition patterns | **UNRESOLVED** | Same. Note the collision with the derived `DIAGNOSTIC_ONLY` count of 158 — `INFERENCE`: likely coincidence, but it is a reason not to trust the figure. |

**Full coverage of the historical 504/36/158 inventories is therefore not claimed.** `target/audit/kairon-llm-situation-v2-transition-coverage.csv` re-derives coverage from the verifiable event space (272 events, 52 normalized types, 33 significant types, 8 state facets) using locally-minted `KV2-###` pattern IDs, with `artifact_evidence = MISSING_ARTIFACT` wherever a prior artifact would have been the source.

### 3.4 Additional counts re-derived from code

| Quantity | Value | Source |
| --- | --- | --- |
| Events implementing `LlmPresentableJournalEvent` | **119** | `observation/journal/event/**` |
| Presentable but in no selection role | **5** | `DockingGranted`, `DockingRequested`, `FuelScoop`, `LaunchDrone`, `MaterialCollected` |
| `NormalizedEventType` constants | **52** | `NormalizedEventType` |
| Graph-`SIGNIFICANT` event classes | **33** | `EventSignificancePolicy.SIGNIFICANT_TYPES` |
| Normalizer direct rules | **30** | `BehaviorEventNormalizer.directRules()` |
| `GameStateFacet` values | **8** | `GameStateFacet` |
| `SIGNIFICANT ∩ NEW_ELIGIBLE` | **23** | computed |
| `NEW_ELIGIBLE` that are not significant | **86** | computed |
| `SIGNIFICANT` that are `DIAGNOSTIC_ONLY` | **8** | computed |
| Test classes / `@Test` methods | **43 / 418** | `src/test` |
| Last suite result | 418 run, 0 failures, 0 errors, 1 skipped | `target/surefire-reports/` (2026-07-31 16:56) |

### 3.5 Runtime samples

`target/snapshot-model-input-examples.jsonl` (6 scenarios) and `target/observer-response-contract-examples.jsonl` (6 scenarios) were regenerated by the current suite and **are** valid current-schema evidence.

`var/*.jsonl` were checked and **rejected as evidence**: they carry no `traceSchemaVersion`, so they predate `kairon-turn-trace-v3`. `VERIFIED_CURRENT_BEHAVIOR`. They also contain zero Status-derived normalized types, which is expected — they are replay runs, and journal replay contains no Status snapshots — so they cannot be used to test the §10.4 question either way.

---

## 4. Verified current immutable data flow

### 4.1 Exact order for one `busSequence`

`VERIFIED_CURRENT_BEHAVIOR` — `ObservationProjectionCoordinator.project`:

```
requireIncreasingBusSequence(observation)                     // strictly monotonic, else IllegalStateException
  -> stateProjector.applyAndCapture(observation)              // -> CurrentGameStateProjection
       assert projection.busSequence() == observation.busSequence()
  -> applyGraph(observation, stateProjection)                 // -> BehaviorGraphApplyResult
       assert graphResult.busSequence() == observation.busSequence()
  -> captureBehaviorSituation(observation, currentState, graphResult)
  -> downstream.publish(new ProjectedObservation(...))
```

All of it runs on one non-daemon single-thread executor named `observation-projection`. This is the only sequential mutation boundary.

### 4.2 Correlation identity

`busSequence` is asserted **four** times for one observation: monotonic entry; state projection; graph result; and twice more in `ProjectedObservation`'s compact constructor (`trigger` vs `graphResult`, `trigger` vs `behaviorSituation`), which additionally requires `graphResult.equals(behaviorSituation.applyResult())`. `INFERENCE`: correlation identity is structurally enforced, not conventional.

### 4.3 Where previous and current state are fixed

`VERIFIED_CURRENT_BEHAVIOR` — `CurrentGameStateProjection` carries **three** snapshots plus the change set:

```java
public record CurrentGameStateProjection(
        long busSequence,
        CurrentGameStateSnapshot previousState,
        CurrentGameStateSnapshot currentState,
        CurrentGameStateSnapshot observationContext,
        CurrentGameStateChangeSet changes)
```

Its compact constructor recomputes `CurrentGameStateChangeSet.between(previousState, currentState)` and throws on mismatch — the change set is provably a function of the two snapshots.

### 4.4 The primary information-loss boundary

`VERIFIED_CURRENT_BEHAVIOR` — `ProjectedObservation` keeps only two of those four:

```java
public record ProjectedObservation(
        PublishedObservation<?> trigger,
        CurrentGameStateSnapshot currentState,     // kept
        CurrentGameStateChangeSet stateChanges,    // kept (facet names only)
        BehaviorGraphApplyResult graphResult,
        BehaviorSituationSnapshot behaviorSituation)
```

**`previousState` and `observationContext` are dropped at the projection boundary.** Everything downstream — including the entire LLM layer — is structurally incapable of computing a before/after delta. This is the single most consequential finding of the audit.

### 4.5 Late reads

`VERIFIED_CURRENT_BEHAVIOR`: none on the LLM path.

- `CurrentGameStateProjector.applyAndCapture` is `synchronized`, has no executor, future, callback, I/O or clock read, and returns fresh immutable records.
- `LlmSituationTurnFactory` is documented as, and verified to be, pure: it reads only `inputs`, never current state or graph services.
- The turn's authoritative envelope is `inputs.triggers().getLast()` — captured, not re-read.

One nuance worth recording: `snapshotFor(...)` intentionally reads projector state *after* mutation, so for `FSSBodySignals`/`SAASignalsFound` the `observationContext` reflects counts that same event just stored. That is a designed post-state, not a late read.

### 4.6 What belongs to the same post-projection moment

`VERIFIED_CURRENT_BEHAVIOR`, for the final NEW trigger of a batch: `currentState`, `stateChanges`, `graphResult`, `behaviorSituation` (including trajectory, active counts, cursor and predictions). All are captured before publication and never re-read.

`VERIFIED_CURRENT_BEHAVIOR`, and important: **state and graph context are taken from the last trigger only.** `LlmSituationTurnFactory.build` uses `inputs.triggers().getLast()` for `gameState(...)`, `behaviorSituation(...)` and all metadata. Intermediate triggers contribute only their own row in `triggers[]`.

---

## 5. Current information-loss boundaries

Ordered by decision impact.

| # | Loss | Evidence | Consequence for `SILENT`/`COMMENT` |
| --- | --- | --- | --- |
| L1 | **No before/after values.** `CurrentGameStateChangeSet` is `Set<GameStateFacet>` — 8 coarse names. | `CurrentGameStateChangeSet`, `GameStateFacet` | The model is told *that* `BODY` changed, never from what to what. It cannot judge novelty or magnitude. |
| L2 | **`previousState` dropped.** | `ProjectedObservation` vs `CurrentGameStateProjection` | L1 is not recoverable downstream at any cost. |
| L3 | **Meaning is prose-only.** All 272 events are `record X(RawJournalData raw)`. Typed values are extracted inside `llmPresentation()` and immediately concatenated into English. | e.g. `FSDJump`: `booleanValue(event.get("BoostUsed")).ifPresent(v -> facts.add(v ? "an FSD boost was used" : "no FSD boost was used"))` | Subject, operation, quantity, stage, completion and **negation** survive only as English substrings. |
| L4 | **Presentation is truncated mid-sentence under budget.** `shorten(...)` cuts at an arbitrary character and appends `…`. | `LlmSituationTurnFactory.shorten` | A negation or quantity can be severed. `presentationTruncated` flags it; the lost content is unrecoverable. |
| L5 | **`CONTEXT_ONLY` and `DIAGNOSTIC_ONLY` provenance is discarded entirely.** | `LlmJournalObserverSubscriber`: `case CONTEXT_ONLY, DIAGNOSTIC_ONLY -> { /* already included in a later NEW snapshot */ }` | The *effect* survives as a final value; *what caused it* does not. A `Scan` establishing `landable`/`planetClass` is invisible as an event. |
| L6 | **Status observations never reach the observer at all.** They are not `JournalEventObservation`, so they fall off the end of `onProjectedObservation`. | `LlmJournalObserverSubscriber` | Same as L5, plus §10.4. |
| L7 | **`observationContext` dropped**, so body facts an event is *about* (vs the body currently selected) are lost. | §4.4 | `SAASignalsFound` for a body the commander has not approached loses its body binding. |
| L8 | **Previous comments are bare strings.** `record PreviousComment(String text)`; coordinator memory is `Deque<String>`. | `LlmSituationTurn.PreviousComment`, `ObserverTurnCoordinator.deliveredComments` | No authority label, no turn linkage, no evidence linkage. Model output is presented indistinguishably from supplied fact. |
| L9 | **Prediction support is dropped.** Domain `NextEventPrediction` has 13 fields; `SituationNextEventPrediction` keeps 5; the model sees 4. | `NextEventPrediction` vs `LlmSituationTurn.Prediction` | `basis`, `globalProbability`, `contextSupport`, `contextKey`, `lastSeenAt` never reach the model. |
| L10 | **Truncated predictions no longer sum to 1.** Probabilities are validated to sum to 1.0 across *all* available predictions; the included subset is a prefix. | `BehaviorSituationSnapshot.requirePredictions` | Included mass is unstated, so remaining probability looks like zero. |
| L11 | **The graph cursor is not labelled as possibly stale.** | §10.3 | `currentEventType` may describe an older occurrence than the trigger. |
| L12 | **Raw vs normalized event type is conflated in the reader's mind.** `triggers[].eventType` is raw (`SupercruiseExit`); trajectory items are normalized (`SUPERCRUISE_EXIT`). Nothing says so. | `LlmSituationTurnFactory.canonicalEventType` vs `trajectory` | Many-to-one collapses are invisible: `LaunchFighter` and `LaunchSRV` both appear as `AUXILIARY_VEHICLE_LAUNCHED`. |

Confirmed on a real current-schema turn (`target/snapshot-model-input-examples.jsonl`, scenario `state-changing-trigger`) — six facets changed, zero values given:

```json
"changedFacets":["COMMANDER","FLIGHT","PRESENCE","SHIP","SYSTEM","VEHICLE"]
```

---

## 6. Field-level delta feasibility

### 6.1 Can it be a pure immutable projection?

**Yes — but only if computed where `previousState` still exists**, i.e. inside `CurrentGameStateProjector` / at the projection boundary. `previousState + currentState + observation + projection metadata → semantic field changes` is a pure function there. Downstream it is impossible (L2).

`DESIGN_PROPOSAL`: widen `ProjectedObservation` with a `CurrentGameStateDelta` computed by the projector. `CurrentGameStateChangeSet` stays untouched (other consumers and `CurrentGameStateProjectorTest` depend on it).

### 6.2 Change kinds — formal semantics and feasibility

Let `b` = before value, `a` = after value for one canonical field.

| Kind | Formal semantics | Required input | Feasibility | Ambiguity | Null semantics |
| --- | --- | --- | --- | --- | --- |
| `ESTABLISHED` | `b == null ∧ a != null` | `previousState`, `currentState` | **FEASIBLE** — pure | Enum fields never null (`commanderMode`, `flightMode`, `vehicleKind` are non-null with `UNKNOWN` sentinels), so for those `UNKNOWN → x` is the establishment signal, not `null → x`. Must be encoded per-field. | `b` serialized as `null` |
| `UPDATED` | `b != null ∧ a != null ∧ b ≠ a` | same | **FEASIBLE** — pure | none | both non-null |
| `CLEARED` | `b != null ∧ a == null` | same | **FEASIBLE** — pure | Verified real: `clearSelectedBody()` nulls `bodyId`/`bodyName`/`broadBodyType` on `SupercruiseEntry` and `LeaveBody`. Also `activeVehicleId` is nulled four ways. Distinguish from `x → UNKNOWN` for enum fields. | `a` serialized as `null` |
| `UNCHANGED` | `b == a` | same | **FEASIBLE** — pure | none | omitted from the delta list; not emitted per-field |
| `ACTIVATED_FROM_CONTEXT` | `a` became non-null **not** because this observation carried it, but because body selection moved and stored `BodyContext` re-hydrated | `previousState`, `currentState`, **and the projector's write-path knowledge** | **FEASIBLE ONLY INSIDE THE PROJECTOR** | See below | `b` may be `null` or a different body's value |

### 6.3 `ACTIVATED_FROM_CONTEXT` — why it needs the projector

`VERIFIED_CURRENT_BEHAVIOR`. `CurrentGameStateProjector` holds `private final Map<BodyKey, BodyContext> bodies = new TreeMap<>();`. Only three events ever write it — `Scan` (`updateBodyFromScan`), `FSSBodySignals` and `SAASignalsFound` (`updateBodySignals`). Every `snapshot()` re-reads it via `currentBodyContext()`, keyed on `(systemAddress, bodyId)`.

So `ApproachBody` — which writes only `bodyId`/`bodyName`/`broadBodyType` and touches nothing in `bodies` — causes `planetClass`, `landable`, `wasDiscovered`, `wasMapped`, `wasFootfalled`, `distanceFromArrivalLs`, and both signal counts to reappear from storage. The repository's own test proves it: after scanning body 83, approaching 84, then re-approaching 83 with an `ApproachBody` carrying **no** `PlanetClass`, `assertEquals("Rocky body", currentFromMatch.planetClass())`.

**Consequence:** a value-only comparison would report this as `ESTABLISHED` — telling the model "we just learned this body is a Rocky body", when in fact it was learned earlier and merely re-activated. That is exactly the novelty error v2 exists to prevent.

`DESIGN_PROPOSAL`: tag by **write path, not by value**. The projector marks body-sourced fields with `origin = STORED_CONTEXT` when they came from `currentBodyContext()` without this observation writing `bodies`, and `origin = OBSERVATION` when this observation wrote them.

**Ambiguity cases that must be handled explicitly:**

- An event both writes `bodies` *and* moves selection (`SAASignalsFound` does both). Origin is `OBSERVATION` for the fields it wrote, `STORED_CONTEXT` for the rest.
- A re-visited body whose stored value is *equal* to what a fresh observation would set. Value comparison cannot distinguish these; write-path tagging can.
- `biologicalSignalCount` / `geologicalSignalCount` can be reset to `0` (not `null`) by a `Signals` array lacking that type. `VERIFIED_CURRENT_BEHAVIOR`. `0` is a value, so this is `UPDATED`, not `CLEARED` — and it is a real hazard: a geology-only `SAASignalsFound` overwrites a prior biological count with `0`.

**Change kinds deliberately NOT proposed:** no `CONFIRMED`, `REFRESHED`, `CORRECTED` or `INVALIDATED`. None is reliably determinable — the projection has no notion of a fact being re-asserted with the same value versus never re-checked.

### 6.4 Scope

The delta covers the 25 components of `CurrentGameStateSnapshot`, grouped under the subject model of §9 rather than the 8 coarse facets. `deprecated bodyType()` is a compatibility scalar from `BodyTypeCompatibilityProjection` and is **not** a canonical field; v2 drops it in favour of `broadBodyType` + `planetClass` + `starType`.

---

## 7. Hidden-source provenance design

### 7.1 The problem, precisely

`VERIFIED_CURRENT_BEHAVIOR`. Between two NEW model-eligible observations, three classes of observation can mutate canonical state or the graph and leave no trace in `triggers[]`:

1. **`CONTEXT_ONLY`** (5 types: `Scan`, `FSSBodySignals`, `SAASignalsFound`, `FSDTarget`, `Location`) — explicitly dropped by the subscriber.
2. **`DIAGNOSTIC_ONLY`** (158 types) — dropped by the same branch. Eight of them are graph-**significant** (`DockingGranted`, `DockingRequested`, `FSSDiscoveryScan`, `FuelScoop`, `LaunchDrone`, `LaunchSRV`, `MaterialCollected`, `StartJump`), so they move the graph cursor while being invisible as events.
3. **Status snapshots** — never reach the subscriber at all, yet produce up to 3 graph occurrences per snapshot.

### 7.2 Options evaluated

| Criterion | 1. Pending delta accumulator | 2. Immutable projection history | 3. Enrichment in `ObserverTurnCoordinator` | 4. Enrichment at projection boundary |
| --- | --- | --- | --- | --- |
| Ownership | ambiguous — who drains it? | ambiguous | **coordinator** — already owns turn boundaries | projection coordinator — does **not** know turn boundaries |
| Lifetime | until drained | unbounded unless trimmed | exactly one turn | undefined |
| Ordering | bus order | bus order | **bus order, drained at `startTurn()`** | bus order |
| Concurrency | needs its own guard | needs its own guard | **none — single-thread `observer-coordinator` executor** | none |
| Immutability | holds immutable envelopes | immutable | **immutable envelopes** | immutable |
| Memory bounds | needs an explicit cap | **unbounded risk** | **bounded ring + explicit omitted count** | would have to buffer indefinitely |
| Batching interaction | must align with batch drain | none | **drained at the same instant as triggers** | cannot align |
| Duplicate risk | if drained twice | low | **none — drained once, with triggers** | high |
| Loss risk | silent overflow | none | **bounded, reported** | high |
| Replay determinism | yes | yes | **yes — bus order is deterministic** | yes |
| Late-read risk | none | none | **none** | none |
| Testability | medium | low | **high — coordinator is already unit-tested** | low |

Option 5 was considered and rejected: deriving hidden effects retroactively by diffing `currentState` between consecutive NEW triggers. It cannot work — the earlier NEW trigger's `currentState` is available, but attributing the diff to a *cause* is exactly the provenance that was discarded, and multiple hidden observations collapse into one indistinguishable diff.

### 7.3 Recommendation — Option 3

**`ObserverTurnCoordinator` owns hidden-source provenance**, fed by the subscriber forwarding non-NEW projections as a distinct command.

`DESIGN_PROPOSAL`, shape only (not an implementation):

- `LlmJournalObserverSubscriber` stops discarding `CONTEXT_ONLY` / `DIAGNOSTIC_ONLY` and stops ignoring `StatusSnapshotObservation`; it posts `ObserverCommand.RecordContextEffect(projectedObservation)`.
- The coordinator keeps a bounded `Deque<ContextEffect>` alongside `newQueue`, capped, counting overflow.
- `startTurn()` drains it in the same critical section that drains `newQueue`, so the accumulator's contents are exactly "everything between the previous turn and this one".
- Entries whose `busSequence` exceeds the last trigger's are retained for the next turn, preserving the "captured after the FINAL NEW trigger" rule.

**This does not change event selection.** `LlmJournalEventSelection.roleOf` and the 109/5/158 role assignment are untouched; only what the coordinator *does* with already-classified non-NEW effects changes. That distinction matters for constraint 13 and is stated explicitly because it is easy to misread.

**Note the tension with `ObservationBus` purity:** the effects travel on `ProjectedObservationBus` (already carrying post-projection envelopes), not `ObservationBus`. No LLM decision, comment, or internal command is published anywhere. `INFERENCE`: this respects ADR-0001/ADR-0006.

---

## 8. Structured trigger fact architecture

### 8.1 Where structured facts must materialise

Four candidate boundaries were assessed against the evidence.

| Boundary | Verdict | Reason |
| --- | --- | --- |
| 1. Journal event layer (inside the 272 records) | **Rejected** | ADR-0002 and `CLAUDE.md` state typed journal records are transport identities around exact `RawJournalData`, **not** a domain model. Adding semantic fields to 272 records makes transport identity carry domain meaning and multiplies the change surface by 272. |
| 2. **Model-independent semantic adapter** | **CHOSEN** | Reusable by LLM, GUI and diagnostics; keeps the LLM layer pure; mirrors a pattern already proven in this codebase (§8.2). |
| 3. LLM projection layer (`LlmSituationTurnFactory`) | **Rejected** | Would make semantics model-specific and unavailable to any other consumer, and would violate the factory's verified purity contract by requiring raw-JSON parsing at turn-build time. |
| 4. Behavior normalizer | **Rejected as the sole home** | It is graph-scoped: only 33 significant types, 30 direct rules, and a vocabulary designed for transition topology, not for commentary. It is however the *template* — see §8.2. |

### 8.2 The precedent that makes this credible

`VERIFIED_CURRENT_BEHAVIOR`. `BehaviorEventNormalizer` already does exactly the required job for its own scope:

```java
private static void register(
        Map<Class<? extends JournalEventObservation>, DirectRule> rules,
        Class<? extends JournalEventObservation> payloadType,
        NormalizedEventType eventType,
        String... attributeNames)
```

It produces `NormalizedBehaviorEvent(eventType, timestamp, Map<String, JsonNode> attributes, originalEventName)` — a whitelisted, typed-ish attribute projection extracted from raw JSON at a model-independent boundary, with duplicate registration a hard init failure.

`INFERENCE`: the v2 semantic adapter is the same mechanism with a richer target type and full-event-space coverage rather than 30 rules. This is not a speculative architecture; it is a generalisation of working code.

`VERIFIED_AVAILABLE_DATA`: `RawJournalData` retains both `rawJson` and `parsedJsonObject` with byte-exact round-trip verification and defensive `deepCopy()` on every access. **Nothing is discarded** — a later adapter can read any field. The only cost is a tree copy per access.

### 8.3 Required primitives

`DESIGN_PROPOSAL` — `SemanticEventFact`, sufficient to express the mandated axes:

| Primitive | Type | Purpose |
| --- | --- | --- |
| `subject` | `SubjectRef` | who/what the fact is about (§9 subject ids) |
| `actor` | `SubjectRef?` | who performed it, when distinct from subject |
| `object` | `EntityRef?` | what it was done to |
| `operation` | `String` (controlled) | the verb: `ENTERED`, `EXITED`, `ACQUIRED`, `LOST`, `COMPLETED`, `FAILED`, `DENIED`, … |
| `qualifiers` | `Map<String,String>` ordered | bounded, controlled modifiers |
| `identity` | `EntityRef?` | stable id of the thing (`MissionID`, `MarketID`, `BodyID`) |
| `quantity` | `Quantity?` (`value` + `unit`) | typed, never prose |
| `processStage` | enum? `START \| PROGRESS \| FINAL \| NOT_APPLICABLE` | e.g. `ScanOrganic` `Log`/`Sample`/`Analyse` |
| `completion` | `Boolean?` | `true`/`false`/`null` (unknown) — never inferred |
| `negation` | `Boolean?` | explicit negative assertion (`BoostUsed=false`) |
| `relationship` | `String?` | link to another subject/entity |
| `assertionSource` | enum `REPORTED \| DERIVED` | reported by the game vs derived by Kairon |

`presentation` **survives** as a human-readable summary. `DESIGN_PROPOSAL`: it is demoted to a *secondary* field and is never the sole carrier of critical meaning — which is exactly the `coverage_status` rule applied in the transition-coverage CSV.

### 8.4 Full event-space strategy — not hardcoded replay anchors

The design must not degenerate into adapters for the seven replay anchors. `DESIGN_PROPOSAL`, a three-tier strategy that accounts for all 272 events:

- **Tier A — sourced adapters (target: the 109 `NEW_ELIGIBLE` + 5 `CONTEXT_ONLY` = 114 selected types).** Explicit per-type rules, exactly as `LlmPresentableJournalEvent` is already required for all 114 (enforced at class-init: *"Active LLM event type lacks sourced presentation"*). The same guard shape extends to semantic facts, so coverage is compile-time enforced rather than aspirational.
- **Tier B — generic structural adapter (the remaining 158 `DIAGNOSTIC_ONLY`).** A default projection producing `subject` + `operation=UNSPECIFIED` + identity/quantity where recognisable field names appear. These types never enter model input today; the tier exists so hidden-source provenance (§7) can name them without a bespoke rule each.
- **Tier C — negative space.** Types with no derivable fact emit `null` and are counted, never silently skipped.

**Ordering matters for correctness here**: `MissionCompleted` / `MissionFailed` / `MissionAbandoned` and the five `Docking*` outcomes are separate Java types — `VERIFIED_CURRENT_BEHAVIOR`, the class identity *is* the polarity, and there is no `boolean succeeded()` anywhere. Tier A must map class identity onto `operation` + `completion` + `negation` rather than re-deriving polarity from prose.

---

## 9. Minimal subject model

### 9.1 Subjects

`DESIGN_PROPOSAL`. Nine subjects, deliberately separated so that legitimate combinations cannot read as contradictions.

| Subject id | Identity | Current state | Authority | Nullable | Unresolved relationships |
| --- | --- | --- | --- | --- | --- |
| `commander` | `fid` | — | canonical | `fid` | — |
| `commanderPresence` | — | `SHIP \| SRV \| ON_FOOT \| UNKNOWN` | canonical | never null (`UNKNOWN`) | which *vehicle* is occupied |
| `primaryShip` | `shipId`, `shipType`, `shipName`, `loadoutHash` | — | canonical | all | — |
| `navigationContext` | — | `flightMode` | canonical, **neutral** | never null (`UNKNOWN`) | whether the value describes the vessel or the commander (OQ-2, **resolved in Phase B as unproven** — §23.3) |
| `associatedVehicle` | `vehicleId`, `kind` | `deployed?` | **partial** | all | occupancy (OQ-3) |
| `occupiedVehicle` | `vehicleId`, `kind` | — | **unresolved** | **always null today** | OQ-3 |
| `currentSystem` | `systemAddress`, `systemName` | — | canonical | both | — |
| `currentLocation` | — | docked/landed/supercruise via `primaryShip.flightState` | canonical | — | — |
| `currentBody` | `bodyId`, `bodyName` | `broadBodyType`, `planetClass`, `starType`, `landable`, `wasDiscovered`, `wasMapped`, `wasFootfalled`, `distanceFromArrivalLs`, signal counts | canonical, **with per-field origin** (§6.3) | all | — |
| `biologicalSampling` | — | `active`, `bodyHasBiology`, `biologicalSignalCount` | canonical | all | genus/species not projected |

### 9.2 The mandated combination

`commanderPresence = ON_FOOT`, `primaryShip.flightState = LANDED`, `associatedVehicle.kind = SRV` is representable **without contradiction** in this model, because presence, ship flight state, and vehicle association are three independent subjects.

`VERIFIED_CURRENT_BEHAVIOR` that the inputs exist: after `Touchdown` → `LaunchSRV` → `Disembark`, the projector holds `flightMode = LANDED` (set by `Touchdown`, never revised by `LaunchSRV`/`Disembark`/`Embark` — none of those three touch `flightMode`), `commanderMode = ON_FOOT`, `vehicleKind = SRV`, `activeVehicleId = <srv id>`.

In v1 this same combination reads as a contradiction, because `flightMode` and `vehicleKind` sit in one flat `activity` object with no subject separation.

### 9.3 What must stay unresolved — and why

`VERIFIED_CURRENT_BEHAVIOR`. `CurrentGameStateSnapshot` has exactly **one** `activeVehicleId` and **one** `vehicleKind`. There is no set of deployed vehicles, no ship-vs-current-vehicle pair, no occupancy flag.

- **Associated ≠ occupied is NOT distinguishable.** After `Disembark` from an SRV the projector keeps `activeVehicleId` = the SRV and sets `vehicleKind = SRV` while `commanderMode = ON_FOOT`. That *accidentally* encodes "SRV exists, commander not in it" — but only by convention, and nothing separates it from a malformed state. `occupiedVehicle` is therefore **always `null`** in v2 until the projection distinguishes them.
- **Fighter presence and telepresence are NOT establishable.** `LaunchFighter` routes to `updateVehicleLaunch(raw, VEHICLE_UNKNOWN)`, overwriting `activeVehicleId` with the fighter's id and `vehicleKind` with `UNKNOWN`, destroying the fact that the commander is still in the ship. `PlayerControlled` is read but discarded by a `NOMAD || SRV` guard. **`DockFighter` is not handled at all** — not imported, no branch in `applyEvent` — so after `LaunchFighter` → `DockFighter` the state stays `vehicleKind=UNKNOWN, activeVehicleId=<fighter id>` until something else overwrites it. v2 emits `null` + `unresolved:["FIGHTER_OCCUPANCY"]`.
- **`VehicleSwitch` is not handled** either — catalogued, never projected.
- **Taxi and multicrew have zero projector handling.** The raw fields exist and are read *for prose only* (`Disembark`, `Embark`, `Docked`, `FSDJump`, `Liftoff` all read `Taxi`/`Multicrew`), but `updateDisembark`/`updateEmbark` never look at them. Disembarking from a taxi or another player's multicrew ship mutates canonical ship state identically to disembarking from one's own ship. v2 emits `null` + `unresolved:["TAXI_CONTEXT","MULTICREW_CONTEXT"]` and **never guesses**.

---

## 10. Graph vocabulary and causal-safety semantics

### 10.1 The five distinct vocabularies

`VERIFIED_CURRENT_BEHAVIOR`. These are routinely conflated and must be separated in v2:

| Vocabulary | Cardinality | Example | Where |
| --- | --- | --- | --- |
| Raw journal event type | 272 | `SupercruiseExit` | `raw().optionalEventType()` |
| Structured trigger semantics | — | `operation=EXITED` | **does not exist** (§8) |
| Normalized graph event type | 52 constants (open-ended `record`) | `SUPERCRUISE_EXIT` | `NormalizedEventType` |
| Significant graph occurrence | 33 classes | — | `EventSignificancePolicy.SIGNIFICANT_TYPES` |
| Graph cursor / trajectory / active counts | — | — | `GraphCursor`, `ActiveEpisodeSituation` |

### 10.2 Raw ≠ normalized — verified many-to-one

- `LaunchFighter` **and** `LaunchSRV` → `AUXILIARY_VEHICLE_LAUNCHED` (the only fan-in among the 30 direct rules).
- `FSDJump`, `Location`, **and** a synthetic ship-switch root (`originalEventName = "ShipSwitch"`) → `SYSTEM_ENTRY` — three-to-one, and the third is not a journal event at all.
- `LaunchDrone` with an unrecognised `Type` → `LIMPET_LAUNCHED` via a `default ->` arm.

Fan-out also occurs: `ScanOrganic` → 3 types on `ScanType`; `StartJump` → 2 on `JumpType`; `LaunchDrone` → 9 on `Type`.

`VERIFIED_CURRENT_BEHAVIOR`: two constants — `FSS_BODY_SIGNALS_FOUND` and `SAA_SCAN_COMPLETE` — are **dead in production**; their raw counterparts classify as `CONTEXT`, never `SIGNIFICANT`, so nothing emits them.

### 10.3 The cursor may not describe the current trigger

`VERIFIED_CURRENT_BEHAVIOR`. The cursor moves only on an accepted occurrence (`recordNormalizedOccurrence` → `graph.withEpisode(activeEpisode).withCursor(cursor)`). It does **not** move on the `NOISE`/`CONTEXT`/`BOUNDARY` early return, the no-graph-id return, the no-active-episode return, projection-policy suppression, or `onNotApplicable`.

`captureSituation` still returns a full situation in that case, labelled `captureStatus = UNCHANGED`.

Concrete failure shape, `INFERENCE` from the verified paths: landing gear deploys (Status → cursor = `LANDING_GEAR_DEPLOYED`), then a `Bounty` fires. `Bounty` is `NEW_ELIGIBLE` (a turn starts) but not significant → cursor unmoved. The model receives `currentEventType: "LANDING_GEAR_DEPLOYED"` and predictions conditioned on landing gear, while commenting on a bounty.

Scale of the exposure: **86 of 109 `NEW_ELIGIBLE` types are not graph-significant**, and only `FSDJump` among them produces an occurrence (as a `SYSTEM_ENTRY` root) — so **85 of 109** trigger a turn while producing no occurrence at all.

`DESIGN_PROPOSAL`: v2 states this explicitly with `graphContext.cursor.matchesFinalTrigger: boolean` and `graphContext.cursor.source: JOURNAL | STATUS | SYNTHETIC`.

### 10.4 Status-derived occurrences — a verified contradiction with `CLAUDE.md`

`StatusStateDeltaAdapter` derives exactly six normalized types — `FSS_MODE_ENTERED/EXITED`, `SAA_MODE_ENTERED/EXITED`, `LANDING_GEAR_DEPLOYED/RETRACTED` — from GUI focus and bit 4 of `Flags`, max 3 per snapshot, each with `originalEventName = "Status"`. The six-occurrence claim is **confirmed**.

The claim that they *"never enter the LLM observer, prompt, batch, or trace"* is **not confirmed**. Verified by direct reading:

1. `BehaviorGraphObservationProcessor` routes Status to `graphService.onStatusDeltas(...)`.
2. That calls `recordNormalizedOccurrence(...)` — **the same method journal events use**.
3. `recordNormalizedOccurrence` appends to `activeEpisode` and executes `graph.withEpisode(activeEpisode).withCursor(cursor)`.
4. `captureSituation` builds the trajectory from `active.timeline().stream()` **unfiltered**.
5. `LlmSituationTurnFactory` maps `occurrence.eventType().value()` into `trajectory[]`, `activeEventCounts[]`, and `currentEventType`.
6. `TransitionProbabilityCalculator.predict` keys entirely off `cursor.eventType()`.

**Accurate scoped statement:** Status-derived occurrences never become a *trigger* and never contribute an event *presentation* — but their normalized type names are visible to the model through the graph situation, and can determine the entire `likelyNext` block.

This could not be settled from `var/` traces: those are replay runs, and journal replay contains no Status snapshots by design (ADR-0011), so the six types are necessarily absent there. The contradiction is live-mode-only. **OQ-1.**

### 10.5 Trajectory is temporal-by-acceptance, and explicitly not causal

`VERIFIED_CURRENT_BEHAVIOR` — `EventOccurrence.EPISODE_ORDER`:

> *"Source-local sequences from independently updated files cannot be compared with each other. The episode sequence therefore preserves the order in which the graph projection accepted journal and status facts."*

This is **stronger than "not causal": it is not even guaranteed chronological.** `episodeSequence` is assigned as `activeEpisode.timeline().size()` — bus-arrival order — and `timestamp` is only the second comparator key. A separate `CHRONOLOGICAL_ORDER` comparator exists precisely because the two differ, and it is never used in trajectory construction.

Every consecutive pair becomes an edge unconditionally, with no causality test (`OccurrenceTransition`: *"One historical transition between adjacent occurrences in one episode"*).

`UNRESOLVED`: no file states an explicit negative ("the trajectory is not causal"). The conclusion is `INFERENCE` from the affirmative ordering contract plus the absence of any causal construct.

`DESIGN_PROPOSAL`: v2 carries `graphContext.trajectory.ordering: "ACCEPTANCE"` and `graphContext.trajectory.causal: false` as explicit fields, plus a scope note that the trajectory is a heavily filtered projection (33 of 272 types, minus repeat suppression, plus Status deltas) and is scoped to the **active system episode only** — episodes end on `NEXT_SYSTEM`, `SHUTDOWN`, `SHIP_SWITCH`, `REPLAY_COMPLETED`, `SOURCE_CLOSED`.

### 10.6 Active counts

Counts are over **normalized active-episode occurrences**, validated to equal counts re-derived from the trajectory (`ActiveEpisodeSituation.immutableCounts`). They are not raw journal event counts and not global counts. v2 names them `activeEpisodeOccurrenceCounts` to remove the ambiguity.

---

## 11. Prediction support semantics

### 11.1 What exists, and what the model currently sees

| Field | Domain `NextEventPrediction` | `SituationNextEventPrediction` | v1 model-facing | v2 |
| --- | --- | --- | --- | --- |
| `predictedEventType` | ✓ | ✓ | ✓ | ✓ |
| `probability` | ✓ | ✓ | ✓ | ✓ |
| `globalProbability` | ✓ | ✗ | ✗ | **✓ (restored)** |
| `effectiveWeight` | ✓ | ✓ | ✓ | **renamed `decayedWeight`, diagnostic** |
| `rawTransitionCount` / `observedTransitionCount` | ✓ | ✓ | ✓ | ✓ |
| `contextKey` | ✓ | ✗ | ✗ | diagnostic only |
| `contextSupport` | ✓ | ✗ | ✗ | **✓ (restored)** |
| `basis` | ✓ | ✗ | ✗ | **✓ (restored — critical)** |
| `lastSeenAt` | ✓ | ✗ | ✗ | ✗ (not decision-relevant) |
| `graphId`/`episodeId`/`currentOccurrenceId`/`currentEventType` | ✓ | partial | ✗ | correlation only |

### 11.2 The four concepts must not be conflated

`VERIFIED_CURRENT_BEHAVIOR`, from `TransitionProbabilityCalculator.predict`:

- **`probability`** = `effectiveWeight / scoreTotal`. A normalised share. Validated to sum to 1.0 over all available predictions.
- **`support`** = evidence mass. Two distinct measures exist: `observedTransitionCount` (`edge.globalCounter().rawCount()` — an integer count) and `contextSupport` (the sum of half-life-decayed counts in this exact `ContextKey` bucket).
- **`basis`** = `GLOBAL` or `CONTEXTUAL`, assigned as `contextual ? CONTEXTUAL : GLOBAL` where `contextual = contextSupport > 0.0`. **A single decayed observation in a bucket flips the basis.**
- **`confidence`** — **does not exist in the domain model.** Nothing computes it.

**`effectiveWeight` is not confidence.** Verified: `contextual ? contextWeight + contextPriorStrength * globalProbability : globalWeight`, where the weights are `counter.valueAt(evaluationTime, halfLife)`. It is a half-life-decayed evidence weight plus a prior. v2 renames it `decayedWeight` and marks it diagnostic, precisely so it cannot be read as confidence.

### 11.3 The `probability = 1.0, count = 1` trap

`VERIFIED_CURRENT_BEHAVIOR`. With a single outgoing edge under `GLOBAL` basis: `effectiveWeight = globalWeight`, `scoreTotal = globalTotal = globalWeight`, therefore `probability = 1.0` while `rawTransitionCount = 1`. One observation renders as certainty.

`DESIGN_PROPOSAL` — three mitigations, all deterministic:

1. **`observedTransitionCount` is mandatory and model-facing.** The factual count is reported directly. A qualitative band over it (`SINGLE_OBSERVATION` / `SPARSE` / `ESTABLISHED`) was proposed in the Phase A draft and is **withdrawn**: nothing in the repository establishes those thresholds, so materialising them would present an invented classification as domain truth. See §23.4.
2. **`basis` is mandatory and model-facing**, so `GLOBAL` at `observedTransitionCount = 1` is legible as "one prior observation, no context match".
3. **`includedProbabilityMass`**, because probabilities sum to 1.0 only across *all* available predictions while the model sees a prefix (L10). Truncation stops looking like zero probability.

### 11.4 Priority rule

`DESIGN_PROPOSAL`, stated in the contract and enforced in the prompt: **explicit trigger facts and explicit negations outrank predictions.** A prediction may never be used to assert that something happened, and `negation = true` on a trigger fact overrides any prediction implying the opposite.

Additional constraint worth surfacing to the model: `contextKey` is non-`EMPTY` for only **2 of 52** normalized types (`SAA_SIGNALS_FOUND`, `TOUCHDOWN`). For the other 50, contextual prediction is structurally unavailable and `basis` is always `GLOBAL`.

---

## 12. Previous-comment memory semantics

### 12.1 What is lost today

`VERIFIED_CURRENT_BEHAVIOR`:

| Datum | Stored? | Where it exists at delivery time |
| --- | --- | --- |
| Comment text | ✓ | `deliveredComments` (`Deque<String>`, cap 3) |
| Count | 3 | `PREVIOUS_COMMENT_LIMIT = 3` |
| "Successfully delivered" | ✓ (gate only) | `terminalDelivery.deliveredForHistory()` — only then is it appended |
| Source turn sequence | **✗ lost** | `ActiveTurn.turnSequence` exists at that moment |
| Evidence bus sequences | **✗ lost** | `validated.evidenceTriggerBusSequences()` exists at that moment |
| Topic / entity / process milestone | **✗ never derived** | — |
| Authority label | **✗ none** | — |

`VERIFIED_CURRENT_BEHAVIOR`: output really is delivery — the memory updates only inside `completeCommentDelivery` when `deliveredForHistory()` is true, and speech completion is part of that result.

**Can model output return next turn without an authority label? Yes.** `record PreviousComment(String text)` carries a bare string; the prompt says only *"previousComments contains up to three comments that were actually delivered."* Nothing marks it as the model's own prior output rather than supplied fact. The prompt does instruct treating `previousComments` as untrusted data, which mitigates injection but not authority confusion.

### 12.2 Proposed structure

`DESIGN_PROPOSAL` — purpose-limited to **repetition suppression, phrasing continuity, and awareness of what was already said.** Never for establishing gameplay facts.

```json
{
  "text": "…",
  "authority": "PREVIOUS_MODEL_OUTPUT",
  "nonAuthoritative": true,
  "turnSequence": 41,
  "evidenceTriggerBusSequences": [1042, 1043]
}
```

`turnSequence` and `evidenceTriggerBusSequences` are `VERIFIED_AVAILABLE_DATA` — both are in scope at the exact moment the comment is appended; they are simply not retained.

`authority` is a single-valued enum today (`PREVIOUS_MODEL_OUTPUT`) with `nonAuthoritative: true` always set. The redundancy is deliberate: a reader scanning the JSON sees the warning without needing to know the enum's meaning. **No topic/entity/milestone extraction is proposed** — that would require semantic analysis of generated text, which is exactly the kind of pre-model interpretation the project forbids.

---

## 13. Exact v2 JSON contract

`schemaVersion` = `"kairon-llm-situation-v2"`.

### 13.1 Top level

| Field | Type | Card. | Null | Notes |
| --- | --- | --- | --- | --- |
| `schemaVersion` | string | 1 | never | fixed literal |
| `turn` | object | 1 | never | §13.2 |
| `triggers` | array\<Trigger\> | 1..8 | never | ascending `busSequence`, unique |
| `stateChanges` | object | 1 | never | §13.4 — **new in v2** |
| `currentState` | object | 1 | never | §13.5 — subject-oriented |
| `graphContext` | object | 1 | never | §13.6 |
| `predictions` | object | 1 | never | §13.7 |
| `previousComments` | array\<PreviousComment\> | 0..3 | never (may be `[]`) | §13.8 |
| `truncation` | object | 1 | never | §13.9 — **all truncation, one place** |

**Naming deviations from the brief, each justified:** `turn` replaces `metadata` (it is turn identity, not metadata about the document); `currentState` replaces `stateAfterBatch` (the subject model makes "after batch" redundant, and `turn.capturedAfterBusSequence` states it precisely); `graphContext` replaces `situationAfterBatch` (it is graph context, and "situation" collided with the whole document's name); `predictions` is promoted to top level from inside the graph situation, because §11 requires it to be read as a separate epistemic category rather than as graph fact.

### 13.2 `turn`

```
firstTriggerBusSequence  long    ≥1
lastTriggerBusSequence   long    ≥ first
triggerCount             int     1..8
firstTriggerTimestamp    string  ISO-8601 instant
lastTriggerTimestamp     string  ISO-8601 instant
capturedAfterBusSequence long    == lastTriggerBusSequence  (explicit capture point)
captureMode              enum    LIVE | REPLAY | BOOTSTRAP
sourceKind               string? null when technical metadata is dropped under budget
graphApplyStatus         enum    APPLIED|NOT_APPLICABLE|DISABLED|NO_GRAPH_ID|FAILED
behaviorCaptureStatus    enum    AVAILABLE|UNCHANGED|GRAPH_DISABLED|NO_GRAPH_ID|
                                 NO_ACTIVE_GRAPH|NO_ACTIVE_EPISODE|SNAPSHOT_FAILED|INCONSISTENT
budgetDegraded           bool
```

### 13.3 `Trigger`

```
busSequence          long     ≥1
rawEventType         string   e.g. "SupercruiseExit"        [model-facing]
normalizedEventType  string?  e.g. "SUPERCRUISE_EXIT"; null when not graph-significant
timestamp            string   ISO-8601
relation             enum     STATE_AND_GRAPH_CHANGED|STATE_CHANGED|GRAPH_CHANGED|
                              TRIGGER_ONLY|SITUATION_UNAVAILABLE|PROJECTION_DEGRADED
fact                 object?  SemanticEventFact (§8.3); null => no adapter (Tier C)
presentation         string   human-readable summary, SECONDARY to `fact`
presentationTruncated bool
changedSubjects      array<string>  subject ids changed by THIS trigger, sorted
```

`SemanticEventFact`:

```
subject       string                     subject id (§9)
actor         string?                    subject id
object        object?  {kind,id?,name?}
operation     string                     controlled vocabulary
identity      object?  {kind,id}
quantity      object?  {value:number, unit:string}
qualifiers    object   ordered string->string, may be {}
processStage  enum     START|PROGRESS|FINAL|NOT_APPLICABLE
completion    bool?    null = unknown, never inferred
negation      bool?    true = explicit negative assertion
relationship  string?
assertionSource enum   REPORTED|DERIVED
```

**`rawEventType` and `normalizedEventType` are both present and both named**, resolving L12. When they differ or collapse many-to-one, the model can see it.

### 13.4 `stateChanges` — the core addition

```
capturedAfterBusSequence long
items                    array<StateChange>   sorted by (subject, field), may be []
hiddenSourceEffects      object               §13.4.2
```

`StateChange`:

```
subject     string   subject id
field       string   canonical field name
changeKind  enum     ESTABLISHED|UPDATED|CLEARED|ACTIVATED_FROM_CONTEXT
before      any?     explicit JSON null when absent
after       any?     explicit JSON null when absent
origin      enum     OBSERVATION|STORED_CONTEXT
sourceBusSequence long?   which observation caused it; null if not attributable
sourceRole  enum?    NEW|CONTEXT_ONLY|DIAGNOSTIC_ONLY|STATUS
```

`UNCHANGED` is **never emitted** — absence means unchanged. This keeps the array proportional to actual change.

#### 13.4.2 `hiddenSourceEffects`

```
count            int    number of non-NEW observations that changed state or graph since the previous turn
omittedCount     int    dropped by the accumulator cap (explicit, never silent)
byRole           object {CONTEXT_ONLY:int, DIAGNOSTIC_ONLY:int, STATUS:int}
items            array<HiddenEffect>   bounded, ascending busSequence
```

`HiddenEffect`: `{busSequence, rawEventType|"Status", sourceRole, changedSubjects[], normalizedEventType?}`.

### 13.5 `currentState`

Subject-oriented (§9). Every subject object may carry `unresolved: [string]`.

```json
{
  "commander":         {"fid": "F-LLM"},
  "commanderPresence": {"mode": "ON_FOOT"},
  "primaryShip":       {"shipId": 42, "shipType": "krait_mkii", "shipName": "Example Ship"},
  "navigationContext": {"flightMode": "LANDED"},
  "associatedVehicle": {"vehicleId": 7, "kind": "SRV", "unresolved": ["OCCUPANCY"]},
  "occupiedVehicle":   null,
  "currentSystem":     {"systemAddress": 7101, "systemName": "Example A"},
  "currentBody":       {"bodyId": null, "bodyName": null, "broadBodyType": null,
                        "planetClass": null, "starType": null, "landable": null,
                        "wasDiscovered": null, "wasMapped": null, "wasFootfalled": null,
                        "distanceFromArrivalLs": null,
                        "biologicalSignalCount": null, "geologicalSignalCount": null},
  "biologicalSampling":{"active": null, "bodyHasBiology": null}
}
```

`commanderPresence.mode` and `navigationContext.flightMode` are never null (`UNKNOWN` sentinel). Everything else is nullable and `null` means **not known** — never guessed. The deprecated flat `bodyType` scalar is dropped.

### 13.6 `graphContext`

```
available          bool
captureStatus      enum   (as §13.2)
owner              object?  {commanderFid, shipId}
graphRevision      long?
topologyRevision   long?
episode            object?  {episodeId, systemAddress, systemName, occurrenceCount}
cursor             object?  {normalizedEventType, episodeSequence,
                             source: JOURNAL|STATUS|SYNTHETIC,
                             matchesFinalTrigger: bool}
trajectory         object   {ordering:"ACCEPTANCE", causal:false, scope:"ACTIVE_EPISODE",
                             totalOccurrenceCount, includedOccurrenceCount,
                             omittedOccurrenceCount, items:[{episodeSequence,
                             normalizedEventType, current}]}
activeEpisodeOccurrenceCounts object {distinctEventTypeCount, includedEventTypeCount,
                             omittedEventTypeCount, items:[{normalizedEventType,count}]}
```

`cursor.matchesFinalTrigger` and `cursor.source` are the fixes for §10.3 and §10.4. `trajectory.causal: false` and `ordering: "ACCEPTANCE"` are constants — stated, not implied.

### 13.7 `predictions`

```
availableCount          int
includedCount           int
includedProbabilityMass number   0..1, sum of included probabilities
basisAvailable          bool     false when contextKey is EMPTY for this cursor type
items                   array<Prediction>   domain order preserved
```

`Prediction`:

```
predictedEventType   string
probability          number  0..1                       [model-facing]
basis                enum    GLOBAL|CONTEXTUAL          [model-facing]
observedTransitionCount long   >= 1, the factual count  [model-facing]
globalProbability    number  0..1                       [model-facing]
contextSupport       number  ≥0                         [diagnostic]
decayedWeight        number? ≥0, null under budget      [diagnostic]
```

Ordering: probability descending, then `predictedEventType` ascending — preserved from the domain to keep the existing validation.

### 13.8 `previousComments`

Per §12.2. Ordered oldest → newest. `[]` when none.

### 13.9 `truncation`

All truncation in one place, so it is never hidden:

```
budgetDegraded            bool
serializedCharacterCount  int
maxSerializedCharacters   int
trajectoryOmitted         long
activeEventTypesOmitted   int
predictionsOmitted        int
hiddenEffectsOmitted      int
presentationsTruncated    int
stateChangesOmitted       int
technicalMetadataDropped  bool
decayedWeightsDropped     bool
```

### 13.10 Full synthetic example

> **ILLUSTRATIVE SYNTHETIC EXAMPLE — hand-authored for this design document. This is NOT a real runtime capture.**

```json
{
  "schemaVersion": "kairon-llm-situation-v2",
  "turn": {
    "firstTriggerBusSequence": 1042, "lastTriggerBusSequence": 1044, "triggerCount": 2,
    "firstTriggerTimestamp": "2026-07-30T14:00:01Z", "lastTriggerTimestamp": "2026-07-30T14:00:06Z",
    "capturedAfterBusSequence": 1044, "captureMode": "LIVE", "sourceKind": "journal-tail",
    "graphApplyStatus": "APPLIED", "behaviorCaptureStatus": "AVAILABLE", "budgetDegraded": false
  },
  "triggers": [
    {
      "busSequence": 1042, "rawEventType": "ApproachBody", "normalizedEventType": "APPROACH_BODY",
      "timestamp": "2026-07-30T14:00:01Z", "relation": "STATE_AND_GRAPH_CHANGED",
      "fact": {
        "subject": "currentBody", "actor": "commander",
        "object": {"kind": "BODY", "id": "83", "name": "Synthetic 3 a"},
        "operation": "APPROACHED", "identity": {"kind": "BODY_ID", "id": "83"},
        "quantity": null, "qualifiers": {"system": "Synthetic Alpha"},
        "processStage": "NOT_APPLICABLE", "completion": null, "negation": null,
        "relationship": null, "assertionSource": "REPORTED"
      },
      "presentation": "The player approached body “Synthetic 3 a” in system “Synthetic Alpha”.",
      "presentationTruncated": false,
      "changedSubjects": ["currentBody"]
    },
    {
      "busSequence": 1044, "rawEventType": "ScanOrganic", "normalizedEventType": "SCAN_ORGANIC_ANALYSE",
      "timestamp": "2026-07-30T14:00:06Z", "relation": "STATE_AND_GRAPH_CHANGED",
      "fact": {
        "subject": "biologicalSampling", "actor": "commander",
        "object": {"kind": "ORGANIC", "id": null, "name": "Bacterium Aurasus"},
        "operation": "SAMPLED", "identity": null,
        "quantity": null, "qualifiers": {"genus": "Bacterium"},
        "processStage": "FINAL", "completion": true, "negation": null,
        "relationship": null, "assertionSource": "REPORTED"
      },
      "presentation": "The player recorded the final scan and completed the sampling sequence for Bacterium Aurasus.",
      "presentationTruncated": false,
      "changedSubjects": ["biologicalSampling"]
    }
  ],
  "stateChanges": {
    "capturedAfterBusSequence": 1044,
    "items": [
      {"subject": "currentBody", "field": "bodyId", "changeKind": "UPDATED",
       "before": 82, "after": 83, "origin": "OBSERVATION",
       "sourceBusSequence": 1042, "sourceRole": "NEW"},
      {"subject": "currentBody", "field": "planetClass", "changeKind": "ACTIVATED_FROM_CONTEXT",
       "before": null, "after": "Rocky body", "origin": "STORED_CONTEXT",
       "sourceBusSequence": 1042, "sourceRole": "NEW"},
      {"subject": "currentBody", "field": "biologicalSignalCount", "changeKind": "ESTABLISHED",
       "before": null, "after": 3, "origin": "OBSERVATION",
       "sourceBusSequence": 1043, "sourceRole": "CONTEXT_ONLY"},
      {"subject": "biologicalSampling", "field": "active", "changeKind": "UPDATED",
       "before": true, "after": false, "origin": "OBSERVATION",
       "sourceBusSequence": 1044, "sourceRole": "NEW"}
    ],
    "hiddenSourceEffects": {
      "count": 1, "omittedCount": 0,
      "byRole": {"CONTEXT_ONLY": 1, "DIAGNOSTIC_ONLY": 0, "STATUS": 0},
      "items": [
        {"busSequence": 1043, "rawEventType": "SAASignalsFound", "sourceRole": "CONTEXT_ONLY",
         "normalizedEventType": "SAA_SIGNALS_FOUND", "changedSubjects": ["currentBody"]}
      ]
    }
  },
  "currentState": {
    "commander": {"fid": "F-SYN"},
    "commanderPresence": {"mode": "ON_FOOT"},
    "primaryShip": {"shipId": 42, "shipType": "krait_mkii", "shipName": "Synthetic Ship",
                    "flightState": "LANDED"},
    "associatedVehicle": {"vehicleId": 7, "kind": "SRV", "unresolved": ["OCCUPANCY"]},
    "occupiedVehicle": null,
    "currentSystem": {"systemAddress": 7101, "systemName": "Synthetic Alpha"},
    "currentBody": {"bodyId": 83, "bodyName": "Synthetic 3 a", "broadBodyType": "Planet",
                    "planetClass": "Rocky body", "starType": null, "landable": true,
                    "wasDiscovered": true, "wasMapped": false, "wasFootfalled": false,
                    "distanceFromArrivalLs": 812.4,
                    "biologicalSignalCount": 3, "geologicalSignalCount": 1},
    "biologicalSampling": {"active": false, "bodyHasBiology": true}
  },
  "graphContext": {
    "available": true, "captureStatus": "AVAILABLE",
    "owner": {"commanderFid": "F-SYN", "shipId": 42},
    "graphRevision": 100, "topologyRevision": 50,
    "episode": {"episodeId": "episode-synthetic", "systemAddress": 7101,
                "systemName": "Synthetic Alpha", "occurrenceCount": 5},
    "cursor": {"normalizedEventType": "SCAN_ORGANIC_ANALYSE", "episodeSequence": 4,
               "source": "JOURNAL", "matchesFinalTrigger": true},
    "trajectory": {
      "ordering": "ACCEPTANCE", "causal": false, "scope": "ACTIVE_EPISODE",
      "totalOccurrenceCount": 5, "includedOccurrenceCount": 5, "omittedOccurrenceCount": 0,
      "items": [
        {"episodeSequence": 0, "normalizedEventType": "SYSTEM_ENTRY", "current": false},
        {"episodeSequence": 1, "normalizedEventType": "APPROACH_BODY", "current": false},
        {"episodeSequence": 2, "normalizedEventType": "TOUCHDOWN", "current": false},
        {"episodeSequence": 3, "normalizedEventType": "DISEMBARK", "current": false},
        {"episodeSequence": 4, "normalizedEventType": "SCAN_ORGANIC_ANALYSE", "current": true}
      ]
    },
    "activeEpisodeOccurrenceCounts": {
      "distinctEventTypeCount": 5, "includedEventTypeCount": 5, "omittedEventTypeCount": 0,
      "items": [
        {"normalizedEventType": "APPROACH_BODY", "count": 1},
        {"normalizedEventType": "DISEMBARK", "count": 1},
        {"normalizedEventType": "SCAN_ORGANIC_ANALYSE", "count": 1},
        {"normalizedEventType": "SYSTEM_ENTRY", "count": 1},
        {"normalizedEventType": "TOUCHDOWN", "count": 1}
      ]
    }
  },
  "predictions": {
    "availableCount": 2, "includedCount": 2, "includedProbabilityMass": 1.0,
    "basisAvailable": false,
    "items": [
      {"predictedEventType": "EMBARK", "probability": 0.67, "basis": "GLOBAL",
       "observedTransitionCount": 2,
       "globalProbability": 0.67, "contextSupport": 0.0, "decayedWeight": 2.0},
      {"predictedEventType": "SCAN_ORGANIC_LOG", "probability": 0.33, "basis": "GLOBAL",
       "observedTransitionCount": 1,
       "globalProbability": 0.33, "contextSupport": 0.0, "decayedWeight": 1.0}
    ]
  },
  "previousComments": [
    {"text": "Three biological signals down there — worth a look before we lift off.",
     "authority": "PREVIOUS_MODEL_OUTPUT", "nonAuthoritative": true,
     "turnSequence": 40, "evidenceTriggerBusSequences": [1039]}
  ],
  "truncation": {
    "budgetDegraded": false, "serializedCharacterCount": 3980, "maxSerializedCharacters": 12000,
    "trajectoryOmitted": 0, "activeEventTypesOmitted": 0, "predictionsOmitted": 0,
    "hiddenEffectsOmitted": 0, "presentationsTruncated": 0, "stateChangesOmitted": 0,
    "technicalMetadataDropped": false, "decayedWeightsDropped": false
  }
}
```

### 13.11 Minimal example

> **ILLUSTRATIVE SYNTHETIC EXAMPLE — not a real runtime capture.** Graph disabled, one trigger, nothing known.

```json
{
  "schemaVersion": "kairon-llm-situation-v2",
  "turn": {
    "firstTriggerBusSequence": 7, "lastTriggerBusSequence": 7, "triggerCount": 1,
    "firstTriggerTimestamp": "2026-07-30T09:00:00Z", "lastTriggerTimestamp": "2026-07-30T09:00:00Z",
    "capturedAfterBusSequence": 7, "captureMode": "LIVE", "sourceKind": "journal-tail",
    "graphApplyStatus": "DISABLED", "behaviorCaptureStatus": "GRAPH_DISABLED", "budgetDegraded": false
  },
  "triggers": [
    {"busSequence": 7, "rawEventType": "Friends", "normalizedEventType": null,
     "timestamp": "2026-07-30T09:00:00Z", "relation": "SITUATION_UNAVAILABLE",
     "fact": null,
     "presentation": "A friend list update was received.",
     "presentationTruncated": false, "changedSubjects": []}
  ],
  "stateChanges": {
    "capturedAfterBusSequence": 7, "items": [],
    "hiddenSourceEffects": {"count": 0, "omittedCount": 0,
      "byRole": {"CONTEXT_ONLY": 0, "DIAGNOSTIC_ONLY": 0, "STATUS": 0}, "items": []}
  },
  "currentState": {
    "commander": {"fid": null},
    "commanderPresence": {"mode": "UNKNOWN"},
    "primaryShip": {"shipId": null, "shipType": null, "shipName": null, "flightState": "UNKNOWN"},
    "associatedVehicle": null, "occupiedVehicle": null,
    "currentSystem": {"systemAddress": null, "systemName": null},
    "currentBody": {"bodyId": null, "bodyName": null, "broadBodyType": null, "planetClass": null,
                    "starType": null, "landable": null, "wasDiscovered": null, "wasMapped": null,
                    "wasFootfalled": null, "distanceFromArrivalLs": null,
                    "biologicalSignalCount": null, "geologicalSignalCount": null},
    "biologicalSampling": {"active": null, "bodyHasBiology": null}
  },
  "graphContext": {
    "available": false, "captureStatus": "GRAPH_DISABLED", "owner": null,
    "graphRevision": null, "topologyRevision": null, "episode": null, "cursor": null,
    "trajectory": {"ordering": "ACCEPTANCE", "causal": false, "scope": "ACTIVE_EPISODE",
                   "totalOccurrenceCount": 0, "includedOccurrenceCount": 0,
                   "omittedOccurrenceCount": 0, "items": []},
    "activeEpisodeOccurrenceCounts": {"distinctEventTypeCount": 0, "includedEventTypeCount": 0,
                                      "omittedEventTypeCount": 0, "items": []}
  },
  "predictions": {"availableCount": 0, "includedCount": 0, "includedProbabilityMass": 0.0,
                  "basisAvailable": false, "items": []},
  "previousComments": [],
  "truncation": {"budgetDegraded": false, "serializedCharacterCount": 1450,
                 "maxSerializedCharacters": 12000, "trajectoryOmitted": 0,
                 "activeEventTypesOmitted": 0, "predictionsOmitted": 0, "hiddenEffectsOmitted": 0,
                 "presentationsTruncated": 0, "stateChangesOmitted": 0,
                 "technicalMetadataDropped": false, "decayedWeightsDropped": false}
}
```

### 13.12 Hidden `CONTEXT_ONLY` / Status delta example

> **ILLUSTRATIVE SYNTHETIC EXAMPLE — not a real runtime capture.** Fragment only.

A `Scan` (`CONTEXT_ONLY`) established body facts and a Status snapshot moved the graph cursor, both between two NEW triggers. In v1 both are completely invisible; in v2:

```json
"stateChanges": {
  "capturedAfterBusSequence": 2210,
  "items": [
    {"subject": "currentBody", "field": "landable", "changeKind": "ESTABLISHED",
     "before": null, "after": true, "origin": "OBSERVATION",
     "sourceBusSequence": 2208, "sourceRole": "CONTEXT_ONLY"},
    {"subject": "currentBody", "field": "planetClass", "changeKind": "ESTABLISHED",
     "before": null, "after": "High metal content body", "origin": "OBSERVATION",
     "sourceBusSequence": 2208, "sourceRole": "CONTEXT_ONLY"}
  ],
  "hiddenSourceEffects": {
    "count": 2, "omittedCount": 0,
    "byRole": {"CONTEXT_ONLY": 1, "DIAGNOSTIC_ONLY": 0, "STATUS": 1},
    "items": [
      {"busSequence": 2208, "rawEventType": "Scan", "sourceRole": "CONTEXT_ONLY",
       "normalizedEventType": null, "changedSubjects": ["currentBody"]},
      {"busSequence": 2209, "rawEventType": "Status", "sourceRole": "STATUS",
       "normalizedEventType": "LANDING_GEAR_DEPLOYED", "changedSubjects": []}
    ]
  }
},
"graphContext": {
  "cursor": {"normalizedEventType": "LANDING_GEAR_DEPLOYED", "episodeSequence": 11,
             "source": "STATUS", "matchesFinalTrigger": false}
}
```

`cursor.source: "STATUS"` + `matchesFinalTrigger: false` is precisely the labelling absent today (§10.3, §10.4).

### 13.13 Explicit negation example

> **ILLUSTRATIVE SYNTHETIC EXAMPLE — not a real runtime capture.** Fragment only.

`FSDJump` reports `BoostUsed: false`. In v1 this survives only as the English substring *"no FSD boost was used"*, which truncation can sever (L4).

```json
{
  "busSequence": 3301, "rawEventType": "FSDJump", "normalizedEventType": "SYSTEM_ENTRY",
  "timestamp": "2026-07-30T18:22:10Z", "relation": "STATE_AND_GRAPH_CHANGED",
  "fact": {
    "subject": "currentSystem", "actor": "commander",
    "object": {"kind": "SYSTEM", "id": "7788", "name": "Synthetic Beta"},
    "operation": "ENTERED", "identity": {"kind": "SYSTEM_ADDRESS", "id": "7788"},
    "quantity": {"value": 42.7, "unit": "LIGHT_YEARS"},
    "qualifiers": {"boostUsed": "false"},
    "processStage": "FINAL", "completion": true, "negation": true,
    "relationship": null, "assertionSource": "REPORTED"
  },
  "presentation": "The player jumped 42.7 light years to system “Synthetic Beta”; no FSD boost was used.",
  "presentationTruncated": false,
  "changedSubjects": ["currentSystem", "currentBody", "primaryShip"]
}
```

`negation: true` is structural: it survives truncation and cannot be misread as its opposite.

### 13.14 Low-support prediction example

> **ILLUSTRATIVE SYNTHETIC EXAMPLE — not a real runtime capture.** Fragment only.

The §11.3 trap, made unmistakable:

```json
"predictions": {
  "availableCount": 1, "includedCount": 1, "includedProbabilityMass": 1.0,
  "basisAvailable": false,
  "items": [
    {"predictedEventType": "LIFTOFF", "probability": 1.0, "basis": "GLOBAL",
     "observedTransitionCount": 1,
     "globalProbability": 1.0, "contextSupport": 0.0, "decayedWeight": 1.0}
  ]
}
```

`probability: 1.0` is still true and still reported — but `GLOBAL` basis at `observedTransitionCount: 1` makes it unreadable as certainty, without inventing a reliability band to say so.

### 13.15 Non-authoritative previous comment example

> **ILLUSTRATIVE SYNTHETIC EXAMPLE — not a real runtime capture.** Fragment only.

```json
"previousComments": [
  {"text": "That's the third bacterium sample from this rock.",
   "authority": "PREVIOUS_MODEL_OUTPUT", "nonAuthoritative": true,
   "turnSequence": 39, "evidenceTriggerBusSequences": [1021, 1022]}
]
```

The count of three is **the model's own earlier assertion**, not a supplied fact. `authority` + `nonAuthoritative` prevent it being re-consumed as ground truth; `evidenceTriggerBusSequences` shows which past triggers it rested on, without making those triggers citable now (response evidence remains current-turn only).

---

## 14. Compaction and truncation policy

`VERIFIED_CURRENT_BEHAVIOR` — the existing degradation ladder in `LlmSituationTurnFactory.prepare` is well-designed and is **retained**: drop effective weights → shrink active counts → shrink trajectory (floor 2) → shrink predictions → drop technical metadata → binary-search presentation length. It always converges or throws `LlmSituationTurnTooLargeException`.

`DESIGN_PROPOSAL` — v2 changes only the **priority order**, and adds the new sections to the ladder. Ordering principle: *the model's ability to judge novelty is the last thing to go.*

| Priority (dropped first → last) | Section | Rationale |
| --- | --- | --- |
| 1 | `decayedWeight`, `contextSupport` | diagnostic only |
| 2 | `activeEpisodeOccurrenceCounts` items | repetition signal, degrades gracefully |
| 3 | `hiddenSourceEffects.items` (keep `count` + `byRole`) | the counts alone preserve the "something happened" signal |
| 4 | `trajectory.items` (floor 2, keep first + last) | existing behaviour |
| 5 | `predictions.items` | weakest epistemic category |
| 6 | `turn.sourceKind` | technical metadata |
| 7 | `presentation` (binary search) | `fact` now carries the meaning, so prose degrades before structure |
| 8 | **`stateChanges.items` — last** | this is the novelty signal; losing it defeats v2 |
| never | `fact`, `changeKind`, `before`/`after`, `negation`, `observedTransitionCount`, `basis`, `authority` | contract-critical |

**Truncation is never hidden.** Every drop increments a counter in `truncation` (§13.9). The `hiddenSourceEffects.omittedCount` field exists specifically so an over-full accumulator cannot silently look like "nothing happened".

Note that v2 is larger than v1 per turn. `stateChanges` and `fact` add volume; dropping the deprecated `bodyType` scalar and the 30-event history removes some. Whether `maxSerializedCharacters = 12_000` remains adequate is **OQ-7** — measurable only by building the serializer and re-running the six scenario fixtures, which is Phase C work.

---

## 15. Diagnostic / model-input separation

`DESIGN_PROPOSAL`. Every field in the catalog carries `model_facing` and `diagnostic_only` flags. The rule: **diagnostic metadata must not consume model budget without model-facing benefit.**

| Class | Fields | Destination |
| --- | --- | --- |
| **Model-facing** | `fact.*`, `stateChanges.items[].{subject,field,changeKind,before,after,origin}`, `currentState.*`, `graphContext.cursor.{normalizedEventType,source,matchesFinalTrigger}`, `trajectory.{ordering,causal,items}`, `predictions.items[].{predictedEventType,probability,basis,observedTransitionCount,globalProbability}`, `previousComments.*`, `truncation.*` | model input **and** trace |
| **Diagnostic-only** | `contextSupport`, `decayedWeight`, `contextKey`, `graphRevision`, `topologyRevision`, `owner`, `episode.episodeId`, `sourceKind`, `lastSeenAt` | trace; dropped from model input first |
| **Correlation-only** | `busSequence`, `capturedAfterBusSequence`, `sourceBusSequence` | both — required for the response contract |

`graphRevision` / `topologyRevision` / `owner` / `episodeId` are classed diagnostic because nothing in the decision rules consumes them; they exist for debugging and determinism checks. They are retained in the trace, where `situationTurn` must stay byte-identical to the user message — so `INFERENCE`: either they are dropped from both, or the trace gains a separate diagnostic sibling object. **The byte-identity guarantee is pinned by `ObserverPipelineTest` and must not be broken.** Recommendation: drop them from the model document and add them to a `TurnTrace` sibling field, which changes the trace schema (`kairon-turn-trace-v4`) but not the identity invariant. **OQ-5.**

---

## 16. Migration strategy summary

> **All phases are complete.** This section is the Phase A plan as written; §23–§28 record what was actually built, and §28 records the Phase E deletion. One planned item was not carried out and the reason is evidence-based, not an omission: `CurrentGameStateSnapshot.bodyType()` is retained because a permanent test still exercises it as a public contract (§28.5).

Full plan in `target/audit/kairon-llm-situation-v2-migration-plan.md`, which is a disposable build artifact. Summary:

- **Phase B — semantic envelope.** Widen `ProjectedObservation` (delta + facts), add `kairon.semantics`, add the hidden-effect accumulator. **No contract change; v1 still emitted and still the only thing sent.** Purely additive, fully testable in isolation.
- **Phase C — v2 DTO + serializer.** Build `LlmSituationTurnV2` + serializer. Optional temporary shadow serialization (build v2, log size, still send v1) to measure §14/OQ-7 without behavioural risk.
- **Phase D — prompt cutover.** Rewrite `SYSTEM_PROMPT` for v2, switch the sender. **v1 stops being created here.**
- **Phase E — regression, then removal.** Delete v1 DTO, serializer, shadow path, and the compatibility `bodyType()` accessor.

**Permanent:** semantic adapter, `CurrentGameStateDelta`, subject model, v2 DTO + serializer, hidden-effect accumulator.
**Temporary:** shadow serialization, v1 DTO + serializer, any dual trace field.
**Deletion point:** end of Phase E. No permanent dual path exists at any point after that, and phases D and E are the only ones that change model behaviour.

**Must not be mixed in one phase:** the semantic envelope (B) and the prompt cutover (D) — otherwise a regression cannot be attributed to either data or prompt.

---

## 17. Test and coverage strategy

`VERIFIED_CURRENT_BEHAVIOR` baseline: 43 test classes, 418 `@Test`, last run 418 / 0 failures / 0 errors / 1 skipped.

**Highest-risk existing tests** (they pin exact strings or exact JSON, so they define the migration's blast radius):

| Test | Pins | Phase affected |
| --- | --- | --- |
| `JournalEventLlmPresentationTest` (127 tests) | exact presentation sentences, number formatting, typographic quotes | B — **must stay green**; `fact` is additive, prose unchanged |
| `LlmSituationTurnFactoryTest` (9) | byte-identical determinism, exact JSON paths, field-**absence** assertions | C — rewritten for v2 |
| `ObserverPipelineTest` (8) | trace `situationTurn` == userMessage minus prefix; prompt fragments; negative pins | D — prompt fragments change |
| `ObserverResponseValidatorTest` (18) | exact violation codes; constructs 17 nested v1 records directly | C — heavy constructor churn |
| `OpenAiCompatibleLlmClientTest` (1) | `SYSTEM_PROMPT` equality on the wire | D |
| `SnapshotReplayIntegrationTest` (2) | exact eventType list, relation, JSON values | C/D |

`DESIGN_PROPOSAL` — new coverage required:

1. **Delta purity** — `(previousState, currentState) → delta` is a pure function; property test over generated snapshot pairs; every `changeKind` has a positive and a negative case.
2. **`ACTIVATED_FROM_CONTEXT`** — the exact `Scan(83)` → `ApproachBody(84)` → `ApproachBody(83)` sequence must yield `ACTIVATED_FROM_CONTEXT`, **not** `ESTABLISHED`. This is the single most important new test.
3. **Zeroing hazard** — a geology-only `SAASignalsFound` after a biological one must emit `UPDATED 3 → 0`, not `CLEARED`.
4. **Hidden provenance** — NEW / `CONTEXT_ONLY` / Status / NEW ordering must produce exactly one `hiddenSourceEffects` entry per non-NEW effect, no duplicates across consecutive turns, and a correct `omittedCount` at the cap.
5. **Cursor labelling** — a `NEW_ELIGIBLE` non-significant trigger after a Status delta must yield `matchesFinalTrigger: false`, `source: "STATUS"`.
6. **Prediction support** — `observedTransitionCount` must reach the model unmodified alongside `basis`; truncated predictions must report `includedProbabilityMass < 1.0`.
7. **Previous-comment authority** — every entry always carries `authority` and `nonAuthoritative: true`.
8. **Adapter coverage guard** — a class-init assertion, mirroring the existing *"Active LLM event type lacks sourced presentation"* guard, that all 114 selected types have a Tier A semantic adapter. This makes full-event-space coverage compile-time enforced rather than a claim.
9. **Truncation honesty** — for every degradation step, the corresponding `truncation` counter is non-zero.
10. **Determinism** — byte-identical repeat serialization, preserved from v1.

Coverage is measured against the verifiable event space (272 / 114 / 33 / 52 / 8), **not** against the unverifiable 504 / 36 / 158 inventories.

---

## 18. Risks

| # | Risk | Severity | Evidence | Mitigation |
| --- | --- | --- | --- | --- |
| R1 | v2 exceeds the 12 000-char budget, forcing `stateChanges` truncation and defeating the purpose | **High** | §14, OQ-7 | Measure in Phase C shadow mode before cutover; `stateChanges` is last in the ladder |
| R2 | Tier A adapters for 114 types is a large, error-prone effort | **High** | 119 presentable implementations already exist as precedent | Class-init coverage guard (test 8); reuse the normalizer's `register` pattern |
| R3 | Forwarding `CONTEXT_ONLY`/`DIAGNOSTIC_ONLY`/Status to the coordinator increases its message volume substantially | Medium | 158 `DIAGNOSTIC_ONLY` types + Status polling | Bounded accumulator; filter to effects that actually changed state or graph |
| R4 | `ACTIVATED_FROM_CONTEXT` mis-tagged, making re-visits look like discoveries | **High** | §6.3, verified re-hydration | Tag by write path, never by value; test 2 is the gate |
| R5 | Prompt cutover regresses comment quality independently of data quality | Medium | prompt is a single constant | Phases B and D strictly separated; ADR-0010 evidence-first evaluation |
| R6 | Widening `ProjectedObservation` breaks 5+ test classes that construct it directly | Medium | `ProjectedObservationTestBridge`, 4 more | Additive components with a compatibility factory during Phase B |
| R7 | Status-derived cursor labelled but the underlying behaviour is actually a defect, so v2 faithfully encodes a bug | Medium | §10.4, OQ-1 | Resolve OQ-1 **before** Phase C |
| R8 | Trace schema change (`v4`) breaks external tooling | Low | trace is best-effort JSONL | Version the schema; §15/OQ-5 |
| R9 | The 5 presentable-but-unselected types suggest the selection profile is drifting | Low | `DockingGranted`, `DockingRequested`, `FuelScoop`, `LaunchDrone`, `MaterialCollected` | Out of scope; recorded as OQ-9 |

---

## 19. Open questions

Only evidence-based, unresolved items. Each blocks or shapes a specific phase.

- **OQ-1 — RESOLVED in Phase B.1 as `VERIFIED_CURRENT_BEHAVIOR`.** The documentation was wrong, not the code. Status-derived occurrences never become a trigger and never contribute a presentation, but they are recorded through the same `recordNormalizedOccurrence` path as journal events, so they enter the active-episode timeline, can own the graph cursor, and their normalized type names are visible to the model through `situationAfterBatch`. `CLAUDE.md` has been corrected to state this precisely (§24.3). Graph behaviour was not changed. Whether Status occurrences *should* be filtered is a product decision and is explicitly **not** part of the v2 implementation.
- **OQ-2 — `flightMode` subject.** *Resolved for implementation in Phase B (§23.3): ownership is unproven, so the field is bound to the neutral `NAVIGATION_CONTEXT` subject rather than to the ship or the commander.* The underlying game-semantics question — which of the two the value actually describes when they differ — remains open and would need evidence outside this repository.
- **OQ-3 — associated vs occupied vehicle.** Should the projection gain a real occupancy distinction, or does `occupiedVehicle` stay permanently `null`? *Shapes §9; not required for Phase B.*
- **OQ-4 — unhandled vehicle events.** `DockFighter` and `VehicleSwitch` are catalogued but never projected, leaving `vehicleKind=UNKNOWN, activeVehicleId=<fighter id>` stuck after a fighter recall. Deliberate scoping or oversight? Nothing in source, javadoc, or tests states either. *`UNRESOLVED`.*
- **OQ-5 — RESOLVED in Phase C (§25.8).** Two separate immutable representations: a model-facing document and a diagnostic shadow record written to its own artifact. The §15 recommendation of a `TurnTrace` sibling field was **not** adopted — it would have coupled a temporary measurement to the permanent trace contract. `JsonLinesTurnTraceWriter` is untouched and the trace stays `kairon-turn-trace-v3`.
- **OQ-6 — support thresholds.** *Closed in Phase B by removal (§23.4).* The qualitative band is withdrawn from the contract; the factual `observedTransitionCount` is reported instead. Re-introducing a band would require evidence the repository does not contain.
- **OQ-7 — MEASURED in Phase C (§25.9).** Yes, with a named residual risk. Every one of sixteen measured cases fits after compaction and none loses mandatory semantics; no pipeline-replay or unit-fixture case loses a state change. Two synthetic worst-case probes do lose state changes, always with an explicit count, and one ordinary exploration replay already needed compaction to fit. Raising the limit remains a code change to the hardcoded `LlmSituationPolicy`.
- **OQ-10 — RESOLVED in Phase C.1 (§26.1).** `EventOccurrenceSource` is recorded when the graph layer accepts an occurrence and carried into `SituationOccurrence`, the v2 cursor and every trajectory item. All downstream event-type inference is deleted. Residual: provenance is not persisted, so an occurrence restored from the graph store reports absence.
- **OQ-11 — RESOLVED in Phase C.1 (§26.2).** `SituationNextEventPrediction` carries basis, both probabilities, both observation counts, context support and the context key. §11.4's claim that `basis` is always `GLOBAL` for 50 of 52 types is **false** (§26.3).
- **OQ-12 — ACCEPTED LIMITATION in Phase D (§27.9).** Occurrence provenance is in-process only, so an occurrence restored from the graph store reports `null`. Nothing infers it, and the prompt states the semantics. The minimal persistence migration stays proposed and deferred.
- **OQ-13 — IMPLEMENTED in Phase D (§27.5).** The production budget is 16 000 characters and overflow fails closed as `CONTEXT_TOO_LARGE`.
- **OQ-8 — missing audit artifacts.** 504 semantic inventory entries / 36 mechanisms / 158 transition patterns cannot be verified or reconstructed (§3.2–3.3). Should they be regenerated before Phase B, or is the code-derived taxonomy sufficient?
- **OQ-9 — five orphaned presentable types.** `DockingGranted`, `DockingRequested`, `FuelScoop`, `LaunchDrone`, `MaterialCollected` have full `llmPresentation()` implementations but no selection role. Deferred deliberately, or an oversight? Nothing asserts the implementer ⇒ subscribed direction.

---

## 20. Explicit design decisions

| # | Decision | Rationale |
| --- | --- | --- |
| D1 | Structured facts materialise in a **model-independent semantic adapter** (`kairon.semantics`), not in the 272 event records, not in the LLM layer | Preserves ADR-0002 transport-identity purity and `LlmSituationTurnFactory` purity; reusable; generalises the proven `BehaviorEventNormalizer` registry (§8) |
| D2 | Field delta is computed **inside the projection boundary** and carried on a widened `ProjectedObservation` | `previousState` exists only there; downstream computation is structurally impossible (§4.4, §6.1) |
| D3 | Five change kinds only: `ESTABLISHED`, `UPDATED`, `CLEARED`, `ACTIVATED_FROM_CONTEXT`, `UNCHANGED` (the last never emitted) | Each is formally derivable; nothing speculative added (§6.2) |
| D4 | `ACTIVATED_FROM_CONTEXT` is tagged by **write path, not value comparison** | Stored `BodyContext` re-hydration is otherwise indistinguishable from discovery (§6.3) |
| D5 | Hidden-source provenance is owned by **`ObserverTurnCoordinator`** | Only component that knows turn boundaries, already single-threaded and unit-tested (§7.3) |
| D6 | Nine separate subjects; `occupiedVehicle` always `null`; taxi/multicrew/fighter `unresolved` | Prevents false contradictions; refuses to guess what the projection cannot establish (§9) |
| D7 | Graph context is explicitly non-causal: `ordering: "ACCEPTANCE"`, `causal: false` | Acceptance order is not even guaranteed chronological (§10.5) |
| D8 | Cursor carries `source` and `matchesFinalTrigger` | 85 of 109 NEW types leave the cursor unmoved; Status can own it (§10.3–10.4) |
| D9 | Both `rawEventType` and `normalizedEventType` on every trigger | Makes many-to-one collapses visible (§10.2) |
| D10 | Predictions separate `probability` / `observedTransitionCount` / `basis`; `effectiveWeight` → `decayedWeight`, diagnostic; **no qualitative support band** | `confidence` does not exist in the domain; `effectiveWeight` is a decayed weight plus prior (§11.2); thresholds for a band are not established (§23.4) |
| D11 | `includedProbabilityMass` is mandatory | Truncated predictions otherwise read as zero probability (L10) |
| D12 | Previous comments always carry `authority` + `nonAuthoritative: true`; no topic/entity extraction | Prevents model output re-entering as fact; extraction would be pre-model interpretation (§12.2) |
| D13 | All truncation counters in one top-level `truncation` object | Truncation must never be hidden (§14) |
| D14 | `stateChanges` is the **last** thing dropped under budget pressure | It is the novelty signal v2 exists to deliver (§14) |
| D15 | Coverage measured against the verifiable event space, not the unverifiable historical inventories | The prior artifacts are gone and were not reconstructed (§3.2) |

---

## 21. Rejected alternatives

| Rejected | Why |
| --- | --- |
| Typed semantic fields on the 272 event records | Violates ADR-0002 (transport identity ≠ domain model); 272-file change surface |
| Structured facts built in `LlmSituationTurnFactory` | Breaks its verified purity; makes semantics model-specific and unusable by GUI/diagnostics |
| Reusing `BehaviorEventNormalizer` as the semantic layer | Graph-scoped: 33 significant types, 30 rules, topology-oriented vocabulary. Kept as the *pattern*, not the *home* |
| Deriving hidden effects by diffing `currentState` between consecutive NEW triggers | Attribution is exactly the provenance already discarded; multiple hidden observations collapse into one indistinguishable diff (§7.2, option 5) |
| Accumulating hidden effects at the projection boundary | It does not know turn boundaries; would buffer unboundedly |
| Unbounded immutable projection history | Memory risk with no bound derivable from the domain |
| Extending `CurrentGameStateChangeSet` with values in place | Breaks `CurrentGameStateProjection`'s recompute-and-verify invariant and `CurrentGameStateProjectorTest`; a parallel delta type is cheaper and safer |
| Extra change kinds (`CONFIRMED`, `REFRESHED`, `CORRECTED`, `INVALIDATED`) | Not reliably determinable — no notion of re-assertion vs never-re-checked (§6.2) |
| Calling `effectiveWeight` "confidence" | The domain model defines no confidence; it is a decayed weight plus prior (§11.2) |
| A numeric `reliability` score for predictions | Would be an invented importance/quality score — forbidden by the core invariants |
| Keeping the deprecated flat `bodyType` scalar | Compatibility projection, not canonical; superseded by three explicit fields |
| Permanent v1/v2 dual path | Explicitly forbidden; migration defines a deletion point (§16) |
| Wiring the corpus subscriber to supply provenance | Raw pre-projection capture with no state/graph/turn linkage; out of scope and not needed |

---

## 22. Evidence index

Primary sources read in full. Paths relative to repository root.

**Projection and state**
`src/main/java/kairon/projection/ObservationProjectionCoordinator.java` · `ProjectedObservation.java` · `src/main/java/kairon/state/CurrentGameStateProjection.java` · `CurrentGameStateSnapshot.java` · `CurrentGameStateChangeSet.java` · `CurrentGameStateProjector.java` · `GameStateFacet.java` · `BodyContext.java` · `BodyKey.java` · `BodyTypeCompatibilityProjection.java` · `CommanderLocationMode.java` · `FlightMode.java`

**Graph**
`src/main/java/kairon/behavior/bus/BehaviorGraphObservationProcessor.java` · `graph/BehaviorGraphService.java` · `graph/TransitionProbabilityCalculator.java` · `normalize/BehaviorEventNormalizer.java` · `normalize/NormalizedEventType.java` · `normalize/NormalizedBehaviorEvent.java` · `classify/EventSignificancePolicy.java` · `classify/BehaviorOccurrenceProjectionPolicy.java` · `snapshot/BehaviorSituationSnapshot.java` · `snapshot/ActiveEpisodeSituation.java` · `snapshot/SituationOccurrence.java` · `snapshot/SituationNextEventPrediction.java` · `model/NextEventPrediction.java` · `model/GraphCursor.java` · `model/PredictionBasis.java` · `model/ContextKey.java` · `model/ContextSnapshot.java` · `model/EventOccurrence.java` · `model/OccurrenceTransition.java` · `context/TransitionContextKeyFactory.java` · `status/StatusStateDeltaAdapter.java`

**LLM and observer**
`src/main/java/kairon/observer/LlmJournalEventSelection.java` · `LlmJournalObserverSubscriber.java` · `ObserverTurnCoordinator.java` · `context/LlmSituationTurn.java` · `context/LlmSituationTurnFactory.java` · `context/JacksonLlmSituationTurnSerializer.java` · `context/LlmSituationPolicy.java` · `context/TriggerRelation.java` · `src/main/java/kairon/llm/SituationSnapshotPromptFactory.java` · `ObserverResponseValidator.java` · `CommentNoveltyGuard.java` · `src/main/java/kairon/trace/JsonLinesTurnTraceWriter.java`

**Event semantics**
`src/main/java/kairon/observation/journal/JournalEventCatalog.java` · `LlmPresentableJournalEvent.java` · `JournalEventObservation.java` (incl. `RawJournalData`) · `UnknownJournalEvent.java` · `observation/journal/event/**` (272 records; 12 read in full)

**Config and diagnostics**
`src/main/java/kairon/config/KaironConfiguration.java` · `config/kairon.example.json` · `src/main/java/kairon/diagnostics/ObservationCorpusJsonlSubscriber.java` · `src/main/java/kairon/app/KaironApplication.java`

**Tests consulted (not modified)**
`LlmSituationTurnFactoryTest` · `ObserverResponseValidatorTest` · `ObserverPipelineTest` · `SnapshotReplayIntegrationTest` · `SpeechOutputTest` · `CurrentGameStateProjectorTest` · `ObservationProjectionCoordinatorTest` · `BehaviorSituationProjectionTest` · `BehaviorSituationSnapshotTest` · `BehaviorGraphManualReplayTest` · `JournalEventLlmPresentationTest` · `JournalSourceTest` · `OpenAiCompatibleLlmClientTest` · `ObservationCorpusJsonlSubscriberTest` · `DesktopGuiTest`

**Runtime samples**
`target/snapshot-model-input-examples.jsonl` (6 scenarios, current schema — **used**) · `target/observer-response-contract-examples.jsonl` (6 scenarios, current schema — **used**) · `target/surefire-reports/` (2026-07-31 16:56) · `var/*.jsonl` (**rejected** — pre-`v3` trace schema)

**Normative docs consulted (not modified)**
`CLAUDE.md` (see OQ-1) · `docs/KAIRON_ARCHITECTURE.md` · `docs/CURRENT_STATE.md` · `docs/decisions/ADR-0001` · `ADR-0002` · `ADR-0006` · `ADR-0010` · `ADR-0011`

**Absent** — all 20 prior `target/audit/*` artifacts (§3.2).

---

## 23. Phase B implementation record

Phase B is implemented. **No v2 DTO, serializer, JSON schema, prompt, shadow output or trace change exists.** The v1 path is byte-for-byte unchanged; the semantic layer is built, populated and tested but is not yet model input.

### 23.1 Implemented semantic types

All under `src/main/java/kairon/semantics/`, with no dependency on `kairon.llm`, `LlmSituationTurn`, JSON serialization, prompt wording, the response DTO, or TTS.

| Type | Role |
| --- | --- |
| `SemanticSubject` | 11 separated subjects (§9 nine, plus `NAVIGATION_CONTEXT` and `UNRESOLVED_SUBJECT`) |
| `SemanticSourceRole` | `NEW`, `CONTEXT_ONLY`, `DIAGNOSTIC_ONLY`, `STATUS`, `CONTROL` |
| `SemanticField` | the 25 canonical state fields, each bound to its subject and flagged `bodyRegistryDerived` |
| `SemanticValue` | sealed: `UnknownValue`, `TextValue`, `BooleanValue`, `IntegralValue`, `DecimalValue`, `SymbolicValue`, `IdentityValue`, `QuantityValue`, `CoordinatesValue` |
| `SemanticProvenance` | `busSequence` + `sourceRole` + `rawObservationType` + `observationId` |
| `SemanticChangeKind` | `ESTABLISHED`, `UPDATED`, `CLEARED`, `ACTIVATED_FROM_CONTEXT` |
| `SemanticValueOrigin` | `OBSERVATION`, `STORED_CONTEXT` |
| `SemanticStateChange` | one exact field delta, self-validating against its change kind |
| `SemanticOperation` | closed controlled verb vocabulary |
| `SemanticFact` | subject/actor/object/operation/identity/quantity/qualifiers/stage/completion/negation/relationship/assertionSource + optional `presentation` |
| `UnresolvedFact` | closed `Reason` set for recorded gaps |
| `SemanticObservationEnvelope` | the immutable per-observation envelope of §4.1 |
| `SemanticEventAdapter` + `SemanticAdapterRegistry` | the boundary of §8 |
| `SemanticEnvelopeFactory` | builds the envelope; owns the error policy |
| `SemanticEffectAccumulator` | the bounded hidden-effect store of §7 |
| `SemanticSourceRoles` | resolves the role by delegating to `LlmJournalEventSelection.roleOf` |

`SemanticValue` deliberately has no `integer` variant distinct from `IntegralValue`: every integral canonical field and every integral raw field fits `long`, so a second variant would be unused.

### 23.2 Boundaries as built

- **Structured facts** materialise in `kairon.semantics`, keyed on payload class by `SemanticAdapterRegistry`, generalising `BehaviorEventNormalizer.register(...)`. No semantic field was added to any of the 272 event records; they remain transport identities. No adapter reads `llmPresentation()` — enforced by `SemanticAdapterRegistryTest.adaptersNeverParseRenderedPresentation`.
- **Field delta** is computed in `CurrentGameStateProjector.applyAndCapture`, where `previousState`, `currentState`, the observation and the write path are simultaneously in scope, and is carried on `CurrentGameStateProjection.semanticChanges`. Nothing downstream recomputes it.
- **Provenance ownership** for hidden sources is `ObserverTurnCoordinator`, via a `SemanticEffectAccumulator` field confined to the `observer-coordinator` thread. It drains in the same critical section that fixes the trigger batch, through the final trigger's `busSequence`.
- **Correlation identity** is enforced structurally: `ProjectedObservation` rejects an envelope whose `busSequence` differs from the trigger, and `CurrentGameStateProjection` rejects a semantic change whose provenance does not belong to it.

### 23.3 FlightMode decision — `NAVIGATION_CONTEXT` (evidence-based)

**Decision: `SemanticField.FLIGHT_MODE.subject() == SemanticSubject.NAVIGATION_CONTEXT`.** Not `PRIMARY_SHIP`, not `COMMANDER_PRESENCE`.

Audit of every write path (`CurrentGameStateProjector`):

| Writer | Value |
| --- | --- |
| `FSDJump`, `SupercruiseExit`, `Liftoff`, `Undocked` | `NORMAL_SPACE` |
| `SupercruiseEntry`, `LeaveBody` | `SUPERCRUISE` |
| `StartJump` | `HYPERSPACE` / `SUPERCRUISE` / `UNKNOWN` |
| `Touchdown` | `LANDED` |
| `Docked` | `DOCKED` |
| `Location` | `DOCKED` or `NORMAL_SPACE`, from the `Docked` flag |
| commander FID change | reset to `UNKNOWN` |

Evidence **for** a vessel reading: every writer is a ship navigation operation. `updateVehicleLaunch`, `updateDisembark`, `updateEmbark`, `updateVehicleDock` and `updateOrganicSampling` contain **zero** `flightMode` writes, so no SRV, fighter or on-foot transition ever moves it.

Evidence **against** committing to `PRIMARY_SHIP`:

1. `updateLocation` sets `flightMode` from the `Docked` flag **in the same branch** that sets `commanderMode` from `OnFoot`/`InSRV`. The projector treats it as a property of the reported situation, not of a named vessel.
2. No consumer attributes it to a subject. v1 groups it in `Activity` beside `commanderLocationMode` and `vehicleKind`; `ContextSnapshot` groups it flatly under mode; `GameStateFacet.FLIGHT` is a facet, not a subject.
3. `FlightMode.java` carries no javadoc and no ownership statement.

Neither ownership is provable from the repository, so the value is bound to a neutral navigation subject. The value never disappears and the ambiguity is not masked: `SemanticSubject.NAVIGATION_CONTEXT` documents explicitly why it exists. Guarded by `CurrentGameStateSemanticDeltaTest.flightModeUsesTheProvenNeutralSubject`.

### 23.4 Prediction support thresholds — withdrawn

The Phase A draft proposed `support.level` in {`SINGLE_OBSERVATION`, `SPARSE`, `ESTABLISHED`} over `observedTransitionCount`. The repository defines no such thresholds, and materialising them would present an invented classification as domain truth — exactly the kind of pre-model scoring the project forbids.

The band is **removed** from the contract (§11.3, §13.7). The factual `observedTransitionCount` is reported instead, alongside `basis`. `effectiveWeight` is still renamed `decayedWeight` and marked diagnostic; it is **not** renamed to confidence. **No Phase B code touches the prediction layer** — this is a contract correction only.

### 23.5 Status-derived graph occurrences — preserved, not reinterpreted

Phase B does not filter Status occurrences, change graph semantics, touch the cursor or trajectory, or amend any document about them. Current behaviour is preserved exactly (§10.4). What Phase B adds is the *ability* to name the source later: `SemanticSourceRole.STATUS` exists and `SemanticProvenance.rawObservationType` carries `"Status"`, so a Phase C contract can label a Status-derived cursor without changing what the graph does. This is recorded as preserved behaviour, not as causal semantics. **OQ-1 remains open.**

### 23.6 Presentable events without a selection role

`DockingGranted`, `DockingRequested`, `FuelScoop`, `LaunchDrone` and `MaterialCollected` keep their `DIAGNOSTIC_ONLY` role. Three of the five mechanisms they belong to now have semantic adapters (`DockingGranted`, `DockingRequested`, plus `LaunchSRV` alongside `LaunchFighter`), because the registry is mechanism-oriented and docking and vehicle launch are single mechanisms. Having a structured adapter is **not** model eligibility: event selection is untouched, and `SemanticAdapterRegistryTest.presentableEventsWithoutSelectionRoleDoNotBecomeNew` pins it. **OQ-9 remains open.**

### 23.7 Accumulator behaviour

- Accepts envelopes strictly ascending by `busSequence`; out-of-order recording throws.
- Retains `NEW`, `CONTEXT_ONLY`, `STATUS`, `CONTROL`. Retains `DIAGNOSTIC_ONLY` **only** when it actually changed canonical state — crossing the bus is not a reason to enter model context.
- `drainThrough(busSequence)` removes everything at or below the boundary; later effects stay for the next turn; drained effects never reappear; a turn that starts before a pending effect does not destroy it.
- **Memory bound without silent loss.** Up to `maxRetainedEnvelopes` (default 512) envelopes are held whole. Beyond that the oldest is folded into a coalesced per-field set — bounded by the 25 canonical fields — preserving the earliest `before`, the latest `after` and the latest provenance. A field that returns to its earlier value is removed as a genuine no-op. Structured facts dropped while folding are counted in `Drained.suppressedFactCount`, and `Drained.bounded()` reports that folding occurred. Gameplay state changes are never evicted.
- Deterministic in replay: single-threaded, insertion-ordered, no clock read, no randomness.

### 23.8 Deferred to Phase C

1. **Production wiring of non-NEW effects.** `LlmJournalObserverSubscriber` still discards `CONTEXT_ONLY`/`DIAGNOSTIC_ONLY` and never sees Status, so in production only `NEW` envelopes reach the accumulator. The `ObserverCommand.RecordSemanticEffect` entry point exists and is fully tested; feeding it requires editing the subscriber, which Phase B's allowed-file list excludes. **This is a scope boundary, not a design gap** — see §23.10.
2. **Adapter coverage.** 34 of 272 catalogue types have adapters (§23.9).
3. Everything in §16 Phase C and later: the v2 DTO, serializer, compaction ladder port, prompt cutover.

### 23.9 Coverage, honestly stated

| Measure | Value |
| --- | --- |
| Catalogue event types accounted for | **272 / 272** |
| Semantic adapters registered | **34** |
| `NEW_ELIGIBLE` with an adapter | **26 / 109** |
| `CONTEXT_ONLY` with an adapter | **5 / 5** |
| `DIAGNOSTIC_ONLY` with an adapter | **3 / 158** |
| Catalogue types without an adapter | **238** — each yields an explicit `NO_SEMANTIC_ADAPTER` gap, never an exception |

All five `CONTEXT_ONLY` types are covered deliberately: they are the hidden-provenance mechanism the whole design exists to recover. No coverage is claimed against the unverifiable historical 36/158 inventories.

### 23.10 Deviations from the proposed design

| Proposed (Phase A) | As built | Why |
| --- | --- | --- |
| `flightState` on `primaryShip` | `flightMode` on `NAVIGATION_CONTEXT` | Ownership unproven (§23.3) |
| `support.level` qualitative band | removed; factual count only | Thresholds unproven (§23.4) |
| `stateChanges` with `origin` on the change | same, plus `SemanticValueOrigin` as a named type | Needed so `ACTIVATED_FROM_CONTEXT` is decidable by write path |
| Accumulator drops oldest on overflow | coalesces per field, counts suppressed facts | "Drop oldest silently" is forbidden; coalescing preserves the net transition |
| Subscriber forwards non-NEW effects | entry point built, wiring deferred | `LlmJournalObserverSubscriber` is outside Phase B's allowed files |

---

## 24. Phase B.1 implementation record

Phase B.1 closes the two gaps Phase B left open: production wiring of non-`NEW` observations, and semantic disposition for the whole catalogue. **Still no v2 DTO, serializer, JSON, prompt, shadow output or trace change.** The v1 path remains byte-for-byte unchanged.

### 24.1 Production wiring

`LlmJournalObserverSubscriber` now contributes **every** projection's semantic effect, not just model-eligible triggers:

```
ProjectedObservation.semanticEnvelope
  -> ObserverCommand.RecordSemanticEffect      (always, first)
  -> ObserverCommand.QueueNewObservation       (only when role is NEW, non-BOOTSTRAP)
  -> ObserverCommand.ReplaySourceExhausted     (only for the control signal)
```

Ordering guarantees, each covered by a test:

1. **Exactly once.** The effect travels only on `RecordSemanticEffect`; `ObserverTurnCoordinator.queueNew` no longer records, so a `NEW` observation is never double-counted. The accumulator additionally rejects a non-ascending `busSequence`, so a duplicate is a hard error rather than a silent double.
2. **Original `busSequence` preserved**, and the accumulator enforces strictly ascending order.
3. **Effect first.** `RecordSemanticEffect` is posted before any command that can make a turn eligible, so a turn never begins without the effects that preceded its final trigger.
4. **No reclassification.** The subscriber reads `semanticEnvelope().sourceRole()` instead of calling the selection classifier again. There is exactly one source-role classifier, `SemanticSourceRoles`, which itself delegates to `LlmJournalEventSelection.roleOf`.
5. **`CONTEXT_ONLY`, `STATUS` and `CONTROL` never become triggers.** Only the `NEW` branch queues one.
6. **`DIAGNOSTIC_ONLY` is not automatically model-relevant.** The accumulator retains it only when it actually changed canonical state.
7. **Event selection and batching are untouched.**

Retention was corrected in the process: `NEW`, `CONTEXT_ONLY`, `STATUS` and `CONTROL` are now retained even when they carry no fact and changed no state. That an observation of one of those roles arrived is itself provenance, and dropping it would silently rewrite what happened between two turns.

### 24.2 Suppression is visible downstream

Phase B counted suppressed facts in a private field a later phase could not read. `SemanticEffectAccumulator.Drained` now carries `Optional<SemanticSuppression>`:

| Field | Meaning |
| --- | --- |
| `reason` | typed enum, currently `MEMORY_BOUND_COALESCING` |
| `suppressedFactCount` | structured facts dropped while folding |
| `coalescedEnvelopeCount` | envelopes folded, at least one |
| `firstSuppressedBusSequence` | lowest bus sequence in the folded span |
| `lastSuppressedBusSequence` | highest bus sequence in the folded span |

The marker is present whenever folding occurred, even if zero facts were dropped, so a consumer can always tell that detail was compressed. Canonical state changes are never suppressed: folding preserves the exact net per-field transition, and a field that round-trips is removed as a genuine no-op. No JSON representation was created.

### 24.3 OQ-1 resolved — `VERIFIED_CURRENT_BEHAVIOR`

Re-verified end to end against current code: `BehaviorGraphService.onStatusDeltas` calls `recordNormalizedOccurrence`, which appends to the active episode and executes `withEpisode(...).withCursor(...)`; `captureSituation` builds the trajectory from `active.timeline()` unfiltered; `LlmSituationTurnFactory` emits `occurrence.eventType().value()` into `trajectory`, `activeEventCounts` and `currentEventType`.

The **documentation** was wrong, not the code. `CLAUDE.md` has been corrected to state that Status-derived occurrences never become a trigger and never contribute a presentation, but do enter the timeline, can own the cursor, and are therefore visible to the model through the graph situation.

Contract statements now fixed for v2:

- `cursor.source` is `JOURNAL | STATUS | SYNTHETIC`.
- `trajectory` contains accepted significant normalized occurrences from **all** currently accepted graph sources.
- `trajectory` is temporal, **not** causal, **not** complete journal history, and does **not** imply correspondence to the final trigger.

**Graph behaviour was not changed.** Whether Status occurrences should be filtered out of the graph is a product decision and is explicitly not part of the v2 implementation.

### 24.4 Catalogue-wide semantic disposition

Every one of the 272 catalogued types now resolves to exactly one `SemanticDisposition`:

| Disposition | Count | How it is decided |
| --- | --- | --- |
| `STRUCTURED` | **101** | an adapter supplies the critical facts |
| `UNRESOLVED_AUTHORITATIVE_SEMANTICS` | **8** | declared in `SemanticDispositions` |
| `NO_CRITICAL_STRUCTURED_FACTS` | **5** | declared in `SemanticDispositions` |
| `DIAGNOSTIC_ONLY` | **158** | derived from the selection role |

`NO_SEMANTIC_ADAPTER` is no longer a normal outcome for a known catalogue type. It is now reserved for a payload the catalogue does not contain — in practice `UnknownJournalEvent`, which is exactly what the parser produces for an unrecognised discriminator.

The eight `UNRESOLVED_AUTHORITATIVE_SEMANTICS` types — `LaunchFighter`, `FighterDestroyed`, `SRVDestroyed`, `Disembark`, `Embark`, `DropshipDeploy`, `CrewMemberJoins`, `CrewMemberQuits` — all concern who or what is physically aboard which vessel. The projection keeps one vehicle slot, models neither taxi nor multicrew, and treats fighter control as unrelated to physical presence. Their adapters still produce every provable fact and record the gap explicitly.

The five `NO_CRITICAL_STRUCTURED_FACTS` types — `CockpitBreached`, `HeatDamage`, `SelfDestruct`, `SystemsShutdown`, `WingLeave` — carry no payload fields beyond the timestamp and discriminator. The operation *is* the entire content.

### 24.5 Coverage guard

`SemanticDispositionCoverageTest` enforces, at test time:

- all 272 catalogue types accounted for, each with exactly one disposition;
- role accounting unchanged at 109 / 5 / 158;
- no model-eligible type is `DIAGNOSTIC_ONLY`;
- every model-eligible type has an adapter or a declared exception;
- `CONTEXT_ONLY` types stay `CONTEXT_ONLY` and are `STRUCTURED`;
- an uncatalogued payload has no disposition;
- a model-eligible type absent from both the registry and the exception sets resolves to no disposition — which is how adding a catalogue type without deciding its semantics breaks the build.

The catalogue is package-private by design, so the guard reads it reflectively rather than widening production visibility for a test's benefit.

### 24.6 Structured-fact coverage

| Measure | Phase B | Phase B.1 |
| --- | --- | --- |
| Adapters registered | 34 | **117** |
| `NEW_ELIGIBLE` adapted | 26 / 109 | **109 / 109** |
| `CONTEXT_ONLY` adapted | 5 / 5 | **5 / 5** |
| `DIAGNOSTIC_ONLY` adapted | 3 / 158 | **3 / 158** |

Against the 49 code-derived KV2 mechanisms:

| Status | Phase B | Phase B.1 |
| --- | --- | --- |
| `COVERED` | 12 | **34** |
| `PARTIALLY_COVERED` | 30 | **9** |
| `UNRESOLVED_SOURCE_ARTIFACT` | 0 | **5** |
| `NOT_COVERED` | 6 | **0** |
| `NOT_APPLICABLE` | 1 | **1** |

**No pattern remains `NOT_COVERED` for want of an adapter.** The 5 `UNRESOLVED_SOURCE_ARTIFACT` rows are the vessel-occupancy mechanisms above: adapted, but with an authoritatively unprovable component. The 9 `PARTIALLY_COVERED` rows are mechanisms whose remaining source types are `DIAGNOSTIC_ONLY` and therefore not model input at all.

New adapters live in three mechanism-oriented groups — `CommerceSemanticAdapters`, `ConflictSemanticAdapters`, `ProgressionSemanticAdapters` — sharing `SemanticAdapterSupport`. None reads `llmPresentation()`.

### 24.7 Deferred to Phase C

1. The v2 DTO, serializer, compaction-ladder port and prompt cutover.
2. Adapters for the 155 unadapted `DIAGNOSTIC_ONLY` types — not model input, so not required.
3. OQ-3, OQ-4, OQ-5, OQ-7, OQ-8, OQ-9 remain open. OQ-1 is resolved (§24.3); OQ-2 is resolved for implementation (§23.3); OQ-6 is closed by removal (§23.4).

---

## 25. Phase C implementation record

Phase C is implemented. **The v2 document exists, is deterministic, and is measured. It is not sent to the model.** The production LLM still receives v1, byte-for-byte unchanged, verified against the recorded regression oracle.

### 25.1 Actual DTO classes

All under `src/main/java/kairon/observer/context/v2/`.

| Class | Role |
| --- | --- |
| `LlmSituationV2` | the model-facing document; nine top-level sections, each nested record self-validating |
| `SemanticValueJson` | the typed JSON shape of one `SemanticValue`; closed value-class set |
| `LlmSituationV2Policy` | limits; `production()` mirrors the v1 policy exactly (8 / 16 / 20 / 5 / 12 000) |
| `LlmSituationV2Inputs` | the fixed turn inputs, and the only thing the factory may read |
| `DeliveredModelComment` | a delivered comment plus the turn and evidence it rested on |
| `LlmSituationV2Factory` | pure projection from inputs to a document |
| `LlmSituationV2Serializer` / `JacksonLlmSituationV2Serializer` | deterministic JSON |
| `LlmSituationV2Compactor` | the ladder; returns a typed `Fitted` or `DoesNotFit` |
| `V2Names` | the explicit subject and field naming map |
| `LlmSituationV2ShadowRecord` | the diagnostic half of the OQ-5 separation |
| `LlmSituationV2ShadowSink` / `JsonLinesLlmSituationV2ShadowSink` | where a measurement goes; default no-op |
| `LlmSituationV2ShadowPipeline` | the temporary measurement path; `disabled()` in every production wiring |

`kairon.semantics` gained no dependency on the v2 DTO, on Jackson, or on `kairon.llm`.

### 25.2 Actual factory inputs

`LlmSituationV2Factory` reads `LlmSituationV2Inputs` and nothing else:

```
turnSequence                          the turn being prepared
triggers            List<ProjectedObservation>   ordered current-turn NEW observations
semanticEffects     SemanticEffectAccumulator.Drained   drained through the final trigger
previousComments    List<DeliveredModelComment>  up to three, oldest first
```

It never touches `CurrentGameStateProjector`, `BehaviorGraphService`, the observation bus, a mutable queue, a clock, the trace writer or v1 output. No state delta is recomputed: every `before`/`after` is copied verbatim from the `SemanticStateChange` the projection boundary produced.

One deliberate robustness rule: a trigger whose envelope is absent from the drained set contributes its own envelope, keyed by bus sequence so a duplicate is impossible. A wiring gap therefore cannot silently swallow a trigger's own canonical changes.

### 25.3 Actual JSON schema

`schemaVersion` = `"kairon-llm-situation-v2"`. Nine top-level sections: `currentState`, `graphContext`, `predictions`, `previousComments`, `schemaVersion`, `stateChanges`, `triggers`, `truncation`, `turn`.

Deviations from §13, each with its reason:

| §13 said | As built | Why |
| --- | --- | --- |
| `Trigger.fact` (singular) | `Trigger.structuredFacts` (list) + `Trigger.unresolvedFacts` (list) | `SemanticObservationEnvelope.structuredFacts` is a list; a singular field would silently drop facts |
| `turn.captureMode`, `sourceKind`, `graphApplyStatus`, `behaviorCaptureStatus` | moved to the shadow record | technical capture statuses are diagnostic; no decision rule consumes them (OQ-5) |
| `turn.capturedAfterBusSequence` and `stateChanges.capturedAfterBusSequence` | one field: `turn.finalTriggerBusSequence` | the capture point must not be stated three times |
| `graphContext.captureStatus`, `owner`, `graphRevision`, `topologyRevision`, `episode.episodeId` | moved to the shadow record | opaque identifiers and revisions (OQ-5) |
| `stateChanges.hiddenSourceEffects.items[]` | dropped; `hiddenSources` keeps `count` + `byRole` | every hidden effect that changed state is already itemised in `stateChanges.items` with `busSequence`, `rawEventType` and `sourceRole`; the block was pure duplication |
| `truncation.serializedCharacterCount`, `maxSerializedCharacters` | moved to the shadow record | a serialized length embedded in the document it measures has no fixed point |
| `predictions.items[].basis`, `globalProbability`, `contextSupport`; `predictions.basisAvailable` | **absent** | not carried by `SituationNextEventPrediction`; see OQ-11 |
| `graphContext.cursor.source` ∈ {JOURNAL, STATUS, SYNTHETIC} | JOURNAL, STATUS or `null` | see OQ-10 |
| `currentState.primaryShip.loadoutHash` | absent from model input | a module fingerprint with no decision value; the field catalog already classed it diagnostic |
| `stateChanges.items` sorted by (subject, field) | sorted by (busSequence, subject, field) | §6 of the Phase C brief requires ascending `busSequence` for every ordered collection, and recency is the novelty signal |
| `SemanticValue` JSON with four always-present fields | `kind` and `unit` omitted when absent | matches the contract's own example `{"type":"SYMBOL","value":"LANDED"}`; their presence is decided entirely by `type`, so a null carries no information. Measured saving: ~1 100 characters on the eight-trigger worst case |

`currentState` carries all ten mandated subjects. `occupiedVehicle` and `currentLocation` are always `null`, typed as `UnresolvedSubject` so resolving OQ-3 is additive rather than a contract change.

### 25.4 Actual null semantics

- Nullable scalars serialize as explicit JSON `null` and always mean **not known**.
- `commanderPresence.mode` and `navigationContext.flightMode` are never null; `UNKNOWN` is a value.
- `SemanticValueJson` distinguishes unknown from empty, zero and false: only `type: "UNKNOWN"` carries a null `value`, and a known empty string is `{"type":"TEXT","value":""}`.
- `Trigger.presentation` is `null` when the event has no sourced presentation **or** when the ladder removed it. An empty string is never emitted, because an empty string is a value and absence is not.
- `cursor.source` is `null` when the repository cannot prove what moved the cursor.
- `previousComments` is `[]`, never `null`.

### 25.5 Actual ordering

Property order is alphabetical at every nesting level (`MapperFeature.SORT_PROPERTIES_ALPHABETICALLY`), exactly as v1, so a v1/v2 size comparison measures the contract rather than the encoder. Collection order is fixed by the DTO and validated by it:

```
triggers                 ascending busSequence, unique
stateChanges.items       ascending (busSequence, subject, field)
hiddenSources.byRole     role name ascending
trajectory.items         ascending episodeSequence
occurrenceCounts.items   normalized event type ascending
predictions.items        probability descending, then type ascending (domain order)
previousComments         oldest to newest
qualifiers               key ascending
```

### 25.6 Actual compaction policy

Budget unit: **Java String characters — UTF-16 code units, `String.length()`.** Not code points, not UTF-8 bytes, and not tokens. This is the unit v1 already uses and Phase C did not change it. The shadow record additionally reports the UTF-8 byte count, for information only.

Ladder, weakest first: active-episode occurrence counts → trajectory occurrences (floor 2, keeping the episode root and the most recent) → predictions → previous-comment text (binary search, floor one character) → trigger presentation text (binary search, may reach removal) → state-change items, oldest first. Then `DoesNotFit`.

Never dropped: schema version, turn correlation, trigger identity and bus sequence, structured facts, explicit negations, process stage and completion, unresolved facts, source roles, current subject state, suppression awareness, truncation counters.

Every drop increments a counter in `truncation.omittedCountsBySection`, and `truncation.applied` is true whenever the document is not the complete available context — including when a policy limit, not budget pressure, caused the loss.

Shortening never splits a surrogate pair, so a truncated presentation is always well-formed text and therefore always well-formed JSON.

### 25.7 Actual shadow path

`ObserverTurnCoordinator` gained one field, `LlmSituationV2ShadowPipeline`, defaulting to `disabled()`. Every existing constructor delegates to the disabled form, so no production wiring builds a v2 document at all. A sink is injected explicitly by a test or a manual harness. There is no configuration key, no feature flag and no runtime selector; a test walks `src/main/java` and fails if one ever appears.

The measurement runs strictly after the v1 turn is fully prepared and cannot alter it. A factory failure, a serializer failure or a throwing sink is contained and reported as a `FAILED` record.

`JsonLinesTurnTraceWriter` is untouched; the trace schema stays `kairon-turn-trace-v3` and the v1 `TurnTrace` record gains no field. The shadow writes a separate JSONL artifact under `target/audit/`.

Two production files changed beyond the v2 package, both justified:

- `ObserverTurnCoordinator` — holds the shadow pipeline and passes it the already-fixed turn inputs. Explicitly in Phase C's allowed list.
- Its previous-comment memory changed from `Deque<String>` to `Deque<DeliveredModelComment>`, so `turnSequence` and `evidenceTriggerBusSequences` — both in scope at the exact moment a comment is appended, and both previously discarded — survive. `ObserverSnapshot.previousComments()` still returns `List<String>`, and the v1 factory still receives `List<String>`, so nothing observable changed for v1 or the GUI.

### 25.8 OQ-5 — resolved

**Resolution: two separate immutable representations, and no change to the existing trace.**

Model-facing (`LlmSituationV2`): structured facts, exact state changes with provenance, subject state, graph cursor semantics, trajectory semantics, prediction probability and observed count, previous-comment authority, truncation awareness.

Diagnostic-only (`LlmSituationV2ShadowRecord`): capture mode, technical source kind, graph apply and behavior capture status, graph owner, graph and topology revisions, episode id, decayed prediction weights, loadout hash, character counts, UTF-8 byte count, per-section character counts, serializer identity.

The recommendation in §15 was a `TurnTrace` sibling field and a `kairon-turn-trace-v4` bump. That is **not** what was built. A sibling field would have coupled a temporary measurement to the permanent trace contract and forced a schema bump for something deleted at Phase E. A separate artifact keeps the `situationTurn` byte-identity invariant untouched, leaves the trace at `v3`, and disappears cleanly. Phase D still needs `contextSchema` to become the v2 string; that is a Phase D change, not a Phase C one.

### 25.9 OQ-7 — measured

Sixteen cases: four driven end to end through the real bus, projection coordinator, behavior graph, subscriber and turn coordinator; six mirroring the existing v1 unit fixtures; six synthetic boundary probes. Artifacts: `target/audit/kairon-llm-situation-v2-shadow-sizes.jsonl` and `target/audit/kairon-llm-situation-v2-size-analysis.json`.

| Measure | Value |
| --- | --- |
| Budget | 12 000 Java String characters |
| Minimum / median / maximum after compaction | 2 567 / 6 548 / 11 996 |
| p95 after compaction | 11 996 (16 cases — an order statistic, **not** a significant estimate) |
| Maximum before compaction | 38 825 |
| Cases above budget before compaction | 4 (1 pipeline replay, 3 synthetic) |
| Cases above budget after compaction | **0** |
| Cases unable to fit mandatory semantics | **0** |
| Cases losing state changes | 2, both synthetic worst-case probes |
| Pipeline-replay and unit-fixture cases losing state changes | **0** |

**Verdict: v2 fits the hardcoded 12 000-character budget, and the migration plan's exit condition — length within budget with `stateChangesOmitted == 0` — holds on every real-pipeline and unit-fixture case.** It fails only on two deliberately extreme synthetic probes: ten hidden body scans between turns, and an eight-trigger batch on top of eight hidden scans. Those lose 85 and 90 state-change items respectively, always with an explicit count.

Two residual risks are named rather than dismissed:

1. **Four real cases is a small sample.** One of them (`pipeline/organic-sampling-sequence`) already needed compaction, arriving at 13 593 characters and fitting at 11 996 by dropping five trajectory occurrences, seven count entries and shortening six presentations. Ordinary exploration is close to the ceiling.
2. **A long hidden-history burst does force state-change loss.** The loss is reported, never silent, but it defeats the point of v2 for that turn. Raising the hardcoded limit is a code change to `LlmSituationPolicy`, which Phase C is not allowed to make.

Section weight, summed over all sixteen cases: `triggers` 46 953, `stateChanges` 34 501, `graphContext` 17 315, `currentState` 10 675, `predictions` 3 697, `truncation` 3 461, `turn` 2 971, `previousComments` 424.

### 25.10 New open questions

- **OQ-10 — cursor source cannot be fully established.** `SituationOccurrence` and `GraphCursor` do not carry `originalEventName`, so from immutable turn inputs only two derivations are sound: a cursor committed by one of this turn's own triggers is `JOURNAL`, and a cursor carrying one of the six normalized types only `StatusStateDeltaAdapter` emits is `STATUS`. A cursor moved by a hidden graph-significant `DIAGNOSTIC_ONLY` journal event and a synthetic ship-switch root are indistinguishable, so both yield `null`. **`SYNTHETIC` is therefore unreachable in Phase C.** Fixing it means adding `originalEventName` to the situation snapshot — a graph-layer change Phase C forbids. *Gates the full §13.6 contract; must be decided before Phase D.*
- **OQ-11 — prediction basis cannot reach the model.** `NextEventPrediction` carries `basis`, `globalProbability` and `contextSupport`; `SituationNextEventPrediction` keeps none of them. §11 makes `basis` mandatory and model-facing, and §13.14 depends on it to make `probability: 1.0` at `observedTransitionCount: 1` legible. Phase C reports the factual count without it. Restoring it is a graph-layer change Phase C forbids. *Blocks the §11.3 mitigation; must be decided before Phase D.*

### 25.11 Temporary components scheduled for deletion at Phase E

| Component | Why it exists |
| --- | --- |
| `LlmSituationV2ShadowPipeline` | builds and measures v2 while v1 is still live |
| `LlmSituationV2ShadowSink`, `LlmSituationV2ShadowSink.NoOp` | where a measurement goes; off by default |
| `JsonLinesLlmSituationV2ShadowSink` | the measurement artifact writer |
| `LlmSituationV2ShadowRecord` and its nested types | the diagnostic representation |
| `ObserverTurnCoordinator.situationShadow` and the constructor overload that supplies it | the only injection point |
| `LlmSituationV1UnchangedByPhaseCTest` | pins that Phase C changed nothing observable in v1 |
| `V2ProductionPipeline`, `LlmSituationV2ShadowMeasurementTest` | the measurement harness |
| `LlmSituationV2Factory.relation(...)` | duplicates the v1 derivation; collapses to one copy when v1 goes |
| `LlmSituationTurn`, `JacksonLlmSituationTurnSerializer`, `LlmSituationTurnFactory` | the v1 path |
| `target/audit/kairon-llm-situation-v1-baseline-*.jsonl` | the temporary regression oracle |

Permanent: the v2 DTO, factory, serializer, compactor, policy, `SemanticValueJson`, `V2Names` and `DeliveredModelComment`.

### 25.12 Phase D prerequisites

1. **Decide OQ-10.** Either add `originalEventName` to the situation snapshot so `cursor.source` is complete, or accept `null` and say so in the prompt.
2. **Decide OQ-11.** Either carry `basis` / `globalProbability` / `contextSupport` through `SituationNextEventPrediction`, or drop the §11.3 basis mitigation and rely on `observedTransitionCount` alone — and write the prompt accordingly.
3. **Decide the budget.** Either accept that a long hidden-history burst loses counted state changes, or raise `LlmSituationPolicy.maxSerializedCharacters`, which is hardcoded and therefore a code change.
4. **Rewrite `SYSTEM_PROMPT` for v2** and switch the sender; v1 stops being constructed at that moment.
5. **Update `TurnTrace.contextSchema`** to the v2 schema string.
6. **Update the prompt-fragment assertions** in `ObserverPipelineTest` and `OpenAiCompatibleLlmClientTest`.
7. **Generalise or replace `ObserverResponseValidator`** so it validates against the v2 trigger set. Phase C did not touch it: the validator and the response DTO are outside its allowed files, and v2 never reached a provider, so nothing needed validating.
8. **Do not merge Phase B and Phase D.** Still the rule.

### 25.13 Test coverage added

64 new tests across nine classes, all under `src/test/java/kairon/observer/context/v2/`: schema and null semantics, triggers and the nine regression anchors, state changes and the subject model, graph context and cursor labelling, predictions, previous comments, compaction, shadow isolation, shadow measurement, and the v1-unchanged oracle. Suite total 551 run, 0 failures, 0 errors, 1 skipped — the one skip remains the opt-in `BehaviorGraphManualReplayTest`.

`target/snapshot-model-input-examples.jsonl` and `target/observer-response-contract-examples.jsonl` regenerate byte-identically to the recorded v1 baselines.

---

## 26. Phase C.1 implementation record

Phase C.1 closes the three Phase D blockers Phase C recorded. **The v1 path remains byte-for-byte unchanged and is still the only production model input.**

### 26.1 Occurrence provenance is recorded, not inferred

`EventOccurrenceSource` — a closed enum with one value per production write path:

| Value | Recorded by |
| --- | --- |
| `JOURNAL` | `BehaviorGraphService.startJournalRoot`, `recordJournalOccurrence` |
| `STATUS` | `BehaviorGraphService.onStatusDeltas` |
| `SYNTHETIC` | the ship-switch episode root |

`EventOccurrence` carries it; `recordNormalizedOccurrence` — the method both the journal and Status paths share — takes it from its caller and rejects null. It flows unchanged into `SituationOccurrence`, and from there into `graphContext.cursor.source` and `graphContext.trajectory.items[].source`.

**OQ-10 is resolved.** The Phase C derivation — trigger-occurrence identity plus a closed set of Status-only normalized types — is deleted. `LlmSituationV2Factory` no longer names a single normalized type for this purpose, pinned by a test that reads its source. Two Phase C gaps close with it: a cursor moved by a hidden graph-significant `DIAGNOSTIC_ONLY` journal event now reports `JOURNAL` instead of `null`, and `SYNTHETIC` is reachable.

Nothing else changed: normalization, significance, transition creation, probability calculation, episode lifecycle and persistence identity are untouched, and Status occurrences still never become triggers.

**Persistence limitation, exactly.** `SituationOccurrence` is not persisted. `EventOccurrence` is, inside `SystemEpisode`, and the store deserializes it with `FAIL_ON_UNKNOWN_PROPERTIES` and no schema-version field. Provenance is therefore `@JsonIgnore`: the persisted JSON is byte-for-byte what it was, no migration is required, and an occurrence restored from disk reports `null`. `null` reaches the model as an absent source and is never replaced by a post-reload guess from `originalEventName`, although `"Status"` and `"ShipSwitch"` would make one possible. The minimal migration — drop `@JsonIgnore`, keep the component nullable, add a schema-version gate — is proposed in the Phase C.1 report and deliberately not performed.

### 26.2 Prediction semantics propagate

`SituationNextEventPrediction` went from five components to ten, all copied verbatim by `situationPredictions`:

```
sourceEventType, predictedEventType, probability, basis, globalProbability,
observedTransitionCount, contextObservedTransitionCount, contextSupport,
contextKey, effectiveWeight
```

Only `contextObservedTransitionCount` is new to the domain: `NextEventPrediction.contextRawTransitionCount`, read from the per-bucket counter the graph already stores. No weight, probability or basis depends on it, and no prediction value in the suite changed.

Model-facing: `predictedEventType`, `probability`, `basis`, `globalProbability`, `observedTransitionCount`, `contextObservedTransitionCount`. Diagnostic: `contextSupport`, `contextKey`, `effectiveWeight`. No `confidence`, no qualitative band, no thresholds. There is no separate `globalObservedTransitionCount` because it would be the same number as `observedTransitionCount`.

**OQ-11 is resolved.**

### 26.3 §11.4 was wrong — `basis` is weaker than the design assumed

§11.4 states that `contextKey` is non-`EMPTY` for only two of 52 normalized types and that "for the other 50, contextual prediction is structurally unavailable and `basis` is always `GLOBAL`". The second half is false.

`VERIFIED_CURRENT_BEHAVIOR`: `TransitionEdge.record` stores a counter for whatever key it receives, `EMPTY` included, and `TransitionProbabilityCalculator.predict` sets `basis = CONTEXTUAL` whenever the summed context weight exceeds zero. `EMPTY` is an ordinary bucket, so `basis` flips to `CONTEXTUAL` for any cursor type once that bucket has weight. The error surfaced as an immediate test failure when an invariant asserting the opposite was added, and the invariant was removed.

The contract therefore carries `predictions.contextDistinguishes`, true only when the key is non-`EMPTY`. A `CONTEXTUAL` basis with `contextDistinguishes: false` is evidence from the catch-all bucket, not from a narrower situation. This replaces the §13.7 name `basisAvailable`, which reads as "the basis field is available" and would mislead given the finding.

### 26.4 State changes are not compactable

Phase C called state changes mandatory and still dropped the oldest under budget pressure. Phase C.1 removes the path:

- no state-change step in the compaction ladder;
- no `stateChangeLimit` in `BuildOptions`;
- no `stateChanges` component in `OmittedCounts` — an always-zero counter would imply a mechanism that does not exist;
- `BuildOptions.mandatoryOnly(policy)` names the floor, and the compactor exposes `mandatoryOnly(inputs)` and `mandatoryCharacterCount(inputs)`.

The only remaining compression of semantic history is the accumulator's upstream coalescing, which preserves the earliest before, the latest after and the change semantics, evicts no canonical change, and always attaches a typed suppression marker.

`Result.DoesNotFit` now carries `turnSequence`, `firstTriggerBusSequence`, `finalTriggerBusSequence`, `mandatoryCharacterCount`, `configuredCharacterBudget`, `originalCharacterCount` and `largestMandatorySections` heaviest first, and rejects construction when the mandatory content actually fits. No document accompanies it.

The §14 ladder is amended accordingly: priority 8 (`stateChanges` last) is withdrawn, and the ladder ends at priority 7.

### 26.5 Budget re-measurement

Sixteen cases against five budgets. Full detail in `target/audit/kairon-llm-situation-v2-budget-decision.md` and `-budget-matrix.csv`.

| Budget | Fitting | Real failures | Synthetic failures | Max mandatory | Losing optional | State changes omitted |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 12 000 | 14 | 0 | 2 | 34 349 | 4 | **0** |
| 16 000 | 14 | 0 | 2 | 34 349 | 1 | **0** |
| 24 000 | 14 | 0 | 2 | 34 349 | 1 | **0** |
| 32 000 | 14 | 0 | 2 | 34 349 | 1 | **0** |
| 48 000 | 16 | 0 | 0 | 34 349 | 2 | **0** |

Three distributions kept apart, because compacted sizes are clamped by the ceiling and describe it rather than the workload (16 cases; compacted covers the 14 that produced a document):

| Distribution | Min | Median | p95 | Max |
| --- | ---: | ---: | ---: | ---: |
| Original | 2 579 | 7 249 | 39 526 | 39 526 |
| Mandatory-only | 2 400 | 5 947 | 34 349 | 34 349 |
| Final compacted | 2 579 | 6 541 | 11 999 | 11 999 |

**OQ-7 is re-answered under the stricter policy.** The largest real mandatory content is 11 909 of 12 000 — 91 characters of headroom. **Recommended Phase D budget: 16 000, with fail-closed on `DoesNotFit`.** It removes all budget-driven compaction from every real and fixture case; 24 000 and 32 000 change nothing measurable; 48 000 admits only two deliberately extreme synthetic probes at the cost of a 39 526-character document. Segmentation was assessed and rejected: it would change the evidence contract and the batching model. No v1 fallback is proposed.

### 26.6 Deviations recorded

| Design said | As built | Why |
| --- | --- | --- |
| §13.7 `predictions.basisAvailable` | `predictions.contextDistinguishes` | §26.3 — the original name misstates what the flag means once `EMPTY` is known to be a real bucket |
| §14 ladder priority 8: `stateChanges` last | no state-change step at all | a counted loss of the novelty signal is still a loss |
| §13.9 `truncation.stateChangesOmitted` | removed | there is no mechanism it could describe |
| `cursor.source` derived downstream | recorded at acceptance on `EventOccurrence` | §26.1 |
| `trajectory.items[]` without a source | `source` per item | one normalized type from two sources must stay distinguishable |

### 26.7 Still open

- **Provenance is not persisted** (§26.1). Occurrences restored from the store report absence.
- **The recommended budget is not implemented.** `LlmSituationPolicy.maxSerializedCharacters` is still a hardcoded 12 000.
- **`DoesNotFit` has no production behavior.** The type is complete; nothing consumes it.
- **The measurement sample is 16 hand-assembled cases**, four of them production-pipeline replays. No captured live session was measured.

---

## 27. Phase D implementation record — production cutover

**`kairon-llm-situation-v2` is the production model input. v1 is production-dead.**

### 27.1 The production path

```
NEW trigger batch + drained semantic effects + previous delivered comments
  -> LlmSituationV2Inputs
  -> LlmSituationV2Compactor (16 000 characters)
       -> Fitted     -> SituationV2PromptFactory -> LlmClient
       -> DoesNotFit -> CONTEXT_TOO_LARGE, no provider call
```

`ObserverTurnCoordinator` builds the inputs from what it already holds and recomputes nothing: state delta, source role, structured facts, prediction basis and occurrence source were all fixed upstream. No projector, graph service, bus, queue, clock, trace writer or GUI is read while building. Ordering is the one fixed in §25.5 and §26, unchanged.

The user message is `"CURRENT SITUATION\n\n"` plus the exact serialized document, and nothing else.

### 27.2 v1 disconnection

`KaironApplication` no longer constructs the v1 factory, serializer, policy or prompt. `ObserverTurnCoordinator` and `ObserverResponseValidator` no longer reference the v1 DTO. The v1 classes remain on disk, unedited, for Phase E to delete.

An architecture test walks `src/main/java`, excludes only the v1 classes themselves, and fails if any other production source mentions the v1 factory, serializer, prompt factory, schema string or `SCHEMA_VERSION`. A fallback, a version selector or a compatibility adapter would all trip it. A second test proves the temporary shadow pipeline is referenced by nothing outside its own package.

### 27.3 Prompt

`SituationV2PromptFactory.SYSTEM_PROMPT` replaces the v1 prompt. It states the rules the JSON shape cannot enforce — evidence restricted to current triggers, structured facts over prose, exact novelty from `stateChanges` rather than snapshot comparison, the ten separated subjects, unknown semantics including a null occurrence source, non-causal graph context, prediction reliability including the catch-all `CONTEXTUAL` case, previous-comment non-authority, and truncation honesty — and deliberately does not restate the schema, which would create a second contract able to drift from the first.

`SituationV2PromptFactoryTest` pins each rule, and maps every historically observed misreading to the rule that forbids it. Those tests assert what the input said; they do not simulate a model, which ADR-0010 forbids reasoning about before the input is inspected.

### 27.4 Evidence scope

`ObserverResponseValidator.validate(rawOutput, ObserverTurnEvidenceScope, previousComments)`. The scope carries the turn sequence and the citable bus sequences, nothing else, so the response contract is now independent of the context schema and cannot be widened by accident. Every existing violation code and rule is retained.

### 27.5 Budget — OQ-13 implemented

`LlmSituationV2Policy.production()` = `(8, 16, 20, 5, 16_000)`, hardcoded in one typed object. The unit remains Java String characters (`String.length()`), never tokens. The evidence is §26.5 and the budget-decision artifact.

State changes stay outside the compaction ladder: no limit, no oldest-first removal, no counter.

### 27.6 `CONTEXT_TOO_LARGE`

Fail closed. On `DoesNotFit`: zero provider calls, zero speech calls, no comment, no synthesised silence, previous-comment memory untouched, a typed turn outcome, a trace record with the overflow detail, and a GUI diagnostic. No retry at a higher budget, with less content, with a segmented context, or on v1.

The batch is consumed exactly once and its triggers are never replayed, because the next turn's evidence scope cannot contain this turn's bus sequences.

The carrier is `ObserverContextOverflow`: turn sequence, first and final trigger bus sequence, mandatory character count, configured budget, original character count, overshoot, and the heaviest mandatory sections.

### 27.7 GUI

No new port. `ValidatedObserverResponse` gained the status `CONTEXT_TOO_LARGE` beside `MODEL_CALL_FAILED` — the same kind of fact, a turn with no decision — so the existing observer status/error path carries it to `KaironGuiHub.ModelDecisionView` with a null decision, null text and the diagnostic sentence in `failure`. It is never presented as commentary and never spoken.

### 27.8 Trace — `kairon-turn-trace-v4`

Bumped because the shape changed, not merely the payload: `situationTurn` and `modelInput` became nullable, and `turnOutcome`, `providerInvoked`, `commentDelivered`, `speechInvoked`, `situationCharacterCount` and `contextOverflow` are new. A `v3` reader cannot consume a null `situationTurn`. The invocation flags are recorded rather than inferred, and the record's compact constructor rejects inconsistent combinations, so an impossible trace cannot be written.

### 27.9 OQ status after Phase D

- **OQ-12 — accepted limitation.** Occurrence provenance is in-process only; a restored occurrence reports `null`, the prompt says what that means, and nothing guesses. The minimal persistence migration stays proposed and deferred.
- **OQ-13 — implemented.** 16 000 characters with fail-closed overflow.
- **OQ-3, OQ-4, OQ-8, OQ-9** remain open and unchanged; none gates production.

### 27.10 Residual risks, stated

1. The budget rests on 16 hand-assembled cases, four of them pipeline replays. No captured live session has been measured.
2. A character budget bounds the document, not the request: no tokenizer measurement exists, so nothing here says the request fits a given model's context window.
3. Overflow remains possible. It fails closed and is recorded, which is the point.
4. Model behavior on v2 has not been evaluated. ADR-0010 applies, and the trace now records the exact supplied input for that purpose.

---

## 28. Phase E completion record — v1 and migration scaffolding removed

**The migration is finished. `kairon-llm-situation-v2` is the only context that exists, not merely the only one used.**

Phase E deleted rather than disconnected. Nothing was renamed, stubbed, kept behind a deprecated facade, or carried forward under a new name, and no semantic behavior changed: every deletion was of code no production path reached.

### 28.1 v1 physically deleted

| File | Was |
| --- | --- |
| `src/main/java/kairon/observer/context/LlmSituationTurn.java` | the v1 DTO and its schema constant |
| `src/main/java/kairon/observer/context/LlmSituationTurnFactory.java` | the v1 factory and its compaction ladder |
| `src/main/java/kairon/observer/context/LlmSituationTurnSerializer.java` | the v1 serializer interface |
| `src/main/java/kairon/observer/context/JacksonLlmSituationTurnSerializer.java` | the v1 serializer |
| `src/main/java/kairon/observer/context/LlmSituationPolicy.java` | the v1 policy, including the superseded 12 000-character budget |
| `src/main/java/kairon/llm/SituationSnapshotPromptFactory.java` | the v1 prompt |

`TriggerRelation` stayed: it is not a v1 type. `LlmSituationV2` and `LlmSituationV2Factory` both use it, and `LlmSituationV2Factory.relation(...)` — recorded in §25.11 as a temporary duplicate of the v1 derivation — is now simply the only derivation. The duplicate disappeared by deleting the other copy, exactly as planned.

### 28.2 Shadow path physically deleted

`LlmSituationV2ShadowPipeline`, `LlmSituationV2ShadowSink` (with `NoOp`), `JsonLinesLlmSituationV2ShadowSink` and `LlmSituationV2ShadowRecord` (with `Diagnostics`, `SectionSize`, `ShadowSourceKind`) are gone, together with `JacksonLlmSituationV2Serializer.SERIALIZER_ID`, whose only reader was the shadow record.

There was no shadow field, constructor parameter or invocation branch left in `ObserverTurnCoordinator` to remove: Phase D had already replaced that constructor when it cut over. The coordinator therefore performs exactly **one** serialization pass per turn — the one that is sent — and has no second document, no failure handling for one, and no injection point for one.

`LlmSituationV2Serializer.serializeFragment(...)` was **kept**. It is no longer a measurement hook: `LlmSituationV2Compactor` uses it to attribute the budget to sections so `Result.DoesNotFit` can name its heaviest mandatory content.

### 28.3 Tests and fixtures deleted

| Test | Existed for | Tests |
| --- | --- | ---: |
| `observer/context/LlmSituationTurnFactoryTest` | v1 byte-identity, v1 JSON paths, v1 field absence; wrote `target/snapshot-model-input-examples.jsonl` | 9 |
| `observer/context/v2/LlmSituationV1UnchangedByPhaseCTest` | the v1 regression oracle | 4 |
| `observer/context/v2/LlmSituationV2ShadowMeasurementTest` | the Phase C size-measurement harness | 3 |

Two assertions in the oracle were **not** about v1 and were moved into the permanent guard rather than dropped: the exact `LlmSituationV2Policy.production()` tuple including the 16 000-character budget, and the `kairon-turn-trace-v4` record shape with its six new components and the absence of any shadow-named component.

`V2ProductionPipeline` was **kept** — it is the real end-to-end pipeline harness that `LlmSituationV2ProductionInputTest`, `LlmSituationV2ContextTooLargeTest` and `LlmSituationV2TurnOutcomeTest` all drive. Only the measurement test that also used it was temporary.

`OpenAiCompatibleLlmClientTest` was retargeted from the deleted v1 prompt constant to `SituationV2PromptFactory.SYSTEM_PROMPT`. It asserts the same thing it always did: the prompt reaches the wire unmodified.

No assertion anywhere was weakened, and no permanent test was consolidated away.

### 28.4 Migration guard replaced by a permanent contract guard

Phase D's guard excluded the v1 classes themselves from its scan, because they still existed. With them deleted that exclusion is meaningless, so `LlmSituationV2ProductionInputTest` now proves the stronger statement — that the retired path does not exist anywhere:

| Guard | Proves |
| --- | --- |
| `theProductionContextContractIsExactlyOneVersion` | context schema `kairon-llm-situation-v2`, trace schema `kairon-turn-trace-v4`, policy `(8, 16, 20, 5, 16 000)`, the v4 trace shape, no shadow-named trace component |
| `theCoordinatorHasNoSecondContextOrMeasurementInjectionPoint` | exactly two public constructors, each taking `LlmSituationV2Compactor` and `SituationV2PromptFactory`, none accepting a measurement pipeline |
| `theOnlyProductionContextPackageIsV2` | `kairon/observer/context` contains only `TriggerRelation.java` and `v2`; exactly one production prompt factory; exactly one `LlmSituationV2Serializer` implementation |
| `noProductionSourceMentionsTheRetiredContextPath` | no production source names any of the six deleted v1 types or the retired schema identifier — no fallback, no selector, no compatibility adapter |
| `noSourceAnywhereCarriesTheRetiredSchemaOrTheMeasurementPath` | neither the retired schema identifier nor any of the four shadow types appears in `src/main/java` or `src/test/java` |
| `theProviderReceivesExactlyOneV2DocumentAndNothingElse` | one request per turn, the exact user message, the nine sections, no retired schema in either message |
| `theTraceRecordsTheExactV2ContextThatWasSent` | the trace keeps the sent context byte for byte, at `v4`, with the typed outcome and the invocation flags |

The guard derives the retired identifier as `LlmSituationV2.SCHEMA_VERSION.replace("v2", "v1")` so the file that forbids the string does not contain it, and the two prompt negative-assertions in `ObserverPipelineTest` and `SituationV2PromptFactoryTest` were changed the same way. Those assertions are unchanged in strength; the literal is simply constructed instead of written. A repository-wide search for the retired identifier now returns nothing.

The guard names the deleted types as string literals in order to forbid them, and excludes only its own source file from the repository-wide scan. That exclusion is stated in the code.

### 28.5 Deprecated compatibility cleanup — one deliberate retention, one refused deletion

**`BodyTypeCompatibilityProjection` is kept, and this is not an oversight.** After the v1 factory was deleted it still has a live production consumer: `BehaviorContextAdapter` calls `compatibleBodyType(state)` to fill `ContextSnapshot.bodyType`, which the graph persists and the Swing occurrence inspector displays. Deleting it would change graph behavior, which Phase E may not do. The canonical dimensions `broadBodyType`, `planetClass` and `starType` are untouched and remain what the model sees.

**`CurrentGameStateSnapshot.bodyType()` is also kept, against the Phase D deletion list, because the safety rule for it is not satisfied.** The evidence, gathered rather than assumed:

| Condition | Result |
| --- | --- |
| Production references | **none** — the only former caller was the v1 factory, and it called `BodyTypeCompatibilityProjection` directly, not this accessor |
| v2 references | none |
| Graph snapshot references | none — the graph reads the projection directly |
| Persistence references | none |
| Tests using it as a live public contract | **yes** — `CurrentGameStateProjectorTest.bodyTypeCompatibilityAccessorKeepsPreMigrationBehavior` exists solely to pin it, and `projectsExistingIdentityLoadoutLocationAndBodySemantics` asserts through it |
| Removal changes canonical classification | no |

Five of six conditions hold; the fifth does not. "Pre-migration" in that test name refers to the earlier body-classification split, not to the v1 to v2 migration, so the test is permanent coverage rather than scaffolding. Deleting the accessor would therefore be a test change, not a dead-code deletion, and Phase E does not edit permanent tests to enable an optional deletion. Its javadoc now records the finding instead of claiming consumers that no longer exist.

### 28.6 Migration narrative removed from permanent code

Comments and javadoc that described the migration rather than the code were rewritten wherever they survived: the shadow-record cross-references in `LlmSituationV2` and `V2Names`, the v1 size-comparison note in `JacksonLlmSituationV2Serializer`, the Phase C/C.1 history in `LlmSituationV2Compactor`, the "duplicate disappears at Phase E" note on `LlmSituationV2Factory.relation(...)`, the v1 unit note in `LlmSituationV2Policy`, the v1 output reference in `LlmSituationV2Inputs`, the Phase C note in `DeliveredModelComment`, the "v1 previous-comment shape" comment in `ObserverTurnCoordinator`, the "no v1 construction here" comment in `KaironApplication`, the "a later phase cannot read" phrasing in `SemanticSuppression`, and the "LLM v1 consumers" statement in `BodyTypeCompatibilityProjection`.

Two references to disposable build artifacts were replaced with references to durable documents, because `target/` is deleted by `mvn clean`: `LlmSituationV2Policy` no longer points at the budget-decision artifact, and `EventOccurrence` no longer points at the Phase C.1 report.

`JsonLinesTurnTraceWriter`'s note that `v4` was bumped from `v3`, and `SemanticField`'s note that the deprecated flat `bodyType` is deliberately not a semantic field, were **kept**: both state a permanent property of the current contract rather than migration history.

`docs/design/kairon-llm-situation-v2-handover.md` was deleted. It was migration navigation, it declared "Phase C has not started", and every path it pointed at is gone.

### 28.7 Temporary artifacts deleted

`target/audit/kairon-llm-situation-v1-baseline-model-input.jsonl` and `-response-contract.jsonl` — the v1 regression oracle — are deleted. They were the reason `mvnw.cmd clean` was previously forbidden; that prohibition is lifted.

Everything else under `target/audit/` is a historical design report or measurement, disposable by definition, and `mvn clean` removes it. Nothing durable depends on it any more: the budget evidence is summarised in §26.5 and in `CURRENT_STATE.md`, and the deletion decisions are in this section.

`target/snapshot-model-input-examples.jsonl` stops being produced, because its writer was the v1 unit test. It is deliberately not replaced: the turn trace already records the exact context that was sent, and `LlmSituationV2ProductionInputTest` asserts that identity byte for byte. `target/observer-response-contract-examples.jsonl` is unaffected.

### 28.8 Production behavior verification

Unchanged, and covered by their existing suites: semantic adapter dispositions, structured facts, canonical state projection, state-change generation, `ACTIVATED_FROM_CONTEXT` write-path semantics, the semantic effect accumulator, source-role wiring, graph normalization, significance, episode lifecycle, occurrence provenance, prediction calculations and support fields, the 16 000-character policy, the compaction order, mandatory state-change retention, `DoesNotFit`, the `CONTEXT_TOO_LARGE` lifecycle, evidence validation, the `SILENT`/`COMMENT` contract, previous-comment memory, TTS, event selection, batching, graph persistence, the GUI overflow diagnostic and the trace v4 shape.

No file under `kairon.behavior`, `kairon.semantics`, `kairon.state`, `kairon.output`, `kairon.speech`, `kairon.observation`, `config/**` or `pom.xml` changed in a way that alters behavior; the edits there are javadoc only.

### 28.9 Clean-build validation

```
mvnw.cmd test        571 run, 0 failures, 0 errors, 1 skipped, BUILD SUCCESS
mvnw.cmd clean test  571 run, 0 failures, 0 errors, 1 skipped, BUILD SUCCESS
```

The pre-Phase-E baseline was 584. The difference is fully accounted for by the three deleted test classes and the guards added to replace them:

```
584 - 9 (LlmSituationTurnFactoryTest)
    - 4 (LlmSituationV1UnchangedByPhaseCTest)
    - 3 (LlmSituationV2ShadowMeasurementTest)
    + 3 (LlmSituationV2ProductionInputTest: 4 tests -> 7)
    = 571
```

The per-class counts are read from the surefire reports of the pre-Phase-E run, not estimated. The one skip is still the opt-in `BehaviorGraphManualReplayTest`.

The clean build matters for a specific reason: stale `target/classes` entries for deleted v1 types could otherwise satisfy a reference that no longer has a source file. After `clean test` no compiled artifact of any deleted type exists in `target/classes` or `target/test-classes`.

### 28.10 Accepted limitations, carried forward unchanged

1. **Occurrence provenance is not persisted.** An occurrence restored from the graph store reports `null`, which reaches the model as an absent source and is never guessed from an event name. The minimal migration is in §26.1 and stays deferred.
2. **The budget rests on a limited sample** — 16 hand-assembled cases, four of them production-pipeline replays. No captured live session has been measured.
3. **No tokenizer measurement exists.** A character budget bounds the document, never the request; nothing in the repository reads a provider context window.
4. **Vessel ontology stays unresolved.** Vehicle occupancy, taxi context, multicrew context and fighter presence are recorded as unresolved facts rather than inferred, because the projection keeps one vehicle slot.

### 28.11 Deferred future work

1. Live-corpus budget validation through `BehaviorGraphManualReplayTest`.
2. Persisted occurrence provenance (§26.1), which needs its own ADR because it changes the store schema.
3. Evaluation of model behavior on v2 under ADR-0010.
4. OQ-3, OQ-4, OQ-8 and OQ-9 remain open. None gates production.

No v3 is proposed, and no new feature was started in this phase.
