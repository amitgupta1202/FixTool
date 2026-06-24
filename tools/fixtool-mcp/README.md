# FixTool MCP server

An [MCP](https://modelcontextprotocol.io) server that lets Claude Code (or any MCP client)
drive a running FixTool instance for automated testing — connect sessions, send FIX
messages, read back parsed messages to verify fields, toggle the built-in demo server, and
capture screenshots.

It is a thin wrapper over FixTool's HTTP **control surface** (see
`composeApp/src/jvmMain/kotlin/com/knapsack/fixtool/control/ControlServer.kt`). That control
server is **off by default** and only starts when the app is launched with the
`FIXTOOL_CONTROL_PORT` environment variable, so normal/production runs are unaffected. It
binds to `127.0.0.1` only.

## Setup

```bash
cd tools/fixtool-mcp
npm install
```

Requires Node ≥ 18 (global `fetch`). Only deps are the MCP SDK and zod.

## Run FixTool with the control surface enabled

```bash
FIXTOOL_CONTROL_PORT=8765 ./gradlew :composeApp:run
```

Optionally require a shared token (the MCP server must send the same one):

```bash
FIXTOOL_CONTROL_PORT=8765 FIXTOOL_CONTROL_TOKEN=secret ./gradlew :composeApp:run
```

## Register with Claude Code

A project-scoped `.mcp.json` at the repo root already registers this server, so Claude Code
picks it up automatically when you open the repo (approve it once when prompted). To register
manually instead:

```bash
claude mcp add fixtool -- node tools/fixtool-mcp/index.mjs
```

## Configuration (env)

| Variable                   | Default                  | Purpose                                    |
| -------------------------- | ------------------------ | ------------------------------------------ |
| `FIXTOOL_CONTROL_URL`      | `http://127.0.0.1:8765`  | Where the FixTool control server listens   |
| `FIXTOOL_CONTROL_TOKEN`    | _(none)_                 | Sent as `X-Control-Token`; must match app  |
| `FIXTOOL_CONTROL_TIMEOUT_MS` | `15000`                | Per-request timeout                        |

## Tools

| Tool                    | What it does                                                            |
| ----------------------- | ---------------------------------------------------------------------- |
| `fixtool_health`        | Liveness check + session count                                         |
| `fixtool_sessions`      | List sessions (index, id, title, state, message count, comp IDs)       |
| `fixtool_profiles`      | List connection profiles                                               |
| `fixtool_save_profile`  | Create/update a connection profile (set up a connection from scratch)  |
| `fixtool_delete_profile`| Delete a connection profile by id                                      |
| `fixtool_panel`         | Show/hide a UI panel (connection, editor, detail, settings)            |
| `fixtool_list_templates`| List saved message templates (optionally per profile)                  |
| `fixtool_save_template` | Create/update a reusable template (from fields or a raw FIX string)     |
| `fixtool_delete_template`| Delete a template by id                                               |
| `fixtool_load_template` | Load a template into the message editor                                |
| `fixtool_demo`          | Start/stop the built-in demo FIX server (registers "Demo User N")      |
| `fixtool_connect`       | Connect a profile by name or id                                        |
| `fixtool_disconnect`    | Disconnect a profile                                                   |
| `fixtool_send`          | Send a raw FIX message from a session                                  |
| `fixtool_get_messages`  | Read parsed messages (ordered `{tag, value}` fields) for verification  |
| `fixtool_select`        | Select a message in the browser → opens the detail/inspection panel    |
| `fixtool_search`        | Cross-session search → chronological timeline; pins to the search pane  |
| `fixtool_filter`        | Filter the grid (global or per-session) for a focused screenshot        |
| `fixtool_screenshot`    | Capture a PNG of the window                                            |

## Example flow (self-contained, no external server)

1. `fixtool_demo { "action": "start" }`
2. `fixtool_connect { "profile": "Demo User 1" }`
3. Poll `fixtool_sessions` until state is `LOGGED_ON`
4. `fixtool_send { "raw": "8=FIX.4.4|35=D|11=ORD1|55=EUR/USD|54=1|38=1000000|40=1|60=20260624-21:47:47|" }`
5. `fixtool_get_messages { "session": "0", "direction": "incoming" }` → assert an
   ExecutionReport (`35=8`) with `150=2` (filled)

Verify on **message fields**, not pixels — `fixtool_screenshot` is for visual spot-checks.
