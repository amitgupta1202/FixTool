# Multi-run scenarios — one run set, four ways in

**Status: proposal.** Nothing here is implemented. It answers four questions that arrived as one
("can we multi-run a scenario?"), argues they are the same feature, and specifies the smallest model
that serves all four without a second reporting stack.

**Mockups:** [`docs/mockups/multi-run-scenarios.html`](mockups/multi-run-scenarios.html) — interactive
(click entries, iterations, lanes and rows to see the report and frozen grid respond); download and open
locally in a browser, since GitHub does not execute a `.html` file's script from its file view. Built
against FixTool's own dark palette, so the mockups read as the app's own screens rather than a separate
design pass.

## The question, disambiguated

"Multi-run" is three different needs wearing one phrase, and they want different things from the tool:

| Reading | The question it answers | Shape |
|---|---|---|
| **Repeat** | *Is this flow flaky?* Run `book-a-trade` twenty times and tell me it passed twenty times. | one scenario × N iterations, sequential |
| **Suite** | *What broke overnight?* Run the twelve scenarios I care about and give me one verdict. | N scenarios × 1, sequential |
| **Examples** | *Does it hold for every instrument?* Run this flow once per row of a table — Cucumber's Scenario Outline. | one scenario × N parameter rows, sequential |
| **Fan-out** | *Does it hold with fifty clients?* Run the same flow on fifty sessions at once. | one scenario × N session maps, concurrent |

