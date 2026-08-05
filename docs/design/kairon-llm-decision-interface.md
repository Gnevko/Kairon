# Kairon — the LLM decision interface

**Status:** implemented. `kairon-llm-decision-v1` is the only production model
input. The previous `kairon-llm-situation-v2.1` path is deleted from source, not
disabled.

This document is normative for what reaches the provider. It says nothing about
what Kairon knows internally, because almost none of that changed.

---

## 1. The rule

Every model-facing field must have a concrete answer to one question:

> **which decision or which sentence does this field improve?**

Without a demonstrated answer the field stays inside Kairon and inside the
trace. The field-by-field record of that judgement, with evidence from the
measured first-100 replay, is
[`target/audit/kairon-llm-provider-field-audit.csv`](../../target/audit/kairon-llm-provider-field-audit.csv).

The corollary is the shape of the contract: the model is told about the game,
never about Kairon. No schema version, no turn counter, no bus sequence, no
absolute timestamp, no selection role, no wire event name, no internal
identity of any kind.

---

## 2. The provider input

```json
{
  "events": [ ... ],
  "changes": [ ... ],
  "context": { ... },
  "trajectory": { "recent": [ ... ], "likelyNext": [ ... ] },
  "contextIncomplete": true
}
```

| Part | Presence | Meaning |
| --- | --- | --- |
| `events` | always, never empty | what just happened, each stating in its own words what it is; the primary factual basis for a comment |
| `changes` | when a change adds decision-relevant novelty | what those events altered |
| `context` | when the events need it to be understood | what else is true right now |
| `trajectory` | when this system visit has a remembered run of events, and the batch is not entirely trajectory-independent kinds | what led here, and what usually follows |
| `contextIncomplete` | only when something relevant was lost | absence is not proof of absence |

Nothing is serialized as `null`, as `[]`, as `{}` or as an `UNKNOWN` marker.
Absence is the contract, and the prompt states that rule once.

The model is told the five names above and nothing about how they were built.

---

## 3. Local event identity — internal only

One current trigger becomes exactly one event. Events are numbered `1, 2, 3, …`
inside one request, and **no part of that numbering is serialized** — neither
`events[].id` nor the `changes[].eventId` that points at it. The provider
receives an ordered array whose entries start with what happened, and a changes
array that says what changed without saying which entry did it.

An identity the model can neither verify nor act on is Kairon's own bookkeeping,
exactly like the schema version — so it stays with the schema version, inside
the process. Two things read it there:

| Reader | Why |
| --- | --- |
| `DecisionChangeSelector` | a change one of the request's own events caused is never reconciled against later state; `eventId` is how it knows |
| the cross-layer contract tests | they read `LlmDecisionRequest` before serialization, because a field that is deliberately not on the wire cannot be asserted from the wire |

Outside the record the same ordering exists as the turn's trigger bus sequences,
in the same order: **the nth event was projected from the nth trigger**. That is
what the trace writes and what a delivered comment is attributed to, so nothing
needs a second copy of the mapping under a name of its own.

Because nothing is sent, the current-trigger-only rule is structural rather than
enforced: a hidden observation, a context fact, a remembered predecessor, a
prediction and a previous comment have no position in `events`, so nothing can
point at them — and neither can the model, which is given no way to point at
anything.

---

## 4. Events

Events are domain statements, not serialized `SemanticFact`s. The semantic layer
is unchanged and remains Kairon's universal internal representation; the
projection to a model-facing event happens in `kairon.observer.decision`.

```json
{"id": 1, "event": "A surface area analysis scan of a body was completed.",
 "body": "Schieni GG-A c3-84 4 a", "probesUsed": 2, "efficiencyTarget": 2}
```

`event` is the literal description the record itself supplies, through
`LlmPresentableJournalEvent.modelFacingDescription()`. It says what kind of
thing happened, in the terms of the game; the fields beside it say what it
happened to. Nothing looks it up: the observation in hand is the only source of
its own meaning, there is no table keyed by kind or by class, and no `switch`
over kinds exists anywhere.

**The internal `kind` is not sent.** It still exists — `DecisionEventCatalog`
still gives every eligible type one, selection, the behaviour graph, the
trajectory vocabulary, diagnostics and the tests all read it — but a name only
this process shares is not an answer to "what happened", and sending it beside
a description would answer one question twice. `LlmDecisionRequest.Event` carries
both; `JacksonDecisionRequestSerializer` writes only the description.

