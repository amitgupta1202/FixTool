package com.knapsack.fixtool.service.demo

import com.knapsack.fixtool.model.FixVersion
import org.slf4j.LoggerFactory
import quickfix.*
import java.io.File
import java.io.FileInputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Demo FIX Server that simulates an FX exchange.
 * Supports market data, order entry, and execution for major FX pairs.
 *
 * @param fixVersion The FIX version to use for sessions (default: FIX 4.4)
 */
class DemoFixServer(
    private val fixVersion: FixVersion = FixVersion.FIX_4_4,
) : Application {
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
    }

    private var acceptor: SocketAcceptor? = null
    private val activeSessions = mutableMapOf<String, SessionID>()
    private var priceTickThread: Thread? = null
    private var responseScheduler: ScheduledExecutorService? = null

    @Volatile
    private var isRunning = false

    // FX Exchange components
    private var marketData: FxMarketData? = null
    private var orderBook: FxOrderBook? = null
    private var orderProcessor: FxOrderProcessor? = null

    /**
     * Starts the demo FIX server
     */
    fun start() {
        try {
            logger.info("Starting Demo FX Server on port {}", DEMO_PORT)

            // Initialize FX components
            marketData = FxMarketData()
            orderBook = FxOrderBook()
            orderProcessor = FxOrderProcessor(marketData!!, orderBook!!)

            // Set up execution report callback for resting order fills
            orderProcessor?.onExecutionReport = { order, execType, sessionId ->
                try {
                    val execReport = createExecutionReport(order, execType)
                    Session.sendToTarget(execReport, sessionId)
                } catch (e: Exception) {
                    logger.error("Error sending execution report: {}", e.message)
                }
            }

            // Initialize response scheduler for non-blocking delays
            responseScheduler = Executors.newScheduledThreadPool(4)

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

            logger.info("Demo FX Server started successfully")
        } catch (e: Exception) {
            logger.error("Failed to start Demo FX Server: {}", e.message, e)
            throw e
        }
    }

    /**
     * Stops the demo FIX server
     */
    fun stop() {
        try {
            isRunning = false
            priceTickThread?.interrupt()
            priceTickThread = null

            // Shutdown response scheduler
            responseScheduler?.shutdown()
            try {
                if (responseScheduler?.awaitTermination(2, TimeUnit.SECONDS) == false) {
                    responseScheduler?.shutdownNow()
                }
            } catch (e: InterruptedException) {
                responseScheduler?.shutdownNow()
            }
            responseScheduler = null

            acceptor?.stop()
            acceptor = null

            // Clear FX components
            orderProcessor?.clear()
            marketData?.clear()
            orderBook?.clear()
            marketData = null
            orderBook = null
            orderProcessor = null

            logger.info("Demo FX Server stopped")
        } catch (e: Exception) {
            logger.error("Error stopping Demo FX Server: {}", e.message, e)
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
                    appendLine("BeginString=${fixVersion.beginString}")
                    appendLine("SenderCompID=$SENDER_COMP_ID")
                    appendLine("TargetCompID=$clientId")
                    appendLine("HeartBtInt=30")
                    // Add DefaultApplVerID for FIX 5.0+ sessions
                    if (fixVersion.isFix50Plus && fixVersion.applVerID != null) {
                        appendLine("DefaultApplVerID=${fixVersion.applVerID}")
                    }
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

        // Start price tick thread if not already running
        if (priceTickThread == null || !priceTickThread!!.isAlive) {
            startPriceTickThread()
        }
    }

    override fun onLogout(sessionId: SessionID) {
        logger.info("Demo server session logged out: {}", sessionId)
        val targetCompID = sessionId.targetCompID
        activeSessions.remove(targetCompID)

        // Stop price tick thread if no active sessions
        if (activeSessions.isEmpty()) {
            isRunning = false
            priceTickThread?.interrupt()
        }
    }

    override fun toAdmin(message: Message, sessionId: SessionID) {
        logger.debug("Demo server toAdmin: {}", message.toString().replace('\u0001', '|'))
    }

    override fun fromAdmin(message: Message, sessionId: SessionID) {
        logger.debug("Demo server fromAdmin: {}", message.toString().replace('\u0001', '|'))
    }

    override fun toApp(message: Message, sessionId: SessionID) {
        logger.debug("Demo server toApp: {}", message.toString().replace('\u0001', '|'))
    }

    override fun fromApp(message: Message, sessionId: SessionID) {
        logger.info("Demo server fromApp: {}", message.toString().replace('\u0001', '|'))

        // Process message with a small delay (non-blocking)
        responseScheduler?.schedule({
            try {
                processMessage(message, sessionId)
            } catch (e: Exception) {
                logger.error("Error processing message: {}", e.message, e)
            }
        }, 100, TimeUnit.MILLISECONDS)
    }

    /**
     * Processes incoming FIX messages and generates appropriate responses
     */
    private fun processMessage(message: Message, sessionId: SessionID) {
        val msgType =
            try {
                message.header.getString(35)
            } catch (e: Exception) {
                "8"
            }

        when (msgType) {
            "D" -> processNewOrderSingle(message, sessionId)
            "F" -> processOrderCancelRequest(message, sessionId)
            "R" -> processQuoteRequest(message, sessionId)
            "G" -> processOrderCancelReplaceRequest(message, sessionId)
            else -> logger.info("Unhandled message type: {}", msgType)
        }
    }

    /**
     * Processes NewOrderSingle (35=D) messages
     */
    private fun processNewOrderSingle(message: Message, sessionId: SessionID) {
        val result = orderProcessor?.processNewOrder(message, sessionId)

        when (result) {
            is OrderResult.Accepted -> {
                // Send New acknowledgment
                val execReport = createExecutionReport(result.order, ExecType.NEW)
                Session.sendToTarget(execReport, sessionId)
            }
            is OrderResult.Filled -> {
                // Send fill report
                val execReport = createExecutionReport(result.order, result.execType)
                Session.sendToTarget(execReport, sessionId)
            }
            is OrderResult.Rejected -> {
                // Send reject
                val rejectReport =
                    createRejectExecutionReport(
                        message,
                        result.reason,
                        result.reasonCode,
                    )
                Session.sendToTarget(rejectReport, sessionId)
            }
            else -> {}
        }
    }

    /**
     * Processes OrderCancelRequest (35=F) messages
     */
    private fun processOrderCancelRequest(message: Message, sessionId: SessionID) {
        val result = orderProcessor?.processCancelRequest(message, sessionId)

        when (result) {
            is OrderResult.Canceled -> {
                val execReport = createExecutionReport(result.order, ExecType.CANCELED)
                Session.sendToTarget(execReport, sessionId)
            }
            is OrderResult.CancelRejected -> {
                val rejectReport = createOrderCancelReject(message, result.reason)
                Session.sendToTarget(rejectReport, sessionId)
            }
            else -> {}
        }
    }

    /**
     * Processes QuoteRequest (35=R) messages
     */
    private fun processQuoteRequest(message: Message, sessionId: SessionID) {
        val quote = orderProcessor?.processQuoteRequest(message, sessionId)

        if (quote != null) {
            val quoteMsg = createQuote(message, quote)
            Session.sendToTarget(quoteMsg, sessionId)
        } else {
            val rejectMsg = createQuoteRequestReject(message)
            Session.sendToTarget(rejectMsg, sessionId)
        }
    }

    /**
     * Processes OrderCancelReplaceRequest (35=G) messages
     * For simplicity, we reject all replace requests
     */
    private fun processOrderCancelReplaceRequest(message: Message, sessionId: SessionID) {
        val rejectReport = createOrderCancelReject(message, "Order replace not supported")
        Session.sendToTarget(rejectReport, sessionId)
    }

    /**
     * Creates an ExecutionReport from an FxOrder
     */
    private fun createExecutionReport(order: FxOrder, execType: ExecType): Message {
        val message = Message()
        message.header.setString(8, fixVersion.beginString)
        message.header.setString(35, "8") // ExecutionReport

        message.setString(37, order.orderId) // OrderID
        message.setString(11, order.clOrdId) // ClOrdID
        message.setString(17, "EXEC${System.currentTimeMillis()}") // ExecID
        message.setString(150, execType.fixValue) // ExecType
        message.setString(39, order.ordStatus.fixValue) // OrdStatus
        message.setString(55, order.symbol) // Symbol
        message.setString(54, order.side.fixValue) // Side
        message.setString(38, order.orderQty.toString()) // OrderQty
        message.setString(40, order.ordType.fixValue) // OrdType
        message.setString(151, order.leavesQty.toString()) // LeavesQty
        message.setString(14, order.cumQty.toString()) // CumQty
        message.setString(6, formatPrice(order.avgPx, order.symbol)) // AvgPx

        if (order.price != null) {
            message.setString(44, formatPrice(order.price, order.symbol)) // Price
        }

        if (order.lastQty > 0) {
            message.setString(32, order.lastQty.toString()) // LastQty
            message.setString(31, formatPrice(order.lastPx, order.symbol)) // LastPx
        }

        if (order.account != null) {
            message.setString(1, order.account) // Account
        }

        message.setString(15, order.currency) // Currency
        message.setString(60, FxOrderProcessor.formatTimestamp()) // TransactTime

        return message
    }

    /**
     * Creates a rejection ExecutionReport
     */
    private fun createRejectExecutionReport(
        request: Message,
        reason: String,
        reasonCode: Int,
    ): Message {
        val message = Message()
        message.header.setString(8, fixVersion.beginString)
        message.header.setString(35, "8") // ExecutionReport

        message.setString(37, "REJECTED") // OrderID
        if (request.isSetField(11)) {
            message.setString(11, request.getString(11)) // ClOrdID
        }
        message.setString(17, "EXEC${System.currentTimeMillis()}") // ExecID
        message.setString(150, ExecType.REJECTED.fixValue) // ExecType
        message.setString(39, OrdStatus.REJECTED.fixValue) // OrdStatus
        message.setString(103, reasonCode.toString()) // OrdRejReason
        message.setString(58, reason) // Text

        if (request.isSetField(55)) {
            message.setString(55, request.getString(55)) // Symbol
        }
        if (request.isSetField(54)) {
            message.setString(54, request.getString(54)) // Side
        }
        if (request.isSetField(38)) {
            message.setString(38, request.getString(38)) // OrderQty
        }

        message.setString(151, "0") // LeavesQty
        message.setString(14, "0") // CumQty
        message.setString(6, "0") // AvgPx
        message.setString(60, FxOrderProcessor.formatTimestamp()) // TransactTime

        return message
    }

    /**
     * Creates an OrderCancelReject message
     */
    private fun createOrderCancelReject(request: Message, reason: String): Message {
        val message = Message()
        message.header.setString(8, fixVersion.beginString)
        message.header.setString(35, "9") // OrderCancelReject

        message.setString(37, "UNKNOWN") // OrderID
        if (request.isSetField(11)) {
            message.setString(11, request.getString(11)) // ClOrdID
        }
        if (request.isSetField(41)) {
            message.setString(41, request.getString(41)) // OrigClOrdID
        }
        message.setString(39, "8") // OrdStatus = Rejected
        message.setString(434, "1") // CxlRejResponseTo = Order Cancel Request
        message.setString(102, "99") // CxlRejReason = Other
        message.setString(58, reason) // Text

        return message
    }

    /**
     * Creates a Quote message
     */
    private fun createQuote(request: Message, quote: FxQuote): Message {
        val message = Message()
        message.header.setString(8, fixVersion.beginString)
        message.header.setString(35, "S") // Quote

        if (request.isSetField(131)) {
            message.setString(131, request.getString(131)) // QuoteReqID
        }
        message.setString(117, "Q${System.currentTimeMillis()}") // QuoteID
        message.setString(55, quote.symbol) // Symbol
        message.setString(132, formatPrice(quote.bidPx, quote.symbol)) // BidPx
        message.setString(133, formatPrice(quote.askPx, quote.symbol)) // OfferPx
        message.setString(134, quote.bidSize.toLong().toString()) // BidSize
        message.setString(135, quote.askSize.toLong().toString()) // OfferSize

        // Copy side from request if present
        if (request.isSetField(54)) {
            message.setString(54, request.getString(54)) // Side
        }

        // ValidUntilTime - quote valid for 30 seconds
        message.setString(
            62,
            LocalDateTime.now().plusSeconds(30).format(DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss")),
        )

        return message
    }

    /**
     * Creates a QuoteRequestReject message
     */
    private fun createQuoteRequestReject(request: Message): Message {
        val message = Message()
        message.header.setString(8, fixVersion.beginString)
        message.header.setString(35, "AG") // QuoteRequestReject

        if (request.isSetField(131)) {
            message.setString(131, request.getString(131)) // QuoteReqID
        }
        message.setString(658, "1") // QuoteRequestRejectReason = Unknown symbol
        message.setString(58, "Symbol not supported") // Text

        return message
    }

    /**
     * Starts the price tick thread that moves prices periodically
     */
    private fun startPriceTickThread() {
        priceTickThread =
            thread(name = "DemoFxServer-PriceTick") {
                try {
                    Thread.sleep(1000) // Initial delay

                    while (isRunning && !Thread.currentThread().isInterrupted) {
                        try {
                            marketData?.tick()
                        } catch (e: Exception) {
                            logger.error("Error during price tick: {}", e.message)
                        }

                        // Tick every 1 second
                        Thread.sleep(1000)
                    }
                } catch (e: InterruptedException) {
                    logger.info("Price tick thread interrupted")
                }
            }
    }

    /**
     * Formats a price with appropriate precision for the symbol
     */
    private fun formatPrice(price: Double, symbol: String): String = FxMarketData.formatPrice(price, symbol)
}
