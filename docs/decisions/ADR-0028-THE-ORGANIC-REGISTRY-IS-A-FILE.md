# ADR-0028: The organic registry is a file

## Status

Accepted. This decision covers what the registry is, what shape it has, where
its contents come from, and what it must never hold.

Implemented: the file, the generator that writes it, `kairon.bio` and its strict
loader, the `bio.registryFile` configuration, and every model-facing name behind
`DecisionOrganicNames`. `ModelFacingReplayBaselineTest` moved by four hunks, all
of them organism names and nothing else, and `CURRENT_STATE.md` records the
diff.

Not done: a live session under it. What the registry costs or buys in what
Kairon actually says is unmeasured. Of the two facts the file carries, the
species' sample value is now read — by the analysis turn under ADR-0029 and by
the GUI's price table — and the genus's colony distance still reaches the model
nowhere.

## Context

Kairon does not know what an organism is called. It repeats the word the journal
happened to write, or it spells the game's internal symbol. Three readings of
that, all taken from the live trace of 2026-08-08 and from the journals under
`Saved Games`:

**The document carries the game client's language.** `DecisionNames.field` maps
`ORGANIC_SAMPLING_VARIANT_LABEL` to `organism`, and the value is
`Variant_Localised` — whatever the game wrote in the language the *game* is set
to. Traced, inside an otherwise English document: `"organism":"Бактерия Aurasus
- лайм"`, `"Стратум Excutitus - изумруд"`, `"Тубус Cavas - серый"`.

**A genus name is not a name.** `DecisionNames.genusField` cuts
`$Codex_Ent_<word>_Genus_Name;` down to `<word>` and sends that. Traced:
`"organisms":["Bacterial"]`, and the same words in `context.biology.collected`
and `remaining`. Ten of the game's genera are called something else:

| symbol stem | what the game calls it |
|---|---|
| `Bacterial` | Bacterium |
| `Shrubs` | Frutexa |
| `Tussocks` | Tussock |
| `Conchas` | Concha |
| `Cactoid` | Cactoida |
| `Fonticulus` | Fonticulua |
| `Fungoids` | Fungoida |
| `Aleoids` | Aleoida |
| `Fumerolas` | Fumerola |
| `Ingensradices` | Radicoida |

That is wrong in every language, including the one the symbol is written in.

**The two disagree inside one turn.** A sampling turn names the organism in
Russian and the genus inventory beside it in symbol stems. The document has two
naming rules and neither was chosen.

The consequence nobody chose either: **which language Kairon names organisms in
is currently decided by the game's settings.** Set the client to English and
Kairon goes on speaking Russian while naming every organism in English.

### What the journal establishes, and what it does not

Every organic record carries a pair: a symbol and a rendering of it.

```
"Genus":"$Codex_Ent_Bacterial_Genus_Name;", "Genus_Localised":"Бактерии"
"Species":"$Codex_Ent_Bacterial_10_Name;",  "Species_Localised":"Бактерия Bullaris"
"Variant":"$Codex_Ent_Bacterial_10_Yttrium_Name;", "Variant_Localised":"Бактерия Bullaris - красный"
```

The symbol is the identity and is the same on every client. The rendering is
display text in the client's language. This is not a Kairon reading of the
format: EDDN strips every `*_Localised` key from its schemas for exactly this
reason, and `TaxonName` already states the rule — the identifier is compared,
the label never is.

What the journal does **not** establish is the name in any other language. A
Russian client emits Russian and nothing else. Harvesting a Commander's own
journals therefore yields exactly one language, however many hours are in them.
This repository's journals hold 540 `ScanOrganic` records covering 12 genera,
34 species and 47 variants — all Russian.

### Why the registry holds no price multiplier

The sale bonus was considered as a registry field and is refused. Three readings
of `SellOrganicData` in this repository's journals decide it:

- **It is not a property of the species.** Clypeus Lacrimam was sold twice, once
  as `"Value":8418000, "Bonus":33672000` and once as `"Value":8418000,
  "Bonus":0`. A species table would attach the bonus to the wrong thing.
- **It is not data, it is one constant.** Every non-zero bonus observed is
  exactly four times the value — base plus 400%, a payout of five times over.
  One number in a decision record, not a column repeated 118 times.
- **It cannot be known before the sale.** The game decides at Vista Genomics and
  states the result there. Anything Kairon computed earlier would be a forecast,
  and a forecast denominated in credits is the most fabrication-prone field this
  project could add. The gravity line produced invented figures in three of one
  evening's turns, and the signal-count line produced "9 biological signals
  detected" on a turn carrying no count at all.

