# Kairon Current State

## Evidence basis

This document describes repository behavior as of 2026-08-05. It is factual,
not a phase plan. Statements are based on production source, tests, `pom.xml`,
the checked-in configuration example, and the current local replay
configuration.

Historical documents under [`archive/`](archive/) are not evidence that a
feature is implemented.

A cross-layer contract harness exists. `SemanticPipelineHarness`,
`PipelineTrace` and `SemanticPipelineAssertions` (test-only, in
`kairon.observer.decision`) run one observation sequence through the production
pipeline and assert canonical state, semantic effects, the behaviour graph,
observer admission, provider invocation and the exact serialized `userMessage`
together — see [ADR-0017](decisions/ADR-0017-CROSS-LAYER-CONTRACT-TESTS.md). It
adds observability and cross-layer invariants only: the execution pipeline
described below is unchanged, no production type was introduced, and four target
contracts covering known defects were checked in **disabled**, each naming the
phase that will make it pass. Nothing about the split between structural,
novelty, eligibility and presentation decisions is resolved by it.

Three of those four are now enabled and passing.
[ADR-0018](decisions/ADR-0018-FIELD-AWARE-STATEMENTS-AND-UNKNOWN-COUNTS.md)
records two corrections: an event states a canonical field only when the field
and the value both match, and a signal category no reading counted stays
unknown instead of being written as zero.
[ADR-0020](decisions/ADR-0020-EFFECT-RETENTION.md) records the third: a
bootstrap observation's semantic effects are no longer retained, so historical
facts reach a live turn as `context` rather than as changes. The model-facing
schema is unchanged. One stays disabled — the repeated-`UNDER_ATTACK`
divergence between graph and observer is still an open product decision.

`AppliedObservation` exists as of
[ADR-0019](decisions/ADR-0019-APPLIED-OBSERVATION.md): one immutable value per
observation carrying its identity, capture mode, source role, effect retention,
the state before and after, the event-local observation context and the exact
semantic delta. `SemanticEnvelopeFactory.create` takes that value and copies
every semantic field out of it, classifying nothing itself, so
`SemanticObservationEnvelope` carries the capture mode and the retention beside
the effects they belong to and cannot disagree with the value that owns them.
That factory lives in `kairon.projection`, beside
`ObservationProjectionCoordinator`, because it reads both an `AppliedObservation`
and the semantic adapters; `kairon.semantics` no longer imports `kairon.state`
anywhere, and the envelope itself still belongs to `kairon.semantics`.

It also carries `EffectRetention`
([ADR-0020](decisions/ADR-0020-EFFECT-RETENTION.md)): `RESTORE_ONLY` under
`BOOTSTRAP` capture, `RETAIN_FOR_TURN` otherwise, derived from capture mode
alone and never from what the model is told. `SemanticEffectAccumulator.record` is
its only reader and declines a `RESTORE_ONLY` envelope, so a historical
observation's delta never becomes a later turn's background change; the facts it
established are canonical and arrive as `context`. Bootstrap still projects
state, restores body/system/vehicle facts, reaches the graph under the existing
rules and stays model-silent.

`ApplicationMode` and `ModelVisibility` are **removed**
([ADR-0021](decisions/ADR-0021-LAYER-BOUNDARIES-AND-DECLARED-RULES.md)). Both
were computed, carried the length of the pipeline and read by nothing in
production; the graph still classifies through `EventSignificancePolicy`, the
observer still selects through `LlmJournalEventSelection`, and the novelty guards
still keep their own memories. Behaviour is unchanged in every layer. The
semantic split brain is not resolved by removing them — moving a decision onto a
shared type stays a behaviour change that has to be argued on its own.

Eight structural boundaries were corrected in
[ADR-0021](decisions/ADR-0021-LAYER-BOUNDARIES-AND-DECLARED-RULES.md), all
behaviour-preserving:

- `kairon.semantics` imports neither `kairon.observer` nor `kairon.behavior`.
  `SemanticSourceRoleCatalog` holds the one journal-type classification and
  `LlmJournalEventSelection` reads it.
- `SystemVisitPolicy` and `SystemVisitTransition` are the one definition of when
  a visit to a system begins, continues and ends. The behaviour graph and
  `BodySurveyNoveltyGuard` both ask it and keep their own memories; the
  arrival-star rule and the arrival body are shared too.
- `DecisionEventCatalog` had two enumerable extension points — class-keyed rules
  and `RecordDecisionRule` — with `declaredRules()` as their union and a
  fail-fast on an ambiguous record. `RecordDecisionRule` is **removed** by
  [ADR-0022](decisions/ADR-0022-VARIANT-DISPATCH-IN-THE-PARSER.md); see below.
- `DecisionContextProfile` is separate from `DecisionMechanism`, and
  `DecisionEventRule.reading(...)` overrides the mechanism's default. `CODEX` and
  `ARRIVAL_DISCOVERY` no longer exist as mechanisms.
- `StatedFacts` is built once per turn and read by both `DecisionChangeSelector`
  and `DecisionContextSelector`; the rendered-string comparison is gone.
- `kairon.trace` and `kairon.llm` no longer import observer packages:
  `kairon.turn.overflow.ContextOverflow` is the shared immutable contract.
  `kairon.turn.evidence.DecisionEvidence` is removed — the response no longer
  cites anything, so there was nothing left for it to be checked against, and
  `kairon.llm` now imports nothing from either side.
- `kairon.semantics.BodyIdentity` is the one `(systemAddress, bodyId)` value.

`PackageDependencyRulesTest` (in `kairon`) enforces the forbidden import
directions by reading `src/main/java`, without a new framework.
`ModelFacingReplayBaselineTest` writes `target/model-facing-baseline.json` — a
deterministic recording of every turn of a fixed replay with its trigger bus
sequences, request document, episodes, occurrences, transitions and cursor — so
a refactor can be compared against itself.

**A wire event name is no longer a unit of meaning**
([ADR-0022](decisions/ADR-0022-VARIANT-DISPATCH-IN-THE-PARSER.md)), and this is
behaviour-preserving: the baseline recording is byte-identical before and after.
Five journal records whose wire event carries more than one domain event are now
sealed interfaces with one nested record per domain event and a single
`of(RawJournalData)` factory registered in `JournalEventCatalog` — `ScanOrganic`
(`Logged`, `Sampled`, `Analysed`, `Unrecognised`), `StartJump` (`Hyperspace`,
`Supercruise`, `Unrecognised`), `EngineerLegacyConvert` (`Previewed`,
`Converted`, `Unrecognised`), `LaunchDrone` (eight researched limpet kinds plus
`Unspecified`) and `Scan` (`BodyReading`, `UndiscoveredStar`). The parser is the
only thing that dispatches: `BehaviorEventNormalizer` lost its four per-record
`switch` branches and its `Scan` branch, and its only remaining `instanceof` is
the `FSDJump` boundary guard.

`kairon.observation.journal.JournalEventLookup` answers a class-keyed registry
for a variant through the record it belongs to — exact match, then one level of
declared interfaces, never deeper. The registries that ask *what kind of journal
event is this* (`SemanticSourceRoleCatalog`, `EventSignificancePolicy`,
`SemanticAdapterRegistry`) keep the wire record as their key; the registries that
ask *which domain event is this* (`BehaviorEventNormalizer`,
`DecisionEventCatalog`) key on the variant, and a variant sharing its record's
kind is catalogued once under the record.

`UnrecognisedEventVariant` marks a variant whose discriminator this build does
not know. It keeps its record's attribute list and takes
`NormalizedEventType.unknown(originalEventName)` rather than a researched type.
`Scan` has none: its discriminator is a shape rather than a vocabulary.

`RecordDecisionRule` and `DecisionEventCatalog.size()` are **removed**.
`Scan.UndiscoveredStar` is catalogued by class with the milestone's unchanged
kind, mechanism, context profile, object name, uncounted claim and retained
qualifiers, so there is nothing left for a record-earned rule to express.
`DecisionEventCatalogCoverageTest` now asserts coverage rather than sizes: every
admitted type resolves to a rule, and every catalogued type is an admitted type
or a variant of one.

`JournalEventVariantContractTest` (in `kairon.observation.journal`) is the
cross-layer contract: for one parsed class there is one structural type, one
domain kind and one description. Its corpus varies every discriminator, including
values this build does not recognise, and asserts that the corpus really splits —
7 wire event names, 23 classes. It replaces `DecisionRecordRuleTest`.

**Each variant now says its own step**
([ADR-0023](decisions/ADR-0023-A-VARIANT-SAYS-ITS-OWN-STEP.md)), which is the one
part of this work that is not behaviour-preserving: the baseline recording moves
by exactly four lines, all four a sampling event naming its step. The four
`ScanOrganic` steps, the nine `LaunchDrone` limpets and
`EngineerLegacyConvert.Unrecognised` each carry their own constant sentence;
the last of these stops reporting a conversion for a record that does not say
whether it was one.

**The trajectory speaks the same sentences.** `DecisionTrajectoryNames` is
`DecisionTrajectoryDescriptions` and holds statements rather than identifiers,
and `likelyNext[].kind` is `likelyNext[].event`. A prediction reads the same
past-tense sentence as a memory of the same event; the field it sits in and its
probability are what say it has not happened, and the system prompt already said
so, so no prompt change was needed. The two scanners no longer share one
remembered name — each says which instrument reported the signals, as their
events always did. Together with the per-variant sentences the baseline moves by
48 lines and 50 537 → 53 872 bytes.

One redundancy is left standing **by decision**: `stage` and `complete` are still
sent for sampling, beside a sentence that now says the same thing. ADR-0023
records both sides of that and what removing them would take.

**A current event now says what it is, in its own words.**
`LlmPresentableJournalEvent.modelFacingDescription()` is a second method on the
contract that already marks the 119 researched records, and every one of them
implements it: one short literal English sentence naming what that kind of event
reports, carrying no value the record supplies and no judgement, prediction,
motive or next step. `DecisionEventProjector` narrows the observation it already
holds and asks it — there is no lookup by class, no lookup by kind, no table and
no `switch` — and `events[*].event` carries the answer where `events[*].kind`
used to be. The internal kind is unchanged and still drives selection, the
behaviour graph, the trajectory vocabulary, diagnostics and the tests; it simply
stops being serialized, because a name only this process shares is not an answer
to what happened. `Scan` reports two different things, and since
[ADR-0022](decisions/ADR-0022-VARIANT-DISPATCH-IN-THE-PARSER.md) that is two
classes with one constant sentence each rather than one class choosing between
two phrases. The parser dispatches on `Scan.reportsUndiscoveredStar` — the one
implementation, which `BodySurveyFacts` delegates to, so no record reads the
semantic layer to describe itself and no layer re-derives the reading. A
trigger that cannot describe itself fails the turn through the existing
preparation-failure path: no provider call, and the internal kind is never a
fallback. `llmPresentation()` is untouched and is still not called in
production. Every phrase was checked against the Frontier Player Journal manual
reference and the pinned `jixxed/ed-journal-schemas` revision, and 83 of them
were corrected: claims the source does not make were removed
(`ShipRedeemed` no longer says a ship joined the fleet, `ApproachBody` no longer
denies a landing, `DropshipDeploy` now says the conflict zone the manual names),
one inverted direction was fixed (`ShipyardTransfer` brings a ship **here**), and
`EngineerLegacyConvert` gained a second fixed phrase because `IsPreview` is not
sent and a preview is not a conversion. `PackageDependencyRulesTest` now also
forbids `kairon.observation` importing `kairon.semantics`.

