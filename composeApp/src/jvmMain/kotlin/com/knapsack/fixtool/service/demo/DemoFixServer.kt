package com.knapsack.fixtool.service.demo

import org.slf4j.LoggerFactory
import quickfix.*
import java.io.File
import java.io.FileInputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Demo FIX Server that accepts connections and sends dummy messages
 * for testing the application without needing a real FIX server
 */
class DemoFixServer : Application {
    companion object {
        private val logger = LoggerFactory.getLogger(DemoFixServer::class.java)
        private const val DEMO_PORT = 19876
        private const val SENDER_COMP_ID = "DEMO_SERVER"

        // Support 4 demo clients
        val DEMO_CLIENTS =
            listOf(
                "DEMO_CLIENT1",
                "DEMO_CLIENT2",
                "DEMO_CLIENT3",
                "DEMO_CLIENT4",
            )

        private val sampleMessages =
            listOf(
                // RFQ Flow - Quote Requests
                "35=R|131=QR001|146=1|55=AAPL|54=1|38=1000|", // Quote Request - Buy AAPL
                "35=R|131=QR002|146=1|55=GOOGL|54=2|38=500|", // Quote Request - Sell GOOGL
                "35=R|131=QR003|146=2|55=MSFT|54=1|38=2000|55=AAPL|54=2|38=1500|", // Multi-symbol Quote Request
                "35=R|131=QR004|146=1|55=TSLA|54=1|38=750|", // Quote Request - Buy TSLA
                "35=R|131=QR005|146=1|55=AMZN|54=2|38=250|", // Quote Request - Sell AMZN
                // Quotes (responses to quote requests)
                "35=S|117=Q001|131=QR001|55=AAPL|132=225.50|133=225.55|134=1000|135=1000|", // Quote for AAPL
                "35=S|117=Q002|131=QR002|55=GOOGL|132=173.20|133=173.25|134=500|135=500|", // Quote for GOOGL
                "35=S|117=Q003|131=QR004|55=TSLA|132=248.75|133=248.85|134=750|135=750|", // Quote for TSLA
                // Execution Reports from accepted quotes
                "35=8|55=AAPL|54=1|38=1000|39=2|150=2|32=1000|31=225.55|11=CLO001|", // Execution Report - Filled
                "35=8|55=GOOGL|54=2|38=500|39=2|150=2|32=500|31=173.20|11=CLO002|", // Execution Report - Filled
                "35=8|55=TSLA|54=1|38=750|39=2|150=2|32=750|31=248.85|11=CLO003|", // Execution Report - Filled
                // Trade Capture Reports
                "35=AE|568=TR001|828=0|55=AAPL|32=1000|31=225.55|54=1|", // Trade Capture - AAPL
                "35=AE|568=TR002|828=0|55=GOOGL|32=500|31=173.20|54=2|", // Trade Capture - GOOGL
                "35=AE|568=TR003|828=0|55=TSLA|32=750|31=248.85|54=1|", // Trade Capture - TSLA
                // Traditional Orders
                "35=D|55=MSFT|54=1|38=2000|40=2|44=425.50|11=CLO004|", // New Order
                "35=8|55=MSFT|54=1|38=2000|39=2|150=2|32=2000|31=425.50|11=CLO004|", // Execution Report
                // Messages with Repeating Groups - Parties
                "35=D|55=AMZN|54=2|38=250|40=2|44=178.25|11=CLO005|453=2|448=BROKER1|447=D|452=1|448=CLIENT1|447=D|452=3|",
                "35=8|55=AMZN|54=2|38=250|39=2|150=2|32=250|31=178.25|11=CLO005|453=3|448=BROKER1|447=D|452=1|" +
                    "448=CLIENT1|447=D|452=3|448=CLEARINGFIRM|447=D|452=4|",
            )
    }

    private var acceptor: SocketAcceptor? = null
    private val activeSessions = mutableMapOf<String, SessionID>() // targetCompID -> SessionID
    private var messageSimulatorThread: Thread? = null

