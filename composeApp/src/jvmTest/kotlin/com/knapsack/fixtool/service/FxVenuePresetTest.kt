package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.BookReading
import com.knapsack.fixtool.model.BookedOrder
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.OrderBook
import com.knapsack.fixtool.model.OrderConstraint
import com.knapsack.fixtool.model.OrderEvent
import com.knapsack.fixtool.model.OrderState
import com.knapsack.fixtool.model.scenario.Matcher
import org.junit.Test
import java.io.File
import java.net.URLClassLoader
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The FX venue is content, and content goes stale silently.**
 *
 * Same stance as `AcceptorPresetsTest`: nothing here reads a template as a string and pronounces it
 * good. Each test asks the engine that runs it — `explain` for whether a rule fires, `plan` for what
 * it sends — because that is the only judgement the wire agrees with.
 *
 * What is new, and what these tests exist for beyond the shipped guards, is that this bundle is the
 * first to put **live expressions** in a preset. An expression that fails to compile does not send an
 * empty field; it sends its own source text. So "no preset puts an empty field on the wire" — the
 * guard the presets have always had — would pass a venue quoting `132=${Random.nextInt(0, 5)}`.
 */
class FxVenuePresetTest {
    private val dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)

    private val bundle = AcceptorPresets.insert(emptyList(), AcceptorPresets.byId(FxVenuePreset.ID)!!).rules

    private fun request(raw: String) =
        FixMessage(
            timestamp = LocalDateTime.now(),
            direction = FixMessage.Direction.INCOMING,
            rawMessage = raw,
            quickfixMessage = AcceptorResponder.buildMessage(raw),
        )

    /**
     * The venue's book as the samples would have left it: ORD-1 received, acknowledged, live.
     *
     * Built as a real [BookedOrder] and read through [OrderBook.fields] rather than hand-written, so a
     * rule reading a name the fold does not actually produce fails here rather than in front of a
     * client. Every sample naming an order names ORD-1, so one order answers for all of them.
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
                            fields = mapOf(11 to "ORD-1", 55 to "EUR/USD", 54 to "1", 38 to "1000000", 44 to "1.08950"),
                        ),
                        OrderEvent(
                            at = LocalDateTime.now(),
                            sent = true,
                            msgType = "8",
                            fields =
                                mapOf(11 to "ORD-1", 37 to "EX-1", 150 to "0", 39 to "0", 14 to "0", 151 to "1000000"),
                        ),
                    ),
            ),
        )

    /** Everything [rule] would send for [raw], resolved exactly as the dispatcher would resolve it. */
    private fun sends(rule: AcceptorResponseRule, raw: String): List<String> =
        AcceptorResponder
            .plan(rule, AcceptorResponder.buildMessage(raw), request(raw), dictionary) { bookedOrder }
            .map { it.render() }

    /** The rule this whole bundle picks for [raw] — the question first-match-wins actually answers. */
    private fun winnerFor(raw: String): AcceptorResponseRule {
        val outcome = AcceptorResponder.explain(bundle, AcceptorResponder.buildMessage(raw)).firstOrNull { it.selected }
        assertNotNull(outcome, "the FX venue answered nothing at all for $raw")
        return outcome.rule
    }

    private fun field(message: String, tag: Int): String? =
        message.split('|').firstOrNull { it.startsWith("$tag=") }?.substringAfter('=')

    private val quoteRequestEur = "35=R|131=Q-1|55=EUR/USD|54=1|38=1000000"
    private val quoteRequestJpy = "35=R|131=Q-2|55=USD/JPY|54=1|38=1000000"
    private val quoteRequestUnknown = "35=R|131=Q-3|55=XXX/YYY|54=1|38=1000000"
    private val marketOrderEur = "35=D|11=ORD-1|55=EUR/USD|54=1|38=1000000|40=1|60=20260730-09:14:22.000"
    private val limitOrderEur = "35=D|11=ORD-2|55=EUR/USD|54=1|38=1000000|40=2|44=1.08950|60=20260730-09:14:22.000"
    private val orderUnknown = "35=D|11=ORD-3|55=XXX/YYY|54=1|38=1000000|40=1|60=20260730-09:14:22.000"
    private val hugeOrder = "35=D|11=ORD-4|55=EUR/USD|54=1|38=20000000|40=2|44=1.08950|60=20260730-09:14:22.000"

    // ---------------------------------------------------------------- the shape of the bundle

    /**
     * **The card order, asserted whole.** Three behaviours depend on it and none is visible in the
     * source, because [AcceptorPresets.insert] reverses each conditioned block on the way in.
     */
    @Test
    fun `the FX venue's cards read in the documented order`() {
        val read = bundle.map { rule -> "${rule.whenMsgType}${rule.whenOrder?.let { "/${it.word}" } ?: ""}" }

        assertEquals(
            listOf(
                "R",
                "R",
                "R",
                "R",
                "D/working",
                "D/pending",
                "D",
                "D",
                "D",
                "D",
                "D",
                "D",
                "F/unknown",
                "F/pending",
                "F/working",
                "F/done",
                "G/working",
                "G",
                "H/unknown",
                "H/working",
                "H/done",
            ),
            read,
        )
        assertEquals(21, bundle.size, "the summary says 21 rules")
    }

    /** Every rule can be reached: nothing above it answers everything of its type. */
    @Test
    fun `every rule of the FX venue is reachable`() {
        bundle.indices.forEach { index ->
            assertTrue(
                AcceptorResponder.shadowingRule(bundle, index) == null,
                "rule ${index + 1} (35=${bundle[index].whenMsgType}) can never fire",
            )
        }
    }

    /**
     * The placement defect that was live-caught when presets first shipped, asked of this bundle: a
     * 20M limit order matches the fill rule *and* the size guard, and first-match-wins must pick the guard.
     */
    @Test
    fun `an oversize order is rejected rather than filled`() {
        val sent = sends(winnerFor(hugeOrder), hugeOrder)

        assertEquals(1, sent.size, "a rejected order gets one report, not a fill sequence")
        assertEquals("8", field(sent.single(), 39))
        assertEquals("3", field(sent.single(), 103))
    }

    /** The duplicate guard has to outrank the fills too, or a scenario run twice fills twice. */
    @Test
    fun `a duplicate ClOrdID is rejected rather than filled again`() {
        val outcome =
            AcceptorResponder
                .explain(
                    bundle,
                    AcceptorResponder.buildMessage(limitOrderEur),
                    BookReading("ORD-2", OrderState.WORKING),
                ).first { it.selected }

        assertEquals(OrderConstraint.WORKING, outcome.rule.whenOrder)
        assertEquals(
            "6",
            field(
                outcome.rule
                    .sequence()
                    .single()
                    .template,
                103,
            ),
            "103=6 is Duplicate Order",
        )
    }

    // ---------------------------------------------------------------- what an unknown symbol gets

    @Test
    fun `a quote request for an unpriced symbol falls past the three pairs`() {
        val sent = sends(winnerFor(quoteRequestUnknown), quoteRequestUnknown).single()

        assertTrue(sent.startsWith("35=AG"), "got: $sent")
        assertEquals("1", field(sent, 658), "658=1 is Unknown symbol")
        assertEquals("XXX/YYY", field(sent, 55), "the reject names the symbol it is refusing")
    }

    @Test
    fun `an order for an unpriced symbol is rejected, not filled`() {
        val sent = sends(winnerFor(orderUnknown), orderUnknown).single()

        assertEquals("8", field(sent, 39))
        assertEquals("1", field(sent, 103), "103=1 is Unknown symbol")
    }

    // ---------------------------------------------------------------- the prices

    /**
     * **The guard the empty-field check cannot be.** A template expression that does not compile is
     * returned as its own source text, so a broken price ships as `132=${…}` — a field with a value,
     * and a perfectly well-formed message carrying nonsense.
     */
    @Test
    fun `no rule of the FX venue sends an unevaluated expression`() {
        bundle.forEach { rule ->
            val raw = sampleFor(rule)
            sends(rule, raw).forEach { sent ->
                assertTrue(
                    !sent.contains("\${"),
                    "35=${rule.whenMsgType} would send an expression it never evaluated: $sent",
                )
            }
        }
    }

    /** A price whose decimal count varies per draw is not a price. Catches the locale trap too. */
    @Test
    fun `every price the venue quotes has its pair's decimals`() {
        listOf(quoteRequestEur to 5, quoteRequestJpy to 3).forEach { (raw, decimals) ->
            val quote = sends(winnerFor(raw), raw).single()
            listOf(132, 133).forEach { tag ->
                val price = field(quote, tag)
                assertNotNull(price, "no tag $tag in $quote")
                assertTrue(
                    Regex("""^\d+\.\d{$decimals}$""").matches(price),
                    "tag $tag should be a $decimals-decimal price, was '$price' in $quote",
                )
            }
        }
    }

    /**
     * **The bid is below the ask, and the spread never moves.** Two independent draws would let it
     * vary and let it invert; one draw, named and read back, is what stops that — and it depends on
     * the naming field preceding the reading one in the template.
     */
    @Test
    fun `a quote's bid is below its ask by exactly the pair's spread, every time`() {
        repeat(50) {
            val quote = sends(winnerFor(quoteRequestEur), quoteRequestEur).single()
            val bid = field(quote, 132)!!.toDouble()
            val ask = field(quote, 133)!!.toDouble()

            assertTrue(bid < ask, "a bid above its ask is not a quote: $quote")
            assertEquals(2.0E-4, ask - bid, 1.0E-9, "the spread must not vary: $quote")
        }
    }

    /** The claim the whole jitter exists for: two quotes in a row are not the same quote. */
    @Test
    fun `two quotes for the same pair price differently`() {
        val prices = (1..40).map { field(sends(winnerFor(quoteRequestEur), quoteRequestEur).single(), 132) }

        assertTrue(prices.distinct().size > 1, "every quote priced identically: ${prices.first()}")
    }

    /** One fill has one price: an AvgPx that disagreed with its own LastPx is a venue contradicting itself. */
    @Test
    fun `a market fill reports the same price as LastPx and AvgPx`() {
        repeat(20) {
            val fill = sends(winnerFor(marketOrderEur), marketOrderEur).last()

            assertEquals(field(fill, 31), field(fill, 6), "31 and 6 must agree in $fill")
        }
    }

    /**
     * A market order carries no 44, which is why the per-pair rules price from their own content.
     *
     * The band is asserted rather than a digit pattern: the jitter deliberately **straddles** the mid,
     * so half the draws are `1.089…` and half `1.090…`, and a pattern pinned to either one is a test
     * that fails one run in two.
     */
    @Test
    fun `a market order is filled at the venue's own price, not an empty one`() {
        repeat(20) {
            val fill = sends(winnerFor(marketOrderEur), marketOrderEur).last()

            val price = field(fill, 31)
            assertNotNull(price, "no LastPx in $fill")
            assertTrue(Regex("""^\d+\.\d{5}$""").matches(price), "not a 5-decimal price: '$price'")
            assertTrue(
                price.toDouble() in 1.08995..1.09004,
                "expected a EUR/USD price straddling the 1.09000 mid, got '$price'",
            )
        }
    }

    /** The limit flow prices from the order, which is what makes the bundled demo scenario assertable. */
    @Test
    fun `a limit order is filled at its own price`() {
        val sent = sends(winnerFor(limitOrderEur), limitOrderEur)

        assertEquals(3, sent.size, "ack, partial, remainder")
        assertEquals("1.08950", field(sent[1], 31), "the partial fills at the order's price")
        assertEquals("1.08950", field(sent[2], 31))
    }

    // ---------------------------------------------------------------- initialisation order

    /**
     * **Neither object may be observed half-built**, whichever the JVM loads first.
     *
     * [FxVenuePreset] composes [AcceptorPresets]' rules by name and [AcceptorPresets.all] holds the
     * bundle, so the two are a cycle. With an eager catalogue it resolved in exactly one direction:
     * reaching `FxVenuePreset` first ran its initialiser, which reached back into `AcceptorPresets`,
     * which built `all`, which read a `preset` that did not exist yet. `all` is `by lazy` to stop that,
     * and this proves it from **both** directions — a fresh class loader each way, because within one
     * JVM the classes initialise once and the order cannot be re-run.
     */
    @Test
    fun `the catalogue is well formed whichever class is touched first`() {
        listOf(
            "com.knapsack.fixtool.service.FxVenuePreset",
            "com.knapsack.fixtool.service.AcceptorPresets",
        ).forEach { touchedFirst ->
            freshLoader().use { loader ->
                // Initialise the named class before anything else in the pair.
                Class.forName(touchedFirst, true, loader)

                val presets = Class.forName("com.knapsack.fixtool.service.AcceptorPresets", true, loader)
                val instance = presets.getField("INSTANCE").get(null)

                @Suppress("UNCHECKED_CAST")
                val all = presets.getMethod("getAll").invoke(instance) as List<Any?>

                assertTrue(all.none { it == null }, "touching $touchedFirst first left a null in the catalogue")
                val ids = all.map { it!!.javaClass.getMethod("getId").invoke(it) as String }
                assertTrue(FxVenuePreset.ID in ids, "the FX bundle is missing when $touchedFirst is touched first")
                assertEquals(ids.size, ids.distinct().size, "duplicate preset id")
            }
        }
    }

    /** A loader with no parent but the platform's, so the classes under test genuinely initialise afresh. */
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

    /**
     * The message each rule is designed against, derived from the rule's **own** conditions rather than
     * a table — a bundle whose rules name three symbols and two order types cannot be sampled by
     * MsgType alone, and a rule tested against a message it was never meant to answer tests nothing.
     */
    private fun sampleFor(rule: AcceptorResponseRule): String {
        val symbol =
            (rule.trigger().firstOrNull { it.tag == 55 }?.parsed() as? Matcher.Exact)?.value ?: "EUR/USD"
        val market = rule.trigger().any { it.tag == 40 && it.parsed() == Matcher.Exact("1") }
        val oversize = rule.trigger().any { it.tag == 38 }
        return when (rule.whenMsgType) {
            "R" -> "35=R|131=Q-1|55=$symbol|54=1|38=1000000"
            "D" ->
                "35=D|11=ORD-1|55=$symbol|54=1|38=${if (oversize) "20000000" else "1000000"}" +
                    (if (market) "|40=1" else "|40=2|44=1.08950") +
                    "|60=20260730-09:14:22.000"
            "F" -> "35=F|41=ORD-1|11=CXL-1|55=EUR/USD|54=1|60=20260730-09:14:22.000"
            "G" -> "35=G|41=ORD-1|11=RPL-1|55=EUR/USD|54=1|38=500000|40=2|44=1.08950|60=20260730-09:14:22.000"
            "H" -> "35=H|37=EX-1|11=ORD-1|55=EUR/USD|54=1"
            else -> error("no sample message for 35=${rule.whenMsgType}")
        }
    }
}
