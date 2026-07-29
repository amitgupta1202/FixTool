# FixTool MCP server (standalone, Node)

> **Most users don't need this.** FixTool **embeds an MCP server** in the app itself — enable it
> in Settings → Automation Control and connect with
> `claude mcp add --transport http fixtool http://127.0.0.1:8765/mcp` (no Node, no clone). See
> [`docs/AUTOMATION.md`](../../docs/AUTOMATION.md). This standalone Node server is a stdio-transport
> alternative, mainly for FixTool developers working in this repo.

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

Requires Node ≥ 18 (global `fetch`). The only dep is the MCP SDK.

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
| `FIXTOOL_CONTROL_RECHECK_MS` | `5000`                 | How often to re-check a FixTool that was down |

## Tools

**This server defines no tools of its own.** `tools/list` and `tools/call` are forwarded verbatim to
FixTool's own MCP endpoint, so the tool set is whatever the running app serves — see
`composeApp/src/jvmMain/kotlin/com/knapsack/fixtool/control/McpTools.kt`, or just ask the app:

```bash
curl -s -XPOST http://127.0.0.1:8765/mcp \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | jq '.result.tools[].name'
```

It used to declare all ~40 tools a second time, in JS, with a second copy of every description.
Nothing kept the copies honest and they drifted: after acceptor rules grew `steps`, `conditions` and
`enabled`, this server still described a rule as `{whenMsgType, whenFields?, responseTemplate}` — the
spelling from before sequences existed. Both files were internally consistent, so nothing anywhere
reported a disagreement; an agent simply got told to write rules in a shape that had not been current
for several releases. There is now one definition of every tool, next to the code that implements it.

While FixTool is not running this server advertises a single `fixtool_health` tool whose description
says so; it re-checks every `FIXTOOL_CONTROL_RECHECK_MS` and notifies the client when the real tool
list becomes available, so starting the app after Claude Code is fine.

## Example flow (self-contained, no external server)

1. `fixtool_demo { "action": "start" }`
2. `fixtool_connect { "profile": "Demo User 1" }`
3. Poll `fixtool_sessions` until state is `LOGGED_ON`
4. `fixtool_send { "raw": "8=FIX.4.4|35=D|11=ORD1|55=EUR/USD|54=1|38=1000000|40=1|60=20260624-21:47:47|" }`
5. `fixtool_get_messages { "session": "0", "direction": "incoming" }` → assert an
   ExecutionReport (`35=8`) with `150=2` (filled)

Verify on **message fields**, not pixels — `fixtool_screenshot` is for visual spot-checks.
