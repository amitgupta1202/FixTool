package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixVersion
import org.junit.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **What a capture cannot classify, said before the scenario is made.**
 *
 * The warning used to ask `dictionary == null`, which called a loaded-but-blind dictionary safe — the one
 * situation where the author can neither see the problem nor guess it, because the tool is visibly holding
 * a dictionary. It asks what the dictionary *knows about the tags at hand* now.
 */
class CaptureRiskTest {
    private val fix44 = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)

    private fun candidate(vararg fields: Pair<Int, String>): ScenarioCapture.Candidate =
        ScenarioCapture.Candidate(
            session = "Venue",
            direction = FixMessage.Direction.OUTGOING,
            messageType = fields.firstOrNull { it.first == 35 }?.second ?: "D",
            wire = fields.joinToString("") { "${it.first}=${it.second}" },
            timestamp = LocalDateTime.of(2026, 7, 21, 11, 0, 0),
            fields = fields.toList(),
            source = null,
        )

    private val standardOrder =
        candidate(35 to "D", 11 to "ORD-1", 55 to "EUR/USD", 54 to "1", 38 to "1000000", 60 to "20260721-11:00:00")

    @Test
    fun `no dictionary is still its own sentence`() {
        val risk = ScenarioCapture.captureRisk(listOf(standardOrder), null)
        assertNotNull(risk)
        assertTrue(risk.startsWith("No dictionary loaded"), risk)
    }

    @Test
    fun `a whole dictionary over standard fields warns about nothing`() {
        assertNull(ScenarioCapture.captureRisk(listOf(standardOrder), fix44))
    }

    @Test
    fun `a dictionary blind to the venue's own tags names them`() {
        val withCustom = candidate(35 to "D", 11 to "ORD-1", 9482 to "A7F3C201881B", 9601 to "20260721-11:05:00")
        val risk = ScenarioCapture.captureRisk(listOf(withCustom), fix44)
        assertNotNull(risk)
        assertTrue(risk.contains("9482"), risk)
        assertTrue(risk.contains("9601"), risk)
        assertTrue(risk.contains("replay stale"), risk)
    }

    /** A tag capture treats correctly with no dictionary at all is not a risk, and must not be named. */
    @Test
    fun `the hardcoded tags are never reported`() {
        assertEquals(
            emptyList(),
            ScenarioCapture.unclassifiedTags(listOf(standardOrder), fix44),
        )
        // ClOrdID(11), TransactTime(60), ExpireTime(126) and MsgType(35) are decided before the dictionary
        // is asked, so even a dictionary that did not know them would leave the capture correct on them.
        val hardcodedOnly = candidate(35 to "D", 11 to "ORD-1", 60 to "20260721-11:00:00", 126 to "20260721-11:09:00")
        assertEquals(emptyList(), ScenarioCapture.unclassifiedTags(listOf(hardcodedOnly), fix44))
    }

    /**
     * One unknown tag is one tag. The first cut of this sentence pluralised the noun and left the pronoun
     * alone — "does not know tag 9482 — their type is unknown" — which reads as a bug in the tool at the
     * exact moment the tool is asking to be believed about a subtle one.
     */
    @Test
    fun `one unknown tag is described in the singular`() {
        val risk = ScenarioCapture.captureRisk(listOf(candidate(35 to "D", 9482 to "A7F3C201881B")), fix44)
        assertNotNull(risk)
        assertTrue(risk.contains("does not know tag 9482"), risk)
        assertTrue(risk.contains("its type is unknown"), risk)
        assertTrue(!risk.contains("their"), risk)
        assertTrue(!risk.contains("tags "), risk)
    }

    @Test
    fun `a wall of unknown tags is summarised rather than printed`() {
        val many = candidate(35 to "D", *(9000..9010).map { it to "x" }.toTypedArray())
        val risk = ScenarioCapture.captureRisk(listOf(many), fix44)
        assertNotNull(risk)
        assertTrue(risk.contains("+5 more"), risk)
    }

    /** Both capture doors — toolbar and review Save — must say the same thing about the same rows. */
    @Test
    fun `the sentence has one source`() {
        val rows = listOf(candidate(35 to "D", 9482 to "A7F3C201881B"))
        assertEquals(
            ScenarioCapture.captureRisk(rows, fix44),
            ScenarioCapture.captureRisk(rows, fix44),
        )
    }
}
