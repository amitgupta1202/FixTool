package com.knapsack.fixtool.integration

import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.FixConnectionState
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for multi-session connect (sessionCount > 1): one profile opening
 * several concurrent FIX sessions that share the same SenderCompID/TargetCompID,
 * distinguished client-side by auto-derived SessionQualifiers.
 *
 * Uses [TestFixServer] (raw sockets) because a QuickFIX/J acceptor cannot host several
 * concurrent sessions with identical comp IDs - the very thing this feature exercises.
 */
class MultiSessionConnectIntegrationTest {
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File
    private lateinit var server: TestFixServer

    // Unique comp IDs per test run: QuickFIX/J keeps a static session registry per JVM,
    // so reusing a SessionID across tests could collide with a not-yet-stopped initiator
    private val runId = System.nanoTime().toString().takeLast(8)

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-multisession-test", "").apply {
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
        awaitQuietly { viewModel.sessions.all { it.connectionState.value != FixConnectionState.LOGGED_ON } }
        server.stop()
        testDir.deleteRecursively()
    }

    private fun buildProfile(
        name: String,
        sessionCount: Int,
        sessionQualifier: String = "",
        senderCompID: String = "LOADGEN$runId",
        targetCompID: String = "MDSERVER$runId",
    ): FixConnectionProfile =
        FixConnectionProfile(
            name = name,
            config =
                FixConnectionConfig(
                    senderCompID = senderCompID,
                    targetCompID = targetCompID,
                    sessionQualifier = sessionQualifier,
                    sessionCount = sessionCount,
                    host = "localhost",
                    port = server.port.toString(),
                    socketConnectHost = "localhost",
                    beginString = "FIX.4.4",
                    heartBtInt = "30",
                    autoReconnect = false,
                    // Matches the ConnectionPanel form default; also lets a replacement session
                    // reuse a freed qualifier without inheriting the old store's sequence numbers
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

    private fun awaitQuietly(condition: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!condition() && System.currentTimeMillis() - start < 5_000) {
            Thread.sleep(100)
        }
    }

    // ========================================
    // Multi-session connect: distinct CompIDs per session
    // ========================================

    @Test
    fun `numbering pattern gives each session a distinct SenderCompID`() {
        val profile =
            buildProfile(
                "MD Load",
                sessionCount = 3,
                senderCompID = "LG{nn}X$runId",
            )

        viewModel.connectProfile(profile.id, profile)

        await("3 sessions all logged on") {
            viewModel.sessions.size == 3 &&
                viewModel.sessions.all { it.connectionState.value == FixConnectionState.LOGGED_ON }
        }

        // The server saw three logons, each with its own SenderCompID
        assertEquals(
            setOf("LG01X$runId", "LG02X$runId", "LG03X$runId"),
            server.logons.map { it.first }.toSet(),
        )
        assertTrue(server.logons.all { it.second == "MDSERVER$runId" })

        // Distinct CompID pairs need no derived qualifier
        assertTrue(
            viewModel.getProfileSessions(profile.id).all { it.sessionQualifier.isEmpty() },
            "No SessionQualifier should be derived when CompID pairs are already unique",
        )
    }

    @Test
    fun `comma-separated list assigns one SenderCompID per session`() {
        val profile =
            buildProfile(
                "MD Load",
                sessionCount = 2,
                senderCompID = "ALPHA$runId, BETA$runId",
            )

        viewModel.connectProfile(profile.id, profile)

        await("2 sessions logged on") {
            viewModel.sessions.size == 2 &&
                viewModel.sessions.all { it.connectionState.value == FixConnectionState.LOGGED_ON }
        }

        assertEquals(
            listOf("ALPHA$runId", "BETA$runId"),
            server.logons.map { it.first }.sorted(),
        )
    }

    @Test
    fun `connectProfile rejects a list whose size does not match the session count`() {
        val profile =
            buildProfile(
                "MD Load",
                sessionCount = 3,
                senderCompID = "ALPHA$runId, BETA$runId", // 2 values for 3 sessions
            )

        viewModel.connectProfile(profile.id, profile)

        Thread.sleep(500)
        assertEquals(0, viewModel.sessions.size, "No session should be created for an invalid identity config")
        assertTrue(viewModel.notifications.isNotEmpty(), "A validation error notification should be shown")
    }

    // ========================================
    // Multi-session connect: shared CompIDs + derived qualifiers
    // ========================================

    @Test
    fun `sessionCount 3 opens three logged-on sessions with distinct qualifiers`() {
        val profile = buildProfile("MD Load", sessionCount = 3, sessionQualifier = "MD")

        viewModel.connectProfile(profile.id, profile)

        await("3 sessions all logged on") {
            viewModel.sessions.size == 3 &&
                viewModel.sessions.all { it.connectionState.value == FixConnectionState.LOGGED_ON }
        }

        // The server saw three real logons sharing the same comp IDs
        assertEquals(3, server.logonCount.get(), "Server should have received 3 logons")

        // Each session carries a distinct derived qualifier and a numbered title
        val group = viewModel.getProfileSessions(profile.id)
        assertEquals(3, group.size)
        assertEquals(listOf("MD-1", "MD-2", "MD-3"), group.map { it.sessionQualifier })
        assertEquals(listOf("MD Load [1]", "MD Load [2]", "MD Load [3]"), group.map { it.title })

        // Aggregated profile state reports the group as logged on
        assertEquals(FixConnectionState.LOGGED_ON, viewModel.getProfileConnectionState(profile.id))
    }

    @Test
    fun `blank base qualifier derives S-numbered qualifiers`() {
        val profile = buildProfile("MD Load", sessionCount = 2, sessionQualifier = "")

        viewModel.connectProfile(profile.id, profile)

        await("2 sessions logged on") {
            viewModel.sessions.size == 2 &&
                viewModel.sessions.all { it.connectionState.value == FixConnectionState.LOGGED_ON }
        }

        assertEquals(listOf("S1", "S2"), viewModel.getProfileSessions(profile.id).map { it.sessionQualifier })
    }

    @Test
    fun `default sessionCount preserves single-session behavior`() {
        val profile = buildProfile("Single", sessionCount = 1, sessionQualifier = "QA")

        viewModel.connectProfile(profile.id, profile)

        await("single session logged on") {
            viewModel.sessions.size == 1 &&
                viewModel.sessions.all { it.connectionState.value == FixConnectionState.LOGGED_ON }
        }

        val session = viewModel.getProfileSession(profile.id)!!
        // Title and qualifier are taken from the profile verbatim - no [n] suffix, no derived qualifier
        assertEquals("Single", session.title)
        assertEquals("QA", session.sessionQualifier)
        assertEquals(1, server.logonCount.get())
    }

    @Test
    fun `disconnectProfile disconnects every session in the group`() {
        val profile = buildProfile("MD Load", sessionCount = 3, sessionQualifier = "MD")

        viewModel.connectProfile(profile.id, profile)
        await("3 sessions logged on") {
            viewModel.sessions.size == 3 &&
                viewModel.sessions.all { it.connectionState.value == FixConnectionState.LOGGED_ON }
        }

        viewModel.disconnectProfile(profile.id)

        await("all sessions disconnected") {
            viewModel.getProfileSessions(profile.id).all {
                it.connectionState.value == FixConnectionState.DISCONNECTED
            }
        }
        assertEquals(FixConnectionState.DISCONNECTED, viewModel.getProfileConnectionState(profile.id))
    }

    @Test
    fun `reconnect tops the group up after a session tab is closed`() {
        val profile = buildProfile("MD Load", sessionCount = 3, sessionQualifier = "MD")

        viewModel.connectProfile(profile.id, profile)
        await("3 sessions logged on") {
            viewModel.sessions.size == 3 &&
                viewModel.sessions.all { it.connectionState.value == FixConnectionState.LOGGED_ON }
        }

        // Close the middle session of the group (frees qualifier MD-2)
        val middleSession = viewModel.getProfileSessions(profile.id)[1]
        viewModel.closeSession(viewModel.sessions.indexOf(middleSession))
        assertEquals(2, viewModel.getProfileSessions(profile.id).size)

        // Wait for the closed session's logout/teardown to finish before reconnecting -
        // its QuickFIX/J initiator unregisters the qualified SessionID asynchronously
        await("closed session's connection torn down") { server.activeConnections.get() == 2 }
        Thread.sleep(500)

        // Connect again: the two live sessions are left alone, one new session fills the gap
        viewModel.connectProfile(profile.id, profile)

        await("group topped back up to 3 logged-on sessions") {
            viewModel.getProfileSessions(profile.id).size == 3 &&
                viewModel.getProfileSessions(profile.id).all {
                    it.connectionState.value == FixConnectionState.LOGGED_ON
                }
        }

        // 3 original logons + 1 top-up
        assertEquals(4, server.logonCount.get())
        assertEquals(
            setOf("MD-1", "MD-2", "MD-3"),
            viewModel.getProfileSessions(profile.id).map { it.sessionQualifier }.toSet(),
            "The freed qualifier should be reused by the replacement session",
        )
    }

    @Test
    fun `closeSession keeps other profiles session mappings intact`() {
        // Pure ViewModel test - no server needed
        val (profileA, _) = viewModel.createSessionWithProfileForTest("Profile A")
        val (profileB, sessionB) = viewModel.createSessionWithProfileForTest("Profile B")

        viewModel.closeSession(0)

        assertEquals(sessionB, viewModel.getProfileSession(profileB.id), "Profile B mapping should survive reindexing")
        assertEquals(null, viewModel.getProfileSession(profileA.id), "Profile A mapping should be removed")
    }

    // ========================================
    // Backward compatibility
    // ========================================

    @Test
    fun `profile json without sessionCount defaults to one session`() {
        // Simulates a connection_profiles.json written by an older version of the tool
        val oldJson =
            """
            {
                "id": "legacy-profile",
                "name": "Legacy",
                "config": {
                    "senderCompID": "OLD_SENDER",
                    "targetCompID": "OLD_TARGET",
                    "host": "localhost",
                    "port": "9876"
                }
            }
            """.trimIndent()

        val profile = Json { ignoreUnknownKeys = true }.decodeFromString<FixConnectionProfile>(oldJson)

        assertEquals(1, profile.config.sessionCount)
        assertEquals("OLD_SENDER", profile.config.senderCompID)
    }
}
