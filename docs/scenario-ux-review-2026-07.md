# Scenario capture / run / reconcile — UX & UI review (July 2026)

> **Outcome (fixed 2026-07-16, same day, single slate — 1364 tests green, all flows re-verified live):**
> every finding below is **fixed** except the three noted here.
> - **#2 (first-replay failure)** was fixed *differently than proposed*: the seeder already emits
>   `numeric ±0` / `presence` / `temporal` — the gap was the repair, not the seed. Silent default
>   tolerances were rejected (a quiet tolerance on a price is a regression the scenario stops catching);
>   instead the reconcile gutter gained an explicit one-click **`±` Loosen** offer that widens a numeric
>   row to cover both sides (exact decimal-string arithmetic), staged and undoable, with the tooltip
>   naming the use case. The gutter widened 56→84dp so all three offers («, ±, ×) stay visible.
> - **#5 second half (bind-row column grid)**: the disconnect is fixed (`+ constraint` now sits under the
>   rows it inserts); full column alignment with the Msg-type/Position header row is **deferred** — the
>   pickers have different natural widths and forcing a shared grid made both rows worse.
> - **Window title says "reconcile" while authoring** (a P2 candidate that was never numbered):
>   **won't fix** — the title is the `?window=reconcile` selector contract in AUTOMATION.md and the
>   verify skill; renaming it breaks every automated screenshot gate.
> - Also deferred, noted inside the findings: editable setup steps (#18 got its warning suffix), a
>   notification history (#19 got sticky errors + dedupe), a persisted hide-hints setting (#8's ⓘ costs
>   one glyph, so there is little left to hide).

Method: drove a release build end-to-end over the control surface plus real mouse input
(demo server, DEMO_CLIENT1) — captured from sessions via the toolbar, edited the scenario,
ran it green and red, opened the reconcile diff, repaired a failure, exercised the paste
capture, then deleted the scenario and closed the session with the failure banner showing.
21 screenshots were taken along the way and every finding below was verified against the
code. Screenshot references (`shot NN`) are the session's evidence set; the key ones were
attached to the review conversation.

Findings are ranked by defect severity. Fix recommendations are included per finding.

---

## P0 — the tool contradicts what is true

### 1. The last-run result outlives everything it is about, and cannot be dismissed
*The reported "lingering error message". Reproduced exactly, both ways.*

`_scenarioResult`, `_lastRunScenario` and `_assertionResults` are written by a run
(`FixMessageViewModel.kt:2032`, `:2134`) and cleared **nowhere**:

- `deleteScenario` (`FixMessageViewModel.kt:1254`) closes the scenario's documents and
  diff windows but leaves all three untouched. After deleting the scenario, the rail
  still shows `Last run — Captured scenario: FAILED (4/5 steps)`, the failure detail,
  and a live **Reconcile assertions →** button (shot 09).
- `closeSession` (`FixMessageViewModel.kt:1798`) leaves them too. With **zero** sessions
  open and the scenario deleted, the red FAILED banner and its button are still the first
  thing in the rail (shot 11).
- The session grid keeps the run's red/green row overlays for a scenario that no longer
  exists (shot 09) — `assertionResults` is never invalidated either.
- `RunStatusLine` (`ScenariosRail.kt:452`) has **no dismiss affordance**. Even a valid
  failure banner sits there until the next run, forever.

**The stale button is worse than the stale text.** The reconcile route is computed with
`remember(result)` (`ScenariosRail.kt:93`), keyed only on the result — deleting the
scenario does not recompute it, so the button stays *Open*. Clicking it re-checks the
route and toasts: *"Scenario 'Captured scenario' is not saved — there are no assertions
on disk to reconcile"* (shot 10) — misleading wording (it was deleted, not "not saved"),
and the dead button remains after the toast.

**Proposed lifespan model for a run result** (the "what is the message's life span"
question). A run result is a claim about `(scenario-id, the session log it ran against)`.
It should live until the first of:

| event | what should happen |
|---|---|
| the same scenario is run again | replaced (works today) |
| **its scenario is deleted** | result + grid overlays cleared |
| **the session it ran against closes** | result + overlays cleared (or banner demoted to "stale — session closed", no button) |
| its scenario is edited & saved | banner marked *stale — scenario changed since this run*; per-step routes already handle this by step identity |
| **the author dismisses it** | a small ✕ on the status line — clears result + overlays |
| another scenario is deleted / other sessions close | nothing (result still valid) |

Concretely: clear the three states in `deleteScenario` and `closeSession` (when the
closed session is the one the run used), add `dismissRunResult()` behind a ✕ on
`RunStatusLine`, and key the rail's route on the scenario store revision as well as the
result so refusals render inline instead of leaving a stale button.

### 2. A freshly captured scenario fails its own first replay
Captured a 2-order flow (limit + market), saved untouched, ran it: **FAILED**, failed
tags `6, 31 LastPx` (shot 07) — the market order refilled at a different price, and
capture seeded `exact` matchers on price fields that legitimately vary per run
(`ExpectationSeeder`). ClOrdID and TransactTime are already parameterized
(reference / temporal), so the machinery exists; per-run-variant numerics are the gap.

This is the first-ten-minutes trust killer: the tool's own capture produces red against
an unchanged venue, and the user's first lesson is "red doesn't mean anything".

Recommend: seed price/qty-fill fields (`LastPx`, `AvgPx`, `CumQty`, `LeavesQty`, …
dictionary type `PRICE`/`QTY` on execution-report-like replies) as `numeric ±` with a
sensible tolerance or `presence`, **or** have the first-run failure banner say "these
tags vary between runs — open the diff and loosen them" so the red teaches the loop
instead of undermining it.

### 3. Two different pass-counts for the same run, one above the other
Status line: `FAILED (4/5 steps)` — counts **all phases** including the injected setup
`clear` (`ScenariosRail.kt:465`). Rail row for the same run: `3/4` — counts the `steps`
phase only (`ScenariosRail.kt:151`). Both visible simultaneously (shot 07). Pick the
steps-phase count for both; mention setup only when setup itself failed.

---

## P1 — core surfaces look and behave unprofessionally

### 4. Assertion editor (diff) — the alignment and annotation defects
*The reported "primitive / unprofessional expectation section".*

- **Ragged matcher-parameter column** (shots 04, 06, 12): each matcher type renders its
  own widths — `exact` 130dp, `numeric` 80+«±»+56, `reference` 180, `regex` 120 with a
  "pattern" label that appears from nowhere, `temporal` dropdown+48+"s". The column edge
  is ragged, rows change shape when the type changes, and tolerance boxes don't align
  across rows (`MatcherEditor.kt:117-185`). Fix: a two-column grid — type dropdown
  (fixed) + a params cell of **constant total width**; put "pattern"/"any of" inside the
  field as placeholder text instead of ad-hoc labels that shift the row.
- **The "unjudged here — resolves at run time" note wraps into a 4-line ragged fragment**
  squeezed against the right edge, quadrupling the ClOrdID row height (shots 12, 14).
  Give the note a real slot: ellipsize with a tooltip, or place it under the value.
- **Unlabeled gutter glyphs**: `«`, `✗`, `⇄` carry no tooltip (`DiffSurface.kt:816-830`;
  `SlimButton` has none). These are the three most consequential clicks on the surface
  — "Accept actual value", "Drop assertion", "Accept new order" — and the author must
  guess them. Add `TooltipArea`s.
- **The verdict line wraps and argues with itself** (shot 14): after repairing the one
  failure it reads *"every checked assertion would now pass … | 12 tags added · 1 not
  checkable here | nothing here changes what this scenario checks — it is all shape"*,
  wrapping mid-sentence. In OPEN mode the 12 "added" tags are *ignored by definition* —
  say `12 extra tags ignored (OPEN)` or drop them from the verdict; keep the line to one
  line.
- Window title + crumb say **reconcile** even when authoring assertions on a step that
  never failed (shot 04, `DiffWindow.kt:119`). Minor, but it names the wrong activity.

### 5. Expect step editor — the "expectation section" is a dead end and its inputs don't line up
- `AssertionsDoor` renders literally `16 asserted rows.` + a button (shot 03,
  `ScenarioEditor.kt:609-634`). For the one thing the step exists to say, show a compact
  read-only summary — tag → matcher chip, first N rows, `+12 more` — so the author can
  see *what* is asserted without opening the diff; keep the door for editing.
- The bind ("Binds to") rows don't align with each other: the header row (`Msg type`
  220dp, `Position` 140dp) and the constraint rows (64+120+96+170dp) share no column
  grid (shot 03, `ScenarioEditor.kt:655-712`), and `+ constraint` sits beside Position
  while new rows appear *below* — the insertion point is visually disconnected. Align the
  constraint grid under the header row's columns.

