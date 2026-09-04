package com.knapsack.fixtool.integration

import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.FixConnectionState
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixMessageSession
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **One venue, many clients** — an acceptor with `TargetCompID = *` accepting a logon from any
 * counterparty and giving each its own pane.
 *
 * The thing under test is separation, not connection: two clients that both log on prove very little
 * on their own, since a single-session acceptor could be made to accept two logons and mix them into
 * one pane. So every assertion here is about a message being where it belongs and *nowhere else* —
 * the failure mode that matters is not "the second client could not connect" but "the second client
 * connected and its fill went to the first".
 */
class MultiClientAcceptorIntegrationTest {
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File
    private var venuePort = 0

    // QuickFIX/J's session registry is static per JVM, so a SessionID reused across tests can collide
    // with one a previous test has not finished tearing down.
    private val runId = System.nanoTime().toString().takeLast(8)
    private val venueCompId get() = "VENUE$runId"

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-multi-client-acceptor", "").apply {
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

    @Test
    fun `two clients each get their own session and pane`() {
        connectVenue()
        val alpha = connectClient("ALPHA")
        val beta = connectClient("BETA")

        val alphaPane = awaitPane("ALPHA")
        val betaPane = awaitPane("BETA")

        assertTrue(alphaPane !== betaPane, "each client must have its own pane")
        assertEquals("$venueCompId->ALPHA$runId", alphaPane.clientSessionId?.let { "${it.senderCompID}->${it.targetCompID}" })
        assertEquals("$venueCompId->BETA$runId", betaPane.clientSessionId?.let { "${it.senderCompID}->${it.targetCompID}" })

        // The Logon is in the pane, which is the whole point of the buffer the engine keeps while the
        // pane is being created: it arrives microseconds after the session exists and milliseconds
        // before anything can be listening for it. (Awaited, not asserted outright — a pane drains its
        // queue on a poll, so "logged on" leads the grid by up to one tick.)
        assertTrue(
            awaitCondition(5_000) { logonSenders(alphaPane).isNotEmpty() && logonSenders(betaPane).isNotEmpty() },
            "each pane should show the Logon that created it",
        )
        assertEquals(listOf("ALPHA$runId"), logonSenders(alphaPane), "alpha's pane holds alpha's Logon and no other")
        assertEquals(listOf("BETA$runId"), logonSenders(betaPane), "beta's pane holds beta's Logon and no other")

        assertTrue(alpha.connectionState.value == FixConnectionState.LOGGED_ON)
        assertTrue(beta.connectionState.value == FixConnectionState.LOGGED_ON)
    }

    @Test
    fun `an order from one client is answered on that client's session alone`() {
        connectVenue(
            rules =
                listOf(
                    AcceptorResponseRule(
                        whenMsgType = "D",
                        responseTemplate = "35=8|39=0|150=0|37=EXEC-\${req.11}|11=\${req.11}|55=\${req.55}",
                    ),
                ),
        )
        val alpha = connectClient("ALPHA")
        val beta = connectClient("BETA")
        val alphaPane = awaitPane("ALPHA")
        val betaPane = awaitPane("BETA")

        alpha.sendFixMessage("35=D|11=ORDER-ALPHA|55=VOD.L|54=1|38=100|40=1", viewModel.dictionary)

        assertTrue(
            awaitCondition(15_000) { execReportClOrdIds(alpha).contains("ORDER-ALPHA") },
            "alpha should receive its own execution report",
        )

        // The venue's side of the same exchange, and only it. Awaited separately: the venue's pane
        // drains its own queue on its own poll, so it trails the client that has already been answered.
        assertTrue(
            awaitCondition(5_000) { clOrdIds(alphaPane).contains("ORDER-ALPHA") },
            "the venue should show the order it answered",
        )
        assertEquals(listOf("ORDER-ALPHA"), clOrdIds(alphaPane), "the venue's alpha pane holds alpha's order and reply")
        assertTrue(clOrdIds(betaPane).isEmpty(), "beta's pane must not see alpha's order")
        assertTrue(execReportClOrdIds(beta).isEmpty(), "beta must not receive alpha's execution report")
    }

