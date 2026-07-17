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
  "fixtool_syntax",
  "The reference for FixTool's two mini-languages, as markdown: template expressions (${uuid}, " +
    "${now+1d}, ${out.D.11}, ${var = ...} — how to parameterize what you send, and which contexts " +
    "resolve them) and matchers (how to assert what came back — an expectation is an ORDERED list, " +
    "the k-th row for a tag asserts the k-th occurrence of it, and there is no 'path'). Read this " +
    "BEFORE authoring a scenario, a templated message, or an expectation — it is the only complete " +
    "statement of either grammar.",
  {},
  async () => text("GET", "/syntax"),
);

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
    "editor), editor (message editor), detail (message details), settings, or scenarios (the " +
    "Scenarios rail, docked in the main window).",
  {
    panel: z.enum(["connection", "editor", "detail", "settings", "scenarios"]),
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
    "QuickFIX/J re-stamps header fields (34/49/52/56) on send. With resolve=true the ${...} template " +
    "expressions in raw are resolved against the session first (${uuid}, ${now}, ${out.D.11}, and the " +
    "per-session ${sessionIndex}/${sessionQualifier}/${sessionTitle}/${sessionSenderCompID}); see " +
    "fixtool_syntax. Without resolve, raw is sent verbatim — an unresolved ${uuid} goes on the wire as " +
    "that literal text.",
  {
    raw: z.string().describe("raw FIX message, pipe- or SOH-delimited"),
    session: z.string().optional().describe("session id, title or index; defaults to active session"),
    resolve: z.boolean().optional().describe("resolve ${...} expressions before sending; default false"),
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
    "against these rather than screenshots. Each message also carries wireOrderKnown: when false, FixTool " +
    "has no wire bytes for it, so fields[] is a best-effort read in QuickFIX's field order rather than the " +
    "venue's — and fixtool_assert will REFUSE that message. Do not build an expectation from one.",
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
  "fixtool_assert",
  "Assert a received message against an expectation — the machine-check that replaces eyeballing a " +
    "response. Selects the message like fixtool_select (by messageType/direction/index), or awaits one " +
    "for up to timeoutMs. Returns {passed, tags:[{tag, matcher, expected, actual, passed, index, " +
    "occurrence, status}]}; a tag's status is ok|value|missing|unexpected|moved|invalid.\n\n" +
    "A TOP-LEVEL status means nothing was judged: 'timeout' (no matching message arrived) or " +
    "'no-wire-bytes' (FixTool could not read the message's bytes, so it does not know the venue's field " +
    "order and REFUSES to evaluate an order-sensitive assertion against a guess). On no-wire-bytes, tags[] " +
    "is empty and passed is false — but the fault is FixTool's, NOT the venue's. Do not report it as a " +
    "venue regression.\n\n" +
    "ORDER MATTERS. fields[] is an ORDERED list of rows {tag, matcher} — there is NO `path`:\n" +
    "  * The k-th row for a tag asserts the k-th occurrence of that tag. Two party entries = two 448 " +
    "rows and two 452 rows; the second 452 row checks the second entry. Position is the address.\n" +
    "  * Your rows must be a SUBSEQUENCE of the message: they must appear in the order you list them, " +
    "with anything else allowed in between. If the venue sends 37 before 11 and you list 11 before 37, " +
    "the step FAILS with status=moved. List rows in the order the venue sends them — or call " +
    "fixtool_capture_expectation, which seeds them in wire order for you.\n" +
    "  * Do not sort or de-duplicate fields[].\n\n" +
    "mode=open asserts only the listed rows (a tag you do not mention is ignored). mode=strict also " +
    "asserts the message shape: same tags, same count, same order.\n\n" +
    "Matcher types: exact (value), presence, absent, regex (pattern), oneOf (values[]), numeric " +
    "(value, tolerance?), temporal (kind today|now_within_tolerance, toleranceSeconds?), reference " +
    "(expression, e.g. ${out.D.11} — see fixtool_syntax). An `absent` row asserts the tag appears " +
    "nowhere, and takes no part in the ordering.",
  {
    session: z.string().default("0").describe("session id, title or index"),
    messageType: z.string().optional().describe("FIX msg type to select/await, e.g. \"8\""),
    direction: z.enum(["in", "incoming", "out", "outgoing"]).optional().describe("filter by direction; default in"),
    index: z.number().int().optional().describe("0-based index into matching messages; default last"),
    timeoutMs: z.number().int().optional().describe("await a matching message up to this long; default 0 = already received"),
    mode: z.enum(["open", "strict"]).optional().describe("open (default) asserts only listed tags; strict fails on extras"),
    fields: z.array(z.record(z.any())).describe("ORDERED rows [{tag, matcher:{type,...}}] in the order the venue sends them; the k-th row for a tag asserts the k-th occurrence of it. No `path`. Do not sort or de-duplicate."),
  },
  async (args) => {
    const body = {};
    for (const [k, v] of Object.entries(args)) if (v !== undefined) body[k] = v;
    return text("POST", "/assert", body);
  },
);

server.tool(
  "fixtool_capture_expectation",
  "Build an auto-seeded expectation from a received message: matchers pre-seeded from dictionary " +
    "field types (timestamps -> temporal, prices/quantities -> numeric, else exact). Asserted for " +
    "PRESENCE only, never value, because the value belongs to this environment or this moment: " +
    "OrderID(37), ExecID(17), the routing addresses 50/57/115/128/142/143/144/145, and " +
    "OrigSendingTime(122) — tighten one to exact if the scenario is about routing. NOT asserted at " +
    "all, and listed in notAsserted: the session envelope 8/9/10/34/49/52/56/369. " +
    "Rows come back in WIRE ORDER, one per occurrence of each tag — the order is part of the " +
    "assertion (see fixtool_assert), so pass fields[] through unsorted and un-de-duplicated. " +
    "Selects by messageType/direction/index like fixtool_select. Returns " +
    "{messageType, mode, fields:[...], notAsserted:[...]} ready to edit and pass to fixtool_assert. " +
    "Returns an error instead when FixTool has no wire bytes for the message: without the venue's field " +
    "order, a seeded expectation would assert an order the venue never sent.",
  {
    session: z.string().default("0").describe("session id, title or index"),
    messageType: z.string().optional().describe("FIX msg type to select, e.g. \"8\""),
    direction: z.enum(["in", "incoming", "out", "outgoing"]).optional().describe("filter by direction; default in"),
    index: z.number().int().optional().describe("0-based index into matching messages; default last"),
  },
  async (args) => {
    const body = {};
    for (const [k, v] of Object.entries(args)) if (v !== undefined) body[k] = v;
    return text("POST", "/expectation/capture", body);
  },
);

server.tool(
  "fixtool_detail_search",
  "Drive the message detail panel's tag search. Set query (tag number, field name, value or enum " +
    "text) and/or mode: bare = matched rows only (legacy); identity = each matching repeating-group " +
    "entry also shows its identity field (e.g. PartyID) so you can tell which entry matched; full = " +
    "the whole matching entry. Pair with fixtool_select + fixtool_screenshot to inspect a nested tag " +
    "(e.g. PartyRole across many parties) without losing context. show reveals the panel.",
  {
    query: z.string().optional().describe("tag number, field name, value, or enum text to search"),
    mode: z.enum(["bare", "identity", "full"]).optional().describe("match-context mode"),
    show: z.boolean().optional().describe("reveal the detail panel"),
  },
  async (args) => {
    const body = {};
    for (const [k, v] of Object.entries(args)) if (v !== undefined) body[k] = v;
    return text("POST", "/detail", body);
  },
);

server.tool(
  "fixtool_screenshot",
  "Capture a PNG screenshot of a FixTool window for visual verification. window picks which: main " +
    "(default), diff (the reconcile/diff window), or a substring of a window's title.",
  { window: z.string().optional().describe("which window: main (default), diff, or a title substring") },
  async ({ window }) => {
    try {
      const path = window ? `/screenshot?window=${encodeURIComponent(window)}` : "/screenshot";
      const res = await call("GET", path);
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
    "responseTemplate}. A responseTemplate understands a restricted subset of the template language — " +
    "${req.<tag>} to echo a request field, ${uuid} and ${now} — and nothing else; see fixtool_syntax.)",
  { profile: z.string().describe("profile id or name") },
  async ({ profile }) => text("GET", `/acceptor/rules?profile=${encodeURIComponent(profile)}`),
);

server.tool(
  "fixtool_save_scenario",
  "Save a repeatable scenario: an ordered sequence of sends + assertions a deterministic runner " +
    "replays (no LLM in the loop). Steps {type,...}: send {raw, session?}; wait {session?, state?, " +
    "match?, timeoutMs?}; expect {session?, direction?, match?, timeoutMs?, expectation:{messageType?, " +
    "mode?, fields:[{tag, matcher}]}}; clearMessages {session?}; resetSeqNum {session?, sender?, " +
    "target?}. match is {messageType?, direction?, fields:[{tag, value}]} (AND). Omit id to create. " +
    "PARAMETERIZE IT: a send's raw, a match value and a reference matcher all resolve ${...} " +
    "expressions — always, with no resolve flag — over one variable scope that persists across every " +
    "step. The idiom is 11=${clOrdId = uuid} in the send, then assert the echo with " +
    '{"type":"reference","expression":"${clOrdId}"}. The REVERSE (venue chooses the value): an ' +
    'expectation row may carry bindAs — {"tag":131,"matcher":{"type":"presence"},"bindAs":"qr"} ' +
    'captures the paired value into the scope, and a later send echoes it with 131=${qr}. ' +
    'traffic:"strict" additionally fails the run if, after the last step (plus a settle window), any ' +
    'incoming application-level message was never bound by an expect — "the venue sent nothing else" ' +
    '(session admin exempt; default "open" ignores unbound messages). Call ' +
    "fixtool_syntax for the full grammar, or fixtool_capture_scenario to record one that is already " +
    "correctly templated.",
  {
    name: z.string().describe("scenario name"),
    id: z.string().optional().describe("existing scenario id to update"),
    profile: z.string().optional().describe("connection profile id/name this scenario targets"),
    userTags: z.array(z.string()).optional().describe("organising tags (also per-profile filtering)"),
    traffic: z
      .enum(["open", "strict"])
      .optional()
      .describe("stream-level mode: strict = no unbound incoming app messages; default open"),
    setup: z.array(z.record(z.any())).optional().describe("steps run before steps"),
    steps: z.array(z.record(z.any())).describe("the ordered steps"),
    teardown: z.array(z.record(z.any())).optional().describe("steps run after steps, even on failure"),
  },
  async (args) => {
    const body = {};
    for (const [k, v] of Object.entries(args)) if (v !== undefined) body[k] = v;
    return text("POST", "/scenarios", body);
  },
);

server.tool(
  "fixtool_capture_scenario",
  "Record the current session message flow into a replayable scenario — the capture-driven way to " +
    "author one, and the quickest route to a correctly-templated scenario. Captures across one or " +
    "more sessions (RFQ / multi-session friendly): outgoing app messages become send steps " +
    "(TransactTime and correlation IDs auto-parameterized), incoming become expect steps with " +
    "smart-seeded matchers, and any echoed id is auto-wired to a reference matcher. Admin messages " +
    "are skipped; setup clears each session. Saved to the store.",
  {
    name: z.string().describe("scenario name"),
    profile: z.string().optional().describe("connection profile id/name to tag the scenario with"),
    sessions: z
      .array(z.string())
      .optional()
      .describe("session ids/titles/indices to capture; default all"),
  },
  async (args) => {
    const body = {};
    for (const [k, v] of Object.entries(args)) if (v !== undefined) body[k] = v;
    return text("POST", "/scenarios/capture", body);
  },
);

server.tool(
  "fixtool_capture_paste",
  "Capture a scenario from PASTED WIRE — a server log fragment, one message per line — with no live " +
    "session at all. The same reader the paste sheet uses: a line whose reading the bytes disprove (a " +
    "'|' inside a value, a checksum that disagrees) is REFUSED and reported, never guessed. Direction " +
    "is read from SenderCompID(49) against the given CompIDs; a message whose direction nothing settles " +
    "blocks the save, because a reply saved as a send asserts nothing. Every step is badged 'pasted'. " +
    "Returns {status,id,steps,pasted,refused[]} or {status:refused,undirected[]}.",
  {
    name: z.string().describe("scenario name"),
    wire: z.string().describe("the pasted bytes — one FIX message per line (SOH or '|' delimited)"),
    session: z.string().optional().describe("session id/title to assign, whose CompIDs settle direction"),
    senderCompId: z.string().optional().describe("our CompID (overrides the session's) — 49 equal to this is outgoing"),
    targetCompId: z.string().optional().describe("the venue's CompID (overrides the session's) — 49 equal to this is incoming"),
    profile: z.string().optional().describe("connection profile id/name to tag the scenario with"),
  },
  async (args) => {
    const body = {};
    for (const [k, v] of Object.entries(args)) if (v !== undefined) body[k] = v;
    return text("POST", "/scenarios/capture-paste", body);
  },
);

server.tool(
  "fixtool_list_scenarios",
  "List saved scenarios (id, name, profile, step counts, userTags), optionally filtered by profile.",
  { profile: z.string().optional().describe("profile id, name, or tag to filter by") },
  async ({ profile }) =>
    text("GET", profile === undefined ? "/scenarios" : `/scenarios?profile=${encodeURIComponent(profile)}`),
);

server.tool(
  "fixtool_get_scenario",
  "Fetch one saved scenario's full JSON definition by id — the exact shape fixtool_save_scenario " +
    "accepts, so a scenario can be read, edited, and saved back losslessly.",
  { id: z.string().describe("scenario id (from fixtool_list_scenarios)") },
  async ({ id }) => text("GET", `/scenarios?id=${encodeURIComponent(id)}`),
);

server.tool(
  "fixtool_run_scenario",
  "Run a scenario deterministically and return a per-step, per-tag pass/fail report. Identify by id " +
    "(from the store) or pass an inline scenario. format=junit returns JUnit XML for CI. When the run " +
    "minted any ${...} variables, the report carries variables:[{name,value,mintedAtStepId?}] — what " +
    "each name held, in mint order. sessions re-aims THIS run at other session names (e.g. the QA pair) " +
    "without persisting anything; sessions the run needs are auto-connected from saved profiles. To keep " +
    "an environment durably, save a remapped copy of the scenario instead.",
  {
    id: z.string().optional().describe("saved scenario id"),
    scenario: z.record(z.any()).optional().describe("inline scenario JSON (alternative to id)"),
    format: z.enum(["json", "junit"]).optional().describe("output format; default json"),
    sessions: z.record(z.string()).optional().describe("throwaway {from: to} session remap applied for this run only"),
  },
  async (args) => {
    const body = {};
    for (const [k, v] of Object.entries(args)) if (v !== undefined) body[k] = v;
    return text("POST", "/scenarios/run", body);
  },
);

server.tool(
  "fixtool_reconcile",
  "Open the reconcile diff on a step that failed the last run — the surface that repairs an assertion. " +
    "With no argument it takes the run's first failing step, exactly as the rail's 'Reconcile \u2192' does; " +
    "step=N addresses one by its 1-based position. Routes through the same check the button does, so a step " +
    "edited since it ran is refused with the reason. The open response repeats the run's variables " +
    "([{name,value,mintedAtStepId?}]) so a reference row's ${id0} can be read while repairing. Pair with " +
    "fixtool_screenshot to see it.",
  { step: z.number().int().optional().describe("1-based step number; default = the first failing step") },
  async ({ step }) => text("POST", "/scenarios/reconcile", step === undefined ? {} : { step }),
);

server.tool(
  "fixtool_diff",
  "Open the plain diff VIEWER on two messages — a read-only FIX diff (=/≠/+A/+B), no assertions, nothing " +
    "that writes. Each side is a PICK ({session, match:{messageType,tag,value,direction}} — a live message, by " +
    "its wire bytes) or a PASTE ({paste:'<bytes>'} — read through the same reader the slot uses, so a '|' inside " +
    "a value is refused, not guessed). A side with no wire bytes is refused. The window's title is " +
    "'diff: A vs B', so photograph it with fixtool_screenshot window='diff:'. Returns {status:open,subject} or an error.",
  {
    a: z.record(z.any()).describe("side A: {session, match} to pick a live message, or {paste} for pasted bytes"),
    b: z.record(z.any()).describe("side B: {session, match} to pick a live message, or {paste} for pasted bytes"),
  },
  async ({ a, b }) => text("POST", "/scenarios/diff", { a, b }),
);

server.tool(
  "fixtool_delete_scenario",
  "Delete a saved scenario by id.",
  { id: z.string().describe("scenario id") },
  async ({ id }) => text("DELETE", "/scenarios", { id }),
);

await server.connect(new StdioServerTransport());
