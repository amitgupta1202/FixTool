package com.knapsack.fixtool.integration

import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.FixConnectionState
import com.knapsack.fixtool.model.FixConnectionState.LOGGED_ON
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixMessageSession
import com.knapsack.fixtool.service.SendResult
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **A user's disconnect, and a connect that follows it, on an initiator with auto-reconnect off.**
 *
 * One disconnect runs two teardowns today. `disconnect()` launches one that sends Logout, waits for the
 * state to leave LOGGED_ON, then stops the connection manager and sets DISCONNECTED. QuickFixService's
 * `onLogout` reports every logout as a connection failure when auto-reconnect is off, and
 * `onConnectionFailed` launches a second one that stops the connection manager and sets ERROR. Both act
 * on whatever the session's mutable fields hold when they run, not on the connection they were started
 * for, and nothing marks the session as disconnecting.
 */
class SessionTeardownIntegrationTest {
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File
    private var venuePort = 0
    private val recorders = mutableListOf<CoroutineScope>()

    // QuickFIX/J's session registry is static per JVM, so a SessionID reused across tests can collide.
    private val runId = System.nanoTime().toString().takeLast(8)
    private val venueCompId get() = "VENUE$runId"

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-session-teardown", "").apply {
                delete()
                mkdirs()
            }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
        venuePort = TestPorts.free()
    }

    @After
    fun cleanup() {
        recorders.forEach { it.cancel() }
        viewModel.disconnectAllSessions()
        awaitCondition(5_000) { viewModel.sessions.none { it.connectionState.value == FixConnectionState.LOGGED_ON } }
        testDir.deleteRecursively()
    }

    /**
     * Issue #55. A Connect that arrives while the session is still logging out is dropped.
     *
     * `disconnect()` returns at once and the state stays LOGGED_ON until the Logout reply comes back, so
     * `reconnectExistingSessions` reads the session as connected and logs "Session already
     * connecting/connected". When the logout finishes, nothing connects, and the session stays down.
     */
    @Test
    fun `a connect requested while the session is logging out connects once the logout finishes`() {
        connectVenue()
        val (profile, client) = connectClient("ALPHA")
        awaitLoggedOn(client)

        client.disconnect()
        viewModel.connectProfile(profile.id, profile)

        assertTrue(
            awaitCondition(15_000) { incomingLogons(client) == 2 && client.connectionState.value == LOGGED_ON },
            "the second Connect must log on again once the logout is done " +
                "(logons=${incomingLogons(client)}, state=${client.connectionState.value})",
        )
    }

    /**
     * Issue #55, the other half of the same window. A Connect that arrives once the state reads
     * DISCONNECTED, but before the teardowns have run, is accepted. A stale teardown then either stops
     * the *new* connection, or sets the session's references to null after `connect()` stored them and
     * leaves the new connection running with nothing pointing at it.
     *
     * `onLogout` sets DISCONNECTED straight away, while `disconnect()`'s coroutine only notices on its next
     * 50 ms poll. So a DISCONNECTING state that merely refuses a Connect is not enough: the teardown has
     * to act on the connection it belongs to.
     */
    @Test
    fun `a connect accepted just after the logout is not torn down by the old disconnect`() {
        connectVenue()
        val (profile, client) = connectClient("ALPHA")
        awaitLoggedOn(client)

        client.disconnect()
        val leftLoggedOn = System.currentTimeMillis() + 5_000
        while (client.connectionState.value == LOGGED_ON && System.currentTimeMillis() < leftLoggedOn) {
            Thread.onSpinWait()
        }
        viewModel.connectProfile(profile.id, profile)

        assertTrue(
            awaitCondition(15_000) { incomingLogons(client) == 2 },
            "the second Connect must log on (logons=${incomingLogons(client)}, state=${client.connectionState.value})",
        )
        // Both old teardowns have long finished by now: the disconnect coroutine polls every 50 ms.
        Thread.sleep(3_000)
        assertEquals(
            FixConnectionState.LOGGED_ON,
            client.connectionState.value,
            "the new connection must survive the previous disconnect's teardown",
        )
        // Reading LOGGED_ON is not enough. A teardown that sets the session's fields to null after the new
        // connect() stored them leaves the new QuickFIX connection running with nothing pointing at it:
        // the state still follows its callbacks, but nothing can send on it, log it out or stop it.
        val sent = client.sendFixMessage("35=0|112=after-reconnect|", viewModel.dictionary)
        assertTrue(sent !is SendResult.Failed, "the reconnected session must be able to send: $sent")
    }

    /**
     * Issue #56. A disconnect the user asked for ends in DISCONNECTED and never passes through ERROR.
     *
     * The `onConnectionFailed` teardown writes ERROR on every logout with auto-reconnect off, so ERROR is
     * written on every one of these disconnects. Usually `disconnect()`'s own teardown writes DISCONNECTED
     * afterwards and hides it. When the two call `stop()` at the same moment the order flips and the session
     * is left in ERROR, which is the intermittent symptom in the issue. Recording every state the session
     * moves through makes the defect visible on every cycle, not only on the unlucky ones.
     */
    @Test
    fun `a disconnect the user asked for never reports ERROR`() {
        connectVenue()
        val (profile, client) = connectClient("ALPHA")
        val seen = record(client)

        repeat(3) { cycle ->
            awaitLoggedOn(client)
            seen.clear()

            client.disconnect()
            assertTrue(
                awaitCondition(5_000) { client.connectionState.value != FixConnectionState.LOGGED_ON },
                "cycle ${cycle + 1}: the logout should complete",
            )
            // Long enough for both teardowns to finish: the disconnect coroutine polls every 50 ms.
            Thread.sleep(1_500)

            assertTrue(
                FixConnectionState.ERROR !in seen,
                "cycle ${cycle + 1}: a clean disconnect passed through ERROR: $seen",
            )
            assertEquals(FixConnectionState.DISCONNECTED, client.connectionState.value, "cycle ${cycle + 1}: $seen")

            viewModel.connectProfile(profile.id, profile)
        }
    }

    /**
     * **The logon stamp is the line between two connections in one log.** A scenario bound with
     * `this_connection` takes only messages stamped after it, so everything the previous connection wrote,
     * and the new Logon exchange itself, must sit before it, and what the counterparty sends next after it.
     */
    @Test
    fun `the logon stamp separates the current connection's messages from the previous one's`() {
        connectVenue()
        val (profile, client) = connectClient("ALPHA")
        awaitLoggedOn(client)
        client.disconnect()
        viewModel.connectProfile(profile.id, profile)
        assertTrue(
            awaitCondition(15_000) { incomingLogons(client) == 2 && client.connectionState.value == LOGGED_ON },
            "the client should log on a second time (logons=${incomingLogons(client)})",
        )
        val stamp = assertNotNull(client.loggedOnAtMicros, "a session that has logged on knows when")

        assertTrue(client.sendTestRequest("after-logon"), "the test request should go out")
        assertTrue(
            awaitCondition(10_000) { fixMessages(client).any { it.isAnswer() } },
            "the venue should answer the test request",
        )

        val log = fixMessages(client)
        val secondLogon = log.filter { it.isIncoming() && it.messageType == "A" }[1]
        val reply = log.first { it.isAnswer() }
        val before = log.takeWhile { it !== secondLogon } + secondLogon
        assertTrue(
            before.all { it.captureTimeMicros < stamp },
            "the previous connection and the new Logon come before the stamp: " +
                before.filter { it.captureTimeMicros >= stamp }.map { it.rawMessage },
        )
        assertTrue(reply.captureTimeMicros > stamp, "what the counterparty sends after the logon comes after it")
    }

    // ------------------------------------------------------------------ helpers

    private fun connectVenue() {
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
                    ),
            )
        viewModel.saveConnectionProfile(profile)
        viewModel.connectProfile(profile.id, profile)
        assertTrue(
            awaitCondition(10_000) { viewModel.sessions.any { it.title == "VENUE" && it.isVenue } },
            "the venue should be listening on $venuePort",
        )
    }

    private fun connectClient(name: String): Pair<FixConnectionProfile, FixMessageSession> {
        val profile =
            FixConnectionProfile(
                name = name,
                config =
                    FixConnectionConfig(
                        connectionType = FixConnectionConfig.ConnectionType.INITIATOR,
                        senderCompID = "$name$runId",
                        targetCompID = venueCompId,
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
        return profile to viewModel.sessions.first { it.title == name }
    }

    private fun awaitLoggedOn(session: FixMessageSession) =
        assertTrue(
            awaitCondition(15_000) { session.connectionState.value == FixConnectionState.LOGGED_ON },
            "${session.title} should log on (state=${session.connectionState.value})",
        )

    /**
     * Every state the session moves through. Collected unconfined, so the collector runs on the thread that
     * set the state and a state that lasts only a few milliseconds is still seen.
     */
    private fun record(session: FixMessageSession): MutableList<FixConnectionState> {
        val seen = CopyOnWriteArrayList<FixConnectionState>()
        val scope = CoroutineScope(Dispatchers.Unconfined).also { recorders += it }
        scope.launch { session.connectionState.collect { seen += it } }
        return seen
    }

    /** Logons the venue sent this client, one per connection. Read off the grid, which trails by one drain. */
    private fun incomingLogons(session: FixMessageSession): Int =
        fixMessages(session).count { it.isIncoming() && it.messageType == "A" }

    private fun fixMessages(session: FixMessageSession): List<FixMessage> =
        session.messages.value.filterIsInstance<FixMessage>()

    private fun FixMessage.isIncoming(): Boolean = direction == FixMessage.Direction.INCOMING

    /** The venue's Heartbeat answering the test request sent after the second logon. */
    private fun FixMessage.isAnswer(): Boolean = isIncoming() && "112=after-logon" in rawMessage

    private fun awaitCondition(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (runCatching(predicate).getOrDefault(false)) return true
            if (System.currentTimeMillis() >= deadline) return false
            Thread.sleep(50)
        }
    }
}
