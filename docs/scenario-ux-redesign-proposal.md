# Scenario UX redesign — the workbench comes home, and the diff becomes an editor

**Status: proposal.** Covers the two releasability blockers in the current scenarios UX:
the workbench living in a separate window, and the reconcile view being a fix-chip table
rather than an editor. Supersedes the *UI* described in
[`scenario-assertion-model.md`](./scenario-assertion-model.md) §"Reconciling a failure";
the assertion **model** in that document is untouched and is the foundation this builds on.

Mockups for review accompany this document (HTML artifact, one section per screen).

---

## What we keep, and why that is most of the value

The engine underneath the current UI is correct, exhaustively tested, and hard-won:

- **The positional assertion model** — an expectation is an ordered list of `{tag, matcher}`
  rows; the *k*-th row for tag `T` asserts the *k*-th occurrence of `T`. Its invariant —
  *pairing never consults the matcher, so an edit can never silently re-aim an assertion* —
  is the property everything else exists to protect (`ExpectationEvaluator.align`,
  pinned by `AlignmentPropertiesTest`'s ~465k-pair oracle).
- **The matcher vocabulary** (`exact · presence · absent · regex · oneOf · numeric ·
  temporal · reference`) and the seeder's type-driven choices.
- **The pure reconcile operations** in `ScenarioReconcile` — `acceptActual` (kind-preserving),
  `loosen`, `drop` (whole-tag), `addAssertion` (insert-where-it-pairs), `acceptNewOrder`
  (verified reorder), `moveBlock` (entry-bounded manual move), `reseed` (reference-keeping) —
  and every guard they carry (`canAcceptActual`, the withheld-move reasons, the role-swap
  refusal).
- **The runner, codec, capture, report, and the reconcile-route discipline** (a failure
  either routes to reconciliation or says exactly why it cannot).
- **The shape-vs-behaviour verdict** — "only the value change alters what this scenario
  checks" is the single most useful sentence in the current UI.

What gets replaced is the *presentation*: how these operations are laid out, invoked,
and where they live. The current implementation's own pain points make the case:

1. **Entries are reconstructed by heuristic on every render.** The model deliberately has
   no groups, so `entryRegions`/`longestRepeat` re-derive repeating-group boundaries by
   period-detection over tag sequences, and `addAssertion`/`reorder` brute-force candidate
   placements until the engine agrees. Correct — after three rounds of review — but it is
   structure the dictionary already knows, thrown away and re-guessed.
2. **The diff is a six-column table with chip buttons.** Expected and actual are two text
   columns in one row; there is no visual alignment, no group banding, no way to see an
   entry as a thing. Every fix is a labelled button, so the row area is half chrome.
3. **Two editing surfaces, one job.** `ExpectationBuilder` (authoring, previews against the
   golden) and `ReconcileView` (repair, diffs against the failing actual) are separate
   implementations with separate row models — and only one of them has undo.
4. **A separate window.** The workbench cannot see the session grid it captures from, the
   tinted rows a run produces, or the detail panel — the context a tester is working in.

---

## Part 1 — Scenarios move into the main window

Two pieces, following the app's two existing layout idioms:

### 1a. The Scenarios rail — a docked tool pane, like the message editor

A narrow left-docked pane using the exact pane idiom `App.kt` already has
(`if (show) { Box(width = maxWidthPx * ratio) { … }; draggable divider }`), sitting
beside the message editor's slot. It is the *test-runner tree* view of scenarios:

- The scenario list, each row expandable to its steps.
- Live run status per scenario and per step (`✓ / ✗ / ▸ running / – not run`), fed by
  `scenarioResult` — the same data the run report renders today.
- A failed step row carries the **Reconcile →** affordance (or the refusal reason,
  verbatim from `reconcileRoute`).
- Top actions: **Capture from sessions**, **New scenario**, Open folder.
- `fixtool_panel "scenarios"` keeps working — it toggles this pane instead of a window.

The rail is cheap to leave open. It is the ambient answer to "what do my regressions say
right now", which a separate window never was.

### 1b. Scenario documents open as tabs in the centre, beside the session tabs

The `TabBar` already switches the centre pane between sessions. Scenario *documents* —
the flow editor, a capture review, a reconcile diff — join it as closable tabs with a
distinct glyph (`⚙ rfq flow v2`, `⇄ Step 2 · reconcile`), the way an IDE mixes editors
and diff tabs in one strip:

- **Width.** A two-sided diff and the step editor need the centre; a 28 %-wide dock
  cannot hold them. The rail stays narrow because documents do not open inside it.
- **Context is one click, not one window.** The session tab with the tinted grid rows is
  a neighbouring tab; in SPLIT view modes the scenario document occupies one split while
  a live session stays visible in the other.
- **Deep links stay honest.** "Reconcile assertions →" (run report, rail, or the message
  viewer) opens/focuses the reconcile *tab* for that step — same `WorkbenchEditRequest`
  plumbing, different landing surface. No `toFront()`, no window-state bugs.

`ScenarioWorkbenchWindow` and the `Mode.List/Capture/Edit` switcher are deleted;
`ScenarioListPane` content becomes the rail; `ScenarioCaptureReview` and `ScenarioEditor`
become document tabs. All state already lives on the shared `FixMessageViewModel`, so
this is a re-homing, not a rewrite of the wiring.

### Capture, in context

Capture review as a document tab regains the link to its source: selecting a candidate
highlights the source message in its session grid and the detail panel (the grid's
selection plumbing — `selectMessage` — already exists). The include/exclude checklist,
the send/expect previews, and the id-echo badges are kept as they are.

---

## Part 2 — The Expectation Diff Editor

One surface replaces both `ReconcileView` and `ExpectationBuilder`. It is an
**IntelliJ-style two-sided diff where the left side is a live editor**:

```
┌ rfq flow v2 › Step 2 · Expect ExecutionReport(8) · TRADE ──────────────────────────┐
│ [STRICT ▾]   4 failing · 12 ok · 1 unjudged   ↑ ↓   ⟲ undo  ⟳ redo                │
│              [Accept all shape changes] [Re-seed from received]   [Cancel] [Save]  │
├──────────────────────── expectation (editable) ──┬─────── received (read-only) ────┤
│ 35  MsgType      [exact ▾] 8                    ═│═  35  MsgType      8            │
│ 11  ClOrdID      [ref   ▾] ${id0}               ═│═  11  ClOrdID      ORD-7f3a     │
│ 151 LeavesQty    [numeric▾] 0.0 ±0            ✗ ─│─  151 LeavesQty    500000    «  │
│ ╭ NoPartyIDs · entry 1 ─ FIRMA · Executing ──────┼──── entry 1 ─ FIRMB · Clearing ╮│
│ │ 448 PartyID    [exact ▾] FIRMA              ⇅ ╲│╱   448 PartyID     FIRMB     « ││
│ │ 447 …                                         ╳│╳   …                           ││
│ ╰─────────────────────────────────────────────╱ │ ╲──────────────────────────────╯│
│ 58  Text         [exact ▾] filled             ✗ ─│    (not sent)              × «  │
│                                                  │+  2376              Y        «  │
├──────────────────────────────────────────────────┴────────────────────────────────┤
│ 3 edits staged · nothing is written to the scenario until you save        [⟲ last] │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

### The two sides

- **Left — the expectation.** Every row is directly editable in place: the matcher kind
  is a dropdown chip, the value a text field, exactly the `MatcherEditor` vocabulary.
  Rows can be added (`+ assertion`, seeded from the dictionary), deleted, multi-selected.
  This *is* the expectation editor — there is no other one.
- **Right — the received message** (`wireRaw`, venue order — never the display string),
  read-only, rendered like the detail panel: tag, name, value, enum description.
- **Alignment** comes from the engine's `align()` — the same pairing the runner judges
  with, so the view cannot draw a diff the engine didn't. Rows pair across the gutter;
  left-only rows (missing) face a gap; right-only rows (unasserted/added) face a gap on
  the left; paired rows with a failing matcher are value mismatches.

### The gutter

The centre gutter carries the chunk connectors and the *apply* controls, IntelliJ-style:

| Chunk | Band | Gutter action |
|---|---|---|
| pair, matcher satisfied | none | — |
| pair, value mismatch | red | `«` **accept actual** (kind-preserving re-seed) — hidden where `canAcceptActual` refuses (temporal, reference) |
| left-only (missing) | red/grey | `×` drop · `∅` assert absent (where legal) |
| right-only (unasserted) | blue | `«` assert it (inserted where it pairs) |
| moved entry (engine-proven) | violet, crossing connector | `«` **accept new order** — one click, atomic |
| whole step wrong | — | toolbar: re-seed from received |

Every apply is one of the existing pure operations; the gutter is just a better place to
put the same buttons. Bulk actions (**Accept all shape changes**) keep their guard:
value mismatches are never included.

### Groups become visible — without entering the engine

A **structure overlay** derived from the QuickFIX `DataDictionary` group definitions
(the same source `HierarchicalGridView` uses to render the grid's tree) brackets rows
into entries *for presentation and for bounding moves*:

- Entry header bands: `NoPartyIDs · entry 1 — FIRMA · Executing`, with a per-entry hue
  and indent guide; nested groups indent further. Count tags (`453`) styled as group
  tags, as in the grid.
- Hovering an entry highlights its aligned counterpart on the other side.
- The dictionary replaces `entryRegions`/`longestRepeat` period-detection as the
  *primary* source of entry boundaries; the structural heuristic remains only as the
  fallback for groups the dictionary does not know. The rotation trap
  (`scenario-assertion-model.md` §"What an entry is") is closed by construction: a
  dictionary entry cannot start mid-entry.
- **The engine never consults the overlay.** Assertion stays positional. The overlay
  bounds what a drag may do; `moveBlock`-equivalent validation still decides whether the
  move is legal. Two renderers disagreeing about structure was the old defect seam — so
  there is exactly one overlay, built once per (dictionary, message), feeding both sides.

### Moving tags and groups

Order is asserted in both modes — STRICT absolutely, OPEN for the rows it lists — so an
out-of-order tag is a real failure. The drags exist to make the fix as cheap as the red.

- **Every row carries a drag handle** (visible on hover; `alt+↑/↓` moves the selected
  row from the keyboard). Entries carry one on their band. While dragging, the insertion
  line previews the landing and a tooltip answers the only question that matters —
  *would every row pass here?* — before the mouse is released.
- **Entries move as units** — drag the entry band (or ↑/↓ on it), within their group.
  Legal moves apply instantly and re-judge; illegal ones snap back with the withheld-move
  reason shown inline (the existing hand-written sentences — a role swap explains that
  the entries did not move).
- **Scalar rows move freely** — a hand-authored expectation whose rows are out of wire
  order (the OPEN `moved` false red) is fixed by dragging the row or accepting the order.
- **The refusal rule generalises cleanly:** a move is offered iff it preserves every
  repeated tag's occurrence mapping — a single row of a repeated tag never crosses its
  same-tag siblings (that drop would silently swap *which occurrence* each row asserts);
  everything else is free. The refusal is per-drop, not per-row, and the reason renders
  at the cursor. Same invariant, one sentence, no special cases.

### Live re-judging

Every edit — matcher change, value keystroke, drop, insert, move, mode flip — re-runs
`diff(draft, actual)` and repaints statuses, bands, and the header verdict immediately.
The user watches the step go green as they resolve it; a fix that doesn't fix shows red
*before* they save, which is the property the current view enforces after-the-fact with
verification passes. Temporal rows are judged at `actualAt` (the message's arrival), and
reference rows render as a third state — **unjudged** (amber, dashed) — never as pass or
fail, excluded from the headline counts.

The "this step asserts nothing" banner survives: an expectation whose rows are all
dropped or `presence`-loosened is called out before it can be saved as a green that
checks nothing.

### Undo, redo, cancel

Edits are commands (`SetMatcher`, `AcceptActual`, `Drop`, `InsertAssertion`, `MoveEntry`,
`AcceptOrder`, `Reseed`, `SetMode`) on a `ReconcileSession` state holder — the current
`history`/`StagedFix` stack, formalised and lifted out of the composable:

- **⌘Z / ⌘⇧Z** walk the stack both ways (the current UI has undo-last only, no redo).
- **Cancel** discards the draft and closes the tab; a dirty tab confirms first.
- **Save** writes the scenario, exactly as today — nothing touches disk before it.
- The footer keeps the staged count and the promise, verbatim: *"nothing is written to
  the scenario until you save."*

One editor means authoring inherits all of it: a never-run step opens the same diff
against its **golden** message instead of a failing actual — same rows, same gutter,
same undo. `ExpectationBuilder`'s "verify generalizes" (judge against a second captured
instance) becomes a reference-message picker on the same surface: *golden · second
instance · last received*.

---

## Part 3 — Comparison semantics as a seam, not a setting

STRICT and OPEN differ only in what they do about unmentioned tags; both are LCS-shaped
sequence alignments. The future the product wants — graph-based comparison, GumTree-style
move detection, ordered tree edit distance — differs in *how rows pair*, not in what a
diff looks like afterwards. So the seam goes between pairing and presentation:

```kotlin
interface ComparisonSemantics {
    val id: String            // "strict" · "open" · later: "tree-ted", "gumtree"
    val label: String
    fun align(expectation: Expectation, actual: MessageView): AlignmentModel
}

// The one shape the diff editor renders, whatever produced it:
class AlignmentModel(val chunks: List<Chunk>)
class Chunk(
    val kind: ChunkKind,      // SAME · VALUE · LEFT_ONLY · RIGHT_ONLY · MOVED
    val left: List<RowRef>, val right: List<RowRef>,
    val moveLink: ChunkId?,   // MOVED chunks point at their counterpart
)
```

- `StrictSemantics` and `OpenSemantics` wrap today's `align()`/`judge()` unchanged.
- A **tree semantics** (Zhang-Shasha ordered TED, or GumTree's top-down/bottom-up
  matching) builds its trees from the same structure overlay, and its edit script —
  update / insert / delete / **move** — maps one-to-one onto `ChunkKind`. The UI already
  renders moves as first-class chunks with crossing connectors, so a semantics that finds
  *more* moves (GumTree's specialty) lights up the same visuals with no UI change.
- **The invariant is the contract, not the algorithm:** any semantics may consult tags,
  positions, and value *equality* to pair — never whether a matcher would pass. That rule
  is what `AlignmentPropertiesTest` pins for the current aligner; it generalises into a
  property harness every registered semantics must pass before it can be selected.
- The mode chip in the editor header is the semantics selector. A scenario stores the
  semantics it was authored under; the editor can preview a failure under another one
  (read-only) without rewriting the scenario.

### Auto-fix, later, for free

An auto-fixer — heuristic or LLM-driven — is a producer of `FixPlan = List<EditOp>` over
an `AlignmentModel`. The diff editor previews a plan as staged-but-unapplied chunks with
checkboxes ("apply 7 safe fixes — 2 need review"), and applying routes through the same
command stack: individually undoable, judged live, guarded by the same `can*` rules.
No new surface, no new persistence, no new safety argument.

---

## What is discarded

Per the decision that dev effort does not weigh here — ranked purely by what the design
needs:

- `ScenarioWorkbenchWindow` and the workbench `Mode` switcher (`ScenarioWorkbench.kt`) —
  replaced by the rail and document tabs.
- `ReconcileView.kt` (the six-column table, `VerdictBar`'s six counters, `RowFixes`,
  `EntryArrows`, `MovedBlockHeader`, `NoMoveNote`) — replaced by the diff editor.
  The *sentences* it renders (verdict, refusals, footer promise) are kept verbatim.
- `ExpectationBuilder.kt`'s separate row model and preview loop — merged into the diff
  editor's authoring mode.
- `entryRegions`/`longestRepeat` as the primary structure source — demoted to fallback
  behind the dictionary overlay.
- The run-report → reconcile *window* hop — deep links land on tabs.

And one model-level fix the reports surfaced: **steps gain stable ids**, so run results
address a step by identity instead of index, and `reconcileRoute`'s "edited since run"
refusal narrows to *the step that actually changed* instead of any edit anywhere.

## What is explicitly out of scope

- Any change to the assertion semantics, matcher vocabulary, wire format, or the
  runner. Scenarios on disk stay valid byte-for-byte (plus the additive `stepId`).
- The control surface and MCP tools — `fixtool_panel "scenarios"` toggles the rail;
  everything else is untouched.
- New comparison algorithms themselves — this proposal builds the socket, not the plugs.

---

## Review checklist (what the mockups show)

1. **Main window** — Scenarios rail docked left, run tree with a failing step, scenario
   document tabs beside session tabs.
2. **The diff editor, mid-failure** — value mismatch, added tag, missing tag, one moved
   entry with crossing connector, group bands with per-entry hues, gutter applies.
3. **Direct editing** — matcher dropdown open on a row, live re-judge flipping rows
   green, staged-edit footer, undo/redo.
4. **A withheld move** — role-swap case: arrows present, refusal reason inline.
5. **Capture review as a tab** — candidate list linked to the session grid.
6. **Semantics selector** — STRICT/OPEN today, tree/GumTree slots visible but disabled.
