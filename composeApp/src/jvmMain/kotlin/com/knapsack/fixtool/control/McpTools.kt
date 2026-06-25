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
            tool("fixtool_health", "Check that the control server is reachable; returns status and session count."),
            tool("fixtool_sessions", "List all FIX sessions with index, id, title, connection state and message count."),
            tool("fixtool_profiles", "List the available connection profiles (id, name, host, port, sender/target CompID)."),
            tool(
                "fixtool_save_profile",
                "Create (or update, if id is given) a FIX connection profile so it can be connected. config " +
                    "only needs fields that differ from defaults: host, port, senderCompID, targetCompID, " +
                    "beginString, connectionType (INITIATOR|ACCEPTOR), heartBtInt, resetOnLogon, useSSL, " +
                    "socketAcceptPort (acceptor), sessionCount, logonFields, and acceptorResponseRules " +
                    "([{whenMsgType, whenFields?, responseTemplate}]) for auto-responding as an acceptor.",
                props("name" to string("display name"), "config" to objectSchema("FixConnectionConfig fields (partial)"), "id" to string("existing id to update")),
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
                "Show or hide a UI panel for verification screenshots: connection, editor, detail or settings.",
                props("panel" to enumStr("connection", "editor", "detail", "settings"), "show" to boolean("default true")),
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
                "Load a saved template into the message editor (and open the editor panel).",
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
                "Send a raw FIX message from a session (active, or session by id/title/index). With resolve=true, " +
                    "template expressions are resolved against the session first.",
                props("raw" to string("raw FIX, pipe- or SOH-delimited"), "session" to string("session id/title/index"), "resolve" to boolean()),
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
                    "fields) for verification — assert against these rather than screenshots.",
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
                "fixtool_admin",
                "FIX session/admin control: seqnum (read), reset-seqnum (sender/target), test-request (id), " +
                    "resend-request (begin/end), sequence-reset (newSeq/gapFill), logout (reason), disconnect (reason).",
                props(
                    "session" to string("session id/title/index"),
                    "action" to enumStr("seqnum", "reset-seqnum", "test-request", "resend-request", "sequence-reset", "logout", "disconnect"),
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
                "Inspect a profile's acceptor auto-response rules (rules are set via fixtool_save_profile's config).",
                props("profile" to string("profile id or name")),
                required = listOf("profile"),
            ),
            tool("fixtool_screenshot", "Capture a PNG screenshot of the FixTool window for visual verification."),
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
