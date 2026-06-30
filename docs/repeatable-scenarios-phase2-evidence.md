# Repeatable Scenarios — Phase 2 (Walk) delivery evidence

Phase 2 turns the single-message assertion of Phase 1 into a **repeatable scenario**: author a flow
once, then a deterministic runner replays the sends and assertions identically — no LLM in the hot
path — and emits a CI-consumable report.

## What shipped

| Plan item | Status | Where |
|-----------|--------|-------|
| 2.1 Scenario model | ✅ | `model/scenario/Scenario.kt` (`Scenario`, `ScenarioStep`, `MatchPredicate`, `StepResult`, `ScenarioResult`) |
| 2.2 Persistent scenario scope (Decision 0) | ✅ | `ScenarioRunner` threads one `MutableMap` across all steps; resolution via `FixMessageTemplate` |
| 2.3 Match predicate: multi-tag AND + consumed cursor | ✅ | `MatchPredicate` + `ScenarioRunner` (Expect consumes its match) |
| 2.4 `ScenarioRunner` (setup → steps → teardown) | ✅ | `service/ScenarioRunner.kt` (+ `ScenarioHost` interface) |
| 2.5 `ScenarioService` directory store | ✅ | `service/ScenarioService.kt` (`~/.fixtool/scenarios/<id>.json`, atomic writes) |
| 2.6 Dual-purpose report (JSON + JUnit XML) | ✅ | `service/ScenarioReport.kt` |
| 2.7 Scenario MCP tools | ✅ | `fixtool_save_scenario` / `list_scenarios` / `run_scenario` / `delete_scenario` (`McpTools.kt` + `ControlServer.kt`) |
| JSON codec | ✅ | `service/ScenarioCodec.kt` (reuses `MatcherCodec`) |

The control layer implements `ScenarioHost` against the live sessions; storage is wired through
`FixMessageViewModel.scenarioService` (new `AppSettings.scenariosPath`).

## How to reproduce

```
./gradlew jvmTest \
  --tests "com.knapsack.fixtool.service.ScenarioRunnerTest" \
  --tests "com.knapsack.fixtool.service.ScenarioCodecTest" \
  --tests "com.knapsack.fixtool.integration.ScenarioIntegrationTest" \
  --tests "com.knapsack.fixtool.integration.ControlServerIntegrationTest"
```

## Result — `BUILD SUCCESSFUL`, 0 failures

| Test class | Tests | What it proves |
|------------|------:|----------------|
| `ScenarioRunnerTest` | 5 | send→expect happy path; **persistent scope** across separate sends; **consumed cursor** walking successive same-type messages (partial fills); clean expect timeout; failure aborts remaining steps but **teardown still runs** |
| `ScenarioCodecTest` | 2 | a full scenario (all step types, every matcher, group path, STRICT mode) **round-trips through JSON unchanged**; JUnit XML reports a failing step |
| `ScenarioIntegrationTest` | 5 | over a live acceptor↔initiator: run an inline scenario end-to-end (reference matcher resolves `${out.D.11}`); a wrong expectation fails; **save → list → run-by-id → delete**; JUnit output; runnable over MCP |
| `ControlServerIntegrationTest` | 29 | existing suite still green; MCP tool count updated 30 → 34 |

## Quality gates
- `./gradlew compileTestKotlinJvm` — clean.
- `./gradlew detekt` — no violations in any added/modified Phase 2 file.

## Not yet delivered (Phase 3 / Run)
The in-app authoring UX: per-tag matcher chips with live preview, capture-to-expectation, the
two-instance "verify generalizes" check, the scenario builder, and the **red/green run-results
overlay in the message detail panel** (the in-app failure rendering).