### 6. Capture writes to disk instantly, under a default name — the "Captured scenario" pile-up
Clicking toolbar **Capture from sessions…** saved `Captured scenario` to the store
*before any Save click* (the file list grew immediately; shots 01→02). The user's store
already held **four** identical "Captured scenario" rows plus a "(copy)" — five
indistinguishable files (shot 01), with no per-row date or last-run info to tell them
apart. Recommend either: capture opens as an **unsaved draft** (dirty tab, reaches disk
on Save — the editor already has the dirty-confirm machinery), or auto-derive a name
(`D→8 EUR/USD · 16 Jul 11:04`) and focus the Name field on open.

### 7. The paste capture's input is a single-line field asking for multiple lines
`PASTE WIRE — ONE MESSAGE PER LINE` sits over a `SlimField` with `singleLine = true`
(`ScenarioCaptureReview.kt:642`, `SlimComponents.kt:63`). A 3-line paste keeps its
newlines in state but **displays as one line** — lines 2 and 3 are invisible and
uneditable, while refusal lint reports "line 2 / line 3" errors about text the user
cannot see (shots 16, 17). Make it a real multi-line area (4–6 rows, scrollable).

### 8. Guidance text is always-on in the editor (reported issue — capture is fixed, editor is not)
Capture review already folded its paragraph behind an ⓘ (`CaptureHelp`,
`ScenarioCaptureReview.kt:380`). The editor still spends rows on every visit:

