# Reconcile bulk repair — the plan learns new repairs, and a repair learns to travel

**Status: slices A and C implemented (2026-07-20).** Slice C's apply is *staged, not
saved*: cross-step fixes write into the scenario **draft** through the same door a
session stages through, so Save remains the only door to disk and the current step is
never silently saved — this supersedes the "one save" wording below, honoring D5's
preview-or-nothing and the one-shot revert (spent on use, stale on save or the next
run). The preview sheet lists every reached row; per-row checkboxes on it are a
follow-up, not shipped. Two
implementation notes against the text below: an author-declared `oneOf` widens on *any*
field (the author decided its kind, exactly as an author-made `Numeric` widens off the
numeric families — the dictionary gate applies to `Exact` rows only), and the plan's
apply on the control surface is `applyFix=[row indexes]` on `/scenarios/reconcile`,
staging through the open session's own `EditOp.fixPlan`.

Extends the fix plan shipped in `b179de5` (see
`ScenarioReconcile.fixPlan`, `FixPlanSheet`) beyond numeric/temporal widening, and gives
any per-row repair a way to apply itself everywhere the same failure occurs. Builds on
the assertion model in [`scenario-assertion-model.md`](./scenario-assertion-model.md)
and the diff surface from
[`scenario-ux-redesign-proposal.md`](./scenario-ux-redesign-proposal.md); neither is
reopened. Mockups are a published Artifact, not repo files:
<https://claude.ai/code/artifact/4607c232-d4f9-46d8-93f3-c6de85bf068e> (5 annotated
screens — entry point, repair-plan sheet, gutter offers, sibling banner, cross-step
sheet — keyed to the sections below).

---

## The gap, in six failure scenarios

The matcher vocabulary has `Regex`, `OneOf` and `Presence` (`Matcher.kt`), but **no
reconcile repair — per-row or bulk — can produce any of them**. A failing value row can
be pinned (`Accept actual`), banded (`±`, numeric family only), tracked (`$`), or
dropped (`×`). Everything else is hand-editing. Concretely:

- **S1 — venue-minted values the seeder didn't catch.** A custom tag (5xxx) or an
  ID field the dictionary doesn't type seeds `Exact` and fails *every* run with a fresh
  value. Accept-actual is a treadmill: green today, red tomorrow. The honest repair is
  `Presence` — unreachable.
- **S2 — enum drift within a benign set.** OrdStatus comes back `2` where capture saw
  `1`. The fix plan *correctly* refuses `±` on enum-coded ints — and then offers
  nothing. The honest repair is `oneOf [1,2]` — unreachable.
- **S3 — pattern-preserving drift.** `ORD-2026-0117` → `ORD-2026-0245`: the *shape* is
  the assertion. The honest repair is `regex /ORD-2026-\d+/` — unreachable.
- **S4 — the same failure in many places.** Four party entries whose `448` all changed
  the same way; a venue release that makes the same tag fail identically in 30 steps.
  Every repair is one click per row, per step. Nothing says "do that again, everywhere
  it applies."
- **S5 — deliberate venue-side change.** N rows fail with the *same* expected→actual
  substitution the author knows is intended. Bulk accept-actual is deliberately absent
  (value-blind acceptance eats regressions); there is no safe middle.
- **S6 — business dates on T+n replay.** `SettlDate`/`TradeDate` seed `Exact` and go
  red on every replay after capture day. **Out of scope here** — the honest repair is an
  anchored date-offset, which is new matcher vocabulary and gets its own proposal.

Two slices. **Slice A** teaches the plan (and the gutter) the missing repairs for
S1–S3. **Slice C** makes any repair travel for S4–S5. Option B from the option review —
free multi-select over rows — is deliberately deferred until A+C have been used in
anger; most of its cases are absorbed by C's substitution scope.

## Principles carried over, not renegotiated

