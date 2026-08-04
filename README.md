# Kairon

Kairon is a local, AI-assisted observer and companion for
[Elite Dangerous](https://www.elitedangerous.com/). It reads the game's player
journal, turns ordered journal events into self-contained facts, and asks a
language model to decide whether anything is worth saying. Most of the time the
answer is silence; occasionally it is one short, useful onboard comment.

Kairon runs on your own machine. It talks to a language model provider of your
choosing — a local LM Studio server or the hosted Mistral API — and optionally
speaks its comments through Google Cloud Text-to-Speech.

The current system persona presents Kairon as a calm, observant woman with an
in-universe flight-operations and survey background. She speaks as the
Commander's shipboard companion rather than identifying herself as a model or
generic assistant.

> **Project status — observer baseline.**
> This publication is the `v0.1.0-observer-baseline`: the journal observer loop
> end to end, plus the deterministic behavior graph that supports it. It is
> under active development and is **not a finished end-user product**. Expect
> rough edges, incomplete semantics, and interfaces that will change. See
> [Known limitations](#known-limitations).

> **Licensing — source-available, noncommercial.**
> Kairon is **source-available software for noncommercial use**. It is *not*
> open source: the code license prohibits commercial use without a separate
> written agreement. See [License](#license).

## Current scope

What actually exists in this baseline:

- **Live journal observation** — watches the game's journal directory and the
  adjacent `Status.json` while you play.
- **Journal replay** — replays a recorded journal file at one-times pacing for
  offline evaluation, through the exact same pipeline as live mode.
- **Observation batching** — bounded, ordered trigger batches with a quiet
  period and a maximum batch age, so one model turn covers a coherent group of
  events.
- **Model-facing event selection** — a typed selection manifest decides which
  observations may trigger a turn, which are context only, and which are
  diagnostic only.
- **LLM-backed comments** — one compact decision document per turn; the model
  answers `SILENT` or `COMMENT`, and responses are structurally validated
  before anything is delivered.
- **Behavior graph** — a deterministic, per-commander and per-ship projection of
  what normally follows what, persisted locally as JSON.
- **Google Cloud Text-to-Speech** — optional spoken output, disabled by default.
- **Desktop GUI** — an optional Swing window showing the journal feed, model
  turns, and the active ship's graph.
- **Turn traces** — one JSONL record per turn for offline inspection.

There is no speech recognition, microphone input, game control, command system,
automation, world model, long-term memory, provider failover, or model
discovery.

## Requirements

- **JDK 21.** The build enforces the range `[21,22)`; newer or older JDKs fail
  at the `validate` phase.
- **Maven Wrapper**, included in the repository and pinned to Maven 3.9.16. A
  separate Maven installation is not required and is not supported by the
  enforcer rule.
- **Operating system.** Development and the confirmed test baseline are on
  Windows. Continuous integration runs the suite on Linux (`ubuntu-latest`).
  The build and test suite are plain Java with no platform-specific code; live
  mode reads a journal directory path that you configure, so the path — not the
  code — is what differs across platforms.
- **External services are needed only to *run* Kairon, never to build or test
  it.** A language model provider is required at runtime: either a local LM
  Studio server or a Mistral API key. Google Cloud Text-to-Speech is optional.
- **The automated test suite requires no credentials, no network services, and
  no game data.** It never calls an LLM provider and never calls Google.

## Build and test

Run the test suite.

On Windows:

```powershell
mvnw.cmd clean test
```

On Linux and macOS:

```sh
bash ./mvnw clean test
```

Build the jar:

```powershell
mvnw.cmd clean package
```

`bash ./mvnw` is used rather than `./mvnw` so the build does not depend on the
wrapper script's executable bit.

## Start here

Before implementation work, new contributors and Codex sessions must read:

1. [Kairon Architecture](docs/KAIRON_ARCHITECTURE.md);
2. [Current State](docs/CURRENT_STATE.md);
3. only the [architecture decisions](docs/decisions/) relevant to the task.

Documents under [`docs/archive/`](docs/archive/) are historical and
non-normative. They must not be used as the specification for new
implementation work. Other conceptual documents are also non-normative unless
an active ADR or `KAIRON_ARCHITECTURE.md` explicitly adopts them.

## Current status

The Journal Observer product loop is implemented as one ordered projection and
snapshot pipeline, summarized in [Current State](docs/CURRENT_STATE.md):

```text
live journal tail or paced replay
    -> complete typed observations
    -> InProcessObservationBus
    -> CurrentGameStateProjector
    -> BehaviorGraphService
    -> immutable ProjectedObservation
    -> bounded batch of NEW ProjectedObservation
    -> LlmSituationTurn from the final NEW snapshot
    -> one two-message OpenAI-compatible request
    -> validated SILENT or COMMENT
    -> console and/or Google Cloud TTS
    -> one snapshot-based JSONL turn trace
```

The same bus independently dispatches every source observation to
`TelemetryDiagnosticSubscriber`; when enabled, `DesktopUiSubscriber` receives
every journal observation. Model decisions reach the same desktop monitor
through a separate internal listener because generated output is not an
external observation and must not be published through `ObservationBus`.

The projection coordinator is the single FIFO writer for canonical state and
the optional behavior graph. Every downstream envelope contains the trigger,
post-trigger state, terminal graph result, and graph situation for one
`busSequence`. Prompt construction reads only those immutable envelopes; it
never performs a late current-state or graph query. The graph remains global
per commander FID and concrete `ShipID`, while only its active system episode
is projected into an LLM turn.

In `live` mode Kairon also polls the separately replaced `Status.json` file.
Every complete valid value is published as an immutable raw
`StatusSnapshotObservation` through the same `ObservationBus`. The behavior
subscriber owns a deterministic state-delta adapter that turns observed
changes into `FSS_MODE_ENTERED`, `FSS_MODE_EXITED`, `SAA_MODE_ENTERED`,
`SAA_MODE_EXITED`, `LANDING_GEAR_DEPLOYED`, or
`LANDING_GEAR_RETRACTED` graph occurrences. These six types are graph-only:
they are not admitted to the LLM observer, prompt, batching, or turn trace.

The event-selection manifest retains NEW, CONTEXT-only, and diagnostic roles.
Only NEW observations enter a model batch. CONTEXT-only observations still
update canonical state and the graph, but their raw presentations are never
stored or sent to the model; their effects appear in a later NEW snapshot.
Each NEW trigger uses its existing event-owned factual English presentation.

`Commander`, `Friends`, and `ApproachBody` are NEW-eligible. A `Commander`
observed through
`LIVE` or `REPLAY` can start a normal model turn. A `Commander` received as
`BOOTSTRAP` updates projections but does not call the model. `Friends`
preserves the exact
documented status and explains that an `Online` event may be a startup
presence snapshot rather than proof of a new login. `ApproachBody` reports
entry into a body's orbital-cruise zone and allows correlated `Scan` context
to make documented atmosphere, gravity, temperature, pressure, mass, radius,
and composition facts available at a useful moment.

Neither target selection nor active admission assigns importance or decides
whether an event deserves a comment. Normal-event comment-worthiness remains
an LLM decision.

A model turn contains all ordered NEW triggers in the current bounded batch,
the canonical state and graph situation captured after the final NEW trigger,
and at most three successfully delivered comments. It contains no journal
history, raw CONTEXT timeline, completed graph episodes, or raw journal JSON.
The first-version limits are eight triggers, 16 active-episode
trajectory occurrences, 20 active event counts, five predictions, and 12,000
serialized characters.

The journal adapter currently maps 272 pinned journal discriminators to
distinct top-level `public record` classes grouped under
`kairon.observation.journal.event.<category>`; unknown future discriminators
use the top-level `UnknownJournalEvent`. The catalogue is pinned to
`jixxed/ed-journal-schemas` revision
`33a8f35e81868b168b4bbd647b5e13dbd8de062a`. Its `Status` schema is deliberately
excluded because it describes the separately updated `Status.json`, not a
record from `Journal.*.log`. `Status.json` instead has its own
`StatusSnapshotObservation` transport path, which preserves the complete raw
snapshot independently of the journal catalogue. All journal classes are
typed transport records around exact raw evidence; selected NEW and CONTEXT
records additionally implement the researched model-presentation contract
without becoming commentary rules. The LLM subscriber consumes only completed
`ProjectedObservation` envelopes and applies the typed selection manifest.
Diagnostic-only and unknown events still reach diagnostics and the GUI, but
do not enter LLM batching, model input, or turn traces.

This is the current product-hypothesis implementation, not a claim that
historical hardening plans are complete. It includes optional Google Cloud TTS
output, but there is no speech recognition, microphone input, game control,
world model, long-term memory, provider failover, model discovery, or durable
broker.

## Configuration

The runtime reads the main JSON file selected by `--config=<path>` and a
mandatory `authentication.json` beside it. Copy the non-secret main example
before editing local values:

```sh
cp config/kairon.example.json config/kairon.json
```

On Windows PowerShell:

```powershell
Copy-Item config/kairon.example.json config/kairon.json
```

`config/kairon.json` and every file named `authentication.json` are ignored by
Git. Replace the selected source path and the selected provider's explicit
model ID.

Create `config/authentication.json` with the API keys needed by the configured
providers:

```json
{
  "llm": {
    "providers": {
      "mistral": {
        "apiKey": "replace-with-mistral-api-key"
      }
    }
  },
  "speech": {
    "googleCloudTts": {
      "apiKey": "replace-with-google-cloud-tts-api-key"
    }
  }
}
```

The profile names under `llm.providers` must exactly match profile names in
`kairon.json`. An unused key may be omitted. Keep the required objects and use
an empty `providers` object plus `googleCloudTts: null` when no key is needed.
The file is plaintext local secret material: protect it with operating-system
file permissions, never commit it, and never attach it to logs or bug reports.

`authentication.json` is the only credential source. Kairon reads no
environment variables for API keys, and never reads a key from
`kairon.example.json` or `kairon.json`. Keep every key out of the example file.

### Supported providers

Verified against the configuration parser, these are the only providers that
exist in this baseline:

| Purpose | Provider | Config `type` |
|---|---|---|
| Language model | LM Studio (local, OpenAI-compatible endpoint) | `LM_STUDIO` |
| Language model | Mistral (hosted API) | `MISTRAL` |
| Speech | Google Cloud Text-to-Speech | `GOOGLE_CLOUD_TTS` |

Exactly one LLM provider is active at a time, selected by `llm.activeProvider`.
There is no OpenAI, Anthropic, Ollama, or other provider in this baseline, and
no automatic discovery, routing, or failover between providers.

### Behavior graph

The `behaviorGraph` object in
[`config/kairon.example.json`](config/kairon.example.json) controls the
deterministic per-ship projection. It can be disabled without changing the
journal observer. When enabled:

- `storageDirectory` selects the local Jackson JSON store;
- `weightHalfLife` controls exponential time decay;
- `contextPriorStrength` smooths contextual predictions with the global
  transition distribution;
- `snapshotEverySignificantEvents` bounds unsaved live progress;
- `storeRawJournalPayload` must remain `false`.

The store is organized by commander FID and concrete `ShipID`. `graph.json`
contains aggregate nodes, edges, counters, episode metadata, and the current
cursor; exact active and completed episode paths are stored separately. The
journal and live `Status.json` file remain the sources of raw payload, so
episode documents contain only normalized attributes and compact context.

Journal replay and live journal publications use the same `ObservationBus`
subscriber and single-writer graph path. In live mode the adjacent
`Status.json` source publishes immutable snapshots through the bus after
journal bootstrap dispatch. The first known value of each tracked status field
is a baseline only and never fabricates a transition. A derived change becomes
a graph occurrence only while a concrete ship episode is active. Later
changes use Frontier's documented
`GuiFocus = 9` for FSS, `GuiFocus = 10` for SAA, and `Flags` bit 2
(`0x00000004`) for deployed landing gear.

The graph stores a subscriber-owned `episodeSequence` on every occurrence.
This is the total order in which journal and status-derived occurrences were
accepted into the active episode; source-specific byte offsets and status
positions remain identity evidence and are not compared as though they shared
one sequence domain.

For a deterministic journal-only rebuild, use replay with an empty configured
graph directory. A Journal file contains no historical `Status.json`
snapshots, so replay cannot reconstruct the six status-derived transitions.
Normal live startup publishes only the existing bounded journal bootstrap
suffix, then establishes a status baseline independently of graph identity. It
does not infer missing identity from the graph directory. There is no
graph-only production replay command; the normal `--config` replay command
runs the complete configured application, including the LLM observer.

`BehaviorGraphQueryService` exposes programmatic summaries, exact
occurrences, outgoing edges, and next-event predictions.
`BehaviorGraphExporter` exposes deterministic aggregate JSON, chronological
episode JSON, and Graphviz DOT strings. The desktop `Behavior Graph` tab uses
an immutable aggregate query snapshot plus on-demand read-only occurrence
queries. Node and edge topology and edge weights remain global for the active
ship, while each node label shows only the occurrence count from the currently
active `SystemEpisode`; global nodes not yet visited in that episode show zero.
`EventTypeNode.rawOccurrenceCount` is retained as a historical diagnostic
across all episodes and is not used as the active-system UI count. Weighted
directed edges use a deterministic left-to-right layout, and the current cursor
node is highlighted. The tab supports scrolling, background drag panning,
debounced live updates, and current-node centering when selected. A structural
node can be selected independently of the cursor; clicking it opens one
reusable modeless `Event Occurrences` dialog. The full tab area remains
available to the graph, while the dialog lists only matching occurrences in
the active episode and formats the selected occurrence's identity, normalized
attributes, and context read-only.

When deterministic occurrence IDs from a paced replay already exist in the
store, the service projects that episode progressively as the same observations
arrive again. This process-local projection advances active counts,
`GraphCursor`, and graph update events without incrementing historical node or
edge aggregates a second time. The normalized event-type and occurrence model
can also accept future commander-command sources, but no command subsystem is
implemented here. No historical episode browser or export CLI is included in
this version.

The graph records researched operational milestones for docking
requests/grants, limpet launches, interdictions, attack alerts, material
collection, and fuel scooping. `LaunchDrone.Type` selects a function-specific
limpet node when known and falls back to `LIMPET_LAUNCHED` without rejecting a
new source value. Repeat-heavy `MaterialCollected`, `UnderAttack`, and
`FuelScoop` records use a deterministic consecutive-run projection: the first
record remains the representative occurrence, while an immediately continuing
run does not manufacture self-edge weight. No undocumented time threshold or
synthetic completion event is inferred.

See
[ADR-0011](docs/decisions/ADR-0011-BEHAVIOR-GRAPH.md).

### Desktop GUI

The main configuration contains:

```json
"ui": {
  "enabled": true,
  "maximumObservationRows": 1000,
  "maximumTurnRows": 200
}
```

When enabled, one tabbed Swing window shows the bounded incoming journal feed,
LLM turns, and the active ship's aggregate behavior graph. The journal view
includes `SILENT`, `COMMENT`, invalid output, model failures, raw responses,
latency, evidence, and terminal output status. The GUI receives all journal
event types and does not reuse the LLM selection profile.

Every journal row starts with `OBSERVER EFFECT = OCCURRED_ONLY`. This means
only that the immutable observation occurred and reached the GUI; it is not an
LLM role or importance judgment. When the LLM observer handles the same
publication, observer-owned lifecycle effects such as `NEW_QUEUED`,
`NEW_IN_FLIGHT`, or a turn-bound `NEW_PROCESSED` reach the hub through the
read-only `ObserverTurnListener` path. The GUI applies those reported effects
by observation identity. It never reruns `LlmJournalEventSelection`, derives a
role from an event name, or writes presentation state into the shared
`PublishedObservation`.

`SwingKaironGuiHub` is the single Swing ingress and EDT owner. GUI display is
monitoring, not successful comment delivery, and does not affect the previous
comments sent to the model. Set `ui.enabled` to `false` for non-interactive
execution. Row limits must be positive and bound retained presentation data.

See [ADR-0008](docs/decisions/ADR-0008-DESKTOP-GUI-HUB.md).

### Replay and live mode

`source.mode` selects one of two ways to feed the same pipeline:

- **`live`** — Kairon watches the journal directory the game writes to, plus the
  adjacent `Status.json`, while you play. Set `source.journalDirectory` to your
  own local journal folder; the path is yours and is never committed.
- **`replay`** — Kairon reads one recorded journal file named by
  `source.replayFile` and republishes it at one-times pacing.

Replay changes **pacing only**. Projection, batching, snapshots, prompt, model,
validation, output, and tracing are identical in both modes, so a replay is a
faithful rehearsal of live behavior — with the one documented exception that a
journal file contains no historical `Status.json` snapshots, so replay cannot
reconstruct the six status-derived transitions.

**No game data ships with this repository.** Specifically:

- No original journal files are included. Replay needs a journal you supply
  yourself, from your own game installation.
- No private replay traces, turn traces, or behavior-graph data are included.
- The only journal-shaped files in the repository are the small synthetic
  fixtures under `src/test/resources/`, which use a synthetic Commander
  identity and hand-written system names.
- The full-journal replay regression is **opt-in**. It runs only when you pass
  private system properties naming a local journal and a local reference trace.
  Public CI never supplies them, so it is always skipped there.

### Replay evaluation

`source.mode = "replay"` selects the test-only paced replay behavior. There is
no separate pacing flag, speed multiplier, or configurable delay cap. The
first complete valid record is published immediately. For each later valid
record, Kairon preserves a positive timestamp gap from the previous
successfully published record at one-times speed, capped at ten seconds.
Missing, invalid, equal, or backward timestamps produce no wait.

After every successful publication, that record's optional timestamp becomes
the next baseline. A missing or invalid timestamp therefore resets the
baseline and also makes the following record immediate. Waiting occurs only on
the replay-source worker and can be interrupted by closing the desktop window
or shutting down the replay; live journal polling is unchanged.

The shared observation, diagnostics, and GUI details preserve the original
`rawJson` and `sourceTime`. `observedAt` is the actual publication time, and
the GUI uses it as the primary time for replay rows. The model turn uses each
NEW trigger's immutable source timestamp and event-owned presentation without
copying raw JSON. The turn trace stores the exact compact situation JSON and
the exact two-message model input.

See [ADR-0009](docs/decisions/ADR-0009-PACED-REPLAY.md).

The first controlled paced replay of 100 observations with
`mistral-small-2603` classified 22 observations as `NEW_ELIGIBLE`, five as
`CONTEXT_ONLY`, and 73 as `DIAGNOSTIC_ONLY`. The 22 eligible observations
produced 21 model turns: 18 `SILENT` and three `COMMENT`. Two comments were
unique because one comment was an exact duplicate of an earlier delivered
comment. The model also made qualitative claims equivalent to “high sulphur
content” and “high yttrium content”. That historical model input contained raw
JSON but did not explain the relevant field semantics or supply a comparison
baseline. The result therefore identifies an application-input gap before it
can be used as evidence of a model grounding failure.

That trace is the baseline from the preceding raw-JSON `BALANCED-103` path.
The subsequent `BALANCED-108` and `CONTEXT-5` run of the same 100 observations
produced 28 model turns: 19 delivered comments and nine `SILENT` decisions.
Its trace exposed repeated comments about the same biological signals and
generic route suggestions, while also showing that correlated `Scan` data lost
surface temperature and gravity in the old model presentation. The active
`BALANCED-109` profile adds the one `ApproachBody` record in that source, so its
routing classification is 32 NEW candidates, five context candidates, and 63
other observations. A controlled provider run of this revised path remains to
be performed.

Exact normalized repeats of one of the last three delivered comments are
rejected locally as `DUPLICATE_PREVIOUS_COMMENT`. Strongly overlapping lexical
near-repeats are rejected conservatively as
`NEAR_DUPLICATE_PREVIOUS_COMMENT`. Both receive terminal validation status
`INVALID` and are not delivered; broader meaning-level novelty remains the
LLM's responsibility.

Before attributing any surprising result to an LLM, inspect the exact traced
input and verify that the necessary game/API semantics, relationships,
terminology, comparison baseline, and clear instruction were actually
supplied. Missing knowledge is first an application presentation defect. This
project rule is normative in
[ADR-0010](docs/decisions/ADR-0010-MODEL-FACING-EVENT-VERBALIZATION.md).

`Music` occurred 17 times in that replay. It remained
`DIAGNOSTIC_ONLY`, had no LLM subscription, and started no turn, while all 17
raw observations still traversed `ObservationBus` and were available to the
desktop feed as `OCCURRED_ONLY`. These results are local evaluation evidence,
not an acceptance fixture or a reason for GUI-side semantic filtering.

### LM Studio

Set `llm.activeProvider` to `lm-studio`, keep the profile type as
`LM_STUDIO`, and replace `model` with the exact model identifier loaded by LM
Studio. The initial endpoint is `http://localhost:1234/v1`. With
no matching `lm-studio` entry in `authentication.json`, Kairon sends no
Authorization header. If the local server requires Bearer authentication, add
an `lm-studio` entry with the same `{"apiKey":"..."}` shape.

A Mistral-family model running locally through LM Studio still uses the
`LM_STUDIO` provider type because the provider type describes the transport
endpoint, not the model family.

### Mistral

Set `llm.activeProvider` to `mistral`, keep the profile type as `MISTRAL`,
keep the initial endpoint `https://api.mistral.ai/v1` unless intentionally
overriding it, and replace `model` with an explicit Mistral model identifier.
Kairon will not discover or select a model automatically.

Add `llm.providers.mistral.apiKey` to the adjacent
`authentication.json`. An active `MISTRAL` profile cannot start without a
nonblank key there. Do not put the key in `kairon.json`, logs, traces, or
committed files.

### LLM request statistics

Every physical LLM call is measured by the provider-neutral
`LlmRequestStatistics` component. It writes one `LLM_REQUEST_STATISTICS`
line at `INFO` after each terminal call and one
`LLM_REQUEST_STATISTICS_SUMMARY` line when the instrumented client closes
after at least one call has completed.
The log includes the safe provider profile/type/model, success/failure/
cancellation counts, end-to-end latency and running averages, reported input,
cached-input, output, and total tokens, cache-hit percentage, and current plus
weighted-average end-to-end output tokens per second. When pricing is present,
the same record includes the exact configured per-million-token rates,
per-call cost estimate, cumulative estimate, and average priced-call estimate.

The rate is `outputTokens / elapsed LlmClient call time`. Because Phase 0 uses
a non-streaming Chat Completions request, it is an end-to-end application
measurement, not time-to-first-token or provider-only generation speed. If a
provider omits usage or cache details, Kairon logs those values as
`unavailable`; it does not guess. Mistral's documented omitted
`cached_tokens` value is treated as a cache miss (`0`) when prompt-token usage
is present, while an omitted LM Studio cache field remains unavailable.
If a 2xx response reports usage but has unusable assistant content, the call
is still counted as `FAILURE` with those normalized token/cost facts.
Cancellation propagates to the upstream HTTP future but is not a guarantee of
zero provider billing. The close summary is emitted only after the last
accepted measurement callback has written its call line. Closing first stops
admission of new instrumented calls, then closes the transport delegate; a
concurrent late `complete(...)` therefore cannot cross into a closing HTTP
client.

Optional explicit tariff data may be added to any provider profile:

```json
"pricing": {
  "currency": "USD",
  "inputPerMillionTokens": 0.15,
  "cachedInputPerMillionTokens": 0.015,
  "outputPerMillionTokens": 0.60
}
```

Use current rates for the configured model and account; the numbers above
only illustrate the shape and are not repository defaults. The three rates
must be non-negative, and `currency` must be an uppercase ISO 4217 currency
code. Keep `"pricing": null` when no trustworthy tariff is configured.
`estimatedCost` is then logged as `unavailable`. When input, cached-input,
and output usage plus pricing are available, Kairon computes the per-call
estimate and reports cumulative plus average estimated cost across priced
calls:

```text
((uncached input * input rate)
 + (cached input * cached-input rate)
 + (output * output rate)) / 1,000,000
```

This is a local estimate, not a provider invoice. A local LM Studio profile
does not automatically mean zero operating cost, and Kairon never infers
prices from provider type or model name. Statistics are operational log data:
they do not enter the semantic prompt, `ObservationBus`, or the aggregate turn
trace, and they never include prompts, model output, API keys, authorization
metadata, or raw provider exception messages.

The normative boundary and measurement semantics are recorded in
[ADR-0007](docs/decisions/ADR-0007-LLM-REQUEST-STATISTICS.md).

### Google Cloud Text-to-Speech

Speech is disabled in the repository example. With
`speech.enabled = false`, a validated `COMMENT` is printed by
`ConsoleCommentSink`, and `SILENT` produces no output.

To enable speech:

1. Select a Google Cloud project with billing enabled and enable the
   Text-to-Speech API:

   ```sh
   gcloud services enable texttospeech.googleapis.com
   ```

2. Create an API key in that project, apply the narrowest available API and
   application restrictions, and add it as
   `speech.googleCloudTts.apiKey` in the adjacent `authentication.json`. See
   Google's [API-key guidance](https://cloud.google.com/docs/authentication/api-keys).

3. In `config/kairon.json`, set `speech.enabled` to `true` and replace
   `replace-with-google-voice-name` with an explicit compatible voice, such as
   one selected manually from Google's
   [supported voices](https://docs.cloud.google.com/text-to-speech/docs/list-voices-and-types)
   page. Kairon does not list or choose voices automatically.

The initial implementation requires `GOOGLE_CLOUD_TTS` and `LINEAR16`.
`languageCode`, `voiceName`, rate, pitch, volume, timeout, and optional exact
Java Sound mixer name come from the main runtime JSON file. The Google API key
comes only from the adjacent authentication file. If
`outputDevice` is `null`, Java Sound uses its default output device.

Google Cloud TTS is a billed service. Synthesis is charged by submitted
characters after any applicable free allowance; review the current
[Google pricing](https://cloud.google.com/text-to-speech/pricing) before
enabling it.

When speech is enabled and `alsoPrintToConsole = true`, console and speech
outcomes are recorded separately. A comment enters the three-comment
model-facing memory only after local audio playback completes. Successful
synthesis by itself is not delivery, and a synthesis or playback failure does
not replay journal events or request another LLM decision.

`SpeechGateway` is the single programmatic speech entry. Its normal
`deliver(text)` path preserves the `CommentSink` contract. Callers that need
request-scoped control use `submit(SpeechRequest)`, retain the returned
`SpeechHandle`, and call `cancel()`, or cancel by the matching request ID.
Queued cancellation removes only that utterance; active cancellation stops the
current Google request or Java Sound playback and leaves the gateway reusable.
There is no Phase 0 CLI hotkey, urgency-based automatic interruption, or
barge-in.

## Run

LM Studio must already be running with the configured model loaded, or the
selected hosted provider must be reachable. Kairon does not launch or discover
models.

Run the selected live or replay mode:

```sh
./mvnw exec:java -Dexec.args="--config=config/kairon.json"
```

On Windows PowerShell:

```powershell
.\mvnw.cmd exec:java "-Dexec.args=--config=config/kairon.json"
```

With the GUI enabled, close the window to stop `live` mode after a final source
drain or to interrupt a pending paced-replay delay. A completed `replay` leaves
the populated window open until it is closed. Source, observer, speech, bus,
and trace shutdown runs outside the EDT.

With the GUI disabled, stop `live` mode with `Ctrl+C`; `replay` exits after the
source-exhaustion signal has flushed all queued turns. Aggregate model-turn
records, including non-secret console and speech outcomes, are written to the
configured `observer.traceFile`.

### Optional real-audio smoke test

The normal automated suite never calls Google. When Google credentials,
billing, an explicit voice, an audio device, and a working LLM provider are
available:

1. Keep a short replay journal in `source.replayFile`; recorded positive gaps
   run at one-times speed with the fixed ten-second cap.
2. Set `source.mode` to `replay`, configure the selected LLM, and enable
   speech as described above.
3. Run the normal command:

   ```sh
   ./mvnw exec:java -Dexec.args="--config=config/kairon.json"
   ```

4. For a turn that returns a valid `COMMENT`, verify that the exact comment is
   optionally printed, audible playback completes without overlap, and the
   trace contains `speechOutcome: "DELIVERED"` with synthesis/playback
   timestamps. A `SILENT` turn must create no Google request and no playback.

This smoke test can incur Google charges. Do not run it in CI or commit the
local configuration, `authentication.json`, audio, or traces.

## Privacy and security

Kairon reads your game journal, which is personal data: it identifies your
Commander, your ships, and everywhere you have flown.

### What this repository must never contain

The repository is deliberately kept free of personal and local material. None of
the following belongs in it, and `.gitignore` is written to keep them out:

- Commander FID or Commander name from a real account
- real journal files (`Journal.*.log`) and journal directories
- turn traces (`observer.traceFile` output)
- behavior graph persistence (`data/`)
- replay logs and evaluation output (`var/`)
- LLM API keys, `authentication.json`, and any credential file
- Google Cloud credentials or service-account JSON
- local configuration (`config/kairon.json`)
- absolute local paths
- any other personal game data

Test fixtures under `src/test/resources/` use a synthetic Commander identity on
purpose. If you contribute a fixture, do the same — never paste a real journal
excerpt.

### What you are responsible for protecting

Running Kairon produces local data that is yours to safeguard:

- **Journal data.** Kairon reads it; the directory stays under your control.
- **Credentials.** `authentication.json` is plaintext. Protect it with
  operating-system file permissions. Never commit it or attach it to a bug
  report.
- **LLM prompts and responses.** The decision document Kairon sends describes
  where you are and what you just did.
- **Turn traces.** These contain the exact model input and output, including
  that same journal-derived content.
- **Graph artifacts.** `data/behavior-graphs/` is keyed by your real Commander
  FID and ship ID.

### Data leaves your machine when you use a hosted provider

**Kairon is not private by default, and this project cannot make it so.** When
you select a hosted provider, the decision document — including journal-derived
facts about your session — is transmitted to that provider and handled under
*their* terms, retention policy, and jurisdiction. Kairon makes no claim about
what a provider does with it.

- Selecting `MISTRAL` sends model input to the Mistral API.
- Enabling speech sends the generated comment text to Google Cloud
  Text-to-Speech.
- Selecting `LM_STUDIO` against a local endpoint keeps model input on your own
  machine or network.

Review the terms of whichever provider you configure and decide for yourself
what is acceptable. If you want no data to leave your machine, use a local LM
Studio provider and leave speech disabled.

### Test suite

The automated suite makes no external calls. It never contacts an LLM provider
or Google, and requires no credentials. Public CI runs it with no secrets
configured.

## Known limitations

This is an observer baseline, not a finished product. Known gaps, verified
against the current code:

- **The model receives machine event kinds, not full semantic explanations.**
  Events reach the model under normalized names with structured fields. There
  is no per-event natural-language explanation layer, so the model must infer
  meaning from names, fields, and values.
- **`trajectory` has no demonstrated influence on comments.** The behavior
  graph contributes recent predecessors and predicted next events to the
  decision document, but no controlled evaluation has yet shown that this
  changes what the model says. Treat it as unproven.
- **No attention or salience policy exists.** Kairon deliberately does not rank
  events by importance, rarity, value, or danger before the model. Deciding what
  is worth commenting on is entirely the model's job. This is an architectural
  choice, but it means there is no deterministic backstop when the model
  misjudges.
- **No commands and no automation.** Kairon only observes and comments. There is
  no speech recognition, no microphone input, and no way to control the game.
- **The `UNDER_ATTACK` contract is known-invalid.** Repeated `UNDER_ATTACK`
  records are deduplicated by a consecutive-run projection in a way the target
  contract rejects. The test stating the intended behavior is present but
  `@Disabled`, pending an open product decision. It is one of the expected
  skipped tests.
- **The model can return schema-invalid JSON.** Responses are structurally
  validated; malformed or non-conforming output is rejected with a terminal
  `INVALID` status and nothing is delivered. This is handled, not prevented —
  a turn can be lost this way.
- **No provider failover.** Exactly one provider is active. If it is
  unreachable or returns an error, the turn fails; there is no retry against a
  second provider, no routing, and no discovery.
- **Comments are not guaranteed to be factually correct.** Output is
  model-generated commentary about a game session. It can be wrong, and it is
  not a source of truth for anything — in the game or outside it.
- **The full-journal regression is opt-in and not run in public CI.** It
  requires a private journal file and a private reference trace supplied through
  system properties. Without them it is skipped. Public CI never supplies them.

## License

The Kairon source code is licensed under the PolyForm Noncommercial
License 1.0.0. Commercial use requires a separate written license
from the copyright holder.

Documentation and media assets are licensed under the Creative Commons
Attribution-NonCommercial-ShareAlike 4.0 International License unless
otherwise stated.

See [`LICENSE`](LICENSE), [`NOTICE`](NOTICE), [`LICENSE-DOCS`](LICENSE-DOCS),
and [`LICENSES.md`](LICENSES.md).

Because the code license restricts commercial use, Kairon is
**source-available software for noncommercial use** and is not open source.

Third-party dependencies and referenced material keep their own licenses; see
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

Copyright 2026 Alex Gnevko.

Required Notice: Copyright 2026 Alex Gnevko (GitHub: Gnevko)

## Disclaimer

Kairon is an independent, unofficial project. It is not affiliated with,
endorsed by, or sponsored by Frontier Developments plc. Elite Dangerous and
related names are the property of their respective owners. This project is a
fan-made tool that reads files the game writes locally; it does not modify the
game, and it is not a product of, or supported by, the game's publisher.

## Documentation

- Normative boundaries: [Kairon Architecture](docs/KAIRON_ARCHITECTURE.md)
- Repository-evidenced status: [Current State](docs/CURRENT_STATE.md)
- Per-ship graph decision:
  [ADR-0011](docs/decisions/ADR-0011-BEHAVIOR-GRAPH.md)
- Decisions: [`docs/decisions/`](docs/decisions/)
- Historical, non-normative material: [`docs/archive/`](docs/archive/)
