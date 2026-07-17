package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.scenario.MatchOp
import com.knapsack.fixtool.model.scenario.MatchPredicate
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioResult
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.model.scenario.ScenarioVariable
import com.knapsack.fixtool.model.scenario.StepResult
import com.knapsack.fixtool.model.scenario.TagValue
import com.knapsack.fixtool.model.scenario.TrafficMode
import com.knapsack.fixtool.model.scenario.withIds
import com.knapsack.fixtool.model.scenario.withSessions

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

    /**
     * Try to bring [session] up — reconnect it if it exists, else connect the saved profile that
     * carries its name. [ConnectAttempt.Started] means the attempt is under way, not that logon
     * succeeded: the runner owns the bounded wait for LOGGED_ON. The default can connect nothing,
     * so a host without connectivity (a test fake, a read-only surface) keeps the fail-fast preflight.
     */
    fun connectSession(session: String?): ConnectAttempt = ConnectAttempt.Failed("this host cannot connect sessions")

    fun sleep(ms: Long) = Thread.sleep(ms)
}

/** The host's answer to [ScenarioHost.connectSession]: an attempt is under way, or why none could be made. */
sealed interface ConnectAttempt {
    /** A connect was initiated from [profileName]; the session may still be short of LOGGED_ON. */
    data class Started(val profileName: String) : ConnectAttempt

