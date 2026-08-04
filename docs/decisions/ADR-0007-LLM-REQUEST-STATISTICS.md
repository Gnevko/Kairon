# ADR-0007: LLM request statistics

## Status

Accepted and implemented.

## Context

Kairon needs to measure the operational cost and performance of model access
without coupling accounting to observer semantics, provider-specific clients,
or `ObservationBus`.

Providers may return complete, partial, absent, or invalid token usage. A
configured tariff can support a useful local cost estimate, but that estimate
is not an authoritative provider invoice. The current non-streaming transport
also cannot measure time to first token or provider-only generation speed.

## Decision

Instrument the single active `LlmClient` with the provider-neutral
`LlmRequestStatistics` component. It forwards model input and completion
semantics unchanged and records one terminal measurement for every physical
`complete(...)` call.

Normalize provider usage into `LlmTokenUsage`, preserving `COMPLETE`,
`PARTIAL`, `UNAVAILABLE`, and `INVALID` instead of inventing missing values.
Track process-local call outcomes, input/cached/output/total tokens, cache
percentage when known, end-to-end latency, running averages, and end-to-end
output-token throughput.

Write one `LLM_REQUEST_STATISTICS` log line after each terminal call and one
`LLM_REQUEST_STATISTICS_SUMMARY` line when the instrumented client closes
after accepted callbacks have settled, provided at least one call completed. A
physical retry or repair call, if introduced later, is a separate measured
call.

Throughput means output tokens divided by the complete non-streaming
`LlmClient` elapsed time. It must not be presented as time to first token or
provider-only generation speed.

Cost estimation is optional and uses only explicit per-million-token rates
from the selected provider profile. Kairon does not infer or discover prices.
An estimate is produced only when the required usage and tariff fields are
available, and is never described as billing truth.

Statistics stay outside `ObservationBus`, semantic model input, model output,
comment delivery, and the aggregate turn trace. Logs and snapshots must not
retain prompts, model responses, API keys, authorization metadata, or raw
provider exception text. Statistics or logging failure must not change an LLM
result.

## Consequences

- Operators can compare token use, cache behavior, latency, throughput, and
  estimated cost across configured provider profiles.
- Missing provider telemetry remains visibly unavailable rather than guessed.
- Measurements describe physical calls and process-local aggregates, not
  durable accounting or semantic model turns.
- Non-streaming measurements cannot expose true first-token or generation-only
  timing.
- Pricing must be maintained explicitly for the configured model and account.

## Rejected alternatives

- Computing model statistics in `ObserverTurnCoordinator` or
  `ObservationBus`.
- Maintaining separate statistics implementations for LM Studio and Mistral.
- Inferring missing token counts, cache use, or prices.
- Treating a local estimate as the provider invoice.
- Logging raw prompts, responses, credentials, or provider exception bodies.
- Describing end-to-end non-streaming throughput as streaming performance.

## Relevant implementation references

- [`LlmRequestStatistics.java`](../../src/main/java/kairon/llm/LlmRequestStatistics.java)
- [`LlmClient.java`](../../src/main/java/kairon/llm/LlmClient.java)
- [`OpenAiCompatibleLlmClient.java`](../../src/main/java/kairon/llm/OpenAiCompatibleLlmClient.java)
- [`KaironApplication.java`](../../src/main/java/kairon/app/KaironApplication.java)
- [`KaironConfiguration.java`](../../src/main/java/kairon/config/KaironConfiguration.java)
- [`LlmRequestStatisticsTest.java`](../../src/test/java/kairon/llm/LlmRequestStatisticsTest.java)
