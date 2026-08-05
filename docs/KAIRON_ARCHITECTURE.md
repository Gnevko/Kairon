# Kairon Architecture

## Status and purpose

This is the concise normative architecture for Kairon. It defines stable
project-wide boundaries, not a class inventory, implementation diary, prompt
copy, configuration schema, or test catalogue.

Before changing Kairon, read:

1. this document;
2. [CURRENT_STATE.md](CURRENT_STATE.md);
3. only the [ADRs](decisions/) relevant to the change.

Documents under [`archive/`](archive/) are historical and non-normative.
Repository code and machine-readable contracts remain authoritative for exact
runtime details.

## Product role

Kairon is an LLM-centred companion. Its first working product loop observes
Elite Dangerous telemetry and asks an LLM whether to remain `SILENT` or emit a
short `COMMENT`.

The LLM owns normal-event interpretation and comment-worthiness. Deterministic
code owns observation capture, ordering, technical context, bounded model
input, structural validation, output delivery, diagnostics, tracing, and
safety boundaries.

Deterministic code must not replace the LLM with:

- importance, rarity, value, danger, or emotion scores;
- event-name commentary rules;
- unsourced, generic, or narrative summaries inserted before the model;
- an attention arbiter or semantic rule engine.

## Runtime boundaries

The project-wide direction for external data is:

```text
external source
    -> source-specific parser and adapter
    -> ObservationBus
    -> ObservationProjectionCoordinator
    -> atomic CurrentGameState projection
    -> terminal behavior-graph apply and situation capture
    -> immutable ProjectedObservation
    -> downstream subscribers
```

The current journal observer continues from its subscriber through:

```text
selected NEW ProjectedObservation queue
    -> bounded ordered trigger batch
    -> state and behavior situation from the final NEW trigger
    -> at most three successfully delivered comments
    -> compact snapshot JSON and provider-independent prompt
    -> LlmClient
    -> validated SILENT or COMMENT
    -> configured output
    -> aggregate turn trace
```

When a source supports both live and replay, both modes must use the same
projection, batching, snapshot, prompt, model, validation, output, and trace
path.
The current Journal source satisfies that invariant. A live-only snapshot
source does not fabricate replay history that was never captured. A source
lifecycle signal may request a technical flush, but it is never a game event
or model evidence.

`REPLAY` is a test-only paced source mode, not an alternate semantic path.
The first valid record is immediate; later consecutive valid source
timestamps retain one-times spacing capped at ten seconds. Pacing is
source-owned, interruptible, and must not delay the bus, EDT, coordinator, or
live source. See [ADR-0009](decisions/ADR-0009-PACED-REPLAY.md).

The optional desktop monitor observes the same runtime without entering the
semantic or delivery path:

```text
ObservationBus -> DesktopUiSubscriber -> KaironGuiHub
ObserverTurnCoordinator -> read-only ObserverTurnListener -> KaironGuiHub
```

## ObservationBus

`ObservationBus` is an in-process typed transport for externally obtained data
and source lifecycle signals.

Sources publish observations without knowing any subscriber, coordinator, LLM,
projection, diagnostic sink, or output channel. Type matching and dispatch are
transport concerns. The bus must not interpret raw telemetry or decide what is
interesting.

An accepted publication is immutable. It carries stable source metadata and a
process-local bus sequence, but no subscriber processing state. Each subscriber
owns its reaction, queues, lifecycle, failures, and derived state.

A reaction is subscriber-owned code invoked for a matching immutable
observation. Handler failure is isolated from other subscribers and does not
change the shared observation.

See [ADR-0001](decisions/ADR-0001-OBSERVATION-BUS.md).

## External observations and domain events

External observations are facts obtained from outside Kairon plus their source
metadata. Source lifecycle signals describe the technical state of such a
source.

The following are not external observations:

- LLM decisions or generated comments;
- internal commands, tasks, or memory mutations;
- permission and authorization decisions;
- proposed or completed game actions;
- arbitrary application exceptions.

If internal domain events become necessary, they require a separate
`DomainEventBus` or equivalent boundary. That boundary does not yet exist and
must not be simulated by publishing internal state through `ObservationBus`.

