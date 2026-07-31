package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.ResponseStep
import org.junit.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **`${order.…}` — a reply reading what the venue is holding.**
 *
 * The mechanism is deliberately [AcceptorResponder.resolveRequestRefs]'s, one level up: two
 * spellings, textual, before the Kotlin expression pass. What is *not* the same is when it runs.
 * `${req.…}` is a fact about the triggering message and is fixed the moment that message arrives;
 * the book moves, and it moves **inside a single reply** — so this resolves per step, as the step is
 * sent. Those two claims are what this file exists for.
 *
 * The other half is the refusal. A missing value would go on the wire as `37=`, a real field with no
 * value, and the client would be blamed for the venue's malformed message.
 */
class AcceptorOrderRefTest {
    private fun order(clOrdId: String = "ORD-1", qty: String = "1000") =
        AcceptorResponder.buildMessage("35=D|11=$clOrdId|55=ACME|54=1|38=$qty|40=2|44=10.00|")

    private fun request(raw: String = "35=D|11=ORD-1|55=ACME|54=1|38=1000|40=2|44=10.00|") =
        FixMessage(
            timestamp = LocalDateTime.now(),
            direction = FixMessage.Direction.INCOMING,
            rawMessage = raw,
            quickfixMessage = AcceptorResponder.buildMessage(raw),
        )

    private val booked =
        mapOf(
            "clOrdId" to "ORD-1",
            "orderId" to "EX-1",
            "ordStatus" to "1",
            "orderQty" to "1000",
            "cumQty" to "400",
            "leavesQty" to "600",
            "symbol" to "ACME",
            "side" to "1",
            "price" to "10.00",
        )

    private fun rule(vararg templates: String) =
        AcceptorResponseRule(
            whenMsgType = "D",
            steps = templates.map { ResponseStep(template = it) },
        )

    private fun render(template: String, order: Map<String, String>? = booked): String =
        AcceptorResponder
            .plan(rule(template), order(), request()) { order }
            .single()
            .render()

    // ------------------------------------------------------------------ the two spellings

    @Test
    fun `a name standing alone is the value`() {
        assertEquals("35=8|37=EX-1|151=600|", render("35=8|37=\${order.orderId}|151=\${order.leavesQty}|"))
    }

    /**
     * The spelling that makes the presets truthful. Written any other way, "half of what is left"
     * would be the author computing it per order size, which is the arithmetic the book exists to
     * take off them.
     */
    @Test
    fun `a name inside a larger expression is arithmetic`() {
        assertEquals(
            "35=8|14=700|151=300|32=300|",
            render(
                "35=8|14=\${order.cumQty + order.leavesQty / 2}|" +
                    "151=\${order.leavesQty - order.leavesQty / 2}|32=\${order.leavesQty / 2}|",
            ),
        )
    }

    @Test
    fun `the book and the request are read in one template, each by its own spelling`() {
        assertEquals(
            "35=8|11=ORD-1|37=EX-1|38=1000|14=400|",
            render("35=8|11=\${req.11}|37=\${order.orderId}|38=\${req.38}|14=\${order.cumQty}|"),
        )
    }

    @Test
    fun `every name a template reads is found, in both spellings`() {
        val names =
            AcceptorResponder.orderNames("35=8|37=\${order.orderId}|14=\${order.cumQty + order.leavesQty / 2}|")

        assertEquals(listOf("orderId", "cumQty", "leavesQty"), names)
    }

    @Test
    fun `a template that reads nothing from the book is left alone, and needs no book`() {
        val template = "35=8|11=\${req.11}|37=\${req.uuid}|"

        assertEquals(template, AcceptorResponder.resolveOrderRefs(template, order = null))
        assertNull(AcceptorResponder.orderRefusal(template, order = null))
    }

    // ------------------------------------------------------------------ when it resolves

    /**
     * **The claim decision 2 made as a comment, at the layer that can state it.**
     *
     * `plan` takes a thunk, so a sequence's second step reads the book *after* the first has gone out
     * and moved it. Captured once instead, all three steps of a fill would report the same quantity —
     * which is the defect at the top of the proposal, not a subtlety.
     */
    @Test
    fun `each step reads the book as it is sent, not as it was when the reply was planned`() {
        // A *fresh map per call*, which is what a real lookup returns. Handing back one mutable map
        // would let a plan that captured the book once still see the change, and this test would pass
        // against the very implementation it exists to refuse.
        var book = mapOf("orderId" to "EX-1", "cumQty" to "0", "leavesQty" to "1000")
        val plan =
            AcceptorResponder.plan(
                rule("35=8|14=\${order.cumQty}|151=\${order.leavesQty}|", "35=8|14=\${order.cumQty}|151=\${order.leavesQty}|"),
                order(),
                request(),
            ) { book }

        val first = plan[0].render()
        // What the wire does between the two steps: the first report reaches the book.
        book = mapOf("orderId" to "EX-1", "cumQty" to "500", "leavesQty" to "500")
        val second = plan[1].render()

        assertEquals("35=8|14=0|151=1000|", first)
        assertEquals("35=8|14=500|151=500|", second, "step two has to see what step one left")
    }

    // ------------------------------------------------------------------ the refusal

    @Test
    fun `a reply that reads the book with no book refuses rather than sending an empty field`() {
        val problem = assertFailsWith<IllegalStateException> { render("35=8|37=\${order.orderId}|", order = null) }

        assertTrue(problem.message!!.contains("no order here"), "got: ${problem.message}")
    }

    @Test
    fun `a name this order has not got is refused, by name`() {
        val unacknowledged = mapOf("clOrdId" to "ORD-1", "orderQty" to "1000")

        val problem = assertFailsWith<IllegalStateException> { render("35=8|37=\${order.orderId}|", unacknowledged) }

        assertTrue(problem.message!!.contains("orderId"), "got: ${problem.message}")
        assertTrue(problem.message!!.contains("has not said"), "got: ${problem.message}")
    }

    /** A typo in the template and a value the venue has not stated send an author to different places. */
    @Test
    fun `a name outside the vocabulary is refused as a name, and the names are listed`() {
        val problem = AcceptorResponder.orderRefusal("35=8|151=\${order.leaves}|", booked)

        assertTrue(problem != null && "is not a name" in problem, "got: $problem")
        assertTrue(problem!!.contains("leavesQty"), "and it says what the names are: $problem")
    }

    /**
     * The refusal has to reach the *dispatcher*, because that is what turns a lost step into a
     * reported one. A `render()` that returned a half-built string instead would put `37=` on the
     * wire, which is the whole thing being prevented.
     */
    @Test
    fun `the refusal is thrown from the step's own render, so one bad step does not take the sequence`() {
        val plan =
            AcceptorResponder.plan(
                rule("35=8|150=0|11=\${req.11}|", "35=8|150=F|37=\${order.orderId}|"),
                order(),
                request(),
            ) { null }

        assertEquals("35=8|150=0|11=ORD-1|", plan[0].render(), "the step that reads nothing is unaffected")
        assertFailsWith<IllegalStateException> { plan[1].render() }
    }
}
