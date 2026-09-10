package com.knapsack.fixtool.model.load

import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.scenario.Lane
import com.knapsack.fixtool.service.load.CompiledTemplate

/**
 * **Several load runs in order, under one seed, with one report.**
 *
 * Most load proofs against a venue are two or three runs that depend on each other: burst
 * NewOrderSingle, then cancel the same ClOrdIDs. Ask for quotes, then order against them. That was a
 * wrapper script around `fixtool load`, and the script owned the seed, the stop rule and the combined
 * report, which are the three things the tool already does well for one run.
 *
 * A set is a named, ordered list of [LoadPhaseSpec]. Each phase is a complete load plan except for the two
 * things the set owns: the [seed], because sharing it is the whole point, and [storeAndLog], because
 * applying a store per phase would put a logon and a sequence reset in the middle of the set's clock.
 * Between phases sits one policy, [onFailure].
 *
 * Templates and profiles are named, not identified, for the same reason a scenario run set names its
 * scenarios: a set checked in beside the code has to survive a fresh `--home`. A name nothing answers to
 * is a refusal that says which phase asked for it.
 */
data class LoadSet(
    /** Also the file: `load-sets/<name>.json`. A slug, so it can never carry the `=` a seed does. */
    val name: String,
    val label: String,
    /**
     * Values seeded into every message's scope, as `${name}`, shared by every phase.
     *
     * A value may be a generator (`${uuid:4}`, `${now:yyyyMMdd}`), rendered **once** when the set starts
     * and then frozen. A literal renders to itself. That is what lets a saved set get a fresh run id every
     * night rather than replaying one machine's four hex characters at the venue forever.
     */
    val seed: Map<String, String> = emptyMap(),
    /** Applied once, to every lane the set opens, for every phase. */
    val storeAndLog: StoreAndLogOverride? = null,
    val onFailure: OnFailure = OnFailure.STOP,
    /** Never empty. A set of none is a refusal, not a set that does nothing. */
    val phases: List<LoadPhaseSpec> = emptyList(),
) {
    /** What a set needs from the workspace to be validated or planned. */
    interface Resolver {
        /** The profile a phase names, by id or by name, or null when nothing answers to it. */
        fun profile(key: String): Profile?

        /** The template a phase names: a path, then a saved message by id or name under [profileId]. */
        fun template(key: String, profileId: String?): LoadTemplate?
    }

    /** A resolved issuing profile: enough to plan a phase and to judge its store. */
    data class Profile(
        val id: String,
        val name: String,
        val config: FixConnectionConfig,
    )

    /** One refusal, and the phase it belongs to. [phase] is 1-based, or null for the set's own. */
    data class Problem(
        val phase: Int?,
        val sentence: String,
    ) {
        /** "Phase 2 · Hit the first 2,000: the template reads …". What every surface prints. */
        fun describe(label: String?): String {
            if (phase == null) return sentence
            return "Phase $phase" + (label?.let { " · $it" } ?: "") + ": " + sentence
        }
    }

    /** The set, ready to run: the seed rendered once, and one [LoadPlan] per phase under one record id. */
    data class Planned(
        val id: String,
        val label: String,
        val name: String,
        val onFailure: OnFailure,
        val seed: Map<String, String>,
        val storeAndLog: StoreAndLogOverride?,
        val phases: List<LoadPlan>,
    )

    /**
     * **Every reason this set cannot run, in the sentences its surfaces print.**
     *
     * Every phase through [LoadPlan.problems], each sentence carrying its phase, plus the set's own: no
     * phases, a label used twice, a template or profile no name answers to, a phase whose template carries
     * no correlation tag, and the store sentence **once** rather than once per phase.
     *
     * A set of six phases must never fail on phase five for something that could have been said before
     * phase one, which is why nothing dials until this is empty.
     *
     * **A muted phase has to resolve, and is not judged.** Its label, its profile, its template and the
     * tag a reply is matched on are still refused, because the record carries its plan and [plan] cannot
     * build one without them. Nothing about how it would have behaved is: not the names its template
     * reads, not its store, not its own captures, and not whether a lane of its profile can log on. A
     * muted phase whose venue is down blocks nothing, which is the point of parking it. What parking does
     * cost is its captures: they leave the set of names a later phase may read, and
     * [mutedCaptureSentence] says so in the reading phase's voice.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun problems(resolve: Resolver, surface: LoadPlan.Surface): List<Problem> {
        if (phases.isEmpty()) return listOf(Problem(null, "A set needs a phase. Add one under Phases."))
        val found = mutableListOf<Problem>()
        // "A set needs a phase" asked of a set that has them and parked every one. Refused rather than run
        // to a verdict on nothing.
        if (phases.all { it.muted }) {
            found += Problem(null, "Every phase is muted. Unmute one, or the set has nothing to run.")
        }
        phases
            .groupBy { it.label.trim().lowercase() }
            .filterValues { it.size > 1 }
            .keys
            .sorted()
            .forEach { key ->
                val shown = phases.first { it.label.trim().lowercase() == key }.label
                found +=
                    Problem(
                        null,
                        "Two phases are both called '$shown'. Give each one its own name, so the report can name them.",
                    )
            }
        // Once per profile, not once per phase: the override is the set's, so the sentence is the set's.
        val judgedProfiles = mutableSetOf<String>()
        // Capture name to the phase that claimed it, so the second claim can name the first.
        val claimed = linkedMapOf<String, Int>()
        phases.forEachIndexed { index, spec ->
            val n = index + 1
            val profile = resolve.profile(spec.profile)
            if (profile == null) {
                found += Problem(n, "no saved connection profile named '${spec.profile}'.")
                return@forEachIndexed
            }
            // Not for a muted phase: the override is judged for the lanes the set will open, and a muted
            // phase opens none, so its profile does not enter [judgedProfiles] either.
            if (!spec.muted && judgedProfiles.add(profile.id)) {
                LoadPlan.storeProblem(profile.name, profile.config, storeAndLog)?.let { found += Problem(null, it) }
            }
            val template = resolve.template(spec.template, profile.id)
            if (template == null) {
                found +=
                    Problem(n, "no template '${spec.template}': not a file, and no saved message of that id or name.")
                return@forEachIndexed
            }
            if (!spec.muted) {
                captureProblems(spec, index, claimed).forEach { found += Problem(n, it) }
                triggerProblems(spec, index, surface).forEach { found += Problem(n, it) }
                // A name is readable in this phase when the set seeds it, a lane hands it over, or an
                // EARLIER LIVE phase captured it. A capture in this phase or a later one is a different
                // mistake, and so is one an earlier phase keeps and is muted, and each sentence below says
                // which rather than sending the author to the Seed band for it.
                //
                // Which is why [tooLate] and the parked names are handed to templateProblems as though
                // they were seeded: a name that IS captured, only in the wrong order or by a phase nothing
                // will run, is not a name nothing seeds, and printing both sentences reads as two faults
                // with two remedies for one mistake.
                val earlier = readableAt(index)
                // Kept by an earlier live phase and still out of reach, which only a reactive phase can be
                // in: its own siblings' captures. Handed to templateProblems with the rest for the same
                // reason [tooLate] is, so one mistake gets one sentence.
                val outOfAncestry =
                    phases
                        .take(index)
                        .filterNot { it.muted }
                        .flatMap { it.capture.keys }
                        .toSet() - earlier
                val tooLate = phases.drop(index).flatMap { it.capture.keys }.toSet()
                val unresolved = template.readsThatAreNotSeeded(seed.keys + earlier)
                val parked = parkedCaptures(index, unresolved)
                val spokenFor = parked.values.flatten().toSet()
                LoadPlan
                    .templateProblems(template, seed.keys + earlier + tooLate + spokenFor + outOfAncestry, surface)
                    .forEach { found += Problem(n, it) }
                unresolved.filter { it in tooLate && it !in spokenFor && it !in outOfAncestry }.forEach { name ->
                    found +=
                        Problem(
                            n,
                            "the template reads \${$name} and no earlier phase captures it. " +
                                "Add a capture to a phase before it, or seed it.",
                        )
                }
                unresolved.filter { it in outOfAncestry }.forEach { name ->
                    found += Problem(n, outOfAncestrySentence(name, index, spec.after))
                }
                parked.forEach { (before, names) ->
                    // A phase that reacts to a muted phase AND reads its captures made one mistake with one
                    // remedy, and [triggerProblems] has already said "unmute it" for this very phase.
                    if (before == spec.after) return@forEach
                    found += Problem(n, mutedCaptureSentence(names, before, phases[before - 1].label, surface))
                }
                spec.capture.keys.forEach { claimed[it] = n }
            }
            if (spec.match == null && template.inferMatch() == null) {
                found +=
                    Problem(
                        n,
                        "'${template.name}' carries none of the tags a reply is matched on " +
                            "(${LoadTemplate.CORRELATION_ORDER.joinToString(", ")}). Say which tags to match on.",
                    )
            }
        }
        return found
    }

    /**
     * **What is wrong with one phase's trigger**, in the phase's voice.
     *
     * Every sentence starts with "it", because [Problem.describe] has already put "Phase 3 · Hit every
     * answer:" in front of it. The bounds case is asked before the order case, so a set of three phases
     * whose phase 2 reacts to phase 5 is told the set has three phases rather than that phase 5 runs
     * after it, which is true of a phase that does not exist and useless to hear.
     *
     * `after` on a burst or a rate is refused rather than ignored. This tool does not keep a setting a
     * surface accepted and then quietly dropped, and the codec writes it either way.
     */
    private fun triggerProblems(spec: LoadPhaseSpec, index: Int, surface: LoadPlan.Surface): List<String> {
        val n = index + 1
        val reactive = spec.shape as? LoadShape.Triggered
        val after = spec.after
        if (reactive == null) {
            return listOfNotNull(
                after?.let {
                    "it names phase $it as its trigger, and only a reactive phase has one. " +
                        "Make its shape reactive, or drop the trigger."
                },
            )
        }
        return listOfNotNull(
            when {
                after == null ->
                    "it is reactive and names no phase to react to. A reactive phase issues one message for " +
                        "each message an earlier phase issued, so it has to say which."
                after < 1 || after > phases.size ->
                    "it reacts to phase $after, and the set has ${phases.size} phases. " +
                        "Name a phase that runs before it."
                after == n -> "it reacts to itself, and nothing would ever fire it. Name a phase that runs before it."
                after > n -> "it reacts to phase $after, which runs after it. Name a phase that runs before it."
                phases[after - 1].muted ->
                    "it reacts to phase $after · ${phases[after - 1].label}, and that phase is muted. " +
                        "Unmute it, or nothing will ever fire this one."
                else -> null
            },
            reactive.cap
                ?.takeIf { it <= 0 }
                ?.let { "it is capped at $it/s, which is not a rate. ${surface.capRemedy}" },
            // #46 step 2 builds the shape, the trigger and these refusals, and nothing runs one yet. This
            // is what keeps a set carrying a reactive phase from opening a lane before finding that out,
            // rather than throwing out of the pacer after logon, which is not a refusal. It goes when the
            // trigger itself lands.
            "it is reactive, and this version of FixTool cannot run a reactive phase yet. Give it a burst or a rate.",
        )
    }

    /**
     * **Every capture name this phase may read**, which for a reactive phase is not every earlier one.
     *
     * A paced phase reads what every earlier live phase kept, because a paced phase runs after them. A
     * reactive phase reads only what its trigger kept and what its trigger could itself read, and that is
     * a real race rather than a tidiness rule: with phases 2 and 3 both reacting to phase 1, phase 1's
     * match for message 7 fires phase 3 at the same moment it fires phase 2, so phase 2's reply for 7 has
     * not landed and its capture for 7 is not there to read.
     *
     * A phase whose trigger does not exist or runs later is judged as a paced phase would be, because it
     * is already being refused for the trigger and a second sentence about a scope it does not have would
     * be two refusals for one mistake.
     */
    private fun readableAt(index: Int): Set<String> {
        val spec = phases[index]
        val earlierLive =
            phases
                .take(index)
                .filterNot { it.muted }
                .flatMap { it.capture.keys }
                .toSet()
        if (spec.shape !is LoadShape.Triggered) return earlierLive
        val after = spec.after?.takeIf { it in 1..index } ?: return earlierLive
        val trigger = phases[after - 1]
        // A muted trigger keeps nothing anybody may read, which is what mutedCaptureSentence is for. Its
        // own ancestry is still in scope: the phases before it ran, whatever became of it.
        return (if (trigger.muted) emptySet() else trigger.capture.keys.toSet()) + readableAt(after - 1)
    }

    /**
     * "the template reads ${quoteId}, which phase 2 keeps, and this phase reacts to phase 1 …"
     *
     * The house opening, because the mistake is what the template reads, and the phase that keeps the name
     * by number because that is the phase whose trigger the author has to choose between. Named rather
     * than "an earlier phase", since "no earlier phase captures it" would be a lie about a name an earlier
     * phase plainly captures.
     */
    private fun outOfAncestrySentence(name: String, index: Int, after: Int?): String {
        val keeper = phases.take(index).indexOfFirst { !it.muted && name in it.capture.keys } + 1
        return "the template reads \${$name}, and phase $keeper keeps it, which is not this phase's trigger " +
            "nor one of its trigger's own. A reactive phase fires as its trigger's replies land, so nothing " +
            "says phase $keeper has answered for the same message yet. React to phase $keeper instead of " +
            "phase $after, or read a name its trigger keeps."
    }

    /**
     * **What is wrong with one phase's captures**, in the phase's voice.
     *
     * A capture becomes a name in a later phase's scope, so it has to be a name a template can read, and
     * it has to be the only thing answering to it: a capture that shadows a seed, a lane name or the
     * message index would make the later phase's message depend on which of the two the renderer reached
     * first.
     */
    private fun captureProblems(spec: LoadPhaseSpec, index: Int, claimed: Map<String, Int>): List<String> =
        spec.capture.keys.mapNotNull { name ->
            when {
                !CompiledTemplate.isVariableName(name) ->
                    "the capture name '$name' is not a name a template can read. " +
                        "Use letters, digits and underscore, starting with a letter or an underscore."
                name in seed -> "the capture $name is also a seed. Rename one of them."
                name in Lane.SEED_NAMES -> "the capture $name is also a lane's own name. Rename it."
                name == CompiledTemplate.MESSAGE_INDEX -> "the capture $name is the message index. Rename it."
                claimed.containsKey(name) ->
                    "the capture $name is also captured by phase ${claimed[name]}. Rename one of them."
                else -> null
            }
        }

    /**
     * **What an earlier muted phase keeps that this phase reads**, by the number of that muted phase.
     *
     * One entry per muted phase, each name attributed to the **first** muted phase that keeps it, which is
     * the attribution the editor's own capture hint already makes. Two muted phases each keeping a name
     * this phase reads is two phases to unmute, so it is two sentences.
     */
    private fun parkedCaptures(index: Int, unresolved: Set<String>): Map<Int, List<String>> {
        val parked = linkedMapOf<Int, List<String>>()
        val spokenFor = mutableSetOf<String>()
        phases.take(index).forEachIndexed { i, before ->
            if (!before.muted) return@forEachIndexed
            val kept = before.capture.keys.filter { it in unresolved && it !in spokenFor }
            if (kept.isNotEmpty()) {
                parked[i + 1] = kept
                spokenFor += kept
            }
        }
        return parked
    }

    /**
     * "the template reads ${quoteId} and ${offer}, and the phase that keeps them, phase 1 · Ask for a
     * quote, is muted. Unmute it, or add them under Seed."
     *
     * In the reading phase's voice, because that is the phase whose edit button is one line above the
     * sentence, and naming the muted phase by number and label saves its reader going to look for it. The
     * remedy follows the surface, as the seed sentence's does: only a dialog has a Seed band to point at.
     */
    private fun mutedCaptureSentence(
        names: List<String>,
        before: Int,
        label: String,
        surface: LoadPlan.Surface,
    ): String {
        val shown = names.map { "\${$it}" }
        val many = shown.size > 1
        val read = if (many) shown.dropLast(1).joinToString(", ") + " and " + shown.last() else shown.single()
        return "the template reads $read, and the phase that keeps ${if (many) "them" else "it"}, " +
            "phase $before · $label, is muted. Unmute it, or ${surface.scopeRemedy(many)}."
    }

    /**
     * **[from] takes the place of [to], and every trigger still names the phase it named.**
     *
     * A trigger is an ordinal, so any edit that moves a phase moves what a later phase's `after` points
     * at. Three editing paths had their own copy of the arithmetic for the list and none for the trigger,
     * which is how a reorder would have quietly repointed a reactive phase at whatever took its place.
     * One owner, pinned by tests rather than by three copies of the sum. Both indices are 0-based, as the
     * dialog's rows are, and one out of range is no change at all.
     */
    fun movePhase(from: Int, to: Int): LoadSet {
        if (from !in phases.indices) return this
        val target = to.coerceIn(0, phases.lastIndex)
        if (target == from) return this
        val order = phases.indices.toMutableList().also { it.add(target, it.removeAt(from)) }
        return reordered(order, order.map { phases[it] })
    }

    /**
     * **The phase at [index] is gone, and so is any trigger that named it.**
     *
     * A phase whose trigger has been removed keeps its reactive shape and loses its `after`, so
     * [problems] says it is reactive and names no phase to react to. Silently repointing it at whatever
     * moved into that position would be the one outcome nobody asked for.
     */
    fun removePhase(index: Int): LoadSet {
        if (index !in phases.indices) return this
        val order = phases.indices.filter { it != index }
        return reordered(order, order.map { phases[it] })
    }

    /**
     * **A copy of the phase at [index], right after it, and every trigger still names the original.**
     *
     * The copy sits one place later, so a phase reacting to the duplicated one goes on reacting to the
     * one that was already there rather than to its new twin, and a copy of a reactive phase reacts to
     * the same trigger its original does.
     */
    fun duplicatePhase(index: Int): LoadSet {
        if (index !in phases.indices) return this
        val spec = phases[index]
        val order = phases.indices.toMutableList().also { it.add(index + 1, index) }
        val built = phases.toMutableList().also { it.add(index + 1, spec.copy(label = spec.label + " copy")) }
        return reordered(order, built)
    }

    /**
     * The set with [built] as its phases, every `after` following the phase it names.
     *
     * [order] is the new list of **old** positions, so a phase that was third and is now first appears at
     * position 0 as 2, and it lines up with [built] one for one. The first entry for an old position wins,
     * which is what makes a duplicate's trigger the original and not the copy, and a name [order] leaves
     * out loses its trigger rather than inheriting a stranger's.
     */
    private fun reordered(order: List<Int>, built: List<LoadPhaseSpec>): LoadSet {
        val moved = mutableMapOf<Int, Int>()
        order.forEachIndexed { position, old -> moved.putIfAbsent(old, position + 1) }
        return copy(phases = built.map { spec -> spec.after?.let { spec.copy(after = moved[it - 1]) } ?: spec })
    }

    /**
     * **One [LoadPlan] per phase, the seed rendered once and shared, every phase under one record id.**
     *
     * [seedOverride] wins over the file, which is how a build passes its own number in: `--seed
     * run=$BUILD_NUMBER` makes the build id the run id, and the venue's logs and the build log then agree
     * on it. An overridden value is taken literally, because a command line has no generators to render.
     *
     * Throws [IllegalArgumentException] when a name does not resolve, so a caller that skipped
     * [problems] finds out rather than running four of six phases.
     *
     * **A fold and not a map**, because a reactive phase's count and index range are its trigger's and it
     * has to have that phase's plan in hand to take them. Every trigger is an earlier phase, so the plan
     * it needs is always one this fold has already built.
     */
    fun plan(resolve: Resolver, seedOverride: Map<String, String>, id: String): Planned {
        require(phases.isNotEmpty()) { "a load set needs a phase" }
        val rendered = seed.mapValues { (_, v) -> CompiledTemplate.renderGenerators(v) } + seedOverride
        val plans =
            phases.foldIndexed(mutableListOf<LoadPlan>()) { index, built, spec ->
                val n = index + 1
                val profile =
                    requireNotNull(resolve.profile(spec.profile)) { "phase $n names no saved profile '${spec.profile}'" }
                val template =
                    requireNotNull(resolve.template(spec.template, profile.id)) { "phase $n names no template '${spec.template}'" }
                val match =
                    requireNotNull(spec.match ?: template.inferMatch()) {
                        "phase $n's template '${template.name}' carries no tag a reply is matched on"
                    }
                val trigger =
                    if (spec.shape is LoadShape.Triggered) {
                        val after = requireNotNull(spec.after) { "phase $n is reactive and names no phase to react to" }
                        require(after in 1 until n) { "phase $n reacts to phase $after, which does not run before it" }
                        built[after - 1]
                    } else {
                        null
                    }
                built +=
                    LoadPlan(
                        id = id,
                        label = spec.label,
                        template = template,
                        profileId = profile.id,
                        profileName = profile.name,
                        listenProfileIds = spec.listen.mapNotNull { resolve.profile(it)?.id },
                        shape = spec.shape,
                        match = match,
                        settleMs = spec.settleMs,
                        seed = rendered,
                        storeAndLog = storeAndLog,
                        strictRate = spec.strictRate,
                        // A reactive phase answers one message per message its trigger issued, at that
                        // message's own index, so both numbers are the trigger's and neither is authored.
                        indexFrom = trigger?.indexFrom ?: spec.indexFrom,
                        capture = spec.capture,
                        muted = spec.muted,
                        requested = trigger?.requested ?: spec.shape.ownCount ?: 0L,
                        after = spec.after,
                    )
                built
            }
        return Planned(id, label.ifBlank { name }, name, onFailure, rendered, storeAndLog, plans)
    }

    companion object {
        /**
         * **2 since a phase could be reactive**, which added `after` and the `triggered` shape.
         *
         * Read on the way in as well as written on the way out, which it never was: a set from a later
         * FixTool is refused whole rather than read with the half of itself this version understands.
         * Forward hygiene and nothing more, because no shipped FixTool reads a load set at all: this file
         * and its codec both postdate `v1.17.0` and load sets are still unreleased.
         */
        const val SCHEMA = 2

        /** A file name from a set's name, the same shape a scenario run set uses. Never carries an `=`. */
        fun slug(name: String): String {
            val slug =
                buildString { for (c in name.lowercase()) append(if (c in 'a'..'z' || c in '0'..'9') c else '-') }
                    .replace(Regex("-+"), "-")
                    .trim('-')
                    .take(SLUG_MAX)
            return slug.ifBlank { "set" }
        }

        private const val SLUG_MAX = 60
    }
}