See [ADR-0006](decisions/ADR-0006-EXTERNAL-AND-DOMAIN-EVENTS.md).

## Raw and typed telemetry

Exact source data is preserved. Parsing may validate encoding and structure and
extract technical metadata, but must not replace the authoritative raw value
with a summary.

Replacement snapshots such as Elite Dangerous `Status.json` follow the same
rule: every accepted value is immutable raw evidence with its own source
identity. A subscriber may derive deterministic state changes from consecutive
snapshots, but those changes do not mutate or replace either snapshot.

Paced replay therefore retains original `rawJson` and `sourceTime`;
`observedAt` records actual publication time. The semantic prompt may render
that observed time for replay without projecting or mutating a raw field.

Known telemetry may additionally have checked-in typed representations,
generated or mechanically derived from a pinned catalogue, for safe Java
subscription and dispatch. Typed transport identities are not Kairon's domain
model and do not confer semantic importance.

A selected concrete event class may also own a researched, deterministic
factual English presentation for the LLM. That presentation supplements raw
evidence; it never replaces or mutates it. An unresearched type must not enter
the active LLM profile through a generic raw or narrative fallback. See
[ADR-0010](decisions/ADR-0010-MODEL-FACING-EVENT-VERBALIZATION.md).

Unknown event discriminators and unknown fields must continue through the raw
path. Adding typed coverage must not make forward-compatible raw observations
unreadable.

See [ADR-0002](decisions/ADR-0002-RAW-AND-TYPED-TELEMETRY.md).

## Subscriber roles and technical context

Subscribers may select observations by declared Java payload type or source.
They may not mutate a shared publication for another subscriber.

The projection coordinator is the single sequential mutation boundary for
canonical state and behavior state. Downstream consumers receive only an
immutable `ProjectedObservation` captured after both projections have reached a
terminal result.

The LLM observer owns its `NEW` queue, batching state, model request,
validation, delivered-comment memory, and delivery bookkeeping. A
`CONTEXT_ONLY` observation still updates canonical state and the behavior graph,
but its raw presentation is not queued, retained as hidden history, or sent to
the model. Its effects become visible only through a later `NEW_ELIGIBLE`
snapshot. `DIAGNOSTIC_ONLY` observations likewise remain outside model input.

## Deterministic behavior graphs

`BehaviorGraph` is a subscriber-owned, deterministic projection of journal
observations and live Status snapshot changes. It learns event-transition
frequencies for one concrete ship without changing telemetry sources,
`ObservationBus`, or the LLM observer.
Each graph is isolated by commander FID and `ShipID`; a loadout change updates
context but does not change graph identity.

The projection preserves two distinct views. A `SystemEpisode` holds the exact
ordered occurrences and transitions from one visit to one star system.
`ShipBehaviorGraph` aggregates normalized event-type nodes and directed,
context-sensitive transition weights across that ship's episodes.
`GraphCursor` identifies the current concrete occurrence, not merely its event
type. System and ship boundaries start new rooted episodes and never create
cross-boundary edges.

Behavior-graph event classification controls only the granularity of this
derived projection. It does not assign narrative importance, infer player
intent, filter the LLM observer, or decide `SILENT`/`COMMENT`. The graph keeps
compact normalized attributes and context; exact raw journal telemetry remains
authoritative in the observation path together with accepted raw Status
snapshots.

`Status.json` is a live replacement-snapshot source. Its complete valid raw
values are published as immutable `StatusSnapshotObservation` values.
Subscriber-owned `StatusStateDeltaAdapter` state treats the first known value
of each tracked field as its baseline, then records only observed changes.
Frontier's Status contract
defines FSS and SAA as `GuiFocus` values 9 and 10 and deployed landing gear as
`Flags` bit 2. The current projection derives exactly six structural types:
FSS and SAA mode entered/exited plus landing gear deployed/retracted. These
are graph-only occurrences; they are not model evidence and do not change LLM
admission.

Every occurrence carries a persisted `episodeSequence` assigned by the
single-writer behavior subscriber. It is the total order inside an episode
across sources. Source-local positions remain identity evidence and must not
be compared as if a journal byte offset and Status snapshot position belonged
to one sequence domain.

