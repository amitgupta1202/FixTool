package com.knapsack.fixtool.integration

import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.FixConnectionState
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixMessageSession
import com.knapsack.fixtool.service.AcceptorPresets
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * **A preset over a socket** — the claim the unit tests cannot make.
 *
 * `AcceptorPresetsTest` asks the engine what a preset renders, and the control-surface tests ask the
 * dry run. Both deliberately stop short of the wire: the dry run does not go through
 * [com.knapsack.fixtool.service.AcceptorDispatch] at all, and a rendered string is not a message a
 * counterparty accepted. Everything between them — the rules persisting on the profile, compiling
 * when the session connects, the dispatcher holding step two for its delay, QuickFIX/J stamping a
 * header on the result — is only exercised here.
 *
 * Which matters more for *content* than for a hand-written rule: a preset is code we ship, so if any
 * link in that chain drops it, every user gets a venue that stays silent and nothing in the suite
 * says so.
 */
class AcceptorPresetIntegrationTest {
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File
    private var venuePort = 0

    // QuickFIX/J's session registry is static per JVM, so CompIDs reused across tests collide with a
    // session a previous test has not finished tearing down.
    private val runId = System.nanoTime().toString().takeLast(8)
    private val venueCompId get() = "PVENUE$runId"
    private val clientCompId get() = "PCLIENT$runId"

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-acceptor-preset", "").apply {
                delete()
                mkdirs()
            }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
        venuePort = TestPorts.free()
    }

    @After
    fun cleanup() {
        viewModel.disconnectAllSessions()
        awaitCondition(5_000) { viewModel.sessions.none { it.connectionState.value == FixConnectionState.LOGGED_ON } }
        testDir.deleteRecursively()
    }

    /**
     * The starter venue doing what it is offered for: a limit order acknowledged, then filled a
     * quarter of a second later, both reports about the same order.
     *
     * The elapsed time is asserted **one-sidedly**. A venue that fired both reports at once would pass
     * every other assertion here — the two messages would still arrive, still carry the right tags,
     * still be in order — so the gap is the only thing that distinguishes the sequence from a burst,
     * and a lower bound is the half of it a slow machine cannot flake.
     */
    @Test
    fun `a limit order is acknowledged and then filled, a step apart, on the wire`() {
        startVenue()
        val client = connectClient()

        client.sendFixMessage(
            "35=D|11=PRESET-1|55=VOD.L|54=1|38=1000|40=2|44=185.25|60=20260730-09:14:22.000",
            viewModel.dictionary,
        )

        assertTrue(
            awaitCondition(15_000) { executionReports(client).size >= 2 },
            "the starter venue should acknowledge a limit order and then fill it; got " +
                executionReports(client).map { field(it, 150) },
        )
        val reports = executionReports(client)
        assertEquals(2, reports.size, "an ack and a fill, and nothing else")

        val (ack, fill) = reports
        assertEquals("0", field(ack, 150), "the first report is the acknowledgement")
        assertEquals("0", field(ack, 39))
        assertEquals("1000", field(ack, 151), "nothing is done yet, so the whole order is still open")

        assertEquals("F", field(fill, 150), "the second is the trade")
        assertEquals("2", field(fill, 39))
        assertEquals("1000", field(fill, 14), "filled in full")
        assertEquals("0", field(fill, 151))
        assertEquals("185.25", field(fill, 31), "at the price the order asked for")

        assertEquals(
            field(ack, 37),
            field(fill, 37),
            "the fill must be about the order the ack acknowledged — a client tracking OrderID would " +
                "otherwise be told about an order it never placed",
        )
        assertNotEquals(field(ack, 17), field(fill, 17), "two reports sharing one ExecID is not something a venue does")

        val gap = Duration.between(ack.timestamp, fill.timestamp).toMillis()
        assertTrue(
            gap >= 150,
            "the fill is authored 250ms after the ack; arriving together means the sequence was not " +
                "played as one, and every other assertion here would still pass. Gap was ${gap}ms",
        )
    }

    /**
     * The reason the fill rule is conditioned on OrdType, tested where it bites.
     *
     * A market order carries no price, so the fill's `31=${req.44}` would substitute nothing and put
     * `31=` on the wire. That is a malformed message — and one whose consequences land on the client,
     * not on us. The condition routes the order past it to the rule below, and this asserts both
     * halves: the ack arrives, and no second report follows it.
     */
    @Test
    fun `a market order gets the acknowledgement alone, and nothing malformed`() {
        startVenue()
        val client = connectClient()

        client.sendFixMessage("35=D|11=PRESET-2|55=VOD.L|54=1|38=1000|40=1|60=20260730-09:14:22.000", viewModel.dictionary)

        assertTrue(
            awaitCondition(15_000) { executionReports(client).isNotEmpty() },
            "a market order must still be answered — falling past the fill is not falling past everything",
        )
        // Longer than the authored 250ms gap, so a fill that was coming would have arrived by now.
        Thread.sleep(1_000)

        val reports = executionReports(client)
        assertEquals(1, reports.size, "only the rule that needs no price should have answered")
        assertEquals("0", field(reports.single(), 150))

        assertTrue(
            fixMessages(client).none { field(it, 35) == "3" || field(it, 35) == "j" },
            "a reject means the venue sent something the client could not read: " +
                fixMessages(client).filter { field(it, 35) == "3" || field(it, 35) == "j" }.map { it.rawMessage },
        )
    }

    /**
     * The malformed-field guard, asked of the bytes rather than of the template.
     *
     * `AcceptorPresetsTest` checks the string a preset renders. This checks what a counterparty
     * actually received, which is the only version of the claim that cannot be true of a rendering
     * and false of the wire.
     */
    @Test
    fun `nothing the venue sends carries a tag with no value`() {
        startVenue()
        val client = connectClient()

        client.sendFixMessage(
            "35=D|11=PRESET-3|55=VOD.L|54=1|38=1000|40=2|44=185.25|60=20260730-09:14:22.000",
            viewModel.dictionary,
        )
        assertTrue(awaitCondition(15_000) { executionReports(client).size >= 2 })

        val empty =
            executionReports(client).flatMap { report ->
                report.rawMessage.split('|').filter { it.isNotBlank() && it.endsWith("=") }
            }
        assertTrue(empty.isEmpty(), "the venue put ${empty.joinToString()} on the wire")
    }

    // ---------------------------------------------------------------- helpers

    private fun startVenue() {
        val rules = AcceptorPresets.insert(emptyList(), AcceptorPresets.byId(AcceptorPresets.STARTER_VENUE)!!).rules
        val profile =
            FixConnectionProfile(
                name = "PRESET VENUE",
                config =
                    FixConnectionConfig(
                        connectionType = FixConnectionConfig.ConnectionType.ACCEPTOR,
                        senderCompID = venueCompId,
                        targetCompID = clientCompId,
                        port = venuePort.toString(),
                        socketAcceptPort = venuePort.toString(),
                        beginString = "FIX.4.4",
                        fileStorePath = File(testDir, "venuestore").absolutePath,
                        fileLogPath = File(testDir, "venuelog").absolutePath,
                        // Saved on the profile and read back by the service at connect — the same path a
                        // preset inserted from the panel or the control surface takes.
                        acceptorResponseRules = rules,
                    ),
            )
        viewModel.saveConnectionProfile(profile)
        viewModel.connectProfile(profile.id, profile)
        assertTrue(
            awaitCondition(10_000) { viewModel.sessions.any { it.title == "PRESET VENUE" } },
            "the venue should be listening on $venuePort",
        )
    }

    private fun connectClient(): FixMessageSession {
        val profile =
            FixConnectionProfile(
                name = "PRESET CLIENT",
                config =
                    FixConnectionConfig(
                        connectionType = FixConnectionConfig.ConnectionType.INITIATOR,
                        senderCompID = clientCompId,
                        targetCompID = venueCompId,
                        host = "localhost",
                        port = venuePort.toString(),
                        socketConnectHost = "localhost",
                        beginString = "FIX.4.4",
                        autoReconnect = false,
                        resetOnLogon = true,
                        fileStorePath = File(testDir, "clientstore").absolutePath,
                        fileLogPath = File(testDir, "clientlog").absolutePath,
                    ),
            )
        viewModel.saveConnectionProfile(profile)
        viewModel.connectProfile(profile.id, profile)
        val session = viewModel.sessions.first { it.title == "PRESET CLIENT" }
        assertTrue(
            awaitCondition(15_000) { session.connectionState.value == FixConnectionState.LOGGED_ON },
            "the client should log on to the venue",
        )
        return session
    }

    private fun fixMessages(session: FixMessageSession): List<FixMessage> =
        session.messages.value.filterIsInstance<FixMessage>()

    /** The execution reports this session received, oldest first — what the venue answered with. */
    private fun executionReports(session: FixMessageSession): List<FixMessage> =
        fixMessages(session).filter { field(it, 35) == "8" && it.direction == FixMessage.Direction.INCOMING }

    private fun field(message: FixMessage, tag: Int): String? =
        message.rawMessage
            .split('|')
            .firstOrNull { it.startsWith("$tag=") }
            ?.substringAfter('=')

    private fun awaitCondition(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        // A torn read is 'not yet', not 'no' -- see [settled].
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (settled(predicate)) return true
            if (System.currentTimeMillis() >= deadline) return false
            Thread.sleep(50)
        }
    }
}
