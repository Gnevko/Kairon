# ADR-0011: Per-ship deterministic behavior graphs

## Status

Accepted. Current implementation status is tracked in
[`CURRENT_STATE.md`](../CURRENT_STATE.md).

## Context

Kairon needs both an exact account of a commander's current system visit and
learned frequencies for repeated externally observed transitions. An event type
cannot identify the current position, while edge counts cannot preserve the
route that produced them. This projection must be deterministic and must not
ask the LLM to count transitions, infer intent, or control the game.

## Decision

`BehaviorGraph` is an independent subscriber-owned projection of typed journal
observations and live `Status.json` snapshots delivered through the existing
`ObservationBus`. Sources do not know the graph subsystem. Graph-derived
notifications remain internal and are not republished as external
observations.

Each graph is identified by `GraphId = CommanderFID + ShipID`. Different
physical ships never share nodes, edges, episodes, or weights; changing
`Loadout` only updates compact context.

The projection keeps two linked representations:

- `SystemEpisode` stores the exact ordered `EventOccurrence` path for one
  visit to one star system, including occurrence-level transitions;
- `ShipBehaviorGraph` aggregates normalized event-type nodes and directed
  transition edges across that ship's episodes.

`EventTypeNode.rawOccurrenceCount` is a cached historical diagnostic across
all episodes of its `GraphId`; it is not active-episode state and prediction
does not depend on it. Concrete instances remain owned by episodes. The
desktop graph visualization overlays active-episode counts on the global
topology: it obtains each displayed count from the active
`SystemEpisode.occurrencesByEventType` index, uses zero when no active episode
or occurrence exists, and continues to use global decayed edge weights.

`GraphCursor` points to a concrete occurrence in the active episode.
`FSDJump`, location restoration, and a ship switch create one `SYSTEM_ENTRY`
root under distinct entry reasons. Episode boundaries never create an
ordinary transition between systems or ships.

`NormalizedEventType` is an extensible canonical value rather than a closed
enum. A future explicit commander-command source can therefore create normal
`EventOccurrence` values and transitions alongside journal-derived
occurrences without changing structural node identity. Command ingestion is
outside this decision's current implementation scope.

An isolated `EventSignificancePolicy` classifies typed events for this
projection as boundary, significant, context, or noise. This classification
controls graph granularity only; it does not assign narrative importance or
decide whether the LLM should comment. Exact journal data remains authoritative
outside the graph, while occurrences retain only normalized attributes,
stable source identity, source position, and compact context.

Structural occurrences represent commander-facing route milestones rather
than every technically distinct journal record. In particular,
`FSSBodySignals` updates per-body context without producing one node per
reported body. `SAAScanComplete` and the following `SAASignalsFound` are both
structural and are never merged: the first is the completion of a deliberate
multi-step action, the second is what the scanner then reported (see the
amendment below). `LeaveBody` is structural because crossing
above orbital-cruise altitude is a distinct departure step in a planetary
route. These graph roles are independent of `LlmJournalEventSelection`; the
same typed records keep their separately reviewed model-facing roles and
event-owned English presentations.

Operational graph milestones also include docking requests and grants,
function-specific limpet launches, interdiction, attack alerts, material
collection, and fuel scooping. This mapping follows the
[Frontier Player Journal Manual v37](https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf).
`LaunchDrone.Type` is open-ended source data: documented values map to
function-specific limpet nodes, the observed `Recon` value maps to
`RECON_LIMPET_LAUNCHED`, and any future value falls back to
`LIMPET_LAUNCHED` while retaining the exact attribute.

The Journal does not provide operation IDs or documented end records for
`MaterialCollected`, `UnderAttack`, or `FuelScoop`. A separate deterministic
projection policy therefore keeps the first record of an uninterrupted
same-key run and omits only immediately continuing records from graph
structure. `UnderAttack` includes `Target` in that key. Any different
significant occurrence or episode boundary ends the run. This rule uses no
timer, does not synthesize completion facts, and does not discard raw source
records.

`Status.json` is handled as a separate replacement-snapshot source rather than
as a journal event. `PollingStatusWatcher` publishes each complete valid raw
snapshot as an immutable `StatusSnapshotObservation`. The behavior subscriber
owns `StatusStateDeltaAdapter`; the first known value of each tracked field
establishes that field's baseline without creating an occurrence. Unchanged
input emits no transition, and malformed input never reaches the adapter.

Later observed changes can create exactly these graph-only occurrences:

- `FSS_MODE_ENTERED` and `FSS_MODE_EXITED`;
- `SAA_MODE_ENTERED` and `SAA_MODE_EXITED`;
- `LANDING_GEAR_DEPLOYED` and `LANDING_GEAR_RETRACTED`.

The mapping follows the
[Frontier Player Journal Manual v37 Status contract](https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf):
FSS and SAA are `GuiFocus` values 9 and 10, while deployed landing gear is
`Flags` bit 2 (`0x00000004`). `GuiFocus` describes the selected scanner
interface; it does not prove that a scan completed. These derived occurrences
have no LLM admission or presentation role and do not enter model turns or
turn traces.

