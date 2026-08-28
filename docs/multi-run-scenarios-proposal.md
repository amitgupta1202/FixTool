# Multi-run scenarios — one run set, three presets

**Status: proposal.** Nothing here is implemented. It answers three questions that arrived as one
("can we multi-run a scenario?"), argues they are the same feature, and specifies the smallest model
that serves all three without a second reporting stack.

## The question, disambiguated

"Multi-run" is three different needs wearing one phrase, and they want different things from the tool:

| Reading | The question it answers | Shape |
|---|---|---|
| **Repeat** | *Is this flow flaky?* Run `book-a-trade` twenty times and tell me it passed twenty times. | one scenario × N iterations, sequential |
| **Suite** | *What broke overnight?* Run the twelve scenarios I care about and give me one verdict. | N scenarios × 1, sequential |
| **Fan-out** | *Does it hold with fifty clients?* Run the same flow on fifty sessions at once. | one scenario × N session maps, concurrent |

All three are possible. Two of them (**Repeat**, **Suite**) are close to free — the runner is already
pure, deterministic, and re-entrant across runs, and the only thing standing between it and a
`for` loop is what the *UI and the report* do with more than one verdict. The third (**Fan-out**) is
a genuine concurrency change and is phased last.

## The model: a run set

The three readings collapse to one primitive, and that is the whole proposal:

> A **run set** is an ordered list of run requests — `(scenario, sessionMap, iteration)` — executed by
> a scheduler, producing a list of `ScenarioResult`s.

- **Repeat** is one scenario × N iterations.
- **Suite** is N scenarios × one iteration.
- **Fan-out** is one scenario × N session maps, with concurrency > 1.
- A **matrix** (the suite, three times over) is the product, and needs no new concept.
- **A single run is a set of one** — which is the property that makes this cheap.

```kotlin
data class RunSet(
    val id: String,
    val label: String,              // "★ Favourites — 12 scenarios" / "book-a-trade ×20"
    val entries: List<RunEntry>,    // planned UP FRONT: the queue is visible before it runs
    val startedAt: Long,
    val policy: RunPolicy,
)

data class RunEntry(
    val scenarioId: String,
    val scenarioName: String,
    val iteration: Int,             // 1-based; always 1 for a plain suite entry
    val sessionMap: Map<String, String> = emptyMap(),
    val state: RunState,            // PENDING | RUNNING | PASSED | FAILED | SKIPPED
    val result: ScenarioResult? = null,
    val durationMs: Long? = null,
)

data class RunPolicy(
    val stopOnFirstFailure: Boolean = false,
    val pauseBetweenMs: Long = 0,
    val concurrency: Int = 1,       // > 1 only under the disjointness rule (Phase 3)
    val isolateIterations: Boolean = true,   // see Decision 2
)
```

**`ScenarioResult` does not change.** Neither does `ScenarioReport.toJson`, nor the per-tag model, nor
the reconcile route. A run set is a *list of the thing that already exists* — that is the difference
between a week of work and a quarter of it, and it is the only reason the reconcile viewer keeps
working through a batch.

## What the code says about feasibility

Four facts from the current implementation, because they decide the phasing:

1. **The runner is already repeat-safe in the parts that matter.** `ScenarioRunner.runIdentified`
   (`ScenarioRunner.kt:120`) allocates a **fresh variable scope**, a fresh consumed-set and a fresh
   watermark per run, so iteration 2 mints its own `ClOrdID` and cannot inherit iteration 1's scope.
   Nothing needs to change for `${uuid}` to stay unique across a repeat.

2. **There is exactly one run slot, and it is a global boolean.**
   `FixMessageViewModel.beginScenarioRun()` (`FixMessageViewModel.kt:2676`) is a CAS on
   `_scenarioRunning`, shared by the Run button and `/scenarios/run`, because two runners "would
   otherwise race each other's consumed-message cursors". For a *sequential* set this is not a
   blocker — it is a correction: the **set** claims the slot once and holds it for the whole batch, so
   an MCP run cannot slip in between iterations 7 and 8. For fan-out it must become a claim over a
   *session set*, and the licence is precisely the comment's own reasoning: cursors are per-session,
   so two runs whose `runSessions()` (`ScenarioRunner.kt:1085`) are disjoint cannot interfere.

3. **The verdict surfaces are singular, and one of them already is not.**
   `_scenarioResult`, `_lastRunScenario` and the reconcile route are one-run-at-a-time; but
   `_assertionResults` (`FixMessageViewModel.kt:268`) is a `Map<FixMessage, StepResult>` keyed by
   *message identity*, so concurrent lanes on disjoint sessions merge into it without collision. The
   grid tint is already fan-out-shaped. Only the verdict is not.

