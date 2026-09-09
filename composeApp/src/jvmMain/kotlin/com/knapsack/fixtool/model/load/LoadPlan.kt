package com.knapsack.fixtool.model.load

import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionConfig.MessageLogKind
import com.knapsack.fixtool.model.FixConnectionConfig.MessageStoreKind
import com.knapsack.fixtool.model.scenario.Lane
import com.knapsack.fixtool.service.load.CompiledTemplate

/**
 * **Everything a load run is asked to do, decided before the first lane logs on.**
 *
 * A load run issues one [template] across the lanes of one profile without waiting for replies, then
 * accounts for every reply that lands on any participating session. Burst and sustained rate are the same
 * run with a different [shape]; everything downstream of the pacer is identical, which is why this is one
 * plan and not two.
 */
data class LoadPlan(
    /** Also the record directory: `loads/<id>/`. */
    val id: String,
    /** "NOS EUR/USD 1M ×4,000 on LOADGEN". What the document, the summary and Recent call it. */
    val label: String,
    val template: LoadTemplate,
    /** The initiator profile whose lanes issue, one lane per session it opens. */
    val profileId: String,
    val profileName: String,
    /** Profiles whose sessions take part in matching but never issue: a drop-copy, a dealer side. */
    val listenProfileIds: List<String> = emptyList(),
    val shape: LoadShape,
    val match: LoadMatch,
    /** How long to wait for replies after the last send. The window closes early when nothing is pending. */
    val settleMs: Long = DEFAULT_SETTLE_MS,
    /** Values seeded into every message's scope, as `${name}`: `run=b7f2` lets a second run address the first. */
    val seed: Map<String, String> = emptyMap(),
    /** The store and log for the sessions this run opens, when they should differ from the profile's. */
    val storeAndLog: StoreAndLogOverride? = null,
    /** Promote a rate shortfall from a reported verdict to a failing exit code. */
    val strictRate: Boolean = false,
    /**
     * Where `${messageIndex}` starts, 1-based, so a phase of a set can address the half another left.
     *
     * Added to the pacer's counter before the prototype renders, and nowhere else: one integer, and the
     * only reason a three-phase RFQ set can say "hit the first 2,000, pass the other 2,000".
     */
    val indexFrom: Int = 1,
    /**
     * Named tag values this phase keeps off each matched reply, for a later phase to address. See
     * [LoadPhaseSpec.capture]: a single run captures nothing, because it has no later phase to read them.
     */
    val capture: Map<String, Int> = emptyMap(),
    /**
     * **This phase of a set is parked**, so the runner skips it and the record notes it as skipped.
     *
     * Set-only, as [indexFrom] and [capture] are: a single run has nothing to park it in. The plan is
     * carried all the same, because the record has to say what would have run. See [LoadPhaseSpec.muted].
     */
    val muted: Boolean = false,
    /**
     * **How many messages the plan asks for**, stored rather than read off the shape.
     *
     * A burst and a rate both count their own messages, so for those this is [LoadShape.ownCount] and the
     * default is the whole answer. A reactive phase issues one message for each message the phase it reacts
     * to issued, so its count belongs to that phase and only [LoadSet.plan] has it: the field is what lets
     * the report, the render-ahead and `indexTo` ask one question and get one answer whatever the shape.
     */
    val requested: Long = shape.ownCount ?: 0L,
    /**
     * **The earlier phase this one reacts to**, 1-based, or null when it runs on its own schedule.
     *
     * Carried on the plan and not only on [LoadPhaseSpec] because the runner is handed plans and never
     * sees a spec, so a spec-only trigger would be invisible to the thing that has to honour it.
     */
    val after: Int? = null,
) {
    /**
     * **Every reason this plan cannot run, in the sentences its surfaces print.**
     *
     * The dialog, `fixtool load` and `POST /load` each grew their own copy of this list, and they had
     * already drifted: the dialog refuses a template that reads a name nothing seeds, and neither of the
     * other two noticed until [com.knapsack.fixtool.service.load.LoadRunner] threw at run time. One list
     * here, three callers, and #45's load set gets to validate a phase without a composition.
     *
     * [config] is the profile's, before [storeAndLog] is applied — the override is what makes the store
     * problem this run's problem rather than the profile's.
     *
     * What this cannot say is anything the plan's own types already rule out: a plan has a template, a
     * match and a shape or it does not exist. Those refusals belong to whatever is assembling the plan,
     * and each surface keeps them, because "pick a template" and "say --count or --rate" are different
     * sentences about the same absence.
     */
    fun problems(config: FixConnectionConfig, surface: Surface): List<String> =
        problems(template, seed, profileName, config, storeAndLog, surface)

    /** The 1-based index of this plan's last message, which is what `indexFrom` shifts. */
    val indexTo: Long get() = indexFrom - 1L + requested

    /**
     * Where a refusal is about to be read, which decides only how its remedy is phrased. The sentence and
     * the diagnosis are the same everywhere; "Add run=… under Seed" is simply a lie on a command line.
     */
    enum class Surface(
        internal val seedRemedy: (String) -> String,
        /**
         * The other way to put a name in scope, as a clause rather than a sentence: the muted-capture
         * refusal ends "Unmute it, or seed them with --seed." and names every name at once, so it cannot
         * borrow [seedRemedy], which is a whole sentence about one of them.
         */
        internal val scopeRemedy: (Boolean) -> String,
    ) {
        DIALOG({ "Add $it=… under Seed." }, { if (it) "add them under Seed" else "add it under Seed" }),
        CLI(
            { "Pass --seed $it=… on the command line, or capture it in an earlier phase of a set." },
            { if (it) "seed them with --seed" else "seed it with --seed" },
        ),
        API({ "Add \"$it\" to the request's seed object." }, { if (it) "seed them" else "seed it" }),
    }

    companion object {
        const val DEFAULT_SETTLE_MS = 60_000L

        /**
         * [problems] for the parts of a plan, so the dialog can ask before it has one. A form that is still
         * missing its shape is not a plan and can still be told its store is wrong.
         */
        fun problems(
            template: LoadTemplate,
            seed: Map<String, String>,
            profileName: String,
            config: FixConnectionConfig,
            storeAndLog: StoreAndLogOverride?,
            surface: Surface,
        ): List<String> =
            templateProblems(template, seed.keys, surface) +
                listOfNotNull(storeProblem(profileName, config, storeAndLog))

        /**
         * What is wrong with the message itself: no MsgType, or a name nothing seeds.
         *
         * Split from the store's own sentence because a set applies one store override to every phase, so
         * the store is refused once for the whole set while the template is refused per phase.
         */
        fun templateProblems(template: LoadTemplate, seeded: Set<String>, surface: Surface): List<String> {
            val compiled =
                template.takeIf { it.msgType != null }?.let { runCatching { CompiledTemplate.compile(it) }.getOrNull() }
            val missing = compiled?.missingVariables(seeded + Lane.SEED_NAMES).orEmpty()
            return listOfNotNull(
                if (template.msgType == null) "The template has no MsgType (35)." else null,
                if (missing.isEmpty()) {
                    null
                } else {
                    "The template reads ${missing.joinToString(", ") { "\${$it}" }} and nothing seeds " +
                        "${if (missing.size == 1) "it" else "them"}. ${surface.seedRemedy(missing.first())}"
                },
            )
        }

        /**
         * The store the run's own sessions would open with, when it cannot work.
         *
         * Printed whole, prefixed only by the profile's name. `storeProblem()` already ends with its own
         * remedy and eight callers share it, so a surface that appends a second one puts the same advice
         * on screen twice, which is exactly what this dialog was doing.
         */
        fun storeProblem(profileName: String, config: FixConnectionConfig, storeAndLog: StoreAndLogOverride?): String? =
            (storeAndLog?.applyTo(config) ?: config).storeProblem()?.let { "$profileName: $it" }

        /** The label every surface shows, built once so the CLI, the rail and the JSON cannot disagree. */
        fun label(template: LoadTemplate, shape: LoadShape, profileName: String): String =
            "${template.name} ${shape.describe()} on $profileName"
    }
}