All-time counts and incrementally decayed weights coexist. Predictions use an
explicit evaluation time and low-cardinality contextual counters backed by a
global prior. Stable source identity makes replay deterministic and
idempotent. Graph-derived notifications are internal events and must not be
published back through `ObservationBus`.

Journal-only replay has no historical Status snapshots and therefore produces
no status-derived occurrences. Exact reconstruction of those transitions
requires a future captured snapshot history; Kairon does not invent them.

See [ADR-0011](decisions/ADR-0011-BEHAVIOR-GRAPH.md).

## LLM semantics and validation

Kairon sends one compact snapshot turn through a provider-independent semantic
prompt. A turn contains the ordered factual presentations of the current
`NEW_ELIGIBLE` batch, canonical state and active-episode behavior situation
captured after the final trigger, and at most three successfully delivered
comments. Exact raw source data remains in observations, diagnostics, and GUI
details; it is not copied into the model context.

The LLM returns one aggregate `SILENT` or `COMMENT` decision, and nothing else:
the model is shown no identity for an event, so it cites nothing and a response
that names anything is invalid. A delivered comment is attributed by Kairon to
every trigger `busSequence` of the turn that produced it. Invalid output is
handled deterministically under the current validator policy.

Structural validation is not a semantic fact checker. Prompt instructions and
controlled evaluation remain necessary to detect unsupported qualitative
claims.

Deterministic novelty validation may reject a normalized exact repeat or a
strongly overlapping lexical near-repeat of a recent successfully delivered
comment. This is a conservative output-quality boundary, not an
event-importance decision or a general semantic classifier. Broader paraphrase
detection and semantic fact judgment remain model-owned.

Administrative bus, source, trace, pricing, credential, and subscriber fields
must not enter the semantic event payload unless required to interpret the
observed game data.

The immutable trigger presentation and timestamp are captured before batching.
The final serialized `LlmDecisionRequest` document is reused byte-for-byte in
the user message and turn trace. Document construction never performs a late
read of current state or graph services. Exactly one contract exists: there is
no earlier path, no fallback and no runtime version selector.

## Evidence-first model evaluation

Kairon does not classify surprising output as an LLM defect until the exact
traced input has been inspected. Evaluation must verify that the required
fact, field meaning, terminology, relationship, comparison baseline, and clear
instruction were actually supplied.

If the model was expected to know an unstated game/API contract, or if a
qualitative claim required a baseline absent from the window, the first defect
is in application context or presentation. Only behavior that remains wrong
with sufficient supplied evidence may be classified as a grounding or
instruction-following failure. See
[ADR-0010](decisions/ADR-0010-MODEL-FACING-EVENT-VERBALIZATION.md).

## LLM providers

`LM_STUDIO` and hosted `MISTRAL` share one `LlmClient` abstraction and one
`OpenAiCompatibleLlmClient` transport. Provider selection and explicit model
selection are configuration concerns, not observer semantics.

Provider-specific credentials and transport metadata must not enter prompts,
model event data, comments, or secret-bearing diagnostics. The current and
target credential mechanisms are distinguished in
[ADR-0003](decisions/ADR-0003-LLM-PROVIDERS.md) and
[CURRENT_STATE.md](CURRENT_STATE.md).

There is no automatic provider failover, discovery, scoring, load balancing,
or simultaneous model request path in the current architecture.

## LLM request statistics

The single active `LlmClient` is instrumented by a provider-neutral statistics
component. Accounting observes each physical model call without changing its
input, output, cancellation, or failure semantics.

Provider-reported token usage is preserved as complete, partial, unavailable,
or invalid; missing values are not guessed. Logged operational measurements
may include call outcome, token and cache use, end-to-end latency, running
averages, end-to-end output-token throughput, and an optional cost estimate
based on explicit configured rates.

Statistics are process-local operational data. They do not use
`ObservationBus`, enter the semantic model payload, influence
`SILENT`/`COMMENT`, or become part of the aggregate turn trace. Prompts, model
responses, credentials, authorization metadata, and raw provider exception
text must not be retained by this component.

See
[ADR-0007](decisions/ADR-0007-LLM-REQUEST-STATISTICS.md).

## Desktop GUI

Desktop presentation enters one explicitly wired `KaironGuiHub`. Sources,
model clients, output sinks, and observer state owners do not create or mutate
Swing controls.

