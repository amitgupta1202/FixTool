package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.BookReading
import com.knapsack.fixtool.model.BookedOrder
import com.knapsack.fixtool.model.FieldCondition
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.OrderBook
import com.knapsack.fixtool.model.OrderConstraint
import com.knapsack.fixtool.model.OrderEvent
import com.knapsack.fixtool.model.OrderState
import com.knapsack.fixtool.model.ResponseStep
import com.knapsack.fixtool.model.scenario.Matcher
import org.junit.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The presets are *content*, and content goes stale silently: a preset that stopped firing looks
 * exactly like a venue that is working. So none of these tests reads a template as a string. Each one
 * asks the engine that runs it — `explain` for whether it fires, `plan` for what it sends — because
 * that is the only judgement the wire agrees with.
 *
 * The sample messages below are the ones each preset was designed against, and they are deliberately
 * minimal: they carry what a real client sends and nothing convenient. A preset that only works
 * against a message padded with the tags it happens to need would pass here and fail in the field.
 */
class AcceptorPresetsTest {
    private val limitOrder = "35=D|11=ORD-1|55=ACME|54=1|38=1000|40=2|44=185.25|60=20260730-09:14:22.000"
    private val marketOrder = "35=D|11=ORD-2|55=ACME|54=1|38=1000|40=1|60=20260730-09:14:22.000"
    private val hugeOrder = "35=D|11=ORD-3|55=ACME|54=1|38=2000000|40=2|44=185.25|60=20260730-09:14:22.000"
    private val cancel = "35=F|41=ORD-1|11=CXL-1|55=ACME|54=1|60=20260730-09:14:22.000"
    private val replace = "35=G|41=ORD-1|11=RPL-1|55=ACME|54=1|38=500|40=2|44=186.00|60=20260730-09:14:22.000"
    private val statusRequest = "35=H|37=EX-1|11=ORD-1|55=ACME|54=1"

    /**
     * The message **each rule** is designed against — per rule, not per preset, because a bundle's
     * rules answer different message types and a cancel rule asked about an order is a test of nothing.
     */
    private fun sampleFor(rule: AcceptorResponseRule): String {
        // Derived from the rule's own trigger, not a table keyed on MsgType. A table was enough while
        // every 35=D preset answered any symbol at any order type; the FX venue's rules name three
        // symbols and two order types, so a fixed 55=ACME sample would ask each of them whether it
        // fires for a message it was never meant to answer — and they would all correctly say no.
        val symbol =
            when (val m = rule.trigger().firstOrNull { it.tag == 55 }?.parsed()) {
                is Matcher.Exact -> m.value
                is Matcher.OneOf -> m.values.first()
                else -> "ACME"
            }
        val quantity = if (rule.trigger().any { it.tag == 38 }) "20000000" else "1000"
        val market = rule.trigger().any { it.tag == 40 && it.parsed() == Matcher.Exact("1") }
        return when (rule.whenMsgType) {
            "D" ->
                "35=D|11=ORD-1|55=$symbol|54=1|38=$quantity" +
                    (if (market) "|40=1" else "|40=2|44=185.25") +
                    "|60=20260730-09:14:22.000"
            "R" -> "35=R|131=Q-1|55=$symbol|54=1|38=1000000"
            "F" -> cancel
            "G" -> replace
            "H" -> statusRequest
            else -> error("no sample message for 35=${rule.whenMsgType}")
        }
    }

    /**
     * The venue state **each rule** is designed against, which for a rule that asks the book is the
     * state it asks for.
     *
     * Not a convenience: a preset conditioned `working` is a claim about a venue holding a live order,
     * and judging it against a venue holding nothing would be asking whether it fires in the one
     * situation it promises not to. Keyed off the rule's own constraint so a preset added later cannot
     * be quietly exempted from the checks below.
     */
    private fun bookFor(rule: AcceptorResponseRule) =
        BookReading(
            key = "ORD-1",
            state = rule.whenOrder?.takeIf { it != OrderConstraint.UNKNOWN }?.let { OrderState.valueOf(it.name) },
            leavesQty = "1000",
        )

