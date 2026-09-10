package com.knapsack.fixtool.service.load

import com.knapsack.fixtool.model.load.LoadPlan
import com.knapsack.fixtool.model.load.LoadReport
import com.knapsack.fixtool.model.load.LoadSet
import com.knapsack.fixtool.model.load.LoadShape
import com.knapsack.fixtool.service.RunSetStats

/**
 * **Which phases of a set form a chain, and what that chain cost per message.**
 *
 * Kept apart from [LoadSetRunner] because it is arithmetic over a finished set and nothing else: no
 * threads, no locks, no lanes. The runner hands it the plan it ran and the times its matchers filled, once
 * every phase has ended, and gets back the block each chain's last phase carries.
 *
 * **A chain is a path down the trigger graph**, from a phase that reacts to nothing to a phase nothing
 * reacts to. Two phases reacting to one gives two chains through that one, and each is measured whole,
 * because a message travelling one of them is a different journey from a message travelling the other.
 */
internal object Chains {
    /**
     * The 0-based phases that take part in any chain, which is exactly the set that needs arrays.
     *
     * Empty for a set with no reacting phase, and that emptiness is what keeps a staged set's record byte
     * for byte the record it was: no arrays are allocated, no times are written and no block is attached.
     */
    fun participants(planned: LoadSet.Planned): Set<Int> =
        paths(planned).flatten().toSet()

    /**
     * The chain block for each chain, by the 0-based phase it ends at.
     *
     * A chain nothing completed is left out rather than reported as zeroes. A distribution over no
     * journeys is not a measurement, and the phases' own counts already say that nothing got through.
     */
    fun of(planned: LoadSet.Planned, times: ChainTimes): Map<Int, LoadReport.Chain> =
        paths(planned).mapNotNull { path -> chainOf(planned, times, path)?.let { path.last() to it } }.toMap()

    /**
     * Every chain as a path of 0-based phases, root first.
     *
     * A path ends where nothing reacts to it, so a chain of three phases produces one path and not three
     * prefixes of one. It starts where the trigger links stop, which for a phase reacting to a paced
     * phase is that paced phase.
     */
    private fun paths(planned: LoadSet.Planned): List<List<Int>> {
        val size = planned.phases.size
        val leaves = (0 until size).filter { trigger(planned, it) != null && (0 until size).none { n -> trigger(planned, n) == it } }
        return leaves.map { leaf -> generateSequence(leaf) { trigger(planned, it) }.toList().reversed() }
    }

    /**
     * The 0-based phase this one reacts to, or null when it runs on a schedule of its own.
     *
     * The same three conditions [LoadPlan.reactsTo] asks, and for the same reason: a paced phase can
     * carry an `after` when a plan reaches the runner without being validated, and it waits on that
     * phase's start and reads nothing it kept, so it is nobody's chain.
     */
    private fun trigger(planned: LoadSet.Planned, index: Int): Int? {
        val plan = planned.phases[index]
        if (plan.muted || plan.shape !is LoadShape.Triggered) return null
        val at = (plan.after ?: return null) - 1
        return at.takeIf { it in 0 until index && !planned.phases[it].muted }
    }

    /** One path's block, or null when nothing travelled it whole and there is nothing to describe. */
    private fun chainOf(planned: LoadSet.Planned, times: ChainTimes, path: List<Int>): LoadReport.Chain? {
        val root = planned.phases[path.first()]
        val from = root.indexFrom
        val to = root.indexTo.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (to < from) return null
        val phases = path.map { times[it] ?: return null }
        val legs = path.indices.map { at -> leg(planned.phases[path[at]], path[at] + 1, phases, at, from, to) ?: return null }
        val end = Samples(to - from + 1)
        for (index in from..to) {
            val started = phases.first().sentAt(index)
            val finished = phases.last().answeredAt(index)
            if (started != ChainTimes.NONE && finished != ChainTimes.NONE) end.add(finished - started)
        }
        val endToEnd = RunSetStats.of(end.sorted()) ?: return null
        return LoadReport.Chain(
            legs = legs,
            endToEnd = endToEnd,
            complete = endToEnd.samples.toLong(),
            requested = root.requested,
        )
    }

    /** One phase's leg: what it waited for, what its own round trip was, and how often it was answered. */
    @Suppress("LongParameterList")
    private fun leg(
        plan: LoadPlan,
        phase: Int,
        phases: List<ChainTimes.Phase>,
        at: Int,
        from: Int,
        to: Int,
    ): LoadReport.Leg? {
        val here = phases[at]
        val before = phases.getOrNull(at - 1)
        val size = to - from + 1
        val trips = Samples(size)
        val handovers = Samples(size)
        for (index in from..to) {
            val sent = here.sentAt(index)
            val answered = here.answeredAt(index)
            if (sent != ChainTimes.NONE && answered != ChainTimes.NONE) trips.add(answered - sent)
            val released = before?.answeredAt(index) ?: ChainTimes.NONE
            if (sent != ChainTimes.NONE && released != ChainTimes.NONE) handovers.add(sent - released)
        }
        val roundTrip = RunSetStats.of(trips.sorted()) ?: return null
        return LoadReport.Leg(
            phase = phase,
            label = plan.label,
            handover = if (before == null) null else RunSetStats.of(handovers.sorted()),
            roundTrip = roundTrip,
            answered = roundTrip.samples.toLong(),
        )
    }

    /**
     * The samples of one leg as a primitive array, sorted once.
     *
     * A list of boxed longs would be three hundred thousand `Long`s per leg on a run that size, allocated
     * to be sorted and thrown away. This is assembled once at the end of a set, but so is the round-trip
     * array it sits beside, and that one is primitive for the same reason.
     */
    private class Samples(
        capacity: Int,
    ) {
        private val data = LongArray(capacity.coerceAtLeast(0))
        private var size = 0

        fun add(value: Long) {
            if (size < data.size) data[size++] = value
        }

        fun sorted(): LongArray = data.copyOf(size).also { it.sort() }
    }
}
