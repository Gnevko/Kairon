# ADR-0001: ObservationBus

## Status

Accepted and implemented.

## Context

Kairon receives externally obtained telemetry from sources that must remain
independent of the LLM observer, diagnostics, future projections, and other
consumers.

Direct source-to-consumer callbacks make one pipeline the apparent owner of a
source and prevent independent reactions from evolving safely.

## Decision

Use one in-process typed `ObservationBus` for external observations and source
lifecycle signals.

A source parses and adapts its data, then publishes an `ObservationDraft`
without knowing any consumer. The bus assigns a process-local sequence,
constructs one immutable `PublishedObservation`, and dispatches it to matching
active subscribers by declared Java payload type.

Published observations contain source metadata and payload only. They contain
no subscriber processing or delivery state. Each subscriber owns its reaction,
queue, state, and failures.

The bus performs transport and dispatch only. It must not decide importance,
interest, danger, meaning, intent, or comment-worthiness.

## Consequences

- One observation can be delivered independently to multiple consumers.
- A failing handler is diagnosed without mutating the publication or blocking
  later handlers for semantic reasons.
- Sources can be reused by live, replay, diagnostic, and future consumers.
- The current implementation uses one FIFO executor and handoff-only handlers.
- Slow handler isolation, bounded subscriber mailboxes, and backpressure would
  be compatible hardening, not a new source contract.

## Rejected alternatives

- Direct source callbacks to `ObserverTurnCoordinator` or an LLM client.
- String topics derived from raw journal `event` values.
- A bus that performs semantic filtering or importance scoring.
- Kafka, RabbitMQ, or another distributed broker for the current in-process
  product slice.

## Relevant implementation references

- [`ObservationBus.java`](../../src/main/java/kairon/observation/bus/ObservationBus.java)
- [`InProcessObservationBus.java`](../../src/main/java/kairon/observation/bus/InProcessObservationBus.java)
- [`ObservationDraft.java`](../../src/main/java/kairon/observation/ObservationDraft.java)
- [`PublishedObservation.java`](../../src/main/java/kairon/observation/PublishedObservation.java)
- [`InProcessObservationBusTest.java`](../../src/test/java/kairon/observation/bus/InProcessObservationBusTest.java)
