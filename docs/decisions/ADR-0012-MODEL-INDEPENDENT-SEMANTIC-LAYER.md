# ADR-0012: Model-independent semantic layer

## Status

Accepted and implemented as `kairon.semantics`, with adapters for all 114
selected journal event types and a disposition for all 272 catalogued types.
The layer feeds diagnostics and, through the projection in
`kairon.observer.decision`, the `kairon-llm-decision-v1` production model input
(ADR-0013). Semantic facts are never serialized to the provider directly.

## Context

Every catalogued journal event is a `record X(RawJournalData raw)`. Under
ADR-0002 those records are transport identities around exact raw JSON and are
not a domain model. Under ADR-0010 each selected type owns an English
`llmPresentation()`.

Prose was therefore the only carrier of meaning reaching the model. Subject,
operation, quantity, process stage, completion and — critically — negation
survived solely as English substrings, and the budget ladder could truncate a
sentence mid-clause, severing a negation or a number. `MissionCompleted` versus
`MissionFailed` is polarity expressed as Java class identity, with no
`boolean succeeded()` anywhere to carry it structurally.

Separately, `ProjectedObservation` kept only `currentState` plus a set of eight
coarse changed-facet names. `previousState` and `observationContext` were
dropped at the projection boundary, so nothing downstream could compute a
before/after delta at any cost. The model was told *that* `BODY` changed, never
from what to what, and `CONTEXT_ONLY`, `DIAGNOSTIC_ONLY` and Status
observations changed state while leaving no trace of what caused it.

## Decision

Introduce a model-independent semantic layer, `kairon.semantics`, that
materialises structured meaning once and serves every consumer.

**Placement.** Structured facts are produced by per-type adapters registered by
payload class in `SemanticAdapterRegistry`, generalising the existing
`BehaviorEventNormalizer.register(...)` pattern. They are not fields on the 272
event records, which would make transport identity carry domain meaning; and
they are not built in the LLM layer, which would make them model-specific and
would break the turn factory's purity contract.

**Dependencies.** `kairon.semantics` depends on no LLM type, no JSON
serialization, no prompt wording, no response DTO and no speech. It is
consumable by diagnostics and the GUI as readily as by the observer.

**Field deltas are computed where they are computable.** The exact per-field
before/after delta is produced inside `CurrentGameStateProjector`, the only
place where the previous snapshot, the current snapshot and the projector's
write path are simultaneously in scope. It is carried on
`CurrentGameStateProjection.semanticChanges` and reaches subscribers on
`ProjectedObservation.semanticEnvelope`. Nothing downstream recomputes it.

**Change kinds are formally derivable and closed.** `ESTABLISHED`, `UPDATED`,
`CLEARED`, `ACTIVATED_FROM_CONTEXT`. `UNCHANGED` is never emitted: absence of
an entry means unchanged. `ACTIVATED_FROM_CONTEXT` is decided by **write path,
never by value comparison** — a re-visited body re-hydrates stored facts whose
values can be identical to freshly observed ones, and reporting that as
`ESTABLISHED` would tell the model something was just learned when it was not.

**Values are typed and closed.** `SemanticValue` is a sealed hierarchy. Unknown,
absent, cleared, empty, zero and false stay distinguishable; none of them is
ever collapsed into another, and no arbitrary `toString()` or untyped map is
used.

**Gaps are stated, not guessed.** `UnresolvedFact` carries a closed reason set.
Vehicle occupancy, taxi context, multicrew context and fighter presence are not
establishable from the current projection, so they are recorded as unresolved
rather than inferred. `occupiedVehicle` has no canonical field and no
`SemanticField` maps to it.

**Hidden provenance is owned by the turn boundary.** `ObserverTurnCoordinator`
holds a bounded `SemanticEffectAccumulator` and drains it in the same critical
section that fixes the trigger batch. Non-`NEW` observations contribute their
effects without ever becoming triggers. Over the memory bound the accumulator
coalesces per field — earliest before, latest after, exact net transition — and
always attaches a typed `SemanticSuppression` marker. Canonical state changes
are never evicted.

