package com.knapsack.fixtool.viewmodel

import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.FixMessageSession
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * **Minimizing a pane must not move any other pane's identity.**
 *
 * The whole risk in this feature is one boundary. The split grid is drawn from the panes that are
 * *visible*, so a position computed there counts a shorter list than the model's, and the two failures
 * that follow are both silent: a move lands one pane off, and a close destroys the wrong session —
 * which disconnects it and discards its log. Nothing throws. Hence [FixMessageViewModel.moveSessionTo]
 * and the object-addressed [FixMessageViewModel.closeSession], and hence these.
 *
 * Panes here are plain acceptors on free ports, which bind immediately and need no counterparty. What
 * is being tested is the bookkeeping around a list of panes, not FIX.
 */
class MinimizedPaneTest {
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File

    private val runId = System.nanoTime().toString().takeLast(8)

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-minimized-pane", "").apply {
                delete()
                mkdirs()
            }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
    }

    @After
    fun cleanup() {
        viewModel.disconnectAllSessions()
        testDir.deleteRecursively()
    }

    // ------------------------------------------------------------------ the venue default

    @Test
    fun `a venue's pane starts in the strip and an ordinary pane does not`() {
        val venue = pane("VENUE", anyClient = true)
        val plain = pane("PLAIN")

        assertTrue(venue.minimized.value, "a venue's own pane holds no traffic, so it starts minimized")
        assertFalse(plain.minimized.value, "an ordinary pane is a conversation and belongs in the grid")
        assertEquals(listOf(plain), viewModel.visibleSessions())
        assertEquals(listOf(venue), viewModel.minimizedSessions())
    }

    /**
     * The space claim, at the level that decides it.
     *
     * The shipped FX Venue example is a venue and two clients, and once both log on the venue holds a
     * pane for each — five panes, which the grid rounds up to six cells and leaves one blank. Four
     * panes is a 2x2 with nothing wasted, and the venue leaving the grid is the whole difference.
     */
    @Test
    fun `a venue and four conversations leave four panes in the grid`() {
        pane("VENUE", anyClient = true)
        repeat(4) { pane("CONV$it") }

        assertEquals(5, viewModel.sessions.size, "every pane still exists")
        assertEquals(4, viewModel.visibleSessions().size, "the grid holds the conversations only")
    }

    // ------------------------------------------------------------------ moving

    /**
     * Panes A B C D with B minimized. Moving C left must put it where A is.
     *
     * `index - 1` in the grid is one *visible* pane to the left, which here is A and not B. Computing
     * it by arithmetic on the model's list would move C onto B instead, and the visible order would not
     * change at all — a button that looks broken rather than one that is wrong.
     */
    @Test
    fun `moving a pane left with one minimized lands where the eye expects`() {
        val a = pane("A")
        val b = pane("B")
        val c = pane("C")
        val d = pane("D")
        viewModel.setSessionMinimized(b, true)

        assertEquals(listOf(a, c, d), viewModel.visibleSessions())

        // What the grid asks for: move C to where its previous *visible* neighbour is.
        val visible = viewModel.visibleSessions()
        viewModel.moveSessionTo(c, visible[visible.indexOf(c) - 1])

        assertEquals(listOf(c, a, b, d), viewModel.sessions.toList(), "real order")
        assertEquals(listOf(c, a, d), viewModel.visibleSessions(), "visible order")
    }

    @Test
    fun `a minimized pane keeps its place in the model while panes move around it`() {
        val a = pane("A")
        val b = pane("B")
        val c = pane("C")
        viewModel.setSessionMinimized(b, true)

        viewModel.moveSessionTo(c, a)

        // B is still between them, which is where it will reappear. Stated rather than discovered: a
        // restored pane returns to its place in real order, not to where it sat in visible order.
        assertEquals(listOf(c, a, b), viewModel.sessions.toList())
        viewModel.setSessionMinimized(b, false)
        assertEquals(listOf(c, a, b), viewModel.visibleSessions())
    }

    // ------------------------------------------------------------------ closing

    /**
     * The destructive one. Closing calls `destroy()`, which disconnects the session and drops its log,
     * so an index that names the wrong pane does not fail — it succeeds at the wrong thing.
     */
    @Test
    fun `closing a pane with another minimized closes the one that was asked for`() {
        val a = pane("A")
        val b = pane("B")
        val c = pane("C")
        val d = pane("D")
        viewModel.setSessionMinimized(b, true)

        viewModel.closeSession(d)

        assertEquals(listOf(a, b, c), viewModel.sessions.toList())
        assertTrue(b.minimized.value, "the minimized pane is untouched and still minimized")
    }

    @Test
    fun `closing the neighbour of a minimized pane leaves it minimized and itself`() {
        val a = pane("A")
        val b = pane("B")
        val c = pane("C")
        viewModel.setSessionMinimized(b, true)

        viewModel.closeSession(a)

        assertEquals(listOf(b, c), viewModel.sessions.toList())
        assertSame(b, viewModel.minimizedSessions().single())
    }

    // ------------------------------------------------------------------ the editor

    /**
     * **A minimized pane stays the editor's target.**
     *
     * Send resolves through `_activeSessionState`, which holds the session *object*, so a minimized
     * pane is a perfectly valid target and the message really is sent and really is logged. What must
     * not happen is the other thing: quietly moving the target to a different counterparty because its
     * pane went away. Pointing a loaded NewOrderSingle at the wrong venue is not a UI annoyance.
     */
    @Test
    fun `minimizing the active session does not move the editor's target`() {
        val a = pane("A")
        pane("B")
        viewModel.setActiveSessionByObject(a)
        val profileBefore = viewModel.selectedEditorProfile.value

        viewModel.setSessionMinimized(a, true)

        assertSame(a, viewModel.activeSession, "the target is the session, not the pane")
        assertEquals(profileBefore?.id, viewModel.selectedEditorProfile.value?.id, "and the editor's profile with it")
    }

    @Test
    fun `a message sent to a minimized pane is in its log when the pane comes back`() {
        val a = pane("A")
        viewModel.setActiveSessionByObject(a)
        viewModel.setSessionMinimized(a, true)

        // Not through the wire: an acceptor with no counterparty cannot send, and what is under test is
        // that the pane behind the chip is still the same live object with the same growing log.
        val before = a.messages.value.size
        a.addSeparator()
        // Drained explicitly: a session publishes its log from a polling loop, and what is being tested
        // is where the message lands rather than how long the loop takes to notice it.
        a.flushMessageQueue()
        assertEquals(before + 1, a.messages.value.size, "a minimized pane is hidden, not detached")

        viewModel.setSessionMinimized(a, false)
        assertEquals(before + 1, a.messages.value.size, "and restoring it shows what arrived while it was away")
    }

    // ------------------------------------------------------------------ invariants and memory

    @Test
    fun `every pane survives a minimize, a reorder and a restore exactly once`() {
        val panes = (1..4).map { pane("P$it") }
        viewModel.setSessionMinimized(panes[1], true)
        viewModel.moveSessionTo(panes[3], panes[0])
        viewModel.setSessionMinimized(panes[2], true)
        viewModel.setSessionMinimized(panes[1], false)
        viewModel.setSessionMinimized(panes[2], false)

        assertEquals(panes.size, viewModel.sessions.size)
        assertEquals(panes.toSet(), viewModel.sessions.toSet())
        assertEquals(viewModel.sessions.toList(), viewModel.visibleSessions(), "nothing is left in the strip")
        assertTrue(viewModel.minimizedSessions().isEmpty())
    }

    /**
     * A restored venue stays restored, across a reconnect and across a restart.
     *
     * Why [com.knapsack.fixtool.model.LayoutState.paneMinimized] holds booleans and not a list of names:
     * a venue starts minimized, so "absent from the list" and "the user restored it" would be the same
     * state, and the default would silently win every time the app opened.
     */
    @Test
    fun `restoring a venue is remembered rather than re-applied`() {
        val venue = pane("VENUE", anyClient = true)
        assertTrue(venue.minimized.value)

        viewModel.setSessionMinimized(venue, false)

        val profileId = viewModel.profileIdForSession(venue)
        assertNotNull(profileId)
        assertEquals(false, viewModel.layoutState.value.paneMinimized["$profileId#0"])
    }

    @Test
    fun `a minimize decision is keyed by profile, not by the session's id`() {
        val a = pane("A")
        val profileId = viewModel.profileIdForSession(a)

        viewModel.setSessionMinimized(a, true)

        // Session ids are fresh UUIDs each run, so a key built from one would never match on restart.
        assertEquals(true, viewModel.layoutState.value.paneMinimized["$profileId#0"])
        val keys = viewModel.layoutState.value.paneMinimized.keys
        assertFalse(keys.any { it.contains(a.id) })
    }

    // ------------------------------------------------------------------ helpers

    /** A pane, by way of a real profile: an acceptor on a free port binds at once and needs no peer. */
    private fun pane(name: String, anyClient: Boolean = false): FixMessageSession {
        val port = freePort().toString()
        val profile =
            FixConnectionProfile(
                name = name,
                config =
                    FixConnectionConfig(
                        connectionType = FixConnectionConfig.ConnectionType.ACCEPTOR,
                        senderCompID = "$name$runId",
                        targetCompID = if (anyClient) FixConnectionConfig.ANY_CLIENT else "PEER$runId",
                        port = port,
                        socketAcceptPort = port,
                        beginString = "FIX.4.4",
                        fileStorePath = File(testDir, "${name}store").absolutePath,
                        fileLogPath = File(testDir, "${name}log").absolutePath,
                    ),
            )
        viewModel.saveConnectionProfile(profile)
        viewModel.connectProfile(profile.id, profile)
        return viewModel.sessions.first { it.title == name }
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
}