/** Burst, sustained rate, or reacting to an earlier phase: the three parameters of one feature. */
sealed interface LoadShape {
    /**
     * **How many messages this shape asks for on its own, or null when only its trigger knows.**
     *
     * A burst counts its own messages and a rate multiplies two numbers it is holding, so both can answer
     * before a lane is open. A reactive phase issues one message for each message the phase it reacts to
     * issued, and a shape has no way to reach that phase: [LoadPlan.requested] is a stored field for this
     * reason, and [LoadSet.plan] is a fold so it has the trigger's plan in hand when it fills it in.
     */
    val ownCount: Long?

    fun describe(): String

    /** Issue [count] messages as fast as the lanes carry them. */
    data class Burst(
        val count: Int,
    ) : LoadShape {
        override val ownCount: Long get() = count.toLong()

        override fun describe(): String = "×${"%,d".format(count)}"
    }

    /** Issue [perSecond] messages a second for [forMs], on a schedule the pacer never skips. */
    data class Rate(
        val perSecond: Int,
        val forMs: Long,
    ) : LoadShape {
        /** Kept as its own property, not only as [ownCount], because the pacer needs it off a [Rate]. */
        val requested: Long get() = perSecond.toLong() * forMs / MILLIS_PER_SECOND

        override val ownCount: Long get() = requested

        override fun describe(): String = "$perSecond/s for ${humanDuration(forMs)}"

        private companion object {
            const val MILLIS_PER_SECOND = 1_000L
        }
    }

