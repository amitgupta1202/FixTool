# Automating FixTool (control surface)

FixTool ships an optional, loopback-only HTTP **control surface** that lets external tools —
Claude Code via the [MCP server](../tools/fixtool-mcp/README.md), plain `curl`, or CI
scripts — drive a running instance for automated testing: connect sessions, send FIX
messages, read back parsed messages to verify fields, install the demo FX venue workspace, and
capture screenshots.

## Why a control surface (and not "Playwright for the UI")

FixTool is a Compose Multiplatform desktop app — the UI is rendered to a Skia canvas, so
there is no DOM/accessibility tree for a Playwright-style driver to walk. Instead of fighting
the renderer, the control server talks to the app's own ViewModel. The payoff: verification
runs against **real FIX message content** (tags and values), which is far more reliable than
OCR-ing a screenshot. Screenshots are a visual spot-check, not the primary channel.

## Enabling it

The server is **off by default** and binds to `127.0.0.1` only. Two ways to turn it on:

- **In the app (recommended for installed/binary users):** Settings → *Automation Control* →
  enable, set the port. No env var, no terminal.
- **Env var (developers):** start with `FIXTOOL_CONTROL_PORT` set — this overrides the setting.

```bash
FIXTOOL_CONTROL_PORT=8765 ./gradlew :composeApp:run
```

## Connecting Claude (embedded MCP server)

The app **embeds an MCP server** at `/mcp` (Streamable HTTP / JSON-RPC 2.0), so Claude Code
connects directly — **no Node, no npm, no clone**. Enable the control server (above), then once:

```bash
claude mcp add --transport http fixtool http://127.0.0.1:8765/mcp
```

This works from **any** project directory (use `--scope user` to make it global), so Claude can
drive FixTool while you work in a different FIX codebase. The Settings → Automation Control screen
shows this exact command. All tools (`fixtool_connect`, `fixtool_send`, `fixtool_wait`,
`fixtool_assert`, …) are
served by the in-app Kotlin registry (`control/McpTools.kt`); the standalone Node server in
`tools/fixtool-mcp/` remains as an alternative stdio transport for FixTool developers, and now
**forwards `tools/list` and `tools/call` verbatim** rather than declaring its own copy of every
tool — so there is exactly one definition of each and the two transports cannot disagree.

Optional shared-secret auth — when set, every request must carry an `X-Control-Token` header
with the same value:

```bash
FIXTOOL_CONTROL_PORT=8765 FIXTOOL_CONTROL_TOKEN=secret ./gradlew :composeApp:run
```

Implementation: `composeApp/src/jvmMain/kotlin/com/knapsack/fixtool/control/ControlServer.kt`,
started from `main.kt` via the `onViewModelCreated` hook. All ViewModel access is marshalled
onto the Swing EDT, since Compose state is EDT-bound.

## Running a scenario without the app (headless CLI)

A saved scenario can be run as a plain command that **exits with a status code** — no window, no
display, no control port:

```bash
fixtool run smoke-nos --junit reports/smoke.xml
echo $?      # 0 passed · 1 ran and failed · 2 could not be run
```

Those three codes are distinct on purpose: a build step that cannot tell *"the venue answered
wrongly"* from *"the scenario file was missing"* goes green on a broken venue.

```
fixtool run <scenario> [options]
fixtool run --set <name> [options]
fixtool run --all [options]

  <scenario>          a saved scenario's id or name, or a path to a scenario .json file
  --set <name>        run a saved run set (<home>/sets/<name>.json)
  --all               run every saved scenario, in name order
  --repeat <n>        run each scenario n times (a flake hunt)
  --rows              run the scenario once per live row of its Examples table
  --row <name>        run only that row of the table (repeatable)
  --fan-out <profile> run the scenario once per logged-on session of a multi-session initiator profile,
                      all at once — a load run against the venue under test
  --over <session>    which of the scenario's sessions the lanes replace (only when it drives >1)
  --pause <500ms|2s>  wait between entries
  --stop-on-failure   end the batch at the first failing entry
  --junit <file|dir>  write the JUnit XML report — one <testsuites> to a .xml file, or one file per
                      entry into a directory
  --json  <file>      write the full JSON report to <file>
  --session <a>=<b>   run the steps naming session <a> against session <b> instead (this run only)
  --home <dir>        read profiles, settings and saved scenarios from <dir> instead of ~/.fixtool
```

**Sessions connect themselves.** A scenario names its sessions; each is dialled from the saved
profile of the same name, and the runner waits for logon before any step runs — so an unattended run
tells the same story a hand-connected one would.

**`--home` is what makes this work on a build box**, which has no `~/.fixtool` and should not be made
to grow one. Point it at a directory holding `connection_profiles.json`, `app_settings.json` and
`scenarios/`, versioned beside the code under test.

The report goes to stdout and progress to stderr, so `fixtool run … > report.txt` keeps them apart.

```bash
# the whole store, one process, one exit code, a JUnit file per entry
fixtool run --all --home ci/fixtool --junit reports/ --stop-on-failure

# a named set from the checkout, as one <testsuites> document
fixtool run --set nightly --home ci/fixtool --junit reports/nightly.xml

# is this flow flaky?
fixtool run book-a-trade --repeat 20 --pause 500ms
```

**A batch leaves a record per entry** under `<home>/runs/<set-id>/`: `set.json` (what ran and how it
went) and one `NN-<scenario>.json` per entry carrying the report **and the messages, with their wire
bytes**. That is what makes an overnight suite answerable in the morning — by the time anybody reads
it, the app that ran it may be closed, and the twelfth entry's traffic is all a live session would
still hold. Two settings bound it: how many messages one record keeps, and how many sets the
directory keeps.

**Fan-out** (`fanOut`, or `fixtool run <scenario> --fan-out <profile>` headless) runs the flow once per
session of a multi-session initiator profile, **all at once**. Each lane seeds four names into its own scope — `${sessionIndex}` (the profile slot),
`${sessionSenderCompID}`, `${sessionTitle}`, `${sessionQualifier}` — so `11=ORD-${sessionIndex}-${uuid}`
gives every lane its own ClOrdID. The report is a `p50/p95/max` distribution over reply latency rather
than N rows — written into the set's `set.json` as a `stats` block, and so returned by
`GET /scenarios/runs/<id>` and `fixtool_run_status`, written by `fixtool run --json`, and printed by the
CLI's own summary. `replyLatency` counts only the steps that measured a round trip; a Send's latency is
local hand-over work and is not a sample. **Point it at the server under test**: against FixTool's own acceptor the numbers are the
tool's ceiling, since one thread answers every session — the response carries a `notice` saying so. See
the Help window's *Fanning a scenario out over those sessions* for the full list of limits.

**Each entry runs isolated** (`binding=this_run`), so iteration 2 cannot bind iteration 1's reply and
report that the venue answered when it has not. Isolation is not a reset of everything: the
scenario's own `clearMessages` and `clearOrderBook` setup steps are what clear a session's log and a
venue's order book.

During development the same entrypoint is `./gradlew :composeApp:run --args="run smoke-nos"`; from an
installed build it is the packaged binary (`FixTool.app/Contents/MacOS/FixTool run smoke-nos` on
macOS). Any invocation whose first argument is `run`, `--help`, `-h` or `help` goes headless — with no
arguments the app opens normally, so double-clicking is unaffected.

## Running a load without the app (`fixtool load`)

A scenario answers *does this flow hold*. A load run answers *how does the venue behave when several
thousand messages arrive at once*: it issues one message across a profile's sessions **without waiting
for replies**, then accounts for every reply that lands on any participating session.

```bash
fixtool load "NOS EUR/USD 1M" --profile LOADGEN --count 4000 --settle 60s --json reports/load.json
fixtool load "NOS EUR/USD 1M" --profile LOADGEN --rate 500/s --for 10m --set run=c118 --junit reports/soak.xml
echo $?      # 0 everything answered · 1 unmatched, tool-limited, stopped, or a strict-rate shortfall · 2 could not run
```

```
fixtool load <template> --profile <name> (--count <n> | --rate <r>/s --for <d>) [options]

  <template>             a saved message's name or id, or a path to a .fix file holding one message
  --profile <name>       the multi-session initiator profile whose lanes issue
  --count <n>            burst: issue n messages as fast as the lanes carry them
  --rate <r>/s --for <d>  sustained: issue r per second for d (90s, 10m, 1h)
  --settle <d>           wait this long for replies after the last send (default 60s); the window
                         closes early when nothing is pending
  --listen <profile>     also match replies landing on this profile's sessions (repeatable)
  --match <req>=<rep>    request tag to reply tag (default: the template's first correlation tag, both sides)
  --reply-type <35>      count only replies of this MsgType as answers
  --set <k>=<v>          seed a value into every message's scope as ${k} (repeatable)
  --store file|memory    message store for this run's sessions (default: the profile's)
  --log file|none        message log for this run's sessions (default: the profile's)
  --strict-rate          exit 1 on a rate shortfall, not only on unmatched replies
  --json <file>          write the load report
  --junit <file>         write one <testsuite> with three cases: completeness, rate, tool
  --home <dir>           read profiles and templates from <dir> instead of ~/.fixtool
```

