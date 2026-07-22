package com.knapsack.fixtool.integration

import com.knapsack.fixtool.control.ControlServer
import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.FixConnectionState
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.service.FixMessageHelper
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Capture must not change the bytes. These tests hold the capture doors (toolbar and paste) to the
 * three promises the rest of the surface already keeps:
 *
 * 1. **A captured Send replays the bytes that were sent.** `58=filled|in full` is legal FIX and the
 *    read side goes to great lengths to keep it ([WirePaste], [FixMessageHelper.wireFields]); a Send
 *    step that re-encodes it ambiguously replays a message the client never sent.
 * 2. **Direction is never guessed.** A CompID pair that cannot settle it (sender == target) must
 *    refuse the save, not mark every reply outgoing.
 * 3. **Admin messages are skipped** — the class contract — by the paste door too, and *reported*,
 *    because a paste leaving lines out silently is the same lie as a capture omitting a reply.
 */
class CaptureFidelityIntegrationTest {
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var server: ControlServer
    private lateinit var testDir: File
    private var port = 0

    private val client: HttpClient = HttpClient.newHttpClient()
    private val baseUrl get() = "http://127.0.0.1:$port"
    private val runId = System.nanoTime().toString().takeLast(8)

    @Before
    fun setup() {
        testDir = File.createTempFile("fixtool-capture-fidelity", "").apply { delete(); mkdirs() }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
        port = freePort()
        server = ControlServer(port, viewModel, windowProvider = { emptyList() }, token = null)
        server.start()
    }

    @After
    fun cleanup() {
        server.stop()
        viewModel.disconnectAllSessions()
        awaitCondition { viewModel.sessions.all { it.connectionState.value != FixConnectionState.LOGGED_ON } }
        testDir.deleteRecursively()
    }

    /**
     * The write side of the pipe defect the read side already refuses to commit: capture a flow whose
     * outgoing order says `58=filled|in full`, replay it, and the venue must receive that exact value —
     * not `58=filled` with the tail silently dropped by a `|`-joined re-encoding of the Send step.
     */
    @Test
    fun `a captured send replays a pipe-in-value field byte for byte`() {
        connectAcceptorAndClient()

        // SOH-delimited over the wire door ( in the JSON), so the pipe is a character, not a delimiter.
        val soh = "\\u0001"
        val send =
            """{"session":"CLI","raw":"35=D${soh}11=PIPE-$runId${soh}55=EUR/USD${soh}54=1${soh}38=100${soh}40=1${soh}58=filled|in full$soh"}"""
        assertTrue(status(post("/send", send)) in listOf("sent", "warning"), "the order must go out")
        assertEquals(
            "matched",
            status(post("/wait", """{"session":"CLI","match":{"messageType":"8","direction":"in"},"timeoutMs":8000}""")),
            "client should receive the acceptor's ExecutionReport",
        )

        // Precondition, not the assertion under test: the send door itself kept the pipe.
        assertEquals(
            "filled|in full",
            receivedOrders().first().valueOfTag(58),
            "the send door must deliver the pipe intact before capture is even in the picture",
        )

        val captured = obj(post("/scenarios/capture", """{"name":"pipe-cap-$runId","sessions":["CLI"]}"""))
        assertEquals("created", captured["status"]!!.jsonPrimitive.content, "capture should save: $captured")
        val id = captured["id"]!!.jsonPrimitive.content

        // The captured Send must re-read to the fields that were captured — the round-trip the
        // scenario file lives or dies by.
        val sendRaw =
            captured["scenario"]!!
                .jsonObject["steps"]!!
                .jsonArray
                .map { it.jsonObject }
                .first { it["type"]!!.jsonPrimitive.content == "send" }["raw"]!!
                .jsonPrimitive.content
        assertEquals(
            "filled|in full",
            FixMessageHelper.parseFixMessage(sendRaw).firstOrNull { it.first == 58 }?.second,
            "the Send step's raw must re-parse to the captured value — this raw does not: $sendRaw",
        )

        val ran = obj(post("/scenarios/run", """{"id":"$id"}"""))
        assertTrue(ran["passed"]!!.jsonPrimitive.boolean, "captured scenario should replay green: $ran")

        // The replayed order (fresh minted ClOrdID, so not PIPE-*) must carry the whole value.
        val replayed = receivedOrders().first { it.valueOfTag(11) != "PIPE-$runId" }
        assertEquals(
            "filled|in full",
            replayed.valueOfTag(58),
            "replay sent a message the client never sent — the venue received: ${replayed.valueOfTag(58)}",
        )
        delete("/scenarios", """{"id":"$id"}""")
    }

