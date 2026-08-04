# ADR-0008: Desktop GUI hub

## Status

Accepted and implemented for the initial Swing monitor.

## Context

Moving-window evaluation needs one place for incoming journal records and every
model outcome, including `SILENT`, invalid output, and model failure. Scattered
Swing controls would duplicate presentation and couple runtime threads to EDT.

`CommentSink` receives only deliverable comments, while LLM decisions cannot
be published as external facts through `ObservationBus`.

The raw observation feed must also distinguish “this occurred” from “the LLM
observer retained, queued, or used this observation”. The latter facts belong
to the observer and cannot be reconstructed reliably from event names in the
desktop layer. In the first controlled 100-observation replay, for example,
17 `Music` observations were valid raw telemetry but were
`DIAGNOSTIC_ONLY` and had no LLM subscription.

## Decision

Use one explicitly wired `KaironGuiHub` as the central presentation ingress.
It is a read-only monitor, not a service locator, global application bus,
semantic subsystem, or comment-delivery channel.

Journal observations reach the GUI through `DesktopUiSubscriber`, an
independent `ObservationBus` subscriber to `JournalEventObservation`. It
receives all valid known and unknown journal events and does not reuse the LLM
event-selection profile or inspect event names for importance.

Every newly presented observation defaults to
`OBSERVER EFFECT = OCCURRED_ONLY`. This is a GUI fallback meaning only that
the raw publication occurred. It is not an observer role, processing state,
importance classification, or assertion that the observer discarded the
observation.

Observer lifecycle effects and turns reach the GUI through the internal,
read-only, handoff-only `ObserverTurnListener`. The observer coordinator owns
and reports effects equivalent to:

- `CONTEXT_RETAINED` and turn-bound `CONTEXT_IN_TURN`;
- `NEW_QUEUED` and turn-bound `NEW_IN_FLIGHT`;
- turn-bound `NEW_PROCESSED` or `NEW_FAILED`;
- `NEW_DISCARDED`.

An effect identifies the immutable publication by `observationId` and
`busSequence`; turn-bound effects additionally carry the observer's turn
sequence and E01–E30 alias. The listener reports a resolved decision
immediately and the terminal output result later under the same process-local
turn sequence. It is not `ObservationBus` or a deferred `DomainEventBus`.

The GUI applies only effects explicitly reported by the observer. It must not
import or rerun `LlmJournalEventSelection`, inspect the raw `event` value to
derive an observer role, or recompute lifecycle state from table selection,
turn evidence, timestamps, or delivery results. Selecting a GUI row changes
presentation only.

Observer effects are not stored in or written back to
`PublishedObservation`. The shared publication remains immutable and free of
subscriber state, so diagnostics and every other subscriber continue to see
the same value.

The GUI may display exact raw journal JSON, raw model output, parsed status,
evidence aliases, latency, and delivery status. Displaying a comment is not
successful delivery and must not update previous-comment history. For
`REPLAY`, the observation table uses actual `observedAt` as its primary time
while details retain original `sourceTime` and raw JSON. Existing console and
speech delivery rules remain authoritative.

`SwingKaironGuiHub` owns EDT marshaling, a bounded coalesced ingress queue,
window lifecycle, and dropped-presentation diagnostics. Bus and observer
callbacks only enqueue immutable view data and return.

All Swing controls remain under `kairon.ui.swing`. `HudTheme` centralizes HUD
tokens and control construction, while one main window composes observation
and model-turn views. Future dialogs must use the same hub-owned theme and
shared composition instead of duplicating controls or using scattered
`JOptionPane` calls.

A window close request signals application lifecycle and interrupts any
pending paced-replay delay. Source drain, observer shutdown, network
cancellation, speech shutdown, and bus drain must occur off the EDT; the frame
is disposed after runtime closure.

## Consequences

- Live and replay expose the same observation and model-turn presentation
  paths.
- Raw events outside the LLM subscription, including the 17 `Music` events in
  the first-100 evaluation, remain visible as `OCCURRED_ONLY`.
- The desktop exposes observer lifecycle without becoming a second owner of
  event-selection or processing rules.
- The GUI can show `SILENT` before any speech playback and later update a
  comment with its terminal delivery result.
- A slow EDT or dropped presentation update cannot change source, bus, model,
  trace, or output semantics.
- Bounded retained rows and ingress protect long-running live sessions and
  paced replay.
- Disabling the GUI preserves non-interactive console/replay behavior.

## Rejected alternatives

- Creating Swing controls in journal, observer, LLM, speech, or trace classes.
- Publishing LLM decisions or comments through `ObservationBus`.
- Treating GUI display as successful comment delivery.
- Reusing the LLM event allowlist for the observation monitor.
- Recomputing observer selection or lifecycle from UI row selection, event
  names, prompt aliases, or model evidence.
- Mutating `PublishedObservation` to store GUI or observer effect state.
- Blocking `ObservationBus`, observer coordination, or the EDT on one another.

## Relevant implementation references

- [`KaironGuiHub.java`](../../src/main/java/kairon/ui/KaironGuiHub.java)
- [`DesktopUiSubscriber.java`](../../src/main/java/kairon/ui/DesktopUiSubscriber.java)
- [`DesktopObserverTurnListener.java`](../../src/main/java/kairon/ui/DesktopObserverTurnListener.java)
- [`ObserverTurnListener.java`](../../src/main/java/kairon/observer/ObserverTurnListener.java)
- [`ObserverTurnCoordinator.java`](../../src/main/java/kairon/observer/ObserverTurnCoordinator.java)
- [`SwingKaironGuiHub.java`](../../src/main/java/kairon/ui/swing/SwingKaironGuiHub.java)
- [`KaironHudWindow.java`](../../src/main/java/kairon/ui/swing/KaironHudWindow.java)
