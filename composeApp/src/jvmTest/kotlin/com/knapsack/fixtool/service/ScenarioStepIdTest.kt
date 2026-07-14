package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.model.scenario.withIds
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * **A step's identity, and the one property it has to have: two independent reads of the same file must
 * agree about it.**
 *
 * `reconcileRoute` asks whether the step that failed is still the step on disk. It answers by loading the
 * scenario a *second* time and comparing. If an id-less file were given fresh random ids on each load, the
 * two copies would agree about nothing, and every failure on a scenario written before ids existed would be
 * refused — by the mechanism added to *stop* refusing so much. So the assignment is deterministic, and that
 * is what these tests pin.
 */
class ScenarioStepIdTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir =
            File.createTempFile("fixtool-stepid", "").apply {
                delete()
                mkdirs()
            }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** A scenario as the codec would have written it before step ids existed: no id on any step. */
    private fun legacyScenario(id: String = "sc-legacy"): Scenario =
        Scenario(
            id = id,
            name = "rfq flow",
            setup = listOf(ScenarioStep.ClearMessages("CLI")),
            steps =
                listOf(
                    ScenarioStep.Send("35=D|11=X|", session = "CLI"),
                    ScenarioStep.Expect(
                        session = "CLI",
                        expectation = Expectation(fields = listOf(FieldExpectation(150, Matcher.Exact("F"))), messageType = "8"),
                    ),
                ),
            teardown = listOf(ScenarioStep.Send("35=5|", session = "CLI")),
        )

    private fun withoutStepIds(scenario: JsonObject): JsonObject =
        buildJsonObject {
            scenario.forEach { (key, value) ->
                if (key in setOf("setup", "steps", "teardown")) {
                    put(
                        key,
                        buildJsonArray {
                            value.jsonArray.forEach { step ->
                                add(JsonObject(step.jsonObject.filterKeys { it != "stepId" }))
                            }
                        },
                    )
                } else {
                    put(key, value)
                }
            }
        }

    // ----- the deterministic assignment -----------------------------------------------------------------

    /**
     * THE ONE THAT MATTERS. Two loads, two objects, one answer — otherwise `reconcileRoute` compares a
     * scenario against itself and concludes it has changed.
     */
    @Test
    fun `two loads of the same id-less file agree about every step`() {
        val json = ScenarioCodec.toJson(legacyScenario())

        val first = ScenarioCodec.fromJson(json)
        val second = ScenarioCodec.fromJson(json)

        assertEquals(first.steps.map { it.stepId }, second.steps.map { it.stepId })
        assertEquals(first, second)
        assertTrue(first.steps.all { it.stepId.isNotBlank() }, "a loaded step is always identified")
    }

    /** Phases index independently, so setup step 1 and steps step 1 must not be the same step. */
    @Test
    fun `a step id is unique across the whole scenario, not just its phase`() {
        val identified = legacyScenario().withIds()
        val all = identified.setup + identified.steps + identified.teardown

        assertEquals(all.size, all.map { it.stepId }.toSet().size, "every step has its own id")
    }

    /** Two scenarios cannot hand the same step id to different steps — the scenario's own id is in the seed. */
    @Test
    fun `the same step in two different scenarios gets two different ids`() {
        val a = legacyScenario(id = "sc-a").withIds()
        val b = legacyScenario(id = "sc-b").withIds()

        assertNotEquals(a.steps[0].stepId, b.steps[0].stepId)
    }

    /**
     * An id that addresses two steps addresses neither: a hand-copied step, or a hand-edited file, must not
     * be able to make the reconcile route pick between two candidates.
     */
    @Test
    fun `a duplicated id is re-minted, so no id ever names two steps`() {
        val scenario =
            Scenario(
                id = "sc-dup",
                name = "copy-paste",
                steps =
                    listOf(
                        ScenarioStep.Send("35=D|", stepId = "same"),
                        ScenarioStep.Send("35=F|", stepId = "same"),
                    ),
            )

        val fixed = scenario.withIds()

        val ids = fixed.steps.map { it.stepId }
        assertEquals("same", ids[0], "the first claimant keeps the id")
        assertNotEquals("same", ids[1])
        assertEquals(2, ids.distinct().size)
    }

    /** An id the file gave us is the step's own, and re-identifying is not allowed to invent a new one. */
    @Test
    fun `an id already on a step survives every normalization`() {
        val once = legacyScenario().withIds()

        assertEquals(once, once.withIds())
        assertEquals(once, once.withIds().withIds())
    }

    // ----- the wire format ------------------------------------------------------------------------------

    /**
     * The format is frozen except for this one additive key. A pre-id file loads, gets its ids, and saves
     * back **byte-identical apart from the ids** — nothing else about it is rewritten on the way through.
     */
    @Test
    fun `a pre-id file gains ids on save, and nothing else changes`() {
        val legacy = ScenarioCodec.toJson(legacyScenario())
        assertTrue(
            legacy["steps"]!!.jsonArray.none { "stepId" in it.jsonObject },
            "the fixture must actually be a pre-id file, or this test proves nothing",
        )

        val loaded = ScenarioCodec.fromJson(legacy)
        val saved = ScenarioCodec.toJson(loaded)

        assertTrue(saved["steps"]!!.jsonArray.all { "stepId" in it.jsonObject }, "saving writes the ids")
        assertEquals(legacy, withoutStepIds(saved), "and rewrites nothing else")
    }

    /** Round-trip: an id written to disk is the id read back, so the identity outlives the process. */
    @Test
    fun `a saved id is the id that loads back`() {
        val service = ScenarioService(customDir = dir.absolutePath)
        val scenario = legacyScenario()
        assertTrue(service.save(scenario))

        val loaded = service.load(scenario.id)!!
        assertEquals(scenario.withIds().steps.map { it.stepId }, loaded.steps.map { it.stepId })
        assertEquals(loaded, service.load(scenario.id))
    }

    /** A blank id never reaches disk: a step the author added by hand is identified by the act of saving. */
    @Test
    fun `saving assigns an id to a step that has none`() {
        val service = ScenarioService(customDir = dir.absolutePath)
        val scenario = legacyScenario().withIds()
        val handAdded = scenario.copy(steps = scenario.steps + ScenarioStep.Send("35=F|", session = "CLI"))
        assertTrue(handAdded.steps.last().stepId.isBlank(), "the fixture's new step must start unidentified")

        assertTrue(service.save(handAdded))

        val loaded = service.load(scenario.id)!!
        assertTrue(loaded.steps.all { it.stepId.isNotBlank() })
        // ...and the steps that already had ids kept them, so saving does not re-address the scenario.
        val keptIds = loaded.steps.dropLast(1)
        assertEquals(scenario.steps.map { it.stepId }, keptIds.map { it.stepId })
    }

    /**
     * The runner names the step in every verdict, so a report — and the reconcile route reading it — can
     * still resolve the failure after the scenario has moved on.
     *
     * The scenario handed over here is identified; the runner also identifies whatever it is given, so an
     * inline scenario from the control surface reports the ids its saved counterpart would carry.
     */
    @Test
    fun `every step result carries the id of the step that produced it`() {
        val scenario = legacyScenario() // deliberately NOT pre-identified: the runner must do it
        var clock = 0L
        // A clock that always moves, so the Expect's timeout is reached rather than waited out.
        val runner = ScenarioRunner(SilentHost(), pollMs = 1, now = { clock += 1_000; clock })

        val result = runner.run(scenario)

        val expected = scenario.withIds()
        assertTrue(result.steps.all { it.stepId != null }, "no verdict is anonymous")
        assertEquals(expected.setup.first().stepId, result.steps.first().stepId)
        assertEquals(expected.steps.first().stepId, result.steps[1].stepId)
        assertEquals(expected.steps[1].stepId, result.steps[2].stepId, "the failing Expect names itself")
    }

    /** A host with no venue behind it: sends succeed, nothing ever arrives, so the Expect times out. */
    private class SilentHost : ScenarioHost {
        override fun resolve(raw: String, scope: MutableMap<String, String>, session: String?) = raw

        override fun send(raw: String, session: String?) = true

        override fun messages(session: String?) = emptyList<FixMessage>()

        override fun connectionState(session: String?) = "LOGGED_ON"

        override fun referenceResolver(session: String?, scope: Map<String, String>): (String) -> String? = { null }

        override fun view(message: FixMessage): MessageView? = null

        override fun clearMessages(session: String?) = true

        override fun resetSeqNum(session: String?, sender: Int?, target: Int?) = true

        override fun sleep(ms: Long) = Unit
    }
}
