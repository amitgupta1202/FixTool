package com.knapsack.fixtool.model.scenario

import java.util.UUID

/**
 * A saved, parameterized sequence of FIX sends and assertions that a deterministic runner replays
 * identically — the "author once, repeat forever, no LLM in the hot path" artifact from the
 * repeatable-scenarios design. Persisted one-file-per-scenario (see `ScenarioService`).
 *
 * `setup` runs before `steps`; `teardown` runs after `steps` (even on failure). The runner threads
 * a single persistent variable scope across every step (see the design's Decision 0).
 */
data class Scenario(
    val id: String,
    val name: String,
    val profile: String? = null,
    val setup: List<ScenarioStep> = emptyList(),
    val steps: List<ScenarioStep> = emptyList(),
    val teardown: List<ScenarioStep> = emptyList(),
    val userTags: List<String> = emptyList(),
    val version: Int = 1,
)

/** One step of a scenario. Every step targets a [session] (null = the active session). */
sealed interface ScenarioStep {
    val session: String?

    /**
     * **Who this step is**, as opposed to where it currently sits.
     *
     * A run result addresses its step by *index*, and nothing invalidates a run when the scenario is
     * edited — so deleting a step above a failure silently re-pointed the reconcile route at a different
     * Expect, and the only defence available was to refuse the route whenever *anything* in the scenario
     * had changed. That refusal is correct and far too wide: it withdraws the fix for step 5 because the
     * author renamed step 1.
     *
     * With an id, the run says which step it means, and the refusal narrows to the step that actually
     * changed. Blank means *not yet assigned* — [withIds] fills it, deterministically, at every door a
     * scenario comes through (load, capture, save, run), so a file written before ids existed gets the
     * same ids on every load and the two sides of that comparison can never disagree.
     */
    val stepId: String

    /**
     * **Where the bytes behind this step came from** — and it is a claim about trust, not about history.
     *
     * FixTool watched a live capture arrive on a wire it was connected to. It did not watch a **paste**: a log
     * fragment, an email, a message from another environment. A step authored or repaired against pasted bytes
     * has none of a live capture's guarantees, and a hand-doctored paste must never be mistakable for one.
     *
     * It is also the sentence that explains something the author would otherwise find inexplicable. The golden
     * is re-pointed **only** for THIS_RUN (V4) — a paste must never become the scenario's canonical example —
     * so a step repaired against a paste has rows describing the paste and a golden describing the old capture,
     * and it opens *red against its own golden*. The badge is why.
     *
     * Additive, defaulted, and written to disk only when it is not the default: a file that never had the key
     * does not grow one. See [ScenarioStep.stepId], which is the same bargain.
     */
    val origin: StepOrigin get() = StepOrigin.LIVE

    /** Send a (parameterized) message; `${...}` is resolved against the scenario scope at run time. */
    data class Send(
        val raw: String,
        override val session: String? = null,
        override val stepId: String = "",
        override val origin: StepOrigin = StepOrigin.LIVE,
    ) : ScenarioStep

    /** Block until a connection state is reached or a matching message arrives (no consume). */
    data class Wait(
        override val session: String? = null,
        val state: String? = null,
        val match: MatchPredicate? = null,
        val timeoutMs: Long = 10_000,
        override val stepId: String = "",
        override val origin: StepOrigin = StepOrigin.LIVE,
    ) : ScenarioStep

    /** Await the next not-yet-consumed matching message and assert it against an expectation. */
    data class Expect(
        override val session: String? = null,
        val direction: String = "in",
        val match: MatchPredicate? = null,
        val timeoutMs: Long = 10_000,
        val expectation: Expectation,
        override val stepId: String = "",
        override val origin: StepOrigin = StepOrigin.LIVE,
    ) : ScenarioStep

    /** Clear a session's observable message log (typical setup step). */
    data class ClearMessages(
        override val session: String? = null,
        override val stepId: String = "",
        override val origin: StepOrigin = StepOrigin.LIVE,
    ) : ScenarioStep

