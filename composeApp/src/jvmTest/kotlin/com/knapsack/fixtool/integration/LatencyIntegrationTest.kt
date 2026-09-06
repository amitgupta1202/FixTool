package com.knapsack.fixtool.integration

import com.knapsack.fixtool.control.ControlServer
import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.CorrelationIdType
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.FixConnectionState
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Latency, measured on a real socket, plain and over TLS.**
 *
 * A FixTool acceptor answers a FixTool initiator in the same process, with latency tracking on. The
 * assertion is about the shape of the measurement rather than a value no machine can promise: the
 * Logon out and the Logon back are paired, an order is paired with its ExecutionReport, the round trip
 * is a sane number, and the grid can find it from the outbound row that started it.
 *
 * The TLS variant is the one that matters. The previous packet-capture design could not read an
 * encrypted stream and so had never measured a venue on port 443; the socket filter sits after
 * QuickFIX/J's SSL filter and must see the same plaintext either way. The venue's certificate is a
 * self-signed one minted by the JDK's `keytool` on the spot, and the one PKCS12 file serves both sides
 * as key store and trust store.
 */
class LatencyIntegrationTest {
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var server: ControlServer
    private lateinit var testDir: File
    private var port = 0

    private val client: HttpClient = HttpClient.newHttpClient()
    private val baseUrl get() = "http://127.0.0.1:$port"
    private val runId = System.nanoTime().toString().takeLast(8)

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-latency-test", "").apply {
                delete()
                mkdirs()
            }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
        // Tracking is per session and created when the session is; it has to be on before connecting.
        viewModel.saveAppSettings(viewModel.appSettings.copy(enableLatencyTracking = true))
        port = TestPorts.free()
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

    @Test
    fun `a round trip over a plain socket is paired from send to reply`() {
        connect(keystore = null)
        assertRoundTripMeasured()
    }

    @Test
    fun `a round trip over TLS is paired the same way`() {
        connect(keystore = selfSignedKeystore())
        assertRoundTripMeasured()
    }

    private fun assertRoundTripMeasured() {
        val cli = viewModel.sessions.single { it.title == "CLI" }
        val tracker = assertNotNull(cli.getLatencyTrackingService(), "tracking was enabled in settings before the session was created")

        // The Logon is the session's first round trip, and it happened before anything else did.
        assertTrue(
            awaitCondition { tracker.getPairsByType(CorrelationIdType.LOGON).size == 1 },
            "Logon out and Logon back are paired; pending sends: ${tracker.getPendingSendCount()}",
        )

        val id = "LAT-$runId"
        // "warning" is sent-with-a-validation-note (the shorthand omits fields the dictionary wants); both mean it left.
        assertTrue(status(post("/send", """{"session":"CLI","raw":"35=D|11=$id|55=EUR/USD|54=1|38=100|40=1|"}""")) in listOf("sent", "warning"))
        assertTrue(
            awaitCondition { tracker.getPairsByType(CorrelationIdType.CL_ORD_ID).any { it.sendTimestamp.correlationId == id } },
            "the ExecutionReport is paired with the order that asked for it",
        )

        val pair = tracker.getPairsByType(CorrelationIdType.CL_ORD_ID).single { it.sendTimestamp.correlationId == id }
        assertTrue(
            pair.roundTripMicros >= 0 && pair.roundTripMicros < 10_000_000,
            "a loopback venue answers within ten seconds and never before it was asked: ${pair.roundTripMicros}μs",
        )
        assertEquals("D", pair.sendTimestamp.messageType)
        assertEquals("8", pair.receiveTimestamp.messageType)

        // The grid asks by the row it shows — the engine's serialisation in pipe form — and must get the same number.
        val row = assertNotNull(cli.snapshotLatestOutgoingByType()["D"], "the order is in the session's log")
        assertEquals(pair.roundTripMicros, cli.getLatencyForMessage(row.rawMessage), "the outbound row resolves to its round trip")

        // FIX's own ping: QuickFIX/J on the venue side answers a TestRequest with a Heartbeat echoing tag 112,
        // with nothing placed — so this is a session-layer round trip and pairs under TestReqID.
        val ping = "PING-$runId"
        assertTrue(status(post("/send", """{"session":"CLI","raw":"35=1|112=$ping|"}""")) in listOf("sent", "warning"))
        assertTrue(
            awaitCondition { tracker.getPairsByType(CorrelationIdType.TEST_REQ_ID).any { it.sendTimestamp.correlationId == ping } },
            "the Heartbeat that echoes the TestReqID is paired with the TestRequest",
        )
        val probe = tracker.getPairsByType(CorrelationIdType.TEST_REQ_ID).single { it.sendTimestamp.correlationId == ping }
        assertEquals("1", probe.sendTimestamp.messageType)
        assertEquals("0", probe.receiveTimestamp.messageType)

        tracker.flushStatistics()
        assertEquals(
            1,
            tracker.statistics.value
                .getValue(CorrelationIdType.CL_ORD_ID)
                .sampleCount,
        )
        assertEquals(
            1,
            tracker.statistics.value
                .getValue(CorrelationIdType.TEST_REQ_ID)
                .sampleCount,
        )
        assertEquals(3, tracker.aggregateStatistics.value.sampleCount, "the logon, the order and the probe")
    }

    /** A venue with one rule (an order is filled) and a client dialling it, both on this machine. */
    private fun connect(keystore: File?) {
        val fixPort = TestPorts.free()
        val rule =
            AcceptorResponseRule(
                whenMsgType = "D",
                responseTemplate = "35=8|150=0|39=0|37=\${uuid}|11=\${req.11}|55=\${req.55}|",
            )

        fun FixConnectionConfig.secured(): FixConnectionConfig =
            if (keystore == null) {
                this
            } else {
                copy(
                    useSSL = true,
                    keyStorePath = keystore.path,
                    keyStorePassword = STORE_PASSWORD,
                    keyStoreType = "PKCS12",
                    trustStorePath = keystore.path,
                    trustStorePassword = STORE_PASSWORD,
                )
            }

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
                    ).secured(),
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
                    ).secured(),
            )
        listOf(acceptor, cli).forEach {
            viewModel.saveConnectionProfile(it)
            viewModel.connectProfile(it.id, it)
        }
        assertTrue(
            awaitCondition(20_000) {
                viewModel.sessions.any { it.title == "CLI" && it.connectionState.value == FixConnectionState.LOGGED_ON }
            },
            "client should log on to the FixTool acceptor" + if (keystore != null) " over TLS" else "",
        )
    }

    /** A self-signed certificate for `localhost`, in a PKCS12 store that is both key store and trust store. */
    private fun selfSignedKeystore(): File {
        val keytool = File(System.getProperty("java.home"), "bin/keytool")
        Assume.assumeTrue("keytool not available at ${keytool.path}", keytool.canExecute())
        val store = File(testDir, "venue.p12")
        val process =
            ProcessBuilder(
                keytool.path,
                "-genkeypair",
                "-alias",
                "venue",
                "-keyalg",
                "RSA",
                "-keysize",
                "2048",
                "-validity",
                "3650",
                "-storetype",
                "PKCS12",
                "-keystore",
                store.path,
                "-storepass",
                STORE_PASSWORD,
                "-keypass",
                STORE_PASSWORD,
                "-dname",
                "CN=localhost",
            ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), "keytool: $output")
        return store
    }

    private fun awaitCondition(timeoutMs: Long = 10_000, predicate: () -> Boolean): Boolean {
        // A torn read is 'not yet', not 'no' -- see [settled].
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (settled(predicate)) return true
            if (System.currentTimeMillis() >= deadline) return false
            Thread.sleep(50)
        }
    }

    private fun post(path: String, body: String): HttpResponse<String> {
        val req =
            HttpRequest
                .newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        return client.send(req, HttpResponse.BodyHandlers.ofString())
    }

    private fun obj(resp: HttpResponse<String>) = Json.parseToJsonElement(resp.body()).jsonObject

    private fun status(resp: HttpResponse<String>) = obj(resp)["status"]!!.jsonPrimitive.content

    private companion object {
        const val STORE_PASSWORD = "fixtool-test"
    }
}
