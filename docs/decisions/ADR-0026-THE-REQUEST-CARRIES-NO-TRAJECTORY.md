# ADR-0026: The request carries no trajectory

## Status

Accepted and implemented. Supersedes the model-facing half of
[ADR-0013](ADR-0013-LLM-DECISION-INTERFACE.md) and of
[ADR-0023](ADR-0023-A-VARIANT-SAYS-ITS-OWN-STEP.md): both remain in force for
everything else they decide, and neither's vocabulary rule has anything left to
govern in the request.

The behaviour graph itself is untouched. It still records episodes,
occurrences, transitions and a cursor, still calculates predictions, and still
answers `occurrenceOnBody` — which is now the whole of what it says to the
model.

## Context

`trajectory` was the one part of `LlmDecisionRequest` built from Kairon's memory
of the visit rather than from the observations in hand. Two halves:

- `recent` — up to three domain-named predecessors from the active episode,
  oldest first;
- `likelyNext` — up to three predictions with the transition model's own
  probability.

Both were carefully built. Neither was used.

**Measured, across every live run this repository has recorded:** not one
delivered comment rested on a predecessor or on a forecast. The negative result
is recorded in the prompt work of 2026-08-06 — under the long prompt, seventeen
comments in a measured session were seventeen captions of the triggering event,
with nothing leaning on the run of events; adding explicit permission to use the
trajectory changed nothing, and *requiring* it manufactured a claim the request
could not support.

Meanwhile the forecast could mislead, and did. From the 2026-08-06 replay:

- **A stage that had already passed.** The graph predicts by structural type and
  the three organic sampling scans are three types, so `SCAN_ORGANIC_LOG` — "the
  organic sampling tool logged the *first* scan of an unfinished sampling
  sequence" — was predicted in the middle of a sequence the same request
  described as running. What actually followed was the second scan.
- **Certainty from a single observation.** The probability is a normalised share
  over the outgoing edges, so the only edge out of a type seen once is `1.0`.
  Run against a deleted graph, the session's first two predictions were both
  `1.0`, each standing on one landing five minutes earlier. The count behind a
  share is deliberately never sent, so nothing in the document told the model
  the difference between that and a habit.

Both were narrowed rather than removed at first — a minimum of two observations,
and no opening step while its sequence runs. That left a section that costs
budget and attention in every turn, is right about the past, occasionally wrong
about the future, and demonstrably read by nobody.

## Decision

**Remove `trajectory` from the model-facing request entirely.** No `recent`, no
`likelyNext`, no `Prediction`, no section name, no compaction rung, no
vocabulary of past-tense sentences for normalized event types.

Removed with it:

- `LlmDecisionRequest.Trajectory` and `LlmDecisionRequest.Prediction`;
- `DecisionTrajectoryProjector`, including `TRAJECTORY_INDEPENDENT_KINDS` —
  a rule that existed to withhold a trajectory from a chat message has nothing
  to withhold;
- `DecisionTrajectoryDescriptions`, the type-to-sentence map ADR-0023 required
  to speak the same sentences the events do;
- `DecisionSections.TRAJECTORY` and the serializer's trajectory writer.

What the graph still sends: `occurrenceOnBody`, scoped to
`(systemAddress, bodyId)` within the active episode.

## Consequences

**The request is shorter and says only what was observed.** Every turn of the
measured replay carried between 40 and 200 characters of trajectory; the largest
was a landing whose 828-character document was mostly the run of events leading
to it, and whose answer was `SILENT`.

**Kairon can no longer tell the model that something has happened before.** A
repeat is now visible only through `occurrenceOnBody` and through the standing
facts in `context`. That is the intended trade: the document states what is,
and the model decides what is worth saying about it.

**What replaces it is not another view of the graph.** The problem the
trajectory was reached for — Kairon repeating herself across turns — is a
problem about what she has *said*, not about where the ship has been. A memory
of delivered comments is a different contract and is not decided here. Note that
showing her the last comments verbatim is already known to fail: she paraphrases
them back. The direction recorded for later is to withhold a fact when it is not
needed rather than to add a section that says it was mentioned.

**Reintroducing it needs a new decision and a new measurement**, not a revert.
The two failure modes above are properties of predicting by structural type from
a sparse graph, and neither is fixed by making the section smaller.
