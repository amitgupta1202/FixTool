package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.ResponseStep
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An acceptor rule replies with a *sequence*, and the two spellings of a reply mean one thing.
 *
 * The delays are asserted as planned offsets rather than by waiting for wall-clock — what is being
 * pinned is the arithmetic (a step's delay is measured from the step before it), and a timing test
 * would pin the scheduler instead, which [AcceptorDispatchTest] already covers.
 */
class AcceptorSequenceTest {
    private fun order(clOrdId: String = "ORD-1", qty: String = "1000") =
        AcceptorResponder.buildMessage("35=D|11=$clOrdId|55=ACME|54=1|38=$qty|")

    // ------------------------------------------------------- the two spellings of one reply

    @Test
    fun `a rule with only the older responseTemplate still replies, immediately, exactly once`() {
        val rule = AcceptorResponseRule(whenMsgType = "D", responseTemplate = "35=8|150=0|39=0|11=\${req.11}|")

        val plan = AcceptorResponder.plan(rule, order())

        assertEquals(1, plan.size, "the single-message spelling is one step")
        assertEquals(0L, plan.single().offsetMillis, "a rule that never mentioned a delay must not acquire one")
        assertEquals("ORD-1", plan.single().build().getString(11))
    }

    @Test
    fun `steps win over the older responseTemplate, and the rule says so`() {
        val rule =
            AcceptorResponseRule(
                whenMsgType = "D",
                responseTemplate = "35=8|39=0|11=\${req.11}|",
                steps = listOf(ResponseStep(template = "35=8|39=2|11=\${req.11}|")),
            )

        assertEquals("35=8|39=2|11=\${req.11}|", rule.sequence().single().template)
        val error = rule.validationError()
        assertTrue(
            error != null && error.contains("responseTemplate"),
            "carrying both spellings should be named, not silently resolved; got $error",
        )
    }

    // ------------------------------------------------------- the delays

    @Test
    fun `a step's delay is measured from the step before it`() {
        val rule =
            AcceptorResponseRule(
                whenMsgType = "D",
                steps =
                    listOf(
                        ResponseStep(template = "35=8|150=0|39=0|11=\${req.11}|", delayMillis = 0),
                        ResponseStep(template = "35=8|150=F|39=1|11=\${req.11}|", delayMillis = 500),
                        ResponseStep(template = "35=8|150=F|39=2|11=\${req.11}|", delayMillis = 500),
                    ),
            )

        assertEquals(
            listOf(0L, 500L, 1000L),
            AcceptorResponder.plan(rule, order()).map { it.offsetMillis },
            "an author writing 0, 500, 500 means ack / half a second later / half a second after that",
        )
    }

    @Test
    fun `a negative delay is flattened rather than pulling the sequence backwards`() {
        val rule =
            AcceptorResponseRule(
                whenMsgType = "D",
                steps =
                    listOf(
                        ResponseStep(template = "35=8|39=0|", delayMillis = 100),
                        ResponseStep(template = "35=8|39=2|", delayMillis = -100),
                    ),
            )

        assertEquals(
            listOf(100L, 100L),
            AcceptorResponder.plan(rule, order()).map { it.offsetMillis },
            "a negative delay must not un-schedule the step before it",
        )
        assertTrue(rule.validationError()!!.contains("negative delay"), "and it should still be reported")
    }

    // ------------------------------------------------------- what resolves when

    @Test
    fun `the request's values are fixed when the trigger arrives`() {
        val rule =
            AcceptorResponseRule(
                whenMsgType = "D",
                steps = listOf(ResponseStep(template = "35=8|11=\${req.11}|38=\${req.38}|")),
            )

        val plan = AcceptorResponder.plan(rule, order(clOrdId = "ORD-7", qty = "250"))
        val built = plan.single().build()

        assertEquals("ORD-7", built.getString(11))
        assertEquals("250", built.getString(38))
    }

    @Test
    fun `each step of a sequence mints its own id rather than sharing the trigger's`() {
        val rule =
            AcceptorResponseRule(
                whenMsgType = "D",
                steps =
                    listOf(
                        ResponseStep(template = "35=8|17=\${uuid}|11=\${req.11}|"),
                        ResponseStep(template = "35=8|17=\${uuid}|11=\${req.11}|", delayMillis = 10),
                    ),
            )

        val plan = AcceptorResponder.plan(rule, order())
        val first = plan[0].build().getString(17)
        val second = plan[1].build().getString(17)

        assertNotEquals(first, second, "two fills sharing one ExecID is not something a venue does")
        assertNotEquals(
            first,
            plan[0].build().getString(17),
            "the id must be minted as the step is sent, not once when the sequence was planned",
        )
    }

    @Test
    fun `the trigger-time half of resolution leaves the clock alone`() {
        val resolved =
            AcceptorResponder.resolveRequestRefs("35=8|11=\${req.11}|60=\${now}|17=\${uuid}|", order(), "req-id")

        assertEquals(
            "35=8|11=ORD-1|60=\${now}|17=\${uuid}|",
            resolved,
            "\${now} and \${uuid} belong to the moment of sending, so planning must not consume them",
        )
    }

    // ------------------------------------------------------- rules that cannot reply

    @Test
    fun `a rule with nothing to reply with is named, not silently inert`() {
        assertEquals(
            "the rule has nothing to reply with",
            AcceptorResponseRule(whenMsgType = "D").validationError(),
        )
        assertTrue(AcceptorResponseRule(whenMsgType = "D").sequence().isEmpty())
    }

    @Test
    fun `a blank step is named by its position, counting from one`() {
        val rule =
            AcceptorResponseRule(
                whenMsgType = "D",
                steps = listOf(ResponseStep(template = "35=8|39=0|"), ResponseStep(template = "  ")),
            )

        assertEquals("step 2 has no message to send", rule.validationError())
    }

    @Test
    fun `a usable sequence has nothing to report`() {
        val rule =
            AcceptorResponseRule(
                whenMsgType = "D",
                steps =
                    listOf(
                        ResponseStep(template = "35=8|150=0|39=0|11=\${req.11}|"),
                        ResponseStep(template = "35=8|150=F|39=2|11=\${req.11}|", delayMillis = 250),
                    ),
            )

        assertNull(rule.validationError())
    }

    // ------------------------------------------------------- computed fields

    private fun requestMessage(raw: String) =
        com.knapsack.fixtool.model.FixMessage(
            timestamp = java.time.LocalDateTime.now(),
            direction = com.knapsack.fixtool.model.FixMessage.Direction.INCOMING,
            rawMessage = raw,
            quickfixMessage = AcceptorResponder.buildMessage(raw),
        )

    /**
     * The partial fill a sequence is *for*. Without arithmetic an author has to hardcode the quantity,
     * so the rule works for exactly one order size and lies about every other — a client sending 1000
     * gets told it filled 50.
     */
    @Test
    fun `a step can compute a field from the request rather than hardcoding it`() {
        val raw = "35=D|11=ORD-9|55=ACME|54=1|38=1000|"
        val rule =
            AcceptorResponseRule(
                whenMsgType = "D",
                steps = listOf(ResponseStep(template = "35=8|150=F|39=1|11=\${req.11}|14=\${req.38 / 2}|")),
            )

        val built =
            AcceptorResponder
                .plan(rule, AcceptorResponder.buildMessage(raw), requestMessage(raw))
                .single()
                .build()

        assertEquals("500", built.getString(14), "half of 1000 is 500, whatever the author hardcoded")
        assertEquals("ORD-9", built.getString(11), "the standalone \${req.N} form still resolves alongside it")
    }

    @Test
    fun `the same rule computes a different fill for a different order size`() {
        val rule =
            AcceptorResponseRule(
                whenMsgType = "D",
                steps = listOf(ResponseStep(template = "35=8|150=F|14=\${req.38 / 2}|")),
            )

        fun fillFor(qty: String): String {
            val raw = "35=D|11=ORD|55=ACME|38=$qty|"
            return AcceptorResponder
                .plan(rule, AcceptorResponder.buildMessage(raw), requestMessage(raw))
                .single()
                .build()
                .getString(14)
        }

        assertEquals("50", fillFor("100"))
        assertEquals("2500", fillFor("5000"))
    }

    /** No request, no engine — and a template with no expressions never wakes the script engine at all. */
    @Test
    fun `a template without expressions is untouched by the evaluator`() {
        val plain = "35=8|150=0|39=0|11=ORD-1|"

        assertEquals(plain, AcceptorResponder.resolveExpressions(plain, requestMessage("35=D|11=ORD-1|"), null))
        assertEquals(plain, AcceptorResponder.resolveExpressions(plain, null, null))
    }
}
