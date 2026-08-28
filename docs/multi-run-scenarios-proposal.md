# Multi-run scenarios — one run set, four ways in

**Status: proposal, revised 2026-08-28.** Nothing here is implemented. It answers four questions that
arrived as one ("can we multi-run a scenario?"), argues they are the same feature, and specifies the
smallest model that serves all four without a second reporting stack.

**Mockups:** [`docs/mockups/multi-run-scenarios.html`](mockups/multi-run-scenarios.html) — interactive
(click entries, iterations, lanes and rows to see the report and the record's grid respond); download
and open locally in a browser, since GitHub does not execute a `.html` file's script from its file
view. Built against FixTool's own dark palette, so the mockups read as the app's own screens rather
than a separate design pass.

## What changed in this revision, and why

The first version of this document was reviewed against `main` at `21ee1c1`, after #35 (acceptor
order state) had landed on top of it. The model survived the review intact. Five things it called free
or already-there did not, and each one moved something in the design:

| The first draft said | What `main` says | What moved |
|---|---|---|
| "the per-lane timings the set already records" give p50/p95 for free | No timing exists anywhere: `ScenarioReport` emits no duration, no per-step elapsed, no `time=` (`ScenarioReport.kt:73-81`) | Per-step latency is recorded by the runner (Phase 0), and the fan-out distribution is over *step* latency, not scenario wall-clock |
| `/scenarios/run` grows set parameters and "run the suite is one call" | The route is synchronous on one of four HTTP threads with no timeout (`ControlServer.kt:141, 1426, 3249`); the MCP shim aborts any call over 15 s (`tools/fixtool-mcp/index.mjs:38, 50-64`) | A set is a **job**: start, poll, fetch. The bare-`id` call stays as it is |
| Evidence is references into the session, held by the tab; the on-disk archive is a follow-on | The runner holds no snapshots between polls (`ScenarioRunner.kt:423-446`); `bufferSize` is 1,000 (`FixMessageSession.kt:54`); `FixMessage` equality is structural-by-accident (`FixMessage.kt:41`); the overnight suite must survive a restart | The **run record on disk is the artifact**; the tab is a viewer over it; headless writes the same record |
| "the tool cannot reset a venue it does not own" | Half the users run both ends, and nothing — not `ClearMessages`, not a run start — resets the venue's order book (`FixMessageSession.kt:400-411`, `QuickFixService.kt:251`) | A `ClearOrderBook` step, and a dialog that says what isolation does *not* reset |
| `--tag` wakes the unused `Scenario.userTags` | `userTags` is the per-profile filter (`ScenarioService.kt:60`) and is emitted by `GET /scenarios` (`ControlServer.kt:1011`) | **Saved run sets** — a file, like everything else the app keeps — replace tag selection |

Smaller corrections are folded in where they apply and marked *(revised)*.

## The question, disambiguated

"Multi-run" is four different needs wearing one phrase, and they want different things from the tool:

| Reading | The question it answers | Shape |
|---|---|---|
| **Repeat** | *Is this flow flaky?* Run `book-a-trade` twenty times and tell me it passed twenty times. | one scenario × N iterations, sequential |
| **Suite** | *What broke overnight?* Run the twelve scenarios I care about and give me one verdict. | N scenarios × 1, sequential |
| **Examples** | *Does it hold for every instrument?* Run this flow once per row of a table — Cucumber's Scenario Outline. | one scenario × N parameter rows, sequential |
| **Fan-out** | *Does it hold with fifty clients?* Run the same flow on fifty sessions at once. | one scenario × N session maps, concurrent |

