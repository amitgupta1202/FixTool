package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixMessage
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

        // Fields 309 and 305 arrive BEFORE the group delimiter 944 — the shape QFJ's native parse mangles
        // and the manual re-parse exists to recover.
        val wireMessage =
            "8=FIX.4.4\u000135=AY\u000149=SENDER\u000156=TARGET\u000134=1\u000152=20251125-21:13:13\u0001" +
                "902=TestID\u0001895=1\u0001903=1\u00011043=1\u0001" +
                "711=2\u0001" +
                "309=XS2201935199\u0001305=4\u0001944=1\u0001879=1099\u0001" + // Instance 1: non-delimiter fields first
                "309=BTT080393489\u0001305=4\u0001944=2\u0001228=10\u0001" + // Instance 2: non-delimiter fields first
                "10=000\u0001"

        // The message as QFJ delivers it: parsed from these bytes, and still carrying them.
        val nativeMessage = parsedFromWire(wireMessage, dictionary)

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

    /**
     * The one case where we genuinely have no wire bytes: a `Message` QFJ did not parse from bytes, so
     * `toRawString()` is empty.
     *
     * What must NOT happen is the thing that used to: filling `wireRaw` with `message.toString()`, which
     * re-emits the body in ascending tag order with the repeating groups relocated to the end. It looks
     * exactly like a wire string — same SOH, same tag=value — and the assertion engine cannot tell, so an
     * order-sensitive expectation was judged against a message no venue sent and went red blaming the venue.
     *
     * `wireRaw` is null instead, and the assertion engine refuses the message rather than guess at it.
     */
    @Test
    fun `a message QFJ did not parse from bytes has no wire order, and none is invented`() {
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

        // Built with setters, never parsed from bytes — so QFJ retained none.
        val nativeMessage = quickfix.Message()
        nativeMessage.header.setString(35, "AY")
        nativeMessage.header.setString(8, "FIX.4.4")
        nativeMessage.header.setString(49, "SENDER")
        nativeMessage.header.setString(56, "TARGET")
        nativeMessage.header.setInt(34, 99)
        nativeMessage.setString(902, "TestID")
        nativeMessage.setString(895, "1")

        val sessionId = quickfix.SessionID("FIX.4.4", "SENDER", "TARGET")
        service.onCreate(sessionId)

        service.fromApp(nativeMessage, sessionId)

        assertNotNull(receivedMessage, "Should have received a message")
        assertEquals(
            nativeMessage,
            receivedMessage!!.quickfixMessage,
            "Should use native message when there are no wire bytes to re-parse",
        )
        assertNull(
            receivedMessage!!.wireRaw,
            "toString() is not the wire: it sorts the body and moves groups to the end. Null is the honest answer.",
        )
        assertNull(
            FixMessageView.of(receivedMessage!!),
            "the assertion engine must refuse a message whose field order it would have to invent",
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

        // An admin heartbeat, as it arrives on the wire — QFJ keeps these bytes on the parsed message.
        val wireMessage = "8=FIX.4.4\u000135=0\u000149=SENDER\u000156=TARGET\u000134=5\u000152=20251125-21:13:13\u000110=123\u0001"
        val nativeMessage = parsedFromWire(wireMessage, dictionary)

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

        val wireMessage =
            "8=FIX.4.4\u000135=AY\u000149=SENDER\u000156=TARGET\u000134=1\u000152=20251125-21:13:13\u0001" +
                "902=TestID\u000110=000\u0001"
        // Parsed with the *empty* dictionary, so the manual re-parse below has nothing to work with.
        val nativeMessage = parsedFromWire(wireMessage, emptyDictionary)

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
     * A message as QuickFIX/J hands it to `fromApp`: parsed **from the wire bytes**, which QFJ retains on
     * the object (`toRawString()`). That is now the only source of wire bytes, so a test that wants to
     * exercise the real path must produce the message the real way.
     *
     * This used to be `setWireMessage(...)`, which pushed the bytes into a process-wide map that a `Log`
     * decorator owned, while the `Message` itself was hand-built with setters. Two objects, correlated by
     * `sender->target:seqnum`, and the test had to keep them in step — which meant the test could pass
     * while the two disagreed, and in production they sometimes did.
     */
    private fun parsedFromWire(wire: String, dictionary: FixDictionaryAdapter): quickfix.Message {
        val message = quickfix.Message()
        message.fromString(wire, dictionary.getDataDictionary(), false)
        return message
    }

    /**
     * The diff's central invariant, pinned on **both** incoming paths: `wireRaw` is the bytes QFJ parsed,
     * never `toString()`.
     *
     * Nothing asserted this before. The other tests here check that `rawMessage` *contains* `35=0` and
     * `49=SENDER` — which `message.toString()` satisfies every bit as well as the wire does — so restoring
     * `wireRaw = wireMessage ?: message.toString()` on the admin path would have left the suite entirely
     * green while every admin message in a capture carried QuickFIX's re-serialised field order.
     *
     * The test is written so it can only pass on the wire: the venue's bytes here are deliberately NOT in
     * ascending tag order (`52` before `34`), which is legal FIX and which `toString()` cannot reproduce.
     */
    @Test
    fun `incoming wireRaw is the venue's bytes, on fromApp and on fromAdmin alike`() {
        val dictionary = getTestDictionary()

        // 52 before 34 — a legal order, and one QFJ's ascending-tag re-serialisation cannot produce.
        val appBytes =
            "8=FIX.4.4\u000135=AY\u000149=SENDER\u000156=TARGET\u000152=20251125-21:13:13\u000134=1\u0001" +
                "902=TestID\u000110=000\u0001"
        val adminBytes =
            "8=FIX.4.4\u000135=0\u000149=SENDER\u000156=TARGET\u000152=20251125-21:13:13\u000134=5\u000110=123\u0001"

        for ((bytes, isAdmin) in listOf(appBytes to false, adminBytes to true)) {
            var received: FixMessage? = null
            val service =
                QuickFixService(
                    config = FixConnectionConfig(),
                    dictionary = dictionary,
                    onMessageReceived = { received = it },
                    onStateChanged = { },
                    onError = null,
                )
            val sessionId = quickfix.SessionID("FIX.4.4", "SENDER", "TARGET")
            service.onCreate(sessionId)
            val message = parsedFromWire(bytes, dictionary)
            if (isAdmin) service.fromAdmin(message, sessionId) else service.fromApp(message, sessionId)

            val wireRaw = received?.wireRaw
            assertNotNull(wireRaw, "wireRaw must be populated from toRawString() (admin=$isAdmin)")
            assertEquals(bytes, wireRaw, "wireRaw must be the venue's bytes verbatim (admin=$isAdmin)")
            assertNotEquals(
                message.toString(),
                wireRaw,
                "wireRaw must not be QuickFIX's re-serialisation (admin=$isAdmin) — this fixture puts 52 " +
                    "before 34 precisely so the two strings cannot coincide",
            )

            // And the engine reads that order, not the sorted one.
            val order = FixMessageView.of(received!!)!!.fields().map { it.first }
            assertTrue(
                order.indexOf(52) < order.indexOf(34),
                "the engine must see the venue's order (admin=$isAdmin): $order",
            )
        }
    }
}