1. **One decider.** `ExpectationSeeder` classifies fields; the plan and the gutter
   consume the classification. A second opinion eventually disagrees with the seed
   (`numericFamily`'s doc says why). New families follow the same rule.
2. **Previewed, then staged as one edit.** Every bulk action shows each row, its
   current matcher, its proposed matcher, and a reason sentence — before anything is
   staged. Applying is one edit, one ⌘Z (`EditOp.fixPlan`).
3. **Marked, never hidden.** A row a policy cannot fix stays visible and red in the
   preview. A plan that hides what it cannot fix lies about what "apply" does.
4. **No false greens.** `452 ± 3` accepts seven meanings while reading like a
   tolerance. The same trap exists for every new class: an over-wide `oneOf` or an
   anchor-free regex is the same lie in different clothes. Every gate below exists to
   refuse it.

---

## Slice A — the repair plan

### A1. Proposal classes

`fixPlan` today emits two classes (numeric `±`, temporal ladder). It gains three. Every
class proposes only on rows with `status == VALUE` that are not moved, not unjudged, and
whose matcher is not `Reference` — the existing refusals, unchanged.

**`∈` oneOf widening (S2).** Gate: the dictionary knows enum values for the tag
(`DataDictionary.hasFieldValue(tag)`), and the matcher is `Exact` or `OneOf`. Proposal:
the current value set with the actual appended (`Exact("1")` failing against `2` →
`OneOf(["1","2"])`; an existing `OneOf` grows by one). The reason decodes both meanings
from the dictionary — *"PartiallyFilled(1) | Filled(2) — both meanings now admitted"* —
so the author reads what they are accepting, not just the digits. When the union grows
past 3 values the reason appends a warning: *"now admits N meanings — if any value is
acceptable, presence is the honest assertion"*. Still offered; the author decides.

**`≈` regex inference (S3).** Gate: matcher is `Exact`, the field is STRING-typed *or*
unknown to the dictionary (custom tags), not enum-coded, and inference is **tight**.
Inference: longest common prefix + longest common suffix of expected and actual
(regex-escaped); both varying middles must be non-empty and drawn from the same
character class, tried narrowest first — `\d+`, `[A-Z]+`, `[a-z]+`, `[A-Z0-9]+`,
`[A-Za-z0-9]+`.
Tight means: at least one literal character survives in prefix+suffix. No literal
anchor → no proposal from this class (an unanchored `[A-Za-z0-9]+` asserts nothing and
reads like it does). The proposed pattern is verified to full-match **both** expected
and actual before it is offered — a proposal that does not repair is a bug, and one
that stops matching the golden breaks re-baselining. Reason: *"ORD-2026-\d+ — the
shape both runs share; the varying run is the venue's counter"*.

**`∃` presence demotion (S1).** Gate: identifier family — a new
`ExpectationSeeder.identifierFamily(tag, dictionary)`: STRING-typed (or
dictionary-unknown), field name ending `ID` or `RefID`, and **no** enum values in the
dictionary (TradingSessionID is enum-coded; it is not an identifier in this sense).
Proposal: `Presence`. Offered **only when the regex class produced nothing** for the
row — a tight pattern is strictly more honest than presence, so regex wins precedence.
Reason: *"the venue mints this fresh per run — assert it arrives, not what it says. If
this value should be stable, Accept actual instead — a changed stable id is a
regression."*

Class disjointness: numeric family, enum-coded, and the regex/presence gates cannot
overlap by construction (numeric types vs `hasFieldValue` vs STRING-non-enum), so a row
receives **at most one proposal**, and precedence only ever arbitrates regex vs
presence.

### A2. Default-checked follows what the proposal still asserts

The sheet gains per-row checkboxes (A3). Their defaults encode one rule:

> **A proposal that still constrains the value defaults on; a proposal that stops
> constraining it defaults off.**

`±`, temporal, `oneOf`, `regex` default **checked** — each still pins the value to a
band, a set, or a shape, and each reason names exactly what widened. `presence`
defaults **unchecked** — it is the one class that asserts strictly less than the author
had, so it takes a deliberate opt-in (one click on the class header checks the whole
class; the S1 treadmill author knows exactly which rows those are). This is the bulk
analogue of the per-row rule that value mismatches are accepted deliberately or not at
all.

### A3. The sheet, regrouped

`FixPlanSheet` becomes the **repair plan** (verdict-line button: `fix N…`, warning
color, unchanged position; the `±` glyph comes off the label because bands are no
longer the whole story):

- Proposals grouped by class, fixed order: `±` numeric · `~` temporal · `∈` oneOf ·
  `≈` regex · `∃` presence. Each group header: glyph, class name, count, check-all /
  uncheck-all.
- The uniform-tolerance knob stays, visually scoped to the numeric group — it never
  meant anything to the other classes.
- Each row: checkbox · tag · name · `current → proposed` · reason. Unchecked rows
  render dimmed, never removed (marked, never hidden). The still-red counter counts
  checked rows whose `repairs == false` (uniform mode only, as today).
- **Apply N** stages only the checked rows, as one edit, one ⌘Z. N is the checked
  count; zero checked disables the button.
- The empty-state copy updates: *"Nothing left that widening, oneOf, a pattern or
  presence would fix — the remaining failures are shape changes or value regressions,
  which this plan deliberately does not touch."*

### A4. One decider for the gutter and the plan

Extract the per-row proposal into
`ScenarioReconcile.rowProposal(row, dictionary, tolerancePolicy): PlannedFix?` — the
plan maps failing rows through it; the gutter renders **the same call** as the row's
loosen-family offer (`∈`, `≈`, `∃`, or `±` by class, replacing today's numeric-only
`loosenOffer`). The gutter and the plan can then never disagree about what a row is
offered — the property test in the test list pins it. Per-row presence keeps its
warning in the tooltip; a single click is already deliberate, so no checkbox semantics
apply.

(Temporal rows gain a per-row ladder offer for free here — today the ladder is
plan-only. Same decider, same reason sentence.)

### A5. Control surface parity

The reconcile payload on the control surface (`fixtool_reconcile` / the
`/scenarios/reconcile` route) carries the plan: class, tag, index, current, proposed,
reason, repairs — and accepts an apply with an explicit row list (the checkbox model,
wire-shaped). Agent-driven repair sees what the author sees and can take the same
subset. Exact JSON shape decided at implementation; parity is the requirement.

---

## Slice C — a repair that travels

### C1. Within the step

After a per-row repair is applied, the session looks for **siblings**: other failing
rows with the same signature.

- For a loosen-family repair (`±`/`∈`/`≈`/`∃`/temporal): same tag, same class,
  `rowProposal` fires for them too.
- For **Accept actual**: same tag *and the same expected→actual substitution* — the S5
  gate. `FIRMA→FIRMB` on four `448` rows is one intent; `448` failing four *different*
  ways is four decisions, and no banner appears.

When siblings exist, the footer shows a banner (the `RefusedMove` idiom — transient,
dismissed by the next edit):

> *Applied to 448 · 3 more rows fail the same way (FIRMA→FIRMB) — apply to:
> [this step] [all steps] [dismiss]*

**[this step]** stages one composite edit over the sibling rows — one footer line, one
⌘Z. Drop never travels: `dropTakesWholeTag` already shows why bulk-dropping repeated
tags silently re-aims survivors, and "Accept all shape changes" owns that territory.

### C2. Across steps

**[all steps]** needs what no `ReconcileSession` has — the run. A run-level coordinator
(`RunRepair`, owned by the ViewModel beside the last-run state, same lifespan table)
walks every other failing step of the last run, re-derives rows via
`ScenarioReconcile.rows` against that step's reply, and collects the rows matching the
signature — **re-gated in their own step** (`rowProposal` / `canAcceptActual` per row;
a tag that is a `Reference` or moved in another step is refused there exactly as it
would be locally — one decider, everywhere).

The result opens as a preview sheet in the plan-sheet idiom, grouped by step: step
label, then rows with checkboxes (defaults per A2), current → proposed, reason.
**Apply** writes the affected steps' expectations into the scenario in **one save**,
and the banner it leaves behind carries a one-shot **Revert** — the ViewModel keeps the
pre-apply scenario snapshot until the next run, save, or scenario switch. The current
step's own staged edits stay staged in-session, untouched — cross-step apply never
silently saves the step you are still looking at.

### What C deliberately does not do

No cross-step apply without the preview sheet, ever — a bulk edit across steps the
author never saw is the false-green machine at scale. No travel for Drop, mode
changes, capture, or track (capture/track are wiring, not repairs; their names are
step-scoped). No "apply to all future runs" policy store — the scenario file *is* the
policy.

---

## Follow-ups this proposal creates but does not build

- **The scenario-wide plan (option D)** is a composition: a `run this plan on every
  failing step` affordance in the repair-plan sheet, feeding C2's preview grouped by
  step. Build after A and C ship.
- **S6 business dates** — anchored date-offset matcher (`TODAY+2` shapes). New
  vocabulary, own proposal.
- **Multi-select (option B)** — revisit only if real failures surface that A's classes
  and C's signatures don't reach.

## Test list

`RepairPlanTest` (service):

- oneOf proposed only where the dictionary has enum values; union preserves listed
  order and appends the actual; reason carries decoded names; >3 values appends the
  presence warning.
- regex proposals full-match both expected and actual; metacharacters in the literal
  parts are escaped; no proposal without a literal anchor; narrowest character class
  wins; enum-coded and numeric-family tags never reach this class.
- presence proposed only for `identifierFamily` rows and only when regex produced
  nothing; reference/moved/unjudged rows get no proposal from any class; a row gets at
  most one proposal.
- every default-on proposal in CoverBoth mode has `repairs == true` by construction.

`ReconcileSessionTest` (one decider):

- for every failing row of a corpus expectation, the gutter's loosen-family offer and
  `fixPlan`'s proposal for that index are the same matcher — the property that A4
  exists to pin.
- applying a checked subset stages exactly those rows as one undoable edit; presence
  rows arrive unchecked; check-all on a class checks only that class.

`RunRepairTest` (C):

- accept-actual siblings require the same expected→actual pair; differing actuals
  produce no banner.
- cross-step collection re-gates per step: a sibling that is Reference/moved/refused in
  its own step is absent from the preview.
- apply writes one scenario save; revert restores the pre-apply scenario byte-identical;
  the snapshot dies on next run/save/switch.
- the in-view step's staged edits are never saved by a cross-step apply.

Scenario tests: the S1 treadmill (custom tag, two runs, presence opt-in ends it), the
S2 enum drift (oneOf survives the next flap), the S5 rename (one repair, banner, all
steps, revert) — each end-to-end over a real session, in the style of the existing
scenario guards.

## Decision log

- **D1 — regex beats presence** on the same row: a tight pattern asserts strictly more.
- **D2 — the default-checked rule** (A2): still-constrains → on; stops-constraining →
  off. The bulk analogue of "value mismatches are accepted deliberately."
- **D3 — drop never travels** (C1): `dropTakesWholeTag` and "Accept all shape changes"
  already own bulk removal; a travelling drop re-aims survivors silently.
- **D4 — substitution signature for accept-actual** (C1): same tag alone is not
  intent; same tag + same old→new pair is.
- **D5 — cross-step apply is preview-or-nothing** (C2), and revert is one-shot and
  run-scoped — a policy store would make the scenario file stop being the truth.
- **D6 — S6 excluded**: date-offset matchers are vocabulary, not repair policy.
- **D7 — the class may be overruled, in one direction, per class** (2026-07-20, after
  the shipped sheet met the case none of S1–S6 named: the plan classifies correctly and
  the author wants *none of those values asserted at all*). `[→ ∃ presence]` on a class
  header rewrites that group's proposals to `Presence`. Three constraints keep it from
  becoming the second opinion A4 exists to prevent: it is **per class, not per row**, so
  it cannot disagree with the decider one row at a time; **presence is the only target**,
  because it is the only operand-free matcher and so the only one writable over a group
  without inventing a per-row value nobody chose; and it is a **view over the plan**, not
  a reclassification — the row keeps its class, the preview shows what would be staged,
  and one click restores the proposals. Note this is the case a travelling repair
  structurally cannot serve: `siblings` is same-tag by construction (`r.tag != fix.tag`),
  and the rows an author wants demoted together are usually different tags.