    @Volatile
    private var isRunning = false

    /**
     * Starts the demo FIX server
     */
    fun start() {
        try {
            logger.info("Starting Demo FIX Server on port {}", DEMO_PORT)

            val settings = createSessionSettings()
            val messageStoreFactory = FileStoreFactory(settings)
            val logFactory = FileLogFactory(settings)
            val messageFactory = DefaultMessageFactory()

            acceptor =
                SocketAcceptor(
                    this,
                    messageStoreFactory,
                    settings,
                    logFactory,
                    messageFactory,
                )

            acceptor?.start()
            isRunning = true

            logger.info("Demo FIX Server started successfully")
        } catch (e: Exception) {
            logger.error("Failed to start Demo FIX Server: {}", e.message, e)
            throw e
        }
    }

    /**
     * Stops the demo FIX server
     */
    fun stop() {
        try {
            isRunning = false
            messageSimulatorThread?.interrupt()
            messageSimulatorThread = null

            acceptor?.stop()
            acceptor = null

            logger.info("Demo FIX Server stopped")
        } catch (e: Exception) {
            logger.error("Error stopping Demo FIX Server: {}", e.message, e)
        }
    }

    private fun createSessionSettings(): SessionSettings {
        val configFile = File.createTempFile("demo_quickfix", ".cfg")
        configFile.deleteOnExit()

        val userHome = System.getProperty("user.home")
        val configContent =
            buildString {
                appendLine("[DEFAULT]")
                appendLine("ConnectionType=acceptor")
                appendLine("FileStorePath=$userHome/.fixtool/demo/store")
                appendLine("FileLogPath=$userHome/.fixtool/demo/log")
                appendLine("StartTime=00:00:00")
                appendLine("EndTime=00:00:00")
                appendLine("SocketAcceptPort=$DEMO_PORT")
                appendLine()

                // Create a session for each demo client
                for (clientId in DEMO_CLIENTS) {
                    appendLine("[SESSION]")
                    appendLine("BeginString=FIX.4.4")
                    appendLine("SenderCompID=$SENDER_COMP_ID")
                    appendLine("TargetCompID=$clientId")
                    appendLine("HeartBtInt=30")
                    appendLine("ResetOnLogon=Y")
                    appendLine("ResetOnLogout=N")
                    appendLine("ResetOnDisconnect=N")
                    appendLine()
                }
            }

        // Ensure directories exist
        File("$userHome/.fixtool/demo/store").mkdirs()
        File("$userHome/.fixtool/demo/log").mkdirs()

        configFile.writeText(configContent)
        return SessionSettings(FileInputStream(configFile))
    }

    override fun onCreate(sessionId: SessionID) {
        logger.info("Demo server session created: {}", sessionId)
        val targetCompID = sessionId.targetCompID
        activeSessions[targetCompID] = sessionId
    }

    override fun onLogon(sessionId: SessionID) {
        logger.info("Demo server session logged on: {}", sessionId)
        val targetCompID = sessionId.targetCompID
        activeSessions[targetCompID] = sessionId

        // Start message simulator if not already running
        if (messageSimulatorThread == null || !messageSimulatorThread!!.isAlive) {
            startMessageSimulator()
        }
    }

    override fun onLogout(sessionId: SessionID) {
        logger.info("Demo server session logged out: {}", sessionId)
        val targetCompID = sessionId.targetCompID
        activeSessions.remove(targetCompID)

        // Stop message simulator if no active sessions
        if (activeSessions.isEmpty()) {
            isRunning = false
            messageSimulatorThread?.interrupt()
        }
    }

    override fun toAdmin(message: Message, sessionId: SessionID) {
        logger.info("Demo server toAdmin: {}", message.toString().replace('\u0001', '|'))
    }

    override fun fromAdmin(message: Message, sessionId: SessionID) {
        logger.info("Demo server fromAdmin: {}", message.toString().replace('\u0001', '|'))
    }

    override fun toApp(message: Message, sessionId: SessionID) {
        logger.info("Demo server toApp: {}", message.toString().replace('\u0001', '|'))
    }

