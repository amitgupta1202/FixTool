package com.knapsack.fixtool.service

import org.slf4j.LoggerFactory
import quickfix.Log
import quickfix.LogFactory
import quickfix.SessionID
import java.util.concurrent.ConcurrentHashMap

/**
 * Wraps a delegate LogFactory to intercept onIncoming() and capture the raw wire message
 * before QuickFIX/J parses it.
 *
 * In QFJ 2.3.1, Log.onIncoming() is called on the MINA I/O thread while fromApp/fromAdmin
 * are called on the QFJ Message Processor thread. Therefore we use a ConcurrentHashMap
 * keyed by message sequence number (tag 34) instead of ThreadLocal.
 */
class RawMessageCapturingLogFactory(
    private val delegate: LogFactory,
) : LogFactory {
    override fun create(sessionID: SessionID): Log {
        val delegateLog = delegate.create(sessionID)
        return RawMessageCapturingLog(delegateLog)
    }

    class RawMessageCapturingLog(
        private val delegate: Log,
    ) : Log {
        companion object {
            private val logger = LoggerFactory.getLogger(RawMessageCapturingLog::class.java)

            // Maps "SenderCompID->TargetCompID:SeqNum" to raw wire message
            private val rawIncomingMessages = ConcurrentHashMap<String, String>()

            /**
             * Extracts a lookup key from a raw FIX message string (with SOH delimiters).
             * Uses tags 49 (SenderCompID), 56 (TargetCompID), and 34 (MsgSeqNum).
             */
            private fun extractKey(rawMessage: String): String? {
                var sender: String? = null
                var target: String? = null
                var seqNum: String? = null
                val delimiter = if (rawMessage.contains('|')) '|' else '\u0001'

                for (field in rawMessage.split(delimiter)) {
                    if (field.isBlank()) continue
                    when {
                        field.startsWith("34=") -> seqNum = field.substring(3)
                        field.startsWith("49=") -> sender = field.substring(3)
                        field.startsWith("56=") -> target = field.substring(3)
                    }
                    if (sender != null && target != null && seqNum != null) break
                }

                return if (sender != null && target != null && seqNum != null) {
                    "$sender->$target:$seqNum"
                } else {
                    null
                }
            }

            /**
             * Retrieves and removes the raw wire message for a given parsed Message.
             * Called from fromApp/fromAdmin on the QFJ Message Processor thread.
             */
            fun getAndRemoveRawIncoming(senderCompID: String, targetCompID: String, msgSeqNum: Int): String? {
                val key = "$senderCompID->$targetCompID:$msgSeqNum"
                return rawIncomingMessages.remove(key)
            }
        }

        override fun onIncoming(message: String) {
            val key = extractKey(message)
            if (key != null) {
                rawIncomingMessages[key] = message
            } else {
                logger.warn("Could not extract key from incoming message, wire bytes will not be available")
            }
            delegate.onIncoming(message)
        }

        override fun onOutgoing(message: String) = delegate.onOutgoing(message)

        override fun onEvent(text: String) = delegate.onEvent(text)

        override fun onErrorEvent(text: String) = delegate.onErrorEvent(text)

        override fun clear() = delegate.clear()
    }
}
