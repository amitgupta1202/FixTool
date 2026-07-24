package com.knapsack.fixtool.model

import com.knapsack.fixtool.model.AcceptorLatencyConfig.Mode
import org.junit.Test
import java.util.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The venue-latency the acceptor adds before a reply goes out.
 *
 * Sampling is asserted against a **seeded** [Random] so the draws are deterministic — what is being
 * pinned is the shape (which mode reads which fields, that a spike replaces rather than adds, that
 * nothing is ever negative), not the RNG, and an unseeded test of a random thing pins nothing.
 */
class AcceptorLatencyConfigTest {
    private fun seeded() = Random(42)

    // ------------------------------------------------------------------- isActive / the inert default

    @Test
    fun `the default config is inert, and says so`() {
        val config = AcceptorLatencyConfig()
        assertFalse(config.isActive(), "a fresh profile must add no delay — existing behaviour unchanged")
        repeat(100) { assertEquals(0L, config.sample(seeded()), "an inert config always samples zero") }
    }

    @Test
    fun `a spike alone makes the config active even in NONE mode`() {
        val config = AcceptorLatencyConfig(mode = Mode.NONE, spikeProbability = 0.1, spikeMaxMillis = 1000)
        assertTrue(config.isActive(), "instant-until-it-isn't is a real venue shape")
    }

    // --------------------------------------------------------------------------------- fixed

    @Test
    fun `fixed mode returns exactly the fixed delay`() {
        val config = AcceptorLatencyConfig(mode = Mode.FIXED, fixedMillis = 250)
        val random = seeded()
        repeat(100) { assertEquals(250L, config.sample(random)) }
    }

    @Test
    fun `a negative fixed delay is floored at zero, not sent as a reply before its trigger`() {
        val config = AcceptorLatencyConfig(mode = Mode.FIXED, fixedMillis = -500)
        assertEquals(0L, config.sample(seeded()))
    }

    // --------------------------------------------------------------------------- random range

    @Test
    fun `random range stays within its bounds over many draws`() {
        val config = AcceptorLatencyConfig(mode = Mode.RANDOM_RANGE, minMillis = 50, maxMillis = 300)
        val random = seeded()
        val samples = List(1000) { config.sample(random) }
        assertTrue(samples.all { it in 50..300 }, "every draw is inside [min, max]")
        assertTrue(samples.distinct().size > 1, "a 250ms-wide range must actually vary")
    }

    @Test
    fun `a zero-width range is a constant`() {
        val config = AcceptorLatencyConfig(mode = Mode.RANDOM_RANGE, minMillis = 100, maxMillis = 100)
        repeat(50) { assertEquals(100L, config.sample(seeded())) }
    }

    @Test
    fun `sampling is deterministic under a seed`() {
        val config = AcceptorLatencyConfig(mode = Mode.RANDOM_RANGE, minMillis = 50, maxMillis = 300)
        val left = Random(99)
        val right = Random(99)
        repeat(50) { assertEquals(config.sample(left), config.sample(right), "same seed, same sequence") }
    }

    // -------------------------------------------------------------------------------- normal

    @Test
    fun `normal mode centres on the mean and never goes negative`() {
        val config = AcceptorLatencyConfig(mode = Mode.NORMAL, meanMillis = 200, stdDevMillis = 50)
        val random = seeded()
        val samples = List(5000) { config.sample(random) }
        assertTrue(samples.all { it >= 0 }, "the lower tail is clamped at zero")
        val mean = samples.average()
        assertTrue(mean in 180.0..220.0, "the empirical mean ($mean) should sit near 200")
    }

    @Test
    fun `a normal distribution near zero with a wide spread still never returns negative`() {
        val config = AcceptorLatencyConfig(mode = Mode.NORMAL, meanMillis = 10, stdDevMillis = 500)
        val random = seeded()
        repeat(5000) { assertTrue(config.sample(random) >= 0L) }
    }

    // ---------------------------------------------------------------------------------- spike

    @Test
    fun `a certain spike replaces the base sample rather than adding to it`() {
        // Base would be a fixed 100ms; a spike that always fires must yield the spike range, not 100
        // and not 100+spike.
        val config =
            AcceptorLatencyConfig(
                mode = Mode.FIXED,
                fixedMillis = 100,
                spikeProbability = 1.0,
                spikeMinMillis = 2000,
                spikeMaxMillis = 5000,
            )
        val random = seeded()
        repeat(200) {
            val sample = config.sample(random)
            assertTrue(sample in 2000..5000, "a guaranteed spike draws only from the spike range, got $sample")
        }
    }

    @Test
    fun `a zero-probability spike never fires`() {
        val config =
            AcceptorLatencyConfig(
                mode = Mode.FIXED,
                fixedMillis = 100,
                spikeProbability = 0.0,
                spikeMinMillis = 2000,
                spikeMaxMillis = 5000,
            )
        val random = seeded()
        repeat(500) { assertEquals(100L, config.sample(random), "no spike may fire at probability 0") }
    }

    // ----------------------------------------------------------------------------- validation

    @Test
    fun `a usable config has no complaint`() {
        assertNull(AcceptorLatencyConfig().validationError())
        assertNull(AcceptorLatencyConfig(mode = Mode.RANDOM_RANGE, minMillis = 50, maxMillis = 300).validationError())
        assertNull(
            AcceptorLatencyConfig(
                mode = Mode.FIXED,
                fixedMillis = 100,
                spikeProbability = 0.05,
                spikeMinMillis = 2000,
                spikeMaxMillis = 5000,
            ).validationError(),
        )
    }

    @Test
    fun `a probability outside zero-to-one is refused`() {
        assertNotNull(AcceptorLatencyConfig(spikeProbability = 1.5).validationError())
        assertNotNull(AcceptorLatencyConfig(spikeProbability = -0.1).validationError())
    }

    @Test
    fun `a range whose max is below its min is refused`() {
        assertNotNull(
            AcceptorLatencyConfig(mode = Mode.RANDOM_RANGE, minMillis = 300, maxMillis = 50).validationError(),
        )
    }

    @Test
    fun `a spike range whose max is below its min is refused`() {
        assertNotNull(
            AcceptorLatencyConfig(spikeProbability = 0.05, spikeMinMillis = 5000, spikeMaxMillis = 2000)
                .validationError(),
        )
    }

    @Test
    fun `a spike probability with a zero spike delay is called out as doing nothing`() {
        assertNotNull(AcceptorLatencyConfig(spikeProbability = 0.05, spikeMaxMillis = 0).validationError())
    }
}
