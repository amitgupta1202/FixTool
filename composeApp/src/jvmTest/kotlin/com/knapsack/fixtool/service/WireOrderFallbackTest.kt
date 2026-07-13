package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.TagStatus
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Characterises the wire-order fallback — the hole open question 5 named.
 *
 * The assertion engine pairs the k-th row for a tag with the k-th occurrence of that tag, and OPEN
 * requires the expectation to be a *subsequence* of the reply, so **the order of the fields the engine
 * reads is load-bearing**. These tests pin down what QuickFIX/J actually hands us, because the answer
 * decides whether an order-sensitive assertion is being judged against the venue's message or against
 * a re-serialisation of it that no venue ever sent.
 */
class WireOrderFallbackTest {
    private fun dictionary(): FixDictionaryAdapter {
        val resource =
            this::class.java.classLoader.getResource("test-group-ordering.xml")
                ?: error("Test data dictionary not found")
        return FixDictionaryAdapter.fromFile(File(resource.toURI()))
    }

    /** An ExecutionReport whose party group sits mid-body, the way a venue actually sends one. */
    private val venueBytes =
        "8=FIX.4.49=035=849=VENUE56=US34=252=20260713-11:02:44" +
            "11=ORD-139=2" +
            "453=2448=FIRMA447=D452=1448=FIRMA447=D452=4" +
            "58=ok10=000"

    @Test
    fun `toString does not preserve the venue's field order — it sorts and relocates the group`() {
        val dd = dictionary().getDataDictionary()
        val parsed = quickfix.Message()
        parsed.fromString(venueBytes, dd, false)

        val reserialised = parsed.toString()

        val venueOrder = FixMessageHelper.parseFixMessage(venueBytes, delimiter = '').map { it.first }
        val stringOrder = FixMessageHelper.parseFixMessage(reserialised, delimiter = '').map { it.first }

        // The proof that the fallback is not merely "a different but equivalent encoding".
        assertNotEquals(venueOrder, stringOrder, "expected toString() to reorder the body; it did not")

        // The venue put the party group before Text(58). Report what toString() did with it, so a
        // future QFJ upgrade that changes this is caught here rather than in a red scenario.
        val groupAt = stringOrder.indexOf(453)
        val textAt = stringOrder.indexOf(58)
        assertTrue(
            groupAt > textAt,
            "QFJ relocated the group to the end of the body (453 after 58) — venue order: $venueOrder, " +
                "toString order: $stringOrder",
        )
    }

    /**
     * The whole reason this is fixable rather than merely survivable: QuickFIX/J already retained the
     * exact bytes it parsed. If this ever stops holding, the engine has no source of wire order at all
     * and must say so rather than guess — which is what [FixMessage.wireRaw] being null now means.
     */
    @Test
    fun `toRawString returns the venue's bytes verbatim`() {
        val dd = dictionary().getDataDictionary()
        val parsed = quickfix.Message()
        parsed.fromString(venueBytes, dd, false)

        assertEquals(venueBytes, parsed.toRawString())

        val rawOrder = FixMessageHelper.parseFixMessage(parsed.toRawString(), delimiter = '').map { it.first }
        val venueOrder = FixMessageHelper.parseFixMessage(venueBytes, delimiter = '').map { it.first }
        assertEquals(venueOrder, rawOrder, "toRawString() must be the wire, field for field, in order")
    }

    /**
     * The damage, made concrete: this is what the old fallback did to a scenario that was entirely correct.
     *
     * Capture seeds its rows from the venue's bytes, so the expectation is a subsequence of its own golden
     * by construction — it passes. Replay the same message, but let the raw-capture lookup miss, and the
     * engine was handed `toString()` instead. Nothing about the venue changed. Nothing about the scenario
     * changed. The step went red anyway, on rows whose values are *identical*, and the failures read as
     * `moved` — which an engineer reasonably reads as "the venue reordered its group".
     *
     * This test asserts the broken behaviour on purpose, against the string the old code would have used,
     * so the cost of that fallback is written down rather than argued about. The production path can no
     * longer reach it: `wireRaw` is the venue's bytes or it is null.
     */
    @Test
    fun `the old fallback reddened a correct expectation, on rows whose values had not changed`() {
        val dd = dictionary().getDataDictionary()
        val parsed = quickfix.Message()
        parsed.fromString(venueBytes, dd, false)

        val venueOrder = FixMessageHelper.parseFixMessage(venueBytes, delimiter = '\u0001')
        val fallbackOrder = FixMessageHelper.parseFixMessage(parsed.toString(), delimiter = '\u0001')

        // Exactly what capture produces from the venue's bytes: rows in wire order.
        val expectation =
            Expectation(
                fields = ExpectationSeeder.seedDetailed(venueOrder, dictionary()).map { it.field },
                messageType = "8",
                mode = MatchMode.OPEN,
            )

        // Against the venue's own message it passes — as a captured scenario must.
        val onVenueBytes = ExpectationEvaluator.evaluate(FixMessageView.ofFields(venueOrder), expectation)
        assertTrue(
            onVenueBytes.all { it.passed },
            "a captured expectation must pass its own golden: ${onVenueBytes.filterNot { it.passed }}",
        )

        // Against QuickFIX's re-serialisation of that same message, it does not.
        val onFallback = ExpectationEvaluator.evaluate(FixMessageView.ofFields(fallbackOrder), expectation)
        val broken = onFallback.filterNot { it.passed }
        assertTrue(broken.isNotEmpty(), "this is the false red the fallback produced; if it is gone, say why")
        assertTrue(
            broken.any { it.status == TagStatus.MOVED || it.status == TagStatus.MISSING },
            "the failures blame the venue for a reordering FixTool performed itself: $broken",
        )
    }
}