`events[*].reverses` is **removed** with the same reasoning: it named its
counterpart action with the internal kind, which no event sends any more, and
it collapsed five distinct relations into one word. The semantic relationship
itself is untouched and still reaches diagnostics.
`DecisionRequestArchitectureGuardTest` now walks **every string value** of every
current event — nested objects and arrays included — and fails if any of them
equals a declared kind, so the vocabulary cannot return under a third field
name; a fixture carrying `LeaveBody` and `Undocked` keeps that guard honest. `changes`, `context`, `trajectory`, selection, batching, the graph
and persistence are unchanged; a before/after
`ModelFacingReplayBaselineTest` diff over 46 turns and 76 events showed
`events[*].kind` → `events[*].event` and nothing else, with documents growing
from a median of 264 to 315 characters and a maximum of 1 031 to 1 394 against
the unchanged 16 000-character budget.

The production model input is `kairon-llm-decision-v1` and the turn trace is
`kairon-turn-trace-v6`. The earlier `kairon-llm-situation-v2.1` context — DTO,
factory, serializer, compactor and prompt — has been **removed** from source,
as was the v1 context and the temporary shadow measurement path before it. No
fallback, runtime version selector or dual serialization exists.

## Version and build

- Maven artifact: `kairon:kairon:0.1.0-SNAPSHOT`.
- Runtime: Java 21, single Maven module.
- Maven Wrapper: Maven 3.9.16.
- Last implementation verification: `mvnw.cmd clean test` on 2026-08-04, 878
  tests run, 0 failures, 0 errors, 3 skipped, BUILD SUCCESS. The three skips
  are the opt-in manual behavior-graph replay (`BehaviorGraphManualReplayTest`,
  which runs only when a journal path is supplied), the `@Disabled`
  known-invalid `UNDER_ATTACK` contract in
  `SemanticPipelineKnownInvalidContractTest`, and the opt-in full-journal
  regression (`FullJournalReplayRegressionTest`, which runs only when the
  private journal and reference-trace system properties are supplied). On a
  headless Linux runner one additional UI test
  (`BehaviorGraphOccurrenceDialogTest`) skips itself by design, for four total.
- `mvnw.cmd clean` is safe again. The v1 regression oracle that previously
  made `target/audit/` irreplaceable was deleted with the v1 path. What
  remains under `target/audit/` is disposable evidence; every decision it
  supports is recorded in this document, in
  [`kairon-llm-decision-interface.md`](design/kairon-llm-decision-interface.md)
  and in [`decisions/`](decisions/).

Build commands:

```text
./mvnw test
./mvnw package
```

On Windows use `mvnw.cmd test` and `mvnw.cmd package`.

## Implemented product loop

The application currently implements:

```text
live journal or test-only paced replay
    -> strict complete-record parsing
    -> exact raw JSON plus typed journal payload
    -> in-process ObservationBus
    -> sequential current-state and behavior-graph projection
    -> immutable ProjectedObservation
    -> selected NEW snapshot batching
    -> ordered trigger presentations plus final state and graph situation
    -> one OpenAI-compatible model request
    -> structural SILENT/COMMENT validation
    -> console and optional Google speech output
    -> one snapshot-based JSONL turn trace
```

In parallel, an optional Swing monitor displays every published journal event
and every resolved/terminal model turn through one `KaironGuiHub`.

When `behaviorGraph.enabled` is true, the projection boundary updates the
deterministic per-ship behavior graph and captures its active-episode situation
before publishing the observation downstream. That captured situation enters
the LLM snapshot; no later graph query is made while constructing a turn.

In `LIVE` mode the adjacent `Status.json` file is also polled as a replacement
snapshot source. Complete valid raw snapshots traverse `ObservationBus`; the
projection boundary derives graph-only state-change occurrences from them.

## Sources and execution modes

Two source modes are implemented:

- `LIVE`: bootstrap the active journal, then poll complete appended records and
  handle bounded rotation;
- `REPLAY`: pace one finite journal file from its recorded timestamps, then
  publish a typed replay-exhaustion signal to flush pending observer work.

Both journal modes use the same journal parser, adapter, bus, subscriptions,
correlation, window, prompt, LLM, validator, output, and trace code.

Replay publishes its first valid record immediately. A later record waits its
positive timestamp gap from the previous successfully published record at
one-times speed, capped at ten seconds. Missing, invalid, equal, or backward
timestamp relationships wait zero. Every successful publication replaces the
baseline with that record's optional timestamp, so a missing/invalid timestamp
also makes the following record immediate. Replay waiting is interruptible on
shutdown or GUI close and does not alter live polling.

Replay observations keep original `rawJson` and `sourceTime`; `observedAt` is
the actual post-wait publication time. There is no replay speed or pacing
configuration: selecting `REPLAY` selects this test behavior.

See [ADR-0009](decisions/ADR-0009-PACED-REPLAY.md).

Elite Dangerous journal files and live `Status.json` replacement snapshots are
implemented external telemetry sources. `PollingStatusWatcher` reads the
`Status.json` beside the journals, validates it independently of the journal
catalogue, and publishes exact immutable `StatusSnapshotObservation` values
through `ObservationBus`. Cargo, NavRoute, Market, microphone, and other
external sources are not implemented.

`Status.json` capture is live-only. The current paced Journal replay does not
have a captured status-snapshot history and therefore cannot reproduce state
changes that exist only in that file.

## ObservationBus and subscribers

`InProcessObservationBus` is implemented as one FIFO `observation-bus`
executor. It assigns a positive process-local sequence and invokes active
matching handlers in registration order.

Implemented raw-bus subscribers are:

- `ObservationProjectionSubscriber`;
- `TelemetryDiagnosticSubscriber`;
- optional `DesktopUiSubscriber`.

`ProjectedObservationBus` invokes `LlmJournalObserverSubscriber` only after
canonical state and graph processing have reached a terminal result.

Publications are immutable and contain no subscriber lifecycle state. Handler
exceptions are isolated and reported in transport receipts. Subscriber
handlers are expected to be short handoff points; isolated per-subscriber
mailboxes and bounded backpressure are not implemented.

## Per-ship behavior graph

The optional behavior subsystem is wired to journal, live Status, and source
lifecycle observations only through `ObservationBus`.
`BehaviorGraphSubscriber` hands journal observations, immutable status
snapshots, and the replay-exhaustion signal to its own single-writer executor,
so state-delta calculation, Jackson storage, and graph updates do not run on
the bus thread.

`GraphId` combines commander FID and the concrete positive `ShipID`.
Graphs for different ships are independent; a `Loadout` change updates compact
metadata and context without changing graph identity.

The implementation keeps two linked representations:

- `SystemEpisode` stores the exact chronological occurrences and
  occurrence-level transitions for one visit to a star system;
- `ShipBehaviorGraph` stores one node per normalized event type and aggregates
  directed global and contextual transition counters across that ship's
  episodes.

`GraphCursor` identifies the final significant occurrence of the active
episode, and is **absent** while a restored visit has recorded nothing yet.
`FSDJump` creates exactly one `SYSTEM_ENTRY` root, and an in-system ship
switch mints a synthetic one. A session-restoring `Location` creates **no
root and no occurrence at all**: it opens an empty `LOCATION_RESTORE`
episode (`SystemEpisode.startRestored`, `rootOccurrenceId == null`,
`awaitingFirstOccurrence()`), and the first structural event of that visit
becomes occurrence zero **without an incoming transition**. Saying where the
Commander already is is not an arrival, and the graph no longer learns an
edge out of a session restart. Completing or
switching an episode does not create an ordinary edge across the boundary.
Stable journal observation identity makes occurrence, episode, and transition
identities deterministic and suppresses duplicate replay input. Each
occurrence additionally has a persisted subscriber-owned `episodeSequence`.
That value is the total accepted order inside one episode across journal and
status-derived input. Journal byte offsets and Status source positions remain
source-local identity evidence and are not compared as one shared ordering
domain.

`EventSignificancePolicy` selects graph boundaries, structural occurrences,
context updates, and ignored noise from typed event classes.
This is projection granularity, not an LLM importance or commentary policy.
`BehaviorEventNormalizer` creates compact normalized attributes; complete raw
journal JSON is not copied into episode files and remains authoritative in the
observation/source path.

The current structural policy additionally records docking requests and
grants, function-specific limpet launches, interdictions, attack alerts,
material collection, and fuel scooping. Unknown `LaunchDrone.Type` values are
retained under the generic `LIMPET_LAUNCHED` type rather than rejected.
`MaterialCollected`, `UnderAttack`, and `FuelScoop` can emit repeated progress
or alert records without a documented operation boundary. The graph therefore
keeps the first record of one uninterrupted same-key run and suppresses only
immediately continuing records until another significant occurrence or
episode boundary. This deterministic projection uses no wall clock or
invented end event; the full Journal remains authoritative.

`FSDTarget` is treated as a **route-target state observation**, not as an action
in itself. A structural `FSD_TARGET_SELECTED` occurrence
(`ROUTE_TARGET_SELECTED` to the model) is admitted only when the effective
target identity or the route position meaningfully changes; repeated journal
publications of the same target create no additional occurrence. The journal
republishes an unchanged target — the same system with the same remaining jumps,
restated around a jump already under way — and admitting each publication made
one selection read as two and pushed a real earlier event out of the three
remembered predecessors. This is repeated equivalent journal state, described as
such and not as a documented Frontier defect.

