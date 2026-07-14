# Scenario UX redesign — phased implementation plan

**This document is the tracker.** Whoever executes this plan (agent or human) edits it in
place: tick the checkboxes as items complete, update the status table, and commit the plan
change together with the work it describes. A box may only be ticked when its acceptance
test exists and passes.

**What is being built** is specified in
[`scenario-ux-redesign-proposal.md`](./scenario-ux-redesign-proposal.md) (the decisions)
and its mockups. The assertion semantics are specified in
[`scenario-assertion-model.md`](./scenario-assertion-model.md) and **do not change**.
Read both, fully, before Phase 0. Read
[`scenario-assertion-model.md` §"Testing this model"](./scenario-assertion-model.md#testing-this-model--and-how-a-green-suite-lies-to-you)
twice — every serious defect in this area shipped under a green suite because a fixture
dodged the hard case.

## Status

| Phase | Title | Status |
|---|---|---|
| 0 | Engine seams (no UI) | not started |
| 1 | The diff surface, standalone | not started |
| 2 | Rail + document tabs; the window dies | not started |
| 3 | The diff surface becomes the only expectation editor | not started |
| 4 | Drag moves, undo/redo, keyboard | not started |
| 5 | Reference slot: paste, pick, provenance | not started |
| 6 | The plain diff viewer | not started |
| 7 | Cleanup, docs, final verification | not started |

---

## Ground rules (apply to every phase)

**Invariants that must survive every commit.** Breaking any of these is a stop-the-line
defect, not a trade-off:

1. **Pairing never consults the matcher.** `ExpectationEvaluator.align` pairs by tag and
   position only. No new code path — semantics, overlay, drag validation, seeding — may
   pair a row to an occurrence because a matcher would pass there.
2. **The engine never reads the group overlay.** `GroupOverlay` (Phase 0) is consumed by
   rendering and move-bounding only. `ExpectationEvaluator` must not import it; add an
   architecture test (classpath/import assertion) so this cannot regress silently.
3. **Only `wireRaw`/`wireFields` feed diffs and assertions** — never the `|`-substituted
   display string. Pasted references are the user's bytes, parsed as-is, marked as pasted.
4. **Nothing writes to a scenario except Save.** Every edit is staged in the session;
   discard/cancel must restore the original exactly.
5. **Scenario wire format is frozen** except the additive `stepId` (Phase 0). A file that
   loads today loads identically after every phase.
6. **A refused action says why, where the user is looking.** No silently missing button,
   no silently withheld move. The existing refusal sentences are kept verbatim.
7. **Reference/temporal rows are a third state** (`unjudged`), never pass/fail, excluded
   from headline counts. Temporals judge at the reference's instant, never wall-clock now.

**Test discipline** (from the model doc, all learned the hard way):

- Reproduce a bug on the old code before claiming the fix; a failing test that doesn't
  fail without the fix is testing something else.
- Mutation-check every guard you add: delete the guard, watch a test fail, restore it.
- Fixtures must include the hard cases: a pipe inside a FIX value, `wireRaw` set, two
  different firms in a party group, a party group with ≥3 entries and ≥3 tags per entry,
  nested groups (NoPartySubIDs inside NoPartyIDs), and the `onChange` feedback loop wired
  the way the app wires it.
- Wire-order/reordering behaviour cannot be tested against the QuickFIX demo acceptor
  (its bytes are already normalized) — use `tools/fake-venue/` modes `golden|shape|swap`.
- Compose UI tests live beside the existing ones (`composeApp/src/jvmTest/.../ui/`) and
  write screenshots to `composeApp/build/scenario-screenshots/`.

**Process per phase:**

- Work directly on `main`. Conventional commits (`feat:` / `fix:` / `test:` / `docs:` /
  `refactor:`), **no Co-Authored-By / session trailers**. One commit per coherent slice;
  the phase's final commit updates this plan's checkboxes and status table.
- Phase gate before ticking the phase: full `./gradlew :composeApp:jvmTest` (known
  pre-existing reds on main: `TabSelectionTest` env-dependent, ktlint/detekt — do not
  make them worse, do not chase them); app builds and launches; live verification via
  the control surface (`FIXTOOL_CONTROL_PORT`, see `.claude/skills/verify/SKILL.md` and
  `docs/AUTOMATION.md`) with `fixtool_screenshot` evidence for UI phases.
- When a decision is genuinely open, prefer the proposal's text; if the proposal is
  silent, prefer the smallest behaviour that keeps the invariants, and note the decision
  in this file under the phase.

---

## Phase 0 — Engine seams (no UI changes, no visual difference)

Everything later plugs into these. The app looks identical after this phase.

### 0.1 Stable step ids
- [ ] `ScenarioStep` gains `stepId: String` (UUID, generated on creation/capture/load —
      a file without ids gets them assigned on load and keeps them on save; additive
      key in `ScenarioCodec`, `ignoreUnknownKeys` untouched).
- [ ] `StepResult` carries `stepId` alongside `stepIndex`; `ScenarioReport.toJson`
      emits it (additive).
- [ ] `FixMessageViewModel.reconcileRoute` refuses "edited since run" **per step by id**:
      route opens iff the *ran* step (by `stepId`) is byte-equal to the *saved* step;
      edits to other steps no longer block. Update `ScenarioDeepLinkTest` — the
      edited-since-run case must split into "this step edited → refused" and "another
      step edited → still routes".
- [ ] Codec round-trip test: load a pre-`stepId` file → ids assigned, everything else
      byte-identical on save except the added ids.

### 0.2 `ComparisonSemantics` + `AlignmentModel`
- [ ] New `service/compare/ComparisonSemantics.kt`: interface
      `{ id, label, align(expectation, actual: MessageView, resolver, at): AlignmentModel }`
      and `AlignmentModel(chunks: List<Chunk>)`,
      `Chunk(kind: SAME|VALUE|LEFT_ONLY|RIGHT_ONLY|MOVED, left: List<RowRef>, right: List<RowRef>, moveLink: ChunkId?)`.
      Rows keep everything `ScenarioReconcile.Row` carries today (status, unknown,
      occurrence, wireIndex, dictionary name).
- [ ] `StrictSemantics` and `OpenSemantics` implemented as thin wrappers over the
      existing `ExpectationEvaluator.diff` + `ScenarioReconcile.rows/movedBlocks` — no
      behaviour change; golden tests assert the wrapper reproduces today's rows exactly
      on the existing fixture corpus.
- [ ] A semantics **registry** with a registration gate: generalize
      `AlignmentPropertiesTest` into a reusable property harness
      (`SemanticsContractTest`) that every registered semantics must pass — including
      "pairing is blind to matcher outcomes" and "an expectation seeded from a message
      aligns clean against it". STRICT and OPEN pass it; the registry refuses (fails
      fast at startup in dev/test) a semantics that hasn't.
