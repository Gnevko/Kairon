# kairon-llm-situation-v2 — human-quality code review

Read-only review of the production code created and changed during the v2
migration. **No production source, test, configuration or build file was
changed.** Evidence is current `src/main`, current `src/test`, durable design
and architecture documents, the Phase B–E audit reports, and pre-v2 classes used
as a style baseline.

---

## 1. Executive verdict

**`NEEDS_TARGETED_REFACTOR`**

The v2 code is readable, follows the project's own conventions closely, and is
diagnosable. It is not over-abstracted: almost every type earns its place, and
the three largest complaints one would expect — long methods, many classes,
vertical formatting — do not survive comparison with the project's pre-existing
code. The verdict is not `HUMAN_MAINTAINABLE` because of three specific,
evidenced defects rather than any general impression:

1. **`Trigger.normalizedEventType` is derived more loosely than the same file
   derives the same fact elsewhere** (Q-01). `LlmSituationV2Factory` answers
   "does this trigger own the graph cursor?" twice — strictly in `ownsCursor`,
   loosely in `normalizedEventType` — and the loose answer feeds model input.
2. **`negation` carries two incompatible meanings** (Q-02), and the record that
   owns it documents none of its 15 components (Q-10). `LeaveBody` and `DockSRV`
   emit `completion: true` together with `negation: true`, while the prompt tells
   the model that explicit negations outrank predictions.
3. **The overflow concept exists as three near-identical records** (Q-03), the
   last of which adds no invariant and buys no decoupling.

None of these blocks extension. All three are cheap to fix, and all three sit
exactly where live testing will look first. `UNSAFE_TO_EXTEND_BEFORE_REFACTOR`
would be unjustified: there is no structure here that a competent maintainer
cannot navigate, and the suite is green at 571 tests.

---

## 2. Review scope

| Area | Files |
| --- | --- |
| Semantic layer | `src/main/java/kairon/semantics/**` — 26 classes |
| Projection and state integration | `CurrentGameStateProjector`, `CurrentGameStateSnapshot`, `ProjectedObservation`, `ObservationProjectionCoordinator` |
| Observer pipeline | `ObserverTurnCoordinator`, `LlmJournalObserverSubscriber`, `ObserverContextOverflow` |
| v2 context | `src/main/java/kairon/observer/context/v2/**` — 10 classes |
| Prompt and validation | `SituationV2PromptFactory`, `ObserverTurnEvidenceScope`, `ObserverResponseValidator` |
| Graph provenance and predictions | `EventOccurrence`, `EventOccurrenceSource`, `SituationOccurrence`, `SituationNextEventPrediction`, `BehaviorGraphApplyResult`, `BehaviorGraphChangeSet`, `graphChanges` |
| Trace and wiring | `JsonLinesTurnTraceWriter`, `KaironApplication.RuntimeWiring` |
| Tests | the 12 v2 test classes, the 5 semantics test classes, and the pre-existing tests they interact with |

Graph, state and trace code was reviewed **only** where v2 changed it.

---

## 3. Evidence methodology

Every finding cites a file, a symbol and a line range read in this session. Line
counts and member counts were computed mechanically, not estimated:

```bash
# file sizes and project medians
find src/main/java -name '*.java' -not -path '*/observation/journal/event/*' \
  | xargs wc -l | sort -n
# nested type density
grep -c 'public record' src/main/java/kairon/observer/context/v2/LlmSituationV2.java
grep -c 'record '      src/main/java/kairon/config/KaironConfiguration.java
# adapter registrations
grep -rc 'builder.register(' src/main/java/kairon/semantics/*.java
```

Member lengths were measured with an awk pass that counts lines between
consecutive declarations at indent level 4. The numbers are approximate at the
boundaries and are used only for **relative** comparison between v2 and pre-v2
code, never as an absolute complexity score.

Where a hazard could not be shown to be reachable today, that is stated in the
finding rather than omitted (Q-01).

---

## 4. Existing project style baseline

Representative pre-v2 sample, chosen across areas and **not** written for the v2
migration.

Production (10): `BehaviorEventNormalizer`, `InProcessObservationBus`,
`KaironConfiguration`, `SpeechGateway`, `Scan` (journal event record),
`CurrentGameStateProjector`, `BehaviorGraphService`, `SystemEpisode`,
`PollingJournalTailReader`, `BodyTypeCompatibilityProjection`.

Tests (5): `CurrentGameStateProjectorTest`, `BehaviorGraphServiceTest`,
`ObserverPipelineTest`, `KaironConfigurationTest`, `JournalSourceTest`.

### Measured baseline

