package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.MatchPredicate
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.model.scenario.StepResult
import com.knapsack.fixtool.model.scenario.TagValue
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

    private val viewTags = java.util.IdentityHashMap<FixMessage, Map<Int, String>>()

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
            if (message in noWireOrder) null else MapView(viewTags[message] ?: emptyMap())

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
}