A description is a property of the type, not of the record: two landings say the
same sentence and differ only in their fields. One class is allowed to choose
between fixed phrases when it genuinely reports two different things — `Scan`
does, through `Scan.reportsUndiscoveredStar` — the one implementation, which
`BodySurveyFacts` delegates to, so the record and the projection cannot disagree — and nothing is ever interpolated into the text.

`trajectory` is unchanged and still speaks in kinds. Making a remembered or
predicted event describe itself is a separate problem: there is no instance
behind either one.

Nor is the wire name evidence about the world. A real journal was observed
emitting `LaunchFighter` for a vehicle whose later `Disembark(SRV=true)`,
`Embark(SRV=true)` and `DockSRV(SRVType_Localised="Nomad")` records, all
carrying the same runtime id, proved it was a **Ship-Launched Vessel**. The
launch record itself has no `SRVType`, no localised name and nothing else that
settles the type, so the kind is `VEHICLE_LAUNCHED` — a vehicle went out — and
not `FIGHTER_LAUNCHED`. This is one observed conflict, not a claim about how
Frontier always behaves; the point is that the record does not carry the proof
either way. The opposite inference is equally unsupported: a launch is never
reported as an SRV. A later event that does establish the type reports it then —
`VEHICLE_RECOVERED` sends `vehicleKind: "SLV"` and `vehicleType: "Nomad"` — and
nothing already sent to the model is revised. The runtime id stays inside
Kairon, where it correlates the lifecycle.

### Vessel classes

Four values, kept apart because they answer different questions:

| Value | What it is |
| --- | --- |
| `SHIP` | the Commander's primary ship |
| `SRV` | a conventional Surface Recon Vehicle |
| `SLV` | a Ship-Launched Vessel, such as the Nomad |
| `Nomad` | the concrete model — a `vehicleType`, never a `vehicleKind` |

The journal exposes a Ship-Launched Vessel through its legacy fighter and SRV
channels: launched by `LaunchFighter`, held by `Cargo(Vessel="SRV")`, boarded
with `SRV=true`, recovered by `DockSRV`. Those channel names are **evidence
about which subsystem the game used, not the domain class**, and the class is
decided in one place — `AuxiliaryVehicleTypes` — rather than copied from
whichever record happened to arrive.

The inference is composite, because no single record proves it: an ambiguous
`LaunchFighter` lifecycle **plus** a `Cargo(Vessel=SRV)` snapshot for that same
active vehicle means `SLV`. A conventional SRV names itself at launch and never
reaches the rule; the same tag with no ambiguous launch behind it still narrows
an unknown class to `SRV`. `DockSRV` carrying `SRVType = lander01` or
`SRVType_Localised = Nomad` — compared without regard to case or locale —
confirms `SLV` / `Nomad`, and any other named vessel stays `SRV`. Once a runtime
id is known to be an `SLV`, a later `SRV=true` record does not downgrade it.

`commander.presence` distinguishes the same four situations: `SHIP`, `SRV`,
`SLV`, `ON_FOOT`. Sitting in a Nomad is not sitting in an SRV.

The class reaches the model before the recovery too, as context rather than as a
claim about the launch. `SURFACE` asks for `ContextNeed.VEHICLE`, so a landing or
a lift-off carries `context.vehicle.kind`, which is the difference between
setting a ship down on a body and driving onto it. `SURFACE` does **not** ask for
`ContextNeed.PRESENCE`: whose hold a snapshot describes is not where the
Commander is sitting, and that stays something `Disembark` and `Embark` say
outright. `DecisionEventCatalog` holds one rule per
model-eligible type, and a test asserts the table matches
`LlmJournalEventSelection.TARGET_NEW_ELIGIBLE` in both directions — 109 of 109,
with nothing extra. A catalogued event cannot reach the generic fallback.

### What an event does not carry

| Dropped | Why |
| --- | --- |
| `subject` | the kind already fixes it |
| `actor: commander` | it is always the Commander |
| `processStage: FINAL` on an atomic action | a constant; 27 of 32 facts in the measured run |
| `completion: true` on an atomic action | the same constant |
| `negation: false` | the default |
| unnamed `quantity` | the exact field that produced the run's factual error |
| `identity` duplicating `object.id` | the same value twice |
| internal identifiers | never speakable, never a discriminator within one request |
| prose `summary` | either the kind is the whole content, or a typed uncertainty carries the caveat |
| surface coordinates | no comment in either measured run referenced one |

### What an event does carry

