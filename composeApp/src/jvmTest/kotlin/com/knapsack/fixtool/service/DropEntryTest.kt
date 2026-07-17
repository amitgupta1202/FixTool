package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.service.ScenarioReconcile.MoveResult
import com.knapsack.fixtool.service.compare.GroupOverlay
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [ScenarioReconcile.dropEntry] — "this environment sends one fewer party", as one edit.
 *
 * The rule mirrors the move rule's core: the entry is the unit. A whole entry may leave (its later
 * siblings deliberately move up one occurrence — that is what the environment did), where a lone row of
 * a repeated tag may not, because rows that jointly describe one party must never be half-deleted into
 * describing two.
 */
class DropEntryTest {
    private val dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)

    private fun exp(vararg fields: Pair<Int, String>) =
        Expectation(fields.map { (tag, value) -> FieldExpectation(tag, Matcher.Exact(value)) }, messageType = "8")

    private fun overlayFor(draft: Expectation) = GroupOverlay.of(draft, dictionary)

    private fun threeParties() =
        exp(
            453 to "3",
            448 to "FIRMA", 447 to "D", 452 to "1",
            448 to "FIRMB", 447 to "D", 452 to "4",
            448 to "FIRMC", 447 to "D", 452 to "7",
        )

    private fun values(result: MoveResult) =
        (result as MoveResult.Applied).expectation.fields.map { (it.matcher as Matcher.Exact).value }

    private fun why(result: MoveResult): String {
        assertTrue(result is MoveResult.Refused, "expected a refusal, got $result")
        return result.why
    }

    @Test
    fun `dropping the middle party removes exactly its rows, and the survivors keep describing their firms`() {
        val draft = threeParties()

        val dropped = ScenarioReconcile.dropEntry(draft, overlayFor(draft), entry = 4..6)

        // FIRMB is gone whole; FIRMA and FIRMC keep their role rows beside them. FIRMC's rows now assert
        // occurrence 2 — deliberately, because the venue that dropped a party now sends FIRMC second.
        assertEquals(listOf("3", "FIRMA", "D", "1", "FIRMC", "D", "7"), values(dropped))
    }

    @Test
    fun `dropping the first party works the same — the delimiter is not special`() {
        val draft = threeParties()

        val dropped = ScenarioReconcile.dropEntry(draft, overlayFor(draft), entry = 1..3)

        assertEquals(listOf("3", "FIRMB", "D", "4", "FIRMC", "D", "7"), values(dropped))
    }

    @Test
    fun `a rotation window is not an entry, and cannot be dropped`() {
        val draft = threeParties()

        // 2..4 is `447,452,448` — the tail of FIRMA's party welded to the head of FIRMB's.
        val why = why(ScenarioReconcile.dropEntry(draft, overlayFor(draft), entry = 2..4))

        assertTrue("not an entry" in why, why)
    }

    @Test
    fun `a single row of a bare repeated run is not an entry drop, it is the row delete wearing its clothes`() {
        val draft = exp(454 to "2", 455 to "ALT-1", 455 to "ALT-2")

        val why = why(ScenarioReconcile.dropEntry(draft, overlayFor(draft), entry = 1..1))

        assertTrue("bare run" in why, why)
    }

    @Test
    fun `a single-row entry the dictionary defines drops like any entry`() {
        val draft =
            Expectation(
                fields =
                    listOf(
                        FieldExpectation(267, Matcher.Exact("2")),
                        FieldExpectation(269, Matcher.Exact("0")),
                        FieldExpectation(269, Matcher.Exact("1")),
                    ),
                messageType = "V",
            )

        val dropped = ScenarioReconcile.dropEntry(draft, overlayFor(draft), entry = 1..1)

        assertEquals(listOf("2", "1"), values(dropped))
    }
}
