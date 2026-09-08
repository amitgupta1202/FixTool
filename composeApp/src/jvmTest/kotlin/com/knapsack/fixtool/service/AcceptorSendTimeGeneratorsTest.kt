package com.knapsack.fixtool.service

import org.junit.Test
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **A shorthand generator in an acceptor reply never reaches the script engine.**
 *
 * The expander turns `${utcnow+1min}` into a Kotlin expression and the engine compiles it, at about
 * 60 ms a reply on the one thread every acceptor reply shares. That was the whole of why the RFQ venue
 * answered fourteen quote requests a second. These pin that the send-time pass renders every pure
 * generator itself, leaves everything else for the passes after it, and produces what the compiled
 * expression would have.
 */
class AcceptorSendTimeGeneratorsTest {
    private val fixPattern = DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS")

    private fun field(message: String, tag: Int): String? =
        message.split('|').firstOrNull { it.startsWith("$tag=") }?.substringAfter('=')

    @Test
    fun `a utcnow offset renders to a UTC timestamp, and nothing is left for the engine`() {
        val resolved = AcceptorResponder.resolveAtSendTime("35=S|117=Q-1|62=\${utcnow+1min}|60=\${now}")

        assertFalse(FixMessageTemplate.hasTemplateExpressions(resolved), "an expression survived: $resolved")
        val validUntil = LocalDateTime.parse(field(resolved, 62), fixPattern)
        val ahead = Duration.between(LocalDateTime.now(ZoneOffset.UTC), validUntil).seconds
        assertTrue(ahead in 55..65, "expected about a minute ahead in UTC, was ${ahead}s: $resolved")
    }

    @Test
    fun `a bare now stays the UTC clock it always was`() {
        val resolved = AcceptorResponder.resolveAtSendTime("60=\${now}")
        val at = LocalDateTime.parse(field(resolved, 60), fixPattern)
        val drift = Duration.between(LocalDateTime.now(ZoneOffset.UTC), at).abs().seconds
        assertTrue(drift < 5, "\${now} should be the UTC clock, was $resolved")
    }

    @Test
    fun `a cut uuid renders to that many dash-less characters`() {
        val id = field(AcceptorResponder.resolveAtSendTime("17=\${uuid:8}"), 17)!!
        assertEquals(8, id.length, id)
        assertFalse(id.contains('-'))
    }

    @Test
    fun `a custom pattern renders through the pattern`() {
        val date = field(AcceptorResponder.resolveAtSendTime("64=\${utcnow+2d:yyyyMMdd}"), 64)!!
        assertTrue(Regex("""^\d{8}$""").matches(date), date)
    }

    /** The passes after this one still get what is theirs: expressions, assignments, book reads. */
    @Test
    fun `anything that is not a pure generator is left exactly as written`() {
        val template = "31=\${\"%.5f\".format(java.util.Locale.US, 1.0)}|6=\${px}|37=\${order.orderId}|11=\${id = uuid}"
        assertEquals(template, AcceptorResponder.resolveAtSendTime(template))
    }

    /**
     * Seconds, which a quote's validity needs and nothing else could say: `min` is still minutes and a
     * bare `m` is still months, so the alternation has to keep `min` first.
     */
    @Test
    fun `a seconds offset renders thirty seconds ahead, on the UTC clock`() {
        val resolved = AcceptorResponder.resolveAtSendTime("35=S|62=\${utcnow+30s}|60=\${utcnow}")

        assertFalse(FixMessageTemplate.hasTemplateExpressions(resolved), "an expression survived: $resolved")
        val validUntil = LocalDateTime.parse(field(resolved, 62), fixPattern)
        val ahead = Duration.between(LocalDateTime.now(ZoneOffset.UTC), validUntil).seconds
        assertTrue(ahead in 25..35, "expected about thirty seconds ahead, was ${ahead}s: $resolved")
    }

    @Test
    fun `five minutes is still five minutes and five months is still five months`() {
        val minutes = LocalDateTime.parse(field(AcceptorResponder.resolveAtSendTime("62=\${utcnow+5min}"), 62), fixPattern)
        val months = LocalDateTime.parse(field(AcceptorResponder.resolveAtSendTime("62=\${utcnow+5m}"), 62), fixPattern)
        val now = LocalDateTime.now(ZoneOffset.UTC)

        assertTrue(Duration.between(now, minutes).toMinutes() in 4..5, "5min read as something else: $minutes")
        assertTrue(Duration.between(now, months).toDays() > 100, "5m should be five months: $months")
    }

