package com.knapsack.fixtool.model.load

import com.knapsack.fixtool.model.FixConnectionConfig
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
     */
    @Suppress("CyclomaticComplexMethod")
    fun problems(resolve: Resolver, surface: LoadPlan.Surface): List<Problem> {
        if (phases.isEmpty()) return listOf(Problem(null, "A set needs a phase. Add one under Phases."))
        val found = mutableListOf<Problem>()
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
        phases.forEachIndexed { index, spec ->
            val n = index + 1
            val profile = resolve.profile(spec.profile)
            if (profile == null) {
                found += Problem(n, "no saved connection profile named '${spec.profile}'.")
                return@forEachIndexed
            }
            if (judgedProfiles.add(profile.id)) {
                LoadPlan.storeProblem(profile.name, profile.config, storeAndLog)?.let { found += Problem(null, it) }
            }
            val template = resolve.template(spec.template, profile.id)
            if (template == null) {
                found +=
                    Problem(n, "no template '${spec.template}' — not a file, and no saved message of that id or name.")
                return@forEachIndexed
            }
            LoadPlan.templateProblems(template, seed.keys, surface).forEach { found += Problem(n, it) }
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
     * **One [LoadPlan] per phase, the seed rendered once and shared, every phase under one record id.**
     *
     * [seedOverride] wins over the file, which is how a build passes its own number in: `--seed
     * run=$BUILD_NUMBER` makes the build id the run id, and the venue's logs and the build log then agree
     * on it. An overridden value is taken literally, because a command line has no generators to render.
     *
     * Throws [IllegalArgumentException] when a name does not resolve, so a caller that skipped
     * [problems] finds out rather than running four of six phases.
     */
    fun plan(resolve: Resolver, seedOverride: Map<String, String>, id: String): Planned {
        require(phases.isNotEmpty()) { "a load set needs a phase" }
        val rendered = seed.mapValues { (_, v) -> CompiledTemplate.renderGenerators(v) } + seedOverride
        val plans =
            phases.mapIndexed { index, spec ->
                val n = index + 1
                val profile =
                    requireNotNull(resolve.profile(spec.profile)) { "phase $n names no saved profile '${spec.profile}'" }
                val template =
                    requireNotNull(resolve.template(spec.template, profile.id)) { "phase $n names no template '${spec.template}'" }
                val match =
                    requireNotNull(spec.match ?: template.inferMatch()) {
                        "phase $n's template '${template.name}' carries no tag a reply is matched on"
                    }
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
                    indexFrom = spec.indexFrom,
                )
            }
        return Planned(id, label.ifBlank { name }, name, onFailure, rendered, storeAndLog, plans)
    }

    companion object {
        const val SCHEMA = 1

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
    /** The multi-session initiator profile whose lanes issue, by id or name. */
    val profile: String,
    /** Profiles whose sessions take part in matching and never issue, by id or name. */
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
    val settleMs: Long = LoadPlan.DEFAULT_SETTLE_MS,
    val strictRate: Boolean = false,
) {
    /** "RFQ Load Pass · 35=AJ → AI · 117 QuoteID · ×2,000 from 2,001 · settle 30s", for a row and a block. */
    fun describe(): String =
        listOfNotNull(
            template,
            match?.let { "$it" },
            shape.describe() + (if (indexFrom > 1) " from ${"%,d".format(indexFrom)}" else ""),
            "settle ${humanDuration(settleMs)}",
        ).joinToString(" · ")
}

/** What a set does after a phase that did not pass. */
enum class OnFailure {
    /** Skip the rest and report them, which is what a build wants: phase two proved nothing, so stop. */
    STOP,

    /** Run every phase and let the set verdict name the first that did not pass. */
    CONTINUE,
}
