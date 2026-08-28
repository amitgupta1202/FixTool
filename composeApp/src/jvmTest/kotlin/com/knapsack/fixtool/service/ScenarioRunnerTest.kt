package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.scenario.BindScope
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
import com.knapsack.fixtool.model.scenario.TrafficMode
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
        val step = result.steps.single { it.kind == "expect" }
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
    fun `preflight auto-connects a missing session and reports it as a passing connect row`() {
        val host = FakeHost()
        var up = false
        var polls = 0
        host.stateOf = { if (up) "LOGGED_ON" else null }
        // Started means "under way", not "logged on" — the state flips only after a few polls of the
        // runner's own bounded wait, which is exactly the gap the wait exists to cover.
        host.connect = { ConnectAttempt.Started("prof-s") }
        host.onSleep = { if (++polls >= 3) up = true }
        val result = run(host, scenario(ScenarioStep.Send("35=D|", session = "s")))
        assertTrue(result.passed, "the run must proceed once auto-connect brings the session up: ${result.steps}")
        val connect = result.steps.first()
        assertEquals("connect", connect.kind)
        assertTrue(connect.passed)
        assertTrue(
            connect.detail!!.contains("prof-s") && connect.detail!!.contains("not found"),
            "the row must name the profile used and the state it healed: ${connect.detail}",
        )
        assertEquals(listOf("35=D|"), host.sent, "the send must run after the auto-connect")
    }

    @Test
    fun `preflight reconnects a disconnected session, and the row says what it was`() {
        val host = FakeHost()
        var state = "DISCONNECTED"
        host.stateOf = { state }
        host.connect = { ConnectAttempt.Started("prof-s") }
        host.onSleep = { state = "LOGGED_ON" }
        val result = run(host, scenario(ScenarioStep.Send("35=D|", session = "s")))
        assertTrue(result.passed, "${result.steps}")
        val connect = result.steps.first { it.kind == "connect" }
        assertTrue(connect.detail!!.contains("was DISCONNECTED"), "detail: ${connect.detail}")
    }

    @Test
    fun `auto-connect that never reaches LOGGED_ON fails preflight and names the profile`() {
        val host = FakeHost()
        // The connect is initiated but the far side never completes the logon — an acceptor whose
        // counterparty is down looks exactly like this.
        host.stateOf = { "CONNECTING" }
        host.connect = { ConnectAttempt.Started("prof-s") }
        val result = run(host, scenario(ScenarioStep.Send("35=D|", session = "s")))
        assertFalse(result.passed)
        val step = result.steps.single()
        assertEquals("preflight", step.kind)
        assertTrue(
            step.detail!!.contains("prof-s") && step.detail!!.contains("did not reach LOGGED_ON"),
            "detail: ${step.detail}",
        )
        assertTrue(host.sent.isEmpty(), "nothing may run when the session never came up")
    }

    @Test
    fun `a host that cannot connect keeps the fail-fast preflight, with the reason attached`() {
        val host = FakeHost()
        host.stateOf = { null }
        val result = run(host, scenario(ScenarioStep.Send("35=D|", session = "gone")))
        assertFalse(result.passed)
        assertTrue(
            result.steps.single().detail!!.contains("no auto-connect in this fake"),
            "the host's reason must reach the report: ${result.steps.single().detail}",
        )
    }

    @Test
    fun `a missing session the scenario itself logs on is connected but not held to LOGGED_ON up front`() {
        val host = FakeHost()
        var state: String? = null
        var sleeps = 0
        // connectSession creates the session immediately (CONNECTED), but logon completes only later,
        // during the scenario's own Wait step — preflight must settle for existence and hand over.
        host.connect = { state = "CONNECTED"; ConnectAttempt.Started("prof-s") }
        host.onSleep = { if (state != null && ++sleeps >= 2) state = "LOGGED_ON" }
        host.stateOf = { state }
        val scenario = scenario(
            ScenarioStep.Wait(session = "s", state = "LOGGED_ON", timeoutMs = 1_000),
            ScenarioStep.Send("35=D|", session = "s"),
        )
        val result = run(host, scenario)
        assertTrue(result.passed, "${result.steps}")
        assertEquals(listOf("connect", "wait", "send"), result.steps.map { it.kind })
    }

    @Test
    fun `a session map re-aims the run without the scenario changing`() {
        val host = FakeHost()
        // Only the QA pair is up — running "as recorded" would fail preflight on the dev names.
        host.stateOf = { session -> if (session?.startsWith("qa-") == true) "LOGGED_ON" else null }
        val scenario =
            scenario(
                ScenarioStep.Send("35=D|", session = "dev-buyside"),
                ScenarioStep.Send("35=D|", session = "dev-sellside"),
            )
        val result = run(host, scenario, mapOf("dev-buyside" to "qa-buyside", "dev-sellside" to "qa-sellside"))
        assertTrue(result.passed, "${result.steps}")
        assertEquals(listOf<String?>("qa-buyside", "qa-sellside"), host.sentTo, "every send must go to the mapped session")
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

    // ----------------------------------------------------------------- muted steps

    @Test
    fun `a muted step is skipped whole - nothing sent, no verdict`() {
        val host = FakeHost()
        val scenario =
            scenario(
                ScenarioStep.Send("35=D|11=PARKED|", session = "s", muted = true),
                ScenarioStep.Send("35=D|11=LIVE|", session = "s"),
            )
        val result = run(host, scenario)
        assertTrue(result.passed, "${result.steps}")
        assertEquals(listOf("35=D|11=LIVE|"), host.sent, "the muted send must not reach the wire")
        assertEquals(1, result.steps.size, "a skipped step reports no verdict — it did not run")
    }

    @Test
    fun `a muted expect neither consumes nor judges — the run passes without it`() {
        val host = FakeHost()
        host.inbox += incoming("8", mapOf(35 to "8", 39 to "2"))
        val scenario =
            scenario(
                // Would fail if it ran (39 is "2", not "9") — muting must park the assertion too.
                expect("8", FieldExpectation(39, Matcher.Exact("9"))).copy(muted = true),
                expect("8", FieldExpectation(39, Matcher.Exact("2"))),
            )
        val result = run(host, scenario)
        assertTrue(result.passed, "${result.steps}")
        // The active expect bound the message the muted one would have consumed.
        // `single { expect }`: the seeded reply predates the run, so the report also carries the binding
        // caveat — which is the point of that check and not this test's business.
        assertEquals("2", result.steps.single { it.kind == "expect" }.tags.single { it.tag == 39 }.actual)
    }

    @Test
    fun `preflight ignores a muted step's session — parking the broken leg is what muting is for`() {
        val host = FakeHost()
        host.stateOf = { session -> if (session == "down") null else "LOGGED_ON" }
        val scenario =
            scenario(
                ScenarioStep.Send("35=D|", session = "down", muted = true),
                ScenarioStep.Send("35=D|11=OK|", session = "s"),
            )
        val result = run(host, scenario)
        assertTrue(result.passed, "a muted step's missing session must not fail preflight: ${result.steps}")
    }

    @Test
    fun `a scenario muted down to nothing is refused, and the refusal says why`() {
        val host = FakeHost()
        val scenario = scenario(ScenarioStep.Send("35=D|", session = "s", muted = true))
        val result = run(host, scenario)
        assertFalse(result.passed, "an all-muted scenario checks nothing and must not report green")
        val detail = result.steps.single().detail.orEmpty()
        assertTrue(detail.contains("muted", ignoreCase = true), "the refusal must name the cause: '$detail'")
    }

    // ----------------------------------------------------------------- strict traffic

    @Test
    fun `strict traffic fails a green run on an incoming message no expect bound`() {
        val host = FakeHost()
        // Both arrive during the run: this is the traffic the scenario provoked, which is the only traffic
        // the verdict judges. Seeded history is somebody else's run — see the watermark tests below.
        host.arriving += incoming("8", mapOf(35 to "8", 39 to "0"))
        host.arriving += incoming("8", mapOf(35 to "8", 39 to "2")) // the surplus: nobody expects the second ER
        val reported = mutableListOf<Pair<FixMessage, StepResult>>()
        val result =
            runRecordingVerdicts(
                host,
                scenario(expect("8", FieldExpectation(39, Matcher.Exact("0")))).copy(traffic = TrafficMode.STRICT),
                reported,
            )
        assertFalse(result.passed, "the unbound ExecutionReport must fail a strict run: ${result.steps}")
        val verdict = result.steps.single { it.kind == "traffic" }
        assertFalse(verdict.passed)
        assertEquals("steps", verdict.phase, "the verdict must decide pass/fail, so it cannot ride in teardown")
        assertTrue(verdict.detail.orEmpty().contains("never bound"), "the detail must say what the red means: '${verdict.detail}'")
        // The surplus is marked in the grid through the same channel as an Expect verdict — the report must
        // never say "1 unexpected message" over a grid where nothing is tinted.
        val stray = reported.single { !it.second.passed && it.second.kind == "traffic" }
        assertEquals("2", stray.first.let { m -> viewTags[m]?.get(39) }, "the marked message is the unbound one")
    }

    @Test
    fun `strict traffic passes, and says so, when every incoming message was bound`() {
        val host = FakeHost()
        host.arriving += incoming("8", mapOf(35 to "8", 39 to "0"))
        val result =
            run(host, scenario(expect("8", FieldExpectation(39, Matcher.Exact("0")))).copy(traffic = TrafficMode.STRICT))
        assertTrue(result.passed, "${result.steps}")
        // A green row, not silence: the run checked something here, and the report says so.
        assertTrue(result.steps.single { it.kind == "traffic" }.passed)
    }

    @Test
    fun `strict traffic ignores session administration but not an unasked-for logout`() {
        val host = FakeHost()
        host.arriving += incoming("8", mapOf(35 to "8", 39 to "0"))
        host.arriving += incoming("0", mapOf(35 to "0")) // heartbeat: the stream's envelope, never a surplus
        val green =
            run(host, scenario(expect("8", FieldExpectation(39, Matcher.Exact("0")))).copy(traffic = TrafficMode.STRICT))
        assertTrue(green.passed, "a heartbeat must never fail a strict run: ${green.steps}")

        // A second host rather than a second run on the first: what the first run left in the log now
        // predates the second and is excluded, which would make this assert something else.
        val noisy = FakeHost()
        noisy.arriving += incoming("8", mapOf(35 to "8", 39 to "0"))
        noisy.arriving += incoming("5", mapOf(35 to "5")) // a goodbye nobody asked for IS the surplus
        val red =
            run(noisy, scenario(expect("8", FieldExpectation(39, Matcher.Exact("0")))).copy(traffic = TrafficMode.STRICT))
        assertFalse(red.passed, "an unbound Logout is exactly what strict traffic exists to report")
    }

    @Test
    fun `strict traffic is not judged on a run that already failed`() {
        val host = FakeHost()
        host.inbox += incoming("8", mapOf(35 to "8", 39 to "0"))
        host.inbox += incoming("8", mapOf(35 to "8", 39 to "2"))
        val result =
            run(
                host,
                // The first expect fails on its value; the second ER is then unbound — but blaming it would
                // point away from the real failure, so the stream verdict must not pile on.
                scenario(expect("8", FieldExpectation(39, Matcher.Exact("9")))).copy(traffic = TrafficMode.STRICT),
            )
        assertFalse(result.passed)
        assertTrue(result.steps.none { it.kind == "traffic" }, "no stream verdict on an already-red run: ${result.steps}")
    }

    @Test
    fun `open traffic stays the default and reports no stream verdict`() {
        val host = FakeHost()
        host.inbox += incoming("8", mapOf(35 to "8", 39 to "0"))
        host.inbox += incoming("8", mapOf(35 to "8", 39 to "2")) // unbound, and under OPEN nobody's business
        val result = run(host, scenario(expect("8", FieldExpectation(39, Matcher.Exact("0")))))
        assertTrue(result.passed, "OPEN must keep the historical semantics: ${result.steps}")
        assertTrue(result.steps.none { it.kind == "traffic" }, "skipped means no row — the check did not run")
    }

    @Test
    fun `muting an expect under strict traffic fails the run on the message it would have bound`() {
        val host = FakeHost()
        host.arriving += incoming("8", mapOf(35 to "8", 39 to "0"))
        host.arriving += incoming("8", mapOf(35 to "8", 39 to "2"))
        val scenario =
            scenario(
                expect("8", FieldExpectation(39, Matcher.Exact("0"))),
                expect("8", FieldExpectation(39, Matcher.Exact("2"))).copy(muted = true),
            ).copy(traffic = TrafficMode.STRICT)
        val result = run(host, scenario)
        // The mute contract ("as if the step were not there") composed with the strict contract ("nothing
        // unasked-for arrived"): a scenario without that expect SHOULD fail strict on the extra reply.
        assertFalse(result.passed, "the parked expect's message is unbound, and strict says so: ${result.steps}")
        assertFalse(result.steps.single { it.kind == "traffic" }.passed)
    }

    // ------------------------------------ the watermark: a strict verdict judges this run's traffic only

    /**
     * The bug in the shape it bites: a strict scenario run twice. The verdict scans each session's whole
     * log, and nothing empties a log between runs — so the second run found the first run's reply sitting
     * unbound and called it the venue's surplus. Every second run was red, and a twenty-times repeat would
     * have been nineteen reds, none of them about the venue. Under [BindScope.THIS_RUN] it was red *by
     * construction*: an older message is not bindable there, so it could not be anything but a stray.
     */
    @Test
    fun `a strict scenario run twice is not failed by its own first run`() {
        val host = FakeHost()
        val scenario =
            Scenario(
                id = "x",
                name = "repeat",
                steps = listOf(expectWith(timeoutMs = 500, FieldExpectation(39, Matcher.Exact("2")))),
                binding = BindScope.THIS_RUN,
                traffic = TrafficMode.STRICT,
            )
        host.arriving += incoming("8", mapOf(35 to "8", 39 to "2"))
        val first = run(host, scenario)
        assertTrue(first.passed, "the first run is the easy one: ${first.steps}")

        // Iteration 2, against a log that still holds iteration 1's reply — which is the ordinary state of
        // a session, not a contrived one: only a ClearMessages in setup would have emptied it.
        host.arriving += incoming("8", mapOf(35 to "8", 39 to "2"))
        val second = run(host, scenario)
        assertTrue(second.passed, "iteration 2 must be judged on its own traffic: ${second.steps.map { it.detail }}")
        // And it says what it set aside: those messages are still in the grid, untinted, and a verdict that
        // quietly ignored them would leave the reader wondering which rows it had counted.
        val verdict = second.steps.single { it.kind == "traffic" }
        assertTrue(
            verdict.detail!!.contains("1 message(s) already in the log when the run began were not judged"),
            "the exclusion must be reported, never silent: ${verdict.detail}",
        )
    }

    /**
     * The same rule under the default binding, where the older message *was* bindable and simply was not
     * wanted: a cancel-reject left over from an earlier run is not this run's surplus either.
     */
    @Test
    fun `strict traffic does not judge a message that was in the log before the run began`() {
        val host = FakeHost()
        host.inbox += incoming("9", mapOf(35 to "9")) // an earlier run's cancel-reject, still in the log
        host.arriving += incoming("8", mapOf(35 to "8", 39 to "0"))
        val result =
            run(host, scenario(expect("8", FieldExpectation(39, Matcher.Exact("0")))).copy(traffic = TrafficMode.STRICT))
        assertTrue(result.passed, "history the run inherited is not traffic the run provoked: ${result.steps}")
        assertTrue(result.steps.single { it.kind == "traffic" }.passed)
    }

    @Test
    fun `an excluded field is kept in the scenario and left off the wire`() {
        val host = FakeHost()
        run(host, scenario(ScenarioStep.Send("35=D|11=ORD|#9303=1|38=100|", session = "s")))
        assertEquals(listOf("35=D|11=ORD|38=100|"), host.sent)
    }

    /**
     * The subtle half of the feature. `resolve` is a whole-string regex that never parses fields, so an
     * excluded row's `${id0 = ...}` would evaluate and bind a scenario variable even though the field it
     * sat in was never sent — and the next step's `${id0}` would then correlate against a value the venue
     * had never seen. Stripping before resolve is what makes an excluded field wholly inert.
     *
     * The reference is left literal here, which is the engine's deliberate behaviour for an unknown name
     * and exactly what the authoring-time `unminted()` warning exists to catch first.
     */
    @Test
    fun `a mint inside an excluded field never binds, so a later reference stays literal`() {
        val host = FakeHost()
        run(
            host,
            scenario(
                ScenarioStep.Send("35=D|#11=\${id0 = \"ABC\"}|", session = "s"),
                ScenarioStep.Send("35=D|41=\${id0}|", session = "s"),
            ),
        )
        assertEquals(listOf("35=D|", "35=D|41=\${id0}|"), host.sent)
    }

    @Test
    fun `unexcluding restores the field, and the mint with it`() {
        val host = FakeHost()
        run(
            host,
            scenario(
                ScenarioStep.Send("35=D|11=\${id0 = \"ABC\"}|", session = "s"),
                ScenarioStep.Send("35=D|41=\${id0}|", session = "s"),
            ),
        )
        assertEquals(listOf("35=D|11=ABC|", "35=D|41=ABC|"), host.sent)
    }

    // --------------------------------------------------- the post-mortem: what arrived, when a step said what did not

    /**
     * The failure this whole thing exists for. The buy side asks; the venue rejects it straight back to the
     * buy side; the sell side is therefore never told anything. The run fails — correctly — on the sell
     * side's expect, for a silence whose cause is a message sitting in the *other* leg's log, which no step
     * ever reached and nothing ever looked at.
     */
    @Test
    fun `a failure on one leg reports the reject that landed on the other, and how it diverges`() {
        val host = FakeHost()
        val reject = incoming("AI", mapOf(35 to "AI", 131 to "Q1", 297 to "5", 58 to "instrument not tradable"))
        host.on("buy") += reject
        host.on("sell")

        val reported = mutableListOf<Pair<FixMessage, StepResult>>()
        val result = runRecordingVerdicts(host, bothLegs(), reported)

        assertFalse(result.passed)
        // The verdict is exactly what it was. The step that failed, the index it failed at, and the whole
        // opening sentence of its detail are untouched — the diagnosis is appended to the report, never
        // substituted into it.
        val failed = result.steps.first { !it.passed }
        assertEquals(1, failed.stepIndex)
        assertEquals("expect", failed.kind)
        assertTrue(
            failed.detail!!.startsWith("no R message within 50ms on 'sell'"),
            "the failing step's own sentence must survive verbatim: '${failed.detail}'",
        )

        val diagnosis = result.steps.single { it.kind == "diagnosis" }
        assertEquals(2, diagnosis.stepIndex, "it pairs with the buy-side AI expect the run never reached")
        assertTrue(!diagnosis.stepId.isNullOrBlank(), "and names it by id, which is what the reconcile door needs")
        assertEquals("5", diagnosis.tags.single { it.tag == 297 }.actual)
        assertTrue(diagnosis.tags.none { it.passed }, "only the diverging rows are worth a report line")
        assertTrue(diagnosis.detail!!.contains("guess at pairing"), "a pairing must say it is a guess")

        // And it is marked in the grid, through the same channel every other verdict uses — a report that
        // names a message the grid does not mark leaves the tester hunting for which one.
        assertTrue(reported.any { it.first === reject }, "the reject must be published for the grid to tint")
    }

    /** The same shape, but the reply is the one the scenario wanted: the diagnosis says so, and does not cry wolf. */
    @Test
    fun `a stray that would have satisfied the step it pairs with is reported as such`() {
        val host = FakeHost()
        host.on("buy") += incoming("AI", mapOf(35 to "AI", 131 to "Q1", 297 to "0"))
        host.on("sell")

        val result = run(host, bothLegs())
        val diagnosis = result.steps.single { it.kind == "diagnosis" }
        assertTrue(diagnosis.passed, "the row's claim is that this message diverges — and it does not")
        assertTrue(
            diagnosis.detail!!.contains("would have satisfied"),
            "it must say the message was fine, not imply it was the problem: '${diagnosis.detail}'",
        )
    }

    /**
     * The near miss. A message of the right type is sitting on the right session and the *bind predicate*
     * turned it down — which today is indistinguishable in the report from nothing having arrived at all.
     * The row names the constraint, and points at the predicate: repairing the assertion rows would leave
     * the step binding nothing on the next run just the same.
     */
    @Test
    fun `a message the bind predicate rejected is named, with the constraint that rejected it`() {
        val host = FakeHost()
        host.inbox += incoming("R", mapOf(35 to "R", 131 to "Q0"))
        val scenario =
            scenario(
                ScenarioStep.Expect(
                    session = "s",
                    match = MatchPredicate(messageType = "R", fields = listOf(TagValue(131, "Q1"))),
                    timeoutMs = 50,
                    expectation = Expectation(fields = listOf(FieldExpectation(131, Matcher.Exact("Q1"))), messageType = "R"),
                ),
            )
        val diagnosis = run(host, scenario).steps.single { it.kind == "diagnosis" }
        assertTrue(diagnosis.detail!!.contains("131=Q1"), "the constraint: '${diagnosis.detail}'")
        assertTrue(diagnosis.detail!!.contains("131=Q0"), "and what the message actually carried")
        assertTrue(diagnosis.detail!!.contains("bind predicate"))
        // No route into the reconcile view: that view repairs expectations, and repairing this step's
        // expectation would not make it bind this message next time. Offering the door would offer a fix
        // that cannot fix it.
        assertEquals(-1, diagnosis.stepIndex)
        assertEquals(null, diagnosis.stepId)
    }

    @Test
    fun `a candidate an earlier step already bound is reported as the authoring bug it is`() {
        val host = FakeHost()
        host.inbox += incoming("8", mapOf(35 to "8", 39 to "2"))
        val result =
            run(
                host,
                scenario(
                    expect("8", FieldExpectation(39, Matcher.Presence)),
                    expectWith(timeoutMs = 50, FieldExpectation(39, Matcher.Presence)),
                ),
            )
        val diagnosis = result.steps.single { it.kind == "diagnosis" }
        assertTrue(
            diagnosis.detail!!.contains("already bound") && diagnosis.detail!!.contains("occurrence"),
            "it must name the collision and the fix: '${diagnosis.detail}'",
        )
    }

    @Test
    fun `traffic no step expects anywhere is reported as unexplained, not paired with something`() {
        val host = FakeHost()
        host.on("buy") += incoming("9", mapOf(35 to "9", 434 to "1"))
        host.on("sell")

        val diagnosis = run(host, bothLegs()).steps.single { it.kind == "diagnosis" }
        assertEquals(-1, diagnosis.stepIndex, "nothing to pair it with, so nothing to reconcile against")
        assertTrue(
            diagnosis.detail!!.contains("no expect step in this scenario looks for a 9"),
            "'${diagnosis.detail}'",
        )
    }

    @Test
    fun `a green run is never diagnosed`() {
        val host = FakeHost()
        host.inbox += incoming("8", mapOf(35 to "8", 150 to "0"))
        val result = run(host, scenario(expect("8", FieldExpectation(150, Matcher.Exact("0")))))
        assertTrue(result.passed)
        assertTrue(result.steps.none { it.kind == "diagnosis" }, "there is no absence to explain")
    }

    /**
     * The property the whole feature is built to preserve: a diagnosis is a narrative, not a vote. It must
     * not bind a message, satisfy a step, or move the blame — otherwise it is out-of-order matching wearing
     * a report's clothes, and the ordering guarantee is gone.
     */
    @Test
    fun `a diagnosis never binds a message or changes which step is blamed`() {
        val host = FakeHost()
        host.on("buy") += incoming("AI", mapOf(35 to "AI", 131 to "Q1", 297 to "5"))
        host.on("sell")

        val result = run(host, bothLegs())
        assertFalse(result.passed, "a diagnosed run is still a failed run")
        // The buy-side AI expect never ran and still has not: the reject explains the failure, it does not
        // satisfy anything. Only the two steps that actually ran produced non-diagnosis results.
        val ran = result.steps.filterNot { it.kind == "diagnosis" }
        assertEquals(listOf(0, 1), ran.map { it.stepIndex })
        assertEquals(1, ran.single { !it.passed }.stepIndex)
    }

    /**
     * **A feed that never stops must not drown the finding.**
     *
     * Market data does not go quiet while a scenario runs: the session log is a ring buffer that is always
     * full of the very type the step is waiting for. Diagnosing one row per message would spend the whole
     * report on ticks — and because they are the newest thing on the wire, recency alone ranks every one of
     * them above the reject that actually explains the failure.
     */
    @Test
    fun `a streaming session is collapsed by reason, and the pairable evidence still wins its slot`() {
        val host = FakeHost()
        // 300 ticks the step will not bind (wrong MDReqID — the previous subscription's), plus the one
        // message an expect further down the scenario was waiting for.
        repeat(300) { host.on("md") += incoming("W", mapOf(35 to "W", 262 to "REQ-1", 270 to "1.0$it")) }
        host.on("md") += incoming("Y", mapOf(35 to "Y", 262 to "REQ-2", 281 to "0", 58 to "unknown symbol"))
        repeat(300) { host.on("md") += incoming("W", mapOf(35 to "W", 262 to "REQ-1", 270 to "2.0$it")) }

        val scenario =
            Scenario(
                id = "x",
                name = "md",
                steps =
                    listOf(
                        ScenarioStep.Expect(
                            session = "md",
                            match = MatchPredicate(messageType = "W", fields = listOf(TagValue(262, "REQ-2"))),
                            timeoutMs = 50,
                            expectation =
                                Expectation(fields = listOf(FieldExpectation(262, Matcher.Exact("REQ-2"))), messageType = "W"),
                        ),
                        // Never runs. It is what the MDRequestReject pairs with.
                        ScenarioStep.Expect(
                            session = "md",
                            match = MatchPredicate(messageType = "Y"),
                            timeoutMs = 1_000,
                            expectation =
                                Expectation(fields = listOf(FieldExpectation(281, Matcher.Exact("1"))), messageType = "Y"),
                        ),
                    ),
            )
        val reported = mutableListOf<Pair<FixMessage, StepResult>>()
        val result = runRecordingVerdicts(host, scenario, reported)
        val diagnosis = result.steps.filter { it.kind == "diagnosis" }

        // 601 messages, a handful of rows. The cap is not what did this — the classification is.
        assertTrue(diagnosis.size <= 3, "600 ticks must not become 600 findings: ${diagnosis.map { it.detail }}")

        // One row for the 600 ticks, carrying the count and the single reason they all share — and saying
        // plainly that it classified the newest 500 rather than presenting that as the whole log.
        val ticks = diagnosis.single { it.detail!!.contains("600 W messages") }
        assertTrue(ticks.detail!!.contains("500 most recent"), "the sample must not pose as the total: ${ticks.detail}")
        assertTrue(ticks.detail!!.contains("262=REQ-2"), "the constraint that rejected them: ${ticks.detail}")
        assertTrue(ticks.detail!!.contains("same reason each time"), "${ticks.detail}")

        // And the reject — one message among 601 — still gets its own row, paired with the step that was
        // waiting for it, because pairable outranks recent.
        val reject = diagnosis.single { it.stepIndex == 1 }
        assertEquals("0", reject.tags.single { it.tag == 281 }.actual)

        // Only the reject and one example tick are marked; the grid is not repainted 600 times.
        assertTrue(reported.size <= 3, "marked ${reported.size} messages; a mark on everything marks nothing")
    }

    // --------------------------------------------------------- binding a message that predates the run

    /**
     * The false green this exists to catch. The session log outlives the run that reads it, so on a session
     * permanently full of the expected type an Expect binds a reply to *earlier* traffic and reports that
     * the venue answered — when the venue has not yet said anything about this run.
     *
     * Under the default scope the step still passes, because that is the semantics every existing scenario
     * was written against and changing it silently is how a suite starts lying in the other direction. What
     * must not happen is the run staying quiet about it.
     */
    @Test
    fun `binding a message older than the run passes, but the run says so`() {
        val host = FakeHost()
        host.inbox += incoming("8", mapOf(35 to "8", 39 to "2")) // already there before the run begins
        val result = run(host, scenario(expect("8", FieldExpectation(39, Matcher.Exact("2")))))

        assertTrue(result.passed, "the default scope binds anything, as it always has")
        val binding = result.steps.single { it.kind == "binding" }
        assertTrue(binding.passed, "a caveat, not a verdict — the scenario did not ask for the guarantee")
        assertTrue(binding.detail!!.contains("before this run started"), "${binding.detail}")
        assertTrue(
            binding.detail!!.contains("this_run"),
            "it must name the way to make this a timeout instead: ${binding.detail}",
        )
    }

    @Test
    fun `under this_run an older message is not bindable, so the step times out honestly`() {
        val host = FakeHost()
        host.inbox += incoming("8", mapOf(35 to "8", 39 to "2"))
        val scenario =
            Scenario(
                id = "x",
                name = "fresh",
                steps = listOf(expectWith(timeoutMs = 50, FieldExpectation(39, Matcher.Exact("2")))),
                binding = BindScope.THIS_RUN,
            )
        val result = run(host, scenario)

        assertFalse(result.passed, "this run has not seen its reply, and says so rather than borrowing one")
        val timedOut = result.steps.single { it.kind == "expect" }
        assertTrue(timedOut.detail!!.contains("no 8"), "${timedOut.detail}")
        // Nothing to caveat: under THIS_RUN the older message was never bindable, so no step bound one.
        assertTrue(result.steps.none { it.kind == "binding" }, "the check is enforcement, not narration, here")
    }

    /**
     * The post-mortem must not hand out advice that cannot work. A message the binding scope excluded looks
     * exactly like one that arrived too late — matching, present, unbound — and "raise the timeout" costs a
     * whole run to disprove, because the step will refuse the same message just as fast next time.
     */
    @Test
    fun `a message excluded by scope is diagnosed as excluded, not as late`() {
        val host = FakeHost()
        host.inbox += incoming("8", mapOf(35 to "8", 39 to "2"))
        val scenario =
            Scenario(
                id = "x",
                name = "fresh",
                steps = listOf(expectWith(timeoutMs = 50, FieldExpectation(39, Matcher.Exact("2")))),
                binding = BindScope.THIS_RUN,
            )
        val diagnosis = run(host, scenario).steps.single { it.kind == "diagnosis" }
        assertTrue(
            diagnosis.detail!!.contains("already in the log when the run began"),
            "it must name the real reason: ${diagnosis.detail}",
        )
        assertFalse(
            diagnosis.detail!!.contains("Raise the timeout"),
            "advice that cannot work is worse than none: ${diagnosis.detail}",
        )
        assertTrue(diagnosis.detail!!.contains("clear the session in setup"), "and give the fix that can")
    }

    @Test
    fun `a message that arrives after the run starts is bound under this_run`() {
        val host = FakeHost()
        val scenario =
            Scenario(
                id = "x",
                name = "fresh",
                steps = listOf(expectWith(timeoutMs = 500, FieldExpectation(39, Matcher.Exact("2")))),
                binding = BindScope.THIS_RUN,
            )
        // Delivered mid-run, on the runner's own poll — this is the traffic the scenario provoked.
        host.onSleep = { host.inbox += incoming("8", mapOf(35 to "8", 39 to "2")) }
        val result = run(host, scenario)

        assertTrue(result.passed, "fresh traffic binds normally: ${result.steps.map { it.detail }}")
        assertTrue(result.steps.none { it.kind == "binding" })
    }

    /** A run that binds only fresh traffic under the default scope has nothing to caveat either. */
    @Test
    fun `a run that binds nothing stale stays silent`() {
        val host = FakeHost()
        host.onSleep = { host.inbox += incoming("8", mapOf(35 to "8", 39 to "2")) }
        val result = run(host, scenario(expectWith(timeoutMs = 500, FieldExpectation(39, Matcher.Exact("2")))))
        assertTrue(result.passed)
        assertTrue(result.steps.none { it.kind == "binding" }, "silence is the ordinary case")
    }

    // ------------------------------------------- messages the tool threw away before anything could see them

    /**
     * A session ingests at a bounded rate and silently discards the overrun. Every downstream mystery on a
     * fast feed reduces to that, so the run has to say it — otherwise a step that timed out on a reply the
     * venue provably sent reads as a venue bug or a bad predicate, and the engineer goes hunting for neither.
     */
    @Test
    fun `messages the tool discarded during a run are reported, and named as the tool's own fault`() {
        val host = FakeHost()
        host.loseDuringRun = 4_213
        val result = run(host, scenario(expectWith(timeoutMs = 30, FieldExpectation(35, Matcher.Exact("8")))))

        val ingest = result.steps.single { it.kind == "ingest" }
        assertTrue(ingest.detail!!.contains("4213 message(s)"), "the count: ${ingest.detail}")
        assertTrue(
            ingest.detail!!.contains("not a venue failure"),
            "it must name the tool, or the reader goes hunting the counterparty: ${ingest.detail}",
        )
        // OPEN traffic: the loss is a caveat on the report, not a verdict of its own. The run is already red
        // from the timeout; this row must not be what made it red.
        assertTrue(ingest.passed, "an OPEN run asserts nothing about traffic, so nothing here is void")
    }

    /**
     * STRICT is where a discard stops being a caveat and becomes a hole in the assertion. "Nothing
     * unexpected arrived" is not a claim a run can make about messages it threw away unread — so the check
     * is void rather than green. A strict run that lost messages and reported PASSED would be asserting
     * something it could not know.
     */
    @Test
    fun `under strict traffic a discard voids the run rather than passing it`() {
        val host = FakeHost()
        host.inbox += incoming("8", mapOf(35 to "8", 39 to "2"))
        host.loseDuringRun = 7
        val scenario =
            Scenario(
                id = "x",
                name = "strict",
                steps = listOf(expect("8", FieldExpectation(39, Matcher.Exact("2")))),
                traffic = TrafficMode.STRICT,
            )
        val result = run(host, scenario)

        assertFalse(result.passed, "a strict run cannot pass on evidence it discarded")
        val ingest = result.steps.single { it.kind == "ingest" }
        assertFalse(ingest.passed)
        assertTrue(ingest.detail!!.contains("void, not passed"), "${ingest.detail}")
        // And the traffic check does not run: a claim we have just said cannot be made is not then made.
        assertTrue(result.steps.none { it.kind == "traffic" }, "no traffic verdict may follow a void one")
        // The step itself still passed. The loss is the run's problem, not this expectation's.
        assertTrue(result.steps.single { it.kind == "expect" }.passed)
    }

    @Test
    fun `a run that lost nothing says nothing`() {
        val host = FakeHost()
        host.inbox += incoming("8", mapOf(35 to "8", 39 to "2"))
        val result = run(host, scenario(expect("8", FieldExpectation(39, Matcher.Exact("2")))))
        assertTrue(result.passed)
        assertTrue(result.steps.none { it.kind == "ingest" }, "silence is the ordinary case")
    }

    /** Only what THIS run lost. The counter is a session lifetime total; the report is about one run. */
    @Test
    fun `a discard that predates the run is not attributed to it`() {
        val host = FakeHost()
        host.discarded = 900 // lost long before this run started
        host.inbox += incoming("8", mapOf(35 to "8", 39 to "2"))
        val result = run(host, scenario(expect("8", FieldExpectation(39, Matcher.Exact("2")))))
        assertTrue(result.steps.none { it.kind == "ingest" }, "the run lost nothing; the session did, earlier")
    }

    /** Buy asks, sell should hear it, buy should get a clean AI. The sell-side leg is what times out. */
    private fun bothLegs(): Scenario =
        Scenario(
            id = "x",
            name = "t",
            steps =
                listOf(
                    ScenarioStep.Send("35=R|131=Q1|", session = "buy"),
                    ScenarioStep.Expect(
                        session = "sell",
                        match = MatchPredicate(messageType = "R"),
                        timeoutMs = 50,
                        expectation = Expectation(fields = listOf(FieldExpectation(131, Matcher.Presence)), messageType = "R"),
                    ),
                    ScenarioStep.Expect(
                        session = "buy",
                        match = MatchPredicate(messageType = "AI"),
                        timeoutMs = 1_000,
                        expectation =
                            Expectation(
                                fields = listOf(FieldExpectation(297, Matcher.Exact("0"))),
                                messageType = "AI",
                            ),
                    ),
                ),
        )

    // ----------------------------------------------------------------- helpers

    private fun run(host: FakeHost, scenario: Scenario, sessionMap: Map<String, String> = emptyMap()) =
        ScenarioRunner(host, pollMs = 10, now = { host.clock }).run(scenario, sessionMap)

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
        val sentTo = mutableListOf<String?>()
        val inbox = mutableListOf<FixMessage>()

        /**
         * Messages delivered on the runner's next poll, one per sleep — the fake's way of saying "this
         * arrived *during* the run". Seeding [inbox] models history the run inherited instead, and the two
         * are no longer interchangeable: the strict-traffic verdict excludes what predates the run, so a
         * surplus seeded into [inbox] is not a surplus at all.
         */
        val arriving = ArrayDeque<FixMessage>()
        var clock = 0L
        var stateOf: (String?) -> String? = { "LOGGED_ON" }
        var clearOk = true
        var connect: (String?) -> ConnectAttempt = { ConnectAttempt.Failed("no auto-connect in this fake") }
        var onSleep: () -> Unit = {}

        override fun resolve(raw: String, scope: MutableMap<String, String>, session: String?): String =
            FixMessageTemplate.evaluate(raw, emptyMap(), emptyMap(), scope, null)

        override fun send(raw: String, session: String?): Boolean {
            sent += raw
            sentTo += session
            return true
        }

        /** Per-session logs, for the both-legs scenarios. Empty = every session sees [inbox]. */
        val inboxes = mutableMapOf<String?, MutableList<FixMessage>>()

        fun on(session: String?): MutableList<FixMessage> = inboxes.getOrPut(session) { mutableListOf() }

        /** Lifetime count of messages the tool received and threw away — what the runner takes a delta of. */
        var discarded = 0L

        /**
         * Losses to apply the first time the runner reads messages, i.e. *after* it has snapshotted the
         * lifetime count. Setting [discarded] directly instead models a loss that predates the run, which the
         * report must not attribute to it.
         */
        var loseDuringRun = 0L
        private var lostYet = false

        override fun discarded(session: String?): Long = discarded

        override fun messages(session: String?): List<FixMessage> {
            if (!lostYet && loseDuringRun > 0) {
                discarded += loseDuringRun
                lostYet = true
            }
            return if (inboxes.isEmpty()) inbox.toList() else inboxes[session]?.toList().orEmpty()
        }

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

        override fun connectSession(session: String?): ConnectAttempt = connect(session)

        override fun sleep(ms: Long) {
            clock += ms
            arriving.removeFirstOrNull()?.let { inbox += it }
            onSleep()
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
