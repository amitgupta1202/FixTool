# Repeatable Scenarios — Testing Evidence

Consolidated test evidence for the whole feature: the assertion engine, the deterministic runner +
store, capture-driven authoring, and the in-app UI (results in the session window + builders).

## Result: 60 tests, 0 failures

Reproduce (units/UI are deterministic; the heavy QuickFIX integration tests are run isolated because
they contend on QuickFIX/J's per-JVM session registry when run together — CI retries them):

```
# units + UI (one run)
./gradlew jvmTest \
  --tests "com.knapsack.fixtool.service.ExpectationEvaluatorTest" \
  --tests "com.knapsack.fixtool.service.ExpectationSeederTest" \
  --tests "com.knapsack.fixtool.service.ScenarioRunnerTest" \
  --tests "com.knapsack.fixtool.service.ScenarioCodecTest" \
  --tests "com.knapsack.fixtool.service.ScenarioCaptureTest" \
  --tests "com.knapsack.fixtool.ui.MatcherEditorTest" \
  --tests "com.knapsack.fixtool.ui.ExpectationBuilderTest" \
  --tests "com.knapsack.fixtool.ui.ScenarioBuilderTest" \
  --tests "com.knapsack.fixtool.ui.ScenarioEditTest"

# integration (each isolated)
./gradlew jvmTest --tests "com.knapsack.fixtool.integration.AssertIntegrationTest"
./gradlew jvmTest --tests "com.knapsack.fixtool.integration.ScenarioIntegrationTest"
./gradlew jvmTest --tests "com.knapsack.fixtool.integration.ControlServerIntegrationTest"
```

## Unit — assertion engine & model (17)

**`ExpectationEvaluatorTest` (9)** — every matcher + rule on an in-memory message:
exact match/mismatch · presence/absent · oneOf/regex · numeric (format-robust + tolerance edges +
non-numeric) · temporal now±N · temporal today · reference resolves against scope · STRICT flags
extras but ignores header/trailer volatiles · group entry located by identity (not position).

**`ExpectationSeederTest` (1)** — dictionary field types → matchers (timestamp→temporal,
price/qty→numeric, OrderID→presence, MsgType→exact) and header volatiles dropped.

**`ScenarioCodecTest` (2)** — a full scenario (every step type, every matcher, group path, STRICT)
round-trips through JSON unchanged · `ScenarioResult` → JUnit XML reports a failing step.

**`ScenarioCaptureTest` (1)** — a **2-session RFQ** (quote + trade) is captured with chronological
multi-session ordering, volatile parameterization (TransactTime + ids), and **cross-session id
correlation** via scenario variables (the order re-uses the quote's `${id0}`).

## Unit — deterministic runner (5)

**`ScenarioRunnerTest`** — send→expect happy path (records the sent message) · **persistent scope**
carries a variable across separate sends · **consumed cursor** walks successive same-type messages
(partial fills) · expect times out cleanly · **failure aborts remaining steps but teardown still
runs**.

## UI — Compose component tests (5, capture PNGs under `build/scenario-screenshots/`)

**`MatcherEditorTest` (1)** — switching matcher type emits a matcher of the chosen type.
**`ExpectationBuilderTest` (2)** — seeded chips + live green/red preview render and save the
expectation · **Verify generalizes** flags the over-specified field vs a 2nd instance.
**`ScenarioBuilderTest` (1)** — builds a scenario from clicks with a **different session per step**
(multi-session).
**`ScenarioEditTest` (1)** — load-for-edit preserves the user's relaxed matchers and restores each
tag's captured value from the stored golden.

## Integration — live FIX sessions over the real control surface (37)

**`AssertIntegrationTest` (3)** — a FixTool acceptor auto-responds to an order; `/assert`
machine-checks the ExecutionReport tag-by-tag (incl. a `reference` matcher resolving `${out.D.11}`);
capture auto-seeds; reachable over MCP.

**`ScenarioIntegrationTest` (5)** — over a live acceptor↔initiator: run an inline scenario
end-to-end (reference correlation) · a wrong expectation fails · save → list → run-by-id → delete ·
JUnit output for CI · runnable over MCP.

**`ControlServerIntegrationTest` (29)** — the full control surface, incl. the MCP tool count (now
35) and that every scenario/assert tool dispatches.

## Live, in-app evidence (the redesign)

Captured from the real running app driven over its control surface — see committed PNGs:
- `docs/images/scenario_results_fail.png`, `docs/images/scenario_results_pass.png` — per-tag
  red/green rendering.
- `docs/images/expectation_builder_verify.png` — matcher chips + live preview + ⚠ over-specified.
- `docs/images/scenario_builder.png` — visual builder, per-step session.
- The session-window overlay (capture → run → inspect): the replayed NewOrderSingle + ExecutionReport
  in the grid, the ExecutionReport asserted tag-by-tag in the detail panel — `ClOrdID ✓ reference
  ${id0}`, `LastPx ✗ exp … · act …` (a genuine volatile-needs-relax break).

## Quality gates
- `./gradlew compileTestKotlinJvm` — clean.
- `./gradlew detekt` — **no findings in any new scenario-feature file** (matcher model, evaluator,
  seeder, codecs, capture, runner, store, view adapters, atomic-write util, the MCP handlers, and the
  UI components). Remaining detekt output is limited to large pre-existing files (`App.kt`,
  `Toolbar.kt`, `MessageDetailPanel.kt`, `FixMessageViewModel.kt`) that were already over the
  size/param thresholds before this work.
