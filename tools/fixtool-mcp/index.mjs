#!/usr/bin/env node
/**
 * FixTool MCP server.
 *
 * Exposes FixTool's loopback control surface (see ControlServer.kt) as MCP tools so that
 * Claude Code — or any MCP client — can drive a running FixTool instance for automated
 * testing: connect sessions, send FIX messages, read back parsed messages and verify
 * fields, toggle the built-in demo server, and capture screenshots.
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

import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";

const BASE = (process.env.FIXTOOL_CONTROL_URL || "http://127.0.0.1:8765").replace(/\/$/, "");
const TOKEN = process.env.FIXTOOL_CONTROL_TOKEN || null;
const TIMEOUT_MS = Number(process.env.FIXTOOL_CONTROL_TIMEOUT_MS || 15000);

async function call(method, path, body) {
  const headers = {};
  if (TOKEN) headers["X-Control-Token"] = TOKEN;
  const opts = { method, headers, signal: AbortSignal.timeout(TIMEOUT_MS) };
  if (body !== undefined) {
    headers["Content-Type"] = "application/json";
    opts.body = JSON.stringify(body);
  }
  return fetch(BASE + path, opts);
}

/** Calls an endpoint and returns a pretty JSON (or raw) string wrapped as MCP text content. */
async function text(method, path, body) {
  let out;
  try {
    const res = await call(method, path, body);
    const raw = await res.text();
    try {
      out = JSON.stringify(JSON.parse(raw), null, 2);
    } catch {
      out = raw;
    }
  } catch (e) {
    out = JSON.stringify(
      {
        status: "error",
        error: `could not reach FixTool control server at ${BASE}: ${e.message}`,
        hint: "Is FixTool running with FIXTOOL_CONTROL_PORT set?",
      },
      null,
      2,
    );
  }
  return { content: [{ type: "text", text: out }] };
}

const server = new McpServer({ name: "fixtool", version: "1.0.0" });

server.tool(
  "fixtool_health",
  "Check that the FixTool control server is reachable; returns status and session count.",
  {},
  async () => text("GET", "/health"),
);

server.tool(
  "fixtool_sessions",
  "List all FIX sessions with index, id, title, connection state and message count.",
  {},
  async () => text("GET", "/sessions"),
);

server.tool(
  "fixtool_profiles",
  "List the available connection profiles (id, name, host, port, sender/target CompID).",
  {},
  async () => text("GET", "/profiles"),
);

server.tool(
  "fixtool_save_profile",
  "Create (or update, if id is given) a FIX connection profile so it can be connected with " +
    "fixtool_connect. config only needs fields that differ from defaults. Common config fields: " +
    'host, port, senderCompID, targetCompID, beginString (e.g. "FIX.4.4"), connectionType ' +
    '("INITIATOR"|"ACCEPTOR"), heartBtInt, resetOnLogon, useSSL, keyStorePath, socketAcceptPort ' +
    "(acceptor), applVerID (FIX5+), sessionCount (multi-session), logonFields ({tag: value}), and " +
    "acceptorResponseRules ([{whenMsgType, whenFields?, responseTemplate}]) for auto-responding as " +
    "an acceptor.",
  {
    name: z.string().describe("display name for the profile"),
    config: z.record(z.any()).describe("FixConnectionConfig fields (partial; defaults fill the rest)"),
    id: z.string().optional().describe("existing profile id to update; omit to create new"),
  },
  async ({ name, config, id }) =>
    text("POST", "/profiles", id === undefined ? { name, config } : { name, config, id }),
);

server.tool(
  "fixtool_delete_profile",
  "Delete a connection profile by id. Demo profiles are protected and cannot be deleted.",
  { id: z.string().describe("profile id") },
  async ({ id }) => text("DELETE", "/profiles", { id }),
);

server.tool(
  "fixtool_panel",
  "Show or hide a UI panel for verification screenshots: connection (the connection/profile " +
    "editor), editor (message editor), detail (message details), or settings.",
  {
    panel: z.enum(["connection", "editor", "detail", "settings"]),
    show: z.boolean().default(true),
  },
  async ({ panel, show }) => text("POST", "/panel", { panel, show }),
);

