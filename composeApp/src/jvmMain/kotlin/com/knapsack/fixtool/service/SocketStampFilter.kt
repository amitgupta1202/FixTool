package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.WireDirection
import org.apache.mina.core.filterchain.IoFilter
import org.apache.mina.core.filterchain.IoFilterAdapter
import org.apache.mina.core.filterchain.IoFilterChainBuilder
import org.apache.mina.core.session.IoSession
import org.apache.mina.core.write.WriteRequest
import org.slf4j.LoggerFactory
import quickfix.Session
import quickfix.SessionID
import quickfix.mina.SessionConnector

/**
 * One message crossing FixTool's socket, and when it did.
 *
 * [sessionId] is the FIX session the socket belongs to, read from the attribute QuickFIX/J leaves on
 * the MINA session; null for the one message that arrives before QuickFIX/J has resolved it, which is
 * a venue's first look at a client's Logon. [wire] is the bytes as they went, SOH and all.
 */
data class SocketStamp(
    val sessionId: SessionID?,
    val direction: WireDirection,
    val wire: String,
    val micros: Long,
)

/**
 * **Stamps every FIX message at the socket boundary — after TLS, before the FIX engine.**
 *
 * The latency panel used to take its stamps in QuickFIX/J's `toApp`/`fromApp` callbacks, and before
 * that from a libpcap capture that needed root and could not read a TLS stream. Neither measured what
 * it claimed. The callback stamp on the way out is taken before serialisation, before MINA's write
 * queue, before encryption and before the socket write; on the way in it is taken after the read,
 * the decrypt, the framing, a hand-off through a queue to QuickFIX/J's single processor thread, the
 * parse and the dictionary validation. A round trip built from those two includes the tool's own
 * engine on both sides, and on a quiet loopback venue the engine was most of the number.
 *
 * This is a MINA `IoFilter`, which is the layer QuickFIX/J is built on and the one place in-process
 * that sees the bytes as they touch the socket:
 *
 * - **SEND** is stamped in `messageSent`, which MINA fires on its processor thread immediately after
 *   the kernel has accepted the last byte of the message. Serialisation, the write queue and TLS
 *   encryption are all behind that instant.
 * - **RECEIVE** is stamped in `messageReceived`, on the same thread, the moment the codec has framed one
 *   message out of the decrypted stream. QuickFIX/J's queue, parse and validation are all ahead of it.
 *
 * Neither needs a privilege, and both work through TLS, because the filter sits **after** the SSL
 * filter in the chain. That position is the one piece of ceremony here: QuickFIX/J builds its chain
 * as *[whatever you gave it] → SslFilter → codec*, appending its own two after yours, so a filter you
 * hand to `setIoFilterChainBuilder` lands at the head and sees ciphertext. So what is handed over is
 * a small installer that, when each connection's chain has been fully built (`sessionCreated`), adds
 * the real filter at the tail — past the SSL filter and past the codec, which is why it receives one
 * decoded `String` per message rather than a buffer of bytes.
 *
 * What it costs the I/O thread per message: one clock read, two map lookups and a callback. Whether
 * anything is *done* with a stamp is decided by whoever owns the sink; a session with latency tracking
 * off drops it on the first null check.
 */
class SocketStampFilter(
    private val sink: (SocketStamp) -> Unit,
    /** How the FIX session is found from the MINA one. Replaceable so a test can stamp without a QuickFIX/J Session. */
    private val sessionIdOf: (IoSession) -> SessionID? = ::quickfixSessionIdOf,
) {
    private val logger = LoggerFactory.getLogger(SocketStampFilter::class.java)

    /**
     * What to give `SessionConnector.setIoFilterChainBuilder` — on the initiator or acceptor, **before**
     * `start()`, because QuickFIX/J reads it once when it builds each connector.
     */
    fun chainBuilder(): IoFilterChainBuilder = IoFilterChainBuilder { chain -> chain.addLast(INSTALLER_NAME, Installer()) }

    /** The head-of-chain stand-in. Passes everything through; its only act is adding [Stamp] at the tail once the chain is complete. */
    private inner class Installer : IoFilterAdapter() {
        override fun sessionCreated(nextFilter: IoFilter.NextFilter, session: IoSession) {
            val chain = session.filterChain
            if (!chain.contains(STAMP_NAME)) chain.addLast(STAMP_NAME, Stamp())
            nextFilter.sessionCreated(session)
        }
    }

    /** The filter that stamps. Lives after the codec, so a message is a `String` in both directions. */
    private inner class Stamp : IoFilterAdapter() {
        override fun messageReceived(nextFilter: IoFilter.NextFilter, session: IoSession, message: Any) {
            // Stamp first, hand on second: nothing FixTool does with the message should be in the number.
            if (message is String) emit(session, WireDirection.RECEIVE, message, CaptureClock.micros())
            nextFilter.messageReceived(session, message)
        }

        override fun messageSent(nextFilter: IoFilter.NextFilter, session: IoSession, writeRequest: WriteRequest) {
            // The codec replaced the request's message with the encoded buffer on the way out and kept
            // the original; under TLS the SSL filter has already unwrapped its own envelope before this
            // fires. Either way the original message is the String QuickFIX/J wrote.
            val original = writeRequest.originalMessage
            if (original is String) emit(session, WireDirection.SEND, original, CaptureClock.micros())
            nextFilter.messageSent(session, writeRequest)
        }

        private fun emit(session: IoSession, direction: WireDirection, wire: String, micros: Long) {
            try {
                sink(SocketStamp(sessionIdOf(session), direction, wire, micros))
            } catch (e: Exception) {
                // A latency sink must never be the reason a FIX message is not delivered.
                logger.warn("Latency stamp dropped: ${e.message}")
            }
        }
    }

    companion object {
        const val INSTALLER_NAME = "fixtool-socket-stamp-installer"
        const val STAMP_NAME = "fixtool-socket-stamp"

        /** The FIX session QuickFIX/J has bound to this connection, or null before it has. */
        fun quickfixSessionIdOf(session: IoSession): SessionID? = (session.getAttribute(SessionConnector.QF_SESSION) as? Session)?.sessionID
    }
}
