package com.knapsack.fixtool.integration

import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.Counterparty
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.FixConnectionState
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixMessageSession
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import java.io.File
import kotlin.test.assertTrue

/**
 * **One venue and its clients, on real sockets, for a test to drive.**
 *
 * The helpers `MultiClientAcceptorIntegrationTest` carries privately, lifted out so a test with four clients
 * does not grow a fourth copy. Every CompID is suffixed with a per-run id, because QuickFIX/J's session
 * registry is static per JVM and a SessionID reused across tests can collide with one still tearing down.
 */
internal class VenueHarness(
    prefix: String,
) {
    val testDir: File =
        File.createTempFile(prefix, "").apply {
            delete()
            mkdirs()
        }
    val viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
    val port = TestPorts.free()
    val runId = System.nanoTime().toString().takeLast(8)
    val venueCompId = "VENUE$runId"

    /** A CompID as this run spells it. */
    fun comp(name: String) = "$name$runId"

    fun connectVenue(
        rules: List<AcceptorResponseRule>,
        counterparties: List<Counterparty> = emptyList(),
    ): FixMessageSession {
        val profile =
            FixConnectionProfile(
                name = "VENUE",
                config =
                    FixConnectionConfig(
                        connectionType = FixConnectionConfig.ConnectionType.ACCEPTOR,
                        senderCompID = venueCompId,
                        targetCompID = FixConnectionConfig.ANY_CLIENT,
                        port = port.toString(),
                        socketAcceptPort = port.toString(),
                        beginString = "FIX.4.4",
                        fileStorePath = File(testDir, "venuestore").absolutePath,
                        fileLogPath = File(testDir, "venuelog").absolutePath,
                        acceptorResponseRules = rules,
                        counterparties = counterparties,
                    ),
            )
        viewModel.saveConnectionProfile(profile)
        viewModel.connectProfile(profile.id, profile)
        assertTrue(
            awaitCondition(10_000) { viewModel.sessions.any { it.title == "VENUE" && it.isVenue } },
            "the venue should be listening on $port",
        )
        return venuePane()
    }

    fun connectClient(name: String): FixMessageSession {
        val profile =
            FixConnectionProfile(
                name = name,
                config =
                    FixConnectionConfig(
                        connectionType = FixConnectionConfig.ConnectionType.INITIATOR,
                        senderCompID = comp(name),
                        targetCompID = venueCompId,
                        host = "localhost",
                        port = port.toString(),
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
        val session = viewModel.sessions.first { it.title == name }
        assertTrue(
            awaitCondition(15_000) { session.connectionState.value == FixConnectionState.LOGGED_ON },
            "$name should log on to the venue",
        )
        // Logged on at the client is not yet a pane at the venue, and a relay to a counterparty the venue has not
        // finished creating would be counted as not delivered for a reason the test did not intend.
        awaitPane(name)
        return session
    }

    /** The venue's pane for [client], once the engine has announced it and the pane has been built. */
    fun awaitPane(client: String): FixMessageSession {
        val expected = "VENUE ← ${comp(client)}"
        assertTrue(
            awaitCondition(15_000) {
                viewModel.sessions.any { it.title == expected && it.connectionState.value == FixConnectionState.LOGGED_ON }
            },
            "the venue should open a pane for $client",
        )
        return viewModel.sessions.first { it.title == expected }
    }

    fun venuePane(): FixMessageSession = viewModel.sessions.first { it.title == "VENUE" }

    fun send(session: FixMessageSession, raw: String) {
        session.sendFixMessage(raw, viewModel.dictionary)
    }

    fun incoming(session: FixMessageSession, msgType: String): List<FixMessage> =
        session.messages.value
            .filterIsInstance<FixMessage>()
            .filter { it.direction == FixMessage.Direction.INCOMING && field(it, 35) == msgType }

    fun field(message: FixMessage, tag: Int): String? =
        message.rawMessage
            .split('|')
            .firstOrNull { it.startsWith("$tag=") }
            ?.substringAfter('=')

    fun awaitCondition(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        // A torn read is 'not yet', not 'no' -- see [settled].
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (settled(predicate)) return true
            if (System.currentTimeMillis() >= deadline) return false
            Thread.sleep(50)
        }
    }

    fun close() {
        viewModel.disconnectAllSessions()
        awaitCondition(5_000) { viewModel.sessions.none { it.connectionState.value == FixConnectionState.LOGGED_ON } }
        testDir.deleteRecursively()
    }
}
