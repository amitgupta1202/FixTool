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
    /** Stream-level counterpart of [Expectation.mode]. Additive on disk — written only when STRICT. */
    val traffic: TrafficMode = TrafficMode.OPEN,
    /** Which messages an Expect may bind. Additive on disk — written only when THIS_RUN. */
    val binding: BindScope = BindScope.ANY,
    /**
     * When this scenario was first authored (epoch millis), for the rail's "creation order" sort. Null on
     * every file written before this field existed — those are **never rewritten** to add it, so the sort
     * falls back to the file's mtime for them. Minted once, at first save (see `ScenarioService.save`).
     * Additive and default-omitting on disk, the same bargain as [traffic] and [binding].
     */
    val createdAt: Long? = null,
)

/**
 * **Whether a step may bind a message that arrived before the run started.**
 *
 * A session's log outlives the run that reads it, and nothing in the runner has ever put a time bound on
 * what an Expect may take. On order flow that is mostly harmless — the log is short, and a bind predicate
 * carrying this run's ClOrdID rules out the past anyway. On a session that streams, it is a false green
 * waiting to happen: the buffer is permanently full of the very type the step wants, so `expect W` binds a
 * snapshot from before the subscription was even sent, and the run reports that the venue answered when the
 * venue has not yet said anything.
 *
 * The two real defences already exist and neither is enforced: a discriminating bind predicate (the request
 * id) makes the past unbindable, and a `ClearMessages` in setup deletes it. This is the third, for the
 * scenario that wants the guarantee rather than the discipline.
 */
enum class BindScope {
    /**
     * The historical (and default) semantics: any message in the log is bindable, whenever it arrived. A run
     * that binds one from before it started still *says* so on the step — the report never stays quiet about
     * it — but the step passes.
     */
    ANY,

    /**
     * Only messages that arrived after the run began may be bound. A step that finds nothing else times out
     * as though the older message were not there, which is exactly the claim being made: this run has not
     * seen its reply yet.
     */
    THIS_RUN,
}

/**
 * How the run judges the messages its Expect steps did **not** bind — [MatchMode] one level up.
 *
 * A field-level [Expectation] answers "is this reply the message I expect"; nothing at that level can
 * answer "and the venue sent nothing else". Every Expect binds one message and the leftovers were simply
 * never examined, so a run whose venue volunteered an extra ExecutionReport — a fill racing the cancel it
 * acknowledged, a third quote where the flow defines two — reported green while the surplus sat unread in
 * the grid.
 */
enum class TrafficMode {
    /**
     * The historical (and default) semantics: an incoming message no Expect binds is ignored. The
     * stream-level "the venue said at least this much" — tolerant of chatter, and the right mode for a
     * flow asserted mid-traffic or a venue that volunteers noise the scenario has no stake in.
     */
    OPEN,

    /**
     * "…and nothing else." After the last main-phase step (plus a settle window, because absence is a
     * claim about time), any incoming **application-level** message the run's Expects never bound fails
     * the run, by name. Session administration — Heartbeat, TestRequest, ResendRequest, SequenceReset,
     * Logon — is exempt, exactly as the envelope is exempt from a STRICT expectation; an unbound Logout
     * or session Reject is *not* exempt, because an unasked-for goodbye is precisely the kind of extra
     * this mode exists to report.
     *
     * So is **everything that was already in the log when the run began**: a session log is not emptied
     * between runs, and a run that inherited yesterday's replies is not the run that provoked them. The
     * claim is about this run's traffic, and the verdict says how many messages it set aside.
     */
    STRICT,
}

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
     * **Parked, not deleted.** The runner skips a muted step entirely — nothing sent, nothing consumed,
     * no verdict — and the scenario's pass/fail is judged over the steps that ran. Muting is how an author
     * keeps a step's shape (its session, its assertions, its place in the flow) while a venue leg is down
     * or a flow is being bisected, without paying the delete-and-reauthor round trip.
     *
     * Additive and default-omitting on disk, the same bargain as [stepId] and [origin]: a file that never
     * muted anything never grows the key.
     */
    val muted: Boolean get() = false

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
        override val muted: Boolean = false,
    ) : ScenarioStep

    /** Block until a connection state is reached or a matching message arrives (no consume). */
    data class Wait(
        override val session: String? = null,
        val state: String? = null,
        val match: MatchPredicate? = null,
        val timeoutMs: Long = 10_000,
        override val stepId: String = "",
        override val origin: StepOrigin = StepOrigin.LIVE,
        override val muted: Boolean = false,
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
        override val muted: Boolean = false,
    ) : ScenarioStep

    /** Clear a session's observable message log (typical setup step). */
    data class ClearMessages(
        override val session: String? = null,
        override val stepId: String = "",
        override val origin: StepOrigin = StepOrigin.LIVE,
        override val muted: Boolean = false,
    ) : ScenarioStep

    /**
     * **Empty the order book FixTool keeps as the venue on this session** (typical setup step).
     *
     * The other half of a run boundary, and the half nothing could reach before. [ClearMessages] empties
     * what a session *displays*; this empties what the acceptor side *remembers* — the per-counterparty
     * book that decides whether the next `35=D` is a new order or one it has already seen. A scenario run
     * twice with the same ClOrdID routes the second one as a move rather than a birth, and every rule
     * conditioned on order state answers the second run from the first run's memory: a duplicate rejected
     * where it was acked, a cancel refused as too late. Both are false reds about a venue that is behaving
     * exactly as told.
     *
     * Only a session FixTool hosts as a venue has a book to empty — an initiator pane's orders live at the
     * far end, where the tool has no reach. Aimed anywhere else it is a named refusal in preflight rather
     * than a silent no-op, because a setup step that quietly does nothing is worse than one that will not
     * run: the run goes green on state it never reset.
     */
    data class ClearOrderBook(
        override val session: String? = null,
        override val stepId: String = "",
        override val origin: StepOrigin = StepOrigin.LIVE,
        override val muted: Boolean = false,
    ) : ScenarioStep

    /** Reset a session's FIX sequence numbers (typical setup step). */
    data class ResetSeqNum(
        override val session: String? = null,
        val sender: Int? = null,
        val target: Int? = null,
        override val stepId: String = "",
        override val origin: StepOrigin = StepOrigin.LIVE,
        override val muted: Boolean = false,
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
        is ScenarioStep.ClearOrderBook -> copy(stepId = id)
        is ScenarioStep.ResetSeqNum -> copy(stepId = id)
    }

