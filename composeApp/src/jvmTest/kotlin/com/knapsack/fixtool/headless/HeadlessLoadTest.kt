package com.knapsack.fixtool.headless

import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.load.LoadMatch
import com.knapsack.fixtool.model.load.LoadShape
import com.knapsack.fixtool.model.load.LoadStatus
import com.knapsack.fixtool.service.load.LoadFixtures
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The command's own small parts: what it reads off the arguments and what it prints at the end. */
class HeadlessLoadTest {
    @Test
    fun `a burst and a rate parse to their shapes, with every option`() {
        val burst =
            assertNotNull(
                HeadlessLoad.Options.parse(
                    listOf(
                        "NOS EUR/USD 1M", "--profile", "LOADGEN", "--count", "4000", "--settle", "60s",
                        "--listen", "DROPCOPY", "--match", "11=11", "--reply-type", "8", "--set", "run=b7f2", "--set", "desk=fx",
                        "--store", "memory", "--log", "none", "--strict-rate", "--json", "r.json", "--junit", "r.xml", "--home", "/tmp/w",
                    ),
                ),
            )
        assertEquals(LoadShape.Burst(4000), burst.shape)
        assertEquals("LOADGEN", burst.profile)
        assertEquals(60_000L, burst.settleMs)
        assertEquals(listOf("DROPCOPY"), burst.listen)
        assertEquals(LoadMatch(11, 11, "8"), burst.match)
        assertEquals(mapOf("run" to "b7f2", "desk" to "fx"), burst.seed)
        assertEquals(FixConnectionConfig.MessageStoreKind.MEMORY, burst.store)
        assertEquals(FixConnectionConfig.MessageLogKind.NONE, burst.log)
        assertTrue(burst.strictRate)
        assertEquals("r.json", burst.jsonFile)
        assertEquals("r.xml", burst.junitFile)
        assertEquals("/tmp/w", burst.home)

        val rate = assertNotNull(HeadlessLoad.Options.parse(listOf("nos.fix", "--profile", "LOADGEN", "--rate", "500/s", "--for", "10m")))
        assertEquals(LoadShape.Rate(500, 600_000), rate.shape)
        assertEquals(LoadShape.Rate(250, 90_000), assertNotNull(HeadlessLoad.Options.parse(listOf("x", "--profile", "p", "--rate", "250", "--for", "90s"))).shape)
    }

    @Test
    fun `neither shape, both shapes, a bad flag or a bad value are refused`() {
        assertNull(assertNotNull(HeadlessLoad.Options.parse(listOf("x", "--profile", "p"))).shape)
        assertNull(assertNotNull(HeadlessLoad.Options.parse(listOf("x", "--profile", "p", "--count", "10", "--rate", "5", "--for", "1s"))).shape)
        assertNull(HeadlessLoad.Options.parse(listOf("x", "--profile", "p", "--count", "0")))
        assertNull(HeadlessLoad.Options.parse(listOf("x", "--profile", "p", "--count", "ten")))
        assertNull(HeadlessLoad.Options.parse(listOf("x", "--profile", "p", "--rate", "-5/s", "--for", "1s")))
        assertNull(HeadlessLoad.Options.parse(listOf("x", "--profile", "p", "--count", "1", "--store", "disk")))
        assertNull(HeadlessLoad.Options.parse(listOf("x", "--profile", "p", "--count", "1", "--set", "novalue")))
        assertNull(HeadlessLoad.Options.parse(listOf("x", "--profile", "p", "--count", "1", "--bogus")))
        assertNull(HeadlessLoad.Options.parse(listOf("x", "y", "--profile", "p", "--count", "1")), "two templates is one too many")
        assertNull(HeadlessLoad.Options.parse(listOf("x", "--profile", "p", "--count", "1", "--match", "11=eleven")))
    }

    @Test
    fun `durations read the way people write them`() {
        assertEquals(500L, HeadlessRun.parseDuration("500ms"))
        assertEquals(2_000L, HeadlessRun.parseDuration("2s"))
        assertEquals(600_000L, HeadlessRun.parseDuration("10m"))
        assertEquals(3_600_000L, HeadlessRun.parseDuration("1h"))
        assertEquals(90_000L, HeadlessRun.parseDuration("1.5m"))
        assertEquals(250L, HeadlessRun.parseDuration("250"))
        assertNull(HeadlessRun.parseDuration("soon"))
    }

    @Test
    fun `the summary block names the three issue numbers, the unmatched ids and the verdict`() {
        val text = HeadlessLoad.summary(LoadFixtures.burstReport(unmatched = 4), File("/tmp/loads/x"))

        assertTrue(text.contains("issued           4,000   requested 4,000 · handed to engine 4,000 · left socket 4,000"), text)
        assertTrue(text.contains("matched          3,996"), text)
        assertTrue(text.contains("unmatched            4   ORD-b7f2-1187 (lane 37) · ORD-b7f2-2410 (lane 10)"), text)
        assertTrue(text.contains("round trip   min 912µs · p50 14ms · p95 212ms · p99 640ms · max 1.88s · mean 41ms  (3,996)"), text)
        assertTrue(text.contains("timing       elapsed 2.7s · drain 1.9s"), text)
        assertTrue(text.contains("tool         clean · 0 discarded on 51 sessions · 0 never left the socket"), text)
        assertTrue(text.contains("UNMATCHED    4 of 4,000 unanswered within 1m · exit 1"), text)
        assertTrue(text.contains("records: /tmp/loads/x"), text)

        val shortfall = HeadlessLoad.summary(LoadFixtures.burstReport(unmatched = 0, rate = LoadFixtures.shortfall), File("r"))
        assertTrue(shortfall.contains("rate         500/s requested · held 9m 41s · behind from second 341 for 19s (min 412/s, 1,672 behind) · max lag 2.3s"), shortfall)
        assertTrue(shortfall.contains("COMPLETE     4,000 of 4,000 answered · RATE SHORTFALL (reported, exit unaffected; --strict-rate would exit 1) · exit 0"), shortfall)

        val stopped = HeadlessLoad.summary(LoadFixtures.burstReport(unmatched = 0, status = LoadStatus.STOPPED), File("r"))
        assertTrue(stopped.contains("STOPPED      after 4,000 of 4,000 issued · exit 1"), stopped)
    }
}