All valid journal observations reach the desktop through an independent typed
`ObservationBus` subscriber. Model decisions and terminal delivery results use
a separate internal read-only listener because generated output is not an
external observation. Neither path feeds information back into model
semantics.

Raw occurrence and observer processing are distinct GUI facts. A newly
displayed observation defaults to `OBSERVER EFFECT = OCCURRED_ONLY`, which
means only that the external observation reached the presentation path. It
does not mean `CONTEXT`, `NEW`, diagnostic-only, interesting, or
comment-worthy.

Any later observer effect is owned and emitted by the observer coordinator,
then projected through the read-only `ObserverTurnListener`. Context retention,
queueing, turn binding, processing, failure, and discard are observer-local
lifecycle facts. The GUI may correlate and display the latest reported effect
by observation identity, bus sequence, and optional turn binding, but it must
not recompute `LlmJournalEventSelection`, infer a role from raw event names, or
invent missing transitions.

Observer effects are never written into the shared `PublishedObservation`.
They are not external observations, do not use `ObservationBus`, and cannot
alter another subscriber's view of the same publication. Closing or dropping
a GUI update cannot change observer lifecycle state.

The Swing implementation owns EDT marshaling, bounded presentation buffering,
retained rows, HUD control construction, and window lifecycle. GUI callbacks
must be handoff-only; source, model, speech, and shutdown work must not block
the EDT. Displaying a comment is not output delivery and cannot update heard
comment history. Replay rows use `observedAt` as their primary displayed time;
live and bootstrap rows retain source-time-first presentation.

See [ADR-0008](decisions/ADR-0008-DESKTOP-GUI-HUB.md).

## Output, speech, and heard history

Output begins only after a validated `COMMENT`. `SILENT` produces no comment
delivery, speech synthesis, or playback.

Console and speech are output channels. Speech is not an observation and never
uses `ObservationBus`. Successful speech delivery means audio playback
completed, not merely that synthesis returned bytes.

Only successfully delivered output enters the bounded previous-comment history
used by later model turns. Output failure must not cause source redelivery or a
second semantic decision for the same observations.

See [ADR-0005](decisions/ADR-0005-SPEECH-OUTPUT.md).

## Future actions and safety

Kairon currently observes and comments; it does not control the game.

Any future game action requires a separate deterministic boundary for:

- explicit authorization and permissions;
- command construction and execution;
- result observation and verification;
- failure, timeout, and cancellation handling.

Model output alone must never be treated as action authorization or proof that
an action succeeded.

## Sources of truth

Exact contracts should be changed at their machine-readable or executable
source first. Documentation links to those sources instead of copying them.

