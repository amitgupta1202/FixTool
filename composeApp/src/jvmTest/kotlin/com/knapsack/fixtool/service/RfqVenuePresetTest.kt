package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.scenario.Matcher
import org.junit.Test
import java.io.File
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
 * What is new here is the dictionary check. This venue's shapes were decided by what FIX 4.4 defines on
 * a QuoteResponse, a QuoteStatusReport and an ExecutionReport, and a template that put `131` on an
 * ExecutionReport would render perfectly well and still be a malformed message. So every reply the bundle
 * can plan is validated against the bundled dictionary, body only, as a client's engine would validate it.
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

    /** Everything [rule] would send for [raw], resolved exactly as the dispatcher would resolve it. */
    private fun sends(rule: AcceptorResponseRule, raw: String): List<String> =
        AcceptorResponder
            .plan(rule, AcceptorResponder.buildMessage(raw, dictionary), request(raw), dictionary)
            .map { it.render() }

    /** The rule this whole bundle picks for [raw], the question first-match-wins actually answers. */
    private fun winnerFor(raw: String): AcceptorResponseRule {
        val outcome =
            AcceptorResponder
                .explain(bundle, AcceptorResponder.buildMessage(raw, dictionary))
                .firstOrNull { it.selected }
        assertNotNull(outcome, "the RFQ venue answered nothing at all for $raw")
        return outcome.rule
    }

    /** What the venue sends for [raw], through the whole bundle. */
    private fun answer(raw: String): String = sends(winnerFor(raw), raw).single()

    private fun field(message: String, tag: Int): String? =
        message.split('|').firstOrNull { it.startsWith("$tag=") }?.substringAfter('=')

    private val requestEur = "35=R|131=RFQ-1|146=1|55=EUR/USD|54=1|38=1000000"
    private val requestJpy = "35=R|131=RFQ-2|146=1|55=USD/JPY|54=1|38=1000000"
    private val requestNoSize = "35=R|131=RFQ-3|146=1|55=EUR/USD|54=1"
    private val requestUnknown = "35=R|131=RFQ-4|146=1|55=XXX/YYY|54=1|38=1000000"

    private fun hit(symbol: String, side: String, price: String, respType: String = "1") =
        "35=AJ|693=RESP-1|694=$respType|117=Q-RFQ-1|11=TRD-1|55=$symbol|54=$side|38=1000000|44=$price"

    // ---------------------------------------------------------------- the shape of the bundle

    /** **The card order, asserted whole**, because [AcceptorPresets.insert] reverses each block on the way in. */
    @Test
    fun `the RFQ venue's cards read in the documented order`() {
        val read = bundle.map { rule -> rule.whenMsgType + describe(rule) }

        assertEquals(
            listOf(
                "R 55=EUR/USD 38",
                "R 55=GBP/USD 38",
                "R 55=USD/JPY 38",
                "R 55∈pairs",
                "R",
                "AJ 117 11 38 694=1 55=EUR/USD 54=1 44=1.09010",
                "AJ 117 11 38 694=1 55=EUR/USD 54=2 44=1.08990",
                "AJ 117 11 38 694=1 55=GBP/USD 54=1 44=1.27015",
                "AJ 117 11 38 694=1 55=GBP/USD 54=2 44=1.26985",
                "AJ 117 11 38 694=1 55=USD/JPY 54=1 44=149.510",
                "AJ 117 11 38 694=1 55=USD/JPY 54=2 44=149.490",
                "AJ 117 11 38 694=1 55∈pairs",
                "AJ 117 694=1 55∈pairs",
                "AJ 117 694=2 55∈pairs",
                "AJ 117 694=6 55∈pairs",
                "AJ 117 55∈pairs",
                "AJ 117",
                "AJ",
            ),
            read,
        )
        assertEquals(18, bundle.size, "the summary says 18 rules")
        assertTrue(bundle.none { it.whenOrder != null }, "the first slice asks the book nothing")
    }

    private fun describe(rule: AcceptorResponseRule): String =
        rule.trigger().joinToString("") { c ->
            when (val m = c.parsed()) {
                is Matcher.Exact -> " ${c.tag}=${m.value}"
                is Matcher.OneOf -> " ${c.tag}∈pairs"
                Matcher.Presence -> " ${c.tag}"
                else -> " ${c.tag}?"
            }
        }

    @Test
    fun `every rule of the RFQ venue is reachable`() {
        bundle.indices.forEach { index ->
            assertTrue(
                AcceptorResponder.shadowingRule(bundle, index) == null,
                "rule ${index + 1} (35=${bundle[index].whenMsgType}) can never fire",
            )
        }
    }

    /**
     * A template expression that does not compile is returned as its own source text, so a broken
     * field ships as `62=${…}`: a field with a value, and a well-formed message carrying nonsense.
     */
    @Test
    fun `no rule of the RFQ venue sends an unevaluated expression`() {
        bundle.forEach { rule ->
            sends(rule, sampleFor(rule)).forEach { sent ->
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
            AcceptorResponder
                .plan(rule, AcceptorResponder.buildMessage(raw, dictionary), request(raw), dictionary)
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

    @Test
    fun `the QuoteID follows from the QuoteReqID, so a client can address a quote it never saw`() {
        val quote = answer(requestEur)

        assertTrue(quote.startsWith("35=S"), "got: $quote")
        assertEquals("RFQ-1", field(quote, 131))
        assertEquals("Q-RFQ-1", field(quote, 117))
    }

    @Test
    fun `every price the venue quotes has its pair's decimals, and is the pair's firm price`() {
        val eur = answer(requestEur)
        assertEquals("1.08990", field(eur, 132))
        assertEquals("1.09010", field(eur, 133))
        assertEquals("USD", field(eur, 15))

        val jpy = answer(requestJpy)
        assertEquals("149.490", field(jpy, 132))
        assertEquals("149.510", field(jpy, 133))
        assertEquals("JPY", field(jpy, 15))
    }

    @Test
    fun `two quotes for one pair carry the same price, which is the point of a firm venue`() {
        val prices = (1..10).map { field(answer(requestEur), 133) }.distinct()
        assertEquals(listOf("1.09010"), prices)
    }

    @Test
    fun `the quote's sizes are the size the request asked for`() {
        val quote = answer(requestEur)
        assertEquals("1000000", field(quote, 134))
        assertEquals("1000000", field(quote, 135))
    }

    /** `62` is a live shorthand, and a shorthand that silently stopped expanding would ship as text. */
    @Test
    fun `the quote is valid for about a minute from when it is sent`() {
        val quote = answer(requestEur)
        val validUntil = assertNotNull(field(quote, 62), "no ValidUntilTime in $quote")
        val at = LocalDateTime.parse(validUntil, DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS"))
        val ahead = Duration.between(LocalDateTime.now(ZoneOffset.UTC), at).seconds
        assertTrue(ahead in 50..70, "expected ValidUntilTime about a minute out, it is ${ahead}s: $quote")
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

    // ---------------------------------------------------------------- the booking

    @Test
    fun `a buy at the offer books a trade at the quoted price, tied back by ClOrdID and QuoteRespID`() {
        val quote = answer(requestEur)
        val trade = answer(hit("EUR/USD", side = "1", price = field(quote, 133)!!))

        assertTrue(trade.startsWith("35=8"), "got: $trade")
        assertEquals("F", field(trade, 150))
        assertEquals("2", field(trade, 39))
        assertEquals("1.09010", field(trade, 31), "the trade is at the quoted offer")
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
    fun `a sell hits the bid`() {
        val trade = answer(hit("USD/JPY", side = "2", price = "149.490"))
        assertEquals("F", field(trade, 150))
        assertEquals("149.490", field(trade, 31))
    }

    @Test
    fun `a hit at any other price is refused, and the reason says so`() {
        val status = answer(hit("EUR/USD", side = "1", price = "1.09000"))
        assertTrue(status.startsWith("35=AI"), "got: $status")
        assertEquals("5", field(status, 297), "297=5 is Rejected")
        assertEquals("Q-RFQ-1", field(status, 117))
        assertTrue(field(status, 58)!!.contains("quoted price"), status)
    }

    /** A buy at the bid is a hit at the wrong price for that side, not a booking at a better one. */
    @Test
    fun `a buy at the bid is refused as the wrong price`() {
        val status = answer(hit("EUR/USD", side = "1", price = "1.08990"))
        assertEquals("5", field(status, 297), status)
    }

    @Test
    fun `a hit without a ClOrdID or a quantity is refused, naming the tags`() {
        val status = answer("35=AJ|693=RESP-1|694=1|117=Q-RFQ-1|55=EUR/USD|54=1|44=1.09010")
        assertTrue(status.startsWith("35=AI"), "got: $status")
        assertEquals("5", field(status, 297))
        val reason = field(status, 58)!!
        assertTrue(reason.contains("ClOrdID (11)") && reason.contains("OrderQty (38)"), status)
    }

    // ---------------------------------------------------------------- the other responses

    @Test
    fun `a pass is acknowledged as a pass`() {
        val status = answer(hit("EUR/USD", side = "1", price = "1.09010", respType = "6"))
        assertTrue(status.startsWith("35=AI"), "got: $status")
        assertEquals("11", field(status, 297), "297=11 is Pass")
    }

    @Test
    fun `a counter is refused, because this venue quotes firm`() {
        val status = answer(hit("EUR/USD", side = "1", price = "1.09000", respType = "2"))
        assertEquals("5", field(status, 297), status)
        assertTrue(field(status, 58)!!.contains("firm"), status)
    }

    @Test
    fun `any other response type is answered, never left in silence`() {
        listOf("3", "4", "5").forEach { respType ->
            val status = answer(hit("EUR/USD", side = "1", price = "1.09010", respType = respType))
            assertEquals("5", field(status, 297), "694=$respType got $status")
            assertTrue(field(status, 58)!!.contains("QuoteRespType"), status)
        }
    }

    @Test
    fun `a response about an unpriced symbol is refused as an unknown symbol`() {
        val status = answer(hit("XXX/YYY", side = "1", price = "1.0"))
        assertEquals("5", field(status, 297), status)
        assertEquals("Unknown symbol", field(status, 58))
        assertEquals("XXX/YYY", field(status, 55))
    }

    /** A QuoteStatusReport requires a QuoteID, so a response without one cannot be answered with one. */
    @Test
    fun `a response without a QuoteID gets a BusinessMessageReject naming the missing field`() {
        val reject = answer("35=AJ|693=RESP-9|694=1|11=TRD-9|55=EUR/USD|54=1|38=1000000|44=1.09010")
        assertTrue(reject.startsWith("35=j"), "got: $reject")
        assertEquals("AJ", field(reject, 372))
        assertEquals("RESP-9", field(reject, 379))
        assertEquals("5", field(reject, 380), "380=5 is Conditionally required field missing")
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

    // ---------------------------------------------------------------- helper

    /** The message each rule is designed against, derived from the rule's own conditions. */
    private fun sampleFor(rule: AcceptorResponseRule): String {
        fun exact(tag: Int) = (rule.trigger().firstOrNull { it.tag == tag }?.parsed() as? Matcher.Exact)?.value
        val symbol = exact(55) ?: "EUR/USD"
        return when (rule.whenMsgType) {
            "R" -> "35=R|131=RFQ-1|146=1|55=$symbol|54=1|38=1000000"
            "AJ" -> hit(symbol, side = exact(54) ?: "1", price = exact(44) ?: "1.09010", respType = exact(694) ?: "1")
            else -> error("no sample message for 35=${rule.whenMsgType}")
        }
    }
}
