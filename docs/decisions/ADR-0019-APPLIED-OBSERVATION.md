# ADR-0019: One applied observation, carried whole

## Status

Accepted and implemented as Phase 1 of the semantic-pipeline plan. Types and
propagation only: no behaviour changes, no model-facing schema change, no
persistence change, no migration. The two remaining target contracts from
[ADR-0017](ADR-0017-CROSS-LAYER-CONTRACT-TESTS.md) stay disabled and still fail
when activated, which is how this phase proves it changed nothing.

## Context

The architecture audit found that the pipeline's ordering and state spine are
sound and its *semantic classification* is split: five questions — is this
structural, is it new, may the model hear it, has it already been said, what does
this value mean — are answered by five to eight owners with no shared result type
and no cross-layer contract.

Two structural facts made that hard to fix rather than merely hard to see.

**The moment could not be passed on.** `CurrentGameStateProjection` carried the
two snapshots and the exact delta, but the observation's own identity and capture
metadata stayed behind at the publication. Downstream had the effects and not
what produced them.

**Capture mode stopped at the envelope factory.** `SemanticEnvelopeFactory` read
the source role and dropped everything else, so `SemanticEffectAccumulator` and
`DecisionChangeSelector` — which hold and select effects across turns — could not
tell a historical observation's effect from a live one. That is the mechanism
behind the bootstrap-effect defect: it is not a missing condition anywhere, it is
a missing field everywhere.

## Decision

### `AppliedObservation` owns the post-event moment

One immutable value for one observation after it has been applied to canonical
state: bus sequence, observation id, raw type, capture mode, source role,
application mode, model visibility, the state before, the state after, the
event-local observation context, and the exact semantic delta. Collections are
copied and the delta is checked to belong to the observation that produced it.

It lives in `kairon.state`, beside `CurrentGameStateProjection`, because it owns
two canonical snapshots and the existing package direction is
`state → semantics → observation`. Reversing that to put it in `kairon.semantics`
would have created a package cycle for no gain.

`CurrentGameStateProjection` becomes a two-field view over it plus the coarse
facet `changes`, with delegating accessors, so every reader keeps its shape while
each fact has one owner. `ProjectedObservation` likewise replaces its
`currentState` component with `applied` and reads the snapshot out of it.

Independent of everything below: no provider, no graph store, no model JSON.
Produced identically with the graph on or off, and for journal and Status
observations alike.

### Two modes, classified once — since **superseded**

> **Amendment (ADR-0021).** The two modes below were built, carried and never
> read. No layer decided anything on them, and a classification kept alive by its
> own tests is a second answer waiting to disagree with the one in force, so both
> enums and their carrier fields are removed. Everything else in this ADR stands:
> `AppliedObservation` still exists, still carries the identity, capture mode,
> source role, effect retention, both snapshots, the observation context and the
> exact delta, and `SemanticEnvelopeFactory` still copies rather than
> reclassifies. The reasoning below is kept because the *distinction* it draws is
> still the right one — it simply has no reader yet, and giving it one is a
> behaviour change to be argued on its own.



`ApplicationMode` — `RESTORE`, `OCCURRENCE`, `CONTEXT_UPDATE`, `DIAGNOSTIC`,
`CONTROL` — is what an observation does to what Kairon knows.
`ModelVisibility` — `MODEL_ELIGIBLE`, `MODEL_SILENT` — is whether the model may
be told about it at all. They are deliberately separate: answering both with one
enum is how a restoring `Location` came to mint an arrival, and how a historical
scanner result came to own the occurrence a live reading was deduplicated
against.

`ObservationSemantics` is the single pure classifier. It derives from the
policies that already decide these things rather than from new lists: the graph's
own `EventSignificancePolicy` for significance, and `SemanticSourceRoles` — which
delegates to the observer's selection profile — for the role. Three journal types
are named outright, and each is named because the existing policies genuinely
cannot separate them: `Location`, `FSDJump` and `Shutdown` are all `BOUNDARY` to
the graph and mean a restore, an arrival and the source ending.

Visibility is the observer's two existing gates and nothing narrower: historical
capture is silent whatever the event, and only a `NEW` role is eligible.
`MODEL_ELIGIBLE` is permission, not a promise — `admitsAsTrigger`, the novelty
guards and batching are untouched and still decide what actually reaches a
request.

`SemanticObservationEnvelope` gains `captureMode`, so the metadata travels with
the effects it belongs to. (It gained the two modes as well; ADR-0021 removed
them.)

`SemanticEnvelopeFactory.create` takes the `AppliedObservation` and copies all of
it — bus sequence, observation id, raw type, capture mode, source role, both
modes and the delta. It classifies nothing. Two independent evaluations of one
pure function cannot disagree in principle, but they can drift the moment either
call site is edited; one owner and one copy cannot. The publication is still
passed alongside, for what belongs to it rather than to the applied moment — the
source, the position in it, the source and observed times — and for the payload
the adapters read. Structured facts stay here because they are the one semantic
product that is not part of applying the observation to state.

That gave the factory two inputs from two packages: an `AppliedObservation` from
`kairon.state` and the adapter registry and envelope from `kairon.semantics`.
While it sat in `kairon.semantics` those two pointed at each other — `state`
reads `semantics` for its field and value types, and `semantics` read `state`
back for the applied observation.

The factory therefore lives in `kairon.projection`, beside
`ObservationProjectionCoordinator`, its only production caller. That package
already read both, so both reads now point away from the factory and the cycle
is gone:

```
projection -> state
projection -> semantics
state      -> semantics
```

Only the class moved. The API, the copying, the error policy and the returned
`SemanticObservationEnvelope` — which stays in `kairon.semantics`, because it is
what semantics means, not how it is assembled — are unchanged, and no
compatibility class was left in the old package: one factory, one entry point.

### Observed, not consulted

Nothing reads the new metadata to decide anything. The graph still classifies
through its significance policy, the observer still selects through its profile,
the accumulator still retains a historical observation's effects, the novelty
guards still keep their own memories, and the change selector is untouched.

That restraint is the phase. Every one of those is a behaviour change that has to
be argued on its own evidence, and making them all at once is how a refactor
becomes a rewrite.

### No visit identity

`AppliedObservation` carries none. A visit is owned by the behaviour graph as a
`SystemEpisode` and, separately, by the observer's novelty guard; neither has
seen the observation at the moment it is applied, and the graph can be switched
off entirely while the value must not be. Deriving one here would be a third
independent counter — the defect rather than the fix. The seam is a shared
novelty owner with the boundary derived once, which is the phase that unifies the
two guards.

## Consequences

- The bootstrap-effect defect is now addressable without a late lookup: the field
  it needs is present where the decision would be made.
- One classification exists to move decisions onto, and it is tested before
  anything depends on it — a later phase changes which layer reads it, not what
  it says.
- `CurrentGameStateProjection` and `ProjectedObservation` each have one owner for
  the canonical snapshot instead of two copies.
- Nothing about the request changed. Exact model-facing documents, provider call
  counts, graph occurrences, batching and source order are all as before, and the
  two disabled target contracts fail on exactly the same evidence as they did
  before this phase.
