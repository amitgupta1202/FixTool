# Repeatable Scenarios — Phase 3 (Run) delivery evidence

Phase 3 brings the feature into the app: a **Scenarios panel** to run/manage scenarios, and the
**per-tag red/green run-results rendering** — the in-app answer to "where does a failed assertion
show up".

## What shipped

| Plan item | Status | Where |
|-----------|--------|-------|
| 3.5 Run-results red/green overlay | ✅ | `ui/ScenarioResultsView.kt` (per-step + per-tag rows, expected vs actual) |
| Scenarios panel (run / list / delete) | ✅ | `ui/ScenariosDialog.kt`, toolbar ▶ button (`ui/Toolbar.kt`), `ui/App.kt` wiring |
| Author by pasting JSON | ✅ | `ScenariosDialog` → `FixMessageViewModel.saveScenarioJson` |
| Run off the UI thread → result state | ✅ | `FixMessageViewModel.runScenario` (`scenarioResult` / `scenarioRunning` flows) |
| Shared runner host (UI + control) | ✅ | `viewmodel/ViewModelScenarioHost.kt` (also now backs `fixtool_run_scenario`) |
| 3.2 Capture-to-expectation | ✅ (tool) | `fixtool_capture_expectation` (Phase 1) |

**Deferred (future convenience UX):** in-*detail-panel* matcher chips with live preview (3.1), the
two-instance "verify generalizes" check (3.3), a visual step-by-step builder (3.4), and a dedicated
multi-session authoring UI (3.6). Scenarios are fully authorable today via JSON / the
`fixtool_save_scenario` tool / an agent.

## Rendered output (real Compose UI, captured in a test harness)

A **failed** run — the `expect` step is red, and each asserted tag shows expected vs actual
(`oneOf` and `absent` failed):

![failed scenario run](./images/scenario_results_fail.png)

An all-**green** run:

![passing scenario run](./images/scenario_results_pass.png)

## How to reproduce

```
# unit + UI (incl. the PNG capture under build/scenario-screenshots/)
./gradlew jvmTest \
  --tests "com.knapsack.fixtool.ui.ScenarioResultsViewTest" \
  --tests "com.knapsack.fixtool.service.Scenario*" \
  --tests "com.knapsack.fixtool.ui.AppIntegrationTest" \
  --tests "com.knapsack.fixtool.ui.ToolbarDictionaryValidationTest"

# live-session runner (isolated — heavy QuickFIX integration tests are retried on CI)
./gradlew jvmTest --tests "com.knapsack.fixtool.integration.ScenarioIntegrationTest"
./gradlew jvmTest --tests "com.knapsack.fixtool.integration.ControlServerIntegrationTest"
```

## Result — 0 failures

| Test class | Tests | What it proves |
|------------|------:|----------------|
| `ScenarioResultsViewTest` | 2 | the results view renders PASS rows green and a failing tag red with expected/actual; writes the PNGs above |
| `ScenarioRunnerTest` / `ScenarioCodecTest` | 5 / 2 | runner + codec (unchanged, still green after the `ViewModelScenarioHost` refactor) |
| `ScenarioIntegrationTest` | 5 | live save/list/run/delete + JUnit + MCP, now through the shared host |
| `ControlServerIntegrationTest` | 29 | full control surface still green (34 MCP tools) |
| `AppIntegrationTest` / `ToolbarDictionaryValidationTest` | — | App + Toolbar still render after the Scenarios button/dialog wiring |

> Note: running all heavy QuickFIX integration tests together in one JVM can flake on
> session-registry/timing contention (they pass individually); CI retries them. The unit/UI
> tests are deterministic.

## Quality gates
- `./gradlew compileTestKotlinJvm` — clean.
- `./gradlew detekt` — no violations in any new Phase 3 file (`ScenarioResultsView`, `ScenariosDialog`,
  `ViewModelScenarioHost`). The remaining detekt output is limited to the pre-existing large
  `App.kt` / `Toolbar.kt` composables, which were already over the size/param thresholds before this
  work (the additions are a few short lines).
