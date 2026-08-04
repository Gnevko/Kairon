# ADR-0003: LLM providers

## Status

Accepted for the provider boundary. Environment-based secret resolution is an
accepted target with an implementation gap.

## Context

Kairon needs both a local LM Studio endpoint and hosted Mistral without
duplicating observer semantics or creating provider-specific LLM pipelines.
Models and prices change independently of observer behavior.

The current runtime resolves API keys from an ignored adjacent
`authentication.json`. It does not yet resolve them from environment
variables.

## Decision

Support two provider types:

- `LM_STUDIO` for a local OpenAI-compatible server;
- `MISTRAL` for the hosted Mistral API.

Both providers use the same `LlmClient` boundary, the same
`OpenAiCompatibleLlmClient`, semantic messages, and response validation.
Provider selection is configuration, and every profile requires an explicit
nonblank model identifier.

Exactly one profile is active. Do not automatically discover models, fail over
between providers, route by health, balance load, or issue simultaneous calls.

Credential values must remain outside the main JSON configuration, logs,
prompts, comments, and traces. The target secret-resolution mechanism is
environment-based. Until code and tests migrate, `CURRENT_STATE.md` and the
README must continue to describe the actual adjacent-file mechanism.

## Consequences

- Observer logic remains provider-independent.
- Local Mistral-family weights served by LM Studio still use `LM_STUDIO`.
- Model identity is deliberate and reproducible.
- Secret migration requires production and test changes; this documentation
  task does not implement it.
- No document may imply that environment resolution already works.

## Rejected alternatives

- Separate LM Studio and Mistral client architectures.
- Provider-specific semantic prompts.
- Hard-coded or first-listed model selection.
- Automatic failover, scoring, or cross-provider retries.
- Credentials embedded in `kairon.json`.

## Relevant implementation references

- [`LlmClient.java`](../../src/main/java/kairon/llm/LlmClient.java)
- [`OpenAiCompatibleLlmClient.java`](../../src/main/java/kairon/llm/OpenAiCompatibleLlmClient.java)
- [`KaironConfiguration.java`](../../src/main/java/kairon/config/KaironConfiguration.java)
- [`OpenAiCompatibleLlmClientTest.java`](../../src/test/java/kairon/llm/OpenAiCompatibleLlmClientTest.java)
- [`CURRENT_STATE.md`](../CURRENT_STATE.md)