    /**
     * The venue's book as the sample messages would have left it: ORD-1 received, acknowledged, live.
     *
     * Built as a real [BookedOrder] and read through [OrderBook.fields] rather than hand-written as a
     * map, so a preset reading a name the fold does not actually produce fails here rather than in
     * front of a client. Every sample above names ORD-1, so one order answers for all of them.
     */
    private val bookedOrder: Map<String, String> =
        OrderBook.fields(
            BookedOrder(
                key = "ORD-1",
                events =
                    listOf(
                        OrderEvent(
                            at = LocalDateTime.now(),
                            sent = false,
                            msgType = "D",
                            fields =
                                mapOf(
                                    11 to "ORD-1",
                                    55 to "ACME",
                                    54 to "1",
                                    38 to "1000",
                                    44 to "185.25",
                                ),
                        ),
                        OrderEvent(
                            at = LocalDateTime.now(),
                            sent = true,
                            msgType = "8",
                            fields = mapOf(11 to "ORD-1", 37 to "EX-1", 150 to "0", 39 to "0", 14 to "0", 151 to "1000"),
                        ),
                    ),
            ),
        )

    private fun eachRule(action: (String, AcceptorResponseRule, String) -> Unit) =
        AcceptorPresets.all.forEach { preset ->
            preset.rules.forEach { rule -> action(preset.id, rule, sampleFor(rule)) }
        }

    private fun request(raw: String) =
        FixMessage(
            timestamp = LocalDateTime.now(),
            direction = FixMessage.Direction.INCOMING,
            rawMessage = raw,
            quickfixMessage = AcceptorResponder.buildMessage(raw),
        )

    /** The rule of [AcceptorPresets] a preset's own trigger picks — the first, for a bundle. */
    private fun leadRule(id: String) = AcceptorPresets.byId(id)!!.rules.first()

    // ------------------------------------------------------------------ every preset, every claim

    @Test
    fun `every preset is a rule the editor would not flag`() {
        AcceptorPresets.all.forEach { preset ->
            preset.rules.forEachIndexed { index, rule ->
                assertNull(
                    rule.validationError(),
                    "${preset.id} rule ${index + 1} ships with a fault the editor would draw a warning for",
                )
            }
        }
    }

    @Test
    fun `every preset ids and names are unique, since the id is how it is asked for`() {
        assertEquals(AcceptorPresets.ids.size, AcceptorPresets.ids.distinct().size, "duplicate preset id")
        val names = AcceptorPresets.all.map { it.name }
        assertEquals(names.size, names.distinct().size, "two presets with one name are one preset in the menu")
    }

    @Test
    fun `every rule of every preset fires against the message it claims to answer`() {
        eachRule { id, rule, raw ->
            // Asked alone, so nothing else in the list can be the reason it did or did not win.
            val outcomes = AcceptorResponder.explain(listOf(rule), AcceptorResponder.buildMessage(raw), bookFor(rule))
            assertTrue(
                outcomes.single().selected,
                "$id answers nothing when sent $raw against a ${rule.whenOrder?.word ?: "any"} order — " +
                    "the one thing a preset must not do",
            )
        }
    }

    /**
     * The other half of the claim above, and the one that is actually new: a preset that asks the book
     * **does not** fire in the state it did not ask for. Without this, `whenOrder` could be ignored
     * entirely by the engine and every test here would still pass.
     */
    @Test
    fun `a preset that asks the book stays silent in every state but its own`() {
        val states = listOf(null, OrderState.PENDING, OrderState.WORKING, OrderState.DONE)
        eachRule { id, rule, raw ->
            val wanted = rule.whenOrder ?: return@eachRule
            states.filterNot { wanted.matches(it) }.forEach { state ->
                val outcome =
                    AcceptorResponder
                        .explain(listOf(rule), AcceptorResponder.buildMessage(raw), BookReading("ORD-1", state))
                        .single()
                assertFalse(
                    outcome.matched,
                    "$id asks for ${wanted.word} but fired against ${state?.name?.lowercase() ?: "an order the book has never seen"}",
                )
            }
        }
    }

    /**
     * The hazard this guards is not hypothetical: `${req.44}` against a market order substitutes an
     * empty string and the parser reads `31=` back as a field with an empty value, so the venue sends
     * a malformed message and the client is blamed for it. Every preset is checked against its own
     * trigger, which is the only message it promises anything about.
     */
    @Test
    fun `no preset puts an empty field on the wire`() {
        eachRule { id, rule, raw ->
            AcceptorResponder.plan(rule, AcceptorResponder.buildMessage(raw), request(raw)) { bookedOrder }.forEach { planned ->
                val empty = planned.render().split('|').filter { it.isNotBlank() }.filter { it.endsWith("=") }
                assertTrue(
                    empty.isEmpty(),
                    "$id would send ${empty.joinToString()} — a tag with no value is a malformed message",
                )
            }
        }
    }

