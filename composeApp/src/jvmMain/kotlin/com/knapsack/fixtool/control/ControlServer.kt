// This file is an HTTP request-handling boundary: each endpoint legitimately catches broad
// exceptions to convert any failure into a 500 response, and the per-endpoint handlers
// naturally push the class past detekt's function-count threshold.
@file:Suppress("TooManyFunctions", "TooGenericExceptionCaught", "ReturnCount", "LargeClass", "CyclomaticComplexMethod")

package com.knapsack.fixtool.control

import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixMessageSession
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.MatchContextMode
import com.knapsack.fixtool.model.SavedFixField
import com.knapsack.fixtool.model.SavedFixMessage
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.ui.firstFailure
import com.knapsack.fixtool.service.ExpectationEvaluator
import com.knapsack.fixtool.service.ExpectationSeeder
import com.knapsack.fixtool.service.FixMessageHelper
import com.knapsack.fixtool.service.FixMessageTemplate
import com.knapsack.fixtool.service.FixMessageValidator
import com.knapsack.fixtool.service.FixMessageView
import com.knapsack.fixtool.service.MatcherCodec
import com.knapsack.fixtool.model.scenario.StepOrigin
import com.knapsack.fixtool.service.ScenarioCapture
import com.knapsack.fixtool.service.ScenarioCodec
import com.knapsack.fixtool.service.ScenarioReport
import com.knapsack.fixtool.service.SendResult
import com.knapsack.fixtool.service.SessionTags
import com.knapsack.fixtool.service.compare.ReferenceMessage
import com.knapsack.fixtool.service.compare.WirePaste
import com.knapsack.fixtool.ui.diff.DiffSide
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import com.sun.net.httpserver.Headers
import com.sun.net.httpserver.HttpContext
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpPrincipal
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.awt.Robot
import java.awt.Window
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLEncoder
import java.util.Base64
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
    /**
     * **Every showing top-level window, not "the first one."**
     *
     * It used to be `() -> Window?` = `getWindows().firstOrNull()`, which was right only because there was
     * one window. Once the diff opens in its own window (Phase 6), `getWindows()` returns AWT's windows in no
     * defined order, so `firstOrNull()` photographs whichever it happens to list first — the main window or a
     * diff, from run to run. So the provider hands back **all** of them and [selectWindow] picks by title, so
     * `?window=` is deterministic and the *main* window's screenshot is deterministic again too.
     */
    private val windowProvider: () -> List<Window>,
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
        httpServer.createContext("/assert") { ex -> handle(ex) { assertMessage(ex) } }
        httpServer.createContext("/expectation/capture") { ex -> handle(ex) { captureExpectation(ex) } }
        httpServer.createContext("/scenarios/reconcile") { ex -> handle(ex) { reconcile(ex) } }
        httpServer.createContext("/scenarios/diff") { ex -> handle(ex) { diffMessages(ex) } }
        httpServer.createContext("/scenarios/run") { ex -> handle(ex) { runScenario(ex) } }
        httpServer.createContext("/scenarios/capture") { ex -> handle(ex) { captureScenario(ex) } }
        httpServer.createContext("/scenarios/capture-paste") { ex -> handle(ex) { capturePaste(ex) } }
        httpServer.createContext("/scenarios") { ex -> handle(ex) { scenariosEndpoint(ex) } }
        httpServer.createContext("/detail") { ex -> handle(ex) { detailSearch(ex) } }
        httpServer.createContext("/search") { ex -> handle(ex) { search(ex) } }
        httpServer.createContext("/filter") { ex -> handle(ex) { filter(ex) } }
        httpServer.createContext("/demo") { ex -> handle(ex) { demo(ex) } }
        httpServer.createContext("/connect") { ex -> handle(ex) { connect(ex) } }
        httpServer.createContext("/disconnect") { ex -> handle(ex) { disconnect(ex) } }
        httpServer.createContext("/send/all") { ex -> handle(ex) { sendAll(ex) } }
        httpServer.createContext("/send") { ex -> handle(ex) { send(ex) } }
        httpServer.createContext("/templates/send") { ex -> handle(ex) { sendTemplate(ex) } }
        httpServer.createContext("/admin") { ex -> handle(ex) { admin(ex) } }
        httpServer.createContext("/validate") { ex -> handle(ex) { validate(ex) } }
        httpServer.createContext("/dictionary") { ex -> handle(ex) { dictionaryEndpoint(ex) } }
        httpServer.createContext("/acceptor/rules") { ex -> handle(ex) { acceptorRules(ex) } }
        httpServer.createContext("/syntax") { ex -> syntax(ex) }
        httpServer.createContext("/screenshot") { ex -> screenshot(ex) }
        httpServer.createContext("/mcp") { ex -> mcpHandle(ex) }
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
                        "scenarios" -> viewModel.showScenariosRail.value to viewModel::toggleScenariosRail
                        else -> return@onEdt null
                    }
                if (state != show) toggle()
                show
            } ?: return errorObject("unknown panel '$name' (connection|editor|detail|settings|scenarios)")
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
            // False when FixTool has no wire bytes for this message: `fields` below is then a BEST-EFFORT read
            // of the display string, in QuickFIX's field order rather than the venue's, and `fixtool_assert`
            // will refuse the message outright. Without this flag the read surface and the assert surface
            // silently disagreed about what arrived — the agent is shown a message, then told it does not exist.
            put("wireOrderKnown", msg.wireRaw != null)
            put(
                "fields",
                buildJsonArray {
                    // From the venue's bytes, not from `raw`. `raw` substitutes '|' for SOH so a human can
                    // read it, and that substitution is lossy: `|` is an ordinary character inside a FIX
                    // value, so splitting `58=Rejected|insufficient margin` on it yields a truncated Text
                    // field. This array is what an agent is told to assert against — its own tool
                    // description says "assert against these rather than screenshots" — so it handed the
                    // agent `58 = "Rejected"`, which fixtool_assert (reading the real bytes) then failed.
                    // The read surface and the assert surface must agree about what arrived.
                    FixMessageHelper.fieldsForDisplay(msg).forEach { (tag, value) ->
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
     * Asserts a received message against an expectation (per-tag matchers): selects/awaits the
     * message like [select]/[waitFor], evaluates each field via [ExpectationEvaluator], and returns
     * a tag-by-tag pass/fail report. This is the standalone keystone of the repeatable-scenarios
     * design — it converts the manual "eyeball the response" step into a machine check. `reference`
     * matchers (e.g. `${out.D.11}`) resolve against the session's message history, exactly the way
     * `fixtool_send resolve=true` does.
     */
    private fun assertMessage(ex: HttpExchange): JsonElement {
        val body = readJson(ex)
        val session = resolveSession(body["session"]?.jsonPrimitive?.content) ?: return errorObject("session not found")
        val msgType = body["messageType"]?.jsonPrimitive?.content
        val directionFilter = body["direction"]?.jsonPrimitive?.content?.lowercase() ?: "in"
        val index = body["index"]?.jsonPrimitive?.intOrNull
        val timeoutMs = (body["timeoutMs"]?.jsonPrimitive?.longOrNull ?: 0L).coerceIn(0, MAX_WAIT_MS)
        val strict = body["mode"]?.jsonPrimitive?.content?.lowercase() == "strict"
        val mode = if (strict) MatchMode.STRICT else MatchMode.OPEN
        val fieldsArray = body["fields"]?.jsonArray ?: return errorObject("missing 'fields' (per-tag matchers)")
        // An empty rows array is not an assertion. `results.all { passed }` on an empty list is `true`, so this
        // answered {passed: true, tags: []} — a check that verified nothing and reported success, to an agent
        // that will record it as a green. The `?:` above only guarded the key being *absent*.
        if (fieldsArray.isEmpty()) {
            return errorObject(
                "'fields' is empty: an expectation with no rows asserts nothing and would pass every run " +
                    "without checking anything. Send at least one {tag, matcher}.",
            )
        }

        val expectation =
            try {
                MatcherCodec.parseExpectation(fieldsArray, msgType, mode)
            } catch (e: IllegalArgumentException) {
                return errorObject("invalid fields: ${e.message}")
            }

        val target =
            awaitMessage(session, msgType, directionFilter, index, timeoutMs)
                ?: return buildJsonObject {
                    put("passed", false)
                    put("status", "timeout")
                    put("error", "no ${msgType ?: "matching"} '$directionFilter' message within ${timeoutMs}ms")
                }

        // See ScenarioRunner: an expectation asserts an order as well as a set of values, so a message
        // whose wire order we do not have cannot be judged at all. Report that, rather than assert
        // against QuickFIX's re-serialisation and blame the venue for the difference.
        val view =
            FixMessageView.of(target)
                ?: return buildJsonObject {
                    put("passed", false)
                    put("status", "no-wire-bytes")
                    put(
                        "error",
                        "FixTool has no wire bytes for the matched ${target.messageType}, so its field " +
                            "order is unknown and this expectation cannot be evaluated. This is a FixTool " +
                            "limitation, not a venue failure.",
                    )
                    // Same keys as a judged reply, so a caller can read this one without special-casing it.
                    // `tags` is present and empty because that is the truth — no row was evaluated — and a
                    // client that reads `tags` unconditionally should get the diagnosis, not a parse error
                    // on the one response whose whole purpose is to tell it something went wrong.
                    put("messageType", target.messageType)
                    put("direction", target.direction.name)
                    put("tags", buildJsonArray { })
                }

        val resolver = referenceResolverFor(session)
        val results =
            ExpectationEvaluator.evaluate(view, expectation, resolver)
        return buildJsonObject {
            put("passed", results.all { it.passed })
            put("messageType", target.messageType)
            put("direction", target.direction.name)
            put("tags", buildJsonArray { results.forEach { add(ScenarioReport.tagToJson(it)) } })
        }
    }

    /**
     * Builds an auto-seeded expectation from a selected/awaited message (matchers pre-seeded from
     * dictionary field types via [ExpectationSeeder]) so an author edits a draft rather than writing
     * matchers from scratch. Returns the same {messageType, mode, fields:[...]} shape [assertMessage]
     * consumes.
     */
    private fun captureExpectation(ex: HttpExchange): JsonElement {
        val body = readJson(ex)
        val session = resolveSession(body["session"]?.jsonPrimitive?.content) ?: return errorObject("session not found")
        val msgType = body["messageType"]?.jsonPrimitive?.content
        val directionFilter = body["direction"]?.jsonPrimitive?.content?.lowercase() ?: "in"
        val index = body["index"]?.jsonPrimitive?.intOrNull
        val target =
            awaitMessage(session, msgType, directionFilter, index, 0)
                ?: return errorObject("no matching message to capture")

        // Through the same door the engine reads: the venue's bytes, in the venue's order. Seeding from
        // `rawMessage` — the '|'-substituted display string — made this tool mint expectations the tool
        // itself could not satisfy. It split a value containing a pipe into a truncated field plus a
        // phantom one, so the seeded row failed against the very message it was seeded from; and where the
        // wire bytes were missing it seeded from QuickFIX's re-serialisation, baking a field order no venue
        // sent into a golden an agent would then save. capture_expectation and assert must agree about what
        // arrived, or the round trip through the MCP surface contradicts itself.
        val fields =
            FixMessageHelper.wireFields(target)
                ?: return errorObject(
                    "FixTool has no wire bytes for the matched ${target.messageType}, so its field order is " +
                        "unknown and an expectation seeded from it would assert an order the venue never sent. " +
                        "This is a FixTool limitation, not a venue failure.",
                )
        val seeded = ExpectationSeeder.seedDetailed(fields, onEdt { viewModel.dictionary })
        return buildJsonObject {
            put("messageType", target.messageType)
            put("direction", target.direction.name)
            put("mode", "open")
            // In wire order, one row per occurrence. The order is the assertion — the k-th row for a tag
            // asserts the k-th occurrence of it — so a caller must not sort or de-duplicate this array.
            put(
                "fields",
                buildJsonArray { seeded.forEach { add(MatcherCodec.fieldExpectationToJson(it.field)) } },
            )
            // What this capture does NOT assert, and why. An agent that cannot see the gap will assume
            // the expectation covers the whole message, and ship a test that checks less than it thinks.
            //
            // There is only one kind of gap left. The groups whose entries we could not tell apart used
            // to be reported here too, because the old model refused to assert them; the sequence model
            // asserts them by position, so that hole is closed rather than disclosed.
            val omitted = fields.map { it.first }.distinct().filter { it in SessionTags.NEVER_ASSERTED }
            if (omitted.isNotEmpty()) {
                put(
                    "notAsserted",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("tags", buildJsonArray { omitted.sorted().forEach { add(it) } })
                                put(
                                    "reason",
                                    "the session envelope — these identify the connection and the moment, not the " +
                                        "venue's behaviour, so asserting them would tie this scenario to the " +
                                        "environment it was captured on",
                                )
                            },
                        )
                    },
                )
            }
        }
    }

    /**
     * Selects (or, with `timeoutMs > 0`, awaits) the message to assert/capture against: the [index]th
     * — default last — message of [msgType]/[directionFilter] in the session's log.
     */
    private fun awaitMessage(
        session: FixMessageSession,
        msgType: String?,
        directionFilter: String?,
        index: Int?,
        timeoutMs: Long,
    ): FixMessage? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val candidates =
                onEdt {
                    session.messages.value
                        .filterIsInstance<FixMessage>()
                        .filter { msgType == null || it.messageType == msgType }
                        .filter { directionMatches(it, directionFilter) }
                }
            val target = if (index != null) candidates.getOrNull(index) else candidates.lastOrNull()
            if (target != null) return target
            if (System.currentTimeMillis() >= deadline) return null
            Thread.sleep(WAIT_POLL_MS)
        }
    }

    /**
     * A resolver for `reference` matchers: resolves a `${...}` expression against the session's
     * latest incoming/outgoing message per type, exactly like the editor's resolve-on-send path.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun referenceResolverFor(session: FixMessageSession): (String) -> String? {
        val messages = onEdt { session.messages.value.filterIsInstance<FixMessage>() }
        val incoming = messages.filter { it.direction == FixMessage.Direction.INCOMING }.associateBy { it.messageType }
        val outgoing = messages.filter { it.direction == FixMessage.Direction.OUTGOING }.associateBy { it.messageType }
        val dictionary = onEdt { viewModel.dictionary }
        return { expression ->
            try {
                FixMessageTemplate
                    .evaluate(expression, incoming, outgoing, null, dictionary)
                    .takeIf { it != expression }
            } catch (e: Exception) {
                null
            }
        }
    }

    // ------------------------------------------------------------ repeatable scenarios

    private fun scenariosEndpoint(ex: HttpExchange): JsonElement =
        when (ex.requestMethod.uppercase()) {
            "GET" -> if (queryParams(ex)["id"] != null) getScenario(ex) else listScenarios(ex)
            "DELETE" -> deleteScenario(ex)
            else -> saveScenario(ex)
        }

    /**
     * One saved scenario's full JSON definition — the exact shape [saveScenario] accepts, so an
     * agent can read → edit → save back losslessly (the list endpoint returns summaries only).
     */
    private fun getScenario(ex: HttpExchange): JsonElement {
        val id = queryParams(ex)["id"] ?: return errorObject("missing 'id'")
        val scenario = viewModel.scenarioService.load(id) ?: return errorObject("scenario not found: $id")
        return ScenarioCodec.toJson(scenario)
    }

    /** Lists saved scenarios (summaries), optionally filtered to a profile (id, name, or tag). */
    private fun listScenarios(ex: HttpExchange): JsonElement {
        val profileKey = queryParams(ex)["profile"]
        val profileId =
            if (profileKey == null) {
                null
            } else {
                onEdt { viewModel.connectionProfiles.firstOrNull { it.id == profileKey || it.name == profileKey }?.id }
                    ?: profileKey
            }
        val service = viewModel.scenarioService
        val scenarios = if (profileId == null) service.list() else service.listForProfile(profileId)
        return buildJsonObject {
            put("count", scenarios.size)
            put(
                "scenarios",
                buildJsonArray {
                    scenarios.forEach { s ->
                        add(
                            buildJsonObject {
                                put("id", s.id)
                                put("name", s.name)
                                s.profile?.let { put("profile", it) }
                                put("setup", s.setup.size)
                                put("steps", s.steps.size)
                                put("teardown", s.teardown.size)
                                put("userTags", buildJsonArray { s.userTags.forEach { add(it) } })
                            },
                        )
                    }
                },
            )
        }
    }

    /** Creates or updates a scenario from its JSON body (id generated when absent). */
    private fun saveScenario(ex: HttpExchange): JsonElement {
        val body = readJson(ex)
        if (body["name"]?.jsonPrimitive?.content == null) return errorObject("missing 'name'")
        val id = body["id"]?.jsonPrimitive?.content ?: java.util.UUID.randomUUID().toString()
        val scenario =
            try {
                ScenarioCodec.fromJson(JsonObject(body + ("id" to JsonPrimitive(id))))
            } catch (e: IllegalArgumentException) {
                return errorObject("invalid scenario: ${e.message}")
            }
        val existed = viewModel.scenarioService.load(id) != null
        val ok = viewModel.scenarioService.save(scenario)
        return buildJsonObject {
            put("status", if (!ok) "failed" else if (existed) "updated" else "created")
            put("id", id)
            put("name", scenario.name)
        }
    }

    /**
     * Records the current session message flow into a replayable scenario (capture-driven authoring),
     * across one or more sessions. Saves it and returns the generated scenario for inspection/editing.
     */
    private fun captureScenario(ex: HttpExchange): JsonElement {
        val body = readJson(ex)
        val name = body["name"]?.jsonPrimitive?.content ?: return errorObject("missing 'name'")
        val profile = body["profile"]?.jsonPrimitive?.content
        val keys = body["sessions"]?.jsonArray?.map { it.jsonPrimitive.content }
        val chosen =
            if (keys.isNullOrEmpty()) onEdt { viewModel.sessions.toList() } else keys.mapNotNull { resolveSession(it) }
        if (chosen.isEmpty()) return errorObject("no sessions to capture")
        val captured = chosen.map { sess ->
            ScenarioCapture.CapturedSession(sess.title, onEdt { sess.messages.value.filterIsInstance<FixMessage>() })
        }
        // scan(), not capture(): a capture is a CLAIM ABOUT COVERAGE, and a message left out has to be
        // reported. The UI honours that (ScenarioCaptureReview's UnreadableNotice); this surface silently
        // discarded them, so an agent writing a CI job was handed a scenario that omits a reply, believes it
        // covers the whole flow, and stays green while the venue regresses on the message that was dropped.
        val scan = ScenarioCapture.scan(captured)
        val scenario =
            ScenarioCapture.captureFrom(
                id = newScenarioId(),
                name = name,
                profile = profile,
                selection = scan.candidates,
                dictionary = onEdt { viewModel.dictionary },
            )
        val ok = viewModel.scenarioService.save(scenario)
        return buildJsonObject {
            put("status", if (ok) "created" else "failed")
            put("id", scenario.id)
            put("name", scenario.name)
            put("steps", scenario.steps.size)
            if (scan.unreadable.isNotEmpty()) {
                put(
                    "omitted",
                    buildJsonArray {
                        scan.unreadable.forEach { m ->
                            add(
                                buildJsonObject {
                                    put("messageType", m.messageType)
                                    put("direction", m.direction.name)
                                    put(
                                        "reason",
                                        "FixTool has no wire bytes for this message, so its field order is " +
                                            "unknown and an assertion seeded from it would check an order the " +
                                            "venue never sent. THIS SCENARIO DOES NOT COVER IT.",
                                    )
                                },
                            )
                        }
                    },
                )
            }
            put("scenario", ScenarioCodec.toJson(scenario))
        }
    }

    /**
     * **Capture from pasted wire, without a hand.** The paste box is click-only — the control surface cannot
     * type into it — so W2's gate (paste a log fragment → save → run → reconcile) could not be driven by the
     * machine. This is the same door the UI's paste review takes: `ScenarioCapture.fromPaste`, then
     * `captureFrom`, so a route it refuses is a route the review refuses in the same words.
     *
     * It honours both of S9's rules loudly. A line whose reading the bytes disprove is **reported** in
     * `refused`, not dropped. A row whose direction nothing settled is **reported** in `undirected` and the
     * capture is not saved — because a reply mis-marked as a Send is a step that asserts nothing, and an agent
     * writing a CI job must be told, not handed a green that checks less than it says.
     */
    private fun capturePaste(ex: HttpExchange): JsonElement {
        val body = readJson(ex)
        val name = body["name"]?.jsonPrimitive?.content ?: return errorObject("missing 'name'")
        val text = body["wire"]?.jsonPrimitive?.content ?: return errorObject("missing 'wire' (the pasted bytes)")
        val sessionKey = body["session"]?.jsonPrimitive?.content
        val session = sessionKey?.let { resolveSession(it) }
        val scan =
            ScenarioCapture.fromPaste(
                text = text,
                session = sessionKey ?: session?.title.orEmpty(),
                senderCompId = pastedCompId(body, "senderCompId") { onEdt { session?.currentConfig?.senderCompID } },
                targetCompId = pastedCompId(body, "targetCompId") { onEdt { session?.currentConfig?.targetCompID } },
            )
        val undirected = ScenarioCapture.undirected(scan.candidates)
        if (scan.candidates.isEmpty() || undirected.isNotEmpty()) {
            return buildJsonObject {
                put("status", "refused")
                put(
                    "reason",
                    if (scan.candidates.isEmpty()) {
                        "no message could be read from the paste"
                    } else {
                        "${undirected.size} message(s) have no direction — pass senderCompId/targetCompId, or a " +
                            "session whose CompIDs match, so a reply is not saved as a Send that asserts nothing"
                    },
                )
                put("refused", buildJsonArray { scan.refused.forEach { add(it) } })
                put("undirected", buildJsonArray { undirected.forEach { add(it.messageType) } })
            }
        }
        val scenario =
            ScenarioCapture.captureFrom(
                id = newScenarioId(),
                name = name,
                profile = body["profile"]?.jsonPrimitive?.content,
                selection = scan.candidates,
                dictionary = onEdt { viewModel.dictionary },
            )
        val ok = viewModel.scenarioService.save(scenario)
        return buildJsonObject {
            put("status", if (ok) "created" else "failed")
            put("id", scenario.id)
            put("name", scenario.name)
            put("steps", scenario.steps.size)
            put("pasted", scenario.steps.all { it.origin == StepOrigin.PASTED })
            put("refused", buildJsonArray { scan.refused.forEach { add(it) } })
            put("scenario", ScenarioCodec.toJson(scenario))
        }
    }

    /** A CompID from the request, or the assigned session's — whichever is present. */
    private fun pastedCompId(body: JsonObject, key: String, fromSession: () -> String?): String? =
        body[key]?.jsonPrimitive?.content ?: fromSession()

    private fun newScenarioId(): String {
        val uuid = java.util.UUID.randomUUID()
        return uuid.toString()
    }

    /** Deletes a scenario by id. */
    private fun deleteScenario(ex: HttpExchange): JsonElement {
        val id = readJson(ex)["id"]?.jsonPrimitive?.content ?: return errorObject("missing 'id'")
        val ok = viewModel.scenarioService.delete(id)
        return buildJsonObject {
            put("status", if (ok) "deleted" else "not found")
            put("id", id)
        }
    }

    /**
     * Runs a scenario deterministically (by `id` from the store, or an inline `scenario`/body) and
     * returns a per-step, per-tag [ScenarioResult]. With `format:"junit"` returns JUnit XML for CI.
     */
    /**
     * **Open the diff on a failing step — the one thing the control surface could not do.**
     *
     * Every repair in the reconcile surface is a click, and this server cannot click. That has been the
     * standing hole in the verification story: `/screenshot` photographs the main window, but nothing could
     * *open* the document worth photographing, so the one surface in the app that authors an assertion was
     * the one surface no automated run had ever seen against a real venue's bytes.
     *
     * It is not a new door into the diff. It calls `openReconcile` — the same function the rail's
     * **Reconcile →** button calls, on the same `StepResult`, through the same `reconcileRoute` decider — so
     * a route this refuses is a route the button refuses, in the same words. Three doors, one destination
     * (T3); this is the fourth, and it goes to the same place.
     *
     * `{}` takes the last run's **first failing step**, which is what the rail's headline button does.
     * `{"step": 2}` addresses one by its 1-based position among the scenario's steps.
     */
    private fun reconcile(ex: HttpExchange): JsonElement {
        val body = readJson(ex)
        val at = body["step"]?.jsonPrimitive?.intOrNull
        val result =
            viewModel.scenarioResult.value
                ?: return errorObject("no scenario has been run — there is no failure to reconcile")

        val step =
            if (at == null) {
                result.firstFailure()
                    ?: return errorObject("the last run passed: ${result.scenario} has nothing to reconcile")
            } else {
                result.steps.firstOrNull { it.phase == "steps" && it.stepIndex == at - 1 }
                    ?: return errorObject("no step $at in the last run of ${result.scenario}")
            }

        // The route is the decider, and it is allowed to say no — "this step was edited since it ran" is a
        // refusal the author must see rather than a document that lies about which bytes it is looking at.
        return when (val route = viewModel.reconcileRoute(step)) {
            is FixMessageViewModel.ReconcileRoute.Refused -> errorObject(route.why)
            is FixMessageViewModel.ReconcileRoute.Open -> {
                onEdt { viewModel.openReconcile(step) }
                buildJsonObject {
                    put("status", "open")
                    put("scenario", result.scenario)
                    put("step", step.stepIndex + 1)
                    put("stepId", step.stepId)
                }
            }
        }
    }

    /**
     * **Open the plain diff viewer on two messages** — the fourth deliberate control-surface door (Phase 7, G8),
     * after `panel`, `reconcile` and `capture-paste`. The viewer is click-only, and the gate is a screenshot, so
     * the machine needs a way to *open* the window it will photograph by title.
     *
     * Each side is either a **pick** (`{session, match}` — a live message, by its wire bytes) or a **paste**
     * (`{paste}` — bytes read through [WirePaste], the same reader the slot uses, so a `|`-in-a-value paste is
     * refused here exactly as it is at the click). A side FixTool has no bytes for is refused, in words.
     */
    private fun diffMessages(ex: HttpExchange): JsonElement {
        val body = readJson(ex)
        val left = resolveDiffSide(body["a"] as? JsonObject)
        val right = resolveDiffSide(body["b"] as? JsonObject)
        if (left == null || right == null) {
            val which = if (left == null) "'a'" else "'b'"
            return errorObject(
                "each side must be {session, match} for a live message with wire bytes, or {paste} for " +
                    "readable bytes — the $which side could not be read",
            )
        }
        onEdt { viewModel.openDiffViewer(left, right) }
        return buildJsonObject {
            put("status", "open")
            put("subject", "${left.messageType ?: "?"} vs ${right.messageType ?: "?"}")
        }
    }

    private fun resolveDiffSide(spec: JsonObject?): DiffSide? {
        if (spec == null) return null
        spec["paste"]?.jsonPrimitive?.content?.let { raw ->
            val paste = WirePaste.read(raw)
            val wire = paste.wire?.takeIf { paste.usable } ?: return null
            return DiffSide(wire, "pasted", ReferenceMessage.Provenance.PASTED)
        }
        val session = resolveSession(spec["session"]?.jsonPrimitive?.content) ?: return null
        val match = spec["match"] as? JsonObject ?: JsonObject(emptyMap())
        val live = onEdt { session.messages.value.filterIsInstance<FixMessage>() }
        val message = live.lastOrNull { matchesMessage(it, match) } ?: return null
        val wire = message.wireRaw ?: return null
        val label = "${session.title} · ${message.messageType}"
        return DiffSide(wire, label, ReferenceMessage.Provenance.PICKED)
    }

    private fun runScenario(ex: HttpExchange): JsonElement {
        val body = readJson(ex)
        val id = body["id"]?.jsonPrimitive?.content
        val scenario: Scenario =
            if (id != null) {
                viewModel.scenarioService.load(id) ?: return errorObject("scenario not found: $id")
            } else {
                val obj = body["scenario"]?.jsonObject ?: body
                val withId =
                    if (obj["id"] != null) {
                        obj
                    } else {
                        JsonObject(obj + ("id" to JsonPrimitive(java.util.UUID.randomUUID().toString())))
                    }
                try {
                    ScenarioCodec.fromJson(withId)
                } catch (e: IllegalArgumentException) {
                    return errorObject("invalid scenario: ${e.message}")
                }
            }
        // The same run the Run button performs — one run slot, one choreography, one verdict published the
        // same way. This endpoint used to keep its own copy of that sequence and had quietly dropped the
        // last step of it, so an agent-driven run left the rail's run report (and the only route to the
        // diff window) blank. Null = the slot is taken; a UI run and a control run would otherwise
        // consume each other's messages.
        val result =
            viewModel.runScenarioBlocking(scenario)
                ?: return errorObject("a scenario run is already in progress")
        return if (body["format"]?.jsonPrimitive?.content?.lowercase() == "junit") {
            buildJsonObject {
                put("passed", result.passed)
                put("junit", ScenarioReport.toJUnitXml(result))
            }
        } else {
            ScenarioReport.toJson(result)
        }
    }

    /**
     * Drives the message detail panel's tag search: sets the search `query` and/or the
     * `mode` (bare|identity|full match-context). With `show=true` the detail panel is revealed.
     * This lets an agent inspect a nested tag (e.g. PartyRole) with its surrounding party context
     * instead of bare matched rows — the [search] endpoint above is the separate global timeline.
     */
    private fun detailSearch(ex: HttpExchange): JsonElement {
        val body = readJson(ex)
        val query = body["query"]?.jsonPrimitive?.content
        val modeRaw = body["mode"]?.jsonPrimitive?.content
        val mode =
            if (modeRaw == null) {
                null
            } else {
                MatchContextMode.fromString(modeRaw)
                    ?: return errorObject("unknown mode '$modeRaw' (bare|identity|full)")
            }
        if (query == null && mode == null) return errorObject("provide 'query' and/or 'mode'")
        val show = body["show"]?.jsonPrimitive?.booleanOrNull

        return onEdt {
            viewModel.setDetailSearch(query, mode)
            if (show == true && !viewModel.showDetailPanel.value) viewModel.toggleDetailPanel()
            buildJsonObject {
                put("status", "ok")
                put("query", viewModel.detailSearchQuery.value)
                put("mode", viewModel.detailMatchContextMode.value.name.lowercase())
                put("detailPanelShown", viewModel.showDetailPanel.value)
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
     * With `resolve: true` the `${...}` template expressions in `raw` are resolved against the
     * session first — the same path the editor's Send button uses. Without it `raw` goes on the wire
     * verbatim, so an unresolved `${uuid}` is sent *as that literal text*. See [SyntaxReference].
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

    /**
     * Session/admin control for FIX testing. `action` is one of reset-seqnum, test-request,
     * resend-request, sequence-reset, logout, disconnect, or seqnum (read current numbers). These
     * call the QuickFIX Session API directly (thread-safe) — no Compose state is touched.
     */
    private fun admin(ex: HttpExchange): JsonElement {
        val body = readJson(ex)
        val action = body["action"]?.jsonPrimitive?.content?.lowercase() ?: return errorObject("missing 'action'")
        val session = resolveSession(body["session"]?.jsonPrimitive?.content) ?: return errorObject("session not found")

        if (action == "seqnum") {
            val seq = session.sequenceNumbers() ?: return errorObject("no active session sequence numbers")
            return buildJsonObject {
                put("status", "ok")
                put("nextSenderSeqNum", seq.first)
                put("nextTargetSeqNum", seq.second)
            }
        }

        val ok =
            when (action) {
                "reset-seqnum" ->
                    session.resetSequenceNumbers(
                        body["sender"]?.jsonPrimitive?.intOrNull,
                        body["target"]?.jsonPrimitive?.intOrNull,
                    )
                "test-request" -> session.sendTestRequest(body["id"]?.jsonPrimitive?.content ?: "TR")
                "resend-request" ->
                    session.sendResendRequest(
                        body["begin"]?.jsonPrimitive?.intOrNull ?: 1,
                        body["end"]?.jsonPrimitive?.intOrNull ?: 0,
                    )
                "sequence-reset" ->
                    session.sendSequenceReset(
                        body["newSeq"]?.jsonPrimitive?.intOrNull ?: return errorObject("missing 'newSeq'"),
                        body["gapFill"]?.jsonPrimitive?.booleanOrNull ?: false,
                    )
                "logout" -> session.forceLogout(body["reason"]?.jsonPrimitive?.content)
                "disconnect" -> session.forceDisconnect(body["reason"]?.jsonPrimitive?.content ?: "control")
                else -> return errorObject(
                    "unknown action '$action' " +
                        "(reset-seqnum|test-request|resend-request|sequence-reset|logout|disconnect|seqnum)",
                )
            }
        return buildJsonObject {
            put("status", if (ok) "ok" else "failed")
            put("action", action)
        }
    }

    /**
     * Inspects the acceptor auto-response rules on a profile. Rules are *set* via the normal
     * `/profiles` upsert (they live on the profile's config), so this is read-only.
     */
    private fun acceptorRules(ex: HttpExchange): JsonElement {
        val profileKey = queryParams(ex)["profile"] ?: return errorObject("missing 'profile'")
        return onEdt {
            val profile = viewModel.connectionProfiles.firstOrNull { it.id == profileKey || it.name == profileKey }
                ?: return@onEdt errorObject("unknown profile: $profileKey")
            buildJsonObject {
                put("profile", profile.name)
                put("connectionType", profile.config.connectionType.name)
                put(
                    "rules",
                    buildJsonArray {
                        profile.config.acceptorResponseRules.forEach { rule ->
                            add(
                                buildJsonObject {
                                    put("whenMsgType", rule.whenMsgType)
                                    put(
                                        "whenFields",
                                        buildJsonObject { rule.whenFields.forEach { (k, v) -> put(k, v) } },
                                    )
                                    put("responseTemplate", rule.responseTemplate)
                                },
                            )
                        }
                    },
                )
            }
        }
    }

    /** Validates a raw FIX message against the loaded data dictionary. */
    private fun validate(ex: HttpExchange): JsonElement {
        val raw = readJson(ex)["raw"]?.jsonPrimitive?.content ?: return errorObject("missing 'raw'")
        val result = onEdt { FixMessageValidator.validate(raw, viewModel.dictionary) }
        return buildJsonObject {
            put("isValid", result.isValid)
            put("errors", buildJsonArray { result.errors.forEach { add(it) } })
        }
    }

    private fun dictionaryEndpoint(ex: HttpExchange): JsonElement =
        if (ex.requestMethod.uppercase() == "POST") setDictionary(ex) else getDictionary()

    private fun getDictionary(): JsonElement =
        onEdt {
            buildJsonObject {
                put("version", viewModel.currentFixVersion.name)
                put("displayName", viewModel.currentFixVersion.displayName)
                put("valid", viewModel.isDictionaryValid.value)
                viewModel.dictionaryErrorMessage.value?.let { put("error", it) }
            }
        }

    private fun setDictionary(ex: HttpExchange): JsonElement {
        val body = readJson(ex)
        val versionKey = body["version"]?.jsonPrimitive?.content
        val path = body["path"]?.jsonPrimitive?.content
        return onEdt {
            when {
                versionKey != null -> {
                    val version =
                        FixVersion.entries.firstOrNull {
                            it.name == versionKey || it.beginString == versionKey || it.displayName == versionKey
                        } ?: return@onEdt errorObject("unknown FIX version '$versionKey'")
                    viewModel.switchDictionaryToVersion(version)
                }
                path != null -> viewModel.switchDictionaryToFile(path, body["transportPath"]?.jsonPrimitive?.content)
                else -> return@onEdt errorObject("provide 'version' or 'path'")
            }
            buildJsonObject {
                put("status", if (viewModel.isDictionaryValid.value) "ok" else "error")
                put("version", viewModel.currentFixVersion.name)
                put("valid", viewModel.isDictionaryValid.value)
                viewModel.dictionaryErrorMessage.value?.let { put("error", it) }
            }
        }
    }

    /**
     * Serves the template-expression / matcher reference as markdown. Raw text rather than JSON:
     * it is prose meant to be read, and JSON-escaping every newline only makes it harder to.
     */
    private fun syntax(ex: HttpExchange) {
        try {
            if (!authorized(ex)) {
                respondText(ex, HTTP_UNAUTHORIZED, "unauthorized")
                return
            }
            val bytes = SyntaxReference.markdown.toByteArray()
            ex.responseHeaders.add("Content-Type", "text/markdown; charset=utf-8")
            ex.sendResponseHeaders(HTTP_OK, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        } catch (e: Exception) {
            logger.error("Syntax reference failed", e)
            respondText(ex, HTTP_SERVER_ERROR, "syntax reference failed: ${e.message}")
        } finally {
            ex.close()
        }
    }

    private fun screenshot(ex: HttpExchange) {
        try {
            if (!authorized(ex)) {
                respondText(ex, HTTP_UNAUTHORIZED, "unauthorized")
                return
            }
            val bytes = captureWindowPng(queryParams(ex)["window"]) ?: run {
                respondText(ex, HTTP_NOT_FOUND, "no window")
                return
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

    /**
     * **Which window `?window=` names.** `main` (or absent) is the main window, by title — never `firstOrNull()`,
     * which is undefined-order once a second window exists. `diff` is the first showing window that is not the
     * main one; any other value matches a window whose title contains it (so a specific step can be targeted).
     * Only `Frame`s (the app's real windows) are considered, which also skips heavyweight popups and tooltips.
     */
    private fun selectWindow(selector: String?): Window? {
        val frames = windowProvider().filter { it.isShowing }.filterIsInstance<java.awt.Frame>()
        return pickWindow(frames.map { it.title.orEmpty() to it }, selector)?.second
    }

    /** Captures a window as a PNG, or null if the selector names none. Shared by HTTP and MCP. */
    private fun captureWindowPng(selector: String?): ByteArray? {
        val window = selectWindow(selector) ?: return null
        val bounds = onEdt { window.bounds }
        val image = Robot().createScreenCapture(bounds)
        return ByteArrayOutputStream().use { out ->
            ImageIO.write(image, "png", out)
            out.toByteArray()
        }
    }

    // ------------------------------------------------------------ embedded MCP server

    /** Maps each MCP tool to the control logic that runs it (screenshot is handled separately). */
    private val mcpDispatch: Map<String, (JsonObject) -> JsonElement> =
        mapOf(
            "fixtool_health" to { _ -> health() },
            "fixtool_sessions" to { _ -> sessions() },
            "fixtool_profiles" to { _ -> profiles() },
            "fixtool_save_profile" to { a -> upsertProfile(mcpExchange(a)) },
            "fixtool_delete_profile" to { a -> deleteProfile(mcpExchange(a)) },
            "fixtool_panel" to { a -> panel(mcpExchange(a)) },
            "fixtool_list_templates" to { a -> listTemplates(mcpExchange(a)) },
            "fixtool_save_template" to { a -> upsertTemplate(mcpExchange(a)) },
            "fixtool_delete_template" to { a -> deleteTemplate(mcpExchange(a)) },
            "fixtool_load_template" to { a -> loadTemplate(mcpExchange(a)) },
            "fixtool_demo" to { a -> demo(mcpExchange(a)) },
            "fixtool_connect" to { a -> connect(mcpExchange(a)) },
            "fixtool_disconnect" to { a -> disconnect(mcpExchange(a)) },
            "fixtool_send" to { a -> send(mcpExchange(a)) },
            "fixtool_send_all" to { a -> sendAll(mcpExchange(a)) },
            "fixtool_send_template" to { a -> sendTemplate(mcpExchange(a)) },
            "fixtool_clear_messages" to { a -> clearMessages(mcpExchange(a)) },
            "fixtool_wait" to { a -> waitFor(mcpExchange(a)) },
            "fixtool_get_messages" to { a -> messages(mcpExchange(a)) },
            "fixtool_search" to { a -> search(mcpExchange(a)) },
            "fixtool_filter" to { a -> filter(mcpExchange(a)) },
            "fixtool_select" to { a -> select(mcpExchange(a)) },
            "fixtool_assert" to { a -> assertMessage(mcpExchange(a)) },
            "fixtool_capture_expectation" to { a -> captureExpectation(mcpExchange(a)) },
            "fixtool_save_scenario" to { a -> saveScenario(mcpExchange(a)) },
            "fixtool_capture_scenario" to { a -> captureScenario(mcpExchange(a)) },
            "fixtool_list_scenarios" to { a -> listScenarios(mcpExchange(a)) },
            "fixtool_get_scenario" to { a -> getScenario(mcpExchange(a)) },
            "fixtool_run_scenario" to { a -> runScenario(mcpExchange(a)) },
            "fixtool_delete_scenario" to { a -> deleteScenario(mcpExchange(a)) },
            "fixtool_detail_search" to { a -> detailSearch(mcpExchange(a)) },
            "fixtool_admin" to { a -> admin(mcpExchange(a)) },
            "fixtool_validate" to { a -> validate(mcpExchange(a)) },
            "fixtool_dictionary" to { a -> if (a.isEmpty()) getDictionary() else setDictionary(mcpExchange(a)) },
            "fixtool_acceptor_rules" to { a -> acceptorRules(mcpExchange(a)) },
        )

    /**
     * MCP Streamable HTTP endpoint (JSON-RPC 2.0). Lets Claude Code connect directly with
     * `claude mcp add --transport http fixtool http://127.0.0.1:<port>/mcp` — no extra process.
     */
    private fun mcpHandle(ex: HttpExchange) {
        try {
            if (!authorized(ex)) {
                respondJson(ex, HTTP_UNAUTHORIZED, errorObject("unauthorized"))
                return
            }
            if (ex.requestMethod.uppercase() != "POST") {
                respondText(ex, HTTP_METHOD_NOT_ALLOWED, "use POST")
                return
            }
            val request = Json.parseToJsonElement(ex.requestBody.readBytes().decodeToString()).jsonObject
            val id = request["id"]
            if (id == null || id is JsonNull) {
                // A notification (e.g. notifications/initialized) — acknowledge with no body.
                ex.sendResponseHeaders(HTTP_NO_CONTENT, -1)
                return
            }
            val result =
                when (request["method"]?.jsonPrimitive?.content) {
                    "initialize" -> mcpInitialize(request)
                    "tools/list" ->
                        buildJsonObject { put("tools", buildJsonArray { McpTools.tools.forEach { add(it) } }) }
                    "tools/call" -> mcpToolsCall(request)
                    "ping" -> buildJsonObject {}
                    else -> null
                }
            respondJson(ex, HTTP_OK, mcpEnvelope(id, result, request["method"]?.jsonPrimitive?.content))
        } catch (e: Exception) {
            logger.error("MCP request failed", e)
            respondJson(ex, HTTP_OK, mcpError(JsonNull, MCP_INTERNAL_ERROR, e.message ?: "internal error"))
        } finally {
            ex.close()
        }
    }

    private fun mcpEnvelope(id: JsonElement, result: JsonObject?, method: String?): JsonObject =
        if (result != null) {
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("result", result)
            }
        } else {
            mcpError(id, MCP_METHOD_NOT_FOUND, "Method not found: $method")
        }

    private fun mcpError(id: JsonElement, code: Int, message: String): JsonObject =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put(
                "error",
                buildJsonObject {
                    put("code", code)
                    put("message", message)
                },
            )
        }

    private fun mcpInitialize(request: JsonObject): JsonObject {
        val clientVersion = request["params"]?.jsonObject?.get("protocolVersion")?.jsonPrimitive?.content
        return buildJsonObject {
            put("protocolVersion", clientVersion ?: MCP_PROTOCOL_VERSION)
            put("capabilities", buildJsonObject { put("tools", buildJsonObject {}) })
            put(
                "serverInfo",
                buildJsonObject {
                    put("name", "fixtool")
                    put("version", "1")
                },
            )
        }
    }

    private fun mcpToolsCall(request: JsonObject): JsonObject {
        val params = request["params"]?.jsonObject ?: return mcpToolResult("missing params", isError = true)
        val name = params["name"]?.jsonPrimitive?.content ?: return mcpToolResult("missing tool name", isError = true)
        val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        if (name == "fixtool_screenshot") return mcpScreenshotResult(args["window"]?.jsonPrimitive?.content)
        // Markdown prose, not a JSON body — hand it back verbatim rather than escaped inside one.
        if (name == "fixtool_syntax") return mcpToolResult(SyntaxReference.markdown)
        val handler = mcpDispatch[name] ?: return mcpToolResult("unknown tool: $name", isError = true)
        return mcpToolResult(handler(args).toString())
    }

    private fun mcpToolResult(text: String, isError: Boolean = false): JsonObject =
        buildJsonObject {
            put(
                "content",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", text)
                        },
                    )
                },
            )
            if (isError) put("isError", true)
        }

    private fun mcpScreenshotResult(selector: String? = null): JsonObject {
        val png = captureWindowPng(selector) ?: return mcpToolResult("no window / screenshot unavailable", isError = true)
        return buildJsonObject {
            put(
                "content",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "image")
                            put("data", Base64.getEncoder().encodeToString(png))
                            put("mimeType", "image/png")
                        },
                    )
                },
            )
        }
    }

    /**
     * Adapts an MCP tool-call's arguments into the [HttpExchange] the existing endpoint handlers
     * expect — primitives go to both the query string and the JSON body; objects/arrays go to the
     * body only — so every handler reads its inputs unchanged (no per-handler refactor).
     */
    private fun mcpExchange(args: JsonObject): HttpExchange {
        val query =
            args.entries
                .mapNotNull { (k, v) -> (v as? JsonPrimitive)?.let { k to it.content } }
                .joinToString("&") { (k, v) -> "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}" }
        val uri = URI("/mcp" + if (query.isEmpty()) "" else "?$query")
        return McpRequestExchange(uri, args.toString().encodeToByteArray())
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
        /** The main window's title, so `?window=main` finds it by name rather than by list order. */
        const val MAIN_WINDOW_TITLE = "FixTool - FiX Message Viewer"

        /**
         * **The pure selection policy, AWT-free so it can be pinned.** `main` (or absent) is the main window by
         * title — never list order; `diff` is the first window that is not the main one; anything else matches a
         * title substring. Returns `(title, window)` or null.
         */
        internal fun <T> pickWindow(candidates: List<Pair<String, T>>, selector: String?): Pair<String, T>? =
            when {
                selector.isNullOrBlank() || selector == "main" ->
                    candidates.firstOrNull { it.first == MAIN_WINDOW_TITLE } ?: candidates.firstOrNull()
                selector == "diff" -> candidates.firstOrNull { it.first != MAIN_WINDOW_TITLE && it.first.isNotBlank() }
                else -> candidates.firstOrNull { it.first.contains(selector, ignoreCase = true) }
            }

        private const val HTTP_OK = 200
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_SERVER_ERROR = 500
        private const val HTTP_METHOD_NOT_ALLOWED = 405
        private const val HTTP_NO_CONTENT = 204
        private const val DEFAULT_MESSAGE_LIMIT = 50
        private const val HTTP_POOL_SIZE = 4
        private const val DEFAULT_WAIT_MS = 10_000L
        private const val MAX_WAIT_MS = 120_000L
        private const val WAIT_POLL_MS = 100L
        private const val MCP_PROTOCOL_VERSION = "2025-06-18"
        private const val MCP_METHOD_NOT_FOUND = -32601
        private const val MCP_INTERNAL_ERROR = -32603
    }
}