server.tool(
  "fixtool_list_templates",
  "List saved message templates (id, name, messageType, userTags, isFavorite, fields). Optionally " +
    "filter to one profile. Templates are reusable messages, organised by userTags and isFavorite.",
  { profile: z.string().optional().describe("profile id or name to filter by") },
  async ({ profile }) =>
    text("GET", profile ? `/templates?profile=${encodeURIComponent(profile)}` : "/templates"),
);

server.tool(
  "fixtool_save_template",
  "Create (or update, if id is given) a reusable message template under a profile. Provide the " +
    "message either as a fields array [{tag, value, excluded?}] or a raw FIX string. Organise with " +
    "userTags and isFavorite. The template then appears in the Saved Messages browser.",
  {
    profile: z.string().describe("profile id or name the template belongs to"),
    name: z.string().describe("template name"),
    fields: z
      .array(
        z.object({
          tag: z.union([z.string(), z.number()]),
          value: z.string(),
          excluded: z.boolean().optional(),
        }),
      )
      .optional()
      .describe("message fields; provide this or raw"),
    raw: z.string().optional().describe("raw FIX string; provide this or fields"),
    userTags: z.array(z.string()).optional().describe("organising tags (defaults to the profile)"),
    isFavorite: z.boolean().optional(),
    id: z.string().optional().describe("existing template id to update; omit to create"),
  },
  async (args) => {
    const body = {};
    for (const [k, v] of Object.entries(args)) if (v !== undefined) body[k] = v;
    return text("POST", "/templates", body);
  },
);

server.tool(
  "fixtool_delete_template",
  "Delete a saved template by id. Pass profile if it cannot be inferred from the template's tags.",
  {
    id: z.string().describe("template id"),
    profile: z.string().optional().describe("profile id or name"),
  },
  async ({ id, profile }) => text("DELETE", "/templates", profile === undefined ? { id } : { id, profile }),
);

server.tool(
  "fixtool_load_template",
  "Load a saved template into the message editor (and open the editor panel) so it can be reviewed, " +
    "screenshotted, or edited before sending.",
  { id: z.string().describe("template id") },
  async ({ id }) => text("POST", "/templates/load", { id }),
);

server.tool(
  "fixtool_demo",
  "Start or stop FixTool's built-in demo FIX server (an FX acceptor on port 19876). Starting it " +
    'registers "Demo User N" profiles you can connect to for self-contained tests.',
  { action: z.enum(["start", "stop"]).default("start") },
  async ({ action }) => text("POST", "/demo", { action }),
);

server.tool(
  "fixtool_connect",
  'Connect a profile by name or id (e.g. "Demo User 1"). Logon is asynchronous; poll ' +
    "fixtool_sessions until the session state is LOGGED_ON.",
  { profile: z.string().describe("profile name or id") },
  async ({ profile }) => text("POST", "/connect", { profile }),
);

server.tool(
  "fixtool_disconnect",
  "Disconnect a profile by name or id.",
  { profile: z.string().describe("profile name or id") },
  async ({ profile }) => text("POST", "/disconnect", { profile }),
);

server.tool(
  "fixtool_send",
  "Send a raw FIX message from a logged-on session. Use pipe- or SOH-delimited FIX, e.g. " +
    '"8=FIX.4.4|35=D|11=ORD1|55=EUR/USD|54=1|38=1000000|40=1|60=20260624-21:47:47|". ' +
    "QuickFIX/J re-stamps header fields (34/49/52/56) on send. With resolve=true, template " +
    'expressions in raw (${...}, {n}) are resolved against the session before sending.',
  {
    raw: z.string().describe("raw FIX message, pipe- or SOH-delimited"),
    session: z.string().optional().describe("session id, title or index; defaults to active session"),
    resolve: z.boolean().optional().describe("resolve template expressions before sending"),
  },
  async (args) => {
    const body = { raw: args.raw };
    if (args.session !== undefined) body.session = args.session;
    if (args.resolve !== undefined) body.resolve = args.resolve;
    return text("POST", "/send", body);
  },
);

server.tool(
  "fixtool_send_all",
  "Send one FIX message to every logged-on session at once (bulk send / load testing). Template " +
    "expressions are re-resolved per session. Returns a per-session result list.",
  { raw: z.string().describe("raw FIX message, pipe- or SOH-delimited") },
  async ({ raw }) => text("POST", "/send/all", { raw }),
);

