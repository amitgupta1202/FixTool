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
import java.io.File
import java.math.BigDecimal
import java.net.URLClassLoader
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The RFQ venue is content, and content goes stale silently.** Same stance as `FxVenuePresetTest`:
 * every claim is asked of the engine that runs the rules, `explain` for which rule fires and `plan` for
 * what it sends, because that is the only judgement the wire agrees with.
 *
 * Two things are asked here that no other preset test asks. The dictionary check, because this venue's
 * shapes were decided by what FIX 4.4 defines on a QuoteResponse, a QuoteStatusReport and an
 * ExecutionReport, and a template that put `131` on an ExecutionReport would render perfectly well and
 * still be malformed. And **the quote book**, because since the second slice this venue's answers
 * depend on what it remembers quoting: every 35=AJ claim below is asked against a stated quote state,
 * which is exactly the argument the live path takes.
 */
class RfqVenuePresetTest {
    private val dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)

    private val bundle = AcceptorPresets.insert(emptyList(), AcceptorPresets.byId(RfqVenuePreset.ID)!!).rules

    private fun request(raw: String) =
        FixMessage(
            timestamp = LocalDateTime.now(),
            direction = FixMessage.Direction.INCOMING,
            rawMessage = raw,
            quickfixMessage = AcceptorResponder.buildMessage(raw, dictionary),
        )

    // ---------------------------------------------------------------- the quote, as the venue holds it

    /** The canonical live quote: EUR/USD at the bottom of both bands, which is what the samples hit. */
    private fun quoted(
        symbol: String = "EUR/USD",
        bid: String = "1.08990",
        offer: String = "1.09010",
        word: String = QuoteConstraint.OPEN.word,
    ) = QuoteReading(
        quoteId = QUOTE_ID,
        entry =
            QuoteEntry(
                quoteId = QUOTE_ID,
                quoteReqId = "RFQ-1",
                symbol = symbol,
                bid = bid,
                offer = offer,
                bidSize = "1000000",
                offerSize = "1000000",
                validUntil = null,
                state = if (word == QuoteConstraint.DONE.word) QuoteState.DONE else QuoteState.OPEN,
            ),
        word = word,
    )

    private val never = QuoteReading.unknown(QUOTE_ID)

    /** Everything [rule] would send for [raw], resolved exactly as the dispatcher would resolve it. */
    private fun sends(rule: AcceptorResponseRule, raw: String, quote: QuoteReading? = null): List<String> =
        AcceptorResponder
            .plan(rule, AcceptorResponder.buildMessage(raw, dictionary), request(raw), dictionary, quote = { quote })
            .map { it.render() }

    /** The rule this whole bundle picks for [raw], the question first-match-wins actually answers. */
    private fun winnerFor(raw: String, quote: QuoteReading? = null): AcceptorResponseRule {
        val outcome =
            AcceptorResponder
                .explain(bundle, AcceptorResponder.buildMessage(raw, dictionary), quote = quote)
                .firstOrNull { it.selected }
        assertNotNull(outcome, "the RFQ venue answered nothing at all for $raw")
        return outcome.rule
    }

    /** What the venue sends for [raw], through the whole bundle, with the book in the state given. */
    private fun answer(raw: String, quote: QuoteReading? = null): String =
        sends(winnerFor(raw, quote), raw, quote).single()

    private fun field(message: String, tag: Int): String? =
        message.split('|').firstOrNull { it.startsWith("$tag=") }?.substringAfter('=')

    /** Two-way requests: no side, so the venue shows both. */
    private val requestEur = "35=R|131=RFQ-1|146=1|55=EUR/USD|38=1000000"
    private val requestJpy = "35=R|131=RFQ-2|146=1|55=USD/JPY|38=1000000"
    private val requestEurBuying = "35=R|131=RFQ-6|146=1|55=EUR/USD|54=1|38=1000000"
    private val requestEurSelling = "35=R|131=RFQ-7|146=1|55=EUR/USD|54=2|38=1000000"
    private val requestNoSize = "35=R|131=RFQ-3|146=1|55=EUR/USD|54=1"
    private val requestUnknown = "35=R|131=RFQ-4|146=1|55=XXX/YYY|54=1|38=1000000"

    private fun hit(symbol: String, side: String, price: String, respType: String = "1") =
        "35=AJ|693=RESP-1|694=$respType|117=$QUOTE_ID|11=TRD-1|55=$symbol|54=$side|38=1000000|44=$price"

    // ---------------------------------------------------------------- the shape of the bundle

    /** **The card order, asserted whole**, because [AcceptorPresets.insert] reverses each block on the way in. */
    @Test
    fun `the RFQ venue's cards read in the documented order`() {
        val read = bundle.map { rule -> rule.whenMsgType + describe(rule) }

        assertEquals(
            listOf(
                "R 55=EUR/USD 38 54=1",
                "R 55=EUR/USD 38 54=2",
                "R 55=GBP/USD 38 54=1",
                "R 55=GBP/USD 38 54=2",
                "R 55=USD/JPY 38 54=1",
                "R 55=USD/JPY 38 54=2",
                "R 55=EUR/USD 38",
                "R 55=GBP/USD 38",
                "R 55=USD/JPY 38",
                "R 55∈pairs",
                "R",
                "AJ 117 55 quote:unknown",
                "AJ 117 quote:expired",
                "AJ 117 quote:done",
                "AJ 117 11 38 694=1 55=quote.symbol 54=1 44=quote.offer quote:open",
                "AJ 117 11 38 694=1 55=quote.symbol 54=2 44=quote.bid quote:open",
                "AJ 117 11 38 694=1 55=quote.symbol quote:open",
                "AJ 117 11 38 694=1 quote:open",
                "AJ 117 694=1 quote:open",
                "AJ 117 694=2 quote:open",
                "AJ 117 694=4 quote:open",
                "AJ 117 694=5 quote:open",
                "AJ 117 694=6 quote:open",
                "AJ 117 quote:open",
                "AJ",
            ),
            read,
        )
        assertEquals(25, bundle.size, "the summary counts the rules, so the count is the summary")
        assertTrue(bundle.none { it.whenOrder != null }, "this venue reads the quote book, not the order book")
    }

    private fun describe(rule: AcceptorResponseRule): String =
        rule.trigger().joinToString("") { c ->
            when (val m = c.parsed()) {
                is Matcher.Exact -> " ${c.tag}=${m.value}"
                is Matcher.OneOf -> " ${c.tag}∈pairs"
                is Matcher.QuoteField -> " ${c.tag}=quote.${m.name}"
                Matcher.Presence -> " ${c.tag}"
                else -> " ${c.tag}?"
            }
        } + (rule.whenQuote?.let { " quote:${it.word}" } ?: "")

    @Test
    fun `every rule of the RFQ venue is reachable`() {
        bundle.indices.forEach { index ->
            assertTrue(
                AcceptorResponder.shadowingRule(bundle, index) == null,
                "rule ${index + 1} (35=${bundle[index].whenMsgType}) can never fire",
            )
        }
    }

    @Test
    fun `every rule of the RFQ venue is a rule the editor would accept`() {
        bundle.forEach { rule ->
            assertEquals(null, rule.validationError(), "35=${rule.whenMsgType} is not a usable rule")
        }
    }

    /**
     * A template expression that does not compile is returned as its own source text, so a broken
     * field ships as `62=${…}`: a field with a value, and a well-formed message carrying nonsense.
     */
    @Test
    fun `no rule of the RFQ venue sends an unevaluated expression`() {
        bundle.forEach { rule ->
            sends(rule, sampleFor(rule), quoteFor(rule)).forEach { sent ->
                assertTrue(
                    !sent.contains("\${"),
                    "35=${rule.whenMsgType} would send an expression it never evaluated: $sent",
                )
            }
        }
    }

    /**
     * **Nothing this venue plans is malformed against FIX 4.4.** The shapes were chosen from the
     * dictionary, and this is what holds them to it: every reply, built the way the acceptor builds it
     * and validated the way a client's engine validates it. A tag the message type does not define, a
     * required tag missing, an enum value the dictionary does not list, all fail here.
     */
    @Test
    fun `every reply the RFQ venue can plan validates against the bundled dictionary`() {
        val dd = assertNotNull(dictionary.getDataDictionary(), "the bundled FIX 4.4 dictionary did not load")
        bundle.forEach { rule ->
            val raw = sampleFor(rule)
            val quote = quoteFor(rule)
            AcceptorResponder
                .plan(rule, AcceptorResponder.buildMessage(raw, dictionary), request(raw), dictionary, { quote })
                .forEach { planned ->
                    val built = planned.build()
                    runCatching { dd.validate(built, true) }
                        .onFailure {
                            throw AssertionError(
                                "35=${rule.whenMsgType} plans a malformed reply: ${it.message} in ${planned.render()}",
                                it,
                            )
                        }
                }
        }
    }

    // ---------------------------------------------------------------- the quote

    /**
     * The one thing every real RFQ venue has in common, and the thing the first slice of this preset
     * got wrong: a QuoteID a client can only answer by having read it.
     */
    @Test
    fun `the QuoteID is opaque, and a different one on every quote`() {
        val ids = (1..20).map { field(answer(requestEur), 117) }

        assertTrue(ids.all { it != null && it.isNotBlank() }, "a quote with no QuoteID cannot be answered")
        assertTrue(
            ids.none { it!!.contains("RFQ-1") },
            "the QuoteID must not be derivable from the request: $ids",
        )
        assertEquals(ids.size, ids.distinct().size, "two quotes shared an id: $ids")
    }

    @Test
    fun `the quote echoes the QuoteReqID, so the client can tie it to what it asked`() {
        assertEquals("RFQ-1", field(answer(requestEur), 131))
    }

    /**
     * **The bands cannot overlap.** An inverted quote is not a quote, and a venue that emitted one
     * would be teaching a client to accept one. Asserted of the table rather than of the draws, so it
     * holds for every draw there could ever be.
     */
    @Test
    fun `no draw this venue could make puts a bid at or above an offer`() {
        RfqVenuePreset.QUOTED.forEach { quoted ->
            val bidHigh = BigDecimal(quoted.bid.high)
            val offerLow = BigDecimal(quoted.offer.low)
            assertTrue(
                bidHigh < offerLow,
                "${quoted.symbol}: the top of the bid band ($bidHigh) is not below the bottom of the offer band ($offerLow)",
            )
            assertTrue(BigDecimal(quoted.bid.low) < bidHigh, "${quoted.symbol}: the bid band has no width")
            assertTrue(offerLow < BigDecimal(quoted.offer.high), "${quoted.symbol}: the offer band has no width")
        }
    }

    @Test
    fun `every price the venue quotes lands in its pair's band, at its pair's decimals`() {
        listOf(requestEur to "EUR/USD", requestJpy to "USD/JPY").forEach { (raw, symbol) ->
            val quoted = RfqVenuePreset.QUOTED.first { it.symbol == symbol }
            repeat(20) {
                val quote = answer(raw)
                val bid = assertNotNull(field(quote, 132), quote)
                val offer = assertNotNull(field(quote, 133), quote)

                assertEquals(quoted.pair.decimals, bid.substringAfter('.').length, "bid decimals: $quote")
                assertEquals(quoted.pair.decimals, offer.substringAfter('.').length, "offer decimals: $quote")
                assertTrue(BigDecimal(bid) >= BigDecimal(quoted.bid.low), "bid below its band: $quote")
                assertTrue(BigDecimal(bid) <= BigDecimal(quoted.bid.high), "bid above its band: $quote")
                assertTrue(BigDecimal(offer) >= BigDecimal(quoted.offer.low), "offer below its band: $quote")
                assertTrue(BigDecimal(offer) <= BigDecimal(quoted.offer.high), "offer above its band: $quote")
                assertTrue(BigDecimal(bid) < BigDecimal(offer), "an inverted quote reached the wire: $quote")
            }
        }
        assertEquals("JPY", field(answer(requestJpy), 15))
    }

    /** A price that is identical on four thousand quotes is a venue nobody would recognise. */
    @Test
    fun `the price moves between quotes`() {
        val offers = (1..40).map { field(answer(requestEur), 133) }.distinct()

        assertTrue(offers.size > 3, "the venue quoted the same price every time: $offers")
    }

    @Test
    fun `the quote's sizes are the size the request asked for`() {
        val quote = answer(requestEur)
        assertEquals("1000000", field(quote, 134))
        assertEquals("1000000", field(quote, 135))
    }

    /** `62` is a live shorthand, and a shorthand that silently stopped expanding would ship as text. */
    @Test
    fun `the quote is valid for the thirty seconds the venue says it is`() {
        val quote = answer(requestEur)
        val validUntil = assertNotNull(field(quote, 62), "no ValidUntilTime in $quote")
        val at = LocalDateTime.parse(validUntil, DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS"))
        val ahead = Duration.between(LocalDateTime.now(ZoneOffset.UTC), at).seconds
        assertTrue(ahead in 25..35, "expected ValidUntilTime about thirty seconds out, it is ${ahead}s: $quote")
    }

    @Test
    fun `a request without a size is refused with a sentence, not quoted at a size nobody asked for`() {
        val reject = answer(requestNoSize)
        assertTrue(reject.startsWith("35=AG"), "got: $reject")
        assertEquals("99", field(reject, 658))
        assertEquals("RFQ-3", field(reject, 131))
        assertTrue(field(reject, 58)!!.contains("OrderQty"), "the reason names the missing tag: $reject")
    }

    @Test
    fun `a request for an unpriced symbol falls to the FX venue's own refusal`() {
        val reject = answer(requestUnknown)
        assertTrue(reject.startsWith("35=AG"), "got: $reject")
        assertEquals("1", field(reject, 658), "658=1 is Unknown symbol")
        assertEquals("XXX/YYY", field(reject, 55))
    }

    // ---------------------------------------------------------------- what the book decides

    /**
     * **The four answers one message can get**, and nothing on the message distinguishes them. This is
     * the whole claim of the slice, and the reason the venue needed a memory.
     */
    @Test
    fun `the same hit is booked, refused as stale, refused as spent, or disowned, by what the venue quoted`() {
        val raw = hit("EUR/USD", side = "1", price = "1.09010")

        assertEquals("8", field(answer(raw, quoted()), 35), "a live quote owes a trade")
        assertEquals("7", field(answer(raw, quoted(word = QuoteConstraint.EXPIRED.word)), 297), "297=7 is Expired")
        assertEquals("5", field(answer(raw, quoted(word = QuoteConstraint.DONE.word)), 297))
        assertEquals("9", field(answer(raw, never), 297), "297=9 is Quote not found")
    }

    @Test
    fun `a hit that arrived too late says so, and names the quote`() {
        val status = answer(hit("EUR/USD", "1", "1.09010"), quoted(word = QuoteConstraint.EXPIRED.word))

        assertTrue(status.startsWith("35=AI"), "got: $status")
        assertEquals(QUOTE_ID, field(status, 117))
        assertEquals("EUR/USD", field(status, 55), "the symbol comes from the venue's own record")
        assertTrue(field(status, 58)!!.contains("expired"), status)
    }

    @Test
    fun `a second hit on a quote already answered is refused, not booked twice`() {
        val status = answer(hit("EUR/USD", "1", "1.09010"), quoted(word = QuoteConstraint.DONE.word))

        assertEquals("5", field(status, 297), status)
        assertEquals("Quote already answered", field(status, 58), status)
    }

    /**
     * The refusal that could not be written at all before the book: a client hitting an id this venue
     * never minted used to be answered as though the quote were live.
     */
    @Test
    fun `a hit on a quote this venue never sent is disowned, in the client's own words`() {
        val status = answer(hit("EUR/USD", "1", "1.09010"), never)

        assertTrue(status.startsWith("35=AI"), "got: $status")
        assertEquals("9", field(status, 297))
        assertEquals("EUR/USD", field(status, 55), "with no quote to read, only the client can name the instrument")
        assertTrue(field(status, 58)!!.contains("did not send"), status)
    }

    @Test
    fun `a response naming a quote this venue never sent, with no symbol either, cannot be answered`() {
        val reject = answer("35=AJ|693=RESP-9|694=1|117=$QUOTE_ID|11=TRD-9|54=1|38=1000000|44=1.09010", never)

        assertTrue(reject.startsWith("35=j"), "got: $reject")
        assertEquals("5", field(reject, 380), "380=5 is Conditionally required field missing")
        assertTrue(field(reject, 58)!!.contains("Symbol (55)"), reject)
    }

    // ---------------------------------------------------------------- the booking

    @Test
    fun `a buy at the quoted offer books a trade at the venue's own price`() {
        val quote = quoted(offer = "1.09017")
        val trade = answer(hit("EUR/USD", side = "1", price = "1.09017"), quote)

        assertTrue(trade.startsWith("35=8"), "got: $trade")
        assertEquals("F", field(trade, 150))
        assertEquals("2", field(trade, 39))
        assertEquals("1.09017", field(trade, 31), "the trade is at the price the quote carried")
        assertEquals(field(trade, 31), field(trade, 6), "one fill has one price")
        assertEquals("1000000", field(trade, 32))
        assertEquals("1000000", field(trade, 14))
        assertEquals("0", field(trade, 151))
        assertEquals("TRD-1", field(trade, 11), "the report names the trade the client named")
        assertEquals("RESP-1", field(trade, 693), "the report names the response that caused it")
        assertNotNull(field(trade, 37))
        assertNotNull(field(trade, 17))
    }

    @Test
    fun `a sell hits the bid, at the bid the quote carried`() {
        val quote = quoted(symbol = "USD/JPY", bid = "149.493", offer = "149.513")
        val trade = answer(hit("USD/JPY", side = "2", price = "149.493"), quote)

        assertEquals("F", field(trade, 150))
        assertEquals("149.493", field(trade, 31))
    }

    @Test
    fun `a hit at any other price is refused, and the reason says so`() {
        val status = answer(hit("EUR/USD", side = "1", price = "1.09000"), quoted())

        assertTrue(status.startsWith("35=AI"), "got: $status")
        assertEquals("5", field(status, 297), "297=5 is Rejected")
        assertEquals(QUOTE_ID, field(status, 117))
        assertTrue(field(status, 58)!!.contains("quoted price"), status)
    }

    /** A buy at the bid is a hit at the wrong price for that side, not a booking at a better one. */
    @Test
    fun `a buy at the bid is refused as the wrong price`() {
        assertEquals("5", field(answer(hit("EUR/USD", side = "1", price = "1.08990"), quoted()), 297))
    }

    /**
     * The instrument the client named has to be the instrument the venue quoted, **and the refusal has to
     * say that is what was wrong**.
     *
     * A hit at the quoted price on another pair used to fall to the wrong-price rule and come back "Price
     * is not the quoted price", which is the one thing that was right about it. A client reading that
     * checks its pricing and finds nothing.
     */
    @Test
    fun `a hit naming a different instrument than the quote is not booked`() {
        val status = answer(hit("GBP/USD", side = "1", price = "1.09010"), quoted(symbol = "EUR/USD"))

        assertTrue(status.startsWith("35=AI"), "got: $status")
        assertEquals("5", field(status, 297))
        assertEquals("Instrument is not the quoted one", field(status, 58), status)
    }

    /** And the price refusal is now only ever about the price, because the instrument had to match first. */
    @Test
    fun `the wrong-price refusal is only reached on the quoted instrument`() {
        val status = answer(hit("EUR/USD", side = "1", price = "1.09000"), quoted(symbol = "EUR/USD"))

        assertEquals("Not the quoted price, or not a side this quote showed", field(status, 58), status)
    }

    @Test
    fun `a hit without a ClOrdID or a quantity is refused, naming the tags`() {
        val status = answer("35=AJ|693=RESP-1|694=1|117=$QUOTE_ID|55=EUR/USD|54=1|44=1.09010", quoted())

        assertTrue(status.startsWith("35=AI"), "got: $status")
        assertEquals("5", field(status, 297))
        val reason = field(status, 58)!!
        assertTrue(reason.contains("ClOrdID (11)") && reason.contains("OrderQty (38)"), status)
    }

    // ---------------------------------------------------------------- the other responses

    @Test
    fun `a pass is acknowledged as a pass`() {
        val status = answer(hit("EUR/USD", "1", "1.09010", respType = "6"), quoted())

        assertTrue(status.startsWith("35=AI"), "got: $status")
        assertEquals("11", field(status, 297), "297=11 is Pass")
    }

    @Test
    fun `a counter is refused, because this venue quotes firm`() {
        val status = answer(hit("EUR/USD", "1", "1.09000", respType = "2"), quoted())

        assertEquals("5", field(status, 297), status)
        assertTrue(field(status, 58)!!.contains("firm"), status)
    }

    @Test
    fun `any other response type is answered, never left in silence`() {
        val status = answer(hit("EUR/USD", "1", "1.09010", respType = "3"), quoted())
        assertEquals("5", field(status, 297), "694=3 got $status")
        assertTrue(field(status, 58)!!.contains("QuoteRespType"), status)
    }

    /** Cover and done away are the ordinary end of a negotiation the venue did not win, and the quote is withdrawn. */
    @Test
    fun `cover and done away are answered, and the quote is withdrawn`() {
        listOf("4" to "cover", "5" to "done away").forEach { (respType, word) ->
            val status = answer(hit("EUR/USD", "1", "1.09010", respType = respType), quoted())
            assertEquals("6", field(status, 297), "694=$respType got $status")
            assertTrue(field(status, 58)!!.contains(word), status)
        }
    }

    // ---------------------------------------------------------------- a disclosed side

    /** Two-way is for a client who withheld direction. A buyer is shown the offer; the bid is the venue's own business. */
    @Test
    fun `a request that discloses a side is quoted that side only, and the quote says so`() {
        val toBuyer = answer(requestEurBuying)
        assertEquals("1", field(toBuyer, 54), toBuyer)
        assertNotNull(field(toBuyer, 133), toBuyer)
        assertEquals(null, field(toBuyer, 132), "a buyer is not shown the bid: $toBuyer")
        assertEquals("1000000", field(toBuyer, 135))
        assertEquals(null, field(toBuyer, 134), toBuyer)

        val toSeller = answer(requestEurSelling)
        assertEquals("2", field(toSeller, 54), toSeller)
        assertNotNull(field(toSeller, 132), toSeller)
        assertEquals(null, field(toSeller, 133), "a seller is not shown the offer: $toSeller")
    }

    @Test
    fun `every quote says it is tradeable`() {
        listOf(requestEur, requestEurBuying, requestEurSelling).forEach { raw ->
            assertEquals("1", field(answer(raw), 537), "QuoteType(537)=1 is Tradeable, on $raw")
        }
    }

    /** A QuoteStatusReport requires a QuoteID, so a response without one cannot be answered with one. */
    @Test
    fun `a response without a QuoteID gets a BusinessMessageReject naming the missing field`() {
        val reject = answer("35=AJ|693=RESP-9|694=1|11=TRD-9|55=EUR/USD|54=1|38=1000000|44=1.09010", quoted())

        assertTrue(reject.startsWith("35=j"), "got: $reject")
        assertEquals("AJ", field(reject, 372))
        assertEquals("RESP-9", field(reject, 379))
        assertEquals("5", field(reject, 380), "380=5 is Conditionally required field missing")
        assertTrue(field(reject, 58)!!.contains("QuoteID (117)"), reject)
    }

    // ---------------------------------------------------------------- initialisation order

    /**
     * Three objects now, one cycle: this preset reads [FxVenuePreset] and [AcceptorPresets], and the
     * catalogue holds both bundles. Proved from every entry point with a fresh class loader each time.
     */
    @Test
    fun `the catalogue is well formed whichever of the three classes is touched first`() {
        listOf(
            "com.knapsack.fixtool.service.RfqVenuePreset",
            "com.knapsack.fixtool.service.FxVenuePreset",
            "com.knapsack.fixtool.service.AcceptorPresets",
        ).forEach { touchedFirst ->
            freshLoader().use { loader ->
                Class.forName(touchedFirst, true, loader)
                val presets = Class.forName("com.knapsack.fixtool.service.AcceptorPresets", true, loader)
                val instance = presets.getField("INSTANCE").get(null)

                @Suppress("UNCHECKED_CAST")
                val all = presets.getMethod("getAll").invoke(instance) as List<Any?>

                assertTrue(all.none { it == null }, "touching $touchedFirst first left a null in the catalogue")
                val ids = all.map { it!!.javaClass.getMethod("getId").invoke(it) as String }
                assertTrue(RfqVenuePreset.ID in ids, "the RFQ bundle is missing when $touchedFirst is touched first")
                assertTrue(FxVenuePreset.ID in ids, "the FX bundle is missing when $touchedFirst is touched first")
                assertEquals(ids.size, ids.distinct().size, "duplicate preset id")
            }
        }
    }

    private fun freshLoader(): URLClassLoader =
        URLClassLoader(
            System
                .getProperty("java.class.path")
                .split(File.pathSeparator)
                .map { File(it).toURI().toURL() }
                .toTypedArray(),
            ClassLoader.getPlatformClassLoader(),
        )

    // ---------------------------------------------------------------- helpers

    /** The message each rule is designed against, derived from the rule's own conditions. */
    private fun sampleFor(rule: AcceptorResponseRule): String {
        fun exact(tag: Int) = (rule.trigger().firstOrNull { it.tag == tag }?.parsed() as? Matcher.Exact)?.value

        // A `quoteField` condition is satisfied by whatever the quote carries, so the sample reads its
        // value out of the very quote the rule is going to be judged against. Written this way rather
        // than hardcoded because that is the only version of the sample that stays true if the bands
        // move: the sample and the quote cannot drift apart if one is derived from the other.
        fun fromQuote(tag: Int) =
            (rule.trigger().firstOrNull { it.tag == tag }?.parsed() as? Matcher.QuoteField)
                ?.let { quoteFor(rule)?.field(it.name) }

        val symbol = exact(55) ?: fromQuote(55) ?: "EUR/USD"
        return when (rule.whenMsgType) {
            "R" -> "35=R|131=RFQ-1|146=1|55=$symbol" + (exact(54)?.let { "|54=$it" } ?: "") + "|38=1000000"
            "AJ" ->
                hit(
                    symbol,
                    side = exact(54) ?: "1",
                    price = exact(44) ?: fromQuote(44) ?: "1.09010",
                    respType = exact(694) ?: "1",
                )
            else -> error("no sample message for 35=${rule.whenMsgType}")
        }
    }

    /** The book state each rule was written for: its own constraint, or no book for a rule that asks none. */
    private fun quoteFor(rule: AcceptorResponseRule): QuoteReading? =
        when (rule.whenQuote) {
            null -> null
            QuoteConstraint.UNKNOWN -> never
            else -> quoted(word = rule.whenQuote!!.word)
        }

    private companion object {
        /** Opaque, as the venue's own ids are. Nothing about it may be derivable from a request. */
        const val QUOTE_ID = "8f14e45f-ea1a-4d9f-b3c1-6bd0f0e2a7c3"
    }
}