The comparison (`RouteTargetSelectionPolicy`) is of state, never of time or
adjacency: the candidate is compared with the **last route-target occurrence of
the active episode**, whatever arrived in between, so two equal targets are one
selection however far apart they are and two different targets are two
selections however close together. The basis is `SystemAddress` when both
records carry one, otherwise the normalised `Name` (`strip` + upper case,
`Locale.ROOT`, no fuzzy matching), plus `RemainingJumpsInRoute` when available —
absent on both sides counts as equal, absent against a concrete value does not.
`StarClass` is target metadata and never on its own defines a new selection. A
record that first establishes a `SystemAddress` where the previous one had none
is preserved as its own occurrence rather than matched on the weaker identity:
an occurrence already written cannot be enriched in place.

The rule is scoped to route targets and admission only. Canonical state carries
no route target (nothing in `CurrentGameStateProjector` reads `FSDTarget`), the
event stays `CONTEXT_ONLY` so it never opened a model turn in the first place,
and parsing, publication, diagnostics and corpus capture are unchanged — both
raw records still exist. Occurrence admission for every other event type is
untouched. **Existing graphs may retain previously admitted duplicate
`FSDTarget` occurrences**: this affects future admission only, no persisted file
is rewritten, no historical edge count recalculated and no migration introduced,
so clean provider-free test graphs are required to observe the corrected
trajectory.

The five newly researched neutral event records
(`DockingRequested`, `DockingGranted`, `LaunchDrone`,
`MaterialCollected`, and `FuelScoop`) now own conservative English
presentations. They remain `DIAGNOSTIC_ONLY`; behavior-graph admission does
not silently add LLM calls or alter `BALANCED`.

The current exploration policy treats a scanner **result** as structural
while refusing to count the same result twice. `Scan` and `FSSBodySignals`
are `SIGNIFICANT` types, and `BodySurveySelectionPolicy` decides per
occurrence whether this record is a distinct result: a `Scan` that is not
`Detailed` or carries no `(SystemAddress, BodyID)` established nothing, and a
reading identical to the last reading of the same body in this visit —
whichever scanner produced it — is the same finding restated.

One shallow reading is an exception, and it is not a scan result.
`BehaviorEventNormalizer` normalizes a `Scan` that reports a `StarType` and
`WasDiscovered: false` at less than `Detailed` depth as
`SYSTEM_UNDISCOVERED_CONFIRMED` rather than `BODY_SCANNED`, and
`BodySurveySelectionPolicy` records it only when its `(SystemAddress, BodyID)`
is the one the episode root names — the body the completed jump that opened
this visit arrived at — and only once per visit. An episode with no root (a
`LOCATION_RESTORE` visit) has no arrival body and records none. This is the only
record in a visit that carries the arrival star's class and its discovery flag
at all; before it existed, both were lost from every model-facing event and
surfaced only as an unattributed canonical change in whatever turn came next.
`ScanBaryCentre`
is untouched and remains `NOISE` / `DIAGNOSTIC_ONLY`. `SAAScanComplete` **is
structural**: it reports the completion of a deliberate
multi-step action — flying to a body and expending probes to map it — and that
completion is part of what the Commander did. `SAASignalsFound` remains a
separate structural result occurrence carrying biological-signal context. The
two are never merged: one is the action, the other is what the scanner then
reported. The resulting sequence is

```text
SAA_SCAN_COMPLETE → SAA_SIGNALS_FOUND
```

and the model-facing trajectory names them `BODY_MAPPING_COMPLETED` and
`BODY_SIGNALS_FOUND`. Both events share one journal timestamp in practice;
order comes from source/FIFO arrival and the contiguous `episodeSequence`,
never from the clock. A detailed `Scan` is now structural as `BODY_SCANNED`
(model-facing `BODY_SCANNED`), and `FSSBodySignals` as
`FSS_BODY_SIGNALS_FOUND`, which shares the model-facing name
`BODY_SIGNALS_FOUND` with `SAA_SIGNALS_FOUND` — what the Commander learned is
what is on the body, and which instrument reported it is Kairon's
bookkeeping. Neither new kind carries `occurrenceOnBody`: a repeat is never
recorded, so the count could only ever be one. Graphs written before this
change contain no `SAA_SCAN_COMPLETE`, `BODY_SCANNED` or
`FSS_BODY_SIGNALS_FOUND` occurrences: they remain readable, but their
topology reflects the old admission. `LeaveBody` is structural and
records crossing above orbital-cruise altitude as a distinct planetary-route
departure. All these event classes retain independently reviewed
`LlmPresentableJournalEvent` implementations; graph classification does not
change their `BALANCED` or `CONTEXT` observer roles.

The live Status path preserves each exact validated snapshot as immutable raw
external evidence. `StatusStateDeltaAdapter` is state owned by the behavior
subscriber; the first known value of each tracked field only establishes that
field's baseline. Later observed changes produce these structural occurrences:

- `FSS_MODE_ENTERED` and `FSS_MODE_EXITED`;
- `SAA_MODE_ENTERED` and `SAA_MODE_EXITED`;
- `LANDING_GEAR_DEPLOYED` and `LANDING_GEAR_RETRACTED`.

The interpretation follows the official Frontier Status contract:
`GuiFocus = 9` identifies FSS mode, `GuiFocus = 10` identifies SAA mode, and
`Flags` bit 2 (`0x00000004`) means landing gear down. Missing optional
`GuiFocus` does not mean `NoFocus`, malformed or unchanged snapshots emit no
transition, and multiple changes from one snapshot use a fixed deterministic
order. See the
[Frontier Player Journal Manual v37, Status File](https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf).

These six status-derived types are behavior-graph facts only. They have no
`LlmJournalEventSelection` role, cannot start a model turn, do not enter model
context or the aggregate turn trace, and do not imply that opening a scanner
completed a scan.

Transition counters preserve never-decreasing historical counts and maintain
incremental exponential decay using the configured half-life. The first
context keys distinguish `SAA_SIGNALS_FOUND` by biological-signal count and
landability, and `TOUCHDOWN` by vehicle kind and known biology. Prediction
mixes observed contextual support with a configurable global prior and always
receives an explicit evaluation time.

The default file-backed implementation stores aggregate and episode JSON
under the configured `behaviorGraph.storageDirectory`, separated by commander
FID and `ShipID`. Active episodes and completed episode files are separate
from `graph.json`; writes use a forced temporary file followed by atomic move
where supported. `BehaviorGraphQueryService` and `BehaviorGraphExporter`
currently provide programmatic query, deterministic JSON, chronological
episode JSON, and Graphviz DOT output. The Swing `Behavior Graph` tab renders
the global topology and global decayed edge weights for the active ship while
overlaying counts from the active episode only. A structural node can be
selected independently of `GraphCursor`; one reusable modeless occurrence
dialog queries only matching `EventOccurrence` values from the current active
episode and formats their persisted normalized attributes and
`ContextSnapshot` read-only. The dialog is opened on demand so the graph keeps
the full tab area. Completed episodes are not loaded into this inspector.
There is no historical episode browser or standalone export CLI.

If a paced replay encounters deterministic occurrence IDs already stored from
an earlier pass, `BehaviorGraphService` exposes a process-local, progressively
advancing view of that same episode. The view updates active-episode counts,
`GraphCursor`, graph revisions, and internal UI notifications as each stored
occurrence is encountered, while global node counters, transition counters,
and decayed edge state remain unchanged. It is not persisted as a second
episode and does not make unrelated completed episodes available to the
occurrence inspector.

An opt-in replay of the 26,167-record
`Journal.2025-10-24T174116.01.log` produced three ship graphs, 22 episodes,
27 aggregate nodes, 41 edges, 212 projected occurrences, and 190 occurrence
transitions. Its two independent empty-store runs produced 28 matching export
files with no SHA-256 mismatch. In the recon-limpet activity, 46
`RECON_LIMPET_LAUNCHED` occurrences and 43 projected
`MATERIAL_COLLECTED` runs exposed the dominant transitions
`RECON_LIMPET_LAUNCHED -> MATERIAL_COLLECTED` (42 observations) and
`MATERIAL_COLLECTED -> RECON_LIMPET_LAUNCHED` (40 observations).

The live journal bootstrap still publishes only its bounded historical suffix.
After that suffix has been dispatched, live startup publishes the current
valid Status snapshot as `BOOTSTRAP` to establish tracked-field baselines.
This baseline does not require graph identity; a later derived change becomes
an occurrence only when commander FID, concrete `ShipID`, and an active system
episode are available. Absence or a transient invalid Status file creates no
baseline and no occurrence; the first later valid live value for each field
becomes that field's baseline.

A complete journal rebuild uses finite journal replay into an empty graph
store, but cannot reconstruct uncaptured historical Status changes. A later
live run does not infer missing active identity from the graph directory.
There is no graph-only production replay command: the existing `--config`
replay path runs the full configured application, including its LLM observer.

An opt-in empty-store replay of the 1,651-record journal
`Journal.2026-07-24T183849.01.log` produced two byte-identical rebuilds with
one graph, 25 episodes, 20 nodes, 41 aggregate edges, 355 occurrences, and 330
occurrence transitions. The previous scanner-result self-edge was absent and
all eight `LeaveBody` records became exact route occurrences. This local
full-journal evidence is not a committed fixture. The v1 store does not
migrate old aggregates after an admission-policy change, so applying this
correction to an existing historical graph requires an empty-store replay.

See [ADR-0011](decisions/ADR-0011-BEHAVIOR-GRAPH.md); for session
restore and scanner-result admission,
[ADR-0014](decisions/ADR-0014-SESSION-RESTORE-AND-SCANNER-RESULTS.md);
for current-state reconciliation and visit-scoped findings,
[ADR-0015](decisions/ADR-0015-CURRENT-CHANGES-AND-VISIT-SCOPED-FINDINGS.md);
and for historical scanner results and non-positive counts,
[ADR-0016](decisions/ADR-0016-HISTORICAL-FINDINGS-AND-POSITIVE-COUNTS.md).

## Telemetry catalogue

The checked-in runtime catalogue is pinned to
`jixxed/ed-journal-schemas` revision
`33a8f35e81868b168b4bbd647b5e13dbd8de062a`.

It contains 272 known journal discriminators represented by 272 typed identities
in 15 category packages: 267 small public record classes and 5 sealed interfaces
whose 21 nested record variants are the domain events those wire names carry
([ADR-0022](decisions/ADR-0022-VARIANT-DISPATCH-IN-THE-PARSER.md)). Each is a
typed transport identity around the same exact `RawJournalData`; it is not a
field-by-field domain model. The 114 identities selected by `BALANCED` and
`CONTEXT` additionally implement `LlmPresentableJournalEvent` and own
researched, event-specific English presentations.

