package com.knapsack.fixtool.ui

import com.knapsack.fixtool.model.load.LoadReport
import com.knapsack.fixtool.model.load.RoundTripHistogram
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The arithmetic behind the two pictures**, kept out of the drawing so it can be read.
 *
 * The curve's job is to make four unanswered out of four thousand visible. On a plain cumulative axis
 * that is 0.1% of the plot height and a tenth of a pixel; complementary and logarithmic it is a plateau
 * the curve never falls off, and this is the test that says so in numbers.
 */
class LoadChartsTest {
    private fun histogramOf(vararg micros: Long): List<Int> =
        RoundTripHistogram.empty().toMutableList().also { h -> micros.forEach { h[RoundTripHistogram.indexOf(it)]++ } }

    @Test
    fun `the curve starts at everything outstanding and falls as replies land`() {
        val curve = outstandingCurve(histogramOf(1_000, 10_000, 100_000, 1_000_000), issued = 4)

        assertEquals(1.0, curve.first().second, "before anything came back, all of it is outstanding")
        assertEquals(listOf(1.0, 0.75, 0.5, 0.25, 0.0), curve.map { it.second })
        assertTrue(curve.zipWithNext().all { (a, b) -> b.first > a.first }, "and it reads left to right in time")
    }

    /**
     * The floor is the point of the whole chart: a request that never came back is outstanding forever, so
     * the denominator is what left the socket and not what was matched.
     */
    @Test
    fun `four unanswered out of four thousand is a plateau at a tenth of a percent`() {
        val answered = LongArray(3_996) { 5_000L }
        val curve = outstandingCurve(histogramOf(*answered), issued = 4_000)

        assertEquals(4.0 / 4_000, curve.last().second, 1e-9, "the curve flattens at the unanswered share")
        assertEquals("0.10%", percent(curve.last().second))
    }

    @Test
    fun `a run where nothing was answered never leaves the top of the chart`() {
        val curve = outstandingCurve(RoundTripHistogram.empty(), issued = 4_000)

        assertEquals(listOf(1.0), curve.map { it.second })
    }

    /** Under the cap, a column is a second. Over it, a column is the range its seconds covered. */
    @Test
    fun `seconds become columns, and a downsampled column is a range and never a worst second`() {
        val seconds = (0 until 8).map { LoadReport.Second(it, issued = 500, matched = 480 + it * 10, p95Us = (it + 1) * 1_000L) }

        assertEquals(8, columnsOf(seconds, 8).size)
        assertEquals(SecondColumn(480, 480, 500, 1_000), columnsOf(seconds, 8).first())

        val four = columnsOf(seconds, 4)
        assertEquals(4, four.size)
        assertEquals(SecondColumn(answeredLow = 480, answeredHigh = 490, issuedHigh = 500, p95High = 2_000), four.first())
        assertEquals(SecondColumn(answeredLow = 540, answeredHigh = 550, issuedHigh = 500, p95High = 8_000), four.last())
    }

    /**
     * A catch-up second above the schedule is exactly the case a single "worst second" per column cannot
     * represent, and min-of-N is what makes a healthy run read as a sustained shortfall.
     */
    @Test
    fun `a column that holds both a stall and its catch-up shows both`() {
        val seconds =
            listOf(
                LoadReport.Second(0, 500, 500, 4_000),
                LoadReport.Second(1, 500, 318, 120_000),
                LoadReport.Second(2, 500, 688, 58_000),
                LoadReport.Second(3, 500, 500, 4_000),
            )

        val one = columnsOf(seconds, 1).single()

        assertEquals(318, one.answeredLow, "the stall is in there")
        assertEquals(688, one.answeredHigh, "and so is the catch-up above the schedule")
        assertEquals(120_000, one.p95High)
    }

    @Test
    fun `an empty run still gives the chart something to draw`() {
        assertEquals(listOf(SecondColumn(0, 0, 0, null)), columnsOf(emptyList(), 600))
    }
}

/**
 * **The one sentence a lane table exists to produce.** A 50 × 6 matrix answers no question anyone asks;
 * "is any lane much worse than the rest" is the question, and it has a one-line answer.
 */
class LaneSentenceTest {
    private fun lane(slot: Int, p95: Long?, unanswered: Long = 0) =
        LoadReport.LaneCounts(slot, matched = 100, unanswered = unanswered, duplicates = 0, p50Us = p95, p95Us = p95)

    @Test
    fun `a lane well outside the rest is named`() {
        val lanes = listOf(lane(7, 40_000), lane(1, 4_000), lane(2, 4_200), lane(3, 3_900))

        assertTrue(laneSentence(lanes).startsWith("lane 7's p95 is"), laneSentence(lanes))
    }

    @Test
    fun `lanes that agree get the fact that they agree, and no lane number`() {
        val lanes = listOf(lane(1, 4_200), lane(2, 4_000), lane(3, 3_900))

        assertTrue(laneSentence(lanes).startsWith("sorted by p95"), laneSentence(lanes))
    }

    /** The most useful lane finding there is, and it needs no latency at all. */
    @Test
    fun `every unanswered request landing on one lane is the sentence, whatever the latencies say`() {
        val lanes = listOf(lane(1, 4_000), lane(2, 4_000, unanswered = 4))

        assertEquals("every unanswered request was issued on lane 2", laneSentence(lanes))
    }

    @Test
    fun `a run with no round trips says so rather than dividing by nothing`() {
        assertEquals("completeness per lane · no round trips to compare", laneSentence(listOf(lane(1, null), lane(2, null))))
    }
}

/** Which lanes earn a row when there are more lanes than rows. */
class WorstLanesTest {
    private fun lane(slot: Int, p95: Long, unanswered: Long = 0) =
        LoadReport.LaneCounts(slot, matched = 79, unanswered = unanswered, duplicates = 0, p50Us = 3_162, p95Us = p95)

    @Test
    fun `under the limit every lane is shown`() {
        val lanes = listOf(lane(1, 4_000), lane(2, 4_000))

        assertEquals(lanes, worstOf(lanes, 8))
    }

    /** Unanswered first: four unanswered requests all on one lane is the answer, whatever its p95 was. */
    @Test
    fun `a lane with something unanswered keeps its row however fast it was`() {
        val quickButMissing = lane(9, 1_000, unanswered = 4)
        val slow = (1..8).map { lane(it, 90_000 - it.toLong()) }

        val shown = worstOf(slow + quickButMissing, 3)

        assertEquals(listOf(9, 1, 2), shown.map { it.slot })
    }

    @Test
    fun `otherwise the limit takes them in the order it was given, which is worst p95 first`() {
        val sorted = (1..50).map { lane(it, 90_000 - it.toLong()) }

        assertEquals((1..8).toList(), worstOf(sorted, 8).map { it.slot })
    }
}
