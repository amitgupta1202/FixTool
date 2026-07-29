#!/usr/bin/env node
/**
 * FixTool MCP server — a transport shim in front of FixTool's own MCP endpoint.
 *
 * FixTool serves MCP itself, over Streamable HTTP at `/mcp` on the control port (see
 * ControlServer.kt and McpTools.kt). This process exists only to reach that endpoint over
 * stdio, for clients that launch their servers as subprocesses.
 *
 * ### It defines no tools of its own, on purpose
 *
 * It used to declare all ~37 tools a second time, in JS, with a second copy of every
 * description. Nothing kept the copies honest, and they drifted: after acceptor rules grew
 * `steps`, `conditions` and `enabled`, this file still described a rule as
 * `{whenMsgType, whenFields?, responseTemplate}` — the spelling from before sequences existed.
 * An agent reading it was told to write rules that had not been the recommended shape for
 * several releases, and nothing anywhere reported a disagreement, because both files were
 * internally consistent and nobody compared them.
 *
 * So `tools/list` and `tools/call` are forwarded verbatim. There is one definition of every
 * tool, in Kotlin, next to the code that implements it, and this file cannot go stale because
 * it no longer knows anything to be stale about.
 *
 * Prerequisites:
 *   1. Run FixTool with the control server enabled:
 *        FIXTOOL_CONTROL_PORT=8765 ./gradlew :composeApp:run
 *   2. (Optional) point this server elsewhere / set a token:
 *        FIXTOOL_CONTROL_URL=http://127.0.0.1:8765
 *        FIXTOOL_CONTROL_TOKEN=<must match the app's token, if set>
 *
 * Transport: stdio (launched by the MCP client). Run directly with `node index.mjs`.
 */

import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { CallToolRequestSchema, ListToolsRequestSchema } from "@modelcontextprotocol/sdk/types.js";

const BASE = (process.env.FIXTOOL_CONTROL_URL || "http://127.0.0.1:8765").replace(/\/$/, "");
const TOKEN = process.env.FIXTOOL_CONTROL_TOKEN || null;
const TIMEOUT_MS = Number(process.env.FIXTOOL_CONTROL_TIMEOUT_MS || 15000);
/** How often to re-check a FixTool that was down, so a late start still populates the tool list. */
const RECHECK_MS = Number(process.env.FIXTOOL_CONTROL_RECHECK_MS || 5000);

const DOWN_HINT =
  `Could not reach FixTool at ${BASE}. Start it with the control server enabled ` +
  `(FIXTOOL_CONTROL_PORT=8765 ./gradlew :composeApp:run), then try again — the full ` +
  `tool list appears once FixTool is running.`;

let rpcId = 0;

/** One JSON-RPC call to FixTool's /mcp endpoint. Throws if unreachable or if the app reports an error. */
async function rpc(method, params) {
  const headers = { "Content-Type": "application/json" };
  if (TOKEN) headers["X-Control-Token"] = TOKEN;
  const res = await fetch(`${BASE}/mcp`, {
    method: "POST",
    headers,
    body: JSON.stringify({ jsonrpc: "2.0", id: ++rpcId, method, params: params ?? {} }),
    signal: AbortSignal.timeout(TIMEOUT_MS),
  });
  if (!res.ok) throw new Error(`FixTool returned HTTP ${res.status} for ${method}`);
  const body = await res.json();
  if (body.error) throw new Error(body.error.message || `FixTool rejected ${method}`);
  return body.result ?? {};
}

/**
 * The one tool offered while FixTool is unreachable.
 *
 * A client caches the tool list from when it connected, and this process usually starts before
 * the app does. An empty list would leave the agent unable to discover that FixTool exists at
 * all, so it gets exactly one tool whose description says what is wrong and how to fix it —
 * and the poll below replaces the list the moment the app answers.
 */
const OFFLINE_TOOLS = [
  {
    name: "fixtool_health",
    description: `Check that the FixTool control server is reachable. ${DOWN_HINT}`,
    inputSchema: { type: "object", properties: {} },
  },
];

const server = new Server(
  { name: "fixtool", version: "2.0.0" },
  { capabilities: { tools: { listChanged: true } } },
);

let lastReachable = null;

server.setRequestHandler(ListToolsRequestSchema, async () => {
  try {
    const result = await rpc("tools/list");
    lastReachable = true;
    return { tools: result.tools ?? [] };
  } catch {
    lastReachable = false;
    return { tools: OFFLINE_TOOLS };
  }
});

server.setRequestHandler(CallToolRequestSchema, async (request) => {
  try {
    const result = await rpc("tools/call", {
      name: request.params.name,
      arguments: request.params.arguments ?? {},
    });
    lastReachable = true;
    return result;
  } catch (e) {
    lastReachable = false;
    // Reported as tool content rather than thrown, so the agent reads the hint and can act on
    // it. A protocol-level error would surface as a bare failure with nothing to do about it.
    return {
      content: [
        {
          type: "text",
          text: JSON.stringify({ status: "error", error: e.message, hint: DOWN_HINT }, null, 2),
        },
      ],
      isError: true,
    };
  }
});

await server.connect(new StdioServerTransport());

// Tell the client to re-list the moment FixTool comes up (or goes away). Without this, a client
// that connected while the app was down keeps the one-tool offline list for the whole session.
setInterval(async () => {
  let reachable;
  try {
    await rpc("ping");
    reachable = true;
  } catch {
    reachable = false;
  }
  if (lastReachable !== null && reachable !== lastReachable) {
    lastReachable = reachable;
    await server.sendToolListChanged().catch(() => {});
  } else {
    lastReachable = reachable;
  }
}, RECHECK_MS).unref();
