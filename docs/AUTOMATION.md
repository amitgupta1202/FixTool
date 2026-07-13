# Automating FixTool (control surface)

FixTool ships an optional, loopback-only HTTP **control surface** that lets external tools —
Claude Code via the [MCP server](../tools/fixtool-mcp/README.md), plain `curl`, or CI
scripts — drive a running instance for automated testing: connect sessions, send FIX
messages, read back parsed messages to verify fields, toggle the built-in demo server, and
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
`tools/fixtool-mcp/` remains as an alternative stdio transport for FixTool developers.

Optional shared-secret auth — when set, every request must carry an `X-Control-Token` header
with the same value:

```bash
FIXTOOL_CONTROL_PORT=8765 FIXTOOL_CONTROL_TOKEN=secret ./gradlew :composeApp:run
```

Implementation: `composeApp/src/jvmMain/kotlin/com/knapsack/fixtool/control/ControlServer.kt`,
started from `main.kt` via the `onViewModelCreated` hook. All ViewModel access is marshalled
onto the Swing EDT, since Compose state is EDT-bound.

## HTTP API

Base URL: `http://127.0.0.1:$FIXTOOL_CONTROL_PORT`. Request/response bodies are JSON.

| Method & path        | Body                                   | Returns                                              |
| -------------------- | -------------------------------------- | ---------------------------------------------------- |
| `GET /syntax`        | —                                      | `text/markdown`: the template-expression + matcher reference (see below) |
| `GET /health`        | —                                      | `{status, sessionCount, version}`                    |
| `GET /sessions`      | —                                      | array of sessions (index, id, title, state, …)       |
| `GET /profiles`      | —                                      | array of connection profiles                         |
| `POST /profiles`     | `{"name", "config":{…}, "id"?}`        | create (or update if `id` given) a profile → `{status, id, name}` |
| `DELETE /profiles`   | `{"id"}` (or `?id=`)                   | delete a profile (demo profiles are protected)       |
| `POST /panel`        | `{"panel":"connection\|editor\|detail\|settings\|scenarios", "show"?}` | show/hide a panel (`scenarios` opens the Scenarios window) |
| `GET /templates`     | query: `profile`?                      | list saved templates (name, type, userTags, isFavorite, fields) |
| `POST /templates`    | `{"profile", "name", "fields"\|"raw", "userTags"?, "isFavorite"?, "id"?}` | create/update a template |
| `DELETE /templates`  | `{"id", "profile"?}`                   | delete a template                                    |
| `POST /templates/load` | `{"id"}`                             | load a template into the editor (opens the editor panel) |
| `POST /demo`         | `{"action":"start"\|"stop"}`           | `{status, action, running}`                          |
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
| `GET /acceptor/rules` | query: `profile`                      | a profile's acceptor auto-response rules (set via `/profiles`) |
| `POST /mcp`          | JSON-RPC 2.0                           | embedded MCP server (initialize / tools/list / tools/call) |

`/admin` `action`: `seqnum` (read sender/target next seq nums), `reset-seqnum` (`sender`/`target`),
`test-request` (`id`), `resend-request` (`begin`/`end`), `sequence-reset` (`newSeq`/`gapFill`),
`logout` (`reason`), `disconnect` (`reason`, ungraceful). Used for session-recovery / gap-fill QA.
| `POST /select`       | `{"session"?, "index"?, "messageType"?, "direction"?}` | selects a message in the browser → opens the detail panel |
| `POST /assert`       | `{"session"?, "messageType"?, "direction"?, "index"?, "timeoutMs"?, "mode"?, "fields":[{tag, matcher}]}` | machine-checks a received message tag-by-tag → `{passed, tags:[{tag, matcher, expected, actual, passed, index, occurrence, status}]}`. `fields` is an **ordered** list: the *k*-th row for a tag asserts the *k*-th occurrence of it, and the rows must be a subsequence of the reply — do not sort or de-duplicate. A top-level `status` of `timeout` or `no-wire-bytes` means nothing was judged (`no-wire-bytes` is a FixTool limitation, not a venue failure). |
| `POST /expectation/capture` | `{"session"?, "messageType"?, "direction"?, "index"?}` | builds an auto-seeded expectation from a message → `{messageType, mode, fields:[…]}` |
| `GET /scenarios`     | query: `profile`?                      | list saved scenarios (id, name, profile, step counts, userTags) |
| `GET /scenarios?id=` | query: `id`                            | one scenario's **full JSON definition** — the exact shape `POST /scenarios` accepts, for read → edit → save round-trips |
| `POST /scenarios`    | scenario JSON `{name, steps:[…], setup?, teardown?, …}` | create/update a scenario (id generated if absent) |
| `DELETE /scenarios`  | `{"id"}`                               | delete a scenario                                   |
| `POST /scenarios/capture` | `{"name", "profile"?, "sessions"?}` | record the live message flow into a scenario (auto-parameterized, echoed ids wired to `reference` matchers) |
| `POST /scenarios/run` | `{"id"}` or `{"scenario":{…}}`, `format`? | run a scenario deterministically → per-step/per-tag report (or JUnit XML with `format:"junit"`) |
| `POST /detail`       | `{"query"?, "mode"?, "show"?}`         | drives the detail panel's tag search: sets the query and/or match-context `mode` (`bare`\|`identity`\|`full`) so a nested tag keeps its repeating-group context |
| `POST /search`       | `{"query", "pin"?}`                    | cross-session matches sorted chronologically (a timeline); pins to the search pane |
| `POST /filter`       | `{"scope"?, "session"?, "regex"?, "messageTypes"?, "showIncoming"?, "showOutgoing"?, "showSeparator"?}` | filters the grid for a focused screenshot |
| `GET /screenshot`    | —                                      | `image/png` bytes of the window                      |