| Property | Pre-v2 reality |
| --- | --- |
| File length, excluding the 272 journal event records | median 106, p75 233, p90 589, max **2539** (`BehaviorGraphService`) |
| Longest members | `BehaviorGraphService.onObservation` **181**, `recordNormalizedOccurrence` **147**, `graph(...)` **132**, `captureSituation` **124** |
| Nested types in one file | `KaironConfiguration`: 10 records + 5 enums in 1518 lines |
| Records | pervasive for value types; compact constructors carry real invariants |
| Constructor style | `Objects.requireNonNull(x, "x")` on entry, one call per line |
| Argument wrapping | one argument per line whenever the call does not fit ~80 columns; closing `)` on its own line |
| Factory style | static named factories (`BehaviorGraphApplyResult.disabled/failed/noGraphId`), private constructors |
| Enum naming | `SCREAMING_SNAKE`, per-constant javadoc where meaning is not obvious |
| Package granularity | fine — `behavior.model`, `behavior.graph`, `behavior.snapshot`, `behavior.normalize`, `behavior.classify` |
| Javadoc | short class-level "what and why"; `@param` used sparsely but present on non-obvious records |
| Registration pattern | `BehaviorEventNormalizer.directRules()` — one static method, 281 lines, 30 keyed rules |
| Test fixtures | a `Fixture` inner class or a `*Fixture` helper; raw JSON text blocks as input |
| Assertion style | JUnit 5 plain `assertEquals`/`assertTrue` with a message argument; no AssertJ, no Hamcrest |

This baseline is used **only** for comparison. Nothing in it is treated as good
merely because it is old — `BehaviorGraphService` at 2539 lines with a 181-line
method is itself a maintainability liability. Its relevance here is that v2 did
**not** make the project worse on any of these axes.

---

## 5. Overall architecture readability

The v2 pipeline reads cleanly in one direction and each hop changes
representation:

```
raw journal JSON
  -> typed event record (transport identity, ADR-0002)
  -> SemanticAdapterRegistry.adapt        -> structured facts + unresolved facts
  -> CurrentGameStateProjector            -> exact per-field delta with write-path origin
  -> SemanticObservationEnvelope          -> one immutable per-observation envelope
  -> ProjectedObservation.semanticEnvelope
  -> SemanticEffectAccumulator            -> bounded, drained at the turn boundary
  -> LlmSituationV2Inputs                 -> the fixed turn inputs
  -> LlmSituationV2Factory                -> model-facing document
  -> LlmSituationV2Compactor              -> Fitted | DoesNotFit
  -> JacksonLlmSituationV2Serializer      -> exact JSON
  -> SituationV2PromptFactory             -> ModelInput
```

Applying §7's indirection test to that chain: representation changes at every
step, each step adds an invariant, and no intermediate can be removed without
losing something. The one exception is the overflow branch, where three shapes
carry the same data (Q-03).

The layering is respected in the direction that matters: `kairon.semantics`
depends on no LLM type, no Jackson type and no prompt wording, verified by
reading its imports. `LlmSituationV2Factory` reads `LlmSituationV2Inputs` and
nothing else, so the "no late reads" invariant is structural rather than
conventional.

---

## 6. Naming findings

### Types reviewed

All 36 new or changed public/package-visible types: 26 in `kairon.semantics`,
10 in `kairon.observer.context.v2`, plus `ObserverContextOverflow`,
`ObserverTurnEvidenceScope`, `SituationV2PromptFactory`, `EventOccurrenceSource`
and the widened `SituationNextEventPrediction`.

### Names that answer "what is this?" well

`SemanticEffectAccumulator`, `SemanticObservationEnvelope`,
`ObserverTurnEvidenceScope`, `ObserverContextOverflow`, `EventOccurrenceSource`,
`DeliveredModelComment`, `LlmSituationV2Compactor`, `SemanticStateChange`,
`UnresolvedFact`, `SemanticValueOrigin`. Each names one responsibility, and each
is a domain name rather than a migration-era name. `DeliveredModelComment` in
particular says exactly what distinguishes it from a generated comment.

### Suffixes checked and cleared

- **`Registry`** (`SemanticAdapterRegistry`) — a real keyed lookup with fail-fast
  duplicate detection, mirroring `BehaviorEventNormalizer`. Earned.
- **`Envelope`** (`SemanticObservationEnvelope`) — one per-observation immutable
  bundle with a correlation invariant. Earned.
- **`Disposition`** (`SemanticDisposition`) — names the recorded decision for a
  catalogue type. Domain term, defined by the enum's own constants.
- **`Inputs`** (`LlmSituationV2Inputs`) — the closed set of things the factory
  may read. The name is the point: it is the purity boundary.
- **`Support`** (`SemanticAdapterSupport`) — the weakest of the set. It is a
  static helper bag, but its members (`negationOf`, `fact`, `fields`) are shared
  by four adapter files and there is no better single noun. Accepted.
- **`RawFields`** — a typed accessor over one event's raw JSON. Accurate and
  short. Accepted.
- **`V2Names`** — carries the version in the name, which usually signals
  migration residue. Here it is correct: it is the naming map of the v2 contract
  and would need replacing wholesale by a v3. Accepted; but see Q-08.

### Problematic names

| Current | Recommended | Reason |
| --- | --- | --- |
| `SemanticFact.negation` | keep the name, fix the meaning | The name is right; two different meanings are stored under it (Q-02). |
| `LlmSituationV2.Trajectory.completeJournalHistory` | derive, do not accept | A component that may only be `false` is not a component (Q-09). |
| `SemanticValueJson.type` (String) | a nested enum | The project's closed sets are enums; the JSON is identical (Q-06). |

### Method names

Generic verbs were checked against §6's list. `build`, `create`, `apply`,
`record`, `resolve` all appear, and in each case the object makes them precise:
`SemanticAdapterRegistry.adapt`, `LlmSituationV2Factory.create`,
`SemanticEffectAccumulator.record`/`drainThrough`, `LlmSituationV2Compactor.prepare`.
`prepare` is a good choice specifically because it does **not** promise a
document — it returns `Fitted | DoesNotFit`.