/**
 * Owns the single [ControlServer] instance and starts/stops it to match the desired state.
 * The `FIXTOOL_CONTROL_PORT` env var, when set, always wins (developer override); otherwise the
 * app settings ([apply]'s `enabled`/`port`) drive it. Off by default.
 */
object ControlServerLauncher {
    private val logger = LoggerFactory.getLogger(ControlServerLauncher::class.java)
    private var server: ControlServer? = null
    private var currentPort: Int? = null

    /**
     * Reconciles the running server with the desired state; safe to call repeatedly (a no-op when
     * already running on the right port). Call on app start and whenever the setting changes.
     */
    @Synchronized
    fun apply(viewModel: FixMessageViewModel, windowProvider: () -> List<Window>, enabled: Boolean, port: Int) {
        val envPort = System.getenv("FIXTOOL_CONTROL_PORT")?.toIntOrNull()
        val desiredPort = envPort ?: if (enabled) port else null
        when {
            desiredPort == null -> stop()
            server != null && currentPort == desiredPort -> Unit // already running on the desired port
            else -> {
                stop()
                try {
                    server = ControlServer(desiredPort, viewModel, windowProvider).also { it.start() }
                    currentPort = desiredPort
                } catch (e: Exception) {
                    logger.error("Failed to start control server on port $desiredPort", e)
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
        currentPort = null
    }
}

/**
 * A minimal in-memory [HttpExchange] used only to replay an MCP tool call through the existing
 * HTTP endpoint handlers (which read the request URI, method and body). The response-side methods
 * are never invoked on this path — handlers return a value rather than writing to the exchange.
 */
private class McpRequestExchange(
    private val uri: URI,
    bodyBytes: ByteArray,
) : HttpExchange() {
    private val body = ByteArrayInputStream(bodyBytes)
    private val headers = Headers()

    override fun getRequestHeaders(): Headers = headers

    override fun getResponseHeaders(): Headers = headers

    override fun getRequestURI(): URI = uri

    override fun getRequestMethod(): String = "POST"

    override fun getRequestBody(): InputStream = body

    override fun close() = Unit

    override fun getResponseBody(): OutputStream = throw UnsupportedOperationException()

    override fun sendResponseHeaders(rCode: Int, responseLength: Long): Unit = throw UnsupportedOperationException()

    override fun getResponseCode(): Int = -1

    override fun getRemoteAddress(): InetSocketAddress = throw UnsupportedOperationException()

    override fun getLocalAddress(): InetSocketAddress = throw UnsupportedOperationException()

    override fun getProtocol(): String = "HTTP/1.1"

    override fun getHttpContext(): HttpContext = throw UnsupportedOperationException()

    override fun getAttribute(name: String?): Any? = null

    override fun setAttribute(name: String?, value: Any?) = Unit

    override fun setStreams(i: InputStream?, o: OutputStream?) = Unit

    override fun getPrincipal(): HttpPrincipal? = null
}
