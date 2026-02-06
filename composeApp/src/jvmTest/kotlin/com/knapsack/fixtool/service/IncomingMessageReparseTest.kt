package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixMessage
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests that incoming messages with non-standard group delimiter ordering
 * are correctly re-parsed using the manual parser, and that the raw wire
 * message is captured for display.
 */
class IncomingMessageReparseTest {
    private fun getTestDictionary(): FixDictionaryAdapter {
        val resourcePath =
            this::class.java.classLoader.getResource("test-group-ordering.xml")
                ?: throw IllegalStateException("Test data dictionary not found")
        val file = File(resourcePath.toURI())
        return FixDictionaryAdapter.fromFile(file)
    }

    @Test
    fun `fromApp re-parses wire message with non-standard delimiter order`() {
        val dictionary = getTestDictionary()
        var receivedMessage: FixMessage? = null

        val service =
            QuickFixService(
                config = FixConnectionConfig(),
                dictionary = dictionary,
                onMessageReceived = { message -> receivedMessage = message },
                onStateChanged = { },
                onError = null,
            )

        // Simulate what the RawMessageCapturingLogFactory does:
        // Set a wire message on the ThreadLocal BEFORE calling fromApp
        // This message has fields 309 and 305 appearing BEFORE the delimiter 944
        val wireMessage =
            "8=FIX.4.4\u000135=AY\u000149=SENDER\u000156=TARGET\u000134=1\u000152=20251125-21:13:13\u0001" +
                "902=TestID\u0001895=1\u0001903=1\u00011043=1\u0001" +
                "711=2\u0001" +
                "309=XS2201935199\u0001305=4\u0001944=1\u0001879=1099\u0001" + // Instance 1: non-delimiter fields first
                "309=BTT080393489\u0001305=4\u0001944=2\u0001228=10\u0001" + // Instance 2: non-delimiter fields first
                "10=000\u0001"

        // Simulate the log factory capturing the wire message into ConcurrentHashMap
        setWireMessage(wireMessage)

        // Create a QuickFIX Message that would result from native parsing (lossy - groups may be broken)
        // Must include header fields 49, 56, 34 so getWireMessage() can look up the wire bytes
        val nativeMessage = quickfix.Message()
        nativeMessage.header.setString(35, "AY")
        nativeMessage.header.setString(8, "FIX.4.4")
        nativeMessage.header.setString(49, "SENDER")
        nativeMessage.header.setString(56, "TARGET")
        nativeMessage.header.setInt(34, 1)
        nativeMessage.setString(902, "TestID")

        // Simulate the session ID
        val sessionId = quickfix.SessionID("FIX.4.4", "SENDER", "TARGET")
        service.onCreate(sessionId)

        // Call fromApp - this should capture wire message and re-parse
        service.fromApp(nativeMessage, sessionId)

        // Verify the received message
        assertNotNull(receivedMessage, "Should have received a message")

        // Verify raw message contains the wire bytes (with | delimiters)
        val raw = receivedMessage!!.rawMessage
        assertTrue(raw.contains("309=XS2201935199"), "Raw message should contain first UnderlyingSecurityID")
        assertTrue(raw.contains("309=BTT080393489"), "Raw message should contain second UnderlyingSecurityID")
        assertTrue(raw.contains("711=2"), "Raw message should contain group count")

        // Verify the quickfixMessage was re-parsed and has complete group data
        val qfMessage = receivedMessage!!.quickfixMessage
        assertNotNull(qfMessage, "Should have a quickfixMessage")
        assertTrue(qfMessage.isSetField(711), "Re-parsed message should have NoUnderlyings group")
        assertEquals(2, qfMessage.getInt(711), "Should have 2 NoUnderlyings instances")

        // Verify first group instance has all fields
        val group1 = qfMessage.getGroup(1, 711)
        assertEquals("XS2201935199", group1.getString(309), "First instance should have UnderlyingSecurityID")
        assertEquals("4", group1.getString(305), "First instance should have UnderlyingSecurityIDSource")
        assertEquals("1", group1.getString(944), "First instance should have CollAction")
        assertEquals("1099", group1.getString(879), "First instance should have UnderlyingMismatch")

        // Verify second group instance has all fields
        val group2 = qfMessage.getGroup(2, 711)
        assertEquals("BTT080393489", group2.getString(309), "Second instance should have UnderlyingSecurityID")
        assertEquals("4", group2.getString(305), "Second instance should have UnderlyingSecurityIDSource")
        assertEquals("2", group2.getString(944), "Second instance should have CollAction")
        assertEquals("10", group2.getString(228), "Second instance should have RelSymTransactTime")
    }