Unknown, missing, and non-string event discriminators use
`UnknownJournalEvent`. Unknown fields on known events remain in raw JSON.
`Status` is not a journal event and is not part of this catalogue. It has a
separate raw typed `StatusSnapshotObservation` source contract and is not an
LLM-presentable journal event.

No catalogue generator or standalone schema manifest is present in this
repository. The checked-in Java catalogue and records are the executable
source of truth.

## Observer input and context profiles

`LlmJournalEventSelection` exposes these active researched profiles:

- `BALANCED`: 112 reviewed types for the `NEW` FIFO,
  including `Commander`, `Friends`, `ApproachBody`, `ReceiveText`, `DockSRV`,
  `Scan`, `FSSBodySignals`, `SAASignalsFound`, and `LeaveBody`;
- `CONTEXT`: two context types: `FSDTarget` and `Location`.

The counts left the profile names in
[ADR-0022](decisions/ADR-0022-VARIANT-DISPATCH-IN-THE-PARSER.md). `BALANCED-112`
pinned how many wire types had been researched into the identity of the profile,
and stopped being answerable once one wire type could dispatch to several
classes. `NEW_EVENT_TYPE_COUNT` and `CONTEXT_EVENT_TYPE_COUNT` are derived from
the lists, and class initialisation checks what the constant was really
protecting: a class listed twice.

All 114 selected records implement the researched presentation contract.
Every other known or unknown event currently has the runtime role
`DIAGNOSTIC_ONLY`. Those 158 known types and `UnknownJournalEvent` still
traverse state and graph projection and reach diagnostics and the GUI, but do
not enter the LLM batch. No raw-JSON semantic fallback is used.

`Commander` and `Friends` are NEW-eligible. `Commander` reports which
Commander took up the current game session, and reaches the model as
`COMMANDER_SESSION_STARTED` naming the Commander. `Friends` reports the
documented friend status; the startup `Online` snapshot is recorded internally
as `LOGIN_TRANSITION_NOT_ESTABLISHED` and is not sent, because the event
reports a status and never asserts a transition into it. Like every
NEW-eligible type, either starts a turn only when captured as `LIVE` or
`REPLAY`; historical `BOOTSTRAP` remains model-silent.

Four admission rules narrow NEW eligibility below the type level.
`LlmJournalEventSelection.admitsAsTrigger` declines a `ReceiveText` whose
`Channel` is `npc`: ambient station and traffic chatter addressed to nobody in
particular. The decision reads the `Channel` field only — never the message
text and never a localised rendering, either of which would make admission
depend on the game's display language. `ReceiveText` remains in
`BALANCED`, every other channel is unaffected, and a declined
observation is still parsed, projected into canonical state and the graph,
recorded as a semantic effect for the next turn, traced and shown in the GUI.
When a batch would have consisted only of declined observations, no batch
exists and the provider is not called.

The same method declines a `Scan.BodyReading` whose depth is not `Detailed` or
which names no body, and a signal record from either scanner reporting no
positive count of anything: neither established a result. One shallow reading is
admitted — a star with `WasDiscovered: false`, which the parser has already given
its own class, `Scan.UndiscoveredStar`, from the record's shape alone. A fourth,
stateful check
follows it. `BodySurveyNoveltyGuard`, owned by `LlmJournalObserverSubscriber`,
declines a scanner result identical to the one the model was already given for
that body **during this visit**. It applies the same rule as the graph
(`BodySurveyFacts`) but keeps its own memory, so model input never depends on
whether the behaviour graph is enabled or on anything it has persisted. The
guard also owns the episode-scoped half of the arrival-star milestone: it
records the `(SystemAddress, BodyID)` of the completed `FSDJump` that opened the
visit — and of nothing else, because a restored `Location` is not an arrival —
and admits the undiscovered star reading only when it is filed under that body
and the visit has not been told yet. The graph asks the same three questions of
the same fields against its own episode root, so a reading one admits is a
reading the other records.

A scan's signature is what the body **is**, not where it is: the body key, the
scan depth, the coarse kind, `StarType`, `PlanetClass`, `Landable`,
`TerraformState`, `Atmosphere`, `Volcanism` and the three survey flags. No
orbital measurement is part of it. Completing a surface survey makes the game
re-emit the whole scan record — identical in every classification and flag,
still `WasMapped: false`, with the survey reported by `SAAScanComplete` and the
signals by their own record beside it. Only `DistanceFromArrivalLS` and
`MeanAnomaly` had moved, because the body travels along its orbit between two
readings. While the distance was part of the signature every one of those
restatements compared as a new result: a second `BODY_SCANNED` for the model
and a second occurrence for the graph, with an `SAA_SCAN_COMPLETE →
BODY_SCANNED` transition nobody made. Every `SAAScanComplete` in the observed
journal was followed by one. The record is still projected, so the fresh
distance still reaches canonical state and the model through the next event and
the body context, where it is a fact rather than an identity.

The guard's visit begins on the same observations the graph opens an episode
on, derived independently from the journal: a completed `FSDJump`, a change of
commander or ship, and a `Location` that either names a different system or
arrives with no visit in progress. A `Shutdown` ends the visit, so a session
resumed in the same system is a second look at it. A `Location` restating the
system already in progress opens no episode and resets nothing. The visit is
an internal monotonic counter: it is never published, never serialized and
never shown to the model.

Capture mode is checked **before** the guard. A historical `BOOTSTRAP` result
is model-silent, so it must not enter the memory of what the model was told;
otherwise the live reading repeating it would be silenced by a finding nobody
was ever given. The behaviour graph refuses the same three records for the same
reason: `BodySurveySelectionPolicy` declines a `BOOTSTRAP` `BODY_SCANNED`,
`FSS_BODY_SIGNALS_FOUND` or `SAA_SIGNALS_FOUND` before any occurrence exists, so
no cursor moves and no transition is learned. Canonical state is projected before
the graph is consulted and is unaffected: a historical reading still restores the
body facts and the positive signal counts it established. This is not a general
rule about historical capture — every other structural type, `SAA_SCAN_COMPLETE`
included, is recorded on bootstrap exactly as before. It is specific to the three
records whose recording decides whether a *later* reading counts as a finding at
all, and it is what keeps the structural occurrence and the model-facing event on
the same observation.

Both scanners are NEW-eligible. `FSSBodySignals` and `SAASignalsFound` share
the model-facing kind `BODY_SIGNALS_FOUND` and the trajectory name of the same
spelling: what the Commander learned is what is on the body, and which
instrument reported it is Kairon's bookkeeping. Either can be the instrument
that reports a set first; the second one restating it opens no turn, and a
second one reporting more is a second finding with its own turn. Two
`BODY_SIGNALS_FOUND` in a row are therefore legitimate, and what distinguishes
them is in `events[].signals`.

One `Scan` reaches the model under a kind its class does not decide.
`DecisionEventCatalog.ruleFor(JournalEventObservation)` returns
`SYSTEM_UNDISCOVERED_CONFIRMED` for the arrival-star reading described above and
the class-keyed rule for everything else; the rule is deliberately outside the
112-entry table, which stays one rule per eligible type. It keeps three of the
adapter's attributes through `DecisionEventRule.retainedQualifiers` — the
`system`, the `starType` and `previouslyDiscovered` — and names the scanned body
`arrivalStar`, declared in `CONTEXT_SLOTS_STATED_BY_EVENT` as answering
`body.name`. The kind asserts only that this star had not been discovered before
now; it does not claim a first-discovery credit has been registered, which the
journal does not say.

It is read by `DecisionMechanism.ARRIVAL_DISCOVERY`, whose only context need is
`SYSTEM`: the milestone's subject is the system, and the star is where the fact
is carried rather than a body the turn describes. So the turn is the event and
the trajectory, and nothing else. The other body facts the same reading
established are still canonical and still exact — `previouslyMapped`,
`previouslyFootfalled`, the zero `distanceFromArrivalLs` and the coarse type
`STAR` — but none of them adds to the milestone: the two survey flags are not
what "undiscovered" turns on, the distance is zero because this star *is* the
arrival point, and the coarse type restates the class beside it.

`ApproachBody` is NEW-eligible and reports entry into a planet or moon's
orbital-cruise zone. A detailed `Scan` is now NEW-eligible and reaches the
model as `BODY_SCANNED`, carrying only the facts a comment could use: body,
system, scan depth, body type, planet class or star type, landability,
terraform state, atmosphere, volcanism, the three previously-* flags and the
arrival distance. Mass, radius, temperature, gravity, orbital elements,
rings, bulk composition, atmospheric composition and material percentages are
not sent.

A change carried by a hidden observation is reconciled against the final
canonical state before it is selected. An observation the model is not shown
keeps its effect until a turn closes over it, and other observations move the
same field in between; the effect that survived the other rules was then
whichever one happened to, not whichever one was true. A restored session
establishing `flightMode = NORMAL_SPACE` outlived the supercruise jump that
replaced it and arrived in a turn whose state already said `SUPERCRUISE` —
and, because a change registers the field as already stated, it displaced the
correct value from the context, so the one thing the document said about the
flight mode was the wrong thing. `DecisionChangeSelector.stale` compares
`change.after()` with `CurrentGameStateSemantics.valueOf(field, finalState)` as
typed semantic values — never as serialized text, and never by re-deriving the
field. It applies only where no event of the request caused the change: a
trigger-owned change is attributed by its `eventId`, and an event of a batch
really can report a step a later event of the same batch moved on from. A
hidden change that is still current is still sent, with no `eventId`, exactly
as before. `eventId` is internal throughout — the causing event's position in
`events`, read by the selector and by the contract tests, and serialized
nowhere.

A change is dropped as already said only when an event of the request states
**the same canonical field at the same value**. `ProjectedEvent.states` takes a
`SemanticField` and a `SemanticValue`, and matches against the facts the
projection actually emitted, keyed by the canonical identity it emitted them
under — the field's own model-facing name from `DecisionNames`, or the slot
declared for an event field that answers a canonical slot under another word
(`system`, `body`, `ship`, `vehicleKind`). Value equality alone used to be
enough: a landing reporting `occurrenceOnBody: 1` counted as having stated every
field whose value happened to be one, so a biological count of one was suppressed
into the context while a count of two stayed in `changes`, and which section a
fact appeared in depended on an unrelated integer.

Matching by value alone had also been suppressing one change correctly by
accident. An `FSDJump` selects the arrival star, whose name is the system's own,
so `body.name` became the system name — and the event's own `system` field, the
same string, dropped it. Field-aware matching stopped doing so and the change
surfaced, reading as a body created or renamed after the system, and on a later
jump as `UPDATED` from the previous system's name. The `TRAVEL` mechanism now
declares that it states `BODY_NAME`: arriving at the arrival star is what a jump
is. Of the events that mechanism reads, only a jump selects a body at all;
arriving at a real body is `BODY_TRANSIT`, which declares nothing of the kind.
Canonical state still selects the arrival star.