**Subjects are separated.** Eleven subjects exist so that legitimate
simultaneous states cannot read as contradictions: a commander on foot beside a
landed ship and a deployed SRV is three independent facts. A canonical field
whose owning subject the repository does not establish is bound to a neutral
subject rather than to a guessed one — this is why flight mode lives on
`NAVIGATION_CONTEXT`.

**The layer adds no judgement.** No importance, rarity, value, danger or
comment-worthiness score exists anywhere in it. Structured facts describe what
was observed; deciding whether it is worth saying remains the model's.

## Consequences

- Meaning survives truncation. A negation, a quantity or a process stage is a
  typed field, not a clause that a character budget can sever.
- Novelty is stated rather than inferred. The model is given the exact
  before/after of every canonical change with its provenance, and never has to
  compare snapshots.
- Coverage is enforced rather than claimed. Every catalogued type resolves to
  exactly one `SemanticDisposition`, and a new type without a decision fails the
  coverage guard at test time.
- The adapter surface is large: 117 adapters today. That cost is deliberate, and
  is bounded by the selected event space rather than by the full catalogue.
- `ProjectedObservation` gained a sixth component, which widened the direct
  construction sites in tests.
- The semantic envelope is the sole input to the model-facing projection. It is
  not itself a model contract, is not versioned as one, and under ADR-0013 is
  not serialized to the provider: the projection turns it into domain-facing
  events first.

## Rejected alternatives

- **Typed semantic fields on the 272 event records.** Violates ADR-0002 and
  multiplies the change surface by 272.
- **Building structured facts in the LLM turn factory.** Breaks its verified
  purity, requires raw-JSON parsing at turn-build time, and makes the semantics
  unusable by any other consumer.
- **Reusing `BehaviorEventNormalizer` as the semantic layer.** It is graph-scoped
  — 33 significant types, a vocabulary designed for transition topology. Kept as
  the pattern, not the home.
- **Deriving hidden effects by diffing `currentState` between consecutive `NEW`
  triggers.** Attribution is exactly the provenance that was discarded, and
  several hidden observations collapse into one indistinguishable diff.
- **Extending `CurrentGameStateChangeSet` with values in place.** Breaks
  `CurrentGameStateProjection`'s recompute-and-verify invariant.
- **Additional change kinds** such as `CONFIRMED` or `REFRESHED`. The projection
  has no notion of a fact being re-asserted versus never re-checked.
- **Dropping oldest effects on accumulator overflow.** Silent loss. Coalescing
  preserves the net transition and reports the suppression.

## Relevant implementation references

`src/main/java/kairon/semantics/` — `SemanticSubject`, `SemanticField`,
`SemanticValue`, `SemanticProvenance`, `SemanticChangeKind`,
`SemanticValueOrigin`, `SemanticStateChange`, `SemanticFact`,
`SemanticOperation`, `UnresolvedFact`, `SemanticObservationEnvelope`,
`SemanticAdapterRegistry`, `SemanticEffectAccumulator`, `SemanticSuppression`,
`SemanticDisposition`.

`src/main/java/kairon/projection/SemanticEnvelopeFactory.java` — the builder of
the envelope. It was part of this package until it came to need the applied
observation; see [ADR-0019](ADR-0019-APPLIED-OBSERVATION.md).

`src/main/java/kairon/state/CurrentGameStateProjector.java` — the delta and the
write-path origin. `src/main/java/kairon/projection/ProjectedObservation.java` —
the envelope on the publication. `src/main/java/kairon/observer/`
`ObserverTurnCoordinator.java` and `LlmJournalObserverSubscriber.java` — the
accumulator and the effect-first ordering.

Design and audit: `docs/design/kairon-llm-situation-v2-design.md` §8, §9, §23,
§24; `target/audit/kairon-llm-situation-v2-phase-b-report.md` and
`-phase-b1-report.md`. For how these facts become model input, see ADR-0013 and
`docs/design/kairon-llm-decision-interface.md`.
