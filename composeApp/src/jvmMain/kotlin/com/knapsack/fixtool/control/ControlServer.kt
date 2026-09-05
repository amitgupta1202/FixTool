// This file is an HTTP request-handling boundary: each endpoint legitimately catches broad
// exceptions to convert any failure into a 500 response, and the per-endpoint handlers
// naturally push the class past detekt's function-count threshold.
@file:Suppress("TooManyFunctions", "TooGenericExceptionCaught", "ReturnCount", "LargeClass", "CyclomaticComplexMethod")

package com.knapsack.fixtool.control

import com.knapsack.fixtool.model.AcceptorLatencyConfig
import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixMessageSession
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.MatchContextMode
import com.knapsack.fixtool.model.SavedFixField
import com.knapsack.fixtool.model.SavedFixMessage
import com.knapsack.fixtool.model.TagRole
import com.knapsack.fixtool.model.TagRoleOverlay
import com.knapsack.fixtool.model.load.LoadMatch
import com.knapsack.fixtool.model.load.LoadPlan
import com.knapsack.fixtool.model.load.LoadReport
import com.knapsack.fixtool.model.load.LoadShape
import com.knapsack.fixtool.model.load.LoadStatus
import com.knapsack.fixtool.model.load.LoadTemplate
import com.knapsack.fixtool.model.load.StoreAndLogOverride
import com.knapsack.fixtool.service.load.LoadReportCodec
import com.knapsack.fixtool.service.load.LoadTemplates
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.RunEntry
import com.knapsack.fixtool.model.scenario.RunPolicy
import com.knapsack.fixtool.model.scenario.RunSet
import com.knapsack.fixtool.model.scenario.RunSetStatus
import com.knapsack.fixtool.model.scenario.RunSource
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.StepOrigin
import com.knapsack.fixtool.model.BookReading
import com.knapsack.fixtool.model.BookSpec
import com.knapsack.fixtool.model.BookedOrder
import com.knapsack.fixtool.model.OrderBook
import com.knapsack.fixtool.model.OrderConstraint
import com.knapsack.fixtool.model.OrderState
import com.knapsack.fixtool.service.ExampleWorkspaces
import com.knapsack.fixtool.service.OrderBookService
import com.knapsack.fixtool.model.EditorTarget
import com.knapsack.fixtool.service.AcceptorPresets
import com.knapsack.fixtool.service.BookView
import com.knapsack.fixtool.service.AcceptorResponder
import com.knapsack.fixtool.service.EchoDetector
import com.knapsack.fixtool.service.ExpectationEvaluator
import com.knapsack.fixtool.service.ExpectationSeeder
import com.knapsack.fixtool.service.FixMessageHelper
import com.knapsack.fixtool.service.FixMessageTemplate
import com.knapsack.fixtool.service.FixMessageValidator
import com.knapsack.fixtool.service.FixMessageView
import com.knapsack.fixtool.service.MatcherCodec
import com.knapsack.fixtool.service.RuleOutcome
import com.knapsack.fixtool.service.RunRecordCodec
import com.knapsack.fixtool.service.RunSets
import com.knapsack.fixtool.service.ScenarioCapture
import com.knapsack.fixtool.service.ScenarioCodec
import com.knapsack.fixtool.service.ScenarioReconcile
import com.knapsack.fixtool.service.ScenarioReport
import com.knapsack.fixtool.service.SendResult
import com.knapsack.fixtool.service.SessionTags
import com.knapsack.fixtool.service.Traces
import com.knapsack.fixtool.service.VenueTagScan
import com.knapsack.fixtool.service.compare.ReferenceMessage
import com.knapsack.fixtool.service.compare.WirePaste
import com.knapsack.fixtool.ui.diff.DiffSide
import com.knapsack.fixtool.ui.diff.EditOp
import com.knapsack.fixtool.ui.diff.ReconcileSession
import com.knapsack.fixtool.ui.firstFailure
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import com.knapsack.fixtool.viewmodel.TraceRendering
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
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
import java.io.File

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
    private val profileJson =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

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
        httpServer.createContext("/scenarios/run") { ex -> handleCoded(ex) { runScenario(ex) } }
        httpServer.createContext("/scenarios/runs") { ex -> handleCoded(ex) { runSets(ex) } }
        httpServer.createContext("/load") { ex -> handleCoded(ex) { startLoad(readJson(ex)) } }
        httpServer.createContext("/loads") { ex -> handleCoded(ex) { loads(ex) } }
        httpServer.createContext("/scenarios/capture") { ex -> handle(ex) { captureScenario(ex) } }
        httpServer.createContext("/scenarios/capture-paste") { ex -> handle(ex) { capturePaste(ex) } }
        httpServer.createContext("/scenarios") { ex -> handle(ex) { scenariosEndpoint(ex) } }
        httpServer.createContext("/detail") { ex -> handle(ex) { detailSearch(ex) } }
        httpServer.createContext("/traces") { ex -> handle(ex) { traces() } }
        httpServer.createContext("/trace") { ex -> handleCoded(ex) { trace(ex) } }
        httpServer.createContext("/search") { ex -> handle(ex) { search(ex) } }
        httpServer.createContext("/filter") { ex -> handle(ex) { filter(ex) } }
        httpServer.createContext("/demo") { ex -> handle(ex) { demo(ex) } }
        httpServer.createContext("/workspace") { ex -> handle(ex) { workspace(ex) } }
        httpServer.createContext("/connect") { ex -> handle(ex) { connect(ex) } }
        httpServer.createContext("/disconnect") { ex -> handle(ex) { disconnect(ex) } }
        httpServer.createContext("/send/all") { ex -> handle(ex) { sendAll(ex) } }
        httpServer.createContext("/send") { ex -> handle(ex) { send(ex) } }
        httpServer.createContext("/templates/send") { ex -> handle(ex) { sendTemplate(ex) } }
        httpServer.createContext("/admin") { ex -> handle(ex) { admin(ex) } }
        httpServer.createContext("/validate") { ex -> handle(ex) { validate(ex) } }
        httpServer.createContext("/dictionary/roles") { ex -> handle(ex) { dictionaryRoles(ex) } }
        httpServer.createContext("/dictionary") { ex -> handle(ex) { dictionaryEndpoint(ex) } }
        httpServer.createContext("/acceptor/test") { ex -> handle(ex) { acceptorTest(ex) } }
        httpServer.createContext("/acceptor/rules") { ex -> handle(ex) { acceptorRulesEndpoint(ex) } }
        httpServer.createContext("/acceptor/presets") { ex -> handle(ex) { acceptorPresets() } }
        httpServer.createContext("/acceptor/orders") { ex -> handle(ex) { acceptorOrdersEndpoint(ex) } }
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
            // In the strip rather than the grid — see [MinimizedStrip]. Only when true, like `discarded`
            // below: every healthy conversation reads false, and a field that always reads false teaches
            // a reader to skip it. Its absence means the pane is in the layout.
            //
            // A venue's own pane starts minimized, so a caller counting panes on screen needs this to get
            // the same answer the window gives. It says nothing about the session: a minimized pane is
            // still connected, still logging, and still a valid send target.
            if (session.minimized.value) put("minimized", true)
            // Only when it is not zero. A field that reads 0 on every healthy session teaches a reader to
            // stop looking at it, which is the opposite of what a loss counter is for — see
            // [FixMessageSession.discarded]. Its absence means nothing was lost.
            val lost = session.discarded.value
            if (lost > 0) put("discarded", lost)
            // Only on an acceptor, and only once it is running — five zeroes on every initiator would
            // teach a reader to skip the section, the same reason `discarded` hides itself at zero.
            session.acceptorStatus()?.let { acceptor ->
                put(
                    "acceptor",
                    buildJsonObject {
                        put("acceptPort", session.currentConfig?.socketAcceptPort ?: "")
                        // Compiled and in force, which is not necessarily how many are saved: a
                        // disabled or unusable rule is compiled away. That difference is the answer
                        // to "I saved it, why does nothing happen".
                        put("rulesLive", acceptor.rulesLive)
                        put("latencyActive", acceptor.latencyActive)
                        put("triggersMatched", acceptor.triggersMatched)
                        put("responsesSent", acceptor.responsesSent)
                        // Still queued behind their delays. `triggersMatched` ahead of `responsesSent`
                        // with this non-zero is a sequence mid-flight — which reads from the message
                        // log exactly like a rule that never matched.
                        put("pendingResponses", acceptor.pendingResponses)
                        // Only for a venue, where they mean something. On an acceptor naming one
                        // counterparty "0 clients refused" is not news, it is furniture.
                        if (session.isVenue) {
                            put("clientsConnected", acceptor.clientsConnected)
                            put("logonsRefused", acceptor.logonsRefused)
                        }
                    },
                )
            }
            // Which counterparty this pane speaks to, when it is one client of a venue. Its CompIDs
            // below are the same pair, but this says *that* it is a venue client — which is what
            // decides whether disconnecting it stops a port or ends one conversation.
            session.clientSessionId?.let { put("venueClientOf", it.senderCompID) }
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

    /**
     * One profile's **whole** config, not the summary the list gives.
     *
     * The list carries the eight fields that identify a profile, which is the right size for choosing
     * one and the wrong size for changing one: an editor that cannot read `useSSL`, `logonFields`,
     * `acceptorLatency` or the existing rules cannot preserve them. That was survivable only while a
     * `/profiles` POST replaced the config wholesale — a caller had to send everything anyway, and
     * silently lost whatever it could not see. Now that the POST merges, this is the other half: read
     * what is there, send back only what changes.
     *
     * Secrets are reported as [REDACTED] rather than echoed. A merging POST never needs them sent
     * back, so the round trip does not depend on it — and a password read into an agent's transcript
     * is in the transcript for good.
     */
    private fun profileDetail(profileKey: String): JsonElement {
        val profile =
            onEdt {
                viewModel.connectionProfiles.firstOrNull { it.id == profileKey || it.name == profileKey }
            } ?: return errorObject("unknown profile: $profileKey")
        val config = profileJson.encodeToJsonElement(FixConnectionConfig.serializer(), profile.config).jsonObject
        return buildJsonObject {
            put("id", profile.id)
            put("name", profile.name)
            put(
                "config",
                JsonObject(
                    config.mapValues { (key, value) ->
                        if (key in SECRET_CONFIG_KEYS && value.jsonPrimitive.contentOrNull?.isNotEmpty() == true) {
                            JsonPrimitive(REDACTED)
                        } else {
                            value
                        }
                    },
                ),
            )
            put(
                "redacted",
                buildJsonArray {
                    SECRET_CONFIG_KEYS
                        .filter { config[it]?.jsonPrimitive?.contentOrNull?.isNotEmpty() == true }
                        .forEach { add(it) }
                },
            )
        }
    }

    /** `/profiles` is method-aware: GET lists (or details one via `?profile=`), POST upserts, DELETE removes. */
    private fun profilesEndpoint(ex: HttpExchange): JsonElement =
        when (ex.requestMethod.uppercase()) {
            "POST" -> upsertProfile(ex)
            "DELETE" -> deleteProfile(ex)
            else -> queryParams(ex)["profile"]?.let { profileDetail(it) } ?: profiles()
        }

    /**
     * Creates a connection profile (or updates one when `id` is given) from a JSON body of the
     * form `{ "name": "...", "config": { ... } }`. `config` only needs the fields that differ
     * from the model defaults (host, port, senderCompID, targetCompID, connectionType, useSSL, …).
     * The saved profile is immediately connectable via `/connect`.
     *
     * ### Updating merges; it does not replace
     *
     * A `config` sent against an existing `id` sets **the keys it carries and no others**. Anything
     * absent keeps the value it had.
     *
     * It replaced the whole config until this, which made the endpoint quietly destructive in the one
     * situation it is most used for. Nothing here can read a profile's SSL settings, logon fields,
     * latency or existing rules — `/profiles` listed eight fields — so "add a rule to my acceptor"
     * was necessarily a POST carrying a rule and nothing else, and it silently took the keystore path,
     * the logon fields and every other rule down with it, then answered `{"status":"updated"}`. The
     * caller could not have avoided it and could not see that it happened.
     *
     * Merging is per top-level key, so an *explicitly* sent value always wins and clearing is still
     * expressible: `"acceptorResponseRules": []` is present, therefore applied. `replace: true` asks
     * for the old wholesale behaviour, for a caller that wants a profile to be exactly what it sends.
     */
    private fun upsertProfile(ex: HttpExchange): JsonElement {
        val body = readJson(ex)
        val name = body["name"]?.jsonPrimitive?.content ?: return errorObject("missing 'name'")
        val configElement = body["config"] as? JsonObject ?: return errorObject("missing 'config' object")
        val replace = body["replace"]?.jsonPrimitive?.booleanOrNull ?: false
        val existingId = body["id"]?.jsonPrimitive?.content

        return onEdt {
            val existing = existingId?.let { id -> viewModel.connectionProfiles.firstOrNull { it.id == id } }
            // A redaction marker read back from `GET /profiles?profile=` and posted again means "leave
            // this alone", never "set the password to the literal string [REDACTED]" — which is what a
            // round trip would otherwise do, locking the user out of their own keystore.
            val incoming =
                JsonObject(
                    configElement.filterNot { (key, value) ->
                        key in SECRET_CONFIG_KEYS && value.jsonPrimitive.contentOrNull == REDACTED
                    },
                )
            val merged =
                if (replace || existing == null) {
                    incoming
                } else {
                    val current =
                        profileJson.encodeToJsonElement(FixConnectionConfig.serializer(), existing.config).jsonObject
                    JsonObject(current + incoming)
                }
            val config =
                try {
                    profileJson.decodeFromJsonElement(FixConnectionConfig.serializer(), merged)
                } catch (e: Exception) {
                    return@onEdt errorObject("invalid config: ${e.message}")
                }
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
                // Said out loud because the two modes differ precisely in what they do to keys the
                // caller did not mention, which is the part a caller cannot see in its own request.
                put("mode", if (replace || existing == null) "replace" else "merge")
                put("applied", buildJsonArray { incoming.keys.sorted().forEach { add(it) } })
                liveAcceptorSessions(profile)?.let { put("appliedToLiveSessions", it) }
                // The store refusal is a warning here and a refusal at connect: a caller may save a
                // half-edited profile and fix Reset on Logon next, but it must be told in the same words.
                val problems = acceptorProblems(config) + listOfNotNull(config.storeProblem())
                if (problems.isNotEmpty()) {
                    put("warnings", buildJsonArray { problems.forEach { add(it) } })
                }
            }
        }
    }

    /**
     * How many of [profile]'s connected sessions a rule edit just reached, or null when the question
     * does not arise (not an acceptor, or nothing connected).
     *
     * Reported because a save that changes the file and a save that changes the wire used to look
     * identical from here, and for a live acceptor they were different things. Saving now applies to
     * live sessions — see `FixMessageViewModel.pushAcceptorRulesToLiveSessions` — and this is how the
     * caller sees that it happened rather than having to trust it.
     */
    private fun liveAcceptorSessions(profile: FixConnectionProfile): Int? {
        if (profile.config.connectionType != FixConnectionConfig.ConnectionType.ACCEPTOR) return null
        return viewModel.liveAcceptorSessionCount(profile).takeIf { it > 0 }
    }

    /**
     * Everything wrong with [config]'s acceptor settings, in the author's words.
     *
     * Reported as warnings on a write rather than refusing it: a half-written rule is a normal state
     * to save through, and [AcceptorResponseRule.validationError] is deliberately not a load-time
     * failure. But a rule that can never fire is also indistinguishable, from outside, from one whose
     * trigger has not come up yet — so the one moment it can be said usefully is when it is written.
     */
    private fun acceptorProblems(config: FixConnectionConfig): List<String> =
        config.acceptorResponseRules.mapIndexedNotNull { index, rule ->
            rule.validationError()?.let { "rule $index (${rule.whenMsgType.ifBlank { "no MsgType" }}): $it" }
        } + listOfNotNull(config.acceptorLatency.validationError()?.let { "acceptorLatency: $it" })

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
        // Grouping is per SESSION, not a window panel: `session` targets one pane, omitted means all —
        // the same bulk semantics as the toolbar button.
        if (name == "conversations") {
            val sessionKey = body["session"]?.jsonPrimitive?.content
            val applied =
                onEdt {
                    if (sessionKey == null) {
                        viewModel.sessions.forEach { it.setGroupByConversation(show) }
                        viewModel.sessions.isNotEmpty()
                    } else {
                        viewModel.sessions
                            .firstOrNull { it.id == sessionKey || it.title == sessionKey }
                            ?.also { it.setGroupByConversation(show) } != null
                    }
                }
            if (!applied) return errorObject(if (sessionKey == null) "no sessions" else "session not found: $sessionKey")
            return buildJsonObject {
                put("status", "ok")
                put("panel", name)
                put("show", show)
                sessionKey?.let { put("session", it) }
            }
        }
        // The Ledger and the followed trace, from one call. `show` puts the panel on screen; `follow`
        // sets what every pane is narrowed to, and `follow:null` clears it. Both may come together
        // because following IS the gesture that opens the panel, and an agent scripting "follow this,
        // then screenshot" would otherwise need two round-trips to say one thing.
        //
        // A window panel, not a per-session setting: there is one followed trace and one Ledger, which
        // is what makes this the opposite of the `conversations` branch above — see [TraceFollow].
        if (name == "trace") {
            val followField = body["follow"]
            val hasFollow = body.containsKey("follow")
            val followId = if (followField is JsonNull) null else followField?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            if (hasFollow && followField !is JsonNull && followId == null) {
                return errorObject("'follow' must be a whole correlation value, or null to stop following")
            }
            // Which of the panel's two drawings to show. A third key on the same call for the same
            // reason `follow` and `show` share one: "follow this and draw it as lanes" is one thought,
            // and an agent scripting a screenshot should not need three round-trips to say it.
            val renderField = body["render"]?.jsonPrimitive?.content
            val rendering =
                when (renderField?.lowercase()) {
                    null -> null
                    "ledger" -> TraceRendering.LEDGER
                    "lanes" -> TraceRendering.LANES
                    else -> return errorObject("'render' must be 'ledger' or 'lanes'")
                }
            val hasShow = body.containsKey("show")
            val state =
                onEdt {
                    if (hasFollow) {
                        // follow() opens the panel itself; unfollow deliberately leaves it as it was,
                        // the same asymmetry the ✕ on the chip has.
                        if (followId != null) viewModel.follow(followId) else viewModel.unfollow()
                    }
                    if (rendering != null) viewModel.setTraceRendering(rendering)
                    // With neither key, `show` keeps its default of true and this is "open the panel",
                    // as it is for every other panel name.
                    if (hasShow || !hasFollow) {
                        if (show) viewModel.openTracePanel() else viewModel.closeTracePanel()
                    }
                    viewModel.tracePanelOpen.value to viewModel.followedTrace.value
                }
            val followed = state.second
            // The rendering as the app holds it, not what was asked for — a call that named none still
            // reports which drawing is on screen, so a caller never has to assume.
            val drawnAs = viewModel.traceRendering.value
            return buildJsonObject {
                put("status", "ok")
                put("panel", name)
                put("show", state.first)
                put("render", drawnAs.name.lowercase())
                // What is followed *as the app resolved it*, not what was asked for. An id the venue has
                // not echoed yet is followed with nothing in it, and `pending` is how a caller tells
                // that apart from a typo — which /trace would have answered with a 404.
                put("following", followed?.label)
                put("followingAnchor", followed?.anchorId)
                put("pending", followed?.pending ?: false)
                put("sessionCount", followed?.sessionCount ?: 0)
                put("messageCount", followed?.messageCount ?: 0)
            }
        }
        // Editing one rule's reply step in the grid rather than as a raw string, which is otherwise a
        // mouse-only path: the button on the step row is the only way in, so nothing without a hand on
        // the mouse could reach it — or check that it works. Same reason `profile` exists below.
        //
        // `action` finishes what was opened: `apply` writes the edited step back to the staged rule
        // (Save still persists it), `cancel` leaves the rule alone. Both restore whatever message was
        // being composed before the step borrowed the editor.
        if (name == "editor" && (body.containsKey("rule") || body.containsKey("action"))) {
            return editorReplyStep(body)
        }
        // `profile` loads that profile onto the connection form, as clicking it in the list does —
        // the only way to reach the acceptor rules editor, the SSL fields, or anything else the form
        // holds. Without it the panel opens on whatever was last clicked, which for an agent is
        // nothing at all.
        val requestedProfile = if (name == "connection") body["profile"]?.jsonPrimitive?.content else null
        if (requestedProfile != null) {
            val found = onEdt { viewModel.requestConnectionPanelSelection(requestedProfile) }
            if (found != true) return errorObject("unknown profile: $requestedProfile")
        }
        val applied =
            onEdt {
                val (state, toggle) =
                    when (name) {
                        "connection" -> viewModel.showConnectionPanel.value to viewModel::toggleConnectionPanel
                        "editor" -> viewModel.showMessageEditor.value to viewModel::toggleMessageEditor
                        "detail" -> viewModel.showDetailPanel.value to viewModel::toggleDetailPanel
                        "settings" -> viewModel.showSettingsDialog.value to viewModel::toggleSettingsDialog
                        "scenarios" -> viewModel.showScenariosRail.value to viewModel::toggleScenariosRail
                        // The book's own panel. Without this the only way to put it on screen is a
                        // click, which is exactly the hole "Reply With…" left and this slice set out
                        // not to repeat — see the note on /acceptor/orders.
                        "orderbook" -> viewModel.showOrderBookPanel.value to viewModel::toggleOrderBookPanel
                        else -> return@onEdt null
                    }
                if (state != show) toggle()
                show
            } ?: return errorObject(
                "unknown panel '$name' (connection|editor|detail|settings|scenarios|conversations|trace|orderbook)",
            )
        return buildJsonObject {
            put("status", "ok")
            put("panel", name)
            put("show", applied)
            requestedProfile?.let { put("profile", it) }
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
            val result =
                viewModel.saveTemplateDirect(profileId, name, fields, userTags, isFavorite, id)
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
            return FixMessageHelper
                .parseFixMessage(raw)
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
                    ?: viewModel.savedMessages
                        .firstOrNull { it.id == id }
                        ?.userTags
                        ?.firstOrNull()
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
            val template =
                viewModel.savedMessages.firstOrNull { it.id == id }
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
            val target =
                (if (index != null) candidates.getOrNull(index) else candidates.last())
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
        // Seeded as the session's own end of the conversation: on an acceptor session the counterparty's
        // ClOrdID is presence-and-bind, not a literal that is new on every run. See [MintingSide].
        val seeded =
            ExpectationSeeder.seedDetailed(
                fields,
                onEdt { viewModel.dictionary },
                onEdt { viewModel.mintingSideOf(session.title) },
            )
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
        val id =
            body["id"]?.jsonPrimitive?.content ?: java.util.UUID
                .randomUUID()
                .toString()
        val scenario =
            try {
                ScenarioCodec.fromJson(JsonObject(body + ("id" to JsonPrimitive(id))))
            } catch (e: IllegalArgumentException) {
                return errorObject("invalid scenario: ${e.message}")
            }
        val existed = viewModel.scenarioService.load(id) != null
        val ok = viewModel.scenarioService.save(scenario)
        return buildJsonObject {
            put(
                "status",
                if (!ok) {
                    "failed"
                } else if (existed) {
                    "updated"
                } else {
                    "created"
                },
            )
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
        val captured =
            chosen.map { sess ->
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
                sides = onEdt { viewModel.mintingSides() },
                // The live-sessions capture, so it knows which panes are venues. The paste capture below
                // has no sessions behind it and passes none — a pasted log cannot say who hosted what.
                venueSessions = onEdt { viewModel.venueSessionTitles() },
            )
        val ok = viewModel.scenarioService.save(scenario)
        val risk = ScenarioCapture.captureRisk(scan.candidates, onEdt { viewModel.dictionary })
        return buildJsonObject {
            put("status", if (ok) "created" else "failed")
            put("id", scenario.id)
            put("name", scenario.name)
            put("steps", scenario.steps.size)
            // Same rule as `omitted` below, applied to what could not be CLASSIFIED rather than what could
            // not be read. A tag the dictionary cannot name is a tag capture treats as a literal — so a
            // timestamp among them replays the captured moment, for ever. The UI says so in a notification;
            // an agent driving this surface would otherwise have no way to learn it at all.
            risk?.let { put("warning", it) }
            echoProposals(scan.candidates, onEdt { viewModel.dictionary }).takeIf { it.isNotEmpty() }
                ?.let { put("echoProposals", it) }
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
    /**
     * Echo proposals for a capture — the ids this flow shows that nobody has declared.
     *
     * Reported rather than applied, exactly as the review reports them: an agent that silently declared a
     * role from a guessed echo would be writing to the venue's dictionary on its own authority. Accept one
     * by POSTing it to /dictionary/roles and capturing again.
     */
    private fun echoProposals(
        selection: List<ScenarioCapture.Candidate>,
        dictionary: FixDictionaryAdapter?,
    ): JsonArray =
        buildJsonArray {
            EchoDetector.detect(selection, dictionary).forEach { p ->
                add(
                    buildJsonObject {
                        put("kind", p.kind.name)
                        put("role", p.role.name)
                        put("tags", buildJsonArray { p.tags.forEach { add(it) } })
                        put("suggestedName", p.suggestedName)
                        put("value", p.value)
                        put("evidence", p.evidence)
                    },
                )
            }
        }

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
                sides = onEdt { viewModel.mintingSides() },
            )
        val ok = viewModel.scenarioService.save(scenario)
        return buildJsonObject {
            put("status", if (ok) "created" else "failed")
            put("id", scenario.id)
            put("name", scenario.name)
            put("steps", scenario.steps.size)
            put("pasted", scenario.steps.all { it.origin == StepOrigin.PASTED })
            ScenarioCapture.captureRisk(scan.candidates, onEdt { viewModel.dictionary })?.let { put("warning", it) }
            echoProposals(scan.candidates, onEdt { viewModel.dictionary }).takeIf { it.isNotEmpty() }
                ?.let { put("echoProposals", it) }
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
     *
     * **The repair plan rides on the response** (A5): the same `fixPlan` the sheet previews — class, row
     * index, current, proposed, the engine's reason, and D2's default — so agent-driven repair sees what
     * the author sees. `{"applyFix": [0, 3]}` stages exactly those plan rows through the same
     * `EditOp.fixPlan` the sheet's Apply stages, into the open session: visible, undoable, saved by Save.
     * An index the plan does not propose refuses the whole apply — a partial stage that silently skipped
     * a row would report a repair it did not make.
     */

    /**
     * The reconcile session for one step, **by slot**. A scenario's diff window holds a slot per step the pass
     * has visited, and the step this call is about is not necessarily the one the window is showing — reading
     * the window's current session would be right nearly always and silently wrong the rest of the time, which
     * is worse than finding nothing.
     */
    private fun sessionForStep(stepId: String?): ReconcileSession? =
        stepId?.let { id ->
            viewModel.openDiffWindows.value
                .firstNotNullOfOrNull { it.slots[id] }
                ?.session
        }

    private fun reconcile(ex: HttpExchange): JsonElement {
        val body = readJson(ex)
        val at = body["step"]?.jsonPrimitive?.intOrNull
        val applyFix = (body["applyFix"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.intOrNull }
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
                // The session the open created (or re-aimed) — the same object the sheet drives, so the
                // plan here and the plan on screen are one computation apart, never two opinions.
                //
                // Addressed by slot, not by the window's *current* step. The open above has just moved the view
                // to this step, so reading `window.session` would be right nearly always and silently wrong the
                // rest of the time — an agent handed another step's fix plan under this step's name. Failing to
                // find the slot is the honest outcome; guessing is not.
                val session = onEdt { sessionForStep(step.stepId) }
                val plan = session?.let { s -> onEdt { s.fixPlan() } }.orEmpty()
                var staged = 0
                if (applyFix != null) {
                    val byIndex = plan.associateBy { it.index }
                    val unknown = applyFix.filter { it !in byIndex }
                    if (session == null || unknown.isNotEmpty()) {
                        return errorObject(
                            "applyFix names rows the plan does not propose: $unknown — " +
                                "the plan proposes ${plan.map { it.index }}",
                        )
                    }
                    val subset = applyFix.map { byIndex.getValue(it) }
                    if (subset.isNotEmpty()) onEdt { session.apply(EditOp.fixPlan(subset)) }
                    staged = subset.size
                }
                buildJsonObject {
                    put("status", "open")
                    put("scenario", result.scenario)
                    put("step", step.stepIndex + 1)
                    put("stepId", step.stepId)
                    // The run's scope, so an agent reconciling a reference row sees what `${id0}` held —
                    // the same variables the diff window's strip shows, from the same report.
                    if (result.variables.isNotEmpty()) {
                        put(
                            "variables",
                            buildJsonArray { result.variables.forEach { add(ScenarioReport.variableToJson(it)) } },
                        )
                    }
                    if (plan.isNotEmpty()) {
                        put("fixPlan", buildJsonArray { plan.forEach { add(planFixJson(it)) } })
                    }
                    if (applyFix != null) put("staged", staged)
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

    /**
     * **One route, two shapes.** Given `id` or `scenario` alone it is exactly what it has always been:
     * synchronous, byte-compatible, the same object back. Given `set`, `ids` or `repeat` it starts a
     * **job** and answers at once — because a twelve-scenario suite is minutes, this route runs on one of
     * four HTTP threads with no timeout, and the MCP shim gives up at fifteen seconds.
     */
    private fun runScenario(ex: HttpExchange): Coded {
        val body = readJson(ex)
        val wantsSet =
            body["set"] != null || body["ids"] != null || body["rows"] != null || body["fanOut"] != null ||
                (body["repeat"]?.jsonPrimitive?.intOrNull ?: 1) > 1
        if (wantsSet) return startRunSet(body)
        val answer = runOneScenario(body)
        // The one status code this route's old shape gains: a set holds the slot for its whole batch, so
        // "already in progress" stops being a rare race and becomes a caller's ordinary answer for
        // minutes at a time. The body is unchanged; a caller that only reads bodies sees no difference.
        // Structural, not a string compare: the refusal now names the session and the run holding it, so
        // matching on the message would have broken the moment it became useful.
        val busy = (answer as? JsonObject)?.get("busy")?.jsonPrimitive?.booleanOrNull == true
        return Coded(if (busy) HTTP_CONFLICT else HTTP_OK, answer)
    }

    /**
     * **Starts a run set as a job**: 202 and an id, or 409 if a run already holds the slot.
     *
     * Three ways in, and they are the three the rail offers: a saved set by name, an explicit list of
     * scenario ids, and one scenario repeated. A saved set whose file names a scenario nothing answers to
     * still runs the rest and says which name it could not resolve — the alternative is a nightly suite
     * that stops existing the day somebody renames a file.
     */
    private fun startRunSet(body: JsonObject): Coded {
        val policy =
            RunPolicy(
                stopOnFirstFailure = body["stopOnFailure"]?.jsonPrimitive?.booleanOrNull ?: false,
                pauseBetweenMs = body["pauseMs"]?.jsonPrimitive?.longOrNull ?: 0,
                isolateIterations = body["isolate"]?.jsonPrimitive?.booleanOrNull ?: true,
            )
        val repeat = (body["repeat"]?.jsonPrimitive?.intOrNull ?: 1).coerceAtLeast(1)
        val now = System.currentTimeMillis()
        val missing = mutableListOf<String>()

        val setName = body["set"]?.jsonPrimitive?.contentOrNull
        val planned: RunSet =
            when {
                setName != null -> {
                    val saved = viewModel.runSetStore.load(setName) ?: return Coded(HTTP_NOT_FOUND, errorObject("no saved run set named '$setName'"))
                    // The file's own policy unless the call overrode part of it: a saved set carries how it
                    // is meant to be run, and a caller that says nothing about that means the file.
                    val asked = if (body["stopOnFailure"] == null && body["pauseMs"] == null) saved.policy else policy
                    val plan = saved.copy(policy = asked).plan(viewModel.scenarioService.list(), now)
                    missing += plan.missing
                    plan.set
                }
                body["ids"] != null -> {
                    val ids = body["ids"]!!.jsonArray.map { it.jsonPrimitive.content }
                    val scenarios =
                        ids.mapNotNull { id ->
                            viewModel.scenarioService.load(id).also { if (it == null) missing += id }
                        }
                    if (scenarios.isEmpty()) return Coded(HTTP_NOT_FOUND, errorObject("none of those scenarios could be loaded: $ids"))
                    val entries = scenarios.flatMap { sc -> (1..repeat).map { RunEntry(sc.id, sc.name, iteration = it) } }
                    RunSet(
                        id = RunSets.id(now, "selected"),
                        label =
                    "${scenarios.size} ${if (scenarios.size == 1) "scenario" else "scenarios"}" +
                        if (repeat > 1) " ×$repeat" else "",
                        source = RunSource.Selected(scenarios.map { it.id }),
                        entries = entries,
                        policy = policy,
                    )
                }
                else -> {
                    val id =
                        body["id"]?.jsonPrimitive?.contentOrNull
                            ?: return Coded(
                                HTTP_OK,
                                errorObject("a run set needs 'set', 'ids', or 'id' with 'repeat', 'rows' or 'fanOut'"),
                            )
                    val scenario = viewModel.scenarioService.load(id) ?: return Coded(HTTP_NOT_FOUND, errorObject("scenario not found: $id"))
                    body["fanOut"]?.jsonObject?.let { fan ->
                        return startFanOut(scenario, fan)
                    }
                    val rows = body["rows"]
                    if (rows == null) {
                        RunSets.repeat(scenario, repeat, now, policy)
                    } else {
                        // `rows: true` is the whole table; a list is the named rows — one row of eight is
                        // how an author debugs the row that failed without re-running the other seven.
                        val only =
                            (rows as? kotlinx.serialization.json.JsonArray)?.map { it.jsonPrimitive.content }
                                ?.also { asked -> missing += asked.filterNot { name -> scenario.examples?.live.orEmpty().any { it.name == name } } }
                        RunSets.examples(scenario, now, policy, only)
                            ?: return Coded(
                                HTTP_OK,
                                errorObject(
                                    "'${scenario.name}' has no rows to run — it has " +
                                        "${scenario.examples?.rows?.size ?: 0} row(s), " +
                                        "${scenario.examples?.live?.size ?: 0} of them live" +
                                        if (missing.isEmpty()) "" else ", and none named ${missing.joinToString()}",
                                ),
                            )
                    }
                }
            }

        if (planned.entries.isEmpty()) {
            return Coded(HTTP_OK, errorObject("that run set has no entries to run (unresolved: ${missing.joinToString()})"))
        }
        val started =
            viewModel.startRunSet(planned)
                ?: return Coded(HTTP_CONFLICT, busyError(viewModel.runBusyReason()))
        return Coded(
            HTTP_ACCEPTED,
            buildJsonObject {
                put("runSet", started.id)
                put("status", "running")
                put("entries", started.entries.size)
                put("label", started.label)
                if (missing.isNotEmpty()) {
                    put("unresolved", buildJsonArray { missing.forEach { add(it) } })
                }
            },
        )
    }

    /**
     * **Fan a flow out over a profile's sessions**, from outside the app.
     *
     * The refusals are the app's own, and each one names something the caller can change: a profile that
     * opens one session, an acceptor asked to be a lane source, a second leg no profile can spread. The
     * far-end notice rides along when the lanes point at FixTool's own venue, because the p95 that comes
     * back is then the tool's, not a venue's.
     */
    private fun startFanOut(scenario: Scenario, fan: JsonObject): Coded {
        val asked = fan["profile"]?.jsonPrimitive?.contentOrNull
            ?: return Coded(HTTP_OK, errorObject("fanOut needs a 'profile' — the multi-session profile that opens the lanes"))
        val profile =
            onEdt { viewModel.connectionProfiles.firstOrNull { it.id == asked || it.name == asked } }
                ?: return Coded(HTTP_NOT_FOUND, errorObject("no saved profile '$asked'"))
        val started =
            onEdt { viewModel.startFanOut(scenario, profile.id, fan["session"]?.jsonPrimitive?.contentOrNull) }
                ?: return Coded(
                    HTTP_CONFLICT,
                    errorObject(
                        (onEdt { viewModel.fanOutLanes(profile.id) } as? FixMessageViewModel.FanOutLanes.Unavailable)?.why
                            ?: "the fan-out could not be started — a run may already be in progress, or the " +
                            "scenario has a leg that cannot be spread across lanes",
                    ),
                )
        return Coded(
            HTTP_ACCEPTED,
            buildJsonObject {
                put("runSet", started.id)
                put("status", "running")
                put("entries", started.entries.size)
                put("label", started.label)
                put("concurrency", started.policy.concurrency)
                put("lanes", buildJsonArray { started.entries.forEach { e -> e.lane?.let { add(it.senderCompID) } } })
                onEdt { viewModel.fanOutFarEndNotice(profile.id) }?.let { put("notice", it) }
            },
        )
    }

    /**
     * `/scenarios/runs` — the job's other three doors: list, poll, fetch one entry's record, stop.
     *
     * The state comes off **disk**, not out of memory, and that is the design rather than an economy:
     * `set.json` is rewritten as each entry lands, so the answer survives a restart, and a headless run on
     * a build box leaves exactly the same thing to read.
     */
    @Suppress("ReturnCount")
    private fun runSets(ex: HttpExchange): Coded {
        val parts =
            ex.requestURI.path
                .trim('/')
                .split('/')
        // /scenarios/runs -> ["scenarios","runs"]; /scenarios/runs/<id>[/entries/<n>|/stop]
        val setId = parts.getOrNull(2)
        if (setId == null) {
            val sets = viewModel.runRecordStore.listSets()
            return Coded(
                HTTP_OK,
                buildJsonObject {
                    put("count", sets.size)
                    put("sets", buildJsonArray { sets.forEach { add(runSetSummary(it)) } })
                },
            )
        }
        val tail = parts.getOrNull(3)
        if (tail == "stop") {
            // Asked of the claim registry, not of `activeRunSet` — that holds the one set the rail is
            // drawing, so with two runs in flight it named the wrong one and this refused to stop a run
            // that was running.
            if (!viewModel.isRunSetRunning(setId)) {
                return Coded(HTTP_CONFLICT, errorObject("run set '$setId' is not running"))
            }
            // Named, because a second run may be in flight on other sessions and this must not stop it.
            viewModel.requestScenarioStop(setId)
            return Coded(
                HTTP_ACCEPTED,
                buildJsonObject {
                    put("status", "stopping")
                    put("runSet", setId)
                },
            )
        }
        if (tail == "entries") {
            val n = parts.getOrNull(4)?.toIntOrNull() ?: return Coded(HTTP_NOT_FOUND, errorObject("which entry?"))
            val record =
                viewModel.runRecordStore.readEntry(setId, n)
                    ?: return Coded(HTTP_NOT_FOUND, errorObject("no record for entry $n of '$setId'"))
            return Coded(HTTP_OK, RunRecordCodec.toJson(record))
        }
        // The poll. `?wait=` holds the request until the set finishes or the wait runs out — kept under the
        // MCP shim's own ceiling, so an agent can wait without the transport giving up underneath it.
        val waitMs = queryParams(ex)["wait"]?.toLongOrNull()?.coerceIn(0, MAX_SET_WAIT_MS) ?: 0
        val deadline = System.currentTimeMillis() + waitMs
        var set = viewModel.runRecordStore.readSet(setId) ?: return Coded(HTTP_NOT_FOUND, errorObject("no run set '$setId'"))
        while (set.status == RunSetStatus.RUNNING && System.currentTimeMillis() < deadline) {
            Thread.sleep(WAIT_POLL_MS)
            set = viewModel.runRecordStore.readSet(setId) ?: set
        }
        return Coded(HTTP_OK, runSetDetail(set, viewModel.runRecordStore.readSetStats(setId)))
    }

    /**
     * `fixtool_run_status` — the poll, the listing and the stop behind one tool, because an agent asking
     * "how is it going" and "stop it" is asking about the same thing and should not have to find two
     * names for it.
     */
    private fun runStatusTool(args: JsonObject): JsonElement {
        val setId = args["runSet"]?.jsonPrimitive?.contentOrNull
        if (setId == null) {
            val sets = viewModel.runRecordStore.listSets()
            return buildJsonObject {
                put("count", sets.size)
                put("sets", buildJsonArray { sets.forEach { add(runSetSummary(it)) } })
            }
        }
        if (args["stop"]?.jsonPrimitive?.booleanOrNull == true) {
            if (!viewModel.isRunSetRunning(setId)) {
                return errorObject("run set '$setId' is not running")
            }
            viewModel.requestScenarioStop(setId)
        }
        val waitMs = args["wait"]?.jsonPrimitive?.longOrNull?.coerceIn(0, MAX_SET_WAIT_MS) ?: 0
        val deadline = System.currentTimeMillis() + waitMs
        var set = viewModel.runRecordStore.readSet(setId) ?: return errorObject("no run set '$setId'")
        while (set.status == RunSetStatus.RUNNING && System.currentTimeMillis() < deadline) {
            Thread.sleep(WAIT_POLL_MS)
            set = viewModel.runRecordStore.readSet(setId) ?: set
        }
        return runSetDetail(set, viewModel.runRecordStore.readSetStats(setId))
    }

    private fun runEntryTool(args: JsonObject): JsonElement {
        val setId = args["runSet"]?.jsonPrimitive?.contentOrNull ?: return errorObject("runSet is required")
        val n = args["entry"]?.jsonPrimitive?.intOrNull ?: return errorObject("entry is required (1-based)")
        val record = viewModel.runRecordStore.readEntry(setId, n) ?: return errorObject("no record for entry $n of '$setId'")
        return RunRecordCodec.toJson(record)
    }

    /**
     * **Starts a load run as a job**: 202 and an id, 409 when the lanes are held or none is logged on, and a
     * plain error object when the plan itself is wrong, including a memory store without Reset on Logon.
     *
     * The template is a saved message by name or id, `fields` as `[{tag, value}]`, or `raw` as one wire
     * line. The shape is `count` for a burst or `rate` with `forMs` for a sustained run. Everything else has
     * the command line's defaults, and the store and log follow the profile unless `store` or `log` say.
     */
    @Suppress("ReturnCount", "LongMethod", "CyclomaticComplexMethod")
    private fun startLoad(body: JsonObject): Coded {
        val profileKey = body["profile"]?.jsonPrimitive?.contentOrNull ?: return Coded(HTTP_OK, errorObject("a load run needs a 'profile'"))
        val profile =
            onEdt { viewModel.connectionProfiles.firstOrNull { it.id == profileKey || it.name == profileKey } }
                ?: return Coded(HTTP_NOT_FOUND, errorObject("no saved profile '$profileKey'"))
        val template =
            when {
                body["raw"] != null -> LoadTemplates.fromRaw(body["name"]?.jsonPrimitive?.contentOrNull ?: "raw", body["raw"]!!.jsonPrimitive.content)
                body["fields"] != null ->
                    LoadTemplate(
                        body["name"]?.jsonPrimitive?.contentOrNull ?: "fields",
                        body["fields"]!!.jsonArray.mapNotNull { f ->
                            val o = f.jsonObject
                            o["tag"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.let { it to (o["value"]?.jsonPrimitive?.contentOrNull ?: "") }
                        },
                    )
                body["template"] != null -> {
                    val key = body["template"]!!.jsonPrimitive.content
                    onEdt { viewModel.loadTemplates(profile.id) }.firstOrNull { it.name.equals(key, ignoreCase = true) }
                        ?: return Coded(HTTP_NOT_FOUND, errorObject("no saved message named '$key' for '${profile.name}'"))
                }
                else -> return Coded(HTTP_OK, errorObject("a load run needs a 'template' (saved message name), 'fields' or 'raw'"))
            }
        if (template.msgType == null) return Coded(HTTP_OK, errorObject("the template has no MsgType (35)"))
        val count = body["count"]?.jsonPrimitive?.intOrNull
        val perSecond = body["rate"]?.jsonPrimitive?.intOrNull
        val forMs = body["forMs"]?.jsonPrimitive?.longOrNull
        val shape =
            when {
                count != null && count > 0 && perSecond == null -> LoadShape.Burst(count)
                perSecond != null && perSecond > 0 && forMs != null && forMs > 0 && count == null -> LoadShape.Rate(perSecond, forMs)
                else -> return Coded(HTTP_OK, errorObject("say either 'count' for a burst, or 'rate' with 'forMs' for a sustained run"))
            }
        val match =
            (body["match"] as? JsonObject)?.let { m ->
                val req = m["requestTag"]?.jsonPrimitive?.intOrNull ?: return Coded(HTTP_OK, errorObject("match needs a requestTag"))
                LoadMatch(req, m["replyTag"]?.jsonPrimitive?.intOrNull ?: req, m["replyType"]?.jsonPrimitive?.contentOrNull)
            } ?: template.inferMatch()?.copy(replyType = body["replyType"]?.jsonPrimitive?.contentOrNull)
                ?: return Coded(HTTP_OK, errorObject("'${template.name}' carries no tag a reply is matched on — pass match:{requestTag, replyTag}"))
        val store = body["store"]?.jsonPrimitive?.contentOrNull?.let { k -> FixConnectionConfig.MessageStoreKind.entries.firstOrNull { it.name.equals(k, true) } }
        val log = body["log"]?.jsonPrimitive?.contentOrNull?.let { k -> FixConnectionConfig.MessageLogKind.entries.firstOrNull { it.name.equals(k, true) } }
        val override = if (store != null || log != null) StoreAndLogOverride(store ?: profile.config.messageStore, log ?: profile.config.messageLog) else null
        (override?.applyTo(profile.config) ?: profile.config).storeProblem()?.let { return Coded(HTTP_OK, errorObject(it)) }
        val listen =
            (body["listen"] as? JsonArray).orEmpty().mapNotNull { e ->
                val key = e.jsonPrimitive.content
                onEdt { viewModel.connectionProfiles.firstOrNull { it.id == key || it.name == key } }?.id
            }
        val label = LoadPlan.label(template, shape, profile.name)
        val plan =
            LoadPlan(
                id = RunSets.id(System.currentTimeMillis(), label),
                label = label,
                template = template,
                profileId = profile.id,
                profileName = profile.name,
                listenProfileIds = listen,
                shape = shape,
                match = match,
                settleMs = body["settleMs"]?.jsonPrimitive?.longOrNull ?: LoadPlan.DEFAULT_SETTLE_MS,
                seed = (body["seed"] as? JsonObject).orEmpty().mapValues { it.value.jsonPrimitive.content },
                storeAndLog = override,
                strictRate = body["strictRate"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        val started =
            onEdt { viewModel.startLoadRun(plan) }
                ?: return Coded(
                    HTTP_CONFLICT,
                    errorObject(
                        (onEdt { viewModel.loadLanes(profile.id) } as? FixMessageViewModel.FanOutLanes.Unavailable)?.why
                            ?: viewModel.runBusyReason(),
                    ),
                )
        return Coded(
            HTTP_ACCEPTED,
            buildJsonObject {
                put("load", started.id)
                put("status", "running")
                put("label", started.label)
                onEdt { viewModel.fanOutFarEndNotice(profile.id) }?.let { put("notice", it) }
            },
        )
    }

    /**
     * `/loads` — list, poll one, stop one. The state comes off disk, as the run set's does, so a run started
     * headless leaves exactly the same thing to read, and the answer survives a restart.
     */
    @Suppress("ReturnCount")
    private fun loads(ex: HttpExchange): Coded {
        val parts = ex.requestURI.path.trim('/').split('/')
        // /loads -> ["loads"]; /loads/<id>[/stop]
        val id = parts.getOrNull(1) ?: return Coded(HTTP_OK, loadList())
        if (parts.getOrNull(2) == "stop") {
            if (!viewModel.isLoadRunning(id)) return Coded(HTTP_CONFLICT, errorObject("load run '$id' is not running"))
            viewModel.stopLoadRun(id)
            return Coded(HTTP_ACCEPTED, buildJsonObject { put("status", "stopping"); put("load", id) })
        }
        val waitMs = queryParams(ex)["wait"]?.toLongOrNull()?.coerceIn(0, MAX_SET_WAIT_MS) ?: 0
        val report = awaitLoad(id, waitMs) ?: return Coded(HTTP_NOT_FOUND, errorObject("no load run '$id'"))
        return Coded(HTTP_OK, LoadReportCodec.toJson(report))
    }

    /** `fixtool_load_status`: the listing, the poll and the stop behind one tool, as `fixtool_run_status` is. */
    private fun loadStatusTool(args: JsonObject): JsonElement {
        val id = args["load"]?.jsonPrimitive?.contentOrNull ?: return loadList()
        if (args["stop"]?.jsonPrimitive?.booleanOrNull == true) {
            if (!viewModel.isLoadRunning(id)) return errorObject("load run '$id' is not running")
            viewModel.stopLoadRun(id)
        }
        val waitMs = args["wait"]?.jsonPrimitive?.longOrNull?.coerceIn(0, MAX_SET_WAIT_MS) ?: 0
        val report = awaitLoad(id, waitMs) ?: return errorObject("no load run '$id'")
        return LoadReportCodec.toJson(report)
    }

    /** The live report while the run is this process's, the record otherwise, held up to [waitMs] for it to finish. */
    private fun awaitLoad(id: String, waitMs: Long): LoadReport? {
        val deadline = System.currentTimeMillis() + waitMs
        fun current(): LoadReport? = viewModel.activeLoadRun.value?.takeIf { it.id == id } ?: viewModel.loadRecordStore.read(id)
        var report = current() ?: return null
        while (report.status == LoadStatus.RUNNING && System.currentTimeMillis() < deadline) {
            Thread.sleep(WAIT_POLL_MS)
            report = current() ?: report
        }
        return report
    }

    private fun loadList(): JsonObject {
        val loads = viewModel.loadRecordStore.list()
        return buildJsonObject {
            put("count", loads.size)
            put(
                "loads",
                buildJsonArray {
                    loads.forEach { r ->
                        add(
                            buildJsonObject {
                                put("id", r.id)
                                put("label", r.label)
                                put("status", r.status.name.lowercase())
                                put("phase", r.phase.name.lowercase())
                                put("issued", r.issue.leftSocket)
                                put("matched", r.replies.matched)
                                put("unmatched", r.replies.unmatched)
                                put("startedAt", r.startedAt)
                                r.finishedAt?.let { put("finishedAt", it) }
                                r.verdict.exitCode?.let { put("exitCode", it) }
                            },
                        )
                    }
                },
            )
        }
    }

    private fun runSetSummary(set: RunSet): JsonObject =
        buildJsonObject {
            put("id", set.id)
            put("label", set.label)
            put("status", set.status.name.lowercase())
            put("total", set.total)
            put("done", set.done)
            put("passed", set.passed)
            put("failed", set.failed)
            put("startedAt", set.startedAt)
            set.finishedAt?.let { put("finishedAt", it) }
        }

    /**
     * [stats] is the set's own `stats` block, read back from disk. It cannot be recomputed here: a set
     * read from `set.json` carries no per-step latencies, so without this the one number a load run
     * exists to produce was reachable only by fetching all N entry records and doing the arithmetic.
     */
    /**
     * A refusal because something else holds the sessions. Carries `busy` so the route can answer 409
     * without matching on the message — which now names the session and the run holding it.
     */
    private fun busyError(reason: String): JsonObject =
        buildJsonObject {
            put("error", reason)
            put("busy", true)
        }

    private fun runSetDetail(set: RunSet, stats: JsonObject? = null): JsonObject =
        buildJsonObject {
            put("status", set.status.name.lowercase())
            put("runSet", set.id)
            put("label", set.label)
            stats?.let { put("stats", it) }
            put(
                "summary",
                buildJsonObject {
                    put("total", set.total)
                    put("done", set.done)
                    put("passed", set.passed)
                    put("failed", set.failed)
                    put("elapsedMs", (set.finishedAt ?: System.currentTimeMillis()) - set.startedAt)
                },
            )
            put(
                "entries",
                buildJsonArray {
                    set.entries.forEachIndexed { i, e ->
                        add(
                            buildJsonObject {
                                put("n", i + 1)
                                put("scenario", e.scenarioName)
                                put("iteration", e.iteration)
                                put("state", e.state.name.lowercase())
                                e.durationMs?.let { put("durationMs", it) }
                                e.record?.let { put("record", it) }
                                e.note?.let { put("note", it) }
                            },
                        )
                    }
                },
            )
        }

    private fun runOneScenario(body: JsonObject): JsonElement {
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
                        JsonObject(
                            obj + (
                                "id" to
                                    JsonPrimitive(
                                        java.util.UUID
                                            .randomUUID()
                                            .toString(),
                                    )
                            ),
                        )
                    }
                try {
                    ScenarioCodec.fromJson(withId)
                } catch (e: IllegalArgumentException) {
                    return errorObject("invalid scenario: ${e.message}")
                }
            }
        // A throwaway remap for THIS run only: `sessions` is {from: to} over the scenario's session
        // names. Nothing is persisted — an environment a flow should keep is a saved copy of the
        // scenario (the rail's "Save as scenario…"), because environments diverge in data and each
        // copy must reconcile independently. This parameter is for the run that must not leave one.
        val sessionMap =
            body["sessions"]?.jsonObject?.entries?.associate { (from, to) ->
                from to (to.jsonPrimitive.contentOrNull ?: return errorObject("sessions['$from'] must be a session name"))
            } ?: emptyMap()
        // The same run the Run button performs — one run slot, one choreography, one verdict published the
        // same way. This endpoint used to keep its own copy of that sequence and had quietly dropped the
        // last step of it, so an agent-driven run left the rail's run report (and the only route to the
        // diff window) blank. Null = the slot is taken; a UI run and a control run would otherwise
        // consume each other's messages.
        val result =
            viewModel.runScenarioBlocking(scenario, sessionMap)
                ?: return busyError(viewModel.runBusyReason())
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
                put(
                    "mode",
                    viewModel.detailMatchContextMode.value.name
                        .lowercase(),
                )
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

    // ------------------------------------------------------------ traces (across every session)

    /**
     * **Every trace, as the Ledger's header rows read them.**
     *
     * The question this answers is not the one `/messages` and the grouped grid answer — *what happened
     * to `RFQ-A1` **on this session*** ([Conversations]) — but *what happened to `RFQ-A1`*: the same
     * relation computed over every pane's snapshot at once. An agent asking that today reads ids off
     * `/messages` session by session and posts a regex to `/search`, which is the loop this route exists
     * to delete. The regex only finds ids the caller already knew, and it matches text: `ORD-9` finds
     * `ORD-91`. A trace joins on whole correlation **values** or not at all.
     *
     * Every field is **quoted**, never derived. [Traces.summarize] defers to [Conversations.summarize] for
     * exactly that reason: counts are facts, `status` is the last thing a message *stated* rendered in the
     * dictionary's own words, and `instrument`/`quantity` appear only where the opening message leaves no
     * doubt. So an agent may assert on this the way it asserts on `fields[]`.
     *
     * `ungrouped` and `total` are here because nothing may be hidden: a message carrying no correlation id
     * — a heartbeat, a logon, an unsolicited venue message — is counted rather than tidied away, and
     * `total` is what every session is holding, so a caller can see the numbers add up.
     */
    private fun traces(): JsonElement {
        val world = traceWorld()
        val grouping = Traces.group(world.snapshots, world.dictionary, world.lostIds)
        return buildJsonObject {
            put(
                "traces",
                buildJsonArray {
                    grouping.traces.forEach { trace ->
                        val summary = Traces.summarize(trace, world.snapshots, world.dictionary).exchange
                        add(
                            buildJsonObject {
                                putTraceIdentity(trace, world)
                                put("messageCount", summary.messageCount)
                                put(
                                    "composition",
                                    buildJsonArray {
                                        summary.composition.forEach { part ->
                                            add(
                                                buildJsonObject {
                                                    put("messageType", part.messageType)
                                                    // Null rather than absent when the dictionary has no
                                                    // word for the type: the reader learns the type was
                                                    // seen and unnamed, not that the key was forgotten.
                                                    put("name", part.name)
                                                    put("count", part.count)
                                                },
                                            )
                                        }
                                    },
                                )
                                put(
                                    "status",
                                    summary.status?.let { stated ->
                                        buildJsonObject {
                                            put("tag", stated.tag)
                                            put("fieldName", stated.fieldName)
                                            put("value", stated.value)
                                            put("valueName", stated.valueName)
                                        }
                                    } ?: JsonNull,
                                )
                                put("instrument", summary.instrument)
                                put("quantity", summary.quantity)
                                put("elapsedMillis", summary.elapsedMillis)
                            },
                        )
                    }
                },
            )
            put("ungrouped", grouping.ungrouped.size)
            put("total", grouping.total)
        }
    }

    /**
     * **One trace at full fidelity**: every message of the exchange carrying `id`, merged into one time
     * order across the sessions that saw it.
     *
     * **`id` is a whole value, not a pattern.** `?id=ORD-9` finds the trace whose id set *contains*
     * `ORD-9` and never the one holding `ORD-91` — which is the entire point of this route existing
     * beside `/search`, where the same string is a substring match over a display line. Give it any id
     * the exchange carries: the client's `RFQ-A1`, the venue's `V-2291` and the quote's `Q-77` are three
     * names for one trace and all three return it.
     *
     * Each message is the shape `/messages` emits — same serialiser, so `wireOrderKnown` and the ordered
     * `fields` array mean exactly what they mean there — plus two things only a trace knows:
     *
     * - `session`, because [FixMessage] deliberately does not carry which pane logged it ([Located]);
     * - `elapsedMillis`, the gap since the previous message **in this trace** on whichever session it
     *   landed. One clock timed both ends, so the number between a request leaving the client and its
     *   copy arriving on an LP is the venue's real forwarding time — the measurement two machines' logs
     *   cannot reconcile. It is a measurement and not a diagnosis: the tool states the gap and says
     *   nothing about its cause. Null on the first message, which had nothing to be measured from.
     *
     * A missing or blank `id` is a **400** and an unknown one a **404**: an empty result would read as
     * "that exchange never happened", and the caller cannot tell a typo from a venue that never replied.
     */
    private fun trace(ex: HttpExchange): Coded {
        val id =
            queryParams(ex)["id"]?.takeIf { it.isNotBlank() }
                ?: return Coded(
                    HTTP_BAD_REQUEST,
                    errorObject("missing 'id' — a whole correlation value the trace carries, e.g. /trace?id=RFQ-A1"),
                )
        val world = traceWorld()
        val grouping = Traces.group(world.snapshots, world.dictionary, world.lostIds)
        val trace =
            grouping.traces.firstOrNull { id in it.ids }
                ?: return Coded(
                    HTTP_NOT_FOUND,
                    errorObject(
                        "no trace carries the id '$id' — it is matched as a whole value, never as a substring; " +
                            "GET /traces lists every trace with the ids it holds",
                    ),
                )
        val summary = Traces.summarize(trace, world.snapshots, world.dictionary).exchange
        val messages = trace.members.map { world.snapshots[it.session][it.index] }
        return Coded(
            HTTP_OK,
            buildJsonObject {
                putTraceIdentity(trace, world)
                put("messageCount", summary.messageCount)
                put("elapsedMillis", summary.elapsedMillis)
                put(
                    "messages",
                    buildJsonArray {
                        messages.forEachIndexed { position, message ->
                            val previous = messages.getOrNull(position - 1)
                            add(
                                buildJsonObject {
                                    put("session", sessionRef(trace.members[position].session, world))
                                    put(
                                        "elapsedMillis",
                                        previous?.let {
                                            java.time.Duration
                                                .between(it.timestamp, message.timestamp)
                                                .toMillis()
                                        },
                                    )
                                    // The same serialiser `/messages` uses, spread in beside the two
                                    // trace-only keys. A second one written for this route would be a
                                    // second answer to "what did that message say".
                                    messageJson(message).forEach { (key, value) -> put(key, value) }
                                },
                            )
                        }
                    },
                )
            },
        )
    }

    /**
     * What both trace routes say about *which* exchange this is, written once so they cannot disagree.
     *
     * `sessions` is where it was seen, in the order it first appeared on each; `truncatedSessions` is
     * where a message it would have contained was already evicted — the trace opened before the buffer,
     * and saying so is better than a first row quietly pretending to be the first message. Empty means
     * nothing was lost; see [Traces.group]'s `lostIds` for what that claim rests on.
     */
    private fun JsonObjectBuilder.putTraceIdentity(trace: Traces.Trace, world: TraceWorld) {
        put("label", trace.label)
        put("labelTag", trace.labelTag)
        put("ids", buildJsonArray { trace.ids.forEach { add(it) } })
        put("sessions", buildJsonArray { trace.sessions.forEach { add(sessionRef(it, world)) } })
        put("truncatedSessions", buildJsonArray { trace.truncatedSessions.forEach { add(sessionRef(it, world)) } })
    }

    /** A session by the two names a caller can act on: its index, which `?session=` takes, and its title. */
    private fun sessionRef(session: Int, world: TraceWorld): JsonObject =
        buildJsonObject {
            put("index", session)
            put("title", world.titles[session])
        }

    /**
     * **What every session is holding right now**, read in one EDT round-trip so the answer describes one
     * moment rather than a walk across panes that kept arriving.
     *
     * Positional throughout, because that is [Located]'s contract: session *s* is `snapshots[s]`, and
     * `titles`/`lostIds` are parallel to it. The lost-id sets are handed over live rather than copied —
     * they are concurrent sets built to be probed, and [Traces.group] only probes.
     */
    private class TraceWorld(
        val titles: List<String>,
        val snapshots: List<List<FixMessage>>,
        val lostIds: List<Set<String>>,
        val dictionary: FixDictionaryAdapter?,
    )

    /**
     * Grouped per request over the current snapshots, **not** read from the Trace panel's memo.
     *
     * The panel keeps a grouping and refreshes it on a tick, but only while it is open or something is
     * followed — a headless agent driving a build with no panel on screen would be handed whatever the
     * last tick saw, or nothing at all. Regrouping here costs single-digit milliseconds for eight
     * sessions of a thousand messages, which is less than the HTTP round-trip carrying the answer, and an
     * agent polling through a burst has to see what the panes see.
     *
     * TODO: if that memo ever becomes unconditional and keyed on snapshot identity, read it from here
     * instead, so the panel and this route cannot answer differently about the same instant.
     */
    private fun traceWorld(): TraceWorld =
        onEdt {
            val sessions = viewModel.sessions.toList()
            TraceWorld(
                titles = sessions.map { it.title },
                snapshots = sessions.map { it.messages.value.filterIsInstance<FixMessage>() },
                lostIds = sessions.map { it.lostCorrelationIds },
                dictionary = viewModel.getDictionaryAdapter(),
            )
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

    /**
     * The demo, over the example workspace it became.
     *
     * Kept at `/demo` with the same two actions because scripts and the MCP tool call it by that name.
     * `start` copies the bundled FX venue into a workspace and opens it; `stop` closes the workspace,
     * which is the nearest true thing to the old uninstall — the copy stays on disk, because it is the
     * caller's now and deleting a directory is not what "stop" ought to mean.
     */
    private fun demo(ex: HttpExchange): JsonElement {
        val body = readJson(ex)
        val action = body["action"]?.jsonPrimitive?.content?.lowercase() ?: "start"
        val outcome =
            onEdt {
                when (action) {
                    "stop" -> {
                        viewModel.closeWorkspace()
                        Result.success(viewModel.openWorkspace)
                    }
                    else -> viewModel.openExample(ExampleWorkspaces.FX_VENUE)
                }
            }
        outcome.exceptionOrNull()?.let { return errorObject("could not $action the example: ${it.message}") }

        return buildJsonObject {
            put("status", "ok")
            put("action", action)
            put("workspace", onEdt { viewModel.openWorkspace.absolutePath })
            put("running", onEdt { !viewModel.openWorkspaceIsHome })
            // Named so a caller can address the venue without knowing the constant.
            put("venue", "FX Demo Venue")
            put("port", DEMO_VENUE_PORT)
        }
    }

    /** The open workspace, and the door to changing it. */
    private fun workspace(ex: HttpExchange): JsonElement {
        val body = readJson(ex)
        val requested = body["workspace"]?.jsonPrimitive?.content
        val example = body["example"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        if (ex.requestMethod == "POST" && example != null) {
            val opened = onEdt { viewModel.openExample(example) }
            opened.exceptionOrNull()?.let { return errorObject("could not open example '$example': ${it.message}") }
        } else if (ex.requestMethod == "POST") {
            if (requested.isNullOrBlank()) {
                onEdt { viewModel.closeWorkspace() }
            } else {
                val opened = onEdt { viewModel.openWorkspace(File(requested)) }
                opened.exceptionOrNull()?.let { return errorObject("could not open '$requested': ${it.message}") }
            }
        }
        return buildJsonObject {
            put("status", "ok")
            put("workspace", onEdt { viewModel.openWorkspace.absolutePath })
            put("isDefault", onEdt { viewModel.openWorkspaceIsHome })
            put(
                "recent",
                buildJsonArray { onEdt { viewModel.recentWorkspaces }.forEach { add(JsonPrimitive(it.absolutePath)) } },
            )
            put(
                "environments",
                buildJsonArray {
                    onEdt { viewModel.environments }.forEach { environment ->
                        add(
                            buildJsonObject {
                                put("name", environment.name)
                                put("host", environment.host)
                                put("port", environment.port)
                                environment.useSSL?.let { put("useSSL", it) }
                            },
                        )
                    }
                },
            )
            put(
                "examples",
                buildJsonArray {
                    ExampleWorkspaces.all().forEach { example ->
                        add(
                            buildJsonObject {
                                put("id", example.id)
                                put("displayName", example.displayName)
                                put("summary", example.summary)
                            },
                        )
                    }
                },
            )
        }
    }

    private fun connect(ex: HttpExchange): JsonElement {
        val body = readJson(ex)
        val key = body["profile"]?.jsonPrimitive?.content ?: return errorObject("missing 'profile'")
        val profile =
            onEdt { viewModel.connectionProfiles.firstOrNull { it.id == key || it.name == key } }
                ?: return errorObject("profile not found: $key")
        // Refused here rather than discovered as an ERROR state a poll later: the connection manager
        // would refuse the same config in the same words, but a caller deserves them in the reply.
        profile.config.storeProblem()?.let { return errorObject(it) }
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
            val template =
                viewModel.savedMessages.firstOrNull { it.id == id }
                    ?: return@onEdt errorObject("template not found: $id")
            val index =
                if (sessionKey != null) {
                    viewModel.sessions
                        .indexOfFirst { it.id == sessionKey || it.title == sessionKey }
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
                    session.messages.value
                        .filterIsInstance<FixMessage>()
                        .firstOrNull { matchesMessage(it, match) }
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
        match["direction"]
            ?.jsonPrimitive
            ?.content
            ?.lowercase()
            ?.let { if (!directionMatches(msg, it)) return false }
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

        // Reports what it dropped rather than just "ok": a sequence with nothing left to cancel and one
        // that was stopped four steps in are different outcomes, and the caller usually wants to know
        // which it got.
        if (action == "stop-responses") {
            return buildJsonObject {
                put("status", "ok")
                put("action", action)
                put("dropped", onEdt { session.stopPendingResponses() } ?: 0)
            }
        }

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
                        "(reset-seqnum|test-request|resend-request|sequence-reset|logout|disconnect|seqnum|stop-responses)",
                )
            }
        return buildJsonObject {
            put("status", if (ok) "ok" else "failed")
            put("action", action)
        }
    }

    /**
     * The shipped venue behaviours, by name — what `POST /acceptor/rules {"preset": …}` can insert.
     *
     * Readable rather than a bare list of ids, because choosing between "cancel accepted" and "cancel
     * rejected" needs the trigger and the reply, and reproducing those from the templates is the work
     * the presets exist to remove. Profile-independent: these are the same for every profile, so this
     * takes no arguments and reads nothing.
     */
    private fun acceptorPresets(): JsonElement =
        buildJsonArray {
            AcceptorPresets.all.forEach { preset ->
                add(
                    buildJsonObject {
                        put("id", preset.id)
                        put("name", preset.name)
                        put("group", preset.group)
                        put("summary", preset.summary)
                        put(
                            "rules",
                            buildJsonArray {
                                preset.rules.forEachIndexed { index, rule -> add(acceptorRuleJson(index, rule)) }
                            },
                        )
                    },
                )
            }
        }

    /**
     * Opens one rule's reply step in the message editor, or finishes the one that is open.
     *
     * The apply path deliberately goes through the **ViewModel**, not through the rules list: it is the
     * same call the Apply button makes, so the refusals it owes — a value carrying the field separator,
     * a tag with no value — are the ones an author gets, and a change to either cannot pass here while
     * failing there. What it does *not* do is save: the step lands in the connection panel's staged
     * rules, exactly as typing into the raw field would, and Save is still what persists it.
     */
    private fun editorReplyStep(body: JsonObject): JsonElement {
        val action = body["action"]?.jsonPrimitive?.content?.lowercase()
        return onEdt {
            when (action) {
                "apply" -> {
                    val target = viewModel.editorTarget as? EditorTarget.ReplyStep
                        ?: return@onEdt errorObject("the editor is not holding a reply step")
                    val applied = viewModel.applyReplyStep()
                        ?: return@onEdt errorObject(
                            viewModel.editorValidationErrors.joinToString(" ").ifBlank { "the step could not be applied" },
                        )
                    buildJsonObject {
                        put("status", "applied")
                        put("rule", applied.ruleIndex)
                        put("step", applied.stepIndex)
                        put("template", applied.template)
                        // Staged, not saved — said plainly, because a venue that has not changed must
                        // never read as one that has.
                        put("saved", false)
                        put("profile", target.profileId)
                    }
                }
                "cancel" -> {
                    if (viewModel.editorTarget !is EditorTarget.ReplyStep) {
                        return@onEdt errorObject("the editor is not holding a reply step")
                    }
                    viewModel.cancelReplyStep()
                    buildJsonObject {
                        put("status", "cancelled")
                    }
                }
                null -> {
                    val profileKey = body["profile"]?.jsonPrimitive?.content
                        ?: return@onEdt errorObject("missing 'profile'")
                    val ruleIndex = body["rule"]?.jsonPrimitive?.intOrNull
                        ?: return@onEdt errorObject("missing 'rule' (the rule's index)")
                    val stepIndex = body["step"]?.jsonPrimitive?.intOrNull ?: 0
                    val profile =
                        viewModel.connectionProfiles.firstOrNull { it.id == profileKey || it.name == profileKey }
                            ?: return@onEdt errorObject("unknown profile: $profileKey")
                    val rules = profile.config.acceptorResponseRules
                    val rule = rules.getOrNull(ruleIndex)
                        ?: return@onEdt errorObject(
                            "rule $ruleIndex is out of range (the profile has ${rules.size} rule(s))",
                        )
                    val step = rule.sequence().getOrNull(stepIndex)
                        ?: return@onEdt errorObject(
                            "step $stepIndex is out of range (rule $ruleIndex replies with ${rule.sequence().size} step(s))",
                        )
                    // The panel has to be showing this profile, or Apply would arrive at a rule list
                    // that is not the one this step came from and be refused for it.
                    viewModel.requestConnectionPanelSelection(profile.id)
                    if (!viewModel.showConnectionPanel.value) viewModel.toggleConnectionPanel()
                    viewModel.openReplyStep(profile.id, ruleIndex, stepIndex, step.template)
                    buildJsonObject {
                        put("status", "editing")
                        put("profile", profile.name)
                        put("rule", ruleIndex)
                        put("step", stepIndex)
                        put("template", step.template)
                    }
                }
                else -> errorObject("unknown action '$action' (apply|cancel), or omit it to open a step")
            }
        }
    }

    /** `/acceptor/rules` is method-aware: GET inspects, POST adds/replaces/toggles one, DELETE removes one. */
    private fun acceptorRulesEndpoint(ex: HttpExchange): JsonElement =
        when (ex.requestMethod.uppercase()) {
            "POST" -> upsertAcceptorRule(ex)
            "DELETE" -> deleteAcceptorRule(ex)
            else -> acceptorRules(ex)
        }

    /**
     * **What the venue is holding, over HTTP** — and the reason this endpoint is in the *first* slice
     * of #35 rather than a later one.
     *
     * "Reply With…" shipped with no way to drive it but a mouse, so it could only ever be verified by
     * hand. The book is the state that everything after it reads, and a state nobody can read from
     * outside the app is a state nobody can test. So: this lands with the book itself.
     *
     * `GET` with no `session` is the roll-up — one line per counterparty, no orders, because a venue
     * with four clients holding a thousand orders each is not a useful default response. With
     * `session` it is that book in full, every order carrying its trail, which is the shape an
     * assertion wants. `order` narrows to one.
     */
    private fun acceptorOrdersEndpoint(ex: HttpExchange): JsonElement =
        when (ex.requestMethod.uppercase()) {
            "POST" -> clearAcceptorOrders(ex)
            else -> acceptorOrders(ex)
        }

    private fun acceptorOrders(ex: HttpExchange): JsonElement {
        val params = queryParams(ex)
        val requested = params["session"]
        val sessions = onEdt { viewModel.sessions.toList() }
        val books =
            sessions
                // A venue that accepts any client is a listener: its counterparties each have a pane,
                // and a line for the listener itself would always read zero.
                .filterNot { it.isVenue }
                .mapNotNull { session -> session.orderBook()?.let { session to it } }
        if (requested == null) {
            return buildJsonObject {
                put(
                    "books",
                    buildJsonArray {
                        books.forEach { (session, book) ->
                            add(
                                buildJsonObject {
                                    put("session", session.title)
                                    put("sessionId", session.id)
                                    put("orders", book.orders.size)
                                    put("working", book.working)
                                    putBookHealth(book)
                                },
                            )
                        }
                    },
                )
            }
        }
        val session =
            resolveSession(requested)
                ?: return errorObject("no session '$requested'; known: " + sessions.joinToString { it.title })
        val book =
            session.orderBook()
                ?: return errorObject("'${session.title}' is not an acceptor, so it holds no orders for anyone")
        val only = params["order"]
        val orders = if (only == null) book.orders else book.orders.filter { it.key == only }
        if (only != null && orders.isEmpty()) {
            return errorObject("'${session.title}' holds no order '$only'")
        }
        return buildJsonObject {
            put("session", session.title)
            put("sessionId", session.id)
            put("working", book.working)
            putBookHealth(book)
            put("orders", buildJsonArray { orders.forEach { add(bookedOrderJson(it)) } })
            // The count is the number; this is what those messages were. A count alone says something
            // is wrong and nothing about what — see decision 7.
            if (book.unattributed.isNotEmpty()) {
                put(
                    "unattributedMessages",
                    buildJsonArray {
                        book.unattributed.forEach { entry ->
                            add(
                                buildJsonObject {
                                    put("at", entry.at.toString())
                                    put("msgType", entry.msgType)
                                    put("why", entry.why)
                                    put("raw", entry.raw)
                                },
                            )
                        }
                    },
                )
            }
        }
    }

    /** The three ways a book can be wrong, reported whether or not anything is wrong with it. */
    private fun JsonObjectBuilder.putBookHealth(book: BookView) {
        put("unattributed", book.unattributedCount)
        put("evicted", book.evicted)
        put("cap", book.cap)
        // An empty book reads identically as "nothing has happened" and "somebody cleared it", and
        // those send a reader in opposite directions.
        book.clearedAt?.let {
            put("clearedAt", it.toString())
            put("clearedBy", book.clearedBy ?: "")
        }
    }

    private fun bookedOrderJson(order: BookedOrder): JsonObject {
        val snapshots = order.snapshots()
        val current = order.current
        return buildJsonObject {
            put("clOrdId", order.key)
            current.orderId?.let { put("orderId", it) }
            current.symbol?.let { put("symbol", it) }
            current.side?.let { put("side", it) }
            current.orderQty?.let { put("orderQty", it) }
            current.cumQty?.let { put("cumQty", it) }
            current.leavesQty?.let { put("leavesQty", it) }
            current.avgPx?.let { put("avgPx", it) }
            current.price?.let { put("price", it) }
            current.ordStatus?.let { put("ordStatus", it) }
            put("state", current.state.name.lowercase())
            order.supersedes?.let { put("supersedes", it) }
            order.supersededBy?.let { put("supersededBy", it) }
            order.firstAt?.let { put("firstAt", it.toString()) }
            order.lastAt?.let { put("lastAt", it.toString()) }
            // The trail: what each message did, and what the order looked like after it. This is the
            // difference between a number a reader has to trust and one they can check (decision 6b).
            put(
                "trail",
                buildJsonArray {
                    order.events.forEachIndexed { index, event ->
                        val after = snapshots.getOrNull(index)
                        add(
                            buildJsonObject {
                                put("at", event.at.toString())
                                put("direction", if (event.sent) "sent" else "received")
                                put("msgType", event.msgType)
                                put("label", event.label)
                                event.execType?.let { put("execType", it) }
                                after?.cumQty?.let { put("cumQty", it) }
                                after?.leavesQty?.let { put("leavesQty", it) }
                                after?.orderId?.let { put("orderId", it) }
                                put("state", (after?.state ?: current.state).name.lowercase())
                                put("messageUid", event.messageUid)
                            },
                        )
                    }
                },
            )
        }
    }

    private fun clearAcceptorOrders(ex: HttpExchange): JsonElement {
        val request = readJson(ex)
        val key = request["session"]?.jsonPrimitive?.contentOrNull ?: queryParams(ex)["session"]
        val session = resolveSession(key) ?: return errorObject("no session '${key.orEmpty()}'")
        if (session.orderBook() == null) {
            return errorObject("'${session.title}' is not an acceptor, so it holds no orders to clear")
        }
        onEdt { session.clearOrderBook(by = "the control surface") }
        return buildJsonObject {
            put("status", "cleared")
            put("session", session.title)
        }
    }

    /**
     * Adds, replaces or toggles **one** rule, leaving the rest of the profile untouched.
     *
     * A rule list is ordered and first-match-wins, so its index is its identity — there is nothing
     * else to name a rule by, and the position is itself meaningful. `index` omitted appends;
     * `index` given replaces in place. `enabled` on its own toggles the rule already there, which is
     * the edit an author narrowing down a venue's behaviour makes most often ("what happens without
     * this one") and the one they least want to spell out a whole rule for.
     *
     * This exists even though `/profiles` now merges, because merging is per top-level key: the rule
     * *list* is one key, so adding a rule through it still means sending every other rule back, read
     * from somewhere else and hoping nothing changed in between.
     */
    private fun upsertAcceptorRule(ex: HttpExchange): JsonElement {
        val body = readJson(ex)
        val profileKey = body["profile"]?.jsonPrimitive?.content
            ?: queryParams(ex)["profile"]
            ?: return errorObject("missing 'profile'")
        val index = body["index"]?.jsonPrimitive?.intOrNull
        val enabled = body["enabled"]?.jsonPrimitive?.booleanOrNull
        val ruleElement = body["rule"] as? JsonObject
        val presetId = body["preset"]?.jsonPrimitive?.content
        // Where a preset goes is part of what it is — a conditioned rule appended below an
        // unconditioned one for the same MsgType can never fire. Naming the conflict beats honouring
        // one of the two and leaving the caller to discover which.
        if (presetId != null && index != null) {
            return errorObject("a preset chooses its own position so it can fire; drop 'index'")
        }

        return onEdt {
            val profile =
                viewModel.connectionProfiles.firstOrNull { it.id == profileKey || it.name == profileKey }
                    ?: return@onEdt errorObject("unknown profile: $profileKey")
            val rules = profile.config.acceptorResponseRules.toMutableList()
            if (index != null && index !in rules.indices && ruleElement == null) {
                return@onEdt errorObject("index $index is out of range (the profile has ${rules.size} rule(s))")
            }
            var placement: AcceptorPresets.Insertion? = null
            val edit =
                when {
                    presetId != null -> {
                        val preset =
                            AcceptorPresets.byId(presetId)
                                ?: return@onEdt errorObject(
                                    "unknown preset: $presetId (known: ${AcceptorPresets.ids.joinToString(", ")})",
                                )
                        // `enabled:false` alongside a preset stages a venue without arming it, the same
                        // override the `rule` branch offers.
                        val staged =
                            if (enabled == null) preset else preset.copy(rules = preset.rules.map { it.copy(enabled = enabled) })
                        val insertion = AcceptorPresets.insert(rules, staged)
                        rules.clear()
                        rules.addAll(insertion.rules)
                        placement = insertion
                        "added" to insertion.index
                    }
                    ruleElement != null -> {
                        val parsed =
                            try {
                                profileJson.decodeFromJsonElement(AcceptorResponseRule.serializer(), ruleElement)
                            } catch (e: Exception) {
                                return@onEdt errorObject("invalid rule: ${e.message}")
                            }
                        // `enabled` alongside a rule body is an override, so one call can add a rule
                        // already switched off — an author staging the next case without arming it yet.
                        placeRule(rules, if (enabled == null) parsed else parsed.copy(enabled = enabled), index)
                    }
                    enabled != null && index != null -> {
                        rules[index] = rules[index].copy(enabled = enabled)
                        (if (enabled) "enabled" else "disabled") to index
                    }
                    else ->
                        return@onEdt errorObject("provide 'rule' (with optional 'index'), or 'index' with 'enabled'")
                } ?: return@onEdt errorObject(
                    "index $index is out of range (the profile has ${rules.size} rule(s))",
                )
            val (action, position) = edit
            val updated = profile.copy(config = profile.config.copy(acceptorResponseRules = rules))
            if (!viewModel.saveConnectionProfile(updated)) {
                return@onEdt errorObject("failed to persist profile")
            }
            buildJsonObject {
                put("status", action)
                put("profile", profile.name)
                put("index", position)
                put("ruleCount", rules.size)
                placement?.let { inserted ->
                    put("preset", presetId)
                    put("rulesAdded", inserted.added)
                    // Said whenever a rule had to go anywhere but the end, because a caller that
                    // assumed "append" would otherwise read the wrong index back as a coincidence.
                    inserted.placedAbove?.let { above ->
                        put("placedAbove", above)
                        put("placedBecause", "rule ${above + 1} also answers 35=${rules[position].whenMsgType}")
                    }
                }
                liveAcceptorSessions(updated)?.let { put("appliedToLiveSessions", it) }
                rules[position].validationError()?.let { put("validationError", it) }
                AcceptorResponder.shadowingRule(rules, position)?.let { put("shadowedBy", it) }
            }
        }
    }

    /**
     * Puts [rule] into [rules] — appended when [index] is null, replacing that position otherwise —
     * and reports what it did. Null means the index does not exist, which is refused rather than
     * quietly appended: a replace that silently becomes an append leaves the rule it was meant to
     * correct still in place and still winning.
     */
    private fun placeRule(
        rules: MutableList<AcceptorResponseRule>,
        rule: AcceptorResponseRule,
        index: Int?,
    ): Pair<String, Int>? =
        when {
            index == null -> {
                rules += rule
                "appended" to rules.lastIndex
            }
            index in rules.indices -> {
                rules[index] = rule
                "replaced" to index
            }
            else -> null
        }

    /** Removes one rule by index. The rules after it shift up, which changes what first-match-wins means. */
    private fun deleteAcceptorRule(ex: HttpExchange): JsonElement {
        val body = readJson(ex)
        val profileKey = body["profile"]?.jsonPrimitive?.content
            ?: queryParams(ex)["profile"]
            ?: return errorObject("missing 'profile'")
        val index = body["index"]?.jsonPrimitive?.intOrNull
            ?: queryParams(ex)["index"]?.toIntOrNull()
            ?: return errorObject("missing 'index'")
        return onEdt {
            val profile =
                viewModel.connectionProfiles.firstOrNull { it.id == profileKey || it.name == profileKey }
                    ?: return@onEdt errorObject("unknown profile: $profileKey")
            val rules = profile.config.acceptorResponseRules.toMutableList()
            if (index !in rules.indices) {
                return@onEdt errorObject("index $index is out of range (the profile has ${rules.size} rule(s))")
            }
            val removed = rules.removeAt(index)
            val updated = profile.copy(config = profile.config.copy(acceptorResponseRules = rules))
            if (!viewModel.saveConnectionProfile(updated)) {
                return@onEdt errorObject("failed to persist profile")
            }
            buildJsonObject {
                put("status", "deleted")
                put("profile", profile.name)
                put("index", index)
                put("whenMsgType", removed.whenMsgType)
                put("ruleCount", rules.size)
            }
        }
    }

    /**
     * Inspects the acceptor auto-response rules on a profile, and the latency they are played through.
     */
    private fun acceptorRules(ex: HttpExchange): JsonElement {
        val profileKey = queryParams(ex)["profile"] ?: return errorObject("missing 'profile'")
        return onEdt {
            val profile =
                viewModel.connectionProfiles.firstOrNull { it.id == profileKey || it.name == profileKey }
                    ?: return@onEdt errorObject("unknown profile: $profileKey")
            buildJsonObject {
                put("profile", profile.name)
                put("connectionType", profile.config.connectionType.name)
                // Rules on an initiator are inert — nothing reads them — and that is invisible from the
                // rules alone, which look configured and correct. Said here because this is the surface
                // someone asks "why does my rule never fire" of.
                if (profile.config.connectionType != FixConnectionConfig.ConnectionType.ACCEPTOR) {
                    put(
                        "inactive",
                        "this profile is an ${profile.config.connectionType.name}; auto-response rules " +
                            "only run when connectionType is ACCEPTOR",
                    )
                }
                put("latency", latencyJson(profile.config.acceptorLatency))
                put(
                    "rules",
                    buildJsonArray {
                        profile.config.acceptorResponseRules.forEachIndexed { ruleIndex, rule ->
                            add(
                                acceptorRuleJson(
                                    ruleIndex,
                                    rule,
                                    AcceptorResponder.shadowingRule(profile.config.acceptorResponseRules, ruleIndex),
                                ),
                            )
                        }
                    },
                )
            }
        }
    }

    /** One rule as it reads: what was written, what it means, and anything wrong with it. */
    private fun acceptorRuleJson(index: Int, rule: AcceptorResponseRule, shadowedBy: Int? = null): JsonObject =
        buildJsonObject {
            // The index is the rule's identity for /acceptor/rules POST and DELETE, and its priority
            // under first-match-wins. Both need it named.
            put("index", index)
            put("whenMsgType", rule.whenMsgType)
            put("enabled", rule.enabled)
            put("whenFields", buildJsonObject { rule.whenFields.forEach { (k, v) -> put(k, v) } })
            // `trigger` is every condition from both spellings, ANDed, exactly as the engine will ask
            // them — so a reader never has to work out which of the two forms a rule used, or whether
            // they combine.
            put(
                "trigger",
                buildJsonArray {
                    rule.trigger().forEach { condition ->
                        add(
                            buildJsonObject {
                                put("tag", condition.tag)
                                put("matcher", condition.matcher)
                                condition.reason()?.let { put("problem", it) }
                            },
                        )
                    }
                },
            )
            // The one condition no tag can express, so it cannot live in `trigger` above. Named in the
            // same words the rule was written in, and only when the rule asks — an absent key is a rule
            // that does not read the book, which is different from one that asks for `unknown`.
            rule.whenOrder?.let { put("whenOrder", it.word) }
            put("responseTemplate", rule.responseTemplate)
            // The reply as it will actually be played, with the offset each step goes out at — a reader
            // asking "what does this rule do" should not have to re-do the accumulation, nor work out
            // which of the two spellings won. `steps` echoes what was written; `sequence` resolves it.
            put(
                "steps",
                buildJsonArray {
                    rule.steps.forEach { step ->
                        add(
                            buildJsonObject {
                                put("template", step.template)
                                put("delayMillis", step.delayMillis)
                            },
                        )
                    }
                },
            )
            var offset = 0L
            put(
                "sequence",
                buildJsonArray {
                    rule.sequence().forEach { step ->
                        offset += step.delayMillis.coerceAtLeast(0)
                        add(
                            buildJsonObject {
                                put("template", step.template)
                                put("offsetMillis", offset)
                            },
                        )
                    }
                },
            )
            // A rule nobody can act on is worse than no rule: it looks configured. There is no
            // authoring UI to catch this, so the read surface is the only place it can be said.
            rule.validationError()?.let { put("validationError", it) }
            // The other way a well-formed rule does nothing: an earlier one already answers every
            // message of this type, so this one is unreachable. Named the same as the dry run's field
            // because it is the same fact — this one just did not need a message to establish it.
            shadowedBy?.let { put("shadowedBy", it) }
        }

    /**
     * The simulated venue latency, as configured, plus what it will actually do.
     *
     * [AcceptorLatencyConfig] has been settable since it shipped — a `/profiles` POST decodes the whole
     * config — but no read surface mentioned it and no tool description named it, so nothing could
     * discover it existed. A delay nobody knows about is worse than no delay: it is an unexplained
     * gap between a trigger and its reply, in the exact data a latency test reads.
     *
     * [addedMillis] is the trigger→first-reply delay this config produces, as a range, because it is
     * usually a draw and a single number would be a fiction. It does **not** include the authored
     * step-to-step gaps, which sit on top of it.
     */
    private fun latencyJson(latency: AcceptorLatencyConfig): JsonObject =
        buildJsonObject {
            put("active", latency.isActive())
            put("mode", latency.mode.name)
            put("config", profileJson.encodeToJsonElement(AcceptorLatencyConfig.serializer(), latency))
            val ordinary =
                when (latency.mode) {
                    AcceptorLatencyConfig.Mode.NONE -> 0L to 0L
                    AcceptorLatencyConfig.Mode.FIXED -> latency.fixedMillis to latency.fixedMillis
                    AcceptorLatencyConfig.Mode.RANDOM_RANGE -> latency.minMillis to latency.maxMillis
                    // A normal draw has no bound, so a range would be a lie either way; ±2 standard
                    // deviations is named as such rather than presented as a limit.
                    AcceptorLatencyConfig.Mode.NORMAL ->
                        (latency.meanMillis - 2 * latency.stdDevMillis).coerceAtLeast(0) to
                            (latency.meanMillis + 2 * latency.stdDevMillis)
                }
            // A spike replaces the ordinary sample rather than adding to it, so it widens the range
            // rather than shifting it — see AcceptorLatencyConfig.sample.
            val spiking = latency.spikeProbability > 0.0
            val low = if (spiking) minOf(ordinary.first, latency.spikeMinMillis) else ordinary.first
            val high = if (spiking) maxOf(ordinary.second, latency.spikeMaxMillis) else ordinary.second
            put(
                "addedMillis",
                buildJsonObject {
                    put("min", low.coerceAtLeast(0))
                    put("max", high.coerceAtLeast(0))
                    if (latency.mode == AcceptorLatencyConfig.Mode.NORMAL) {
                        put("basis", "mean ± 2 standard deviations; unbounded")
                    }
                },
            )
            if (latency.spikeProbability > 0.0) {
                put(
                    "spike",
                    "${(latency.spikeProbability * PERCENT).toInt()}% of replies stall to " +
                        "${latency.spikeMinMillis}-${latency.spikeMaxMillis}ms instead of the ordinary sample",
                )
            }
            latency.validationError()?.let { put("validationError", it) }
        }

    /**
     * **Answers "what would this acceptor do with this message" without a counterparty.**
     *
     * Authoring a rule otherwise costs a full round trip through reality: save the profile, connect,
     * arrange for someone to send the trigger, then read the message list. And when nothing comes
     * back, that round trip has told you only that nothing came back — which is equally the shape of
     * a typo'd tag, a condition that reads a field the message does not carry, a rule shadowed by an
     * earlier one, a rule switched off, and a rule on a profile that is not even an acceptor. Every
     * one of those has a different fix and none of them is visible from the outcome.
     *
     * So this reports every rule's verdict and its working: which conditions passed, **what value each
     * one actually read**, which rule wins, and the reply it would play with each step's offset. The
     * evaluation is [AcceptorResponder.explain] and the reply is [AcceptorResponder.plan] — the same
     * functions the live path uses, so a dry run cannot pass where the wire would fail.
     *
     * Nothing is sent and nothing is saved; the profile is read as it stands on disk, which is also
     * why a dry run and a *connected* session can disagree — rules compile when a session connects.
     */
    private fun acceptorTest(ex: HttpExchange): JsonElement {
        val body = readJson(ex)
        val profileKey = body["profile"]?.jsonPrimitive?.content ?: return errorObject("missing 'profile'")
        val raw = body["raw"]?.jsonPrimitive?.content ?: return errorObject("missing 'raw'")
        if (raw.isBlank()) return errorObject("'raw' is empty")

        val profile =
            onEdt { viewModel.connectionProfiles.firstOrNull { it.id == profileKey || it.name == profileKey } }
                ?: return errorObject("unknown profile: $profileKey")
        val dictionary = onEdt { viewModel.dictionary }

        val incoming =
            try {
                AcceptorResponder.buildMessage(raw)
            } catch (e: Exception) {
                return errorObject("could not parse 'raw' as a FIX message: ${e.message}")
            }
        val request = asRequest(raw, incoming)
        val assumedWord = body["orderState"]?.jsonPrimitive?.contentOrNull
        val assumed =
            assumedState(assumedWord, incoming)
                ?: return errorObject(
                    "'$assumedWord' is not an order state; known: ${OrderConstraint.words.joinToString(", ")}",
                )

        val assumedOrder = assumedOrder(body)

        val outcomes = AcceptorResponder.explain(profile.config.acceptorResponseRules, incoming, assumed)
        val winner = outcomes.firstOrNull { it.selected }
        val incomingType = request.messageType ?: ""

        return buildJsonObject {
            put("profile", profile.name)
            put("connectionType", profile.config.connectionType.name)
            put("assumedOrderState", assumedStateJson(assumed, given = assumedWord != null))
            putIfNotAcceptor(profile)
            put("msgType", incomingType)
            put("matched", winner != null)
            put("latency", latencyJson(profile.config.acceptorLatency))
            put(
                "rules",
                buildJsonArray {
                    outcomes.forEach { outcome -> add(ruleOutcomeJson(outcome, outcomes, incomingType)) }
                },
            )
            winner?.let { selected ->
                put("response", plannedReplyJson(selected.rule, incoming, request, dictionary, assumedOrder))
                put(
                    "note",
                    "offsets are from the trigger and exclude simulated latency, which is drawn once per " +
                        "trigger and shifts the whole reply; \${uuid} and \${now} are resolved per step as it is sent",
                )
                // Said only when it is true, and it is the difference between "your rule is broken"
                // and "this dry run had nothing to read". A live venue reads its own book per step.
                if (selected.rule.readsTheBook() && assumedOrder == null) {
                    put(
                        "orderNote",
                        "this reply reads \${order.…} and no 'order' was given, so the steps that read it are " +
                            "reported unrendered; pass order:{${OrderBook.names.take(3).joinToString(", ")}, …} " +
                            "to see what would be sent",
                    )
                }
            }
        }
    }

    /**
     * **A dry run of a stateful trigger has to name the state it assumed, and take one.**
     *
     * Once a rule can read the book, "what would this acceptor do with this message" stops being a
     * question about the profile and the message alone: it is a question about a venue in some state,
     * and the answer changes with it. So [word] is that assumption, and `unknown` is the default —
     * a real venue state (one that has never seen this order), not a stand-in for "not asked".
     *
     * Keyed by whatever the message names, so what comes back says which order was assumed *about*
     * and not merely that something was. Null when [word] is not one of the four.
     */
    private fun assumedState(word: String?, incoming: quickfix.Message): BookReading? {
        val constraint = word?.let { OrderConstraint.byWord(it) ?: return null } ?: OrderConstraint.UNKNOWN
        return BookReading(
            key = OrderBook.namedKeys(OrderBookService.fieldsOf(incoming), BookSpec.ORDERS).firstOrNull(),
            state = if (constraint == OrderConstraint.UNKNOWN) null else OrderState.valueOf(constraint.name),
        )
    }

    /**
     * Rules on an initiator are inert and that is invisible from the rules themselves, which look
     * configured and correct. Said on the dry run because this is the surface someone asks "why does
     * my rule never fire" of.
     */
    private fun JsonObjectBuilder.putIfNotAcceptor(profile: FixConnectionProfile) {
        if (profile.config.connectionType == FixConnectionConfig.ConnectionType.ACCEPTOR) return
        put(
            "inactive",
            "this profile is an ${profile.config.connectionType.name}; the rules below are " +
                "evaluated for you but would never run — auto-responses need connectionType ACCEPTOR",
        )
    }

    /**
     * The tested message as the *request* the engine reads.
     *
     * The expression pass reaches the triggering message through a [FixMessage], exactly as the live
     * path hands it the one QuickFIX just delivered — so `${req.38 / 2}` computes here too, and a dry
     * run cannot quietly differ from the wire in the one place authors most rely on it.
     */
    private fun asRequest(raw: String, incoming: quickfix.Message): FixMessage =
        FixMessage(
            timestamp = java.time.LocalDateTime.now(),
            direction = FixMessage.Direction.INCOMING,
            rawMessage = raw,
            quickfixMessage = incoming,
        )

    /**
     * The order a dry run renders `${order.…}` against, or null if the caller supplied none.
     *
     * The other half of [assumedState]'s argument: that one says what a *trigger* would read, this
     * says what a *reply* would substitute. A dry run of a template that reads the book needs both,
     * or it can only report that it could not render. Taken as the book's own names, so the caller
     * writes what the template writes — see `OrderBook.fields`.
     */
    private fun assumedOrder(body: JsonObject): Map<String, String>? =
        (body["order"] as? JsonObject)
            ?.mapNotNull { (name, value) -> value.jsonPrimitive.contentOrNull?.let { name to it } }
            ?.toMap()

    /** The assumption, reported back whether or not it was given — see [assumedState]. */
    private fun assumedStateJson(assumed: BookReading, given: Boolean): JsonObject =
        buildJsonObject {
            put("state", assumed.word)
            assumed.key?.let { put("order", it) }
            put("given", given)
            if (!given) {
                put(
                    "note",
                    "no 'orderState' was given, so the rules were judged against a venue that has never " +
                        "seen this order; pass one of ${OrderConstraint.words.joinToString(", ")} to ask " +
                        "what would happen in another state",
                )
            }
        }

    /**
     * The whole reply [rule] would play, rendered as the wire would carry it.
     *
     * Rendered, not described: `${req.<tag>}` is already filled in from the message that triggered it,
     * so what is shown is what would be sent. `${uuid}` and `${now}` resolve per step as it goes out,
     * so a real reply differs from this in exactly those two and nowhere else.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun plannedReplyJson(
        rule: AcceptorResponseRule,
        incoming: quickfix.Message,
        request: FixMessage,
        dictionary: FixDictionary?,
        /** The order to render `${order.…}` against, or null when the caller supplied none. */
        order: Map<String, String>?,
    ): JsonArray =
        buildJsonArray {
            val steps = rule.sequence()
            AcceptorResponder.plan(rule, incoming, request, dictionary) { order }.forEachIndexed { index, planned ->
                add(
                    buildJsonObject {
                        put("offsetMillis", planned.offsetMillis)
                        // A step reading the book with no book to read refuses rather than rendering
                        // `37=`, and here that refusal *is* the answer: the dry run says which step
                        // could not be built and why, instead of showing a message the wire would
                        // never carry. Passing `order` is how a caller gets the rendering.
                        try {
                            put("message", planned.render().replace(SOH, '|'))
                        } catch (e: Exception) {
                            put("unrendered", e.message ?: "this step could not be built")
                            steps.getOrNull(index)?.let { put("template", it.template) }
                        }
                    },
                )
            }
        }

    /** One rule's verdict on the tested message, with the working that produced it. */
    private fun ruleOutcomeJson(
        outcome: RuleOutcome,
        all: List<RuleOutcome>,
        incomingType: String,
    ): JsonObject =
        buildJsonObject {
            put("index", outcome.index)
            put("whenMsgType", outcome.rule.whenMsgType)
            put("matched", outcome.matched)
            put("selected", outcome.selected)
            outcome.skipped?.let { put("skipped", it) }
            // A rule that matches but does not fire has been beaten by an earlier one. That is the
            // failure that looks least like itself, so it is named rather than left to be inferred
            // from two booleans.
            if (outcome.matched && !outcome.selected) {
                put("shadowedBy", all.first { it.selected }.index)
            }
            if (outcome.skipped == null && outcome.rule.whenMsgType != incomingType) {
                put("mismatch", "MsgType is $incomingType, the rule wants ${outcome.rule.whenMsgType}")
            }
            put(
                "conditions",
                buildJsonArray {
                    outcome.conditions.forEach { condition ->
                        add(
                            buildJsonObject {
                                put("tag", condition.tag)
                                put("matcher", MatcherCodec.matcherToJson(condition.matcher))
                                put("satisfied", condition.satisfied)
                                // Null and empty-string are different answers here: one is "the message
                                // has no such tag", the other is "it has it and it is blank". Conflating
                                // them hides the commonest typo.
                                if (condition.actual == null) {
                                    put("actual", JsonNull)
                                    put("absent", true)
                                } else {
                                    put("actual", condition.actual)
                                }
                            },
                        )
                    }
                },
            )
            // The book constraint reports exactly as a tag condition does: what was asked, what was
            // read, and the verdict. Same shape on purpose — a reader diagnosing "why did nothing
            // fire" should not have to learn a second way of being told.
            outcome.order?.let { order ->
                put(
                    "whenOrder",
                    buildJsonObject {
                        put("constraint", order.constraint.word)
                        order.key?.let { put("order", it) }
                        order.actual?.let { put("actual", it) }
                        put("satisfied", order.satisfied)
                    },
                )
            }
            outcome.rule.validationError()?.let { put("validationError", it) }
        }

    /** Validates a raw FIX message against the loaded data dictionary. */
    private fun validate(ex: HttpExchange): JsonElement {
        val raw = readJson(ex)["raw"]?.jsonPrimitive?.content ?: return errorObject("missing 'raw'")
        val result = onEdt { FixMessageValidator.validate(raw, viewModel.dictionary) }
        return buildJsonObject {
            put("isValid", result.isValid)
            put("errors", buildJsonArray { result.errors.forEach { add(it) } })
            if (result.warnings.isNotEmpty()) {
                put("warnings", buildJsonArray { result.warnings.forEach { add(it) } })
            }
        }
    }

    private fun dictionaryEndpoint(ex: HttpExchange): JsonElement =
        if (ex.requestMethod.uppercase() == "POST") setDictionary(ex) else getDictionary()

    /**
     * **The venue's own tags, and what the author has said about them** — `GET` to list, `POST` to declare.
     *
     * The same list and the same write path the settings editor uses, so an agent and a human cannot
     * produce different files. `POST {"roles": {"20001": "CLIENT_MINTED_ID"}}` replaces the declaration
     * wholesale (a role map is small and a merge semantics nobody can see is worse than a replace they
     * can), writes the sidecar beside the loaded dictionary, and reloads so the next capture uses it.
     */
    private fun dictionaryRoles(ex: HttpExchange): JsonElement =
        if (ex.requestMethod.uppercase() == "POST") setDictionaryRoles(ex) else getDictionaryRoles()

    private fun getDictionaryRoles(): JsonElement =
        onEdt {
            val dictionary = viewModel.dictionary
            buildJsonObject {
                put("path", dictionary?.getFilePath())
                put("sidecar", dictionary?.getFilePath()?.let { TagRoleOverlay.sidecarFor(it).absolutePath })
                put("summary", VenueTagScan.summary(dictionary))
                put(
                    "tags",
                    buildJsonArray {
                        VenueTagScan.scan(dictionary).forEach { c ->
                            add(
                                buildJsonObject {
                                    put("tag", c.tag)
                                    put("name", c.name)
                                    put("tier", c.tier.name)
                                    put("roles", buildJsonArray { c.roles.forEach { add(it.name) } })
                                    // Standard-or-venue and what FixTool already answers: an agent deciding
                                    // whether a tag needs declaring needs the same two facts the dialog puts
                                    // on the row, or it re-declares what is already handled.
                                    put("custom", c.custom)
                                    put("builtIn", buildJsonArray { c.builtIn.forEach { add(it.name) } })
                                    c.builtInReason?.let { put("builtInReason", it) }
                                },
                            )
                        }
                    },
                )
            }
        }

    private fun setDictionaryRoles(ex: HttpExchange): JsonElement {
        val body = readJson(ex)
        val roles = body["roles"]?.jsonObject ?: return errorObject("missing 'roles' object")
        val path = onEdt { viewModel.dictionary?.getFilePath() }
            ?: return errorObject(
                "no dictionary file is loaded — venue tag roles live beside the venue's dictionary, and a " +
                    "bundled standard dictionary has no venue tags to declare",
            )
        val parsed = mutableMapOf<Int, Set<TagRole>>()
        val refused = mutableListOf<String>()
        for ((key, value) in roles) {
            val tag = key.trim().toIntOrNull()
            if (tag == null) {
                refused += "'$key' is not a tag number"
                continue
            }
            val names =
                runCatching { value.jsonArray.map { it.jsonPrimitive.content } }
                    .getOrElse { listOf(value.jsonPrimitive.content) }
            val set = names.mapNotNull { TagRole.parse(it) }.toSet()
            if (set.size != names.size) refused += "tag $tag: unknown role in ${names.joinToString(", ")}"
            if (set.isNotEmpty()) parsed[tag] = set
        }
        if (refused.isNotEmpty()) {
            return buildJsonObject {
                put("status", "refused")
                put("refused", buildJsonArray { refused.forEach { add(it) } })
                put("known", buildJsonArray { TagRole.entries.forEach { add(it.name) } })
            }
        }
        val file = TagRoleOverlay.writeBeside(path, parsed)
        // The adapter caches its overlay, so the declaration only reaches capture once it is invalidated.
        // Reporting "saved" over a dictionary still using the old roles would be success for a change that
        // does not take effect until the next launch. See [FixDictionaryAdapter.reloadTagRoles].
        onEdt { viewModel.dictionary?.reloadTagRoles() }
        return buildJsonObject {
            put("status", "saved")
            put("sidecar", file.absolutePath)
            put("declared", parsed.size)
            put("summary", onEdt { VenueTagScan.summary(viewModel.dictionary) })
        }
    }

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
            val bytes =
                captureWindowPng(queryParams(ex)["window"]) ?: run {
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
     * main one; any other value matches a window whose title contains it (so a specific **scenario** can be
     * targeted). Only `Frame`s (the app's real windows) are considered, which also skips heavyweight popups
     * and tooltips.
     *
     * **A step can no longer be targeted this way**, and callers should know it rather than find out. A
     * scenario has exactly one reconcile window and the step in its title is whichever one it is *showing*, so
     * `?window=Step 3` now means "the window that happens to be on step 3 at this instant" — a match that can
     * change under a caller that did nothing. Address a scenario; drive the step through the reconcile API.
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
            "fixtool_profiles" to { a ->
                a["profile"]?.jsonPrimitive?.contentOrNull?.let { profileDetail(it) } ?: profiles()
            },
            "fixtool_save_profile" to { a -> upsertProfile(mcpExchange(a)) },
            "fixtool_delete_profile" to { a -> deleteProfile(mcpExchange(a)) },
            "fixtool_panel" to { a -> panel(mcpExchange(a)) },
            "fixtool_list_templates" to { a -> listTemplates(mcpExchange(a)) },
            "fixtool_save_template" to { a -> upsertTemplate(mcpExchange(a)) },
            "fixtool_delete_template" to { a -> deleteTemplate(mcpExchange(a)) },
            "fixtool_load_template" to { a -> loadTemplate(mcpExchange(a)) },
            "fixtool_demo" to { a -> demo(mcpExchange(a)) },
            "fixtool_workspace" to { a -> workspace(mcpExchange(a)) },
            "fixtool_connect" to { a -> connect(mcpExchange(a)) },
            "fixtool_disconnect" to { a -> disconnect(mcpExchange(a)) },
            "fixtool_send" to { a -> send(mcpExchange(a)) },
            "fixtool_send_all" to { a -> sendAll(mcpExchange(a)) },
            "fixtool_send_template" to { a -> sendTemplate(mcpExchange(a)) },
            "fixtool_clear_messages" to { a -> clearMessages(mcpExchange(a)) },
            "fixtool_wait" to { a -> waitFor(mcpExchange(a)) },
            "fixtool_get_messages" to { a -> messages(mcpExchange(a)) },
            "fixtool_search" to { a -> search(mcpExchange(a)) },
            "fixtool_traces" to { _ -> traces() },
            // MCP has no status codes either, so the 400/404 is the body — as `fixtool_run_scenario` does.
            "fixtool_trace" to { a -> trace(mcpExchange(a)).body },
            "fixtool_filter" to { a -> filter(mcpExchange(a)) },
            "fixtool_select" to { a -> select(mcpExchange(a)) },
            "fixtool_assert" to { a -> assertMessage(mcpExchange(a)) },
            "fixtool_capture_expectation" to { a -> captureExpectation(mcpExchange(a)) },
            "fixtool_save_scenario" to { a -> saveScenario(mcpExchange(a)) },
            "fixtool_capture_scenario" to { a -> captureScenario(mcpExchange(a)) },
            "fixtool_capture_paste" to { a -> capturePaste(mcpExchange(a)) },
            "fixtool_list_scenarios" to { a -> listScenarios(mcpExchange(a)) },
            "fixtool_get_scenario" to { a -> getScenario(mcpExchange(a)) },
            // MCP has no status codes, so a job's answer is its body — 202/409 are an HTTP nicety.
            "fixtool_run_scenario" to { a -> runScenario(mcpExchange(a)).body },
            "fixtool_run_set" to { a -> startRunSet(a).body },
            "fixtool_run_status" to { a -> runStatusTool(a) },
            "fixtool_run_entry" to { a -> runEntryTool(a) },
            "fixtool_load" to { a -> startLoad(a).body },
            "fixtool_load_status" to { a -> loadStatusTool(a) },
            "fixtool_reconcile" to { a -> reconcile(mcpExchange(a)) },
            "fixtool_diff" to { a -> diffMessages(mcpExchange(a)) },
            "fixtool_delete_scenario" to { a -> deleteScenario(mcpExchange(a)) },
            "fixtool_detail_search" to { a -> detailSearch(mcpExchange(a)) },
            "fixtool_admin" to { a -> admin(mcpExchange(a)) },
            "fixtool_validate" to { a -> validate(mcpExchange(a)) },
            "fixtool_dictionary" to { a -> if (a.isEmpty()) getDictionary() else setDictionary(mcpExchange(a)) },
            "fixtool_acceptor_rules" to { a -> acceptorRules(mcpExchange(a)) },
            "fixtool_acceptor_presets" to { _ -> acceptorPresets() },
            "fixtool_acceptor_rule" to { a ->
                // One tool, both directions, because "delete" is a mode of editing a rule list and a
                // separate tool for it would be a fourth thing to find. `delete:true` is explicit so
                // removal can never be what a malformed edit degrades into.
                if (a["delete"]?.jsonPrimitive?.booleanOrNull == true) {
                    deleteAcceptorRule(mcpExchange(a))
                } else {
                    upsertAcceptorRule(mcpExchange(a))
                }
            },
            "fixtool_acceptor_test" to { a -> acceptorTest(mcpExchange(a)) },
            // Reading the book is a GET; clearing it is the same tool with `clear:true`, for the same
            // reason `fixtool_acceptor_rule` carries its own `delete` — a second tool is a second
            // thing to find, and an explicit flag is not something a malformed read can become.
            "fixtool_acceptor_orders" to { a ->
                if (a["clear"]?.jsonPrimitive?.booleanOrNull == true) {
                    clearAcceptorOrders(mcpExchange(a))
                } else {
                    acceptorOrders(mcpExchange(a))
                }
            },
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
        val clientVersion =
            request["params"]
                ?.jsonObject
                ?.get("protocolVersion")
                ?.jsonPrimitive
                ?.content
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

    /**
     * A response that chooses its own status code.
     *
     * Everything else here answers 200 and puts `status: "error"` in the body, which agents have learned
     * to read. The job API cannot: "the set started" and "somebody else is already running" are the two
     * answers a caller has to branch on before it has a body to parse, and 202/409 are the words HTTP
     * already has for them.
     */
    private data class Coded(val code: Int, val body: JsonElement)

    private fun handleCoded(ex: HttpExchange, block: () -> Coded) {
        try {
            if (!authorized(ex)) {
                respondJson(ex, HTTP_UNAUTHORIZED, errorObject("unauthorized"))
                return
            }
            val coded = block()
            respondJson(ex, coded.code, coded.body)
        } catch (e: Exception) {
            logger.error("Control request failed: ${ex.requestURI}", e)
            respondJson(ex, HTTP_SERVER_ERROR, errorObject(e.message ?: e.toString()))
        } finally {
            ex.close()
        }
    }

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

    /** One plan row, in the sheet's own words — `describe` is the same text the preview draws. */
    private fun planFixJson(fix: ScenarioReconcile.PlannedFix): JsonObject =
        buildJsonObject {
            put("index", fix.index)
            put("tag", fix.tag)
            if (fix.name.isNotEmpty()) put("name", fix.name)
            put(
                "class",
                when (fix.klass) {
                    ScenarioReconcile.FixClass.NUMERIC -> "numeric"
                    ScenarioReconcile.FixClass.TEMPORAL -> "temporal"
                    ScenarioReconcile.FixClass.ONE_OF -> "oneOf"
                    ScenarioReconcile.FixClass.REGEX -> "regex"
                    ScenarioReconcile.FixClass.PRESENCE -> "presence"
                },
            )
            put("current", ExpectationEvaluator.describe(fix.current))
            put("proposed", ExpectationEvaluator.describe(fix.proposed))
            put("reason", fix.reason)
            put("repairs", fix.repairs)
            put("defaultChecked", fix.defaultChecked)
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
        /** The port the bundled FX venue example binds, named so `/demo` can report it. */
        const val DEMO_VENUE_PORT = 19876

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

        /**
         * What a redacted secret reads as, and what a caller may post back to mean "unchanged".
         *
         * One token for both directions on purpose: the value `GET` hands out is the value `POST`
         * ignores, so a read-modify-write of a whole config is safe without the caller having to know
         * which of its keys were secrets.
         */
        internal const val REDACTED = "[REDACTED]"

        /** Config keys never echoed by a read. See [REDACTED]. */
        internal val SECRET_CONFIG_KEYS = setOf("password", "keyStorePassword", "trustStorePassword")

        /** FIX's field delimiter, swapped for `|` when a raw message is reported for reading. */
        private const val SOH = '\u0001'
        private const val PERCENT = 100

        private const val HTTP_OK = 200
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_SERVER_ERROR = 500
        private const val HTTP_METHOD_NOT_ALLOWED = 405
        private const val HTTP_NO_CONTENT = 204
        private const val DEFAULT_MESSAGE_LIMIT = 50
        private const val HTTP_POOL_SIZE = 4
        private const val DEFAULT_WAIT_MS = 10_000L
        private const val MAX_WAIT_MS = 120_000L
        private const val WAIT_POLL_MS = 100L

        /** One sentence, two doors — and the 409 is decided by comparing against it. */

        private const val HTTP_ACCEPTED = 202
        private const val HTTP_CONFLICT = 409

        /**
         * The ceiling on a run-set poll's `?wait=`. Ten seconds, because the MCP shim aborts a call at
         * fifteen and an agent that waits past its own transport's patience learns nothing.
         */
        private const val MAX_SET_WAIT_MS = 10_000L
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