A reported signal set states the canonical counts its categories are **declared**
to carry: `BIOLOGICAL` states `BIOLOGICAL_SIGNAL_COUNT` and `GEOLOGICAL` states
`GEOLOGICAL_SIGNAL_COUNT`, through `DecisionNames.signalCategoryField`, and only
for the categories the set actually contains. Human, Thargoid and uncatalogued
categories state nothing, because no canonical field exists for them. So a
`BODY_SIGNALS_FOUND` reporting one biological signal no longer also carries
`biologicalSignals: {"after": 1}` as a change or `biologicalSignals: 1` as
context — the set said it once. A category the same reading says nothing about
stays where it belongs: an earlier biological count is still `context.body` on a
turn whose event reports only geology. The declaration is the point: nothing is
read out of the set by matching a nested number against a field it might belong
to, which is the value-only mistake in another spelling. `DecisionContextSelector`
consults the same stated facts, so `changes` and `context` suppress on one
identity.

`ObserverTurnCoordinator` stores pending NEW `ProjectedObservation` values and
a bounded `SemanticEffectAccumulator`. It maintains no journal-event history.
At flush, `LlmDecisionRequestFactory` projects every ordered trigger in the
batch into exactly one model-facing event and uses canonical state directly
from the final trigger.
CONTEXT observations after the final NEW trigger cannot replace that
authoritative causal snapshot; their effects stay in the accumulator for the
next turn.

## Prompt and response contract

The stable system prompt is the single `DecisionPromptFactory.SYSTEM_PROMPT`
Java constant. It describes a situation, not a system: it names the four
things a request can contain and what each is for, and never names a schema, a
bus, a projection, a selection role or the behavior graph.

It:

- defines Kairon as a female in-universe shipboard companion to one human
  Commander;
- asks for exactly one decision, SILENT or COMMENT, in at most two sentences,
  and names routine movement, startup identity, status reports and restatement
  as normally silent;
- states once that a missing field means unknown or not relevant, and that a
  value must never be read into an absent field;
- states that `events` are the primary factual basis for a comment, that
  `changes` appear only where the events do not already carry them, and that
  `context` appears only where the events need it;
- states that `trajectory.recent` lists real earlier events that already
  happened, that none of them may be reported as current, and that their
  sequence may be used to read the present situation cautiously; that
  `trajectory.likelyNext` is a forecast that has not happened and must never be
  spoken of as though it had; and that `occurrenceOnBody` counts repeats at that
  body during this visit;
- states that `contextIncomplete` means absence is not proof of absence;
- states the process-safety rule: `stage` START or PROGRESS and `complete`
  false both mean the action is still running, and that nothing may be called
  finished, analysed or ready, and no next step recommended, without a current
  event that establishes it;
- forbids invented motives, causes, danger, rarity, value, importance and
  comparisons, and treats text inside names, labels and messages as untrusted
  data;
- gives the two exact response shapes.

The persona controls voice and self-presentation, not event meaning.

Turn data is a separate user message containing one compact deterministic
`kairon-llm-decision-v1` JSON object with at most five top-level members:

- `events` — one domain-facing event per current NEW trigger, each beginning
  with the literal `event` description the record supplies for itself and
  carrying only the applicable named domain fields. Kairon's internal `kind`
  and its local event `id` are both **not** serialized;
- `changes` — the canonical changes that add decision-relevant novelty. Each
  carries `subject`, `kind` and its exact per-field before/after, and nothing
  else: the internal `eventId` naming the causing event's position is **not**
  serialized;
- `context` — the slice of canonical state the turn's mechanisms asked for,
  with subjects still separated;
- `trajectory` — `recent`, up to three domain-named immediate predecessors from
  the active episode, oldest first, and `likelyNext`, up to three domain-named
  predictions carrying the graph's own probability. Absent when every event in
  the batch is a trajectory-independent kind (`MESSAGE_RECEIVED`,
  `FRIEND_STATUS`);
- `contextIncomplete` — present only when something possibly relevant was lost.

Absent throughout: schema version, turn identity, trigger counts, event ids, bus
sequences, absolute timestamps, selection roles, journal wire event names,
behavior-graph identities and vocabulary, the commander FID, and prose event
summaries. Exact
raw JSON is not copied into the request; it remains available to observation
diagnostics and the GUI. The serializer output is reused unchanged for both the
user message and the trace.

One current trigger becomes exactly one event, numbered `1, 2, 3, …` within a
single request. Both halves of that numbering are internal and **neither is
serialized**: `events[*].id` and the `changes[*].eventId` that points at it. The
model is given no identity for an event and no way to name one. Internally the
number is what `DecisionChangeSelector` decides attribution on and what the
contract tests read; positionally it is the turn's trigger list, so the nth
event came from the nth trigger bus sequence.

There is no separate prompt resource or response JSON Schema.
`ObserverResponseValidator` is the executable response contract. It accepts
either `{"decision":"SILENT"}` with exactly that one property, or
`{"decision":"COMMENT","comment":"…"}` with exactly those two. Comments are
limited to one or two sentences. Any further property — including the removed
`evidence` and the earlier `evidenceTriggerBusSequences` — is
`INVALID_PROPERTIES`; there is no id validation left, because the request offers
no id to validate against. `validate` takes the raw output and the previous
comments, and nothing about the request.

`ValidatedObserverResponse` is `status`, `decision`, `comment`, `violations`
and `failure` — the parsed answer and Kairon's verdict on it, with nothing
derived from the request or the batch on it.

Attribution is computed separately, by `ObserverTurnCoordinator`, from the
batch: `triggerBusSequences` is the turn's own triggers in bus order. It travels
beside the validated response on `ObserverTurnListener.DecisionResolved`, is
retained on `DeliveredModelComment`, and is what the GUI shows and the trace
records. Hidden observations, context facts and previous comments are outside it
by construction. It is a fact about the question, never a claim by the model.

`CommentNoveltyGuard` is unchanged. It rejects a comment that, after Unicode,
case, whitespace, and trailing-punctuation normalization, exactly matches one
of the three previous delivered comments. The violation is
`DUPLICATE_PREVIOUS_COMMENT`. A conservative character-trigram and word-overlap
check rejects only strongly overlapping lexical near-repeats as
`NEAR_DUPLICATE_PREVIOUS_COMMENT`. Invalid output has terminal status
`INVALID` and is not delivered; it is not relabeled as `SILENT`. This guard is
not a semantic classifier; broader paraphrase avoidance remains model-owned,
and no model repair call is implemented.

## LLM providers and authentication

Implemented provider types are:

- `LM_STUDIO`, using a local OpenAI-compatible endpoint;
- hosted `MISTRAL`.

Both use `OpenAiCompatibleLlmClient`, the same semantic messages, and an
explicit configured model identifier. Exactly one provider is active. There
is no model discovery, automatic failover, routing, load balancing, or
cross-provider retry.

Current authentication is not environment-based:

- Kairon reads an ignored `authentication.json` beside the selected main
  configuration;
- LM Studio authentication is optional;
- active Mistral authentication requires a nonblank API key in that adjacent
  file.

[ADR-0003](decisions/ADR-0003-LLM-PROVIDERS.md) records environment-based
secret resolution as a proposed migration, not current behavior.

## LLM request statistics

`KaironApplication` wraps the active `OpenAiCompatibleLlmClient` with one
provider-neutral `LlmRequestStatistics` instance. Every terminal physical
`complete(...)` call produces an `LLM_REQUEST_STATISTICS` log line; closing
the client produces a process summary after accepted callbacks settle when at
least one call completed.

Current measurements include success, failure, and cancellation counts;
provider-reported input, cached-input, output, and total tokens; usage
completeness; cache percentage; end-to-end latency and averages; and
end-to-end output tokens per second. Because requests are non-streaming, this
rate is not time to first token or provider-only generation speed.

Optional pricing on a provider profile enables per-call, cumulative, and
average local cost estimates. Rates are explicit, missing usage is not
invented, and estimates are not provider invoices.

Statistics remain outside `ObservationBus`, model input, output delivery, and
the aggregate turn trace. The component does not retain prompts, model
responses, credentials, authorization metadata, or raw provider exception
text. Its logging failures are isolated from model completion.

See
[ADR-0007](decisions/ADR-0007-LLM-REQUEST-STATISTICS.md).

## Desktop GUI

The initial Swing monitor is implemented and enabled by `ui.enabled`. It shows:

- bounded ordered `BOOTSTRAP`, `LIVE`, and `REPLAY` journal observations,
  including exact raw JSON and source diagnostics;
- resolved `SILENT` and `COMMENT` decisions;
- invalid-output and model-call-failure statuses;
- raw model output, the numeric trigger bus sequences a delivered comment was
  produced from, latency, and terminal console/speech delivery status.

`DesktopUiSubscriber` listens to every `JournalEventObservation`, including
unknown event types; it does not reuse semantic LLM selection.
Each raw row initially has the GUI-only display
`OBSERVER EFFECT = OCCURRED_ONLY`: the source observation happened, but no
observer processing fact is implied.
`DesktopObserverTurnListener` supplies both model decisions and read-only
observer lifecycle effects without publishing them through `ObservationBus`.
Current observer effects distinguish retained or turn-bound context and the
queued, in-flight, processed, failed, or discarded NEW lifecycle.

Effects originate in `ObserverTurnCoordinator` and are correlated to a GUI
row by immutable observation identity and bus sequence, with turn sequence and
turn binding only when the effect is turn-bound. The desktop does not import or
rerun `LlmJournalEventSelection`, inspect event names to reconstruct selection,
or mutate the shared `PublishedObservation`. An observation that has no LLM
subscription therefore remains visible as raw `OCCURRED_ONLY` telemetry.

`SwingKaironGuiHub` is the only Swing ingress. It coalesces a bounded update
queue and performs all view mutation on the EDT. `HudTheme` centralizes current
HUD tokens and control factories. GUI display remains monitoring only and
cannot update previous-comment history. Replay rows use actual `observedAt` as
their primary displayed time; their details retain original `sourceTime` and
raw JSON. Live and bootstrap rows remain source-time-first.

With GUI enabled, closing the window interrupts a pending replay delay and
initiates source/runtime shutdown off the EDT. A completed replay keeps its
window open until the user closes it. With GUI disabled, replay exits
automatically after exhaustion has settled observer work.

See [ADR-0008](decisions/ADR-0008-DESKTOP-GUI-HUB.md).