    @Test
    fun `one client leaving leaves the venue and its other clients up`() {
        connectVenue()
        val alpha = connectClient("ALPHA")
        connectClient("BETA")
        val alphaPane = awaitPane("ALPHA")
        val betaPane = awaitPane("BETA")

        alpha.disconnect()

        assertTrue(
            awaitCondition(15_000) { alphaPane.connectionState.value == FixConnectionState.DISCONNECTED },
            "alpha's pane should show its client has gone",
        )
        assertEquals(
            FixConnectionState.LOGGED_ON,
            betaPane.connectionState.value,
            "beta must be untouched by alpha's logout",
        )
        assertEquals(
            FixConnectionState.CONNECTED,
            venuePane().connectionState.value,
            "the venue is still listening — one client's logout is not the port closing",
        )
        assertTrue(alphaPane.messages.value.isNotEmpty(), "alpha's history outlives its session")
    }

    @Test
    fun `a logon addressed to the wrong venue is refused and reported`() {
        connectVenue()
        connectClient("ALPHA", venueName = "NOTUS$runId")

        val venue = venuePane()
        assertTrue(
            awaitCondition(15_000) { venue.refusedLogons.value.isNotEmpty() },
            "a logon we cannot serve must be recorded, not silently dropped",
        )
        val refused = venue.refusedLogons.value.first()
        assertEquals("NOTUS$runId", refused.sessionId.senderCompID, "the venue name the client addressed")
        assertEquals("ALPHA$runId", refused.sessionId.targetCompID, "who was asking")
        assertTrue(viewModel.sessions.none { it.clientSessionId != null }, "no pane for a client we refused")
    }

    // ------------------------------------------------------------------ minimized

    /**
     * **A venue's pane starts in the strip, and everything about the venue keeps working.**
     *
     * The pane is minimized by default because it holds no traffic of its own — every message on a
     * venue belongs to one of its clients — so its cell in the grid was a full pane for four lines of
     * text, and on the shipped example it was the fifth of five panes that the grid rounds up to six
     * cells with one left blank.
     *
     * What must not follow is any of the venue's *behaviour* going with it. The pane is what is hidden,
     * not the engine: the port stays bound, arrivals still open panes, and a refusal is still recorded.
     * That last one is the reason to test it rather than assume it, because a refused logon produces no
     * FIX message on either side and is the one fact the app holds nowhere else — losing it silently is
     * indistinguishable from a venue that is working.
     */
    @Test
    fun `a venue works minimized - it listens, opens panes for arrivals, and still records a refusal`() {
        connectVenue()
        val venue = venuePane()
        assertTrue(venue.minimized.value, "a venue's own pane starts in the strip")
        assertTrue(viewModel.visibleSessions().none { it.isVenue }, "and not in the grid")

        // Arrivals: the client's pane appears, and it is a conversation, so it is *not* minimized.
        connectClient("ALPHA")
        val alphaPane = awaitPane("ALPHA")
        assertFalse(alphaPane.minimized.value, "a client's pane is a conversation and belongs in the grid")
        assertTrue(alphaPane in viewModel.visibleSessions())
        assertTrue(venue.minimized.value, "and its arrival does not restore the venue")

        // Refusals: recorded on a pane nobody is looking at, which is where the chip's badge reads it.
        connectClient("BETA", venueName = "NOTUS$runId")
        assertTrue(
            awaitCondition(15_000) { venue.refusedLogons.value.isNotEmpty() },
            "a minimized venue must still record a logon it turned away",
        )
        val refusal = venue.refusedLogons.value.first()
        assertEquals("NOTUS$runId", refusal.sessionId.senderCompID)
    }

