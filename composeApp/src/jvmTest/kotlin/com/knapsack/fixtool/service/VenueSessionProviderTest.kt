package com.knapsack.fixtool.service

import org.junit.After
import org.junit.Test
import quickfix.Application
import quickfix.DefaultMessageFactory
import quickfix.MemoryStoreFactory
import quickfix.Message
import quickfix.SLF4JLogFactory
import quickfix.Session
import quickfix.SessionID
import quickfix.SessionSettings
import java.io.ByteArrayInputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Who a venue will and will not serve.**
 *
 * The wildcard is not "accept everything": it wildcards the counterparty and leaves this acceptor's
 * own identity pinned. That distinction is the whole test — an acceptor that answered to any name
 * would turn a client's misconfiguration into a green run, and a client addressing the wrong venue
 * is a failure that must survive the simulator.
 *
 * The refusals also have to be *reported*. QuickFIX/J's own answer to an unknown logon is silence,
 * so a returned null with nothing recorded would be indistinguishable from the venue never having
 * been reached at all.
 */
class VenueSessionProviderTest {
    private val template = SessionID("FIX.4.4", "VENUE", "*")
    private val refused = mutableListOf<SessionID>()
    private val created = mutableListOf<SessionID>()

    private val settings: SessionSettings =
        SessionSettings(
            ByteArrayInputStream(
                """
                [DEFAULT]
                ConnectionType=acceptor
                StartTime=00:00:00
                EndTime=00:00:00
                HeartBtInt=30
                SocketAcceptPort=0

                [SESSION]
                BeginString=FIX.4.4
                SenderCompID=VENUE
                TargetCompID=*
                AcceptorTemplate=Y
                """.trimIndent().toByteArray(),
            ),
        )

    private val provider =
        VenueSessionProvider(
            settings = settings,
            template = template,
            application = SilentApplication(),
            messageStoreFactory = MemoryStoreFactory(),
            logFactory = SLF4JLogFactory(settings),
            messageFactory = DefaultMessageFactory(),
            onRefused = { refused += it },
        )

    @After
    fun cleanup() {
        // Sessions register themselves in a JVM-static map; a leaked one collides with the next test
        // that happens to use the same CompIDs.
        created.forEach { Session.lookupSession(it)?.close() }
    }

    @Test
    fun `a logon addressed to this venue gets a session`() {
        val client = SessionID("FIX.4.4", "VENUE", "CLIENT_A")
        val session = open(client)

        assertNotNull(session, "a client addressing VENUE should be served")
        assertEquals(client, session.sessionID)
        assertTrue(refused.isEmpty(), "nothing was refused")
    }

    @Test
    fun `a logon addressed to another acceptor is refused, and said so`() {
        val misaddressed = SessionID("FIX.4.4", "NOT_VENUE", "CLIENT_B")

        assertNull(open(misaddressed), "we are VENUE; this logon is for someone else")
        assertEquals(listOf(misaddressed), refused, "a refusal nobody hears is the failure we are fixing")
    }

    @Test
    fun `a logon on another FIX version is refused`() {
        // The dictionary is loaded for one version. Accepting another would produce a session whose
        // every message is judged against the wrong definitions.
        val wrongVersion = SessionID("FIX.4.2", "VENUE", "CLIENT_C")

        assertNull(open(wrongVersion))
        assertEquals(listOf(wrongVersion), refused)
    }

    @Test
    fun `sub and location IDs do not stop a client being served`() {
        // A client that sets TargetSubID is addressing us correctly by every meaning that matters,
        // and the profile never configured a sub ID to compare it against.
        val subbed = SessionID("FIX.4.4", "VENUE", "TRADING", "LDN", "CLIENT_D", "DESK", "NYC", null)

        assertNotNull(open(subbed), "a sub ID is not a different venue")
        assertTrue(refused.isEmpty())
    }

    private fun open(sessionId: SessionID): Session? =
        provider.getSession(sessionId, null)?.also { created += sessionId }

    private class SilentApplication : Application {
        override fun onCreate(sessionId: SessionID) = Unit

        override fun onLogon(sessionId: SessionID) = Unit

        override fun onLogout(sessionId: SessionID) = Unit

        override fun toAdmin(message: Message, sessionId: SessionID) = Unit

        override fun fromAdmin(message: Message, sessionId: SessionID) = Unit

        override fun toApp(message: Message, sessionId: SessionID) = Unit

        override fun fromApp(message: Message, sessionId: SessionID) = Unit
    }
}