    /**
     * **Issue one message for each message an earlier phase issued, as that phase's replies land.**
     *
     * The phase it reacts to is [LoadPhaseSpec.after], and the count and the index range are that phase's:
     * a reactive phase never asks for a number of its own. That is what turns a three-phase RFQ set from
     * three blocks into 196 chains that finish in the first 200ms, with the four that never answered still
     * costing the trigger's whole settle window.
     *
     * [cap] is a ceiling in messages a second and never a schedule, so a second nothing was released in is
     * a second nothing was ready, not a shortfall. Null issues each message the moment its trigger lands.
     */
    data class Triggered(
        val cap: Int? = null,
    ) : LoadShape {
        override val ownCount: Long? get() = null

        override fun describe(): String = "reactive" + (cap?.let { ", capped $it/s" } ?: "")
    }
}

/**
 * How a reply is paired with its request: the tag read off the request, the tag read off the reply, and an
 * optional reply MsgType so a `35=j` carrying the id does not count as the answer to a `35=D`.
 */
data class LoadMatch(
    val requestTag: Int,
    val replyTag: Int = requestTag,
    val replyType: String? = null,
) {
    fun describe(): String = "$requestTag → $replyTag" + (replyType?.let { ", reply 35=$it" } ?: "")
}

/** A per-run store and log, applied to the sessions the run opens and never written back to the profile. */
data class StoreAndLogOverride(
    val store: MessageStoreKind,
    val log: MessageLogKind,
) {
    fun applyTo(config: FixConnectionConfig): FixConnectionConfig = config.copy(messageStore = store, messageLog = log)

    fun describe(): String = "${store.name.lowercase()} store, ${if (log == MessageLogKind.NONE) "no log" else "file log"}"

    companion object {
        /** What a load run wants unless told otherwise. */
        val FOR_LOAD = StoreAndLogOverride(MessageStoreKind.MEMORY, MessageLogKind.NONE)
    }
}

/** `90s`, `10m`, `1h 5m`: durations as a person writes them, for labels. */
fun humanDuration(ms: Long): String {
    val totalSeconds = ms / MILLIS
    val hours = totalSeconds / SECONDS_PER_HOUR
    val minutes = totalSeconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return listOfNotNull(
        hours.takeIf { it > 0 }?.let { "${it}h" },
        minutes.takeIf { it > 0 }?.let { "${it}m" },
        seconds.takeIf { it > 0 || (hours == 0L && minutes == 0L) }?.let { "${it}s" },
    ).joinToString(" ")
}

private const val MILLIS = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L