## Controlled first-100 replay evaluation

A local paced replay of the first 100 complete journal observations using
`mistral-small-2603` produced this baseline under the preceding
`BALANCED-103` profile. The local evidence files are
`var/replay-first-100.log` and
`var/replay-first-100-paced-mistral-small-2603-turns.jsonl`.

- 22 `NEW_ELIGIBLE`;
- five `CONTEXT_ONLY`;
- 73 `DIAGNOSTIC_ONLY`.

All 22 NEW-eligible observations appeared once as `NEW` across 21 model turns;
one turn contained two NEW observations. The model returned 18 `SILENT` and
three structurally valid `COMMENT` decisions. All three comments completed
configured console and speech delivery, but only two comment texts were unique:
one was an exact duplicate of the immediately preceding delivered comment.

The subsequent `BALANCED-108` and `CONTEXT-5` replay trace
`var/replay-first-100-balanced-108-xml-semantic-novelty-kore-mistral-small-2603-turns.jsonl`
contains 28 model turns: 19 valid delivered comments and nine `SILENT`
decisions. It showed repeated biological-signal and route-selection comments
and little use of physical environment data. Trace inspection established
that `Scan` was correlated into relevant turns but its old English
presentation omitted surface gravity and temperature.

The active `BALANCED-109` profile adds the one `ApproachBody` record in this
source, producing routing counts of 32 NEW candidates, five context
candidates, and 63 other observations. `Scan` now exposes the missing physical
facts and the prompt prioritizes concrete timely information. These revised
changes have not yet been evaluated against the real provider.

The 100 raw observations included 17 `Music` events. `Music` is
`DIAGNOSTIC_ONLY`, has no current LLM-observer subscription, and entered no
model turn. Those events nevertheless remained immutable bus publications and
were available to diagnostics and the GUI raw-observation feed. In the GUI
they remain `OCCURRED_ONLY` unless an observer-owned effect is explicitly
reported; the UI does not infer `DIAGNOSTIC_ONLY` itself.

## Model-output and input-quality findings

An earlier controlled local replay produced a structurally valid comment that
called a biological sample “rare”. The model input consisted of raw Journal
JSON and did not explain the event contract or provide a rarity baseline.
That trace therefore demonstrates an input/context defect first; it is not
sufficient evidence by itself that the model ignored knowledge it had been
given.

In the first-100 evaluation, the model also described sulphur content and
yttrium content as “high”. The cited raw observations supplied a numeric
sulphur value and a biological variant identifier containing `Yttrium`, but
did not explain those field meanings or establish either qualitative
comparison. A later turn also repeated an earlier comment exactly despite
receiving it in previous-comment history.

The `Scan` verbalizer identifies material numbers as occurrence percentages
without classifying them as rare or common. The current system prompt forbids
unsupported qualitative comparisons. Deterministic validation prevents
normalized exact repeats and only strongly overlapping lexical near-repeats;
it does not perform semantic fact verification or general paraphrase
detection.

Permanent evaluation discipline is evidence-first: inspect the exact traced
window, confirm that required facts and field semantics were supplied from an
authoritative source, confirm any comparison baseline, and verify instruction
clarity before attributing behavior to the LLM. Missing game/API knowledge in
the input is an application presentation defect. See
[ADR-0010](decisions/ADR-0010-MODEL-FACING-EVENT-VERBALIZATION.md).

The traces demonstrating these issues are under ignored `var/` and are local
evaluation evidence, not committed acceptance fixtures.

## Output support

Implemented output includes:

- `ConsoleCommentSink`;
- `SpeechGateway` with FIFO serialization and request-scoped cancellation;
- the official Google Cloud Text-to-Speech Java client;
- `LINEAR16` synthesis and WAV playback through Java Sound;
- optional console output alongside speech;
- separate console and speech results in the aggregate turn trace.

Speech is considered delivered only after playback completes. Only successful
delivery updates previous-comment history. `SILENT` performs no synthesis or
playback.

Google TTS currently uses an API key from adjacent `authentication.json`, not
Application Default Credentials. This is an implementation gap relative to
the approved ADC direction in
[ADR-0005](decisions/ADR-0005-SPEECH-OUTPUT.md).

Normal automated tests use fake synthesis and audio-player implementations;
they do not call Google or require an audio device.

## Model-independent semantic layer

Implemented as `kairon.semantics`, governed by
[ADR-0012](decisions/ADR-0012-MODEL-INDEPENDENT-SEMANTIC-LAYER.md). It has no
dependency on the LLM layer, JSON serialization, prompt wording, the response
DTO or speech.

- 117 registered semantic adapters, keyed by payload class. All 112
  `NEW_ELIGIBLE` and all 2 `CONTEXT_ONLY` types are adapted; 3 of the 158
  `DIAGNOSTIC_ONLY` types are, because they belong to an adapted mechanism.
- All 272 catalogued types resolve to exactly one `SemanticDisposition`: 101
  `STRUCTURED`, 8 `UNRESOLVED_AUTHORITATIVE_SEMANTICS`, 5
  `NO_CRITICAL_STRUCTURED_FACTS`, 158 `DIAGNOSTIC_ONLY`. A new catalogue type
  without a decision fails `SemanticDispositionCoverageTest`.
- The exact per-field before/after delta is computed in
  `CurrentGameStateProjector`, carried on
  `CurrentGameStateProjection.semanticChanges`, and published on
  `ProjectedObservation.semanticEnvelope`. Nothing downstream recomputes it.
  `ACTIVATED_FROM_CONTEXT` is decided by projector write path, never by
  comparing values.
- `LlmJournalObserverSubscriber` contributes every projection's semantic
  effect and queues a trigger only for `NEW` non-`BOOTSTRAP` observations.
  `ObserverTurnCoordinator` owns a bounded `SemanticEffectAccumulator` and
  drains it in the same critical section that fixes the trigger batch. Over
  the bound it coalesces per field and always attaches a typed
  `SemanticSuppression`; canonical state changes are never evicted.
- Vehicle occupancy, taxi context, multicrew context and fighter presence are
  recorded as `UnresolvedFact` rather than inferred.
- A completed `FSDJump` leaves `flightMode = SUPERCRUISE`. The ship arrives at
  the destination star with the FSD still running; `NORMAL_SPACE` is what
  `SupercruiseExit`, `Liftoff` and `Undocked` mean, and none of them has
  happened. No Status field feeds `flightMode` — every value is written by an
  explicit journal branch in `CurrentGameStateProjector.applyEvent` — so the
  earlier `NORMAL_SPACE` never self-corrected in live capture either.
- `BodyContext` holds the **whole** normalized signal reading for a body
  (`Map<category, count>`), and the published `biologicalSignalCount` /
  `geologicalSignalCount` are read out of it. Human, Thargoid and every other
  category the game reports are retained instead of discarded on the way in.
  Nothing retracts a count. A reading that does not mention a category is
  silence about it, and a reading that lists a category at zero — or below it —
  established nothing there either: the game reports a signal by counting it and
  has no way of saying that what was counted is gone.
  `BodySurveyFacts.normalizedSignalCounts` is the one definition of a normalized
  signal set and keeps only counts above zero, so the canonical merge, the
  signature the graph deduplicates on, the observer's novelty memory and the
  `signals` array the model is shown are the same set and none of them can carry
  a count below one. Nothing is defaulted either: a category no reading has
  counted stays out of the map, so `biologicalSignalCount` /
  `geologicalSignalCount` return `null`, `CurrentGameStateSemantics` reads it as
  `UnknownValue`, and it appears in no change, no context group and no event.
  There is no way to say "surveyed and found none" — that claim would need a
  source that makes it. A reading whose positive set is empty is therefore not a
  finding: no occurrence, no turn, nothing cleared and nothing invented.
- **A Nomad is a Ship-Launched Vessel (`SLV`), not an `SRV`, not a `SHIP` and
  not a class called `NOMAD`.** The canonical vehicle-class domain is
  `SHIP | SRV | SLV | UNKNOWN` (`AuxiliaryVehicleTypes`, referenced by the
  `CurrentGameStateSnapshot.VEHICLE_*` constants), and the concrete model —
  `Nomad` — is a type within a class rather than a class of its own. The
  Elite Dangerous journal exposes an SLV through the legacy fighter and SRV
  channels (`LaunchFighter`, `Cargo(Vessel=SRV)`, `Embark`/`Disembark(SRV=true)`,
  `DockSRV`); Kairon reads those channel names as evidence and never as the
  final classification. Model-facing, the distinction is `vehicleKind = SLV`
  beside `vehicleType = Nomad`. `CommanderLocationMode` gained `SLV` for the
  same reason: being aboard a Ship-Launched Vessel is not being aboard an SRV.
  `AuxiliaryVehicleTypes.canonicalKind` reads the legacy class `NOMAD` — the
  value a behavior graph written before this change persisted on its
  occurrences — back as `SLV`, so `TransitionContextKeyFactory` buckets an old
  landing with a new one; nothing writes `NOMAD` any more and no stored file is
  rewritten or deleted.
- **The inference rule is composite**: an ambiguous `LaunchFighter` lifecycle
  (a vehicle out, class not established) plus a `Cargo(Vessel=SRV)` snapshot for
  that same active vehicle means `SLV`. Neither observation classifies anything
  alone, and a conventional SRV never reaches the rule because `LaunchSRV` names
  its own vessel. Without an ambiguous launch behind it the tag still narrows an
  unknown class to `SRV`, exactly as before. The launch provenance is a private
  projector flag, never published and never model-facing.
  `DockSRV` carrying `SRVType = lander01` or `SRVType_Localised = Nomad`
  (compared without regard to case or locale) confirms `SLV` / `Nomad` and
  records `SLV` against the runtime id; any other named vessel stays `SRV`.
  A later `Embark(SRV=true)` on a runtime id already known to be an `SLV` keeps
  the class — the flag names the record's form, not the vessel.
- A `LaunchFighter` record does not establish the vehicle type. One observed
  journal emitted it for a lifecycle whose later `Disembark(SRV=true)`,
  `Embark(SRV=true)` and `DockSRV(SRVType_Localised="Nomad")` records — same
  runtime `ID` — proved a Ship-Launched Vessel, and the launch record carries no
  `SRVType` or localised name. Canonical state therefore projects it as
  `VEHICLE_UNKNOWN` (`CurrentGameStateProjector.updateVehicleLaunch`) while
  keeping `activeVehicleId` for correlation, the graph normalizes it to the
  neutral `AUXILIARY_VEHICLE_LAUNCHED`, and the model-facing kind is
  `VEHICLE_LAUNCHED`. The reverse inference is equally unsupported: a launch is
  never reported as an SRV. A later record that names the vehicle reports it
  then; delivered turns are never rewritten.
