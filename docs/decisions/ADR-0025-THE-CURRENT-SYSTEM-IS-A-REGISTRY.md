# ADR-0025: The current system is a registry, not a body cache

## Status

Accepted. Steps one to six of the staging in *Consequences* are implemented. Step
five also required a piece of what had been deferred: the behaviour graph's body
context is supplied from the registry, translated by the projection coordinator,
so that removing those fields from canonical state did not silently coarsen the
graph's transition context keys.

`target/model-facing-baseline.json` grew when the `Scan` fixture was added, did
not move by a byte while the registry and its view were built, moved at step
four — 85 159 to 86 499 bytes, every added line a `biology` group — did not move
by a byte at step five, and moved at step six by 41 leaves, every one an
addition and every one in the two fixtures that contain a discovery scan.
Per-step status is tracked in [`CURRENT_STATE.md`](../CURRENT_STATE.md).

## Context

What Kairon knows about celestial bodies lives in one private field of
`CurrentGameStateProjector`:

```java
private final Map<BodyIdentity, BodyContext> bodies = new TreeMap<>();
```

It is not a model of a star system. It is a cache, and it has the shape of one:

- **cross-system** — keyed by `(systemAddress, bodyId)` and cleared only when
  the Commander FID changes, so it accumulates every body of every system
  visited in the run;
- **flat** — one `BodyContext` for anything a scanner reported, with
  `starType` and `planetClass` side by side and one of them always empty;
- **structureless** — nothing records what orbits what;
- **private** — only the *selected* body's facts reach the snapshot, plus one
  exception for records that report a body the Commander has already left.

It was built so that returning to a body would not lose what was learned about
it, and at that it works. Three things now wanted are not expressible in it at
all.

**What is in this system.** How many bodies there are, how many are scanned,
what is left. The journal states the total — `FSSDiscoveryScan.BodyCount` and
`FSSAllBodiesFound.Count` — and it has nowhere to live.

**Where a body sits.** A moon is a planet whose parent is a planet. Nothing
records parents, so the distinction does not exist.

**What has been collected on this planet, and what has not.**
`SAASignalsFound.Genuses` names the biological genera on a body;
`ScanOrganic.Analysed` completes the collection of one. The two facts never
meet, because the first survives only for the turn that reported it.
`BiologicalSamplingProcess` documents the consequence honestly — no sample
count, no required count, no readiness flag, "because no journal event
establishes any of them". That is true exactly as long as nothing keeps the
genus list.

### The journal gives the hierarchy

The design was going to need a way to tell a moon from a planet, and the only
apparent source was the body name: `Sol A 2 a`. Parsing that is guessing, which
this project forbids on principle and which this ADR would have had to argue
for.

There is nothing to guess. Every `Scan` carries **`Parents`**: the chain of
`BodyType:BodyID` pairs from the immediate parent up to the root of the system,
with `Null` standing for a barycentre. The hierarchy is observed, not derived.

The rest of the vocabulary is closed and documented in the
[Frontier Player Journal Manual v37](https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf):
`BodyType` is one of `Null`, `Star`, `Planet`, `PlanetaryRing`, `StellarRing`,
`Station`, `AsteroidCluster` — which is also why the current `broadBodyType`
field is not a body classification at all, but an answer to "what did the
Commander arrive at". A star reading carries `StarType`, `Subclass`,
`StellarMass`, `Radius`, `AbsoluteMagnitude`, `Luminosity`, `Age_MY`; a planet
reading carries `PlanetClass`, `TidalLock`, `TerraformState`, atmosphere,
`Volcanism`, `SurfaceGravity`, `SurfacePressure`, `Landable`, `Materials`,
`Composition`, `ReserveLevel`; both carry the same orbital parameters,
`Rings`, and the same discovery flags.

## Decision

`kairon.system` holds a registry of the star system the Commander is in. It is a
peer of the behaviour graph: a separate projection with its own lifetime, its
own snapshot and its own view in the desktop GUI.

### One system, one visit

The registry describes the current system and nothing else. It begins empty and
is discarded when the visit ends.