Two observations, neither severe:

- `LlmSituationV2Factory` has both `create(inputs)` and `build(inputs, options,
  compactionAttempted)`; `create` is the public one-argument form and `build` the
  package-private workhorse. The distinction is real but not stated in either
  javadoc. Minor.
- Boolean methods follow the `is`/`has`/`can` convention where they are
  predicates (`fits`, `empty`, `bounded`, `any`). `fits(policy)` reads as a
  question and is fine; `Drained.empty()` and `Truncation.applied()` are
  accessors on records rather than predicates and match the project's habit.

No negative boolean names were found. No abbreviation outside the project's
established vocabulary was found. Parameters named `value`, `data`, `result` or
`item` appear only where the type is genuinely generic (`SemanticValue value`,
`Object fragment`).

---

## 7. Class granularity

### Small types that earn their place

- `SemanticProvenance` (4 components) — answers who/when/what-role for every fact
  and is validated against its envelope. Not a wrapper.
- `SemanticValueOrigin`, `SemanticChangeKind`, `EventOccurrenceSource` — closed
  enums that each replace an inference the project explicitly forbids.
- `TriggerRelation` — pre-existing, now single-owner.
- `LlmSituationV2Serializer` (one implementation) — the compactor depends on the
  interface, and `serializeFragment` is used for section attribution. A single
  implementation is not by itself a defect; here the boundary also keeps Jackson
  out of the compactor's signature. **Kept deliberately.**

### Small types worth questioning

- `LlmSituationV2.Commander(String fid)`, `CommanderPresence(String mode)`,
  `NavigationContext(String flightMode)` — single-component records with no
  invariant. They exist to produce a JSON **object** per subject rather than a
  flat scalar, which is the subject-separation contract itself. That is a real
  reason, so they stay; recorded here so a future reviewer does not delete them
  as noise.

### Large types

`LlmSituationV2` at 1014 lines and 34 nested records is the one genuine
granularity finding (Q-04). Contrast: `KaironConfiguration`, the closest
pre-existing "one file is the whole contract" type, has 15 nested types in 1518
lines. v2's nesting density is roughly three times higher.

`ObserverTurnCoordinator` at 1217 lines is **not** an outlier: `BehaviorGraphService`
is 2539. See §11.

### Indirection

No removable layer was found on the main path. One removable layer was found on
the overflow path (Q-03).

---

## 8. Method readability

`LlmSituationV2Factory` is organised with section banners
(`// ---- turn`, `// ---- triggers`, `// ---- predictions`) and each private
method projects exactly one section. `build(...)` reads as a list of section
constructions followed by one `new LlmSituationV2(...)`. That is the most
readable part of the v2 code.

`LlmSituationV2Compactor.prepare` reads top to bottom as the ladder it is, and
the terminal comment at :217-219 states why there is no further step. The mixed
linear/binary search strategy is the only blemish (Q-07).

`ObserverTurnCoordinator.startTurn` (115 lines) and `completeModelTurn` (124
lines) are long, and each has a single visible happy path with early returns for
the exceptional branches. Measured against `BehaviorGraphService.onObservation`
(181) they are within project norm (Q-14). Both carry why-comments at the
non-obvious decisions — the drain boundary at :364-365 and the fail-closed
rationale at :397-400 are exactly the comments a reviewer needs.

`SemanticAdapterRegistry.adapt` is 38 lines with one switch and one fall-through,
and its javadoc states the contract that makes it safe: never throws for an
unregistered type.

No method was found where a simple operation is fragmented across too many
private helpers, and none where a complex policy hides inside a single long
expression.

---

## 9. Formatting and vertical verbosity

The complaint was checked against concrete examples rather than accepted.

| Classification | Finding |
| --- | --- |
| `FORMATTER_REQUIRED` | **None.** No file deviates from `.editorconfig`: 4-space indent, 120-column limit, LF, final newline. |
| `LOCALLY_VERBOSE` | Inline fully-qualified names against the project's import convention — `SemanticAdapterRegistry.java:155-156`, `ObserverTurnCoordinator.java:866,897,926,1004,1010,1028` (Q-12). |
| `STRUCTURALLY_VERBOSE` | `LlmSituationV2Factory.java:117-140` — one `return new LlmSituationV2(...)` spanning 24 lines with three nested `new` calls inside it. The cause is the nine-section constructor, not the formatter. Same shape at `:660-669` for `Trajectory`. |
| `ACCEPTABLE_FOR_COMPLEX_DTO` | `LlmSituationV2.java` compact constructors, and the adapter builder chains in `JournalSemanticAdapters` (e.g. `:399-424`). One argument per line is exactly what the pre-existing `Scan.java` and `KaironConfiguration` do. |

Representative files: `LlmSituationV2Factory.java` (structural),
`SemanticAdapterRegistry.java` (local), `JournalSemanticAdapters.java`
(acceptable).

**Conclusion: the vertical volume of v2 is a consequence of a nine-section
contract and a builder-based adapter style, not of formatting.** Running a
formatter would change nothing. The two structural cases are addressed by Q-04
and Q-09, not by reflowing.

---

## 10. Semantic adapter architecture

### Structure