`session` may be an index (`0`), an id, or a title. `direction` is `in`/`out` (or omitted).
Each message in `/messages` includes `timestamp`, `direction`, `messageType` (tag 35), the
`raw` string, and an ordered `fields` array of `{tag, value}`.

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
| `presence` | — | tag is present (value ignored) |
| `absent` | — | tag is not present |
| `regex` | `pattern` | value matches the pattern |
| `oneOf` | `values[]` | value ∈ set |
| `numeric` | `value`, `tolerance`? | `abs(actual − value) ≤ tolerance` (0 still ignores formatting) |
| `temporal` | `kind` (`today`\|`now_within_tolerance`), `toleranceSeconds`? | parsed as UTCTimestamp/UTCDate |
| `reference` | `expression` | equals a `${…}` expr resolved over session history, e.g. `${out.D.11}` |

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
`resetSeqNum {session?, sender?, target?}`. An `expect` consumes the message it matches, so a
partial-fill sequence is just successive `expect`s; `match {messageType?, direction?, fields:[{tag,
value}]}` selects by AND. Each step can target a different `session` (initiator + acceptor in one
scenario). Scenarios are stored one-file-per-scenario under `~/.fixtool/scenarios/`.

A send's `raw`, a `match` value and a `reference` matcher all resolve `${…}` expressions (above) —
always, with no `resolve` flag — over the one variable scope the run threads through every step. The
idiom is `11=${clOrdId = uuid}` in the send, then `{"type":"reference","expression":"${clOrdId}"}` to
assert the echo. `/scenarios/capture` (MCP: `fixtool_capture_scenario`) records a live flow into a
scenario that is already parameterized this way, which is usually faster than authoring one by hand.

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

### Setting up connections from scratch

`POST /profiles` is how an agent configures a connection — it writes the same profile the
connection panel produces, so the result is immediately connectable via `/connect`. The
`config` object only needs the fields that differ from the model defaults; everything in
`FixConnectionConfig` is accepted (host, port, senderCompID, targetCompID, beginString,
connectionType, heartBtInt, resetOnLogon, useSSL/keyStorePath/…, applVerID, sessionCount,
logonFields, …).

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

### Acceptor auto-responses

When FixTool runs as an **acceptor** (`connectionType: ACCEPTOR`), it can auto-respond to incoming
application messages using rules carried on the profile's config as `acceptorResponseRules`. Each
rule is `{whenMsgType, whenFields?, responseTemplate}`; the first rule whose `whenMsgType` (and every
`whenFields` entry, by exact value) matches the incoming message wins. The `responseTemplate` is raw
FIX (app fields only — QuickFIX stamps the session header) supporting `${req.<tag>}` (echo a request
field), `${uuid}`, and `${now}`. Rules are set via the normal `/profiles` upsert and inspected via
`GET /acceptor/rules`.

```bash
curl -s -XPOST $B/profiles -d '{
  "name":"My Acceptor",
  "config":{"connectionType":"ACCEPTOR","senderCompID":"ME","targetCompID":"THEM",
            "socketAcceptPort":"9100","beginString":"FIX.4.4",
            "acceptorResponseRules":[
              {"whenMsgType":"D",
               "responseTemplate":"35=8|150=0|39=0|37=${uuid}|11=${req.11}|55=${req.55}|38=${req.38}"}
            ]}
}'
curl -s -XPOST $B/connect -d '{"profile":"My Acceptor"}'   # now auto-acks any NewOrderSingle
```

### Message templates

Templates are reusable saved messages (`SavedFixMessage`), stored per profile and organised by
`userTags` and `isFavorite`. `POST /templates` creates or updates one from a `fields` array
(`[{tag, value, excluded?}]`) or a `raw` FIX string; `/templates/load` loads it into the
message editor so it can be reviewed, screenshotted, or sent.

> Note: in FixTool, a template's `userTags` double as its **profile associations** (which
> profiles see it and which session auto-selects when loaded). They default to the saving
> profile's id — include that id if you also add free-form labels.

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
curl -s -XPOST $B/connect -d '{"profile":"Demo User 1"}'
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
