package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.scenario.MatchPredicate
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioResult
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.model.scenario.StepResult

/**
 * The primitives the [ScenarioRunner] needs from its environment. Kept behind an interface so the
 * runner is pure and unit-testable with a fake host; the real implementation lives in the control
 * layer (which owns sessions, sending, and resolution).
 */
interface ScenarioHost {
    /** Resolve `${...}` in [raw] against the persistent [scope] and the session's message context. */
    fun resolve(raw: String, scope: MutableMap<String, String>, session: String?): String

    /** Send a fully-resolved raw message to [session]; returns true on success. */
    fun send(raw: String, session: String?): Boolean

    /** A chronological snapshot (oldest first) of a session's messages. */
    fun messages(session: String?): List<FixMessage>

    /** The session's connection-state name (e.g. LOGGED_ON), or null. */
    fun connectionState(session: String?): String?

    /**
     * A resolver for `reference` matchers. Resolves `${...}` against both the persistent scenario
     * [scope] (so a value sent on one session can be echo-matched in a response on any session) and
     * the session's incoming/outgoing message history (`${out.D.11}`-style refs).
     */
    fun referenceResolver(session: String?, scope: Map<String, String>): (String) -> String?

    /** Adapt a captured message to the evaluator's view. */
    fun view(message: FixMessage): MessageView

    fun clearMessages(session: String?)

    fun resetSeqNum(session: String?, sender: Int?, target: Int?)

    fun sleep(ms: Long) = Thread.sleep(ms)
}

/**
 * Walks a [Scenario] deterministically — `setup` → `steps` → `teardown` (teardown always runs) — over
 * a single persistent variable scope, with no LLM in the loop. `Expect` steps consume the message
 * they match so successive expectations walk successive messages (partial-fill sequences). Produces a
 * per-tag [ScenarioResult] that drives both CI and the in-app overlay.
 */
class ScenarioRunner(
    private val host: ScenarioHost,
    private val pollMs: Long = 100,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    fun run(scenario: Scenario): ScenarioResult {
        val scope = mutableMapOf<String, String>()
        val consumed = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<FixMessage, Boolean>())
        val results = mutableListOf<StepResult>()

        var abort = false
        for ((i, step) in scenario.setup.withIndex()) {
            val r = runStep(step, i, "setup", scope, consumed)
            results += r
            if (!r.passed) { abort = true; break }
        }
        if (!abort) {
            for ((i, step) in scenario.steps.withIndex()) {
                val r = runStep(step, i, "steps", scope, consumed)
                results += r
                if (!r.passed) break
            }
        }
        for ((i, step) in scenario.teardown.withIndex()) {
            results += runStep(step, i, "teardown", scope, consumed)
        }
        return ScenarioResult(scenario.name, results.all { it.passed }, results)
    }

    private fun runStep(
        step: ScenarioStep,
        index: Int,
        phase: String,
        scope: MutableMap<String, String>,
        consumed: MutableSet<FixMessage>,
    ): StepResult =
        when (step) {
            is ScenarioStep.Send -> {
                val resolved = host.resolve(step.raw, scope, step.session)
                val ok = host.send(resolved, step.session)
                StepResult(index, "send", phase, ok, detail = if (ok) resolved else "send failed: $resolved")
            }
            is ScenarioStep.Wait -> runWait(step, index, phase)
            is ScenarioStep.Expect -> runExpect(step, index, phase, scope, consumed)
            is ScenarioStep.ClearMessages -> {
                host.clearMessages(step.session)
                StepResult(index, "clear", phase, true, detail = "cleared")
            }
            is ScenarioStep.ResetSeqNum -> {
                host.resetSeqNum(step.session, step.sender, step.target)
                StepResult(index, "reset", phase, true, detail = "reset-seqnum")
            }
        }

    @Suppress("ReturnCount")
    private fun runWait(step: ScenarioStep.Wait, index: Int, phase: String): StepResult {
        if (step.state == null && step.match == null) {
            return StepResult(index, "wait", phase, true, detail = "nothing to wait for")
        }
        val deadline = now() + step.timeoutMs
        while (true) {
            if (step.state != null && step.state.equals(host.connectionState(step.session), ignoreCase = true)) {
                return StepResult(index, "wait", phase, true, detail = "state=${step.state}")
            }
            if (step.match != null && host.messages(step.session).any { matches(it, null, null, step.match) }) {
                return StepResult(index, "wait", phase, true, detail = "matched")
            }
            if (now() >= deadline) return StepResult(index, "wait", phase, false, detail = "timeout")
            host.sleep(pollMs)
        }
    }

    private fun runExpect(
        step: ScenarioStep.Expect,
        index: Int,
        phase: String,
        scope: Map<String, String>,
        consumed: MutableSet<FixMessage>,
    ): StepResult {
        val msgType = step.match?.messageType ?: step.expectation.messageType
        val direction = step.match?.direction ?: step.direction
        val deadline = now() + step.timeoutMs
        var target: FixMessage? = null
        while (target == null) {
            target = host.messages(step.session)
                .firstOrNull { it !in consumed && matches(it, msgType, direction, step.match) }
            if (target != null) break
            if (now() >= deadline) break
            host.sleep(pollMs)
        }
        if (target == null) {
            val detail = "no ${msgType ?: "matching"} message within ${step.timeoutMs}ms"
            return StepResult(index, "expect", phase, false, detail = detail)
        }
        consumed.add(target)
        val resolver = host.referenceResolver(step.session, scope)
        val tags = ExpectationEvaluator.evaluate(host.view(target), step.expectation, resolver)
        return StepResult(
            index,
            "expect",
            phase,
            tags.all { it.passed },
            detail = "messageType=${target.messageType}",
            tags = tags,
        )
    }

    @Suppress("ReturnCount")
    private fun matches(msg: FixMessage, msgType: String?, direction: String?, predicate: MatchPredicate?): Boolean {
        val type = predicate?.messageType ?: msgType
        if (type != null && msg.messageType != type) return false
        val dir = predicate?.direction ?: direction
        if (dir != null && !directionMatches(msg, dir)) return false
        predicate?.fields?.forEach { if (msg.valueOfTag(it.tag) != it.value) return false }
        return true
    }

    private fun directionMatches(msg: FixMessage, direction: String): Boolean =
        when (direction.lowercase()) {
            "in", "incoming" -> msg.direction == FixMessage.Direction.INCOMING
            "out", "outgoing" -> msg.direction == FixMessage.Direction.OUTGOING
            else -> true
        }
}
