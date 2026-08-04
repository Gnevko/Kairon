# ADR-0009: Paced replay for model evaluation

## Status

Accepted and implemented.

## Context

Immediate publication of an entire recorded journal does not resemble the
live arrival cadence. It can collapse activity into artificial batches and
make the desktop monitor difficult to follow.

Recorded timestamps remain valuable facts, but their old dates can mislead a
current evaluation; mutating the shared raw observation is also unacceptable.

## Decision

`source.mode = "replay"` is Kairon's test-only paced replay mode. There is no
second replay mode, speed multiplier, or replay-pacing configuration property.

The first complete valid record is immediate. A later record has a delay only
when it and the previous successfully published record have valid timestamps
and the current timestamp is later:

```text
delay = min(current sourceTime - previous sourceTime, 10 seconds)
```

A missing timestamp, invalid timestamp, equal timestamp, or backward timestamp
produces zero delay. After every successful publication, the baseline becomes
that record's optional timestamp. Missing or invalid time clears the baseline;
the next record is immediate unless both consecutive records again have valid
increasing times.

The delay runs only on the replay-source execution context. It is interruptible
by replay shutdown, including a desktop-window close, and never blocks
`ObservationBus`, the observer coordinator, the EDT, or the live source.

The shared replay observation preserves its exact source `rawJson` and original
`sourceTime`. Its `observedAt` is the actual clock time immediately before
publication after any delay.

For model input, `ObserverPromptFactory` renders model-facing time beside the
event-owned factual presentation. A `REPLAY` event with a valid source
timestamp uses the observation's `observedAt`; a replay record without valid
source time does not acquire an invented timestamp. `LIVE` and `BOOTSTRAP`
use valid `sourceTime`, falling back to `observedAt` only when source time is
unavailable. No raw journal value is mutated.

The desktop observation table uses `observedAt` as the primary time for
`REPLAY`. It continues to expose original `sourceTime` and exact raw JSON in
details. Live presentation keeps its existing source-time behavior.

## Consequences

- Replay exercises the existing bus, correlation, batching, prompt, model,
  validation, output, GUI, and trace path at a more live-like cadence.
- One recorded gap can delay replay by at most ten seconds, while shorter
  positive gaps retain their original one-times duration.
- Diagnostics and subscribers continue to receive immutable original source
  facts; only the centrally rendered model-facing time is current.
- The aggregate trace's exact model input records the rendered time.
  Its event binding retains original `sourceTime`, actual `observedAt`, and
  whether the model timestamp was rebased.
- Replay close must cancel a pending delay deterministically before runtime
  shutdown proceeds.

## Rejected alternatives

- Keeping immediate replay as the only evaluation behavior.
- Adding configurable speeds, caps, or a second replay mode.
- Delaying dispatch on `ObservationBus` or changing live polling.
- Mutating `RawJournalData`, `sourceTime`, or the configured replay file.
- Rendering replay model time in the journal adapter or LLM client.

## Relevant implementation references

- [`JournalReplaySource.java`](../../src/main/java/kairon/observation/journal/JournalReplaySource.java)
- [`ObserverPromptFactory.java`](../../src/main/java/kairon/llm/ObserverPromptFactory.java)
- [`KaironHudWindow.java`](../../src/main/java/kairon/ui/swing/KaironHudWindow.java)
- [`JournalSourceTest.java`](../../src/test/java/kairon/observation/journal/JournalSourceTest.java)
- [ADR-0010](ADR-0010-MODEL-FACING-EVENT-VERBALIZATION.md)