117 registrations across four files, keyed by payload class, generalising
`BehaviorEventNormalizer.register(...)`. Duplicate registration throws at class
initialisation. An unregistered type never throws — it yields an explicit
`UnresolvedFact`.

| File | Registrations |
| --- | ---: |
| `ProgressionSemanticAdapters` | 42 |
| `JournalSemanticAdapters` | 34 |
| `CommerceSemanticAdapters` | 24 |
| `ConflictSemanticAdapters` | 17 |

### Finding an adapter

There is no index. Locating one means `grep -rn "register(ApproachBody.class"`.
Within `JournalSemanticAdapters` the comment banners (Commander identity, System
transition, Body approach and departure, Surface, Docking, Presence transfer,
Auxiliary vehicles, Exploration, Missions, Market, Social) do orient a reader,
but they live inside a single 574-line method (Q-05).

### Adding an event

Four places, and the build catches the one that matters:

1. `JournalEventCatalog` — register the discriminator.
2. `LlmJournalEventSelection` — assign a role, or inherit `DIAGNOSTIC_ONLY`.
3. An adapter, **or** a declaration in `SemanticDispositions`.
4. Optionally `BehaviorEventNormalizer` / `EventSignificancePolicy`.

**A missed disposition cannot be forgotten**: `SemanticDispositionCoverageTest`
fails when a model-eligible type has neither an adapter nor a declared
exception. This is the strongest single design decision in the semantic layer.

### Reuse — real or declared?

Real. `presenceTransfer` is shared by `Disembark` and `Embark`;
`dockingFactBuilder`/`dockingFact` by five docking outcomes; `missionFactBuilder`
by four mission outcomes; `marketFact` by the market pair; `power(...)` by the
Powerplay family. Polarity is carried by class identity into `operation` +
`completion`, exactly as ADR-0012 requires.

### Walkthroughs

| Event | Path | Difficulty |
| --- | --- | --- |
| `ReceiveText` | `JournalSemanticAdapters:620` → one fact, `COMMANDER`/`RECEIVED`, sender/channel/message as three independent qualifiers → no state change → accumulator → trigger `structuredFacts` | **EASY** — the comment at :622-623 states why sender and channel must not substitute for each other |
| `ApproachBody` | `:196` → `CURRENT_BODY`/`APPROACHED` + `bodyRef` → projector selects the body and re-hydrates `BodyContext` → deltas tagged `ACTIVATED_FROM_CONTEXT` by write path → `stateChanges` | **MODERATE** — the fact is local, but the interesting half happens in `CurrentGameStateProjector`, in another package |
| `LaunchFighter` | `:399` → `ASSOCIATED_VEHICLE`/`LAUNCHED` + `UnresolvedFact(OCCUPIED_VEHICLE, FIGHTER_OCCUPANCY_NOT_ESTABLISHED)` → projector overwrites `vehicleKind` with `UNKNOWN` → both the fact and the gap reach the trigger | **EASY** — the unresolved fact is emitted beside the positive fact, so the gap is visible in model input |
| `CodexEntry` | `:489` → `negationOf(raw, "IsNewEntry")` supplies `negation` from an asserted field | **EASY** — and this is the correct use of `negation` (contrast Q-02) |
| `ScanOrganic` | `:521` → `BIOLOGICAL_SAMPLING_PROCESS` with `processStage` from `ScanType`; the normalizer fans the same event out to three graph types | **MODERATE** — two independent classifications of one event live in two packages; neither references the other |
| `Embark` / `Disembark` | `:355`/`:362` → shared `presenceTransfer` at `:796`, which branches on `SRV`/`Taxi`/`Multicrew` flags and yields `EntityKind.UNRESOLVED` for taxi and multicrew | **EASY** — the refusal to guess is one readable if/else chain |

Where a human will struggle: `ApproachBody` and `ScanOrganic`, because the fact
and the state delta are produced in different packages by different mechanisms
and nothing in either file points at the other.

### Breakpoints

Setting one breakpoint on a single event is straightforward: the lambda in
`builder.register(X.class, ...)` is a normal lambda body. Following one
observation end to end requires breakpoints in four places
(`SemanticEnvelopeFactory`, `CurrentGameStateProjector.applyAndCapture`,
`SemanticEffectAccumulator.record`, `LlmSituationV2Factory.triggers`), which is
proportionate to the pipeline.

---

## 11. `ObserverTurnCoordinator`

### Actual responsibility map

| Responsibility | Members |
| --- | --- |
| Command intake, single-thread confinement | `post`, `apply`, `executor` |
| NEW queue | `newQueue`, `queueNew`, `QueuedProjection` |
| Semantic accumulation | `semanticEffects`, `lastDrainedSemanticEffects` |
| Batch timing | `scheduleOrStartTurn`, `eligibilityTask`, `quietPeriod`, `maximumBatchAge` |
| Turn construction | `startTurn`, `ActiveTurn`, `LlmSituationV2Inputs` |
| Budget outcome | `situationCompactor`, `finishContextTooLarge`, `finishPreparationFailure` |
| Provider call | `llmClient`, `activeRequest`, `completeModelTurn` |
| Validation | `responseValidator`, `ObserverTurnEvidenceScope` |
| Delivery and TTS | `commentSink`, `activeDelivery`, `completeCommentDelivery` |
| Previous-comment memory | `deliveredComments` |
| Trace | `traceWriter`, `turnTrace` |
| GUI notification | `turnListener`, `notify*` |
| Lifecycle | `shutdown`, `close`, `beginShutdown`, `finishShutdownIfPossible`, `idleWaiters` |

