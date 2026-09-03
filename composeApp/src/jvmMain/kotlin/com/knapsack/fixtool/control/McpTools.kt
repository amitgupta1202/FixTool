// A schema registry: tool descriptions and single-line property lists read best unwrapped.
@file:Suppress("MaxLineLength")

package com.knapsack.fixtool.control

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The authoritative MCP tool definitions (names, descriptions, JSON-Schema inputs) served by the
 * app's embedded MCP server (`/mcp`, see [ControlServer]). Each tool maps 1:1 onto a control
 * endpoint; [ControlServer] holds the dispatch table that runs them. The standalone Node server
 * (`tools/fixtool-mcp`) mirrors these for the stdio transport.
 */
object McpTools {
    val tools: List<JsonObject> =
        listOf(
            tool(
                "fixtool_syntax",
                "The reference for FixTool's two mini-languages, as markdown: template expressions " +
                    "(\${uuid}, \${now+1d}, \${out.D.11}, \${var = ...} — how to parameterize what you send, and " +
                    "which contexts resolve them) and matchers (how to assert what came back: fields[] is an ORDERED " +
                    "list, the k-th row for a tag asserts the k-th occurrence of it, and there is no 'path'). " +
                    "Read this BEFORE authoring a scenario, a templated message, or an " +
                    "expectation — it is the only complete statement of either grammar.",
            ),
            tool("fixtool_health", "Check that the control server is reachable; returns status and session count."),
            tool(
                "fixtool_sessions",
                "List all FIX sessions with index, id, title, connection state and message count. An ACCEPTOR " +
                    "session also reports an `acceptor` block: acceptPort, rulesLive (compiled and in force — a " +
                    "disabled or unusable rule is compiled away, so this can be lower than the number saved), " +
                    "latencyActive, triggersMatched, responsesSent and pendingResponses. triggersMatched ahead of " +
                    "responsesSent with pendingResponses non-zero means a reply sequence is still playing out — " +
                    "which from the message log alone looks identical to a rule that never matched.",
            ),
            tool(
                "fixtool_profiles",
                "List the connection profiles (id, name, host, port, sender/target CompID), or pass `profile` " +
                    "(id or name) for ONE profile's whole config — every field, including acceptorResponseRules, " +
                    "acceptorLatency, logonFields and the SSL settings. Read this before editing a profile: the " +
                    "list form is a summary and does not carry enough to preserve what you are not changing. " +
                    "Passwords read back as [REDACTED]; post that value again (or omit the key) to leave them alone.",
                props("profile" to string("id or name; omit to list all")),
            ),
            tool(
                "fixtool_save_profile",
                "Create (or update, if id is given) a FIX connection profile so it can be connected. " +
                    "UPDATING MERGES: a config sent with an id sets the keys it carries and leaves every other " +
                    "key as it was, so adding one setting cannot wipe the rest — pass replace:true for the old " +
                    "whole-config-replacement behaviour. An explicitly sent value always wins, so " +
                    "\"acceptorResponseRules\": [] does clear the rules. " +
                    "config only needs fields that differ from defaults: host, port, senderCompID, targetCompID, " +
                    "beginString, connectionType (INITIATOR|ACCEPTOR), heartBtInt, resetOnLogon, useSSL, " +
                    "socketAcceptPort (acceptor), sessionCount, logonFields, acceptorResponseRules " +
                    "([{whenMsgType, conditions?, enabled?, steps:[{template, delayMillis}]}]) for auto-responding " +
                    "as an acceptor — see fixtool_acceptor_rules for the full shape, and fixtool_acceptor_rule to " +
                    "edit one rule at a time — and acceptorLatency to give those replies a realistic delay " +
                    "({mode:NONE|FIXED|RANDOM_RANGE|NORMAL, fixedMillis, minMillis, maxMillis, meanMillis, " +
                    "stdDevMillis, spikeProbability, spikeMinMillis, spikeMaxMillis}). " +
                    "Returns the keys it applied, whether it merged or replaced, warnings for any acceptor rule " +
                    "or latency setting that cannot work, and appliedToLiveSessions when the save also took " +
                    "effect on an already-connected acceptor (rule and latency edits apply without reconnecting; " +
                    "CompID/port/SSL changes still need one).",
                props(
                    "name" to string("display name"),
                    "config" to objectSchema("FixConnectionConfig fields (partial; merged into the existing config when id is given)"),
                    "id" to string("existing id to update"),
                    "replace" to boolean("true = replace the whole config instead of merging (default false)"),
                ),
                required = listOf("name", "config"),
            ),
            tool(
                "fixtool_delete_profile",
                "Delete a connection profile by id (demo profiles are protected).",
                props("id" to string("profile id")),
                required = listOf("id"),
            ),
            tool(
                "fixtool_panel",
                "Show or hide a UI pane for verification screenshots: connection, editor, detail, settings, " +
                    "scenarios (the Scenarios rail, docked in the main window) or orderbook (what the selected " +
                    "acceptor session is holding, with each order's trail — see fixtool_acceptor_orders for the " +
                    "same data as JSON). " +
                    "`connection` takes a `profile` to load onto the form, as clicking it in the list does. " +
                    "`editor` with a `profile` and a `rule` (and optional `step`, default 0) opens that acceptor " +
                    "rule's reply step in the message editor, where its tags carry dictionary names and its values " +
                    "carry enum menus — the same hand-off the button on the step row performs, which is otherwise " +
                    "reachable only with a mouse. Finish it with action:apply (writes the step back to the staged " +
                    "rule; Save still persists it, and the response says saved:false) or action:cancel. Apply " +
                    "refuses, by tag, a value carrying the '|' field separator or a tag left with no value — both " +
                    "would put a malformed message on the wire. " +
                    "`trace` opens the Trace panel (the Ledger: every exchange across every session, with its " +
                    "session count and the gap between its messages). Add `follow` with a whole correlation " +
                    "value to narrow every pane to that one exchange — the same set fixtool_trace returns — or " +
                    "`follow: null` to stop. Following opens the panel; show:false closes it without unfollowing. " +
                    "`render` switches the same rows between the two drawings: `ledger` (the grid, every trace) " +
                    "or `lanes` (one column per session, time down, the followed trace only — so follow " +
                    "something first or it shows the list to pick from). Combines with follow and show; the " +
                    "response always says which drawing is on screen.",
                props(
                    "panel" to
                        enumStr("connection", "editor", "detail", "settings", "scenarios", "conversations", "trace", "orderbook"),
                    "show" to boolean("default true"),
                    "follow" to string("trace: a whole correlation value to follow, or null to stop following"),
                    "render" to enumStr("ledger", "lanes"),
                    "profile" to string("profile id or name (connection, or editor with a rule)"),
                    "rule" to integer("editor: which acceptor rule's reply to edit"),
                    "step" to integer("editor: which step of that reply (default 0)"),
                    "action" to enumStr("apply", "cancel"),
                ),
                required = listOf("panel"),
            ),
            tool(
                "fixtool_list_templates",
                "List saved message templates (id, name, messageType, userTags, isFavorite, fields), optionally per profile.",
                props("profile" to string("profile id or name to filter by")),
            ),
            tool(
                "fixtool_save_template",
                "Create/update a reusable message template under a profile, from a fields array " +
                    "[{tag, value, excluded?}] or a raw FIX string. Organise with userTags and isFavorite.",
                props(
                    "profile" to string("profile id or name"),
                    "name" to string("template name"),
                    "fields" to arraySchema(objectSchema(), "message fields; this or raw"),
                    "raw" to string("raw FIX string; this or fields"),
                    "userTags" to arraySchema(string(), "organising tags"),
                    "isFavorite" to boolean(),
                    "id" to string("existing template id to update"),
                ),
                required = listOf("profile", "name"),
            ),
            tool(
                "fixtool_delete_template",
                "Delete a saved template by id; pass profile if it can't be inferred.",
                props("id" to string("template id"), "profile" to string("profile id or name")),
                required = listOf("id"),
            ),
            tool(
                "fixtool_load_template",
                "Load a saved template into the message editor (and open the editor panel). May switch the active " +
                    "session to the best of the template's profiles; the active session stays put when it already " +
                    "belongs to one of them.",
                props("id" to string("template id")),
                required = listOf("id"),
            ),
            tool(
                "fixtool_demo",
                "Open or close the FX venue example workspace: an 'FX Demo Venue' acceptor on 19876 carrying " +
                    "the FX venue rule bundle, two 'Demo Client N' initiator profiles, FX templates and " +
                    "bundled scenarios. The venue accepts any CompID. start copies the example into a " +
                    "workspace of its own and opens it (name defaults to 'FX Venue'); stop closes the " +
                    "workspace without deleting it.",
                props(
                    "action" to enumStr("start", "stop"),
                    "name" to string("workspace name for start (default 'FX Venue')"),
                    "fixVersion" to string("FIX version the copied sessions speak (default FIX 4.4)"),
                ),
            ),
            tool(
                "fixtool_workspace",
                "Read or change the open project workspace — the profiles, saved messages, scenarios and " +
                    "run records. GET-style with no arguments: reports the open workspace, whether it is the " +
                    "installation's own directory, the recently opened ones and the bundled examples. Pass " +
                    "workspace=<path> to open that folder, or workspace=\"\" to close and go back to the " +
                    "installation's own directory. Opening one takes every session down first.",
                props("workspace" to string("folder to open, or empty to close")),
            ),
            tool(
                "fixtool_connect",
                "Connect a profile by name or id; logon is async (poll fixtool_sessions or use fixtool_wait).",
                props("profile" to string("profile name or id")),
                required = listOf("profile"),
            ),
            tool(
                "fixtool_disconnect",
                "Disconnect a profile by name or id.",
                props("profile" to string("profile name or id")),
                required = listOf("profile"),
            ),
            tool(
                "fixtool_send",
                "Send a raw FIX message from a session (active, or session by id/title/index). With resolve=true the " +
                    "\${...} template expressions in raw are resolved against the session first (\${uuid}, \${now}, " +
                    "\${out.D.11}, and the per-session \${sessionIndex}/\${sessionQualifier}/\${sessionTitle}/" +
                    "\${sessionSenderCompID}); see fixtool_syntax. Without resolve, raw is sent verbatim — an " +
                    "unresolved \${uuid} goes on the wire as that literal text.",
                props(
                    "raw" to string("raw FIX, pipe- or SOH-delimited"),
                    "session" to string("session id/title/index"),
                    "resolve" to boolean("resolve \${...} expressions before sending; default false"),
                ),
                required = listOf("raw"),
            ),
            tool(
                "fixtool_send_all",
                "Send one FIX message to every logged-on session (bulk send / load testing); expressions re-resolved per session.",
                props("raw" to string("raw FIX message")),
                required = listOf("raw"),
            ),
            tool(
                "fixtool_send_template",
                "Send a saved template (expressions resolved) from a session by id/title/index, or the active one.",
                props("id" to string("template id"), "session" to string("session id/title/index")),
                required = listOf("id"),
            ),
            tool(
                "fixtool_clear_messages",
                "Clear a session's message log (so assertions see only new messages).",
                props("session" to string("session id/title/index")),
                required = listOf("session"),
            ),
            tool(
                "fixtool_wait",
                "Block until a session reaches a state (e.g. LOGGED_ON) or a matching message arrives, or until " +
                    "timeoutMs elapses. The deterministic replacement for client-side polling.",
                props(
                    "session" to string("session id/title/index"),
                    "state" to string("connection state to await, e.g. LOGGED_ON"),
                    "match" to objectSchema("predicate: messageType / direction / tag / value"),
                    "timeoutMs" to integer("default 10000, cap 120000"),
                ),
                required = listOf("session"),
            ),
            tool(
                "fixtool_get_messages",
                "Read parsed messages from a session (timestamp, direction, messageType, raw, ordered {tag,value} " +
                    "fields) for verification — assert against these rather than screenshots. Each message also " +
                    "carries wireOrderKnown: when false, FixTool has no wire bytes for it, so fields[] is a " +
                    "best-effort read in QuickFIX's order rather than the venue's — and fixtool_assert will refuse " +
                    "that message. Do not build an expectation from a message whose wireOrderKnown is false.",
                props("session" to string("session id/title/index"), "limit" to integer("most recent last"), "direction" to enumStr("in", "incoming", "out", "outgoing")),
            ),
            tool(
                "fixtool_search",
                "Cross-session search returning matches sorted chronologically (a timeline); pins them to the search pane.",
                props("query" to string("regex or substring, e.g. a ClOrdID"), "pin" to boolean("default true")),
                required = listOf("query"),
            ),
            tool(
                "fixtool_traces",
                "Every TRACE across every session: the grouped grid's relation computed over all sessions at once, " +
                    "rather than one pane at a time. A trace is one business exchange followed through every session " +
                    "that touched it, joined by shared correlation-id VALUES — the venue's own id on the far side of a " +
                    "hop is an edge, a substring never is. Ask this instead of guessing a regex for fixtool_search when " +
                    "the question is \"what happened to this exchange\". " +
                    "Each header carries label, labelTag, ids (every value in the trace — what to pass to fixtool_trace), " +
                    "sessions [{index,title}], messageCount, composition, status (the LAST status a message STATED, in " +
                    "the dictionary's words — quoted, never inferred), instrument, quantity, elapsedMillis, and " +
                    "truncatedSessions: sessions that already evicted a message this trace would have contained, so it " +
                    "opened before the buffer and what you can see is not its start. " +
                    "Plus `ungrouped` (messages carrying no correlation id at all — heartbeats, logons) and `total`, so " +
                    "the numbers add up and nothing is hidden.",
            ),
            tool(
                "fixtool_trace",
                "ONE trace at full fidelity: every message of the exchange carrying `id`, merged into a single time " +
                    "order across the sessions that saw it. Each message is the shape fixtool_get_messages returns " +
                    "(timestamp, direction, messageType, raw, wireOrderKnown, ordered {tag,value} fields) plus `session` " +
                    "{index,title} and `elapsedMillis` — the gap since the previous message IN THIS TRACE, on whichever " +
                    "session it landed, null for the first. One clock timed both ends, so the gap between a request " +
                    "leaving a client and its copy arriving on an LP is the venue's real forwarding time; it is a " +
                    "measurement, not a diagnosis of what caused it. " +
                    "`id` is matched as a WHOLE correlation value, never as a substring: ORD-9 will not find ORD-91. Any " +
                    "id the exchange carries works — the client's RFQ-A1, the venue's V-2291 and the quote's Q-77 are " +
                    "three names for one trace. Unknown id answers an error naming it; fixtool_traces lists them.",
                props("id" to string("a whole correlation value the trace carries, e.g. RFQ-A1")),
                required = listOf("id"),
            ),
            tool(
                "fixtool_filter",
                "Filter the message grid for a focused screenshot. scope global (default) or session (also supports " +
                    "messageTypes / showSeparator).",
                props(
                    "scope" to enumStr("global", "session"),
                    "session" to string("session id/title/index (scope=session)"),
                    "regex" to string("row filter regex; \"\" clears"),
                    "messageTypes" to string("comma-separated FIX types (scope=session)"),
                    "showIncoming" to boolean(),
                    "showOutgoing" to boolean(),
                    "showSeparator" to boolean(),
                ),
            ),
            tool(
                "fixtool_select",
                "Select a message in the browser (opens the detail panel). Pick by index (default last), narrowed " +
                    "by messageType and direction.",
                props(
                    "session" to string("session id/title/index"),
                    "index" to integer("0-based into matching messages"),
                    "messageType" to string("FIX msg type, e.g. 8"),
                    "direction" to enumStr("in", "incoming", "out", "outgoing"),
                ),
            ),
            tool(
                "fixtool_detail_search",
                "Drive the message detail panel's tag search. Set query (tag number, field name, value or enum " +
                    "text) and/or mode: bare = matched rows only (legacy); identity = each matching repeating-group " +
                    "entry also shows its identity field (e.g. PartyID) so you can tell which entry matched; full = " +
                    "the whole matching entry. Pair with fixtool_select + fixtool_screenshot to inspect a nested tag " +
                    "(e.g. PartyRole across many parties) without losing context. show reveals the panel.",
                props(
                    "query" to string("tag number, field name, value, or enum text"),
                    "mode" to enumStr("bare", "identity", "full"),
                    "show" to boolean("reveal the detail panel"),
                ),
            ),
            tool(
                "fixtool_assert",
                "Assert a received message against an expectation — the machine-check that replaces eyeballing a " +
                    "response. Selects the message like fixtool_select (by messageType/direction/index), or awaits " +
                    "one for up to timeoutMs. Returns {passed, tags:[{tag, matcher, expected, actual, passed, index, " +
                    "occurrence, status}]}; a tag's status is ok|value|missing|unexpected|moved|invalid.\n\n" +
                    "A TOP-LEVEL status means nothing was judged: 'timeout' (no matching message arrived) or " +
                    "'no-wire-bytes' (FixTool could not read the message's bytes, so it does not know the venue's " +
                    "field order and REFUSES to evaluate an order-sensitive assertion against a guess). On " +
                    "no-wire-bytes, tags[] is empty and passed is false — but the fault is FixTool's, NOT the " +
                    "venue's. Do not report it as a venue regression.\n\n" +
                    "ORDER MATTERS. fields[] is an ORDERED list of rows, and the order is part of the assertion:\n" +
                    "  * The k-th row for a tag asserts the k-th occurrence of that tag. Two party entries = two 448 " +
                    "rows and two 452 rows; the second 452 row checks the second entry. There is no group path — " +
                    "position is the address.\n" +
                    "  * Your rows must be a SUBSEQUENCE of the message: they must appear in the order you list them, " +
                    "with anything else allowed in between. If the venue sends 37 before 11 and you list 11 before 37, " +
                    "the step FAILS with status=moved. List rows in the order the venue sends them — or call " +
                    "fixtool_capture_expectation, which seeds them in wire order for you.\n\n" +
                    "mode=open asserts only the listed rows (any tag you do not mention is ignored). mode=strict also " +
                    "asserts the message's shape: same tags, same count, same order — an unexpected tag fails it.\n\n" +
                    "Matcher {type,...}: exact (value), presence, absent, regex (pattern), oneOf (values[]), " +
                    "range (min/max, each optional; minInclusive/maxInclusive default true), numeric " +
                    "(value, tolerance?), temporal (kind today|now_within_tolerance, toleranceSeconds?), reference " +
                    "(expression, e.g. \${out.D.11} — see fixtool_syntax). An `absent` row asserts the tag does not " +
                    "appear; it takes no part in the ordering.",
                props(
                    "session" to string("session id/title/index; default active"),
                    "messageType" to string("FIX msg type to select/await, e.g. 8"),
                    "direction" to enumStr("in", "incoming", "out", "outgoing"),
                    "index" to integer("0-based into matching messages; default last"),
                    "timeoutMs" to integer("await a matching message up to this long; default 0 = use already-received"),
                    "mode" to enumStr("open", "strict"),
                    "fields" to arraySchema(
                        objectSchema("FieldExpectation: {tag, matcher:{type,...}}"),
                        "ORDERED rows, in the order the venue sends them. The k-th row for a tag asserts the k-th " +
                            "occurrence of it. Do not sort or de-duplicate.",
                    ),
                ),
                required = listOf("fields"),
            ),
            tool(
                "fixtool_capture_expectation",
                "Build an auto-seeded expectation from a received message: matchers pre-seeded from dictionary field " +
                    "types (timestamps -> temporal, prices/quantities -> numeric, else exact). Asserted for PRESENCE " +
                    "only, never value, because the value is this environment's or this moment's: OrderID(37), " +
                    "ExecID(17), the routing addresses SenderSubID(50)/TargetSubID(57)/OnBehalfOfCompID(115)/" +
                    "DeliverToCompID(128)/SenderLocationID(142)/TargetLocationID(143)/OnBehalfOfLocationID(144)/" +
                    "DeliverToLocationID(145), and OrigSendingTime(122) — tighten one to exact if the scenario is " +
                    "about routing. NOT asserted at all, and reported in notAsserted: the session envelope " +
                    "8/9/10/34/49/52/56/369. Selects by messageType/direction/index like fixtool_select. " +
                    "Returns {messageType, mode, fields:[...], notAsserted:[...]} ready to edit and pass to fixtool_assert.\n\n" +
                    "Rows come back in WIRE ORDER, one per occurrence of each tag — the order is part of the " +
                    "assertion (see fixtool_assert), so pass fields[] through UNSORTED and UN-DE-DUPLICATED. " +
                    "Sorting it by tag, or collapsing the repeated 448/447/452 rows of a two-party group, silently " +
                    "re-aims every assertion.\n\n" +
                    "Returns an error instead when FixTool has no wire bytes for the message: without the venue's " +
                    "field order, a seeded expectation would assert an order the venue never sent.",
                props(
                    "session" to string("session id/title/index; default active"),
                    "messageType" to string("FIX msg type to select, e.g. 8"),
                    "direction" to enumStr("in", "incoming", "out", "outgoing"),
                    "index" to integer("0-based into matching messages; default last"),
                ),
            ),
            tool(
                "fixtool_save_scenario",
                "Save a repeatable scenario (an ordered sequence of sends + assertions a deterministic runner " +
                    "replays). Body is the scenario JSON: {name, profile?, userTags?, traffic?, setup?:[step], " +
                    "steps:[step], " +
                    "teardown?:[step]}. A step is {type, ...}: send {raw, session?}; wait {session?, state?, match?, " +
                    "timeoutMs?}; expect {session?, direction?, match?, timeoutMs?, expectation:{messageType?, mode?, " +
                    "fields:[{tag, matcher}]}}; clearMessages {session?}; resetSeqNum {session?, sender?, " +
                    "target?}. match is {messageType?, direction?, fields:[{tag, value}]} (AND). Omit id to create. " +
                    "PARAMETERIZE IT: a send's raw, a match value and a reference matcher all resolve \${...} " +
                    "expressions — always, with no resolve flag — over one variable scope that persists across every " +
                    "step. The idiom is 11=\${clOrdId = uuid} in the send, then assert the echo with " +
                    "{\"type\":\"reference\",\"expression\":\"\${clOrdId}\"}. traffic:\"strict\" additionally " +
                    "fails the run if, after the last step (plus a settle window), any incoming application-level " +
                    "message was never bound by an expect — \"the venue sent nothing else\" (session admin exempt; " +
                    "default \"open\" ignores unbound messages). EXCLUDE A FIELD WITHOUT DELETING IT: prefix its tag " +
                    "with '#' in a send's raw — 35=D|11=ORD|#9303=1 keeps 9303 in the scenario and leaves it off the " +
                    "wire, so \"does the venue still accept this without it?\" is a toggle rather than a retype. An " +
                    "excluded field is wholly inert: it is not sent, not linted, and its \${...} never resolves, so a " +
                    "mint inside one binds nothing. Call fixtool_syntax for the full " +
                    "grammar, or fixtool_capture_scenario to record one that is already correctly templated.",
                props(
                    "name" to string("scenario name"),
                    "id" to string("existing scenario id to update"),
                    "profile" to string("connection profile id/name this scenario targets"),
                    "userTags" to arraySchema(string(), "organising tags (also used for per-profile filtering)"),
                    "traffic" to enumStr("open", "strict"),
                    "setup" to arraySchema(objectSchema("ScenarioStep run before steps"), "setup steps"),
                    "steps" to arraySchema(objectSchema("ScenarioStep"), "the ordered steps"),
                    "teardown" to arraySchema(objectSchema("ScenarioStep run after steps, even on failure"), "teardown steps"),
                ),
                required = listOf("name", "steps"),
            ),
            tool(
                "fixtool_capture_scenario",
                "Record the current session message flow into a replayable scenario — the capture-driven " +
                    "way to author one. Captures across one or more sessions (RFQ / multi-session friendly): " +
                    "outgoing app messages become Send steps (TransactTime and correlation IDs auto-parameterized), " +
                    "incoming become Expect steps with smart-seeded matchers, and any echoed id is auto-wired to a " +
                    "reference matcher. Admin messages skipped; setup clears each session. Saved to the store.",
                props(
                    "name" to string("scenario name"),
                    "profile" to string("connection profile id/name to tag the scenario with"),
                    "sessions" to arraySchema(string(), "session ids/titles/indices to capture; default all"),
                ),
                required = listOf("name"),
            ),
            tool(
                "fixtool_capture_paste",
                "Capture a scenario from PASTED WIRE — a server log fragment, one message per line — with no live " +
                    "session at all. The same reader the paste sheet uses: a line whose reading the bytes disprove " +
                    "(a '|' inside a value, a checksum that disagrees) is REFUSED and reported, never guessed. " +
                    "Direction is read from SenderCompID(49) against the given CompIDs; a message whose direction " +
                    "nothing settles blocks the save (a reply saved as a Send asserts nothing). Every step is " +
                    "badged 'pasted'. Returns {status, id, steps, pasted, refused[]} or {status:refused, undirected[]}.",
                props(
                    "name" to string("scenario name"),
                    "wire" to string("the pasted bytes — one FIX message per line (SOH or '|' delimited)"),
                    "session" to string("session id/title to assign, and whose CompIDs settle direction"),
                    "senderCompId" to string("our CompID (overrides the session's) — 49 equal to this means outgoing"),
                    "targetCompId" to string("the venue's CompID (overrides the session's) — 49 equal to this means incoming"),
                    "profile" to string("connection profile id/name to tag the scenario with"),
                ),
                required = listOf("name", "wire"),
            ),
            tool(
                "fixtool_list_scenarios",
                "List saved scenarios (id, name, profile, step counts, userTags), optionally filtered by profile.",
                props("profile" to string("profile id, name, or tag to filter by")),
            ),
            tool(
                "fixtool_get_scenario",
                "Fetch one saved scenario's full JSON definition by id — the exact shape fixtool_save_scenario " +
                    "accepts, so a scenario can be read, edited, and saved back losslessly.",
                props("id" to string("scenario id (from fixtool_list_scenarios)")),
                required = listOf("id"),
            ),
            tool(
                "fixtool_run_scenario",
                "Run a scenario deterministically (no LLM in the loop) and return a per-step, per-tag pass/fail " +
                    "report. Identify it by id (from the store) or pass an inline scenario. With format=junit the " +
                    "result is returned as JUnit XML for CI; otherwise as JSON {scenario, passed, steps:[...]}.",
                props(
                    "id" to string("saved scenario id"),
                    "scenario" to objectSchema("inline scenario JSON (alternative to id)"),
                    "format" to enumStr("json", "junit"),
                ),
            ),
            tool(
                "fixtool_run_set",
                "Run MANY scenarios as one job and return at once — the answer to \"run the suite and tell me what " +
                    "broke\". Three ways in: set=<name> for a saved run set (~/.fixtool/sets/<name>.json), " +
                    "ids=[...] for an explicit list, or id=<scenario> with repeat=N for a flake hunt. Returns " +
                    "{runSet, status, entries} immediately — a twelve-scenario suite is minutes, so poll it with " +
                    "fixtool_run_status and read a failed entry with fixtool_run_entry. Each entry runs isolated " +
                    "(binding=this_run, so iteration 2 cannot bind iteration 1's reply) and writes its report AND " +
                    "its messages to ~/.fixtool/runs/<runSet>/ as it lands, so the evidence outlives the grid. " +
                    "stopOnFailure=true ends the set at the first red (CI); the default runs everything, because " +
                    "\"3 of 20 failed\" is what a flake hunt needs.",
                props(
                    "set" to string("name of a saved run set"),
                    "ids" to arraySchema(string(), "scenario ids to run, in order"),
                    "id" to string("one scenario id, with repeat=N"),
                    "repeat" to integer("run each scenario N times"),
                    "stopOnFailure" to boolean("stop at the first failing entry (default false)"),
                    "pauseMs" to integer("pause between entries, milliseconds"),
                    "rows" to objectSchema("true for the scenario's whole Examples table, or [\"row name\"] for named rows"),
                    "fanOut" to objectSchema(
                        "{profile, session?} — run the flow ONCE PER SESSION of a multi-session initiator " +
                            "profile, all at once, for load. Each lane knows which client it is: " +
                            "\${sessionIndex}, \${sessionSenderCompID}, \${sessionTitle}, \${sessionQualifier}. " +
                            "`session` names which leg to spread when the scenario drives more than one. " +
                            "Refused by name when the profile opens one session, when it is an acceptor, or " +
                            "when a second leg would be shared by every lane. Point it at the server under " +
                            "test: against FixTool's own acceptor the latencies are the tool's own ceiling",
                    ),
                ),
            ),
            tool(
                "fixtool_run_status",
                "Where a run set has got to: {status: running|passed|failed|stopped, summary:{total,done,passed," +
                    "failed,elapsedMs}, entries:[{n,scenario,iteration,state,durationMs,record,note}]}. A finished " +
                    "set also carries stats:{replyLatency:{p50,p95,max,samples}, wallClock:{…}, failedLanes:[…]} — " +
                    "replyLatency is the venue's number, measured between the bytes that left and the bytes that " +
                    "answered, and is what to read a fan-out from rather than the per-entry rows. Pass " +
                    "wait=<ms> (up to 10000) to hold the call until the set finishes — under this transport's own " +
                    "ceiling, so waiting never costs you the answer. With no runSet it lists the recent sets, " +
                    "newest first. The state is read from disk, so it survives a restart of the app.",
                props(
                    "runSet" to string("the id returned by fixtool_run_set; omit to list recent sets"),
                    "wait" to integer("milliseconds to wait for the set to finish (max 10000)"),
                    "stop" to boolean("ask the running set to stop where it is"),
                ),
            ),
            tool(
                "fixtool_run_entry",
                "One entry's RECORD: the same per-step, per-tag report fixtool_run_scenario returns, plus every " +
                    "message the entry saw, in arrival order, with the wire bytes — and `bound`, which says which " +
                    "message each step judged. This is what a failed entry is diagnosed from: by the time you read " +
                    "it the grid holds a later entry's traffic, or nothing at all.",
                props(
                    "runSet" to string("the run set id"),
                    "entry" to integer("1-based entry number, from fixtool_run_status"),
                ),
                required = listOf("runSet", "entry"),
            ),
            tool(
                "fixtool_reconcile",
                "Open the reconcile diff on a step that failed the last run — the one surface in the app that " +
                    "can author or repair an assertion. With no argument it takes the run's first failing step, " +
                    "exactly as the rail's 'Reconcile →' does; step=N addresses one by its 1-based position. It " +
                    "routes through the same check the button does, so a step edited since it ran is refused with " +
                    "the reason rather than opened over the wrong bytes. The response carries fixPlan: the repair " +
                    "plan the sheet previews (per row: index, class numeric/temporal/oneOf/regex/presence, " +
                    "current, proposed, reason, repairs, defaultChecked). applyFix=[indexes] stages exactly those " +
                    "plan rows in the open session — visible, undoable, saved by Save; an index the plan does not " +
                    "propose refuses the whole apply. Pair with fixtool_screenshot to see it.",
                props(
                    "step" to string("1-based step number; default = the first failing step"),
                    "applyFix" to arraySchema(integer(), "plan row indexes to stage, e.g. [0,3]; omit to only read the plan"),
                ),
            ),
            tool(
                "fixtool_diff",
                "Open the plain diff VIEWER on two messages — a read-only FIX diff (=/≠/+A/+B), no assertions, " +
                    "nothing that writes. Each side is a PICK ({session, match:{messageType,tag,value,direction}} " +
                    "— a live message, by its wire bytes) or a PASTE ({paste:'<bytes>'} — read through the same " +
                    "reader the slot uses, so a '|' inside a value is refused, not guessed). A side with no wire " +
                    "bytes is refused. The window's title is 'diff: A vs B', so photograph it with " +
                    "fixtool_screenshot window='diff:'. Returns {status:open,subject} or an error.",
                props(
                    "a" to objectSchema("side A: {session, match} to pick a live message, or {paste} for pasted bytes"),
                    "b" to objectSchema("side B: {session, match} to pick a live message, or {paste} for pasted bytes"),
                ),
                required = listOf("a", "b"),
            ),
            tool(
                "fixtool_delete_scenario",
                "Delete a saved scenario by id.",
                props("id" to string("scenario id")),
                required = listOf("id"),
            ),
            tool(
                "fixtool_admin",
                "FIX session/admin control: seqnum (read), reset-seqnum (sender/target), test-request (id), " +
                    "resend-request (begin/end), sequence-reset (newSeq/gapFill), logout (reason), disconnect (reason), " +
                    "stop-responses (drop this session's queued acceptor auto-responses mid-sequence; reports how many).",
                props(
                    "session" to string("session id/title/index"),
                    "action" to enumStr("seqnum", "reset-seqnum", "test-request", "resend-request", "sequence-reset", "logout", "disconnect", "stop-responses"),
                    "sender" to integer(),
                    "target" to integer(),
                    "id" to string("TestReqID"),
                    "begin" to integer(),
                    "end" to integer("0 = up to latest"),
                    "newSeq" to integer(),
                    "gapFill" to boolean(),
                    "reason" to string(),
                ),
                required = listOf("session", "action"),
            ),
            tool(
                "fixtool_validate",
                "Validate a raw FIX message against the loaded data dictionary; returns {isValid, errors}.",
                props("raw" to string("raw FIX message")),
                required = listOf("raw"),
            ),
            tool(
                "fixtool_dictionary",
                "Read or switch the active FIX data dictionary. No args = read current version/validity; pass a " +
                    "version (e.g. FIX_4_4) for a bundled dictionary, or a path (+ transportPath for FIX 5.0+) for a custom one.",
                props("version" to string("bundled FIX version name/beginString/display"), "path" to string("custom dictionary file"), "transportPath" to string("FIXT transport (FIX 5.0+)")),
            ),
            tool(
                "fixtool_acceptor_rules",
                "Inspect a profile's acceptor auto-response rules (rules are set via fixtool_save_profile's config, " +
                    "as acceptorResponseRules:[{whenMsgType, whenFields?, conditions?, whenOrder?, enabled?, " +
                    "steps:[{template, delayMillis}]}]). " +
                    "enabled defaults true; a rule switched off is skipped so the message falls to the next rule. " +
                    "A trigger is whenMsgType plus conditions, ANDed: conditions:[{tag, matcher}] where matcher is " +
                    "the same JSON the scenario assertions use ({\"type\":\"range\",\"min\":10000} for 38 > 10000, " +
                    "also exact/notEqual/presence/absent/oneOf/regex/numeric/temporal — but not reference, which needs a " +
                    "scenario scope a trigger does not have). whenFields is the older exact-only form and still " +
                    "works; the two are ANDed, never chosen between. " +
                    "whenOrder is the one condition no tag can express: what the venue was HOLDING for the order " +
                    "this message names, as unknown|pending|working|done — so a cancel for an order that was never " +
                    "placed and a cancel for a live one can be answered differently by the same rule list. " +
                    "A template can READ the book too: \${order.<name>} where name is one of orderId, clOrdId, " +
                    "origClOrdId, symbol, side, orderQty, cumQty, leavesQty, avgPx, price, ordStatus — names and " +
                    "not tag numbers, because half of them are facts the venue computed rather than fields of any " +
                    "message. Both spellings work, so \${order.leavesQty} is the value and " +
                    "\${order.leavesQty / 2} is arithmetic, and each is resolved as its own step is sent. " +
                    "A rule replies with a sequence: each step's delayMillis is measured from the step before it, " +
                    "so 0/500/500 is ack, half a second later a partial fill, half a second after that the rest. " +
                    "The older single-message spelling (responseTemplate) still works and reads as one immediate " +
                    "step. A template understands a restricted subset of the template language — \${req.<tag>} to " +
                    "echo a request field, \${uuid} and \${now} — and nothing else; see fixtool_syntax. \${req.<tag>} " +
                    "is fixed when the trigger arrives, \${uuid} and \${now} are resolved per step as it is sent. " +
                    "A req reference inside a larger expression is computed: 14=\${req.38 / 2} is half the order " +
                    "quantity, scoped to the message that triggered this rule. " +
                    "The response reports each rule's `index` (its identity for fixtool_acceptor_rule, and its " +
                    "priority under first-match-wins), its played `sequence` with the offset each step goes out at, " +
                    "a `validationError` on any rule that cannot reply, and the profile's `latency` — the simulated " +
                    "venue delay applied to every reply, with the millisecond range it adds.",
                props("profile" to string("profile id or name")),
                required = listOf("profile"),
            ),
            tool(
                "fixtool_acceptor_presets",
                "List the ready-made acceptor behaviours for the common order flow — acknowledge, fill, partial " +
                    "fill, reject, cancel accept/reject, replace, business reject — plus the `starter-venue` bundle " +
                    "that makes a plausible venue in one call. Takes no arguments. Each entry reports its `id` " +
                    "(what fixtool_acceptor_rule's `preset` takes), what triggers it, and the exact reply it will " +
                    "insert. They are ordinary rules once inserted: editable, reorderable, deletable, and " +
                    "indistinguishable from hand-written ones. Use these rather than composing an ExecutionReport " +
                    "by hand — each already carries the tags its message type owes, and only reads request tags its " +
                    "own trigger guarantees are there.",
                props(),
            ),
            tool(
                "fixtool_acceptor_rule",
                "Add, replace, toggle or delete ONE acceptor auto-response rule, leaving the rest of the profile " +
                    "untouched. Use this rather than fixtool_save_profile when changing rules: the rule list is a " +
                    "single config key, so saving it through the profile still means re-sending every other rule. " +
                    "`rule` with no `index` appends; `rule` with an `index` replaces that position; `index` plus " +
                    "`enabled` toggles the rule already there (disabled rules are kept and skipped, so the message " +
                    "falls through to the next rule — the fastest way to ask 'what happens without this one'); " +
                    "`index` plus delete:true removes it. Rules are ordered and first-match-wins, so the index is " +
                    "both the rule's identity and its priority; deleting one shifts everything after it up. " +
                    "A rule is {whenMsgType, conditions?, whenFields?, whenOrder?, enabled?, " +
                    "steps:[{template, delayMillis}]} — see fixtool_acceptor_rules for the full vocabulary. " +
                    "A step's template can also READ the book: \${order.orderId}, \${order.cumQty}, " +
                    "\${order.leavesQty} and the rest of the names, standing alone or inside arithmetic " +
                    "(14=\${order.cumQty + order.leavesQty / 2}). Resolved per step AS IT IS SENT, so a " +
                    "sequence's later fills see what its earlier ones left. A rule whose reply reads the book " +
                    "must be able to guarantee one — set whenOrder to pending/working/done, or trigger on 35=D, " +
                    "which brings the order with it; anything else is a validationError. " +
                    "`whenOrder` is one more condition, ANDed with the rest, asking what the VENUE IS HOLDING for " +
                    "the order this message names (41 if it names one, else 11): unknown|pending|working|done. It " +
                    "reads the state the venue held BEFORE this message, so a rule on 35=D conditioned `unknown` " +
                    "fires for a new order and one conditioned `working` fires only for a duplicate ClOrdID. " +
                    "Test it with fixtool_acceptor_test before " +
                    "connecting anything. An edit applies to an already-connected acceptor on its next trigger — no " +
                    "reconnect — and the response says appliedToLiveSessions when it did. " +
                    "`preset` inserts a ready-made behaviour instead of a hand-written `rule` (ids from " +
                    "fixtool_acceptor_presets) and chooses its own position, so it cannot be combined with `index`: " +
                    "a conditioned rule placed below an unconditioned one for the same MsgType would never fire, so " +
                    "one lands above it and the response says `placedAbove`. The response also reports `shadowedBy` " +
                    "when the rule you just wrote is unreachable for that reason.",
                props(
                    "profile" to string("profile id or name"),
                    "rule" to objectSchema("the rule to add or replace; omit to toggle or delete"),
                    "preset" to string("insert a ready-made behaviour by id — see fixtool_acceptor_presets"),
                    "index" to integer("which rule; omit with 'rule' to append"),
                    "enabled" to boolean("toggle a rule on/off, or set the state of a rule being written"),
                    "delete" to boolean("true = remove the rule at 'index'"),
                ),
                required = listOf("profile"),
            ),
            tool(
                "fixtool_acceptor_test",
                "Dry-run a message against a profile's acceptor rules WITHOUT connecting, sending, or changing " +
                    "anything — the fast way to author a rule. Give it a profile and a raw FIX message (pipe- or " +
                    "SOH-delimited, e.g. \"35=D|11=ORD-1|55=EUR/USD|38=100000|54=1\") and it reports, for EVERY " +
                    "rule: whether its trigger matched, each condition's verdict with the value it actually read " +
                    "off the message (`absent:true` when the tag is not there at all — the commonest cause of a " +
                    "rule that never fires), whether the rule was skipped because it is disabled or unusable, and " +
                    "`shadowedBy` when it matched but an earlier rule won. For the winning rule it renders the " +
                    "whole reply it would play: each step's exact FIX text with \${req.<tag>} already substituted, " +
                    "and the offset it goes out at. Also reports `inactive` if the profile is not an ACCEPTOR, in " +
                    "which case none of the rules would ever run. Offsets exclude the simulated latency, which is " +
                    "drawn per trigger and reported separately. It reads the profile as saved, which is also what " +
                    "a connected acceptor is running, so a dry run and a live session cannot disagree. " +
                    "A rule can also condition on what the venue is HOLDING (`whenOrder`), and a dry run of one has " +
                    "to assume a state: `orderState` is that assumption — unknown|pending|working|done, defaulting " +
                    "to `unknown` — so \"what would this rule do if the order were already filled\" is answerable " +
                    "without arranging for an order to be already filled. The answer always reports " +
                    "`assumedOrderState` back, and each conditioned rule reports `whenOrder` with what it asked for, " +
                    "what it read and the verdict. A reply that READS the book (\${order.…}) needs an `order` to " +
                    "render against; without one those steps come back `unrendered` with the reason, because a dry " +
                    "run that invented quantities would be confidently wrong about what the wire would carry.",
                props(
                    "profile" to string("profile id or name"),
                    "raw" to string("the incoming FIX message to test the rules against"),
                    "orderState" to
                        string("the state to assume the named order is in: unknown|pending|working|done (default unknown)"),
                    "order" to
                        objectSchema(
                            "the order to render \${order.…} against, by the book's own names " +
                                "({\"orderId\":\"EX-1\",\"cumQty\":\"400\",\"leavesQty\":\"600\"}); " +
                                "without it a reply that reads the book is reported unrendered rather than faked",
                        ),
                ),
                required = listOf("profile", "raw"),
            ),
            tool(
                "fixtool_acceptor_orders",
                "Read what a venue is holding for a counterparty: the orders it has booked, and for each one the " +
                    "trail of messages that put it in that state. The book is fed from the WIRE — every message " +
                    "received and every reply actually sent, whether a rule sent it, a person typed it, or a " +
                    "scenario step did — so it is the client's view of the venue rather than the venue's " +
                    "intentions. Acceptor sessions only; an initiator holds no book. " +
                    "With no `session` it is a roll-up: one line per counterparty with how many orders and how " +
                    "many are still working. With `session` it is that book in full — each order's ClOrdID, " +
                    "OrderID, symbol, side, quantities, OrdStatus and `state` (pending = received but not yet " +
                    "answered, working, done), plus `trail`: every message that touched it with what the order " +
                    "looked like after it. `order` narrows to one ClOrdID. " +
                    "Three fields say how the book may be WRONG, and they are the ones to check when a number " +
                    "surprises you: `unattributed` counts reports that named an order this book has never seen " +
                    "(with `unattributedMessages` listing them and why), `evicted` counts orders dropped to stay " +
                    "inside `cap`, and `clearedAt` says the book was emptied rather than never filled. " +
                    "`clear:true` empties one session's book — use it to start a test from a known state.",
                props(
                    "session" to string("session index, id or title; omit for the roll-up across all venues"),
                    "order" to string("one ClOrdID, to get just that order and its trail"),
                    "clear" to boolean("true = empty this session's book, recording that it was cleared"),
                ),
            ),
            tool(
                "fixtool_screenshot",
                "Capture a PNG screenshot of a FixTool window for visual verification. `window` picks which: " +
                    "`main` (default) the main window; `diff` the reconcile/diff window; or any substring of a " +
                    "window's title to target a specific one.",
                props("window" to string("which window: main (default), diff, or a title substring")),
            ),
        )

