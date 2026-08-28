package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.scenario.BindScope
import com.knapsack.fixtool.model.scenario.ExampleRow
import com.knapsack.fixtool.model.scenario.Examples
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.MatchOp
import com.knapsack.fixtool.model.scenario.MatchPredicate
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioResult
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.model.scenario.StepResult
import com.knapsack.fixtool.model.scenario.TagResult
import com.knapsack.fixtool.model.scenario.TagValue
import com.knapsack.fixtool.model.scenario.TemporalKind
import com.knapsack.fixtool.model.scenario.TrafficMode
import com.knapsack.fixtool.model.scenario.validationError
import com.knapsack.fixtool.model.scenario.withIds
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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

    /**
     * `bindAs` is additive in exactly the `stepId`/`origin` way: written only where set, so a row that
     * does not capture — and every pre-capture file — keeps its exact old shape.
     */
    @Test
    fun `a capture round-trips, and only the capturing row carries the key`() {
        val scenario =
            Scenario(
                id = "sc-b",
                name = "capture",
                steps =
                    listOf(
                        ScenarioStep.Expect(
                            expectation =
                                Expectation(
                                    fields =
                                        listOf(
                                            FieldExpectation(37, Matcher.Presence, bindAs = "oid"),
                                            FieldExpectation(39, Matcher.Exact("2")),
                                        ),
                                    messageType = "8",
                                ),
                        ),
                    ),
            )
        val json = ScenarioCodec.toJson(scenario)
        assertEquals(scenario.withIds(), ScenarioCodec.fromJson(json))
        val text = json.toString()
        assertEquals(1, Regex("\"bindAs\"").findAll(text).count(), "written only where set: $text")
    }

    /** A blank name is refused by name — the author meant to capture something, and silence loses it. */
    @Test
    fun `a blank capture name fails the load, by name`() {
        val valid =
            ScenarioCodec.toJson(
                Scenario(
                    id = "sc-b",
                    name = "capture",
                    steps =
                        listOf(
                            ScenarioStep.Expect(
                                expectation =
                                    Expectation(
                                        fields = listOf(FieldExpectation(37, Matcher.Presence, bindAs = "oid")),
                                        messageType = "8",
                                    ),
                            ),
                        ),
                ),
            )
        val blanked =
            valid.toString().replace(Regex("\"bindAs\"\\s*:\\s*\"oid\""), "\"bindAs\": \"\"")
        val e =
            assertFailsWith<IllegalArgumentException> {
                ScenarioCodec.fromJson(Json.parseToJsonElement(blanked).jsonObject)
            }
        assertTrue("bindAs" in (e.message ?: ""), "the refusal names the key: ${e.message}")
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

    // ------------------------------------------------------------------ range

    @Test
    fun `a one-sided range round-trips, writing only what it means`() {
        val matcher = Matcher.Range(min = 10_000.0, minInclusive = false)
        val json = MatcherCodec.matcherToJson(matcher)

        assertEquals("range", json["type"]!!.jsonPrimitive.content)
        assertEquals(false, json["minInclusive"]!!.jsonPrimitive.content.toBoolean())
        assertTrue("max" !in json, "an open upper bound must not be written as a value")
        assertTrue("maxInclusive" !in json, "nor its default flag")
        assertEquals(matcher, MatcherCodec.parseMatcher(json))
    }

    /** Omitted flags mean inclusive, so `>=`/`<=` stay the terse form on disk. */
    @Test
    fun `omitted inclusivity parses as inclusive`() {
        val json = Json.parseToJsonElement("""{"type":"range","min":1.08,"max":1.09}""").jsonObject

        assertEquals(Matcher.Range(min = 1.08, max = 1.09), MatcherCodec.parseMatcher(json))
    }

    /**
     * Two ranges that assert nothing, both reachable by clearing a field in the editor. Carried by the
     * codec unharmed — a bad assertion is not a corrupt scenario — and reported by validationError, the
     * same bargain the uncompilable regex above gets.
     */
    @Test
    fun `a range that asserts nothing is carried, and named`() {
        val unbounded = Json.parseToJsonElement("""{"type":"range"}""").jsonObject
        assertEquals(Matcher.Range(), MatcherCodec.parseMatcher(unbounded))
        assertTrue(Matcher.Range().validationError()!!.contains("no bound"))

        val inverted = Matcher.Range(min = 10.0, max = 1.0)
        assertEquals(inverted, MatcherCodec.parseMatcher(MatcherCodec.matcherToJson(inverted)))
        assertTrue(inverted.validationError()!!.contains("nothing can match"))
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

    // ----- op and occurrence (Decision 6: disambiguating same-type messages) ------------------------

    /**
     * The default EQ constraint must write exactly `{tag, value}` — no `op` key — so a scenario file written
     * before this feature round-trips byte-identical, and old FixTools keep reading new EQ constraints.
     */
    @Test
    fun `an EQ constraint still writes just tag and value, byte-identical to before ops existed`() {
        val json = ScenarioCodec.predicateToJson(MatchPredicate(messageType = "8", fields = listOf(TagValue(150, "F"))))
        val field = json["fields"]!!.jsonArray.single().jsonObject
        assertEquals(setOf("tag", "value"), field.keys, "an EQ field must not grow an 'op' key: $field")
        assertEquals("150", field["tag"]!!.jsonPrimitive.content)
        assertEquals("F", field["value"]!!.jsonPrimitive.content)
    }

    @Test
    fun `present, absent and occurrence round-trip`() {
        val p =
            MatchPredicate(
                messageType = "8",
                occurrence = 2,
                fields = listOf(TagValue(131, "", MatchOp.PRESENT), TagValue(41, "", MatchOp.ABSENT)),
            )
        assertEquals(p, ScenarioCodec.predicateFromJson(ScenarioCodec.predicateToJson(p)))
    }

    /** A present/absent constraint tests existence, so it is complete without a `value`. */
    @Test
    fun `a present constraint needs no value`() {
        val json = Json.parseToJsonElement("""{"fields":[{"tag":131,"op":"present"}]}""").jsonObject
        assertEquals(listOf(TagValue(131, "", MatchOp.PRESENT)), ScenarioCodec.predicateFromJson(json).fields)
    }

    /** Same stance as an unknown mode: an op this build cannot read fails the load, not silently becomes EQ. */
    @Test
    fun `a match op the build does not know fails the load, by name`() {
        val json = Json.parseToJsonElement("""{"fields":[{"tag":131,"op":"presentish"}]}""").jsonObject
        val why = assertFailsWith<IllegalArgumentException> { ScenarioCodec.predicateFromJson(json) }
        assertTrue("presentish" in why.message!!, "the refusal must name the op: ${why.message}")
    }

    @Test
    fun `a non-positive occurrence fails the load`() {
        val zero = Json.parseToJsonElement("""{"messageType":"8","occurrence":0,"fields":[]}""").jsonObject
        assertFailsWith<IllegalArgumentException> { ScenarioCodec.predicateFromJson(zero) }
    }

    // ----- the version ------------------------------------------------------------------------------

    /**
     * **A file written by a NEWER FixTool is refused, not silently mis-read.**
     *
     * A future build may add a step type, a mode, or a matcher this build does not know. Loading such a
     * file anyway would drop what it cannot parse and pass while checking less than the author wrote — the
     * same silent-degrade the mode refusal exists to prevent. So a version above what this build understands
     * fails the load loudly, by name, telling the reader to upgrade.
     */
    @Test
    fun `a scenario from a newer FixTool fails the load, by name`() {
        val next = ScenarioCodec.CURRENT_SCENARIO_VERSION + 1
        val future = Json.parseToJsonElement("""{"id":"sc-1","name":"n","version":$next}""").jsonObject
        val why = assertFailsWith<IllegalArgumentException> { ScenarioCodec.fromJson(future) }
        assertTrue("$next" in why.message!!, "the refusal must name the version: ${why.message}")
        assertTrue("upgrade" in why.message!!, "and tell the reader what to do: ${why.message}")
    }

    /** A scenario at the version this build writes loads unchanged. */
    @Test
    fun `a scenario at the current version loads cleanly`() {
        val current = Json.parseToJsonElement(
            """{"id":"sc-1","name":"n","version":${ScenarioCodec.CURRENT_SCENARIO_VERSION}}""",
        ).jsonObject
        assertEquals(ScenarioCodec.CURRENT_SCENARIO_VERSION, ScenarioCodec.fromJson(current).version)
    }

    /** An absent version is not a from-the-future file: it defaults to 1, and old files load as they always did. */
    @Test
    fun `a scenario with no version key still loads`() {
        val noVersion = Json.parseToJsonElement("""{"id":"sc-1","name":"n"}""").jsonObject
        assertEquals(1, ScenarioCodec.fromJson(noVersion).version)
    }

    // ----- createdAt ---------------------------------------------------------------------------------

    /**
     * Additive and default-omitting: a scenario with no birth time grows no key — so a file older than the
     * field round-trips byte-identical — and one with a birth time writes it and reads it straight back.
     */
    @Test
    fun `createdAt is written only when set, and round-trips`() {
        val without = ScenarioCodec.toJson(Scenario(id = "c", name = "n"))
        assertFalse("createdAt" in without.keys, "a null createdAt must not grow a key: $without")

        val withIt = ScenarioCodec.toJson(Scenario(id = "c", name = "n", createdAt = 1_700_000_000_000L))
        assertTrue("createdAt" in withIt.keys)
        assertEquals(1_700_000_000_000L, ScenarioCodec.fromJson(withIt).createdAt)
    }

    /**
     * A new file's createdAt key does not trip the version gate — it loads at version 1, which is what lets
     * the already-released build (which knows nothing of the key) keep opening files this build writes.
     */
    @Test
    fun `a file carrying createdAt loads at version 1`() {
        val obj = Json.parseToJsonElement("""{"id":"c","name":"n","createdAt":1700000000000}""").jsonObject
        val loaded = ScenarioCodec.fromJson(obj)
        assertEquals(1, loaded.version)
        assertEquals(1_700_000_000_000L, loaded.createdAt)
    }

    /** Absent createdAt is unknown, not an error: it reads as null, and the rail sorts such a file by mtime. */
    @Test
    fun `a file with no createdAt reads as null`() {
        assertNull(ScenarioCodec.fromJson(Json.parseToJsonElement("""{"id":"c","name":"n"}""").jsonObject).createdAt)
    }

    // ----- muted -------------------------------------------------------------------------------------

    /** Muted survives the round trip on every step kind that can carry it. */
    @Test
    fun `muted round-trips through json`() {
        val scenario =
            Scenario(
                id = "sc-m",
                name = "parked",
                steps = listOf(
                    ScenarioStep.Send("35=D|", session = "s", muted = true),
                    ScenarioStep.Wait(session = "s", state = "LOGGED_ON", muted = true),
                    ScenarioStep.Expect(session = "s", expectation = Expectation(emptyList()), muted = true),
                    ScenarioStep.ClearMessages("s", muted = true),
                    ScenarioStep.ResetSeqNum("s", sender = 1, target = 1, muted = true),
                ),
            ).withIds()
        val back = ScenarioCodec.fromJson(ScenarioCodec.toJson(scenario))
        assertTrue(back.steps.all { it.muted }, "muted must survive save → load on every step kind")
    }

    /**
     * Default-omitting, the same bargain as `stepId` and `origin`: a scenario that never muted anything
     * writes no `muted` key, so a file that loads today writes byte-identically after this feature.
     */
    @Test
    fun `an unmuted step writes no muted key, and an absent key reads unmuted`() {
        val scenario = Scenario(id = "sc-m", name = "n", steps = listOf(ScenarioStep.Send("35=D|", session = "s"))).withIds()
        val step = ScenarioCodec.toJson(scenario)["steps"]!!.jsonArray.single().jsonObject
        assertNull(step["muted"], "the wire format is frozen except additively")
        assertFalse(ScenarioCodec.fromJson(ScenarioCodec.toJson(scenario)).steps.single().muted)
    }

    /** The sixth step kind, through the file and back — additive, and it carries only a session. */
    @Test
    fun `clear-order-book round-trips through json`() {
        val scenario =
            Scenario(id = "sc-b", name = "n", setup = listOf(ScenarioStep.ClearOrderBook("ACC")), steps = listOf(ScenarioStep.Send("35=D|", session = "s")))
        val json = ScenarioCodec.toJson(scenario)
        assertEquals("clearOrderBook", json["setup"]!!.jsonArray.single().jsonObject["type"]!!.jsonPrimitive.content)
        // Ids are minted on the way through, so compare what the file actually carries: kind and session.
        val back = ScenarioCodec.fromJson(json).setup.single()
        assertTrue(back is ScenarioStep.ClearOrderBook, "the sixth kind must survive the round trip: $back")
        assertEquals("ACC", back.session)
    }

    // ----- the outline's table ------------------------------------------------------------------------

    @Test
    fun `an examples table round-trips, and a parked row stays parked`() {
        val scenario =
            Scenario(
                id = "sc-x",
                name = "n",
                steps = listOf(ScenarioStep.Send("35=D|55=\${symbol}|", session = "s")),
                examples =
                    Examples(
                        columns = listOf("symbol", "qty"),
                        rows =
                            listOf(
                                ExampleRow("EUR/USD partial", mapOf("symbol" to "EUR/USD", "qty" to "100")),
                                ExampleRow("parked", mapOf("symbol" to "USD/JPY"), muted = true),
                            ),
                    ),
            )

        val back = assertNotNull(ScenarioCodec.fromJson(ScenarioCodec.toJson(scenario)).examples)

        assertEquals(listOf("symbol", "qty"), back.columns)
        assertEquals(listOf("EUR/USD partial", "parked"), back.rows.map { it.name })
        assertEquals(mapOf("symbol" to "EUR/USD", "qty" to "100"), back.rows[0].values)
        assertTrue(back.rows[1].muted)
        assertEquals(listOf("EUR/USD partial"), back.live.map { it.name })
    }

    /** The columns are the contract: a cell under a name no column declares is nobody's variable. */
    @Test
    fun `a cell for a column the table does not declare is dropped on load`() {
        val json =
            Json.parseToJsonElement(
                """{"id":"sc-x","name":"n","examples":{"columns":["symbol"],
                   "rows":[{"name":"r","values":{"symbol":"EUR/USD","stray":"nobody-declared-me"}}]}}""",
            ).jsonObject

        val row = ScenarioCodec.fromJson(json).examples!!.rows.single()

        assertEquals(mapOf("symbol" to "EUR/USD"), row.values)
    }

    /** Additive, the same bargain the rest of the format keeps. */
    @Test
    fun `a scenario with no table grows no key`() {
        val scenario = Scenario(id = "sc-x", name = "n", steps = listOf(ScenarioStep.Send("35=D|", session = "s")))
        assertNull(ScenarioCodec.toJson(scenario)["examples"], "the wire format is frozen except additively")
    }

    // ----- traffic -----------------------------------------------------------------------------------

    @Test
    fun `strict traffic round-trips through json`() {
        val scenario =
            Scenario(id = "sc-t", name = "n", steps = listOf(ScenarioStep.Send("35=D|", session = "s")), traffic = TrafficMode.STRICT)
        val json = ScenarioCodec.toJson(scenario)
        assertEquals("strict", json["traffic"]!!.jsonPrimitive.content)
        assertEquals(TrafficMode.STRICT, ScenarioCodec.fromJson(json).traffic)
    }

    /** The stepId/origin/muted bargain again: a scenario that never went strict never grows the key. */
    @Test
    fun `open traffic writes no key, and an absent key reads open`() {
        val scenario = Scenario(id = "sc-t", name = "n", steps = listOf(ScenarioStep.Send("35=D|", session = "s")))
        assertNull(ScenarioCodec.toJson(scenario)["traffic"], "the wire format is frozen except additively")
        val noKey = Json.parseToJsonElement("""{"id":"sc-t","name":"n"}""").jsonObject
        assertEquals(TrafficMode.OPEN, ScenarioCodec.fromJson(noKey).traffic)
    }

    /**
     * The `modeFrom` stance at the stream level: reading an unknown traffic mode as OPEN would be the
     * quietest possible way to weaken a scenario — "and nothing else" silently stops being checked while
     * every run keeps passing. So it fails the load, by name.
     */
    @Test
    fun `a traffic mode the build does not know fails the load, by name`() {
        val typo = Json.parseToJsonElement("""{"id":"sc-t","name":"n","traffic":"stric"}""").jsonObject
        val why = assertFailsWith<IllegalArgumentException> { ScenarioCodec.fromJson(typo) }
        assertTrue("stric" in why.message!!, "the refusal must name the mode it could not read: ${why.message}")
    }

    // ----- binding -----------------------------------------------------------------------------------

    @Test
    fun `this_run binding round-trips through json`() {
        val scenario =
            Scenario(
                id = "sc-b",
                name = "n",
                steps = listOf(ScenarioStep.Send("35=D|", session = "s")),
                binding = BindScope.THIS_RUN,
            )
        val json = ScenarioCodec.toJson(scenario)
        assertEquals("this_run", json["binding"]!!.jsonPrimitive.content)
        assertEquals(BindScope.THIS_RUN, ScenarioCodec.fromJson(json).binding)
    }

    /** The same additive bargain: a scenario that never asked for fresh-only never grows the key. */
    @Test
    fun `any binding writes no key, and an absent key reads any`() {
        val scenario = Scenario(id = "sc-b", name = "n", steps = listOf(ScenarioStep.Send("35=D|", session = "s")))
        assertNull(ScenarioCodec.toJson(scenario)["binding"], "the wire format is frozen except additively")
        val noKey = Json.parseToJsonElement("""{"id":"sc-b","name":"n"}""").jsonObject
        assertEquals(BindScope.ANY, ScenarioCodec.fromJson(noKey).binding)
    }

    /**
     * Reading an unknown binding scope as ANY is how a scenario that asked to bind only this run's traffic
     * quietly goes back to binding anything — and passes on a reply to a run that finished yesterday. The
     * quiet weakening is the whole reason this refuses.
     */
    @Test
    fun `a binding scope the build does not know fails the load, by name`() {
        val typo = Json.parseToJsonElement("""{"id":"sc-b","name":"n","binding":"thisrunn"}""").jsonObject
        val why = assertFailsWith<IllegalArgumentException> { ScenarioCodec.fromJson(typo) }
        assertTrue("thisrunn" in why.message!!, "the refusal must name what it could not read: ${why.message}")
    }
}