That is thirteen responsibilities in one class. **But they are not independent**:
every one of them is a phase of exactly one turn, and the class exists to make
the turn atomic on a single thread. Splitting it into services would move the
temporal coupling from a single readable class into cross-object protocol, which
is worse, not better.

### Lifecycle of one turn

`queueNew` → `scheduleOrStartTurn` (quiet period or maximum batch age) →
`startTurn` fixes the batch and drains the accumulator **in the same critical
section** (:364-368) → `prepare` → either `finishContextTooLarge` or
`promptFactory.create` + `llmClient.complete` → `completeModelTurn` (validation,
novelty guard, delivery) → `completeCommentDelivery` → `finishTurn` (trace, GUI,
previous-comment memory).

Exactly-once guarantees are visible: `startTurn` removes triggers from
`newQueue` before anything can fail, and `finishContextTooLarge` deliberately
does not return them. The javadoc at :454-462 says so in one paragraph.

`CONTEXT_TOO_LARGE` behaviour is the clearest part of the class: one branch, one
typed carrier, one comment stating that no retry happens.

### Temporal coupling

One piece is implicit rather than hidden: `lastDrainedSemanticEffects` is a
field written in `startTurn` and read later by `snapshot()`/diagnostics. It is
not used to build the document — that uses the local value — so there is no
correctness hazard, but a field that exists only for observation could be named
to say so.

### Constructor

Six parameters, all collaborators the composition root already owns. No
parameter requires knowledge of the whole system. There is no command hierarchy
beyond three records (`QueueNewObservation`, `RecordSemanticEffect`,
`ReplaySourceExhausted`), which is proportionate.

### Stack traces

A failure inside document construction surfaces through
`finishPreparationFailure` with the turn sequence logged. A failure inside an
adapter is caught in `SemanticEnvelopeFactory` and downgraded to a gap, so it
does **not** reach a stack trace — deliberate, documented, and the one place
where diagnosis depends on a log line rather than an exception.

**Recommendation: do not split this class.** No cohesion boundary was found that
would survive the single-thread turn invariant.

---

## 12. v2 DTO and serializer

`LlmSituationV2`: 1014 lines, 34 nested public records, maximum nesting depth 2
(root → section → item). Every nested record has a compact constructor, and most
carry real invariants: `Trigger` validates bus sequence and blankness,
`StateChanges` validates the `(busSequence, subject, field)` ordering,
`Trajectory` validates count consistency and ascending `episodeSequence`,
`Predictions` validates the probability mass.

Records **without** an invariant: `Commander`, `Qualifier`'s siblings
`OccurrenceCount`, `RoleCount`, `SectionWeight`. They exist to shape JSON. That
is a legitimate reason, and it is what makes the count high.

JSON contract and domain semantics are mixed in one type by design — the record
*is* the schema — and that is why the serializer can stay trivial and
deterministic. The alternative (a domain model plus a mapping layer) would add
the indirection this review is looking for.

### The three options, judged on this code

| Option | Verdict |
| --- | --- |
| One root with nested records (current) | Best for reading the contract as a whole; worst for IDE navigation and fixture construction. |
| Separate top-level DTO records | Would remove the `LlmSituationV2.` prefix from ~200 references and split the file, but scatters a single schema across 34 files. Not supported by the evidence. |
| **Hybrid, section-based** | Extract the four heavy sections (`Trigger` + `Fact` family, `CurrentState` + its 10 subjects, `GraphContext` + trajectory/counts, `Predictions`) into top-level records in the same package; keep the root and the light sections nested. Reduces the root to roughly 350 lines while keeping the schema readable in four files. **Recommended, low priority.** |

`JacksonLlmSituationV2Serializer` is 77 lines, has no domain logic, and its
javadoc now explains why each setting exists. `SemanticValueJson` is the one
mapping layer, and it is a genuine boundary — a sealed domain hierarchy cannot
serialize to a JSON-native scalar without one. Its per-component javadoc is the
best in the v2 code and should be the model for Q-10.

---

## 13. Compactor

The ladder reads top to bottom: counts → trajectory → predictions → comment text
→ presentation text → `DoesNotFit`. Proving that state changes are never dropped
takes one search: there is no `stateChanges` branch anywhere in the class, no
`stateChangeLimit` in `BuildOptions`, and no `stateChanges` component in
`OmittedCounts`. **Absence is the proof, and it is easy to verify.**

Policy and mechanics are separated: `LlmSituationV2Policy` holds the numbers,
`BuildOptions` holds the current candidate's limits, the compactor holds the
order. `BuildOptions` has `full(policy)` and `mandatoryOnly(policy)` plus five
`withXLimit` methods — five intermediate objects per ladder step, but they are
immutable and named, and `mandatoryOnly` doubles as the budget-viability probe.

### Walkthroughs

| Scenario | Path |
| --- | --- |
| Small turn | `candidate(full)` fits at the first check → `Fitted(compactionApplied = false)`. One build, one serialization. |
| Ordinary exploration turn | Same first check; the measured `pipeline/organic-sampling-sequence` case fits at 16 000 without compaction. |
| Large turn that fits after optional compaction | Descends the count loop, then the trajectory loop, until `fits(policy)`; `compactionApplied = true` and `truncation.omittedCountsBySection` is non-zero. |
| Mandatory overflow | All five steps exhaust, `doesNotFit(...)` measures the mandatory document once more and returns the typed failure with the heaviest sections. |

