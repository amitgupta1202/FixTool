package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.MatchOp
import com.knapsack.fixtool.model.scenario.MatchPredicate
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.model.scenario.ScenarioVariable
import com.knapsack.fixtool.model.scenario.StepResult
import com.knapsack.fixtool.model.scenario.TagValue
import com.knapsack.fixtool.model.scenario.withIds
import org.junit.Test
import quickfix.Message
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the deterministic [ScenarioRunner] driven by a fake [ScenarioHost]. A virtual clock
 * (advanced by the host's no-op sleep) keeps timeout paths instant and deterministic.
 */
class ScenarioRunnerTest {
    @Test
    fun `send then expect passes and records the sent message`() {
        val host = FakeHost()
        host.inbox += incoming("8", mapOf(35 to "8", 150 to "0"))
        val scenario =
            scenario(
                ScenarioStep.Send("35=D|11=ORD|", session = "s"),
                expect("8", FieldExpectation(35, Matcher.Exact("8")), FieldExpectation(150, Matcher.Exact("0"))),
            )
        val result = run(host, scenario)
        assertTrue(result.passed, "scenario should pass: ${result.steps}")
        assertEquals(listOf("35=D|11=ORD|"), host.sent)
    }

    /**
     * A matched message whose wire order we do not have cannot be judged, and the step says so.
     *
     * The tempting alternatives are both worse than a red. Asserting anyway means judging against
     * QuickFIX's re-serialisation — ascending tags, repeating groups moved to the end — which is a message
     * no venue sent, so the verdict is about nothing. Skipping the order check and passing the values means
     * a step that reports green while silently doing less than it says.
     *
     * So: it fails, and the text blames FixTool rather than the counterparty, because a red that sends an
     * engineer hunting a venue bug that does not exist is barely better than a green.
     */
    @Test
    fun `a matched message with no wire order fails the step, and blames the tool`() {
        val host = FakeHost()
        val reply = incoming("8", mapOf(35 to "8", 39 to "2"))
        host.inbox += reply
        host.noWireOrder += reply

        val reported = mutableListOf<Pair<FixMessage, StepResult>>()
        val result =
            runRecordingVerdicts(host, scenario(expect("8", FieldExpectation(39, Matcher.Exact("2")))), reported)

        assertFalse(result.passed, "an expectation that cannot be evaluated has not passed")

        // Reported through the same channel as any other verdict. This is what tints the matched message red
        // in the grid and gives the reconcile deep-link a message to show; an early return that skipped it
        // left the one step that failed as the one message the grid does not mark — red in the report, clean
        // on the surface the tester actually looks at.
        assertEquals(1, reported.size, "the failing step must report the message it bound to")
        assertEquals(reply, reported.single().first)
        assertFalse(reported.single().second.passed, "and it must report it as failed")
        val step = result.steps.single()
        val detail = step.detail.orEmpty()
        assertTrue(
            detail.contains("no wire bytes", ignoreCase = true) && detail.contains("FixTool"),
            "the failure must name the tool as the cause, not the venue: '$detail'",
        )
        assertTrue(
            step.tags.isEmpty(),
            "no per-tag verdicts may be reported: every one of them would be a claim about a message " +
                "whose field order we invented",
        )
    }

    @Test
    fun `persistent scope carries a variable across separate sends`() {
        val host = FakeHost()
        val scenario =
            scenario(
                ScenarioStep.Send("11=\${id = \"ORD-1\"}", session = "s"),
                ScenarioStep.Send("11=\${id}", session = "s"),
            )
        run(host, scenario)
        // The variable assigned in step 1 resolves identically in step 2 — proves the runner owns a
        // persistent scope (the engine's per-send map would have lost it).
        assertEquals(listOf("11=ORD-1", "11=ORD-1"), host.sent)
    }

    @Test
    fun `consumed cursor walks successive same-type messages (partial fills)`() {
        val host = FakeHost()
        host.inbox += incoming("8", mapOf(35 to "8", 39 to "1")) // partial
        host.inbox += incoming("8", mapOf(35 to "8", 39 to "2")) // fill
        val scenario =
            scenario(
                expect("8", FieldExpectation(39, Matcher.Presence)),
                expect("8", FieldExpectation(39, Matcher.Presence)),
            )
        val result = run(host, scenario)
        assertTrue(result.passed)
        // First Expect binds to the partial, the second to the fill — not the same message twice.
        assertEquals("1", result.steps[0].tags.single { it.tag == 39 }.actual)
        assertEquals("2", result.steps[1].tags.single { it.tag == 39 }.actual)
    }

    @Test
    fun `expect times out cleanly when no message arrives`() {
        val host = FakeHost()
        val scenario = scenario(expectWith(timeoutMs = 50, FieldExpectation(35, Matcher.Exact("8"))))
        val result = run(host, scenario)
        assertFalse(result.passed)
        assertTrue(result.steps.single().detail!!.contains("no 8"), "detail: ${result.steps.single().detail}")
    }

    @Test
    fun `failure aborts remaining steps but teardown still runs`() {
        val host = FakeHost()
        val scenario =
            Scenario(
                id = "x",
                name = "abort",
                steps = listOf(
                    expectWith(timeoutMs = 30, FieldExpectation(35, Matcher.Exact("8"))), // fails (empty inbox)
                    ScenarioStep.Send("SHOULD-NOT-SEND", session = "s"),
                ),
                teardown = listOf(ScenarioStep.Send("TEARDOWN", session = "s")),
            )
        val result = run(host, scenario)
        assertFalse(result.passed)
        assertEquals(listOf("TEARDOWN"), host.sent, "the second step must be skipped; teardown must run")
    }

    @Test
    fun `a failing teardown does not flip an otherwise-green verdict, but stays in the report`() {
        val host = FakeHost()
        host.clearOk = false // the teardown ClearMessages will fail (its session vanished mid-run)
        val scenario =
            Scenario(
                id = "x",
                name = "teardown-fails",
                steps = listOf(ScenarioStep.Send("35=D|11=ORD|", session = "s")),
                teardown = listOf(ScenarioStep.ClearMessages(session = "s")),
            )
        val result = run(host, scenario)
        // Every setup+step passed; teardown is best-effort cleanup and must not decide the verdict.
        assertTrue(result.passed, "a green run must stay green when only teardown fails: ${result.steps}")
        // But the teardown failure is still reported, not swallowed — the report shows cleanup went wrong.
        val teardown = result.steps.single { it.phase == "teardown" }
        assertFalse(teardown.passed, "the failing teardown step must still be present and marked failed")
    }

    @Test
    fun `preflight fails fast by name when a session is missing`() {
        val host = FakeHost()
        host.stateOf = { session -> if (session == "gone") null else "LOGGED_ON" }
        val scenario = scenario(ScenarioStep.Send("35=D|", session = "gone"))
        val result = run(host, scenario)
        assertFalse(result.passed)
        val step = result.steps.single()
        assertEquals("preflight", step.kind)
        assertTrue(step.detail!!.contains("session 'gone' not found"), "detail: ${step.detail}")
        assertTrue(host.sent.isEmpty(), "nothing may run after a preflight failure")
    }

    @Test
    fun `preflight requires LOGGED_ON for sessions the scenario sends on`() {
        val host = FakeHost()
        host.stateOf = { "DISCONNECTED" }
        val result = run(host, scenario(ScenarioStep.Send("35=D|", session = "s")))
        assertFalse(result.passed)
        assertTrue(result.steps.single().detail!!.contains("not LOGGED_ON"), "detail: ${result.steps.single().detail}")
    }

    @Test
    fun `an empty scenario fails preflight instead of reporting a vacuous green`() {
        val host = FakeHost()
        // No steps at all: `results.all { it.passed }` would be vacuously true and report passed=true,
        // a green gate that did and checked nothing. The runner must reject it up front.
        val result = run(host, Scenario(id = "x", name = "empty"))
        assertFalse(result.passed, "an empty scenario must not report passed")
        val step = result.steps.single()
        assertEquals("preflight", step.kind)
        assertTrue(step.detail!!.contains("no steps"), "detail: ${step.detail}")
    }

    @Test
    fun `a Send-only scenario is not rejected by the empty-scenario guard`() {
        val host = FakeHost()
        // A scenario that only sends does real work (a load driver, or a scope fixture that asserts on what
        // was sent) — it must NOT be rejected for having no Expect. Only a truly empty scenario is a false
        // green, so this one runs.
        val result = run(host, scenario(ScenarioStep.Send("11=ORD-1", session = "s")))
        assertTrue(result.steps.none { it.kind == "preflight" }, "a Send-only scenario must clear preflight")
        assertEquals(listOf("11=ORD-1"), host.sent, "the send must actually run")
    }

    @Test
    fun `a scenario with at least one Expect is unaffected by the asserts-nothing guard`() {
        val host = FakeHost()
        host.inbox += incoming("8", mapOf(35 to "8", 150 to "0"))
        val result = run(host, scenario(expect("8", FieldExpectation(150, Matcher.Exact("0")))))
        assertTrue(result.passed, "a scenario that asserts still runs and can pass: ${result.steps}")
        assertTrue(result.steps.none { it.kind == "preflight" }, "the guard must not trip when a step asserts")
    }

    @Test
    fun `a scenario that waits for logon itself passes preflight while disconnected`() {
        val host = FakeHost()
        host.stateOf = { "DISCONNECTED" }
        val scenario = scenario(
            ScenarioStep.Wait(session = "s", state = "LOGGED_ON", timeoutMs = 30),
            ScenarioStep.Send("35=D|", session = "s"),
        )
        val result = run(host, scenario)
        // It fails later at the wait timeout — but preflight let it try, as the scenario owns logon.
        assertEquals("wait", result.steps.first().kind)
        assertFalse(result.steps.first().passed)
    }

    @Test
    fun `clear on a session that vanished mid-run fails instead of silently passing`() {
        val host = FakeHost()
        host.clearOk = false
        val result = run(host, scenario(ScenarioStep.ClearMessages(session = "s")))
        assertFalse(result.passed)
        assertTrue(result.steps.single().detail!!.contains("not found"), "detail: ${result.steps.single().detail}")
    }

    @Test
    fun `match predicate values resolve against the scenario scope (correlation-aware binding)`() {
        val host = FakeHost()
        host.inbox += incoming("8", mapOf(35 to "8", 11 to "OTHER-ORDER"), bindTags = mapOf(11 to "OTHER-ORDER"))
        host.inbox += incoming("8", mapOf(35 to "8", 11 to "ORD-1"), bindTags = mapOf(11 to "ORD-1"))
        val scenario = scenario(
            ScenarioStep.Send("11=\${id = \"ORD-1\"}", session = "s"),
            ScenarioStep.Expect(
                session = "s",
                match = MatchPredicate("8", null, listOf(TagValue(11, "\${id}"))),
                timeoutMs = 1_000,
                expectation = Expectation(fields = listOf(FieldExpectation(11, Matcher.Exact("ORD-1"))), messageType = "8"),
            ),
        )
        val result = run(host, scenario)
        assertTrue(result.passed, "should bind to OUR order's report, skipping the other one: ${result.steps}")
        assertEquals("ORD-1", result.steps[1].tags.single().actual)
    }

    @Test
    fun `a bind constraint matches a later occurrence of a repeated tag, not only the first`() {
        val host = FakeHost()
        // Tag 11 appears twice in wire order; only the SECOND occurrence carries the value we bind on. The
        // old firstOrNull check consulted occurrence #1 ("AAA"), never matched, and the step timed out.
        host.inbox += incomingWire("8", listOf(35 to "8", 11 to "AAA", 11 to "ORD-1"))
        val scenario = scenario(
            ScenarioStep.Expect(
                session = "s",
                match = MatchPredicate("8", null, listOf(TagValue(11, "ORD-1"))),
                timeoutMs = 1_000,
                expectation = Expectation(fields = listOf(FieldExpectation(35, Matcher.Exact("8"))), messageType = "8"),
            ),
        )
        val result = run(host, scenario)
        assertTrue(result.passed, "the constraint must match the 2nd occurrence of tag 11: ${result.steps}")
    }

    @Test
    fun `expect timeout names the session, its state, and the bind constraints`() {
        val host = FakeHost()
        val scenario = scenario(
            ScenarioStep.Expect(
                session = "s",
                match = MatchPredicate("8", null, listOf(TagValue(150, "F"))),
                timeoutMs = 40,
                expectation = Expectation(fields = emptyList(), messageType = "8"),
            ),
        )
        val detail = run(host, scenario).steps.single().detail!!
        assertTrue(detail.contains("where 150=F"), "detail: $detail")
        assertTrue(detail.contains("on 's'"), "detail: $detail")
        assertTrue(detail.contains("state=LOGGED_ON"), "detail: $detail")
    }

    // ----- Decision 6: disambiguating two same-type messages -----------------------------------------

    /** Two ExecutionReports; the assertion belongs to the second. An occurrence ordinal binds it, not the first. */
    @Test
    fun `an occurrence ordinal binds the Nth message of a type, not the first`() {
        val host = FakeHost()
        host.inbox += incoming("8", mapOf(35 to "8", 39 to "0")) // ack
        host.inbox += incoming("8", mapOf(35 to "8", 39 to "2")) // fill — the one asserted
        val step =
            ScenarioStep.Expect(
                session = "s",
                match = MatchPredicate(messageType = "8", occurrence = 2),
                timeoutMs = 1_000,
                expectation = Expectation(fields = listOf(FieldExpectation(39, Matcher.Exact("2"))), messageType = "8"),
            )
        val result = run(host, scenario(step))
        assertTrue(result.passed, "occurrence=2 must bind the second 8 (OrdStatus=2): ${result.steps}")
    }

    /** The screenshot's real fix: bind the ExecutionReport that carries QuoteReqID, skipping the ack that does not. */
    @Test
    fun `a present constraint binds the message that carries the tag, skipping the one that does not`() {
        val host = FakeHost()
        host.inbox += incoming("8", mapOf(35 to "8", 39 to "0")) // no QuoteReqID
        host.inbox += incoming("8", mapOf(35 to "8", 39 to "2", 131 to "QR-9")) // carries QuoteReqID
        val step =
            ScenarioStep.Expect(
                session = "s",
                match = MatchPredicate(messageType = "8", fields = listOf(TagValue(131, "", MatchOp.PRESENT))),
                timeoutMs = 1_000,
                expectation = Expectation(fields = listOf(FieldExpectation(39, Matcher.Exact("2"))), messageType = "8"),
            )
        val result = run(host, scenario(step))
        assertTrue(result.passed, "present(131) must bind the second report: ${result.steps}")
    }

    @Test
    fun `an absent constraint binds the message that lacks the tag`() {
        val host = FakeHost()
        host.inbox += incoming("8", mapOf(35 to "8", 39 to "0", 131 to "QR-9")) // carries QuoteReqID
        host.inbox += incoming("8", mapOf(35 to "8", 39 to "2")) // does not
        val step =
            ScenarioStep.Expect(
                session = "s",
                match = MatchPredicate(messageType = "8", fields = listOf(TagValue(131, "", MatchOp.ABSENT))),
                timeoutMs = 1_000,
                expectation = Expectation(fields = listOf(FieldExpectation(39, Matcher.Exact("2"))), messageType = "8"),
            )
        assertTrue(run(host, scenario(step)).passed)
    }

    /** An ordinal is absolute, so two steps cannot both claim the same slot — the second says so, by name. */
    @Test
    fun `an occurrence pointing at a message an earlier step took fails, naming the conflict`() {
        val host = FakeHost()
        host.inbox += incoming("8", mapOf(35 to "8", 39 to "0"))
        host.inbox += incoming("8", mapOf(35 to "8", 39 to "2"))
        val first = expect("8", FieldExpectation(39, Matcher.Exact("0"))) // walk-in-order takes the 1st
        val clash =
            ScenarioStep.Expect(
                session = "s",
                match = MatchPredicate(messageType = "8", occurrence = 1), // wants the 1st too
                timeoutMs = 40,
                expectation = Expectation(fields = emptyList(), messageType = "8"),
            )
        val result = run(host, scenario(first, clash))
        assertFalse(result.passed)
        val detail = result.steps[1].detail!!
        assertTrue(detail.contains("already matched by an earlier step"), "detail: $detail")
        assertTrue(detail.contains("1st"), "the detail should name the ordinal: $detail")
    }

    @Test
    fun `an occurrence beyond what arrived times out, saying it wanted more`() {
        val host = FakeHost()
        host.inbox += incoming("8", mapOf(35 to "8")) // only one
        val step =
            ScenarioStep.Expect(
                session = "s",
                match = MatchPredicate(messageType = "8", occurrence = 2),
                timeoutMs = 40,
                expectation = Expectation(fields = emptyList(), messageType = "8"),
            )
        val result = run(host, scenario(step))
        assertFalse(result.passed)
        assertTrue(result.steps.single().detail!!.contains("fewer than 2"), "detail: ${result.steps.single().detail}")
    }

    /**
     * The run's final scope comes out on the result, each name attributed to the step that minted it —
     * not to a later step that merely referenced it. This is what the report, the editor strip, and the
     * diff window's THIS_RUN reference all read; before it existed the scope died with the run and every
     * `${id0}` was invisible at exactly the moment an author was staring at a reference row.
     */
    @Test
    fun `the final scope is reported, each name attributed to the step that minted it`() {
        val host = FakeHost()
        host.inbox += incoming("8", mapOf(35 to "8"))
        val scenario =
            scenario(
                ScenarioStep.Send("35=D|11=\${id0 = \"A1\"}|", session = "s"),
                ScenarioStep.Send("35=D|41=\${id0}|", session = "s"),
                expect("8", FieldExpectation(35, Matcher.Exact("8"))),
            )
        val result = run(host, scenario)
        assertTrue(result.passed, "scenario should pass: ${result.steps}")
        val identified = scenario.withIds()
        assertEquals(
            listOf(ScenarioVariable("id0", "A1", identified.steps[0].stepId)),
            result.variables,
            "one mint, attributed to the Send that made it",
        )
    }

    @Test
    fun `a run that mints nothing reports no variables`() {
        val host = FakeHost()
        host.inbox += incoming("8", mapOf(35 to "8"))
        val result = run(host, scenario(expect("8", FieldExpectation(35, Matcher.Exact("8")))))
        assertTrue(result.passed)
        assertEquals(emptyList(), result.variables, "no `\${...}` mints, no variables — the pre-scope shape")
    }

    /**
     * The `steps` phase stops at the first failure, and the reported scope is exactly what the failing
     * step was judged with: mints from before and at the failure are in it, mints from the steps the run
     * never reached are not. That correspondence is what lets the reconcile view resolve `${...}` rows
     * with this scope and claim the verdicts match the runner's.
     */
    @Test
    fun `the scope of an aborted run holds what was minted before the failure and nothing after`() {
        val host = FakeHost()
        // No inbox at all: the expect times out.
        val scenario =
            scenario(
                ScenarioStep.Send("35=D|11=\${id0 = \"A1\"}|", session = "s"),
                expectWith(timeoutMs = 50, FieldExpectation(35, Matcher.Exact("8"))),
                ScenarioStep.Send("35=D|11=\${id1 = \"B2\"}|", session = "s"),
            )
        val result = run(host, scenario)
        assertFalse(result.passed)
        assertEquals(listOf("id0"), result.variables.map { it.name }, "id1's Send never ran, so id1 was never minted")
    }

    /**
     * The receive→send half of correlation: the venue chooses a value (`OrderID`), a `bindAs` row
     * captures it, and the next send echoes it back — the dealer-side flow that was inexpressible
     * before `bindAs` existed. The captured name is a variable like any other: it comes out on the
     * report, attributed to the Expect that captured it.
     */
    @Test
    fun `a bindAs row captures the venue's value, and a later send echoes it`() {
        val host = FakeHost()
        host.inbox += incoming("8", mapOf(35 to "8", 37 to "VENUE-77"))
        val scenario =
            scenario(
                expect("8", FieldExpectation(37, Matcher.Presence, bindAs = "oid")),
                ScenarioStep.Send("35=F|41=\${oid}|", session = "s"),
            )
        val result = run(host, scenario)
        assertTrue(result.passed, "scenario should pass: ${result.steps}")
        assertEquals(listOf("35=F|41=VENUE-77|"), host.sent, "the send echoes the value the venue chose")
        assertEquals(
            listOf(ScenarioVariable("oid", "VENUE-77", scenario.withIds().steps[0].stepId)),
            result.variables,
            "captured into scope, attributed to the Expect that captured it",
        )
    }

    /** No silent empty-string mint: a row whose tag never arrived captures nothing. */
    @Test
    fun `a bindAs row whose tag never arrived mints nothing`() {
        val host = FakeHost()
        host.inbox += incoming("8", mapOf(35 to "8", 39 to "2"))
        val result =
            run(host, scenario(expect("8", FieldExpectation(37, Matcher.Presence, bindAs = "oid"))))
        assertFalse(result.passed, "the Presence row failed — 37 never arrived")
        assertEquals(emptyList(), result.variables, "nothing was observed, so nothing was minted")
    }

    /**
     * Capture is about the value being OBSERVED, not about the row passing: teardown runs after a
     * failure, and a teardown that cancels the venue's order needs the id whether or not the step's
     * assertion held.
     */
    @Test
    fun `a failing row still captures the value it paired with`() {
        val host = FakeHost()
        host.inbox += incoming("8", mapOf(35 to "8", 37 to "VENUE-77"))
        val result =
            run(host, scenario(expect("8", FieldExpectation(37, Matcher.Exact("SOMETHING-ELSE"), bindAs = "oid"))))
        assertFalse(result.passed)
        assertEquals("VENUE-77", result.variables.single().value, "observed, so captured — teardown may need it")
    }

    /** The k-th row for a tag captures the k-th occurrence — the positional model, applied to capture. */
    @Test
    fun `a bindAs on the second row of a repeated tag captures the second occurrence`() {
        val host = FakeHost()
        host.inbox += incomingWire("8", listOf(35 to "8", 448 to "FIRMA", 448 to "FIRMB"))
        val result =
            run(
                host,
                scenario(
                    expect(
                        "8",
                        FieldExpectation(448, Matcher.Presence),
                        FieldExpectation(448, Matcher.Presence, bindAs = "counterparty"),
                    ),
                ),
            )
        assertTrue(result.passed, "${result.steps}")
        assertEquals("FIRMB", result.variables.single().value)
    }

    // ----------------------------------------------------------------- helpers

    private fun run(host: FakeHost, scenario: Scenario) =
        ScenarioRunner(host, pollMs = 10, now = { host.clock }).run(scenario)

    /** [run], but recording every (message, verdict) the runner reports — what tints the message grid. */
    private fun runRecordingVerdicts(
        host: FakeHost,
        scenario: Scenario,
        seen: MutableList<Pair<FixMessage, StepResult>>,
    ) = ScenarioRunner(
        host,
        pollMs = 10,
        now = { host.clock },
        onExpectMatched = { m, r -> seen += m to r },
    ).run(scenario)

    private fun scenario(vararg steps: ScenarioStep) = Scenario(id = "x", name = "t", steps = steps.toList())

    private fun expect(messageType: String, vararg fields: FieldExpectation) =
        ScenarioStep.Expect(
            session = "s",
            match = MatchPredicate(messageType = messageType),
            timeoutMs = 1_000,
            expectation = Expectation(fields = fields.toList(), messageType = messageType),
        )

    private fun expectWith(timeoutMs: Long, vararg fields: FieldExpectation) =
        ScenarioStep.Expect(
            session = "s",
            match = MatchPredicate(messageType = "8"),
            timeoutMs = timeoutMs,
            expectation = Expectation(fields = fields.toList(), messageType = "8"),
        )

    private fun incoming(type: String, tags: Map<Int, String>, bindTags: Map<Int, String> = emptyMap()): FixMessage =
        FixMessage(
            timestamp = LocalDateTime.now(),
            direction = FixMessage.Direction.INCOMING,
            rawMessage = "35=$type|",
            messageType = type,
            // Bind-predicate matching reads the QuickFIX message (FixMessage.valueOfTag).
            quickfixMessage = Message().apply { bindTags.forEach { (t, v) -> setString(t, v) } },
        ).also { viewTags[it] = tags }

    /** Like [incoming] but the wire view keeps order and may repeat a tag (a grouped field) — a Map cannot. */
    private fun incomingWire(type: String, wire: List<Pair<Int, String>>): FixMessage =
        FixMessage(
            timestamp = LocalDateTime.now(),
            direction = FixMessage.Direction.INCOMING,
            rawMessage = "35=$type|",
            messageType = type,
            quickfixMessage = Message(),
        ).also { wireFields[it] = wire }

    private val viewTags = java.util.IdentityHashMap<FixMessage, Map<Int, String>>()
    private val wireFields = java.util.IdentityHashMap<FixMessage, List<Pair<Int, String>>>()

    /** Fake host: an in-memory inbox + sent log, a virtual clock advanced by the no-op sleep. */
    private inner class FakeHost : ScenarioHost {
        val sent = mutableListOf<String>()
        val inbox = mutableListOf<FixMessage>()
        var clock = 0L
        var stateOf: (String?) -> String? = { "LOGGED_ON" }
        var clearOk = true

        override fun resolve(raw: String, scope: MutableMap<String, String>, session: String?): String =
            FixMessageTemplate.evaluate(raw, emptyMap(), emptyMap(), scope, null)

        override fun send(raw: String, session: String?): Boolean {
            sent += raw
            return true
        }

        override fun messages(session: String?): List<FixMessage> = inbox.toList()

        override fun connectionState(session: String?): String? = stateOf(session)

        override fun referenceResolver(session: String?, scope: Map<String, String>): (String) -> String? =
            { expr -> scope[expr.removePrefix("\${").removeSuffix("}")] }

        /** Messages whose wire order we do not have — the host answers null, and the runner must say so. */
        val noWireOrder = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<FixMessage, Boolean>())

        override fun view(message: FixMessage): MessageView? =
            when {
                message in noWireOrder -> null
                message in wireFields -> ListView(wireFields.getValue(message))
                else -> MapView(viewTags[message] ?: emptyMap())
            }

        override fun clearMessages(session: String?): Boolean {
            if (!clearOk) return false
            inbox.clear()
            return true
        }

        override fun resetSeqNum(session: String?, sender: Int?, target: Int?): Boolean = true

        override fun sleep(ms: Long) {
            clock += ms
        }
    }

    private class MapView(private val tags: Map<Int, String>) : MessageView {
        // A LinkedHashMap keeps insertion order, which is what the callers below rely on: under the
        // sequence model the order of the fields *is* the addressing, so a view that reordered them
        // would be testing against a message no venue ever sent.
        override fun fields(): List<Pair<Int, String>> = tags.toList()
    }

    /** A wire view that keeps exact order and may repeat a tag — what a message with grouped fields looks like. */
    private class ListView(private val wire: List<Pair<Int, String>>) : MessageView {
        override fun fields(): List<Pair<Int, String>> = wire
    }
}
