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
| 0 | Engine seams (no UI) | **complete** |
| 1 | The diff surface, standalone | **complete** |
| 2 | Rail + document tabs; the window dies | **complete** |
| 3 | The diff surface becomes the only expectation editor | **complete** |
| 4 | Drag moves, undo/redo, keyboard | **complete** |
| 5 | Reference slot: paste, pick, provenance | **complete** |
| 6 | The diff gets its own window (direction change 2026-07-14) | **complete** |
| 7 | The plain diff viewer | not started |
| 8 | Cleanup, docs, final verification | not started |

---

## Start here — the state of play (updated at the end of Phase 3)

**Phases 0–4 are complete and on `main`.** The engine seams exist; the scenarios workbench is a docked
rail and a set of document tabs in the main window (no second window); and **`DiffSurface` is now the
only surface in the app that can author or repair an assertion** — `ReconcileView`, `ExpectationBuilder`
and `ExpectationDrafts` are deleted. A scenario has exactly **one unsaved draft** (the *workspace*),
however many documents are looking at it.

**Phase 4 is done.** Rows and entries drag, `⌘Z`/`⌘⇧Z`/`alt+↑/↓`/`↑↓`/`n`/`p`/`esc` work, the **violet
crossing connector** is drawn, and a drag answers *would every row pass here?* before the mouse is released.
Two of its decisions were not input-layer work at all: the move validator's refusal sentence was being
**computed and thrown away** on every call (M1), and the right-hand column at a moved entry was showing the
reply's fields **in an order the reply does not have** (M2) — which is also why the connector had nothing to
cross. Both are fixed; read M1–M9 before touching a move.

**Phase 5 is done.** The right-hand side is a swappable **slot** with a five-source menu (this run · golden ·
second instance · pick · paste), and swapping it re-judges without staging (P8). The **paste reader**
(`WirePaste`) reads a message to READ / UNVERIFIED / REFUSED and **never guesses** — a `|` inside a value is
refused with the message's own checksum as evidence. A step repaired or authored against pasted bytes carries
a `pasted` **origin** that survives the save (the golden is still re-pointed only for THIS_RUN, V4). Capture
gained a **second source** — pasted wire, one message per line, direction read off `SenderCompID(49)` and
refused a save where nothing settles it. Three of the phase's decisions were **live defects** (S1 the verdict
sentence and FAILED chip, S2 a run stealing a hand-bound reference, S10 the fake venue's disarmed pipe);
read S1–S11 before touching the slot.

**Phase 6 is done.** The diff opens in its **own top-level window** now (`ui/diff/DiffWindow.kt`), beside the
grid it is about — `ScenarioDoc.Reconcile` and `ReconcileDocument` are deleted, the state moved to
`DiffWindowState` on the ViewModel (`openDiffWindows`), and the twelve slot/rebind callers moved with it. The
window has its own `rememberWindowState` (the `8f93596` trap, F2), its own chrome and notifications, and raises
itself on a deep-link (F6); `/screenshot?window=main|diff` addresses windows **by title**, deterministic once a
second window exists (F3). A scenario's views are documents **plus** diff windows, and the draft lives until
the last of either closes (F4). Read F1–F11 before touching the window; F1 (the migration IS the phase), F2
(two window states, don't merge), F3 (`firstOrNull()` was already wrong), F4 (the draft lifecycle) are the
load-bearing ones.

> **Direction change (2026-07-14), decided by Amit during review: the diff surface moved into a
> dedicated window** — reconcile, authoring, and the future viewer — the way an IDE opens a diff (Phase 6, now
> **complete**). The rail, the flow editor, and capture review stay in the main window exactly as built. The
> viewer and cleanup phases are 7 and 8. Rationale and rules: proposal §1c. The thing that makes this window
> safe where the old workbench window was not is the Phase-3 workspace: a diff window never holds the only copy
> of unsaved state.

> **The control surface can now open the diff** — `POST /scenarios/reconcile` / `fixtool_reconcile`, through
> the same `reconcileRoute` decider the rail's *Reconcile →* button uses. It is the one thing the automation
> could not do (it cannot click), and it is why the one surface that repairs an assertion had never been
> opened live against a real venue's bytes. A deviation from the proposal's scope, taken deliberately —
> see Phase 4's outcome.

**What exists now, and where:**

| Thing | File |
|---|---|
| Comparison seam + `AlignmentModel`/`Chunk` (`Chunk.pairs` = row → the field it faces) | `service/compare/ComparisonSemantics.kt` |
| Dictionary-derived entry boundaries + labels; `EntrySource.DICTIONARY\|HEURISTIC` | `service/compare/GroupOverlay.kt` |
| The right-hand slot: provenance + the instant temporals judge at; **the swap menu's five options** | `service/compare/ReferenceMessage.kt` |
| **The paste reader — READ / UNVERIFIED / REFUSED, and never a guess** (Phase 5) | `service/compare/WirePaste.kt` |
| **The verdict — counted once; the sentence and chip chosen by provenance** | `service/compare/Verdict.kt` |
| Draft + snapshot undo/redo + the gutter's offers + memoized `DiffModel` + **the selection and the display list** | `ui/diff/ReconcileSession.kt` |
| The two-column diff, the gutter, the group bands | `ui/diff/DiffSurface.kt`, `DiffPalette.kt` |
| **The drag, the keys, the crossing connector, the would-pass tooltip** | `ui/diff/DiffMoves.kt` (Phase 4) |
| **Opening the diff without a hand** — the same route the rail's button takes, now onto a window | `POST /scenarios/reconcile` · `fixtool_reconcile` |
| **Photographing a specific window** — by title, deterministic once a second window exists (Phase 6) | `GET /screenshot?window=main\|diff` · `fixtool_screenshot` |
| **Capturing a paste without a hand** — the same reader the sheet uses (Phase 5) | `POST /scenarios/capture-paste` · `fixtool_capture_paste` |
| **The step's provenance on disk** — `LIVE\|PASTED`, additive, written only when not the default (Phase 5) | `StepOrigin` in `model/scenario/Scenario.kt` |
| **Capture's second source — pasted wire, direction read off `49`** (Phase 5) | `ScenarioCapture.fromPaste`; the paste box in `ui/ScenarioCaptureReview.kt` |
| The docked rail: the run tree, the routes, the actions | `ui/ScenariosRail.kt` |
| A document and its state (the editor/capture tab owns its view-state — see T2) | `ui/ScenarioDocuments.kt` |
| The document tab strip + the host that composes the active one (editor/capture only) | `ui/ScenarioDocumentPane.kt` |
| Documents, diff windows, the centre's selection, the close-confirm, the scenario list | `FixMessageViewModel` |
| **The workspace — one unsaved draft per scenario, whatever is looking at it (documents + windows, F4)** | `ScenarioDraft` (`ui/ScenarioDocuments.kt`); `openScenarios` on the ViewModel |
| **The diff's state: its session, reference slot, undo stack — one per subject** (Phase 6) | `DiffWindowState` (`ui/ScenarioDocuments.kt`); `openDiffWindows` on the ViewModel |
| **The one surface that authors or repairs an assertion, and its only host — a dedicated window** | `ui/diff/DiffSurface.kt` in `ui/diff/DiffWindow.kt`; composed at application scope in `main.kt` |
| ~~Dev bench~~ / ~~the reconcile document tab~~ | deleted (2.2 / Phase 6) |

Steps carry a stable `stepId` (Phase 0); `reconcileRoute` addresses a failure by it, and each diff window is
keyed on `(scenarioId, stepId)` for the same reason — one window per subject, reopened by focus not duplicate.
The `ReconcileSession` lives in `DiffWindowState` on the ViewModel (Phase 6, F5), not in a composable `remember`.

**Commands.** `./gradlew :composeApp:jvmTest` — **1282 tests, 0 failures** is the current bar.
The lint rule is that **your files add none**; measure it by counting findings *per file* on the
pre-phase tree and again after (`ktlintCheck` + `detekt`, `--continue`), because a bare total moves
whenever a file is added or deleted. The tree is currently **1769** by that count, against the 2121 the
same command reports on the pre-Phase-2 tree — 352 fewer, most of them deleted along with the two
surfaces Phase 3 removed.

**Seven traps, all of which cost time in Phases 0–3:**

1. **Never run `ktlintFormat`.** It reformats all 64 files of the module and buries the work in
   a style diff. Fix your own lines by hand, or commit first and then
   format-and-revert-everything-except-the-files-you-wrote-whole.
2. **A UI phase is gated by its screenshots, not its tests.** The worst defect in Phase 1 —
   a gutter offering Accept-actual on a *moved* row, one click from deleting "FIRMA holds role
   1" — was found by looking at the picture, with fourteen tests green. Phase 2's rail shipped a
   tree whose expand chevron was clipped to nothing by `Modifier.size(10.dp)`, so it read as a
   flat list. Also found by looking at the picture. Also with every test green.
3. **A test harness must feed the callback back**, the way the app does. A harness that merely
   records `onChange` once let a completely dead staging mechanism survive seven passing tests —
   and in Phase 2, three capture-review tests went red the moment its state was hoisted, because
   the harness was still dropping `onStateChange` on the floor.
4. **The control surface can toggle panes and run scenarios, but it cannot click.** Everything
   reachable only by a click — opening a document, the gutter, a drag — needs a Compose UI test
   that clicks, and writes its screenshot to `composeApp/build/scenario-screenshots/`. The
   `/screenshot` endpoint captures the main window, which is now the whole app.
5. **Only the active document is composed.** A tab is not the window it replaced: switch away and
   its subtree is *disposed*. Anything the author has typed must live in the document
   (`ScenarioDoc`), not in the composable. Phase 3's `ReconcileSession` — the undo stack, the
   reference slot, the staged count — lives there for exactly this reason: left in a `remember`, the
   author comes back to a footer promising *"0 edits staged · nothing is written until you save"* over
   a draft three edits from disk, which is worse than a lost undo stack.
6. **A sentence is a claim, and the arithmetic behind it is a decider.** Every phase so far has found
   the verdict lying: the entry count fed into the row count; `added` counted twice; and in Phase 3, a
   denominator that never existed (*"4 of 2 rows need attention"*) and — under it — added tags counted
   as failures in **OPEN**, so a passing step painted itself FAILED and *disagreed with the engine*.
   When a rule is already known somewhere (`canAcceptShape` knew this one), the surface that does not
   know it is the bug.
7. **Do not put literal SOH bytes in a source file you will edit again.** Tooling eats them silently,
   and a fixture whose wire has been quietly de-delimited parses as **one field** — which looks exactly
   like a real alignment defect and costs an hour. Write `\u0001`.

**Phase 2's `### Decisions taken before implementation` is written** (below, T1–T8), and it held:
T1 was a live defect — the editor dropped every `stepId` on Save, so the id was a hash of the
position again — and T2 caught the regression the phase would otherwise have shipped.

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

### Decisions taken before implementation — read these first

Five things the checklist below under-specifies or gets wrong. Settled here, with the
reasoning, so the next reader does not re-derive them.

**D1 — The move rule, stated precisely. Supersedes the one-liner 0.4 used to carry.**

*"A move is legal iff every repeated tag's occurrence mapping is preserved"* refuses every
entry swap. When FIRMA's party moves past FIRMB's, FIRMA's `448` **becomes the second**
`448` — the occurrence mapping is precisely what changes, and that is the move the design
calls safe (mockup *"Why moving a row can lie"* §04; `ReconcileMoveBlockTest` pins it
green, and that test may not be modified). The rule that actually holds is:

> **No row is ever separated from the entry it belongs to; a row of a repeated tag may
> cross a same-tag sibling only if its whole entry crosses with it; and no move may land
> strictly inside another entry.**

Every case falls out of it. A top-level scalar is an entry of one — it has no same-tag
siblings, so it moves freely, which is what the proposal wants for a hand-authored row in
the wrong wire order. A lone row of a repeated tag crossing its sibling is the per-row
arrow (§03) and is refused. An entry crossing an entry carries all of itself (§04) and is
allowed. A rotation window is not an entry (§05) and is refused — the overlay is what says
where an entry begins. `moveRow` and `moveEntry` are two callers of one validator, and
`moveBlock` becomes a third, keeping its signature.

> **The rule includes the one-row entry, and the first implementation did not — see R3 in the
> review below.** A group the dictionary defines can have entries one field wide
> (`NoMDEntryTypes`), and such an entry crossing its sibling *is* the whole entry crossing. It
> is only the *heuristic* bracket around a bare repeated tag — `448, 448, 448`, a period-1
> repeat that is not a group at all — whose single rows may not cross. `EntrySource` is what
> tells them apart, and it is the reason the overlay computes it.

One clause is ours rather than the mockup's: **a row may not leave its entry for another
one.** Moving a `452` into an entry that carries no `452` crosses no sibling, so the rule
above would permit it — and it would silently re-aim the row at another party. The live
re-judge cannot catch that (the row goes green). Refused, as the smallest behaviour that
keeps the invariant.

**D2 — The registry gate is a build-time test, not a startup assertion.**

0.2 asks the registry to "fail fast at startup" for a semantics that has not passed the
contract. The contract harness enumerates hundreds of thousands of (message, expectation)
pairs; nothing runs that at app start. So: `SemanticsContractTest` iterates
`SemanticsRegistry.all()` and fails the **build** for any registered semantics that
violates the contract — an unverified semantics cannot ship — plus a cheap startup check
that every `MatchMode` resolves to a registered id. Same guarantee, honest mechanism.

**D3 — `stepId` must be assigned deterministically, or it breaks the route it exists to fix.**

`reconcileRoute` loads the scenario from disk a *second* time to compare it against the one
that ran. If a file with no ids were given fresh random ids on each load, the two loads
would never agree, and **every failure on a pre-`stepId` file would be refused** — the exact
opposite of 0.1's purpose. So a step without an id gets `UUID.nameUUIDFromBytes` over
(scenario id, phase, index): stable across loads.

> **There is no random path, and that makes the ordering of the assignment load-bearing — see
> R1 in the review below.** Every id is a hash of a *position*, so the id minted for a new step
> at index 1 is the id the existing step at index 1 already carries. `withIds` therefore claims
> every id that exists in a **first pass**, and mints for the blanks only in a second: a step
> that has an id can never lose it to a newcomer, and the newcomer salts past the collision. The
> first implementation minted in one pass, and an insertion slid every id below it onto the next
> step down — which is how an id becomes an index with a hash on top.

`stepId` defaults to `""` (unassigned) so the positionally-constructed steps throughout the
existing tests stay equal to one another; a single `Scenario.withIds()` normalizer runs at
codec load, at capture, and at save, and an invariant test asserts a blank id never reaches
disk. Ids duplicated within one scenario are re-minted on load. Equality still *includes*
`stepId` — that is what makes the edited-since-run check exact.

**D4 — Two live defects fall inside Phase 0's scope and are fixed with it.**

- `ScenarioCodec.expectationFromJson` reads the mode as `strict = (mode == "strict")`, so
  **any unrecognised value silently becomes OPEN**. A typo in a hand-edited scenario
  loosens the assertion instead of failing the load — the "silently degrade" case 0.2
  forbids. Fixed there, with the mode → semantics-id parsing.
- `ScenarioReconcile.verbatimWindow` asks `ExpectationEvaluator.satisfies`, which hard-codes
  `Instant.now()` and a null resolver. An entry carrying a temporal row (an `MDEntryTime`
  in `NoMDEntries`) or a reference row can therefore never be recognised as moved-verbatim,
  so **Accept new order is silently withheld for market-data groups** — a red with no button
  and no reason, which is the failure mode this area keeps producing. The anchor threading in
  0.5 fixes it. Reproduce it on the current code first.

**D5 — Three shapes the mockups need, which Phase 0 is the only place to produce.**

- `Reorder.Possible` gains `placement: Map<Int, Int>` (row index → wire index). The violet
  crossing connector and `Chunk.moveLink` have no other source of truth. Additive; no
  behaviour change.
- `GroupOverlay` entry labels carry the mockup's form — `NoPartyIDs · entry 1 — FIRMA ·
  1 Executing`: the delimiter's value **and** an identity field's enum description, both
  computed in the overlay, never in the UI.
- Rows keep their occurrence (the mockup renders `448#2` in the tag column);
  `Alignment.occurrence` already carries it, and `AlignmentModel` must not drop it.

### 0.1 Stable step ids
- [x] `ScenarioStep` gains `stepId: String` (UUID, generated on creation/capture/load —
      a file without ids gets them assigned on load and keeps them on save; additive
      key in `ScenarioCodec`, `ignoreUnknownKeys` untouched).
- [x] `StepResult` carries `stepId` alongside `stepIndex`; `ScenarioReport.toJson`
      emits it (additive).
- [x] `FixMessageViewModel.reconcileRoute` refuses "edited since run" **per step by id**:
      route opens iff the *ran* step (by `stepId`) is byte-equal to the *saved* step;
      edits to other steps no longer block. Update `ScenarioDeepLinkTest` — the
      edited-since-run case must split into "this step edited → refused" and "another
      step edited → still routes".
- [x] Codec round-trip test: load a pre-`stepId` file → ids assigned, everything else
      byte-identical on save except the added ids.

### 0.2 `ComparisonSemantics` + `AlignmentModel`
- [x] New `service/compare/ComparisonSemantics.kt`: interface
      `{ id, label, align(expectation, actual: MessageView, resolver, at): AlignmentModel }`
      and `AlignmentModel(chunks: List<Chunk>)`,
      `Chunk(kind: SAME|VALUE|LEFT_ONLY|RIGHT_ONLY|MOVED, left: List<RowRef>, right: List<RowRef>, moveLink: ChunkId?)`.
      Rows keep everything `ScenarioReconcile.Row` carries today (status, unknown,
      occurrence, wireIndex, dictionary name).
- [x] `StrictSemantics` and `OpenSemantics` implemented as thin wrappers over the
      existing `ExpectationEvaluator.diff` + `ScenarioReconcile.rows/movedBlocks` — no
      behaviour change; golden tests assert the wrapper reproduces today's rows exactly
      on the existing fixture corpus.
- [x] A semantics **registry** with a registration gate: generalize
      `AlignmentPropertiesTest` into a reusable property harness
      (`SemanticsContractTest`) that every registered semantics must pass — including
      "pairing is blind to matcher outcomes" and "an expectation seeded from a message
      aligns clean against it". STRICT and OPEN pass it; the registry refuses (fails
      fast at startup in dev/test) a semantics that hasn't.
- [x] Mode selection continues to serialize as `MatchMode` — `strict`/`open` map to
      semantics ids; unknown future ids must fail loudly at load, not silently degrade.

### 0.3 `GroupOverlay` (dictionary-derived structure, presentation-only)
- [x] New `service/compare/GroupOverlay.kt`: built from
      `FixDictionaryAdapter`/`DataDictionary` group definitions (reuse the recursive
      knowledge in `FixStructure.walk` / `FixMessageHelper.processFields`), mapping a
      flat field list to nested entry nodes `{groupTag, entryIndex, rowRange, children}`.
      One overlay instance feeds **both** sides of a diff (built per side from the same
      dictionary — the two sides must never disagree about where an entry starts for
      identical tag sequences).