It does not decide when that happens. `kairon.semantics.SystemVisitPolicy`
already answers `BEGIN`/`CONTINUE`/`END`, and the graph and
`BodySurveyNoveltyGuard` both ask it without reading each other. The registry
becomes the third asker under the same rule as the other two: it asks, it does
not re-derive. A visit has one definition, and a third independent counter is
the defect that rule exists to prevent.

A body left behind in a system left behind is forgotten. This is deliberate and
it has a consequence that must be honoured rather than papered over: returning
to a system later in the same run finds an empty registry, and the honest answer
to "what was collected here" is then *unknown*, never *nothing*. Absence is not
zero — the rule already in force for signal counts
([ADR-0016](ADR-0016-HISTORICAL-FINDINGS-AND-POSITIVE-COUNTS.md),
[ADR-0024](ADR-0024-ONE-SHAPE-FOR-A-SIGNAL-COUNT.md)) applies here unchanged.

### The registry records; it does not infer

Every structural fact comes from a record that stated it. The parent chain comes
from `Parents`. The body class comes from `StarType`/`PlanetClass`. The system
total comes from `FSSDiscoveryScan`. Nothing is read out of a body name, and no
value is computed from another value.

A body may therefore be *known to exist and nothing else*: a scan of a moon
names its planet in the parent chain, so that planet enters the registry with an
identity, a parent and no properties. That is what makes "4 of 14 scanned"
truthful under any scanning order, and it is the alternative to inventing
placeholder nodes.

`Parents` is carried by `Scan` and not by `FSSBodySignals` or
`SAASignalsFound`. A body first heard of through a signals record therefore has
an identity and signals but no place in the tree until a scan arrives. It is
recorded as what it is; its position stays absent.

### Objects, not one record with blanks

`SystemObject` is a sealed hierarchy. What orbits carries identity, name,
parent chain, orbital parameters, discovery flags and knowledge level;
`StarBody`, `PlanetBody`, `RingBody`, `BeltClusterBody` and `Barycentre` carry
what their own kind of thing has. A moon is a `PlanetBody` whose parent is a
`PlanetBody` — not a class of its own, because the game does not distinguish
them and neither does the journal.

Storage is a flat map from body id to object, plus the parent chain on each
object as the journal gave it. The tree is a read, not a stored structure: a
partially known system is the normal case, and parent-to-children links would
require inventing the nodes that are not known yet.

A ring is one object, referenced by the body it orbits. It is reported twice —
as a `Rings` entry on the parent's scan and as a body in its own right — and two
stored copies of one ring eventually disagree.

### Knowledge is a level, and it only rises