    /** No attempt was possible — [reason] is the sentence the report gets. */
    data class Failed(val reason: String) : ConnectAttempt
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
    /**
     * How long a [TrafficMode.STRICT] run listens after its last main-phase step before judging that
     * nothing else came. "The venue sent nothing extra" is a claim about time — the check can only ever
     * mean "nothing extra by then" — and a check at the instant the last Expect binds would miss the
     * surplus by racing it. The price is this much wall-clock on every strict run, green or red.
     */
    private val settleMs: Long = 1_000,
    /** How long preflight waits for a session it auto-connected to come up before giving up. */
    private val connectTimeoutMs: Long = 10_000,
) {
    /**
     * [withIds] first, so every [StepResult] can name the step that produced it. It is deterministic, so
     * the ids the runner sees are the ids the same scenario has everywhere else — an un-normalized
     * scenario handed straight to the runner (a test, an inline scenario from the control surface) still
     * reports the ids its saved counterpart would carry.
     *
     * [sessionMap] is the control surface's throwaway remap ([Scenario.withSessions]): this one run is
     * re-aimed, nothing is persisted, and the report still attributes to the scenario as saved. The
     * durable form of the same idea is a materialized copy — see the ViewModel's save-remapped-copy.
     */
    fun run(scenario: Scenario, sessionMap: Map<String, String> = emptyMap()): ScenarioResult =
        runIdentified(scenario.withIds().withSessions(sessionMap))

    private fun runIdentified(scenario: Scenario): ScenarioResult {
        // Preflight, by name, before any step runs: a missing/unconnected session otherwise surfaces
        // minutes later as a misleading Expect timeout. It gets one recovery attempt first — the host
        // connects what the scenario needs, and each success is a passing "connect" row in the report,
        // so an automated run tells the same story a hand-connected one would.
        val results = mutableListOf<StepResult>()
        preflight(scenario, results)?.let { return ScenarioResult(scenario.name, false, results + it) }

        val scope = mutableMapOf<String, String>()
        val consumed = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<FixMessage, Boolean>())
        // Which step wrote each name. Diffed around every step rather than instrumented into the template
        // evaluator, which does not know steps exist; first writer wins, matching the scope's own semantics.
        val mintedBy = mutableMapOf<String, String?>()

        fun ran(step: ScenarioStep, r: StepResult): StepResult {
            scope.keys.forEach { name -> mintedBy.putIfAbsent(name, step.stepId.ifBlank { null }) }
            return r
        }

        // A muted step is skipped whole: nothing sent, nothing consumed, no StepResult — the run reads
        // as if the step were not there, which is the promise the editor's mute toggle makes. The index
        // still counts the muted step, so a result's stepIndex keeps naming the same list position the
        // editor and the rail show.
        var abort = false
        for ((i, step) in scenario.setup.withIndex()) {
            if (step.muted) continue
            val r = ran(step, runStep(step, i, "setup", scope, consumed))
            results += r
            if (!r.passed) { abort = true; break }
        }
        if (!abort) {
            for ((i, step) in scenario.steps.withIndex()) {
                if (step.muted) continue
                val r = ran(step, runStep(step, i, "steps", scope, consumed))
                results += r
                if (!r.passed) break
            }
        }
        // The stream-level verdict, judged before teardown (whose own sends provoke traffic that is
        // nobody's surplus) and only on a run that is otherwise green: after a failed step, every message
        // its successors never got to bind is "unexpected", and a wall of strays pointing away from the
        // real failure is worse than not judging. Skipped entirely under OPEN — no result row, because
        // the check did not run, not because it passed.
        if (scenario.traffic == TrafficMode.STRICT && results.all { it.passed }) {
            results += trafficCheck(scenario, consumed)
        }
        for ((i, step) in scenario.teardown.withIndex()) {
            if (step.muted) continue
            results += ran(step, runStep(step, i, "teardown", scope, consumed))
        }
        // Teardown is best-effort cleanup — a teardown Expect/Wait that times out, or a ClearMessages whose
        // tab was closed mid-run, must not flip an otherwise-green verdict. Judge on the setup/steps phases
        // only; the teardown StepResults stay in `results`, so a cleanup problem is still visible in the
        // report, it just does not decide pass/fail. Vacuously true over an empty `results`, but preflight
        // has already rejected the zero-step scenario before we get here.
        val passed = results.none { it.phase != "teardown" && !it.passed }
        // The final scope, in mint order (the scope map is insertion-ordered) — see [ScenarioVariable].
        val variables = scope.map { (name, value) -> ScenarioVariable(name, value, mintedBy[name]) }
        return ScenarioResult(scenario.name, passed, results, variables)
    }

    /** Non-null = the reason this scenario cannot run at all. Successful auto-connects append to [results]. */
    @Suppress("ReturnCount")
    private fun preflight(scenario: Scenario, results: MutableList<StepResult>): StepResult? {
        // Only the steps that will run are preflighted: a muted step's session needn't exist, let alone be
        // LOGGED_ON — parking the broken leg is half of what muting is for.
        val all = (scenario.setup + scenario.steps + scenario.teardown).filterNot { it.muted }
        // An empty scenario must never report a green. The final verdict passes when no non-teardown step
        // failed, which is vacuously true over an empty result set — so a scenario with no steps at all would
        // report passed on every run while doing and checking nothing, a CI gate that is green precisely
        // because it looked at nothing. A Send-only / Wait-only scenario is deliberately NOT rejected here:
        // it does real work and its result is meaningful (a load driver, or the scope fixtures that assert on
        // what was sent). Only the degenerate zero-step case is the false green this guards — and a scenario
        // muted down to nothing is the same false green wearing a mute button.
        if (all.isEmpty()) {
            val everyStepMuted = (scenario.setup + scenario.steps + scenario.teardown).isNotEmpty()
            return preflightFailure(
                if (everyStepMuted) {
                    "Every step of this scenario is muted — it would report passed on every run without " +
                        "sending or checking anything. Unmute at least one step."
                } else {
                    "This scenario has no steps — it would report passed on every run without sending or " +
                        "checking anything."
                },
            )
        }
        // A session whose logon the scenario itself waits for doesn't need to be LOGGED_ON up front.
        val waitCovered =
            all.filterIsInstance<ScenarioStep.Wait>()
                .filter { it.state?.equals("LOGGED_ON", ignoreCase = true) == true }
                .map { it.session }
                .toSet()
        val traffic = all.filter { it is ScenarioStep.Send || it is ScenarioStep.Expect }
        // A session that would once have failed the run right here gets one recovery attempt first: the
        // host reconnects it (or connects the saved profile carrying its name), and preflight waits —
        // bounded — for it to come up. ONLY sessions that would have failed are touched: an existing
        // session whose logon the scenario itself waits for is the scenario's business, not preflight's.
        val pending = mutableListOf<PendingConnect>()
        for (session in all.map { it.session }.distinct()) {
            val label = session ?: "(active session)"
            val state = host.connectionState(session)
            val needsLogon = traffic.any { it.session == session } && session !in waitCovered
            if (sessionReady(state, needsLogon)) continue
            when (val attempt = host.connectSession(session)) {
                is ConnectAttempt.Failed -> return preflightFailure(
                    if (state == null) {
                        "session '$label' not found, and auto-connect could not bring it up (${attempt.reason}) — " +
                            "connect it, or remap the step's session in the editor, then run again"
                    } else {
                        "session '$label' is $state, not LOGGED_ON, and auto-connect could not bring it up " +
                            "(${attempt.reason}) — connect it before running"
                    },
                )
                is ConnectAttempt.Started -> pending += PendingConnect(session, label, state, needsLogon, attempt.profileName)
            }
        }
        // Every attempt was initiated above before any wait below, so slow logons overlap and one
        // deadline covers the lot. A session only clear/reset steps touch — or one whose logon the
        // scenario waits for itself — need merely exist; the rest must reach LOGGED_ON.
        val deadline = now() + connectTimeoutMs
        for (p in pending) {
            var state = host.connectionState(p.session)
            while (!sessionReady(state, p.needsLogon) && now() < deadline) {
                host.sleep(pollMs)
                state = host.connectionState(p.session)
            }
            if (!sessionReady(state, p.needsLogon)) {
                return preflightFailure(
                    "auto-connect started profile '${p.profileName}' for session '${p.label}', but it did not " +
                        "reach LOGGED_ON within ${connectTimeoutMs}ms (state=${state ?: "session not found"}). " +
                        "If the profile is an acceptor, the counterparty must initiate the logon.",
                )
            }
            // Index -1, no stepId: like the preflight failure and the strict-traffic verdict, this is a
            // run-level row — no scenario step produced it, so none can be blamed for or edited from it.
            results += StepResult(
                -1,
                "connect",
                "setup",
                passed = true,
                detail = "auto-connected session '${p.label}' via profile '${p.profileName}' " +
                    "(was ${p.wasState ?: "not found"}, now $state)",
            )
        }
        return null
    }

    private fun sessionReady(state: String?, needsLogon: Boolean): Boolean =
        state != null && (!needsLogon || state.equals("LOGGED_ON", ignoreCase = true))

    /** A not-ready session whose connect the host has initiated; preflight still owes it a bounded wait. */
    private data class PendingConnect(
        val session: String?,
        val label: String,
        val wasState: String?,
        val needsLogon: Boolean,
        val profileName: String,
    )

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
        scope: MutableMap<String, String>,
        consumed: MutableSet<FixMessage>,
    ): StepResult {
        // Resolve ${...} in the bind predicate once, up front — its inputs (the scenario scope) do
        // not change while this step polls.
        val match = step.match?.let { resolveMatch(it, step.session, scope) }
        val msgType = match?.messageType ?: step.expectation.messageType
        val direction = match?.direction ?: step.direction
        val deadline = now() + step.timeoutMs
        val occurrence = match?.occurrence
        var target: FixMessage? = null
        // Set when an absolute `occurrence` points at a message an earlier step already bound. That is a hard
        // fail — two steps cannot own one message — and distinct from "the N-th has not arrived yet", which
        // keeps polling until the deadline.
        var takenByEarlierStep = false
        while (target == null && !takenByEarlierStep) {
            if (occurrence != null) {
                // Absolute position over the type+direction+fields-filtered snapshot, 1-based. The consumed
                // cursor does not shift the index — "the 2nd ExecutionReport" is the same message whatever the
                // earlier steps took — but a message already taken cannot be re-bound here.
                val nth = host.messages(step.session)
                    .filter { matches(it, msgType, direction, match) }
                    .getOrNull(occurrence - 1)
                when {
                    nth == null -> Unit // fewer than N have arrived; keep polling
                    nth in consumed -> takenByEarlierStep = true
                    else -> target = nth
                }
            } else {
                target = host.messages(step.session)
                    .firstOrNull { it !in consumed && matches(it, msgType, direction, match) }
            }
            if (target != null || takenByEarlierStep) break
            if (now() >= deadline) break
            host.sleep(pollMs)
        }
        if (target == null) {
            val constraints = match?.fields?.takeIf { it.isNotEmpty() }
                ?.joinToString(" AND ", prefix = " where ") { describeConstraint(it) } ?: ""
            val where = "on '${label(step.session)}' " +
                "(state=${host.connectionState(step.session) ?: "session not found"}, " +
                "${host.messages(step.session).size} messages seen)"
            val detail = when {
                takenByEarlierStep ->
                    "the ${ordinal(occurrence!!)} ${msgType ?: "matching"} message$constraints was already matched " +
                        "by an earlier step — two steps cannot bind the same message — $where"
                occurrence != null ->
                    "fewer than $occurrence ${msgType ?: "matching"} message$constraints within " +
                        "${step.timeoutMs}ms $where"
                else ->
                    "no ${msgType ?: "matching"} message$constraints within ${step.timeoutMs}ms $where"
            }
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
        // Capture-into-scope (bindAs): rows that name a variable write the value they paired with. Read
        // off the evaluator's own results, not re-derived from the wire — the row↔occurrence pairing has
        // ONE decider, and a second walk here would eventually disagree with it. A row that paired with
        // nothing (MISSING) captures nothing; whether the row PASSED is irrelevant — the value was
        // observed, and teardown (which runs after a failure) may need it.
        step.expectation.fields.forEachIndexed { rowIndex, fe ->
            val name = fe.bindAs ?: return@forEachIndexed
            tags.firstOrNull { it.index == rowIndex }?.actual?.let { scope[name] = it }
        }
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

    /**
     * The [TrafficMode.STRICT] verdict: listens for [settleMs], then reports every incoming
     * application-level message the run's Expects never bound — or one green row saying there were none.
     * A green row, not silence: the run *checked* something here, and a report line is how it says so.
     *
     * Scope is the sessions the setup/steps phases touch (teardown's are not judged — its traffic happens
     * after this verdict), deduplicated by message identity because `null` and a named session can be the
     * same underlying tab. The verdict is a run-level claim, so like preflight it wears index -1 and no
     * stepId — the rail names it by kind, and no reconcile route is offered for it (there is no
     * expectation to repair; the fix is an Expect for the surplus, or [TrafficMode.OPEN]).
     */
    private fun trafficCheck(scenario: Scenario, consumed: Set<FixMessage>): StepResult {
        val settled = now() + settleMs
        while (now() < settled) host.sleep(pollMs)
        val sessions = (scenario.setup + scenario.steps).filterNot { it.muted }.map { it.session }.distinct()
        val strays = mutableListOf<FixMessage>()
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<FixMessage, Boolean>())
        for (session in sessions) {
            host.messages(session)
                .filter { it.direction == FixMessage.Direction.INCOMING }
                .filterNot { it in consumed || it.messageType in SESSION_ADMIN_TYPES }
                .forEach { if (seen.add(it)) strays += it }
        }
        if (strays.isEmpty()) {
            return StepResult(-1, "traffic", "steps", passed = true, detail = "no unexpected incoming messages (settled ${settleMs}ms)")
        }
        val listed = strays.take(MAX_LISTED_STRAYS).joinToString(", ") { it.messageType } +
            (if (strays.size > MAX_LISTED_STRAYS) " +${strays.size - MAX_LISTED_STRAYS} more" else "")
        val result = StepResult(
            -1,
            "traffic",
            "steps",
            passed = false,
            detail =
                "traffic is strict, and ${strays.size} incoming message(s) were never bound by any expect: " +
                    "$listed. Every unbound message is marked in the grid. Add an expect for what the venue " +
                    "now sends, or set the scenario's traffic back to open.",
        )
        // Through the SAME channel as an Expect verdict, for the same reason as the no-wire-bytes red:
        // this is what tints the surplus in the grid. Without it the report says "3 unexpected messages"
        // over a grid where nothing is marked, and the tester is left hunting for which three.
        strays.forEach { onExpectMatched(it, result) }
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
                // bind — the step timed out looking for a message that was sitting right there. PRESENT/ABSENT
                // test existence regardless of value — the replay-safe discriminator for a message that differs
                // only by carrying (or not) a correlation id whose value is minted fresh each run.
                val ok = when (tv.op) {
                    MatchOp.EQ -> wire.any { it.first == tv.tag && it.second == tv.value }
                    MatchOp.PRESENT -> wire.any { it.first == tv.tag }
                    MatchOp.ABSENT -> wire.none { it.first == tv.tag }
                }
                if (!ok) return false
            }
        }
        return true
    }

    private fun describeConstraint(tv: TagValue): String =
        when (tv.op) {
            MatchOp.EQ -> "${tv.tag}=${tv.value}"
            MatchOp.PRESENT -> "${tv.tag} present"
            MatchOp.ABSENT -> "${tv.tag} absent"
        }

    private fun ordinal(n: Int): String {
        val suffix = if (n % 100 in 11..13) {
            "th"
        } else {
            when (n % 10) {
                1 -> "st"
                2 -> "nd"
                3 -> "rd"
                else -> "th"
            }
        }
        return "$n$suffix"
    }

    private fun directionMatches(msg: FixMessage, direction: String): Boolean =
        when (direction.lowercase()) {
            "in", "incoming" -> msg.direction == FixMessage.Direction.INCOMING
            "out", "outgoing" -> msg.direction == FixMessage.Direction.OUTGOING
            else -> true
        }

    private companion object {
        /**
         * The envelope of the stream: session administration a venue may send any number of times without
         * it meaning anything about the flow under test — the [TrafficMode.STRICT] counterpart of the
         * header/trailer tags a STRICT expectation never counts as extras. Deliberately NOT here: Logout
         * (5) and session Reject (3). A goodbye or a session-level refusal nobody asked for is exactly the
         * surplus a strict run exists to report.
         */
        val SESSION_ADMIN_TYPES = setOf("0", "1", "2", "4", "A")

        /** Strays named in the detail line before "+N more" — the grid marks every one regardless. */
        const val MAX_LISTED_STRAYS = 6
    }
}
