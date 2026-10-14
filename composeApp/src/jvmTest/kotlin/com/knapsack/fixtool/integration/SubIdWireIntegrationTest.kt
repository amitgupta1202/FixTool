package com.knapsack.fixtool.integration

import org.junit.After
import org.junit.Before
import org.junit.Test
import quickfix.SessionID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **A SubID the author sends is a SubID the venue receives**, on real sockets.
 *
 * The editor and capture dropped SenderSubID on the premise that QuickFIX/J writes it on the way out. It writes
 * one only when the session's own SessionID names it, and leaves the message's value alone otherwise, so on a
 * session that names none the value the author typed is the value that arrives. This holds the premise the
 * editor's and capture's filtering now rest on.
 */
class SubIdWireIntegrationTest {
    private lateinit var venue: VenueHarness

    @Before
    fun setup() {
        venue = VenueHarness("fixtool-subid-wire")
    }

    @After
    fun cleanup() {
        venue.close()
    }

    @Test
    fun `a SenderSubID sent on a session that names none arrives at the venue as it was sent`() {
        venue.connectVenue(rules = emptyList())
        val client = venue.connectClient("CLIENT")
        val session = assertNotNull(client.sessionId())
        assertEquals(SessionID.NOT_SET, session.senderSubID, "the profile gives the session no SubID")
        val pane = venue.awaitPane("CLIENT")

        venue.send(client, "35=D|50=DESK1|11=ORD-1|55=IBM|54=1|38=100|40=1|60=20261014-09:30:00|")

        assertTrue(
            venue.awaitCondition(10_000) { venue.incoming(pane, "D").isNotEmpty() },
            "the venue should receive the order",
        )
        assertEquals("DESK1", venue.field(venue.incoming(pane, "D").single(), 50))
    }
}