- `Cargo.Vessel` is supporting evidence for the associated vehicle kind. The
  journal tags a cargo snapshot with the vessel whose hold it describes, and
  `CurrentGameStateProjector.refineVehicleKindFromCargo` uses `SRV` — matched
  case-insensitively with `Locale.ROOT` — to narrow a `VEHICLE_UNKNOWN` kind:
  to `VEHICLE_SLV` when the active vehicle went out through the ambiguous
  fighter channel, and to `VEHICLE_SRV` otherwise. It also records the kind
  against `activeVehicleId` in
  `vehicleKindsById` when one is known. `Count = 0` is still evidence; an empty
  hold is a hold. A snapshot with no `Vessel` changes nothing. **It never
  establishes occupancy or physical presence**: `commanderMode`,
  `activeVehicleId` and `flightMode` are untouched, and being in an SRV stays
  something `Disembark(SRV=true)` and `Embark(SRV=true)` say directly.
  A cargo snapshot **refines an unknown kind and never overwrites a known one**
  — a snapshot arriving while a Nomad is out is not a vehicle switch, and a
  `Vessel=Ship` snapshot neither clears an active SRV nor establishes a vehicle
  of its own. `Cargo` remains `DIAGNOSTIC_ONLY`: it opens no model turn and
  creates no graph occurrence, but its state update is visible to every later
  event. `DecisionMechanism.SURFACE` requests `ContextNeed.VEHICLE`, so
  `TOUCHDOWN` and `LIFTOFF` carry `context.vehicle.kind` when the kind is known
  and omit the group when it is not. `SURFACE` deliberately does not request
  `ContextNeed.PRESENCE`.
- A running organic-sampling sequence reaches presence turns.
  `DecisionMechanism.PRESENCE` requests `ContextNeed.SAMPLING` alongside
  `PRESENCE`, `VEHICLE` and `BODY_IDENTITY`, so `EMBARKED`, `DISEMBARKED` and
  `DROPSHIP_DEPLOYED` carry `context.sampling` while a sequence is running.
  `trajectory.recent` holds three predecessors, and two rides between samples
  are enough to lose the scan that started the sequence; the presence events
  themselves say nothing about sampling. This is the case the group exists for,
  and it is unchanged by the suppression below.
- **`BIOLOGICAL_SAMPLE` event stages and `context.sampling.stage` are two
  vocabularies for two tenses.** The event states the transition it just made —
  `START`, `PROGRESS`, `FINAL` with `complete` beside it — and is unchanged.
  The context states the persistent state already reached: canonical `START`
  becomes `STARTED`, canonical `PROGRESS` becomes `IN_PROGRESS`. The mapping is
  the explicit `DecisionNames.samplingContextStage` table, never the enum name,
  and an unmapped value drops the field rather than falling back to the event's
  tense. Canonical `BiologicalSamplingStage`, journal parsing, semantic change
  values, graph node names and trajectory names are untouched.
- **The two vocabularies never appear in one turn.** `context.sampling` is
  omitted from a turn whose batch contains a `BIOLOGICAL_SAMPLE`, decided on the
  event's mechanism (`DecisionMechanism.SAMPLING`) rather than on its kind
  string. The scan already reports the organism, the position it reached and
  whether that finished it, so the group added nothing but a second spelling of
  the position — `stage: PROGRESS` in the event beside `stage: IN_PROGRESS` in
  the context — which is the reading the two tenses were separated to prevent.
  `context.body` and `context.commander` are unaffected and still reach the
  sampling turn: neither is something the scan states. The `SAMPLING` mechanism
  keeps `ContextNeed.SAMPLING`, which also puts the sampling subject in scope for
  a hidden change; only the group is dropped. Canonical sampling state, the
  graph, the prompt, the response contract and `trajectory` are untouched.
- Completed or inactive sampling is omitted from context. `Analyse` clears the
  canonical process, so there is no `FINAL` or `COMPLETED` standing state and no
  `active: false`; the whole group is absent. `context.sampling` carries
  `organism` and `stage` only, and an active sequence with no known variant
  label sends `stage` alone rather than a blank string or a Codex token.
- `likelyNext` is unchanged by any of this. It remains the raw historical graph
  prediction — `DISEMBARKED → BIOLOGICAL_SAMPLE_STARTED` at probability `1.0`
  still reaches the model exactly as calculated. The sampling context
  supplements it and does not filter, rerank, annotate or renormalize it.

## Behavior-graph occurrence provenance

Every occurrence the graph accepts records an `EventOccurrenceSource`:
`JOURNAL`, `STATUS` or `SYNTHETIC` (the ship-switch episode root). It is
recorded at acceptance and carried into `SituationOccurrence`; it is never
derived from the normalized event type, because a journal event and a Status
delta can normalize to the same type.

It is **not persisted**. The graph store schema is unchanged, so an occurrence
restored from disk reports `null` — an explicit absence, never a guess.
Provenance and prediction propagation widened three immutable snapshot types
without changing any value they already carried: normalization, significance,
transition creation, probability values, episode lifecycle and persistence
identity are all unchanged.

`SituationOccurrence` also carries `body`: the pair (`systemAddress`, `bodyId`)
taken from the `ContextSnapshot` the occurrence was accepted with, or `null`
when the graph had established no body at that moment. Like provenance it is
carried, never derived — a body is never inferred from a name — and it changes
nothing the graph calculates or stores. It exists so that a repeated event can
be counted against one body rather than against a whole system visit; a body id
alone would merge equal ids in different systems.

`SituationNextEventPrediction` carries the full prediction semantics the domain
establishes: probability, basis, global probability, the all-time observation
count, the count inside the context bucket, the cursor-level context support,
the context key and the decayed weight. No confidence field and no qualitative
support band exist. Only `probability` and a domain name for the predicted type
reach the model.

## kairon-llm-decision-v1 — the production model input

`kairon.observer.decision` holds the production contract: the request record,
the event catalogue and mechanisms, four projections (events, changes, context,
trajectory), a deterministic Jackson serializer with an explicit property order,
and a one-rung compaction ladder. There is no evidence mapping: the response
cites nothing, so nothing needs translating back.
`ObserverTurnCoordinator` builds exactly one request per turn — one
serialization pass, no second document — and sends it through
`DecisionPromptFactory`.

There is no earlier path, no fallback and no runtime version selector.
`DecisionRequestArchitectureGuardTest` is the permanent guard, and it reads
serialized requests rather than source text: it builds eight production
fixtures covering every mechanism that ever contributed a removed field,
collects every property name in each serialized request, and fails if any of
`schemaVersion`, `turn`, `turnSequence`, `triggerCount`, `busSequence`,
`firstTriggerBusSequence`, `finalTriggerBusSequence`, `finalTriggerTimestamp`,
`sourceRole`, `rawEventType`, `normalizedEventType`, `graphContext`, `fid`,
`cursor`, `occurrenceId`, `episodeSequence`, `graphId`, `basis`, `contextKey`,
`observedTransitionCount`, `contextObservedTransitionCount`,
`omittedOccurrenceCount`, `totalOccurrenceCount`, `activeEventCounts`,
`currentEventType` or `matchesFinalTrigger` appears. It also fails if a request
contains the account identifier, the string `busSequence`, a contract version, a
normalized event spelling whose domain name differs from it, an occurrence-id
prefix, a `null`, an empty array or an empty object, or if local ids are not
`1..n` in order. A separate assertion fails if the prompt names any Kairon
internal.

`DecisionEventCatalogCoverageTest` asserts catalogue coverage in both
directions: every one of the 112 model-eligible types has a rule, and the
catalogue covers nothing outside the selection profile. A catalogued event
therefore cannot reach the generic fallback.

- Every current event carries the literal description its own record supplies
  (`LlmPresentableJournalEvent.modelFacingDescription()`), serialized as
  `events[*].event` in place of the internal kind. `DecisionRequestArchitectureGuardTest`
  fails a request whose event carries a `kind`, a blank description or an
  internal spelling; `ModelFacingDescriptionContractTest` checks all 112
  model-eligible types for a single short neutral sentence, and
  `ModelFacingEventDescriptionTest` checks the serialized request, the property
  order, an explicit multi-record batch and the fail-closed refusal.
- The event projection drops what a decision cannot use: the subject the kind
  already fixes, a Commander actor that is always the Commander, a FINAL stage
  and a true completion on an atomic action, `negation: false`, an identity
  duplicating the object id, internal identifiers, surface coordinates, and
  prose summaries. An unnamed quantity is **dropped rather than sent**; every
  quantity that is sent is named for what it measures.
- Stage and completion survive where they are information: `stage` START or
  PROGRESS and `complete: false` are always sent, and `stage: FINAL` with
  `complete: true` is sent for the four genuinely multi-step mechanisms.
- Change selection sends a change only when it adds novelty the events do not
  already carry, or when a hidden observation touched a subject one of this
  turn's mechanisms needs. **Exact state changes that survive that selection
  are not in the compaction ladder and cannot be dropped at any budget.**
- Context selection sends only the subjects the turn's mechanisms asked for,
  minus anything the events or changes already state. Subject separation
  survives; the commander group carries presence and nothing else.
- The behavior graph reaches provider input only as domain content:
  `trajectory.recent`, `trajectory.likelyNext` and `occurrenceOnBody` on the
  event. `DecisionTrajectoryDescriptions` maps every declared
  `NormalizedEventType` to what that event says, and a test parses a record of
  each class the type can come from and requires the sentence to be the class's
  own `modelFacingDescription()`; an unmapped type (`UNKNOWN_*`, built from a
  journal wire name) is dropped rather than passed through. A prediction reads
  the same past-tense sentence as a memory of the same event — `likelyNext` and
  its probability are what say it has not happened, and the prompt says so
  outright, so there is no second vocabulary in a forward tense to keep in
  step. `DecisionTrajectoryProjector` reads the situation captured with the
  final trigger and recomputes nothing — order and probabilities are the
  calculation's own. It sends no trajectory at all when every projected event in
  the batch is one of the closed `TRAJECTORY_INDEPENDENT_KINDS` —
  `MESSAGE_RECEIVED` and `FRIEND_STATUS` — whose meaning owes nothing to where
  the ship has been. That rule reads projected `kind` values only, never the
  message text, sender, channel or friend name, and a batch containing any other
  kind keeps its trajectory in full; a test pins the set against the catalogue.
  It is not deduplication: repeated identical events remain separate events with
  separate local ids. `DecisionOccurrenceScope` decides cursor ownership by
  re-minting the occurrence id the graph would give this observation, because
  `APPLIED` alone is also satisfied by an owner switch, an episode switch or a
  bare revision bump. Graph calculation, normalization, weights, probabilities,
  persistence, the graph UI and graph diagnostics are unchanged.
