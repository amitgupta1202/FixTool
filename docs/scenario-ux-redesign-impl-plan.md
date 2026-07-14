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
| 1 | The diff surface, standalone | 1.1–1.2 done · 1.3 (the composable) pending |
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

### 1.3 The `DiffSurface` composable
- [ ] `ui/diff/DiffSurface.kt` renders a `ReconcileSession` per the mockups: header (crumb,
      semantics chip, reference chip, the `Verdict` from 1.1), two aligned columns, centre
      gutter, footer (staged count, the *"nothing is written to the scenario until you save"*
      sentence verbatim, undo/redo/cancel/save). Left rows: tag (with `#2` occurrence
      suffix) · name · `MatcherEditor` (P9). Right rows: tag · name · value · enum
      description as a **separate dim span**, per the mockup — not folded into the value
      string. Gaps get the hatched treatment; `unjudged` rows the amber `◌` third state.
      Built from `SlimComponents`, never Material3 defaults.
- [ ] Gutter per the proposal's table, but offered by `offersFor` (P6): `«` accept-actual,
      `«` assert-it, `×` drop (whole-tag rule in its tooltip), `∅` assert-absent,
      `⇄ Accept new order` on engine-proven moves. Bulk: Accept all shape changes, Re-seed.
- [ ] Group bands from `GroupOverlay`: entry headers with the overlay's own labels, per-entry
      hue, nested indent; hover highlights the aligned counterpart; `HEURISTIC` entries
      badged as the guess they are. Moved entries: violet band + crossing connector.
      Entry `↑`/`↓` ship now (they exist today; the surface must not regress against the view
      it replaces) — drag is Phase 4.
- [ ] The withheld-move reason renders **on the group it is about** (the mockup moves it off
      the detached note it is today), verbatim from `Reorder.Refused.why`.
- [ ] Compose tests + screenshots: value mismatch / added / missing / moved / unjudged; every
      gutter apply mutating the draft through the session; an edit flipping a row green live;
      accept-all-shape never touching a value mismatch; the `judged == 0` headline; hover
      pairing. **The test harness must feed `onChange` back**, as `ScenarioEditor` does — a
      harness that merely records it let a completely dead staging mechanism survive seven
      passing tests (`ReconcileViewTest.kt:63-87`, and its docstring says so).

**Phase 1 gate:** full suite green; `DiffSurface` driven interactively as a temporary
`Mode.Diff` inside the **existing** `ScenarioWorkbenchWindow` — no new window, and the host is
already condemned in 2.2, so nothing is written to be deleted — against fake-venue `shape` (the
entries swap places; a re-order **must** be offered) and `swap` (the firms swap roles; a
re-order must **never** be offered, and the refusal must render on the group). Screenshot set
committed from the Compose tests, not from the harness.

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
| `service/compare/ComparisonSemantics.kt`, `GroupOverlay.kt`, `ReferenceMessage.kt` | new (Phase 0) |
| `service/compare/Verdict.kt` | new (Phase 1.1) — the counting and the sentences, lifted out of `ReconcileView` verbatim and shared by both surfaces until the old one dies |
| `ui/diff/ReconcileSession.kt`, `ui/diff/DiffSurface.kt` | new (Phase 1) |
| `ui/ReconcileView.kt` | deleted (Phase 3.1) after test porting |
| `ui/ExpectationBuilder.kt` | deleted (Phase 3.2) after test porting |
| `ui/ScenarioWorkbench.kt` window + `Mode` | deleted (Phase 2.2); list content → rail |
| `ui/ScenarioEditor.kt`, `ui/ScenarioCaptureReview.kt` | kept, re-homed into tabs; `ExpectDetail` shrinks (3.2); capture gains paste source (5) |
| `ui/MatcherEditor.kt`, `ui/ScenarioUi.kt`, `ui/WindowChrome.kt` | kept |
| `main.kt` second-window block | deleted (Phase 2.2) |
| `ui/App.kt`, `ui/TabBar.kt`, `ui/Toolbar.kt` | modified: rail pane, document tabs, toolbar wording |
| `control/ControlServer.kt` `panel("scenarios")` | retargeted to the rail, endpoint unchanged |