Only applicable domain fields, under domain names: `probesUsed`,
`efficiencyTarget`, `credits`, `units`, `distanceLy`, `organism`, `stage`,
`complete`, `sender`, `channel`, `message`, `body`, `system`, `commander`,
`friend`, `status`, `vehicle`, `vehicleKind`, `vehicleType`,
`playerControlled`, `newEntry`, `occurrenceOnBody`.

A semantic relationship is **not** among them. It named its counterpart with
Kairon's own kind — the vocabulary an event no longer sends — so once the kind
stopped being serialized the value pointed at a word the model never sees, and
it flattened five different relations (`cancels`, `negates`, `releases`,
`inverse of`, `negative outcome of`) into one field called `reverses`. The
relationship is unchanged on the semantic fact and still reaches diagnostics;
saying it to the model needs a contract of its own, which is a separate
design step.

A recovered vessel carries **two** fields, not one label: `vehicleKind` is the
class (`SLV`, `SRV`) and `vehicleType` the model (`Nomad`, `Scarab`). One
unqualified `vehicle: "Nomad"` was read as a ship's name, which is what a name
slot invites. An unclassified recovery sends neither field rather than an empty
one, and `vehicleType` falls back to the raw identifier only when there is no
localised label — the same rule every other label follows.

`vehicleKind` on an event also answers `context.vehicle.kind`, so the group is
not sent beside it (`DecisionNames.contextSlotStatedBy`). The event says which
vessel it is about; the context would say which one is current — after a
recovery those are different vessels, and two answers to "which vehicle" in one
request is exactly the ambiguity the split was made to remove.

`occurrenceOnBody` is the one field an event cannot derive from its own payload;
it comes from the remembered episode and is described in §8, along with the one
kind that deliberately omits it.

A name is always said to be a name *of* something. `commander` is the Commander
running the session, `friend` a third party, `body` a body — never a bare
`name` the model has to attribute.

Closed vocabularies are sent in one casing. `stage`, `presence`,
`flightMode` and a change's `kind` are upper snake case because they come from
Java enums;
the ones that arrive as Frontier tokens — a friend's `status`, a body's `type`,
a message's `channel` — are brought into the same shape: `Online` to `ONLINE`,
`PlanetaryRing` to `PLANETARY_RING`, `squadron` to `SQUADRON`. Only the spelling
changes, and only for values the semantic layer already declared symbolic rather
than free text. `channel` goes through an explicit table in `DecisionNames`
rather than a mechanical uppercase, because each of Frontier's channel names is a
decision about what to call it; a channel outside the table still gets the
contract's casing rather than the journal's, since dropping the field would be a
worse answer than naming it consistently. The raw journal event is untouched, and
`message` and `sender` are passed through as written.

A bare number with no name is **dropped rather than sent**. That is the whole
correction: `quantity: 2` beside `efficiencyTarget: 2` is what the model
misread, so an unnamed number now fails closed.

### Stages and completion

- `stage` START or PROGRESS, and `complete: false`, are **always** sent.
- `stage: FINAL` and `complete: true` are sent **only** for a mechanism with
  genuine multiple steps: organic sampling, a scheduled ship transfer, a
  scheduled carrier jump, a construction depot.
- That pair is the **only** statement of where a sequence stands. `ScanOrganic`
  carries a `ScanType` the adapter already maps one for one — `Log` to
  START/`false`, `Sample` to PROGRESS/`false`, `Analyse` to FINAL/`true` — and
  sampling is multi-stage, so all three reach the model as `stage` and
  `complete`. The qualifier is therefore dropped rather than renamed: a
  a biological sample is exactly `id`, `event`, `organism`, `stage`, `complete`.
  The internal `scanType` qualifier and the `ScanType` field are unchanged.
- An event whose rule declares `wholeAction` carries no `stage` at all, and no
  uncertainty either: its kind is the entire assertion, so there is no process
  position and no adjacent claim for a gap to qualify. Today exactly one event
  does — `VEHICLE_LAUNCHED`. It names its loadout, its adapter's START is a
  deployment beginning rather than a position anyone can act on, and launching a
  vehicle establishes nothing about where the Commander is. Where the
  Commander is comes from `context.commander.presence`, which answers it
  directly. The flag is a per-event claim that has to be defensible each time it
  is set, and a test pins the set of events that carry it.

### Uncertainty

An unprovable aspect is stated in the terms of the thing that is unknown, never
as an internal reason code:

