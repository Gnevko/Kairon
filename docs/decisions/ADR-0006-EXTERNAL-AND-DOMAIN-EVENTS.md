# ADR-0006: External observations and domain events

## Status

Accepted as an architectural boundary. The internal domain-event mechanism is
deferred.

## Context

Kairon must distinguish facts obtained from the game or another external
source from its own decisions, proposals, delivery outcomes, and mutable
application state.

Putting both categories on one bus could make generated model output or an
internal proposal look like observed game truth.

## Decision

`ObservationBus` carries only:

- externally obtained observations with source metadata;
- technical lifecycle signals emitted by an observation source.

It does not carry LLM decisions, generated comments, internal commands, tasks,
memories, permission decisions, action authorizations, action-result records,
or arbitrary application exceptions.

If Kairon later needs internal domain events, introduce a separate
`DomainEventBus` or equivalent internal boundary. Its contracts, persistence,
and delivery semantics require a future decision; they are not designed or
implemented by this ADR.

Future externally observed telemetry that verifies an action may still enter
`ObservationBus` as source fact. Kairon's internal interpretation of that fact
as an action result remains domain state.

## Consequences

- Model output cannot be mistaken for an externally observed fact.
- Observation subscribers can rely on source provenance.
- Output, tasks, permissions, and future action workflows remain separate.
- No `DomainEventBus` placeholder or speculative abstraction is required now.
- Future action support needs deterministic authorization and result
  verification in addition to any internal event mechanism.

## Rejected alternatives

- One global bus for arbitrary application objects.
- Publishing `SILENT`, `COMMENT`, speech outcomes, or task mutations as
  observations.
- Treating exceptions as telemetry.
- Designing or implementing a domain-event platform before a concrete need.

## Relevant implementation references

- [`ObservationPayload.java`](../../src/main/java/kairon/observation/ObservationPayload.java)
- [`ObservationDraft.java`](../../src/main/java/kairon/observation/ObservationDraft.java)
- [`PublishedObservation.java`](../../src/main/java/kairon/observation/PublishedObservation.java)
- [`ObservationSourceSignal.java`](../../src/main/java/kairon/observation/source/ObservationSourceSignal.java)
- [`ObservationBus.java`](../../src/main/java/kairon/observation/bus/ObservationBus.java)
- There is intentionally no `DomainEventBus` implementation.