    /** Reset a session's FIX sequence numbers (typical setup step). */
    data class ResetSeqNum(
        override val session: String? = null,
        val sender: Int? = null,
        val target: Int? = null,
        override val stepId: String = "",
        override val origin: StepOrigin = StepOrigin.LIVE,
    ) : ScenarioStep
}

/**
 * **Did FixTool see these bytes on a wire, or did somebody paste them in?**
 *
 * Two values, not five. The *reference slot* has five provenances because it has five sources; a **step**
 * records only whether what was written to it was vouched for. THIS_RUN, GOLDEN, SECOND_INSTANCE and PICKED
 * are all bytes FixTool watched arrive — they need no badge. A paste is the one that does.
 */
enum class StepOrigin {
    /** The bytes came off a wire FixTool was connected to. */
    LIVE,

    /** Bytes FixTool cannot vouch for: a log, an email, another environment. Badged, everywhere it shows. */
    PASTED,
    ;

    companion object {
        /**
         * **An unrecognised origin degrades toward LESS trust, never more.**
         *
         * Unlike `mode` — where an unknown value changes *what is checked*, and so fails the load loudly — an
         * unknown origin changes nothing that is checked. But reading it as LIVE would let a file claim **more**
         * trust than it carries, and the badge exists precisely to stop that. So anything this build does not
         * recognise is treated as not-vouched-for.
         */
        fun from(raw: String?): StepOrigin =
            when (raw?.lowercase()) {
                null, "live" -> LIVE
                else -> PASTED
            }
    }
}

/** The same step under a new identity. A sealed interface has no `copy`, and the id has to be assignable. */
fun ScenarioStep.withStepId(id: String): ScenarioStep =
    when (this) {
        is ScenarioStep.Send -> copy(stepId = id)
        is ScenarioStep.Wait -> copy(stepId = id)
        is ScenarioStep.Expect -> copy(stepId = id)
        is ScenarioStep.ClearMessages -> copy(stepId = id)
        is ScenarioStep.ResetSeqNum -> copy(stepId = id)
    }

/** The same step, said to have come from somewhere else. See [ScenarioStep.origin]. */
fun ScenarioStep.withOrigin(origin: StepOrigin): ScenarioStep =
    when (this) {
        is ScenarioStep.Send -> copy(origin = origin)
        is ScenarioStep.Wait -> copy(origin = origin)
        is ScenarioStep.Expect -> copy(origin = origin)
        is ScenarioStep.ClearMessages -> copy(origin = origin)
        is ScenarioStep.ResetSeqNum -> copy(origin = origin)
    }

/**
 * Every step carries an id — and a step that has none gets one **deterministically**.
 *
 * That word is the whole design. [com.knapsack.fixtool.viewmodel.FixMessageViewModel.reconcileRoute]
 * compares the step that *ran* against the step now *on disk*, and it reads the disk copy with a second,
 * independent load. Mint a random id for an id-less file and those two loads would agree about nothing:
 * every failure on a scenario written before ids existed would be refused — by the very mechanism added
 * to stop refusing so much. So an unassigned step is identified by where it sits in the file it was read
 * from, and two loads of that file produce the same ids, for ever, until someone saves it with them.
 *
 * Ids duplicated inside one scenario (a hand-copied step, a file edited by hand) are re-minted: an id
 * that addresses two steps addresses neither.
 */
