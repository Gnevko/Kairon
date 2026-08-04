# ADR-0002: Raw and typed telemetry

## Status

Accepted and implemented for the raw catalogue; model-facing presentation is
incremental under [ADR-0010](ADR-0010-MODEL-FACING-EVENT-VERBALIZATION.md).

## Context

Kairon needs exact source facts for LLM interpretation and forward
compatibility, while Java subscribers benefit from stable typed dispatch.
Replacing raw data with normalized DTOs can discard unknown data. Conversely,
raw field names and opaque values alone do not give a general LLM the
event-specific knowledge needed to interpret them.

## Decision

Preserve the exact validated raw source value as authoritative telemetry.

Known journal discriminators additionally map to a schema-pinned, checked-in
typed catalogue. Catalogue records provide Java type identity around the same
raw data. They remain transport representations, not Kairon's world model.

An LLM-selected record may additionally implement
`LlmPresentableJournalEvent`. Its concrete class owns a researched,
deterministic factual English presentation of that event's documented fields.
The presentation does not replace, mutate, or reduce the shared raw source.

Unknown event discriminators, missing discriminators, and unknown fields
continue through the raw path. They must not be rejected merely because the
typed catalogue is older than the source.

Typed telemetry records are transport and subscription types. They are not
Kairon's world model, narrative model, or proof that an event is important.

## Consequences

- Raw evidence remains available for diagnostics, GUI display, and traces.
- The LLM receives researched event-owned facts instead of requiring implicit
  Journal API knowledge.
- Subscribers can use Java payload types without parsing topic strings.
- Catalogue evolution remains compatible with unknown future fields/events.
- A catalogue pin makes coverage auditable.
- The current repository contains checked-in catalogue records but no
  standalone generator; current state must not claim an automated generation
  workflow that is not present.

## Rejected alternatives

- Sending only normalized DTO fields and discarding raw telemetry.
- Raw JSON alone as semantic model input.
- A central generic or unsourced natural-language summary subsystem.
- Rejecting unknown events or stripping unknown fields.
- Treating each telemetry class as a Kairon domain aggregate.
- Using raw `event` strings as bus topics.

## Relevant implementation references

- [`JournalEventObservation.java`](../../src/main/java/kairon/observation/journal/JournalEventObservation.java)
- [`LlmPresentableJournalEvent.java`](../../src/main/java/kairon/observation/journal/LlmPresentableJournalEvent.java)
- [`JournalEventCatalog.java`](../../src/main/java/kairon/observation/journal/JournalEventCatalog.java)
- [`UnknownJournalEvent.java`](../../src/main/java/kairon/observation/journal/UnknownJournalEvent.java)
- [`event/`](../../src/main/java/kairon/observation/journal/event/)
- [`JournalSourceTest.java`](../../src/test/java/kairon/observation/journal/JournalSourceTest.java)
- [`JournalEventLlmPresentationTest.java`](../../src/test/java/kairon/observation/journal/JournalEventLlmPresentationTest.java)