`DoesNotFit` is understandable without the design document: the record's javadoc
at :97-104 states that it is returned rather than thrown, never accompanied by a
document, and carries enough to diagnose without re-running.

The one weakness is Q-07: three linear descents and two binary searches solve the
same problem in one method.

---

## 14. Graph provenance and predictions

Reviewed only where v2 changed it.

`EventOccurrenceSource` is a three-value enum with one value per production write
path, taken from the caller in `recordNormalizedOccurrence` and rejected if null.
Nothing infers it from an event name. `SituationOccurrence.source` and
`LlmSituationV2.Cursor.source` copy it. The `@JsonIgnore` on persistence and the
resulting post-reload `null` are documented on `EventOccurrence` itself. This is
the cleanest provenance implementation in the codebase.

`SituationNextEventPrediction` grew from five to ten components. All ten are
copied verbatim by `situationPredictions`; the v2 factory reconstructs nothing.
`contextDistinguishes` on the section — rather than a per-item flag — is the
right shape, because it is a property of the cursor's context key, not of an
individual prediction.

The one hazard is Q-01, which sits in the v2 factory rather than in the graph.

---

## 15. Test quality

| Classification | Tests |
| --- | --- |
| `PERMANENT_BEHAVIOR_GUARD` | `LlmSituationV2StateTest` (four change kinds, `ACTIVATED_FROM_CONTEXT` on the `Scan(83)→ApproachBody(84)→ApproachBody(83)` sequence, signal-count zeroing), `SemanticEffectAccumulatorTest` (13 tests: ordering, exactly-once drain, boundary retention, coalescing, suppression marker), `LlmSituationV2ContextTooLargeTest`, `LlmSituationV2CompactionTest`, `JournalSemanticAdaptersTest`, `ObserverResponseValidatorTest` |
| `PERMANENT_ARCHITECTURE_GUARD` | `SemanticDispositionCoverageTest` (272 types, one disposition each — the single most valuable guard in the layer), `LlmSituationV2ProductionInputTest` (schema, prompt, serializer, policy, trace identity, package contents, constructor shape) |
| `USEFUL_REGRESSION` | `LlmSituationV2SchemaTest` (alphabetical ordering, null semantics, byte-identical repeat serialization), `LlmSituationV2TriggerTest`, `LlmSituationV2PredictionTest`, `LlmSituationV2PreviousCommentTest` |
| `OVER-SPECIFIED` | `SituationV2PromptFactoryTest` — 61 pinned English clauses (Q-11) |
| `BRITTLE_SOURCE_SCAN` | Two files read `src/` from a test: `LlmSituationV2GraphContextTest:187-190` reads `LlmSituationV2Factory.java` to prove no event-type inference; `LlmSituationV2ProductionInputTest` runs five source scans. Both are deliberate and both would break on a rename — which is the point — but they are the tests most likely to obstruct a future refactor. |
| `REDUNDANT` | None found. |
| `MIGRATION_RESIDUE` | None remaining — the three migration-era classes were deleted in Phase E. |

`V2TurnFixture` (501 lines) is the largest v2 test file and is a genuine fixture
builder: it drives the production parser, adapter and projector and synthesises
only the graph situation. `V2ProductionPipeline` (382 lines) drives the real bus
end to end. Both are appropriate; neither duplicates the other.

Setup repetition across the eight `LlmSituationV2*Test` classes is low because
both fixtures are shared. Fixture blocks are raw JSON text blocks, matching
`CurrentGameStateProjectorTest` and `JournalSourceTest`.

**No test should be deleted.** Q-11 is a granularity change, not a removal.

---

## 16. Documentation and comments

Phase E removed the migration narrative, and a re-scan confirms it: no
production comment references Phase B/C/D/E, v1, the shadow path, or a
post-cutover TODO. Two references to disposable `target/audit` artifacts were
replaced with references to durable documents.

What remains is mostly "why" rather than "what": `SemanticSubject` documents all
11 constants and states why `OCCUPIED_VEHICLE` and `CURRENT_LOCATION` have no
field; `LlmSituationV2Compactor` states why there is no state-change step;
`ObserverTurnCoordinator:454-462` states why an overflow batch is consumed once.
None of these restates a signature.

Two defects:

- `SemanticFact` has 15 components and no `@param` at all (Q-10) — the root cause
  of Q-02.
- `V2Names` claims a test that does not exist (Q-08).

`UNKNOWN`/`null`/provenance/authority semantics are documented where they are
defined (`SemanticValueJson`, `SemanticSubject`, `EventOccurrence`,
`LlmSituationV2.PreviousComment`) and restated in the prompt. That is one
statement per boundary, not duplication.

---

## 17. Debugging walkthroughs

### Case A — `LaunchFighter`, model falsely comments about biological sampling

**MODERATE.** Files to inspect: the trace record (the exact document is stored
verbatim), then `JournalSemanticAdapters:399` for the emitted fact, then
`SemanticSubject` to confirm `ASSOCIATED_VEHICLE` and
`BIOLOGICAL_SAMPLING_PROCESS` are separate subjects. Because the fact carries an
explicit subject and an explicit `UnresolvedFact`, the trace answers the question
without re-running anything — ADR-0010 evidence-first evaluation is mechanically
possible here, which was the point of v2. It is `MODERATE` rather than `EASY`
only because the answer spans the trace, one adapter and one enum.

