package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.WireDirection
import org.apache.mina.core.buffer.IoBuffer
import org.apache.mina.core.filterchain.IoFilter
import org.apache.mina.core.filterchain.IoFilterAdapter
import org.apache.mina.core.session.DummySession
import org.apache.mina.core.session.IoSession
import org.apache.mina.core.write.DefaultWriteRequest
import org.junit.Test
import quickfix.SessionID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The filter sits after the codec, and stamps the string QuickFIX/J wrote or read.**
 *
 * Built against a MINA `DummySession` the way QuickFIX/J builds a real chain: our builder runs first,
 * then QuickFIX/J appends its codec (and, on a TLS session, its SSL filter before that). What these
 * tests pin is the relocation — that the stamping filter ends up *after* the codec, where a message is a
 * `String` in both directions — and that the outbound stamp reads the original message rather than the
 * encoded buffer the codec swapped in. The real-socket behaviour, plain and TLS, is
 * `LatencyIntegrationTest`'s to prove.
 */
class SocketStampFilterTest {
    private val soh = Char(1)
    private val sessionId = SessionID("FIX.4.4", "CLI", "VENUE")
    private val stamps = mutableListOf<SocketStamp>()

    private fun order(id: String) = "8=FIX.4.4${soh}9=40${soh}35=D${soh}11=$id${soh}55=EUR/USD${soh}10=000$soh"

    /** A chain built the way QuickFIX/J builds one: ours first, its codec appended after, then the session comes up. */
    private fun chainWith(filter: SocketStampFilter): DummySession {
        val session = DummySession()
        filter.chainBuilder().buildFilterChain(session.filterChain)
        session.filterChain.addLast("FIXCodec", IoFilterAdapter())
        session.filterChain.fireSessionCreated()
        return session
    }

    @Test
    fun `the stamping filter is installed after the codec, not where the builder put it`() {
        val session = chainWith(SocketStampFilter({ stamps += it }, { sessionId }))

        assertEquals(
            listOf(SocketStampFilter.INSTALLER_NAME, "FIXCodec", SocketStampFilter.STAMP_NAME),
            session.filterChain.all.map { it.name },
            "the installer stays at the head as a pass-through; the stamp goes to the tail, past the codec",
        )
    }

    @Test
    fun `a second sessionCreated does not install a second stamp`() {
        val session = chainWith(SocketStampFilter({ stamps += it }, { sessionId }))
        session.filterChain.fireSessionCreated()

        assertEquals(1, session.filterChain.all.count { it.name == SocketStampFilter.STAMP_NAME })
    }

    @Test
    fun `a decoded message is stamped as a receive, with the session it belongs to`() {
        val session = chainWith(SocketStampFilter({ stamps += it }, { sessionId }))
        val wire = order("ORD-1").replace("35=D", "35=8")

        session.filterChain.fireMessageReceived(wire)

        val stamp = stamps.single()
        assertEquals(WireDirection.RECEIVE, stamp.direction)
        assertEquals(wire, stamp.wire, "the bytes as they arrived, SOH and all")
        assertEquals(sessionId, stamp.sessionId)
        assertTrue(stamp.micros > 0)
    }

    @Test
    fun `a written message is stamped as a send from the original string, not the encoded buffer`() {
        val session = chainWith(SocketStampFilter({ stamps += it }, { sessionId }))
        val wire = order("ORD-2")
        val request = DefaultWriteRequest(wire)
        // What the codec does on the way out: the request keeps travelling, carrying the bytes instead.
        request.setMessage(IoBuffer.wrap(wire.toByteArray()))

        session.filterChain.fireMessageSent(request)

        val stamp = stamps.single()
        assertEquals(WireDirection.SEND, stamp.direction)
        assertEquals(wire, stamp.wire)
    }

    @Test
    fun `bytes that are not a decoded message are not stamped`() {
        val session = chainWith(SocketStampFilter({ stamps += it }, { sessionId }))

        session.filterChain.fireMessageReceived(IoBuffer.wrap(byteArrayOf(1, 2, 3)))
        session.filterChain.fireMessageSent(DefaultWriteRequest(IoBuffer.wrap(byteArrayOf(1, 2, 3))))

        assertTrue(stamps.isEmpty(), "a raw buffer is a TLS record or a handshake, not a FIX message")
    }

    @Test
    fun `a connection QuickFIX has not bound to a session yet stamps with no session id`() {
        // The default resolver reads the attribute QuickFIX/J sets; a DummySession has none.
        val session = chainWith(SocketStampFilter({ stamps += it }))

        session.filterChain.fireMessageReceived(order("ORD-3").replace("35=D", "35=A"))

        assertNull(stamps.single().sessionId, "a venue's first sight of a client's Logon")
    }

    @Test
    fun `a sink that throws does not stop the message reaching the engine`() {
        val reached = mutableListOf<Any>()
        val session = chainWith(SocketStampFilter({ throw IllegalStateException("boom") }, { sessionId }))
        session.filterChain.addLast(
            "probe",
            object : IoFilterAdapter() {
                override fun messageReceived(nextFilter: IoFilter.NextFilter, session: IoSession, message: Any) {
                    reached += message
                    nextFilter.messageReceived(session, message)
                }
            },
        )

        session.filterChain.fireMessageReceived(order("ORD-4"))

        assertEquals(1, reached.size, "a latency sink is never the reason a FIX message is not delivered")
    }

    @Test
    fun `the stamp is taken before the message is handed on`() {
        var handedOnAt = 0L
        val session = chainWith(SocketStampFilter({ stamps += it }, { sessionId }))
        session.filterChain.addLast(
            "probe",
            object : IoFilterAdapter() {
                override fun messageReceived(nextFilter: IoFilter.NextFilter, session: IoSession, message: Any) {
                    handedOnAt = CaptureClock.micros()
                    nextFilter.messageReceived(session, message)
                }
            },
        )

        session.filterChain.fireMessageReceived(order("ORD-5"))

        assertTrue(stamps.single().micros <= handedOnAt, "nothing downstream is in the number")
    }
}