- Send: the `${...}` expressions paragraph (`ScenarioEditor.kt:465`);
- Expect: the two-line "Binds to — …" paragraph that wraps awkwardly and ends in a
  dangling colon (`:648`, shot 03);
- Wait: `e.g. LOGGED_ON`; Clear: its one-liner; AssertionsDoor's sentences.

Recommend the capture pattern everywhere: one ⓘ per section with the same words in a
tooltip — plus a persisted `hide hints` toggle in AppSettings for the day the user has
learned the tool (progressive disclosure, reversible from Settings).

### 9. Wrong empty-state copy in paste mode
With a paste box open, the empty state still says *"Nothing to capture: no business
messages in any session. **Drive the flow in the main window first.**"* (shots 15, 16,
`ScenarioCaptureReview.kt:175-182`) — it sends the user away from the flow they are in.
Branch on `paste != null`: "Nothing captured yet — paste wire above, one message per line."

---

## P2 — polish details (each small, together they are the "not professional" feel)

| # | finding | evidence / code |
|---|---|---|
| 10 | Rail header button row overflows: **"Folder" renders as "Fo"** at default rail width | shot 01; `ScenariosRail.kt:273-285` — no overflow handling in the Row |
| 11 | Scenario name runs into the step count with no gap: `…regression1/2` | shots 15–19; `ScenariosRail.kt:345` — count has `end` padding only |
| 12 | Failed step label hard-clips against the Reconcile button: `Expect ExecutionReport (8) · 17` ("tags" cut) | shot 07; `ScenariosRail.kt:420-426` — `maxLines=1` without `overflow=Ellipsis` |
| 13 | Paste-capture tab is titled **"capture: capture"** | shot 15; tab title falls back to the doc kind when the name is blank |
| 14 | Paste refusal lint shows literal `**` markdown asterisks, and repeats the same 2-line lecture per refused line | shot 16; `WirePaste` lint copy; group repeats: "3 lines refused — same reason" |
| 15 | Pasted candidates show the **paste time** (all identical), not the message's SendingTime(52) | shot 17 — three rows all `11:13:35.177` against `52=09:00:0x` |
| 16 | Send preview leads with six `dropped (session/transport header)` rows before the payload | shot 17; collapse to one line: "6 transport headers dropped (8, 9, 34, 49, 52, 56)" |
| 17 | Failed-tag list names tags only when the dictionary knows them: `failed tags: 6, 31 LastPx` | shot 07; render unknown tags as `6 (?)` so the bare number reads as deliberate |
| 18 | Auto-injected setup `Clear messages` wipes the session log on every run, and the editor shows it as an uneditable sentence | shots 02, 07; `ScenarioEditor.kt:232-240` — at minimum say "clears the session log each run"; ideally make setup steps editable/removable |
| 19 | Error toasts auto-dismiss after 10s with no history — a missed `Scenario run failed` toast is gone forever | `NotificationPopup.kt:67`; keep ERROR until dismissed, or add a notifications log |
| 20 | Empty-rail copy says "over the MCP tools" — developer jargon in end-user UI | `ScenariosRail.kt:99` |
| 21 | Reconcile window reopens centered at 1100×900 every time — author's size/position not remembered within the session | `DiffWindow.kt:61` |
| 22 | Rail run/edit/duplicate/delete are 18dp targets, delete (red) adjacent to duplicate; inline confirm mitigates, but spacing or an overflow menu would be safer | `ScenariosRail.kt:346-357` |

---

## What already works well (keep)

- The reconcile diff itself is the strongest surface in the feature: staged edits with
  undo/redo, the footer promise ("nothing is written until you save"), refusals that say
  why, `Accept actual` one-click repair with the staged label (`Accepted 44 = 1.08500`),
  and the honest "unjudged" treatment of reference rows (shots 12, 14).
- Failure → rail auto-expand at the failing step, with `Reconcile →` on the step row.
- The undirected-paste flow: refusing to save until direction is settled, with the
  per-row `which way? ▶Send ◀Expect` toggle (shot 17), is exactly right.
- Save-from-capture lands in the editor tab, named, one click after the name (shot 19).
- The paste lint refusing bad checksums with the exact reason (shot 16) — the *content*
  is right even where the presentation (P2 #14) needs work.
