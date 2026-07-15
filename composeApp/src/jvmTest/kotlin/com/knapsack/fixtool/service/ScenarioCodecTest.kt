package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.MatchPredicate
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioResult
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.model.scenario.StepResult
import com.knapsack.fixtool.model.scenario.TagResult
import com.knapsack.fixtool.model.scenario.TagValue
import com.knapsack.fixtool.model.scenario.TemporalKind
import com.knapsack.fixtool.model.scenario.validationError
import com.knapsack.fixtool.model.scenario.withIds
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScenarioCodecTest {
    @Test
    fun `scenario round-trips through json unchanged`() {
        val scenario =
            Scenario(
                id = "sc-1",
                name = "book-a-trade",
                profile = "demo",
                setup = listOf(ScenarioStep.ClearMessages("CLI"), ScenarioStep.ResetSeqNum("CLI", sender = 1, target = 1)),
                steps = listOf(
                    ScenarioStep.Send("35=D|11=\${id=\"ORD-1\"}|", session = "CLI"),
                    ScenarioStep.Wait(session = "CLI", state = "LOGGED_ON", timeoutMs = 5_000),
                    ScenarioStep.Expect(
                        session = "CLI",
                        direction = "in",
                        match = MatchPredicate(messageType = "8", direction = "in", fields = listOf(TagValue(150, "F"))),
                        timeoutMs = 8_000,
                        expectation = Expectation(
                            fields = listOf(
                                FieldExpectation(35, Matcher.Exact("8")),
                                FieldExpectation(39, Matcher.OneOf(listOf("1", "2"))),
                                FieldExpectation(31, Matcher.Numeric(1.2345, 0.00005)),
                                FieldExpectation(60, Matcher.Temporal(TemporalKind.NOW_WITHIN_TOLERANCE, 30)),
                                FieldExpectation(11, Matcher.Reference("\${out.D.11}")),
                                FieldExpectation(448, Matcher.Exact("BROKER-A")),
                                FieldExpectation(448, Matcher.Exact("BROKER-B")),
                            ),
                            messageType = "8",
                            mode = MatchMode.STRICT,
                        ),
                    ),
                ),
                teardown = listOf(ScenarioStep.Send("35=5|", session = "CLI")),
                userTags = listOf("demo"),
            )
        // Reading is what assigns a step its id, so the fixture's blanks are filled on the way back — and by
        // the same deterministic rule, which is why this compares against the identified original rather than
        // ignoring the ids. See ScenarioStepIdTest for what that guarantee is load-bearing for.
        val restored = ScenarioCodec.fromJson(ScenarioCodec.toJson(scenario))
        assertEquals(scenario.withIds(), restored)
    }

    @Test
    fun `junit xml reports a failing step`() {
        val result =
            ScenarioResult(
                scenario = "book-a-trade",
                passed = false,
                steps = listOf(
                    StepResult(0, "send", "steps", passed = true),
                    StepResult(
                        1,
                        "expect",
                        "steps",
                        passed = false,
                        tags = listOf(TagResult(39, "exact 2", "2", "0", passed = false)),
                    ),
                ),
            )
        val xml = ScenarioReport.toJUnitXml(result)
        assertTrue(xml.contains("<testsuite name=\"book-a-trade\" tests=\"2\" failures=\"1\""), xml)
        assertTrue(xml.contains("<failure"), xml)
        assertTrue(xml.contains("tag 39"), xml)
    }

    /**
     * An uncompilable pattern is a **bad assertion**, not a corrupt file.
     *
     * The codec used to refuse it on both sides, and the symmetry was the trap: refusing on encode
     * meant one half-typed pattern in one row failed the save of the entire scenario — every other
     * step, every other assertion, the author's whole session's work — while they were still typing.
     * Refusing on read-only was the bug that symmetry was introduced to fix (a file written that
     * could never be loaded back). Both are the same mistake: treating an editable value as a
     * structural invariant.
     *
     * So neither side rejects. The pattern round-trips verbatim, the row fails the assertion with a
     * reason attached, and the editor says so live while it is being typed. Nobody loses work, and
     * nothing silently passes.
     */
    @Test
    fun `an uncompilable pattern does not fail the save of the whole scenario`() {
        val expectation =
            Expectation(
                fields = listOf(
                    FieldExpectation(11, Matcher.Exact("ORD-1")),
                    FieldExpectation(37, Matcher.Regex("EXEC-[")), // half-typed character class
                    FieldExpectation(39, Matcher.Exact("2")),
                ),
                messageType = "8",
            )

        val json = expectation.fields.map { MatcherCodec.fieldExpectationToJson(it) }

        assertEquals(3, json.size, "one bad pattern must not take the other assertions down with it")
        assertEquals("EXEC-[", json[1]["matcher"]!!.jsonObject["pattern"]!!.jsonPrimitive.content)
    }

    @Test
    fun `and it loads back as exactly what was written`() {
        val bad = Json.parseToJsonElement("""{"type":"regex","pattern":"EXEC-["}""").jsonObject
        assertEquals(Matcher.Regex("EXEC-["), MatcherCodec.parseMatcher(bad))
    }

    @Test
    fun `and the row it is on fails, saying why`() {
        val result =
            ExpectationEvaluator
                .evaluate(wireView(37 to "EXEC-9"), Expectation(listOf(FieldExpectation(37, Matcher.Regex("EXEC-[")))))
                .single()

        assertFalse(result.passed)
        // Not merely "invalid" — *why*. The reader who most needs the reason is the one who cannot open
        // the editor: an agent reading a failed fixtool_assert, or an engineer reading a red CI step.
        assertTrue(result.expected.contains("invalid"), "the row should say what is wrong with it: ${result.expected}")
        assertTrue(result.expected.contains("not a usable pattern"), "and that it is the pattern: ${result.expected}")
        assertTrue(result.expected.contains("Unclosed character class"), "and why: ${result.expected}")
    }

    @Test
    fun `the editor can name the problem before the author saves`() {
        assertNull(Matcher.Regex("EXEC-\\d+").validationError())
        assertTrue(Matcher.Regex("EXEC-[").validationError()!!.contains("EXEC-["))
        assertNull(Matcher.Exact("EXEC-[").validationError(), "only a regex matcher compiles its value")
    }

    @Test
    fun `a valid regex matcher still round-trips`() {
        val ok = Json.parseToJsonElement("""{"type":"regex","pattern":"EXEC-\\d+"}""").jsonObject
        assertEquals(Matcher.Regex("EXEC-\\d+"), MatcherCodec.parseMatcher(ok))
    }

    // ----- the mode ----------------------------------------------------------------------------------

    /**
     * **A mode this build does not know is a refusal, not a default.**
     *
     * The codec read `strict = (mode == "strict")` and took everything else as OPEN — so a typo, or a mode
     * written by a later build, silently *loosened* the expectation: STRICT became OPEN, every tag the venue
     * added stopped being reported, and the scenario went on passing while checking less than it said. That
     * is the quietest possible way to lose coverage, and it is exactly the "silently degrade" this format
     * refuses to do anywhere else.
     */
    @Test
    fun `a mode the build does not know fails the load, by name`() {
        val strict = ScenarioCodec.expectationToJson(Expectation(emptyList(), mode = MatchMode.STRICT))
        assertEquals("strict", strict["mode"]!!.jsonPrimitive.content)

        val typo = Json.parseToJsonElement("""{"mode":"stict","fields":[]}""").jsonObject
        val why = assertFailsWith<IllegalArgumentException> { ScenarioCodec.expectationFromJson(typo) }
        assertTrue("stict" in why.message!!, "the refusal must name the mode it could not read: ${why.message}")

        val future = Json.parseToJsonElement("""{"mode":"gumtree","fields":[]}""").jsonObject
        assertFailsWith<IllegalArgumentException> { ScenarioCodec.expectationFromJson(future) }
    }

    /** An absent mode is not an unknown one: it is the model's default, and old files are entitled to it. */
    @Test
    fun `an expectation with no mode at all is OPEN, as it always was`() {
        val noMode = Json.parseToJsonElement("""{"fields":[]}""").jsonObject
        assertEquals(MatchMode.OPEN, ScenarioCodec.expectationFromJson(noMode).mode)
    }

    // ----- the match predicate ---------------------------------------------------------------------

    /**
     * A hand-edited `match` field with no `tag` is a **malformed file, not a crash**. It used to reach
     * `!!` and surface as a bare NullPointerException — swallowed generically upstream into an
     * uninformative "Cannot load scenario: null" — instead of the by-name refusal the codec gives
     * everywhere else. A missing (or non-integer) key now fails the load loudly, saying which key.
     */
    @Test
    fun `a match predicate field with no tag fails the load, by name`() {
        val json = Json.parseToJsonElement(
            """{"id":"sc-1","name":"n","steps":[{"type":"wait","match":{"fields":[{"value":"F"}]}}]}""",
        ).jsonObject
        val why = assertFailsWith<IllegalArgumentException> { ScenarioCodec.fromJson(json) }
        assertTrue("tag" in why.message!!, "the refusal must name the key it could not read: ${why.message}")
    }

    @Test
    fun `a match predicate field with no value fails the load, by name`() {
        val json = Json.parseToJsonElement(
            """{"id":"sc-1","name":"n","steps":[{"type":"wait","match":{"fields":[{"tag":150}]}}]}""",
        ).jsonObject
        val why = assertFailsWith<IllegalArgumentException> { ScenarioCodec.fromJson(json) }
        assertTrue("value" in why.message!!, "the refusal must name the key it could not read: ${why.message}")
    }

    /** An empty string is a value; only an *absent* key is a refusal. An empty `value` still loads. */
    @Test
    fun `a match predicate field with an empty-string value still loads`() {
        val json = Json.parseToJsonElement(
            """{"id":"sc-1","name":"n","steps":[{"type":"wait","match":{"fields":[{"tag":150,"value":""}]}}]}""",
        ).jsonObject
        val wait = ScenarioCodec.fromJson(json).steps.single() as ScenarioStep.Wait
        assertEquals(listOf(TagValue(150, "")), wait.match!!.fields)
    }
}