    /**
     * **Closing a minimized venue still closes its clients.**
     *
     * The recursion through `isClientOf` is what stops a client pane outliving the port that holds its
     * session open, and it is addressed by session now rather than by index — a stale index would close
     * some other pane, disconnecting it and discarding its log, without anything failing.
     */
    @Test
    fun `closing a minimized venue closes the client panes with it`() {
        connectVenue()
        connectClient("ALPHA")
        connectClient("BETA")
        awaitPane("ALPHA")
        awaitPane("BETA")

        val venue = venuePane()
        assertTrue(venue.minimized.value)
        val clientPanes = viewModel.sessions.count { it.isClientOf(venue) }
        assertEquals(2, clientPanes, "both clients should have a pane before the venue closes")

        viewModel.closeSession(venue)

        assertTrue(viewModel.sessions.none { it.isVenue }, "the venue's pane is gone")
        assertTrue(
            viewModel.sessions.none { it.clientSessionId != null },
            "and no client pane is left showing a conversation on an engine that has stopped",
        )
        // The clients' own initiator panes are untouched: they are connections of their own.
        assertTrue(viewModel.sessions.any { it.title == "ALPHA" }, "the client's own pane is not the venue's to close")
    }

    /**
     * **A rule saved while a venue is up reaches every client, not just the busy one.**
     *
     * Saving applies to live sessions, and a venue's rules are compiled once on the service that all
     * its clients share — so the reload either reaches all of them or the model is wrong. The failure
     * this guards against is the plausible one: a reload that follows the *session that happened to
     * trigger it* and leaves every other client on the old ruleset. Two clients would then answer the
     * same order differently, which is the hardest kind of venue bug to see, because each client's own
     * log is internally consistent.
     *
     * Beta is deliberately silent until after the edit. If both clients traded before it, a
     * per-session reload would look identical to a per-venue one.
     */
    @Test
    fun `a rule saved while the venue is up reaches every connected client`() {
        connectVenue(
            rules =
                listOf(
                    AcceptorResponseRule(
                        whenMsgType = "D",
                        responseTemplate = "35=8|39=0|150=0|11=\${req.11}|58=BEFORE",
                    ),
                ),
        )
        val alpha = connectClient("ALPHA")
        val beta = connectClient("BETA")
        awaitPane("ALPHA")
        awaitPane("BETA")

        alpha.sendFixMessage("35=D|11=ORDER-1|55=VOD.L|54=1|38=100|40=1", viewModel.dictionary)
        assertTrue(
            awaitCondition(15_000) { textOfReply(alpha, "ORDER-1") == "BEFORE" },
            "alpha should get the original rule's reply first",
        )

        // The edit: same profile, new rule, venue still up and both clients still logged on.
        val profile = viewModel.connectionProfiles.first { it.name == "VENUE" }
        assertTrue(
            viewModel.saveConnectionProfile(
                profile.copy(
                    config =
                        profile.config.copy(
                            acceptorResponseRules =
                                listOf(
                                    AcceptorResponseRule(
                                        whenMsgType = "D",
                                        responseTemplate = "35=8|39=0|150=0|11=\${req.11}|58=AFTER",
                                    ),
                                ),
                        ),
                ),
            ),
            "the profile should persist",
        )

        // The client that had already traded picks up the new rule...
        alpha.sendFixMessage("35=D|11=ORDER-2|55=VOD.L|54=1|38=100|40=1", viewModel.dictionary)
        assertTrue(
            awaitCondition(15_000) { textOfReply(alpha, "ORDER-2") == "AFTER" },
            "alpha should get the edited rule without reconnecting; got ${textOfReply(alpha, "ORDER-2")}",
        )

        // ...and so does the one that had not, which is what makes this about the venue and not a session.
        beta.sendFixMessage("35=D|11=ORDER-3|55=VOD.L|54=1|38=100|40=1", viewModel.dictionary)
        assertTrue(
            awaitCondition(15_000) { textOfReply(beta, "ORDER-3") == "AFTER" },
            "beta must be on the same ruleset as alpha; got ${textOfReply(beta, "ORDER-3")}",
        )

        assertEquals(
            1,
            venuePane().acceptorStatus()?.rulesLive,
            "the venue should be running exactly the one rule that is saved",
        )
    }

    // ---------------------------------------------------------------- helpers

