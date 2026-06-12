package com.knapsack.fixtool.integration

import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.FixConnectionState
import com.knapsack.fixtool.model.NotificationType
import com.knapsack.fixtool.service.SendResult
import com.knapsack.fixtool.ui.FixField
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for bulk send: one message sent to every logged-on session, with
 * template expressions re-resolved per session.
 *
 * Uses [TestFixServer] so the messages each session sends can be observed server-side.
 */
class BulkSendIntegrationTest {
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File
    private lateinit var server: TestFixServer

    private val runId = System.nanoTime().toString().takeLast(8)

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-bulksend-test", "").apply {
                delete()
                mkdirs()
            }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
        server = TestFixServer()
        server.start()
    }

    @After
    fun cleanup() {
        viewModel.disconnectAllSessions()
        val start = System.currentTimeMillis()
        while (
            viewModel.sessions.any { it.connectionState.value == FixConnectionState.LOGGED_ON } &&
            System.currentTimeMillis() - start < 5_000
        ) {
            Thread.sleep(100)
        }
        server.stop()
        testDir.deleteRecursively()
    }

    private fun buildProfile(sessionCount: Int): FixConnectionProfile =
        FixConnectionProfile(
            name = "MD Load",
            config =
                FixConnectionConfig(
                    senderCompID = "LG{n}X$runId",
                    targetCompID = "MDSERVER$runId",
                    sessionCount = sessionCount,
                    host = "localhost",
                    port = server.port.toString(),
                    socketConnectHost = "localhost",
                    beginString = "FIX.4.4",
                    heartBtInt = "30",
                    autoReconnect = false,
                    resetOnLogon = true,
                    fileStorePath = File(testDir, "store").absolutePath,
                    fileLogPath = File(testDir, "log").absolutePath,
                ),
        )

    private fun await(description: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!condition() && System.currentTimeMillis() - start < timeoutMs) {
            Thread.sleep(100)
        }
        assertTrue(condition(), "Timed out after ${timeoutMs}ms waiting for: $description")
    }

    private fun connectAndAwait(sessionCount: Int): FixConnectionProfile {
        val profile = buildProfile(sessionCount)
        viewModel.connectProfile(profile.id, profile)
        await("$sessionCount sessions logged on") {
            viewModel.sessions.size == sessionCount &&
                viewModel.sessions.all { it.connectionState.value == FixConnectionState.LOGGED_ON }
        }
        return profile
    }

    // NewOrderSingle with template expressions; tag 11 must resolve to a fresh UUID per
    // session, tag 58 exercises the seeded per-session variables
    private fun orderFields(): List<FixField> =
        listOf(
            FixField(tag = "35", value = "D"),
            FixField(tag = "11", value = "\${UUID.randomUUID()}"),
            FixField(tag = "21", value = "1"),
            FixField(tag = "55", value = "EUR/USD"),
            FixField(tag = "54", value = "1"),
            FixField(tag = "60", value = "\${LocalDateTime.now().format(DateTimeFormatter.ofPattern(\"yyyyMMdd-HH:mm:ss\"))}"),
            FixField(tag = "38", value = "1000000"),
            FixField(tag = "40", value = "1"),
            FixField(tag = "58", value = "\${sessionIndex}:\${sessionSenderCompID}"),
        )

    @Test
    fun `bulk send delivers one message to every logged-on session`() {
        connectAndAwait(sessionCount = 3)

        val outcomes = viewModel.sendMessageToAllConnectedSessions(orderFields())

        assertEquals(3, outcomes.size)
        assertTrue(
            outcomes.none { it.result is SendResult.Failed },
            "No send should fail: ${outcomes.map { it.result }}",
        )

        await("server received 3 application messages") { server.applicationMessages.size == 3 }
        assertTrue(server.applicationMessages.all { TestFixServer.fieldValue(it, 35) == "D" })
    }

    @Test
    fun `template expressions resolve to unique values per session`() {
        connectAndAwait(sessionCount = 3)

        viewModel.sendMessageToAllConnectedSessions(orderFields())
        await("server received 3 application messages") { server.applicationMessages.size == 3 }

        // ${UUID.randomUUID()} in ClOrdID must be re-resolved per session, not shared
        val clOrdIds = server.applicationMessages.map { TestFixServer.fieldValue(it, 11) }
        assertEquals(3, clOrdIds.toSet().size, "Each session should get a unique ClOrdID, got: $clOrdIds")

        // Seeded per-session variables resolve to each session's own identity
        val texts = server.applicationMessages.mapNotNull { TestFixServer.fieldValue(it, 58) }
        assertEquals(
            setOf("1:LG1X$runId", "2:LG2X$runId", "3:LG3X$runId"),
            texts.toSet(),
        )

        // Each message went out over its own session: SenderCompIDs on the wire are distinct
        val senders = server.applicationMessages.map { TestFixServer.fieldValue(it, 49) }
        assertEquals(setOf("LG1X$runId", "LG2X$runId", "LG3X$runId"), senders.toSet())
    }

    @Test
    fun `bulk send with no logged-on session reports a warning and sends nothing`() {
        val outcomes = viewModel.sendMessageToAllConnectedSessions(orderFields())

        assertTrue(outcomes.isEmpty())
        assertTrue(
            viewModel.notifications.any { it.type == NotificationType.WARNING },
            "A warning notification should be shown when there is nothing to send to",
        )
        assertEquals(0, server.applicationMessages.size)
    }

    @Test
    fun `bulk send skips sessions that are not logged on`() {
        val profile = connectAndAwait(sessionCount = 3)

        // Take one session of the group down
        val sessionToDrop = viewModel.getProfileSessions(profile.id)[1]
        sessionToDrop.disconnect()
        await("dropped session disconnected") {
            sessionToDrop.connectionState.value == FixConnectionState.DISCONNECTED
        }

        val outcomes = viewModel.sendMessageToAllConnectedSessions(orderFields())

        assertEquals(2, outcomes.size, "Only the two logged-on sessions should be targeted")
        await("server received 2 application messages") { server.applicationMessages.size == 2 }
    }
}