fun Scenario.withIds(): Scenario {
    val used = mutableSetOf<String>()
    val phases = listOf("setup" to setup, "steps" to steps, "teardown" to teardown)

    // FIRST every step that already has an id claims it, and only THEN is anything minted.
    //
    // One pass would not do, and the difference is the whole point of the id. A minted id is a hash of
    // (scenario, phase, index) — so the id a *new* step is minted with at index 2 is precisely the id the
    // *existing* step at index 2 is already carrying. Mint before that step has claimed it, and the newcomer
    // takes its identity: the displaced step is then re-minted onto its own successor's id, and every step
    // below the insertion slides one place down the id list. The failing step's id would then name a
    // different step, `reconcileRoute` would find that one, and — where the two are alike, two Expects
    // awaiting two fills of the same shape — it would open the reconcile view on it and let "Accept actual"
    // write the failing message's bytes into an assertion that never saw them. That is the exact corruption
    // the id was introduced to prevent, so an existing step's id is never available to be minted into.
    val keeps = phases.map { (_, list) -> list.map { it.stepId.isNotBlank() && used.add(it.stepId) } }

    fun assign(phase: String, list: List<ScenarioStep>, kept: List<Boolean>): List<ScenarioStep> =
        list.mapIndexed { index, step ->
            // A blank id, or a duplicate of one another step claimed first: either way, this step needs one.
            if (kept[index]) step else step.withStepId(mint(id, phase, index, used))
        }

    return copy(
        setup = assign("setup", setup, keeps[0]),
        steps = assign("steps", steps, keeps[1]),
        teardown = assign("teardown", teardown, keeps[2]),
    )
}

/** A stable id for the step at (scenario, phase, index) — salted only if that id is somehow already taken. */
private fun mint(scenarioId: String, phase: String, index: Int, used: MutableSet<String>): String {
    var salt = 0
    while (true) {
        val seed = if (salt == 0) "$scenarioId/$phase/$index" else "$scenarioId/$phase/$index#$salt"
        val id = UUID.nameUUIDFromBytes(seed.toByteArray()).toString()
        if (used.add(id)) return id
        salt++
    }
}

/**
 * Selects a message by message type / direction and zero or more tag constraints (**AND**), the
 * latter being the partial-fill discriminator (e.g. ExecType=F AND OrdStatus=2). Extends the old
 * single-tag `fixtool_wait` predicate with multi-tag matching; the runner adds a consumed cursor so
 * successive `Expect`s walk successive messages instead of re-matching the first.
 *
 * Two same-type messages that the [fields] cannot tell apart (e.g. an ack ExecutionReport and the
 * terminal one, both echoing the same ClOrdID) are disambiguated by [occurrence]: when set, this step
 * binds the N-th message (1-based) in the type+direction+fields-filtered chronological snapshot rather
 * than the first not-yet-consumed one. `null` keeps the walk-in-order behaviour. Prefer a [TagValue]
 * discriminator (a differing value, or [MatchOp.PRESENT]/[MatchOp.ABSENT] of a distinguishing tag) over
 * a positional [occurrence] — a discriminator survives a venue inserting a message, a count does not.
 */
data class MatchPredicate(
    val messageType: String? = null,
    val direction: String? = null,
    val fields: List<TagValue> = emptyList(),
    val occurrence: Int? = null,
)

/**
 * A single tag constraint used inside a [MatchPredicate]. [value] is compared only for [MatchOp.EQ]; it
 * is ignored (and conventionally blank) for [MatchOp.PRESENT]/[MatchOp.ABSENT], which test the tag's
 * mere existence on the wire — the replay-safe way to distinguish messages by a correlation id whose
 * value is minted fresh each run.
 */
data class TagValue(
    val tag: Int,
    val value: String = "",
    val op: MatchOp = MatchOp.EQ,
)

/** How a [TagValue] tests the wire: equal to [TagValue.value], or present/absent regardless of value. */
enum class MatchOp { EQ, PRESENT, ABSENT }

/** The result of one step's execution. */
data class StepResult(
    val stepIndex: Int,
    val kind: String,
    val phase: String,
    val passed: Boolean,
    val detail: String? = null,
    val tags: List<TagResult> = emptyList(),
    /**
     * Which step this is a result *of* — the index says only where it sat when it ran. Null for the
     * preflight failure, which is a verdict on the scenario rather than on any step of it.
     */
    val stepId: String? = null,
)

/** The result of a whole scenario run — drives both CI (exit code) and the in-app red/green overlay. */
data class ScenarioResult(
    val scenario: String,
    val passed: Boolean,
    val steps: List<StepResult>,
)