    @Test
    fun `every preset replies with the message type its template advertises`() {
        eachRule { id, rule, raw ->
            AcceptorResponder.plan(rule, AcceptorResponder.buildMessage(raw), request(raw)) { bookedOrder }.forEach { planned ->
                val advertised = planned.render().split('|').first { it.startsWith("35=") }.removePrefix("35=")
                assertEquals(
                    advertised,
                    planned.build().header.getString(35),
                    "$id builds a different message type than its template says",
                )
            }
        }
    }

    /**
     * Why the fill presets are conditioned on OrdType at all. A market order carries no price, so a
     * template pricing at `${req.44}` would substitute nothing and send `31=`; the condition is what
     * routes that order to the rule below instead, which asks for no price.
     */
    @Test
    fun `a market order falls past the fill to the plain acknowledgement`() {
        val venue = AcceptorPresets.byId(AcceptorPresets.STARTER_VENUE)!!.rules

        val winner = AcceptorResponder.explain(venue, AcceptorResponder.buildMessage(marketOrder)).first { it.selected }

        assertEquals(1, winner.rule.sequence().size, "a market order must not reach the rule that prices a fill")
        val sent =
            AcceptorResponder
                .plan(winner.rule, AcceptorResponder.buildMessage(marketOrder), request(marketOrder))
                .single()
                .render()
        assertTrue(
            sent.split('|').none { it.isNotBlank() && it.endsWith("=") },
            "the reply a market order does get must still be well formed: $sent",
        )
    }

    // ------------------------------------------------------------------ the ids across a sequence

    /**
     * A sequence is one order's lifecycle, so OrderID cannot move between its steps: a client tracking
     * tag 37 would see the fill belong to an order it never placed. ExecID is the opposite — it
     * identifies the *report*, so two reports sharing one is not something a venue does.
     */
    @Test
    fun `a sequence keeps one OrderID and mints an ExecID per step`() {
        val plan =
            AcceptorResponder.plan(
                leadRule("ack-then-fill"),
                AcceptorResponder.buildMessage(limitOrder),
                request(limitOrder),
            )
        val built = plan.map { it.build() }

        assertEquals(2, built.size)
        assertEquals(
            built[0].getString(37),
            built[1].getString(37),
            "the fill must be about the order the ack acknowledged",
        )
        assertNotEquals(built[0].getString(17), built[1].getString(17), "two reports sharing one ExecID")
    }

    @Test
    fun `two orders get two OrderIDs`() {
        val rule = leadRule("ack-then-fill")
        fun orderIdFor(raw: String) =
            AcceptorResponder.plan(rule, AcceptorResponder.buildMessage(raw), request(raw)).first().build().getString(37)

        assertNotEquals(
            orderIdFor(limitOrder),
            orderIdFor(limitOrder.replace("ORD-1", "ORD-9")),
            "one id per triggering message, not one per rule",
        )
    }

    // ------------------------------------------------------------------ the arithmetic

    @Test
    fun `a partial fill and its remainder add up to the order, for an odd quantity too`() {
        val odd = "35=D|11=ORD-7|55=ACME|54=1|38=1001|40=2|44=10.00|60=20260730-09:14:22.000"
        val built =
            AcceptorResponder
                .plan(leadRule("ack-partial-fill"), AcceptorResponder.buildMessage(odd), request(odd))
                .map { it.build() }

        assertEquals("500", built[1].getString(14), "half of 1001, taken the way the template takes it")
        assertEquals("501", built[1].getString(151), "and the rest, so the two still make the order")
        assertEquals("1001", built[2].getString(14), "the last report says the whole order is done")
        assertEquals("0", built[2].getString(151))
    }

    // ------------------------------------------------------------------ placement

    private fun unconditioned(msgType: String) =
        AcceptorResponseRule(whenMsgType = msgType, steps = listOf(ResponseStep("35=8|39=0|")))

    @Test
    fun `a conditioned preset lands above an unconditioned rule for the same type`() {
        val existing = listOf(unconditioned("D"))

        val insertion = AcceptorPresets.insert(existing, AcceptorPresets.byId("order-reject-size")!!)

        assertEquals(0, insertion.index, "appended, it would sit below a rule that answers every 35=D")
        assertEquals(0, insertion.placedAbove)
        assertTrue(insertion.note!!.contains("rule 1"), "the note names the rule it had to get above")
        assertNull(
            AcceptorResponder.shadowingRule(insertion.rules, 0),
            "the whole point of the placement is that the new rule is reachable",
        )
    }

