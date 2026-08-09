# ADR-0030: A surveyed body says what it is worth at least

## Status

Proposed. Extends [ADR-0029](ADR-0029-A-FINISHED-SAMPLE-SAYS-WHAT-IT-PAYS.md),
which put a payout on the analysis that finishes one sample, and
[ADR-0028](ADR-0028-THE-ORGANIC-REGISTRY-IS-A-FILE.md), whose file this reads
from and extends. Nothing in either is reversed.

## Context

The surface scan names the genera on a body and nothing more:
`SAASignalsFound.Genuses` is `Bacterium, Fonticulua, Tussock, …`. That is the
moment the Commander decides whether the body is worth landing on, and it is
the one moment Kairon currently has nothing to say about worth — the price is
attached to the *species*, and the scan names only the genus. A genus averages
9.6 species in the shipped registry, and those species differ by up to a factor
of twelve in what they pay, so "Bacterium" alone is not a number.

The Commander asked for the total future reward at that moment, and chose
prediction-then-fact: narrow the species by the conditions of the body, say what
it is worth at least, and let ADR-0029's `valueMCr` state the fact later when
the sample is actually collected.

**This would be the first prediction Kairon makes.** Everything the request
carries today is something the game stated. A species narrowed from body
conditions is Kairon's own inference from a third-party table, and it is
therefore the one thing in the document that can be wrong without the game
having been wrong. That is why this is an ADR and not an addition.

### What was measured first

The upstream BioScan rulesets — 116 species, 254 rulesets — were run against
this Commander's own journals: 922 detailed scans, 30 bodies with an analysed
sample, 83 samples where both a scan and a ruleset exist. Body conditions only:
planet class, atmosphere, gravity, temperature, pressure, volcanism. Galactic
region, star class, materials and the presence of other bodies were deliberately
**not** consulted.

- **The species actually sampled survived the filter in 82 of 82 cases** where
  the filter produced anything at all. Not once did the conditions exclude the
  organism that was really there.
- **One body produced no candidate at all**: `Clypeus_01` on HIP 87621 2 b a, a
  rocky body at 468 K. One case in 83.
- **The filter narrows hard**: 9.6 species per genus before, 1.7 after, and
  exactly one survivor in 46 of 82.
- **The floor is usually the truth**: the cheapest survivor equalled the actual
  species value in 61 of 82 cases, median ratio 1.00, worst case 0.18.
- **87% of the surviving wrong candidates are region-gated.** A galactic-region
  lookup would remove most of what is left, and is the obvious next tightening.

## Decision

**A reading that names genera carries `atLeastMCr`: the sum over those genera of
the cheapest species the body's own conditions still allow.** It is a floor and
its name says so. `firstFootfall` multiplies it by the same five as ADR-0029,
on the same evidence and for the same reason.

**A floor, because a floor cannot be wrong in the direction that matters.**
Excluding the true species is the only failure that would make Kairon lie, and
the measurement says the filter does not do it; including species that are not
there only lowers the floor. This is also why region-blindness is acceptable to
ship: ignoring a constraint can only widen the candidate set, and a wider set can
only lower a minimum. A ceiling, or a range, or a best guess would each need the
opposite property and none of them has it.

**A genus that survives nothing contributes nothing**, and if no genus survives
there is no field. One body in 83 had no candidate for its genus; the sum over
the rest is still a floor over the rest. The turn falls back to saying nothing
about money rather than to saying something it cannot support.

**No species is named.** The filter leaves exactly one candidate in 56% of
cases, and in this Commander's journals that candidate was always right — but
naming a species is a claim that is either correct or a fabrication, while a
floor understates and stays true. The money is what was asked for. Naming the
organism is a separate decision with a separate risk, and it can be made later
against the same measurement.

**The rulesets live in the registry file**, per species, beside the value that
ADR-0028 already put there. One file, one loader, one strict validation; a
second file for the same organisms would be two things to keep in step. The
generator already downloads exactly these modules — it reads their names and
values and discards their rulesets — so this is a widening of what is kept, not
a new source.

**The conditions are already recorded and nothing new is stored.** ADR-0025's
`PlanetBody` carries planet class, atmosphere, atmosphere type, volcanism,
surface gravity, surface temperature and surface pressure, straight from the
detailed `Scan`, because that is what a body *is*. All six inputs are therefore
a lookup in the current-system registry — no new field, no second copy, and no
change to `BodyProfile`. None of them is added to `context.body` either, which
stays what it was made: what a body is, in four fields, and not an instrument
dump.

**Volcanism reads `Any` as "some volcanism, of any kind".** The upstream writes
three shapes — the string `None`, the string `Any`, and a list of substrings
matched case-insensitively against the body's volcanism text. All three
readings of `Any` were measured (a literal substring match, no constraint at
all, and volcanism-present) and the first and third are indistinguishable on
this data: recall 100%, 1.67 candidates per genus, one survivor in 46 of 82.
Reading it as no constraint is looser — 1.89 candidates and one survivor in only
28 — so the tighter reading is taken, and it is also the one the word means.

## Consequences

**Kairon now says something the game did not say.** The mitigation is the shape
of the claim, not a disclaimer: a floor, from a pinned table, over a candidate
set that the measurement says contains the truth. If a body ever pays less than
`atLeastMCr` said, this is the ADR to reopen, and the first suspect is a game
update the pinned revision predates.

**The unit and the rounding are ADR-0029's**, unchanged: millions to one
decimal, because the field exists to be spoken.

**One money figure per turn still holds.** `atLeastMCr` is on the survey turn
and `valueMCr` on the analysis turn; a body's `bodyTotalMCr` replaces
`valueMCr` on the turn that finishes it. No turn carries two.

**The fabrication watch continues.** ADR-0029 named the thing to watch — a
credit figure on a turn carrying no money field — and this adds a second: a
figure that is not the one sent. A floor spoken as a certainty ("this body will
pay 12 million") is a misreading the prompt line must not encourage, and the
line therefore says *at least*.