server.tool(
  "fixtool_send_template",
  "Send a saved template (expressions resolved) from a session by id/title/index, or the active one.",
  {
    id: z.string().describe("template id"),
    session: z.string().optional().describe("session id, title or index; defaults to active session"),
  },
  async ({ id, session }) =>
    text("POST", "/templates/send", session === undefined ? { id } : { id, session }),
);

server.tool(
  "fixtool_clear_messages",
  "Clear a session's message log (useful between test phases so assertions see only new messages).",
  { session: z.string().describe("session id, title or index") },
  async ({ session }) => text("POST", "/messages/clear", { session }),
);

server.tool(
  "fixtool_wait",
  "Block until a session reaches a state (e.g. LOGGED_ON) or a matching message arrives, or until " +
    "timeoutMs elapses. The deterministic replacement for client-side polling loops. Provide state " +
    "or match (messageType / direction / tag+value). Returns the matched message, or status=timeout.",
  {
    session: z.string().describe("session id, title or index"),
    state: z.string().optional().describe('connection state to await, e.g. "LOGGED_ON"'),
    match: z
      .object({
        messageType: z.string().optional(),
        direction: z.enum(["in", "incoming", "out", "outgoing"]).optional(),
        tag: z.number().int().optional(),
        value: z.string().optional(),
      })
      .optional()
      .describe("message predicate to await"),
    timeoutMs: z.number().int().positive().optional().describe("max wait (default 10000, cap 120000)"),
  },
  async (args) => {
    const body = { session: args.session };
    if (args.state !== undefined) body.state = args.state;
    if (args.match !== undefined) body.match = args.match;
    if (args.timeoutMs !== undefined) body.timeoutMs = args.timeoutMs;
    return text("POST", "/wait", body);
  },
);

server.tool(
  "fixtool_get_messages",
  "Read parsed messages from a session for verification. Each message has timestamp, direction, " +
    'messageType (tag 35), the raw string, and an ordered "fields" array of {tag, value} — assert ' +
    "against these rather than screenshots.",
  {
    session: z.string().default("0").describe("session id, title or index"),
    limit: z.number().int().positive().default(50).describe("max messages, most recent last"),
    direction: z.enum(["in", "incoming", "out", "outgoing"]).optional().describe("filter by direction"),
  },
  async ({ session, limit, direction }) => {
    let path = `/messages?session=${encodeURIComponent(session)}&limit=${limit}`;
    if (direction) path += `&direction=${encodeURIComponent(direction)}`;
    return text("GET", path);
  },
);

server.tool(
  "fixtool_search",
  "Run a global (cross-session) search and get the matches sorted chronologically — a timeline of " +
    "every message matching the query, across all sessions. Also pins the results to the on-screen " +
    "search pane (unless pin=false) so a follow-up fixtool_screenshot shows them. Query is a regex " +
    "(falls back to substring). Great for tracing one order's lifecycle by ClOrdID.",
  {
    query: z.string().describe("regex or substring, e.g. a ClOrdID like \"CLAUDE-ORD-3\""),
    pin: z.boolean().default(true).describe("pin results to the visible search pane"),
  },
  async ({ query, pin }) => text("POST", "/search", { query, pin }),
);

server.tool(
  "fixtool_filter",
  "Apply a display filter so the message grid shows only matching rows — for a focused verification " +
    'screenshot. scope "global" (default) filters all sessions by regex/direction; scope "session" ' +
    "also supports messageTypes (e.g. \"D,8\") and showSeparator. Omitted fields are left unchanged.",
  {
    scope: z.enum(["global", "session"]).default("global"),
    session: z.string().optional().describe("session id, title or index (scope=session)"),
    regex: z.string().optional().describe("row filter regex; pass \"\" to clear"),
    messageTypes: z.string().optional().describe("comma-separated FIX msg types (scope=session)"),
    showIncoming: z.boolean().optional(),
    showOutgoing: z.boolean().optional(),
    showSeparator: z.boolean().optional().describe("scope=session only"),
  },
  async (args) => {
    const body = {};
    for (const [k, v] of Object.entries(args)) if (v !== undefined) body[k] = v;
    return text("POST", "/filter", body);
  },
);