| Concern | Current source of truth |
|---|---|
| Runtime configuration | [`config/kairon.example.json`](../config/kairon.example.json) and the strict loader/records in [`KaironConfiguration.java`](../src/main/java/kairon/config/KaironConfiguration.java); there is no standalone JSON Schema |
| Telemetry definitions | [`JournalEventCatalog.java`](../src/main/java/kairon/observation/journal/JournalEventCatalog.java), the checked-in [`event/`](../src/main/java/kairon/observation/journal/event/) records, [`observation/status/`](../src/main/java/kairon/observation/status/), and raw-contract tests |
| Model-facing event presentation | [`LlmPresentableJournalEvent.java`](../src/main/java/kairon/observation/journal/LlmPresentableJournalEvent.java), participating event records, [`JournalEventLlmPresentationTest.java`](../src/test/java/kairon/observation/journal/JournalEventLlmPresentationTest.java), and [ADR-0010](decisions/ADR-0010-MODEL-FACING-EVENT-VERBALIZATION.md) |
| Model-independent semantics | [`kairon/semantics/`](../src/main/java/kairon/semantics/), the per-field delta in [`CurrentGameStateProjector.java`](../src/main/java/kairon/state/CurrentGameStateProjector.java), and [ADR-0012](decisions/ADR-0012-MODEL-INDEPENDENT-SEMANTIC-LAYER.md) |
| LLM model input | [`kairon/observer/decision/`](../src/main/java/kairon/observer/decision/) — [`LlmDecisionRequest.java`](../src/main/java/kairon/observer/decision/LlmDecisionRequest.java) (`kairon-llm-decision-v1`), [`DecisionEventCatalog.java`](../src/main/java/kairon/observer/decision/DecisionEventCatalog.java), the three projections, [`JacksonDecisionRequestSerializer.java`](../src/main/java/kairon/observer/decision/JacksonDecisionRequestSerializer.java), [`DecisionTurnPolicy.java`](../src/main/java/kairon/observer/decision/DecisionTurnPolicy.java), their tests, and [`kairon-llm-decision-interface.md`](design/kairon-llm-decision-interface.md) |
| LLM prompt | `DecisionPromptFactory.SYSTEM_PROMPT` in [`DecisionPromptFactory.java`](../src/main/java/kairon/llm/DecisionPromptFactory.java); there is no separate prompt resource |
| LLM response format | [`ObserverResponseValidator.java`](../src/main/java/kairon/llm/ObserverResponseValidator.java), [`CommentNoveltyGuard.java`](../src/main/java/kairon/llm/CommentNoveltyGuard.java), and their tests; the response names nothing, so there is no evidence mapping and no standalone response JSON Schema |
| Turn trace | [`JsonLinesTurnTraceWriter.java`](../src/main/java/kairon/trace/JsonLinesTurnTraceWriter.java) (`kairon-turn-trace-v6`) and its tests |
| Replay pacing and model-facing time | [`JournalReplaySource.java`](../src/main/java/kairon/observation/journal/JournalReplaySource.java), immutable projected triggers, their tests, and [ADR-0009](decisions/ADR-0009-PACED-REPLAY.md) |
| LLM request statistics | [`LlmRequestStatistics.java`](../src/main/java/kairon/llm/LlmRequestStatistics.java), [`LlmClient.LlmTokenUsage`](../src/main/java/kairon/llm/LlmClient.java), and [`LlmRequestStatisticsTest.java`](../src/test/java/kairon/llm/LlmRequestStatisticsTest.java) |
| Bus contract | [`ObservationBus.java`](../src/main/java/kairon/observation/bus/ObservationBus.java), [`InProcessObservationBus.java`](../src/main/java/kairon/observation/bus/InProcessObservationBus.java), and [`InProcessObservationBusTest.java`](../src/test/java/kairon/observation/bus/InProcessObservationBusTest.java) |
| Desktop GUI and observer effects | [`KaironGuiHub.java`](../src/main/java/kairon/ui/KaironGuiHub.java), [`ObserverTurnListener.java`](../src/main/java/kairon/observer/ObserverTurnListener.java), [`SwingKaironGuiHub.java`](../src/main/java/kairon/ui/swing/SwingKaironGuiHub.java), and GUI tests |
| Per-ship behavior graph | [`behavior/`](../src/main/java/kairon/behavior/), [`BehaviorGraphService.java`](../src/main/java/kairon/behavior/graph/BehaviorGraphService.java), [`StatusStateDeltaAdapter.java`](../src/main/java/kairon/behavior/status/StatusStateDeltaAdapter.java), [`behavior` tests](../src/test/java/kairon/behavior/), and [ADR-0011](decisions/ADR-0011-BEHAVIOR-GRAPH.md) |
| Implementation status | [`CURRENT_STATE.md`](CURRENT_STATE.md) |
| Architectural decisions | [`docs/decisions/`](decisions/) |
| Historical design | [`JOURNAL_OBSERVER_MVP_PROFILE.md`](archive/JOURNAL_OBSERVER_MVP_PROFILE.md) and [`JOURNAL_OBSERVER_TECHNICAL_DESIGN.md`](archive/JOURNAL_OBSERVER_TECHNICAL_DESIGN.md) under `archive/` |

## Change discipline

Architecture changes require an ADR update or a new ADR. Implementation-status
changes require `CURRENT_STATE.md` to be updated from repository evidence.

Do not copy complete configuration examples, prompt bodies, telemetry
catalogues, response schemas, or test catalogues into this document. Link to
their authoritative sources.

When a proposed ADR differs from current runtime behavior, the ADR must name
the implementation gap and `CURRENT_STATE.md` must continue to describe the
actual behavior until code and tests change.
