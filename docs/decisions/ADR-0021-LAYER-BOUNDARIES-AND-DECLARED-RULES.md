# ADR-0021: Layer boundaries, one visit, and rules that can be enumerated

## Status

Accepted and implemented. Behaviour-preserving: the model-facing JSON, the graph
occurrences, the trigger selection profile and every observable output are
unchanged, and a deterministic replay of the checked-in fixtures produces a
byte-identical document set before and after.

## Context

A read-only audit of the pipeline found eight structural defects. None of them
had produced a wrong comment yet; each of them was the shape that had produced
one twice before — two layers holding two versions of one rule, and each layer's
own tests green.

1. `kairon.semantics` imported `kairon.observer` and `kairon.behavior.classify`.
   The meaning of an observation was therefore defined by the profile of the one
   consumer that happened to be built first.
2. The behaviour graph and the observer's novelty guard each decided
   independently when a visit to a system begins and ends, and each derived the
   arrival body its own way.
3. `DecisionEventCatalog.ruleFor(observation)` carried one `if` for the rule a
   record earns rather than a type. It was invisible to any property asserted of
   "every rule", and a second such case would have been a second `if` whose
   precedence was whichever was written first.
4. `DecisionMechanism` carried both the family of game event and the slice of the
   situation sent with it. Two of its constants — `CODEX` and
   `ARRIVAL_DISCOVERY` — existed only to remove body context.
5. `changes` and `context` suppressed already-stated facts with different
   machinery: canonical field and value in one, slot names plus **rendered
   strings** in the other. A boolean or a number an event stated outright was
   never suppressed in the context.
6. `ApplicationMode` and `ModelVisibility` were computed, carried the whole
   length of the pipeline and read by nothing in production.
7. `kairon.trace` and `kairon.observer` imported each other, and so did
   `kairon.llm` and `kairon.observer.decision`.
8. `(systemAddress, bodyId)` existed as four separate records in four packages.

## Decision

### One classification, in the layer that owns meaning

`SemanticSourceRoleCatalog` holds the journal-type classification and lives in
`kairon.semantics`. `LlmJournalEventSelection` is now a view of it: the profile
names, the counts, the presentation-readiness requirement and `admitsAsTrigger`
stay in the observer, and the lists come from the catalogue. One list, not two —
the previous pair of hand-maintained copies was checked against itself at class
initialisation, which could only catch a divergence a single list makes
impossible.

Retention is unchanged and is derived from capture mode alone, so reclassifying a
type cannot silently change which effects survive to a later turn.

### One visit, two memories

`SystemVisitPolicy` answers what an observation does to the visit in progress:
`BEGIN`, `CONTINUE` or `END`, with the system, the arrival body and the reason.
Both the behaviour graph and the observer's novelty guard ask it, and neither
reads the other. The graph keeps its `SystemEpisode`, its timeline and its
persistence; the observer keeps the scanner results it has already reported.
What is shared is the answer, not the memory.

The same policy owns the arrival-star rule that the graph's survey policy and the
observer's guard used to implement separately, and the arrival body both of them
resolve.

The observer now ends its visit on replay exhaustion, which the graph already
completed its episode on.

### Both extension points enumerable

`RecordDecisionRule` declares a rule a record earns. `DecisionEventCatalog` keeps
the class-keyed table and the record-keyed list as separate extension points,
`declaredRules()` is the union of both, every record rule is evaluated rather
than the first matching one, and a record two rules claim fails fast.

### A family and a slice are two things

`DecisionContextProfile` holds the context needs and the subjects in scope;
`DecisionMechanism` holds the family and what an event of it states.
`DecisionEventRule.reading(profile)` overrides the mechanism's default, so
narrowing one event's scope is a claim on that event. `CODEX` and
`ARRIVAL_DISCOVERY` are gone: both are `EXPLORATION` read against the system
alone.

A mechanism's stated fields are split into what an event **caused** — the change
is redundant, the current value is still worth stating — and which of the event's
own fields **also answers** a second canonical field, matched on the value.

### One definition of what has already been said

`StatedFacts` is built once from the turn's projected events and read by both
selectors. A statement is a canonical identity, a typed value and the event that
said it. A change occupies a slot; an event states a fact, and both halves must
match. The rendered-string comparison is gone — the one case it caught that was
not otherwise structural is the jump whose `system` field is also the arrival
star's name, and that pairing is now declared on the mechanism.

Scope is deliberately not part of it: a fact excluded because the turn is about
another body was never *stated*, and keeping the two apart is what gives every
exclusion exactly one reason.

### Nothing is carried that nothing reads

`ApplicationMode` and `ModelVisibility` are removed, with their carrier fields on
`AppliedObservation` and `SemanticObservationEnvelope`. A classification kept
alive by its own tests is a second answer waiting to disagree with the one in
force. `EffectRetention` stays: the effect accumulator reads it.

### Shared turn contracts have a home of their own

`kairon.turn.evidence.DecisionEvidence` and
`kairon.turn.overflow.ContextOverflow` are immutable values with no behaviour
beyond their own invariants, needed by two packages that must not depend on each
other. The compactor's typed failure projects itself into `ContextOverflow`, so
the sizing is not restated by whoever records it.

### One body identity

`kairon.semantics.BodyIdentity` is the value every layer means by "this body":
scanner readings, the canonical per-body registry, the body an occurrence
happened at, and the body a visit arrived at.

## Consequences

Nine executable architecture contracts now exist:
`PackageDependencyRulesTest` (six forbidden directions),
`SemanticSourceRoleCatalogTest`, `SystemVisitPolicyContractTest`,
`SystemVisitBoundaryContractTest`, `DecisionRecordRuleTest`,
`DecisionContextProfileContractTest`, `StatedFactsContractTest`,
`BodyIdentityContractTest`, and the existing cross-layer harness of
[ADR-0017](ADR-0017-CROSS-LAYER-CONTRACT-TESTS.md).

`ModelFacingReplayBaselineTest` writes `target/model-facing-baseline.json`: every
turn of a fixed replay with its trigger bus sequences, its full request document
and the graph's episodes, occurrences, transitions and cursor. It is a recording
rather than an expectation, and the comparison is between two runs of it.

What this does not change: the system prompt, the model-facing schema, the event
kinds, the trigger selection profile and its 112 types, the graph persistence
format, trajectory semantics, the provider and speech paths, and the disabled
repeated-`UNDER_ATTACK` contract.