    override fun fromApp(message: Message, sessionId: SessionID) {
        logger.info("Demo server fromApp: {}", message.toString().replace('\u0001', '|'))

        // Echo back a response for any message received
        try {
            val response = createDummyResponse(message)
            Thread.sleep(200) // Small delay to simulate processing
            Session.sendToTarget(response, sessionId)
        } catch (e: Exception) {
            logger.error("Error sending demo response: {}", e.message, e)
        }
    }

    private fun startMessageSimulator() {
        messageSimulatorThread =
            thread(name = "DemoFixServer-MessageSimulator") {
                try {
                    Thread.sleep(2000) // Initial delay

                    while (isRunning && !Thread.currentThread().isInterrupted) {
                        // Send messages to all active sessions
                        val sessions = activeSessions.values.toList()
                        if (sessions.isNotEmpty()) {
                            try {
                                val randomMessage = sampleMessages.random()
                                // Send to a random active session
                                val randomSession = sessions.random()
                                val message = createMessageFromRaw(randomMessage, randomSession)
                                Session.sendToTarget(message, randomSession)

                                logger.info(
                                    "Demo server sent periodic message to {}: {}",
                                    randomSession.targetCompID,
                                    randomMessage,
                                )
                            } catch (e: Exception) {
                                logger.error("Error sending periodic demo message: {}", e.message, e)
                            }
                        }

                        // Send messages every 5-10 seconds
                        Thread.sleep((5000..10000).random().toLong())
                    }
                } catch (e: InterruptedException) {
                    logger.info("Demo message simulator interrupted")
                }
            }
    }

    private fun createDummyResponse(request: Message): Message {
        val msgType =
            try {
                request.header.getString(35)
            } catch (e: Exception) {
                "8" // Default to execution report
            }

        return when (msgType) {
            "D" -> { // New Order -> Execution Report + Trade Capture
                val execReport = createExecutionReport(request)
                // Send trade capture report after a short delay
                Thread.sleep(500)
                val sessionId = activeSessions.values.firstOrNull()
                if (sessionId != null) {
                    try {
                        val tradeCaptureReport = createTradeCaptureReport(execReport)
                        Session.sendToTarget(tradeCaptureReport, sessionId)
                    } catch (e: Exception) {
                        logger.error("Error sending trade capture report: {}", e.message)
                    }
                }
                execReport
            }
            "R" -> createQuoteOrReject(request) // Quote Request -> Quote or Reject (20% rejection rate)
            "F" -> createOrderCancelReject(request) // Cancel Request -> Reject
            "G" -> createExecutionReport(request) // Order Replace -> Execution Report
            else -> createExecutionReport(request) // Default response
        }
    }

    private fun createExecutionReport(request: Message): Message {
        val message = Message()
        message.header.setString(8, "FIX.4.4")
        message.header.setString(35, "8") // Execution Report
        // Use request's sender/target (swapped for response)
        message.header.setString(49, request.header.getString(56)) // Response sender = request target
        message.header.setString(56, request.header.getString(49)) // Response target = request sender

        // Copy some fields from request if available
        try {
            if (request.isSetField(11)) message.setString(11, request.getString(11)) // ClOrdID
            if (request.isSetField(55)) message.setString(55, request.getString(55)) // Symbol
            if (request.isSetField(54)) message.setString(54, request.getString(54)) // Side
            if (request.isSetField(38)) {
                val orderQty = request.getString(38)
                message.setString(38, orderQty) // OrderQty
                message.setString(32, orderQty) // LastQty - filled quantity
                message.setString(31, "%.2f".format(Random.Default.nextDouble(100.0, 500.0))) // LastPx
            }
        } catch (e: Exception) {
            // Ignore missing fields
        }

        message.setString(37, "ORD${System.currentTimeMillis()}") // OrderID
        message.setString(17, "EXEC${System.currentTimeMillis()}") // ExecID
        message.setString(150, "2") // ExecType - Filled
        message.setString(39, "2") // OrdStatus - Filled
        message.setString(
            60,
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS")),
        ) // TransactTime

        return message
    }