    /** The Text (58) of the execution report answering [clOrdId] on this session, or null if none yet. */
    private fun textOfReply(session: FixMessageSession, clOrdId: String): String? =
        fixMessages(session)
            .filter { field(it, 35) == "8" && field(it, 11) == clOrdId }
            .mapNotNull { field(it, 58) }
            .firstOrNull()

    private fun connectVenue(rules: List<AcceptorResponseRule> = emptyList()) {
        val profile =
            FixConnectionProfile(
                name = "VENUE",
                config =
                    FixConnectionConfig(
                        connectionType = FixConnectionConfig.ConnectionType.ACCEPTOR,
                        senderCompID = venueCompId,
                        targetCompID = FixConnectionConfig.ANY_CLIENT,
                        port = venuePort.toString(),
                        socketAcceptPort = venuePort.toString(),
                        beginString = "FIX.4.4",
                        fileStorePath = File(testDir, "venuestore").absolutePath,
                        fileLogPath = File(testDir, "venuelog").absolutePath,
                        acceptorResponseRules = rules,
                    ),
            )
        viewModel.saveConnectionProfile(profile)
        viewModel.connectProfile(profile.id, profile)
        assertTrue(
            awaitCondition(10_000) { viewModel.sessions.any { it.title == "VENUE" && it.isVenue } },
            "the venue should be listening on $venuePort",
        )
    }

    private fun connectClient(name: String, venueName: String = venueCompId): FixMessageSession {
        val profile =
            FixConnectionProfile(
                name = name,
                config =
                    FixConnectionConfig(
                        connectionType = FixConnectionConfig.ConnectionType.INITIATOR,
                        senderCompID = "$name$runId",
                        targetCompID = venueName,
                        host = "localhost",
                        port = venuePort.toString(),
                        socketConnectHost = "localhost",
                        beginString = "FIX.4.4",
                        autoReconnect = false,
                        resetOnLogon = true,
                        fileStorePath = File(testDir, "${name}store").absolutePath,
                        fileLogPath = File(testDir, "${name}log").absolutePath,
                    ),
            )
        viewModel.saveConnectionProfile(profile)
        viewModel.connectProfile(profile.id, profile)
        return viewModel.sessions.first { it.title == name }
    }

    /** The venue's pane for [client], once the engine has announced it and the pane has been built. */
    private fun awaitPane(client: String): FixMessageSession {
        val expected = "VENUE ← $client$runId"
        assertTrue(
            awaitCondition(15_000) {
                viewModel.sessions.any { it.title == expected && it.connectionState.value == FixConnectionState.LOGGED_ON }
            },
            "the venue should open a pane for $client",
        )
        return viewModel.sessions.first { it.title == expected }
    }

    private fun venuePane(): FixMessageSession = viewModel.sessions.first { it.title == "VENUE" }

    private fun fixMessages(session: FixMessageSession): List<FixMessage> =
        session.messages.value.filterIsInstance<FixMessage>()

    /** SenderCompIDs of the Logons in this pane — the counterparties it has heard from. */
    private fun logonSenders(session: FixMessageSession): List<String> =
        fixMessages(session)
            .filter { field(it, 35) == "A" && it.direction == FixMessage.Direction.INCOMING }
            .mapNotNull { field(it, 49) }

    private fun clOrdIds(session: FixMessageSession): List<String> =
        fixMessages(session).mapNotNull { field(it, 11) }.distinct()

    private fun execReportClOrdIds(session: FixMessageSession): List<String> =
        fixMessages(session)
            .filter { field(it, 35) == "8" }
            .mapNotNull { field(it, 11) }

    private fun field(message: FixMessage, tag: Int): String? =
        message.rawMessage
            .split('|')
            .firstOrNull { it.startsWith("$tag=") }
            ?.substringAfter('=')

    private fun awaitCondition(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val start = System.currentTimeMillis()
        while (!predicate() && System.currentTimeMillis() - start < timeoutMs) {
            Thread.sleep(50)
        }
        return predicate()
    }
}