- [x] Fallback: where the dictionary does not know the group, fall back to the existing
      `ScenarioReconcile.entries` period-detection; the overlay says which source it
      used per group (the UI will badge heuristic entries).
- [x] Entry labels: delimiter value + best identity description from the dictionary
      (e.g. `FIRMA · 1 Executing`), computed here, not in the UI.
- [x] Tests: nested groups; a 3-entry party group (the rotation trap from the model doc —
      the overlay must produce entry boundaries where period-detection was fooled);
      unknown custom group falls back; **architecture test that `ExpectationEvaluator`
      has no dependency on `GroupOverlay`** (mutation-check: add the import, test fails).

### 0.4 Generalized move validation
- [x] One validator implementing **D1's rule** (not the one-liner this section used to
      carry — see the decisions above): no row leaves its entry; a row of a repeated tag
      crosses a same-tag sibling only if its whole entry crosses with it; no move lands
      inside an entry.
- [x] `ScenarioReconcile.moveRow(draft, fromIndex, toIndex): MoveResult` — the per-row
      drag, legal for a scalar (an entry of one), refused for a lone row of a repeated tag.
- [x] `ScenarioReconcile.moveEntry(draft, overlay?, entry, toSlot): MoveResult` — an
      entry-range move to any sibling slot within its group (the proposal's entry drag;
      today's `moveBlock` only swaps with the adjacent sibling). A null overlay falls back
      to the `entries` heuristic, which is what keeps the old callers behaving identically.
- [x] `MoveResult` is `Applied(newExpectation)` or `Refused(why)`, reusing the existing
      refusal sentences where they fit and adding the occurrence-swap sentence for the
      single-row refusal.
- [x] `moveBlock` keeps its signature and becomes a caller of the same validator;
      `ReconcileMoveBlockTest` stays green **unmodified** — it is the regression net for
      this refactor, and it is what proves D1 subsumes today's behaviour.
- [x] Property test in the `AlignmentPropertiesTest` style: for generated expectations,
      every `Applied` move leaves each row asserting the same *entry* it asserted before
      (the correspondence, not the raw `(tag, occurrence)` binding — an entry move changes
      that binding on purpose); every occurrence-swapping candidate that does not carry a
      whole entry is `Refused`; the three-party rotation window is `Refused`.
      Mutation-check the guard.

### 0.5 Reference anchoring
- [x] `ReferenceMessage` value type: `{view: MessageView, provenance: THIS_RUN|GOLDEN|SECOND_INSTANCE|PICKED|PASTED, label, anchorInstant}` —
      anchor = arrival instant for live/run messages (as `actualAt` today), the
      message's own `SendingTime(52)` for pasted (fallback: null → temporals render
      unjudged, with the reason).
