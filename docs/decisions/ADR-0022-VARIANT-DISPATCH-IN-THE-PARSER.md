# ADR-0022: One class, one domain event

## Status

Accepted and implemented. Behaviour-preserving: the model-facing JSON, the graph
occurrences, the trigger selection profile and every observable output are
unchanged, and `target/model-facing-baseline.json` is byte-identical (50 537
bytes) before and after.

## Context

`modelFacingDescription()` is a domain assertion, and it was declared on a
transport identity. `CLAUDE.md` says a typed journal record is a transport
identity around exact `RawJournalData` — not a domain model, conferring no
semantic importance — and the class was nevertheless being asked what domain
event it is.

A wire event name is not a unit of meaning. `ScanOrganic` is one name and four
domain events: logging the first scan of a sampling sequence, taking a further
sample and completing the sequence are three different things to have happened,
plus a step this build does not recognise. `LaunchDrone` is nine, `StartJump`
three, `EngineerLegacyConvert` three, and a `Scan` is two — a reading of what a
body is, and the only record in the journal that ever says nobody had discovered
this star.

The consequence was that every layer needing to tell them apart rediscovered the
discriminator, with its own predicate and its own granularity: the behaviour
normalizer with a `switch` per record, the decision catalogue with a predicate
over the record's fields, the description with a ternary of its own. Where
somebody had noticed and wired them together by hand — `Scan`, whose ternary
delegated to the same predicate the projection read — the layers agreed. Where
nobody had, they silently disagreed: `EngineerLegacyConvert` distinguished a
preview from a conversion in its sentence and nowhere else, and no test could
catch it, because each layer's own tests were green about its own answer.

One thing made the split look expensive. Class-keyed registries answer two
different questions, and a first attempt that listed every variant everywhere
cascaded into 43 test classes:

| question | registries | key |
|---|---|---|
| what kind of journal event is this — source role, structural significance, semantic adapter | `SemanticSourceRoleCatalog`, `EventSignificancePolicy`, `SemanticAdapterRegistry` | the **wire record**, decided once when the event was researched |
| which domain event is this | `BehaviorEventNormalizer`, `DecisionEventCatalog` | the **variant** |

One research answer does not become nine because the parser learned to dispatch.

## Decision

### The parser dispatches, and it is the only thing that does

A record whose wire event carries more than one domain event becomes a sealed
interface named after the wire event — the mirror of `jixxed/ed-journal-schemas`
is kept — with one nested record per domain event and a single static
`of(RawJournalData)` factory registered in `JournalEventCatalog`. Five records
are split: `ScanOrganic` (4 variants), `StartJump` (3),
`EngineerLegacyConvert` (3), `LaunchDrone` (9), `Scan` (2).

Nothing downstream dispatches again. The behaviour normalizer's per-record
`switch` branches are gone and its only remaining `instanceof` is the `FSDJump`
boundary guard, which is a different contract.

### An unrecognised discriminator is a runtime condition, not a defect

`UnrecognisedEventVariant` marks a variant whose discriminator this build does
not know. Frontier adds values to these vocabularies, so every split record that
dispatches on one has such a variant. It keeps its record's attribute list — it
is the same record — and never borrows a researched type: the normalizer gives it
`NormalizedEventType.unknown(originalEventName)`. `Scan` has no such variant,
because its discriminator is a shape the record either has or does not rather
than a vocabulary that can grow.

### Two questions, two keys, one lookup

`kairon.observation.journal.JournalEventLookup` answers a class-keyed registry
for a variant through the record it belongs to: exact match first, then one level
of declared interfaces. The registries that ask what kind of journal event this
is keep the one key they always had, so splitting a record moves no counter and
requires no re-review.

One level is deliberate. A variant's direct interface is the sealed group naming
its wire event; a deeper walk would start matching `LlmPresentableJournalEvent`
and turn a missing registration into a silent framework-wide default.

### A profile name is not a review count

`BALANCED-112`/`CONTEXT-2` become `BALANCED`/`CONTEXT`. The number pinned how
many wire types had been researched into the identity of the profile, and class
initialisation refused to start unless the list was exactly that long. It stopped
being answerable once one wire type could dispatch to several classes: a record
carrying three domain events is one researched wire type and three admitted
classes, and no single number is both. What the count actually protected — a
class listed twice, admitted twice and reviewed once — is checked structurally by
`requireDistinct`, and `NEW_EVENT_TYPE_COUNT` is derived from the list it counts.

### One extension point, because a class can now say which kind it is

`RecordDecisionRule` is deleted. It existed because a class-keyed table could not
express "one `Scan`, two kinds", so the arrival-star milestone earned its rule
from a predicate re-read at projection time. `Scan.BodyReading` and
`Scan.UndiscoveredStar` are two classes now, each with its own catalogue entry,
and the milestone's kind, mechanism, context profile, object name, uncounted
claim and retained qualifiers are unchanged.

`DecisionEventCatalog.size()` goes with it. The coverage test compared the
catalogue's size against the admitted-type count, which was the same question
only while every admitted type had exactly one entry; it now compares coverage,
and an entry is reachable when it is an admitted type or a variant of one.

## Consequences

`JournalEventVariantContractTest` is the architecture as an executable contract:
for one parsed class there is one structural type, one domain kind and one
description, whatever the record said. It runs a corpus that varies exactly the
fields the discriminators are read from — including values this build does not
recognise — and asserts the corpus really splits (7 wire event names, 23
classes), so a test that had stopped testing anything would fail rather than
pass.

`LlmPresentableJournalEvent.modelFacingDescription()` no longer licenses a record
to choose between fixed phrases by reading its own fields. Both records that did
are split, and the sentence is now a property of the class and nothing else.

**The text is deliberately unchanged.** Every variant returns the sentence its
record returned before the split, which is what makes this change structural and
provable against the replay baseline. Giving each variant its own sentence — and
moving the trajectory vocabulary onto descriptions rather than identifiers — is
model-facing work and is its own change.

Two behaviours are preserved deliberately and remain candidates for their own
fixes, because each is a claim about the event rather than about its class:

- `EngineerLegacyConvert` without `IsPreview` is described as a completed
  conversion. That was an `orElse(false)`; it is now written down as
  `CONVERTED_DESCRIPTION` with the reason.
- `LaunchDrone` dispatches on `Type` case-sensitively, as the graph always did,
  while its presentation ignores case. The divergence is documented in the class.

*Amends [ADR-0021](ADR-0021-LAYER-BOUNDARIES-AND-DECLARED-RULES.md), "Both
extension points enumerable": there is one extension point again, and
`DecisionRecordRuleTest` is replaced by `JournalEventVariantContractTest`. Amends
[ADR-0015](ADR-0015-CURRENT-CHANGES-AND-VISIT-SCOPED-FINDINGS.md) and
[ADR-0014](ADR-0014-SESSION-RESTORE-AND-SCANNER-RESULTS.md) on the profile names
only; the admitted types are unchanged.*

What this does not change: the system prompt, the model-facing schema, the event
kinds, the 112 admitted types and 2 context types, the graph persistence format,
trajectory semantics, and the provider and speech paths.
