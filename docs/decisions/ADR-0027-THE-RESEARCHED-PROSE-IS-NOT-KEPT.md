# ADR-0027: The researched prose is not kept

## Status

Accepted and implemented. Supersedes the retention clause of
[ADR-0010](ADR-0010-MODEL-FACING-EVENT-VERBALIZATION.md) as amended by
[ADR-0013](ADR-0013-LLM-DECISION-INTERFACE.md). Everything else both decide
stands: an event's meaning is still owned by the event, the model still receives
one constant sentence per class through `modelFacingDescription()`, and the
evidence-first evaluation rule of ADR-0010 is untouched.

## Context

ADR-0010 gave every researched journal record a second method,
`llmPresentation()`, which rendered that record's own field values as English
prose — the body's name in quotes, the counts spelled out, the facts joined with
"and". It was the model's input at the time.

ADR-0013 replaced it: the provider now receives a structured document, and the
consequence section said

> ADR-0010's `llmPresentation()` remains the authority for diagnostics, the GUI
> and the observation corpus; it is simply no longer model input.

**That sentence described an intention, not the code.** Measured on 2026-08-08:

| question | answer |
| --- | --- |
| call sites of `llmPresentation()` in `src/main/java` | **0** |
| call sites in `src/test/java` | 139, in 2 classes |
| does the observation corpus read it | no — it writes raw journal JSON |
| does the desktop GUI read it | no — no reference anywhere in `kairon.ui` |
| does the turn trace read it | no — the trace stores the serialized request |

`docs/CURRENT_STATE.md` had already recorded the fact — "`llmPresentation()` is
untouched and is still not called in production" — while the ADR went on
claiming three readers. The repository was honest in one place and stale in the
other for as long as both were true at once.

What it cost to keep:

| | files | lines |
| --- | --- | --- |
| implementations | 121 records | ~6 800 |
| the record, the abstract method and five helpers that only served them | 1 interface | ~120 |
| tests | 2 classes | 2 701 |

The tests are the sharpest part of it. `JournalEventLlmPresentationTest` was 127
hand-written cases, one per record, 2 574 lines — **14% of the whole suite** —
and not one of them touched `modelFacingDescription()`. The live sentence is
covered by `ModelFacingDescriptionContractTest`: 11 cases, driven off
`JournalEventCatalog`, which cover every record there is and keep covering a
record added tomorrow.

## Decision

Remove `llmPresentation()`, the `LlmEventPresentation` record, all 121
implementations, the private helpers that existed only to build them, the five
interface statics that existed only to phrase them, and both test classes.

Keep:

- `modelFacingDescription()` — one constant sentence per class, unchanged;
- `LlmPresentableJournalEvent` itself, which is still what "this record has been
  researched" means, and is still the type selection and projection ask for;
- `displayText`, `textual`, `booleanValue`, `normalizeInlineText` — the four
  helpers with real callers: the parser dispatches on a flag, the current-system
  registry reads an organism label;
- `RawJournalData` as the authoritative observation. It always was.

`SemanticAdapterRegistryTest.adaptersNeverParseRenderedPresentation` is retired
in place, with the reason written where it stood: it asserted that no adapter
took or returned an `LlmEventPresentation`, and a guard against a type that no
longer exists cannot fail.

## Consequences

- **The research is not lost.** What each record's fields mean is encoded in
  `kairon.semantics` — the adapters read the same source fields, and unlike the
  prose they are read by the runtime. The pinned `jixxed/ed-journal-schemas`
  revision and the Frontier manual references in each record's javadoc are
  unchanged. The prose itself remains in git history.
- **A rendering is now built where it is read, or not at all.** If diagnostics
  or the GUI ever wants prose, it will be written against what that reader
  needs, and its reader will be the thing that keeps it true.
- **The suite lost 133 tests and no coverage**: 880 → 747, with the model-facing
  surface covered by more of the catalogue than before, not less.
- **A contract with no reader is not a contract.** The test that reads it is not
  a reader — it is the thing that makes an unused surface look maintained. This
  is the rule the next such method is measured against.