| Internal reason | Model-facing field |
| --- | --- |
| `VEHICLE_OCCUPANCY_NOT_ESTABLISHED`, `FIGHTER_OCCUPANCY_NOT_ESTABLISHED` | `occupancy: "UNCONFIRMED"`, except on an event declaring `wholeAction` or settling that gap |
| `TAXI_CONTEXT_NOT_MODELLED` | `taxi: "UNCONFIRMED"` |
| `MULTICREW_CONTEXT_NOT_MODELLED` | `multicrew: "UNCONFIRMED"` |
| `IDENTIFIER_KIND_NOT_ESTABLISHED` | `identifiedObject: "UNCONFIRMED"` |
| `NO_SEMANTIC_ADAPTER`, `AUTHORITATIVE_SEMANTICS_NOT_ESTABLISHED` | `details: "UNAVAILABLE"` |

A launched fighter therefore asserts nothing about where the Commander is, and
does not stay silent about not knowing.

A rule may also settle **one named gap** without dropping the rest, through
`DecisionEventRule.settledGap`. Today the two presence transfers do:
`DISEMBARKED` and `EMBARKED` drop `occupancy`. A presence transfer is the
Commander moving between vessels, and the presence mechanism always asks for
`context.commander.presence`, so the same request states `ON_FOOT` or `SRV`
outright — an `UNCONFIRMED` occupancy beside it invites doubt about a fact that
is not in doubt.

This is narrower than `wholeAction`, and it is still a per-event claim rather
than a property of the gap or of the mechanism: `FIGHTER_DESTROYED` raises the
same gap with nothing in its request to answer it, and keeps it. A test pins the
settled set exactly. The flag and `wholeAction` are mutually exclusive — a kind
that settles the whole action has no remaining gap to name.

One gap has **no** model-facing representation:
`LOGIN_TRANSITION_NOT_ESTABLISHED`. A `Friends` event reports a friend's
current status and never asserts a transition into it, so there is no claim for
the contract to qualify — and saying that a transition is unconfirmed would
introduce the very claim it disclaims. The reason is unchanged in
`UnresolvedFact` and still reaches diagnostics. A friend status is therefore
exactly `id`, `event`, `friend`, `status`.

---

## 5. Mechanisms

Projection is organised by **mechanism**, not one class per event. Eighteen
mechanisms cover 109 event types, and each decides two things an individual
event cannot.

**Which context the turn may ask for.** A friend notification asks for nothing.
A landing asks for the body and the flight mode. A sample asks for the body,
where the Commander is standing, and the running sequence.

**Which canonical fields the event already states.** Entering supercruise
changes the flight mode, and reporting that as a change beside an event named
for it is the same sentence twice.

| Mechanism | Context it may ask for |
| --- | --- |
| `IDENTITY`, `SOCIAL`, `POWERPLAY` | none |
| `TRAVEL` | system, body name, navigation |
| `BODY_TRANSIT` | system, body detail, navigation |
| `SURFACE` | body detail, navigation, system, vehicle |
| `PRESENCE` | commander presence, vehicle, body name, sampling |
| `VEHICLE` | vehicle, commander presence |
| `DOCKING` | system, navigation |
| `EXPLORATION` | system, body detail |
| `SAMPLING` | body detail, sampling, commander presence |
| `MISSION`, `ENGINEERING`, `CARRIER`, `COLONISATION` | system |
| `COMBAT` | system, body name, ship |
| `COMMERCE` | system, ship |
| `SHIP_STATUS` | ship, navigation |

---

## 6. Changes

The full exact canonical delta is still computed inside the projection boundary
and still reaches the trace and diagnostics untouched. Only what a decision
needs is sent, and the default is no.

```json
{"subject": "body", "kind": "ACTIVATED_FROM_CONTEXT",
 "fields": {"planetClass": {"after": "Icy body"}, "landable": {"after": true}}}
```

A change says what changed and about what. It does not say which event caused
it: `eventId` is internal (§3), and a pointer is worth only as much as what it
points at — the events carry no identity for it to name. `busSequence`, the wire
event name, the selection role and the write-path origin all stay inside Kairon
too.

`kind` is retained because `ESTABLISHED` and `UPDATED` are different news:
learning a body's class for the first time is not the same as it changing.
`ACTIVATED_FROM_CONTEXT` never appears — a recall from the stored body registry
is not a change at all, and its values arrive as `context.body` instead. §7
covers why.

### The eleven reasons a change is dropped

