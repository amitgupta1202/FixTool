// This file is an HTTP request-handling boundary: each endpoint legitimately catches broad
// exceptions to convert any failure into a 500 response, and the per-endpoint handlers
// naturally push the class past detekt's function-count threshold.
@file:Suppress("TooManyFunctions", "TooGenericExceptionCaught", "ReturnCount", "LargeClass")

package com.knapsack.fixtool.control

import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixMessageSession
import com.knapsack.fixtool.model.SavedFixField
import com.knapsack.fixtool.model.SavedFixMessage
import com.knapsack.fixtool.service.FixMessageHelper
import com.knapsack.fixtool.service.SendResult
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.awt.Robot
import java.awt.Window
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

/**
 * A small loopback-only HTTP control surface that lets external tools (Claude Code, an MCP
 * server, curl, CI scripts) drive a running FixTool instance for automated testing,
 * verification and screenshots.
 *
 * The server is OFF by default and only starts when the `FIXTOOL_CONTROL_PORT` environment
 * variable is set, so normal/production runs are completely unaffected. It binds to
 * 127.0.0.1 only. An optional `FIXTOOL_CONTROL_TOKEN` can require a `X-Control-Token`
 * header on every request.
 *
 * All ViewModel access happens on the Swing event-dispatch thread because Compose state is
 * EDT-bound; the HTTP handlers run on the server's own thread pool.
 */
