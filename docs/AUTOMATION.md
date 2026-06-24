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

The server is **off by default**. It starts only when `FIXTOOL_CONTROL_PORT` is set, and
binds to `127.0.0.1` only.

```bash
FIXTOOL_CONTROL_PORT=8765 ./gradlew :composeApp:run
```

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
| `GET /health`        | —                                      | `{status, sessionCount, version}`                    |
| `GET /sessions`      | —                                      | array of sessions (index, id, title, state, …)       |
| `GET /profiles`      | —                                      | array of connection profiles                         |
| `POST /profiles`     | `{"name", "config":{…}, "id"?}`        | create (or update if `id` given) a profile → `{status, id, name}` |
| `DELETE /profiles`   | `{"id"}` (or `?id=`)                   | delete a profile (demo profiles are protected)       |
| `POST /panel`        | `{"panel":"connection\|editor\|detail\|settings", "show"?}` | show/hide a panel for screenshots |
| `GET /templates`     | query: `profile`?                      | list saved templates (name, type, userTags, isFavorite, fields) |
| `POST /templates`    | `{"profile", "name", "fields"\|"raw", "userTags"?, "isFavorite"?, "id"?}` | create/update a template |
| `DELETE /templates`  | `{"id", "profile"?}`                   | delete a template                                    |
| `POST /templates/load` | `{"id"}`                             | load a template into the editor (opens the editor panel) |
| `POST /demo`         | `{"action":"start"\|"stop"}`           | `{status, action, running}`                          |
| `POST /connect`      | `{"profile":"<name or id>"}`           | `{status, profile}` (logon is async)                 |
| `POST /disconnect`   | `{"profile":"<name or id>"}`           | `{status, profile}`                                  |
| `POST /send`         | `{"raw":"8=FIX.4.4|35=D|…", "session"?}` | `{status, result}` (QuickFIX SendResult)           |
| `GET /messages`      | query: `session`, `limit`, `direction` | `{session, total, messages:[…]}` with parsed fields  |
| `POST /select`       | `{"session"?, "index"?, "messageType"?, "direction"?}` | selects a message in the browser → opens the detail panel |
| `POST /search`       | `{"query", "pin"?}`                    | cross-session matches sorted chronologically (a timeline); pins to the search pane |
| `POST /filter`       | `{"scope"?, "session"?, "regex"?, "messageTypes"?, "showIncoming"?, "showOutgoing"?, "showSeparator"?}` | filters the grid for a focused screenshot |
| `GET /screenshot`    | —                                      | `image/png` bytes of the window                      |

`session` may be an index (`0`), an id, or a title. `direction` is `in`/`out` (or omitted).
Each message in `/messages` includes `timestamp`, `direction`, `messageType` (tag 35), the
`raw` string, and an ordered `fields` array of `{tag, value}`.

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