1. the field has no model-facing name at all — an account identifier, a vessel
   id, a system address beside a system name, a raw taxon key;
2. the causing event's mechanism already states the field;
3. an event in this request already carries the same value under its own name;
4. the change is a clearing — "no longer known" is what the absence of the field
   from the context already says, and whatever replaced it arrives as its own
   change;
5. the change establishes the coarse body type for the first time. A body did
   not become a planet when the ship dropped out of supercruise; it always was
   one, and Kairon simply learned which. A later change of type is a real
   update and passes through;
6. the change is a recall from the stored per-body registry. The ice, the signal
   counts and the fact that nobody has landed there were all true before the
   approach and are still true after it; presenting them as a change invites a
   comment about something having just happened, and the qualifier that would
   prevent it is an internal write-path term. Nothing is lost: every recallable
   field is a body fact, and every mechanism that can trigger a recall asks for
   the body in its context, so the same values arrive as `context.body`;
7. the change establishes `activeOrganicSampling` as inactive for the first
   time. The flag starts unestablished and becomes `false` the moment anything
   deselects a body, which is Kairon learning its value — not the game reporting
   that a sequence finished or was interrupted. A reader shown `active: false`
   cannot tell those apart. The rule is deliberately narrow: a sequence starting
   and a running sequence ending are real transitions and pass through
   untouched;
8. the text differs only in case, which is a normalisation artefact;
9. the observation was kept for diagnostics only;
10. the turn is the session's identity bootstrap, where every field is being
   established for the first time and none of it is news;
11. a hidden observation changed a subject none of this turn's mechanisms has any
   business hearing about.

Rule 11 is what stops a chat message arriving with the ship, the system and the
flight mode a startup event happened to establish a minute earlier.

---

## 7. Context

Not the full canonical state. Only the subjects the turn's mechanisms asked for,
minus anything this request already says.

Two overlap checks, deliberately different. A change names the same canonical
field the context would, so it is matched **by name** — and the change is the
better version, because it also says the value was recalled rather than freshly
observed. An event names things in its own vocabulary, so it is matched **by
rendered value**, which also makes free text and a closed symbol carrying the
same string count as one statement. Only string-like values are matched: a
shared `true` between unrelated fields is coincidence, not repetition.

Subject separation survives. `commander` carries presence and nothing else — the
account identifier has no representation at all — while `ship`, `vehicle`,
`body`, `system`, `navigation` and `sampling` remain separate groups. An
associated vehicle is never merged into where the Commander is standing.

Body survey flags are named for the tense they are in:
`previouslyDiscovered`, `previouslyMapped`, `previouslyFootfalled`, and the
distance is `distanceFromArrivalLs`. A bare `discovered` beside an arrival reads
as something that just happened; what the field records is what was true before
it. Context names come from the same `DecisionNames` table a change uses, so the
two ways the model can hear about one canonical field cannot drift into two
spellings.

A sampling sequence appears only while one is running. The previous contract
sent `active: false` in thirteen turns that had nothing to do with sampling,
which is a declaration of absence in a contract whose whole rule is that absence
needs no declaration.

**The event and the context speak in two tenses, and the vocabularies are kept
apart.** A `BIOLOGICAL_SAMPLE` event states the transition it just made —
`START`, `PROGRESS`, `FINAL`, with `complete` beside it — and is unchanged.
`context.sampling.stage` states the persistent state a sequence has already
reached, and has exactly two values:

| canonical stage | `context.sampling.stage` |
| --- | --- |
| `START` | `STARTED` |
| `PROGRESS` | `IN_PROGRESS` |

The mapping is an explicit table in `DecisionNames.samplingContextStage`, not
the enum's own name, so a stage added later arrives unmapped and is dropped
rather than reaching the model in the event's tense. There is no `FINAL` and no
`COMPLETED` standing state: completing a sequence clears the canonical process,
so a finished one is absent by the ordinary rule rather than described as
finished. The group is `{organism, stage}` and nothing else — no `active`, no
`complete`, no counters, no taxon identifiers, no body. An active sequence whose
variant label is unknown sends `stage` alone; a raw Codex token is never a
fallback for a speakable name.

**Presence-transition events receive it.** `EMBARKED`, `DISEMBARKED` and
`DROPSHIP_DEPLOYED` share `DecisionMechanism.PRESENCE`, which requests
`ContextNeed.SAMPLING`. Getting out and getting back in is what a Commander does
in the middle of a sequence, and `trajectory.recent` remembers three
predecessors — two rides and a landing are enough to push the scan that started
it out of that memory. The events themselves say nothing about sampling, so
without this the fact is unreachable. When no sequence is running the group is
absent, for these three exactly as everywhere else.