server.tool(
  "fixtool_select",
  "Select a message in FixTool's message browser — opens the detail/inspection panel and switches " +
    "to the owning session tab (like clicking a row). Pick by index (default: most recent), " +
    "optionally narrowed by messageType (tag 35) and direction. Pair with fixtool_screenshot to " +
    "visually inspect the parsed message.",
  {
    session: z.string().default("0").describe("session id, title or index"),
    index: z.number().int().optional().describe("0-based index into matching messages; default last"),
    messageType: z.string().optional().describe("filter by FIX msg type, e.g. \"8\" for ExecutionReport"),
    direction: z.enum(["in", "incoming", "out", "outgoing"]).optional().describe("filter by direction"),
  },
  async ({ session, index, messageType, direction }) => {
    const body = { session };
    if (index !== undefined) body.index = index;
    if (messageType !== undefined) body.messageType = messageType;
    if (direction !== undefined) body.direction = direction;
    return text("POST", "/select", body);
  },
);

server.tool(
  "fixtool_screenshot",
  "Capture a PNG screenshot of the FixTool window for visual verification.",
  {},
  async () => {
    try {
      const res = await call("GET", "/screenshot");
      if (!res.ok) {
        return { content: [{ type: "text", text: `screenshot failed: HTTP ${res.status}` }] };
      }
      const buf = Buffer.from(await res.arrayBuffer());
      return { content: [{ type: "image", data: buf.toString("base64"), mimeType: "image/png" }] };
    } catch (e) {
      return { content: [{ type: "text", text: `screenshot failed: ${e.message}` }] };
    }
  },
);

server.tool(
  "fixtool_admin",
  "FIX session/admin control for recovery & gap-fill testing. action is one of: seqnum (read " +
    "current sender/target seq nums), reset-seqnum (sender/target), test-request (id), " +
    "resend-request (begin/end), sequence-reset (newSeq/gapFill), logout (reason), disconnect " +
    "(reason, ungraceful).",
  {
    session: z.string().describe("session id, title or index"),
    action: z.enum([
      "seqnum",
      "reset-seqnum",
      "test-request",
      "resend-request",
      "sequence-reset",
      "logout",
      "disconnect",
    ]),
    sender: z.number().int().optional(),
    target: z.number().int().optional(),
    id: z.string().optional().describe("TestReqID"),
    begin: z.number().int().optional(),
    end: z.number().int().optional().describe("0 = up to latest"),
    newSeq: z.number().int().optional(),
    gapFill: z.boolean().optional(),
    reason: z.string().optional(),
  },
  async (args) => {
    const body = {};
    for (const [k, v] of Object.entries(args)) if (v !== undefined) body[k] = v;
    return text("POST", "/admin", body);
  },
);

server.tool(
  "fixtool_validate",
  "Validate a raw FIX message against the loaded data dictionary; returns {isValid, errors}.",
  { raw: z.string().describe("raw FIX message, pipe- or SOH-delimited") },
  async ({ raw }) => text("POST", "/validate", { raw }),
);

server.tool(
  "fixtool_dictionary",
  "Read or switch the active FIX data dictionary. With no args, returns the current version and " +
    'validity. Pass a version (e.g. "FIX_4_4", "FIX_5_0_SP2") to switch to a bundled dictionary, or ' +
    "a path (+ optional transportPath for FIX 5.0+) to load a custom one.",
  {
    version: z.string().optional().describe("bundled FIX version name, beginString, or display name"),
    path: z.string().optional().describe("custom dictionary file path"),
    transportPath: z.string().optional().describe("FIXT transport dictionary path (FIX 5.0+)"),
  },
  async (args) => {
    if (args.version === undefined && args.path === undefined) return text("GET", "/dictionary");
    const body = {};
    for (const [k, v] of Object.entries(args)) if (v !== undefined) body[k] = v;
    return text("POST", "/dictionary", body);
  },
);

server.tool(
  "fixtool_acceptor_rules",
  "Inspect a profile's acceptor auto-response rules. (Rules are SET via fixtool_save_profile by " +
    "putting an acceptorResponseRules array in config; each rule is {whenMsgType, whenFields?, " +
    'responseTemplate} where the template echoes request fields via ${req.<tag>}, ${uuid}, ${now}.)',
  { profile: z.string().describe("profile id or name") },
  async ({ profile }) => text("GET", `/acceptor/rules?profile=${encodeURIComponent(profile)}`),
);

await server.connect(new StdioServerTransport());
