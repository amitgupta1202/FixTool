package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.Matcher
import org.junit.Test
import quickfix.Message
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The engine must read the bytes the venue actually sent.
 *
 * `FixMessage.rawMessage` is a **display** string: it substitutes `|` for SOH so a human can read it,
 * and that substitution is lossy, because `|` is an ordinary character inside a FIX value. A venue is
 * entitled to reject an order with `58=Rejected|insufficient margin`, and splitting that on `|` yields a
 * truncated Text field plus a phantom one.
 *
 * Harmless while only a human was reading it. Not harmless once the assertion engine reads the field
 * list, where it means comparing an assertion against a value the venue never sent — in either
 * direction: a scenario that should pass goes red, and one asserting the truncated prefix goes green.
 */
class WireFieldsTest {
    private val soh = '\u0001'

    private fun message(wire: String): FixMessage =
        FixMessage(
            timestamp = LocalDateTime.now(),
            direction = FixMessage.Direction.INCOMING,
            rawMessage = wire.replace(soh, '|'), // exactly what the transport does for display
            messageType = "8",
            quickfixMessage = Message(),
            wireRaw = wire,
        )

    /** An ExecutionReport whose Text field contains a pipe — legal FIX, and the delimiter is SOH. */
    private val withPipeInValue =
        message("35=8${soh}11=ORD-1${soh}58=Rejected|insufficient margin${soh}39=8$soh")

    @Test
    fun `a pipe inside a value is part of the value, not a field boundary`() {
        val fields = FixMessageHelper.wireFields(withPipeInValue)

        assertEquals(
            listOf(35 to "8", 11 to "ORD-1", 58 to "Rejected|insufficient margin", 39 to "8"),
            fields,
            "splitting the display string on '|' truncates tag 58 and invents a phantom field",
        )
    }

    @Test
    fun `an assertion on that value is compared against what the venue sent`() {
        val expectation =
            Expectation(
                listOf(
                    FieldExpectation(11, Matcher.Exact("ORD-1")),
                    FieldExpectation(58, Matcher.Exact("Rejected|insufficient margin")),
                    FieldExpectation(39, Matcher.Exact("8")),
                ),
            )

        val results = ExpectationEvaluator.evaluate(FixMessageView(withPipeInValue), expectation)

        assertTrue(results.all { it.passed }, "the reply says exactly this: ${results.filterNot { it.passed }}")
    }

    @Test
    fun `and the truncated prefix does not pass`() {
        val expectation = Expectation(listOf(FieldExpectation(58, Matcher.Exact("Rejected"))))

        val result = ExpectationEvaluator.evaluate(FixMessageView(withPipeInValue), expectation).single()

        assertTrue(!result.passed, "'Rejected' is a prefix of the Text field, not the Text field")
        assertEquals("Rejected|insufficient margin", result.actual)
    }

    @Test
    fun `without the wire bytes it still reads the display string, in order`() {
        // The fallback path: the raw-capture log missed, so all we have is the substituted string.
        val fallback =
            FixMessage(
                timestamp = LocalDateTime.now(),
                direction = FixMessage.Direction.INCOMING,
                rawMessage = "35=8|11=ORD-1|39=2|",
                messageType = "8",
                quickfixMessage = Message(),
                wireRaw = null,
            )

        assertEquals(listOf(35 to "8", 11 to "ORD-1", 39 to "2"), FixMessageHelper.wireFields(fallback))
    }
}