/** The same step, aimed at another session. Identity is untouched — see [Scenario.withSessions]. */
fun ScenarioStep.withSession(session: String?): ScenarioStep =
    when (this) {
        is ScenarioStep.Send -> copy(session = session)
        is ScenarioStep.Wait -> copy(session = session)
        is ScenarioStep.Expect -> copy(session = session)
        is ScenarioStep.ClearMessages -> copy(session = session)
        is ScenarioStep.ClearOrderBook -> copy(session = session)
        is ScenarioStep.ResetSeqNum -> copy(session = session)
    }

/**
 * The same scenario, aimed at other sessions: every step whose session is a key of [map] now targets
 * that key's value; sessions the map does not name — including null, the active session — are left
 * alone. Two callers, two lifetimes: the "save as new scenario" door materializes the result as its
 * own document (environments diverge in data, so each environment's copy must evolve and reconcile
 * independently), and the control surface's `sessions` parameter applies it for one run and discards
 * it. Step identity survives either way — [withIds] mints from position, not content.
 */
fun Scenario.withSessions(map: Map<String, String>): Scenario {
    if (map.isEmpty()) return this

    fun remap(steps: List<ScenarioStep>): List<ScenarioStep> =
        steps.map { step ->
            val to = step.session?.let { map[it] }
            if (to == null) step else step.withSession(to)
        }
    return copy(setup = remap(setup), steps = remap(steps), teardown = remap(teardown))
}

/** The same step, parked or un-parked. See [ScenarioStep.muted]. */
fun ScenarioStep.withMuted(muted: Boolean): ScenarioStep =
    when (this) {
        is ScenarioStep.Send -> copy(muted = muted)
        is ScenarioStep.Wait -> copy(muted = muted)
        is ScenarioStep.Expect -> copy(muted = muted)
        is ScenarioStep.ClearMessages -> copy(muted = muted)
        is ScenarioStep.ClearOrderBook -> copy(muted = muted)
        is ScenarioStep.ResetSeqNum -> copy(muted = muted)
    }

/** The same step, said to have come from somewhere else. See [ScenarioStep.origin]. */
fun ScenarioStep.withOrigin(origin: StepOrigin): ScenarioStep =
    when (this) {
        is ScenarioStep.Send -> copy(origin = origin)
        is ScenarioStep.Wait -> copy(origin = origin)
        is ScenarioStep.Expect -> copy(origin = origin)
        is ScenarioStep.ClearMessages -> copy(origin = origin)
        is ScenarioStep.ClearOrderBook -> copy(origin = origin)
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

/**
 * One variable of a run's scope, as the run left it: the name, the value, and the step that wrote it.
 *
 * The scope itself is a per-run `MutableMap` the runner threads through every step, and it used to be
 * thrown away when the run ended — which made `${id0}` invisible at exactly the moment an author is
 * staring at a reference row in the reconcile view wondering what it resolved to. The runner now hands
 * the final scope out on the [ScenarioResult], so the report, the rail, and the diff window can all say
 * what a name held. Final scope is enough: a mint is one-per-name in practice (`id0`, `id1`, …), and the
 * `steps` phase stops at the first failure, so nothing minted *after* the step being reconciled can
 * shadow what that step was judged with. A name deliberately re-assigned mid-run is the one distortion,
 * and capture never authors one.
 */
data class ScenarioVariable(
    val name: String,
    val value: String,
    /** The step whose evaluation wrote this name — blank ids reported as null. */
    val mintedAtStepId: String? = null,
)

/** The result of a whole scenario run — drives both CI (exit code) and the in-app red/green overlay. */
data class ScenarioResult(
    val scenario: String,
    val passed: Boolean,
    val steps: List<StepResult>,
    /** The run's final variable scope, in mint order. Empty when the run minted nothing (or never began). */
    val variables: List<ScenarioVariable> = emptyList(),
)