4. **STRICT traffic is not watermark-aware, and repetition makes that systematic.**
   `trafficCheck` (`ScenarioRunner.kt:527`) scans each session's whole log minus what *this run*
   consumed. Iteration 2 therefore sees iteration 1's messages as unbound strays and fails —
   as does any second manual run today, which is a latent bug repetition would turn into a rule.
   The fix belongs to the runner, not to the batch: exclude what the run's own watermark says predates
   it (`PreRun.predates`, `ScenarioRunner.kt:1051`), which is the semantics the check always meant.

## The focused entry — why there is no second reporting stack

A run set has N verdicts; the grid, the run report and the diff window each speak about one. So:

> **The existing single-run surfaces become "the focused entry of the current set".**

Selecting a row in the set report republishes that entry's `ScenarioResult` through
`publishScenarioResult`, sets `lastRunScenario` to that entry's scenario, and retints the grid. A
single run is a set of one, auto-focused — so today's behaviour is the degenerate case of the new
one, and `RunStatusLine`, the reconcile route and *Save & re-run* keep working with no change to what
they read.

One honesty obligation comes with it. A repeat clears the sessions between iterations (capture
authors a `ClearMessages` per session into `setup`, `ScenarioCapture.kt:392`), so focusing iteration 3
of 20 may offer a report whose *messages* no longer exist. The report itself is intact — expected and
actual are values in `TagResult`, not pointers into the grid — but the tint and the reconcile route
cannot be honoured. The rail already refuses a route by explaining why rather than hiding the button;
this is the same sentence with a new reason: *"the messages this run judged were cleared by iteration
4 — re-run this scenario alone to reconcile it."*

## Decisions

### Decision 1 — The set holds the run slot; entries do not re-claim it

`runScenarioBlocking` splits into `claimRunSlot { … }` and a slot-free `runOne`. A set claims once.
Consequence, and it is the right one: `/scenarios/run` answers *"a scenario run is already in
progress"* for the whole duration of a batch instead of interleaving with it.

### Decision 2 — A repeat isolates its iterations by default (`binding = this_run`)

Under the default `BindScope.ANY`, an `expect` may bind a message that arrived before the run started
(`Scenario.kt` / `BindScope`) — so iteration 2 can bind iteration 1's `ExecutionReport` and report
that the venue answered when it has not. On a single run that is a caveat the report already prints
(`staleBindCheck`, `ScenarioRunner.kt:1068`). On a twenty-times repeat it is a false green *by
construction*, and a feature whose purpose is catching flakiness must not manufacture passes.

So a repeat runs its entries under `BindScope.THIS_RUN` — a **run-time override, never a file
rewrite** — with a checkbox in the run dialog ("Isolate each iteration") and a line on the set header
saying it is on. The alternative (warn and proceed) was rejected: the warning would appear on exactly
the runs nobody reads, the green ones.

### Decision 3 — Continue on failure by default; stop-on-first is a switch

A suite exists to produce the whole morning's picture, and a flake hunt wants "3 of 20 failed", not
"failed at 4". CI gates want the opposite, so `--stop-on-failure` / `stopOnFailure: true` exists — but
it is not the default anywhere.

### Decision 4 — Selection reuses what the rail already has

No new taxonomy. A set is built from **★ Favourites** (`ScenarioViewState.favouriteIds`), from the
**current filter text** (whatever the rail is showing), or from an explicit **multi-select**. The
`Scenario.userTags` field exists and is unused by any surface; a `--tag` selector on the CLI is the
one place worth waking it, because CI selects by name, not by a local star file.

### Decision 5 — Fan-out is licensed by disjointness, and refused without it

Concurrency > 1 is permitted only when every lane's `runSessions()` is disjoint from every other's.
Two lanes on the same session share a message log and a consumed cursor, and the resulting report
would be indistinguishable from a venue bug. The natural source of disjoint lanes already exists:
a multi-session profile expands to `QUOTE1…QUOTE50`, and `Scenario.withSessions` is documented as the
throwaway per-run remap that `RemapScenarioDialog` already authors. A fan-out is N of those maps.

## Surfaces

### The rail

The header's `+`/sort row gains a **Run ▾** control; the per-row hover Run button is unchanged (it is
a set of one). The run report grows a set header — progress, counts, elapsed — over a compact,
scrollable, click-to-focus entry list. Everything below it is today's report, for the focused entry.

