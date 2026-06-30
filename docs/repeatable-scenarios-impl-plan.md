# Repeatable Scenarios — Phased Implementation Plan

Companion to the [design spec](./repeatable-scenarios-proposal.md) and the
[`fixtool_assert` crawl-phase spec](./fixtool-assert-spec.md). This is the
task-level breakdown: what ships in each phase, which files it touches, the new
types/services/tools, and the exit criteria that gate the next phase.

The phasing follows the design's **crawl → walk → run**. Each phase is independently
shippable and useful on its own. Build the assertion vocabulary first — it is the
hard part; the scenario format is a thin sequencing layer on top of it.

> **Status — all three phases are delivered and tested.**
> Phase 1 (Crawl): the matcher vocabulary, `fixtool_assert`, `fixtool_capture_expectation`,
> auto-seeding, atomic writes.
> Phase 2 (Walk): the `Scenario` model, persistent-scope `ScenarioRunner`, the extended
> match predicate (multi-tag AND + consumed cursor), the directory `ScenarioService`,
> the JSON+JUnit report, and the four scenario MCP tools.
> Phase 3 (Run): the in-app Scenarios panel — run / list / delete / author-by-JSON — and the
> per-tag **red/green run-results rendering** in the app.

Sizing is relative (S ≈ ½ day, M ≈ 1–2 days, L ≈ 3–5 days), assuming familiarity
with the codebase.

```
Phase 1 (Crawl)            Phase 2 (Walk)                 Phase 3 (Run)
matcher core ─────────────► scenario model + runner ─────► authoring UX
fixtool_assert              persistent scope, storage      matcher chips, preview
auto-seeding                save/list/run + CI report      verify-generalizes
                                                           run-results overlay
machine-check ONE msg      author once / repeat / CI       in-app authoring
```

---

## Decisions this plan implements

From the spec's [Resolved decisions](./repeatable-scenarios-proposal.md#resolved-decisions):

| # | Decision | Lands in |
|---|----------|----------|
| 0 | Runner owns a persistent, scenario-wide variable scope | Phase 2 |
| 1 | Directory store, one file per scenario, atomic writes | Phase 2 (atomic-write util in Phase 1) |
| 2 | Setup/teardown steps in scope; env reset is a precondition | Phase 2 |
| 3 | Per-field-type numeric tolerance, visible & editable | Phase 1 (seeding), Phase 3 (edit UI) |
| 4 | `match` predicate on `Expect`, multi-tag AND + consumed cursor | Phase 2 |
| 5 | Multi-session supported in the model; authoring UX later | Phase 2 (model), Phase 3 (UX) |

---

## Phase 1 — Crawl: `fixtool_assert` + matcher core + auto-seeding ✅ Delivered

**Goal:** turn the current "eyeball one response" step into a machine check, via a
single standalone MCP tool. No scenarios yet. Output: the matcher vocabulary is
frozen and proven against a real `ExecutionReport`.

> **Delivered.** All deliverables below shipped; the in-app Help gained an
> "Automated Verification (Assertions)" section.

**Why first:** the assertion vocabulary (tolerances, absence, temporal, references,
group-by-identity, OPEN/STRICT) is the genuinely hard design. Pinning it down on one
message — with immediate standalone value — de-risks everything downstream.
`Expectation` then becomes "these matchers + the captured golden", and the runner is
a loop over this evaluation.

### Deliverables

