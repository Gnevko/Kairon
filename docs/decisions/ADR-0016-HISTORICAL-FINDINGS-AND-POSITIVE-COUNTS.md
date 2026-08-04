# ADR-0016: A finding belongs to the observation that was heard, and a zero is not a retraction

## Status

Accepted and implemented. Amends
[ADR-0014](ADR-0014-SESSION-RESTORE-AND-SCANNER-RESULTS.md) on scanner-result
admission and supersedes two consequences of
[ADR-0015](ADR-0015-CURRENT-CHANGES-AND-VISIT-SCOPED-FINDINGS.md): a `BOOTSTRAP`
scanner result no longer creates a graph occurrence, and an explicitly reported
`Count: 0` no longer replaces a known count. No persistence format changes, no
selection-profile changes, and no migration is provided.

## Context

Two defects survived the ADR-0015 implementation, and both are about a finding
being credited to the wrong reading.

**The occurrence and the event stopped belonging to the same observation.**
ADR-0015 moved the `BOOTSTRAP` check ahead of the observer's novelty memory, so a
historical result no longer silences the live reading that repeats it. The graph
kept recording historical results, and the two halves then disagreed:

```
BOOTSTRAP SAASignalsFound(BIO=1)  -> occurrence SAA_SIGNALS_FOUND, no event
LIVE      FSSBodySignals(BIO=1)   -> no occurrence, event BODY_SIGNALS_FOUND
```

The model was given an event with no occurrence of its own, standing in a
trajectory whose predecessor was `BODY_SIGNALS_FOUND` — the same finding, told
once as history and once as news. ADR-0015 called this "one finding, one
occurrence, one event — recorded at bootstrap, reported when live" and accepted
it. It is not acceptable: the trajectory is what the model reads as *what came
before this*, and there is nothing before a finding that is the finding.

**A zero was read as a disappearance.** `normalizedSignalCounts` kept an
explicitly reported count of zero, and the canonical merge let it overwrite a
known positive count. A surface survey reporting `Biological: 0` therefore erased
a biological count the system scan had established. ADR-0015 recorded this as
deliberate — "an instrument answering 'none' is an answer" — and it is the wrong
reading of the source. The game reports a signal by counting it. It has no
record that asserts absence, and one instrument listing a heading at zero is not
that record.

## Decision

### Historical scanner results are canonical-only

`BodySurveySelectionPolicy` refuses `BODY_SCANNED`, `FSS_BODY_SIGNALS_FOUND` and
`SAA_SIGNALS_FOUND` when the observation's capture mode is `BOOTSTRAP`. It
refuses before `recordNormalizedOccurrence`, so no occurrence is created and
later deleted, no node count moves, no transition is minted and the cursor stays
where it was. The capture mode is the typed `ObservationCaptureMode` already on
`PublishedObservation`, passed as an argument — never inferred from a timestamp,
a file name or anything else.

Canonical state is untouched by this: `ObservationProjectionCoordinator` projects
current state before the graph is consulted, so a historical reading still
restores the body facts and the positive signal counts it established. What it
does not do is claim to be a finding.

The rule is deliberately narrow. These three records are the only ones whose
recording decides whether a *later* observation is a finding at all; every other
structural type, `SAA_SCAN_COMPLETE` included, is recorded on bootstrap exactly
as before. The general contract stands: bootstrap is model-silent, and now, for
these three records only, it is graph-silent too — so the occurrence and the
event either both belong to the live observation or neither exists.

### A count below one establishes nothing and retracts nothing

`BodySurveyFacts.normalizedSignalCounts` keeps only counts above zero, and it is
the single definition of a normalized signal set: the signature the graph
deduplicates on is built from it, the observer's novelty memory compares the same
signature, the canonical merge applies the same map, and the model-facing
`signals` array shares the one threshold. There is no path on which a fourth,
different set could appear.

A reading whose positive set is empty is not a finding: no signature, so no
occurrence, no trigger and no turn — and, since the merge only ever adds or
raises, nothing cleared. A mixed reading is judged on its positive half alone:
`Biological: 0, Geological: 2` is the geological finding, the model is shown
`GEOLOGICAL 2` and never `BIOLOGICAL 0`, and the biological count an earlier
reading established stands and appears as context.

Retraction semantics are not introduced. Removing a known count would need a
source that actually asserts absence; when one is identified it is a separate
product decision with its own evidence.

## Consequences

- A `BOOTSTRAP` scanner result costs one canonical merge and nothing else. The
  graph learns exploration behaviour from the session it was watching, which is
  the only session in which the order of the Commander's actions was observed.
- Replaying a journal Kairon has already bootstrapped now records the scanner
  results once, not twice under two capture modes.
- Live and bootstrapped graphs are not comparable on `BODY_SCANNED`,
  `FSS_BODY_SIGNALS_FOUND` and `SAA_SIGNALS_FOUND`, in the same way they are
  already not comparable on the six Status-derived types.
- A count can go up and never down. If a future source does assert absence, this
  is the decision it has to reopen.
- No migration and no backward compatibility. Existing development graph data
  must be cleared or replaced before the next replay verification.