    /**
     * The case a live run found and the first version of this got wrong: the reject was placed below
     * the starter venue's ack-and-fill rule, so a two-million-share *limit* order filled instead of
     * rejecting. Reachable — a market order that size would have reached it — and still not the answer
     * to the question the author had just asked.
     */
    @Test
    fun `a conditioned preset lands above a conditioned rule of the same type too`() {
        val venue = AcceptorPresets.insert(emptyList(), AcceptorPresets.byId(AcceptorPresets.STARTER_VENUE)!!).rules

        val withReject = AcceptorPresets.insert(venue, AcceptorPresets.byId("order-reject-size")!!).rules

        val huge = "35=D|11=ORD-4|55=ACME|54=1|38=2000000|40=2|44=185.25|60=20260730-09:14:22.000"
        val winner = AcceptorResponder.explain(withReject, AcceptorResponder.buildMessage(huge)).first { it.selected }
        assertEquals(
            "8",
            winner.rule.sequence().first().template.substringAfter("39=").substringBefore("|"),
            "an order over the size limit must be rejected, not filled",
        )

        // And the rule it displaced still does its own job for everything it was already doing.
        val ordinary = "35=D|11=ORD-5|55=ACME|54=1|38=1000|40=2|44=185.25|60=20260730-09:14:22.000"
        assertEquals(
            2,
            AcceptorResponder.explain(withReject, AcceptorResponder.buildMessage(ordinary))
                .first { it.selected }
                .rule
                .sequence()
                .size,
            "a limit order under the limit still reaches the rule that fills it",
        )
    }

    @Test
    fun `an unconditioned preset appends, and does not jump a queue it does not need to`() {
        val existing = listOf(unconditioned("D"), unconditioned("F"))

        val insertion = AcceptorPresets.insert(existing, AcceptorPresets.byId("order-ack")!!)

        assertEquals(2, insertion.index)
        assertNull(insertion.note, "an append needs no explanation")
    }

    @Test
    fun `a rule for another message type is not displaced by one it cannot shadow`() {
        val existing = listOf(unconditioned("F"))

        assertEquals(1, AcceptorPresets.insert(existing, AcceptorPresets.byId("order-reject-size")!!).index)
    }

    @Test
    fun `a disabled rule displaces nothing, because it answers nothing`() {
        val existing = listOf(unconditioned("D").copy(enabled = false))

        assertEquals(
            1,
            AcceptorPresets.insert(existing, AcceptorPresets.byId("order-reject-size")!!).index,
            "a rule that is skipped at match time cannot be shadowing anything",
        )
    }

    /**
     * The bundle's own order is load-bearing: its conditioned limit-order rule has to precede its
     * unconditioned one, or first-match-wins never reaches the fill and the starter venue only ever
     * acknowledges.
     */
    @Test
    fun `the starter venue is internally reachable, every rule of it`() {
        val insertion = AcceptorPresets.insert(emptyList(), AcceptorPresets.byId(AcceptorPresets.STARTER_VENUE)!!)

        assertEquals(7, insertion.rules.size)
        assertEquals(7, insertion.added)
        insertion.rules.indices.forEach { index ->
            assertNull(
                AcceptorResponder.shadowingRule(insertion.rules, index),
                "rule ${index + 1} of the starter venue can never fire",
            )
        }
        val fill =
            AcceptorResponder.explain(insertion.rules, AcceptorResponder.buildMessage(limitOrder)).first { it.selected }
        assertEquals(2, fill.rule.sequence().size, "a limit order must reach the rule that fills it, not just the ack")
    }

    // ------------------------------------------------------------------ what shadowing claims

    @Test
    fun `an earlier unconditioned rule of the same type makes a later one unreachable`() {
        val rules = listOf(unconditioned("D"), leadRule("order-reject-size"))

        assertEquals(0, AcceptorResponder.shadowingRule(rules, 1))
    }