All four are possible. Three of them (**Repeat**, **Suite**, **Examples**) are close to free — the
runner is already pure, deterministic, and re-entrant across runs, and the only thing standing between
it and a `for` loop is what the *UI and the report* do with more than one verdict; Examples costs one
extra line once the run set exists (see below). **Fan-out** is the one genuine concurrency change, and
is phased last — though its sessions turn out to need no new connection logic either, only an
assignment step over infrastructure the app already has (see
[Fan-out and where its sessions come from](#fan-out-and-where-its-sessions-come-from)).

## The model: a run set

The three readings collapse to one primitive, and that is the whole proposal:

> A **run set** is an ordered list of run requests — `(scenario, sessionMap, iteration)` — executed by
> a scheduler, producing a list of `ScenarioResult`s.

- **Repeat** is one scenario × N iterations.
- **Suite** is N scenarios × one iteration.
- **Examples** is one scenario × N parameter rows.
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
    val row: ExampleRow? = null,    // the parameter row this entry runs, when it has one
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

## Where the evidence lives — and why it is not the grid

**This is the question a suite actually asks**, and the first sketch of this design got it wrong. It
said: focusing an entry republishes its result and *retints the live grid*. That works for a set of
one and for nothing else. Entry 2's setup calls `ClearMessages` (capture authors one per session,
`ScenarioCapture.kt:392`), so the moment the second scenario starts, the first one's messages are gone
from the grid — and even without a clear, `FixMessageSession` is a ring buffer that evicts at
`bufferSize` (`FixMessageSession.kt:313`). By the time a twelve-scenario suite lands, the grid holds
the last entry's traffic and nothing else. Eleven reports would point at messages that are not there.

So the evidence does not stay in the grid. **It travels with the entry.**

### It is already retained; nothing knows it yet

Three facts from the code make this cheap rather than a storage project:

1. **`clearMessages()` does not destroy messages.** It empties the session's deque and publishes an
   empty snapshot (`FixMessageSession.kt:399`). A `FixMessage` referenced from anywhere else survives
   the clear intact — `wireRaw`, `quickfixMessage`, timestamp and all.
2. **The published snapshot is already immutable.** `_messages.value = retained.toList()`. Holding a
   run's messages is holding *references*, not copying bytes.
3. **The reconcile route never reads the grid.** `rebindSlot` (`FixMessageViewModel.kt:2726`) finds the
   failing step's message by scanning `_assertionResults` for its `stepId` and takes `wireRaw` off the
   key. The one reason reconcile dies after the next run is that `runScenarioBlocking` wipes that map
   on the way in (`setAssertionResults(emptyMap())`).

The whole fix is therefore: **stop wiping the map, and start keeping one per entry.**

```kotlin
data class RunEntry(
    …,
    /** Every message this run saw, accumulated as it ran. References, not copies. */
    val evidence: List<FixMessage> = emptyList(),
    /** What today is ONE global map, wiped by the next run. Per entry, it is the entry's tint. */
    val assertions: Map<FixMessage, StepResult> = emptyMap(),
    val truncated: Int = 0,   // messages dropped by the cap, reported and never silent
)
```

Accumulated **during** the run, not swept at the end: a sweep after the last step loses whatever the
ring buffer evicted mid-run, and on a streaming session that is most of it. The runner already takes
a snapshot of each session on every poll cycle, so the accumulation is an identity-set union over a
list it is holding anyway.

### The run set is a document, and the grid stays live

The rail's rule is that everything clickable opens a **document tab, never a window**
(`ScenariosRail.kt`), and `ScenarioDoc` is the sealed list of what a tab can be. A run set is one:

> **`ScenarioDoc.RunSet`** — entries down the left, and for the focused entry: its steps, its verdict,
> its variables, and **its own frozen message grid**, tinted exactly as the live grid would have
> tinted it.

That settles the ambiguity the first sketch created. Two surfaces, two jobs, neither lying:

| | shows | tint |
|---|---|---|
| **Session grid** | now — live traffic, still arriving | the last entry that ran on that session, for the messages still in it |
| **Run set tab** | any entry, from its own evidence | that entry's own assertion map |

The frozen grid is the same composable the live grid uses, over a list rather than a `StateFlow`, with
a header that says what it is: `cancel-replace · entry 3 of 12 · 14 messages · frozen 09:41:18`. It is
not a screenshot — rows expand, the detail panel opens, tag search works, because the messages are the
real objects.

### Reconcile, from an entry that ran an hour ago

It works, and it works the way it works today. `rebindSlot` needs a `stepId` and a `FixMessage` with
`wireRaw`; the entry's own assertion map has both. So "Reconcile step 3 →" on entry 3 of 12 opens the
diff on the bytes entry 3 actually judged, long after entries 4–12 have cleared the sessions twice
over. **Save & re-run** from there re-runs *that scenario alone*, as a new set of one: the old set is a
record, and records do not mutate.

The one thing that genuinely cannot be honoured is a live re-bind: the diff's "received — this run"
slot for an archived entry is that entry's run, not whatever ran most recently. Which is correct, and
the diff already labels its provenance.

### What it costs

Evidence is references into memory that the session was holding anyway — until the session clears,
after which the entry is the only owner. A twelve-scenario suite of ordinary order flow is a few
hundred objects. A twenty-times repeat over a quote stream is not, so there is a per-entry cap
(default 1,000 messages), and it drops the right ones: **every message the report references is kept**
— bound messages, strays named by a STRICT verdict — and the cap falls on the unbound remainder,
newest first. An entry that lost anything says so on its grid header: `1,000 of 3,412 messages kept`.

Retention is the tab. A set opens a document; the set is held as long as its tab is; closing it
releases the evidence. Nothing is written to disk in Phase 1 — an on-disk run archive
(`~/.fixtool/runs/`, so a set survives a restart and CI can read a failed entry's bytes) is the
obvious follow-on, and it is a strictly additive one once the entry owns its evidence.

## Examples — the same run set, from a table

A scenario is **already a Scenario Outline**. Every step is parameterized, every `${…}` resolves against
one variable scope the runner threads through the whole run, and that scope already covers both
directions: a Send puts `${symbol}` on the wire, an Expect's `reference` matcher asserts `${symbol}` came
back, and an Expect's `bindAs` writes the venue's own choice *into* the scope for later steps to use.

What is missing is the table, and one line of runner.

### The line

```kotlin
// ScenarioRunner.runIdentified, today
val scope = mutableMapOf<String, String>()

// with examples
val scope = params.mapValues { host.resolve(it.value, mutableMapOf(), null) }.toMutableMap()
```

That is the entire engine change. Everything downstream already reads that map: `host.resolve` for a
Send's raw, `resolveMatch` (`ScenarioRunner.kt:1130`) for a bind predicate's values, and
`referenceResolver` for a `reference` matcher's expression. A row's cells are resolved once as they are
seeded, so a cell may itself say `${uuid}` or `${LocalDate.now()}` and give each row its own fresh id.

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

`ScenarioResult` is untouched. Which row an entry ran is the **entry's** business, exactly as which
iteration it was is: `RunEntry.row`. So the report, the frozen grid, the reconcile route and the JUnit
renderer all keep working, and a CI testcase names itself the way a parameterized test always has:
`book-a-trade [EUR/USD partial fill]`.

### Inbound and outbound, without a new concept

The distinction is real and the tool already computes it. `ScenarioAnnotations.sites()`
(`ScenarioAnnotations.kt`) returns, per variable, the steps that **mint** it (a Send chose the value and
put it on the wire), the steps that **capture** it (an Expect's `bindAs` read it off a reply), and the
steps that **reference** it. So an examples column needs no inbound/outbound flag of its own — the
editor derives it and shows it in the column header:

- **↑ out** — read by a Send. The row is *driving* the flow.
- **↓ in** — read by an expectation. The row is *asserting* the reply.
- **↑↓** — both, which is the ordinary case for an id echoed back.

A column the scenario never reads, and a `${name}` that no column supplies and no step mints, are both
lints those same annotations already make findable. Neither is an error — they are what a half-finished
outline looks like — but neither should be discovered by watching a run fail.

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

The fix is available where the problem is: the run already reports its final scope
(`ScenarioResult.variables`), and the diff window already shows it as the variables strip. So an accept
on an examples entry compares the incoming value against that row's own cells and, on a hit, writes
`${symbol}` — offering the literal as the deliberate alternative rather than the default. It is the same
judgement the reference-matcher mint already makes; it just has to be made here too.

### Together, or separate?

**One run set, two pieces of work.** Examples are a third *source of entries* — a repeat produces N
identical ones, a suite produces N different ones, a table produces N seeded ones — and past that point
every surface is shared: the scheduler, the set report, the per-entry evidence, the frozen grid, the
reconcile route, the JUnit wrapper. Building a separate "data-driven runner" would mean a second run
report that disagrees with the first about what a failure looks like.

The **authoring** is genuinely separate work — the table on the model, the editor grid, the extract door,
the lints — and none of it blocks the suite. So: shared model, its own phase, and **ahead of fan-out**,
because a QA team gets more from eight instruments through one flow than from fifty sessions through one.

## Decisions

### Decision 1 — The set holds the run slot; entries do not re-claim it

`runScenarioBlocking` splits into `claimRunSlot { … }` and a slot-free `runOne`. A set claims once.
Consequence, and it is the right one: `/scenarios/run` answers *"a scenario run is already in
progress"* for the whole duration of a batch instead of interleaving with it.

### Decision 2 — A set isolates its entries by default (`binding = this_run`)

Under the default `BindScope.ANY`, an `expect` may bind a message that arrived before the run started
(`Scenario.kt` / `BindScope`) — so iteration 2 can bind iteration 1's `ExecutionReport` and report
that the venue answered when it has not. On a single run that is a caveat the report already prints
(`staleBindCheck`, `ScenarioRunner.kt:1068`). On a twenty-times repeat it is a false green *by
construction*, and a feature whose purpose is catching flakiness must not manufacture passes.

This is not only a repeat's problem. Entry 2 of a suite inherits entry 1's traffic exactly as
iteration 2 inherits iteration 1's, and a scenario that does not clear in setup can bind it. So a
**set** runs its entries under `BindScope.THIS_RUN` — a **run-time override, never a file
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

### Decision 5 — Fan-out reuses preflight, and refuses when the profile cannot supply lanes

Sessions are reused when up and created from their saved profile when not — the rule preflight already
applies. Concurrency > 1 is permitted only when every lane's `runSessions()` is disjoint from every
other's. See
[Fan-out and the sessions it borrows](#fan-out-and-the-sessions-it-borrows) below, which is the whole
argument.

## Fan-out and where its sessions come from

**It is the same feature, and the substrate is already built.** `FixConnectionConfig.sessionCount`
(1–100, initiators only) already opens N sessions from one profile on a single Connect:

- **Identity per slot** is `SessionIdentityResolver` — a `{n}` / `{nn}` numbering pattern or a
  comma-separated list across SenderCompID, TargetCompID, Username and Password, so slot 7 of
  `LOADGEN{nn}` logs on as `LOADGEN07`. Where two slots would resolve to the same sender/target pair
  it derives a distinct `SessionQualifier` per slot, which is what keeps QuickFIX session ids and
  message store files from colliding. That is the genuinely hard part of running fifty clients, and
  it is done.
- **Titles** are `"<profile> [<slot>]"` — `LoadGen [1]` … `LoadGen [50]` — and
  `getProfileSessions(profileId)` hands the group back in creation order.
- **Steps target sessions by title**, and `Scenario.withSessions` remaps by title. So one lane is
  literally `withSessions(mapOf("QUOTE1" to "LoadGen [7]"))`.

So the identity half of fan-out is done, and the connection half is done too — see below. What is left
is **assignment**: which lane gets which session.

### The decision: fan-out reuses preflight, and adds exactly one question

An earlier draft of this document said *"the run never creates sessions"*. **That was wrong about what
the tool already does**, and the existing behaviour is the better principle. `preflight`
(`ScenarioRunner.kt:222`) already resolves a session this way, per run:

1. If it is there and ready — **use it**, touch nothing.
2. If it is down, or does not exist at all — **one recovery attempt**: `connectSession` reconnects it
   through its own profile, or, for a session that does not exist, strips the `[n]` slot suffix and
   connects the *profile carrying its name* — which creates every slot.
3. Every attempt is started before any wait, so slow logons overlap under one `connectTimeoutMs`
   deadline; each success becomes a passing `connect` row in the report, so an auto-connected run tells
   the same story a hand-connected one would; each failure is a named, actionable refusal.

And `connectProfile` is already **per-slot** reuse-or-create: `reconnectExistingSessions` revives the
group's sessions that are down, and `createMissingSessions` computes the free slots and fills only those.
Connect a 50-session profile that already has 30 sessions up and you get the missing 20 — a top-up, not
a duplicate set.

That is precisely the rule fan-out wants, so **fan-out does not get its own connection logic**. It calls
the same preflight, on the lane group's base name, once. What survives from the earlier draft's three
reasons is only the part that was actually about fan-out: the run must never invent sessions *outside a
saved profile*, because a session is a visible object — identity, connection state, message log, a pane
in the grid — and the evidence design holds references into that log. A lane conjured outside a profile
would be one the author cannot see, inspect, tint or disconnect.

### The one new question: can this profile make more than one session?

Everything fan-out needs beyond preflight is a **capability check**, and it is one field:

```kotlin
val profile = viewModel.profileForSession(session) ?: profileNamed(session.stripSlotSuffix())
val lanes = when {
    profile == null -> Unavailable("no saved profile behind '$session'")
    profile.config.connectionType != INITIATOR -> Unavailable.acceptor(profile)
    profile.config.sessionCount <= 1 -> Unavailable.singleSession(profile)
    else -> Available(profile, profile.config.sessionCount)
}
```

- **`sessionCount > 1`** — the group is the lane pool. Preflight brings up whatever is missing, and the
  lanes are the group's `LOGGED_ON` sessions.
- **`sessionCount == 1`** — fan-out is **unavailable**, and the refusal names the fix in the field's own
  words:

  > Fan-out needs a profile that opens more than one session, and **LoadGen** opens 1. Set **Sessions**
  > on the profile to the number of lanes you want — with a `{nn}` pattern in SenderCompID, so each lane
  > logs on as its own identity — then reconnect. 50 sessions are already connected under other
  > profiles: pick one of those instead.

- **An acceptor profile** — always one session by construction (`connectProfile` forces `targetCount = 1`
  for anything that is not an initiator, because an acceptor binds one listen port). So an acceptor leg
  can never be a lane *source*, and the refusal says that rather than pointing at a Sessions field that
  would not help.

The **Fan out over sessions…** item in the Run ▾ menu is therefore **disabled with its reason showing**,
not hidden. That is the rail's existing habit, and its existing argument: *"silently withholding what
the tool has already decided is the same mistake in a smaller costume — the author cannot tell 'this
cannot be done, and here is why' from 'this feature does not exist'."*

### How "which sessions" is answered today — and the symmetry to keep

Four places already choose sessions, and they do not all choose the same way:

| Today | Chooses | Filter |
|---|---|---|
| **Bulk Send** — `sendMessageToAllConnectedSessions` | every session | `LOGGED_ON` only |
| **Capture** — `captureScenarioFromSessions` | every session, or the titles named | **none** — a disconnected session is captured too |
| **Preflight** — `connectSession` → `connectProfile` | one, and the group behind it | reuse if ready, create the missing slots if not |
| **A scenario step** — `resolveSession` | one, by title or index | none; **null takes the first session in the list** |

Three things follow for fan-out:

1. **Lanes are `LOGGED_ON`, like Bulk Send's targets.** Capture's looser rule is defensible for capture —
   it reads history, which a disconnected session still has — but a lane that is not logged on is not a
   lane. Two different answers in one app to *"which sessions does many-sessions mean"* would be a defect.
2. **A shortfall is reported, not refused.** Preflight tops the group up and waits; if the venue lets only
   38 of 50 on, the set runs 38 and says so on its header — *"38 lanes: 12 of LoadGen's sessions did not
   reach LOGGED_ON."* A load test that declines to start because it is twelve short of a round number is
   one nobody runs.
3. **`null` must mean *this lane's session*.** `ScenarioStep.session`'s KDoc says *"null = the active
   session"*; `resolveSession` returns `list.firstOrNull()`. Today that is a footnote. Under fan-out it is
   a trap: a scenario captured on one session, naming none, would pin all fifty lanes to session index 0
   — and `withSessions` deliberately leaves null alone, so the remap could not rescue it. Reading null as
   the lane's own session is both the safe choice and the useful one: one captured flow spreads over fifty
   lanes with no editing at all. (Outside a fan-out, the KDoc and the code still have to be reconciled
   with each other — they cannot both stay as they are.)

### A lane already knows what to call itself

Bulk Send seeds four names into the template scope for each target it sends to
(`sessionTemplateVariables`): `sessionIndex` (1-based, per target), `sessionQualifier`, `sessionTitle`
and `sessionSenderCompID` — so `262=MD-${sessionIndex}` gives each session a distinct MDReqID with no
authoring ceremony. **A fan-out lane should seed exactly those four names**, into exactly the scope
[Examples](#examples--the-same-run-set-from-a-table) seeds a row into.

That is where the readings stop being separate features. A lane is a run whose scope carries its
session's identity; a row is a run whose scope carries the table's values; an iteration is a run whose
scope carries neither. One seeding mechanism, one `params` argument, and a scenario saying
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

### The acceptor side comes free

If the far end is FixTool in acceptor mode, `attachVenueClient` already opens a pane per connecting
client, titled `"<profile> ← <client CompID>"`. Fifty `LOADGEN{nn}` initiators against a FixTool venue
therefore produce fifty named panes on the other side, and both ends of a lane are addressable by name
— by a scenario step, by a capture, by the fan-out dialog. Nothing to build.

### Evidence at fifty lanes

The suite's default — keep every entry's messages — is wrong at this scale, and saying so is part of
the design rather than an afterthought. A fan-out set flips it:

- **failed lanes** keep their evidence whole, because they are what the run is for;
- **the first passing lane** is kept entire as a reference specimen;
- **the rest** keep counts and timings only, and the report says so.

### The report is a distribution, not fifty rows

Nobody clicks through fifty lanes. The set report for a fan-out is the repeat strip's shape — one tick
per lane, click to focus — over a summary that answers the question a load test actually asks:

```
LoadGen ×50 lanes          48 ✓   2 ✗
▪▪▪▪▪▪▪▪▪▪▪▪▪▫▪▪▪▪▪▪▪▪▪▪▪▪▪▪▪▪▪▪▪▪▪▪▪▪▪▪▪▪▪▪▫▪▪▪▪▪
p50 214ms · p95 1.9s · max 8.4s · failures: lane 14, lane 45
evidence: 2 failed lanes kept whole · lane 1 kept as reference · 47 counted
```

"Did all fifty pass" is the weaker question. *What did the ninety-fifth percentile cost* is the one a
venue is being asked, and the per-lane timings the set already records answer it for free.

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
{ "id": "book-a-trade", "rows": ["EUR/USD partial fill"] }    // one row, for a debug run

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
fixtool run book-a-trade --row "EUR/USD partial fill"   # one row of the table
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
| **2 — The run set document** | Per-entry `evidence` + `assertions` (stop wiping the global map), `ScenarioDoc.RunSet` with the frozen grid, reconcile from any entry, the per-entry cap | ViewModel, rail, documents. |
| **3 — Examples** | `Scenario.examples`, the seeded scope (`params`), the editor's table + "Extract to example column…", the column lints, row-aware reconcile | Model, codec, runner (one line), editor, reconcile. |
| **4 — Fan-out** | Run slot becomes a per-session-set claim under a disjointness check; `concurrency`; the spread/pinned lane dialog over `getProfileSessions`; percentile summary; the failed-lanes-only evidence policy | ViewModel, runner host, rail dialog. No change to profiles or `SessionIdentityResolver`. |

Phase 1 makes a set run. **Phase 2 makes it inspectable**, and a suite is not much use without it —
if the only thing a twelve-scenario run leaves behind is twelve verdicts and one session's worth of
messages, the eleven failures nobody can look at will send the author back to running scenarios one at
a time. Phases 1 and 2 ship together or the feature is half a feature. Phase 3 is where the model starts paying
for itself — an outline costs one line of runner precisely because Phases 1 and 2 built everything around
it. Phase 4 is a load-testing feature that happens to share the model, and should be judged on its own
merits when it is wanted.

A follow-on worth naming now, because Phase 2's shape decides whether it is easy: an **on-disk run
archive** under `~/.fixtool/runs/`. Once an entry owns its evidence, writing it is serialization of
something already in hand — and it is what turns "what broke overnight" into a question answerable in
the morning, from a suite the app has since restarted out of.

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
  third of the rail. The set's *detail* is the document tab, which is exactly why the rail does not
  have to grow to hold it.
- **Two grids that could be confused for each other.** A frozen grid that looked identical to the live
  one would be worse than no frozen grid at all — an author reading an hour-old failure as current
  traffic. Hence the header that names the entry and the freeze time, and hence the live grid keeping
  its own tint rather than being borrowed by whatever the author last clicked.
