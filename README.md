# Kairon

Kairon is a local, LLM-centred companion for
[Elite Dangerous](https://www.elitedangerous.com/).

The long-term intent is a companion that behaves like a crew member rather than
a voice command parser: one that follows the flight, keeps track of what is
going on, remembers what matters across sessions, speaks when it is worth
speaking, and stays quiet when it is not. Language understanding, reasoning and
judgement belong to the model; capture, ordering, bounded context, validation,
delivery and safety belong to deterministic code. Kairon runs on your own
machine, against a model provider you choose.

## Project status

**Early development. This is a foundation, not a finished product.**

This publication is `v0.1.0-observer-baseline`: the first working product loop.
Kairon watches the game, decides whether anything is worth saying, and
occasionally says one short thing. That loop runs end to end — but it is the
starting point of the companion described above, not the whole of it.

Expect rough edges, incomplete semantics, and interfaces that will change.
There is no conversation, no memory across sessions, no goals or tasks, no
speech recognition, and no game control.

## What exists today

- **Live journal observation** and **paced journal replay**, both through one
  identical pipeline.
- **Ordered observation batching** into bounded model turns.
- **Model-facing event selection**, deciding what may trigger a turn and what is
  context only.
- **LLM-backed comments** — one compact decision document per turn; the model
  answers `SILENT` or `COMMENT`, and responses are structurally validated before
  anything is delivered.
- **Deterministic behavior graph** — a per-commander, per-ship projection of
  what normally follows what, persisted locally.
- **Optional Google Cloud Text-to-Speech**, disabled by default, and an
  **optional desktop GUI** showing the journal feed, model turns and the graph.
- **Turn traces** — one JSONL record per turn for offline inspection.

## Requirements

- **JDK 21.** The build enforces `[21,22)`.
- **Maven Wrapper**, included and pinned to Maven 3.9.16. A separate Maven
  installation is not supported.
- **A model provider, at runtime only** — a local LM Studio server or a Mistral
  API key. Google Cloud Text-to-Speech is optional.

Building and testing require no credentials, no network services and no game
data. The test suite never calls a model provider or Google.

## Build and test

```powershell
mvnw.cmd clean test
```

On Linux and macOS use `bash ./mvnw clean test`. Build the jar with
`clean package` instead of `clean test`.

## Configuration

Copy the example and edit your local copy:

```sh
cp config/kairon.example.json config/kairon.json
```

Credentials live in a separate `config/authentication.json` beside it. Both
files are git-ignored. `authentication.json` is the only credential source —
Kairon reads no environment variables for API keys, and no key belongs in the
example file.

Supported providers, as implemented: **LM Studio** (`LM_STUDIO`) and
**Mistral** (`MISTRAL`) for the model, **Google Cloud Text-to-Speech**
(`GOOGLE_CLOUD_TTS`) for speech. Exactly one model provider is active at a
time; there is no discovery, routing or failover.

Run it:

```powershell
mvnw.cmd exec:java "-Dexec.args=--config=config/kairon.json"
```

See [`config/kairon.example.json`](config/kairon.example.json) for every
available setting.

## Documentation

- [Architecture](docs/KAIRON_ARCHITECTURE.md) — normative project boundaries.
- [Current State](docs/CURRENT_STATE.md) — what is actually implemented, from
  repository evidence, including known gaps and deferred scope.
- [Decisions](docs/decisions/) — architecture decision records.
- [Design notes](docs/design/) — the model-facing decision interface.
- [`docs/archive/`](docs/archive/) — historical and non-normative.

## Privacy

Your game journal is personal data: it identifies your Commander, your ships,
and everywhere you have flown. Nothing personal belongs in this repository — no
journals, traces, graph data, credentials, local configuration or Commander
identity. Test fixtures use a synthetic Commander on purpose.

**Kairon is not private by default.** With a hosted provider, journal-derived
facts about your session are sent to that provider and handled under their
terms. Enabling speech sends comment text to Google. If you want nothing to
leave your machine, use a local LM Studio provider and leave speech disabled.
Turn traces and graph data stay on your disk and are yours to protect.

## License

Kairon is **source-available software for noncommercial use** — not open
source.

Source code is licensed under the **PolyForm Noncommercial License 1.0.0**.
Commercial use requires a separate written license from the copyright holder.

Documentation and media assets are licensed under **CC BY-NC-SA 4.0** unless
otherwise stated.

See [`LICENSE`](LICENSE), [`LICENSE-DOCS`](LICENSE-DOCS),
[`LICENSES.md`](LICENSES.md), [`NOTICE`](NOTICE) and
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

Copyright 2026 Alex Gnevko.

Required Notice: Copyright 2026 Alex Gnevko (GitHub: Gnevko)

## Disclaimer

Kairon is an independent, unofficial project. It is not affiliated with,
endorsed by, or sponsored by Frontier Developments plc. Elite Dangerous and
related names are the property of their respective owners. Kairon reads files
the game writes locally; it does not modify the game.