    private fun tool(
        name: String,
        description: String,
        properties: JsonObject = JsonObject(emptyMap()),
        required: List<String> = emptyList(),
    ): JsonObject =
        buildJsonObject {
            put("name", name)
            put("description", description)
            put(
                "inputSchema",
                buildJsonObject {
                    put("type", "object")
                    put("properties", properties)
                    if (required.isNotEmpty()) put("required", buildJsonArray { required.forEach { add(it) } })
                },
            )
        }

    private fun props(vararg p: Pair<String, JsonObject>): JsonObject = buildJsonObject { p.forEach { (k, v) -> put(k, v) } }

    private fun string(description: String? = null): JsonObject =
        buildJsonObject {
            put("type", "string")
            description?.let { put("description", it) }
        }

    private fun integer(description: String? = null): JsonObject =
        buildJsonObject {
            put("type", "integer")
            description?.let { put("description", it) }
        }

    private fun boolean(description: String? = null): JsonObject =
        buildJsonObject {
            put("type", "boolean")
            description?.let { put("description", it) }
        }

    private fun enumStr(vararg values: String): JsonObject =
        buildJsonObject {
            put("type", "string")
            put("enum", buildJsonArray { values.forEach { add(it) } })
        }

    private fun arraySchema(items: JsonObject, description: String? = null): JsonObject =
        buildJsonObject {
            put("type", "array")
            put("items", items)
            description?.let { put("description", it) }
        }

    private fun objectSchema(description: String? = null): JsonObject =
        buildJsonObject {
            put("type", "object")
            description?.let { put("description", it) }
        }
}
