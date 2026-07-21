package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.scenario.MatchOp
import com.knapsack.fixtool.model.scenario.MatchPredicate
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioResult
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.model.scenario.ScenarioVariable
import com.knapsack.fixtool.model.scenario.StepResult
import com.knapsack.fixtool.model.scenario.TagResult
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

    /**
     * Messages this session received and discarded before anything could see them — a lifetime count, so
     * callers take a delta. 0 from a host that cannot tell, which reads the same as "nothing was lost": the
     * check this feeds only ever reports an increase, so a host with no answer stays silent rather than
     * inventing a reassurance.
     */
    fun discarded(session: String?): Long = 0

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
        // Lifetime discard counts before a single step runs, so the check below reports what *this run* lost
        // rather than what the session has lost since it connected.
        val ingestBefore = runSessions(scenario).associateWith { host.discarded(it) }
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
        var failure: Failure? = null
        // `consumed` grows by exactly one when an Expect binds, so its size across a step is the one honest
        // answer to "did this step find anything" — no second bookkeeping to drift out of step with it.
        for ((i, step) in scenario.setup.withIndex()) {
            if (step.muted) continue
            val held = consumed.size
            val r = ran(step, runStep(step, i, "setup", scope, consumed))
            results += r
            if (!r.passed) {
                abort = true
                failure = Failure(step, i, "setup", r, consumed.size == held)
                break
            }
        }
        if (!abort) {
            for ((i, step) in scenario.steps.withIndex()) {
                if (step.muted) continue
                val held = consumed.size
                val r = ran(step, runStep(step, i, "steps", scope, consumed))
                results += r
                if (!r.passed) {
                    failure = Failure(step, i, "steps", r, consumed.size == held)
                    break
                }
            }
        }
        // Before both the post-mortem and the strict verdict: what a run never saw shapes how the rest of the
        // report should be read, and in STRICT it decides whether the traffic claim may be made at all.
        ingestCheck(scenario, ingestBefore)?.let { results += it }
        // The post-mortem, before teardown — whose own sends provoke replies that are nobody's evidence,
        // for the same reason the strict-traffic verdict is judged before it. It costs no wall-clock: the
        // step that failed has already spent its whole timeout polling, so whatever there is to see has
        // arrived. See [PostMortem] for what it may and may not do.
        failure?.let { f ->
            val diagnosis = PostMortem(scenario, f, scope, consumed).diagnose()
            if (diagnosis.isNotEmpty()) {
                val at = results.indexOfFirst { it === f.result }
                if (at >= 0) results[at] = f.result.copy(detail = f.result.detail.orEmpty() + POINTER)
                results += diagnosis
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
                // Excluded fields come off BEFORE resolve, not after. `resolve` is a whole-string regex
                // (FixMessageTemplate.evaluate) that never parses fields, so a `${id = uuid:20}` sitting
                // in an excluded row would still execute and bind a scenario variable that nothing ever
                // sends — a later `${id}` would then reference a value the venue never saw. Strip first
                // and an excluded field is inert: it mints nothing, references nothing, sends nothing.
                val resolved = host.resolve(SendFields.wire(step.raw), scope, step.session)
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
        val sessions = runSessions(scenario)
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

    /**
     * The step whose failure stopped the run — what a [PostMortem] is a post-mortem *of*.
     *
     * [boundNothing] is the whole precondition: only a step that failed by *not finding* a message has an
     * absence to explain. An Expect that bound one and then failed on its tags has its actual in hand, the
     * reconcile view already opens on it, and a post-mortem would do nothing but re-report that same message
     * as if it were evidence about something else.
     */
    private data class Failure(
        val step: ScenarioStep,
        val index: Int,
        val phase: String,
        val result: StepResult,
        val boundNothing: Boolean,
    )

    /** Why a same-type message on the failed step's own session did not become its match. */
    private enum class NearMiss { TAKEN, LATE, REJECTED }

    /** An `Expect` the run never reached: what a message nobody bound can be held up against. */
    private data class PendingExpect(
        val step: ScenarioStep.Expect,
        val index: Int,
        val phase: String,
    )

    /**
     * **The presences that explain an absence.**
     *
     * A run reports the first step that failed, and an `Expect` fails by naming what did *not* arrive. That
     * verdict is correct and, alone, often useless. The buy side sends an order; the venue rejects it
     * straight back; the sell side is therefore never told about it; and the report blames the sell side's
     * expect for a silence whose cause is sitting in the buy side's log — unbound, unexamined, and discarded
     * when the run ends. The evidence was always there. Nothing ever looked at it.
     *
     * This looks, once the run has already gone red, and answers three questions in order:
     *
     * 1. **Did the right message arrive here and fail to bind?** A message of the expected type can be
     *    sitting on the expected session and never bind, because one [MatchPredicate] constraint disagreed.
     *    Today that is indistinguishable in the report from nothing having arrived at all. It names the
     *    constraint that rejected it — and says to fix the predicate, because repairing the *assertion* rows
     *    would leave the step binding nothing on the next run just the same.
     * 2. **Was the only candidate already taken?** An authoring bug (two steps racing for one message),
     *    not a venue bug, and it reads nothing like one.
     * 3. **What arrived anywhere else that no step bound?** Paired, where possible, with an `Expect` the run
     *    never reached, and judged against it by the same [ExpectationEvaluator] the step itself would have
     *    used — so the report can say *the reject came back and here is the tag that differs* instead of
     *    merely *the other side heard nothing*.
     *
     * **It has no vote.** Nothing here consumes a message, alters a verdict, or moves the blame: the failing
     * step, its index, and the whole first sentence of its detail are exactly what they were before. A
     * pairing is a guess; it is offered only when the paired step's own bind predicate would have accepted
     * the message, it is capped, and it says in words that it is a guess. The alternative — letting a
     * plausible pairing satisfy a step — is out-of-order matching, which is precisely what strict ordering
     * exists to prevent, and it would buy a nicer report at the cost of the property the model is built on.
     */
    private inner class PostMortem(
        private val scenario: Scenario,
        private val failure: Failure,
        private val scope: Map<String, String>,
        private val consumed: Set<FixMessage>,
    ) {
        private val out = mutableListOf<StepResult>()
        private val diagnosed =
            java.util.Collections.newSetFromMap(java.util.IdentityHashMap<FixMessage, Boolean>())
        private var suppressed = 0

        fun diagnose(): List<StepResult> {
            // Only a step that failed by *not finding* something has an absence to explain. A failed Send,
            // a failed clear, a preflight refusal — those already say all there is to say — and so does an
            // Expect that bound a message and failed judging it. See [Failure.boundNothing].
            if (failure.result.kind != "expect" && failure.result.kind != "wait") return emptyList()
            if (!failure.boundNothing) return emptyList()
            (failure.step as? ScenarioStep.Expect)?.let { nearMisses(it) }
            strays()
            if (suppressed > 0) {
                out +=
                    row(
                        at = -1,
                        stepId = null,
                        phase = failure.phase,
                        passed = false,
                        detail = "+$suppressed further unbound message(s) were not diagnosed.",
                    )
            }
            return out
        }

        // -------------------------------------------------- Q1/Q2: the failed step's own session

        private fun nearMisses(step: ScenarioStep.Expect) {
            val match = step.match?.let { resolveMatch(it, step.session, scope) }
            val type = match?.messageType ?: step.expectation.messageType
            val direction = match?.direction ?: step.direction
            val session = label(step.session)
            val here =
                newestFirst(host.messages(step.session))
                    .filter { type == null || it.messageType == type }
            val named = type ?: "matching"
            val occurrence = match?.occurrence
            // Every same-type message on this session is this pass's business, row or no row. Without the
            // mark, one it deliberately stays quiet about would resurface downstream as a stray and be
            // reported as a type "no expect step looks for" — while the step that just timed out looking for
            // exactly that type sits two lines above it in the same report.
            diagnosed += here
            // Classify by WHY, not one row per message. On a market-data session the same story repeats
            // hundreds of times a second — six rows each saying "262 did not match" is six copies of one
            // sentence, and it pushes the sentence that matters out of the report. Newest first, so the
            // first message into a bucket is the newest example of it.
            val buckets = LinkedHashMap<Pair<NearMiss, String>, MutableList<FixMessage>>()
            val examined = here.take(SCAN_LIMIT)
            for (m in examined) {
                val binds = matches(m, type, direction, match)
                // An `occurrence` step that timed out did so because too few arrived, not because any one
                // that did was wrong — and its own detail already says "fewer than N".
                if (binds && occurrence != null) continue
                val key =
                    when {
                        binds && m in consumed -> NearMiss.TAKEN to ""
                        binds -> NearMiss.LATE to ""
                        else -> NearMiss.REJECTED to rejections(match, direction, m).joinToString("; ")
                    }
                buckets.getOrPut(key) { mutableListOf() } += m
            }
            for ((key, group) in buckets) {
                if (!room()) {
                    suppressed += group.size
                    continue
                }
                val (kind, reason) = key
                val subject = subject(session, named, group, total = here.size, examined = examined.size)
                val detail =
                    when (kind) {
                        NearMiss.TAKEN ->
                            "$subject matching this step's bind predicate, but an earlier step had already " +
                                "bound it — two steps cannot bind the same message. Give this step an " +
                                "occurrence or a discriminating field."
                        NearMiss.LATE ->
                            "$subject matching this step's bind predicate, but not before its " +
                                "${step.timeoutMs}ms window closed. Raise the timeout."
                        NearMiss.REJECTED ->
                            "$subject this step did not bind, for the same reason each time: $reason. The " +
                                "bind predicate is what rejected them — correct the predicate, not the " +
                                "assertion rows, or the step binds nothing on the next run either."
                    }
                // No stepId and index -1 even though this *is* about the failed step: the repair here is the
                // predicate, and the reconcile view repairs expectations. Offering that door would offer a fix
                // that cannot fix this. Only the newest example is marked in the grid — marking nine hundred
                // identical ticks marks nothing.
                unpaired(group.first(), detail)
            }
        }

        /**
         * How a bucket names itself — and, when the log was too long to classify whole, it says so.
         *
         * The count has to be the count of what was *looked at*, never a total quietly presented as one. A row
         * reading "500 W messages" over a log holding 600 is a small lie that a reader would reasonably use to
         * conclude the other hundred were something else.
         */
        private fun subject(session: String, named: String, group: List<FixMessage>, total: Int, examined: Int): String {
            val newest = at(group.first())
            return when {
                total > examined ->
                    "'$session' received $total $named messages; of the $examined most recent, " +
                        "${group.size} (newest at $newest) were ones that"
                group.size == 1 -> "'$session' received a $named message at $newest that"
                else -> "'$session' received ${group.size} $named messages (newest at $newest) that"
            }
        }

        /** Why [matches] turned this message down — one clause per constraint that said no. */
        private fun rejections(match: MatchPredicate?, direction: String?, m: FixMessage): List<String> {
            val reasons = mutableListOf<String>()
            val dir = match?.direction ?: direction
            if (dir != null && !directionMatches(m, dir)) {
                reasons += "it is ${m.direction.name.lowercase()} and the step binds '$dir'"
            }
            val wire = host.view(m)?.fields()
            if (wire != null) {
                match?.fields?.forEach { tv ->
                    val ok =
                        when (tv.op) {
                            MatchOp.EQ -> wire.any { it.first == tv.tag && it.second == tv.value }
                            MatchOp.PRESENT -> wire.any { it.first == tv.tag }
                            MatchOp.ABSENT -> wire.none { it.first == tv.tag }
                        }
                    if (!ok) {
                        reasons +=
                            when (tv.op) {
                                MatchOp.EQ -> "it wants ${tv.tag}=${tv.value}, the message has ${tv.tag}=${actual(wire, tv.tag)}"
                                MatchOp.PRESENT -> "it wants ${tv.tag} present, the message has no ${tv.tag}"
                                MatchOp.ABSENT -> "it wants ${tv.tag} absent, the message has ${tv.tag}=${actual(wire, tv.tag)}"
                            }
                    }
                }
            }
            return reasons.ifEmpty { listOf("no constraint of the predicate accounts for it") }
        }

        private fun actual(wire: List<Pair<Int, String>>, tag: Int): String =
            wire.filter { it.first == tag }.joinToString("/") { it.second }.ifBlank { "nothing" }

        // -------------------------------------------------- Q3: everything else nobody bound

        private fun strays() {
            val pending = pendingExpects()
            val taken = mutableSetOf<PendingExpect>()
            val found = collect()
            // **Pairable first, and recency second.** On a stream that never stops, every message is recent,
            // so recency alone ranks a market-data tick above the MDRequestReject that explains the failure.
            // A message an unrun expect was actually waiting for is the evidence; the rest is weather.
            //
            // The quota is what keeps that true when the stream *is* the expected type. Six hundred unbound
            // ExecutionReports under one unrun `expect 8` are not six hundred pieces of evidence: one expect
            // binds one message, so exactly one of them — the newest, since [collect] runs newest-first — can
            // be the one it was waiting for. The rest are weather no matter what type they are.
            val quota = pending.groupingBy { typeOf(it) }.eachCount().toMutableMap()
            val pairable = mutableListOf<Pair<FixMessage, String>>()
            val weather = mutableListOf<Pair<FixMessage, String>>()
            for (entry in found) {
                val left = quota[entry.first.messageType] ?: 0
                if (left > 0) {
                    quota[entry.first.messageType] = left - 1
                    pairable += entry
                } else {
                    weather += entry
                }
            }
            for ((m, session) in pairable) {
                if (!room()) {
                    suppressed++
                    continue
                }
                stray(m, session, pending, taken)
            }
            collapse(weather, pending)
        }

        /** Every unbound incoming business message across the scenario's sessions, newest first. */
        private fun collect(): List<Pair<FixMessage, String>> {
            val sessions = runSessions(scenario)
            val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<FixMessage, Boolean>())
            val found = mutableListOf<Pair<FixMessage, String>>()
            for (session in sessions) {
                for (m in newestFirst(host.messages(session))) {
                    if (m.direction != FixMessage.Direction.INCOMING) continue
                    if (m in consumed || m in diagnosed) continue
                    if (m.messageType in SESSION_ADMIN_TYPES) continue
                    if (!seen.add(m)) continue
                    found += m to label(session)
                }
            }
            return found
        }

        /**
         * **The traffic no expectation was ever waiting for, counted rather than listed.**
         *
         * One row for the lot, as a histogram by session and type. A subscription that is still ticking sends
         * hundreds of these a second, and the alternative — a row each, six of them, chosen by arrival time —
         * is six rows that say nothing about the failure and crowd out the ones that do.
         *
         * Nothing here is marked in the grid either. Tinting nine hundred rows amber is not a mark; it is a
         * new background colour for the session. The count is the finding; the messages are still all there.
         */
        private fun collapse(weather: List<Pair<FixMessage, String>>, pending: List<PendingExpect>) {
            if (weather.isEmpty()) return
            if (weather.size == 1) {
                val (m, session) = weather.single()
                val preamble = "'$session' received a ${m.messageType} at ${at(m)} that no step bound"
                // The stronger claim only where it is true. Over-quota messages land here too — a second
                // unbound ExecutionReport under one unrun `expect 8` is not evidence that nothing expects an 8.
                unpaired(
                    m,
                    if (pending.none { typeOf(it) == m.messageType }) {
                        "$preamble, and no expect step in this scenario looks for a ${m.messageType} on '$session'."
                    } else {
                        "$preamble. The expect step that looks for a ${m.messageType} has already been paired " +
                            "with a different unbound message; this is another one."
                    },
                )
                return
            }
            if (!room()) {
                suppressed += weather.size
                return
            }
            val histogram =
                weather
                    .groupingBy { (m, session) -> "'$session' ${m.messageType}" }
                    .eachCount()
                    .entries
                    .sortedByDescending { it.value }
            val listed =
                histogram.take(MAX_LISTED_STRAYS).joinToString(", ") { "${it.key} ×${it.value}" } +
                    (if (histogram.size > MAX_LISTED_STRAYS) " +${histogram.size - MAX_LISTED_STRAYS} more types" else "")
            out +=
                row(
                    at = -1,
                    stepId = null,
                    phase = failure.phase,
                    passed = false,
                    // Deliberately NOT "…that no expect step looks for". This bucket holds the over-quota
                    // messages too — the 259th unbound ExecutionReport under one unrun `expect 8` — and that
                    // claim would be false about every one of them.
                    detail =
                        "${weather.size} further message(s) arrived that no step bound: $listed. Not " +
                            "individually marked — on a session that is still streaming, this is the shape of " +
                            "the traffic, not a finding about any one message.",
                )
        }

        @Suppress("ReturnCount") // One guard per way a pairing can fail, each with its own sentence.
        private fun stray(
            m: FixMessage,
            session: String,
            pending: List<PendingExpect>,
            taken: MutableSet<PendingExpect>,
        ) {
            val preamble = "'$session' received a ${m.messageType} at ${at(m)} that no step bound"
            // Same session first, then any session: a same-session, same-type expectation is a far better
            // guess than a same-type one on the other leg, and both are still guesses.
            val candidate =
                pending.firstOrNull {
                    it !in taken && label(it.step.session) == session && typeOf(it) == m.messageType
                } ?: pending.firstOrNull { it !in taken && typeOf(it) == m.messageType }
            if (candidate == null) {
                // "Nothing looks for this" and "the thing that looks for this is already spoken for" are
                // different findings, and the second one must never be printed as the first: a scenario whose
                // expect for this very type sits three lines above would be called a scenario that never
                // mentions the type.
                val spokenFor = pending.any { it in taken && typeOf(it) == m.messageType }
                unpaired(
                    m,
                    if (spokenFor) {
                        "$preamble. The expect step that looks for a ${m.messageType} has already been paired " +
                            "with a different unbound message; this is another one."
                    } else {
                        "$preamble, and no expect step in this scenario looks for a ${m.messageType} on '$session'."
                    },
                )
                return
            }
            val where =
                "step ${candidate.index + 1} (expect ${typeOf(candidate) ?: "any"} on '${label(candidate.step.session)}')"
            val pm = candidate.step.match?.let { resolveMatch(it, candidate.step.session, scope) }
            val type = pm?.messageType ?: candidate.step.expectation.messageType
            val direction = pm?.direction ?: candidate.step.direction
            // A pairing is only offered when that step would actually have *bound* this message. Otherwise the
            // reconcile door it opens would invite repairs to assertion rows that the step never reaches.
            if (!matches(m, type, direction, pm)) {
                unpaired(
                    m,
                    "$preamble. $where expects a ${m.messageType} there, but its bind predicate would reject " +
                        "this one: ${rejections(pm, candidate.step.direction, m).joinToString("; ")}.",
                )
                return
            }
            val view = host.view(m)
            if (view == null) {
                unpaired(
                    m,
                    "$preamble. FixTool has no wire bytes for it, so its field order is unknown and it cannot " +
                        "be compared against $where. This is a FixTool limitation, not a venue failure.",
                )
                return
            }
            taken += candidate
            val resolver = host.referenceResolver(candidate.step.session, scope)
            val diverging =
                ExpectationEvaluator
                    .evaluate(view, candidate.step.expectation, resolver)
                    .filterNot { it.passed }
            val guess = "This is a guess at pairing, not a match — the run failed at step ${failure.index + 1}."
            val detail =
                if (diverging.isEmpty()) {
                    "$preamble. It would have satisfied $where, which this run stopped before reaching. $guess"
                } else {
                    "$preamble. Closest expectation is $where, which it would NOT have satisfied — " +
                        "${diverging.size} row(s) diverge. $guess"
                }
            // The one row that carries a stepId: it names a real, still-editable Expect, and its message is
            // published through the same channel the run's own verdicts use — so the reconcile view opens on
            // that step with this message as the actual, which is the whole point of looking.
            emit(
                m,
                row(
                    at = candidate.index,
                    stepId = candidate.step.stepId.ifBlank { null },
                    phase = candidate.phase,
                    passed = diverging.isEmpty(),
                    detail = detail,
                    tags = diverging,
                ),
            )
        }

        /** A message with nothing to hold it against: marked and described, but no route to a repair. */
        private fun unpaired(m: FixMessage, detail: String) =
            emit(m, row(at = -1, stepId = null, phase = failure.phase, passed = false, detail = detail))

        /** The unmuted `Expect`s the run never got to, in order — [failure] stopped short of all of them. */
        private fun pendingExpects(): List<PendingExpect> {
            val out = mutableListOf<PendingExpect>()

            fun collect(steps: List<ScenarioStep>, phase: String, after: Int) =
                steps.forEachIndexed { i, s ->
                    if (i > after && !s.muted && s is ScenarioStep.Expect) out += PendingExpect(s, i, phase)
                }
            if (failure.phase == "setup") {
                collect(scenario.setup, "setup", failure.index)
                collect(scenario.steps, "steps", -1)
            } else {
                collect(scenario.steps, "steps", failure.index)
            }
            return out
        }

        private fun typeOf(p: PendingExpect): String? = p.step.match?.messageType ?: p.step.expectation.messageType

        // -------------------------------------------------- plumbing

        /**
         * **Newest first, everywhere.** A session's log outlives the run that reads it, and the runner puts
         * no time bound on anything — an `Expect` will bind a message that arrived before the run started,
         * so a post-mortem that ruled such messages out would be answering from a different universe than the
         * one the step was judged in, and two answers to "which messages is this run about" eventually
         * disagree. Order is the honest lever instead: the traffic that explains a failure is the traffic
         * that just happened, so under [MAX_DIAGNOSED] the fresh reject wins the slot and a hundred stale
         * replies on a log nobody cleared do not crowd it out. Each row carries its message's arrival time,
         * which is what lets a reader spot a stale one for what it is.
         */
        private fun newestFirst(messages: List<FixMessage>): List<FixMessage> = messages.asReversed()

        private fun room(): Boolean = out.size < MAX_DIAGNOSED

        private fun emit(m: FixMessage, r: StepResult) {
            diagnosed += m
            out += r
            // Through the SAME channel as every other verdict — this is what marks the message in the grid.
            // A report naming a message the grid does not mark leaves the tester hunting for which one.
            onExpectMatched(m, r)
        }

        private fun row(
            at: Int,
            stepId: String?,
            phase: String,
            passed: Boolean,
            detail: String,
            tags: List<TagResult> = emptyList(),
        ) = StepResult(at, "diagnosis", phase, passed, detail = detail, tags = tags, stepId = stepId)

        private fun at(m: FixMessage): String = m.timestamp.format(DIAGNOSIS_TIME)
    }

    /** The distinct sessions the run's setup and steps touch — teardown's traffic is nobody's evidence. */
    private fun runSessions(scenario: Scenario): List<String?> =
        (scenario.setup + scenario.steps).filterNot { it.muted }.map { it.session }.distinct()

    /**
     * **What this run never got to see** — null when nothing was lost, which is the ordinary case.
     *
     * A session ingests at a bounded rate ([FixMessageSession] drains a fixed batch on a fixed period) and
     * discards what overruns it. Those messages were received and thrown away: not late, not filtered, not
     * evicted from the display buffer — never visible to anything. Every downstream mystery on a fast feed
     * reduces to this. A step that timed out on a reply the venue provably sent, a bind predicate that
     * "never matched", an expectation that looks wrong: all of them look like venue or authoring problems
     * and none of them are, and until now nothing in the report distinguished the cases.
     *
     * It fails the run **only under [TrafficMode.STRICT]**, and that asymmetry is the whole point. A strict
     * run asserts that nothing unexpected arrived — a claim that cannot honestly be made about messages that
     * were thrown away unread, so the assertion is void rather than green. An OPEN run asserts nothing of
     * the kind: its steps either found what they wanted or did not, so the loss is a caveat on the report
     * and gets a passing row that says so. Neither reading blames the venue, because the venue did nothing.
     */
    private fun ingestCheck(scenario: Scenario, before: Map<String?, Long>): StepResult? {
        val lost =
            before.mapNotNull { (session, was) ->
                val delta = host.discarded(session) - was
                if (delta > 0) label(session) to delta else null
            }
        if (lost.isEmpty()) return null
        val total = lost.sumOf { it.second }
        val listed = lost.joinToString(", ") { "'${it.first}' ×${it.second}" }
        val strict = scenario.traffic == TrafficMode.STRICT
        val why =
            "$total message(s) arrived during this run and were discarded before anything could see them: " +
                "$listed. FixTool could not ingest them fast enough — this is a tool limitation, not a venue " +
                "failure, and the messages are gone rather than late. Any step that timed out may have been " +
                "waiting for one of them."
        val verdict =
            if (strict) {
                " Traffic is strict, and 'nothing unexpected arrived' is not a claim this run can make about " +
                    "messages it threw away unread — so the check is void, not passed."
            } else {
                " No step's verdict is changed by this; read the rest of the report knowing it is incomplete."
            }
        return StepResult(-1, "ingest", "steps", passed = !strict, detail = why + verdict)
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

        /** Diagnosis rows a failed run may add. A post-mortem that buries the verdict has failed at its job. */
        const val MAX_DIAGNOSED = 6

        /**
         * How many same-type messages the near-miss pass classifies before it stops looking.
         *
         * Classifying costs a wire view per message, and a market-data session's log — a ring buffer the user
         * may set as high as 100,000 — is full of exactly the type a market-data step expects. The newest few
         * hundred carry the same story as the rest: this bounds the work without changing the answer.
         */
        const val SCAN_LIMIT = 500

        /**
         * Appended to the failing step's detail — never inserted into it. The sentence a reader already
         * knows how to read has to survive the arrival of the ones underneath it.
         */
        const val POINTER = " — messages arrived that no step bound; see the diagnosis rows below."

        val DIAGNOSIS_TIME: java.time.format.DateTimeFormatter =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    }
}