---

## 8. Trajectory

The behaviour graph reaches the model as exactly two lists of statements, and as
one number on the current event.

```json
"trajectory": {
  "recent": [
    "The organic sampling tool logged the first scan of an unfinished sampling sequence.",
    "The Commander, on foot, got into a ship or SRV.",
    "A ship took off from the surface of a planet or moon."
  ],
  "likelyNext": [
    {"event": "The Commander stepped out of a ship or SRV.", "probability": 1.0}
  ]
}
```

`recent` is up to three immediate predecessors of the current events, oldest
first, taken from the active episode's own trajectory. Every occurrence this
turn's own triggers committed is excluded, not merely the last one — the batch
is what `events` already states, and repeating it would read as the same thing
happening twice. Ownership is derived rather than believed: the occurrence id
the graph would mint for an observation is recomputed and compared with the
cursor, because an `APPLIED` status alone is also satisfied by an owner switch,
an episode switch or a bare revision bump.

`likelyNext` is the transition model's own calculation, ordered and numbered as
it produced it, truncated to three. Nothing is recomputed and no weight,
half-life or prior is touched. It is absent when there is no prediction.

### Some turns are not about where the ship has been

`DecisionTrajectoryProjector.TRAJECTORY_INDEPENDENT_KINDS` is a closed set of
kinds whose meaning owes nothing to the flight history:

| Kind | Why |
| --- | --- |
| `MESSAGE_RECEIVED` | a message says what it says; where the Commander flew before a squadron greeting arrived cannot make it easier or harder to read |
| `FRIEND_STATUS` | it happens to a person somewhere else entirely; the ship's movements are not part of it |

**A batch every one of whose events is in that set gets no `trajectory` at
all** — a forecast of the ship's next manoeuvre beside one of these invites the
model to connect two unrelated things.

The rule is deterministic and reads the projected `kind` values only: not the
message text, the sender, the channel, the friend's name, or whether a status
repeats. A batch containing any other kind keeps its trajectory in full, because
that other event is exactly the one a history might explain — one friend
notification does not silence a landing.

Growing the set is a claim about an event kind that has to be defensible on its
own; it is not a place for anything that merely looks noisy. A test pins the set
against the catalogue, so a kind cannot be listed under a spelling no event
produces.

Nothing about the graph changes: the episode still advances, the occurrence is
still recorded, the prediction is still calculated. Only the projection declines
to send it. This is not deduplication either — two friends coming online at once
remain two events with two local ids, and a repeated identical status is never
folded into the one before it.

### What does not come with it

| Dropped | Why |
| --- | --- |
| occurrence id, episode id, graph id, cursor | identities; a sentence cannot rest on one |
| `episodeSequence`, `totalOccurrenceCount`, `omittedOccurrenceCount` | positions in Kairon's own bookkeeping |
| `source`, `matchesFinalTrigger` | pipeline classification |
| `basis`, `contextKey`, `globalProbability`, `observedTransitionCount`, `contextObservedTransitionCount`, `contextSupport`, `effectiveWeight` | the evidence behind a probability. Quoting it beside the probability invites the model to re-derive a number it was already handed |
| raw and normalized event type names | the model has one vocabulary, not two |

### The vocabulary

`DecisionTrajectoryDescriptions` maps every declared `NormalizedEventType` to
what that event says. Where a type is produced by a journal class the sentence
**is** that class's `modelFacingDescription()`, and a test parses a minimal
record of each and compares — a landing remembered must say what a landing says.
Six types no journal class describes are authored here in the same register, and
all six are Status-derived — the two scanner modes and the landing gear — for
which no journal record exists at all. Every other entry comes from a class,
including the two frame-shift charges and the system honk, which describe
themselves without being model-eligible as events.

The three sampling steps each say which step they were, so a remembered sample
needs no stage to fold in.

A prediction reads the same past-tense sentence as a memory of the same event.
What has not happened is said by the field it sits in — `likelyNext`, with a
probability beside it — and the prompt states it outright. A second set of
sentences in a forward tense would be a second vocabulary to keep in step with
the first.

An unmapped type — an unrecognised discriminator normalizes to an `UNKNOWN_*`
value built from the journal's own event name — yields no sentence and is dropped
from the list rather than passed through.

### Occurrence count

