package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.QuoteConstraint
import com.knapsack.fixtool.model.QuoteEntry
import com.knapsack.fixtool.model.QuoteReading
import com.knapsack.fixtool.model.QuoteState
import com.knapsack.fixtool.model.scenario.Matcher
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The fixed-income desk, held to a bond desk's vocabulary rather than to FIX's alone.**
 *
 * A message can be perfectly well formed and still be nonsense to the person reading it, so most of what
 * follows is about the domain: that a disclosed side is quoted one way, that a price sits on the 32nd
 * grid, that the yields move opposite the prices, and that Cover and Done Away are answers rather than
 * errors. The dictionary check is here too, for the same reason every venue has one — `632`, `634`,
 * `537`, `423` and the Instrument block are fields this venue is the first to use, and a tag on a
 * message that does not define it renders perfectly and is still malformed.
 */
class FiRfqVenuePresetTest {
    private val dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)

    private val bundle = AcceptorPresets.insert(emptyList(), AcceptorPresets.byId(FiRfqVenuePreset.ID)!!).rules

    private val tenYear = FiRfqVenuePreset.ISSUES.last()

    private fun request(raw: String) =
        FixMessage(
            timestamp = LocalDateTime.now(),
            direction = FixMessage.Direction.INCOMING,
            rawMessage = raw,
            quickfixMessage = AcceptorResponder.buildMessage(raw, dictionary),
        )

    /** The desk's own record of a quote, as the book would hand it over. */
    private fun quoted(
        issue: FiRfqVenuePreset.Issue = tenYear,
        state: QuoteState = QuoteState.OPEN,
        word: String = QuoteConstraint.OPEN.word,
    ) = QuoteReading(
        quoteId = QUOTE_ID,
        entry =
            QuoteEntry(
                quoteId = QUOTE_ID,
                quoteReqId = "RFQ-1",
                symbol = issue.symbol,
                bid = issue.bidPx,
                offer = issue.offerPx,
                bidSize = "10000000",
                offerSize = "10000000",
                validUntil = null,
                state = state,
            ),
        word = word,
    )

    private fun answers(raw: String, quote: QuoteReading? = null): List<String> {
        val message = AcceptorResponder.buildMessage(raw, dictionary)
        val rule =
            AcceptorResponder.firstMatch(AcceptorResponder.compile(bundle), message, null, quote)
                ?: error("no rule answered: $raw")
        return AcceptorResponder
            .plan(rule, message, request(raw), dictionary, { quote })
            .map { it.render() }
    }

    private fun answer(raw: String, quote: QuoteReading? = null) = answers(raw, quote).first()

    private fun field(raw: String, tag: Int) =
        raw.split("|").firstOrNull { it.startsWith("$tag=") }?.substringAfter("=")

    private fun requestFor(issue: FiRfqVenuePreset.Issue, side: String? = null, size: String? = "10000000") =
        listOfNotNull(
            "35=R",
            "131=RFQ-1",
            "146=1",
            "55=${issue.symbol}",
            "48=${issue.cusip}",
            "22=1",
            side?.let { "54=$it" },
            size?.let { "38=$it" },
        ).joinToString("|")

    // ---------------------------------------------------------------- the shape of the bundle

    @Test
    fun `the desk's cards read one-way before two-way, and the refusals last`() {
        val read = bundle.map { rule -> rule.whenMsgType + describe(rule) }

        // The one-way rules are the two-way trigger plus a `54`, so below them a client that disclosed a
        // side would be shown both — the very thing this venue exists to get right.
        assertTrue(read.take(6).all { it.contains("54=") }, "one-way first: ${read.take(6)}")
        assertTrue(read.slice(6..8).none { it.contains("54=") }, "then two-way: ${read.slice(6..8)}")
        assertEquals("R 55∈run", read[9], "the no-size refusal reads after every quote rule")
        assertEquals("R", read[10], "and the unknown issue is the foot of the block")
        assertEquals(26, bundle.size, "the summary counts the rules, so the count is the summary")
    }

    private fun describe(rule: AcceptorResponseRule): String =
        rule.trigger().joinToString("") { c ->
            when (val m = c.parsed()) {
                is Matcher.Exact -> " ${c.tag}=${m.value}"
                is Matcher.OneOf -> " ${c.tag}∈run"
                is Matcher.QuoteField -> " ${c.tag}=quote.${m.name}"
                Matcher.Presence -> " ${c.tag}"
                else -> " ${c.tag}?"
            }
        } + (rule.whenQuote?.let { " quote:${it.word}" } ?: "")

    @Test
    fun `every rule of the fixed-income desk is reachable`() {
        bundle.indices.forEach { index ->
            assertTrue(
                AcceptorResponder.shadowingRule(bundle, index) == null,
                "rule ${index + 1} (35=${bundle[index].whenMsgType}) can never fire",
            )
        }
    }

    @Test
    fun `every rule of the fixed-income desk is a rule the editor would accept`() {
        bundle.forEach { rule -> assertEquals(null, rule.validationError(), "35=${rule.whenMsgType} is not usable") }
    }

    @Test
    fun `no rule of the fixed-income desk sends an unevaluated expression`() {
        bundle.forEach { rule ->
            answers(sampleFor(rule), quoteFor(rule)).forEach { sent ->
                assertTrue(
                    !sent.contains("\${"),
                    "35=${rule.whenMsgType} sends an expression it never evaluated: $sent",
                )
            }
        }
    }

    /**
     * **Nothing this desk plans is malformed against FIX 4.4.** This venue is the first to use the yield
     * fields, QuoteType, PriceType and the Instrument block, and a tag on a message type that does not
     * define it renders perfectly well and is still a message a client rejects.
     */
    @Test
    fun `every reply the fixed-income desk can plan validates against the bundled dictionary`() {
        val dd = assertNotNull(dictionary.getDataDictionary(), "the bundled FIX 4.4 dictionary did not load")
        bundle.forEach { rule ->
            val raw = sampleFor(rule)
            val quote = quoteFor(rule)
            AcceptorResponder
                .plan(rule, AcceptorResponder.buildMessage(raw, dictionary), request(raw), dictionary, { quote })
                .forEach { planned ->
                    runCatching { dd.validate(planned.build(), true) }
                        .onFailure {
                            error("35=${rule.whenMsgType} plans an invalid reply: ${planned.render()} — $it")
                        }
                }
        }
    }

    // ---------------------------------------------------------------- the issues

    /**
     * **Every level is an exact thirty-second.** `98-16+` is `98.515625`; a price between grid points is
     * not a price a Treasury desk would recognise, and the reason this venue states its levels rather
     * than drawing them is that no native generator can land on the grid.
     */
    @Test
    fun `every price the desk quotes is an exact thirty-second of a point`() {
        FiRfqVenuePreset.ISSUES.forEach { issue ->
            listOf(issue.bidPx, issue.offerPx).forEach { px ->
                val sixtyFourths = BigDecimal(px) * BigDecimal(64)
                assertEquals(
                    0,
                    sixtyFourths.stripTrailingZeros().scale().coerceAtLeast(0),
                    "${issue.symbol} quotes $px, which is not a whole number of 64ths",
                )
            }
            assertTrue(
                BigDecimal(issue.offerPx) > BigDecimal(issue.bidPx),
                "${issue.symbol} quotes an inverted market",
            )
        }
    }

    /** Price and yield move opposite ways, or the desk is quoting two different bonds. */
    @Test
    fun `the bid is the lower price and therefore the higher yield`() {
        FiRfqVenuePreset.ISSUES.forEach { issue ->
            assertTrue(
                BigDecimal(issue.bidYield) > BigDecimal(issue.offerYield),
                "${issue.symbol}: a lower price must carry a higher yield",
            )
        }
    }

    @Test
    fun `every issue is identified the way a bond is`() {
        FiRfqVenuePreset.ISSUES.forEach { issue ->
            assertTrue(issue.cusip.matches(Regex("^[0-9A-Z]{9}$")), "${issue.cusip} is not a CUSIP")
            assertTrue(issue.maturity.matches(Regex("^20[2-9][0-9][01][0-9][0-3][0-9]$")), issue.maturity)
            assertTrue(BigDecimal(issue.coupon) > BigDecimal.ZERO, "${issue.symbol} has no coupon")
        }
    }

    // ---------------------------------------------------------------- what the desk does

    /**
     * **A disclosed side is quoted one way** — the correction this venue exists to make. A client that
     * says it is buying is shown the offer and nothing else; the bid is the desk's own business.
     */
    @Test
    fun `a request that discloses a buy is shown the offer alone`() {
        val quote = answer(requestFor(tenYear, side = "1"))

        assertEquals(tenYear.offerPx, field(quote, 133), "the offer is what a buyer can trade on")
        assertEquals(tenYear.offerYield, field(quote, 634))
        assertEquals(null, field(quote, 132), "the bid is not the buyer's business: $quote")
        assertEquals(null, field(quote, 632))
        assertEquals("1", field(quote, 54), "the quote names the side it is showing")
    }

    @Test
    fun `a request that discloses a sell is shown the bid alone`() {
        val quote = answer(requestFor(tenYear, side = "2"))

        assertEquals(tenYear.bidPx, field(quote, 132))
        assertEquals(tenYear.bidYield, field(quote, 632))
        assertEquals(null, field(quote, 133), "the offer is not the seller's business: $quote")
        assertEquals("2", field(quote, 54))
    }

    /** Two-way is for a client that withheld direction, which is the whole point of withholding it. */
    @Test
    fun `a request that discloses no side is quoted both ways`() {
        val quote = answer(requestFor(tenYear))

        assertEquals(tenYear.bidPx, field(quote, 132))
        assertEquals(tenYear.offerPx, field(quote, 133))
        assertEquals(tenYear.bidYield, field(quote, 632))
        assertEquals(tenYear.offerYield, field(quote, 634))
        assertEquals(null, field(quote, 54), "a two-way quote has no side")
    }

    @Test
    fun `every quote says it is tradeable, priced in percent of par, and when it settles`() {
        val quote = answer(requestFor(tenYear))

        assertEquals("1", field(quote, 537), "537=1 is Tradeable: this desk books at the level it showed")
        assertEquals("1", field(quote, 423), "423=1 is percent of par")
        assertEquals("MATURITY", field(quote, 235), "the yield is a yield to maturity")
        assertEquals("0", field(quote, 63), "regular settlement")
        assertTrue(field(quote, 64)!!.matches(Regex("^20[0-9]{6}$")), "a settlement date, not a code: $quote")
        assertEquals("USD", field(quote, 15))
    }

    @Test
    fun `every quote carries the instrument the way a bond desk names one`() {
        val quote = answer(requestFor(tenYear))

        assertEquals(tenYear.cusip, field(quote, 48))
        assertEquals("1", field(quote, 22), "22=1 is CUSIP")
        assertEquals("TNOTE", field(quote, 167))
        assertEquals(tenYear.coupon, field(quote, 223))
        assertEquals(tenYear.maturity, field(quote, 541))
        assertEquals("US TREASURY", field(quote, 106))
        assertEquals(tenYear.thirtySeconds, field(quote, 58), "the level, the way it is said out loud")
    }

    @Test
    fun `the size the desk shows is a round million of nominal`() {
        repeat(SAMPLES) {
            val quote = answer(requestFor(tenYear))
            listOf(134, 135).forEach { tag ->
                val size = field(quote, tag)!!.toLong()
                assertEquals(0L, size % 1_000_000L, "a Treasury RFQ is sized in millions, not $size")
                assertTrue(size in 5_000_000L..25_000_000L, "showed $size")
            }
        }
    }

    @Test
    fun `a request with no size is refused, and one for an issue off the run is refused differently`() {
        val noSize = answer(requestFor(tenYear, size = null))
        assertTrue(noSize.startsWith("35=AG"), noSize)
        assertEquals("99", field(noSize, 658))
        assertTrue(field(noSize, 58)!!.contains("nominal"), noSize)

        val offRun = answer("35=R|131=RFQ-2|146=1|55=T 9.875 11/15/45|38=10000000")
        assertEquals("1", field(offRun, 658), "658=1 is Unknown symbol")
    }

    // ---------------------------------------------------------------- the negotiation

    @Test
    fun `a lift at the quoted offer books, at the desk's own level and with its settlement terms`() {
        val lift =
            "35=AJ|693=RESP-1|694=1|117=$QUOTE_ID|11=TRD-1|55=${tenYear.symbol}|54=1|38=10000000|" +
                "44=${tenYear.offerPx}"
        val trade = answer(lift, quoted())

        assertTrue(trade.startsWith("35=8"), trade)
        assertEquals("F", field(trade, 150))
        assertEquals(tenYear.offerPx, field(trade, 31), "booked at the level the desk quoted")
        assertEquals(tenYear.offerPx, field(trade, 6))
        assertEquals("RESP-1", field(trade, 693), "the trade names the response that caused it")
        assertEquals("1", field(trade, 423))
        assertEquals("0", field(trade, 63))
        assertTrue(field(trade, 64)!!.matches(Regex("^20[0-9]{6}$")), "a bond trade needs a settlement date")
    }

    /**
     * **Cover and Done Away are the ordinary end of a negotiation you did not win.** The FX desk answers
     * them "QuoteRespType not accepted", which tells a client its conformant message is malformed.
     */
    @Test
    fun `cover and done away are answered, and the level is withdrawn`() {
        listOf("4" to "cover", "5" to "done away").forEach { (respType, word) ->
            val reply = answer("35=AJ|693=RESP-2|694=$respType|117=$QUOTE_ID|55=${tenYear.symbol}", quoted())

            assertTrue(reply.startsWith("35=AI"), reply)
            assertEquals("6", field(reply, 297), "297=6 is Removed from market, which the level now is")
            assertTrue(field(reply, 58)!!.contains(word), "the answer should name what happened: $reply")
        }
    }

    @Test
    fun `a pass is answered as a pass, and a counter is declined with the level left standing`() {
        val pass = answer("35=AJ|693=RESP-3|694=6|117=$QUOTE_ID|55=${tenYear.symbol}", quoted())
        assertEquals("11", field(pass, 297), "297=11 is Pass")

        val counter = answer("35=AJ|693=RESP-4|694=2|117=$QUOTE_ID|55=${tenYear.symbol}", quoted())
        assertEquals("5", field(counter, 297))
        assertTrue(field(counter, 58)!!.contains("stands"), counter)
    }

    @Test
    fun `the quote book still refuses a stale, a spent and an unknown level by name`() {
        val hit =
            "35=AJ|693=RESP-5|694=1|117=$QUOTE_ID|11=TRD-2|55=${tenYear.symbol}|54=1|38=10000000|" +
                "44=${tenYear.offerPx}"

        assertEquals(
            "7",
            field(answer(hit, quoted(word = QuoteConstraint.EXPIRED.word)), 297),
            "an expired level is stale, not merely wrong",
        )
        assertEquals(
            "5",
            field(answer(hit, quoted(state = QuoteState.DONE, word = QuoteConstraint.DONE.word)), 297),
        )
        assertEquals(
            "9",
            field(answer(hit, QuoteReading(QUOTE_ID, null, QuoteConstraint.UNKNOWN.word)), 297),
        )
    }

    @Test
    fun `a lift at a level the desk did not quote is refused rather than booked`() {
        val wrong =
            "35=AJ|693=RESP-6|694=1|117=$QUOTE_ID|11=TRD-3|55=${tenYear.symbol}|54=1|38=10000000|44=98.000000"
        val reply = answer(wrong, quoted())

        assertTrue(reply.startsWith("35=AI"), reply)
        assertEquals("5", field(reply, 297))
    }

    // ---------------------------------------------------------------- helpers

    private fun quoteFor(rule: AcceptorResponseRule): QuoteReading? =
        when (rule.whenQuote) {
            null -> null
            QuoteConstraint.UNKNOWN -> QuoteReading(QUOTE_ID, null, QuoteConstraint.UNKNOWN.word)
            else -> quoted(state = QuoteState.OPEN, word = rule.whenQuote!!.word)
        }

    private fun sampleFor(rule: AcceptorResponseRule): String {
        fun exact(tag: Int) = (rule.trigger().firstOrNull { it.tag == tag }?.parsed() as? Matcher.Exact)?.value
        fun fromQuote(tag: Int) =
            (rule.trigger().firstOrNull { it.tag == tag }?.parsed() as? Matcher.QuoteField)
                ?.let { quoteFor(rule)?.field(it.name) }

        val symbol = exact(55) ?: fromQuote(55) ?: tenYear.symbol
        val issue = FiRfqVenuePreset.ISSUES.firstOrNull { it.symbol == symbol } ?: tenYear
        return when (rule.whenMsgType) {
            "R" -> requestFor(issue, side = exact(54))
            "AJ" -> {
                val side = exact(54) ?: "1"
                val price = fromQuote(44) ?: if (side == "1") issue.offerPx else issue.bidPx
                "35=AJ|693=RESP-1|694=${exact(694) ?: "1"}|117=$QUOTE_ID|11=TRD-1|55=$symbol|54=$side|" +
                    "38=10000000|44=$price"
            }
            else -> error("no sample message for 35=${rule.whenMsgType}")
        }
    }

    private companion object {
        const val QUOTE_ID = "3f2504e0-4f89-41d3-9a0c-0305e82c3301"

        /** Enough draws that a size off the million grid would show up. */
        const val SAMPLES = 15
    }
}
