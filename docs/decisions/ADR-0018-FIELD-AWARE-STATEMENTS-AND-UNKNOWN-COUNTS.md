# ADR-0018: A fact is a field and a value, and nobody counted what nobody counted

## Status

Accepted and implemented as Phase 0.5 of the semantic-pipeline plan. Two local
corrections to production semantics. No new architectural layer, no schema
change, no migration. Amends the change-selection rules of
[ADR-0013](ADR-0013-LLM-DECISION-INTERFACE.md) and the signal-count consequences
of [ADR-0016](ADR-0016-HISTORICAL-FINDINGS-AND-POSITIVE-COUNTS.md), and closes
two of the four target contracts recorded in
[ADR-0017](ADR-0017-CROSS-LAYER-CONTRACT-TESTS.md).

## Context

The Phase 0 contract harness reproduced two defects on the production pipeline.

**A fact was identified by its value alone.** `ProjectedEvent.states` asked only
whether any field of any event in the request carried an equal `SemanticValue`,
and a change matching one was dropped as already said. A landing reports
`occurrenceOnBody: 1` when it is the first at that body — so every canonical
field whose value happened to be `1` counted as already stated. The same
established fact, from the same observation, was presented as a change when the
biological count was two and as context when it was one:

```
BIO=2 → "changes":[{"subject":"body","fields":{"biologicalSignals":{"after":2}}}]
BIO=1 → "context":{"body":{"biologicalSignals":1}}
```

The sections stop meaning anything if which one a fact lands in depends on an
unrelated integer in the same document. `changes` is what just happened and
`context` is what is standing; neither is decided by arithmetic.

**A category nobody counted was written as zero.**
`CurrentGameStateProjector.updateBodySignals` defaulted the two published
categories to `0` the moment a reading listed any signals. A system scan finding
one biological signal therefore also asserted that there are no geological ones,
and the model was shown `geologicalSignals: {"after": 0}` as an established
change. A measured zero and an unmeasured category are different facts, and the
request's whole contract is that an absent field means unknown — so the default
was the one place in the pipeline that broke it.

## Decision

### An event states a field, not a number

`ProjectedEvent.states(SemanticField, SemanticValue)` replaces
`states(SemanticValue)`. Both halves have to match.

The event's side is `statedFacts`, built when the projection turns a semantic
fact into a model-facing field — from typed `SemanticValue`s, never from
serialized JSON, and containing exactly the fields the sparse projection actually
emitted. It is keyed by the canonical identity the fact was emitted under: the
field's own model-facing name from `DecisionNames.field`, or, where an event
answers a canonical slot under a different word, the slot declared in
`DecisionNames.CONTEXT_SLOTS_STATED_BY_EVENT`.

That table gains three entries beside the one it had. An event names the system,
body or ship it happened to under the entity's own word — `system: "Schieni"` —
while the canonical field is spelled `name` inside its group. One fact, two
spellings, and the pairing is now declared rather than inferred from two values
being equal. This is the same table the context selector already consults, so
`changes` and `context` continue to answer to one identity.

A compound field states what it is declared to state, and nothing more.
`signals` carries a set, and two of its categories are canonical fields in their
own right: `DecisionNames.signalCategoryField` names them —
`BIOLOGICAL → BIOLOGICAL_SIGNAL_COUNT`, `GEOLOGICAL → GEOLOGICAL_SIGNAL_COUNT` —
and the projector expands only the categories the set actually contains. Human,
Thargoid and uncatalogued categories state nothing, because no canonical field
exists for them to state. The declaration is what separates this from the defect:
a number is never read out of the set and matched against a field it might belong
to, and a category the reading omitted is not a count of zero.

`DecisionContextSelector.Stated` registers the same stated facts, so a fact the
event reported is not repeated as context either. The two "already said"
suppressions now answer to one identity instead of two.

The two neighbouring rules are untouched. A trigger-owned change is still dropped
when its own event's mechanism declares the field (`DecisionMechanism.states`),
which was already typed. Reconciliation against the final canonical state
(`stale`) and the treatment of clearings are unchanged.

### A category nobody counted has no count

`updateBodySignals` no longer defaults anything. `BodySurveyFacts.normalizedSignalCounts`
already keeps only what a reading positively established; the merge now writes
only that. A category no reading has counted stays out of the map, so
`biologicalSignalCount` / `geologicalSignalCount` return `null`,
`CurrentGameStateSemantics` reads `UnknownValue`, and the category appears in no
change, no context group and no event.

Neither a known zero nor a retraction is introduced. A reading listing a category
at zero still establishes nothing and still clears nothing; "surveyed and found
none" is a claim no source in the journal makes, and inventing a field to carry it
would be inventing the fact.

## Consequences

- Requests are slightly smaller and slightly truer: `geologicalSignals: 0` no
  longer appears anywhere it was never measured, a background fact no longer
  moves between `changes` and `context` because of an unrelated number, and a
  scanner finding is stated once — in the set that found it — instead of twice.
- Changes the old rule suppressed by cross-field value equality now survive.
  Where that suppression was right — an event naming the body it happened on
  beside a `body.name` change — the pairing is declared and the suppression is
  kept; where it was accidental, the fact is stated once, in the section its
  cause puts it in.
- `SemanticChangeKind` for a first geological reading becomes `ESTABLISHED`
  rather than `UPDATED` from a zero that was never observed.
- No persistence format changed and no graph data is affected.
- The split brain is not resolved. Historical semantic effects still reach a live
  turn as background changes, and the repeated-`UNDER_ATTACK` divergence between
  graph and observer is still an open product decision; both remain recorded as
  disabled target contracts.
