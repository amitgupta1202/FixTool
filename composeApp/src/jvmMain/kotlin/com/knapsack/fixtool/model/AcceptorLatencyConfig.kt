package com.knapsack.fixtool.model

import kotlinx.serialization.Serializable
import java.util.Random

/**
 * The venue's latency, modelled: how long an acceptor waits before an auto-response reaches the wire.
 *
 * FixTool otherwise replies as fast as the machine allows, which is a latency a real venue never has —
 * so a client whose timeout/retry logic is wrong passes here and fails in production, the tool masking
 * the class of bug it exists to find. This adds the delay back, and lets it vary the way a network does.
 *
 * ### One sample per trigger, not per message
 *
 * A sample is drawn **once for each incoming message that fires a rule**, and the rule's whole reply —
 * ack, then partial, then fill — is shifted by that one number. The authored inter-step gaps
 * ([ResponseStep.delayMillis]) are left exactly as written, so the sequence keeps its order and its
 * shape; only its start slides. Sampling each step independently would let an unlucky draw land the
 * partial before the ack, inverting an order the author wrote — the hazard [AcceptorDispatch]'s single
 * thread exists to prevent, undone by the very feature meant to sit on top of it. Different triggers
 * each draw their own sample, so a stream of orders still sees the full spread of the distribution.
 *
 * ### Composition with per-step delays
 *
 * This is the trigger→first-reply delay, the network hop the venue's own processing time
 * ([ResponseStep.delayMillis], measured step-to-step) is layered on top of. The two are added, not
 * chosen between: a step that says "+500ms" means 500ms after the step before it *however late the
 * whole reply started*, which is what "500ms of venue processing" means whether the network was quick
 * or slow that time.
 */
@Serializable
data class AcceptorLatencyConfig(
    val mode: Mode = Mode.NONE,
    /** [Mode.FIXED]: every response delayed by exactly this. */
    val fixedMillis: Long = 0,
    /** [Mode.RANDOM_RANGE]: delay drawn uniformly from [[minMillis], [maxMillis]]. */
    val minMillis: Long = 0,
    val maxMillis: Long = 0,
    /** [Mode.NORMAL]: delay drawn from a normal distribution, clamped at zero. */
    val meanMillis: Long = 0,
    val stdDevMillis: Long = 0,
    /**
     * Chance in [0, 1] that a response is a spike — a large delay drawn from [[spikeMinMillis],
     * [spikeMaxMillis]] **instead of** the ordinary sample — simulating an occasional network stall.
     * Independent of [mode], so a venue can be instant-but-occasionally-stalling ([Mode.NONE] with a
     * spike probability set).
     */
    val spikeProbability: Double = 0.0,
    val spikeMinMillis: Long = 0,
    val spikeMaxMillis: Long = 0,
) {
    enum class Mode { NONE, FIXED, RANDOM_RANGE, NORMAL }

    /**
     * Whether this config would ever add a delay. The cheap check the acceptor makes before drawing a
     * sample, so a default (inert) config puts no RNG on the path of every message a loaded acceptor
     * receives, and existing profiles behave exactly as they did.
     */
    fun isActive(): Boolean = mode != Mode.NONE || spikeProbability > 0.0

    /**
     * One delay in milliseconds for one triggered reply. A spike, when it wins its coin flip, replaces
     * the ordinary sample rather than adding to it — "5% of responses stall to 2–5s" reads as an
     * absolute, which is how the field is written. Never negative: a distribution's lower tail and a
     * hand-edited negative are both floored at zero, since a reply cannot precede its trigger.
     */
    fun sample(random: Random): Long {
        if (spikeProbability > 0.0 && random.nextDouble() < spikeProbability) {
            return drawRange(random, spikeMinMillis, spikeMaxMillis)
        }
        return when (mode) {
            Mode.NONE -> 0L
            Mode.FIXED -> fixedMillis.coerceAtLeast(0)
            Mode.RANDOM_RANGE -> drawRange(random, minMillis, maxMillis)
            Mode.NORMAL -> (meanMillis + random.nextGaussian() * stdDevMillis).toLong().coerceAtLeast(0)
        }
    }

    /**
     * What is wrong with this config, in the author's words, or null if it is usable — reported beside
     * the panel that edits it, never by the file format, the same stance as [AcceptorResponseRule].
     * The digit-only fields cannot go negative through the editor, so these guard the orderings and the
     * settings that are set but do nothing, which the editor cannot.
     */
    fun validationError(): String? =
        when {
            spikeProbability < 0.0 || spikeProbability > 1.0 ->
                "spike probability must be between 0 and 1 (e.g. 0.05 for 5%)"
            mode == Mode.RANDOM_RANGE && maxMillis < minMillis ->
                "the latency range's max (${maxMillis}ms) is below its min (${minMillis}ms)"
            spikeProbability > 0.0 && spikeMaxMillis < spikeMinMillis ->
                "the spike range's max (${spikeMaxMillis}ms) is below its min (${spikeMinMillis}ms)"
            spikeProbability > 0.0 && spikeMaxMillis <= 0L ->
                "a spike probability is set but the spike delay is zero, so spikes would do nothing"
            else -> null
        }

    /** A uniform draw from [[lo], [hi]], both floored at zero and ordered so a swapped pair still draws. */
    private fun drawRange(
        random: Random,
        lo: Long,
        hi: Long,
    ): Long {
        val low = lo.coerceAtLeast(0)
        val high = hi.coerceAtLeast(low)
        return if (high == low) low else low + (random.nextDouble() * (high - low)).toLong()
    }
}
