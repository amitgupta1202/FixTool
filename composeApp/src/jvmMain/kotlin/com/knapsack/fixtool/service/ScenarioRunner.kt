package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.scenario.MatchPredicate
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioResult
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.model.scenario.StepResult
import com.knapsack.fixtool.model.scenario.withIds

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

    /** The session's connection-state name (e.g. LOGGED_ON), or **null when the session doesn't exist**. */
    fun connectionState(session: String?): String?

    /**
     * A resolver for `reference` matchers. Resolves `${...}` against both the persistent scenario
     * [scope] (so a value sent on one session can be echo-matched in a response on any session) and
     * the session's incoming/outgoing message history (`${out.D.11}`-style refs).
     */
    fun referenceResolver(session: String?, scope: Map<String, String>): (String) -> String?

    /**
     * Adapt a captured message to the evaluator's view — **null when its wire order is unknown**, which
     * the caller must report rather than paper over. See [FixMessage.wireRaw].
     */
    fun view(message: FixMessage): MessageView?

    /** Clear a session's message log; returns false when the session doesn't exist. */
    fun clearMessages(session: String?): Boolean

    /** Reset a session's sequence numbers; returns false when the session doesn't exist. */
    fun resetSeqNum(session: String?, sender: Int?, target: Int?): Boolean

    fun sleep(ms: Long) = Thread.sleep(ms)
}

/**
 * Walks a [Scenario] deterministically — `setup` → `steps` → `teardown` (teardown always runs) — over
 * a single persistent variable scope, with no LLM in the loop. `Expect` steps consume the message
 * they match so successive expectations walk successive messages (partial-fill sequences); their
 * match-predicate values may be `${...}` expressions resolved against the scenario scope, so a step
 * binds to *the response to this run's order*, not just the first same-type message. Produces a
 * per-tag [ScenarioResult] that drives both CI and the in-app overlay.
 */