A repeated event carries `occurrenceOnBody` on the event itself:

```json
{"id": 1, "event": "A ship landed on the surface of a planet or moon.",
 "body": "Schieni GG-A c3-84 4 a", "playerControlled": true,
 "occurrenceOnBody": 2}
```

It counts occurrences of that event type, at that exact body, inside the current
system episode. `1` means the first time here.

Two scoping decisions, both deliberate:

- **Not the all-time count.** "The second landing here" is something a Commander
  can recognise. "The ninetieth landing since Kairon started watching" is not,
  and the graph's all-time counters are not sent.
- **By body identity, never by name.** The scope key is the pair
  (`systemAddress`, `bodyId`), carried on the occurrence from the context the
  graph accepted it with. A body id is only unique inside its own system, so the
  pair is the smallest thing that will not merge the fourth body of one system
  with the fourth body of the next.

That pair is the one piece of projection metadata this added:
`SituationOccurrence.body`, carried from `ContextSnapshot`. Graph ownership,
calculation, weights and persistence are untouched — the store schema did not
change.

The field is absent when the trigger owns no occurrence, or when the graph had
established no body for it. A friend coming online happens to the Commander, not
at a place, and a count with no scope to be true of is worse than no count.

**`BIOLOGICAL_SAMPLE` does not expose `occurrenceOnBody`.** Its internal graph
occurrences are stage-specific — `SCAN_ORGANIC_LOG`, `SCAN_ORGANIC_SAMPLE` and
`SCAN_ORGANIC_ANALYSE` are three structural types — while the model-facing event
kind is shared by all three. Exposing the stage-specific counter under a generic
field would be ambiguous: `"occurrenceOnBody": 1` on a finished sequence counts
the first analysis at that body and reads as the first biological sample there.
The event's `stage` and `complete` express the biological sampling transition
instead. Suppression is declared per kind on the rule
(`DecisionEventRule.stageSpecificOccurrences`, set only for `BIOLOGICAL_SAMPLE`)
and applied where the field is added, in
`DecisionEventProjector.appendOccurrenceCount`; the body-scoped calculation in
`DecisionOccurrenceScope` is unchanged and every other kind keeps the field.
Nothing replaces it — there is no sample number, no completed-cycle counter.

The internal counts remain, in full: the graph keeps all three types, their
occurrences, their body-scoped counts, their transitions and their probabilities,
which is what graph learning and prediction run on. The trajectory still names
them — `BIOLOGICAL_SAMPLE_STARTED`, `_CONTINUED`, `_COMPLETED`.

Unchanged: graph calculation, normalization, persistence, predictions, the graph
UI, and graph diagnostics.

---

## 9. The budget

`DecisionTurnPolicy` carries two bounds: `maxTriggers` 8 and
`maxSerializedCharacters` 16 000, both in Java `String` characters.

One compaction rung: the selected context can go, because it was chosen as the
part the events could be understood without. Events, their exact changes and the
trajectory cannot — the first two are never compactable, and the third is six
items at most, so dropping it would buy nothing against the loss of a repeat
being recognisable. Dropping the context sets `contextIncomplete`. When the
mandatory content alone still exceeds the budget the turn fails closed as
`CONTEXT_TOO_LARGE`: zero provider calls, zero speech, no synthetic silence,
previous-comment memory untouched, batch consumed exactly once.

---

## 10. The response

```json
{"decision":"SILENT"}
```

```json
{"decision":"COMMENT","comment":"…"}
```

- `SILENT` carries exactly one property.
- `COMMENT` carries exactly two, and requires a non-blank comment.
- any third property is `INVALID_PROPERTIES`, including the removed `evidence`.
- the response names nothing, because the request identifies nothing.

There was once a third property listing the events a comment rested on by their
local ids. Both halves went together: with no ids in the request, a citation
could not be checked against anything the model had been shown, and no reader
downstream ever branched on which subset came back.

`ValidatedObserverResponse` is `status`, `decision`, `comment`, `violations`,
`failure` — the parsed answer and Kairon's verdict on it. Nothing on it comes
from the request. That separation is deliberate: a record that mixed the answer
with facts about the question invites a reader to treat the second as though the
model had asserted it, which is exactly how a comment ends up "citing" events
nobody ever showed it. `validate` takes the raw output and the previous
comments; it is given nothing about the request because it needs nothing.