Every occurrence stores a subscriber-owned `episodeSequence`, assigned on the
single-writer behavior executor. It is the persisted total order for one
episode across journal and Status input. Source-specific positions continue to
identify their own observations but are not cross-source ordering values.

Aggregate edges retain an all-time `rawCount` and an incrementally maintained
exponentially decayed value. Prediction uses an explicitly supplied evaluation
time. Low-cardinality canonical context keys provide contextual counters, and
a configurable global prior prevents one contextual observation from erasing
other learned branches.

Identifiers derive from stable source observation identity and episode
boundaries. Reprocessing the same source observation is idempotent; random
UUIDs and wall-clock time are not identity inputs.

The prototype store uses Jackson JSON files per commander and ship, with
separate aggregate and episode documents, schema versions, stable ordering,
and atomic replacement where supported. In-memory storage supports tests.
Diagnostic JSON, chronological episode JSON, and Graphviz DOT exports do not
change the stored model.

## Consequences

- Live Journal and Status observations update the same projection without
  coupling either source to the graph.
- Journal replay updates the same journal projection path, but a Journal file
  contains no historical Status snapshots. It therefore creates no
  status-derived occurrences; exact reconstruction requires future captured
  snapshot history.
- Exact paths remain queryable independently of aggregate probabilities, and
  old behavior loses prediction influence without losing historical counts.
- This subsystem predicts event transitions, not commander intent; semantic
  intent recognition may be layered above it later.
- The current v1 store does not migrate aggregates after a graph-admission
  policy change. A corrected historical graph must therefore be rebuilt by
  replaying its journal source into an empty store.

## Amendment — a completed surface survey is structural

The original decision kept `SAAScanComplete` as technical context on the ground
that scanner output arrives in bursts and should not read as repeated commander
choices. That reasoning holds for the bursts and not for this event.

Mapping a body is a deliberate multi-step action: fly to it, expend probes,
finish. `SAAScanComplete` reports that the action finished, and it is exactly
what a later event should be read against — an approach to a body the Commander
has just surveyed is a different situation from a first arrival. Under the old
policy the survey left no trace at all in the run of events, and the approach
that followed it arrived with no memory of it.

`SAAScanComplete` is therefore `SIGNIFICANT`, normalizes to the already-declared
`NormalizedEventType.SAA_SCAN_COMPLETE`, and keeps `SystemAddress`, `BodyID`,
`BodyName`, `ProbesUsed` and `EfficiencyTarget` as normalized attributes. It
participates in nodes, edges, counters, decay, context keys and predictions by
the ordinary path; no formula changed and no special case was added.

`SAASignalsFound` is unchanged and remains its own occurrence. The two are two
different facts — the action and its reported result — and they commonly share
one journal timestamp, so their order comes from source/FIFO arrival and the
contiguous `episodeSequence`, never from the clock. `Scan` stays contextual.

The topology this produces is

```text
… → SAA_SCAN_COMPLETE → SAA_SIGNALS_FOUND → …
```

where it was previously `… → SAA_SIGNALS_FOUND → …`.

**Persistence.** No schema change. The store has no version field and no
event-type allowlist; `NormalizedEventType` serializes as a validated
upper-snake string, so a new type needs nothing added to read or write it.
Existing graph files remain readable and are not migrated, reset or deleted —
which is the standing rule for an admission-policy change in this ADR. Their
topology is only *partially* comparable with new graphs: they contain no
`SAA_SCAN_COMPLETE` occurrences, so an edge that now runs through the survey
runs around it in the older data. Correcting a historical graph still means
replaying its journal into an empty store.

## Rejected alternatives

- One graph per commander, ship type, loadout, or star system.
- A graph made only of aggregate counts or only of episode timelines.
- Structural node identities containing source-specific payload values.
- Cross-episode or cross-ship transitions.
- Scanning all history for decay or using the LLM to build weights.
- Merging `SAAScanComplete` into `SAASignalsFound` because they share a
  timestamp. They answer different questions, and one occurrence could only
  answer one of them.
- Migrating existing graphs to insert the newly structural occurrence. The
  occurrence never existed in those runs; inventing it would fabricate history.
- An external graph database or publishing derived graph state through
  `ObservationBus`.

## Relevant implementation references

- [`behavior/`](../../src/main/java/kairon/behavior/)
- [`BehaviorGraphService.java`](../../src/main/java/kairon/behavior/graph/BehaviorGraphService.java)
- [`BehaviorGraphSubscriber.java`](../../src/main/java/kairon/behavior/bus/BehaviorGraphSubscriber.java)
- [`StatusStateDeltaAdapter.java`](../../src/main/java/kairon/behavior/status/StatusStateDeltaAdapter.java)
- [`StatusSnapshotObservation.java`](../../src/main/java/kairon/observation/status/StatusSnapshotObservation.java)
- [`PollingStatusWatcher.java`](../../src/main/java/kairon/observation/status/PollingStatusWatcher.java)
- [`BehaviorGraphStore.java`](../../src/main/java/kairon/behavior/persistence/BehaviorGraphStore.java)
- [`BehaviorGraphExporter.java`](../../src/main/java/kairon/behavior/export/BehaviorGraphExporter.java)
- [`KaironConfiguration.java`](../../src/main/java/kairon/config/KaironConfiguration.java)