    /**
     * `49=SAME` where sender and target CompID are both `SAME` settles nothing — marking every such
     * message OUTGOING turns the venue's replies into Send steps that assert nothing. The paste door
     * must refuse the save and name the undirected rows, exactly as it does when no CompID matches.
     */
    @Test
    fun `a compid pair that cannot settle direction refuses the save`() {
        val soh = "\\u0001"
        val wire = """35=8${soh}49=SAME${soh}56=SAME${soh}11=AMB-$runId${soh}150=0$soh"""
        val resp =
            obj(
                post(
                    "/scenarios/capture-paste",
                    """{"name":"ambig-$runId","wire":"$wire","senderCompId":"SAME","targetCompId":"SAME"}""",
                ),
            )
        assertEquals(
            "refused",
            resp["status"]?.jsonPrimitive?.content,
            "an ambiguous CompID pair settles nothing, and a guess is a false green: $resp",
        )
        assertTrue(
            resp["undirected"]!!.jsonArray.isNotEmpty(),
            "the refusal must name the rows nobody directed: $resp",
        )
    }

    /**
     * A pasted session-log fragment starts with a Logon and breathes Heartbeats. Those lines are the
     * transport's business: replaying a `35=A` through the application door onto a logged-on session
     * is a duplicate Logon most venues answer with a disconnect. The paste door must skip them like
     * live capture does — and *say so*, because a silently thinner scenario is a coverage lie.
     */
    @Test
    fun `pasted admin messages are skipped and reported, not replayed`() {
        val soh = "\\u0001"
        val wire =
            listOf(
                """35=A${soh}49=CLIENT${soh}56=VENUE${soh}98=0${soh}108=30$soh""",
                """35=0${soh}49=VENUE${soh}56=CLIENT$soh""",
                """35=D${soh}49=CLIENT${soh}56=VENUE${soh}11=ADM-$runId${soh}55=EUR/USD${soh}54=1${soh}38=100${soh}40=1$soh""",
                """35=8${soh}49=VENUE${soh}56=CLIENT${soh}11=ADM-$runId${soh}150=0$soh""",
            ).joinToString("\\n")
        val resp =
            obj(
                post(
                    "/scenarios/capture-paste",
                    """{"name":"adm-$runId","wire":"$wire","senderCompId":"CLIENT","targetCompId":"VENUE"}""",
                ),
            )
        assertEquals("created", resp["status"]?.jsonPrimitive?.content, "the business flow still saves: $resp")
        assertEquals(
            2,
            resp["steps"]!!.jsonPrimitive.int,
            "only the order and its report are steps — a Logon or Heartbeat replayed is a broken session: $resp",
        )
        val refused = resp["refused"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(
            refused.any { it.contains("35=A") } && refused.any { it.contains("35=0") },
            "the skipped admin lines must be reported, not silently thinner coverage: $refused",
        )
        delete("/scenarios", """{"id":"${resp["id"]!!.jsonPrimitive.content}"}""")
    }

    /**
     * A log line with a second, headless record concatenated after the first message's trailer —
     * `...10=004<SOH>35=A<SOH>49=VENUE` — must be refused whole. The frame's arithmetic covers nothing
     * past CheckSum(10), so binding the trailing fields would put bytes the venue never framed into a
     * scenario under a checksum that never counted them.
     */
    @Test
    fun `a paste carrying bytes after the trailer is refused, not partially bound`() {
        val soh = "\u0001"
        val body = "35=8${soh}49=VENUE${soh}56=CLIENT${soh}11=TRL-$runId${soh}150=0$soh"
        val head = "8=FIX.4.4${soh}9=${body.length}$soh"
        val checksum = (head + body).toByteArray(Charsets.ISO_8859_1).sumOf { it.toInt() and 0xFF } % 256
        val framed = head + body + "10=%03d$soh".format(checksum)
        val wire = (framed + "35=A${soh}49=VENUE$soh").replace(soh, "\\u0001")

        val resp =
            obj(
                post(
                    "/scenarios/capture-paste",
                    """{"name":"trail-$runId","wire":"$wire","senderCompId":"CLIENT","targetCompId":"VENUE"}""",
                ),
            )
        assertEquals(
            "refused",
            resp["status"]?.jsonPrimitive?.content,
            "bytes outside the frame disprove the reading — nothing may be bound: $resp",
        )
        val refused = resp["refused"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(
            refused.any { it.contains("CheckSum(10)") },
            "the refusal must name the trailer the message ended at: $refused",
        )
    }

    /**
     * The advertised no-session use case: paste with explicit CompIDs, no live session named at all.
     * The saved steps must bind to **no** session (null = the active one at run time) — not to a session
     * literally named "", which resolves against nothing and fails every preflight with
     * "session '' not found" regardless of what is connected.
     */
    @Test
    fun `a capture-paste without a session runs on the active session, not on a session named nothing`() {
        connectAcceptorAndClient()
        // The active session is the first one; make that the client, the side this flow was pasted from.
        viewModel.moveSession(viewModel.sessions.indexOfFirst { it.title == "CLI" }, 0)
        val soh = "\\u0001"
        val wire =
            listOf(
                """35=D${soh}49=CLIENT${soh}56=VENUE${soh}11=NS-$runId${soh}55=EUR/USD${soh}54=1${soh}38=100${soh}40=1$soh""",
                """35=8${soh}49=VENUE${soh}56=CLIENT${soh}11=NS-$runId${soh}37=OID-1${soh}39=0${soh}55=EUR/USD${soh}150=0$soh""",
            ).joinToString("\\n")
        val created =
            obj(
                post(
                    "/scenarios/capture-paste",
                    """{"name":"nosess-$runId","wire":"$wire","senderCompId":"CLIENT","targetCompId":"VENUE"}""",
                ),
            )
        assertEquals("created", created["status"]?.jsonPrimitive?.content, "the paste is readable and directed: $created")
        val id = created["id"]!!.jsonPrimitive.content

        val ran = obj(post("/scenarios/run", """{"id":"$id"}"""))
        assertTrue(
            ran["passed"]!!.jsonPrimitive.boolean,
            "no session named means the active session — not a preflight against a session named '': $ran",
        )
        delete("/scenarios", """{"id":"$id"}""")
    }

    /**
     * **A replay must put a VALUE on the wire, never the expression that was meant to produce one.**
     *
     * The full RFQ is a round trip, and both chairs are FixTool's: the client asks, the acceptor quotes, the
     * client hits the quote back with the acceptor's own `QuoteID(117)`, the acceptor reports on it. Capture
     * read that middle echo as evidence that 117 was the *counterparty's* id to bind, and rewrote every
     * occurrence of the value to `${quoteID}` — the Quote that invented it included. Nothing minted it, so
     * the replayed Quote carried the ten characters `${quoteID}` in tag 117, which is what the venue
     * rejected.
     *
     * **The verdict does not see it, which is why this reads the wire.** Their id is seeded `Presence`, so
     * the Expect for that Quote reported `117: presence — actual ${quoteID} — ok`: the step agreed that a
     * field was there and said nothing about what was in it. The run did go red, but a row away — 131 held
     * the capture-day literal for the same reason 117 held an expression — and a verdict that fails for the
     * wrong reason is how a defect survives a fix. An expression on the wire is the fact; assert the fact.
     */
    @Test
    fun `a captured RFQ round trip replays a minted QuoteID, not an unbound reference`() {
        connectAcceptorAndClient()
        val soh = "\\u0001"
        val rfq = "RFQ-$runId"
        val quoteId = "Q-$runId"

        // The conversation, on the wire, from the chair that speaks each message. ACC is an acceptor
        // session, so capture reads it from the venue's end and CLI from the client's — the pairing that
        // makes 117 ours to mint on one side and theirs to echo on the other.
        exchange("CLI", "ACC", "R", "35=R${soh}131=$rfq${soh}146=1${soh}55=EUR/USD$soh")
        exchange("ACC", "CLI", "S", "35=S${soh}117=$quoteId${soh}131=$rfq${soh}55=EUR/USD${soh}133=1.0853$soh")
        exchange("CLI", "ACC", "AJ", "35=AJ${soh}693=RSP-$runId${soh}117=$quoteId${soh}131=$rfq${soh}55=EUR/USD$soh")
        exchange("ACC", "CLI", "AI", "35=AI${soh}117=$quoteId${soh}297=0${soh}55=EUR/USD$soh")

        val captured = obj(post("/scenarios/capture", """{"name":"rfq-round-trip-$runId"}"""))
        assertEquals("created", captured["status"]!!.jsonPrimitive.content, "capture should save: $captured")
        val id = captured["id"]!!.jsonPrimitive.content

        // The Quote's own id is minted by the step that sends it. Read off the saved scenario, because this
        // is the value the author opens the editor to — a bare `${quoteID}` there is bound by nothing.
        val quoteSend =
            captured["scenario"]!!
                .jsonObject["steps"]!!
                .jsonArray
                .map { it.jsonObject }
                .first { it["type"]!!.jsonPrimitive.content == "send" && it["raw"]!!.jsonPrimitive.content.contains("35=S") }["raw"]!!
                .jsonPrimitive.content
        assertTrue(
            quoteSend.contains("117=\${quoteID = uuid:20}"),
            "the Quote invents the QuoteID, so its step must MINT one; got $quoteSend",
        )

        val ran = obj(post("/scenarios/run", """{"id":"$id"}"""))
        assertTrue(ran["passed"]!!.jsonPrimitive.boolean, "the captured RFQ should replay green: $ran")

        // THE ASSERTION: the replay's bytes. The run clears both logs first, so everything here is the
        // replay's own — and not one field of it may still be an expression.
        val unresolved =
            viewModel.sessions
                .flatMap { it.messages.value.filterIsInstance<FixMessage>() }
                .filter { it.rawMessage.contains("\${") }
                .map { "${it.messageType} ${it.direction}: ${it.rawMessage}" }
        assertTrue(
            unresolved.isEmpty(),
            "a replay must send values, not expressions — these went on the wire unresolved: $unresolved",
        )
        delete("/scenarios", """{"id":"$id"}""")
    }

    // ----------------------------------------------------------------- helpers

    /** One message, sent from [from] and awaited on [to], so the capture's order is the wire's order. */
    private fun exchange(from: String, to: String, messageType: String, raw: String) {
        assertTrue(
            status(post("/send", """{"session":"$from","raw":"$raw"}""")) in listOf("sent", "warning"),
            "$messageType should go out on $from",
        )
        assertEquals(
            "matched",
            status(post("/wait", """{"session":"$to","match":{"messageType":"$messageType","direction":"in"},"timeoutMs":8000}""")),
            "$to should receive the $messageType",
        )
    }

    /** The NewOrderSingles the acceptor side actually received, newest last. */
    private fun receivedOrders(): List<FixMessage> =
        viewModel.sessions
            .first { it.title == "ACC" }
            .messages.value
            .filterIsInstance<FixMessage>()
            .filter { it.direction == FixMessage.Direction.INCOMING && it.messageType == "D" }

    private fun connectAcceptorAndClient() {
        val fixPort = freePort()
        val rule =
            AcceptorResponseRule(
                whenMsgType = "D",
                responseTemplate = "35=8|150=0|39=0|37=\${uuid}|11=\${req.11}|55=\${req.55}|",
            )
        val acceptor =
            FixConnectionProfile(
                name = "ACC",
                config =
                    FixConnectionConfig(
                        connectionType = FixConnectionConfig.ConnectionType.ACCEPTOR,
                        senderCompID = "ACC$runId",
                        targetCompID = "CLI$runId",
                        port = fixPort.toString(),
                        socketAcceptPort = fixPort.toString(),
                        beginString = "FIX.4.4",
                        fileStorePath = File(testDir, "accstore").absolutePath,
                        fileLogPath = File(testDir, "acclog").absolutePath,
                        acceptorResponseRules = listOf(rule),
                    ),
            )
        val cli =
            FixConnectionProfile(
                name = "CLI",
                config =
                    FixConnectionConfig(
                        connectionType = FixConnectionConfig.ConnectionType.INITIATOR,
                        senderCompID = "CLI$runId",
                        targetCompID = "ACC$runId",
                        host = "localhost",
                        port = fixPort.toString(),
                        socketConnectHost = "localhost",
                        beginString = "FIX.4.4",
                        autoReconnect = false,
                        resetOnLogon = true,
                        fileStorePath = File(testDir, "clistore").absolutePath,
                        fileLogPath = File(testDir, "clilog").absolutePath,
                    ),
            )
        listOf(acceptor, cli).forEach {
            viewModel.saveConnectionProfile(it)
            viewModel.connectProfile(it.id, it)
        }
        assertTrue(
            awaitCondition(15_000) {
                viewModel.sessions.any { it.title == "CLI" && it.connectionState.value == FixConnectionState.LOGGED_ON }
            },
            "client should log on to the FixTool acceptor",
        )
    }

    /** See [TestPorts]: an ephemeral port closed before it is bound is a race this suite kept losing. */
    private fun freePort(): Int = TestPorts.free()

    private fun awaitCondition(timeoutMs: Long = 5_000, predicate: () -> Boolean): Boolean {
        val start = System.currentTimeMillis()
        while (!predicate() && System.currentTimeMillis() - start < timeoutMs) {
            Thread.sleep(100)
        }
        return predicate()
    }

    private fun request(method: String, path: String, body: String?): HttpResponse<String> {
        val publisher =
            if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)
        val req =
            HttpRequest
                .newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .method(method, publisher)
                .build()
        return client.send(req, HttpResponse.BodyHandlers.ofString())
    }

    private fun post(path: String, body: String) = request("POST", path, body)

    private fun delete(path: String, body: String) = request("DELETE", path, body)

    private fun obj(resp: HttpResponse<String>) = Json.parseToJsonElement(resp.body()).jsonObject

    private fun status(resp: HttpResponse<String>) = obj(resp)["status"]?.jsonPrimitive?.content
}
