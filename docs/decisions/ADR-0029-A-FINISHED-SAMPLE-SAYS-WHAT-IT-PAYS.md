# ADR-0029: A finished sample says what it pays

## Status

Accepted and implemented. **Supersedes the "no multiplier, no derived price, no
computed worth" clause of
[ADR-0028](ADR-0028-THE-ORGANIC-REGISTRY-IS-A-FILE.md)**, which is three days
old. Everything else ADR-0028 decides stands, including that the registry holds
the base price and that `colonyDistanceM` reaches the model nowhere.

## Context

ADR-0028 refused to put a price in the request, on three readings of this
repository's own sale records:

- the bonus is not a property of the species — Clypeus Lacrimam sold once at
  `"Value":8418000, "Bonus":33672000` and once at the same value with no bonus
  at all;
- it is not data but one constant — every non-zero bonus observed is exactly
  four times the value, a payout of five times over;
- it cannot be known before the sale, because the game decides at Vista
  Genomics.

The first two are arguments about *where* the multiplier lives, and they are
still right: it is not a column in the registry, it is a rule. The third is an
argument about *when* it is known, and it is where this decision differs.

**What is known at the analysis is not the sale, it is the footfall.** The scan
that found the body states `WasFootfalled`, and the current-system registry
keeps it. A body nobody has walked on is a body nobody has sampled, because
sampling requires walking. So on a first footfall the data is undiscovered and
the sale will carry the bonus — not as a forecast about the market, but as a
consequence of a fact the game already stated.

The Commander asked for this, and the request is reasonable: the one moment a
payout is worth hearing is the moment the sample is collected, and 5× versus 1×
is the difference between a detour worth making and one that is not.

## Decision

**The analysis that finishes a sampling sequence carries `valueMCr`, and carries
`firstFootfall: true` when the multiplier was applied.** No other step does: a
log and a sample in the middle of a sequence have collected nothing, and a price
on them would be a price on work not yet done. Declared per event on
`DecisionEventRule.reportsSampleValue`, set on `ScanOrganic.Analysed` alone.

**One number, already multiplied, in millions.** `valueMCr` is the payout and
not the base. The alternative — a base price and a flag, with the model doing
the arithmetic — is the shape this project already knows fails: a model given a
number and a reason to multiply it invents a third number. `firstFootfall` says
*why* the figure is large and is never a factor to apply.

**Millions to one decimal, because the field exists to be spoken.**
`12934900` is seven digits to read out and `12.9` is two syllables. One decimal
covers the whole registry — the cheapest organism is 0.1 and the dearest at five
times over is 100.0, and nothing rounds to zero. It is deliberately not exact:
the exact figure is the game's to state at the sale, and a companion saying
"twelve point nine three four nine million" is reading out a database. The file
and `OrganicRegistry.valueCr` stay in credits, because that is what the game
publishes and what the generator checks against recorded sales; the rounding is
the document's, at the one place the number is written.

**The multiple is five, and it is a constant in the projector**, not a field of
the registry and not a column of the file. Base plus a 400% first-discovery
bonus, which is what all 61 sales recorded in this project's journals show.

**Silence about footfall is not a "no".** Where the game never said whether the
body had been walked on, the published price is sent without the flag. That
price is true whatever the bonus does; claiming the bonus needs evidence.

**No price at all without a registry, and none for an organism it does not
price.** The value comes from `OrganicRegistry.valueCr` or from nowhere. The
registry now exposes it — ADR-0028 loaded it and deliberately kept it private
until something read it, and this is that something.

## Amendment, 2026-08-08: a finished body says so, and says what it paid in all

Two additions, on the same evening and from the same live run. Neither changes
what is above; both extend it.

**`context.biology.allCollected: true` when every surveyed genus is collected.**
Absence is how this contract says *nothing there*, and that is not being
revised — `remaining` still opts out when it is empty and `collected` still opts
out when it is. What is new is that finishing is now *stated* rather than left to
be read off a missing field. The cost of not stating it is measured twice: a
document reading `{"collected":[…five…]}` and nothing else was answered "the rest
of the organisms on this body are still unstudied", and the same reading recurred
on T07 of the live run of 2026-08-08. Five promptings failed to teach absence;
this is the alternative that does not make absence ambiguous — a positive fact,
present only when true, exactly like every other field here.

**`bodyTotalMCr` replaces `valueMCr` on the turn that finishes the body.** Not
beside it. One money figure per turn is the rule this ADR already decided, for
the reason it already gives: a model handed two numbers and a relation between
them produces a third. The last sample's own price is inside the total, so
nothing is lost by dropping it, and "what this body paid" is the larger news at
the one moment both are true.

The total is **summed over the collected species**, not the genera: the game
prices a species and one genus can be several. It carries the same ×5 on the
same `firstFootfall` evidence. **An unpriced species withholds the whole
figure** — a sum missing one term is not an approximation of the total, it is a
false statement about it, and the turn falls back to saying nothing about money
rather than to saying something smaller than the truth.

`BiologicalSurvey` gained the collected-species set to make this answerable;
`allCollected()` is deliberately false for a body the surface scanner never
mapped, because a body with no list has not finished one.

Discovered while implementing this: the fixture named `exobiology.jsonl` carried
only `Log` and `Sample` records and **not one `Analyse`** — no fixture in the
repository had ever finished a sampling sequence, so neither `valueMCr` nor
anything downstream of a completed sample was reachable from a fixture at all.
One record was appended and the graph path it drives grew by one transition.

## Consequences

**This is the only predicted claim in the document, and it is a claim.** The
bonus is decided at the sale. Another Commander landing and selling between the
scan Kairon read and the sale the Commander makes would take it, and Kairon will
have said five times. That is the accepted cost, stated here rather than
discovered later.

**The measured risk is fabrication, and it is why there is one number.** Two
prompt lines naming a field as important produced nine invented quantities in a
single evening, always on turns where the named field was absent. Nothing here
is a prompt line: the field is present in exactly the turn it describes, and
`<grounding>` already says to state as fact only what the request contains. If
figures start appearing on turns that carry no `valueMCr`, this is the first
thing to withdraw.

**`ModelFacingReplayBaselineTest` does not move.** The fixtures configure no
registry, so no fixture turn carries a price. The contract is asserted instead
by `OrganicNamingContractTest`, which drives one journal three ways — first
footfall, already footfalled, and no registry — and reads the whole document
each time. The glossary's reachability check does now load the shipped registry,
because `valueMCr` comes from the registry or from nowhere and exempting it as
"no fixture drives one" would exempt a name the next live turn produces.

**`firstFootfall` is exempted from that check**, beside `previouslyFootfalled`
and for the same reason: no fixture carries a `Scan`, so no fixture establishes
whether a body had been walked on. The Commander's own journals state it 3 189
times.