class ControlServer(
    private val port: Int,
    private val viewModel: FixMessageViewModel,
    private val windowProvider: () -> Window?,
    private val token: String? = System.getenv("FIXTOOL_CONTROL_TOKEN")?.ifBlank { null },
) {
    private val logger = LoggerFactory.getLogger(ControlServer::class.java)
    private var server: HttpServer? = null

    // Tolerant decoder so an agent can post a partial config and let model defaults fill the rest.
    private val profileJson = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    fun start() {
        val httpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0)
        // Handlers block on invokeAndWait round-trips to the EDT, so give a little headroom.
        httpServer.executor = Executors.newFixedThreadPool(HTTP_POOL_SIZE)
        httpServer.createContext("/health") { ex -> handle(ex) { health() } }
        httpServer.createContext("/sessions") { ex -> handle(ex) { sessions() } }
        httpServer.createContext("/profiles") { ex -> handle(ex) { profilesEndpoint(ex) } }
        httpServer.createContext("/panel") { ex -> handle(ex) { panel(ex) } }
        httpServer.createContext("/templates/load") { ex -> handle(ex) { loadTemplate(ex) } }
        httpServer.createContext("/templates") { ex -> handle(ex) { templatesEndpoint(ex) } }
        httpServer.createContext("/messages/clear") { ex -> handle(ex) { clearMessages(ex) } }
        httpServer.createContext("/messages") { ex -> handle(ex) { messages(ex) } }
        httpServer.createContext("/wait") { ex -> handle(ex) { waitFor(ex) } }
        httpServer.createContext("/select") { ex -> handle(ex) { select(ex) } }
        httpServer.createContext("/search") { ex -> handle(ex) { search(ex) } }
        httpServer.createContext("/filter") { ex -> handle(ex) { filter(ex) } }
        httpServer.createContext("/demo") { ex -> handle(ex) { demo(ex) } }
        httpServer.createContext("/connect") { ex -> handle(ex) { connect(ex) } }
        httpServer.createContext("/disconnect") { ex -> handle(ex) { disconnect(ex) } }
        httpServer.createContext("/send/all") { ex -> handle(ex) { sendAll(ex) } }
        httpServer.createContext("/send") { ex -> handle(ex) { send(ex) } }
        httpServer.createContext("/templates/send") { ex -> handle(ex) { sendTemplate(ex) } }
        httpServer.createContext("/screenshot") { ex -> screenshot(ex) }
        httpServer.start()
        server = httpServer
        logger.info("FixTool control server listening on http://127.0.0.1:$port (token={})", token != null)
    }

    fun stop() {
        server?.stop(0)
        server = null
    }

    // ---------------------------------------------------------------- endpoints

    private fun health(): JsonElement =
        buildJsonObject {
            put("status", "ok")
            put("sessionCount", onEdt { viewModel.sessions.size })
            put("version", "1")
        }

    private fun sessions(): JsonElement =
        buildJsonArray {
            onEdt { viewModel.sessions.toList() }.forEachIndexed { index, session ->
                add(sessionSummary(index, session))
            }
        }

    private fun sessionSummary(index: Int, session: FixMessageSession): JsonObject =
        buildJsonObject {
            put("index", index)
            put("id", session.id)
            put("title", session.title)
            put("state", session.connectionState.value.name)
            put("profileSlot", session.profileSlot)
            put("messageCount", session.messages.value.size)
            put("senderCompID", session.currentConfig?.senderCompID ?: "")
            put("targetCompID", session.currentConfig?.targetCompID ?: "")
        }

    private fun profiles(): JsonElement =
        buildJsonArray {
            onEdt { viewModel.connectionProfiles.toList() }.forEach { profile ->
                add(
                    buildJsonObject {
                        put("id", profile.id)
                        put("name", profile.name)
                        put("connectionType", profile.config.connectionType.name)
                        put("host", profile.config.host)
                        put("port", profile.config.port)
                        put("beginString", profile.config.beginString)
                        put("senderCompID", profile.config.senderCompID)
                        put("targetCompID", profile.config.targetCompID)
                    },
                )
            }
        }

    /** `/profiles` is method-aware: GET lists, POST creates/updates, DELETE removes. */
    private fun profilesEndpoint(ex: HttpExchange): JsonElement =
        when (ex.requestMethod.uppercase()) {
            "POST" -> upsertProfile(ex)
            "DELETE" -> deleteProfile(ex)
            else -> profiles()
        }

    /**
     * Creates a connection profile (or updates one when `id` is given) from a JSON body of the
     * form `{ "name": "...", "config": { ... } }`. `config` only needs the fields that differ
     * from the model defaults (host, port, senderCompID, targetCompID, connectionType, useSSL, …).
     * The saved profile is immediately connectable via `/connect`.
     */
    private fun upsertProfile(ex: HttpExchange): JsonElement {
        val body = readJson(ex)
        val name = body["name"]?.jsonPrimitive?.content ?: return errorObject("missing 'name'")
        val configElement = body["config"] ?: return errorObject("missing 'config' object")
        val config =
            try {
                profileJson.decodeFromJsonElement(FixConnectionConfig.serializer(), configElement)
            } catch (e: Exception) {
                return errorObject("invalid config: ${e.message}")
            }

        val existingId = body["id"]?.jsonPrimitive?.content
        return onEdt {
            val existing = existingId?.let { id -> viewModel.connectionProfiles.firstOrNull { it.id == id } }
            val profile =
                existing?.copy(name = name, config = config)
                    ?: if (existingId != null) {
                        FixConnectionProfile(id = existingId, name = name, config = config)
                    } else {
                        FixConnectionProfile(name = name, config = config)
                    }
            if (!viewModel.saveConnectionProfile(profile)) {
                return@onEdt errorObject("failed to persist profile")
            }
            buildJsonObject {
                put("status", if (existing != null) "updated" else "created")
                put("id", profile.id)
                put("name", profile.name)
            }
        }
    }

    private fun deleteProfile(ex: HttpExchange): JsonElement {
        val id =
            readJson(ex)["id"]?.jsonPrimitive?.content
                ?: queryParams(ex)["id"]
                ?: return errorObject("missing 'id'")
        onEdt { viewModel.deleteConnectionProfile(id) }
        return buildJsonObject {
            put("status", "deleted")
            put("id", id)
        }
    }

    /**
     * Shows or hides a UI panel/dialog for verification screenshots. `panel` is one of
     * connection, editor, detail, settings; `show` (default true) sets the desired state.
     */
    private fun panel(ex: HttpExchange): JsonElement {
        val body = readJson(ex)
        val name = body["panel"]?.jsonPrimitive?.content?.lowercase() ?: return errorObject("missing 'panel'")
        val show = body["show"]?.jsonPrimitive?.booleanOrNull ?: true
        val applied =
            onEdt {
                val (state, toggle) =
                    when (name) {
                        "connection" -> viewModel.showConnectionPanel.value to viewModel::toggleConnectionPanel
                        "editor" -> viewModel.showMessageEditor.value to viewModel::toggleMessageEditor
                        "detail" -> viewModel.showDetailPanel.value to viewModel::toggleDetailPanel
                        "settings" -> viewModel.showSettingsDialog.value to viewModel::toggleSettingsDialog
                        else -> return@onEdt null
                    }
                if (state != show) toggle()
                show
            } ?: return errorObject("unknown panel '$name' (connection|editor|detail|settings)")
        return buildJsonObject {
            put("status", "ok")
            put("panel", name)
            put("show", applied)
        }
    }

    /** `/templates` is method-aware: GET lists, POST creates/updates, DELETE removes. */
    private fun templatesEndpoint(ex: HttpExchange): JsonElement =
        when (ex.requestMethod.uppercase()) {
            "POST" -> upsertTemplate(ex)
            "DELETE" -> deleteTemplate(ex)
            else -> listTemplates(ex)
        }

    private fun listTemplates(ex: HttpExchange): JsonElement {
        val profileKey = queryParams(ex)["profile"]
        // Resolve and read in one EDT hop; reject an unknown profile rather than listing everything.
        val templates =
            onEdt {
                val profileId =
                    if (profileKey == null) {
                        null
                    } else {
                        viewModel.connectionProfiles.firstOrNull { it.id == profileKey || it.name == profileKey }?.id
                            ?: return@onEdt null
                    }
                viewModel.savedMessages.filter { profileId == null || profileId in it.userTags }
            } ?: return errorObject("unknown profile: $profileKey")
        return buildJsonObject {
            put("count", templates.size)
            put("templates", buildJsonArray { templates.forEach { add(templateJson(it)) } })
        }
    }

    private fun templateJson(t: SavedFixMessage): JsonObject =
        buildJsonObject {
            put("id", t.id)
            put("name", t.name)
            put("messageType", t.getMessageType())
            put("isFavorite", t.isFavorite)
            put("userTags", buildJsonArray { t.userTags.forEach { add(it) } })
            put(
                "fields",
                buildJsonArray {
                    t.fields.forEach { f ->
                        add(
                            buildJsonObject {
                                put("tag", f.tag)
                                put("value", f.value)
                                if (f.excluded) put("excluded", true)
                            },
                        )
                    }
                },
            )
        }

    /**
     * Creates a template (or updates one when `id` is given). Fields come from a `fields` array of
     * `{tag, value, excluded?}` or from a `raw` FIX string. Organise via `userTags` (defaults to the
     * profile) and `isFavorite`.
     */
    private fun upsertTemplate(ex: HttpExchange): JsonElement {
        val body = readJson(ex)
        val name = body["name"]?.jsonPrimitive?.content ?: return errorObject("missing 'name'")
        val fields = templateFieldsFromBody(body) ?: return errorObject("provide 'fields' [{tag,value}] or 'raw'")
        if (fields.isEmpty()) return errorObject("no fields parsed")
        val id = body["id"]?.jsonPrimitive?.content
        val isFavorite = body["isFavorite"]?.jsonPrimitive?.booleanOrNull ?: false
        val profileKey = body["profile"]?.jsonPrimitive?.content
        val userTagsOverride = (body["userTags"] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet()

        // Resolve profile, save, and shape the response in a single EDT round-trip.
        return onEdt {
            val profileId =
                viewModel.connectionProfiles.firstOrNull { it.id == profileKey || it.name == profileKey }?.id
                    ?: return@onEdt errorObject("missing or unknown 'profile'")
            val userTags = userTagsOverride ?: setOf(profileId)
            val result = viewModel.saveTemplateDirect(profileId, name, fields, userTags, isFavorite, id)
                ?: return@onEdt errorObject("failed to persist template")
            buildJsonObject {
                put("status", if (result.created) "created" else "updated")
                put("id", result.message.id)
                put("name", result.message.name)
                put("messageType", result.message.getMessageType())
            }
        }
    }

    private fun templateFieldsFromBody(body: JsonObject): List<SavedFixField>? {
        (body["fields"] as? JsonArray)?.let { arr ->
            return arr.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val tag = obj["tag"]?.jsonPrimitive?.content ?: return@mapNotNull null
                SavedFixField(
                    tag = tag,
                    value = obj["value"]?.jsonPrimitive?.content ?: "",
                    excluded = obj["excluded"]?.jsonPrimitive?.booleanOrNull ?: false,
                )
            }
        }
        body["raw"]?.jsonPrimitive?.content?.let { raw ->
            return FixMessageHelper.parseFixMessage(raw)
                .map { (tag, value) -> SavedFixField(tag = tag.toString(), value = value) }
        }
        return null
    }

    private fun deleteTemplate(ex: HttpExchange): JsonElement {
        val body = readJson(ex)
        val id = body["id"]?.jsonPrimitive?.content ?: queryParams(ex)["id"] ?: return errorObject("missing 'id'")
        val profileKey = body["profile"]?.jsonPrimitive?.content
        return onEdt {
            val profileId =
                profileKey?.let { k -> viewModel.connectionProfiles.firstOrNull { it.id == k || it.name == k }?.id }
                    ?: viewModel.savedMessages.firstOrNull { it.id == id }?.userTags?.firstOrNull()
                    ?: return@onEdt errorObject("could not determine profile for template; pass 'profile'")
            viewModel.deleteSavedMessage(id, profileId)
            buildJsonObject {
                put("status", "deleted")
                put("id", id)
            }
        }
    }

    /** Loads a saved template into the message editor (and opens the editor panel for a screenshot). */
    private fun loadTemplate(ex: HttpExchange): JsonElement {
        val id = readJson(ex)["id"]?.jsonPrimitive?.content ?: return errorObject("missing 'id'")
        return onEdt {
            val template = viewModel.savedMessages.firstOrNull { it.id == id }
                ?: return@onEdt errorObject("template not found: $id")
            viewModel.loadEditorMessage(template)
            if (!viewModel.showMessageEditor.value) viewModel.toggleMessageEditor()
            buildJsonObject {
                put("status", "loaded")
                put("id", template.id)
                put("name", template.name)
                put("messageType", template.getMessageType())
            }
        }
    }

    private fun messages(ex: HttpExchange): JsonElement {
        val params = queryParams(ex)
        val limit = (params["limit"]?.toIntOrNull() ?: DEFAULT_MESSAGE_LIMIT).coerceAtLeast(0)
        val directionFilter = params["direction"]?.lowercase()
        val session = resolveSession(params["session"]) ?: return errorObject("session not found")

        // Filter and slice inside the EDT block so only the needed messages cross the thread boundary.
        val (total, recent) =
            onEdt {
                val matching =
                    session.messages.value
                        .filterIsInstance<FixMessage>()
                        .filter { directionMatches(it, directionFilter) }
                matching.size to matching.takeLast(limit)
            }

        return buildJsonObject {
            put("session", session.title)
            put("total", total)
            put(
                "messages",
                buildJsonArray { recent.forEach { add(messageJson(it)) } },
            )
        }
    }

    private fun directionMatches(msg: FixMessage, filter: String?): Boolean =
        when (filter) {
            "in", "incoming" -> msg.direction == FixMessage.Direction.INCOMING
            "out", "outgoing" -> msg.direction == FixMessage.Direction.OUTGOING
            else -> true
        }

    private fun messageJson(msg: FixMessage): JsonObject =
        buildJsonObject {
            put("timestamp", msg.timestamp.toString())
            put("direction", msg.direction.name)
            put("messageType", msg.messageType)
            put("raw", msg.rawMessage)
            put(
                "fields",
                buildJsonArray {
                    FixMessageHelper.parseFixMessage(msg.rawMessage).forEach { (tag, value) ->
                        add(
                            buildJsonObject {
                                put("tag", tag)
                                put("value", value)
                            },
                        )
                    }
                },
            )
        }

    /**
     * Selects a message in the running app's message browser: sets it as the selected message,
     * which opens the detail/inspection panel and switches to the owning session tab. Pick by
     * `index` into the session's message list (default: most recent), optionally narrowed by
     * `messageType` (tag 35) and `direction`. Mirrors clicking a row in the UI.
     */
    private fun select(ex: HttpExchange): JsonElement {
        val body = readJson(ex)
        val session = resolveSession(body["session"]?.jsonPrimitive?.content) ?: return errorObject("session not found")
        val msgType = body["messageType"]?.jsonPrimitive?.content
        val directionFilter = body["direction"]?.jsonPrimitive?.content?.lowercase()
        val index = body["index"]?.jsonPrimitive?.intOrNull

        // Filter, pick, and select in a single EDT round-trip.
        return onEdt {
            val candidates =
                session.messages.value
                    .filterIsInstance<FixMessage>()
                    .filter { msgType == null || it.messageType == msgType }
                    .filter { directionMatches(it, directionFilter) }
            if (candidates.isEmpty()) return@onEdt errorObject("no matching messages to select")
            val target = (if (index != null) candidates.getOrNull(index) else candidates.last())
                ?: return@onEdt errorObject("index out of range (0..${candidates.size - 1})")

            viewModel.selectMessage(target)
            buildJsonObject {
                put("status", "selected")
                put("session", session.title)
                put("messageType", target.messageType)
                put("direction", target.direction.name)
                put("raw", target.rawMessage)
            }
        }
    }

    /**
     * Runs a global (cross-session) search and returns the matches sorted chronologically — i.e.
     * a timeline. When `pin` is true (default) the results are pinned to the on-screen search-
     * results pane so a follow-up screenshot shows them.
     */
    private fun search(ex: HttpExchange): JsonElement {
        val body = readJson(ex)
        val query = body["query"]?.jsonPrimitive?.content ?: return errorObject("missing 'query'")
        val pin = body["pin"]?.jsonPrimitive?.booleanOrNull ?: true

        val results =
            onEdt {
                viewModel.setGlobalSearchQuery(query)
                if (pin) viewModel.pinSearchResults()
                viewModel.globalSearchResults.value.toList()
            }

        return buildJsonObject {
            put("query", query)
            put("count", results.size)
            put(
                "results",
                buildJsonArray {
                    results.forEach { r ->
                        add(
                            buildJsonObject {
                                put("timestamp", r.message.timestamp.toString())
                                put("session", r.sessionUsername)
                                put("direction", r.message.direction.name)
                                put("messageType", r.message.messageType)
                                put("description", r.messageTypeDescription)
                                put("msgSeqNum", r.msgSeqNum)
                                put("senderCompID", r.senderCompId)
                                put("matched", r.matchedText)
                                put("raw", r.message.rawMessage)
                            },
                        )
                    }
                },
            )
        }
    }

    /**
     * Applies a display filter so the message grid shows only matching rows (for a focused
     * verification screenshot). `scope` is "global" (default, across all sessions) or "session".
     * Recognised keys: regex, showIncoming, showOutgoing; plus messageTypes and showSeparator
     * for session scope. Omitted keys are left unchanged.
     */
    private fun filter(ex: HttpExchange): JsonElement {
        val body = readJson(ex)
        val scope = body["scope"]?.jsonPrimitive?.content?.lowercase() ?: "global"
        if (scope != "global" && scope != "session") {
            return errorObject("unknown scope '$scope' (global|session)")
        }
        val regex = body["regex"]?.jsonPrimitive?.content
        val showIncoming = body["showIncoming"]?.jsonPrimitive?.booleanOrNull
        val showOutgoing = body["showOutgoing"]?.jsonPrimitive?.booleanOrNull

        if (scope == "session") {
            val session =
                resolveSession(body["session"]?.jsonPrimitive?.content) ?: return errorObject("session not found")
            val messageTypes = body["messageTypes"]?.jsonPrimitive?.content
            val showSeparator = body["showSeparator"]?.jsonPrimitive?.booleanOrNull
            onEdt {
                regex?.let { session.setFilterRegex(it) }
                messageTypes?.let { session.setFilterMessageTypes(it) }
                showIncoming?.let { session.setFilterShowIncoming(it) }
                showOutgoing?.let { session.setFilterShowOutgoing(it) }
                showSeparator?.let { session.setFilterShowSeparator(it) }
            }
            return buildJsonObject {
                put("status", "filtered")
                put("scope", "session")
                put("session", session.title)
            }
        }

        onEdt {
            regex?.let { viewModel.setGlobalFilterRegex(it) }
            showIncoming?.let { viewModel.setGlobalFilterShowIncoming(it) }
            showOutgoing?.let { viewModel.setGlobalFilterShowOutgoing(it) }
        }
        return buildJsonObject {
            put("status", "filtered")
            put("scope", "global")
        }
    }

    private fun demo(ex: HttpExchange): JsonElement {
        val body = readJson(ex)
        val action = body["action"]?.jsonPrimitive?.content?.lowercase() ?: "start"
        onEdt {
            when (action) {
                "stop" -> viewModel.stopDemoServer()
                else -> viewModel.startDemoServer()
            }
        }
        return buildJsonObject {
            put("status", "ok")
            put("action", action)
            put("running", onEdt { viewModel.demoServerRunning.value })
        }
    }

    private fun connect(ex: HttpExchange): JsonElement {
        val body = readJson(ex)
        val key = body["profile"]?.jsonPrimitive?.content ?: return errorObject("missing 'profile'")
        val profile =
            onEdt { viewModel.connectionProfiles.firstOrNull { it.id == key || it.name == key } }
                ?: return errorObject("profile not found: $key")
        onEdt { viewModel.connectProfile(profile.id, profile) }
        return buildJsonObject {
            put("status", "connecting")
            put("profile", profile.name)
        }
    }

    private fun disconnect(ex: HttpExchange): JsonElement {
        val body = readJson(ex)
        val key = body["profile"]?.jsonPrimitive?.content ?: return errorObject("missing 'profile'")
        val profile =
            onEdt { viewModel.connectionProfiles.firstOrNull { it.id == key || it.name == key } }
                ?: return errorObject("profile not found: $key")
        onEdt { viewModel.disconnectProfile(profile.id) }
        return buildJsonObject {
            put("status", "disconnecting")
            put("profile", profile.name)
        }
    }

    /**
     * Sends a raw FIX message from one session (the active one, or `session` by id/title/index).
     * With `resolve: true` the template expressions in `raw` (`${...}`, `{n}`) are resolved against
     * the session before sending — the same path the editor's Send button uses.
     */
    private fun send(ex: HttpExchange): JsonElement {
        val body = readJson(ex)
        val raw = body["raw"]?.jsonPrimitive?.content ?: return errorObject("missing 'raw' FIX message")
        val sessionKey = body["session"]?.jsonPrimitive?.content
        val resolve = body["resolve"]?.jsonPrimitive?.booleanOrNull ?: false

        return onEdt {
            val index =
                if (sessionKey != null) {
                    val i = viewModel.sessions.indexOfFirst { it.id == sessionKey || it.title == sessionKey }
                    if (i < 0) return@onEdt errorObject("session not found: $sessionKey")
                    viewModel.setActiveSession(i)
                    i
                } else {
                    viewModel.activeSessionIndex
                }
            val result =
                if (resolve) {
                    if (index < 0) return@onEdt errorObject("no active session")
                    viewModel.sendResolvedToSession(raw, index)
                } else {
                    viewModel.sendMessage(raw)
                }
            buildJsonObject {
                put("status", if (result == null) "failed" else sendResultStatus(result))
                put("result", result?.toString() ?: "no active session")
            }
        }
    }

    /** Bulk-sends one message to every logged-on session, re-resolving template expressions per session. */
    private fun sendAll(ex: HttpExchange): JsonElement {
        val raw = readJson(ex)["raw"]?.jsonPrimitive?.content ?: return errorObject("missing 'raw' FIX message")
        val outcomes = onEdt { viewModel.sendMessageToAllConnectedSessions(raw) }
        return buildJsonObject {
            put("status", "ok")
            put("count", outcomes.size)
            put(
                "results",
                buildJsonArray {
                    outcomes.forEach { o ->
                        add(
                            buildJsonObject {
                                put("session", o.session.title)
                                put("result", sendResultStatus(o.result))
                                put("detail", o.result.toString())
                            },
                        )
                    }
                },
            )
        }
    }

    /** Sends a saved template (expressions resolved) from a session by id/title/index, or the active one. */
    private fun sendTemplate(ex: HttpExchange): JsonElement {
        val body = readJson(ex)
        val id = body["id"]?.jsonPrimitive?.content ?: return errorObject("missing 'id'")
        val sessionKey = body["session"]?.jsonPrimitive?.content
        return onEdt {
            val template = viewModel.savedMessages.firstOrNull { it.id == id }
                ?: return@onEdt errorObject("template not found: $id")
            val index =
                if (sessionKey != null) {
                    viewModel.sessions.indexOfFirst { it.id == sessionKey || it.title == sessionKey }
                        .also { if (it < 0) return@onEdt errorObject("session not found: $sessionKey") }
                } else {
                    viewModel.activeSessionIndex
                }
            if (index < 0) return@onEdt errorObject("no active session")
            val rawTemplate =
                template.fields.filterNot { it.excluded }.joinToString("|") { "${it.tag}=${it.value}" } + "|"
            val result = viewModel.sendResolvedToSession(rawTemplate, index)
            buildJsonObject {
                put("status", if (result == null) "failed" else sendResultStatus(result))
                put("template", template.name)
                put("result", result?.toString() ?: "send failed")
            }
        }
    }

    private fun clearMessages(ex: HttpExchange): JsonElement {
        val session =
            resolveSession(readJson(ex)["session"]?.jsonPrimitive?.content) ?: return errorObject("session not found")
        onEdt { session.clearMessages() }
        return buildJsonObject {
            put("status", "cleared")
            put("session", session.title)
        }
    }

    /**
     * Blocks (up to `timeoutMs`) until a session reaches `state` (e.g. LOGGED_ON) or a message
     * matching `match` ({messageType?, direction?, tag?, value?}) arrives. Returns the matched
     * message, or {status:"timeout"}. Polls StateFlow values off-thread (no EDT blocking) so it is
     * the deterministic replacement for client-side polling loops.
     */
    private fun waitFor(ex: HttpExchange): JsonElement {
        val body = readJson(ex)
        val session =
            resolveSession(body["session"]?.jsonPrimitive?.content) ?: return errorObject("session not found")
        val targetState = body["state"]?.jsonPrimitive?.content?.uppercase()
        val match = body["match"] as? JsonObject
        if (targetState == null && match == null) return errorObject("provide 'state' or 'match'")
        val timeoutMs = (body["timeoutMs"]?.jsonPrimitive?.longOrNull ?: DEFAULT_WAIT_MS).coerceIn(0, MAX_WAIT_MS)

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (targetState != null && session.connectionState.value.name == targetState) {
                return buildJsonObject {
                    put("status", "matched")
                    put("state", session.connectionState.value.name)
                }
            }
            if (match != null) {
                val found =
                    session.messages.value.filterIsInstance<FixMessage>().firstOrNull { matchesMessage(it, match) }
                if (found != null) {
                    return buildJsonObject {
                        put("status", "matched")
                        put("message", messageJson(found))
                    }
                }
            }
            Thread.sleep(WAIT_POLL_MS)
        }
        return buildJsonObject { put("status", "timeout") }
    }

    private fun matchesMessage(msg: FixMessage, match: JsonObject): Boolean {
        match["messageType"]?.jsonPrimitive?.content?.let { if (msg.messageType != it) return false }
        match["direction"]?.jsonPrimitive?.content?.lowercase()?.let { if (!directionMatches(msg, it)) return false }
        val tag = match["tag"]?.jsonPrimitive?.intOrNull
        if (tag != null) {
            val actual = msg.valueOfTag(tag) ?: return false
            match["value"]?.jsonPrimitive?.content?.let { if (actual != it) return false }
        }
        return true
    }

    private fun sendResultStatus(result: SendResult): String =
        when (result) {
            is SendResult.Success -> "sent"
            is SendResult.SuccessWithWarning -> "warning"
            is SendResult.Failed -> "failed"
        }

    private fun screenshot(ex: HttpExchange) {
        try {
            if (!authorized(ex)) {
                respondText(ex, HTTP_UNAUTHORIZED, "unauthorized")
                return
            }
            val window =
                windowProvider() ?: run {
                    respondText(ex, HTTP_NOT_FOUND, "no window")
                    return
                }
            val bounds = onEdt { window.bounds }
            val image = Robot().createScreenCapture(bounds)
            val bytes =
                ByteArrayOutputStream().use { out ->
                    ImageIO.write(image, "png", out)
                    out.toByteArray()
                }
            ex.responseHeaders.add("Content-Type", "image/png")
            ex.sendResponseHeaders(HTTP_OK, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        } catch (e: Exception) {
            logger.error("Screenshot failed", e)
            respondText(ex, HTTP_SERVER_ERROR, "screenshot failed: ${e.message}")
        } finally {
            ex.close()
        }
    }

    // ---------------------------------------------------------------- plumbing

    private fun handle(ex: HttpExchange, block: () -> JsonElement) {
        try {
            if (!authorized(ex)) {
                respondJson(ex, HTTP_UNAUTHORIZED, errorObject("unauthorized"))
                return
            }
            respondJson(ex, HTTP_OK, block())
        } catch (e: Exception) {
            logger.error("Control request failed: ${ex.requestURI}", e)
            respondJson(ex, HTTP_SERVER_ERROR, errorObject(e.message ?: e.toString()))
        } finally {
            ex.close()
        }
    }

    private fun authorized(ex: HttpExchange): Boolean {
        val required = token ?: return true
        return ex.requestHeaders.getFirst("X-Control-Token") == required
    }

    private fun resolveSession(key: String?): FixMessageSession? =
        onEdt {
            val list = viewModel.sessions
            when {
                key == null -> list.firstOrNull()
                key.toIntOrNull() != null -> list.getOrNull(key.toInt())
                else -> list.firstOrNull { it.id == key || it.title == key }
            }
        }

    private fun queryParams(ex: HttpExchange): Map<String, String> =
        ex.requestURI.rawQuery
            ?.split("&")
            ?.mapNotNull { pair ->
                val idx = pair.indexOf('=')
                if (idx <= 0) {
                    null
                } else {
                    java.net.URLDecoder.decode(pair.substring(0, idx), "UTF-8") to
                        java.net.URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                }
            }?.toMap()
            .orEmpty()

    private fun readJson(ex: HttpExchange): JsonObject {
        val text = ex.requestBody.readBytes().decodeToString()
        if (text.isBlank()) return JsonObject(emptyMap())
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun respondJson(ex: HttpExchange, code: Int, body: JsonElement) =
        respondText(ex, code, body.toString(), "application/json")

    private fun respondText(ex: HttpExchange, code: Int, text: String, contentType: String = "text/plain") {
        val bytes = text.encodeToByteArray()
        ex.responseHeaders.add("Content-Type", contentType)
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    /** Runs [block] on the Swing EDT and returns its result, since Compose state is EDT-bound. */
    private fun <T> onEdt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        val holder = AtomicReference<Any?>()
        SwingUtilities.invokeAndWait {
            holder.set(
                runCatching(block).fold(onSuccess = { it as Any? }, onFailure = { it }),
            )
        }
        return when (val result = holder.get()) {
            is Throwable -> throw result
            else ->
                @Suppress("UNCHECKED_CAST")
                (result as T)
        }
    }

    private fun errorObject(message: String): JsonObject =
        buildJsonObject {
            put("status", "error")
            put("error", message)
        }

    companion object {
        private const val HTTP_OK = 200
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_SERVER_ERROR = 500
        private const val DEFAULT_MESSAGE_LIMIT = 50
        private const val HTTP_POOL_SIZE = 4
        private const val DEFAULT_WAIT_MS = 10_000L
        private const val MAX_WAIT_MS = 120_000L
        private const val WAIT_POLL_MS = 100L
    }
}

/**
 * Starts a single [ControlServer] when `FIXTOOL_CONTROL_PORT` is set; a no-op otherwise.
 * Safe to call multiple times — only the first call with a valid port starts a server.
 */
object ControlServerLauncher {
    private val logger = LoggerFactory.getLogger(ControlServerLauncher::class.java)
    private var server: ControlServer? = null

    @Synchronized
    fun maybeStart(viewModel: FixMessageViewModel, windowProvider: () -> Window?) {
        if (server != null) return
        val port = System.getenv("FIXTOOL_CONTROL_PORT")?.toIntOrNull() ?: return
        try {
            server = ControlServer(port, viewModel, windowProvider).also { it.start() }
        } catch (e: Exception) {
            logger.error("Failed to start control server on port $port", e)
        }
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
    }
}
