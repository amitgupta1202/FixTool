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
    /** The multi-session initiator profile whose lanes issue. */
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
) {
    /** How many messages the plan asks for. */
    val requested: Long get() = shape.requested

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

    /**
     * Where a refusal is about to be read, which decides only how its remedy is phrased. The sentence and
     * the diagnosis are the same everywhere; "Add run=… under Seed" is simply a lie on a command line.
     */
    enum class Surface(
        internal val seedRemedy: (String) -> String,
    ) {
        DIALOG({ "Add $it=… under Seed." }),
        CLI({ "Pass --set $it=… on the command line." }),
        API({ "Add \"$it\" to the request's seed object." }),
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
        ): List<String> {
            val compiled =
                template.takeIf { it.msgType != null }?.let { runCatching { CompiledTemplate.compile(it) }.getOrNull() }
            val missing = compiled?.missingVariables(seed.keys + Lane.SEED_NAMES).orEmpty()
            return listOfNotNull(
                if (template.msgType == null) "The template has no MsgType (35)." else null,
                if (missing.isEmpty()) {
                    null
                } else {
                    "The template reads ${missing.joinToString(", ") { "\${$it}" }} and nothing seeds " +
                        "${if (missing.size == 1) "it" else "them"}. ${surface.seedRemedy(missing.first())}"
                },
                // Printed whole, prefixed only by the profile's name. storeProblem() already ends with its
                // own remedy and eight callers share it, so a surface that appends a second one puts the
                // same advice on screen twice — which is exactly what this dialog was doing.
                (storeAndLog?.applyTo(config) ?: config).storeProblem()?.let { "$profileName: $it" },
            )
        }

        /** The label every surface shows, built once so the CLI, the rail and the JSON cannot disagree. */
        fun label(template: LoadTemplate, shape: LoadShape, profileName: String): String =
            "${template.name} ${shape.describe()} on $profileName"
    }
}

/** Burst or sustained rate: the two parameters of one feature. */
sealed interface LoadShape {
    val requested: Long

    fun describe(): String

    /** Issue [count] messages as fast as the lanes carry them. */
    data class Burst(
        val count: Int,
    ) : LoadShape {
        override val requested: Long get() = count.toLong()

        override fun describe(): String = "×${"%,d".format(count)}"
    }

    /** Issue [perSecond] messages a second for [forMs], on a schedule the pacer never skips. */
    data class Rate(
        val perSecond: Int,
        val forMs: Long,
    ) : LoadShape {
        override val requested: Long get() = perSecond.toLong() * forMs / MILLIS_PER_SECOND

        override fun describe(): String = "$perSecond/s for ${humanDuration(forMs)}"

        private companion object {
            const val MILLIS_PER_SECOND = 1_000L
        }
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
