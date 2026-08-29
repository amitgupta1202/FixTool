package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.ui.sessionOrNull

/**
 * **Which sessions a run will touch, decided before it starts.**
 *
 * The run slot is a claim over sessions rather than one global boolean, and that only works if the claim
 * can be checked *before* the run begins. The runner already resolves this — but inside preflight, after
 * the slot was taken, which is too late to ask whether anything else holds those sessions.
 *
 * Pure on purpose. This is the part of the concurrency rule that can be reasoned about at a desk, and the
 * part that decides whether two runs may share a venue, so it is kept where a test can reach it.
 */
object RunSessions {
    /**
     * [sessions] are the titles the run will drive. [exclusive] means the answer could not be determined
     * — and is deliberately *not* the same as an empty set.
     */
    data class Touched(
        val sessions: Set<String>,
        val exclusive: Boolean = false,
    )

    /**
     * The sessions one scenario would touch, through its remap.
     *
     * A step naming no session runs on [defaultSession] — the lane's, for a fan-out entry — or failing
     * that on [firstOpen], which is what `ViewModelScenarioHost.resolveSession(null)` picks. When neither
     * exists the run's target is genuinely unknown, and the claim must take everything: two runs that
     * cannot name what they touch would otherwise agree they were disjoint and drive the same session.
     *
     * Muted steps are excluded because they do not run, and a claim is about what will happen.
     */
    fun of(
        scenario: Scenario,
        sessionMap: Map<String, String> = emptyMap(),
        defaultSession: String? = null,
        firstOpen: String? = null,
    ): Touched {
        val steps = (scenario.setup + scenario.steps + scenario.teardown).filterNot { it.muted }
        val named = steps.mapNotNull { it.sessionOrNull() }.map { sessionMap[it] ?: it }
        if (steps.none { it.sessionOrNull() == null }) return Touched(named.toSet())
        val fallback = defaultSession ?: firstOpen
        return Touched((named + listOfNotNull(fallback)).toSet(), exclusive = fallback == null)
    }

    /** The union over a set's entries — each with its own remap and its own lane. */
    fun ofAll(parts: List<Touched>): Touched =
        Touched(
            sessions = parts.flatMapTo(mutableSetOf()) { it.sessions },
            exclusive = parts.any { it.exclusive },
        )

    /**
     * **May these two run at the same time?** Only when both know what they touch and the two sets are
     * disjoint — the licence fan-out already rests on for its lanes, one level up.
     */
    fun conflict(held: Touched, wanted: Touched): Boolean =
        held.exclusive || wanted.exclusive || held.sessions.any { it in wanted.sessions }
}
