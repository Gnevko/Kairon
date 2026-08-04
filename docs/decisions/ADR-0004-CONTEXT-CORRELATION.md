# ADR-0004: Context correlation

## Status

Accepted and implemented for the current journal observer.

## Context

Some raw journal observations describe state needed to interpret a later
action or outcome but should not create a model turn by themselves. A simple
global “last N events” window can attach unrelated scans, locations, or route
targets after system transitions.

At the same time, deterministic context code must not become an importance or
narrative engine.

## Decision

The observer uses two researched runtime profiles:

- `BALANCED-109` identifies 109 reviewed types that become observer-local
  `NEW`;
- `CONTEXT-5` identifies five context-only types;
- `DIAGNOSTIC_ONLY` is the fallback role outside those sets.

All 114 selected event classes have an event-owned presentation implemented
under [ADR-0010](ADR-0010-MODEL-FACING-EVENT-VERBALIZATION.md). The five
context-only types are `Scan`, `FSSBodySignals`, `SAASignalsFound`,
`FSDTarget`, and `Location`. Membership only permits model participation; it
does not require a comment.

Correlate context using stable technical identity only: observation source,
system address, body identity, ordering, a source-local transition epoch, and
available genus, species, and variant identities. System, body, and biological
catalogue relationships may select related observations for a bounded model
window. Correlation reads exact raw technical identity, while the semantic
window renders each admitted observation through its event-owned
presentation.

`ApproachBody` is a NEW event. Its source/system/body identity allows a
previous `Scan` to enter the same turn as context when the ship reaches the
orbital-cruise zone. This correlation makes documented environmental facts
timely without declaring them important or manufacturing a narrative.

The model window is assembled in this order:

1. the current ordered NEW batch;
2. directly correlated observations captured for those NEW events;
3. at most five recent processed observations from a technically compatible
   episode.

Source and transition epoch must match for the recent episode tail. Identity
fields that are present on both observations must agree. Missing fields do not
create an inferred mismatch, and the five-event bound prevents this fallback
from rebuilding a broad global history. The complete window remains capped at
30 and unused capacity is left unused.

Correlation may not assign rarity, value, significance, emotion, player
intent, narrative importance, or comment-worthiness. The LLM remains the
semantic decision-maker.

`CONTEXT` and `NEW` are model-window roles owned by this observer. They are not
properties of the shared publication.

## Consequences

- Context-only telemetry cannot start a turn by itself.
- Related evidence can survive unrelated traffic and system transitions.
- Long-running sessions no longer fill every model turn with unrelated recent
  history.
- Unknown and unselected events still reach diagnostics.
- Profile membership is product scope, not a deterministic COMMENT rule.
- Profile or correlation changes require controlled replay evaluation.

## Rejected alternatives

- Subscribing the LLM observer to every journal type without a product profile.
- Event importance scores, priorities, allowlists for comments, or rarity
  rules.
- Correlating only by recency.
- Filling every available model-window slot merely because older history
  exists.
- Moving correlation state into `PublishedObservation` or `ObservationBus`.
- Correlation-generated or unsourced narrative context summaries.

## Relevant implementation references

- [`LlmJournalEventSelection.java`](../../src/main/java/kairon/observer/LlmJournalEventSelection.java)
- [`ObserverContextStore.java`](../../src/main/java/kairon/observer/ObserverContextStore.java)
- [`EventWindowBuilder.java`](../../src/main/java/kairon/observer/EventWindowBuilder.java)
- [`ObserverPipelineTest.java`](../../src/test/java/kairon/observer/ObserverPipelineTest.java)
- [ADR-0010](ADR-0010-MODEL-FACING-EVENT-VERBALIZATION.md)