`Value` in a sale record is also the sum over a batch, not a unit price:
`15236736` is `119037 × 128`. Reading it as the price of one sample would write
nonsense into the registry, which is what the divisibility check in the
generator's report exists to catch.

## Decision

**One registry, one file, read from a configured path.** `bio.registryFile` in
`kairon.json`, defaulting to `./config/organic-registry.json`. `null` is a
deliberate "no registry" and is allowed — every name then falls back to the
journal's own label, which is today's behaviour. A path that is set and cannot
be read or parsed is a startup failure, loud, like every other strict loader in
this project.

**The identifier is the identity, everywhere.** `id` is the game's
`$Codex_Ent_…_Name;` symbol. Nothing is keyed, compared, merged or deduplicated
by a name in any language.

**Three tables, one level each.** `genera`, `species`, `variants`. A species
names its genus, a variant names its species, both by identifier. Arrays rather
than keyed objects, so a duplicate `id` is a loader failure instead of a silent
last-one-wins.

**A name is per language, and the document uses `observer.outputLanguage`.**
This supersedes the paragraph in `DecisionNames.genusField` that chose symbol
stems to keep Cyrillic out of the request. The objection recorded there was that
the document's own vocabulary moved with a setting nothing else moved with — and
the setting was the *game's*. Naming from `observer.outputLanguage` answers that
objection rather than ignoring it: one rule, chosen by Kairon's configuration,
applied to `organism`, `organisms`, `collected` and `remaining` alike.

**The lookup falls back and never invents.** Registry name for the configured
language, else the journal's own label, else the name is not sent at all. An
identifier the registry does not know is not an error — Frontier adds organisms —
it is a diagnostic and a line in the generator's next report.

**Nothing is composed at runtime.** Every string a reader can return is a string
that is literally in the file. The game itself is not consistent enough to
compose against: it writes `"Бактерия Bullaris - красный"` and `"Бактерия
Informem - Кобальт"`, one colour lower-case and one capitalised. Composition is
the generator's job, and a composed string that cannot be verified against an
observed one is not written.

**Reference facts are stored and not sent.** `valueCr` on a species and
`colonyDistanceM` on a genus are recorded because they are sourced game facts
and because the first is what makes the generator's output checkable against
real sales. Neither reaches the model without its own measured decision, under
the rule that has already retired three graph-derived fields.

**No multiplier, no derived price, no computed worth.** See above.
**Superseded three days later by
[ADR-0029](ADR-0029-A-FINISHED-SAMPLE-SAYS-WHAT-IT-PAYS.md)**, which keeps the
first two readings — the bonus is not a species fact and is one constant, not a
column — and revisits the third: what is known at the analysis is not the sale
but the footfall, and a body nobody has walked on is a body nobody has sampled.

**The file is generated, never hand-edited, and the upstream is pinned.**
`OrganicRegistryGeneratorTest` is opt-in, reads two pinned upstream revisions and
the Commander's own journals, and writes the whole file. Hand-editing it would
survive exactly until the next regeneration. No upstream file is copied into this
repository; the pins and licences are recorded in `THIRD_PARTY_NOTICES.md`.

## Consequences

**Kairon's naming stops depending on the game's language setting.** That is the
point of the whole exercise, and it is what makes the registry worth its weight
rather than a lookup table for words the journal already supplied.

**Coverage is uneven, on purpose.** English is complete because the upstream
tables are complete. Russian covers what these journals have seen plus what
composes and verifies against them; the rest falls back to the journal label at
runtime, which is what happens today. A body whose organism has neither is named
by nothing, and that is the same silence the contract already uses everywhere
else.

**One genus cannot compose and will not.** Radicoida's Russian rendering
translates the epithet — `Radicoida Unicus` is `Подобокорень уника` — so the
"genus word plus Latin epithet" rule that holds for the other eleven genera
observed here does not hold for it. The generator detects that by verifying and
declines to compose for that genus at all.

**Values drift with the game.** Update 15 revalued exobiology wholesale. The
`_meta` block records the date and the upstream revisions the numbers came from,
and the generator's report checks every `valueCr` against every
`SellOrganicData` record it can see. That check is what will notice the next
revaluation.

**Switching the model-facing names onto the registry is a behaviour change.**
`ModelFacingReplayBaselineTest` will differ in every turn that names an organism.
The diff is the evidence for that step and belongs in its commit, not in this
one.