- [x] `ScenarioReconcile.rows`/judging accept the anchor from `ReferenceMessage`
      (today's `actualAt` parameter generalized). Test: a pasted message an hour old
      judges its `~now ±60s` row against 52, not the clock.

**Phase 0 gate:** all existing scenario/reconcile/evaluator tests green and unmodified
except where a test asserted the old edited-since-run refusal; new tests as listed; app
behaviour visibly unchanged (run a scenario, reconcile a failure in the current UI).

### Phase 0 outcome — what actually happened

**Gate met.** Full `:composeApp:jvmTest` green (including `TabSelectionTest`, which passed
here); detekt/ktlint findings unchanged from the pre-phase baseline in every file touched.
Live-verified against the demo acceptor: a **pre-`stepId` scenario file** (hand-written, no
ids on disk — as were all of the user's existing `PLAY`/`DEMO` scenarios) loads, runs, fails,
and reports `stepId`s that are **identical across two independent runs**, which is D3's whole
argument, proven on a real file rather than a fixture.

`ReconcileMoveBlockTest` is green **unmodified**, which is what proves D1's rule subsumes the
old `moveBlock` behaviour rather than merely coexisting with it.

**Three defects were found and fixed inside Phase 0's scope** (D4 predicted two of them):

1. `ScenarioCodec` read the mode as `strict = (mode == "strict")`, so any unrecognised value
   silently became OPEN — a typo, or a mode written by a later build, *loosened* the
   expectation and the scenario went on passing while checking less than it said.
2. `verbatimWindow` asked `ExpectationEvaluator.satisfies`, which hard-coded `Instant.now()`
   and a null resolver, so an entry carrying an `MDEntryTime` or an echoed id could never be
   recognised as having moved. Worse than the predicted silent withholding: the tool printed
   the *torn-entry refusal* — "these rows did not move… the values changed in place… the
   venue is behaving differently" — every word of which was false about a message whose
   entries had plainly swapped.
3. The entry label took the *first* dictionary-described field in an entry, which is
   `PartyIDSource` — so every party in every message would have been labelled
   `ProprietaryCustomCode`. It takes the last one (the role), which is what identifies it.

**A "defect" was reported here and then withdrawn.** It is left in the record because the
mistake is more instructive than the finding would have been.

During verification, a scenario run a second time in the same session appeared to leave the
grid tinting the *first* run's message while the new failure got no banner and no "Reconcile
assertions…" button. It reproduced on `ac99574` too, so it was written up as a pre-existing
defect. It is not one. **The fixture was wrong**: the scenario sent a *fixed* `ClOrdID` and
carried an *empty* bind predicate, so on the second run the Expect had no way to tell the two
ExecutionReports apart — and `runExpect` correctly took the first unconsumed match in the
session's history, which was the first run's reply. The tool re-judged *that* message and
tinted *that* row. Everything on screen was consistent; the reader was not.

Re-verified with the scenario **as capture actually authors one** — `${id0 = UUID.randomUUID()}`
minted on the Send and an `11=${id0}` bind constraint on the Expect — and the second run binds
to its own reply, tints its own row, and shows the banner and the reconcile button. No fix
needed, and none made.

The real lesson, which is worth carrying into Phase 2: **a scenario with no correlation
constraint re-binds to the oldest matching message in the session log.** That is by design
(the `consumed` cursor is per-run, and `ClearMessages` exists as a setup step for exactly
this), and capture emits the constraint automatically — but a *hand-written* scenario, or one
an agent composes over `fixtool_save_scenario`, can fall into it silently and then present a
confusing failure against an ancient message. The rail (2.1) shows live per-step status and
will make this trap more visible, not less; consider whether the run report should say *which
message* a step bound to.

### Phase 0 review — three defects the phase shipped, and what they teach

The phase was reviewed after it was declared complete. The gate claims held (full suite green,
`ReconcileMoveBlockTest` and `ReconcileView.kt` byte-unchanged, the architecture test real).
**Three defects were found in the phase's own work and are now fixed**, each with a test that
fails without the fix and a mutation-check on the guard. All three are the same failure at
different addresses: *a rule was stated correctly in prose and implemented as something
narrower or wider.* Read them before Phase 1 — they are the shape of the mistake this area
keeps producing.

**R1 — `withIds` gave a new step the id of an existing one, and the reconcile route opened on
the wrong step.** D3 says "only genuinely new steps get a random one"; no random path was ever
written, so *every* id is `UUID.nameUUIDFromBytes(scenario/phase/index)` — a hashed index. The
assignment minted for the first blank id it met **before the steps below it had claimed theirs**,
so a step inserted at index 1 was minted with the id the existing step at index 1 was already
carrying, took it, and pushed that step onto its own successor's id. Every step below an
insertion slid one place down the id list. The failing step's id then named a *different* step;
`reconcileRoute` found that one; and where the two were alike — two Expects awaiting two fills
of the same shape — the "is this still the step that ran?" check passed and the route **opened
on the wrong Expect**, where one click of Accept actual writes the failing message's bytes into
an assertion that never saw it. That is the exact false green the id was introduced to close,
re-created by the id. Fixed by claiming every existing id in a first pass, before anything is
minted. Deletes, reorders and in-place edits were always safe and are pinned as such now.

*The lesson:* an identifier derived from position is a position. It was never checked against
the one edit — insertion — that its whole design was a response to.

**R2 — one row nobody could read was enough to hide an entry that had plainly moved.**
`verbatimWindow` asked `holds` of every row of a candidate block, including a `reference`, which
resolves against a live run's variable scope — and a reconcile view has none, so **no caller has
a resolver to pass, because there is nothing to pass**. The row answered "false" about a value it
simply could not read, the block never fitted, and the tool told the author *"these entries did
not move; it is the values there that changed"* about a message whose party entries had swapped.
The engine already knew better in the two adjacent places (`placeByOccurrence`'s `hasFixedValue`
carve-out, `reorder`'s `unjudgeable` carve-out); `verbatimWindow` was the one that forgot.
Unjudgeable rows are now placed on their tag and never value-checked — bounded by a new guard,
mutation-checked: **a block with no judgeable row in it proves nothing** and is refused outright,
because a tag sequence matching a tag sequence is the rotation trap with the values taken away.
`ComparisonSemantics.kindOf` had a coupled bug behind it — it asked `unknown` before `moved`, so
the newly-movable reference row would have rendered as "nothing to do" and split its own entry's
chunk in two. Fixed with it.

*The lesson, and it is the one the plan already warned about in bold:* `ReconcileAnchorTest`'s
"an entry carrying a reference is still recognised as having moved" **passed** — because it
handed the engine a working `referenceResolver` that the app can never supply. The fixture dodged
the hard case, exactly as §"Testing this model" says every serious defect in this area has. A
test for a reconcile-time behaviour must be given what the *reconcile view* has, not what the
runner has.

**R3 — a one-field group entry could not be moved, and the refusal blamed it for something an
entry move is licensed to do.** `moveEntry` refused any single-row entry of a repeated tag. That
is right for a *heuristic* bracket — a bare `448, 448, 448` run is a period-1 repeat, not a group
— and wrong for a *dictionary* one: `NoMDEntryTypes` is delimited by `MDEntryType` and holds
nothing else, so its entries really are one row wide. Yes, moving one changes the occurrence
mapping; **that is what an entry move is** (D1's own worked example is FIRMA's `448` becoming the
second `448`). The danger the per-row arrow carries — a row arriving beside another entry's rows
and being silently re-aimed onto them — cannot arise where the entry has no other rows to be
separated from. The refusal is now conditioned on `EntrySource`, which is precisely what the
overlay computes it for, and which `moveEntry` was throwing away by taking bare `IntRange`s.
Phase 4's entry drag would have been silently dead for every single-field group.

*The lesson:* D1 is right as written. The code was stricter than the rule and said so in the
rule's own words, which is the hardest kind of wrongness to see.

**Also corrected:** `moveEntry`'s out-of-range refusal told a *middle* entry it was "already last
in its group", which it plainly was not. A refusal that describes the wrong situation is worse
than one that says nothing, because the author believes it.

**Two things found and deliberately not fixed**, recorded so Phase 1 does not inherit them
unknowingly:

- `ComparisonSemantics.linkMoves` pairs the *k*-th moved chunk with the *k*-th chunk by landing
  place. That is correct for the two-entry swap (they point at each other) and is the *inverse*
  edge for a three-way rotation. `Chunk.placement` is the real source of truth for the connector,
  so Phase 1 should decide what the crossing line means for a rotation and take it from there.
- `SemanticsContractTest` exercises only the `MessageView` overload of `align`, never the
  `ReferenceMessage` one — which is the overload carrying the anchoring rule. Widen it when the
  reference slot lands (Phase 5), and R2 is the reason to.

---

## Phase 1 — The diff surface, standalone

Build the new component next to the old UI; nothing routes to it yet.

### Decisions taken before implementation — read these first

Nine things the checklist under-specifies or gets wrong, settled here with the reasoning, so
the next reader does not re-derive them. **P1 and P2 change the shape of the phase.**

**P1 — The verdict is *lifted* out of the old view, not rewritten. This is the phase's
biggest risk and the checklist does not mention it.**

`ReconcileView.VerdictBar` (`ui/ReconcileView.kt:303-390`) is the most hard-won code in the
app. Every counter in it carries a comment naming a bug that *shipped*:

- the entry count fed into the row count — *"2 of 21 rows need attention"* over six rows that
  had all moved;
- `unknown` rows dropped from the counts — one click of *Loosen → reference* made a red row
  unjudgeable and the bar announced *"✓ every assertion would now pass"* over an expectation
  asserting that a ClOrdID equals an OrdStatus;
- a `MOVED` row that no re-ordering covers fell out of every bucket, so the bar **declared
  success over a step that fails**;
- `judged == 0` is not a separate banner — it *is* the headline, and it is the only thing
  standing between an author and a step that passes for ever while checking nothing.

A new composable that counts its own rows gets all six back, silently, and its tests will not
catch them because its tests will be written from the same misunderstanding. So:

> **`Verdict` becomes a pure data class computed by one function in `service/compare/`, moved
> out of `ReconcileView` verbatim — same arithmetic, same sentences, same order of clauses.
> `ReconcileView` is refactored to call it *in this phase*, so `ReconcileViewTest` keeps
> pinning it, unmodified. `DiffSurface` calls the same function.**

Two surfaces, one verdict, one set of tests. The same treatment goes to `canAcceptShape` (the
OPEN/unasserted guard at `:147-149`, whose comment records the bug where the bulk button
asserted every field an OPEN author had deliberately left alone). This inverts what Phase 3
expected to do: most of `ReconcileViewTest`'s assertions stop being *UI* tests at all, so
there is nothing to "port" — they were never about the composable.

**P2 — Undo is a snapshot stack. The checklist asks for command inverses, and that is wrong.**

1.1 says "`EditOp` sealed commands **with inverses**". Writing an inverse for each op is writing
each op twice. `drop` on a repeated tag removes *every* row of that tag — the whole-tag rule —
so its inverse must restore N rows at N indices. `reseed` rebuilds from the seeder, re-attaches
the reference rows, *and* appends the echoes the reply no longer carries. `acceptNewOrder`
applies a verified permutation. An inverse that is subtly wrong is a corruption that appears
only after `⌘Z` — the quietest possible defect in the one surface whose entire promise is
*"nothing is written to the scenario until you save."*

The existing view already does the right thing and nobody noticed: `StagedFix(label, before)`
(`ReconcileView.kt:241`) snapshots the **whole expectation**. An `Expectation` is a small
immutable data class; a snapshot is O(rows) and *cannot be wrong*.

So: an `EditOp` is a **label plus an application** (`(Expectation) -> Expectation?`) — which is
exactly what a future auto-fix `FixPlan` produces, and what the footer's *"loosen 151 · accept
order (2 entries)"* reads from. Undo/redo is a list of `(label, expectation)` snapshots with a
cursor; redo is the forward half of the same list. The checklist's "command/inverse round-trip"
test becomes **apply → undo → byte-equal**, which is what it was really asking for, and it is
now true by construction rather than by a second implementation of every operation.

**P3 — The session is keyed on the step's `stepId`, and this has already bitten twice.**

`ReconcileView.stepKey` (`:71-90`) carries the scars: keying the staging state on `expectation`
destroyed history on every click (every fix replaces it), and keying on `crumb` destroyed it
whenever the author edited the step's session or message type — both are in the crumb, and both
are editable while the view is open. The host currently passes `stepIndex`
(`ScenarioEditor.kt:539`), which is the identity it could name rather than the one it meant.

Phase 0 gave every step a `stepId`. **That is the key** — `remember(stepId) { ReconcileSession(…) }` —
and it is the identity the old comment was reaching for.

**P4 — The memo key must be by value. `RawMessageView` has no `equals`, and that will defeat it
silently.**

1.1 wants `chunks` memoized on (draft, reference, semantics). But `RawMessageView`
(`service/RawMessageView.kt:13`) is a plain class with **no value equality**, so
`ReferenceMessage` — a data class — inherits *identity* equality on its `view`. Build the
`ReferenceMessage` from the `WorkbenchEditRequest` inside the composable (the natural way to
write it) and the key changes every frame: the memo never hits, and every recomposition re-runs
`reorder`, which enumerates every contiguous block of the expectation and scans each across the
wire. Nothing fails. It is merely slow, for a reason nobody would ever find.

So the memo key is **value-typed**: `(draft, wire bytes, anchorInstant, provenance,
semanticsId)`. Pinned by a test that constructs an equal-but-distinct `ReferenceMessage` and
asserts the recompute count does not move. And a **budget test**: a 40-row expectation against a
60-field message re-judges under a fixed ceiling, so the live re-judge cannot quietly become
sluggish as the engine grows.

**P5 — The two sides are paired *once*, in the session — never zipped in the composable.**

`Chunk` carries `rows` and `right` as two parallel lists, and they are **not 1:1 in every kind**.
A `SAME` chunk holding an `absent` row that passes has a row with no wire field at all
(`rightOf` returns null), so `right` is shorter than `rows` — and a naive `zip` slides every
field below it up one line. The two sides of the diff would then disagree about what faces what:
the exact seam `GroupOverlay` was built to close, re-opened in the renderer.

So the session emits a display model — `DiffLine(chunk, left: Row?, right: MessageField?,
offers)` — built once and tested without Compose. The pairing rule, stated: **a row faces the
field at its `wireIndex`; a moved row faces the field at its `placement`; a row with neither
faces a gap.** The composable renders lines and never decides what faces what. (This is a small
additive change to Phase 0's `Chunk`, and it is recorded as one.)

**P6 — The gutter's offers come from the engine's predicates, never from the chunk kind.**

The proposal's gutter table maps chunk kind → button, but whether an offer is *honest* is a
row-level engine question: `canAcceptActual` refuses temporal and reference rows,
`canAssertAbsent` refuses when the tag appears elsewhere in the reply, and `dropTakesWholeTag`
changes what `×` means. The mockup already says *"hidden where `canAcceptActual` refuses"*.

So `offersFor(line): List<Offer>` lives in the session, is a pure function of the engine's own
predicates, and is tested without Compose. **The UI cannot draw a button the engine would
refuse** — the same one-decider rule Phase 0 enforced between the engine and the overlay.

**P7 — The mode chip is an edit, and the selected semantics is *derived*, not stored.**

The proposal wants the chip to preview a failure under a semantics the scenario was not authored
under, read-only. But today the only registered semantics **are** the two `MatchMode`s — so a
read-only preview of OPEN that you then cannot save is strictly worse than editing the mode and
pressing `⌘Z`. `SetMode` is therefore an ordinary `EditOp`: staged, undoable, saved.

And `selectedSemantics` is **derived** — `SemanticsRegistry.forMode(draft.mode)` — never stored
on the session. A stored copy is a second source of truth, and it would eventually say STRICT
over a step that saves OPEN. When a semantics arrives that is *not* a `MatchMode` (tree, GumTree),
that is when the preview-only path is built and the chip's menu grows the disabled entries the
mockup already draws.

**P8 — `SwapReference` is not an edit.**

It changes what you are comparing *against*, not what you are *asserting*. It must not stage,
must not push undo, must not set `isDirty`, and must not appear in "3 edits staged". It
re-judges, and that is all. The session owns the slot from this phase, so Phase 5 is a UI, not a
refactor.

**P9 — The chip vocabulary is `MATCHER_TYPES` minus `reference`, and the row's *value* column
is `MatcherEditor` itself.**

`ReconcileView` narrows the Loosen menu to six types (`LOOSENINGS`, `:300`) and excludes
`reference` for a reason recorded at `MatcherEditor.kt:90-100`: a dropdown-seeded `${out.D.11}`
on a failing OrdStatus row makes that row unjudgeable, drops it out of every count, and the bar
announces that every assertion would now pass. The mockup's menu agrees — it lists seven types,
omits `reference`, and footnotes it: *"REFERENCE ROWS ARE MADE AT CAPTURE — ${id0} BINDS ACROSS
STEPS"*. `exact` is back, because this is direct editing and not "loosen".

So: the dropdown offers seven; a row that already *is* a reference still shows `reference` on its
chip; nothing can switch *to* it. And the left row is `tag · name · MatcherEditor` — the matcher
editor **is** the value column, which is why neither `ExpectationEvaluator.describe` nor
`ScenarioUi.matcherSummary` renders there. (Those two are a pre-existing duplication; `describe`
stays, for the footer's staged labels and the engine's refusal sentences. Note it for Phase 7.)

### 1.1 The shared verdict (extract first, build on it after) — **complete**
- [x] `service/compare/Verdict.kt`: `Verdict(judged, values, added, missing, movedRows,
      movedEntries, unresolved, unknown)` + `attention`, `headline`, `shapeVersusBehaviour`,
      `parts`, `assertsNothing` — the arithmetic and the sentences moved out of `VerdictBar`.
      Pure: `Verdict.of(rows, movedRows, movedEntries)`. `canAcceptShape` moved with it.
- [x] `ReconcileView` refactored to render it. `ReconcileViewTest` passes **unmodified** —
      the proof the extraction changed nothing, and the reason for doing it before the new
      surface exists rather than after.
- [x] Unit tests for `Verdict` covering each bug its comments record: the entry-vs-row count,
      the unjudgeable row that must not read as a pass, the unbracketed `MOVED` row, and
      `judged == 0`. Each mutation-checked.

**And it found a seventh bug, which is the argument for the whole decision.** `attention`
already contained `added`, and the headline added it a *second* time (`val needing = attention
+ added`), so every tag a venue added inflated the first number an author reads — the number
they use to decide whether a run is worth opening. The canonical four-failures ExecutionReport
announced *"10 of 17 rows need attention"* over nine rows that need it. No test pinned the
number, so nothing in the view caught it in the years it was there; it took twenty lines of
unit test to fall out. Counted once now, and pinned.

### 1.2 `ReconcileSession` (state holder, not a composable) — **complete**
- [x] `ui/diff/ReconcileSession.kt`: holds `original`, `draft`, the reference slot; derives
      the semantics (P7); exposes `model: DiffModel` — `lines` (P5), `verdict`, `overlay`,
      `acceptOrder`, `withheldMove`, `canAcceptShape` — memoized on the value-typed key (P4),
      recomputed on draft / reference change and nothing else.
- [x] `EditOp` = label + application (P2), one per existing pure op and **no reimplementation
      of any**. `SwapReference` is *not* one (P8) — it re-judges without staging or dirtying.
- [x] Snapshot undo/redo with a cursor: `undo()`/`redo()`/`canUndo`/`canRedo`/`isDirty`/
      `staged`/`stagedLabels`; `discard()` restores `original`. A refused or no-op edit is
      never stacked. (`⌘Z` key wiring is Phase 4; the stacks land now.)
- [x] `offersFor(row)` from the engine's `can*` predicates (P6).
- [x] `Chunk.pairs` (P5): the row→field correspondence, decided where `wireIndex`, the
      reorder's `placement` and the `absent`-row fallback already live. `rows`/`right` derived,
      so Phase 0's tests hold unmodified.
- [x] Tests (18): apply → undo → **byte-equal** for every op; redo, and a fresh edit abandoning
      the redo branch; discard after an arbitrary sequence; `SwapReference` re-judges without
      staging, dirtying or pushing undo; the memo holds across an equal-but-distinct reference
      and invalidates on each real input; the offers withheld where the engine refuses. Every
      guard mutation-checked.
- [ ] The re-judge budget test (a 40-row expectation against a 60-field message under a fixed
      ceiling) — deferred to 1.3, where the surface that must stay responsive exists.

> **A note for whoever runs `ktlintFormat`: do not.** It reformats all 64 files of the module,
> not the ones you touched, and buries the work in a style diff. The baseline is 1684 findings
> (`ktlintCheck` + `detekt`, `--continue`); the rule is only that your files add none.

### 1.3 The `DiffSurface` composable — **complete**
- [x] `ui/diff/DiffSurface.kt` renders a `ReconcileSession`: header (crumb, semantics chip,
      reference chip, verdict), two aligned columns, centre gutter, footer (staged count and
      labels, the *"nothing is written to the scenario until you save"* sentence verbatim,
      undo/redo/cancel/save). Left rows: tag (with `#2` occurrence suffix) · name ·
      `MatcherEditor` (P9 — `MATCHER_TYPES` minus `reference`). Right rows: tag · name · value
      · enum description as a separate dim span. Gaps hatched; `unjudged` rows amber `◌`.
      Built from `SlimComponents`. `ui/diff/DiffPalette.kt` holds the two things the app-wide
      theme has no word for: the moved violet, and the alternating entry hues.
- [x] Gutter offered by `offersFor` (P6): `«` accept-actual, `«` assert-it, `×` drop (whole-tag
      rule in its tooltip), `∅` assert-absent, `⇄ Accept new order` once, on the first moved
      band. Bulk: Accept all shape changes, Re-seed from reference.
- [x] Group bands from `GroupOverlay` with the overlay's own labels on both sides, per-entry
      hue, `HEURISTIC` entries badged `guessed`. Entry `↑`/`↓` ship now (the surface must not
      be able to do less than the one it replaces); drag is Phase 4.
- [x] The withheld-move reason renders on the group, verbatim from `Reorder.Refused.why`.
- [x] Keystrokes coalesce (`EditOp.coalesceKey`): typing `500000` is **one** edit, not six, so
      `⌘Z` means *"undo the value I set"* rather than walking back through `50000`, `5000`…
      The old view did the latter.
- [x] 14 Compose tests, 7 screenshots. The harness **feeds `onChange` back**, as
      `ScenarioEditor` does.

**And the screenshot caught a defect that no assertion did.** The moved rows were drawn with
`«` Accept-actual and `×` Drop in their gutter. A moved row's *status* is `VALUE` — FIRMA's
`448` faces FIRMB, which is indistinguishable, row by row, from the venue having changed the
value — so a gutter keyed on status alone puts a per-row fix under it. **One click and FIRMA's
row asserts FIRMB while the two `452` rows stay exactly where they are**: the expectation reads
FIRMB/role-1 and FIRMB/role-4, "FIRMA holds role 1" is gone, and the step is green. That is the
false green the entire sequence model exists to make impossible, walked back in through the
gutter. The entry moved as a unit and it is repaired as one — by Accept-new-order and nothing
else. Guard added, mutation-checked, pinned at both the session and the surface.

Every test passed before that was found. It was found by *looking at the picture*. The rule
that follows: **a UI phase is not gated by its tests, it is gated by its screenshots** — the
tests only pin what you already thought to ask.

**Phase 1 gate — met.** Full suite green (1195 tests, 0 failures); ktlint/detekt exactly at the
1684-finding baseline. `DiffSurface` is drivable interactively as `Mode.Diff` inside the
**existing** `ScenarioWorkbenchWindow` (`FIXTOOL_DIFF_HARNESS=1 ./gradlew :composeApp:run`) —
no new window, and the host is deleted in 2.2 regardless, so nothing was written to be thrown
away. Fixtures are the fake venue's own `shape` (a re-order **must** be offered) and `swap` (a
re-order must **never** be offered, and the refusal renders on the group). Screenshot set
committed from the Compose tests, at `build/scenario-screenshots/diff_surface_*.png`.

*Not done by me:* clicking it. The bench lives in the second window, which the control
surface's `/screenshot` cannot reach — it captures the main window. The app launches on the
bench without exception and the control surface answers; the interactive pass is the author's.

---

## Phase 2 — Rail + document tabs; the window dies

### Decisions taken before implementation — read these first

Eight things the checklist under-specifies or gets wrong, settled here with the reasoning, so the
next reader does not re-derive them. **T1 is a live defect that Phase 0 exists to prevent and the
editor has been re-creating on every Save; T2 changes the shape of the phase.**

**T1 — The scenario editor throws away every step id on Save. The id is a position again.**

`EditStep` (`ScenarioEditor.kt:69`) carries no `stepId`, so `toStep()` builds every step with the
default `stepId = ""`, and Save hands `ScenarioService` a scenario whose steps have **no ids at all**.
`save` then calls `withIds()`, whose first pass finds nothing to claim, and mints every id from
`(scenario, phase, index)`.

So the id is a hash of the position — **R1's defect at a different address**, and `withIds`' careful
two-pass claim-before-mint is defeated by a caller that hands it a clean slate. In-place edits survive
by accident: the minting is deterministic, so a step that has not moved is re-minted with the id it
already had. **Move, insert or delete a step and Save, and every id below the edit shifts with the
positions.** A run held from before that Save then names, by id, a step that is not the one that
failed. `reconcileRoute`'s equality check catches that only where the two steps *differ* — and the
case it cannot catch is the one R1 already documented: two Expects awaiting two fills of the same
shape, where the route opens on the **wrong Expect** and one click of Accept actual writes the failing
message's bytes into an assertion that never saw them.

Nothing pinned it. `ScenarioEditorStepIdentityTest` is *named* for step identity and never looks at a
`stepId`; the codec's invariant test asserts that no blank id reaches disk, which is true — the ids on
disk are simply not the ones that went in. The fixture dodged the hard case by moving nothing.

`EditStep` gains `stepId`; `toEditStep`/`toStep` carry it through; a step the author *adds* gets the
blank that `withIds` mints at Save. Reproduce on the current code first, and mutation-check the
round-trip.

It is fixed here rather than filed, because **T2 is built on it**: a document's dirty flag is
`draft != seed`, and a draft that loses its ids differs from its seed the moment it is built — every
tab would open dirty and every close would ask to confirm.

**T2 — A tab is not a window. Only the active document is composed, so the *document* must own its
state; the composables cannot.**

The workbench window kept `ScenarioEditor`'s `remember`ed state alive while the author looked at the
session grid, because the window stayed composed. A document tab does not. Switch to the session tab
and the editor's subtree is disposed — **its unsaved edits, and capture-review's entire include/exclude
curation, go with it.** That is not a missing feature, it is a *regression against the window we are
deleting*, and it sits on both of Phase 2's own gate paths (capture → save; deep-link → edit → save).

There are two ways out and the cheap one is wrong. Keeping every open document composed but unplaced
preserves all of it for free — and puts every document's nodes in the semantics tree, where
`onNodeWithTag("editor-save")` will find a **hidden** tab's button and pass. A UI test that cannot tell
which document it is looking at is worse than no test, and this phase's gate is a UI gate.

So the documents own their state and the composables mirror it out:

- `ScenarioEditor` gains `onChange: (Scenario) -> Unit`, emitted when the built scenario changes by
  value; the tab re-seeds it from the document's draft when it comes back.
- `ScenarioCaptureReview` becomes a controlled component over `CaptureReviewState(name, selectedIdx,
  included)` — `state` in, `onStateChange` out. `RangeSelectors` stops mutating a `SnapshotStateList`
  in place.
- Both test harnesses **must feed the callback back** (ground rule 3). A harness that records it once
  will sit happily on top of a document that keeps nothing.
- **Dirty is measured against the editor's own round-tripped seed** — `initial.steps.map {
  it.toEditStep().toStep() }` — never against the file. `EditStep` normalizes a Send's raw
  (`parseFixMessage` → re-join), so comparing the draft against the file marks an untouched document
  dirty over a difference the author did not make.

**T3 — The centre pane has a selection of its own, and it is not `activeSessionIndex`.**

A document tab is not a session, but the mockup puts them in one strip. If focusing a document moved
`activeSessionIndex`, then the message editor's target, `fixtool_send`, the grid's tint and every
control-surface call that means "the active session" would follow the author into a scenario document.

So the ViewModel owns `openDocuments: List<ScenarioDoc>` and `activeDocumentId: String?`, and
**`activeDocumentId == null` *is* the session view.** Clicking a session tab clears it; clicking a
document tab sets it and leaves `activeSessionIndex` exactly where it was. One decider, in the
ViewModel, because three doors (rail, run line, `openScenarioEditorForFailure`) and the control
surface all open documents.

`WorkbenchEditRequest`'s one-shot flow is deleted with the window that had to observe it — the
ViewModel opens the document itself. The payload type is renamed `ScenarioEditRequest`; the word
*workbench* does not survive this phase in the code either.

**T4 — In SPLIT, the document takes one split and the sessions keep the other.**

The proposal is explicit ("in SPLIT view modes the scenario document occupies one split while a live
session stays visible in the other"), and SPLIT has no tab bar to hide the grid behind. So the centre
becomes `session grid | divider | document area`, split **along the axis the author already chose** —
SPLIT_HORIZONTAL (sessions left→right) puts the document to the right, SPLIT_VERTICAL (sessions
stacked) puts it below. With no document open the region is not composed at all and SPLIT renders
exactly as it does today. The document tab strip heads the document area in SPLIT and merges into the
`TabBar` in TABS, from one component.

**T5 — The rail cannot own the scenario list.**

`ScenarioListPane` holds `scenarios` in a `remember` and calls its own `refresh()` after every write,
which worked only because every write happened inside it. In Phase 2 the writes happen in a *document
tab* — Save in the editor, Save in capture review — and a list that refreshes itself would never hear
about them: the author saves, and the rail goes on showing the old step count. So the ViewModel owns
`scenarios: StateFlow<List<Scenario>>`, refreshed on save/delete/capture, and the rail renders it.

**T6 — The rail attributes a run by scenario *id*, never by `ScenarioResult.scenario`.**

That field is a **name** (`ScenarioResult(scenario = scenario.name, …)`). Two scenarios may share one,
and a green tick on the wrong row is a lie about what passed. `lastRunScenario` carries the identified
`Scenario` that ran — match on its `id`. Per-step status matches on `stepId` first (`StepResult.stepId`
against the saved step), falling back to `(phase == "steps", stepIndex)` for a result minted before the
file grew ids.

**T7 — Per-step *live* status does not exist. Do not draw it.**

`ScenarioRunner` publishes one `ScenarioResult` at the end of the run; `assertionResults` trickle in
per matched message but carry no notion of "step 3 is running now". So the ▸ belongs to the **scenario**
(`scenarioRunning` ∧ `lastRunScenario.id`), and its steps stay `–` until the verdict lands. Inventing a
per-step spinner means inventing the state behind it.

And the runner **breaks at the first failing step** (`ScenarioRunner.kt:93`), so every step after a
failure has no `StepResult` at all. Those render *"— not reached"* (the mockup's own words), never a
bare `–`: `–` reads as *"ran, nothing to say"*, and this is the same class of silence as a missing
button with no reason beside it.

**T8 — A tab is a document, not a modal: Save does not close it.**

`onSave` in the workbench meant "save and go back to the list", because the list was the only place to
go back to. In a tab there is nowhere to go: Save writes, the document becomes clean, and the tab stays
open — which is also what makes Phase 3's *Save & re-run* possible without inventing a new lifecycle.
The two exceptions are honest ones: capture review's Save *creates* a scenario, so its document has
served its purpose and closes; and `esc` / `×` on a **dirty** document confirms first, inline on the
tab, in the app's own delete-confirm idiom (`ScenarioRow`) rather than a new dialog.

### 2.1 The Scenarios rail — **complete**
- [x] New left-docked pane in `ui/App.kt` following the exact existing idiom
      (`if (show) { Box(width = maxWidthPx * ratio) { … }; draggable divider }`, ratio
      state + clamp like `editorPanelSplitRatio`), in **both** TABS and SPLIT layouts.
      Content: scenario list → expandable step tree with live ✓/✗/▸/– from
      `scenarioResult`, per-scenario Run/Edit/Duplicate/Delete (port from
      `ScenarioListPane`), Capture from sessions…, New, Open folder, last-run line.
      (`ui/ScenariosRail.kt`; the scenario list itself is owned by the ViewModel — T5.)
- [x] Failing step rows carry **Reconcile →** or the `reconcileRoute` refusal sentence —
      never silence (port `RunStatusLine` semantics). Steps below a failure say
      *"— not reached"* (T7), because the runner stops there and a bare `–` reads as
      *"it ran, and there was nothing to say"*.
- [x] `showScenariosDialog` → `showScenariosRail`; `ControlServer.panel("scenarios")` and
      the toolbar button work unchanged from the caller's view (`McpTools` says "pane").
- [x] Tests: rail renders run tree states; refusal renders; `fixtool_panel scenarios`
      toggles the rail (`ControlServerIntegrationTest`).

### 2.2 Scenario documents as tabs — **complete**
- [x] `TabBar` gains document tabs alongside session tabs: distinct glyph + violet accent,
      closable (`×`), `esc` closes the focused doc tab, dirty tabs confirm **inline on the
      tab** in the app's own confirm idiom. Document kinds: scenario editor, capture review
      (Phase 3 adds the reconcile diff). The confirm state lives on the ViewModel, because
      `esc` and the `×` must not be two answers to *"is it safe to close this"*.
- [x] `ScenarioEditRequest` deep-links land on (open-or-focus) the right tab, scrolled to
      the failing step — from the rail, the run line, and `openScenarioEditorForFailure`
      (the message-viewer door). Same single decider, three doors, one destination. A
      second failure in a scenario already open **re-aims** the tab (`focusEpoch`) rather
      than re-seeding it, so unsaved edits survive the deep-link.
- [x] SPLIT view modes: the document takes one split and the sessions keep the other,
      along the axis the author already chose (T4); with no document open, SPLIT composes
      exactly what it composed before.
- [x] **Deleted** `ScenarioWorkbenchWindow`, the `Mode` switcher (`ui/ScenarioWorkbench.kt`
      is gone entirely), `ui/diff/DiffHarness.kt`, and the second-window block in `main.kt`
      — `viewModelRef` is a plain `var` again, because nothing composable reads it.
      `WindowChrome.kt` stays (the main window uses it).
- [x] Tests: deep-link from a failed run lands on the editor tab focused on the step
      (`ScenarioRunReportTest`, `ScenarioDeepLinkTest`, both ported); closing a dirty editor
      tab confirms; capture tab ↔ grid source highlighting (`selectMessage` fires on
      candidate selection — mutation-checked).

**Phase 2 gate — met.** The app has no second window. Full suite green (**1204 tests, 0
failures**); every file at or below its lint baseline (total 2084 vs the 2121 the same
command reports on the pre-phase tree). Live-verified against the demo acceptor: the rail
docked in the main window, a real scenario run, a real failure, the tree auto-expanded on it
with ✓/✗/*not reached* and **Reconcile →** on the failing step, and the grid tinting the
message beside it. The click-only half of the loop — Reconcile → document tab → edit → the
`×` that asks → Save — is driven and photographed by `ScenarioDocumentsScreenshotTest`,
because the control surface has no hook for a click.

### Phase 2 outcome — what actually happened

**T1 was real, and it was live.** `EditStep` carried no `stepId`, so every Save through the
editor handed `ScenarioService` a scenario with no ids and `withIds()` minted all of them from
`(scenario, phase, index)` — R1's defect at a different address, with `withIds`' two-pass
claim-before-mint defeated by a caller that hands it a clean slate. Reproduced on the old code
first (both new tests fail without the round-trip). In-place edits survived by luck of the
determinism; a **move** slid every id below it onto the next step down.
`ScenarioEditorStepIdentityTest` is *named* for step identity and had never moved a step or
looked at a `stepId` — the fixture dodging the hard case, again.

**T2 was the phase's real work.** A tab, unlike the window it replaces, is disposed when you
look away from it. Both document composables now hold their state in the document
(`ScenarioEditor` gains `onChange`; `ScenarioCaptureReview` becomes a controlled component over
`CaptureReviewState`), and both test harnesses had to be taught to **feed the callback back** —
three `ScenarioCaptureReviewTest` cases went red the moment the state was hoisted and the
harness kept dropping it on the floor, which is exactly ground rule 3 catching itself.

**And the screenshot found two defects that no assertion did.** The expand chevron was drawn
inside `Modifier.size(10.dp)`, which clips a 9sp glyph away to nothing: the tree rendered as a
flat list with the one affordance that says *these rows open* invisible. And scenario names
truncated with no ellipsis, so a name simply appeared to end early. Every test was green. The
rule holds: **a UI phase is gated by its screenshots.**

**One defect found outside the checklist, and fixed:** the rail cannot own the scenario list
(T5), and neither can the ViewModel own the *refreshing* of it — two of the four doors that
write a scenario (`POST /scenarios` and `fixtool_save_scenario`) never come through the
ViewModel. `ScenarioService` notifies on write now. Without it, an agent saving a scenario over
MCP watched the rail go on showing the old one. Found by a test failing for the right reason.

**Deviations from the plan, and things deliberately left:**

- The rail does **not** open itself when a deep-link fires. The destination is a tab; forcing a
  pane open because the author clicked *Reconcile* in the message viewer would be a second
  thing happening that they did not ask for.
- `ScenarioEditor` gained a hoisted **selection** (`selectedStep`/`onSelectStep`) as well as a
  draft, so a glance at the session grid does not move the author's cursor. The decisions
  section only called for the draft; the inconsistency with capture review (whose `selectedIdx`
  *is* in its state) was not worth keeping.
- **The reconcile view is narrower here than the workbench window gave it.** That window opened
  at 90% of the screen precisely because the six-column table needed the width; the centre pane
  with the rail open gives it roughly 90px less. It is not worth fixing: Phase 3 deletes
  `ReconcileView` and puts `DiffSurface` — a two-column layout built for this — in its place.
- `ScenarioService.load()` of an id that does not exist fires a **user-facing error toast** (it
  cannot tell "missing" from "corrupt"). Pre-existing, surfaced by a bad request during
  verification, and left alone — but it is a Phase 7 cleanup candidate.

---

## Phase 3 — The diff surface becomes the only expectation editor

### Decisions taken before implementation — read these first

Ten things the checklist under-specifies or gets wrong. **V1 changes the shape of the phase, and it
is settled by the checklist contradicting itself.**

**V1 — 3.1 and 3.2 disagree about where `DiffSurface` lives, and 3.1 is right — but only if the
scenario's draft stops living in the editor document.**

3.1 says *"the reconcile document tab hosts `DiffSurface`"*. 3.2 says *"`ExpectDetail` shrinks to:
bind-predicate editing + `DiffSurface`"* — which puts it inside the **editor** document. Both cannot
be the primary host, and the difference is not cosmetic: it decides how many unsaved drafts of one
expectation can exist at once.

Take the checklist literally and they can both be open. Scenario X's editor tab holds a draft (the
author renamed it and edited step 1); its reconcile tab holds a `ReconcileSession` over step 2. Save
from the reconcile tab and it must write *a whole scenario* — so whose? Off disk, and the editor's
next Save writes step 2's **old** expectation straight back over the repair. Off the editor's draft,
and saving a diff quietly commits a rename the author never asked to commit. That is the
two-editing-surfaces defect from the model doc, re-created between two tabs.

So: **the reconcile diff is its own document (3.1), and the scenario's draft is hoisted out of the
editor document into a per-scenario workspace that every document of that scenario is a view onto.**

> `openScenarios: Map<scenarioId, ScenarioDraft(draft, seed)>` on the ViewModel. `ScenarioDoc.Editor`
> and `ScenarioDoc.Reconcile` both carry a `scenarioId` and no draft of their own. Dirty is the
> workspace's. **Closing the *last* document of a dirty scenario confirms; closing one of several does
> not**, because the draft is not in the tab.

Three things fall out that argue for it independently:

- The diff gets the **whole centre**. That was one of the two stated reasons documents are tabs at all
  (*"a two-sided diff and the step editor need the centre"*), and hosting it inside `ExpectDetail`
  gives it whatever is left after the step list — which is how the workbench window came to be opened
  at 90% of the screen in the first place.
- `DiffBody` can become a `LazyColumn` (V8). Inside `ExpectDetail` it cannot: that pane is already a
  `verticalScroll`, and a lazy list inside one is measured with infinite height.
- Phase 6's plain diff viewer is a document that belongs to **no scenario at all**. A document that
  is a *view* and a draft that is *per-scenario* is the shape that survives it.

**V2 — There is exactly one host for `DiffSurface`, and `ExpectDetail` is not it.**

3.2's *"`ExpectDetail` shrinks to bind-predicate + `DiffSurface`"* becomes: **bind-predicate + the door
to the step's diff tab.** One composable that authors assertions (the phase gate), and one place that
composes it. Two hosts would be two sets of props, two lifetimes, and two answers to "which reference
is bound" — and this document has already recorded what happens when one behaviour has two deciders.

The door is *Edit assertions →* when the step has never failed (reference: GOLDEN) and *Reconcile →*
when it has (reference: THIS_RUN). Same tab, same surface, different slot — which is the proposal's
whole argument, made where it costs nothing.

**V3 — The `ReconcileSession` lives in the document, or the footer lies.**

Only the active document is composed (Phase 2, T2). A session held in `remember(stepId)` inside the
composable is **disposed the moment the author glances at the session grid** — and it holds the undo
stack, the redo branch, the reference slot, and the staged count.

The edits themselves would survive (they flow out through `onChange` into the draft). Everything that
*describes* them would not: come back to the tab and the footer says **"0 edits staged · nothing is
written to the scenario until you save"** over a draft that is three edits from disk, and `⌘Z` does
nothing. A footer that miscounts the thing it exists to promise is worse than a lost undo stack.

So the session is a field of `ScenarioDoc.Reconcile`, keyed by `(scenarioId, stepId)` — P3's
`remember(stepId)` rule, moved to where the lifetime actually is. Two consequences:

- `ReconcileSession.original` must become **rebasable**. After a Save, "3 edits staged" is a lie in the
  other direction — they *are* written now. Save rebases the session on the saved expectation and
  clears the stack; `isDirty` and `staged` go to zero because they have become zero.
- A step deleted in the editor takes its session with it.

**V4 — The golden follows the reference only when the reference is THIS_RUN.**

`ExpectDetail` re-points `expectation.golden` at the failing message when it reconciles
(`ScenarioEditor.kt:590`), and the comment there records why: an expectation reconciled against *this*
message describes *this* message, and leaving the old golden makes the authoring view show red rows
for edits that are correct, and offer to "fix" them back.

Generalise that to a **slot** without thinking and it becomes a defect. Swap the reference to a
SECOND_INSTANCE — the whole point of which is that it is a *different* message — and the golden would
be rewritten to it, destroying the very thing verify-generalizes was checking against. Bind a PASTED
reference (Phase 5) and a hand-doctored paste silently becomes the scenario's canonical example.

> **The golden is re-pointed when, and only when, the reference is `THIS_RUN`.** GOLDEN has nothing to
> do; SECOND_INSTANCE is deliberately not canonical; PICKED and PASTED are not FixTool's to vouch for.

**V5 — A red row against a SECOND_INSTANCE is not a venue regression, and the verdict must not say it
is.**

3.2 says *"'Verify generalizes' = swapping the reference to SECOND_INSTANCE"*, and as a mechanism that
is exactly right — the same rows, re-judged. But the *sentences* are wrong: the old builder answered
`✓ generalizes` / `⚠ 2 over-specified`, and the verdict bar answers **"2 rows need attention"**, which
in this surface has always meant *the venue did something new*. Against a second instance it means the
opposite: **the expectation is over-specified** — it only passes against its own capture, which is the
author's fault and the author's to loosen.

`Verdict` already carries the counts. The headline sentence is chosen from the reference's provenance,
and against SECOND_INSTANCE it says so. (`ExpectationBuilderTest`'s over-specified case is the test
that must survive this, and it is the one that will catch it if the sentence is merely inherited.)

**V6 — A step with no reference gets a prompt, not a diff against nothing.**

`ReconcileSession` requires a `ReferenceMessage`. Hand one an empty `MessageView` — the natural reading
of 3.2's *"an empty reference"* — and every asserted row comes back `missing`: a wall of red, a verdict
announcing a catastrophe, and a gutter offering to drop every row of a step that has simply never run.

So the reference slot is `ReferenceMessage?`, no session is built until it is bound, and the tab shows a
prompt instead. What the prompt offers, in order: the **golden** where the step has one; otherwise the
**message currently selected in a session grid** (pick-from-grid in its cheapest honest form — the armed
slot arrives in Phase 5); otherwise the sentence saying why there is nothing to diff against yet.

**V7 — `reorder` must be given the same unjudgeable rows the diff has — R2's defect, at the anchor
seam.**

`ScenarioReconcile.rows(draft, reference, …)` already does the right thing with an **unanchored**
reference (no `SendingTime(52)`): a temporal row becomes `unknown`, with `NO_ANCHOR` as its reason,
rather than being judged against the wall clock. `ReconcileSession.build()` then calls
`reorder(draft, message, now = { anchor ?: Instant.now() }, …)` — and **`reorder` is not told.** For a
golden or a paste with no `52`, a temporal row inside a moved entry is value-checked against *now*,
fails, the block does not fit, and the entry that plainly moved is refused a re-order — and told, in
the torn-entry refusal, that *"the values there changed in place"*.

That is R2 exactly: *a row nobody could read was enough to hide an entry that had plainly moved.* R2
fixed `verbatimWindow` for `reference` rows and the anchor seam re-opens it for `temporal` ones. Phase 3
is where it starts firing, because GOLDEN references are where anchorless references become routine.
Reproduce it first (a golden with no `52`, a swapped party entry, one `TransactTime` row), then give
`reorder` a `ReferenceMessage` overload that carries the same unjudgeable set. Mutation-check it.

**V8 — `DiffBody` becomes a `LazyColumn`.**

The row-level deep link (3.1) has to *scroll to a row*, and today the body is a
`Column(verticalScroll)` whose row offsets nobody knows. Its items are already a flat `List<Item>`
(bands and lines), and Phase 1 drew no cross-item canvas — the moved connector is band styling, not a
`Canvas` — so nothing is in the way. It also buys Phase 4's `n`/`p` chunk navigation and keeps a
200-row message cheap to draw. It is only possible because of V1: a lazy list inside `ExpectDetail`'s
`verticalScroll` is measured with infinite height.

The tag has to travel with the deep link: `MessageDetailPanel`'s door is
`onEditAssertion: (() -> Unit)?` today and becomes `(Int?) -> Unit`, fired from the **failing tag row
the author clicked**, through `ScenarioEditRequest.focusTag`, into the document.

**V9 — Save & re-run must re-bind the reference, or the surface goes on showing the failure it just
fixed.**

Nothing refreshes a document's `RunFailureContext` when a new run lands — Phase 2 never needed it.
So: Save & re-run, the step goes green, the rail says PASSED — and the diff tab is still bound to the
**old** run's failing bytes, still red, still offering to fix what is already fixed. The author's own
verification loop would be lying to them at the exact moment it mattered.

When a run completes, every open reconcile document re-binds by `stepId`: to the new run's matched
message where the step failed again, and — where the step passed — to the message that passed it, so
the surface goes green in front of the author. A step the new run never reached falls back to its
golden and says so. Disabled while a run is in flight (the shared run slot already enforces one run).

**V10 — What dies, and what has to exist before it does.**

Every behavioural assertion in the two doomed files needs a successor *first*. The map, checked
against the current suites:

| `ReconcileViewTest` (11) | successor |
|---|---|
| separates shape from behaviour · accept-all-shape leaves the value alone · accept-new-order in one click · dropping every row is never a pass · role swap offers no move and says why · a hand move on a role swap cannot fake a pass | already in `DiffSurfaceTest` |
| Undo last walks the fix back | `ReconcileSessionTest` + `DiffSurfaceTest` undo/redo |
| **fixes stay staged when the editor feeds the change back** | **none — write it.** The feedback loop now runs through the *workspace* (V1), which is a longer wire than the one that broke last time |
| **the arrow moves the entry the author picked, not the one the engine planned** | **none — write it** |
| **a bracketed entry can be moved by hand from the block header** | **none — write it** |
| `ExpectationBuilderTest` (2) | successor |
| renders seeded chips with live preview, and saves | **none — write it**: `DiffSurface` against a GOLDEN reference *is* the authoring surface |
| verify generalizes flags the over-specified field | **none — write it**, and it is V5's test |
| `ExpectationDraftsTest` (3) | successor |
| occurrence numbering · a lone row ticks the first occurrence · a row the golden cannot account for keeps its place | the pairing rule itself (`AlignmentPropertiesTest`) plus `ReconcileSessionTest`'s *"the lines are the rows, and a row that faces nothing says so"*. **Read them before deleting; do not assume.** |

And **the budget test that fell between the phases**: 1.2 deferred *"a 40-row expectation against a
60-field message re-judges under a fixed ceiling"* to 1.3, and 1.3 shipped without it. Its box is still
open. Phase 3 is where the surface it protects goes live in front of a user, and where it gets written.

### 3.0 The scenario workspace (V1) — **complete**
- [x] `openScenarios: Map<String, ScenarioDraft(draft, seed)>` on the ViewModel. The draft moved
      **out** of `ScenarioDoc.Editor`; `Editor` and `Reconcile` both carry a `scenarioId` and are
      *views* onto it. **One unsaved draft per scenario, by construction.**
- [x] Dirty is the workspace's. Closing the **last** document of a dirty scenario confirms; closing
      one of several does not. The last close drops the draft — a draft nothing is looking at is
      unreachable and unsaveable, and the next open would silently hand back edits already walked
      away from.
- [x] Save writes the workspace draft (from either tab) and re-seeds it from disk.
- [x] `ScenarioDraft.of` normalizes with `withIds()` — **found by a test**: a document addresses a
      step by `stepId`, so a draft with blank ids is a draft whose steps cannot be found. The
      workspace is a new door, and D3's normalizer runs at every door.
- [x] Tests (`ScenarioWorkspaceTest`, `ScenarioDeepLinkTest`): the diff and the editor are two views
      of one draft — an edit in the diff is visible in the editor and saved exactly once, and the
      rename in the editor survives the deep-link that opens the diff.

### 3.1 Reconcile routes into `DiffSurface` — **complete**
- [x] `ScenarioDoc.Reconcile(scenarioId, stepId, session, thisRunWire, focusTag)` — the **only** host
      of `DiffSurface` (V2), bound to the failing step with `ReferenceMessage(THIS_RUN)`. `onChange`
      writes the expectation into the workspace draft **by `stepId`**; the golden is re-pointed **only**
      for THIS_RUN (V4).
- [x] The `ReconcileSession` is a field of that document (V3) and is **rebased** on Save.
- [x] An unbound reference is a **prompt** (V6), offering the golden, then the message selected in a
      session grid, then the reason there is nothing to diff against yet.
- [x] Row-level deep link: every failing tag row in `MessageDetailPanel` is its own door, it travels
      as `focusTag`, and `DiffBody` — now a `LazyColumn` (V8) — scrolls to that row.
- [x] `reorder` gets the reference's unjudgeable rows (V7). Reproduced first; the guard is
      mutation-checked by that reproduction.
- [x] **Deleted `ui/ReconcileView.kt`** — after the three missing successors in V10's map existed and
      were green on `DiffSurface`.
- [x] Save & re-run: saves, runs, and **re-binds every open diff by `stepId`** when the result lands
      (V9); disabled while a run is in flight.
- [x] The re-judge budget test 1.2 deferred and 1.3 dropped: a 40-row expectation against a 60-field
      message, rebuilt from scratch, under a fixed ceiling.

### 3.2 Authoring is the same surface — **complete**
- [x] A never-run Expect step opens the same document with `ReferenceMessage(GOLDEN)`; a step with no
      golden gets V6's prompt.
- [x] "Verify generalizes" = swapping the reference to `SECOND_INSTANCE` — **and the verdict says
      *over-specified*** (`Verdict.headlineAgainst`, V5), because a red row there is the author's
      assertion being too tight, not the venue regressing.
- [x] **Deleted `ui/ExpectationBuilder.kt`** and `ExpectationDrafts`, after every one of their test
      assertions had a successor in `DiffAuthoringTest`.
- [x] `ScenarioEditor.ExpectDetail` shrinks to the **step** — direction, timeout, bind predicate — plus
      the door to the diff. It no longer edits assertions at all.

**Phase 3 gate — met, with one honest gap.** There is exactly one surface in the app that can author or
repair an assertion, and exactly one host composing it: `rg` finds no `ReconcileView`, no
`ExpectationBuilder`, no `ExpectationDrafts`. Full suite green (**1208 tests, 0 failures**); every file
at or below its lint baseline (the tree is **1769** findings against the 2121 the same command reports
on the pre-phase tree — 352 fewer, most of them deleted with the surfaces). The fail → fix → save →
re-run → green loop is proven end-to-end against a **real FIX acceptor** by `ScenarioIntegrationTest`,
and click-by-click, with pictures, by `ScenarioDocumentsScreenshotTest`.

*The gap:* the loop was **not** driven live against `tools/fake-venue`'s `swap`/`shape` modes. The
control surface can run a scenario but it cannot *click*, and every repair in this phase is a click —
so a live pass over the reorder case is the author's, and it is the one thing this phase did not prove
in the app itself. (A live smoke against the demo acceptor did run: the app launches with both surfaces
deleted, the rail reports the failure, and the route is offered.)

### Phase 3 outcome — what actually happened

**V1 held, and it was the checklist that was wrong.** 3.1 and 3.2 contradicted each other about where
`DiffSurface` lives, and taking both literally would have allowed **two unsaved drafts of one
expectation** — one tab's Save writing the other's work away. That is the two-editing-surfaces defect
from the model doc, re-created between two tabs. The draft belongs to the scenario now.

**Two live defects were waiting in the phase's path, exactly as V4 and V7 predicted.**

- `reorder` was never told that an **unanchored** reference cannot judge a `~now` row. `rows()` has
  always known. So on a golden with no `SendingTime(52)`, a timestamp inside a moved entry was
  value-checked against the wall clock, "failed", and the tool printed the torn-entry refusal —
  *"the values there changed in place"* — about entries that had plainly swapped. **R2's defect, one
  matcher along.** Latent while every reference was a live run's; it fires the moment authoring routes
  to the diff, and it lands on market-data snapshots, every one of which carries an `MDEntryTime`.
- The golden-re-pointing rule, generalised to a slot without thought, would have had
  verify-generalizes **rewrite the golden to the second instance** — destroying the very thing it was
  checking against — and a pasted reference silently become the scenario's canonical example.

**And the picture found two more, which is now four phases out of four.**

The first screenshot of the diff read **"4 of 2 rows need attention"**. The numerator counts rows from
*both* sides — a tag the venue added is a row the expectation does not have — and the denominator
counted only the expectation's. There was never an honest ratio there, so the headline is now what the
mockup always said: *"N rows need attention"*, with the split below it.

Underneath that was the real one: **added tags counted as failures in OPEN.** OPEN's entire promise is
that an unmentioned tag is ignored — so a fully passing OPEN step whose venue sends three optional
fields announced *"3 rows need attention"*, painted itself FAILED, and **disagreed with the engine
about whether the step passed**. `canAcceptShape` has known the OPEN/STRICT distinction since Phase 1;
the verdict — the sentence read *first* — did not. `Verdict.of` now takes the mode, with **no default**:
a caller who forgets is a caller who calls a passing step failed.

Also found by looking: the matcher editor's `± tol` label had nowhere to go in the expectation column
and wrapped **one character per line**.

**Deviations, and things deliberately left:**

- The reconcile tab is titled `⇄ Step N · reconcile` and is keyed on `(scenarioId, stepId)`, so it
  follows the step if the author reorders the flow underneath it.
- `Save & re-run` is enabled on a **clean** step too. Re-running a step you have not touched is how you
  find out whether the venue has settled down, and that is a question worth being able to ask.
- The mockup's violet **crossing connector** between moved entries still does not exist; Phase 1 drew
  the move as band styling and a `⇄ Accept new order` chip, and Phase 3 did not add the curve. The
  information is all there; the line is not.
- `ScenarioService.load()` of an id that does not exist still fires a user-facing error toast (it cannot
  tell "missing" from "corrupt"). Pre-existing; still a Phase 7 cleanup candidate.

---

## Phase 4 — Drag moves, undo/redo, keyboard

### Decisions taken before implementation — read these first

Eight things the checklist under-specifies or gets wrong, settled here with the reasoning, so the next
reader does not re-derive them. **M1 and M2 change the shape of the phase, and M2 is a live defect: the
right-hand column, at a moved entry, is currently showing the reply's fields in an order the reply does
not have.**

**M1 — The refusal sentence is thrown away by `EditOp`'s shape. This is not wiring; it is the phase's
first commit.**

Phase 0 built the validator and it answers properly: `moveRow`/`moveEntry` return
`MoveResult.Applied(expectation) | Refused(why)`, and every refusal is a hand-written sentence naming the
exact assertion the move would have re-aimed. Phase 1 then defined an `EditOp` as `(Expectation) ->
Expectation?` (P2), where **null means both "the engine refused" and "nothing would change"** — and
`EditOp.moveRow` duly does `(moveRow(…) as? Applied)?.expectation`. So `Refused.why` is computed, and
dropped on the floor, every time.

Today that is invisible: the ↑/↓ arrows are *disabled* where a move is illegal, so the sentence is never
needed. A drag has no disabled state — the author is already holding the row over the forbidden gap, and
the drop either lands or it does not. Phase 4's whole promise is *"refused drops snap back with the reason
at the cursor"*, and ground rule 6 says **a refused action says why, where the user is looking.** A UI that
cannot see the reason cannot show it.

> **`EditOp.apply` returns an `EditResult` — `Applied(expectation) | Refused(why) | Unchanged` — and
> `ReconcileSession.apply(op)` returns it.** Ops that cannot refuse are built through `EditOp.pure { … }`,
> which maps today's `null` to `Unchanged`, so every existing op's construction is untouched and its
> behaviour is byte-identical. Only the two move ops learn to speak.

`Unchanged` is kept separate from `Refused` deliberately: a no-op move (`from == to`, the drop that landed
where the row already was) must say **nothing at all**, and collapsing the two would put a refusal sentence
under a drag that simply did not go anywhere. The session gains `refusal: String?` — set by the last
refused `apply`, cleared by the next successful edit, by undo/redo/discard, and when a fresh drag starts —
because a keyboard move (`alt+↓`) has no cursor to put a tooltip at and must still say why.

**M2 — A moved entry's rows do not face the fields the venue sent there, and that is why the crossing
connector cannot be drawn. The pairing reverts to wire order.**

The mockup's moved entry reads: left `448 exact FIRMA`, right `448 FIRMB` — the reply, **in its own
order** — and a violet curve crossing the gutter to say *FIRMA is down there*. That is what every diff tool
does: each side keeps its own order, and the connector carries the move.

The implementation does the opposite. `ComparisonSemantics.chunk()` special-cases `MOVED` and pairs each
moved row with **the field it landed on** (`placement`), so FIRMA's row faces FIRMA. `AlignmentModelTest`
pins it: `moved[0].right` is wire `3,4,5` and `moved[1].right` is wire `0,1,2`. Read the right-hand column
top to bottom and it says `3,4,5,0,1,2` — **a message no venue sent, wearing tag=value**, which is the
exact sin `wireRaw` exists to prevent, committed in the renderer instead of the codec.

Two consequences, and the second is the phase's whole visual:

- The reader cannot see *where* the entry went. Both sides say FIRMA; the only thing saying it moved is a
  9sp note. The surface's own kdoc promises *"a party that arrived out of order is one violet thing that
  crossed, not six red rows"* — and under placement-pairing **there is nothing to cross.** Two straight
  lines are not a crossing.
- The justification in the code is not true. `AlignmentModelTest` says *"a moved row pairs with nothing, so
  its own alignment has no wire position"* — while `ComparisonSemantics`, forty lines away, says the
  opposite and is right: *"every row still pairs: the rows go red as value mismatches (FIRMA's 448 landing
  on FIRMB) and not one of them carries `TagStatus.MOVED`."* A moved row's `wireIndex` is set, and it is
  the positional one. The `MOVED` branch is overriding a correct pairing with a nicer-looking one.

> **Delete the `MOVED` branch in `chunk()`'s pairing.** A moved row faces the field at its `wireIndex`, like
> every other paired row. `Chunk` gains `landing: List<Int>` — the wire indices its rows went to, published
> from `placement` — which is the connector's other end and the only new thing anyone needs.

**And this re-arms the trap Phase 1 disarmed by accident.** Under the old pairing, FIRMA's row showed
FIRMA on the right, so an Accept-actual under it would have been visibly absurd. Under the true pairing it
shows **FIRMB** — a value mismatch to every eye — and the one thing standing between the author and *"FIRMA
holds role 1"* being deleted by a single click is `offersFor`'s `if (row.index in moved) return emptyList()`.
That guard was found by a screenshot with fourteen tests green. It is now load-bearing again, and it is
re-mutation-checked in this phase.

**M3 — The connector is drawn from `placement`, never from `moveLink` — and a rotation is why.**

`linkMoves` pairs the *k*-th moved chunk with the *k*-th chunk by landing place. For two entries that
traded places they point at each other and it is right. For a three-way rotation it is the **inverse edge**
— Phase 0's review recorded this and left it for the phase that draws the line, which is this one.

A move is not a pairing, it is a **function**: chunk *k*'s rows went to wire span *L(k)*. So the connector
is one curve per moved chunk, from its left band to the display line whose right cell holds `landing.first()`
— which under M2 is a real, findable line, because the right column is in wire order. For a swap that draws
two curves that cross; for a rotation, three that cycle. `moveLink` stays as metadata and the surface stops
consulting it.

**M4 — The display list moves out of the composable and into the model, or none of this is testable.**

`itemsOf(model)` — bands, lines, hues, depths, and which band is the *first* moved one — is a private
function of `DiffSurface.kt`. Phase 4 needs that list in three places that are not drawing: `↑/↓` walks it,
`n`/`p` walks the chunks in it, and the connector needs the **item indices** of the two ends to ask
`LazyListState.layoutInfo` where they are on screen. Leave it in the composable and all three become
Compose-only, which is where this project's defects go to hide.

> **`DiffModel.items: List<DiffItem>`**, built in the session with the rest of the model, memoized with it.
> The composable renders items and decides nothing — the same rule P5 already applied to *what faces what*,
> applied now to *what is drawn in what order*.

**M5 — Selection lives in the session, and it must follow a move — or `alt+↓` twice moves two different
rows.**

Trap 5: only the active document is composed, so a selection in a `remember` is destroyed by a glance at
the session grid, and the author comes back to a surface where `n` starts from the top again. It goes in the
`ReconcileSession` (which is a field of `ScenarioDoc.Reconcile`, exactly as V3 put the undo stack there) —
not in `ScenarioDoc.Reconcile` directly, because that would push a ViewModel document-list update and a
`StateFlow` emission through every press of an arrow key.

The part that is a defect waiting to happen: **selection is an index, and a move is precisely the thing that
changes indices.** Select row 3, press `alt+↓`: the row is now at 4, and a selection still reading 3 is
pointing at whatever slid up into its place. Press `alt+↓` again and the author moves a *different row* —
having pressed the same key twice, watching the same highlight. So the selection is re-anchored from the
move that was applied, and a test presses `alt+↓` twice and asserts the same row travelled two places.

Selection is `Line(index)` for an expectation row, `Line(wireIndex)` for a right-only one (it is not a row
of the expectation, so `alt+↑/↓` does nothing on it and says nothing — there is no move to refuse), and
`Band(entry)` for an entry band, which is what makes `alt+↑/↓` move an entry without a second key.

**M6 — Two key handlers, and which key goes in which is not a matter of taste.**

The left column is full of live text fields. Attach one `onPreviewKeyEvent` to the surface and typing `n`
into a value field navigates to the next chunk instead of typing `n` — a keyboard shortcut that eats the
alphabet.

> **Bare keys (`↑` `↓` `n` `p` `esc`) go through `onKeyEvent`, which *bubbles*:** a focused `BasicTextField`
> consumes its own arrows and characters, and the surface only ever sees the keys nothing else wanted.
> **Modified keys (`⌘Z` `⌘⇧Z` `alt+↑/↓`) go through `onPreviewKeyEvent`, which *captures*:** they belong to
> the document, and Compose's text field carries its own undo stack — leave `⌘Z` to bubble and an author
> editing a value undoes *characters* out of a field while the diff's snapshot stack, which is the thing the
> footer is counting, sits still. Two undo stacks over one state is the defect P2 refused to build; it must
> not arrive through the keyboard.

The surface's root is focusable and takes focus on first composition; **clicking any row selects it and
returns focus to the surface**, so the bare keys work again the moment the author leaves a text field.

**M7 — `esc` is already taken, and it keeps its meaning. It becomes a stack.**

`App.kt` binds `Escape` to *"close the focused document; a dirty one confirms"* (Phase 2, T8). The checklist
also wants `esc` to cancel the diff. They are not in conflict once the diff has a drag: an `esc` while
something is *in flight* is about the thing in flight, and only when nothing is does it mean the tab.

> **`esc` cancels the drag if one is live; otherwise it dismisses a refusal if one is showing; otherwise it
> falls through to `App.kt` and closes the document.** The surface consumes it only in the first two cases.

Nothing is lost: Cancel *is* close-the-tab for a diff document (`onCancel` already discards and closes), so
the footer's *"esc cancel"* is honest — with the dirty confirm the app decided on in T8 and the mockup does
not draw.

**M8 — The would-pass tooltip is a dry run of the same op, and it needs a third sentence.**

The mockup draws two: *"drop here — every row would pass ✓"* and the refusal. There is a third case it does
not draw and the author will meet constantly — **the move is legal and the step still fails**, because a
value is wrong somewhere else entirely. Answering that with the green sentence is a lie; answering it with
silence makes the tooltip's own question (*would every row pass here?*) unanswered exactly when it is
interesting. So: *"drop here — N rows would still need attention"*.

The dry run is `session.preview(op)` — the identical `EditOp`, applied to the draft and **not staged** — so
the tooltip and the drop cannot come to disagree, and a legal drop is judged by re-running `Verdict` over
the moved draft. It is recomputed **only when the landing index changes**, not per pointer event: the
re-judge enumerates every contiguous block of the expectation and scans each across the wire (P4's memo
exists for exactly this), and a drag fires sixty times a second. Nothing would fail if it were per-pixel; it
would merely be slow, for a reason nobody would ever find.

**M9 — The drag's own state is the one thing that belongs in the composable.**

Everything else this phase adds is hoisted (M4, M5), so the exception needs stating: `dragging: DragState?`
— which row, where the pointer is, what the preview says — is **ephemeral by definition.** A drag cannot
survive the tab being switched away from, because the pointer cannot. It dies with the subtree, and that is
correct rather than tolerated.

Two small things that ride along, both of which are bugs if got wrong:

- **The handle is always drawn, and brightens on hover** — it never appears *on* hover. A handle that
  materialises reflows the row it is in, which moves the drop target the author is aiming at, under the
  pointer that is aiming at it.
- **One drop is one snapshot.** The move is applied at release, not per pointer move, so `⌘Z` undoes *the
  move*, and moves never coalesce with each other (each is a discrete fact, unlike the keystrokes of a value
  — `EditOp.coalesceKey`, P2).

### 4.1 The refusal reaches the surface (M1) — **complete**
- [x] `EditResult = Applied | Refused(why) | Unchanged`; `EditOp.apply` and `ReconcileSession.apply` return
      it. `EditOp.pure { … }` preserves every existing op verbatim (`null` → `Unchanged`).
- [x] `ReconcileSession.refusal: String?` — the last refusal, cleared by the next successful edit, by
      undo/redo/discard, and by the start of a drag. `preview(op)` dry-runs without staging (M8).
- [x] Tests: a refused `moveRow` leaves the draft **byte-identical** and carries the engine's sentence
      verbatim; a no-op move is `Unchanged` and says nothing; `preview` stages nothing and pushes no undo.

### 4.2 Wire-order pairing and the crossing connector (M2, M3) — **complete**
- [x] Deleted the `MOVED` pairing override in `ComparisonSemantics.chunk()`; `Chunk.landing: List<Int>`
      published from `placement`. `AlignmentModelTest`'s two pinned assertions changed — **they pinned the
      defect**, and the new one asserts what was actually wrong: the right column, read top to bottom, is
      the reply's own order.
- [x] Re-mutation-checked `offersFor`'s moved-row guard — **two tests die without it**, and M2 is what made
      it load-bearing again (FIRMA's row now shows FIRMB, which reads exactly like a value mismatch).
- [x] `DiffModel.items` (M4): bands and lines, hues, depths, first-moved-band — out of `DiffSurface`,
      memoized with the model, tested without Compose.
- [x] The violet crossing connector: a `Canvas` over the `LazyColumn`, one curve per moved chunk, from its
      band to the display line holding `landing.first()`, positioned from `LazyListState.layoutInfo`. Ends
      scrolled off-screen leave the viewport in the direction the entry went. A three-way rotation draws
      three curves, not two (M3).

### 4.3 Drag: rows and entries — **complete**
- [x] Row drag from the `⠿` handle: insertion line at the landing, live would-pass tooltip (M8) with its
      three sentences, refused drops snap back with the reason at the cursor. Every drop routes through
      `ScenarioReconcile.moveRow` — the UI cannot construct a move the engine did not approve.
- [x] Entry drag from the band handle (the overlay's range → `moveEntry`); the ↑/↓ buttons stay.
- [x] The withheld-move sentence keeps rendering inline on the group (role-swap case) — unchanged.

### 4.4 Keyboard — **complete**
- [x] `⌘Z`/`⌘⇧Z` (preview), `alt+↑/↓` (preview) → the existing stack and the existing validator; `↑/↓`
      selection, `n`/`p` next/previous **diff** chunk (skipping `SAME`, no wrap), `esc` (bubble, M7).
- [x] Selection in the session (M5), **re-anchored after every move**; the header gained the mockup's
      `↑ prev` / `↓ next diff` buttons, wired to the same functions `n`/`p` call.
- [x] Tests: `alt+↓` twice moves **the same row** two places (the M5 defect, mutation-checked); typing `n`
      into a value field types `n` — **and M6's stated mechanism was wrong, see below**; `⌘Z` walks the
      diff's stack while a value field has focus, and the field's own undo never runs.

### 4.5 The gate — **complete**
- [x] Real **drag** UI tests (`performMouseInput`), screenshots in `composeApp/build/scenario-screenshots/`:
      the out-of-order hand-authored row fixed by a drag (with the *would-pass* tooltip before the release),
      the refused sibling-crossing drop with its reason at the cursor, and the entry drag.
- [x] Undo restores byte-equal drafts across a mixed edit/move/accept sequence.
- [x] Mutation-check: disable the occurrence-mapping guard, a test fails.

**Phase 4 gate — met.** Full suite green (**1227 tests, 0 failures**); every touched file at or below its
lint baseline (`DiffSurface` 3, `ReconcileSession` 2, `ComparisonSemantics`/`DiffPalette`/`DiffMoves`/
`ControlServer` 0). The mockup-4 scenarios are reproduced and photographed by `DiffDragTest`, click by
click, with a real mouse.

**And the loop Phase 3 could not drive is now driven — by the machine, not by hand.** `/scenarios/reconcile`
opens the diff on a failing step through the *same* `reconcileRoute` decider the rail's **Reconcile →**
button calls, so the surface that repairs an assertion is finally reachable without a human hand. Live
against `tools/fake-venue`: `golden` captures and passes; `shape` fails with **the two party entries
swapped** (plus a real `151` regression and a dropped `58`) and the route opens on the Expect; `swap` fails
with **the two firms' roles exchanged** and the route opens on the same step — no exceptions, correct
`stepId` both times. `ControlServerIntegrationTest` pins the whole loop against a real acceptor: run → fail
→ open the diff → the document is a `ScenarioDoc.Reconcile` on the right step, bound to the bytes that
failed it, whose verdict agrees with the engine's.

*The one thing not done here:* **live pixels.** `GET /screenshot` returned an all-black frame throughout —
the display in this environment is asleep, and `screencapture` gets the same black frame, so it is the
machine and not the app. The picture evidence is therefore the Compose screenshot set, which is driven by a
real mouse; the live pass proved the *route*, the *bytes* and the *absence of exceptions*, not the pixels.

### Phase 4 outcome — what actually happened

**M1 and M2 were both real, and M2 was live.** The refusal sentence *was* being computed by the validator and
discarded by the op that called it (`(Expectation) -> Expectation?`, where `null` meant both "refused" and
"nothing changed") — invisible only because the ↑/↓ arrows are *disabled* where a move is illegal, and a drag
has no disabled state. And the right-hand column *was* showing the reply's fields in an order the reply does
not have: `AlignmentModelTest` pinned `moved[0].right == [3,4,5]` and `moved[1].right == [0,1,2]`, so reading
the column top to bottom across a swapped party group gave `3,4,5,0,1,2`. A message no venue sent, in the
renderer. The test that pinned it justified itself with a sentence — *"a moved row pairs with nothing"* —
that `ComparisonSemantics` contradicts forty lines away, and is right to: a moved row pairs perfectly well,
positionally, which is exactly why its rows go red as value mismatches.

**M6 was right about the danger and wrong about the mechanism, and the test said so.** The decision was that
bare keys should *bubble* (`onKeyEvent`) because "a focused text field consumes its own characters first".
That is true on Android and **false on the desktop**: Compose's text field takes a printable character from
the separate `KEY_TYPED` event and leaves the `KeyDown` for `N` completely unconsumed. It bubbled straight
into `nextChunk()` — the letter still arrived in the field, *and* the author was thrown down the diff. The
guard is now `isFocused` on the surface's own node. Bubbling still earns its place (it is what leaves the
arrow keys to a text field's cursor), but it is not sufficient, and the reasoning that said it was is
recorded where the next reader will meet it.

**The picture found what no assertion did — five phases out of five.** A row the venue sent *in another
position* rendered its right-hand gap as **"not sent"**. The engine has always distinguished the two
(`TagStatus.MOVED` — *"present, but not in this position"*), and `ChunkKind`'s own comment promised the
surface would keep saying it; the renderer collapsed every gap into the same three words. So a hand-authored
step whose rows are out of wire order **accused the venue of dropping a field that was sitting two lines away
on the same screen** — sending an engineer to hunt a regression that does not exist, which is the exact
failure this area keeps producing. Reproduced first, then fixed.

**And the semantics tree found one that a screen reader would have found the hard way.** `Modifier.clickable`
on a row sets `mergeDescendants`, which folds the whole row into a single semantics node — so the matcher
editor's value field, the gutter's buttons and every child test tag became unreachable. Seven tests went red
at once and named it. Selection now taps through a gesture, which carries no semantics and merges nothing.

**A deviation, deliberately taken, and it is outside the proposal's stated scope.** The proposal puts the
control surface out of scope (*"`fixtool_panel` toggles the rail; everything else is untouched"*), and Phase 4
adds **`POST /scenarios/reconcile`** (plus its MCP tool). The reason: every repair in this surface is a
click, the control surface cannot click, and so the one surface in the app that can author or repair an
assertion was the one surface no automated run had ever opened against a real venue's bytes — which is the
gap Phase 3 recorded and could not close. It is not a second door: it calls `openReconcile` on the same
`StepResult`, through the same `reconcileRoute` decider, as the rail's button, so a route it refuses is a
route the button refuses in the same words. It made this phase's gate provable, and it makes Phases 5–7's
gates provable too.

**Deviations, and things deliberately left:**

- **The `⇄ Accept new order` chip still shows only its glyph**, not the mockup's full label — it is a 56dp
  gutter, and the words do not fit. Pre-existing from Phase 1; the band beside it now says *"⇅ moved — same
  tags, same values, different position"*, so the meaning is on screen even if the button is terse.
- **A drag does not auto-scroll** when the pointer reaches the edge of the viewport. On a 200-field
  market-data snapshot an entry cannot be dragged past the fold; the keyboard (`alt+↑/↓`) can. Worth fixing,
  not blocking.
- **The connector is drawn between chunk *centres*.** For entries of very different heights the curve leaves
  and lands slightly off the band's own line. It is a stroke, not an assertion.
- `ScenarioService.load()` of an id that does not exist still fires a user-facing error toast. Pre-existing;
  still a Phase 7 cleanup candidate.

---

## Phase 5 — Reference slot: paste, pick, provenance

### Decisions taken before implementation — read these first

Eleven things the checklist under-specifies or gets wrong, settled here with the reasoning, so the next
reader does not re-derive them. **Three are live defects**: the header calls a step FAILED that did not fail
(S1), a run silently takes away the message the author chose to compare against (S2), and the fake venue
stopped sending the pipe that is the entire reason it exists (S10).

**S1 — The verdict's sentence is chosen by the provenance, PICKED and PASTED have none — and the FAILED
chip is a sentence too, which is where this is already lying.**

`Verdict.headlineAgainst` (V5) knows one special case: against a SECOND_INSTANCE a red row is the author's
assertion being **over-specified**, not the venue regressing. PICKED and PASTED fall through to *"N rows need
attention"*, which in this surface has always meant **the venue did something new**. Against a message from
another environment — which is the whole point of a paste — that is not what it means, and it sends an
engineer hunting a regression that does not exist. That is the failure this area keeps producing, and it is
the one V5 was written to stop.

What a red row means is decided by *what licensed the comparison*:

| slot | a red row means | the sentence |
|---|---|---|
| THIS_RUN | the venue did something new | *"N rows need attention"* |
| GOLDEN | the expectation and the step's own capture disagree | *"N rows need attention"* |
| SECOND_INSTANCE | the author asked *does this generalize?* and it does not | *"⚠ N rows are over-specified"* |
| PICKED · PASTED | **FixTool does not know** | *"N rows do not hold against the pasted message"* |

> **Against a slot the author bound by hand, the tool reports and does not diagnose.** It cannot know why the
> author bound that message: it may be UAT's reply to the same order, a rejection where a fill was captured,
> or another venue entirely. *"Does not hold"* is the whole of what is true, and the split (`1 value changed ·
> 1 tag missing`) says the rest.

**And the header's `FAILED` chip is the same claim in five letters, drawn from `verdict.needsAttention` with no
idea what is in the slot.** V5 fixed the headline and left the chip, and the chip is read first. The verdict
line's error tint is the same bug in colour.

**This is live, on the one path that already exists.** `bindPickedReference` is reachable today from V6's
prompt — *"Use the message selected in the grid"* — and that prompt is shown **precisely when the step has
never run and has no golden**. So an author binds a message from the grid, three rows do not hold against it,
and the surface answers *"3 rows need attention"* under a red **FAILED** chip — about a step that **has not
run**, cannot have failed, and is being *authored*. Both sentences are false, and they are the two a reader
takes first. (SECOND_INSTANCE is worse in kind but reachable only from `DiffAuthoringTest` until this phase's
menu ships — which is exactly why the menu must not ship before the sentence does.)

So the chip's word comes from the same function the headline does — `Verdict.chipAgainst(provenance)`:
`failed` (THIS_RUN) · `would fail` (GOLDEN — the step has not run; this is a prediction and says so) ·
`over-specified` (SECOND_INSTANCE) · `does not hold` (PICKED, PASTED) — the first two in error, the rest in
warning. One decider, in `Verdict`, tested there. And the right column's heading stops saying **RECEIVED** over
a message that was not received.

**S2 — A run must not take away the message the author chose to compare against. V9 meets the slot, and the
slot loses.**

V9 re-binds **every** open diff of the scenario that ran, so that *Save & re-run* cannot leave the author
looking at the failure they have already fixed. It is right, and it is unconditional: `rebindReconcileDocuments`
calls `swapReference` on every reconcile document of the ran scenario, whatever is in its slot.

Phase 5 is what makes that a defect at scale — and, like S1, **it is already live on the one hand-binding path
that exists.** Bind a message from the grid through V6's prompt (provenance PICKED), let any run of that
scenario land, and the picked message is **silently replaced** by the run's bytes. With the swap menu it
becomes the ordinary case: an author binds a PASTED reference — UAT's reply, the reason they opened the diff —
tightens two rows against it, presses **Save & re-run** (the button is in the same header), and the thing they
were comparing against is gone, at the moment they were using it, and nothing says so.

> **A run re-binds the slots it owns, and only those.** THIS_RUN, and GOLDEN — a step that had never run now
> has a run, and that is the answer the author asked for by pressing the button. SECOND_INSTANCE, PICKED and
> PASTED were bound *by hand*, and they are **kept**.

`thisRunWire` is still updated in every case, so the swap menu's *received — this run* entry offers the **new**
run's bytes; and a document whose slot was kept says a newer run has landed, with the one click that goes to
it. Ground rule 6 in both directions: not silently replaced, and not silently withheld either.

**S3 — The golden still follows THIS_RUN and nothing else (V4) — and the `pasted` badge is not decoration, it
is the explanation for what that leaves behind.**

V4 stands exactly as written: `applyExpectation` re-points `expectation.golden` **only** when the reference is
THIS_RUN. A hand-doctored paste must never become the scenario's canonical example, because every future
authoring session is judged against the golden and would then be judged against bytes no venue sent.

The consequence Phase 5 creates, and which nothing in the plan mentions: an author who repairs a step against a
PASTED reference and saves has an expectation whose **rows describe the paste** and whose **golden still
describes the old capture**. Re-open the authoring diff — reference GOLDEN — and rows go red against the step's
own canonical example. Correctly. And inexplicably, unless the surface says why.

> So the badge is load-bearing: **`pasted` is the sentence that explains why a step's rows and its own golden
> disagree.** It is on the step in the editor, on the diff header, and it survives the save (S4). A badge that
> only appeared while the paste was bound would vanish exactly when the explanation was needed.

**S4 — Provenance on disk is a step-level additive key, and an unknown value degrades toward *less* trust,
never more.**

**On the step**, as the checklist says, and not on the `Expectation` — because capture's second source (S9)
makes **Send** steps out of pasted bytes too, and a Send has no expectation to hang it on. `ScenarioStep` gains
`origin: StepOrigin` beside `stepId`, on the interface, with the same defaulting and the same codec rule:
**written only when it is not the default**, so a file that never had the key does not grow one (invariant 5 —
a file that loads today loads identically after every phase).

Two values, not five. The *reference* has five provenances because the slot has five sources; the **step**
records only whether what was written was vouched for:

- `LIVE` — the bytes came off a wire FixTool was connected to. THIS_RUN, GOLDEN, SECOND_INSTANCE and PICKED are
  all this: FixTool watched them arrive.
- `PASTED` — bytes FixTool cannot vouch for: a log, an email, another environment.

So the question *"which provenance taints a save?"* has one answer — **PASTED** — and it is the only one the
badge has to carry.

**An unrecognised value on load reads as `PASTED`, not as `LIVE`.** Unlike `mode` — where an unknown value
changes *what is checked*, and so fails the load loudly (D4) — an unknown origin changes nothing that is
checked. But defaulting it to LIVE would let a file claim **more** trust than it carries, and the badge exists
precisely to stop that. Mutation-checked.

**S5 — The paste is read, and where the reading is *disproved* it is refused. This is the phase, and the pipe
is the reason.**

`58=filled|in full` is legal FIX. `|` is an ordinary character inside a value, `tools/fake-venue` sends one on
purpose (S10), and this codebase has shipped the bug twice: `GET /messages` reporting `58 = "filled"` and
silently dropping the tail, and the reconcile view diffing against the `|`-substituted display string. The
right-hand column of this surface is what **Accept actual** writes into the scenario — so a paste read wrongly
is a value **no venue sent**, written into an assertion, for ever.

`FixMessageHelper.parseFixMessage` already has the delimiter precedence right — **SOH wins, and a `|` is a
delimiter only when there is no SOH** — and it stays the one decider. What it does not do is *say* anything: a
segment it cannot read (`in full`) is **silently dropped**, which is precisely what makes the pipe invisible.
The paste box reads the segments itself, keeps what would have been dropped, and reports.

Three verdicts, and *Use as reference* follows from them:

1. **READ** — the bytes carry SOH (unambiguous by construction: SOH cannot occur inside a FIX value*), **or**
   they are `|`-rendered and **the message's own arithmetic agrees** — `BodyLength(9)` and `CheckSum(10)`,
   recomputed over the bytes as read. The message verifies its own reading, and nothing is guessed.
2. **UNVERIFIED** — every segment read cleanly, but there is no `8=`/`10=` pair to check it against (a log
   *fragment*). Bound, and **said**: *"no CheckSum(10) — nothing in these bytes can confirm that every `|` was a
   delimiter. If a value here contains one, it has been split, and there is no way to tell."* `PASTED`
   provenance is what carries that doubt forward, to the badge and to disk.
3. **REFUSED** — the reading is **disproved**: a segment at or after the first field that is not `tag=value`
   (which is exactly what a pipe inside a value looks like — `58=filled`, then `in full`), or a `CheckSum` that
   disagrees with the bytes as read. Nothing is bound. The lint quotes the evidence and names the fix — *paste
   the raw bytes; a `|`-rendered log cannot be read back where a value contains a `|`.*

**And it must not repair.** The checksum says **how many** pipes were read wrongly — each costs
`0x7C - 0x01 = 123`, mod 256 — and never **which**. Searching for the subset of pipes that makes the sum come
out is guessing with extra steps, and it would eventually produce a message no venue sent wearing a checksum
that agrees with it. *The engine reads the venue's bytes, or it refuses to judge* — the model doc's own words
for open question 5, and a paste is not an exception to them.

Two rules fall out, and both are needed or the box is unusable:

- **A log prefix is skipped and reported; it is not a refusal.** Real lines look like
  `08:12:31.412 INFO [pool-1] IN: 8=FIX.4.4|9=196|…`. Everything before the **first readable field** is ignored
  and the lint says what was ignored. Only an unreadable segment *at or after* the first field is proof of a
  mangled value. Without this the box refuses every log line ever pasted into it.
- **More than one message in the slot is a refusal, not a choice.** A second `8=FIX…` after a `10=` means the
  author pasted two messages; the slot takes one, and which one is not FixTool's to pick. (Capture's paste box
  takes one message per line — that is *its* contract, and it is the same reader.)

> \* the exception, stated because it will be met: a **length-prefixed data field** (`RawDataLength(95)` /
> `RawData(96)`, `XmlDataLen(212)`, …) may legally contain SOH. FixTool's parser has never honoured the length
> prefix. Such a message reads as junk segments and is refused by the rule above — which is the honest outcome,
> and better than the silent misread it replaces. Noted for Phase 8, not fixed here.

**S6 — The anchor is *said*, and an anchorless paste is not a broken paste.**

Everything is already built: `ReferenceMessage.pasted` anchors on the message's own `SendingTime(52)` and leaves
`anchorInstant = null` where there is none (0.5); `rows` and `reorder` both carry the unjudgeable set and the
`NO_ANCHOR` sentence (0.5, V7). Phase 5 owes it one thing — the lint line **says which**: *"temporals anchor to
SendingTime(52) 08:12:31"*, or *"no SendingTime(52) — `~now` rows are left unjudged, not failed."*

A fragment with no `52` is perfectly usable. It simply cannot judge a moment, and the row says so, in amber,
rather than manufacturing a red about how long ago the message was pasted.

**S7 — `SemanticsContractTest` is widened to the `ReferenceMessage` overload — and it is given what the
reconcile view actually has, which is *no resolver*.**

Phase 0's review recorded this and left it: the harness exercises only the `MessageView` overload, which is the
one that does **not** carry the anchoring rule. **R2 is why it matters.** `ReconcileAnchorTest`'s *"an entry
carrying a reference is still recognised as having moved"* passed — because the fixture handed the engine a
working `referenceResolver` **the app can never supply**. A reconcile view has no run scope; there is nothing to
pass. The fixture dodged the hard case, and a row nobody could read hid an entry that had plainly moved.

Two properties on top of the five that exist, both run against every registered semantics, both
mutation-checked:

6. **The reference overload pairs exactly as the message overload does** over the same bytes. Pairing is blind
   to provenance and to the anchor, as it is already blind to the matcher — or the diff and the runner would
   come to disagree about what faces what.
7. **A row nobody can read is `unknown`, never `false`.** With the resolver a reconcile view actually has
   (none), a `reference` row is unjudged; with an unanchored reference, a `temporal` row is unjudged. Not
   failed, not passed, and not counted.

**S8 — The armed slot lives on the ViewModel, because the grid it is waiting for is somewhere else — and after
Phase 6 it is in another *window*.**

V6 shipped pick-from-grid in its cheapest honest form: a button that binds *whatever is selected right now*. The
mockup's is the other way round — **arm the slot, and the next grid row click binds it.**

Arming cannot live in the diff composable, and not in `ScenarioDoc.Reconcile` either. The author must **leave
the diff** to click a grid row, and only the active document is composed (trap 5) — so the state that says *"the
next click means this"* must be visible to the grid, the detail panel and the diff at once, and two of them
cannot see the document. It is `armedReferenceSlot: String?` (the diff's subject id) on the ViewModel, for the
same reason `activeDocumentId` is (T3): three surfaces, one fact, one decider.

The direction change of 2026-07-14 makes this the phase's one load-bearing dependency on Phase 6, and the plan
says so in the checklist: the diff moves into its **own window**, so the arming must survive a *focus hop
between windows*. State held anywhere else does not, and a re-write in Phase 6 would be a second decider
arriving in the middle of a feature whose whole promise is that one click means one thing.

Clicking a grid row while armed binds it and disarms; `esc` disarms; and a message whose `wireRaw` FixTool does
not have **cannot be bound at all** — invariant 3 — which is refused at the click, in words, rather than by a
click that does nothing.

**S9 — A pasted capture candidate has no direction, and it may not be given one by a guess.**

A live candidate carries its session and its direction from the wire it arrived on. A paste carries neither.
The session is the author's (dropdown, required). The direction is the trap:

**Default it to `in` and a reply mis-marked as a Send becomes a step that asserts nothing.** The scenario sends
the venue's own ExecutionReport back at it, the step "passes" — a Send always does — and the coverage the author
thought they were capturing is silently gone. That is a false green by omission, and it is the one outcome this
project does not accept.

> **The bytes decide it where they can, and the author decides it where they cannot.** Once a session is
> assigned, `SenderCompID(49)` equal to that session's `senderCompID` is **outgoing**; equal to its
> `targetCompID` is **incoming**. Where the bytes do not decide — no session config, no `49`, a log from a third
> party — the row's direction is **undetermined**, and an *included* row with an undetermined direction
> **blocks Save**, naming the rows.

And it is the same capturer, not a second one. `ScenarioCapture.captureFrom` reads only `direction`,
`messageType` and the golden bytes off `Candidate.message` — so `Candidate` carries those three **itself**, and
its `FixMessage` becomes `source: FixMessage?`: **null for a paste**, which has no source row to highlight.
Synthesising a `FixMessage` to fill the field would put a message in the grid's selection that never arrived on
any wire, which is the same lie in a smaller font.

**S10 — The fake venue stopped sending the pipe. The trap the whole file exists for has been disarmed since the
modes landed, and this phase's gate pastes its log.**

`tools/fake-venue/README.md` gives the pipe its own row in the table of traps: *"`58=filled|in full` — a pipe
**inside a value**. Legal FIX… Anything that establishes a message's delimiter by looking for a pipe shreds this
field. It is what caught `GET /messages` reporting `58 = "filled"`, silently dropping the tail."*

The venue sends `58=filled in full`. **A space.** The pipe was there in `9e6427d`, with the comment *"NOTE the
pipe: a legal FIX value"* — and `088251c`, the commit that added the three modes, rewrote those lines without
it. Every live run against `golden`, `shape` and `swap` since then has been run against a venue whose headline
trap is not armed, and the README has been describing bytes nobody sends. **The fixture dodged the hard case** —
in the tool that exists to stop exactly that.

Restored, in all three modes. And the venue gains the one thing a real venue has and this one does not: **a
message log with the bytes in it** (SOH-delimited, both directions), because W2 is *paste a log fragment* and
the venue's stdout renders SOH as `|`. With the pipe back, that stdout line is precisely the ambiguous form S5
refuses — so **both halves of S5 are demonstrable against the same venue, live**: the SOH log line reads, and
keeps the pipe inside `58`; the `|`-rendered stdout line is refused, with the message's own checksum as the
evidence.

**S11 — The menu shows what it cannot offer, and why. A missing entry is a feature the author concludes was
never built.**

Three of the five entries have nothing behind them some of the time: *received — this run* (no run), *golden*
(never captured), *second instance* (no later live message of the type — `liveSecondInstance` already answers
this). They are **drawn, disabled, with the reason**, exactly as the mockup's semantics menu draws its
tree/GumTree slots. Ground rule 6: no silently missing button. *Pick from session…* and *paste wire…* are always
available, because both are ways of *getting* a message rather than uses of one FixTool already has.

### 5.0 The sentences the slot changes (S1) — and the run that must not steal it (S2)

- [x] `Verdict.headlineAgainst` gains PICKED and PASTED: *"N rows do not hold against the
      pasted message"* — never *"need attention"*, which in this surface means the venue
      regressed. `Verdict.chipAgainst(provenance)` replaces the header's unconditional
      `FAILED` chip, which today calls a step FAILED because a **second instance** has two
      over-specified rows. Reproduce that on the current code first. Both in `Verdict`,
      tested there, mutation-checked; the verdict line's error tint follows the chip, and
      the right column stops saying **RECEIVED** over a message that was not received.
- [x] `rebindReconcileDocuments` re-binds only THIS_RUN and GOLDEN (S2). A slot the author
      bound by hand survives the run, `thisRunWire` is updated anyway (so the menu offers
      the new bytes), and the surface **says** a newer run has landed. Test: bind a paste,
      Save & re-run, the paste is still on the right.

### 5.1 The reference chip and the swap menu

- [x] Reference chip + swap menu in the `DiffSurface` header: this run · golden ·
      second instance · pick from session… · paste wire…. Swapping re-judges instantly
      (session already supports it; this is the UI). Entries with nothing behind them are
      **disabled with the reason**, never hidden (S11).
- [x] Pick from session: arming the slot lets the next grid row click bind the
      reference (and highlights bidirectionally while bound). **The armed state and the
      binding must live on the ViewModel, not in the diff document's composable** —
      Phase 6 moves the diff into its own window, and this interaction must survive the
      focus hop to the main window unchanged. A message with no `wireRaw` is refused at
      the click, in words (S8).

### 5.2 The paste sheet and its lint line (S5, S6)

- [x] `service/compare/WirePaste.kt` — one reader, two hosts (the slot and capture).
      Multi-format parse (SOH preferred, `|` accepted — through `parseFixMessage`'s
      detection, which stays the one delimiter decider); **the lint line reports what was
      read**: field count, delimiter, the log prefix it ignored, the segments it could not
      read, and the message's own arithmetic (`BodyLength(9)`, `CheckSum(10)`) where the
      paste carries it.
- [x] Three verdicts (S5): **READ** (SOH, or `|` with the checksum agreeing) · **UNVERIFIED**
      (clean, but no checksum to confirm the delimiter — bound, and said) · **REFUSED** (an
      unreadable segment at or after the first field, or a checksum that disagrees — the
      reading is disproved, nothing is bound, and nothing is repaired by search).
- [x] The temporal anchor is shown: *"temporals anchor to SendingTime(52) 08:12:31"*, or
      *"no SendingTime(52) — `~now` rows are left unjudged, not failed"* (S6). Use as reference.

### 5.3 Provenance that survives a save (S3, S4)

- [x] `ScenarioStep.origin: StepOrigin = LIVE | PASTED` on the interface, beside `stepId`:
      additive codec key, **written only when it is not the default** (invariant 5). An
      unrecognised value loads as PASTED — *less* trust, never more — and that is
      mutation-checked.
- [x] The chip always names the source; the `pasted` badge follows anything saved while a
      pasted reference was bound, and it renders on the step in the editor and on the diff
      header — because it is the explanation for why a `pasted`-repaired step's rows
      disagree with its own golden (S3). The golden is still re-pointed **only** for
      THIS_RUN (V4, unchanged).

### 5.4 Capture's second source (S9)

- [x] Capture review gains `source: live sessions | pasted wire`; pasted mode = one message
      per line → candidates with per-row direction toggle + session dropdown; sends
      parameterize, replies seed, exactly as live capture (reuse
      `ScenarioCapture.captureFrom`, which needs `Candidate` to carry its own `direction`,
      `messageType` and wire bytes — and `source: FixMessage?`, **null for a paste**).
- [x] Direction is read from the bytes where they decide it (`SenderCompID(49)` against the
      assigned session's CompIDs) and is **undetermined** where they do not; an *included*
      row with an undetermined direction **blocks Save**, naming the rows. A reply
      mis-marked as a Send is a step that asserts nothing (S9).
- [x] A line the reader refuses is not a candidate, and it is **reported** where the
      unreadable live messages already are (`Scan.unreadable`) — with its reason.

### 5.5 The fake venue sends the pipe again (S10)

- [x] Restore `58=filled|in full` in all three modes of `tools/fake-venue/fake_venue.py` —
      `088251c` dropped the pipe the README's own table of traps is built on, and every
      live run since has been against a venue with its headline trap disarmed.
- [x] The venue writes a **message log with the bytes in it** (SOH, both directions), so
      W2's *"paste a log fragment"* has a fragment to paste — and its `|`-rendered stdout
      is the ambiguous form S5 refuses, from the same message, live.

### 5.6 Tests

- [x] Paste with a pipe inside a value: the SOH form **reads** and keeps `58` whole; the
      `|` form is **refused**, and the sentence names the segment and the checksum (the
      historical bug case as the fixture — written with ``, never a literal SOH).
- [x] Pasted temporals anchor to 52, and render unjudged when 52 is absent; provenance
      survives a save/load round-trip; a pasted capture round-trips to a **runnable**
      scenario; swap-reference re-judges (golden green → this-run red without any edit).
- [x] `SemanticsContractTest` widened to the `ReferenceMessage` overload (S7), given the
      resolver a reconcile view actually has — **none**: pairing is identical to the
      `MessageView` overload over the same bytes, and an unreadable row is `unknown`,
      never `false`. Both mutation-checked. (Phase 0's review asked for this and named R2
      as the reason.)
- [x] Compose UI tests + screenshots to `composeApp/build/scenario-screenshots/` for
      everything the control surface cannot click: the swap menu, the paste sheet with each
      of its three verdicts, the armed slot binding from a grid click, and the provenance
      badge on a saved step.

**Phase 5 gate — met.** Full suite green (**1274 tests, 0 failures**); every touched file at or below
its lint baseline, and the new files (`WirePaste`, its tests, `ReferenceSlotTest`, `ScenarioOriginTest`,
`CaptureFromPasteTest`, `ReferenceSlotScreenshotTest`) carry none. W2 is proven **live end-to-end against
the fake venue**: a `NewOrderSingle` sent, the venue's hostile ExecutionReport logged **with the pipe
inside `58`**, its two log lines pasted through `POST /scenarios/capture-paste`, a two-step scenario saved
**both steps badged `pasted`** with `58=filled|in full` whole in the golden and in the row seeded from it,
replayed **green** against the live venue in `golden` mode, **failed** in `shape` mode (151 regressed, the
party entries swapped as `448`/`452` value mismatches, `58` missing), and the reconcile route opened the
diff on the failing step — no exceptions in the run log.

### Phase 5 outcome — what actually happened

**Three of the eleven decisions were live defects, and all three were reproduced on the old code first.**

**S1 was live on the one hand-binding path that already shipped.** `bindPickedReference` is reachable from
V6's no-reference prompt — *"Use the message selected in the grid"* — and that prompt is shown **precisely
when the step has never run and has no golden**. So an author binding a grid message to a never-run step
was told *"3 rows need attention"* (which in this surface has always meant *the venue did something new*)
under a red **FAILED** chip, about a step that cannot have failed and is being *authored*. Both sentences
false, and both the two a reader takes first. The word is now licensed by the slot, from one function —
`headlineAgainst` and `chipAgainst` — so the two can never disagree; red is reserved for the message the
step is actually about (`Provenance.chosenByTheAuthor` is the one predicate under all of it), and the right
column stops saying **RECEIVED** over a golden and a paste.

**S2 was live too, for PICKED.** V9 re-binds every open diff when a run lands, unconditionally — so a
reference the author bound **by hand** was silently replaced by the run's bytes: the thing they were
comparing against, gone, at the moment they were using it. A run now re-binds only the slots it owns
(THIS_RUN, and a GOLDEN that was standing in for a run that had not happened). `thisRunWire` is still
updated, so the swap menu offers the new bytes — the run offers, it does not impose.

**S10: the fake venue's headline trap had been disarmed for three phases.** `tools/fake-venue` exists to
send `58=filled|in full` — a pipe inside a value, the exact bug this codebase has shipped twice — and
`088251c` (the commit that added the three modes) had quietly rewritten it to `58=filled in full`, a space.
Every live run since had been against a venue that could not spring its own headline trap, and the README
had been describing a message nobody sent. **The fixture dodged the hard case, in the tool built to stop
that.** Restored, and the venue gained a message log with the SOH bytes in it — which is what W2 pastes,
and whose `|`-rendered stdout is the very form the reader refuses.

**The paste reader is the phase, and it never guesses.** `WirePaste` reads a paste to one of three verdicts
— READ (SOH, or `|` with the message's own `BodyLength(9)`/`CheckSum(10)` agreeing), UNVERIFIED (clean, but
no arithmetic to confirm the delimiter), REFUSED (a segment that is not `tag=value`, or arithmetic that
disagrees) — and it **never repairs**: the checksum says *how many* pipes were misread, never *which*, and
searching for the subset that balances is guessing with extra steps. *The engine reads the venue's bytes,
or it refuses to judge* — the model doc's own words for open question 5.

**And the tests caught things, twice over.** A log prefix and the BeginString live in the same segment
(`…08:12:31 OUT 8=FIX.4.4`), so the reader's first cut dropped `8=` with the prefix and the paste silently
downgraded from *verified* to *unverifiable* — the message begins where the first *field* begins, not at a
segment boundary. And the reader refused the tests' own **dishonest fixtures**: they carried `10=000`, and
a message whose arithmetic disagrees with its bytes is a message nobody sent, so they compute real checksums
now. The SOH also got eaten out of two fixtures and a handover paragraph as they were typed (trap 7); every
one is the `` escape now.

**S7 closed R2 at the seam.** `SemanticsContractTest` had never exercised the `ReferenceMessage` overload —
the one carrying the anchoring rule — and the example test that did handed the engine a `referenceResolver`
**the app can never supply**. Widened now, and given what a reconcile view actually has (no resolver, and
for a paste no moment): an unreadable row is `unknown`, never `false`. Deleting the anchor guard turns the
new property red.

**Deviations, and things deliberately left:**

- **The control surface grew a paste door** — `POST /scenarios/capture-paste` / `fixtool_capture_paste` —
  exactly as Phase 4 grew `POST /scenarios/reconcile`, and for the same reason: the paste box is click-only,
  so W2's gate could not be driven without a hand. It is the same reader and the same rules (a disproved
  line refused, an undirected message blocking the save), so a route it refuses is one the review refuses in
  the same words. The proposal puts the control surface out of scope; this is the third deliberate deviation
  from that (after Phase 2's `panel` retarget and Phase 4's reconcile route), and it made this gate provable.
- **Live pixels are still black.** `GET /screenshot` returned an all-black frame throughout — the display in
  this environment is asleep, as Phase 4 recorded — so the picture evidence is the Compose screenshot set
  (`diff_reference_menu`, `diff_paste_refused`, `diff_paste_unverified`, `diff_armed_slot`), driven by a real
  cursor. The live pass proved the route, the bytes, the badging, and the absence of exceptions.
- **S8's armed slot lives on the ViewModel**, which is the one load-bearing dependency on Phase 6: the diff
  moves into its own window, so the arming must survive a focus hop between windows. Written there now, so
  Phase 6 inherits it rather than re-deciding it mid-feature.
- `ScenarioService.load()` of a missing id still fires a user-facing error toast. Pre-existing; still a
  Phase 8 cleanup candidate.

---

## Phase 6 — The diff gets its own window (direction change, 2026-07-14)

**What changed and why.** Amit revised part of Decision 1 during review: the diff surface —
reconcile, authoring-against-golden, and the Phase 7 viewer — opens in a **dedicated,
task-scoped window**, IntelliJ-style, instead of a document tab. The rail and the
editor/capture tabs stay in the main window as built. The reasoning (proposal §1c): a diff
is consulted *against* context — in a tab it hides the very grid the failure lives in; in a
window the grid, rail, and detail panel stay visible beside it. This is not the workbench
window returning: that window was a persistent *place*; this is a disposable *tool*, and
the Phase-3 workspace (one draft per scenario, whatever views it) guarantees a diff window
never holds the only copy of unsaved state.

### Decisions taken before implementation — read these first

Eleven things the checklist under-specifies or gets wrong, settled here with the reasoning, so the next
reader does not re-derive them. The **direction is settled** (Amit decided it, proposal §1c) and these are
about executing it without shipping a defect — and three of them are stop-the-line: **F2** (two window states,
and merging them re-arms the trap the main window already learned), **F3** (`Window.getWindows().firstOrNull()`
is already the wrong window the moment a second one exists — so *every* screenshot goes nondeterministic, not
just the diff's), and **F4** (the scenario draft's lifecycle now spans two collections, and getting it wrong
drops an author's unsaved work out from under a live window).

**F1 — The phase is not "add a window." It is "move a document kind, and re-point every caller Phase 5 keyed
to it." That is the shape of the phase, and the window is the small part.**

`ScenarioDoc.Reconcile` is one document kind among `Editor`/`Capture`, held in `_openDocuments` and selected by
`activeDocumentId`. Phase 5 wired the entire reference slot to it: **twelve** ViewModel members take a
`ScenarioDoc.Reconcile` or reach into `_openDocuments` for one — `openReconcileDocument`, `bind`,
`bindPickedReference`, `bindPastedReference`, `referenceOptions`, `selectReference`, `expectStep`,
`applyExpectation`'s doc lookup, `rebindReconcileDocuments`, `saveScenario`'s rebase loop, `bindArmedReference`
(`_openDocuments.firstOrNull { it.id == armed } as? ScenarioDoc.Reconcile`), and the arming resolution itself.

> **The migration is the work.** A new `DiffWindowState` replaces `ScenarioDoc.Reconcile` as the thing all
> twelve hold, and they move **together** — because the slot (arm → click → bind → re-judge) and Save & re-run
> (rebind by `stepId`) are one mechanism, and half-moving it leaves the menu talking to a document that no
> longer exists. Do this first, with the diff still hosted where it is (a document tab), prove the slot and the
> S1/S2 tests still green against the new type, and only *then* stand the window up. The window is a host swap
> on a state holder that already left the composable in Phase 3.

**F2 — There are TWO window states, and 6.1 names both in one breath. Merging them re-arms the 8f93596 trap —
this time on the diff window.**

`8f93596` fixed the main window: a **bare** `WindowState` is a new object on every recomposition of the scope
that composes it, and Compose re-applies it to the live window — size snaps to `1920×1080`, position
re-cascades, the user's own resize is thrown away (main.kt:79-85 preserves the warning verbatim). The fix was
`rememberWindowState`.

So the diff window has two distinct states, and they live in two distinct places:

- **`DiffWindowState`** — the *content*: the `ReconcileSession`, the reference slot, `thisRunWire`, `focusTag`,
  `focusEpoch`. This is exactly what `ScenarioDoc.Reconcile` carries today, it is **ViewModel-owned** (a list on
  a `StateFlow`, as documents are), and F5 says why it may not retreat into the composable.
- **The Compose `WindowState`** — the *frame*: size and position. This is **composable-owned**, via
  `rememberWindowState` keyed by the window's id, inside each window's own composition. Put it on the
  ViewModel, or construct a bare `WindowState`, and it is re-created and re-applied on every recomposition of
  the application scope — which is the 8f93596 bug, now moving the diff window (or, worse, the main one, since
  they share that scope). The gate's regression check — *open/close/reopen a diff window, main window's size
  and position untouched* — is really the assertion that these two states were never conflated.

And a documented reversal: `viewModelRef` is a plain `var` today, made so by `271b34f` with the comment *"There
is no second window"* (main.kt:54-57). Phase 6 brings the second window back, so the diff windows compose at
**application scope** — siblings of the main `Window`, iterating `viewModel.openDiffWindows.collectAsState()`,
the shape the deleted `ScenarioWorkbenchWindow` had at `271b34f~1` — and `viewModelRef` returns to
`by remember { mutableStateOf(...) }` so that scope can react. Reverse the comment; do not delete it.

**F3 — `Window.getWindows().firstOrNull()` is already the wrong window the moment a second one exists — so the
screenshot selector is a *prerequisite*, and it protects the MAIN window's screenshot, not the diff's.**

`/screenshot` grabs `windowProvider()` = `{ java.awt.Window.getWindows().firstOrNull() }` (main.kt:120), and
the main window's own focus workaround grabs the same `firstOrNull()` (main.kt:94). `getWindows()` returns AWT's
windows in **no defined order** — today it is right only because there is one window. Stand up a diff window and
`/screenshot` may photograph the diff when asked for the main, or the reverse, from run to run; the focus
listener may attach to the wrong window. (It may already be fragile: a Compose `DropdownMenu`/tooltip can be a
heavyweight popup window, so `firstOrNull()` is only accidentally the main window even now — worth checking
during the phase.)

> So 6.3 is not "make the diff visible to the gate." It is *"make any screenshot deterministic again,"* and
> without it Phase 6 ships a regression in the main window's own automation. The fix: windows are addressable
> **by title** — the main window sets `title = "FixTool - FiX Message Viewer"` (main.kt:78) and each diff sets
> its subject title (6.1). `windowProvider` becomes `(selector) -> Window?` that filters `getWindows()` by a
> stable marker (title prefix, or a registered id), `/screenshot` grows `?window=main|diff[:subject]`, and the
> main window's focus workaround stops trusting `firstOrNull()` too.

**F4 — The scenario draft's lifecycle now spans two collections, and the checklist's close-semantics bullet
states only half of it. Both halves are drop-your-work hazards.**

Today `closeDocument` drops a scenario's draft when `remaining.none { it.scenarioId == scenarioId }` — counted
over `_openDocuments` only — and `requestCloseDocument`'s discard test counts `documentsOf(scenarioId)`, also
`_openDocuments` only. The diff was a document, so it was counted. As a window it is not, and both tests go
blind to it:

- **Closing the last *editor* tab while a diff window is still open would drop the draft** — out from under a
  live window that is still editing it. The window's next `onChange` writes into a workspace that no longer
  exists. `closeDocument`'s draft-drop test must count open diff windows as views.
- **Closing a diff window that is the only remaining view of a dirty scenario must confirm** (6.1 says this) —
  *and* must run the same draft-drop on discard. The window needs `requestCloseDocument`'s logic, spanning both
  collections.
- **The false positive:** closing a diff window while the editor tab is open must **not** confirm and must
  **not** drop the draft — nothing is discarded. Same predicate, other direction.

> One predicate — **"the open views of this scenario" = editor/capture documents *plus* diff windows** — feeds
> the draft-drop in `closeDocument`, the discard test in `requestCloseDocument`, and the window's own close. It
> is the Phase-3 workspace invariant (a draft nothing is looking at is unreachable) restated across two lists
> instead of one; write it once and call it from three places.

**F5 — A window is not disposed when you look away, so trap 5's pressure is gone — and a careless reader
concludes the session can move into the window's `remember`. It cannot, for three reasons, one of them new.**

V3 put the `ReconcileSession` in the document because *only the active document is composed* (trap 5): a glance
at the session grid disposed a `remember`ed session and the footer came back lying. A top-level window stays
composed regardless of where focus is, so that specific pressure disappears — which is exactly the reasoning
that would talk someone into a `remember { ReconcileSession(...) }` inside `DiffWindow`. Three reasons it stays
ViewModel-owned survive the window, and the third is created by the window:

1. **Save & re-run (V9)** rebases and re-binds *every* open diff's session from the **rail**, which is in the
   main window. A session the main window cannot reach is a Save & re-run that cannot refresh it.
2. **Cross-window arming (S8)** binds a reference from the main window's **grid** into the diff window's slot.
   S8 already put the armed-slot *flag* on the ViewModel for this; the slot it fills must be reachable from
   there too.
3. **The close/reopen lifecycle (new):** re-opening the same `(scenarioId, stepId)` must find the existing
   window's session — not build a fresh one over a draft three edits from disk. A composable-local session dies
   on close and cannot be rebased on reopen.

The genuinely-per-composition state stays in the composable, as M9 already established: the `FocusRequester`,
and the drag's ephemeral `DragState`. A window has exactly one composition, so those need no key.

**F6 — The deep-link now has to *raise* a window, which a tab never did — and `focusEpoch` already carries the
signal.**

A tab deep-link set `activeDocumentId` and it was on screen. A diff window that already exists may be **behind**
the main window that sent the author to it — *"the fix was on screen, underneath the window that sent you
there,"* which is the exact complaint `271b34f`'s `toFront()` pattern was written for. `ScenarioDoc.Reconcile`
already carries `focusEpoch`, bumped by every re-open (Phase 2/3); it moves to `DiffWindowState`, and a
`LaunchedEffect(focusEpoch)` inside the window content does what the workbench did: `state.isMinimized = false;
window.toFront(); window.requestFocus()`. `focusTag` (scroll-to-row) rides the same bump. Re-opening a subject
that has a window **focuses it**; it does not mint a second (6.1 — one window per subject, keyed on the id
`reconcileId(scenarioId, stepId)` already computes).

**F7 — `esc` moves off `App.kt` and into the window, and M7's stack keeps its meaning there.**

M7 made `esc` a stack in the diff: cancel a live drag, else dismiss a refusal, else fall through to `App.kt`,
which closed the focused *document* (App.kt:139-146). With the diff in its own window, the fall-through has
nowhere to go — there is no diff document to close — so the last rung becomes **close the window**, handled by
the window's own key path, and the main window's `esc` handler narrows to editor/capture documents only (it
must stop calling `requestCloseDocument` for a diff, which is no longer in `_openDocuments`). The contract —
*esc dismisses what is in flight, then closes the view* — is preserved; only the code that owns the last rung
moves, and a dirty last-view close still confirms (F4).

**F8 — Authoring's door already exists. 6.2 describes work that is largely done; the change is one glyph and one
call site.**

`ScenarioEditor.ExpectDetail` is *already* direction + timeout + bind predicate + `AssertionsDoor` — and
`AssertionsDoor` is already the "*Edit assertions →*" / reconcile door, hosting nothing itself. 6.2's *"shrinks
to bind-predicate + summary + Edit expectation ⧉"* is a description of the current file. The real changes:
`onOpenDiff` opens a **window** (`openDiffForStep` re-points to the window path), and the button's glyph becomes
the window glyph (**⧉**) so it reads as *opens elsewhere*, not *switches tab*. Recorded so the phase does not
re-shrink a pane that is already shrunk — and so the "two hosts" escape hatch 6.2 offers is seen for what it
is: there was never a second host to keep, `AssertionsDoor` is a door and not an editor.

**F9 — SPLIT loses one of its two reasons, and the document area's `when(doc)` must drop an arm that is now
unreachable — `rg` proves the diff document is dead.**

T4 built SPLIT so *"the document takes one split and the sessions keep the other,"* and the diff was the
document that most needed the centre width (proposal §1b). With the diff in a window, the width argument is
served by the window; SPLIT still earns its place (the **editor** beside a live session) but no longer for the
diff. So `ScenarioDocumentArea`/`ScenarioDocumentPane`'s `when(doc)` drops the `is ScenarioDoc.Reconcile` arm,
`documentTabsOf` stops minting a reconcile tab, and `ReconcileDocument` (the host composable) is deleted.
Dead-code gate: `rg ScenarioDoc.Reconcile` and `rg ReconcileDocument` find only the deletions, and no path mints
a reconcile *document*.

**F10 — What dies, and what has to exist before it does. Every test that pins the reconcile *document* migrates
to the window — it is the regression net, not scaffolding.**

The `ScenarioDoc.Reconcile` kind, the `ReconcileDocument` host, and the reconcile tab wiring are deleted — after
the window hosts every behaviour they carried (F1 slot, V9 rebind over windows, F4 lifecycle, F6 raise). Three
existing tests assert on the *document* and are the S1/S2/route regression nets; they **move** to assert on the
window, and are not dropped:

| test | today | after |
|---|---|---|
| `ScenarioWorkspaceTest` *"a new run re-binds the open diff"* (V9) | `activeDocument as ScenarioDoc.Reconcile` | the diff **window** for the subject re-binds |
| `ScenarioWorkspaceTest` *"a run does not take away the reference the author bound by hand"* (S2) | same cast | same, against the window's session |
| `ControlServerIntegrationTest` *"reconcile opens the diff on the failing step…"* | `activeDocument is ScenarioDoc.Reconcile` | a diff **window** opened for that `stepId`, its session bound to the failing bytes |
| all of `ReferenceSlotTest` (S1/S3/S4/S8/S11) | `activeDocument as ScenarioDoc.Reconcile` | the window's `DiffWindowState` |

New tests the phase owes (none of these have a predecessor): open/close/**reopen** focuses the existing window
rather than duplicating; the **main window's size and position are untouched** across a diff window's whole
lifecycle (F2's regression check); closing the last editor tab **keeps** the draft while a diff window is open,
and closing the last *view* (window included) drops it (F4); cross-window arming — arm in the window, click in
the main grid, the bind lands and the highlight is bidirectional (F5·2, and the one live thing Phase 5 could
only assert through the ViewModel).

**F11 — `fixtool_reconcile` still returns `open`; the automation can now *drive* the window but cannot *see* it
until F3 lands — and that ordering is the phase's own trap.**

`POST /scenarios/reconcile` opens the diff through the same `reconcileRoute` decider (Phase 4); after Phase 6 it
opens a **window**, and the return stays `{status: open, step, stepId}`. But the gate is *screenshots* (trap 2)
and the only hand is the control surface (trap 4) — so a diff window that F3 has not yet made addressable is a
window the machine opened and cannot photograph, which is the invisible-gate failure 6.3 names. Sequence F3
**before** the live gate, or the phase's own W1 screenshot proof (both windows on screen) cannot be taken —
the same lesson Phase 4 learned when live pixels came back black, one layer up.

### 6.1 The window host
- [x] `ui/diff/DiffWindow.kt`: application-scope `Window`s driven by
      `viewModel.openDiffWindows: SnapshotStateList<DiffWindowState>`; the per-diff state
      that today lives in `ScenarioDoc.Reconcile` (its `ReconcileSession`, reference slot,
      undo stack) moves into `DiffWindowState`. Trap 5 (only the active document is
      composed) stops applying to the diff — but keep the state in the window-state object
      anyway; a composable-local session is still wrong.
- [x] **The 8f93596 trap, by name:** each window's state is `rememberWindowState` keyed by
      the window's id. A bare `WindowState` constructed in application scope is re-created
      on any recomposition of that scope and re-applies size/position — this exact bug
      moved the *main* window last time. Regression check in the gate: open/close/reopen a
      diff window and assert the main window's position and size are untouched.
- [x] `FixToolWindowChrome` wraps the content (a second `Window` inherits no
      CompositionLocals — learned in redesign 2) and the window gets its own
      `NotificationPopupContainer`. The `toFront()`/`requestFocus()` deep-link pattern can
      be resurrected from `ScenarioWorkbenchWindow` in git history (pre-`271b34f`).
- [x] **One window per subject:** opening the same `(scenarioId, stepId)` focuses the
      existing window; different subjects may hold windows simultaneously (two diffs side
      by side is a real workflow). Title names the subject:
      `rfq flow v2 · Step 2 · Expect ExecutionReport(8) — FixTool`.
- [x] Close semantics: `esc` and the close button close the *view*. **A closing diff
      window never silently discards the workspace draft** — the draft is scenario-scoped
      and stays visible (dirty) in the rail and editor tab. The undo stack may die with
      the window; the draft may not. No dirty-confirm on window close *unless* the window
      holds the only open view of a dirty scenario — then the same confirm the editor tab
      uses.

### 6.2 Re-route the hosts
- [x] Every reconcile door — rail **Reconcile →**, run line, `MessageDetailPanel` deep
      link, `POST /scenarios/reconcile` / `fixtool_reconcile` — opens-or-focuses the
      window through the same `reconcileRoute` decider. The `ScenarioDoc.Reconcile`
      document kind and `ReconcileDocument` host are **deleted**; the tab strip no longer
      offers a diff document.
- [x] Authoring moves with it: the editor tab's `ExpectDetail` shrinks to bind-predicate
      editing + a read-only expectation summary + **"Edit expectation ⧉"**, which opens
      the diff window bound to the golden. One surface, one host, in both repair and
      authoring. (If review during the phase finds embedded authoring worth keeping, that
      is a recorded decision with the cost named: two hosts that must stay consistent
      forever.)
- [x] Save & re-run works from the window; the rail verdict and the window header update
      together. Pick-from-session arming (Phase 5) verified across the window boundary:
      arm in the diff window, click in the main window's grid, binding and bidirectional
      highlight work, armed state survives the focus hop.

### 6.3 The automation eye
- [x] `/screenshot` gains a window selector (`window=main|diff` or an index) and
      `fixtool_screenshot` exposes it (`McpTools`, `index.mjs`, `AUTOMATION.md`). Trap 2
      gates UI phases on screenshots and trap 4 says the control surface is the only
      hand — without this the diff window is invisible to every gate that follows.

### 6.4 Tests and gate
- [x] Tests: each door opens the window (route parity with the old document tests);
      same-subject focus instead of duplicate; close semantics (draft survives, dirty
      state still visible in main window); main-window state untouched across the
      lifecycle; cross-window pick-from-session; screenshots of the window via the new
      endpoint parameter.

**Phase 6 gate — met.** Full suite green (**1282 tests, 0 failures**); every touched file at or below its
lint baseline, and the new `DiffWindow.kt` carries none. **W1 driven live** against `tools/fake-venue`
through the multi-window build: a scenario captured and passing in `golden`, failed in `shape`, and
`POST /scenarios/reconcile` opened the diff **in its own top-level window** — `GET /screenshot?window=main`
returns the main window at 1728×1080 and `GET /screenshot?window=diff` returns a **distinct** window at
exactly the 1100×900 `DiffWindow` sets (proof it is a real second window, deterministically addressable, not
`firstOrNull()` luck). A second run with the diff window open exercised `rebindDiffWindows` and the window
stayed addressable; **no exceptions in the run log** across the whole loop.

### Phase 6 outcome — what actually happened

**F1 was the phase, exactly as the decision said.** The window was a day's work; re-pointing the **twelve**
callers Phase 5 had keyed to `ScenarioDoc.Reconcile` onto `DiffWindowState` — the slot, the rebind, the save
rebase, the arming resolution — was the rest, and they had to move as one, because the arm→click→bind→re-judge
loop and Save & re-run are a single mechanism. Done with the tests migrated in lockstep, so the S1/S2/route
nets stayed green against the new type.

**F2 held, and the two states stayed apart.** `DiffWindowState` (the session and slot) is ViewModel-owned;
each window's Compose `WindowState` (size/position) is its own `rememberWindowState`, keyed by id in main.kt's
`key(...)`. `viewModelRef` went back to Compose state, reversing `271b34f`'s *"there is no second window"*
comment — there is one again, and it is the diff. The live main window stayed 1728×1080 with a diff window open
and closed beside it: the bare-`WindowState` trap did not reappear.

**F3 was the right call to make first, and it was already latently wrong.** `windowProvider` was
`getWindows().firstOrNull()` — and the live run proved it: with two windows up, `?window=main` and
`?window=diff` return **different bitmaps of different sizes**, which `firstOrNull()` could not have
distinguished. The screenshot is deterministic again — for the *main* window too, which is the part the
checklist under-stated.

**F4 was a real correctness hazard, and both directions are pinned and mutation-checked.** The draft's views
are documents **plus** diff windows; closing the last editor tab keeps the draft while a window still views it,
and closing the last view (window included) drops it. Deleting either half of the `views = documents + windows`
predicate turns a test red.

**The three things the migration did NOT need, recorded so the next reader does not add them back:** a window
is *not* disposed on switch-away, so trap 5's pressure is gone — but the session stays in the ViewModel anyway
(F5: Save & re-run reaches it from the rail, arming binds into it from the grid, reopen must find it). And
authoring's door already existed (F8): the change was one glyph (**⧉**) and one call site, not a re-shrink of a
pane that Phase 3 already shrank.

**Deviations, and things deliberately left:**

- **The click-repair inside the diff window is the author's**, live — the control surface cannot click, so the
  live loop proved the *route* (window opens on the right step, bound to the failing bytes), the *rebind*, the
  *selector* and the *absence of exceptions*, not a gutter click. The repair mechanics are unit-tested
  (`DiffSurfaceTest`, `ReferenceSlotTest`) and the window's own tests assert the lifecycle.
- **Live pixels are black**, as every phase since 4 — the display in this environment is asleep, so the
  evidence is the *window bounds* (two distinct sizes) and the Compose screenshot set, not the pixels.
- **The UI-flow tests that clicked into the diff-as-a-tab moved to assert the ViewModel** — the diff is a
  top-level window now, not part of a `createComposeRule` scene, so *"the route reaches the diff"* is a VM
  assertion and *"the diff renders"* stays `DiffSurfaceTest`'s. Nothing was dropped; the seam moved.
- Several diff windows over different subjects centre-stack (`WindowPosition(Alignment.Center)`); harmless,
  and the alternative (cascade) risks the very shove the centred position was chosen to avoid.
- `ScenarioService.load()` of a missing id still fires a user-facing error toast. Pre-existing; still a Phase 8
  cleanup candidate.

---

## Phase 7 — The plain diff viewer

- [ ] `DiffSurface` left side generalizes to `Expectation | Message`; message-left mode
      renders read-only (no matcher chips, no gutter applies, no save), statuses
      reduce to same/value/only-A/only-B/moved, footer states it is read-only.
- [ ] Entry points — each opens a **diff window** (Phase 6 host): grid multi-select (2)
      → "Diff selected" (context/toolbar); `MessageDetailPanel` → "Diff against…";
      rail/toolbar → "Diff messages…" with two empty slots (pick/paste each). Subject
      key for focus-not-duplicate: the message pair.
- [ ] "Seed expectation from A/B ▾" seeds via `ExpectationSeeder` and flips the window
      into editor mode with the other side as reference; "add to scenario…" files it as
      an Expect step (scenario/step picker; new scenario allowed).
- [ ] Tests: viewer mode cannot mutate anything (architecture-level: no `EditOp` except
      `SwapReference`/`SetMode` accepted); two-message alignment on the fixture corpus;
      seed-then-edit produces a valid step that round-trips and runs.

**Phase 7 gate:** W3 verified live: two grid rows → diff window beside the grid;
UAT-style paste vs live message → diff → seed → step added to a scenario → run.

---

## Phase 8 — Cleanup, docs, final verification

- [ ] Demote `entryRegions`/`longestRepeat` to the documented fallback path (only
      caller: `GroupOverlay`); delete anything now uncalled (`bracketsFor` UI plumbing,
      old workbench-only helpers). `rg` for dead references to the deleted composables.
- [ ] `resources/help.html` §12 rewritten for rail/tabs/the diff window/paste/diff viewer;
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
| `service/compare/ComparisonSemantics.kt`, `GroupOverlay.kt`, `ReferenceMessage.kt` | new (Phase 0) |
| `service/compare/Verdict.kt` | new (Phase 1.1) — the counting and the sentences, lifted out of `ReconcileView` verbatim and shared by both surfaces until the old one dies |
| `ui/diff/ReconcileSession.kt`, `ui/diff/DiffSurface.kt` | new (Phase 1) |
| `ui/ReconcileView.kt` | **deleted (Phase 3.1)** — successors on `DiffSurface` first |
| `ui/ExpectationBuilder.kt` + `ExpectationDrafts` | **deleted (Phase 3.2)** — successors in `DiffAuthoringTest` first |
| `ui/ScenarioWorkbench.kt` window + `Mode` | **deleted (Phase 2.2)** — whole file; list content → `ui/ScenariosRail.kt` |
| `ui/diff/DiffHarness.kt` | **deleted (Phase 2.2)** with its host, as planned |
| `ui/ScenarioDocuments.kt`, `ui/ScenarioDocumentPane.kt`, `ui/ScenariosRail.kt` | new (Phase 2) |
| `ui/ScenarioEditor.kt`, `ui/ScenarioCaptureReview.kt` | kept, re-homed into tabs (Phase 2) and now **state-hoisted** (T2); `ExpectDetail` shrinks (3.2); capture gains paste source (5) |
| `ui/MatcherEditor.kt`, `ui/ScenarioUi.kt`, `ui/WindowChrome.kt` | kept |
| `main.kt` second-window block | **deleted (Phase 2.2)** |
| `ui/App.kt`, `ui/TabBar.kt`, `ui/Toolbar.kt` | modified (Phase 2): rail dock, document tabs, SPLIT document area |
| `ui/diff/DiffWindow.kt` | new (Phase 6) — the dedicated, task-scoped diff window host |
| `ScenarioDoc.Reconcile` + `ReconcileDocument` host | **deleted (Phase 6.2)** — per-diff state moves to `DiffWindowState` |
| `control/ControlServer.kt` `/screenshot` | modified (Phase 6.3): window selector, so the diff window stays visible to the gates |
| `control/ControlServer.kt` `panel("scenarios")` | retargeted to the rail (Phase 2), endpoint unchanged |
