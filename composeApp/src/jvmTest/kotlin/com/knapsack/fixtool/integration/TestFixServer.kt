package com.knapsack.fixtool.integration

import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.io.OutputStream
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
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

    /**
     * **What the venue answers an application message with.** Nothing, by default, so every test written
     * before this existed is unchanged. A load test sets it: one ExecutionReport per order, two for a
     * duplicate, none for a chosen ClOrdID, or one routed to another client's connection.
     */
    @Volatile
    var answer: (request: String) -> List<Reply> = { emptyList() }

    /** One reply the venue sends: its type, its body fields, and the client CompID to send it to, or null for the requester. */
    data class Reply(
        val msgType: String,
        val fields: List<String>,
        val toClient: String? = null,
    )

    /** One client's connection, so a reply can be routed to it and its sequence numbers kept straight. */
    private class Connection(
        val output: OutputStream,
        var senderCompId: String,
        var targetCompId: String,
    ) {
        var seqNum = 1

        @Synchronized
        fun write(msgType: String, fields: List<String>) {
            output.write(buildMessage(msgType, senderCompId, targetCompId, seqNum++, fields).toByteArray(Charsets.ISO_8859_1))
            output.flush()
        }
    }

    /** Connections by the client's own CompID, as its Logon named it. */
    private val connections = ConcurrentHashMap<String, Connection>()

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
                val connection = Connection(it.getOutputStream(), senderCompId = "SERVER", targetCompId = "CLIENT")
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
                                connection.senderCompId = fieldValue(message, 56) ?: connection.senderCompId
                                connection.targetCompId = fieldValue(message, 49) ?: connection.targetCompId
                                connections[connection.targetCompId] = connection
                                logonCount.incrementAndGet()
                                logons.add(connection.targetCompId to connection.senderCompId)
                                val heartBtInt = fieldValue(message, 108) ?: "30"
                                val ackFields = mutableListOf("98=0", "108=$heartBtInt")
                                if (fieldValue(message, 141) == "Y") {
                                    ackFields.add("141=Y")
                                }
                                connection.write("A", ackFields)
                            }
                            "0" -> {
                                // Heartbeat - nothing to do
                            }
                            "1" -> {
                                val testReqId = fieldValue(message, 112)
                                connection.write("0", listOfNotNull(testReqId?.let { id -> "112=$id" }))
                            }
                            "5" -> connection.write("5", emptyList())
                            else -> {
                                applicationMessages.add(message)
                                for (reply in answer(message)) {
                                    val target = reply.toClient?.let { client -> connections[client] } ?: connection
                                    target.write(reply.msgType, reply.fields)
                                }
                            }
                        }
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

        private val execIds = AtomicInteger(0)

        /**
         * An ExecutionReport answering [request]: the client's ClOrdID echoed, a fresh OrderID and ExecID,
         * the given OrdStatus. What a venue says to a NewOrderSingle it accepted.
         */
        fun executionReportFor(request: String, ordStatus: String = "0", toClient: String? = null): Reply {
            val clOrdId = fieldValue(request, 11) ?: "?"
            val n = execIds.incrementAndGet()
            return Reply(
                msgType = "8",
                fields =
                    listOf(
                        "37=O-$n",
                        "11=$clOrdId",
                        "17=E-$n",
                        "150=$ordStatus",
                        "39=$ordStatus",
                        "55=${fieldValue(request, 55) ?: "EUR/USD"}",
                        "54=${fieldValue(request, 54) ?: "1"}",
                        "151=0",
                        "14=0",
                    ),
                toClient = toClient,
            )
        }

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