- `occurrenceOnBody` counts that event type at that exact body inside the
  current system episode; the graph's all-time counters are not sent. The scope
  key is the pair (`systemAddress`, `bodyId`) carried on
  `SituationOccurrence.body` from the occurrence's own `ContextSnapshot` — the
  one piece of projection metadata this required. Nothing is inferred from a
  body name, and the graph store schema is unchanged. The field is absent when
  the trigger owns no occurrence or the graph established no body for it.
- `context.body` and body-subject `changes` are sent only when canonical state
  answers for the body the turn's events are about (`DecisionBodyScope`). A
  remote `Scan` reads a body without going to it — canonical state is right not
  to select it — so the body Kairon knows is still the arrival star, and its
  class, distance and survey flags reached the document under `body` beside an
  event about a planet. Nothing revealed the mismatch: the star is named after
  its system, so its name was suppressed by the event's own `system` field. The
  facts of a body no event names are dropped from both sections; a batch naming
  two bodies drops them too, since no single canonical body is both. A turn that
  names no body — a sample — still describes where the ship is.
  Canonical selection, the body registry and the graph are unchanged.
- The same scope now also answers "does anything here ask about a body at all".
  `DecisionBodyScope` returns false when no mechanism in the turn requests
  `BODY_IDENTITY` or `BODY_DETAIL`, so a body **change** follows the same rule
  the context already followed. The two halves used to disagree for a change one
  of the turn's own events caused: `SYSTEM_UNDISCOVERED_CONFIRMED` asks for the
  system, so no `context.body` was built, while the same reading's
  `previouslyMapped`, `previouslyFootfalled` and `distanceFromArrivalLs` still
  arrived as `changes` under the subject `body`. Decided on mechanisms only —
  never on a kind — so a landing, a scan, an approach or a sample is untouched
  and is still settled by the identity comparison.
- A **codex entry** is the one kind that asks for no body at all. It is read by
  `DecisionMechanism.CODEX`, whose only context need is `SYSTEM`, so neither
  `context.body` nor a body-subject change reaches a turn a codex entry is alone
  in. The entry's `BodyID` is not usable as an identity and is not used as one:
  the measured journal files a Sudarsky-class gas giant and a T Tauri star under
  `BodyID: 0` of systems whose body 0 the adjacent `Scan` reports as a K star and
  a B star. Under the previous mechanism the arrival star's `starType`,
  `previouslyDiscovered` and `distanceFromArrivalLs` were attached to the T Tauri
  entry's turn with nothing in the document to say they were two objects. Nothing
  is repaired or guessed — the contradictory field is simply not read as
  identity, and a turn that also carries an event which *does* establish a body
  (a scan, a sample) still gets the body from that event's mechanism.
- `DecisionContextSelector` suppresses a body fact an event already states
  through `ProjectedEvent.states` — field and value, the same answer
  `DecisionChangeSelector` uses. Its own name/value check cannot see a field an
  event states under exactly the canonical spelling with a boolean value, so a
  turn that reported `previouslyDiscovered: false` had it repeated back as
  `context.body`. The arrival milestone now asks for no body at all, so this
  guard is what protects any future kind in the same position.
- It is also absent on a **system-scoped** kind. `SYSTEM_JUMP` and
  `SYSTEM_SURVEY_COMPLETED` name a system and no body, and the body on their
  occurrence is only where the ship happened to be — the arrival star an
  `FSDJump` selects, which is still selected when a survey completes. Both are
  declared `uncounted()`, on the same rule flag that already omits a constant
  count. The graph still records the body; only the presentation drops it.
- `broadBodyType` always describes the selected body. Only records that select
  a body report `BodyType` — `FSDJump` sends `Star`, `SupercruiseExit` sends
  `Planet` — and `ApproachBody` and `Touchdown` send none, so holding the field
  until something overwrote it left the arrival star's type standing beside a
  planet's class and signal counts. `CurrentGameStateProjector.selectBody` now
  compares the body identity it had against the one it is setting — system
  address, body id and body name — and on a real change replaces the type with
  whatever was established for the new body, or nothing. A record that does
  report a type establishes it for the body it named, kept per body in the
  registry, so returning to that body recovers it; re-selecting the same body
  without a type keeps what is known. The write deliberately does not set the
  registry write marker: that marker says the published body facts came from
  the record rather than from storage, and a reported type did. `ApproachBody`
  and `Touchdown` therefore leave `body.type` absent until something says
  otherwise — absent, never another body's.
- `BIOLOGICAL_SAMPLE` carries no `occurrenceOnBody` at any stage. The graph
  counts its three stages as three structural types (`SCAN_ORGANIC_LOG`,
  `SCAN_ORGANIC_SAMPLE`, `SCAN_ORGANIC_ANALYSE`) while the model-facing kind is
  shared, so the count would be true of one stage and read as true of the kind;
  `stage` and `complete` state the transition instead, and nothing replaces the
  number. The suppression is declared per kind
  (`DecisionEventRule.stageSpecificOccurrences`, pinned by a test to this one
  kind) and applied in `DecisionEventProjector.appendOccurrenceCount`. The
  body-scoped calculation, the graph's per-type occurrences and counts, its
  transitions, probabilities and predictions, and the trajectory names
  `BIOLOGICAL_SAMPLE_STARTED`/`_CONTINUED`/`_COMPLETED` are all unchanged.
- The compaction ladder has one rung: the selected context, which sets
  `contextIncomplete` when dropped. Events, changes and the trajectory are
  mandatory; the trajectory is six items at most, so it cannot be why a turn
  overflows.
- The character budget is 16 000 Java String characters (`String.length()`),
  hardcoded in `DecisionTurnPolicy.production()`. It is not code points, not
  UTF-8 bytes and not tokens. The number is carried over unchanged from the
  measured v2 budget matrix; the decision contract is far smaller than the
  document that was measured, so the same number is now a wider margin.
- When the mandatory content alone exceeds the budget the compactor returns a
  typed `DoesNotFit` and the turn ends as `CONTEXT_TOO_LARGE`: the provider is
  not called, no comment and no synthetic silence is produced, speech is not
  invoked, previous-comment memory is untouched, the GUI receives a typed
  diagnostic through the existing observer status path, and the batch is
  consumed exactly once.
- The turn trace is `kairon-turn-trace-v6`. It records the exact request that
  was sent, the local-id-to-bus-sequence mapping, the typed turn outcome, and
  whether the provider, comment delivery and speech were actually invoked.
  `situationTurn` and `modelInput` are null exactly when no request was made.
  The version moved from `v5` when event ids stopped being sent, and no field
  is named `evidence` any more. `validatedDecision` lost both the ids the model
  returned and the bus sequences they resolved to, so it now describes the
  answer and only the answer. `localEvidence` is gone with nothing in its place:
  it mapped event position `1..n` onto a trigger bus sequence, and
  `triggerBusSequences` is that same list in that same order — the `v5` record
  even asserted the two were equal. "Which observation was the third event?" is
  `triggerBusSequences[2]`.

The `kairon.observer.context` package no longer exists. `TriggerRelation`,
`LlmDecisionContext`, `LlmDecisionContextFactory`, `LlmDecisionContextCompactor`,
`JacksonLlmDecisionContextSerializer`, `V2Names`, `LlmSituationV2Policy`,
`LlmSituationV2Inputs`, `DecisionContextPromptFactory` and
`ObserverTurnEvidenceScope` were deleted. `DeliveredModelComment` moved to
`kairon.observer.decision` unchanged; the policy and turn inputs were replaced
by `DecisionTurnPolicy` and `DecisionTurnInputs`, and the policy lost the three
graph-related bounds because the sections they bounded no longer reach the
model.

`target/observer-response-contract-examples.jsonl` is still written by
`ObserverResponseValidatorTest`, now with the local-id contract.

## Next planned work

1. Exercise the new `Status.json` baseline and FSS/SAA/landing-gear deltas in
   a controlled live session, including source replacement, rapid focus
   changes, shutdown, and same-timestamp journal/status ordering.
2. Decide whether later Status-derived hardpoint, cargo-scoop, lights, or other
   state transitions add useful behavior structure before admitting them.
   Captured status history remains required for their future replay.
3. Run a controlled paced replay over the fully verbalized profiles; inspect
   exact input before scoring silence, usefulness, repetition, evidence
   accuracy, or model grounding.
4. Compare configured models with identical source, prompt, and temperature
   settings after the revised baseline is recorded, then run live evaluation.
5. Review any presentation gaps exposed by traced model behavior against
   authoritative event documentation before changing prompts or models.
6. Google Cloud TTS follow-up: reconcile the approved ADC authentication
   direction with the current API-key implementation and repeat a controlled
   audible smoke evaluation.
7. Validate the 16 000-character budget against a real captured journal. The
   mechanism exists (`BehaviorGraphManualReplayTest`); the current evidence is
   16 hand-assembled cases, only 4 of them driven through the production
   pipeline.
8. Evaluate model behavior on the v2 context. Per ADR-0010, any surprising
   output is traced to the exact supplied input before the model is blamed;
   the trace now records that input verbatim.
9. Decide whether occurrence provenance should be persisted. It is in-process
   only, so an occurrence restored from the graph store reports absence. The
   minimal migration — drop `@JsonIgnore` on `EventOccurrence.source`, keep the
   component nullable so existing files still load, add a schema-version gate —
   is recorded in design §26.1 and remains deliberately unperformed.
10. Decide whether a token measurement against the deployed model is worth
   adding. The 16 000-character budget bounds the document, not the request;
   nothing in the repository reads a provider context window.

## Explicitly deferred

- additional external telemetry sources beyond Journal and live Status, and a
  world projection;
- `DomainEventBus`, task system, permissions, memories, and long-term memory;
- game control, action authorization, execution, and result verification;
- microphone input, speech recognition, and barge-in;
- provider discovery, failover, routing, or simultaneous model calls;
- durable broker, database, observation archive, or redelivery;
- semantic event scores, deterministic commentary rules, or generic/unsourced
  narrative summaries;
- schema-repair calls, general retry policy, and request-size hardening;
- isolated subscriber mailboxes, bounded bus backpressure, and distributed
  messaging;
- streaming LLM metrics such as true time-to-first-token;
- behavior-to-intent semantics, LLM-trained graph weights, automatic
  prediction execution, macros, keyboard input, or game control.
