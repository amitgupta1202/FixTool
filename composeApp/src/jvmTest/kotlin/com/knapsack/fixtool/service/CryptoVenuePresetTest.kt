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
 * **The crypto venue is content, and content goes stale silently** — the stance every preset test here
 * takes, asked of the engine that runs the rules rather than of the source.
 *
 * What this one has to prove is that it is **a crypto venue and not the equity venue with different
 * tickers**. So the assertions below are mostly about the four rules an equity venue has no use for: a
 * Day order refused because there is no day, a post-only order refused rather than crossed, a size finer
 * than a satoshi, and a size below the dust floor. Each is asked of a message that should trigger it and,
 * where the distinction is subtle, of the message that looks like it and must not.
 */
class CryptoVenuePresetTest {
    private val dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)

    private val bundle = AcceptorPresets.insert(emptyList(), AcceptorPresets.byId(CryptoVenuePreset.ID)!!).rules

    private val btc = CryptoVenuePreset.PRODUCTS.first { it.symbol == "BTC-USD" }

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

    /** **The card order, asserted whole**, because first-match-wins makes it the venue's behaviour. */
    @Test
    fun `the crypto venue's cards read in the documented order`() {
        val read = bundle.map { rule -> rule.whenMsgType + describe(rule) }

        // The four refusals outrank every accept path, and post-only outranks the IOC fills and the
        // resting rule — or a marketable post-only order is filled, which is what its sender paid to
        // avoid. Day is first because it is the only one that is a fact about the venue's hours.
        assertEquals("D 55∈listed 59=0", read[2], "the Day refusal has to outrank everything")
        assertEquals("D 55∈listed 38~fine", read[3], "then the size the venue cannot hold")
        assertEquals(
            listOf("D 55=BTC-USD 38<1.0E-4", "D 55=ETH-USD 38<0.001", "D 55=SOL-USD 38<0.01"),
            read.slice(4..6),
            "then the dust floor, per product",
        )
        assertTrue(read.slice(7..12).all { it.contains("18=6") }, "post-only next: ${read.slice(7..12)}")
        assertTrue(read.slice(13..18).all { it.contains("59=3") }, "then the IOC fills: ${read.slice(13..18)}")
        assertEquals("D 55∈listed 40=2 59=3", read[19], "an IOC that is not marketable is cancelled")
        assertEquals("D 55∈listed 40=2", read[20], "and everything else that is a limit rests")
        assertEquals("D", read[27], "with the unlisted refusal at the foot of the block")
        assertEquals(41, bundle.size, "the summary counts the rules, so the count is the summary")
    }

    private fun describe(rule: AcceptorResponseRule): String =
        rule.trigger().joinToString("") { c ->
            when (val m = c.parsed()) {
                is Matcher.Exact -> " ${c.tag}=${m.value}"
                is Matcher.OneOf -> " ${c.tag}∈listed"
                is Matcher.Regex -> " ${c.tag}~fine"
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
    fun `every rule of the crypto venue is reachable`() {
        bundle.indices.forEach { index ->
            assertTrue(
                AcceptorResponder.shadowingRule(bundle, index) == null,
                "rule ${index + 1} (35=${bundle[index].whenMsgType}) can never fire",
            )
        }
    }

    @Test
    fun `every rule of the crypto venue is a rule the editor would accept`() {
        bundle.forEach { rule -> assertEquals(null, rule.validationError(), "35=${rule.whenMsgType} is not usable") }
    }

    @Test
    fun `no rule of the crypto venue sends an unevaluated expression`() {
        bundle.forEach { rule ->
            answers(sampleFor(rule), rule.whenOrder).forEach { sent ->
                assertTrue(
                    !sent.contains("\${"),
                    "35=${rule.whenMsgType} sends an expression it never evaluated: $sent",
                )
            }
        }
    }

    /** Every reply validated the way a client's engine validates it — undefined enums included. */
    @Test
    fun `every reply the crypto venue can plan validates against the bundled dictionary`() {
        val dd = assertNotNull(dictionary.getDataDictionary(), "the bundled FIX 4.4 dictionary did not load")
        bundle.forEach { rule ->
            val raw = sampleFor(rule)
            AcceptorResponder
                .plan(
                    rule,
                    AcceptorResponder.buildMessage(raw, dictionary),
                    request(raw),
                    dictionary,
                    { null },
                    { BOOKED },
                )
                .forEach { planned ->
                    runCatching { dd.validate(planned.build(), true) }
                        .onFailure {
                            error("35=${rule.whenMsgType} plans an invalid reply: ${planned.render()} — $it")
                        }
                }
        }
    }

    @Test
    fun `no reply of the crypto venue reaches the script engine`() {
        val native = Regex("""^(uuid(:\d+)?|now|utcnow.*|random:[-\d.]+:[-\d.]+:\d+|req\.[\w.]+|order\.[\w.]+)$""")
        bundle.forEach { rule ->
            rule.steps.forEach { step ->
                Regex("""\$\{([^}]*)}""").findAll(step.template).forEach { m ->
                    assertTrue(
                        native.matches(m.groupValues[1]),
                        "35=${rule.whenMsgType} carries '${m.value}', which the script engine would compile",
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------- the products

    /** Hyphenated, as every crypto venue writes them — not the FX desk's `BTC/USD`. */
    @Test
    fun `the products are named the way a crypto venue names them`() {
        CryptoVenuePreset.PRODUCTS.forEach {
            assertTrue(it.symbol.matches(Regex("""^[A-Z]{3,4}-USD$""")), "${it.symbol} is not a crypto product name")
            assertTrue(BigDecimal(it.offer) > BigDecimal(it.bid), "${it.symbol} prints an inverted touch")
        }
    }

    // ---------------------------------------------------------------- what only a crypto venue does

    /**
     * **The rule this venue is here for.** A post-only order that would cross is refused, where every
     * other venue in the build would have filled it — which is the whole economic point of the flag.
     */
    @Test
    fun `a post-only order that would take liquidity is refused, not filled`() {
        val refused = answer("35=D|11=C-1|55=BTC-USD|54=1|38=0.10000000|40=2|18=6|44=61300.00|59=1|60=x")

        assertEquals("8", field(refused, 39), "it must not fill: got $refused")
        assertEquals("11", field(refused, 103))
        assertTrue(field(refused, 58)!!.contains("18=6"), refused)
        assertTrue(field(refused, 58)!!.contains("taken liquidity"), refused)
    }

    @Test
    fun `a post-only order that would rest is left to rest`() {
        val sent = answers("35=D|11=C-2|55=BTC-USD|54=1|38=0.10000000|40=2|18=6|44=61000.00|59=1|60=x")

        assertEquals(1, sent.size, "a resting order draws one reply")
        assertEquals("0", field(sent.single(), 39))
    }

    /** A sell is marketable downwards, so its post-only bound is the mirror of a buy's. */
    @Test
    fun `a post-only sell at or below the bid is refused too`() {
        val atTheBid = "35=D|11=C-3|55=BTC-USD|54=2|38=0.10000000|40=2|18=6|44=61240.50|59=1|60=x"
        val above = "35=D|11=C-4|55=BTC-USD|54=2|38=0.10000000|40=2|18=6|44=61500.00|59=1|60=x"
        assertEquals("8", field(answer(atTheBid), 39), "a sell at the bid would have taken")
        assertEquals("0", field(answer(above), 39), "and one above it rests")
    }

    @Test
    fun `a Day order is refused, because this venue has no day to be good for`() {
        val refused = answer("35=D|11=C-5|55=BTC-USD|54=1|38=0.10000000|40=2|44=61000.00|59=0|60=x")

        assertEquals("8", field(refused, 39))
        assertEquals("11", field(refused, 103), "103=11 is Unsupported order characteristic")
        assertTrue(field(refused, 58)!!.contains("never closes"), refused)
        assertTrue(field(refused, 58)!!.contains("59=1"), "it has to say what to send instead: $refused")
    }

    /**
     * A size with a ninth decimal is finer than the venue can hold. `0.10000000` is eight decimals
     * written long and a perfectly good size — the condition matches the fault, not the good case.
     */
    @Test
    fun `a size finer than a satoshi is refused, and one written long is not`() {
        val refused = answer("35=D|11=C-6|55=BTC-USD|54=1|38=0.123456789|40=2|44=61000.00|59=1|60=x")
        assertEquals("13", field(refused, 103), "103=13 is Incorrect quantity")
        assertTrue(field(refused, 58)!!.contains("0.00000001"), refused)

        val rested = answers("35=D|11=C-7|55=BTC-USD|54=1|38=0.10000000|40=2|44=61000.00|59=1|60=x")
        assertEquals(1, rested.size, "eight decimals written out is a size, not a fault")
        assertEquals("0", field(rested.single(), 39))
    }

    @Test
    fun `a size below the product's dust floor is refused, and names the floor`() {
        CryptoVenuePreset.PRODUCTS.forEach { product ->
            val under = (BigDecimal(product.minSize) / BigDecimal(10)).toPlainString()
            val refused = answer("35=D|11=C-8|55=${product.symbol}|54=1|38=$under|40=2|44=${product.bid}|59=1|60=x")
            assertEquals("13", field(refused, 103), "${product.symbol} took a dust order")
            assertTrue(field(refused, 58)!!.contains(product.minSize), refused)
            assertTrue(field(refused, 58)!!.contains(product.base), "the floor is in the base currency: $refused")
        }
    }

    @Test
    fun `an IOC that crosses is filled at the touch, and one that does not is cancelled`() {
        val filled = answers("35=D|11=C-9|55=BTC-USD|54=1|38=0.01000000|40=2|44=61300.00|59=3|60=x")
        assertEquals(btc.offer, field(filled[1], 31), "a buy pays the offer")
        assertEquals(btc.offer, field(filled[1], 6))

        val cancelled = answers("35=D|11=C-10|55=BTC-USD|54=1|38=0.01000000|40=2|44=61000.00|59=3|60=x")
        assertEquals("4", field(cancelled[1], 39))
        assertEquals("0", field(cancelled[1], 151))
    }

    @Test
    fun `a market order fills at the touch on the side it took`() {
        assertEquals(btc.offer, field(answers("35=D|11=C-11|55=BTC-USD|54=1|38=0.01000000|40=1|59=1|60=x")[1], 31))
        assertEquals(btc.bid, field(answers("35=D|11=C-12|55=BTC-USD|54=2|38=0.01000000|40=1|59=1|60=x")[1], 31))
    }

    @Test
    fun `an order for a product this venue does not list is refused, and says what it does list`() {
        val refused = answer("35=D|11=C-13|55=DOGE-USD|54=1|38=1.00000000|40=2|44=0.12|59=1|60=x")
        assertEquals("1", field(refused, 103))
        CryptoVenuePreset.SYMBOLS.forEach { assertTrue(field(refused, 58)!!.contains(it), refused) }
    }

    @Test
    fun `the book it publishes is two-sided, in band, and sized in fractions`() {
        CryptoVenuePreset.PRODUCTS.forEach { product ->
            repeat(SAMPLES) {
                val snapshot = answers("35=V|262=M-1|263=0|264=1|267=2|269=0|269=1|146=1|55=${product.symbol}").single()
                val prices = snapshot.split("|").filter { it.startsWith("270=") }.map { it.substringAfter("=") }
                assertTrue(
                    BigDecimal(prices[0]) in BigDecimal(product.bidLow)..BigDecimal(product.bidHigh),
                    "${product.symbol} published a bid of ${prices[0]}",
                )
                assertTrue(BigDecimal(prices[0]) < BigDecimal(prices[1]), "published an inverted book: $snapshot")
                snapshot.split("|").filter { it.startsWith("271=") }.forEach { entry ->
                    val size = entry.substringAfter("=")
                    assertEquals(8, size.substringAfter(".").length, "a crypto size carries eight decimals: $entry")
                }
            }
        }
    }

    @Test
    fun `market data for a product this venue does not publish is rejected by name`() {
        val sent = answer("35=V|262=M-2|263=0|264=1|267=1|269=0|146=1|55=DOGE-USD")
        assertTrue(sent.startsWith("35=Y"), sent)
        assertEquals("0", field(sent, 281))
    }

    // ---------------------------------------------------------------- helpers

    private fun bookState(constraint: OrderConstraint) =
        when (constraint) {
            OrderConstraint.PENDING -> com.knapsack.fixtool.model.OrderState.PENDING
            OrderConstraint.WORKING -> com.knapsack.fixtool.model.OrderState.WORKING
            OrderConstraint.DONE -> com.knapsack.fixtool.model.OrderState.DONE
            OrderConstraint.UNKNOWN -> null
        }

    private fun sampleFor(rule: AcceptorResponseRule): String {
        fun exact(tag: Int) = (rule.trigger().firstOrNull { it.tag == tag }?.parsed() as? Matcher.Exact)?.value
        fun bound(tag: Int) = rule.trigger().firstOrNull { it.tag == tag }?.parsed() as? Matcher.Range

        val symbol = exact(55) ?: "BTC-USD"
        val product = CryptoVenuePreset.PRODUCTS.firstOrNull { it.symbol == symbol } ?: btc
        val side = exact(54) ?: "1"
        val ordType = exact(40) ?: "2"
        val tif = exact(59) ?: "1"
        val postOnly = exact(18)?.let { "|18=$it" } ?: ""
        val qty =
            when {
                rule.trigger().any { it.parsed() is Matcher.Regex } -> "0.123456789"
                bound(38)?.max != null -> "0.00000100"
                else -> "0.10000000"
            }
        val price =
            when {
                bound(44)?.max != null -> BigDecimal(bound(44)!!.max!!.toString()).toPlainString()
                bound(44)?.min != null -> BigDecimal(bound(44)!!.min!!.toString()).toPlainString()
                else -> product.bid
            }
        return when (rule.whenMsgType) {
            "D" ->
                if (ordType == "1") {
                    "35=D|11=S-1|55=$symbol|54=$side|38=$qty|40=1|59=$tif|60=x"
                } else {
                    "35=D|11=S-1|55=$symbol|54=$side|38=$qty|40=2|44=$price$postOnly|59=$tif|60=x"
                }
            "F" -> "35=F|11=S-2|41=S-1|55=$symbol|54=$side|60=x"
            "G" -> "35=G|11=S-3|41=S-1|55=$symbol|54=$side|38=0.20000000|40=2|44=${product.bid}|60=x"
            "H" -> "35=H|11=S-1|55=$symbol|54=$side"
            "V" -> "35=V|262=S-4|263=0|264=1|267=2|269=0|269=1|146=1|55=$symbol"
            else -> error("no sample message for 35=${rule.whenMsgType}")
        }
    }

    private companion object {
        const val SAMPLES = 15

        val BOOKED =
            mapOf(
                "orderId" to "8f14e45f-ea1a-4d9f-b3c1-6bd0f0e2a7c3",
                "ordStatus" to "0",
                "avgPx" to "0",
                "cumQty" to "0",
                "leavesQty" to "0.10000000",
                "symbol" to "BTC-USD",
                "side" to "1",
                "orderQty" to "0.10000000",
            )
    }
}