- [ ] Mode selection continues to serialize as `MatchMode` — `strict`/`open` map to
      semantics ids; unknown future ids must fail loudly at load, not silently degrade.

### 0.3 `GroupOverlay` (dictionary-derived structure, presentation-only)
- [ ] New `service/compare/GroupOverlay.kt`: built from
      `FixDictionaryAdapter`/`DataDictionary` group definitions (reuse the recursive
      knowledge in `FixStructure.walk` / `FixMessageHelper.processFields`), mapping a
      flat field list to nested entry nodes `{groupTag, entryIndex, rowRange, children}`.
      One overlay instance feeds **both** sides of a diff (built per side from the same
      dictionary — the two sides must never disagree about where an entry starts for
      identical tag sequences).
- [ ] Fallback: where the dictionary does not know the group, fall back to the existing
      `ScenarioReconcile.entries` period-detection; the overlay says which source it
      used per group (the UI will badge heuristic entries).
- [ ] Entry labels: delimiter value + best identity description from the dictionary
      (e.g. `FIRMA · 1 Executing`), computed here, not in the UI.
- [ ] Tests: nested groups; a 3-entry party group (the rotation trap from the model doc —
      the overlay must produce entry boundaries where period-detection was fooled);
      unknown custom group falls back; **architecture test that `ExpectationEvaluator`
      has no dependency on `GroupOverlay`** (mutation-check: add the import, test fails).

### 0.4 Generalized move validation
- [ ] `ScenarioReconcile.moveRow(draft, fromIndex, toIndex): MoveResult` implementing the
      proposal's one rule: **a move is legal iff every repeated tag's occurrence mapping
      is preserved** — scalar rows move freely; a row of a repeated tag may not cross a
      same-tag sibling; entry-range moves (from the overlay) move as a unit. `MoveResult`
      is `Applied(newExpectation)` or `Refused(why)` reusing the existing sentences where
      they fit and adding the occurrence-swap sentence for single-row refusals.
