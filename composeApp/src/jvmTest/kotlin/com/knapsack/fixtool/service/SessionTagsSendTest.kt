package com.knapsack.fixtool.service

import org.junit.Test
import quickfix.SessionID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **What QuickFIX/J writes on a send, and so what is left for the author to send.**
 *
 * The editor and capture both dropped SenderSubID, TargetSubID and the two location IDs on the premise that
 * QuickFIX/J writes them on the way out. It writes each one only when the session's own SessionID names it,
 * and no FixTool profile gives a session one, so they were dropped and never sent: a venue that routes on
 * SenderSubID could not be tested at all.
 */
class SessionTagsSendTest {
    private val everySend = setOf(8, 9, 10, 34, 49, 52, 56)

    @Test
    fun `with no session, only what the engine writes on every send`() {
        assertEquals(everySend, SessionTags.writtenOnSend(null))
    }

    @Test
    fun `a session that names only its CompIDs writes no sub or location ID`() {
        assertEquals(everySend, SessionTags.writtenOnSend(SessionID("FIX.4.4", "CLI", "SRV")))
    }

    @Test
    fun `a session that names a SenderSubID writes it, and nothing it does not name`() {
        val session = SessionID("FIX.4.4", "CLI", "DESK1", "", "SRV", "", "", "")

        assertEquals(everySend + 50, SessionTags.writtenOnSend(session))
    }

    @Test
    fun `a session that names every sub and location ID writes all four`() {
        val session = SessionID("FIX.4.4", "CLI", "DESK1", "LDN", "SRV", "VDESK", "NYC", "")

        assertEquals(everySend + setOf(50, 142, 57, 143), SessionTags.writtenOnSend(session))
    }

    @Test
    fun `a captured send drops the engine's tags and the resend markers, and keeps the routing`() {
        assertEquals(everySend + setOf(43, 97, 122, 369), SessionTags.NEVER_REPLAYED)
        assertTrue(listOf(50, 57, 115, 128, 142, 143, 144, 145).none { it in SessionTags.NEVER_REPLAYED })
    }

    /** Echo detection, the venue tag scan and the unclassified-tag warning were all built on these, and keep them. */
    @Test
    fun `the transport envelope is still the nineteen tags its readers were built on`() {
        assertEquals(
            setOf(8, 9, 10, 34, 43, 49, 50, 52, 56, 57, 97, 115, 122, 128, 142, 143, 144, 145, 369),
            SessionTags.TRANSPORT_ENVELOPE,
        )
    }
}
