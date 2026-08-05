# ADR-0024: One shape for a signal count

## Status

Accepted and implemented. Not behaviour-preserving: the model-facing shape of a
scanner finding changes, and `target/model-facing-baseline.json` shrinks from
53 872 to 52 920 bytes.

## Context

The same fact reached the model in two shapes, and which one depended only on
whether it was news.

As an event, a scanner finding was one compound field carrying a set:

```json
{"event": "A surface area analysis scan reported signal data for a planet or rings.",
 "body": "Schieni 4 a", "signals": [{"type": "BIOLOGICAL", "count": 1}]}
```

As standing background one turn later, the same finding was a flat field:

```json
{"context": {"body": {"biologicalSignals": 1}}}
```

Two spellings of one fact needed a bridge before anything could see they were
one. `DecisionNames.signalCategoryField` declared that the `BIOLOGICAL` entry of
a set counts `BIOLOGICAL_SIGNAL_COUNT`, and `StatedFacts.appendSignalCounts`
expanded the set through that declaration so the context would not repeat what
the event had just said. The declaration was correct and
[ADR-0018](ADR-0018-FIELD-AWARE-STATEMENTS-AND-UNKNOWN-COUNTS.md) argued it
properly — but it existed only to reconcile a difference that had no reason to
be there.

The set was chosen deliberately. `SemanticValue.SignalCountsValue` recorded the
reason: a reading is what it found, and a field per category would either invent
a form with blanks or collapse categories the Commander distinguishes.

## Decision

A scanner reading emits one count per category, named exactly as the context
names it: `biologicalSignals`, `geologicalSignals`, `humanSignals`,
`thargoidSignals`, `otherSignals`. The compound value is removed, and so are
both halves of the bridge.

The blanks objection does not survive contact with the rule already in force: a
category no reading counted is **absent**, so a count per category produces no
blanks to fill. `BodySurveyFacts.signalCountsByName` emits only the categories
the reading positively counted, in a fixed order, and
`normalizedSignalCounts` — which keeps only counts above one — remains the single
definition it always was.

The collapse objection is real and is accepted. Categories outside the closed
set are summed into `otherSignals` and lose the game's localised label with it,
so two uncatalogued categories now read as one number. What is bought is a
single shape for the four named categories, which are the only ones any reading
in the measured corpus reports. What is kept is the rule the label lived under:
the game's own `$SAA_SignalType_*` identifier still never reaches the model.

Nothing else about signals changes. A zero still establishes nothing, an omitted
category is still not a zero, a reading whose positive set is empty is still not
a finding, and the graph signature, the novelty memory and the canonical merge
still share one definition.

## Consequences

`StatedFacts` suppresses the repeat with no declaration at all. The event's field
is literally the context's field name, so the ordinary name-and-value match
applies and `signalCategoryField` has nothing left to declare. What the
declaration protected still holds and is still asserted: a human count of three
does not state a biological three, and a category the reading omitted states
nothing.

Deleted: `SemanticValue.SignalCountsValue` and its serializer branch,
`BodySurveyFacts.signals`, `DecisionNames.signalCategoryField`,
`StatedFacts.appendSignalCounts`, and the label carrier behind them. The contract
has no compound value left.

*Amends [ADR-0018](ADR-0018-FIELD-AWARE-STATEMENTS-AND-UNKNOWN-COUNTS.md), whose
"a compound field states what it is declared to state" applied to the one
compound field that existed. The field-aware rule it established is untouched and
is what makes the flat form work without a declaration.*

A stray NUL byte was found and removed with the code that carried it: the key
separator in the deleted merge was written as a literal `\0` character rather
than the escape, which made every text tool treat `BodySurveyFacts.java` as
binary. `rg` had been skipping the file silently.