`BodyKnowledgeLevel` is `LISTED` (the body's existence and place, nothing more),
`SCANNED` (a `Scan` established its class and properties), `MAPPED` (an
`SAAScanComplete` for it). This is the FSS/DSS layering, and it is monotonic: a
later reading may add facts and may correct a value, but no reading lowers the
level. `Landable`, `WasDiscovered`, `WasMapped` and footfall stay attributes of
the body — landing on a body reveals nothing further about it, so it is not a
rung on a ladder about how much is known.

The level is not graded by `ScanType`. `AutoScan`, `Basic`, `Detailed` and the
nav-beacon variants all establish the body's properties, and grading them would
be a second opinion about a record the parser has already read.

### Where a fact came from

Every object records the source that established it: `OBSERVED` or `EXTERNAL`.
Only the first is written today. The second is the placeholder for a future
third-party source (Spansh, EDSM) and exists from the first day for one reason:
without it, an external claim and a Commander's own reading become
indistinguishable the moment the second source is added, and the model would be
told it saw something it never saw. An observation replaces an external record;
an external record never replaces an observation.

Provenance is per object, not per field.

The other per-field provenance problem — `SemanticValueOrigin`, decided once per
observation in `CurrentGameStateProjector` — is not solved here but dissolved by
step five. It existed to say which body facts a record had written and which the
projector had merely served again from its per-body map, because flying to the
next body changed all of them at once. With those facts held per body in the
registry and out of canonical state, there is no delta to qualify:
`SemanticValueOrigin` and `SemanticChangeKind.ACTIVATED_FROM_CONTEXT` are gone,
and so are the two selector rules that read them.

### Recording is not admission

The registry records every observation that states something about the system,
whatever the model is told. `CONTEXT_ONLY` records, records declined as
triggers, and records captured during `BOOTSTRAP` all update it — exactly as
canonical state is updated on bootstrap today.

This is deliberate and it is what makes the feature work across a restart: a
Kairon started mid-session reads the journal it already has and knows which
samples were completed. It does not conflict with
[ADR-0014](ADR-0014-SESSION-RESTORE-AND-SCANNER-RESULTS.md) or
[ADR-0020](ADR-0020-EFFECT-RETENTION.md), which govern something else — whether
a historical reading may become a *finding*, an occurrence, or a background
change in a later turn. It may not, and that is unchanged. Being known and being
news are different questions, and the registry answers only the first.

### A peer of the graph

`CurrentSystemRegistry` is applied by `ObservationProjectionCoordinator` after
the state projection and beside the graph, and
`ObservationProjectionCoordinator` remains the only sequential mutation
boundary. The registry publishes nothing back through `ObservationBus`;
registry-derived notifications are internal, under the same rule as the graph's.

Unlike the graph it is owned by the coordinator rather than supplied to it. The
graph is configurable, has a store and can be switched off; the registry is
none of those — it is pure computation over the record — so there is nothing for
a caller to choose, and it is constructed like the semantic envelope factory
already beside it.

The order is fixed as state → registry → graph for reproducibility, and carries
no dependency: neither projection reads the other. Two peer projections that
read each other are two projections that drift.

`SystemRegistrySnapshot` is immutable and is captured at the projection
boundary, then carried on `ProjectedObservation` beside the behaviour
situation. This is not an implementation preference: building a decision request
must never perform a late read of a live service
([ADR-0013](ADR-0013-LLM-DECISION-INTERFACE.md)), so the registry must be able
to hand out a cheap immutable snapshot per observation, and that requirement
shapes the whole type.

The registry is journal-only. Unlike the graph, whose `Status.json`-derived
occurrences cannot be reconstructed from a journal file, a replayed registry is
identical to the live one. Replay parity is therefore total here and is
asserted, not assumed.

### What the model gets

Nothing, at first. The registry is state; what reaches the model stays governed
by `DecisionContextSelector` and by the 16 000-character budget, and the whole
registry could never fit inside it.

The second use, added after a measured run showed the cost of its absence, is
how much of the system has been read: `context.system` carries `bodyCount` — the
total a discovery scan stated — beside `scannedCount`, how many of those bodies
the Commander actually has a reading for. A run without them had Kairon call the
eleventh body of a system "the first planet discovered here", and nothing in the
request could contradict it.

Both or neither. Progress is a fraction, and a bare numerator is worse than
silence: before the discovery scan states a total, the arrival star's own
milestone turn carried `scannedCount: 1` — the reading that turn is about, handed
back to it as background. A count of zero is absent too, for the reason every
other absent field is.

The numerator counts stars and planets, because that is what a discovery scan
totals. Read off a measured journal rather than assumed: Schieni GG-A c3-64
reported `BodyCount: 9`, and the Commander took eight planet readings plus the
arrival star's. Barycentres, rings and belt clusters are recorded all the same;
they are not what the total is a total of, and two numbers that are not
comparable must not be put side by side.

The first and only model-facing use is exobiology on the body the Commander is
on: which genera the surface survey reported, and which of them have a completed
sampling sequence. The denominator is `SAASignalsFound.Genuses`, available only
after mapping; before mapping the journal states a count and no names, so the
honest form is "one of three" without them. Genera are matched on raw
identifiers through the existing `TaxonName.sameIdentity`, never on localised
labels. Since no source states that a genus cannot appear twice on one body,
both the named set and the reported count are kept, and neither is derived from
the other.

This supplies a fact; it does not request speech. There is no rule that a
completed sequence must be commented on. Comment-worthiness belongs to the
model, and a rule keyed to an event type is exactly the mechanism this project
refuses to build.

The shape is one `context` group named `biology`, with one field per organism —
the organism's own name — valued `COLLECTED` or `NOT_COLLECTED`. Not one field
carrying a list: `SemanticValue` is a closed set with no list variant, and
adding one would put back the compound value
[ADR-0024](ADR-0024-ONE-SHAPE-FOR-A-SIGNAL-COUNT.md) removed. The field names in
this one group are therefore data rather than schema, which is accepted: they
are the game's own words for the organisms, the model already meets them in the
sampling events, and the value vocabulary stays closed at two tokens.

A genus the game supplies no usable word for is left out of the group rather
than spelled as its `$Codex_Ent_…` symbol — the identifier is what readings are
compared on and it is not a name anything shows. It is still recorded, still
counted and still compared; only the naming is withheld.

The whole inventory is sent, including an organism the turn's own event has just
finished. That is not the event stated twice: the event reports an action, the
group reports what stands on the body, and the group's worth is in being
complete. A list with the just-collected organism removed reads as the list of
what is left, one item short.

### A registry is state; a catalogue is not

Future reference data — minerals, exobiology values, anything static and
knowable without observation — does not belong under `kairon.system` and will
not be called a registry there. It has the opposite lifetime: loaded once, never
reset, independent of where the Commander is. It gets its own root,
`kairon.reference`, so that one word never comes to mean both a per-visit
projection and a lookup table.

## Consequences

The work is staged so that each step is provable on its own.

1. **A fixture containing `Scan` records is added to
   `ModelFacingReplayBaselineTest`.** No existing fixture contains a single one,
   although `Scan` is the most frequent structural record in a live journal, so
   the baseline is currently blind to every change described here. This comes
   first, before any code, or the remaining steps cannot be verified at all.
2. **`kairon.system`, the object hierarchy, the registry and its snapshot.**
   The model-facing request is unchanged byte for byte and
   `target/model-facing-baseline.json` must not move.
3. **The GUI view.**
4. **Exobiology on the current body reaches `context`.** The baseline changes
   here, and only here.
5. **Canonical body facts are read from the registry, and `bodies` and
   `BodyContext` are deleted.** Not earlier: the snapshot fields derived from
   that map are already model-facing, and removing it first would change
   everything at once and prove nothing. This step is also where the open
   per-field-origin defect ends — "what changed now" and "what is known about
   this body" stop being one value with a flag on it.
6. **How much of the system has been read reaches `context.system`.** The
   baseline moves here, by addition only: 41 leaves in the two fixtures that
   contain a discovery scan, and not one existing value.
7. Later, outside this ADR's staging: stations and signal sources as further
   `SystemObject` kinds, the external source, the reference catalogues, and any
   further use by the graph.

### What step five turned out to require of step six

Canonical state had a second reader of its body fields: `ContextSnapshot`, which
the graph persists with every occurrence and which
`TransitionContextKeyFactory` buckets two transitions by — the biological count
and landability after a surface survey, and whether the body has biology after a
touchdown. Removing the fields without replacing that supply would have merged
every landing into one bucket and coarsened `likelyNext` for good. Worse, it
would have done so **invisibly**: the replay baseline records edges as
`from->to=count` without their context keys, so a uniform key change moves not
one byte of it.

So the graph keeps its body context, and gets it from the registry. Not by
reading it: `kairon.behavior → kairon.system` stays forbidden, and the peer
isolation above is unchanged. `BodyDetail` is ten plain values in
`kairon.behavior.context` and `BodyDetailLookup` is how one is asked for by
`(systemAddress, bodyId)`; `ObservationProjectionCoordinator` — the one place
that already applies both projections in order — hands the graph a
`RegistryBodyDetail` over the snapshot this observation just produced. The graph
never learns that a registry exists, the registry never learns that a graph
does, and neither reads the other. Handed in with the observation rather than
fetched, for the same reason `SystemRegistrySnapshot` is a snapshot at all: a
late read of a service that has moved on is the defect
[ADR-0013](ADR-0013-LLM-DECISION-INTERFACE.md) forbids.

This is a smaller thing than "the graph uses the registry", which stays deferred.
The graph asks nothing new; it is answered from the new place.

`ProjectedObservation` gains a field, so `SemanticPipelineHarness` and
`PipelineTrace` gain the registry, and the discipline of
[ADR-0017](ADR-0017-CROSS-LAYER-CONTRACT-TESTS.md) extends to it verbatim: a
registry assertion without a provider assertion is not a test, and neither is
the reverse.

Five forbidden package directions are added to `PackageDependencyRulesTest`:
`kairon.semantics → kairon.system`, because what an observation means cannot
depend on a projection; `kairon.system → kairon.observer`, because a projection
cannot know its consumer; `kairon.system → kairon.behavior` together with
`kairon.behavior → kairon.system`, which is the peer isolation above stated as a
rule; and `kairon.system → kairon.state`, because step five reverses that
direction and a registry importing the projection it will feed is the cycle that
step would run into. What the registry needs of canonical state arrives as a
`VisitIdentity` of four plain values.

The guard's source reader now drops string literals before looking for package
references. It had none to drop before; the graph's schema version
`"kairon.system-episode/v3"` is the first value that reads as a package name
without being one. A Java package cannot be reached through a string literal —
nothing here uses reflection — so this makes the reader more accurate rather
than more permissive.

Stations, settlements and signal sources are out of scope and are expected to
enter as further `SystemObject` kinds without changing anything decided here.
They arrive through different records, carry no `bodyId`, and need no new
storage shape.

## Rejected alternatives

- **Keeping the cross-system map and adding fields to `BodyContext`.** Every
  question above is about structure, and none of them is a missing field.
- **Deriving the hierarchy from body names.** The journal states it. Two answers
  to one question is how they drift.
- **One record for every kind of body, with the irrelevant fields left null.**
  It is what exists, and it is why a star and a planet were once described as
  one body.
- **Storing the tree as parent-to-children links.** A partially known system
  would need invented nodes for bodies that have not been scanned.
- **A second definition of when a visit begins and ends.** Three counters that
  agree until they do not.
- **Per-field provenance in the registry now.** A real problem, a different one.
- **Sending the registry to the model, in whole or summarised.** The budget
  forbids the first and
  [ADR-0010](ADR-0010-MODEL-FACING-EVENT-VERBALIZATION.md) forbids the second.
- **Anything derived from registry completeness that ranks, scores or prioritises
  a body.** The registry supplies facts. Importance is the model's.
- **Publishing registry state through `ObservationBus`,** which carries external
  observations only ([ADR-0006](ADR-0006-EXTERNAL-AND-DOMAIN-EVENTS.md)).
- **One package for the registry and the future catalogues.** They share a word
  and nothing else.

## Relevant implementation references

- [`CurrentGameStateProjector.java`](../../src/main/java/kairon/state/CurrentGameStateProjector.java)
- [`CurrentSystemRegistry.java`](../../src/main/java/kairon/system/CurrentSystemRegistry.java)
- [`RegistryBodyDetail.java`](../../src/main/java/kairon/projection/RegistryBodyDetail.java)
- [`BodyDetail.java`](../../src/main/java/kairon/behavior/context/BodyDetail.java)
- [`SystemVisitPolicy.java`](../../src/main/java/kairon/semantics/SystemVisitPolicy.java)
- [`ObservationProjectionCoordinator.java`](../../src/main/java/kairon/projection/ObservationProjectionCoordinator.java)
- [`Scan.java`](../../src/main/java/kairon/observation/journal/event/exploration/Scan.java)
- [`SAASignalsFound.java`](../../src/main/java/kairon/observation/journal/event/exploration/SAASignalsFound.java)
- [`ScanOrganic.java`](../../src/main/java/kairon/observation/journal/event/exploration/ScanOrganic.java)
- [`BiologicalSamplingProcess.java`](../../src/main/java/kairon/state/BiologicalSamplingProcess.java)
- [`PackageDependencyRulesTest.java`](../../src/test/java/kairon/PackageDependencyRulesTest.java)