/**
 * **One member of a set: a complete load plan minus the two things the set owns.**
 *
 * A phase keeps its own template, issuing profile, listen profiles, match tags, reply type, shape and
 * settle window, because the RFQ example changes four of those between phases and a cancel storm is often
 * shorter than the order storm it follows.
 */
data class LoadPhaseSpec(
    val label: String,
    /** As `LoadTemplates.resolve` reads it: a path, then a saved message by id or name. */
    val template: String,
    /** The initiator profile whose lanes issue, one lane per session it opens, by id or name. */
    val profile: String,
    /**
     * **Profiles whose sessions take part in matching**, by id or name.
     *
     * A profile named here may also be the issuing profile of another phase, a two-sided set being the
     * ordinary case: the initiator issues in phase 1 while the responder listens, and the responder issues
     * in phase 2 while the initiator listens. Its issuing lanes then do the listening, all of them, so
     * naming a profile here never costs it the sessions it issues on. A profile no phase ever issues from
     * gets one session, because matching is the only thing it is there for.
     */
    val listen: List<String> = emptyList(),
    /** Null infers from the template, as a single run does. */
    val match: LoadMatch? = null,
    val shape: LoadShape,
    /**
     * Where this phase's `${messageIndex}` starts, 1-based.
     *
     * `${messageIndex}` restarts at 1 in every phase, so there was no way to say "the other 2,000". A
     * three-phase RFQ set hits indices 1 to 2,000 and passes 2,001 to 4,000, and this is how the second
     * one says so.
     */
    val indexFrom: Int = 1,
    /**
     * **Named tag values kept off each matched reply, for a later phase to address.**
     *
     * `{"quoteId": 117, "offer": 133}` keeps QuoteID and OfferPx from every Quote this phase's requests
     * draw, at that request's own message index. A later phase reads them as per-message names,
     * `117=${quoteId}`, resolved by the same `${messageIndex}` its ids are built from.
     *
     * This is what makes a load set work against a venue that mints its own ids. The derivable seed covers
     * the RFQ round trip against a venue built to be addressable from it, and nothing else.
     */
    val capture: Map<String, Int> = emptyMap(),
    val settleMs: Long = LoadPlan.DEFAULT_SETTLE_MS,
    val strictRate: Boolean = false,
    /**
     * **Parked, not deleted.** The phase keeps its template, its profile, its shape and its place in the
     * order, and the runner skips it entirely: no lane of its profile is opened and nothing is issued.
     *
     * The record still carries its plan, as a SKIPPED phase noted [LoadRecord.MUTED_NOTE], so a reader can
     * say what would have run. Written to the file only when set and read back absent as false, which is
     * the same bargain a scenario step's `muted` strikes: a set that never parked a phase never grows the
     * key.
     */
    val muted: Boolean = false,
    /**
     * **The earlier phase this one reacts to**, 1-based, for a [LoadShape.Triggered] phase and nothing else.
     *
     * Named by ordinal rather than by label, because every sentence the set prints already says "phase N"
     * and a label in the JSON reads worse than the number beside it. A label would survive a reorder for
     * free, which the ordinal does not: [LoadSet.movePhase], [LoadSet.removePhase] and
     * [LoadSet.duplicatePhase] own that arithmetic so no editing path has to know it.
     *
     * A phase with a burst or a rate that names one is refused rather than quietly ignored, because a
     * setting a surface accepted and then dropped is worse than one it never took.
     */
    val after: Int? = null,
) {
    /**
     * Whether the row says where this phase's indices start.
     *
     * No " from N" for a reactive phase: its indices are its trigger's, and a spec has no trigger plan to
     * read them off, so the only honest thing a spec can print for a derived number is nothing.
     */
    private val countsFromHere: Boolean get() = indexFrom > 1 && shape !is LoadShape.Triggered

    /** "RFQ Load Pass · 35=AJ → AI · 117 QuoteID · ×2,000 from 2,001 · settle 30s", for a row and a block. */
    fun describe(): String =
        listOfNotNull(
            template,
            // `describe()` and not the data class's own toString, which put "LoadMatch(requestTag=131,
            // replyTag=131, replyType=S)" on the phase row where "131 → 131, reply 35=S" belongs.
            match?.describe(),
            shape.describe() + if (countsFromHere) " from ${"%,d".format(indexFrom)}" else "",
            after?.let { "after phase $it" },
            "settle ${humanDuration(settleMs)}",
            capture.keys.takeIf { it.isNotEmpty() }?.let { "keeps ${it.joinToString(", ")}" },
        ).joinToString(" · ")
}

/** What a set does after a phase that did not pass. */
enum class OnFailure {
    /** Skip the rest and report them, which is what a build wants: phase two proved nothing, so stop. */
    STOP,

    /** Run every phase and let the set verdict name the first that did not pass. */
    CONTINUE,
}
