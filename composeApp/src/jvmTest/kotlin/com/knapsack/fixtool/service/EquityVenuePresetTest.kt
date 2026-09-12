package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.BookReading
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.OrderConstraint
import com.knapsack.fixtool.model.scenario.Matcher
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The equity venue is content, and content goes stale silently** — the stance `FxVenuePresetTest` and
 * `RfqVenuePresetTest` take, asked of the engine that runs the rules rather than of the source.
 *
 * Two claims here are this venue's own, and both are claims about **the real domain** rather than about
 * the tool. The reason codes must be values FIX 4.4 actually defines, which is what the dictionary
 * validation below is for: the first draft of this venue refused a sub-penny price with `103=18`
 * *Invalid price increment*, which is exactly the right name and does not exist before FIX 5.0 — the 4.4
 * enum stops at 15 and 99. And the price band must be the band the venue says it is, so it is
 * **re-derived from the printed touch here** instead of being read back from the same literals the rules
 * were built from.
 */
class EquityVenuePresetTest {
    private val dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)

    private val bundle = AcceptorPresets.insert(emptyList(), AcceptorPresets.byId(EquityVenuePreset.ID)!!).rules

    private val aapl = EquityVenuePreset.LISTINGS.first { it.symbol == "AAPL" }

    private fun request(raw: String) =
        FixMessage(
            timestamp = LocalDateTime.now(),
            direction = FixMessage.Direction.INCOMING,
            rawMessage = raw,
            quickfixMessage = AcceptorResponder.buildMessage(raw, dictionary),
        )

    /**
     * What the venue sends back to [raw], with the book in [state].
     *
     * [BOOKED] stands in for the order a `${order.…}` reply reads. It has to be supplied rather than
     * left null, because a status request's whole reply is read out of the book and a null one would
     * render every field of it empty — a reply that is still well formed and says nothing.
     */
    private fun answers(raw: String, state: OrderConstraint? = null): List<String> {
        val message = AcceptorResponder.buildMessage(raw, dictionary)
        val reading = state?.let { BookReading(key = "K", state = bookState(it), leavesQty = null) }
        val rule =
            AcceptorResponder.firstMatch(AcceptorResponder.compile(bundle), message, reading)
                ?: error("no rule answered: $raw")
        return AcceptorResponder
            .plan(rule, message, request(raw), dictionary, { null }, { BOOKED })
            .map { it.render() }
    }

    private fun answer(raw: String, state: OrderConstraint? = null) = answers(raw, state).first()

    private fun field(raw: String, tag: Int) =
        raw.split("|").firstOrNull { it.startsWith("$tag=") }?.substringAfter("=")

    // ---------------------------------------------------------------- the shape of the bundle

    /**
     * **The card order, asserted whole.** [AcceptorPresets.insert] reverses each declared block on the
     * way in, so the order a reader sees is not the order the source lists, and first-match-wins makes
     * that order the venue's behaviour.
     */
    @Test
    fun `the equity venue's cards read in the documented order`() {
        val read = bundle.map { rule -> rule.whenMsgType + describe(rule) }

        assertEquals(
            listOf(
                // 35=D: the guards first, or an order is accepted for a reason that is not true of it.
                "D order:working",
                "D order:pending",
                "D 38>1000000.0",
                "D 55∈listed 54=5 114:absent",
                "D 55∈listed 40=2 44~subpenny",
                "D 55=AAPL 40=2 44>238.78",
                "D 55=AAPL 40=2 44<216.04",
                "D 55=MSFT 40=2 44>439.03",
                "D 55=MSFT 40=2 44<397.21",
                "D 55=TSLA 40=2 44>258.9",
                "D 55=TSLA 40=2 44<234.25",
                "D 55=AAPL 40=2 59=3 54=1 44>=227.42",
                "D 55=AAPL 40=2 59=3 54=2 44<=227.4",
                "D 55=MSFT 40=2 59=3 54=1 44>=418.14",
                "D 55=MSFT 40=2 59=3 54=2 44<=418.1",
                "D 55=TSLA 40=2 59=3 54=1 44>=246.6",
                "D 55=TSLA 40=2 59=3 54=2 44<=246.55",
                "D 55∈listed 40=2 59=3",
                "D 55∈listed 40=2",
                "D 55=AAPL 40=1 54=1",
                "D 55=AAPL 40=1 54=2",
                "D 55=MSFT 40=1 54=1",
                "D 55=MSFT 40=1 54=2",
                "D 55=TSLA 40=1 54=1",
                "D 55=TSLA 40=1 54=2",
                "D",
                // 35=F, 35=G, 35=H: reused from the starter venue, and here they have something to act on.
                "F order:unknown",
                "F order:pending",
                "F order:working",
                "F order:done",
                "G 38 order:working",
                "G 38",
                "H order:unknown",
                "H order:working",
                "H order:done",
                // 35=V: a subscription per listing, then a snapshot per listing, then the refusal.
                "V 55=AAPL 263=1",
                "V 55=MSFT 263=1",
                "V 55=TSLA 263=1",
                "V 55=AAPL",
                "V 55=MSFT",
                "V 55=TSLA",
                "V",
            ),
            read,
        )
        assertEquals(42, bundle.size, "the summary counts the rules, so the count is the summary")
    }

    private fun describe(rule: AcceptorResponseRule): String =
        rule.trigger().joinToString("") { c ->
            when (val m = c.parsed()) {
                is Matcher.Exact -> " ${c.tag}=${m.value}"
                is Matcher.OneOf -> " ${c.tag}∈listed"
                is Matcher.Regex -> " ${c.tag}~subpenny"
                Matcher.Presence -> " ${c.tag}"
                Matcher.Absent -> " ${c.tag}:absent"
                is Matcher.Range ->
                    when {
                        m.min != null && m.minInclusive -> " ${c.tag}>=${m.min}"
                        m.min != null -> " ${c.tag}>${m.min}"
                        m.max != null && m.maxInclusive -> " ${c.tag}<=${m.max}"
                        else -> " ${c.tag}<${m.max}"
                    }
                else -> " ${c.tag}?"
            }
        } + (rule.whenOrder?.let { " order:${it.word}" } ?: "")

    @Test
    fun `every rule of the equity venue is reachable`() {
        bundle.indices.forEach { index ->
            assertTrue(
                AcceptorResponder.shadowingRule(bundle, index) == null,
                "rule ${index + 1} (35=${bundle[index].whenMsgType}) can never fire",
            )
        }
    }

    @Test
    fun `every rule of the equity venue is a rule the editor would accept`() {
        bundle.forEach { rule ->
            assertEquals(null, rule.validationError(), "35=${rule.whenMsgType} is not a usable rule")
        }
    }

    @Test
    fun `no rule of the equity venue sends an unevaluated expression`() {
        bundle.forEach { rule ->
            answers(sampleFor(rule), rule.whenOrder).forEach { sent ->
                assertTrue(
                    !sent.contains("\${"),
                    "35=${rule.whenMsgType} would send an expression it never evaluated: $sent",
                )
            }
        }
    }

    /**
     * **Nothing this venue plans is malformed against FIX 4.4** — the same validation the RFQ venue
     * carries, and the reason this venue's reason codes are the ones it sends. A tag the message type
     * does not define, a required tag missing, **or an enum value the dictionary does not list** all
     * fail here, which is what caught `103=18` before it shipped.
     */
    @Test
    fun `every reply the equity venue can plan validates against the bundled dictionary`() {
        val dd = assertNotNull(dictionary.getDataDictionary(), "the bundled FIX 4.4 dictionary did not load")
        bundle.forEach { rule ->
            val raw = sampleFor(rule)
            val message = AcceptorResponder.buildMessage(raw, dictionary)
            AcceptorResponder
                .plan(rule, message, request(raw), dictionary, { null }, { BOOKED })
                .forEach { planned ->
                    runCatching { dd.validate(planned.build(), true) }
                        .onFailure { error("35=${rule.whenMsgType} plans an invalid reply: ${planned.render()} — $it") }
                }
        }
    }

    /**
     * **The whole venue renders natively.** `${random:…}`, `${uuid}`, `${now}`, `${req.…}` and
     * `${order.…}` are substituted by the renderer in microseconds; anything else is a Kotlin expression,
     * and the script engine compiles one at a time for the whole process at tens of milliseconds each.
     * That is the difference between a venue a load run can measure and the FX venue, whose drawn fill
     * price costs its dispatch thread ~58 ms per order.
     */
    @Test
    fun `no reply of the equity venue reaches the script engine`() {
        val native = Regex("""^(uuid(:\d+)?|now|utcnow.*|random:[-\d.]+:[-\d.]+:\d+|req\.[\w.]+|order\.[\w.]+)$""")
        bundle.forEach { rule ->
            rule.steps.forEach { step ->
                Regex("""\$\{([^}]*)}""").findAll(step.template).forEach { m ->
                    assertTrue(
                        native.matches(m.groupValues[1]),
                        "35=${rule.whenMsgType} carries '${m.value}', which the script engine would have to compile",
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------- the listings

    /**
     * **The band is re-derived, not read back.** Every bound is the printed mid ±5%, to the cent, and
     * asserting that here is the only thing standing between a band and a number somebody typed.
     */
    @Test
    fun `every price band is five per cent either side of the printed mid`() {
        EquityVenuePreset.LISTINGS.forEach { listing ->
            val mid =
                (BigDecimal(listing.bid) + BigDecimal(listing.offer))
                    .divide(BigDecimal(2))
            fun at(factor: String) =
                (mid * BigDecimal(factor)).setScale(2, RoundingMode.HALF_UP).toPlainString()
            assertEquals(at("0.95"), listing.bandLow, "${listing.symbol} opens where it says it does")
            assertEquals(at("1.05"), listing.bandHigh, "${listing.symbol} closes where it says it does")
        }
    }

    @Test
    fun `the printed offer is above the printed bid, on every listing`() {
        EquityVenuePreset.LISTINGS.forEach {
            assertTrue(
                BigDecimal(it.offer) > BigDecimal(it.bid),
                "${it.symbol} prints an inverted touch",
            )
        }
    }

    // ---------------------------------------------------------------- what the venue does

    /**
     * **The rule this venue exists for.** One reply, `39=0`, and the order is still working afterwards —
     * which is what every cancel, replace and status claim below depends on.
     */
    @Test
    fun `a Day limit order is acknowledged and then left alone`() {
        val sent = answers("35=D|11=A-1|55=AAPL|54=1|38=100|40=2|44=225.00|59=0|60=x")

        assertEquals(1, sent.size, "a resting order draws one reply: ${sent.joinToString()}")
        assertEquals("0", field(sent.single(), 39))
        assertEquals("0", field(sent.single(), 150))
        assertEquals("100", field(sent.single(), 151), "all of it is still working")
    }

    /**
     * FIX's default TimeInForce is Day, so an order that names none has to rest. The resting rule
     * carries no `59` condition for this reason, and an order with no `59` used to fall past every order
     * rule to the unlisted-symbol refusal — told, in effect, that the venue had never heard of AAPL.
     */
    @Test
    fun `an order with no TimeInForce rests, because Day is the default`() {
        val sent = answers("35=D|11=A-2|55=AAPL|54=1|38=100|40=2|44=225.00|60=x")

        assertEquals(1, sent.size)
        assertEquals("0", field(sent.single(), 39))
    }

    @Test
    fun `an IOC that crosses the touch is filled at the touch, not at its own limit`() {
        val sent = answers("35=D|11=A-3|55=AAPL|54=1|38=100|40=2|44=228.00|59=3|60=x")

        assertEquals(2, sent.size, "New, then the fill")
        assertEquals("2", field(sent[1], 39))
        assertEquals(aapl.offer, field(sent[1], 31), "a buy pays the offer; the improvement is the client's")
        assertEquals(aapl.offer, field(sent[1], 6), "one fill, so LastPx and AvgPx are the same number")
        assertEquals("100", field(sent[1], 14))
    }

    @Test
    fun `an IOC away from the touch is cancelled, and says why`() {
        val sent = answers("35=D|11=A-4|55=AAPL|54=1|38=100|40=2|44=225.00|59=3|60=x")

        assertEquals(2, sent.size)
        assertEquals("4", field(sent[1], 39))
        assertEquals("0", field(sent[1], 14), "nothing filled")
        assertEquals("0", field(sent[1], 151), "and nothing is left working, which is what IOC means")
        assertTrue(field(sent[1], 58)!!.contains("Immediate-or-cancel"), sent[1])
    }

    @Test
    fun `a market buy prints at the offer and a market sell at the bid`() {
        assertEquals(aapl.offer, field(answers("35=D|11=A-5|55=AAPL|54=1|38=100|40=1|60=x")[1], 31))
        assertEquals(aapl.bid, field(answers("35=D|11=A-6|55=AAPL|54=2|38=100|40=1|60=x")[1], 31))
    }

    /**
     * **SEC Rule 612**, and the reason the condition matches the fault rather than the good case: written
     * as `^\d+(\.\d{1,2})?$` over the acceptable prices it would also have refused `227.4200`, which is a
     * penny price with trailing zeros and perfectly good FIX.
     */
    @Test
    fun `a sub-penny limit price is refused, and a penny price written long is not`() {
        val refused = answer("35=D|11=A-7|55=AAPL|54=1|38=100|40=2|44=227.4213|59=0|60=x")
        assertEquals("8", field(refused, 39))
        assertEquals("11", field(refused, 103), "103=11 is Unsupported order characteristic, which 4.4 defines")
        assertTrue(field(refused, 58)!!.contains("612"), refused)

        val rested = answers("35=D|11=A-8|55=AAPL|54=1|38=100|40=2|44=227.4200|59=0|60=x")
        assertEquals(1, rested.size, "trailing zeros are not a sub-penny price")
        assertEquals("0", field(rested.single(), 39))
    }

    /** Reg SHO: a broker has to have located the stock before it can sell it short. */
    @Test
    fun `a sell short with no locate is refused, and one with a locate rests`() {
        val refused = answer("35=D|11=A-9|55=AAPL|54=5|38=100|40=2|44=230.00|59=0|60=x")
        assertEquals("8", field(refused, 39))
        assertEquals("0", field(refused, 103))
        assertTrue(field(refused, 58)!!.contains("Reg SHO"), refused)

        val rested = answers("35=D|11=A-10|55=AAPL|54=5|38=100|40=2|44=230.00|59=0|114=Y|60=x")
        assertEquals(1, rested.size)
        assertEquals("0", field(rested.single(), 39))
    }

    @Test
    fun `a limit outside the band is refused, and names the bound it crossed`() {
        val high = answer("35=D|11=A-11|55=AAPL|54=1|38=100|40=2|44=250.00|59=0|60=x")
        assertEquals("3", field(high, 103), "103=3 is Order exceeds limit — here a price limit")
        assertTrue(field(high, 58)!!.contains(aapl.bandHigh), high)

        val low = answer("35=D|11=A-12|55=AAPL|54=1|38=100|40=2|44=100.00|59=0|60=x")
        assertTrue(field(low, 58)!!.contains(aapl.bandLow), low)
    }

    @Test
    fun `an order for a name this venue does not list is refused, and says what it does list`() {
        val refused = answer("35=D|11=A-13|55=IBM|54=1|38=100|40=2|44=200.00|59=0|60=x")
        assertEquals("1", field(refused, 103), "103=1 is Unknown symbol")
        EquityVenuePreset.SYMBOLS.forEach { assertTrue(field(refused, 58)!!.contains(it), refused) }
    }

    // ---------------------------------------------------------------- market data

    @Test
    fun `a subscription draws a two-sided snapshot and then an update on each side`() {
        val sent = answers("35=V|262=M-1|263=1|264=1|267=2|269=0|269=1|146=1|55=AAPL")

        assertEquals(3, sent.size, "snapshot, then one update per side")
        assertTrue(sent[0].startsWith("35=W"), sent[0])
        assertEquals("2", field(sent[0], 268), "both sides of the touch")
        assertEquals("M-1", field(sent[0], 262), "the request's own id, so a client can tie it back")
        sent.drop(1).forEach {
            assertTrue(it.startsWith("35=X"), it)
            assertEquals("1", field(it, 279), "279=1 is Change, which is what a top-of-book update is")
        }
    }

    @Test
    fun `a snapshot request draws a snapshot and nothing after it`() {
        val sent = answers("35=V|262=M-2|263=0|264=1|267=2|269=0|269=1|146=1|55=MSFT")

        assertEquals(1, sent.size)
        assertTrue(sent.single().startsWith("35=W"), sent.single())
    }

    @Test
    fun `market data for a name this venue does not publish is rejected by name`() {
        val sent = answer("35=V|262=M-3|263=0|264=1|267=1|269=0|146=1|55=IBM")

        assertTrue(sent.startsWith("35=Y"), sent)
        assertEquals("0", field(sent, 281), "281=0 is Unknown symbol")
        assertEquals("M-3", field(sent, 262))
    }

    /** Every published price sits in its listing's own band, at the penny. */
    @Test
    fun `every published price lands in its listing's band`() {
        EquityVenuePreset.LISTINGS.forEach { listing ->
            repeat(SAMPLES) {
                val snapshot = answers("35=V|262=M-4|263=0|264=1|267=2|269=0|269=1|146=1|55=${listing.symbol}").single()
                val prices = snapshot.split("|").filter { it.startsWith("270=") }.map { it.substringAfter("=") }
                assertEquals(2, prices.size, snapshot)
                assertTrue(
                    BigDecimal(prices[0]) in BigDecimal(listing.bidLow)..BigDecimal(listing.bidHigh),
                    "${listing.symbol} published a bid of ${prices[0]}",
                )
                assertTrue(
                    BigDecimal(prices[1]) in BigDecimal(listing.offerLow)..BigDecimal(listing.offerHigh),
                    "${listing.symbol} published an offer of ${prices[1]}",
                )
                assertTrue(BigDecimal(prices[0]) < BigDecimal(prices[1]), "published an inverted book: $snapshot")
            }
        }
    }

    /** A lit book shows round lots, so a draw that published 137 shares would be wrong about the domain. */
    @Test
    fun `every published size is a round lot`() {
        repeat(SAMPLES) {
            val snapshot = answers("35=V|262=M-5|263=0|264=1|267=2|269=0|269=1|146=1|55=AAPL").single()
            snapshot.split("|").filter { it.startsWith("271=") }.forEach { entry ->
                val size = entry.substringAfter("=").toInt()
                assertEquals(0, size % 100, "published an odd lot: $entry")
                assertTrue(size in 100..900, "published $size shares")
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun bookState(constraint: OrderConstraint) =
        when (constraint) {
            OrderConstraint.PENDING -> com.knapsack.fixtool.model.OrderState.PENDING
            OrderConstraint.WORKING -> com.knapsack.fixtool.model.OrderState.WORKING
            OrderConstraint.DONE -> com.knapsack.fixtool.model.OrderState.DONE
            OrderConstraint.UNKNOWN -> null
        }

    /** The message each rule is designed against, derived from the rule's own conditions. */
    private fun sampleFor(rule: AcceptorResponseRule): String {
        fun exact(tag: Int) = (rule.trigger().firstOrNull { it.tag == tag }?.parsed() as? Matcher.Exact)?.value
        fun bound(tag: Int) = rule.trigger().firstOrNull { it.tag == tag }?.parsed() as? Matcher.Range

        val symbol = exact(55) ?: "AAPL"
        val listing = EquityVenuePreset.LISTINGS.firstOrNull { it.symbol == symbol } ?: aapl
        val side = exact(54) ?: "1"
        val ordType = exact(40) ?: "2"
        val tif = exact(59)?.let { "|59=$it" } ?: ""
        val locate = if (rule.trigger().any { it.tag == 114 }) "" else "|114=Y"
        val qty = bound(38)?.min?.let { (it + 1).toLong().toString() } ?: "100"
        val price =
            when {
                rule.trigger().any { it.parsed() is Matcher.Regex } -> "227.4213"
                bound(44)?.max != null -> BigDecimal(bound(44)!!.max!!.toString()).setScale(2).toPlainString()
                bound(44)?.min != null -> BigDecimal(bound(44)!!.min!!.toString()).setScale(2).toPlainString()
                else -> "225.00"
            }
        return when (rule.whenMsgType) {
            "D" ->
                if (ordType == "1") {
                    "35=D|11=S-1|55=$symbol|54=$side|38=$qty|40=1$tif$locate|60=x"
                } else {
                    "35=D|11=S-1|55=$symbol|54=$side|38=$qty|40=2|44=$price$tif$locate|60=x"
                }
            "F" -> "35=F|11=S-2|41=S-1|55=$symbol|54=$side|60=x"
            "G" -> "35=G|11=S-3|41=S-1|55=$symbol|54=$side|38=200|40=2|44=${listing.bid}|60=x"
            "H" -> "35=H|11=S-1|55=$symbol|54=$side"
            "V" -> "35=V|262=S-4|263=${exact(263) ?: "0"}|264=1|267=2|269=0|269=1|146=1|55=$symbol"
            else -> error("no sample message for 35=${rule.whenMsgType}")
        }
    }

    private companion object {
        /** Enough draws that a band edge or a stray odd lot shows up, cheap because nothing compiles. */
        const val SAMPLES = 25

        /** The order a `${order.…}` reply reads, as the book would hand it over. */
        val BOOKED =
            mapOf(
                "orderId" to "8f14e45f-ea1a-4d9f-b3c1-6bd0f0e2a7c3",
                "ordStatus" to "0",
                "avgPx" to "0",
                "cumQty" to "0",
                "leavesQty" to "100",
                "symbol" to "AAPL",
                "side" to "1",
                "orderQty" to "100",
            )
    }
}