    private fun createOrderCancelReject(request: Message): Message {
        val message = Message()
        message.header.setString(8, "FIX.4.4")
        message.header.setString(35, "9") // Order Cancel Reject
        // Use request's sender/target (swapped for response)
        message.header.setString(49, request.header.getString(56))
        message.header.setString(56, request.header.getString(49))

        try {
            if (request.isSetField(11)) message.setString(11, request.getString(11))
            if (request.isSetField(41)) message.setString(41, request.getString(41))
        } catch (e: Exception) {
            // Ignore
        }

        message.setString(37, "ORD${System.currentTimeMillis()}")
        message.setString(39, "0") // OrdStatus - New
        message.setString(434, "1") // CxlRejResponseTo - Order Cancel Request
        message.setString(102, "0") // CxlRejReason - Too late to cancel

        return message
    }

    private fun createMessageFromRaw(rawMessage: String, sessionId: SessionID): Message {
        val message = Message()
        message.header.setString(8, "FIX.4.4")
        message.header.setString(49, sessionId.senderCompID)
        message.header.setString(56, sessionId.targetCompID)

        val fields = rawMessage.split("|").filter { it.isNotBlank() }
        for (field in fields) {
            val parts = field.split("=")
            if (parts.size == 2) {
                val tag = parts[0].toIntOrNull()
                if (tag != null) {
                    when (tag) {
                        8, 9, 35, 49, 56, 34, 52 -> message.header.setString(tag, parts[1])
                        10 -> {} // Checksum - skip
                        else -> message.setString(tag, parts[1])
                    }
                }
            }
        }

        // Set required header fields if not present
        if (!message.header.isSetField(35)) message.header.setString(35, "8") // Default to ExecutionReport
        if (!message.header.isSetField(52)) {
            message.header.setString(
                52,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS")),
            )
        }

        return message
    }

    /**
     * Creates either a Quote or Quote Request Reject (20% chance of rejection)
     */
    private fun createQuoteOrReject(request: Message): Message {
        // 20% chance of rejection
        return if (Random.Default.nextDouble() < 0.2) {
            createQuoteRequestReject(request)
        } else {
            createQuote(request)
        }
    }