- [ ] `moveBlock` becomes a caller of the same validation (behaviour today = subset of
      the new rule); `ReconcileMoveBlockTest` stays green unmodified — it is the
      regression net for this refactor.
- [ ] Property test in the `AlignmentPropertiesTest` style: for generated expectations,
      every `Applied` move preserves each row's `(tag, occurrence)` binding; every
      occurrence-swapping candidate is `Refused`. Mutation-check the guard.

### 0.5 Reference anchoring
- [ ] `ReferenceMessage` value type: `{view: MessageView, provenance: THIS_RUN|GOLDEN|SECOND_INSTANCE|PICKED|PASTED, label, anchorInstant}` —
      anchor = arrival instant for live/run messages (as `actualAt` today), the
      message's own `SendingTime(52)` for pasted (fallback: null → temporals render
      unjudged, with the reason).
- [ ] `ScenarioReconcile.rows`/judging accept the anchor from `ReferenceMessage`
      (today's `actualAt` parameter generalized). Test: a pasted message an hour old
      judges its `~now ±60s` row against 52, not the clock.

**Phase 0 gate:** all existing scenario/reconcile/evaluator tests green and unmodified
except where a test asserted the old edited-since-run refusal; new tests as listed; app
behaviour visibly unchanged (run a scenario, reconcile a failure in the current UI).

---

## Phase 1 — The diff surface, standalone

Build the new component next to the old UI; nothing routes to it yet.

### 1.1 `ReconcileSession` (state holder, not a composable)
- [ ] `ui/diff/ReconcileSession.kt`: holds `original`, `draft`, the reference slot, the
      selected semantics; exposes `chunks` (memoized: recompute only on draft/reference/
      semantics change, not on every recomposition); applies `EditOp`s.
- [ ] `EditOp` sealed commands with inverses: `SetMatcher`, `AcceptActual`, `Loosen`,
      `Drop`, `AssertAbsent`, `InsertAssertion`, `MoveRow`, `MoveEntry`, `AcceptOrder`,
      `Reseed`, `SetMode`, `SwapReference`. Each delegates to the existing pure
      `ScenarioReconcile` functions — **no reimplementation of any operation**.
- [ ] Undo/redo stacks (`⌘Z`/`⌘⇧Z` wiring comes in Phase 4; the stacks and `undo()`/
      `redo()`/`canUndo`/`canRedo` land now). `discard()` restores `original`.
      `isDirty` drives save/cancel affordances.
- [ ] Tests: command/inverse round-trips for every op (apply → undo → byte-equal
      expectation); redo after undo; discard after arbitrary sequences; the memoized
      re-judge invalidates on each of the three inputs and not otherwise.

### 1.2 The `DiffSurface` composable
- [ ] `ui/diff/DiffSurface.kt` renders a `ReconcileSession` per the mockups: header
      (crumb, semantics chip, reference chip, verdict line with the existing
      shape-vs-behaviour sentence), two aligned columns, centre gutter, footer (staged
      count, the "nothing is written…" sentence verbatim, undo/redo/cancel/save).
      Left rows: tag · dictionary name · matcher chip (dropdown = `MatcherEditor`
      vocabulary) · value (inline editable). Right rows: tag · name · value · enum
      description. Alignment gaps get the hatched treatment; `unjudged` rows the amber
      third-state treatment.
- [ ] Gutter applies per chunk kind exactly per proposal table: `«` accept-actual
      (hidden where `canAcceptActual` refuses), `«` assert-it, `×` drop
      (whole-tag rule surfaced in its tooltip), `∅` assert-absent (where legal),
      `⇄ Accept new order` on engine-proven moves. Bulk toolbar: Accept all shape
      changes (value mismatches never included), Re-seed from reference.
- [ ] Group bands from `GroupOverlay`: entry header rows with labels, per-entry hue,
      indent guides, nested indent; hover on an entry highlights its aligned counterpart;
      heuristic-sourced entries visibly badged. Moved entries: violet band + crossing
      connector in the gutter.
- [ ] The "asserts nothing" banner: an expectation whose rows are all dropped/presence
      renders the warning before save (reuse the existing detection).
- [ ] Live re-judge: every `EditOp` updates row statuses, bands, and the verdict counts
      in the same frame (via the memoized session, not per-row recomputation).
- [ ] Compose tests + screenshots covering: value mismatch / added / missing / moved /
      unjudged rendering; each gutter apply mutating the draft through the session; an
      edit flipping a row green live; accept-all-shape never touching a value mismatch;
      the empty-assertion banner; hover pairing. Reuse the fixture corpus from
      `ReconcileViewTest` so behaviour parity is checked against the old view's cases.

**Phase 1 gate:** `DiffSurface` demonstrable in a test harness window (temporary,
dev-only) against fake-venue `shape` and `swap` outputs; screenshot set committed.

---

## Phase 2 — Rail + document tabs; the window dies

### 2.1 The Scenarios rail
- [ ] New left-docked pane in `ui/App.kt` following the exact existing idiom
      (`if (show) { Box(width = maxWidthPx * ratio) { … }; draggable divider }`, ratio
      state + clamp like `editorPanelSplitRatio`), in **both** TABS and SPLIT layouts.
      Content: scenario list → expandable step tree with live ✓/✗/▸/– from
      `scenarioResult`, per-scenario Run/Edit/Duplicate/Delete (port from
      `ScenarioListPane`), Capture from sessions…, New, Open folder, last-run line.
- [ ] Failing step rows carry **Reconcile →** or the `reconcileRoute` refusal sentence —
      never silence (port `RunStatusLine` semantics).
- [ ] `showScenariosDialog` flow is renamed/retargeted to toggle the rail;
      `ControlServer.panel("scenarios")` and the toolbar button work unchanged from the
      caller's view (`McpTools` descriptions updated to say "pane").
- [ ] Tests: rail renders run tree states; refusal renders; `fixtool_panel scenarios`
      toggles it (control-surface test).

### 2.2 Scenario documents as tabs
- [ ] `TabBar` gains document tabs alongside session tabs: distinct glyph + accent,
      closable (`×`), `esc` closes focused doc tab, dirty tabs confirm. Document kinds:
      scenario editor, capture review, reconcile diff (Phase 3 routes into it; until
      then the editor/capture docs host the existing `ScenarioEditor` /
      `ScenarioCaptureReview` composables, which are already window-agnostic).
- [ ] `WorkbenchEditRequest` deep-links land on (open-or-focus) the right tab, scrolled
      to the failing step — from the rail, the run line, and
      `openScenarioEditorForFailure` (the message-viewer door). Same single decider,
      three doors, one destination.
- [ ] SPLIT view modes: document tabs occupy the centre pane like a session tab does;
      verify both split orientations render.
- [ ] **Delete** `ScenarioWorkbenchWindow` and the `Mode` switcher from
      `ui/ScenarioWorkbench.kt`; delete the second-window block in `main.kt` (lines
      around the `viewModelRef` plumbing stay only if still needed — prefer deleting).
      `WindowChrome.kt` stays (used by the main window).
- [ ] Tests: deep-link from a failed run lands on the editor tab focused on the step
      (port `ScenarioRunReportTest` / `ScenarioDeepLinkTest` expectations); closing a
      dirty editor tab confirms; capture tab ↔ grid source highlighting
      (`selectMessage` fires on candidate selection).

**Phase 2 gate:** the app has no second window; every workbench capability reachable via
rail + tabs; live verification: capture → save → run → fail → deep-link → edit → save,
entirely in the main window, with screenshots.

---

## Phase 3 — The diff surface becomes the only expectation editor

### 3.1 Reconcile routes into `DiffSurface`
- [ ] The reconcile document tab hosts `DiffSurface` bound to the failing step
      (`ReferenceMessage(THIS_RUN)` from the `WorkbenchEditRequest` — wire bytes +
      arrival instant). `onChange` feeds the editor's step exactly as today
      (`ScenarioEditor` golden-repointing behaviour preserved).
- [ ] Row-level deep link: opening from a clicked failing tag in `MessageDetailPanel`
      scrolls to that row.
- [ ] **Delete `ui/ReconcileView.kt`** and its private pieces (`VerdictBar`, `RowFixes`,
      `EntryArrows`, `MovedBlockHeader`, `NoMoveNote`). Port `ReconcileViewTest`'s
      cases onto `DiffSurface` first — every behavioural assertion in that file must
      have a successor test before the file goes.
- [ ] Save & re-run in the header: saves the scenario, triggers the run
      (`runScenario`), rail verdict updates; disabled while a run is in flight (the
      shared run slot already enforces one run).
- [ ] Guard tests carried over: dropping every row is never a pass; fixes stay staged
      through the feedback loop; a hand move on a role swap cannot fake a pass; the
      arrows/moves land on the entry the author picked.

### 3.2 Authoring is the same surface
- [ ] A never-run Expect step opens `DiffSurface` with `ReferenceMessage(GOLDEN)`;
      no-golden steps (hand-added Expect) open with an empty reference and a prompt to
      bind one (pick/paste — full slot arrives Phase 5; pick-from-grid can land here if
      trivial, otherwise the prompt offers golden only and Phase 5 widens it).
- [ ] "Verify generalizes" = swapping the reference to `SECOND_INSTANCE` (live message
      of the same shape, the existing selection logic ported).
- [ ] **Delete `ui/ExpectationBuilder.kt`** (and `ExpectationDrafts`) after porting its
      test assertions; `MatcherEditor.kt` stays (it is the chip editor inside
      `DiffSurface`).
- [ ] `ScenarioEditor.ExpectDetail` shrinks to: bind-predicate editing + `DiffSurface`.

**Phase 3 gate:** there is exactly one surface in the app that can author or repair an
assertion; the full fail→fix→save→re-run→green loop verified live against fake-venue
`swap` (a reorder: one-click Accept new order) and `shape` (added/missing tags), with
screenshots; all ported tests green.

---

## Phase 4 — Drag moves, undo/redo, keyboard

- [ ] Row drag: handle on hover (`⠿`), insertion-line preview, live would-pass tooltip
      during drag (judged via `moveRow` dry-run against the reference), refused drops
      snap back with the reason at the cursor. All drops route through Phase 0.4
      validation — the UI cannot construct a move the engine didn't approve.
- [ ] Entry drag: band header handle drags the whole entry (overlay range); ↑/↓ buttons
      stay for keyboard/accessibility parity; withheld-move sentences render inline on
      the group (role-swap case).
- [ ] Keyboard: `alt+↑/↓` moves selected row/entry, `⌘Z`/`⌘⇧Z` undo/redo, `↑/↓` row
      selection, `n/p` next/previous diff chunk, `esc` cancel (dirty-confirm).
- [ ] Tests: drag-drop through the session (Compose input injection where feasible,
      session-level tests where not — but at least one real drag UI test); a refused
      drop leaves the draft untouched; undo restores byte-equal drafts across a mixed
      edit/move/accept sequence; mutation-check: disable the occurrence-mapping guard,
      a test fails.

**Phase 4 gate:** the mockup-4 scenarios reproduced live (out-of-order hand-authored row
fixed by drag; refused sibling-crossing drop with reason), screenshots committed.

---

## Phase 5 — Reference slot: paste, pick, provenance

- [ ] Reference chip + swap menu in the `DiffSurface` header: this run · golden ·
      second instance · pick from session… · paste wire…. Swapping re-judges instantly
      (session already supports it; this is the UI).
- [ ] Pick from session: arming the slot lets the next grid row click bind the
      reference (and highlights bidirectionally while bound).
- [ ] Paste sheet: multi-format parse (SOH preferred, `|` accepted — reuse
      `parseFixMessage`'s detection), **lint line reports** delimiter, field count, and
      ambiguity (possible pipe-in-value truncation flagged, never guessed); temporal
      anchor from tag 52 shown; Use as reference.
- [ ] Provenance: chip always names the source; `pasted` badge follows anything saved
      while a pasted reference was bound (persisted as an annotation on the step —
      additive codec key, same freeze rules as 0.1).
- [ ] Capture's second source: capture review gains `source: live sessions | pasted
      wire`; pasted mode = one message per line → candidates with per-row direction
      toggle + session dropdown; sends parameterize, replies seed, exactly as live
      capture (reuse `ScenarioCapture.captureFrom` on synthesized candidates).
- [ ] Tests: paste with pipe-inside-value flags the lint (the historical bug case as a
      fixture); pasted temporals anchor to 52 (and render unjudged when 52 absent);
      provenance survives save/load round-trip; pasted capture round-trips to a
      runnable scenario; swap-reference re-judges (golden green → this-run red without
      any edit).

**Phase 5 gate:** W2 verified live end-to-end: paste a fake-venue log fragment → capture
review → save → run against the live fake venue → reconcile the differences.

---

## Phase 6 — The plain diff viewer

- [ ] `DiffSurface` left side generalizes to `Expectation | Message`; message-left mode
      renders read-only (no matcher chips, no gutter applies, no save), statuses
      reduce to same/value/only-A/only-B/moved, footer states it is read-only.
- [ ] Entry points: grid multi-select (2) → "Diff selected" (context/toolbar);
      `MessageDetailPanel` → "Diff against…"; rail/toolbar → "Diff messages…" with two
      empty slots (pick/paste each).
- [ ] "Seed expectation from A/B ▾" seeds via `ExpectationSeeder` and flips the tab
      into editor mode with the other side as reference; "add to scenario…" files it as
      an Expect step (scenario/step picker; new scenario allowed).
- [ ] Tests: viewer mode cannot mutate anything (architecture-level: no `EditOp` except
      `SwapReference`/`SetMode` accepted); two-message alignment on the fixture corpus;
      seed-then-edit produces a valid step that round-trips and runs.

**Phase 6 gate:** W3 verified live: two grid rows → diff; UAT-style paste vs live
message → diff → seed → step added to a scenario → run.

---

## Phase 7 — Cleanup, docs, final verification

- [ ] Demote `entryRegions`/`longestRepeat` to the documented fallback path (only
      caller: `GroupOverlay`); delete anything now uncalled (`bracketsFor` UI plumbing,
      old workbench-only helpers). `rg` for dead references to the deleted composables.
- [ ] `resources/help.html` §12 rewritten for rail/tabs/diff surface/paste/diff viewer;
      `docs/AUTOMATION.md` + `McpTools`/`index.mjs` descriptions updated where they
      mention the workbench window; `syntax.md` untouched (semantics unchanged —
      verify by diff).
- [ ] `scenario-assertion-model.md` §"Reconciling a failure" gets a pointer to the
      proposal + this plan (its UI description is superseded; its model is not).
- [ ] Regenerate the screenshot evidence set (`build/scenario-screenshots/`) from the
      new UI tests; delete stale PNGs.
- [ ] Full suite + detekt/ktlint status vs the known baseline; live smoke of W1/W2/W3
      once more; final commit updates this plan to all-complete with a short
      "deviations from plan" section appended (empty is a valid answer, silence is not).

---

## File disposition summary

| File | Fate |
|---|---|
| `service/ExpectationEvaluator.kt`, `model/scenario/Matcher.kt`, `ScenarioRunner/Capture/Codec/Report/Service`, `ExpectationSeeder` | untouched (0.1/0.5 additive params excepted) |
| `service/ScenarioReconcile.kt` | kept — ops become `EditOp` delegates; `moveRow` added; entry heuristics demoted to overlay fallback |
| `service/compare/ComparisonSemantics.kt`, `GroupOverlay.kt` | new (Phase 0) |
| `ui/diff/ReconcileSession.kt`, `ui/diff/DiffSurface.kt` | new (Phase 1) |
| `ui/ReconcileView.kt` | deleted (Phase 3.1) after test porting |
| `ui/ExpectationBuilder.kt` | deleted (Phase 3.2) after test porting |
| `ui/ScenarioWorkbench.kt` window + `Mode` | deleted (Phase 2.2); list content → rail |
| `ui/ScenarioEditor.kt`, `ui/ScenarioCaptureReview.kt` | kept, re-homed into tabs; `ExpectDetail` shrinks (3.2); capture gains paste source (5) |
| `ui/MatcherEditor.kt`, `ui/ScenarioUi.kt`, `ui/WindowChrome.kt` | kept |
| `main.kt` second-window block | deleted (Phase 2.2) |
| `ui/App.kt`, `ui/TabBar.kt`, `ui/Toolbar.kt` | modified: rail pane, document tabs, toolbar wording |
| `control/ControlServer.kt` `panel("scenarios")` | retargeted to the rail, endpoint unchanged |