All four are possible. Three of them (**Repeat**, **Suite**, **Examples**) are close to free in the
runner — it is already pure, deterministic and re-entrant across runs — and what stands between it and
a `for` loop is what the *UI, the report and the control surface* do with more than one verdict.
**Fan-out** is the one genuine concurrency change and is phased last; its sessions need no new
connection logic (see [Fan-out](#fan-out-and-where-its-sessions-come-from)), but its safety rule and
its far end both needed correcting.

## The model: a run set

The four readings collapse to one primitive, and that is the whole proposal:

> A **run set** is an ordered list of run requests — `(scenario, iteration, row, lane)` — executed by
> a scheduler, producing a list of `ScenarioResult`s, each written to disk as a **run record** the
> moment it lands.

- **Repeat** is one scenario × N iterations.
- **Suite** is N scenarios × one iteration.
- **Examples** is one scenario × N parameter rows.
- **Fan-out** is one scenario × N lanes, with concurrency > 1.
- A **matrix** (the suite, three times over) is the product, and needs no new concept.
- **A single run is a set of one** — which is the property that makes this cheap.

```kotlin
data class RunSet(
    val id: String,                 // also the record directory: ~/.fixtool/runs/<id>/
    val label: String,              // "nightly — 12 scenarios" / "book-a-trade ×20"
    val source: RunSource,          // how the entries were chosen (below)
    val entries: List<RunEntry>,    // planned UP FRONT: the queue is visible before it runs
    val policy: RunPolicy,
    val startedAt: Long,
)

/** Where the entries came from. A saved set is the only one that persists; the rest describe a click. */
sealed interface RunSource {
    data class Saved(val setName: String) : RunSource
    object Favourites : RunSource
    data class Filtered(val text: String) : RunSource
    data class Selected(val ids: List<String>) : RunSource
    data class Repeat(val scenarioId: String, val times: Int) : RunSource
    data class Examples(val scenarioId: String) : RunSource
    data class FanOut(val scenarioId: String, val profileId: String) : RunSource
}

data class RunEntry(
    val scenarioId: String,
    val scenarioName: String,
    val iteration: Int,             // 1-based; always 1 for a plain suite entry
    val row: ExampleRow? = null,    // the parameter row this entry runs, when it has one
    val lane: Lane? = null,         // the fan-out lane this entry runs on, when it has one
    val sessionMap: Map<String, String> = emptyMap(),
    val state: RunState,            // PENDING | RUNNING | PASSED | FAILED | SKIPPED | STOPPED
    val result: ScenarioResult? = null,
    val durationMs: Long? = null,
    val record: Path? = null,       // the entry's file, once written
)

/** A lane is a session slot, named by the number the profile gave it — not by its position in a list. */
data class Lane(val slot: Int, val sessionTitle: String, val senderCompID: String, val qualifier: String)

data class RunPolicy(
    val stopOnFirstFailure: Boolean = false,
    val pauseBetweenMs: Long = 0,
    val concurrency: Int = 1,       // > 1 only under the disjointness rule (Phase 4)
    val isolateIterations: Boolean = true,   // see Decision 2
)
```

**`ScenarioResult` and `ScenarioReport.toJson` grow two additive fields and nothing else:**
`ScenarioResult.durationMs` and `StepResult.latencyMs` (Phase 0, below). The per-tag model and the
reconcile route do not change. A run set is a *list of the thing that already exists*, and that is the
only reason the reconcile viewer keeps working through a batch.

## What the code says about feasibility *(revised)*

Eight facts from the current implementation, because they decide the phasing. The first four were in
the original draft and hold; the last four were found in review and each one changed the design.

1. **The runner is already repeat-safe in the parts that matter.** `ScenarioRunner.runIdentified`
   (`ScenarioRunner.kt:120`) allocates a **fresh variable scope** (`:128`), a fresh consumed-set
   (`:129`) and a fresh watermark (`:138-140`) per run, so iteration 2 mints its own `ClOrdID` and
   cannot inherit iteration 1's scope. Nothing needs to change for `${uuid}` to stay unique across a
   repeat.

2. **There is exactly one run slot, and it is a global boolean.**
   `FixMessageViewModel.beginScenarioRun()` (`FixMessageViewModel.kt:2689`) is a CAS on
   `_scenarioRunning`, shared by the Run button and `/scenarios/run` through their one common caller
   `runScenarioBlocking` (`:2879`), because two runners "would otherwise race each other's
   consumed-message cursors". For a *sequential* set this is not a blocker — it is a correction: the
   **set** claims the slot once and holds it for the whole batch. For fan-out it must become a claim
   over a *session set*, and the licence is the comment's own reasoning: cursors are per-run over
   per-session logs, so two runs whose sessions are disjoint cannot interfere.

3. **The verdict surfaces are singular, and the tint map is not what it looks like.** `_scenarioResult`,
   `_lastRunScenario` and the reconcile route are one-run-at-a-time. `_assertionResults`
   (`FixMessageViewModel.kt:275`) is a `Map<FixMessage, StepResult>` — but it is Compose
   `mutableStateOf`, not a flow, and `FixMessage` is a `data class` whose equality collapses to
   identity only because `quickfix.Message` never overrode `equals` (`FixMessage.kt:41`). It works as
   an identity map today; a `copy()` or a re-parse breaks it. Per-entry maps in this design key by
   `AppMessage.uid` (`FixMessage.kt:25`), which is the identity the class actually promises.

4. **STRICT traffic is not watermark-aware, and repetition makes that systematic.**
   `trafficCheck` (`ScenarioRunner.kt:527-538`) scans each session's whole log minus what *this run*
   consumed. Iteration 2 therefore sees iteration 1's messages as unbound strays and fails — as does
   any second manual run today. `PreRun.predates` (`:1051`) exists and is used only to narrate stale
   binds (`:388`, `:468`). Under `BindScope.THIS_RUN` it is worse, not better: pre-run messages are
   unbindable (`:1053`), so every one of them is *guaranteed* to be a stray. No test seeds the log
   before a run (`ScenarioRunnerTest.kt:666-757`). This is a bug today, and Decision 2 depends on it
   being fixed first.

5. **No timing exists.** `ScenarioReport.toJson` and `toJUnitXml` (`ScenarioReport.kt:18, 67`) take a
   `ScenarioResult` that carries no duration and no per-step elapsed; the JUnit `<testcase>` has no
   `time=`. Every number on the repeat strip and the fan-out summary is new. And the number the first
   draft would have recorded — scenario wall-clock — is the wrong one for a venue's p95: it includes
   preflight, `settleMs` (paid only when a STRICT run is green, `ScenarioRunner.kt:202, 528`),
   `pauseBetweenMs` and Expect timeouts. The runner knows the Send it wrote and the reply it bound;
   the latency between them is the number a venue is being asked for.

6. **The control surface cannot carry a set as it stands.** `POST /scenarios/run` runs the scenario
   on the calling HTTP thread — one of four (`ControlServer.kt:141, 1426, 3249`) — with no request
   timeout; errors go out as HTTP 200 with a `status: "error"` body (`:3109, 3203`). The MCP Node
   shim imposes a 15 s ceiling that `fixtool_run_scenario` cannot extend (`index.mjs:38, 50-64`). A
   12-scenario suite is minutes. So a set is started and polled, and the bare-`id` call is left alone.

7. **Nothing resets the venue's order book at a run boundary, and no step can.** #35 gave the
   acceptor a book per client session (`QuickFixService.kt:251, 687-701`). `ClearMessages` clears the
   message deque only (`FixMessageSession.kt:400-411`); the book is cleared by the panel button or
   `/acceptor/orders` (`ControlServer.kt:2085-2097`), never by a step. A repeat that reuses a
   `ClOrdID` routes iteration 2's `35=D` as `Moved`, not `Born` (`OrderBook.kt:399-400`), and the
   `whenOrder`-conditioned presets read iteration 1's state: the duplicate-`ClOrdID` rule
   (`AcceptorPresets.kt:415-427`) rejects on iteration 2 what it acked on iteration 1 — a false red —
   unless iteration 1 filled, in which case neither rule fires. A repeat with unique ids grows the
   book to its cap and then evicts, which changes what a cancel is answered with mid-soak (decision
   8a of the order-state doc). Either way, a repeated run against FixTool's own venue is running
   against a venue whose memory the scenario cannot reach.

8. **The runner cannot be stopped.** `abort` (`ScenarioRunner.kt:154-169`) is stop-on-failure inside
   one run; nothing external can interrupt an Expect polling toward an 8 s deadline. A twenty-times
   repeat with a ⏹ button needs a cancel token checked in the three poll loops (`:281-284`,
   `:378-396`, `:423-446`).

## Phase 0 — what is a bug or a missing primitive today

Every later phase leans on these, and each is independently worth shipping. None changes a file format.

- **The watermark fix.** `trafficCheck` excludes what the run's own watermark says predates it
  (`PreRun.predates`) — the semantics the check always meant. STRICT stays judged only on green runs
  (`:202`). A test that seeds the log before the run, which does not exist today.
- **Per-step latency and run duration.** `StepResult.latencyMs` — for an `Expect`/`Wait`, the bound
  message's `captureTimeMicros` minus the most recent preceding `Send` on the same session; for a
  `Send`, the time to hand it to the session. `ScenarioResult.durationMs`. Both additive in
  `toJson`; `toJUnitXml` gains `time=` on every `<testcase>` and `<testsuite>`.
- **The JUnit disagreement.** `toJUnitXml` counts teardown failures (`ScenarioReport.kt:68`) while the
  verdict exempts teardown (`ScenarioRunner.kt:214`), so a run can exit 0 with `failures="1"`. And
  run-level rows render as `step -1 traffic (steps)` (`:74`). Both fixed before a `<testsuites>`
  wrapper multiplies them by N.
- **A cancel token.** `ScenarioRunner` takes `cancelled: () -> Boolean`; a cancelled run reports a
  run-level `stopped` row and returns; the ⏹ button and `POST /scenarios/runs/<id>/stop` flip it.
- **`ScenarioStep.ClearOrderBook(session)`.** The sixth step kind. Valid only for a session that is a
  venue pane (`FixMessageSession.clearOrderBook`, `:650-652`); on any other session it is a named
  refusal in preflight, the way a `ClearMessages` on a missing session already is. Capture authors it
  into setup beside `ClearMessages` when the selected session is a venue pane, so a captured
  both-sides scenario resets both memories by default.

## The run record — where the evidence lives *(revised)*

**This is the question a suite actually asks.** Entry 2's setup calls `ClearMessages` (capture authors
one per session, `ScenarioCapture.kt:392`), so the moment the second scenario starts, the first one's
messages are gone from the grid — and even without a clear, `FixMessageSession` is a ring buffer of
1,000 (`FixMessageSession.kt:54, 331, 393`). By the time a twelve-scenario suite lands, the grid holds
the last entry's traffic and nothing else. Eleven reports would point at messages that are not there.

The first draft answered this with references: hold each entry's `FixMessage` objects in memory, in
the tab. Review found three things wrong with that, and they point the same way:

- **The overnight suite is the primary case, and it must survive a restart.** "What broke overnight"
  is answered in the morning, from an app that may have been closed, crashed or updated since.
  References die with the process.
- **Headless runs are the other primary case, and they have no tab.** `HeadlessRun` is the CI driver;
  a failed entry's bytes are what a build log cannot show. References cannot reach it at all.
- **The references were never as cheap as they looked.** The runner holds no snapshot between polls,
  a stream evicts inside a run, and the per-entry maps needed a stable key the `FixMessage` class
  does not actually provide (facts 3 and 5 above).

So the evidence goes to disk as it is produced, and **the tab is a viewer over the record**.

### The record

```
~/.fixtool/runs/
  2026-08-28T09-36-02-nightly/
    set.json                      the RunSet: label, source, policy, entries and their states
    01-smoke-nos.json
    02-book-a-trade.json
    03-cancel-replace.json        one file per entry, written the moment the entry lands
    …
```

```jsonc
// 03-cancel-replace.json
{
  "set": "2026-08-28T09-36-02-nightly", "entry": 3, "iteration": 1,
  "scenario": { "id": "…", "name": "cancel-replace" },
  "row": null, "lane": null,
  "startedAt": "2026-08-28T09:41:15.201Z", "durationMs": 3104,
  "result": { … },                           // exactly ScenarioReport.toJson(result)
  "messages": [                              // every message the run saw, in arrival order
    { "i": 0, "session": "QUOTE1", "dir": "out", "at": 1724838075201331,
      "raw": "8=FIX.4.49=…35=D…" },
    { "i": 1, "session": "QUOTE1", "dir": "in",  "at": 1724838075289740, "raw": "…" }
  ],
  "bound": { "step-3f1a": 3 },               // stepId → the message index that step judged
  "dropped": 0                               // messages the cap removed — reported, never silent
}
```

Three facts from the code make this a serialization of things already in hand, not a storage project:

1. **The report is already JSON.** `ScenarioReport.toJson` (`ScenarioReport.kt:18`) is the `result`
   block verbatim.
2. **Wire already round-trips.** `wireRaw` is on every captured message (`FixMessage.kt:75`), and
   `/scenarios/capture-paste` (`ControlServer.kt:1140`) already turns pasted wire back into
   `FixMessage`s the grid can show. The record's grid is that path, over the record's `messages`.
3. **Reconcile needs `stepId` and `wireRaw`, nothing else.** `rebindSlot` (`FixMessageViewModel.kt:2739`)
   takes the bytes off the map key; the record's `bound` index plus the message's `raw` is the same pair.

### The recorder

Messages are accumulated **while the entry runs, by a subscriber, not by the runner's polls**. A
recorder collects `FixMessageSession.messages` — a `StateFlow<List<AppMessage>>`
(`FixMessageSession.kt:79-80`) — for every session in the entry's `runSessions()`, from preflight to
the last teardown row, and unions by `uid`. The flow conflates, which is fine: the buffer evicts one
message per arrival, so the recorder loses nothing as long as it observes at least once per
`bufferSize` arrivals — once per thousand, against an ingest ceiling of about a thousand a second.
The runner does not change to make this happen; the host that owns the run does.

### The cap, and what it drops

A twelve-scenario suite of order flow is a few hundred messages; a repeat over a quote stream is not.
The cap is per entry, in messages, a setting beside the message buffer size on the Sessions page
(`AppSettings.runRecordCap`, default 5,000). It drops the right ones: **every message the report
references is kept** — bound messages, strays a STRICT verdict named — and the cap falls on the
unbound remainder, oldest first. An entry that lost anything says so on its grid header and in
`dropped`.

### Retention is a directory, not a tab

The runs directory keeps the most recent sets (`AppSettings.runRecordsKept`, default 20) and any set
the author has pinned. Closing a tab releases nothing on disk; opening **Recent runs ▸** from the Run ▾
menu reopens a set the app has since restarted out of. Headless writes the same directory (under
`--home` when given), so `fixtool run --set nightly` on a build box leaves the same records a click
would, and a CI job can attach them.

## The run set is a document, and the grid stays live

`ScenarioDoc` (`ScenarioDocuments.kt:73`) is the sealed list of what a centre-pane tab can be — an
editor or a capture review today. A run set is a third:

> **`ScenarioDoc.RunSet`** — a viewer over one record directory: entries down the left, and for the
> focused entry its steps, its verdict, its variables (and row, and lane), and **its own message grid
> re-parsed from the record**, tinted exactly as the live grid would have tinted it.

*(Revised: the first draft cited the rail's "everything opens a document tab, never a window" rule.
That rule is stale — reconcile has been a top-level window since Phase 6 (`FixMessageViewModel.kt:417`)
— so the design does not lean on it; a run set is a document because it is a thing to read, not because
of a rule.)*

Two surfaces, two jobs, neither lying:

| | shows | tint |
|---|---|---|
| **Session grid** | now — live traffic, still arriving | the last entry that ran on that session, for the messages still in it |
| **Run set tab** | any entry, from its record | that entry's own `bound` map |

The record's grid is `HierarchicalGridView` (`HierarchicalGridView.kt:234, 244`) — it already takes a
`List<AppMessage>` and its own tint map as parameters, so nothing about it knows whether the list came
from a session or a file. Its header says what it is:
`cancel-replace · entry 3 of 12 · 5 messages · 03-cancel-replace.json`. Rows expand, the detail panel
opens, tag search works, because the messages are real objects parsed from real bytes.

### The set publishes nothing global until an entry is focused

`runScenarioBlocking` publishes every run's result to `_scenarioResult` and rebinds every open diff
window on the way out (`FixMessageViewModel.kt:2700-2703, 2726-2734`). A set of twenty would re-aim
the author's open reconcile window twenty times while they were editing in it. So a set entry runs
through the slot-free `runOne` (Decision 1) and writes its record; **focusing** an entry — in the rail
or in the tab — is what publishes its result and rebinds the window, exactly as a single run does today.
The live grid's tint is the exception: it follows the last entry that ran on that session, because
that is what the grid is showing.

### Reconcile, from an entry that ran an hour ago

It works the way it works today, with today's gates stated rather than assumed. `reconcileRoute`
(`FixMessageViewModel.kt:1718-1801`) needs the message with its `wireRaw`, the run attributed to a
scenario, the scenario still on disk with that step in it, and **the step unchanged since the run**
(`:1785`). A record supplies the first two; the last two are checked against the saved file when the
entry is focused, and an entry whose scenario has been edited since says so:
*"cancel-replace has changed since this entry ran — re-run to reconcile."* That is the right refusal;
reconciling bytes against an expectation that no longer exists would be a lie.

Two smaller things review found, fixed here: `reconcileRoute` finds the message by `phase+stepIndex`
(`:1745`) while `rebindSlot` uses `stepId` (`:2739`) — one key, `stepId`, for both; and the diff
window is per scenario (`diff:$scenarioId`), so with twenty iterations of one scenario in a set the
window binds to the **focused** entry and re-aims when the focus moves, never to "the latest".

**Save & re-run** from there re-runs *that scenario alone*, as a new set of one: the old set is a
record, and records do not mutate.

## Examples — the same run set, from a table

A scenario is **already a Scenario Outline**. Every step is parameterized, every `${…}` resolves against
one variable scope the runner threads through the whole run, and that scope already covers both
directions: a Send puts `${symbol}` on the wire, an Expect's `reference` matcher asserts `${symbol}` came
back, and an Expect's `bindAs` writes the venue's own choice *into* the scope for later steps to use.

What is missing is the table, one runner parameter, and *(revised)* two places that must learn about it.

### The seed

```kotlin
// ScenarioRunner.runIdentified, today (ScenarioRunner.kt:120, :128)
private fun runIdentified(scenario: Scenario): ScenarioResult
    val scope = mutableMapOf<String, String>()

// with a seed
private fun runIdentified(scenario: Scenario, seed: Map<String, String> = emptyMap()): ScenarioResult
    val scope = seed.mapValues { host.resolve(it.value, mutableMapOf(), null) }.toMutableMap()
```

Everything downstream already reads that map: `host.resolve` for a Send's raw, `resolveMatch`
(`ScenarioRunner.kt:1130`) for a bind predicate's values, and `referenceResolver` for a `reference`
matcher's expression. A row's cells are resolved once as they are seeded, so a cell may itself say
`${uuid}` or `${LocalDate.now()}` and give each row its own fresh id.

**The two places that must learn about the seed** *(revised)*: `ScenarioResult.variables` records
provenance per name as the step that minted it (`mintedBy`, `:145-148, 216`) — a seeded name would be
credited to whichever step ran first, so `ScenarioVariable` gains `source = ROW | LANE | STEP`. And
`ScenarioAnnotations.sites()` / `unminted()` (`ScenarioAnnotations.kt:51, 92-95`) are regex passes
over the step list — they would flag every column as never minted — so they take the column names as
a second input. Neither is large; both are the difference between a lint that helps and one that cries
wolf on every outline.

**Precedence is assignment.** A Send that mints a name the table also supplies — `11=${clOrdID = uuid}`
over a `clOrdID` column — overwrites the cell, because that is what `${name = expr}` means everywhere
else. The editor says so on the column header rather than letting a run discover it.

### The table

```kotlin
/** Additive on the Scenario, default-omitting on disk — the same bargain as `traffic` and `createdAt`. */
data class Examples(
    val columns: List<String>,        // variable names, seeded into the scope before setup runs
    val rows: List<ExampleRow>,
)

data class ExampleRow(
    /** "EUR/USD partial fill" — what the report says instead of "row 3". */
    val name: String,
    val values: Map<String, String>,
    /** Parked, not deleted — the same bargain as `ScenarioStep.muted`. */
    val muted: Boolean = false,
)
```

Which row an entry ran is the **entry's** business, exactly as which iteration it was is:
`RunEntry.row`. So the report, the record, the reconcile route and the JUnit renderer all keep working,
and a CI testcase names itself the way a parameterized test always has: `book-a-trade [EUR/USD partial fill]`.

### Inbound and outbound, without a new concept

The distinction is real and the tool already computes it. `sites()` returns, per variable, the steps
that **mint** it (a Send chose the value and put it on the wire), the steps that **capture** it (an
Expect's `bindAs` read it off a reply), and the steps that **reference** it (`ScenarioAnnotations.kt:34-41`).
So an examples column needs no inbound/outbound flag of its own — the editor derives it and shows it in
the column header:

- **↑ out** — read by a Send. The row is *driving* the flow.
- **↓ in** — read by an expectation. The row is *asserting* the reply.
- **↑↓** — both, which is the ordinary case for an id echoed back.

A column the scenario never reads, and a `${name}` that no column supplies and no step mints, are both
lints. Neither is an error — they are what a half-finished outline looks like — but neither should be
discovered by watching a run fail.

### Getting from a capture to an outline

Nobody hand-writes the first table. A captured scenario has literals baked into its sends and
expectations, so the door is **"Extract to example column…"** on any value in the editor: the literal
becomes `${symbol}`, a column appears named through `mintName` (the dictionary's field name, so it reads
`symbol` and `clOrdID` rather than `col1`), and the value it replaced becomes row 1's cell. Capture a
flow once, extract three values, add rows.

### The one thing that must not be allowed

**Reconcile must never write a row's literal into a shared expectation.** Row 3 fails, the author clicks
*Accept actual*, and the actual carries `55=GBP/USD` — writing that literal repairs row 3 and breaks the
other seven, silently, because the expectation belongs to all of them.

The fix is where the problem is: the run reports its final scope (`ScenarioResult.variables`), the diff
window shows it as the variables strip, and the gutter already offers `$ TRACK` where an actual equals a
variable's value. On an examples entry that offer becomes the **default** rather than an alternative —
but *(revised)* keyed by the right thing. The mockup's own table has a row where `qty` and
`expectCumQty` are both `17`; an actual `14=17` equals two cells, and matching by value alone would
offer the wrong column. So the repair is chosen by **the column the failing expectation referenced**
(`${expectCumQty}` on tag 14), and value equality is the fallback for an expectation that referenced
nothing. The literal stays available as the deliberate alternative.

### Together, or separate?

**One run set, two pieces of work.** Examples are a third *source of entries* — a repeat produces N
identical ones, a suite produces N different ones, a table produces N seeded ones — and past that point
every surface is shared: the scheduler, the set report, the record, the tab, the reconcile route, the
JUnit wrapper. Building a separate "data-driven runner" would mean a second run report that disagrees
with the first about what a failure looks like.

The **authoring** is genuinely separate work — the table on the model, the editor grid, the extract door,
the lints — and none of it blocks the suite. So: shared model, its own phase, and **ahead of fan-out**,
because a QA team gets more from eight instruments through one flow than from fifty sessions through one.

## Decisions

### Decision 1 — The set holds the run slot; entries do not re-claim it

`runScenarioBlocking` splits into `claimRunSlot { … }` and a slot-free `runOne`. A set claims once.
Consequence, and it is the right one: a bare `POST /scenarios/run` answers *"a scenario run is already
in progress"* — with a 409, not a 200 — for the whole duration of a batch instead of interleaving with
it.

### Decision 2 — A set isolates its entries by default, and says what that does not reset

Under the default `BindScope.ANY`, an `expect` may bind a message that arrived before the run started
(`Scenario.kt:49-63`) — so iteration 2 can bind iteration 1's `ExecutionReport` and report that the
venue answered when it has not. On a single run that is a caveat the report already prints
(`staleBindCheck`, `ScenarioRunner.kt:1068`). On a twenty-times repeat it is a false green *by
construction*, and a feature whose purpose is catching flakiness must not manufacture passes.

So a **set** runs its entries under `BindScope.THIS_RUN` — an in-memory `scenario.copy(binding =
THIS_RUN)`, never a file write; there is no other override path today and none is needed — with a
checkbox in the run dialog ("Isolate each iteration") and a line on the set header saying it is on.
The reconcile gate compares steps, not binding (`FixMessageViewModel.kt:1785`), so the override does
not break reconcile.

*(Revised)* The dialog also says what isolation is **not**: it does not clear messages, and it does not
clear the venue's order book. Those are the scenario's own setup steps — `ClearMessages` and
`ClearOrderBook` — and a capture writes both. The alternative (warn and proceed on `ANY`) was rejected:
the warning would appear on exactly the runs nobody reads, the green ones.

### Decision 3 — Continue on failure by default; stop-on-first is a switch

A suite exists to produce the whole morning's picture, and a flake hunt wants "3 of 20 failed", not
"failed at 4". CI gates want the opposite, so `--stop-on-failure` / `stopOnFailure: true` exists — but
it is not the default anywhere.

### Decision 4 — Selection is a saved set; favourites and the filter are ways to make one *(revised)*

No new taxonomy on the scenario. A run set is built from **★ Favourites** (`ScenarioViewState.favouriteIds`),
from the **current filter text**, or from an explicit **multi-select** — and any of those can be
**saved as a set**:

```
~/.fixtool/sets/nightly.json
{ "name": "nightly",
  "entries": [ { "scenario": "smoke-nos" }, { "scenario": "book-a-trade", "repeat": 3 },
               { "scenario": "cancel-replace", "rows": ["EUR/USD partial"] } ],
  "policy": { "stopOnFirstFailure": false, "pauseBetweenMs": 0 } }
```

A saved set is a file, like a scenario, a profile and the view state; it appears in the Run ▾ menu by
name; and it is the thing CI selects — `fixtool run --set nightly` — because CI selects by a name in a
checkout, not by a local star file. The first draft proposed waking `Scenario.userTags` for a `--tag`
selector. Review found it is not asleep: it is the per-profile scenario filter
(`ScenarioService.kt:60`) and part of the `GET /scenarios` payload, so a `nightly` tag would hide the
scenario from every profile-filtered listing. It stays as it is.

### Decision 5 — Fan-out reuses preflight, and refuses when the profile cannot supply lanes

Sessions are reused when up and created from their saved profile when not — the rule preflight already
applies. Concurrency > 1 is permitted only when every lane's resolved sessions are disjoint from every
other's. See [Fan-out](#fan-out-and-where-its-sessions-come-from), which is the whole argument.

### Decision 6 — The record on disk is the artifact; the tab is a viewer *(new)*

Every entry is written as it lands, by GUI and headless runs alike. Memory holds the set's *state*
(entries, progress, the focused entry's parsed messages), never the evidence. The argument is in
[The run record](#the-run-record--where-the-evidence-lives-revised).

### Decision 7 — A set is a job on the control surface *(new)*

Started, polled, fetched, stoppable; the bare-`id` call is byte-compatible with today. See
[HTTP / MCP](#http--mcp).

### Decision 8 — Timing is per step, recorded by the runner *(new)*

`StepResult.latencyMs` is the venue's number; `ScenarioResult.durationMs` is the flow's. The fan-out
distribution is over the first; the repeat strip shows both.

## Fan-out and where its sessions come from

**It is the same feature, and the substrate is already built.** `FixConnectionConfig.sessionCount`
(`FixConnectionConfig.kt:12`; initiators only, the 1–100 bound lives in the form) already opens N
sessions from one profile on a single Connect:

- **Identity per slot** is `SessionIdentityResolver` — a `{n}` / `{nn}` numbering pattern or a
  comma-separated list across SenderCompID, TargetCompID, Username and Password
  (`SessionIdentityResolver.kt:8-15`), so slot 7 of `LOADGEN{nn}` logs on as `LOADGEN07`. Where two
  slots would resolve to the same sender/target pair it derives a distinct `SessionQualifier` per slot
  (`:29-37`), which is what keeps QuickFIX session ids and message store files from colliding. That is
  the genuinely hard part of running fifty clients, and it is done.
- **Titles** are `"<profile> [<slot>]"` (`FixMessageViewModel.kt:3495`), and each session carries its
  `profileSlot`.
- **Steps target sessions by title**, and `Scenario.withSessions` remaps by title (`Scenario.kt:252`).
  So one lane is literally `withSessions(mapOf("QUOTE1" to "LoadGen [7]"))`.

So the identity half of fan-out is done, and the connection half is done too — see below. What is left
is **assignment**: which lane gets which session, and what the lane is called.

### Fan-out reuses preflight, and adds exactly one question

`preflight` (`ScenarioRunner.kt:222`) already resolves a session this way, per run:

1. If it is there and ready — **use it**, touch nothing (`:261`).
2. If it is down, or does not exist at all — **one recovery attempt** (`:262-272`):
   `connectSession` reconnects it through its own profile, or, for a session that does not exist, the
   host strips the `[n]` slot suffix (`ViewModelScenarioHost.kt:110`, `:157`) and connects the
   *profile carrying its name* — which creates every slot.
3. Every attempt is started before any wait, so slow logons overlap under one `connectTimeoutMs`
   deadline (`:278-284`); each success becomes a passing `connect` row in the report (`:294-301`);
   each failure is a named, actionable refusal.

And `connectProfile` (`FixMessageViewModel.kt:3417-3441`) is already **per-slot** reuse-or-create:
`reconnectExistingSessions` revives the group's sessions that are down, and `createMissingSessions`
computes the free slots and fills only those (`:3476-3512`). Connect a 50-session profile that already
has 30 sessions up and you get the missing 20 — a top-up, not a duplicate set.

That is precisely the rule fan-out wants, so **fan-out does not get its own connection logic**. It calls
the same preflight, on the lane group's base name, once. The run must never invent sessions *outside a
saved profile*, because a session is a visible object — identity, connection state, message log, a
pane in the grid. A lane conjured outside a profile would be one the author cannot see, inspect, tint
or disconnect.

### The one new question: can this profile make more than one session?

Everything fan-out needs beyond preflight is a **capability check**, and it is one field:

```kotlin
val profile = viewModel.profileForSession(session)      // FixMessageViewModel.kt:2126
    ?: profileNamed(session.replace(SLOT_SUFFIX, ""))    // the host's own regex, made shareable
val lanes = when {
    profile == null -> Unavailable("no saved profile behind '$session'")
    profile.config.connectionType != INITIATOR -> Unavailable.acceptor(profile)
    profile.config.sessionCount <= 1 -> Unavailable.singleSession(profile)
    else -> Available(profile, profile.config.sessionCount)
}
```

- **`sessionCount > 1`** — the group is the lane pool. Preflight brings up whatever is missing, and the
  lanes are the group's `LOGGED_ON` sessions, **ordered by `profileSlot`** *(revised — `getProfileSessions`
  returns append order, and a refilled slot goes last, `FixMessageViewModel.kt:3628-3636`)*.
- **`sessionCount == 1`** — fan-out is **unavailable**, and the refusal names the fix in the field's own
  words:

  > Fan-out needs a profile that opens more than one session, and **LoadGen** opens 1. Set **Sessions**
  > on the profile to the number of lanes you want — with a `{nn}` pattern in SenderCompID, so each lane
  > logs on as its own identity — then reconnect. 50 sessions are already connected under other
  > profiles: pick one of those instead.

- **An acceptor profile** — always one session by construction (`connectProfile` forces `targetCount = 1`
  for anything that is not an initiator, `:3418`). So an acceptor leg can never be a lane *source*, and
  the refusal says that rather than pointing at a Sessions field that would not help.

The **Fan out over sessions…** item in the Run ▾ menu is therefore **disabled with its reason showing**,
not hidden: the author cannot tell "this cannot be done, and here is why" from "this feature does not
exist" if it is withheld.

### How "which sessions" is answered today — and the symmetry to keep

Four places already choose sessions, and they do not all choose the same way:

| Today | Chooses | Filter |
|---|---|---|
| **Bulk Send** — `sendMessageToAllConnectedSessions` (`:3327`) | every session | `LOGGED_ON` only |
| **Capture** — `captureScenarioFromSessions` (`:3925`) | every session, or the titles named | **none** — a disconnected session is captured too |
| **Preflight** — `connectSession` → `connectProfile` | one, and the group behind it | reuse if ready, create the missing slots if not |
| **A scenario step** — `resolveSession` (`ViewModelScenarioHost.kt:146`) | one, by title or index | none; **null takes the first session in the list** |

Three things follow for fan-out, and the third is where the first draft's safety rule had a hole:

1. **Lanes are `LOGGED_ON`, like Bulk Send's targets.** Capture's looser rule is defensible for capture —
   it reads history, which a disconnected session still has — but a lane that is not logged on is not a
   lane.
2. **A shortfall is reported, not refused.** Preflight tops the group up and waits; if the venue lets only
   38 of 50 on, the set runs 38 and says so on its header — *"38 lanes: 12 of LoadGen's sessions did not
   reach LOGGED_ON."*
3. **`null` means *this lane's session*, and disjointness is checked after that is resolved** *(revised)*.
   `ScenarioStep`'s doc says *"null = the active session"* (`Scenario.kt:93`); `resolveSession` returns
   `list.firstOrNull()`; and `runSessions()` returns `null` as its own element beside the named ones
   (`ScenarioRunner.kt:1085`). So two lanes whose *named* sessions are disjoint can both clear and read
   session 0 through a null-session step — and every captured scenario on an unnamed session has one
   (`ClearMessages(null)`, `ScenarioCapture.kt:392`). `withSessions` cannot rescue it: it short-circuits
   on null (`Scenario.kt:256`). So the fix is not a remap. Each lane's host carries a **default
   session** — `ViewModelScenarioHost(defaultSession = lane.sessionTitle)` — that null resolves to, and
   the disjointness licence is computed over the sessions the steps will *actually* touch, after
   resolution. Outside a fan-out the KDoc and the code still have to be reconciled with each other;
   "the active session" is a name the code has never implemented.

### A lane already knows what to call itself

Bulk Send seeds four names into the template scope for each target it sends to
(`sessionTemplateVariables`, `FixMessageViewModel.kt:3312-3318`): `sessionIndex`, `sessionQualifier`,
`sessionTitle` and `sessionSenderCompID` — so `262=MD-${sessionIndex}` gives each session a distinct
MDReqID with no authoring ceremony. **A fan-out lane seeds exactly those four names**, into exactly the
scope Examples seeds a row into — with one correction *(revised)*: Bulk Send's `sessionIndex` is the
target's position in the logged-on list (`:3336-3341`), which changes when a session drops. A lane's
`sessionIndex` is its **`profileSlot`**, so lane 7 is `LOADGEN07` on every run and in every record.

That is where the readings stop being separate features. A lane is a run whose scope carries its
session's identity; a row is a run whose scope carries the table's values; an iteration is a run whose
scope carries neither. One seeding mechanism, one `seed` argument, and a scenario saying
`11=ORD-${sessionIndex}-${clOrdSuffix}` draws on both without knowing they came from different places.

### The second leg, and where disjointness still bites

A one-leg scenario needs nothing else: lanes spread over the group, and that is the dialog.

A scenario with a **second** session is where a choice appears, because the second leg may not have a
multi-session profile behind it — fifty client lanes against one shared back-office session. That
pinning is what breaks the licence for running lanes concurrently: fifty lanes sharing `TRADE1` share its
message log and its consumed cursor, so lane 12 can bind the reply to lane 30's order and the report
becomes indistinguishable from a venue bug. So a pinned session under `concurrency > 1` is refused, with
the same shape of remedy:

> `TRADE1` is pinned to one session, and 50 lanes would share its message log — lane 12 could bind
> lane 30's reply. Give `TRADE1` a multi-session profile of its own, fan out only the `QUOTE1` leg, or
> set concurrency to 1.

One relaxation is legitimate and deliberately out of the first cut: a pinned session the scenario only
*sends* on shares no consumed cursor, because only `Expect` consumes. It still shares the stray scan
under `TrafficMode.STRICT`, so it needs its own thinking rather than a quiet exception.

### The far end is addressable — it is not free *(revised)*

If the far end is FixTool in acceptor mode, `attachVenueClient` already opens a pane per connecting
client, titled `"<profile> ← <client CompID>"` (`FixMessageViewModel.kt:3563`), with its own order book
(`QuickFixService.kt:687-701`). Fifty `LOADGEN{nn}` initiators against a FixTool venue therefore produce
fifty named panes and fifty books on the other side, and both ends of a lane are addressable by name.

What is *not* free is the venue's throughput. All fifty sessions of one acceptor share QuickFIX/J's
single event-handling thread, and every reply — including a zero-delay one — goes through
`AcceptorDispatch`'s one scheduled thread (`AcceptorDispatch.kt:42-47`). Under fifty concurrent lanes
an authored `delayMillis` becomes a floor, not a value, and the p95 a lane measures against FixTool's
own venue is FixTool's. The fan-out dialog says so when the target profile is a FixTool acceptor pane;
making the venue side a load *target* rather than a convenient far end is acceptor work, and it is out
of scope here.

### Headless lanes *(revised)*

`HeadlessScenarioHost.connectSession` creates one session per profile name and ignores `sessionCount`
(`HeadlessScenarioHost.kt:99-131`), and every headless process shares the default QuickFIX file store
(`FixConnectionConfig.kt:29-30`). Fan-out is GUI-first; a headless `--fan-out` is listed under Phase 4
as its own item, needing the host to honour `sessionCount` the way `connectProfile` does.

### Evidence at fifty lanes

Keeping every lane's record whole is the wrong default at this scale, and saying so is part of the
design. A fan-out set writes every lane's record as it lands — it cannot know the verdict before it has
it — and then, **after the set completes**, trims:

- **failed lanes** keep their records whole, because they are what the run is for;
- **the first passing lane** is kept entire as a reference specimen;
- **the rest** keep `result`, timing and counts, and their `messages` array is emptied with `dropped`
  set — the report says so.

### The report is a distribution, not fifty rows

Nobody clicks through fifty lanes. The set report for a fan-out is the repeat strip's shape — one tick
per lane, click to focus — over a summary that answers the question a load test actually asks, and
*(revised)* over the right number: the per-step latency of the flow's Send→Expect pairs, not the lane's
wall-clock.

```
LoadGen ×50 lanes          48 ✓   2 ✗        concurrency 50
▪▪▪▪▪▪▪▪▪▪▪▪▪▫▪▪▪▪▪▪▪▪▪▪▪▪▪▪▪▪▪▪▪▪▪▪▪▪▪▪▪▪▪▪▫▪▪▪▪▪
35=D → 35=8   p50 214ms · p95 1.9s · max 8.4s     failures: lane 14, lane 45
lane wall-clock   median 1.1s · slowest lane 45 · 8.4s
evidence: 2 failed lanes kept whole · lane 1 kept as reference · 47 trimmed to counts
measured at the client · far end is QuoteVenue (FixTool acceptor, one reply thread)
```

"Did all fifty pass" is the weaker question. *What did the ninety-fifth percentile cost* is the one a
venue is being asked, and it has to be measured between the bytes that left and the bytes that answered.

## Surfaces

### The rail

The header's controls row gains a **Run ▾** control; the per-row hover Run button is unchanged (it is
a set of one). `RunStatusLine` — which sits **above** the list today (`ScenariosRail.kt:141`), under the
same bounded-height rule (`:1010-1016`) — becomes the set report while a set is running or focused:
progress, counts, elapsed, a compact click-to-focus entry list, and today's report for the focused entry.

```
┌ Scenarios ─────────────────── + ⋮ ┐     ┌ Run ▾ ───────────────────────┐
│ Run ▾   ⌕ filter…                 │     │ Run set ▸  nightly     (12)  │
├───────────────────────────────────┤     │            smoke        (4)  │
│ ▸ Running nightly       7/12  ⏹   │     │ Run ★ favourites       (12)  │
│   ✗ 2 failed · 4m12s elapsed      │     │ Run filtered            (4)  │
│   ✓ smoke-nos              1.2s   │     │ Run selected…                │
│   ✓ book-a-trade           4.8s   │     │ Save as set…                 │
│   ✗ cancel-replace   2/4   3.1s ▸ │     │ ──────────────────────────── │
│   ⟳ quote-stream         running  │     │ Repeat this scenario ×N…     │
│   · allocation           pending  │     │ Run examples table      (8)  │
├───────────────────────────────────┤     │ Fan out over sessions…       │
│ Last run — cancel-replace: FAILED │     │ ──────────────────────────── │
│ First failure: step 3 expect …    │     │ Recent runs ▸                │
│ Reconcile step 3 →                │     └──────────────────────────────┘
└───────────────────────────────────┘
```

A repeat set collapses to one row per scenario with an iteration strip, because twenty rows of the
same name is not a report:

```
│ ▾ book-a-trade ×20      17/20 ✓   │
│   ▪▪▪▪▪▫▪▪▪▪▪▪▪▪▫▪▪▪▫▪    3 failed │   ← each ▪ click-to-focus; ▫ = failed
│   failures: #6, #15, #19          │
│   flow  median 1.9s · slowest #15 8.4s      │
│   35=D→8  p50 180ms · p95 1.7s              │
```

Median and slowest are the point of a repeat: a flow that passes twenty times but drifts from 1.9s to
8.4s has told you something no pass/fail can — and the step line under it says whether the drift is
the venue or the flow.

### HTTP / MCP

`POST /scenarios/run` given `id` or `scenario` alone returns exactly the object it returns today —
synchronous, unchanged. Given `set`, `ids`, `repeat`, `rows` or `concurrency`, it **starts a job** and
returns at once:

```jsonc
POST /scenarios/run
{ "set": "nightly" }                                          // a saved set
{ "ids": ["smoke-nos", "book-a-trade"], "repeat": 3 }         // an ad-hoc one
{ "id": "book-a-trade", "rows": ["EUR/USD partial fill"] }    // one row, for a debug run

202 { "runSet": "2026-08-28T09-36-02-nightly", "status": "running", "entries": 12 }
409 { "status": "error", "error": "a scenario run is already in progress" }

GET  /scenarios/runs/2026-08-28T09-36-02-nightly              // state now; ?wait=10000 long-polls
{ "status": "running",                                        // running | passed | failed | stopped
  "summary": { "total": 12, "done": 7, "passed": 5, "failed": 2, "elapsedMs": 252000 },
  "entries": [ { "n": 3, "scenario": "cancel-replace", "iteration": 1, "state": "FAILED",
                 "durationMs": 3104, "record": "03-cancel-replace.json" }, … ] }

GET  /scenarios/runs/<id>/entries/3                           // the record: today's report + messages
POST /scenarios/runs/<id>/stop
GET  /scenarios/runs                                          // recent sets, newest first
```

Every `record` is the report shape an agent already knows how to read and reconcile from, plus the
bytes. `fixtool_run_scenario` keeps its parameters (and finally declares `sessions`, which the route
has accepted all along, `ControlServer.kt:1416`); `fixtool_run_set`, `fixtool_run_status` (with `wait`,
kept under the shim's ceiling) and `fixtool_run_entry` are the new tools. An agent asking "run the
suite and tell me what broke" is start, wait, read the failed entries.

### CLI

`HeadlessRun`'s own header calls the batch sweep *"the strongest driver"* and then supports one
scenario per process (`HeadlessRun.kt:30`). This closes it:

```bash
fixtool run --set nightly --junit reports/          # a saved set, one file per entry
fixtool run --all --stop-on-failure                 # every saved scenario
fixtool run book-a-trade --repeat 20 --pause 500ms
fixtool run book-a-trade --row "EUR/USD partial fill"   # one row of the table
echo $?    # 0 all passed · 1 something failed · 2 could not run
```

`ScenarioReport.toJUnitXml` gains a list overload emitting a `<testsuites>` wrapper of the suites it
already renders, with `time=` throughout; an iteration is `name="book-a-trade #3"`, a row is
`name="book-a-trade [EUR/USD partial fill]"`. `--junit <dir>` writes one file per entry;
`--junit <file.xml>` writes the wrapper. Records go to `--home`'s runs directory either way.

## Phasing

| Phase | Content | Touches |
|---|---|---|
| **0 — Bugs and primitives** | The watermark fix + a seeded-log test; per-step `latencyMs` and `durationMs` (+ JUnit `time=`); the JUnit teardown/verdict disagreement and `step -1` naming; the runner's cancel token; `ScenarioStep.ClearOrderBook` (+ capture authoring it for venue panes) | Runner, report, capture. No file format change beyond the additive step kind. |
| **1 — Repeat + Suite, sequential** | `RunSet`/`RunEntry`/`RunPolicy`/`RunSource`, the set-owned run slot, **the run record written per entry**, saved sets, rail Run ▾ + set report + Recent runs, the job API and its MCP tools, CLI `--set` / `--all` / `--repeat`, `<testsuites>` | ViewModel, rail, control, headless, two settings. |
| **2 — The run set document** | `ScenarioDoc.RunSet` as a viewer over a record directory; the record's grid via the capture-paste parser; focus-publishes; reconcile from an entry with the gates stated; one lookup key (`stepId`) | ViewModel, documents, reconcile. |
| **3 — Examples** | `Scenario.examples`, the `seed` parameter, `ScenarioVariable.source`, `sites()`/`unminted()` over columns, the editor's table + "Extract to example column…", column-keyed row-aware reconcile | Model, codec, runner, annotations, editor, reconcile. |
| **4 — Fan-out** | Run slot becomes a per-session-set claim under a post-resolution disjointness check; `concurrency`; per-lane default session; lanes by `profileSlot`; the spread/pinned lane dialog; the step-latency distribution; post-completion trimming; the acceptor-ceiling notice; *then* headless `--fan-out` | ViewModel, runner host, headless host, rail dialog. No change to profiles or `SessionIdentityResolver`. |

Phase 0 is small and every item in it is a defect or a missing measurement today. Phase 1 makes a set
run **and leaves a record**; Phase 2 makes the record readable in the app — and a suite is not much use
without it, so the two ship together or the feature is half a feature. Phase 3 is where the model starts
paying for itself. Phase 4 is a load-testing feature that happens to share the model, and should be
judged on its own merits when it is wanted — including the question of whether the venue side is meant
to be measured or merely present.

## Risks

- **A batch that hides a venue's state drift.** Twenty green iterations against a venue that is
  quietly accumulating open orders is not the same evidence as twenty against a clean one. The set
  report shows per-iteration duration and step latency precisely so drift is visible before it is a
  failure; against FixTool's own venue, `ClearOrderBook` in setup is the reset, and the dialog says
  when it is absent.
- **Wall-clock.** A STRICT scenario pays `settleMs` (1 s) per green run; a twenty-times repeat pays
  twenty seconds of it. Worth saying in the run dialog, not worth optimising away — the settle window
  is the claim.
- **A set report that buries the list.** The rail has been here before (`scenario-rail-phase3.md`);
  the set report inherits the same rule — bounded height, internal scroll. The set's *detail* is the
  document tab, which is exactly why the rail does not have to grow to hold it.
- **Two grids that could be confused for each other.** A record's grid that looked identical to the
  live one would be worse than none — an author reading an hour-old failure as current traffic. Hence
  the header that names the entry, the file and the time, and hence the live grid keeping its own tint
  rather than being borrowed by whatever the author last clicked.
- **A runs directory that grows.** Twenty sets of twelve entries of five thousand messages is real
  disk. The retention count and the per-entry cap are both settings, both visible, and a set's size is
  on its row in Recent runs.