    @Test
    fun `fromApp falls back to native message when no wire message captured`() {
        val dictionary = getTestDictionary()
        var receivedMessage: FixMessage? = null

        val service =
            QuickFixService(
                config = FixConnectionConfig(),
                dictionary = dictionary,
                onMessageReceived = { message -> receivedMessage = message },
                onStateChanged = { },
                onError = null,
            )

        // No wire message set in ConcurrentHashMap - getWireMessage() will return null

        // Create a native QuickFIX message with header fields for lookup
        val nativeMessage = quickfix.Message()
        nativeMessage.header.setString(35, "AY")
        nativeMessage.header.setString(8, "FIX.4.4")
        nativeMessage.header.setString(49, "SENDER")
        nativeMessage.header.setString(56, "TARGET")
        nativeMessage.header.setInt(34, 99) // seqnum with no matching wire message
        nativeMessage.setString(902, "TestID")
        nativeMessage.setString(895, "1")

        val sessionId = quickfix.SessionID("FIX.4.4", "SENDER", "TARGET")
        service.onCreate(sessionId)

        service.fromApp(nativeMessage, sessionId)

        assertNotNull(receivedMessage, "Should have received a message")
        // The quickfixMessage should be the native message (no re-parse)
        assertEquals(
            nativeMessage,
            receivedMessage!!.quickfixMessage,
            "Should use native message when no wire message captured",
        )
    }

    @Test
    fun `fromAdmin captures wire message for raw display`() {
        val dictionary = getTestDictionary()
        var receivedMessage: FixMessage? = null

        val service =
            QuickFixService(
                config = FixConnectionConfig(),
                dictionary = dictionary,
                onMessageReceived = { message -> receivedMessage = message },
                onStateChanged = { },
                onError = null,
            )

        // Set a wire message for an admin heartbeat
        val wireMessage = "8=FIX.4.4\u000135=0\u000149=SENDER\u000156=TARGET\u000134=5\u000152=20251125-21:13:13\u000110=123\u0001"
        setWireMessage(wireMessage)

        // Create a native message
        val nativeMessage = quickfix.Message()
        nativeMessage.header.setString(35, "0")
        nativeMessage.header.setString(8, "FIX.4.4")
        nativeMessage.header.setString(49, "SENDER")
        nativeMessage.header.setString(56, "TARGET")
        nativeMessage.header.setInt(34, 5)
        nativeMessage.header.setString(52, "20251125-21:13:13")

        val sessionId = quickfix.SessionID("FIX.4.4", "SENDER", "TARGET")
        service.onCreate(sessionId)

        service.fromAdmin(nativeMessage, sessionId)

        assertNotNull(receivedMessage, "Should have received a message")
        // Raw message should come from wire bytes
        val raw = receivedMessage!!.rawMessage
        assertTrue(raw.contains("35=0"), "Raw message should contain MsgType from wire")
        assertTrue(raw.contains("49=SENDER"), "Raw message should contain SenderCompID from wire")
    }

    @Test
    fun `fromApp handles manual re-parse failure gracefully`() {
        // Use a default (empty) dictionary that will cause re-parse to fail
        val emptyDictionary = FixDictionaryAdapter.createDefault()
        var receivedMessage: FixMessage? = null

        val service =
            QuickFixService(
                config = FixConnectionConfig(),
                dictionary = emptyDictionary,
                onMessageReceived = { message -> receivedMessage = message },
                onStateChanged = { },
                onError = null,
            )

        // Set a wire message
        val wireMessage =
            "8=FIX.4.4\u000135=AY\u000149=SENDER\u000156=TARGET\u000134=1\u000152=20251125-21:13:13\u0001" +
                "902=TestID\u000110=000\u0001"
        setWireMessage(wireMessage)

        // Create a native message with header fields for lookup
        val nativeMessage = quickfix.Message()
        nativeMessage.header.setString(35, "AY")
        nativeMessage.header.setString(8, "FIX.4.4")
        nativeMessage.header.setString(49, "SENDER")
        nativeMessage.header.setString(56, "TARGET")
        nativeMessage.header.setInt(34, 1)
        nativeMessage.setString(902, "TestID")

        val sessionId = quickfix.SessionID("FIX.4.4", "SENDER", "TARGET")
        service.onCreate(sessionId)

        // Should not throw - falls back to native message
        service.fromApp(nativeMessage, sessionId)

        assertNotNull(receivedMessage, "Should have received a message")
        // Raw message should still come from wire bytes
        assertTrue(
            receivedMessage!!.rawMessage.contains("902=TestID"),
            "Raw message should contain wire bytes even when re-parse fails",
        )
        // quickfixMessage should fall back to native (since dictionary has no data dictionary)
        assertEquals(
            nativeMessage,
            receivedMessage!!.quickfixMessage,
            "Should fall back to native message when dictionary has no data dictionary",
        )
    }

    /**
     * Helper to store a wire message in the ConcurrentHashMap, simulating what
     * RawMessageCapturingLogFactory.RawMessageCapturingLog.onIncoming() does.
     */
    private fun setWireMessage(message: String) {
        val log =
            RawMessageCapturingLogFactory.RawMessageCapturingLog(
                object : quickfix.Log {
                    override fun onIncoming(message: String) {}

                    override fun onOutgoing(message: String) {}

                    override fun onEvent(text: String) {}

                    override fun onErrorEvent(text: String) {}

                    override fun clear() {}
                },
            )
        log.onIncoming(message)
    }
}
