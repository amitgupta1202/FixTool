package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.model.scenario.StepOrigin
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **A paste never quietly becomes a capture.**
 *
 * `StepOrigin` is the one thing a scenario file carries about *trust*: FixTool watched a live capture arrive
 * on a wire it was connected to, and it did not watch a paste. The badge that follows a pasted step around is
 * read off this, and it survives the save — which is the whole promise, because the golden is deliberately
 * **not** re-pointed at a paste (V4), so a step repaired against one opens red against its own canonical
 * example ever after, and the badge is the only thing that explains why.
 *
 * Two rules, and the second is the one that would be easy to get backwards.
 */
class ScenarioOriginTest {
    private val expectation =
        Expectation(listOf(FieldExpectation(150, Matcher.Exact("2"))), messageType = "8", mode = MatchMode.OPEN)

    private fun scenario(origin: StepOrigin) =
        Scenario(
            id = "sc-1",
            name = "pasted flow",
            steps =
                listOf(
                    ScenarioStep.Send("35=D|11=X|", stepId = "s1", origin = origin),
                    ScenarioStep.Expect(expectation = expectation, stepId = "s2", origin = origin),
                ),
        )

    @Test
    fun `a pasted step says so on disk, and says so again when it is read back`() {
        val json = ScenarioCodec.toJson(scenario(StepOrigin.PASTED))

        val steps = json["steps"]!!.jsonArray
        assertEquals("pasted", steps[0].jsonObject["origin"]?.jsonPrimitive?.content)
        assertEquals("pasted", steps[1].jsonObject["origin"]?.jsonPrimitive?.content)

        val back = ScenarioCodec.fromJson(json)
        assertTrue(back.steps.all { it.origin == StepOrigin.PASTED }, "provenance survives the round trip")
    }

    /**
     * **Invariant 5: a file that loads today loads identically after every phase.** A live step writes no
     * `origin` key at all — so a scenario captured before this existed does not grow one by being saved, and
     * the bytes on disk are the bytes that were there.
     */
    @Test
    fun `a live step does not grow the key, because the default is not written`() {
        val json = ScenarioCodec.toJson(scenario(StepOrigin.LIVE))
        val steps = json["steps"]!!.jsonArray

        assertNull(steps[0].jsonObject["origin"], "a live step writes nothing: the format is frozen except additively")
        assertNull(steps[1].jsonObject["origin"])
        assertTrue(ScenarioCodec.fromJson(json).steps.all { it.origin == StepOrigin.LIVE }, "and reads back live")
    }

    /**
     * **The direction of the doubt.** An unknown `mode` fails the load, loudly, because it changes *what is
     * checked* (D4). An unknown `origin` changes nothing that is checked — so it must not fail the load. But
     * reading it as LIVE would let a file claim **more** trust than it carries, which is precisely what the
     * badge exists to prevent. So anything this build does not recognise is not-vouched-for.
     */
    @Test
    fun `an origin this build does not know degrades toward less trust, never more`() {
        assertEquals(StepOrigin.LIVE, StepOrigin.from(null), "absent is the default, and files predate the key")
        assertEquals(StepOrigin.LIVE, StepOrigin.from("live"))
        assertEquals(StepOrigin.PASTED, StepOrigin.from("pasted"))
        assertEquals(
            StepOrigin.PASTED,
            StepOrigin.from("synthesised-by-some-later-build"),
            "an origin we cannot vouch for is an origin we do not vouch for",
        )
        assertFalse(StepOrigin.from("anything at all") == StepOrigin.LIVE)
    }
}