```
┌ Scenarios ─────────────────── + ⋮ ┐     ┌ Run ▾ ───────────────────────┐
│ Run ▾   ⌕ filter…                 │     │ Run ★ favourites      (12)   │
├───────────────────────────────────┤     │ Run filtered           (4)   │
│ ▸ Running ★ Favourites  7/12  ⏹   │     │ Run selected…                │
│   ✗ 2 failed · 4m12s elapsed      │     │ ──────────────────────────── │
│   ✓ smoke-nos              1.2s   │     │ Repeat this scenario ×N…     │
│   ✓ book-a-trade           4.8s   │     │ Fan out over sessions…       │
│   ✗ cancel-replace   2/4   3.1s ▸ │     └──────────────────────────────┘
│   ⟳ quote-stream         running  │
│   · allocation           pending  │
├───────────────────────────────────┤
│ Last run — cancel-replace: FAILED │   ← today's report, for the FOCUSED entry
│ First failure: step 3 expect …    │
│ Reconcile step 3 →                │
└───────────────────────────────────┘
```

A repeat set collapses to one row per scenario with an iteration strip, because twenty rows of the
same name is not a report:

```
│ ▾ book-a-trade ×20      17/20 ✓   │
│   ▪▪▪▪▪▫▪▪▪▪▪▪▪▪▫▪▪▪▫▪    3 failed │   ← each ▪ click-to-focus; ▫ = failed
│   failures: #6, #15, #19          │
│   slowest #15 8.4s · median 1.9s  │
```

Median and slowest are the point of a repeat: a flow that passes twenty times but drifts from 1.9s to
8.4s has told you something no pass/fail can.

### HTTP / MCP

`/scenarios/run` stays byte-compatible: given `id` or `scenario` alone it returns exactly the object
it returns today. Given any of `ids`, `tag`, `repeat`, `stopOnFailure` or `concurrency`, it returns a
set:

```jsonc
POST /scenarios/run
{ "ids": ["smoke-nos", "book-a-trade"], "repeat": 3, "stopOnFailure": false }

{ "passed": false,
  "summary": { "total": 6, "passed": 5, "failed": 1, "durationMs": 21840 },
  "runs": [ { "scenario": "smoke-nos", "iteration": 1, "passed": true,
              "durationMs": 1180, "steps": [ … ] }, … ] }   // each entry = today's report shape
```

`fixtool_run_scenario` gains the same optional parameters. An agent asking "run the suite and tell me
what broke" is one call, and each element of `runs` is the report shape it already knows how to read
and reconcile from.

### CLI

`HeadlessRun`'s own header calls the batch sweep *"the strongest driver"* and then supports one
scenario per process (`HeadlessRun.kt:30`). This closes it:

```bash
fixtool run --all --junit reports/            # every saved scenario, one file per scenario
fixtool run --tag nightly --stop-on-failure
fixtool run book-a-trade --repeat 20 --pause 500ms
echo $?    # 0 all passed · 1 something failed · 2 could not run
```

`ScenarioReport.toJUnitXml` gains a list overload emitting a `<testsuites>` wrapper of the suites it
already renders; an iteration is `name="book-a-trade #3"`. `--junit <dir>` writes one file per entry;
`--junit <file.xml>` writes the wrapper.

## Phasing

| Phase | Content | Touches |
|---|---|---|
| **1 — Repeat + Suite, sequential** | `RunSet`/`RunEntry`/`RunPolicy`, set-owned run slot, rail Run ▾ + set report, `/scenarios/run` set mode, CLI `--all` / `--repeat` / `--tag`, `<testsuites>` | ViewModel, rail, control, headless. No disk format, no scenario file change. |
| **1a — the watermark fix** | STRICT's stray scan excludes what predates the run (`ScenarioRunner.kt:527`) | Runner. Ship with or before Phase 1 — it is a bug today. |
| **2 — Focus & reconcile from a set** | Click-to-focus republishes an entry; the "cleared by a later iteration" refusal sentence | ViewModel, rail. |
| **3 — Fan-out** | Slot becomes a per-session-set claim under a disjointness check; `concurrency`; lanes from a multi-session profile | ViewModel, runner host, rail dialog. |

Phase 1 is the one that answers the question as asked. Phase 3 is a load-testing feature that happens
to share the model, and it should be judged on its own merits when it is wanted.

## Risks

- **A batch that hides a venue's state drift.** Twenty green iterations against a venue that is
  quietly accumulating open orders is not the same evidence as twenty against a clean one. The set
  report shows per-iteration duration precisely so drift is visible before it is a failure; the tool
  cannot reset a venue it does not own.
- **Wall-clock.** A STRICT scenario pays `settleMs` (1s) per run; a twenty-times repeat pays twenty
  seconds of it. Worth saying in the run dialog, not worth optimising away — the settle window is the
  claim.
- **A set report that buries the list.** The rail has been here before (`scenario-rail-phase3.md`);
  the set report inherits the same rule — bounded height, internal scroll, never more than about a
  third of the rail.