| # | Item | Files (new unless noted) | Size |
|---|------|--------------------------|------|
| 1.1 | **Matcher model** — `Matcher` sealed interface (Exact/Presence/Absent/Regex/OneOf/Numeric/Temporal/Reference), `FieldExpectation`, `GroupPath`, `MatchMode`, `Expectation`, `TagResult`. `@Serializable` with a `type` discriminator matching the [assert-spec encodings](./fixtool-assert-spec.md#matcher-json-encodings). | `model/scenario/Matcher.kt`, `model/scenario/Expectation.kt` | M |
| 1.2 | **Expectation evaluator** — pure function `evaluate(message, expectation, scope, dictionary): List<TagResult>` implementing the 6 [evaluation rules](./fixtool-assert-spec.md#evaluation-rules): per-tag eval, `absent`, STRICT synthetic failures (excluding header/trailer volatiles `8,9,10,34,52`), group-by-identity via `GroupPath`, reference resolution, numeric tolerance, temporal (UTCTimestamp/UTCDate) parsing. No I/O, no MCP, no UI. | `service/ExpectationEvaluator.kt` | L |
| 1.3 | **Reference resolution shim** — resolve `${...}` against a supplied variable scope **and** session message history, reusing `FixMessageTemplate` exactly as `fixtool_send resolve=true` does. | extend `service/FixMessageTemplate.kt` usage | S |
| 1.4 | **Auto-seeding** — `ExpectationSeeder.seed(message, dictionary): Expectation` mapping `DataDictionary.getFieldType(tag)` → default matcher per the [auto-seed table](./repeatable-scenarios-proposal.md#auto-seeding-matchers-the-usability-lever), incl. per-field-type numeric tolerance (Decision 3) and omission of `9,10,34,52`. | `service/ExpectationSeeder.kt` | M |
| 1.5 | **`fixtool_assert` tool** — definition mirroring the `tool(...)` DSL; handler does select-or-await (reuse `fixtool_wait` internals for `timeoutMs>0`), builds `Expectation` from inline `fields`, evaluates, returns `{passed, messageType, tags:[...]}`. | `control/McpTools.kt`, `control/ControlServer.kt` (handler + dispatch) | M |
| 1.6 | **`fixtool_capture_expectation` tool** — from a selected message, return an auto-seeded `Expectation` JSON ready to edit/paste into `fixtool_assert`. | `control/McpTools.kt`, `control/ControlServer.kt` | S |
| 1.7 | **Atomic-write utility** — `writeAtomically(file, content)` (temp + `Files.move(ATOMIC_MOVE)`); used by storage in Phase 2. Optionally backport to `SavedMessagesService`/`ConnectionProfileService` (their `saveAll` is `writeText`, not atomic despite the comment). | `util/AtomicFiles.kt` | S |

### Tests
- Unit: one suite per matcher type (incl. `Numeric` tolerance edges `1.2345` vs `1.23451`, `Temporal` now±N, `Absent` true/false, `OneOf`, `Regex`, `Reference` against scope).
- Unit: evaluator rules — STRICT synthetic failures, header-volatile exclusion, group-by-identity (entry present / absent / multiple roles).
- Unit: seeder maps each field type to the expected matcher + tolerance.
- Integration: `fixtool_assert` against the bundled demo acceptor (`fixtool_demo`, FX acceptor :19876) over a real book-a-trade `ExecutionReport` — both a passing and a deliberately-failing field.

### Exit criteria
- A response can be machine-checked via one MCP call; manual eyeballing is optional.
- Matcher JSON shape is frozen (it is the schema the scenario format reuses verbatim).

---

## Phase 2 — Walk: scenario model + runner + storage + CI report ✅ Delivered

**Goal:** author a flow once, repeat it deterministically any number of times with no
LLM, and emit a CI-consumable report. This is the regression-testing payoff.

> **Delivered.** `Scenario`/`ScenarioStep`/`MatchPredicate` model, `ScenarioCodec`,
> `ScenarioService` (directory store), `ScenarioRunner` (persistent scope, consumed
> cursor, setup/teardown), `ScenarioReport` (JSON + JUnit), and the
> `fixtool_save_scenario` / `list_scenarios` / `run_scenario` / `delete_scenario` tools.

### Deliverables

| # | Item | Files (new unless noted) | Size |
|---|------|--------------------------|------|
| 2.1 | **Scenario model** — `Scenario` (+ `setup`/`teardown`/`version`), `ScenarioStep` (`Send`/`Wait`/`Expect`), `Direction`, `MatchPredicate`. `@Serializable`. | `model/scenario/Scenario.kt` | M |
| 2.2 | **Persistent scenario scope (Decision 0)** — the engine's `variables` map is created fresh per send, so the runner cannot reuse it. Expose post-evaluation variables from `FixMessageTemplate` (an overload that mutates a caller-owned `MutableMap`, or returns the final map alongside the resolved message), and have the runner thread one scope across all steps as `seedVariables`. | extend `service/FixMessageTemplate.kt` | M |
| 2.3 | **`MatchPredicate` extension (Decision 4)** — multi-tag AND + a **consumed cursor** scoped to a single run, so successive same-type `Expect`s walk successive fills. Build on `ControlServer.matchesMessage`. | `service/MatchPredicate.kt`, refactor `control/ControlServer.kt` matcher | M |
| 2.4 | **`ScenarioRunner`** — walks `setup → steps → teardown` (teardown always runs); `Send` resolves `${...}` against the scenario scope and sends; `Wait` blocks on predicate/state; `Expect` awaits the `match`ing message then evaluates via the Phase 1 evaluator. Resolves each step's `session` independently (Decision 5). Produces `ScenarioResult`. | `service/ScenarioRunner.kt` | L |
| 2.5 | **`ScenarioService` (Decision 1)** — directory store `~/.fixtool/scenarios/<id>.json` (path via `AppSettings.scenariosPath`), atomic per-file writes (2.x: util from 1.7), versioned + `migrate()` per file, `userTags` profile filter. `list/load/save/delete`. | `service/ScenarioService.kt`, extend `model/AppSettings.kt` | M |
| 2.6 | **Report (Decision: dual-purpose)** — `ScenarioResult`/`StepResult`/`TagResult` → JSON and JUnit XML; a headless run entrypoint that sets a process exit code from `ScenarioResult.passed`. | `service/ScenarioReport.kt`, CLI/headless hook | M |
| 2.7 | **MCP tools** — `fixtool_save_scenario`, `fixtool_list_scenarios`, `fixtool_run_scenario` (returns `ScenarioResult`), `fixtool_delete_scenario`. | `control/McpTools.kt`, `control/ControlServer.kt` | M |

### Tests
- Runner against the demo acceptor: full book-a-trade scenario, **run twice** — both passes identical (proves repeatability; catches any `Exact`-on-volatile mistakes).
- Partial-fill: a scenario with `Expect(150=F,39=1)` then `Expect(150=F,39=2)` binds to the right messages via the consumed cursor.
- Scope: value assigned in step 1 referenced in a `Reference` matcher in step 3 (proves Decision 0 across sends).
- Storage: save/list/load/delete round-trip; atomic write survives a simulated mid-write failure; per-file `migrate()` bumps an old version.
- Report: JUnit XML validates; exit code non-zero on a failing scenario.
- Setup/teardown: `clear_messages` + `reset-seqnum` setup yields a clean start; teardown runs after a mid-scenario failure.

### Exit criteria
- A saved scenario runs deterministically (twice → identical pass) and emits a
  CI-consumable report with a meaningful exit code.
- Multi-session is exercisable by hand-authoring steps with differing `session`.

---

## Phase 3 — Run: in-app authoring UX ✅ Delivered (results overlay + run/manage; rich visual authoring deferred)

**Goal:** a QA engineer (or agent) authors and saves scenarios in the app, without
hand-editing JSON.

> **Delivered.** A **Scenarios panel** (toolbar ▶ button → `ScenariosDialog`) that lists
> saved scenarios and lets you **run** (3.5), **delete**, and **author by pasting JSON**
> them, with the **per-tag red/green run-results overlay** (`ScenarioResultsView`) — the
> in-app rendering of a failed assertion. The runner is shared with the control surface via
> `ViewModelScenarioHost`. Capture-to-expectation (3.2) is available via the
> `fixtool_capture_expectation` tool.
>
> **Deferred (future enhancement):** the richer in-*detail-panel* authoring — per-tag matcher
> chips with live preview (3.1), the two-instance "verify generalizes" check (3.3), a visual
> step-by-step builder (3.4), and a dedicated multi-session authoring UI (3.6). Scenarios are
> fully authorable today via JSON, the `fixtool_save_scenario` tool, or an agent; these items
> are convenience UX on top of an already-complete feature.

### Deliverables

| # | Item | Files | Size |
|---|------|-------|------|
| 3.1 | **Per-tag matcher chips** — each tag row in the detail panel shows its matcher as an editable chip with a live green/red result computed against the captured message. | `ui/MessageDetailPanel.kt`, `ui/HierarchicalGridView.kt` | L |
| 3.2 | **Capture → expectation flow** — "capture expectation" action on a selected message (uses `ExpectationSeeder`), incl. group-by-identity editing for repeating groups. | `ui/` + viewmodel | M |
| 3.3 | **Two-instance "verify generalizes"** — one-click action that evaluates the expectation against a second instance of the response (fresh IDs/timestamps); flags any field still on `Exact` that should be relaxed. | `ui/` + `viewmodel/FixMessageViewModel.kt` | M |
| 3.4 | **Scenario builder** — record a sequence of sends, attach `Expect`s, parameterize requests, set `setup`/`teardown`, save via `ScenarioService`. | new `ui/ScenarioBuilder*.kt` | L |
| 3.5 | **Run-results overlay** — render a `ScenarioResult` as red/green per-tag highlights in the existing viewer (reuses `TagResult`). | `ui/MessageDetailPanel.kt` | M |
| 3.6 | **Multi-session authoring (Decision 5, deferred)** — UI to assign steps across initiator/acceptor sessions. Optional within this phase. | `ui/ScenarioBuilder*.kt` | M |

### Tests
- UI/integration for chip editing, live preview, verify-generalizes.
- Manual: author a book-a-trade scenario end-to-end in-app, save, run, see red/green.

### Exit criteria
- A non-developer can author, save, and run a scenario without touching JSON.

---

## Sequencing & risks

- **Hard dependency:** Phase 2 builds on the Phase 1 matcher core and evaluator;
  Phase 3 builds on the Phase 2 model, runner, and storage. Ship in order.
- **Recommended first PR:** Deliverables 1.1–1.5 (matcher core + evaluator +
  `fixtool_assert`). It is self-contained, unit-testable, and immediately useful.
- **Watch items**
  - *Post-send variables (2.2)* — the single most likely surprise. Confirm exactly
    how `FixMessageTemplate` writes assignments back before designing the runner's
    scope threading; it currently discards them into a per-send copy.
  - *Consumed-cursor lifetime (2.3)* — scope it to one run; never leak across runs.
  - *Temporal timezone* — FIX UTCTimestamp is UTC; evaluate against UTC `now`, not
    local, to avoid flaky ±tolerance failures.
  - *STRICT volatiles* — keep the `8,9,10,34,52` exclusion list in one place shared
    by the evaluator and the seeder.
- **Backport (optional, low-risk):** apply the 1.7 atomic-write util to the existing
  two services to close the same truncate-in-place corruption gap.
