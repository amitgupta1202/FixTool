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

    /** The claim that matters at load: a reply with only shorthand generators renders in microseconds. */
    @Test
    fun `a thousand generator-only replies render well inside a second`() {
        val template = "35=S|117=\${uuid}|62=\${utcnow+1min}|60=\${now}|17=\${uuid:12}"
        val started = System.nanoTime()
        repeat(1_000) { AcceptorResponder.resolveAtSendTime(template) }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsedMs < 1_000, "1,000 renders took ${elapsedMs}ms; the engine is back on the path")
    }
}