**What varies per message.** `${messageIndex}` (1-based), the `--set` seeds, `${uuid}`, `${uuid:N}`,
`${now}` and `${utcnow}` with their offsets and patterns are rendered per message by string substitution.
`${sessionIndex}` and the other lane names are the lane's, as in a fan-out. Anything else, a `${out.D.11}`
or a Kotlin expression, is evaluated **once per lane** and frozen, and the report lists its tag under
`fixedTags` so nobody believes it was re-read per message. A `${name}` nothing seeds is refused before a
lane dials, with the `--set` that would fix it.

**How replies are matched.** One matcher reads the socket stamps of every participating session, the lanes
and the `--listen` sessions alike. A request is pending from its SEND stamp until the first reply carrying
its id arrives anywhere, which is the match and the round trip. A repeat is a **duplicate** (reported, not
judged: an order legitimately draws several ExecutionReports). A reply matching nothing issued is a
**stray**. Nothing is aged out before the settle window closes, and a reply after that is **late**.

**Three verdicts, one exit code.** `completeness` fails on anything unanswered within the settle window.
`rate` is not applicable to a burst, `HELD`, or `SHORTFALL` when the achieved rate fell more than 2%
under the requested one for a full second or more; a shortfall exits 0 unless `--strict-rate`, because the
venue answered everything and a build that wants to gate on the tool's own pacing has to say so. `tool`
is `LIMITED` when FixTool itself got in the way: messages the panes discarded, messages handed to the engine
that never left the socket, or sends the engine refused. `issued` is therefore three numbers, requested,
handed to the engine and left the socket, and completeness is judged over the last.

**The report.** `loads/<id>/load.json` in the workspace, written as the run progresses and once more at the
end, beside `unmatched.fix` (the wire of every unanswered request) and `specimens.fix` (fifty matched
pairs, request then reply). Never every message. The JSON carries `issue`, `rate`, `replies`, `timing`
(`elapsedMs` first send to last matched reply, `drainMs` last send to last matched reply), `roundTrip`
(min, p50, p95, p99, max, mean, samples), `perSecond` buckets, `tool`, the first 1,000 `unmatched` with
`unmatchedTotal`, and `verdict`. The JUnit file is one `<testsuite>` with the three cases, so a build that
already ingests `fixtool run`'s XML needs no change.

**From the control surface.** `POST /load` starts the same run as a job and answers 202 with its id;
`GET /loads/<id>?wait=10000` polls it and returns the finished report; `POST /loads/<id>/stop` stops it.
The MCP tools are `fixtool_load` and `fixtool_load_status`, the same shape as `fixtool_run_set` and
`fixtool_run_status`.

**Store and log for a load run.** Pass `--store memory --log none` unless the profile already says so: the
per-message file appends cap how fast a lane can issue, and a store that grows for the length of a soak is
not wanted. A memory store needs Reset on Logon on the profile, and the command exits 2 with the reason
when it is off.

## HTTP API

Base URL: `http://127.0.0.1:$FIXTOOL_CONTROL_PORT`. Request/response bodies are JSON.

