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
                "Show or hide a UI pane for verification screenshots: connection, editor, detail, settings or " +
                    "scenarios (the Scenarios rail, docked in the main window).",
                props("panel" to enumStr("connection", "editor", "detail", "settings", "scenarios", "conversations"), "show" to boolean("default true")),
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
                "Start or stop the built-in demo FIX server (an FX acceptor on 19876) which registers " +
                    "\"Demo User N\" profiles for self-contained tests.",
                props("action" to enumStr("start", "stop")),
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
                    "as acceptorResponseRules:[{whenMsgType, whenFields?, conditions?, enabled?, steps:[{template, delayMillis}]}]). " +
                    "enabled defaults true; a rule switched off is skipped so the message falls to the next rule. " +
                    "A trigger is whenMsgType plus conditions, ANDed: conditions:[{tag, matcher}] where matcher is " +
                    "the same JSON the scenario assertions use ({\"type\":\"range\",\"min\":10000} for 38 > 10000, " +
                    "also exact/notEqual/presence/absent/oneOf/regex/numeric/temporal — but not reference, which needs a " +
                    "scenario scope a trigger does not have). whenFields is the older exact-only form and still " +
                    "works; the two are ANDed, never chosen between. " +
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
                "fixtool_acceptor_rule",
                "Add, replace, toggle or delete ONE acceptor auto-response rule, leaving the rest of the profile " +
                    "untouched. Use this rather than fixtool_save_profile when changing rules: the rule list is a " +
                    "single config key, so saving it through the profile still means re-sending every other rule. " +
                    "`rule` with no `index` appends; `rule` with an `index` replaces that position; `index` plus " +
                    "`enabled` toggles the rule already there (disabled rules are kept and skipped, so the message " +
                    "falls through to the next rule — the fastest way to ask 'what happens without this one'); " +
                    "`index` plus delete:true removes it. Rules are ordered and first-match-wins, so the index is " +
                    "both the rule's identity and its priority; deleting one shifts everything after it up. " +
                    "A rule is {whenMsgType, conditions?, whenFields?, enabled?, steps:[{template, delayMillis}]} — " +
                    "see fixtool_acceptor_rules for the full vocabulary. Test it with fixtool_acceptor_test before " +
                    "connecting anything. An edit applies to an already-connected acceptor on its next trigger — no " +
                    "reconnect — and the response says appliedToLiveSessions when it did.",
                props(
                    "profile" to string("profile id or name"),
                    "rule" to objectSchema("the rule to add or replace; omit to toggle or delete"),
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
                    "a connected acceptor is running, so a dry run and a live session cannot disagree.",
                props(
                    "profile" to string("profile id or name"),
                    "raw" to string("the incoming FIX message to test the rules against"),
                ),
                required = listOf("profile", "raw"),
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