class ScenarioRunner(
    private val host: ScenarioHost,
    private val pollMs: Long = 100,
    private val now: () -> Long = { System.currentTimeMillis() },
    /** Called when an Expect step binds to a live message, so the UI can tint that row green/red. */
    private val onExpectMatched: (FixMessage, StepResult) -> Unit = { _, _ -> },
) {
    /**
     * [withIds] first, so every [StepResult] can name the step that produced it. It is deterministic, so
     * the ids the runner sees are the ids the same scenario has everywhere else — an un-normalized
     * scenario handed straight to the runner (a test, an inline scenario from the control surface) still
     * reports the ids its saved counterpart would carry.
     */
    fun run(scenario: Scenario): ScenarioResult = runIdentified(scenario.withIds())

    private fun runIdentified(scenario: Scenario): ScenarioResult {
        // Fail fast, by name, before touching anything: a missing/unconnected session otherwise
        // surfaces minutes later as a misleading Expect timeout.
        preflight(scenario)?.let { return ScenarioResult(scenario.name, false, listOf(it)) }

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
        // Teardown is best-effort cleanup — a teardown Expect/Wait that times out, or a ClearMessages whose
        // tab was closed mid-run, must not flip an otherwise-green verdict. Judge on the setup/steps phases
        // only; the teardown StepResults stay in `results`, so a cleanup problem is still visible in the
        // report, it just does not decide pass/fail. Vacuously true over an empty `results`, but preflight
        // has already rejected the zero-step scenario before we get here.
        val passed = results.none { it.phase != "teardown" && !it.passed }
        return ScenarioResult(scenario.name, passed, results)
    }

    /** Non-null = the reason this scenario cannot run at all. */
    @Suppress("ReturnCount")
    private fun preflight(scenario: Scenario): StepResult? {
        val all = scenario.setup + scenario.steps + scenario.teardown
        // An empty scenario must never report a green. The final verdict passes when no non-teardown step
        // failed, which is vacuously true over an empty result set — so a scenario with no steps at all would
        // report passed on every run while doing and checking nothing, a CI gate that is green precisely
        // because it looked at nothing. A Send-only / Wait-only scenario is deliberately NOT rejected here:
        // it does real work and its result is meaningful (a load driver, or the scope fixtures that assert on
        // what was sent). Only the degenerate zero-step case is the false green this guards.
        if (all.isEmpty()) {
            return preflightFailure(
                "This scenario has no steps — it would report passed on every run without sending or " +
                    "checking anything.",
            )
        }
        // A session whose logon the scenario itself waits for doesn't need to be LOGGED_ON up front.
        val waitCovered =
            all.filterIsInstance<ScenarioStep.Wait>()
                .filter { it.state?.equals("LOGGED_ON", ignoreCase = true) == true }
                .map { it.session }
                .toSet()
        val traffic = all.filter { it is ScenarioStep.Send || it is ScenarioStep.Expect }
        for (session in all.map { it.session }.distinct()) {
            val label = session ?: "(active session)"
            val state = host.connectionState(session)
                ?: return preflightFailure(
                    "session '$label' not found — connect it, or remap the step's session in the editor, then run again",
                )
            val needsLogon = traffic.any { it.session == session } && session !in waitCovered
            if (needsLogon && !state.equals("LOGGED_ON", ignoreCase = true)) {
                return preflightFailure("session '$label' is $state, not LOGGED_ON — connect it before running")
            }
        }
        return null
    }

    private fun preflightFailure(detail: String): StepResult =
        StepResult(-1, "preflight", "setup", passed = false, detail = detail)

    /** Every verdict names its step. The index says where it sat; the id says which step it was. */
    private fun runStep(
        step: ScenarioStep,
        index: Int,
        phase: String,
        scope: MutableMap<String, String>,
        consumed: MutableSet<FixMessage>,
    ): StepResult = execute(step, index, phase, scope, consumed).copy(stepId = step.stepId.ifBlank { null })

    private fun execute(
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
                val detail =
                    if (ok) resolved
                    else "send failed on '${label(step.session)}' (state=${host.connectionState(step.session) ?: "session not found"}): $resolved"
                StepResult(index, "send", phase, ok, detail = detail)
            }
            is ScenarioStep.Wait -> runWait(step, index, phase, scope)
            is ScenarioStep.Expect -> runExpect(step, index, phase, scope, consumed)
            is ScenarioStep.ClearMessages -> {
                val ok = host.clearMessages(step.session)
                StepResult(index, "clear", phase, ok, detail = if (ok) "cleared" else "session '${label(step.session)}' not found")
            }
            is ScenarioStep.ResetSeqNum -> {
                val ok = host.resetSeqNum(step.session, step.sender, step.target)
                StepResult(index, "reset", phase, ok, detail = if (ok) "reset-seqnum" else "session '${label(step.session)}' not found")
            }
        }

    @Suppress("ReturnCount")
    private fun runWait(step: ScenarioStep.Wait, index: Int, phase: String, scope: Map<String, String>): StepResult {
        if (step.state == null && step.match == null) {
            return StepResult(index, "wait", phase, true, detail = "nothing to wait for")
        }
        val match = step.match?.let { resolveMatch(it, step.session, scope) }
        val deadline = now() + step.timeoutMs
        while (true) {
            if (step.state != null && step.state.equals(host.connectionState(step.session), ignoreCase = true)) {
                return StepResult(index, "wait", phase, true, detail = "state=${step.state}")
            }
            if (match != null && host.messages(step.session).any { matches(it, null, null, match) }) {
                return StepResult(index, "wait", phase, true, detail = "matched")
            }
            if (now() >= deadline) {
                return StepResult(index, "wait", phase, false, detail = "timeout ${describeWaitTarget(step)}")
            }
            host.sleep(pollMs)
        }
    }

    private fun describeWaitTarget(step: ScenarioStep.Wait): String =
        "waiting for ${step.state ?: "a matching message"} on '${label(step.session)}' " +
            "(state=${host.connectionState(step.session) ?: "session not found"})"

    private fun runExpect(
        step: ScenarioStep.Expect,
        index: Int,
        phase: String,
        scope: Map<String, String>,
        consumed: MutableSet<FixMessage>,
    ): StepResult {
        // Resolve ${...} in the bind predicate once, up front — its inputs (the scenario scope) do
        // not change while this step polls.
        val match = step.match?.let { resolveMatch(it, step.session, scope) }
        val msgType = match?.messageType ?: step.expectation.messageType
        val direction = match?.direction ?: step.direction
        val deadline = now() + step.timeoutMs
        var target: FixMessage? = null
        while (target == null) {
            target = host.messages(step.session)
                .firstOrNull { it !in consumed && matches(it, msgType, direction, match) }
            if (target != null) break
            if (now() >= deadline) break
            host.sleep(pollMs)
        }
        if (target == null) {
            val constraints = match?.fields?.takeIf { it.isNotEmpty() }
                ?.joinToString(" AND ", prefix = " where ") { "${it.tag}=${it.value}" } ?: ""
            val detail = "no ${msgType ?: "matching"} message$constraints within ${step.timeoutMs}ms on " +
                "'${label(step.session)}' (state=${host.connectionState(step.session) ?: "session not found"}, " +
                "${host.messages(step.session).size} messages seen)"
            return StepResult(index, "expect", phase, false, detail = detail)
        }
        consumed.add(target)
        // No wire bytes, no verdict. The expectation's row order *is* half of what it asserts, so judging
        // it against a message whose order we had to invent would produce a result about a message nobody
        // sent — green or red, it would not be about the venue. A step that cannot be evaluated fails, and
        // it names the tool rather than the counterparty, because that is whose fault it is.
        val view = host.view(target)
        if (view == null) {
            val why =
                "FixTool has no wire bytes for the matched ${target.messageType} on '${label(step.session)}', " +
                    "so the order of its fields is unknown and this expectation cannot be evaluated. This is a " +
                    "FixTool limitation, not a venue failure — the message itself may be perfectly correct."
            val failed = StepResult(index, "expect", phase, false, detail = why, stepId = step.stepId.ifBlank { null })
            // Reported through the same channel as every other verdict, and that is the whole point of not
            // returning early here. onExpectMatched is what tints the matched message red in the grid and
            // hands the reconcile deep-link the message to show. Skipping it would have left the one step
            // that failed as the one message the grid does not mark — a run that is red in the report and
            // clean on the surface the tester actually looks at, which is a false green wearing a red hat.
            onExpectMatched(target, failed)
            return failed
        }
        val resolver = host.referenceResolver(step.session, scope)
        val tags = ExpectationEvaluator.evaluate(view, step.expectation, resolver)
        val result = StepResult(
            index,
            "expect",
            phase,
            tags.all { it.passed },
            detail = "messageType=${target.messageType}",
            tags = tags,
            // The grid's tint and the reconcile deep-link both come off this map, so the entry has to know
            // which step it belongs to — not merely which slot that step occupied during this run.
            stepId = step.stepId.ifBlank { null },
        )
        onExpectMatched(target, result)
        return result
    }

    /** Resolves `${...}` in the predicate's constraint values against the scenario scope. */
    private fun resolveMatch(predicate: MatchPredicate, session: String?, scope: Map<String, String>): MatchPredicate {
        if (predicate.fields.none { it.value.contains("\${") }) return predicate
        val resolver = host.referenceResolver(session, scope)
        return predicate.copy(
            fields = predicate.fields.map { tv ->
                if (tv.value.contains("\${")) tv.copy(value = resolver(tv.value) ?: tv.value) else tv
            },
        )
    }

    private fun label(session: String?): String = session ?: "(active session)"

    @Suppress("ReturnCount")
    private fun matches(msg: FixMessage, msgType: String?, direction: String?, predicate: MatchPredicate?): Boolean {
        val type = predicate?.messageType ?: msgType
        if (type != null && msg.messageType != type) return false
        val dir = predicate?.direction ?: direction
        if (dir != null && !directionMatches(msg, dir)) return false
        // Read through the SAME door the engine judges through. This used to ask FixMessage.valueOfTag, which
        // walks QuickFIX's parsed field maps and is blind to any tag inside a repeating group — it answers
        // null for one. So a bind constraint on a grouped tag could never match, and the step timed out
        // looking for a message that was sitting right there. Two deciders for "what is tag T in this
        // message", and the one deciding WHICH MESSAGE a step binds to was the weaker of them.
        val fields = predicate?.fields
        if (!fields.isNullOrEmpty()) {
            // A message we have no wire bytes for cannot be EXCLUDED by a field constraint either — refusing
            // to bind would make the step time out with "no matching message", which points at the venue for
            // something that is FixTool's own limitation, and hides a message that is sitting right there. It
            // binds, and the expect step then fails loudly and says whose fault it is.
            val wire = host.view(msg)?.fields() ?: return true
            fields.forEach { tv ->
                // ANY occurrence of the tag satisfies the constraint. `firstOrNull` consulted only occurrence
                // #1, so a constraint on a repeated/grouped tag whose match sat in a later copy could never
                // bind — the step timed out looking for a message that was sitting right there.
                if (wire.none { it.first == tv.tag && it.second == tv.value }) return false
            }
        }
        return true
    }

    private fun directionMatches(msg: FixMessage, direction: String): Boolean =
        when (direction.lowercase()) {
            "in", "incoming" -> msg.direction == FixMessage.Direction.INCOMING
            "out", "outgoing" -> msg.direction == FixMessage.Direction.OUTGOING
            else -> true
        }
}