    /**
     * A venue prices its own quotes, and a price identical on four thousand of them is a venue nobody
     * would recognise. Quantised, because the value goes on a wire as a price.
     */
    @Test
    fun `a random price lands inside its band, with the decimals it was asked for`() {
        val seen = mutableSetOf<String>()
        repeat(200) {
            val resolved = AcceptorResponder.resolveAtSendTime("35=S|133=\${random:1.09000:1.09090:5}")
            assertFalse(FixMessageTemplate.hasTemplateExpressions(resolved), "an expression survived: $resolved")
            val price = field(resolved, 133)!!
            seen += price
            assertEquals(5, price.substringAfter('.').length, "five decimals, plainly written: $price")
            assertTrue(price.toBigDecimal() >= "1.09000".toBigDecimal(), price)
            assertTrue(price.toBigDecimal() <= "1.09090".toBigDecimal(), price)
        }
        assertTrue(seen.size > 10, "every quote got the same price, which is no venue: $seen")
    }

    /**
     * **Bounds carrying more decimals than the price does cannot push the draw out of the band.**
     *
     * `${'$'}{random:0.15:2.85:0}` asks for a whole number between the two, so the only answers are 1 and 2.
     * Rounding a draw from the raw band gave 0 and 3 as well, and a venue quoting outside its own band is
     * the bug a client would report against the tool.
     */
    @Test
    fun `a band quoted finer than its decimals still draws inside itself`() {
        val low = "0.15".toBigDecimal()
        val high = "2.85".toBigDecimal()
        val seen = mutableSetOf<String>()
        repeat(400) {
            val price = field(AcceptorResponder.resolveAtSendTime("35=S|133=\${random:0.15:2.85:0}"), 133)!!
            seen += price
            assertTrue(price.toBigDecimal() >= low, "below the band: $price")
            assertTrue(price.toBigDecimal() <= high, "above the band: $price")
        }
        assertEquals(setOf("1", "2"), seen, "the only whole numbers in [0.15, 2.85]")
    }

    /** Both ends are answers, not just the middle: a closed range that cannot reach its own bounds is not one. */
    @Test
    fun `both ends of a band are reachable`() {
        val seen = mutableSetOf<String>()
        repeat(600) {
            seen += field(AcceptorResponder.resolveAtSendTime("35=S|133=\${random:1.09000:1.09002:5}"), 133)!!
        }
        assertEquals(setOf("1.09000", "1.09001", "1.09002"), seen, "min and max are both quotable")
    }

    /**
     * The Kotlin the old path still compiles draws from the **narrowed** band too.
     *
     * A scenario step's `${'$'}{random:…}` is rewritten by the expander rather than rendered natively, so the
     * two would quote different bands if only one of them narrowed.
     */
    @Test
    fun `the rewritten Kotlin draws from the same narrowed band`() {
        val rewritten = ShorthandTemplateExpander.expand("133=\${random:0.15:2.85:0}", null)

        // [0.15, 2.85] narrows to [1, 2], one tick wide, so the draw is over two ticks and floored.
        assertTrue(rewritten.contains("""multiply(java.math.BigDecimal("2")"""), rewritten)
        assertTrue(rewritten.contains("setScale(0, java.math.RoundingMode.FLOOR)"), rewritten)
        assertTrue(rewritten.contains("""add(java.math.BigDecimal("1")"""), rewritten)
    }

    /** A band with no quotable price in it is not a band, and is refused where a reversed one is. */
    @Test
    fun `a band that quantises to nothing is refused`() {
        val template = "133=\${random:0.15:0.85:0}"

        assertEquals(template, AcceptorResponder.resolveAtSendTime(template), "no whole number lies in [0.15, 0.85]")
        val errors = ShorthandTemplateExpander.validateShorthand(template, null)
        assertTrue(errors.any { it.contains("min no greater than max") }, errors.toString())
    }

    @Test
    fun `a random whose numbers cannot mean anything is left for the engine to complain about`() {
        val template = "133=\${random:1.09:1.08:5}"
        assertEquals(template, AcceptorResponder.resolveAtSendTime(template), "max below min is not a band")
        val errors = ShorthandTemplateExpander.validateShorthand(template, null)
        assertTrue(errors.any { it.contains("min no greater than max") }, errors.toString())
    }

    /** The claim that matters at load: a reply with only shorthand generators renders in microseconds. */
    @Test
    fun `a thousand generator-only replies render well inside a second`() {
        val template = "35=S|117=\${uuid}|62=\${utcnow+30s}|60=\${now}|17=\${uuid:12}|133=\${random:1.09:1.10:5}"
        val started = System.nanoTime()
        repeat(1_000) { AcceptorResponder.resolveAtSendTime(template) }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsedMs < 1_000, "1,000 renders took ${elapsedMs}ms; the engine is back on the path")
    }
}
