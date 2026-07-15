# Pre-Release Findings — Repeatable Scenarios & Reconcile Viewer

**Review date:** 2026-07-15
**Scope:** everything shipped since `v1.7.0` under the "repeatable scenarios" and "reconcile / diff viewer" features (149 files, ~35k insertions).
**Method:** the comparison/reconcile engine was read end-to-end and exercised at runtime with an empirical probe across the full difference matrix (both STRICT and OPEN); every finding below was then **independently re-verified by an adversarial agent whose job was to refute it**. Only findings that survived refutation, with a concrete input→wrong-output trace, are listed. Two claims had their *trigger* corrected during verification (see [Corrected during verification](#corrected-during-verification)); no finding was fully refuted.

> The engine itself is sound. The pairing model makes false-greens structurally impossible and is exhaustively property-tested (`AlignmentPropertiesTest`, 100k+ pairs vs an independent oracle). All 8 matchers are wired end-to-end; the codec round-trip is lossless; the reorder engine cleanly separates shape from behaviour and refuses to manufacture a fix. The reconciler correctly handles **every** difference combination (parties/tags reordered, missing/extra tag, missing/extra group, missing count tag, nested groups, role swap) in both modes — see [Appendix A](#appendix-a--verified-difference-matrix). The findings below are defects *around* that core, not in it.

---

## Fix status (updated 2026-07-15)

| ID | Status | Notes |
|----|--------|-------|
| **F1** | ✅ Fixed + tested | `DATE_TYPES` narrowed to `{UTCDATEONLY, UTCDATE}`; LOCALMKTDATE/MONTHYEAR now seed `Exact`. Tests prove green-against-own-capture. |
| **F3** | ✅ Fixed + tested (**reclassified**) | Guard rejects only the **truly empty** (zero-step) scenario. A blanket "must have an Expect" was wrong — Send-only/Wait-only scenarios are a first-class, tested concept (load drivers, scope fixtures); it broke 5 existing tests and would break the load-testing use case. See [F3 detail](#f3--assertion-less-scenario-reports-pass--blocker-ci-integrity). |
| **F7** | ✅ Fixed + tested | Plain viewer now routes Esc/× through `requestCloseDiffViewer`, which shows the app's `DiscardConfirm` when the seeded editor `isDirty`. |
| **F2** | ✅ Fixed + tested | Verdict now `results.none { it.phase != "teardown" && !it.passed }` — teardown still runs and is reported, but does not decide pass/fail. |
| **F4** | ✅ Fixed + tested | Bind constraint now matches **any** occurrence of a repeated tag (`wire.none { tag && value }`), not just the first. |
| **F5** | ✅ Fixed + tested | `predicateFromJson` throws a by-name `IllegalArgumentException` (missing `tag`/`value`) instead of an NPE; empty-string value still loads. |
| **F6** | ✅ Fixed + tested | Empty `OneOf` reports `INVALID` with a reason; the shared INVALID prefix generalized from "invalid regex:" to "invalid:". |
| **F10a/b/c** | ✅ Tests added | Committed assertion tests for nested-group reorder, whole-group add/remove, and a missing count tag (behavior was already correct; now regression-protected). |
| **U1** | ✅ Fixed | Mode chip is now a filled, hover-highlighted, hand-cursor toggle (no misleading `▾`) with a `TooltipArea` explaining STRICT vs OPEN, consistent across both diff surfaces. |
| **U2** | ✅ Fixed | Meaningful informational text moved off `textDisabled` (≈3:1) to `textSecondary` (≈7.7:1) on both surfaces. |
| **U3** | ✅ Fixed | Viewer gutter markers now map to their side (+A = info/A-colour, +B = warning/B-colour); only-in-A no longer reads as an error-red value mismatch. |
| **U4** | ✅ Fixed | Plain viewer gained next/prev-diff navigation (buttons + `n`/`p`) mirroring the editor, over a `LazyColumn`. |
| **F9** | ✅ Fixed | `Scenario.version` now has a real job: `fromJson` refuses a from-the-future file (`version > CURRENT_SCENARIO_VERSION`) by name, and is the seam a future `migrate()` hooks into. |
| **F8** | 🗂️ Deferred → [issue #39](https://github.com/amitgupta1202/FixTool/issues/39) | No headless exit-code entrypoint. No current CI-from-CLI requirement; filed for the future. |

**All review findings are now resolved or deferred.** Full `jvmTest` suite passes (BUILD SUCCESSFUL, no failures) after every pass; the UX changes were also visually confirmed against the rendered screenshot output. The only open item is **F8**, deferred to issue #39 by decision (no current need).

---

## Release recommendation

**~~Do not release without F1, F7, and F3~~ — now fixed & verified.** They each produced a *wrong* pass/fail on correct input, which is why they were blockers:

- **F1** — the assertion engine manufactures **false failures** on correct venue output, on the **default** dictionary, on everyday fields (SettlDate/TradeDate), and the only sanctioned repair silently deletes coverage.
- **F7** — **silent, unrecoverable loss** of authoring work on a single accidental `Esc`.
- **F3** — a scenario that asserts nothing reports **PASS**, i.e. a green CI gate that checks nothing.

**F8 is a conditional blocker:** if "run scenarios in CI" is part of this release's promise (it is a Phase-2 exit criterion in `repeatable-scenarios-impl-plan.md`), then the absence of any headless / exit-code entrypoint blocks that promise. If the release only claims GUI + control-port operation, F8 is a documented limitation.

Everything else is **should-fix** (correctness with low reachability, or regression-net gaps) or **polish**, and need not block the release.

---

## Blocker summary

| ID | Finding | Blocker |
|----|---------|---------|
| **F1** | Date fields seeded `Temporal(TODAY)` → wrong-red on correct dates; repair erodes coverage | **Yes** |
| **F7** | Seeded-expectation editor loses all staged edits on close (no dirty guard) | **Yes** |
| **F3** | Assertion-less / empty scenario reports PASS | **Yes** (CI integrity) |
| **F8** | No headless run / process exit code; JUnit XML only as an HTTP string | **Conditional** (blocks CI-from-CLI) |

---

## All findings

| ID | Finding | Area | Verdict | Severity | Blocker | Fix location |
|----|---------|------|---------|----------|---------|--------------|
| F1 | `LOCALMKTDATE`/`MONTHYEAR` seeded `Temporal(TODAY)`; wrong-red on non-today dates | Engine (seeder) | CONFIRMED (A live, B latent) | High | **Yes** | `ExpectationSeeder.kt:90,107` |
| F7 | Plain-viewer seeded editor: staged edits discarded silently on Esc/close | UX / data-loss | CONFIRMED | High | **Yes** | `DiffViewerWindow.kt`, `FixMessageViewModel.kt:1031` |
| F3 | Empty/assertion-less scenario → `passed=true` | Runner | CONFIRMED | Medium | **Yes** | `ScenarioRunner.kt:99` / preflight |
| F8 | No headless entrypoint / exit code | Completeness | CONFIRMED | Moderate (feature-gap) | **Conditional** | `main.kt`, new CLI path |
| F2 | Teardown step failure flips a green run to FAILED | Runner | CONFIRMED (trigger corrected) | Medium | No | `ScenarioRunner.kt:99` |
| F10a | Nested-group reorder has no assertion test | Test coverage | CONFIRMED | Regression-risk | No | new test |
| F10b | Whole-group add/remove has no assertion test | Test coverage | CONFIRMED | Regression-risk | No | new test |
| F10c | Missing group count-tag (453) has no assertion test | Test coverage | CONFIRMED | Regression-risk | No | new test |
| U1 | STRICT/OPEN chip illegible as a control; viewer `▾` implies a menu that doesn't exist; mode never explained | UX | CONFIRMED | Defect | No | `DiffSurface.kt:192`, `DiffViewerSurface.kt:141` |
| F5 | Malformed `match` predicate → NPE → uninformative toast (violates codec's "say why by name") | Codec | PARTIAL (fails safe) | Low | No | `ScenarioCodec.kt:170-172` |
| F6 | Empty `OneOf([])` → permanently-red row, blank expected, no warning | Matcher | CONFIRMED (fails safe) | Low | No | `Matcher.kt:68`, `ExpectationEvaluator.kt:323` |
| F4 | Bind/match constraint checks only occurrence #1 of a repeated tag | Runner | CONFIRMED (latent) | Low | No | `ScenarioRunner.kt:290` |
| U2 | `textDisabled` #6A6A6A ≈3.08:1 on meaningful 8–11sp text (below AA) | UX / a11y | CONFIRMED | Defect | No | `Theme.kt:74`, usages |
| U3 | Viewer gutter paints only-in-A red like a value mismatch; marker colours don't map to their side | UX | CONFIRMED | Defect | No | `DiffViewerSurface.kt:96-98,277-278` |
| U4 | Read-only viewer lacks the editor's next-diff navigation | UX | CONFIRMED | Nice-to-have | No | `DiffViewerSurface.kt` |
| F9 | `Scenario.version` written but read by nothing; no scenario `migrate()` | Completeness | CONFIRMED | Inert | No | — |

---

## Blocker detail

### F1 — Date fields seeded `Temporal(TODAY)` produce false failures  ·  **Blocker**

**What happens.** `ExpectationSeeder` maps every FIX date type (`UTCDATEONLY, UTCDATE, LOCALMKTDATE, MONTHYEAR`, `ExpectationSeeder.kt:107`) to `Matcher.Temporal(TemporalKind.TODAY)` (`:90`). `TODAY` passes only if the value equals the current UTC date (`ExpectationEvaluator.kt:352`). So any date field whose value is **not** literally today fails.

- **Sub-claim A — LIVE on the default dictionary.** The bundled `FIX44.xml` types `SettlDate(64)` and `TradeDate(75)` as `LOCALMKTDATE`. A `SettlDate` of `20260717` (T+2) seeded and evaluated with `now = 2026-07-15` → `passed=false, status=VALUE`. These fields appear on ordinary order/exec messages, so this fires with **zero custom config**. The live runner (`ScenarioRunner.kt:242`) evaluates with wall-clock `now`, so it reds on every run.
- **Sub-claim B — LATENT.** `MONTHYEAR` wire values are 6 chars (`202612`); `parseFixDate` only parses `value.take(8)` with `BASIC_ISO_DATE` (8-digit `yyyyMMdd`, `ExpectationEvaluator.kt:361-363`), so a `MONTHYEAR` can never parse. But the bundled dictionary is a trimmed 93-field file with **no** MONTHYEAR-typed field (tags 200/432/442/541 absent), so B only bites **custom venue dictionaries** that type a field MONTHYEAR.

**Why it's a blocker, not a nuisance.** The only reconcile repairs offered on a temporal row are **Loosen** and **Drop** (`ScenarioReconcile.canAcceptActual` returns false for `Temporal`, `:188-189`) — neither preserves the date-value check. So a wrong-red funnels straight to **deleted coverage** of a business-critical field: the exact "false green by a longer route" the codebase treats as serious elsewhere (`ExpectationEvaluator.kt:368-382`).

**Partial mitigation that does *not* save it.** Reconciling against a golden judges at the reference's own `SendingTime` (`ScenarioReconcile.kt:125-137`), which rescues **same-day** dates (TradeDate on capture day) — but not T+n dates (SettlDate/Maturity/Expire) and not the live runner.

**Fix (seeder mapping, not the parser).** Drop `LOCALMKTDATE` and `MONTHYEAR` from the `TODAY` bucket at `ExpectationSeeder.kt:90/107` and let them fall to `else → Exact(value)`, so a golden evaluates green against itself. `UTCDATEONLY`/`UTCDATE`, whose semantics genuinely are "current UTC date" (e.g. MDEntryDate on a live snapshot), may keep `TODAY`. Fixing `parseFixDate` alone would **not** help — a maturity month still is not "today".

**Tests to add.** `Temporal(TODAY)` against a real date field (currently *no* test does this — every temporal test uses `NOW_WITHIN_TOLERANCE`); a seeder round-trip proving a captured `SettlDate` evaluates green against its own capture; a `MONTHYEAR` 6-char parse case.

---

### F7 — Seeded-expectation editor loses staged edits on close  ·  **Blocker**

**What happens.** In the scenario-less plain viewer, "Seed expectation from A/B" floats an **editable** `ReconcileSession` (`DiffViewerWindow.kt:285-297`). Its `onChange` is a deliberate no-op (`FixMessageViewModel.kt:1062-1069`), so every staged edit (loosen, accept-actual, move row) lives **only** in the in-memory session. Pressing `Esc` (`DiffViewerWindow.kt:81-83`) or the OS close button (`:65`) calls `closeDiffViewer` (`FixMessageViewModel.kt:1031-1035`), which drops the state with **no dirty check and no confirm dialog**.

**Why the existing guard misses it.** The scenario reconcile window (`DiffWindow`) has a full `DiscardConfirm` (`DiffWindow.kt:98-105,230-251`), but its guard `isLastDirtyView` keys off `_openScenarios[scenarioId].dirty` (`FixMessageViewModel.kt:405-409`) — and a scenario-less seeded editor has **no `scenarioId`**, so it is never covered.

**Recoverability.** Re-seeding reproduces the *default* seed, not the user's loosened matchers / accepted rows / moves. The staged editing work — the entire point of the editor — is **unrecoverable**.

**Fix.** Mirror the sibling window, keyed off the session's own already-public `isDirty` (`ReconcileSession.kt:394`): add `requestCloseDiffViewer(id)` (confirm when `editing?.isDirty`), render a `DiscardConfirm` in `DiffViewerWindow`, and rewire `main.kt:165` `onClose` to it.

---

### F3 — Assertion-less scenario reports PASS  ·  **Blocker (CI integrity)**

**What happens.** An empty scenario (or one whose steps are all `Send`/`ClearMessages` and no `Expect`) passes preflight (`ScenarioRunner.kt:104-124` loops over an empty session set) and reaches `ScenarioResult(name, results.all { it.passed }, results)` — and `emptyList().all { }` is **vacuously true** (`:99`). CI sees `tests=0, failures=0, passed=true` (`ScenarioReport.kt`, `ControlServer.kt:1058`). The reconcile *view* has an `assertsNothing` guard (`Verdict.kt:71`); the **runner does not**.

**Reachability.** Shipped: `/scenarios/run` accepts an inline scenario, and capturing a selection with no incoming rows produces zero `Expect` steps (`ScenarioCapture.kt:203-215`).

**Fix applied — and reclassified.** The first instinct ("reject any scenario with no `Expect`") is **wrong**: the runner's existing, tested contract treats Send-only / Wait-only scenarios as first-class — e.g. `persistent scope carries a variable across separate sends` (`ScenarioRunnerTest.kt:82`) runs a two-`Send` scenario and asserts on `host.sent`, and the load-testing feature drives sends without asserting. A blanket guard broke 5 existing tests and would break that use case. The shipped fix therefore rejects **only the truly empty (zero-step) scenario** at preflight (`ScenarioRunner.kt`, `if (all.isEmpty()) return preflightFailure("This scenario has no steps …")`), which is the only indefensible vacuous green. The narrower "captured-but-no-Expect" concern (a capture that produced sends but no assertions) is better handled at capture/authoring time with a warning, not a runner hard-fail.

---

### F8 — No headless run / process exit code  ·  **Conditional blocker**

**What happens.** The only entrypoint is `main.kt:33 fun main()` (no args), which unconditionally launches the Compose GUI; there is no `exitProcess`/`System.exit`/arg handling anywhere in `jvmMain`. `ScenarioReport.toJUnitXml` is emitted **only** as a string inside an HTTP/MCP JSON body (`ControlServer.kt:1059`) — never written to a file, never mapped to an exit code. So a CI job must boot the GUI, enable the control port, POST `fixtool_run_scenario`, and derive pass/fail from JSON itself.

**Disposition.** Correct as far as it goes; this is a **missing capability**, not a defect. It becomes a **blocker** only if headless CI execution is in scope for this release — which the impl plan lists as a Phase-2 exit criterion ("CI-consumable report with a meaningful exit code"). Decide explicitly.

---

## Should-fix (not blocking)

### F2 — Teardown failure flips a green run to FAILED
Teardown runs unconditionally (`ScenarioRunner.kt:96-98`) and folds into the verdict (`:99`). A run where every setup+step passes but a teardown `Expect`/`Wait`/`Send` fails (or a `ClearMessages` whose tab was *closed* mid-run) reports **FAILED**. **Correction from verification:** a session merely *disconnecting* does **not** trigger this — the session stays resolvable, so `ClearMessages` still passes; the tab must be removed, or the teardown step must itself fail. False-**red**, hand-authored teardowns only. **Fix:** score only non-teardown phases at `:99`.

### F10a/b/c — Reconciler regression-net holes
Runtime behaviour is correct (proven by the probe in [Appendix A](#appendix-a--verified-difference-matrix)), but there is **no committed assertion test** for: (a) nested-group entry **reorder** through the reorder engine (`placeByOccurrence`/`placeByMovedEntry`/`verbatimWindow`, which take no overlay); (b) missing/extra **whole** group as one shape; (c) a missing group **count tag (453)**. (a) is the highest-value gap — FixTool runs against 10+ venue dictionaries that nest groups, yet the nested reorder path is unasserted. Single-level party reorder *is* heavily tested.

### U1 — STRICT/OPEN control illegible and inconsistent
The reconcile mode toggle is drawn by the same `Chip` composable as static read-only badges with no affordance marking it clickable (`DiffSurface.kt:192-198,302-313`); the viewer chip shows `"$label ▾"` implying a dropdown that does not exist — it just toggles (`DiffViewerSurface.kt:141,103-108`); and STRICT vs OPEN is never explained in-app though both neighbouring controls carry footnotes. Toggling mode is a **saved** edit that materially strengthens/weakens the assertion, so this borders on correctness-adjacent. **Highest-value polish fix.**

### F5 — Malformed `match` predicate → NPE
`ScenarioCodec.kt:170-172` uses `!!`/`.int` on hand-editable fields; a missing `tag`/`value` throws `NullPointerException` → generic `"Cannot load scenario 'x.json': null"` toast and the scenario vanishes from the rail, violating the codec's own "missing ≠ corrupt, say why by name" contract (`:29-30`). **Corrections from verification:** an *empty-string* `value` loads fine (not a trigger), and it is not fully silent (there is an error toast). **Fails safe** — a malformed file never runs. **Fix:** mirror `MatcherCodec`'s by-name `IllegalArgumentException` pattern.

### F6 — Empty `OneOf([])` is a silent foot-gun
Evaluates to always-false with a blank expected column and no warning (`ExpectationEvaluator.kt:323`), and the UI can produce it by clearing the "any of" field (`MatcherEditor.kt:157-158`) or switching a blank row to oneOf (`:71`). **Fails safe.** **Fix:** flag it via `Matcher.validationError()` (`Matcher.kt:68`) and route through the `INVALID`/reason path like a bad regex.

### F4 — Bind constraint checks only occurrence #1
`ScenarioRunner.kt:290` uses `wire.firstOrNull { it.first == tv.tag }`, so a bind/match constraint on a repeated/grouped tag ignores later occurrences. **Latent** — capture only emits top-level ID-tag constraints (`ScenarioCapture.kt:295-298`), so this is reachable only via hand/agent-authored scenarios. **Fix:** `if (wire.none { it.first == tv.tag && it.second == tv.value }) return false`.

---

## Polish (not blocking)

- **U2 — contrast.** `textDisabled` #6A6A6A on #1E1E1E ≈ **3.08:1** (below WCAG AA 4.5:1) is used for *meaningful* 8–11sp text: the difference breakdown (`DiffViewerSurface.kt:181`), gutter "only in A/B" (`:266`), 8sp all-caps footnotes (`DiffSurface.kt:838`). `textSecondary` #B0B0B0 (≈7.7:1) already exists — reassign.
- **U3 — colour semantics.** The viewer claims to diagnose nothing yet paints only-in-A in error red like a value mismatch (`DiffViewerSurface.kt:277`), and side chips (A=blue, B=amber, `:96-98`) don't match their gutter markers (+A=red, +B=blue, `:277-278`).
- **U4 — viewer navigation.** The read-only viewer is a plain scrolling `Column` with no next-diff jump, though it builds on the same `DiffModel.diffChunks` the editor navigates with `n`/`p` + buttons.
- **F9 — inert versioning.** `Scenario.version` is written and round-tripped but read by nothing, and there is no scenario `migrate()` hook. Harmless, but it's scaffolding that implies a capability that isn't there.

---

## Corrected during verification

Adversarial verification refuted two *trigger* details in the original review (the underlying findings stand):

1. **F2** — a session *disconnecting* does not flip the verdict (the session stays resolvable); the real trigger is a failing teardown `Expect`/`Wait`/`Send`, or a tab *closed* mid-run.
2. **F5** — an *empty-string* `value` does not throw (only a missing key or non-integer `tag` does), and the failure is not fully silent (a generic error toast fires).

---

## Verified good (no action)

- Pairing never consults whether a matcher would pass — the anti-false-green invariant — exhaustively property-tested over 100k+ pairs against an independent oracle (`AlignmentPropertiesTest`).
- One alignment feeds both runner and view, so they cannot disagree about what failed.
- `GroupOverlay` is display-only and architecturally forbidden from influencing a verdict (`GroupOverlayArchitectureTest`).
- All 8 matchers wired model→codec→evaluator→UI; no dead matchers; lossless codec round-trip.
- The reorder engine verifies every fix it offers against the engine itself and correctly **refuses** role-swaps and prepended-entry cases rather than manufacture a false green.

---

## Appendix A — verified difference matrix

Driven against the real engine (empirical probe, both modes). Every combination behaves correctly; STRICT and OPEN diverge only on the unmentioned extra tag, by design.

| Difference | STRICT | OPEN | Reorder engine | One-click fix |
|---|---|---|---|---|
| Party entries reordered | FAIL (value) | FAIL (value) | POSSIBLE | Accept-new-order → PASS |
| Scalar tags reordered | FAIL (MOVED) | FAIL (MOVED) | POSSIBLE | Accept-new-order → PASS |
| Intra-entry field reorder | FAIL | FAIL | POSSIBLE | Accept-new-order → PASS |
| Nested sub-entries swapped | FAIL | FAIL | POSSIBLE | Accept-new-order → PASS |
| Missing tag | FAIL (MISSING) | FAIL (MISSING) | None | assert-absent / drop |
| Missing whole group | FAIL | FAIL | None | — |
| Missing count tag (453) | FAIL (MISSING) | FAIL | None | — |
| Extra tag | **FAIL** (unexpected) | **PASS** (ignored) | None | assert (strict) |
| Extra group (appended) | FAIL (count + extras) | FAIL (count) | None | — |
| Extra group (prepended) | FAIL (all shift) | FAIL (all shift) | **REFUSED** ✓ | — (not a reorder) |
| Value mismatch | FAIL | FAIL | None | accept-actual |
| Role swap (behaviour) | FAIL | FAIL | **REFUSED** ✓ | accept-actual per row |

All 8 matchers verified: exact, presence, absent, regex (full-match anchored), oneOf, numeric (tolerance + format-robust), temporal (today / now±N incl. time-only), reference (resolver-bound).