### Case B — a Status change modifies current state but does not appear in `triggers`

**EASY.** The document itself answers it: the change appears in
`stateChanges.items` carrying `sourceRole: "STATUS"` and its own `busSequence`,
and `stateChanges.hiddenSources.byRole` counts it. No log correlation is needed.
This is the single largest debuggability gain over v1.

### Case C — `CONTEXT_TOO_LARGE`

**EASY to find, MODERATE to trace through the code.** The developer reads one
trace line: `turnOutcome`, `contextOverflow.mandatoryCharacterCount`,
`configuredCharacterBudget`, `overshootCharacters`,
`largestMandatorySections`, `providerInvoked: false`, `commentDelivered: false`,
`speechInvoked: false`, `situationTurn: null`. Exactly-once consumption is
provable from the next record's disjoint trigger set. The `MODERATE` half is
Q-03: following the value through the code crosses three near-identical records.

### Case D — prediction `probability = 1.0` with support = 1

**EASY.** `predictions.items[]` carries `probability`, `basis`,
`globalProbability`, `observedTransitionCount` and
`contextObservedTransitionCount`, and the section carries `contextDistinguishes`.
The path back is `TransitionProbabilityCalculator.predict` →
`NextEventPrediction` → `SituationNextEventPrediction` (verbatim) →
`LlmSituationV2Factory.predictions` (verbatim). No value is recomputed, so
reading the JSON is equivalent to reading the counter.

### Case E — restored graph cursor has `source = null`

**EASY.** `EventOccurrence`'s javadoc states that `source` is not persisted, that
`null` means "predates in-process provenance", and that it is never a claim about
what produced the occurrence. The prompt states the same rule to the model, and
`JsonBehaviorGraphStoreTest.inProcessOccurrenceProvenanceIsNotPersistedAndComesBackAbsent`
pins it. A developer meeting a null source finds the explanation in the type that
owns the field.

No case rated `DIFFICULT` or `UNREASONABLY_DIFFICULT`.

---

## 18. Highest-value simplifications

1. **Q-01** — one method reusing `ownsCursor`. Smallest change, largest
   correctness benefit.
2. **Q-02 + Q-10** — document `SemanticFact`'s components and move
   reversal-of-a-paired-operation from `negation` to `relationship`. Removes the
   one place where model input can be structurally misread.
3. **Q-03** — delete `ContextOverflowTrace` and `SectionWeightTrace`. Removes two
   types and one conversion for zero loss.
4. **Q-05** — split `build()` into per-mechanism registration methods. Pure
   mechanical restructuring, no behaviour.
5. **Q-08** — either write the pinning test or delete the claim.

---

## 19. Things that look unusual but are justified

- **34 nested records in one file.** The document *is* the schema; splitting it
  entirely would scatter one contract across 34 files. Only a section-based
  hybrid is worth considering.
- **Ten separated subjects, two of which are permanently `null`.**
  `occupiedVehicle` and `currentLocation` are typed `UnresolvedSubject` precisely
  so that resolving them later is additive rather than a contract change.
- **A 1217-line coordinator.** Thirteen responsibilities, all phases of one
  atomic turn on one thread. Splitting would convert readable temporal coupling
  into cross-object protocol.
- **`serializeFragment(Object)` taking `Object`.** It measures arbitrary sections
  for overflow attribution; a typed signature would need a marker interface on
  every section for no benefit.
- **Single-implementation interfaces** `LlmSituationV2Serializer` and
  `SemanticEventAdapter`. The first keeps Jackson out of the compactor's
  signature; the second is the lambda target for 117 registrations.
- **Explicit name mapping in `V2Names` instead of enum-name transformation.** A
  transformation would silently rename model-facing keys when an enum constant is
  renamed. The explicitness is the safety.
- **117 adapters.** Bounded by the selected event space, enforced by a coverage
  guard, and the cost is deliberate per ADR-0012.
- **`UnresolvedFact` emitted beside a positive fact.** Emitting the gap next to
  the fact is what stops the model inferring occupancy from association.

---

## 20. Risks of premature refactoring

- **Renaming anything in `kairon.observer.context.v2` breaks two source-scanning
  tests** (`LlmSituationV2ProductionInputTest`,
  `LlmSituationV2GraphContextTest:187`). That is intended, but it means renames
  must be done with the guards in view, not after them.
- **Any change to `LlmSituationV2` field names changes model input.** Model
  behaviour has not yet been evaluated on v2. Renaming a JSON key before the
  first replay evaluation destroys the comparability of that evaluation.
- **Splitting the coordinator before live testing** would move the exactly-once
  and drain-boundary guarantees across object boundaries at precisely the moment
  those guarantees are being exercised for the first time.
- **Touching the compaction ladder order** invalidates the 16-case budget
  measurement that justifies 16 000 characters.
- **Extracting adapter registrations** is safe, but doing it together with any
  semantic change would make a live-testing regression unattributable — the same
  rule that kept Phase B and Phase D apart.

---

## 21. Recommended refactoring sequence