| Method & path        | Body                                   | Returns                                              |
| -------------------- | -------------------------------------- | ---------------------------------------------------- |
| `GET /syntax`        | —                                      | `text/markdown`: the template-expression + matcher reference (see below) |
| `GET /health`        | —                                      | `{status, sessionCount, version}`                    |
| `GET /sessions`      | —                                      | array of sessions (index, id, title, state, …); an ACCEPTOR session also carries an `acceptor` block, and a venue's per-client session carries `venueClientOf` — see below |
| `GET /profiles`      | —                                      | array of connection profiles (summary: id, name, type, host, port, CompIDs) |
| `GET /profiles?profile=` | query: `profile` (id or name)      | **one profile's whole config** — every field, for a read → edit → save round-trip. Passwords read as `[REDACTED]` |
| `POST /profiles`     | `{"name", "config":{…}, "id"?, "replace"?}` | create, or **merge** into an existing profile if `id` is given → `{status, id, name, mode, applied[], warnings?}`. `replace:true` replaces the whole config instead |
| `DELETE /profiles`   | `{"id"}` (or `?id=`)                   | delete a profile (demo profiles are protected)       |
| `POST /panel`        | `{"panel":"connection\|editor\|detail\|settings\|scenarios\|conversations\|trace\|orderbook", "show"?, "follow"?, "render"?, "profile"?, "rule"?, "step"?, "action"?}` | show/hide a panel (`scenarios` toggles the Scenarios rail; `conversations` sets group-by-conversation — per session with `"session"`, all sessions without; `trace` opens the Trace panel and takes `"follow"`: a whole correlation value to narrow every pane to that exchange, or `null` to stop, and `"render"`: `ledger` (the grid, every trace) or `lanes` (the followed trace as swimlanes) — see [Following one exchange across every session](#following-one-exchange-across-every-session); `connection` takes `"profile"` to load that profile onto the form, as clicking it in the list does; `editor` with a `profile` and a `rule` (+ optional `step`) opens that acceptor rule's reply step in the message editor, and `action:apply`/`action:cancel` finishes it) |
| `GET /templates`     | query: `profile`?                      | list saved templates (name, type, userTags, isFavorite, fields) |
| `POST /templates`    | `{"profile", "name", "fields"\|"raw", "userTags"?, "isFavorite"?, "id"?}` | create/update a template |
| `DELETE /templates`  | `{"id", "profile"?}`                   | delete a template                                    |
| `POST /templates/load` | `{"id"}`                             | load a template into the editor (opens the editor panel; may switch the active session — see [Message templates](#message-templates)) |
| `POST /demo`         | `{"action":"start"\|"stop"}`           | `{status, action, running, venue, port}`             |
| `POST /connect`      | `{"profile":"<name or id>"}`           | `{status, profile}` (logon is async)                 |
| `POST /disconnect`   | `{"profile":"<name or id>"}`           | `{status, profile}`                                  |
| `POST /send`         | `{"raw":"8=FIX.4.4|35=D|…", "session"?, "resolve"?}` | `{status, result}`; `resolve` (default **false**) resolves `${…}` first — without it `raw` goes on the wire verbatim |
| `POST /send/all`     | `{"raw":"…"}`                          | bulk-send to all logged-on sessions; per-session results |
| `POST /templates/send` | `{"id", "session"?}`                 | send a saved template (expressions resolved)         |
| `GET /messages`      | query: `session`, `limit`, `direction` | `{session, total, messages:[…]}` with parsed fields  |
| `POST /messages/clear` | `{"session"}`                        | clear a session's message log                        |
| `POST /wait`         | `{"session", "state"?, "match"?, "timeoutMs"?}` | block until state/message-match or timeout; returns the match |
| `POST /admin`        | `{"session", "action", …}`             | session/admin control (see below)                    |
| `POST /validate`     | `{"raw"}`                              | `{isValid, errors}` against the loaded dictionary    |
| `GET /dictionary`    | —                                      | current FIX version + validity                       |
| `POST /dictionary`   | `{"version"}` or `{"path", "transportPath"?}` | switch the data dictionary                    |
| `GET /acceptor/rules` | query: `profile`                      | a profile's acceptor auto-response rules, each with its `index`, its ANDed `trigger`, its played `sequence`, any `validationError` and `shadowedBy` when an earlier rule already answers everything it would, plus the profile's simulated `latency` |
| `POST /acceptor/rules` | `{"profile", "rule"?, "preset"?, "index"?, "enabled"?}` | add (`rule`, no index), replace (`rule` + `index`), toggle (`index` + `enabled`) or insert a ready-made behaviour (`preset`) — **one** rule at a time, leaving the rest of the profile alone |
| `GET /acceptor/presets` | —                                    | the shipped acceptor behaviours by `id`, each with what triggers it and the reply it inserts |
| `DELETE /acceptor/rules` | `{"profile", "index"}`             | remove one rule; the rules after it shift up                |
| `POST /acceptor/test` | `{"profile", "raw", "orderState"?, "order"?}` | **dry-run** a message against the rules — no connection, no send, nothing saved. Per rule: `matched`, each condition's verdict with the value it read, `whenOrder` when the rule asks the book, `skipped`, `shadowedBy`; for the winner, the rendered reply with each step's offset. `orderState` is the venue state to assume (`unknown`\|`pending`\|`working`\|`done`, default `unknown`); the answer always reports `assumedOrderState` back. `order` is the order to render `${order.…}` against, by the book's own names |
| `POST /mcp`          | JSON-RPC 2.0                           | embedded MCP server (initialize / tools/list / tools/call) |

`/admin` `action`: `seqnum` (read sender/target next seq nums), `reset-seqnum` (`sender`/`target`),
`test-request` (`id`), `resend-request` (`begin`/`end`), `sequence-reset` (`newSeq`/`gapFill`),
`logout` (`reason`), `disconnect` (`reason`, ungraceful), `stop-responses` (drop this session's queued
acceptor auto-responses → `{dropped}`). Used for session-recovery / gap-fill QA.
| `POST /select`       | `{"session"?, "index"?, "messageType"?, "direction"?}` | selects a message in the browser → opens the detail panel |
| `POST /assert`       | `{"session"?, "messageType"?, "direction"?, "index"?, "timeoutMs"?, "mode"?, "fields":[{tag, matcher}]}` | machine-checks a received message tag-by-tag → `{passed, tags:[{tag, matcher, expected, actual, passed, index, occurrence, status}]}`. `fields` is an **ordered** list: the *k*-th row for a tag asserts the *k*-th occurrence of it, and the rows must be a subsequence of the reply — do not sort or de-duplicate. A top-level `status` of `timeout` or `no-wire-bytes` means nothing was judged (`no-wire-bytes` is a FixTool limitation, not a venue failure). |
| `POST /expectation/capture` | `{"session"?, "messageType"?, "direction"?, "index"?}` | builds an auto-seeded expectation from a message → `{messageType, mode, fields:[…]}` |
| `GET /scenarios`     | query: `profile`?                      | list saved scenarios (id, name, profile, step counts, userTags) |
| `GET /scenarios?id=` | query: `id`                            | one scenario's **full JSON definition** — the exact shape `POST /scenarios` accepts, for read → edit → save round-trips |
| `POST /scenarios`    | scenario JSON `{name, steps:[…], setup?, teardown?, …}` | create/update a scenario (id generated if absent) |
| `DELETE /scenarios`  | `{"id"}`                               | delete a scenario                                   |
| `GET /dictionary/roles` | — | the venue's own tags and what has been declared about each → `{path, sidecar, summary, tags:[{tag, name, tier, roles[]}]}`. `tier` is `DECLARED` / `IDENTIFIER` / `OTHER` — an **ordering, not a filter**; every venue tag is listed |
| `POST /dictionary/roles` | `{"roles": {"20001": "CLIENT_MINTED_ID", "117": ["CLIENT_MINTED_ID","VENUE_MINTED_ID"]}}` | declare who mints a venue tag — the one thing a FIX dictionary cannot record. Replaces the declaration wholesale, writes `<dictionary>.roles.json` beside the loaded dictionary, and reloads so the **next capture** uses it. Roles: `CLIENT_MINTED_ID` (minted fresh per run, echo→`reference`+bind), `VENUE_MINTED_ID` (`presence`), `LIFETIME`. Refused with `known[]` if a role name is unrecognised, or if no dictionary **file** is loaded (a bundled one has no venue tags) |
| `POST /scenarios/capture` | `{"name", "profile"?, "sessions"?}` | record the live message flow into a scenario (auto-parameterized, echoed ids wired to `reference` matchers). Returns `warning` when the loaded dictionary cannot name a captured tag — an unclassifiable tag is replayed as a literal, so a timestamp among them replays **stale**; `omitted[]` names messages left out entirely. `echoProposals[]` lists correlation ids this **flow** reveals that nobody has declared — `{kind: MINT\|CAPTURE, role, tags[], suggestedName, value, evidence}`. Reported, never applied: accept one by POSTing it to `/dictionary/roles` and capturing again |
| `POST /scenarios/capture-paste` | `{"name", "wire", "session"?, "senderCompId"?, "targetCompId"?, "profile"?}` | capture from **pasted wire** — one FIX message per line, read like the paste sheet: a `\|`-inside-a-value line is **refused** (never guessed), and a message whose direction `SenderCompID(49)` cannot settle **blocks the save**. Every step is badged `pasted`. Returns `{status, id, steps, pasted, warning?, echoProposals?, refused[]}` or `{status:"refused", undirected[]}` — `warning` names the tags the loaded dictionary cannot classify (see `/scenarios/capture`) |
| `POST /load`         | `{"profile", "template"\|"fields"\|"raw", "count"\|("rate","forMs"), "settleMs"?, "listen"?, "match"?, "seed"?, "store"?, "log"?, "strictRate"?}` | **starts a load run as a job** → 202 `{load, status, label, notice?}`; 409 when the lanes are held or none is logged on; an error object when the plan is wrong, including a memory store without Reset on Logon. See [Running a load without the app](#running-a-load-without-the-app-fixtool-load) for what every field means |
| `GET /loads`         | —                                      | recent load runs, newest first: `{count, loads:[{id, label, status, phase, issued, matched, unmatched, startedAt, finishedAt?, exitCode?}]}` |
| `GET /loads/<id>`    | query: `wait`? (ms, max 10000)         | the load report, live while it runs and from `loads/<id>/load.json` afterwards, the same JSON `fixtool load --json` writes. `wait` holds the call until the run finishes or the wait runs out |
| `POST /loads/<id>/stop` | —                                   | 202 `{status:"stopping"}`, or 409 when that run is not running |
| `POST /scenarios/run` | `{"id"}` or `{"scenario":{…}}`, `format`?, `sessions`? | run a scenario deterministically → per-step/per-tag report (or JUnit XML with `format:"junit"`). `sessions` is a throwaway `{from: to}` session remap for this run only — nothing persisted; sessions the run needs are auto-connected from saved profiles. To keep an environment durably, save a remapped copy of the scenario (the rail's ▾ beside Run). **409** while a run or a set holds the slot |
| `POST /scenarios/run` (a **set**) | `{"set":"nightly"}`, `{"ids":[…],"repeat"?}` or `{"id","repeat":20}`, and `{"id","rows":true}` / `{"id","rows":["row name"]}` for the Examples table or named rows, or `{"id","fanOut":{"profile","session"?}}` to run it once per session of a multi-session profile, concurrently, plus `stopOnFailure`?, `pauseMs`? | **starts a job** and answers `202 {runSet, status, entries, unresolved?}` — a twelve-scenario suite is minutes and this route runs on one of four HTTP threads. Each entry runs isolated and writes its record as it lands |
| `GET /scenarios/runs` | — | the recent sets, newest first: `{count, sets:[{id,label,status,total,done,passed,failed,startedAt}]}` |
| `GET /scenarios/runs/<id>` | `?wait=<ms>` (≤10000) | where a set has got to: `{status: running\|passed\|failed\|stopped, summary:{total,done,passed,failed,elapsedMs}, entries:[{n,scenario,iteration,state,durationMs,record,note}]}`. Read **from disk**, so it survives a restart; `wait` long-polls until the set finishes |
| `GET /scenarios/runs/<id>/entries/<n>` | — | that entry's whole **record**: the report, every message it saw with its wire bytes, and `bound` (which message each step judged) |
| `POST /scenarios/runs/<id>/stop` | — | ask the running set to stop where it is → `202`, or `409` if that set is not the one running |
| `POST /scenarios/reconcile` | `{}` or `{"step": N}`            | **open the diff on a step the last run failed** — the one surface that can repair an assertion, in its own **window** (Phase 6). `{}` takes the first failing step, as the rail's *Reconcile →* does; it goes through the same route check, so a step edited since it ran is refused **with the reason**. Pair with `GET /screenshot?window=reconcile`. |
| `POST /scenarios/diff` | `{"a": {…}, "b": {…}}` — each side a **pick** `{"session", "match":{"messageType"?,"tag"?,"value"?,"direction"?}}` or a **paste** `{"paste":"<bytes>"}` | **open the plain diff viewer** on two messages — a read-only structural diff (`=`/`≠`/`+A`/`+B`), no assertions, scenario-less (Phase 7). Each side is read through the same reader the paste sheet uses — a `\|`-inside-a-value line is **refused** (never guessed), and a side with no wire bytes is refused **with the reason**. The window's title carries `diff:`, so pair with `GET /screenshot?window=diff:`. Returns `{status:"open", subject}` or an error |
| `POST /detail`       | `{"query"?, "mode"?, "show"?}`         | drives the detail panel's tag search: sets the query and/or match-context `mode` (`bare`\|`identity`\|`full`) so a nested tag keeps its repeating-group context |
| `POST /search`       | `{"query", "pin"?}`                    | cross-session matches sorted chronologically (a timeline); pins to the search pane |
| `GET /traces`        | —                                      | every **trace** — one exchange as it appears across **every** session at once: `{traces:[{label, labelTag, ids[], sessions[{index,title}], messageCount, composition[], status\|null, instrument\|null, quantity\|null, elapsedMillis, truncatedSessions[]}], ungrouped, total}`. See [Following one exchange across every session](#following-one-exchange-across-every-session) |
| `GET /trace?id=`     | query: `id` — a **whole** correlation value | that one trace at full fidelity: every message merged into one time order, each with its `session` `{index,title}`, `elapsedMillis` since the previous message *in this trace* (null for the first), and the same ordered `fields` array `/messages` emits. **400** with no `id`, **404** when no trace carries it — a substring is never a match |
| `POST /filter`       | `{"scope"?, "session"?, "regex"?, "messageTypes"?, "showIncoming"?, "showOutgoing"?, "showSeparator"?}` | filters the grid for a focused screenshot |
| `GET /screenshot`    | query: `window` (`main`\|`diff`\|title substring; default `main`) | `image/png` bytes of a window, picked by title. With a reconcile **and** a viewer window both open, `diff` is ambiguous — address them by substring: `window=reconcile` (the repair diff) and `window=diff:` (the plain viewer). |

`session` may be an index (`0`), an id, or a title. `direction` is `in`/`out` (or omitted).
Each message in `/messages` includes `timestamp`, `direction`, `messageType` (tag 35), the
`raw` string, and an ordered `fields` array of `{tag, value}`.

### Following one exchange across every session

A **trace** is the grouped grid's relation over every session at once: one business exchange followed
through every pane that saw it, joined by shared correlation-id **values** — and a substring never
matches. `GET /traces` (MCP: `fixtool_traces`) lists them all; `GET /trace?id=` (MCP: `fixtool_trace`)
returns one in full.

This is the answer to *what happened to `RFQ-A1`* — as opposed to *what happened to `RFQ-A1` on this
session*, which is what `/messages` and the grouped grid give you. The alternative is to read ids off
each pane and post a regex to `/search`, which fails three ways: it only finds ids you already knew,
`ORD-9` also matches `ORD-91`, and a missed id is silent. A venue that mints its own handle per hop is
exactly the case that breaks it — the client's `RFQ-A1`, the LP's `V-2291` and the `Q-77` on the quote
that bridges them are three names for one trace, and **any** of them returns it:

```bash
B=http://127.0.0.1:8765

curl -s $B/traces                    # every exchange, with the ids each one holds
curl -s "$B/trace?id=V-2291"         # the whole exchange, even though the client never said V-2291
```

```jsonc
// GET /traces
{
  "traces": [{
    "label": "RFQ-A1", "labelTag": 131,          // the first id on the earliest message
    "ids": ["RFQ-A1", "V-2291", "Q-77"],         // every value in the component — pass any to /trace
    "sessions": [{"index":0,"title":"CLIENT"}, {"index":1,"title":"LP-1"}],
    "messageCount": 4,
    "composition": [{"messageType":"R","name":"QuoteRequest","count":2},   // name is the dictionary's
                    {"messageType":"S","name":"Quote","count":2}],         // word, or null if it has none
    "status": null,                              // the last status a message STATED, or null. Never inferred
    "instrument": "EUR/USD", "quantity": "10000000",
    "elapsedMillis": 40,
    "truncatedSessions": []                      // non-empty = it opened before the buffer; see below
  }],
  "ungrouped": 1,                                // messages carrying no correlation id (heartbeats, logons)
  "total": 5                                     // every message every session holds, so the numbers add up
}

// GET /trace?id=V-2291  — merged across sessions, one message shown of four
{
  "label": "RFQ-A1", "labelTag": 131, "ids": ["RFQ-A1","V-2291","Q-77"],
  "sessions": [{"index":0,"title":"CLIENT"},{"index":1,"title":"LP-1"}],
  "truncatedSessions": [], "messageCount": 4, "elapsedMillis": 40,
  "messages": [{
    "session": {"index":1,"title":"LP-1"},
    "elapsedMillis": 10,                         // since the previous message IN THIS TRACE; null on the first
    "timestamp": "2026-09-02T10:00:00.010", "direction": "INCOMING", "messageType": "R",
    "raw": "35=R|131=V-2291|55=EUR/USD|38=10000000|",
    "wireOrderKnown": true,
    "fields": [{"tag":35,"value":"R"},{"tag":131,"value":"V-2291"},
               {"tag":55,"value":"EUR/USD"},{"tag":38,"value":"10000000"}]
  }]
}
```

Three things to read carefully:

- **`elapsedMillis` on a message is a measurement, not a diagnosis.** One clock timed both ends —
  FixTool held every session — so the gap between a request leaving the client and its copy arriving on
  an LP is the venue's real forwarding time. The tool states the gap and says nothing about its cause.
- **Every header field is quoted, never derived.** Counts are facts; `status` is the last status a
  message *stated*, rendered in the dictionary's own words, or `null`; `instrument`/`quantity` appear
  only where the opening message leaves no doubt. Assert on these the way you assert on `fields[]`.
- **`truncatedSessions` is the honest limit.** A session's retained window evicts its oldest message,
  so a trace that lost one opened before what you can see. Non-empty means *this trace is missing
  history at the front* — not that the exchange started when the first row says.

A trace only crosses a session where some **value** crosses it. A venue that mints a fresh id per hop
and echoes nothing leaves no edge, and joining those would be the tool inventing one — declare the
venue's echo tag with `POST /dictionary/roles` and the join happens everywhere with no code.

`POST /panel {"panel":"trace"}` drives the on-screen side of it. The **Ledger** is the bottom panel
listing every trace with its session count, its composition and the gap between its messages;
**following** narrows every pane — tabs and split — to one exchange, ANDed on top of each pane's own
filters rather than written into them, so stopping restores every pane exactly.

```bash
curl -s -XPOST $B/panel -d '{"panel":"trace"}'                      # open the Ledger
curl -s -XPOST $B/panel -d '{"panel":"trace","follow":"V-2291"}'    # follow (this opens it too)
curl -s -XPOST $B/panel -d '{"panel":"trace","follow":null}'        # stop following
curl -s -XPOST $B/panel -d '{"panel":"trace","show":false}'         # close the panel, keep following
curl -s -XPOST $B/panel -d '{"panel":"trace","follow":"V-2291","render":"lanes"}'   # follow, drawn as lanes
curl -s -XPOST $B/panel -d '{"panel":"trace","render":"ledger"}'    # back to the grid
```

```jsonc
{"status":"ok", "panel":"trace", "show":true, "render":"lanes",
 "following":"RFQ-A1",        // the trace's label as the app resolved it, or null
 "followingAnchor":"V-2291",  // the id you asked for — a trace has several names
 "pending":false,             // true = followed, but no message carries that id YET. Not a typo:
                              //        a typo is a 404 from /trace. An id a venue mints three hops
                              //        in is legitimately followed before it exists.
 "sessionCount":2, "messageCount":4}
```

`follow`, `show` and `render` combine, and each does one thing: `follow` with a value follows and
opens the panel, `follow:null` stops following and leaves the panel as it was, `show` opens or closes
without touching what is followed, `render` switches the drawing without touching either. Closing the
panel never unfollows — the toolbar chip goes on naming what the panes are narrowed to. `render` is
echoed on every `trace` call, asked for or not, so a caller never has to assume which drawing a
screenshot is about to catch.

#### Ledger and Lanes — two drawings of the same rows

`render:"ledger"` (the default) is the grid: **every** trace, its session count, its composition and
the gap between its messages. `render:"lanes"` is the swimlane picture of the **one** followed trace:
a column per session with initiators left of a dashed rule and acceptors right of it (read from each
profile's `connectionType`, not inferred from CompIDs), time running down, and each message a chip in
the lane of the pane that logged it. With nothing followed, Lanes lists the traces to pick from rather
than drawing an empty grid — so an agent scripting a Lanes screenshot follows first.

The two cannot disagree, because both read the same trace rows, with one deliberate difference: in a
both-sides test the same bytes appear as an OUT on one pane and an IN on another, and **Lanes draws
those as one arrow when the bytes are identical**. That is a fact about two strings — not a claim that
the venue forwarded anything. The Ledger keeps both rows, because both panes logged it. The elapsed
printed on the arrow is the gap between the two ends of that hop, measured on one clock; the gutter
beside a row is the gap since the previous row *started* (so it never goes negative when a venue fans
one request out to several lanes), and where nothing pairs — every real venue test — it is the
Ledger's Elapsed to the millisecond. Like every other elapsed here both state a gap and never a cause.

The Ledger's fold is per `(opener session, label)`, never per label: across sessions two venues both
saying `ORD-1` is the normal case, not the edge.

### Template expressions

`${…}` expressions parameterize what you send, so a scenario re-run does not collide with its own
last run. `GET /syntax` (MCP: `fixtool_syntax`) serves the full grammar as markdown — it is the
authoritative reference; the summary here is the shape of it:

| Expression | Produces |
| --- | --- |
| `${uuid}` | a fresh UUID |
| `${now}`, `${now:yyyyMMdd}`, `${now+1d}`, `${now-2h:yyyyMMdd}` | timestamp, optionally offset (`h`/`d`/`w`/`m`/`y`) and/or formatted |
| `${D.11}`, `${in.D.11}`, `${out.D.ClOrdID}`, `${out.D.11.0}` | a tag off the latest message of that type (auto / incoming / outgoing; trailing index for a repeating group) |
| `${clOrdId = uuid}` then `${clOrdId}` | assign (expanding to the value inline), then re-read later |

**Where they resolve** is the part that bites: a scenario `send`/`match`/`reference` matcher
**always** resolves them, with no flag — but `POST /send` only does so with `"resolve": true`, and
its default is `false`, so an unresolved `${uuid}` is sent as *that literal text*. The variable scope
persists across an entire scenario run (setup → steps → teardown, and across sessions), which is what
lets a value sent on one session be asserted in a response on another. An unknown `${name}` is left
as literal text rather than raising an error.

`{n}`/`{nn}` is *not* one of these — it is the profile CompID numbering pattern (a `senderCompID` of
`LOADGEN{nn}` with `sessionCount: 4`), and it is not resolved in a message body.

### Asserting responses

`/assert` (MCP: `fixtool_assert`) machine-checks a received message against an **expectation** —
a list of per-tag matchers — instead of eyeballing it. It selects the message (by
`messageType`/`direction`/`index`) or awaits one for up to `timeoutMs`, then returns a tag-by-tag
report. `mode` is `open` (default — only the listed rows are checked, **in the order they are listed**:
they must be a subsequence of the reply; unmentioned tags are ignored) or `strict` (additionally: the same
tags, the same number of times, in the same order, and any unexpected tag — besides the session envelope —
fails). Each field is `{tag, matcher:{type, …}}` — there is **no** `path`; the *k*-th row for a tag asserts
the *k*-th occurrence of it. `matcher.type` is one of:

| type | extra fields | checks |
| --- | --- | --- |
| `exact` | `value` | literal equality |
| `notEqual` | `value` | tag is present **and** its value is anything else (not the same as `absent`) |
| `presence` | — | tag is present (value ignored) |
| `absent` | — | tag is not present |
| `regex` | `pattern` | value matches the pattern |
| `oneOf` | `values[]` | value ∈ set |
| `numeric` | `value`, `tolerance`? | `abs(actual − value) ≤ tolerance` (0 still ignores formatting) |
| `range` | `min`?, `max`?, `minInclusive`? (default true), `maxInclusive`? (default true) | number above and/or below a bound; either may be omitted, so this covers `>`, `>=`, `<`, `<=` and *between*. Omitting both is refused, not passed |
| `temporal` | `kind` (`today`\|`now_within_tolerance`), `toleranceSeconds`? (default 60) | parsed as UTCTimestamp/UTCDate |
| `reference` | `expression` | equals a `${…}` expr resolved over session history, e.g. `${out.D.11}` |

**The field's type decides which of these can be judged.** `numeric` and `range` return false for any
value that will not parse as a number, `temporal` for anything that is not a timestamp or a current UTC
date — so one of those on a `STRING` tag is not a loose assertion, it is a row that can never be green.
Nothing over this API warns you (the reconcile editor dims such types and gives the reason; JSON gets no
hint). Text → `exact`/`oneOf`/`regex`/`presence`; price, qty, amount → `numeric`/`range`; timestamp or a
date meaning *today* → `temporal` (a `LOCALMKTDATE` settlement date is a business date, not "today" —
assert it `exact`). **Never a tolerance on an enum-coded int**: `PartyRole(452)` parses as a number
perfectly well, and `4 ± 3` over a vocabulary of codes accepts seven unrelated meanings. Use `oneOf`.

`path` (`{groupTag, identityTag, identityValue, occurrence?}`) locates a repeating-group entry by
identity, not position — e.g. `{"groupTag":453, "identityTag":452, "identityValue":"1"}` is "the
NoPartyIDs entry whose PartyRole is 1". `occurrence` (0-based, default `0`) is only needed when that
identity is *not* unique, such as several `NoMDEntries` sharing an `MDEntryType`; it counts, in wire
order, among the entries sharing that identity value. `/expectation/capture` (MCP:
`fixtool_capture_expectation`) returns a draft expectation with matchers pre-seeded from the data
dictionary, ready to edit.

```bash
# send an order, then assert the ExecutionReport echoes the ClOrdID and has an OrderID
curl -s -XPOST $B/send   -d '{"session":"CLI","raw":"35=D|11=ORD-1|55=EUR/USD|54=1|38=100|40=1|"}'
curl -s -XPOST $B/assert -d '{
  "session":"CLI","messageType":"8","direction":"in","timeoutMs":5000,
  "fields":[
    {"tag":150,"matcher":{"type":"exact","value":"0"}},
    {"tag":39,"matcher":{"type":"oneOf","values":["0","1","2"]}},
    {"tag":37,"matcher":{"type":"presence"}},
    {"tag":11,"matcher":{"type":"reference","expression":"${out.D.11}"}}
  ]
}'
```

### Repeatable scenarios

A **scenario** chains a whole flow into a saved, parameterized sequence of sends and assertions that
a deterministic runner replays identically — no LLM in the hot path. It is an ordered list of steps
over one persistent variable scope, plus optional `setup`/`teardown` step lists (teardown always
runs). Step `{type, …}` is one of: `send {raw, session?}`, `expect {session?, direction?, match?,
timeoutMs?, expectation}`, `wait {session?, state?, match?, timeoutMs?}`, `clearMessages {session?}`,
`clearOrderBook {session?}` (the venue-side order book, valid only on a session FixTool hosts as an
acceptor — a run boundary that `clearMessages` does not reach), `resetSeqNum {session?, sender?,
target?}`. An `expect` consumes the message it matches, so a
partial-fill sequence is just successive `expect`s; `match {messageType?, direction?, fields:[{tag,
value}]}` selects by AND. Each step can target a different `session` (initiator + acceptor in one
scenario). Scenarios are stored one-file-per-scenario under `~/.fixtool/scenarios/`.

A send's `raw`, a `match` value and a `reference` matcher all resolve `${…}` expressions (above) —
always, with no `resolve` flag — over the one variable scope the run threads through every step. The
idiom is `11=${clOrdId = uuid}` in the send, then `{"type":"reference","expression":"${clOrdId}"}` to
assert the echo. `/scenarios/capture` (MCP: `fixtool_capture_scenario`) records a live flow into a
scenario that is already parameterized this way, which is usually faster than authoring one by hand.

The run report says what that scope ended up holding: when the run minted anything, the report JSON
carries an additive `variables: [{name, value, mintedAtStepId?}]` (mint order; the key is absent for
a run that minted nothing, so pre-existing consumers keep parsing). `/scenarios/reconcile`'s `open`
response repeats it, so an agent repairing a `reference` row sees what `${id0}` actually held this
run — the same data the diff window's variables strip shows the human.

The reverse direction — the **venue** choosing a value our later sends must echo — is an expectation
row's `bindAs`: `{"tag":131,"matcher":{"type":"presence"},"bindAs":"qr"}` captures the value the row
pairs with into the scope when the step binds, and a later send says `131=${qr}`. The capture follows
the positional pairing rule (the k-th row captures the k-th occurrence), captures nothing when the tag
never arrives, and is additive on disk (written only when set). See
`scenario-assertion-model.md` §The model.

```bash
# run a book-a-trade flow and get a pass/fail report (no AI in the loop)
curl -s -XPOST $B/scenarios/run -d '{"scenario":{
  "name":"book-a-trade",
  "setup":[{"type":"clearMessages","session":"CLI"}],
  "steps":[
    {"type":"send","session":"CLI","raw":"35=D|11=ORD-1|55=EUR/USD|54=1|38=100|40=1|"},
    {"type":"expect","session":"CLI","direction":"in","timeoutMs":8000,"expectation":{
      "messageType":"8","fields":[
        {"tag":150,"matcher":{"type":"exact","value":"0"}},
        {"tag":11,"matcher":{"type":"reference","expression":"${out.D.11}"}}
      ]}}
  ]}}'                                    # add "format":"junit" for CI XML
```

`fixtool_save_scenario` / `fixtool_list_scenarios` / `fixtool_get_scenario` /
`fixtool_run_scenario` / `fixtool_delete_scenario` are the MCP equivalents —
get + save give an agent a lossless read → edit → save-back loop.

#### The examples table — one scenario, once per row

A scenario is a Scenario Outline: `examples` declares the columns, and each row seeds them into the run's
scope **before setup runs**, so `${symbol}` on a send and a `reference` matcher asserting `${symbol}` both
resolve to that row's value. Additive and default-omitting — a scenario with no table grows no key.

```jsonc
"examples": {
  "columns": ["symbol", "qty"],
  "rows": [
    {"name": "EUR/USD 1M", "values": {"symbol": "EUR/USD", "qty": "1000000"}},
    {"name": "GBP/USD 2M", "values": {"symbol": "GBP/USD", "qty": "2000000"}},
    {"name": "parked",     "values": {"symbol": "USD/JPY", "qty": "3000000"}, "muted": true}
  ]
}
```

The cell map is **`values`**, not `cells`. This matters more than it looks: a cell under a key no column
declares is *dropped on load* (the columns are the contract), so a misspelled key does not error — the row
runs with that column seeding an empty string, and the send goes out carrying nothing where it should have
carried a value. If a row's cells arrive empty, check the key first.

A cell is resolved as it is seeded, so a row may hold `${uuid}` and give each of its runs a fresh id —
which is what makes an outline safe to run twice. `muted` parks a row: kept, skipped on every run.

Run it with `{"id":…,"rows":true}` (or `{"rows":["EUR/USD 1M"]}` for named rows) and each entry's record
carries the `row` it ran — `{"name":…, "values":{…}}` — so a failure names the case, not just the
iteration. Entries are named `<scenario> [<row name>]` everywhere: the rail, the record, and the JUnit XML.

### Setting up connections from scratch

`POST /profiles` is how an agent configures a connection — it writes the same profile the
connection panel produces, so the result is immediately connectable via `/connect`. The
`config` object only needs the fields that differ from the model defaults; everything in
`FixConnectionConfig` is accepted (host, port, senderCompID, targetCompID, beginString,
connectionType, heartBtInt, resetOnLogon, useSSL/keyStorePath/…, applVerID, sessionCount,
logonFields, acceptorResponseRules, acceptorLatency, messageStore, messageLog, …).

**Store and log.** `messageStore` is `FILE` (the default: sequence numbers and sent messages under
the workspace's `store/`, so resend works) or `MEMORY` (a heap map, sequence numbers start at 1 on
every logon, nothing written). `messageLog` is `FILE` (the per-session QuickFIX/J log under `log/`)
or `NONE`. Both matter for a load or soak run, where the per-message file appends cap how fast a
session can issue and a store that grows for the length of the run is not wanted. A `MEMORY` store
with `resetOnLogon: false` is accepted by `POST /profiles` with a `warnings` entry and **refused by
`/connect`** with the same sentence, because the next logon would start at 1 while the venue expects
the number it last saw.

**Updating merges.** A `config` posted with an `id` sets the keys it carries and leaves every
other key exactly as it was, so adding one setting cannot silently delete the rest. This used to
replace the whole config, which made the endpoint destructive in its commonest use: nothing could
*read* a profile's SSL settings, logon fields or existing rules — the list gives eight fields — so
"add a rule to my acceptor" was necessarily a POST carrying a rule and nothing else, and it took
the keystore path and every other rule with it, then answered `{"status":"updated"}`.

Merging is per top-level key, so an explicitly sent value always wins and clearing stays
expressible: `"acceptorResponseRules": []` is present in the JSON, therefore applied. `replace:
true` asks for the old wholesale behaviour when you want a profile to be exactly what you send.
The response says which it did (`mode`) and which keys it took (`applied`).

`GET /profiles?profile=<id or name>` is the other half — the whole config, not the summary, so a
read → edit → save round-trip preserves what it did not touch. Passwords come back as
`[REDACTED]`; post that value again (or leave the key out) and the stored secret is untouched.

```bash
curl -s "$B/profiles?profile=My%20Acceptor"                      # read everything
curl -s -XPOST $B/profiles -d '{"id":"<id>","name":"My Acceptor",
  "config":{"acceptorLatency":{"mode":"FIXED","fixedMillis":250}}}'   # change one thing
```

```bash
# create a profile, then connect it
curl -s -XPOST $B/profiles -d '{
  "name": "My Server",
  "config": {"host":"fix.example.com","port":"5001","senderCompID":"ME","targetCompID":"THEM",
             "beginString":"FIX.4.4","connectionType":"INITIATOR","resetOnLogon":true}
}'
curl -s -XPOST $B/connect -d '{"profile":"My Server"}'
curl -s -XPOST $B/panel   -d '{"panel":"connection"}'   # open the panel for a screenshot
```

The agent does not keystroke into the on-canvas form (Compose renders to Skia, so there are
no focusable DOM fields); it writes the profile directly, which is exactly what the panel does
on Save. The new profile then appears in the panel's profile dropdown.

### One acceptor, many clients

Set an acceptor's `targetCompID` to `*` and it becomes a **venue**: it binds the port once and accepts
a logon from any counterparty addressed to its `senderCompID`, opening a session — and a session pane
— per client. `GET /sessions` then shows the venue plus one entry per client, each carrying
`venueClientOf` and its own `messageCount`; the venue's `acceptor` block adds `clientsConnected` and
`logonsRefused`. Drive a specific client by passing its session id or title to `/send`, `/messages`,
`/admin` and scenario steps, exactly as for any other session.

`senderCompID` is never wildcarded, so a logon naming a different acceptor is still refused — and
because QuickFIX/J answers an unrecognised logon with *nothing at all* (no Logout, no Reject), those
refusals are surfaced rather than dropped: they raise a notification, appear on the venue's pane, and
increment `logonsRefused`. When nobody can connect, that counter is the difference between "wrong
CompID" and "wrong port".

An acceptor naming one counterparty is unchanged in every respect, including refusing every other
CompID. Auto-response rules live on the venue, so one rule set serves every client.

```bash
curl -s -XPOST $B/profiles -d '{"name":"Venue","config":{"connectionType":"ACCEPTOR",
  "senderCompID":"VENUE","targetCompID":"*","socketAcceptPort":"9876","beginString":"FIX.4.4"}}'
curl -s -XPOST $B/connect -d '{"profile":"Venue"}'
curl -s $B/sessions      # → Venue (CONNECTED) + "Venue ← BUYSIDE1", "Venue ← BUYSIDE2" as they arrive
```

### Acceptor auto-responses

When FixTool runs as an **acceptor** (`connectionType: ACCEPTOR`), it can auto-respond to incoming
application messages using rules carried on the profile's config as `acceptorResponseRules`. Each
rule is `{whenMsgType, conditions?, steps, enabled?}`; the first **enabled** rule whose whole trigger
matches the incoming message wins. `enabled` defaults true; a rule switched off is kept and skipped, so
the message falls through to the rule after it — which is what an author toggling one off is asking to
see. Replies already queued on a session are dropped with
`POST /admin {"action":"stop-responses"}`, which reports how many it dropped. Rules are set via the normal `/profiles` upsert and inspected via `GET /acceptor/rules`.

**Saving applies to live sessions.** Editing a rule on a profile whose acceptor is already logged on
takes effect on the next trigger — no disconnect, no reconnect. Both write paths (the Auto-Responses
panel and the control surface) go through the same save, so both do this; the response reports
`appliedToLiveSessions` when a save reached a running session, so you can tell the file changing from
the wire changing.

Rules are still compiled once **per ruleset**, not per message — re-parsing on every inbound message
would put JSON on the path of every message a loaded acceptor receives, to reach an answer that has
not changed. Saving recompiles and swaps the whole ruleset atomically, so a trigger sees one ruleset or
the other and never a half-applied edit. Replies already queued keep the templates they were planned
with; the swap governs the next trigger. Only rules and latency travel this way — CompIDs, ports and
SSL are session *identity*, and changing those still needs a reconnect.

(Before this, rules were compiled once when the session connected and nothing re-read them, so an edit
under a logged-on acceptor changed the file and nothing else — and nothing said so, which reads exactly
like a rule that does not work.)

A trigger is `whenMsgType` plus `conditions: [{tag, matcher}]`, all **ANDed**. The `matcher` is the
same JSON the scenario assertions use (see `docs/scenario-assertion-model.md`), so `38 > 10000` is
`{"tag":38,"matcher":{"type":"range","min":10000,"minInclusive":false}}` and `exact`, `presence`,
`absent`, `oneOf`, `regex`, `numeric`, `notEqual` and `temporal` all work too. `notEqual` is not
`absent`: a tag that never arrived satisfies neither, because "not X" about a field the message does
not carry is a question with no answer. `reference` does **not** — it
resolves against a scenario run's scope, and a trigger has none; one is refused by name rather than
silently never matching. Express OR as a second rule; first match wins.

`whenFields` is the older exact-only form (`{"55":"EUR/USD"}`) and still works. The two spellings are
**ANDed, never chosen between** — unlike the two spellings of a *reply*, where picking one sends one
message or the other. Ignoring a trigger spelling would drop a constraint, and a rule that fires on
messages the author excluded is the dangerous direction to be wrong in.

A rule's reply is a **sequence**: `steps` is `[{template, delayMillis?}]`, and a step's `delayMillis`
is measured **from the step before it**, so `0 / 400 / 400` is an ack, a partial fill 400ms later,
and the rest 400ms after that. The author writes the gaps; FixTool does the accumulation.

Rules are also editable in the GUI — the **Auto-Responses** section of the Connection panel, shown for
acceptor profiles only. To put a profile on that form from here (there is no other way in without a
mouse):

```bash
curl -s -XPOST $B/panel -d '{"panel":"connection","profile":"My Acceptor"}'
```

Each `template` is raw FIX (app fields only — QuickFIX stamps the session header) supporting
`${req.<tag>}` (echo a request field), `${uuid}`, and `${now}`. `${req.<tag>}` is fixed when the
trigger arrives; `${uuid}` and `${now}` are resolved **per step as that step is sent**, so a fill
carries its own ExecID and its own TransactTime rather than the acknowledgement's.

A `req` reference **inside** a larger expression is computed, by the same Kotlin engine the message
editor uses: `14=${req.38 / 2}` fills half the order quantity, so one partial-fill rule works for every
order size instead of the one its author hardcoded. The value is substituted raw, which is what makes
the arithmetic work and what keeps this to numbers — a string field is read with the standalone
`${req.11}` form, which needs none of it. Expressions are scoped to the message that **triggered the
rule**, not "the latest incoming", so two orders in flight cannot read each other's quantities.

Replies always leave *after* the inbound callback has returned, on a dispatch thread of their own —
including a zero-delay one. A reply sent inline can reach the counterparty before their own `send()`
has returned, which would let a client with wrong ordering assumptions pass here and fail against a
real venue. Steps of one sequence keep their order; sequences triggered by different inbound messages
interleave. Anything still queued for a session is dropped when that session logs out.

The older one-message spelling, `{whenMsgType, responseTemplate}`, still works and reads as a single
step with no delay. A rule carrying both plays `steps` and says so in `validationError` on
`GET /acceptor/rules` — which also reports the played `sequence` with the offset each step goes out
at, so you never have to redo the arithmetic.

```bash
curl -s -XPOST $B/profiles -d '{
  "name":"My Acceptor",
  "config":{"connectionType":"ACCEPTOR","senderCompID":"ME","targetCompID":"THEM",
            "socketAcceptPort":"9100","beginString":"FIX.4.4",
            "acceptorResponseRules":[
              {"whenMsgType":"D",
               "conditions":[{"tag":55,"matcher":{"type":"exact","value":"EUR/USD"}},
                             {"tag":38,"matcher":{"type":"range","max":1000000}}],
               "steps":[
                 {"template":"35=8|150=0|39=0|37=${uuid}|17=${uuid}|11=${req.11}|38=${req.38}|14=0"},
                 {"delayMillis":400,
                  "template":"35=8|150=F|39=1|37=${uuid}|17=${uuid}|11=${req.11}|38=${req.38}|14=50"},
                 {"delayMillis":400,
                  "template":"35=8|150=F|39=2|37=${uuid}|17=${uuid}|11=${req.11}|38=${req.38}|14=100"}
               ]}
            ]}
}'
curl -s -XPOST $B/connect -d '{"profile":"My Acceptor"}'   # a NewOrderSingle now gets ack, fill, fill
curl -s "$B/acceptor/rules?profile=My%20Acceptor"          # offsets: 0, 400, 800
```

#### Editing one rule at a time

`POST /acceptor/rules` changes a single rule and leaves the rest of the profile alone. Needed even
though `/profiles` merges, because merging is per top-level key: the rule *list* is one key, so
adding a rule through it still means re-sending every other rule.

A rule's **index is its identity** — the list is ordered and first-match-wins, so position is
itself meaningful and there is nothing else to name a rule by. `GET /acceptor/rules` reports it.

```bash
# append a rule
curl -s -XPOST $B/acceptor/rules -d '{"profile":"My Acceptor",
  "rule":{"whenMsgType":"F","steps":[{"template":"35=9|434=1|11=${req.11}|"}]}}'
# replace rule 0 in place
curl -s -XPOST $B/acceptor/rules -d '{"profile":"My Acceptor","index":0,"rule":{…}}'
# "what happens without this one" — kept, skipped, falls through to the next rule
curl -s -XPOST $B/acceptor/rules -d '{"profile":"My Acceptor","index":1,"enabled":false}'
# remove it (everything after it shifts up)
curl -s -XDELETE $B/acceptor/rules -d '{"profile":"My Acceptor","index":1}'
```

#### Presets — the common order flow, ready made

`GET /acceptor/presets` lists the shipped behaviours; `POST /acceptor/rules` inserts one by `preset`
instead of a hand-written `rule`. They are ordinary rules once inserted — editable, reorderable,
deletable, indistinguishable on disk from typed ones.

```bash
curl -s $B/acceptor/presets                                   # ids, triggers, replies
curl -s -XPOST $B/acceptor/rules -d '{"profile":"My Acceptor","preset":"starter-venue"}'
curl -s -XPOST $B/acceptor/rules -d '{"profile":"My Acceptor","preset":"order-reject-size"}'
```

| id | answers |
|---|---|
| `starter-venue` | a coherent venue in one click: ack + fill for limits, ack for the rest, replace, and a cancel answered **four ways from the book** — unknown → `102=1`, pending/working → canceled, done → `102=0` too late |
| `order-ack` | `35=D` → ExecutionReport, New |
| `ack-then-fill` | `35=D` with `40=2` → ack, then a fill 250ms later |
| `ack-partial-fill` | `35=D` with `40=2` → ack, half, then the rest |
| `ack-accumulating-fills` | `35=D` with `40=2` → ack, then three fills that each read what the last one left |
| `order-reject-size` | `35=D` with `38 > 1000000` → rejected, `103=3` |
| `duplicate-clordid` | `35=D` whose ClOrdID the venue already holds live → rejected, `103=6` (two rules: `pending` and `working`) |
| `cancel-accepted` | `35=F` → pending cancel, then canceled, whatever the book holds |
| `cancel-accepted-working` | `35=F` **and the order is working** → pending cancel, then canceled |
| `cancel-rejected` | `35=F` → `35=9`, unknown order, whatever the book holds |
| `cancel-rejected-unknown` | `35=F` **and the order is unknown** → `35=9`, `102=1` |
| `replace-accepted` | `35=G` carrying `38` → replaced, with a **new** OrderID |
| `replace-accepted-same-id` | `35=G` **and the order is working** → replaced, **keeping** the chain's OrderID |
| `unsupported-message` | `35=H` → `35=j`, unsupported message type (a venue that answers no status requests) |
| `status-request-unknown` | `35=H` **and the order is unknown** → `35=j`, `380=1` Unknown ID |
| `status-request-working` | `35=H` **and the order is working** → `150=I` with the quantities the book holds |

**A preset chooses its own position, so it cannot be given an `index`.** Rules are first-match-wins,
so a preset that carries conditions goes **above the first enabled rule for its MsgType** — otherwise
`order-reject-size` added to a venue that already fills orders would sit below the fill and a
two-million-share order would fill rather than reject. An unconditioned preset appends, since it
answers everything of its type and would take the type from the rules already there. The response
says `placedAbove` and `placedBecause`; every rule keeps its up/down arrows. For the same
reason `GET /acceptor/rules` and every write report **`shadowedBy`** on a rule an earlier one already
answers in full. That claim is only made when it is provable — an earlier *enabled* rule for the same
MsgType with *no* conditions. Whether two conditioned rules overlap is not decidable in general, and
is left alone.

#### Rules that ask what the venue is holding

`whenOrder` is one more condition on a trigger, ANDed with the rest, and the only one no tag can
express: **what the venue was holding for the order this message names** — `41` if it names one, else
`11`. It takes one of four words: `unknown` (no such order on this session), `pending` (the venue has
it, the client has not been told anything yet), `working`, `done`. Omit it and the rule asks nothing,
which is what every rule written before this did.

It reads the state held **before** this message. That is what makes both of these writable, and they
are the same MsgType:

```bash
# a new order is acknowledged; a second use of the same ClOrdID is a duplicate
curl -s -XPOST $B/acceptor/rules -d '{"profile":"My Acceptor","preset":"duplicate-clordid"}'
# a cancel for an order nobody sent is rejected; one for a live order is accepted — same rule list
curl -s -XPOST $B/acceptor/rules -d '{"profile":"My Acceptor","preset":"cancel-rejected-unknown"}'
curl -s -XPOST $B/acceptor/rules -d '{"profile":"My Acceptor","preset":"cancel-accepted-working"}'
```

A dry run of such a rule has to assume a state, so `/acceptor/test` takes one — which is how "what
would this rule do if the order were already filled" is answered without arranging for an order to be
already filled. The answer names its assumption whether or not you gave one:

```bash
curl -s -XPOST $B/acceptor/test -d '{"profile":"My Acceptor","raw":"35=F|11=CXL-4|41=ORD-1|",
                                     "orderState":"working"}'
# → assumedOrderState {state:"working", order:"ORD-1", given:true}
#   per rule: whenOrder {constraint:"unknown", actual:"working", satisfied:false}
```

**A rule that asks the book cannot be judged without one, so it does not fire** — the same call
`compile` makes for a trigger it cannot parse, and the safe direction: a rule firing on messages its
author excluded is the dangerous way to be wrong. `GET /acceptor/orders` is the book itself,
and reports the `cap` each book is running — set in **Settings → Sessions → Order book**, applied to
books already open, with the oldest *finished* orders evicted first and every eviction counted.

#### Templates that read the book

A step's template can also **read** what the venue is holding: `${order.<name>}`, where the names are
`orderId`, `clOrdId`, `origClOrdId`, `symbol`, `side`, `orderQty`, `cumQty`, `leavesQty`, `avgPx`,
`price`, `ordStatus`. Names and not tag numbers on purpose — half of them are facts the venue
*computed* rather than fields of any message, and `${order.14}` would send a reader looking at the
wire for something that was never on it.

Both spellings work, exactly as `${req.…}` does: `${order.leavesQty}` is the value and
`${order.leavesQty / 2}` is arithmetic.

**Resolved per step, as that step is sent** — which is the difference from `${req.…}`, a fact about
the triggering message that cannot change. The book can, and does, *within one reply*:

```
35=8|150=0|151=${req.38}|                                    ack — 1000 open
35=8|150=F|14=${order.cumQty + order.leavesQty / 2}|…        +250ms → 14=500  151=500
35=8|150=F|14=${order.cumQty + order.leavesQty / 2}|…        +250ms → 14=750  151=250
35=8|150=F|14=${order.orderQty}|151=0|32=${order.leavesQty}| +250ms → 14=1000 151=0
```

Written statelessly, those three fills report the same `14=` three times and a client tracking CumQty
watches each one undo the last.

**A reply that reads the book has to be sure of getting one**, and that is checked on the rule rather
than at send time: set `whenOrder` to `pending`/`working`/`done`, *or* trigger on `35=D`, which brings
the order with it. Anything else is a `validationError`. If a reference cannot be resolved anyway — a
book cleared mid-sequence, an evicted order — the step **is not sent** and says why, rather than
putting `37=` on the wire as a real field with no value.

"Reply With…" applies the same rule with the message in hand: a shape reading the book against an
order the venue has not got is offered and greyed out with the reason, the way Fill is already
refused on a market order.

Two things every preset does that a hand-written rule should copy:

- **`${req.uuid}` for OrderID, `${uuid}` for ExecID.** The first is drawn once per triggering message,
  so every step of a sequence carries the same OrderID; the second resolves as each step is sent. An
  ack and its fill carrying different OrderIDs are two unrelated orders to a client tracking tag 37.
- **Never read a tag the trigger does not guarantee.** `${req.44}` against a market order substitutes
  nothing and puts `31=` on the wire, which is a malformed message the client gets blamed for. That
  is why the fill presets are conditioned on `40 = 2` and the replace preset requires `38`.

#### Editing a reply step in the message editor

A step is a raw FIX string, and reading one is the same problem writing one was. `POST /panel` opens
it in the message editor instead, where its tags carry dictionary names and its values carry enum
menus:

```bash
curl -s -XPOST $B/panel -d '{"panel":"editor","profile":"My Acceptor","rule":0,"step":1}'
curl -s -XPOST $B/panel -d '{"panel":"editor","action":"apply"}'    # or "cancel"
```

**Apply stages the step; it does not save it** — exactly as typing into the raw field would, so Save
is still what persists it and still the only thing a live venue notices. The response says
`saved:false` for that reason.

Two things are refused **by tag** rather than written, because the round trip is lossless only while
both ends agree what a field is: a value containing `|`, which would come back as two fields, and a
tag left with no value, which goes on the wire as `31=`.

The message being composed in the editor is not disturbed — it comes back when the step is applied or
cancelled.

#### Testing a rule without a counterparty

`POST /acceptor/test` runs a message through the rules and reports what would happen. It connects
nothing, sends nothing and saves nothing.

This is the fast authoring loop. Without it, checking a rule costs a full round trip through
reality — save, connect, arrange for someone to send the trigger, read the message list — and when
nothing comes back, that round trip has told you only that nothing came back. A typo'd tag, a
condition reading a field the message does not carry, a rule shadowed by an earlier one, a rule
switched off, and a rule on a profile that is not an acceptor all look identical from outside, and
each needs a different fix.

```bash
curl -s -XPOST $B/acceptor/test -d '{"profile":"My Acceptor",
  "raw":"35=D|11=ORD-1|55=EUR/USD|54=1|38=1000|"}'
```

For **every** rule it reports `matched`, and per condition the verdict plus `actual` — the value it
actually read off the message, with `absent: true` when the tag is not there at all, which is the
commonest cause of a rule that never fires and the one an empty string would hide. A rule that never
reached the matcher says `skipped` (disabled, or an unusable trigger); a rule that matched but lost
says `shadowedBy: <index>`. For the winner it renders the whole reply — each step's exact FIX text
with `${req.<tag>}` already substituted, and the offset it goes out at. `inactive` appears when the
profile is not an `ACCEPTOR`, in which case none of the rules would ever run.

The evaluation is the same code the wire uses (`AcceptorResponder.explain` shares its per-condition
judgement with `firstMatch`, and the reply comes from `plan`), so a dry run cannot pass where the
live session would do nothing. It reads the profile **as saved** — which is also what a connected
acceptor is running, since saving now applies to live sessions, so testing here and connecting there
cannot disagree about the ruleset.

#### What a running acceptor is doing

`GET /sessions` gives an `acceptor` block on any session whose profile is an `ACCEPTOR`:

```json
"acceptor": {"acceptPort":"9100","rulesLive":2,"latencyActive":true,
             "triggersMatched":3,"responsesSent":7,"pendingResponses":2}
```

`rulesLive` is how many rules are **compiled and in force**, which is not necessarily how many are
saved — a disabled or unusable rule is compiled away, and that difference is the answer to "I saved
it, why does nothing happen".

`triggersMatched` and `responsesSent` are cumulative and separate on purpose: the gap between them is
a state. `triggersMatched` ahead of `responsesSent` with `pendingResponses` non-zero means a reply
sequence has been triggered and is still playing out behind its delays — which, read from the message
log alone, looks exactly like a rule that never matched. Both only ever go up, so two reads can be
diffed rather than reasoned about.

The block is absent on initiators rather than reported as zeroes, for the same reason `discarded` is:
a field that reads 0 on every healthy session teaches a reader to stop looking at it.

#### Simulated latency

FixTool otherwise replies as fast as the machine allows, which is a latency no real venue has — so a
client whose timeout or retry logic is wrong passes here and fails in production. `acceptorLatency`
on the profile config puts the delay back:

```bash
curl -s -XPOST $B/profiles -d '{"id":"<id>","name":"My Acceptor","config":{"acceptorLatency":{
  "mode":"RANDOM_RANGE","minMillis":20,"maxMillis":80,
  "spikeProbability":0.05,"spikeMinMillis":2000,"spikeMaxMillis":5000}}}'
```

`mode` is `NONE` (default), `FIXED` (`fixedMillis`), `RANDOM_RANGE` (`minMillis`/`maxMillis`) or
`NORMAL` (`meanMillis`/`stdDevMillis`, clamped at zero). `spikeProbability` is independent of `mode`:
that fraction of replies stalls to `spikeMinMillis`–`spikeMaxMillis` **instead of** the ordinary
sample, so an otherwise instant venue can still stall occasionally.

One sample is drawn **per triggering message** and the rule's whole reply shifts by it; the authored
step-to-step gaps are left as written, so a sequence keeps its order and its shape and only its start
slides. The two add: a step that says "+500ms" means 500ms after the step before it however late the
reply started. `GET /acceptor/rules` reports the config, the millisecond range it adds, and any
setting that would do nothing.

### Message templates

Templates are reusable saved messages (`SavedFixMessage`), stored per profile and organised by
`userTags` and `isFavorite`. `POST /templates` creates or updates one from a `fields` array
(`[{tag, value, excluded?}]`) or a `raw` FIX string; `/templates/load` loads it into the
message editor so it can be reviewed, screenshotted, or sent.

> Note: in FixTool, a template's `userTags` double as its **profile associations** (which
> profiles see it, and which session it is loaded against). They default to the saving
> profile's id — include that id if you also add free-form labels.

Loading a template can change which session is active, so `/templates/load` followed by a
`/send` without an explicit `session` may not go where you expect:

- the active session stays put if it belongs to one of the template's profiles — a template
  that fits the session you are on never moves you off it;
- otherwise the best of the template's profiles is selected (connected first, then ones that
  own a session, then alphabetically) and its session becomes active;
- if that profile has never been connected it owns no session, so no session becomes active
  and the editor reports that the profile is not connected.

A template with no `userTags` (or whose profiles no longer exist) never touches the session
selection. `/templates/send` is unaffected by all of this: it sends to the `session` you name,
or to the active one.

```bash
# create a NewOrderSingle template, list, load into the editor
curl -s -XPOST $B/templates -d '{
  "profile":"My Server","name":"NOS GBP/USD","isFavorite":true,
  "fields":[{"tag":"35","value":"D"},{"tag":"55","value":"GBP/USD"},{"tag":"54","value":"1"},
            {"tag":"38","value":"1000000"},{"tag":"40","value":"2"},{"tag":"44","value":"1.2750"}]
}'
curl -s -G $B/templates --data-urlencode "profile=My Server"
curl -s -XPOST $B/templates/load -d '{"id":"<id-from-create>"}'
```

## End-to-end example (curl, self-contained)

```bash
B=http://127.0.0.1:8765

curl -s -XPOST $B/demo    -d '{"action":"start"}'
curl -s -XPOST $B/connect -d '{"profile":"Demo Client 1"}'
# wait until state is LOGGED_ON
until curl -s $B/sessions | grep -q LOGGED_ON; do sleep 0.5; done

curl -s -XPOST $B/send -d '{"raw":"8=FIX.4.4|35=D|11=ORD1|55=EUR/USD|54=1|38=1000000|40=1|60=20260624-21:47:47|"}'

# verify an ExecutionReport came back (35=8, 150=2 filled)
curl -s "$B/messages?session=0&direction=incoming&limit=5"

curl -s $B/screenshot -o window.png
```

## Driving it from Claude Code / MCP

See [`tools/fixtool-mcp/`](../tools/fixtool-mcp/README.md). A project `.mcp.json` registers
the `fixtool` MCP server, exposing the endpoints above as tools (`fixtool_connect`,
`fixtool_send`, `fixtool_get_messages`, `fixtool_screenshot`, …).
