package com.knapsack.fixtool.integration

import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal raw-socket FIX acceptor for integration tests.
 *
 * Unlike a QuickFIX/J acceptor, this server accepts any number of CONCURRENT connections
 * that log on with the SAME SenderCompID/TargetCompID - which is exactly what the
 * multi-session (sessionCount > 1) feature produces on the wire, since SessionQualifier
 * is a client-side-only discriminator.
 *
 * Behavior per connection: answers Logon (35=A) with a Logon ack, answers TestRequest (35=1)
 * with a Heartbeat, answers Logout (35=5) with a Logout, ignores Heartbeats, and records
 * every other message in [applicationMessages].
 */
class TestFixServer {
    private lateinit var serverSocket: ServerSocket
    private val executor =
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable).apply { isDaemon = true }
        }

    @Volatile
    private var running = false

    /** Number of Logon messages received across all connections. */
    val logonCount = AtomicInteger(0)

    /** SenderCompID -> TargetCompID of every Logon received, in arrival order. */
    val logons: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())

    /** Number of currently open client connections. */
    val activeConnections = AtomicInteger(0)

    /** Raw application-level (non-admin) messages received, in arrival order. */
    val applicationMessages: MutableList<String> = Collections.synchronizedList(mutableListOf())

    val port: Int
        get() = serverSocket.localPort

    fun start() {
        serverSocket = ServerSocket(0)
        running = true
        executor.submit {
            while (running) {
                try {
                    val socket = serverSocket.accept()
                    executor.submit { handleConnection(socket) }
                } catch (e: SocketException) {
                    // Server socket closed during stop()
                }
            }
        }
    }

    fun stop() {
        running = false
        serverSocket.close()
        executor.shutdownNow()
    }

    private fun handleConnection(socket: Socket) {
        activeConnections.incrementAndGet()
        try {
            socket.use {
                val input = it.getInputStream()
                val output = it.getOutputStream()
                var seqNum = 1
                var senderCompId = "SERVER"
                var targetCompId = "CLIENT"
                val buffer = StringBuilder()
                val bytes = ByteArray(8192)

                while (running) {
                    val read = input.read(bytes)
                    if (read < 0) break
                    buffer.append(String(bytes, 0, read, Charsets.ISO_8859_1))

                    while (true) {
                        val message = extractMessage(buffer) ?: break
                        when (fieldValue(message, 35)) {
                            "A" -> {
                                // Reply with comp IDs mirrored from the client's logon
                                senderCompId = fieldValue(message, 56) ?: senderCompId
                                targetCompId = fieldValue(message, 49) ?: targetCompId
                                logonCount.incrementAndGet()
                                logons.add(targetCompId to senderCompId)
                                val heartBtInt = fieldValue(message, 108) ?: "30"
                                val ackFields = mutableListOf("98=0", "108=$heartBtInt")
                                if (fieldValue(message, 141) == "Y") {
                                    ackFields.add("141=Y")
                                }
                                output.write(
                                    buildMessage("A", senderCompId, targetCompId, seqNum++, ackFields)
                                        .toByteArray(Charsets.ISO_8859_1),
                                )
                            }
                            "0" -> {
                                // Heartbeat - nothing to do
                            }
                            "1" -> {
                                val testReqId = fieldValue(message, 112)
                                output.write(
                                    buildMessage("0", senderCompId, targetCompId, seqNum++, listOfNotNull(testReqId?.let { id -> "112=$id" }))
                                        .toByteArray(Charsets.ISO_8859_1),
                                )
                            }
                            "5" -> {
                                output.write(
                                    buildMessage("5", senderCompId, targetCompId, seqNum++, emptyList())
                                        .toByteArray(Charsets.ISO_8859_1),
                                )
                            }
                            else -> applicationMessages.add(message)
                        }
                        output.flush()
                    }
                }
            }
        } catch (e: Exception) {
            // Connection torn down - expected during client disconnect or server stop
        } finally {
            activeConnections.decrementAndGet()
        }
    }

    companion object {
        private const val SOH = '\u0001'
        private val CHECKSUM_FIELD_REGEX = Regex("${SOH}10=\\d{3}$SOH")
        private val SENDING_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss").withZone(ZoneOffset.UTC)

        private fun extractMessage(buffer: StringBuilder): String? {
            val checksumField = CHECKSUM_FIELD_REGEX.find(buffer) ?: return null
            val message = buffer.substring(0, checksumField.range.last + 1)
            buffer.delete(0, checksumField.range.last + 1)
            return message
        }

        fun fieldValue(message: String, tag: Int): String? =
            Regex("(^|$SOH)$tag=([^$SOH]*)$SOH").find(message)?.groupValues?.get(2)

        private fun buildMessage(
            msgType: String,
            senderCompId: String,
            targetCompId: String,
            seqNum: Int,
            extraFields: List<String>,
        ): String {
            val body =
                buildString {
                    append("35=$msgType$SOH")
                    append("49=$senderCompId$SOH")
                    append("56=$targetCompId$SOH")
                    append("34=$seqNum$SOH")
                    append("52=${SENDING_TIME_FORMAT.format(Instant.now())}$SOH")
                    extraFields.forEach { append("$it$SOH") }
                }
            val header = "8=FIX.4.4${SOH}9=${body.length}$SOH"
            val checksum = (header + body).sumOf { it.code } % 256
            return header + body + "10=%03d$SOH".format(checksum)
        }
    }
}