    @Test
    fun `two conditioned rules are left alone, because the overlap cannot be settled by looking`() {
        val a = AcceptorResponseRule(
            whenMsgType = "D",
            conditions = listOf(FieldCondition(38, MatcherCodec.matcherToJson(Matcher.Range(min = 500.0)))),
            steps = listOf(ResponseStep("35=8|39=0|")),
        )
        val b = AcceptorResponseRule(
            whenMsgType = "D",
            conditions = listOf(FieldCondition(38, MatcherCodec.matcherToJson(Matcher.Range(min = 1000.0)))),
            steps = listOf(ResponseStep("35=8|39=8|")),
        )

        assertNull(
            AcceptorResponder.shadowingRule(listOf(a, b), 1),
            "b is in fact unreachable here, but nothing decidable says so — a warning that guesses is one nobody reads",
        )
    }

    @Test
    fun `a disabled earlier rule shadows nothing, and a blank MsgType matches nothing`() {
        val off = unconditioned("D").copy(enabled = false)
        assertNull(AcceptorResponder.shadowingRule(listOf(off, unconditioned("D")), 1))

        val blank = AcceptorResponseRule(whenMsgType = "", steps = listOf(ResponseStep("35=8|")))
        assertNull(
            AcceptorResponder.shadowingRule(listOf(blank, blank), 1),
            "a rule with no MsgType answers nothing, so it takes nothing from the rule after it",
        )
    }

    // ------------------------------------------------------------------ the starter venue's cancels

    /**
     * **The test that should have existed since slice B and did not.**
     *
     * Three slices of order state shipped while the starter bundle — the venue almost everyone will
     * actually run — still answered a cancel for an order nobody placed with "canceled". That is
     * verbatim the defect issue #35 opens with, surviving inside the very feature built to fix it,
     * because the conditioned rules sat in the menu and the bundle never picked them up.
     *
     * So the claim is made of the **bundle**, not of the rules it happens to contain: whatever the
     * starter venue is, a cancel must get the right answer in every state a book can be in.
     */
    @Test
    fun `the starter venue answers a cancel in every state, and never with silence`() {
        val venue = AcceptorPresets.insert(emptyList(), AcceptorPresets.byId(AcceptorPresets.STARTER_VENUE)!!).rules
        val message = AcceptorResponder.buildMessage(cancel)

        fun replyTo(state: OrderState?): List<String> {
            val outcome =
                AcceptorResponder
                    .explain(venue, message, BookReading("ORD-1", state, leavesQty = "1000"))
                    .firstOrNull { it.selected }
            assertNotNull(
                outcome,
                "a cancel against ${state ?: "an unknown order"} matched no rule at all — " +
                    "a venue that answers with silence gives a client no error path to take",
            )
            return outcome.rule.sequence().map { it.template }
        }

        // Never placed: the answer the issue asks for, and the one the bundle used to get wrong.
        val unknown = replyTo(null).single()
        assertTrue(unknown.startsWith("35=9"), "got: $unknown")
        assertTrue(unknown.contains("102=1"), "unknown order, by reason code: $unknown")

        // Held but unanswered, and acknowledged: both are orders the venue has, so both cancel.
        listOf(OrderState.PENDING, OrderState.WORKING).forEach { state ->
            val steps = replyTo(state)
            assertEquals(2, steps.size, "$state should get pending-cancel then canceled")
            assertTrue(steps.last().contains("150=4"), "got: ${steps.last()}")
        }

        // Finished — and *not* "unknown order", which would send the client's error handling down the
        // path for a lost order rather than a completed one.
        val done = replyTo(OrderState.DONE).single()
        assertTrue(done.startsWith("35=9"), "got: $done")
        assertTrue(done.contains("102=0"), "too late to cancel, not unknown: $done")
    }

    /**
     * The cards read in the order a person thinks in — unknown, pending, working, done. That falls out
     * of [AcceptorPresets.insert] placing each conditioned rule above the first for its MsgType, so
     * the bundle lists them backwards to get it. Pinned because it is not obvious from the source, and
     * a silent drift into a scrambled list would make the one artifact people read to learn the
     * feature read like noise.
     */
    @Test
    fun `the starter venue's cancel rules read in state order`() {
        val venue = AcceptorPresets.insert(emptyList(), AcceptorPresets.byId(AcceptorPresets.STARTER_VENUE)!!).rules

        assertEquals(
            listOf(OrderConstraint.UNKNOWN, OrderConstraint.PENDING, OrderConstraint.WORKING, OrderConstraint.DONE),
            venue.filter { it.whenMsgType == "F" }.map { it.whenOrder },
        )
    }

    // ------------------------------------------------------------------ the presets that ask the book

