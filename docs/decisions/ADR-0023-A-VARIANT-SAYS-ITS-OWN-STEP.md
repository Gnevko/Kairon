# ADR-0023: Every statement in a request is a sentence

## Status

Accepted and implemented.

Not behaviour-preserving, and deliberately so.
`target/model-facing-baseline.json` moves by 48 lines and 50 537 → 53 872 bytes:
4 sampling events naming the step they are, 30 `trajectory.recent` arrays and 14
`likelyNext` entries. Nothing else changes.

## Context

[ADR-0022](ADR-0022-VARIANT-DISPATCH-IN-THE-PARSER.md) split five journal records
into one class per domain event and deliberately kept every variant's sentence
identical to its record's, so the structural change could be proved byte-for-byte
against the replay. That left the sentences saying less than the classes now
know.

Three records were affected, and each said something different about the gap.

`ScanOrganic` is four classes that all said *"The organic sampling tool was used
on an organic discovery."* The step reached the model only as `stage: "START"`
and `complete: false` — a second vocabulary, and one the projector already
refuses to write twice: it drops the record's own `scanType` for exactly that
reason, noting that the raw spelling was what the model twice misread as a
discovery.

`EngineerLegacyConvert.Unrecognised` is the class for a record whose
`IsPreview` is missing, and it said *"A legacy engineered module was converted to
the current format."* — a conversion, asserted of a record that does not say
whether it was one. The claim predates the split (it was an `orElse(false)`) and
survived it as a written-down constant, which made it honest about being a
decision without making it a defensible one.

`LaunchDrone` is nine classes sharing *"A drone or limpet was launched."* Its
description reaches no model today — the type is `DIAGNOSTIC_ONLY` — but the
behaviour graph has nine structural types for it and the trajectory vocabulary
has nine names, so the moment the trajectory speaks in sentences, nine classes
with one sentence lose eight distinctions the model gets now.

The trajectory was the other half of the same defect.
[ADR-0010](ADR-0010-MODEL-FACING-EVENT-VERBALIZATION.md) established that a name
only this process shares is not an answer to what happened, and removed it from
`events[*].kind`. `trajectory.recent` kept carrying `"SYSTEM_ENTERED"` and
`likelyNext` kept carrying `{"kind": "BODY_SIGNALS_FOUND"}` — in 34 of the 46
baseline turns — because a remembered event has no journal payload to ask, and
what it could have been compared against was another identifier.

## Decision

### Each variant states its own step, as a constant on its own class

- `ScanOrganic.Logged` logged the first scan of an unfinished sequence;
  `Sampled` recorded a subsequent scan of an unfinished sequence; `Analysed`
  recorded the final scan and completed a sequence; `Unrecognised` was used at an
  unidentified step. The wording follows the researched `llmPresentation()`
  sentences, which were checked against the Frontier manual.
- `EngineerLegacyConvert.Unrecognised` reports a conversion **or** a preview
  without saying which — which is the one thing the variant exists to say.
- Each `LaunchDrone` limpet names its own kind; `Unspecified` keeps the sentence
  that says a launch happened and the journal did not name the limpet, renamed
  from `SHARED_DESCRIPTION` to `UNSPECIFIED_DESCRIPTION` because it is no longer
  shared.

No value is interpolated: every sentence is a constant, and
`JournalEventVariantContractTest` still holds — one class, one description.

### The trajectory says the same sentences the events say

`DecisionTrajectoryNames` becomes `DecisionTrajectoryDescriptions` and holds
sentences. `likelyNext[].kind` becomes `likelyNext[].event`, so both halves of
the trajectory and the events themselves use one word for one thing.

Where a type is produced by a journal class, the sentence **is** that class's
`modelFacingDescription()`, and `DecisionTrajectoryTest` parses a minimal record
of each and compares. That is what the variant split bought: while one class
could mean several things, its sentence was not a constant and there was nothing
to compare against but another identifier.

A graph vertex the model can be shown has to be able to say what it is, so the
two records that could describe themselves and did not now do:
`StartJump` (both charges) and `FSSDiscoveryScan` sign the presentation contract.
Neither becomes model-eligible by signing — eligibility is the source-role
catalogue's answer and is unchanged, exactly as `LaunchDrone` is presentable and
`DIAGNOSTIC_ONLY` — and neither reaches `events`. What changes is where their
sentence lives: on the class, where the contract test compares it, instead of in
the table where nothing could.

Six of the 54 types are left with an authored sentence, and all six are
Status-derived: the two scanner modes and the landing gear. No journal record
exists for them at all, so there is nothing that could be asked. Moving them
would mean stopping the graph from recording Status-derived occurrences, which
is a different decision with its own consequences for the trajectory and the
predictions.

**A prediction reads the same past-tense sentence as a memory of the same
event.** What has not happened is said by the field it sits in — `likelyNext`,
with a probability beside it — and the system prompt already says so outright
("It has not happened. Never say or imply that a predicted event has occurred"),
so no prompt change was needed. The alternative was a second set of 54 sentences
in a forward tense, which is a second vocabulary to keep in step with the first —
the shape this whole change exists to remove.

One consequence is a real change rather than a rewording: the two scanners used
to share the remembered name `BODY_SIGNALS_FOUND`, on the grounds that which
instrument found the signals was Kairon's bookkeeping. Their events have always
described their own instrument, so sharing one sentence would have made the
trajectory the one place in the request where a finding loses its source. Each
now says which instrument reported it. The kind they share is unchanged.

## Consequences

`stage` and `complete` are now redundant for sampling and are **deliberately
still sent**. The event reads *"…logged the first scan of an unfinished sampling
sequence."* beside `"stage": "START", "complete": false`, which is the same
distinction under a second name. The argument for keeping them is that a
structured field is easier to key on than prose, and the redundancy is the price;
the argument against is the projector's own rule, which drops the record's
`scanType` because "a second name for the same distinction is the same fact
twice". Kept as a decision rather than an oversight. Removing them later needs
either a narrower claim on `DecisionEventRule` — `wholeAction` bundles a second
one and suppresses only the stage, not the completion — or three kinds where
there is now one kind and a stage.

The trajectory costs more characters: about 72 bytes a turn across the measured
replay, against a 16 000-character budget it is not part of the ladder for. Six
items at most, so it still cannot be why a turn overflows.

What this does not change: the system prompt, the model-facing schema, the event
kinds, the admitted types, the graph and its probabilities, which occurrences
reach the trajectory, and the provider and speech paths.