Attribution is computed separately, by `ObserverTurnCoordinator`, from the batch
it built: `triggerBusSequences`, the turn's own triggers in bus order. It travels
beside the response on `DecisionResolved`, is retained on
`DeliveredModelComment`, and is what the GUI shows and the trace records. The
novelty guard and speech continue to work as they always did.

---

## 11. The trace

`kairon-turn-trace-v6`, context schema `kairon-llm-decision-v1`.

The version moved because nothing called evidence is left, and a `v5` reader
expects all of it. `validatedDecision` lost both the ids the model returned and
the bus sequences they resolved to: it now describes the answer and only the
answer, so no reader can mistake a fact about the batch for something the model
asserted.

`localEvidence` is gone too, with nothing in its place. It mapped event position
`1..n` onto a trigger bus sequence, and `triggerBusSequences` is that same list
in that same order — the `v5` record even asserted the two were equal. One list
is the mapping; a second copy of it under a name that no longer meant anything
was a field nothing read. "Which observation was the third event?" is
`triggerBusSequences[2]`.

The trace keeps `turnSequence`, `triggerBusSequences`, the exact provider input,
the exact raw provider output, the validated answer, and provider, speech and
outcome diagnostics. The provider input and the traced document are
byte-identical. `triggerBusSequences` is written for every turn, including one
that failed closed before reaching the provider. No trace-only metadata is sent
to the model.

```json
{"traceSchemaVersion": "kairon-turn-trace-v6",
 "contextSchema": "kairon-llm-decision-v1",
 "turnOutcome": "COMMENT",
 "triggerBusSequences": [1041, 1042],
 "situationTurn": "{\"events\":[…]}",
 "situationCharacterCount": 383,
 "contextOverflow": null,
 "providerInvoked": true, "commentDelivered": true, "speechInvoked": false,
 "provider": {"profileName": "…", "type": "…", "baseUrl": "…", "model": "…"},
 "modelInput": {"systemMessage": "…", "userMessage": "{\"events\":[…]}"},
 "rawModelResponse": "{\"decision\":\"COMMENT\",\"comment\":\"…\"}",
 "validatedDecision": {"status": "VALID", "decision": "COMMENT",
                       "comment": "…", "violations": [], "failure": null},
 "tokenUsage": {…}, "latencyMs": 812,
 "consoleOutcome": "DELIVERED", "speechEnabled": false, "…": "…",
 "deliveredComment": "…"}
```

---

## 12. Where the contract lives

| Concern | Class |
| --- | --- |
| Request | `kairon.observer.decision.LlmDecisionRequest` |
| Comment attribution | `ObserverTurnCoordinator` — the turn's own `triggerBusSequences`; there is no evidence mapping |
| Event rules | `kairon.observer.decision.DecisionEventCatalog`, keyed by journal class |
| Event description | `LlmPresentableJournalEvent.modelFacingDescription()`, one constant sentence per class |
| Mechanisms | `kairon.observer.decision.DecisionMechanism` |
| Context slices | `kairon.observer.decision.DecisionContextProfile` |
| Already-stated facts | `kairon.observer.decision.StatedFacts` |
| Model-facing names | `kairon.observer.decision.DecisionNames` |
| Event projection | `kairon.observer.decision.DecisionEventProjector` |
| Change selection | `kairon.observer.decision.DecisionChangeSelector` |
| Context selection | `kairon.observer.decision.DecisionContextSelector` |
| Assembly | `kairon.observer.decision.LlmDecisionRequestFactory` |
| Serialization | `kairon.observer.decision.JacksonDecisionRequestSerializer` |
| Budget | `kairon.observer.decision.LlmDecisionRequestCompactor`, `DecisionTurnPolicy` |
| Prompt | `kairon.llm.DecisionPromptFactory` |
| Response | `kairon.llm.ObserverResponseValidator`, `CommentNoveltyGuard` |
| Trace | `kairon.trace.JsonLinesTurnTraceWriter` |
| Context overflow | `kairon.turn.overflow.ContextOverflow` |

---

## 13. What did not change

Observation bus, `busSequence` generation, projection ordering, canonical state
semantics, semantic adapters, graph calculation, weights, probabilities and
persistence, batching, event selection (109 NEW / 5 CONTEXT / 158 subscribed),
provider model and temperature, the meaning of SILENT and COMMENT, and speech
behaviour.

The one addition to the graph layer is `SituationOccurrence.body`: two longs
carried from the context snapshot an occurrence was already accepted with, so
that a repeat can be counted against one body. It is projection metadata — the
store schema, the calculation and the ownership rules are unchanged.