    /**
     * **The venue the whole feature was for**, assembled the way a tester would assemble it: two
     * presets off the menu, no hand-editing, and the cancel is answered two different ways by one rule
     * list. Before the book, showing a client both behaviours meant switching a rule off between them.
     */
    @Test
    fun `the two cancel presets together answer an unknown cancel and a live one differently`() {
        var rules = AcceptorPresets.insert(emptyList(), AcceptorPresets.byId(AcceptorPresets.STARTER_VENUE)!!).rules
        rules = AcceptorPresets.insert(rules, AcceptorPresets.byId("cancel-rejected-unknown")!!).rules
        rules = AcceptorPresets.insert(rules, AcceptorPresets.byId("cancel-accepted-working")!!).rules

        val rejected =
            AcceptorResponder
                .explain(rules, AcceptorResponder.buildMessage(cancel), BookReading.unknown("ORD-1"))
                .first { it.selected }
        assertTrue(
            rejected.rule
                .sequence()
                .single()
                .template
                .startsWith("35=9"),
            "a cancel for an order nobody sent has to come back as a cancel reject",
        )

        val accepted =
            AcceptorResponder
                .explain(rules, AcceptorResponder.buildMessage(cancel), BookReading("ORD-1", OrderState.WORKING))
                .first { it.selected }
        assertEquals(2, accepted.rule.sequence().size, "a cancel for a live order gets pending-cancel then canceled")
        assertTrue(
            accepted.rule
                .sequence()
                .last()
                .template
                .contains("150=4"),
        )
    }

    /**
     * A duplicate is not "the book holds this ClOrdID" — by the time any rule is asked the book holds
     * it either way, because the order's own arrival booked it. What separates the two is the state
     * the venue held *before* the message, which is what `whenOrder` reads (decision 4a). This is the
     * test that fails if that ever becomes an after-reading.
     */
    @Test
    fun `the duplicate preset rejects a repeated ClOrdID and lets a new order through`() {
        val rules =
            AcceptorPresets
                .insert(
                    AcceptorPresets.insert(emptyList(), AcceptorPresets.byId("order-ack")!!).rules,
                    AcceptorPresets.byId("duplicate-clordid")!!,
                ).rules

        val fresh =
            AcceptorResponder
                .explain(rules, AcceptorResponder.buildMessage(limitOrder), BookReading.unknown("ORD-1"))
                .first { it.selected }
        assertTrue(
            fresh.rule
                .sequence()
                .single()
                .template
                .contains("150=0"),
            "a new order is acknowledged",
        )

        val repeated =
            AcceptorResponder
                .explain(rules, AcceptorResponder.buildMessage(limitOrder), BookReading("ORD-1", OrderState.WORKING))
                .first { it.selected }
        assertTrue(
            repeated.rule
                .sequence()
                .single()
                .template
                .contains("103=6"),
            "a repeat is rejected as a duplicate",
        )
    }

    /**
     * Two 35=H presets that are two different venues: one that does not support status requests at
     * all, and one that supports them but has never heard of this order. Conditioning the first into
     * the second would have left neither available.
     */
    @Test
    fun `the two status-request presets stay distinguishable`() {
        val unsupported = leadRule("unsupported-message")
        val unknown = leadRule("status-request-unknown")

        assertNull(unsupported.whenOrder, "'unsupported' is a property of the venue, not of the order")
        assertEquals(OrderConstraint.UNKNOWN, unknown.whenOrder)
        assertTrue(
            unsupported
                .sequence()
                .single()
                .template
                .contains("380=3"),
            "unsupported message type",
        )
        assertTrue(
            unknown
                .sequence()
                .single()
                .template
                .contains("380=1"),
            "unknown id — a different reject",
        )
    }

    /**
     * `whenOrder` is a constraint, so a rule carrying only one must be placed and judged as a
     * conditioned rule. Read off `trigger()` alone — which is what both callers did before this
     * slice — it looks unconditioned, gets appended below the rule it was meant to precede, and never
     * fires.
     */
    @Test
    fun `a preset conditioned only on the book still lands where it can fire`() {
        val existing = listOf(unconditioned("F"))

        val insertion = AcceptorPresets.insert(existing, AcceptorPresets.byId("cancel-rejected-unknown")!!)

        assertEquals(0, insertion.index, "appended, it would sit below a rule that answers every 35=F")
        assertNull(AcceptorResponder.shadowingRule(insertion.rules, 0))
    }
}