Full detail, with rollback boundaries, in
`target/audit/kairon-v2-refactoring-plan.md`. Summary order:

1. **CS-1** — documentation only: `SemanticFact` components, `V2Names` claim.
2. **CS-2** — formatting only: inline FQN imports.
3. **CS-3** — correctness alignment: `normalizedEventType` reuses `ownsCursor`.
4. **CS-4** — semantics of `negation` on the two reversal adapters.
5. **CS-5** — delete the third overflow representation.
6. **CS-6** — split the adapter registration methods.
7. **CS-7** — `SemanticValueJson.type` as an enum; `Trajectory` constants derived.
8. **CS-8** — optional: section-based DTO split.
9. **CS-9** — optional: prompt-fragment test granularity.

The order departs from the suggested default in one place: **CS-3 and CS-4 are
placed before the organisational changesets**, because they are the only two that
affect what the model receives, and they should land before any live evaluation
rather than after a series of cosmetic commits.

---

## 22. Evidence index

**Production read in this session**
`kairon/semantics/`: `SemanticAdapterRegistry`, `SemanticAdapterSupport`,
`SemanticFact`, `SemanticSubject`, `SemanticValue`, `SemanticEffectAccumulator`,
`SemanticSuppression`, `SemanticField`, `JournalSemanticAdapters`,
`CommerceSemanticAdapters`, `ConflictSemanticAdapters`,
`ProgressionSemanticAdapters`, `RawFields`.
`kairon/observer/context/v2/`: all ten classes.
`kairon/observer/`: `ObserverTurnCoordinator`, `ObserverContextOverflow`.
`kairon/llm/`: `SituationV2PromptFactory`, `ObserverResponseValidator`,
`ObserverTurnEvidenceScope`.
`kairon/behavior/`: `BehaviorGraphApplyResult`, `BehaviorGraphChangeSet`,
`BehaviorGraphService.graphChanges`, `EventOccurrence`,
`EventSignificancePolicy`.
`kairon/trace/JsonLinesTurnTraceWriter`, `kairon/app/KaironApplication`,
`kairon/state/CurrentGameStateSnapshot`, `BodyTypeCompatibilityProjection`.

**Style baseline (pre-v2)**
`BehaviorEventNormalizer`, `InProcessObservationBus`, `KaironConfiguration`,
`SpeechGateway`, `Scan`, `CurrentGameStateProjector`, `BehaviorGraphService`,
`SystemEpisode`, `PollingJournalTailReader`, `BodyTypeCompatibilityProjection`;
tests `CurrentGameStateProjectorTest`, `BehaviorGraphServiceTest`,
`ObserverPipelineTest`, `KaironConfigurationTest`, `JournalSourceTest`.

**Tests read**
All 12 v2 test classes, 5 semantics test classes, `SituationV2PromptFactoryTest`,
`OpenAiCompatibleLlmClientTest`, `ObserverPipelineTest`,
`CurrentGameStateProjectorTest`.

**Documents**
`CLAUDE.md`, `docs/KAIRON_ARCHITECTURE.md`, `docs/CURRENT_STATE.md`,
`docs/decisions/ADR-0012`, `docs/design/kairon-llm-situation-v2-design.md`
(§8, §9, §11, §13, §14, §23–§28), and the Phase B/B.1/C/C.1/D audit reports.

**Validation**
`mvnw.cmd test` — 571 run, 0 failures, 0 errors, 1 skipped, BUILD SUCCESS. No
production or test file was modified during this review.

---

## 23. Quantitative inventory

Counted mechanically. **These are descriptions, not scores, and no verdict in
this document rests on a number alone.**

| Measure | Value | Method |
| --- | ---: | --- |
| Production classes in `kairon.semantics` | 26 | `ls src/main/java/kairon/semantics/*.java` |
| Production classes in `observer.context.v2` | 10 | `ls` |
| Records across both packages | 65 | `grep -rho 'record [A-Z][A-Za-z0-9]*'` |
| Nested records in `LlmSituationV2` | 34 | `grep -c 'public record'` |
| Enums across both packages | 10 | `grep -rlo 'public enum'` |
| Interfaces with one implementation | 2 | `LlmSituationV2Serializer`, `SemanticEventAdapter` (a lambda target with 117 instances) |
| Factory classes | 3 | `LlmSituationV2Factory`, `SemanticEnvelopeFactory`, `SituationV2PromptFactory` |
| Adapter registrations | 117 | `grep -rc 'builder.register('` |
| Distinct raw journal field literals in adapters | 170 | `grep -rho '"[A-Z][A-Za-z_]*"'`, deduplicated |
| Distinct qualifier keys | 55 | `grep -rho '\.qualifier(\s*"[a-zA-Z]*"'`, deduplicated |
| v2 source files above the project median (106 lines) | 20 of 36 | `wc -l` against the median excluding journal event records |
| Source-scanning tests | 2 | `LlmSituationV2ProductionInputTest`, `LlmSituationV2GraphContextTest` |
| Exact-string prompt assertions | 61 | quoted fragments inside `assertContainsAll` blocks |
| Generic-verb method names (`build`/`create`/`apply`/`record`/`resolve`) | 14 | across both packages; each disambiguated by its receiver |

The project median file length excluding the 272 journal event records is **106
lines** (p75 233, p90 589, max 2539). Fourteen of the 36 v2 files are below it.