    /**
     * Creates a Quote response for a Quote Request
     */
    private fun createQuote(request: Message): Message {
        val message = Message()
        message.header.setString(8, "FIX.4.4")
        message.header.setString(35, "S") // Quote
        message.header.setString(49, request.header.getString(56))
        message.header.setString(56, request.header.getString(49))

        try {
            // QuoteReqID - reference to the request
            if (request.isSetField(131)) {
                message.setString(131, request.getString(131))
            }

            // QuoteID - unique quote identifier
            message.setString(117, "Q${System.currentTimeMillis()}")

            // Symbol
            val symbol = if (request.isSetField(55)) request.getString(55) else "UNKNOWN"
            message.setString(55, symbol)

            // Generate bid/ask prices based on symbol
            val (bidPx, offerPx) = generateBidAskPrices(symbol)
            message.setString(132, "%.2f".format(bidPx)) // BidPx
            message.setString(133, "%.2f".format(offerPx)) // OfferPx

            // BidSize and OfferSize (match requested quantity if available)
            val qty = if (request.isSetField(38)) request.getString(38) else "1000"
            message.setString(134, qty) // BidSize
            message.setString(135, qty) // OfferSize

            // Side (copy from request)
            if (request.isSetField(54)) {
                message.setString(54, request.getString(54))
            }

            // ValidUntilTime - quote valid for 30 seconds
            message.setString(
                62,
                LocalDateTime.now().plusSeconds(30).format(DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss")),
            )
        } catch (e: Exception) {
            logger.error("Error creating quote: {}", e.message, e)
        }

        return message
    }

    /**
     * Creates a Quote Request Reject
     */
    private fun createQuoteRequestReject(request: Message): Message {
        val message = Message()
        message.header.setString(8, "FIX.4.4")
        message.header.setString(35, "AG") // Quote Request Reject
        message.header.setString(49, request.header.getString(56))
        message.header.setString(56, request.header.getString(49))

        try {
            // QuoteReqID
            if (request.isSetField(131)) {
                message.setString(131, request.getString(131))
            }

            // QuoteRequestRejectReason
            // 1=Unknown symbol, 2=Exchange closed, 3=Quote Request exceeds limit,
            // 4=Too late to enter, 5=Invalid price, 6=Not authorized to quote security, 99=Other
            val rejectReasons = listOf(1, 2, 3, 6, 99)
            val rejectReason = rejectReasons.random()
            message.setString(658, rejectReason.toString()) // QuoteRequestRejectReason
            message.setString(693, rejectReason.toString()) // QuoteRequestRejectReason (FIX 4.4)

            // Text - human readable reason
            val rejectText =
                when (rejectReason) {
                    1 -> "Unknown symbol"
                    2 -> "Exchange closed"
                    3 -> "Quote request exceeds limit"
                    6 -> "Not authorized to quote security"
                    else -> "Quote request rejected"
                }
            message.setString(58, rejectText)
        } catch (e: Exception) {
            logger.error("Error creating quote request reject: {}", e.message, e)
        }

        return message
    }

    /**
     * Creates a Trade Capture Report from an execution report
     */
    private fun createTradeCaptureReport(executionReport: Message): Message {
        val message = Message()
        message.header.setString(8, "FIX.4.4")
        message.header.setString(35, "AE") // Trade Capture Report
        message.header.setString(49, executionReport.header.getString(49))
        message.header.setString(56, executionReport.header.getString(56))

        try {
            // TradeRequestID
            message.setString(568, "TR${System.currentTimeMillis()}")

            // TradeReportID - unique identifier for this trade report
            message.setString(571, "TRD${System.currentTimeMillis()}")

            // TrdType - 0=Regular Trade, 1=Block Trade, 2=EFP
            message.setString(828, "0")

            // ExecID - reference to execution
            if (executionReport.isSetField(17)) {
                message.setString(17, executionReport.getString(17))
            }

            // Symbol
            if (executionReport.isSetField(55)) {
                message.setString(55, executionReport.getString(55))
            }

            // Side
            if (executionReport.isSetField(54)) {
                message.setString(54, executionReport.getString(54))
            }

            // LastQty - trade quantity
            if (executionReport.isSetField(32)) {
                message.setString(32, executionReport.getString(32))
            }

            // LastPx - trade price
            if (executionReport.isSetField(31)) {
                message.setString(31, executionReport.getString(31))
            }

            // TradeDate
            message.setString(75, LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")))

            // TransactTime
            message.setString(
                60,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS")),
            )
        } catch (e: Exception) {
            logger.error("Error creating trade capture report: {}", e.message, e)
        }

        return message
    }

    /**
     * Generates realistic bid/ask prices based on symbol
     */
    private fun generateBidAskPrices(symbol: String): Pair<Double, Double> {
        // Base prices for common symbols
        val basePrice =
            when (symbol) {
                "AAPL" -> 225.0
                "GOOGL" -> 173.0
                "MSFT" -> 425.0
                "TSLA" -> 248.0
                "AMZN" -> 178.0
                "META" -> 495.0
                "NVDA" -> 875.0
                "AMD" -> 165.0
                else -> Random.Default.nextDouble(100.0, 500.0)
            }

        // Add some random variation (+/- 2%)
        val variation = basePrice * Random.Default.nextDouble(-0.02, 0.02)
        val midPrice = basePrice + variation

        // Spread is typically 0.05% to 0.1% of price
        val spread = midPrice * Random.Default.nextDouble(0.0005, 0.001)

        val bidPx = midPrice - (spread / 2)
        val offerPx = midPrice + (spread / 2)

        return Pair(bidPx, offerPx)
    }
}
