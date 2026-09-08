package com.knapsack.fixtool.headless

import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.load.LoadMatch
import com.knapsack.fixtool.model.load.LoadPhaseSpec
import com.knapsack.fixtool.model.load.LoadRecord
import com.knapsack.fixtool.model.load.LoadReport
import com.knapsack.fixtool.model.load.LoadSet
import com.knapsack.fixtool.model.load.LoadShape
import com.knapsack.fixtool.model.load.LoadStatus
import com.knapsack.fixtool.model.load.LoadTemplate
import com.knapsack.fixtool.model.load.OnFailure
import com.knapsack.fixtool.model.load.StoreAndLogOverride
import com.knapsack.fixtool.service.load.LoadFixtures
import com.knapsack.fixtool.service.load.LoadReportCodec
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
        assertNull(HeadlessLoad.Options.parse(listOf("x", "--profile", "p", "--count", "1", "--seed", "novalue")))
        assertNull(HeadlessLoad.Options.parse(listOf("x", "--profile", "p", "--count", "1", "--bogus")))
        assertNull(HeadlessLoad.Options.parse(listOf("x", "y", "--profile", "p", "--count", "1")), "two templates is one too many")
        assertNull(HeadlessLoad.Options.parse(listOf("x", "--profile", "p", "--count", "1", "--match", "11=eleven")))
    }

    @Test
    fun `--set names a saved set when its value carries no equals, and seeds when it does`() {
        val asSet =
            assertNotNull(HeadlessLoad.Options.parse(listOf("--set", "rfq-round-trip", "--on-failure", "continue")))
        assertEquals("rfq-round-trip", asSet.set)
        assertEquals(OnFailure.CONTINUE, asSet.onFailure)
        assertEquals(emptyMap(), asSet.seed)
        assertTrue(asSet.template.isBlank(), "a set is not a template")

        val asSeed =
            assertNotNull(HeadlessLoad.Options.parse(listOf("x", "--profile", "p", "--count", "1", "--set", "run=b7f2")))
        assertNull(asSeed.set)
        assertEquals(mapOf("run" to "b7f2"), asSeed.seed)
        assertTrue(asSeed.seededWithSet, "and it says so on stderr")

        val seeded = assertNotNull(HeadlessLoad.Options.parse(listOf("--set", "nightly", "--seed", "run=cli1")))
        assertEquals("nightly", seeded.set)
        assertEquals(mapOf("run" to "cli1"), seeded.seed)

        assertNull(HeadlessLoad.Options.parse(listOf("--set", "nightly", "--on-failure", "maybe")))
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

    private fun spec(
        label: String,
        count: Int,
        indexFrom: Int = 1,
        settleMs: Long = 60_000,
        match: LoadMatch = LoadMatch(131, 131, "S"),
    ) =
        LoadPhaseSpec(
            label = label,
            template = "RFQ Load QuoteRequest",
            profile = "RFQ Load Client",
            match = match,
            shape = LoadShape.Burst(count),
            indexFrom = indexFrom,
            settleMs = settleMs,
        )

    private val roundTrip =
        LoadSet(
            name = "rfq-round-trip",
            label = "RFQ round trip",
            seed = mapOf("run" to "b7f2", "desk" to "LDN"),
            storeAndLog = StoreAndLogOverride.FOR_LOAD,
            onFailure = OnFailure.STOP,
            phases =
                listOf(
                    spec("Ask for a quote", 4_000),
                    spec("Hit the first 2,000", 2_000, match = LoadMatch(11, 11, "8")),
                    spec(
                        "Pass the other 2,000",
                        2_000,
                        indexFrom = 2_001,
                        settleMs = 30_000,
                        match = LoadMatch(117, 117, "AI"),
                    ),
                ),
        )

    /** The three header lines, a block per phase under its heading, and the set's own line last. */
    @Test
    fun `a set's summary names its phases, the skipped ones and the phase that failed`() {
        val planned =
            roundTrip.plan(
                object : LoadSet.Resolver {
                    override fun profile(key: String) = LoadSet.Profile("p", "RFQ Load Client", FixConnectionConfig())

                    override fun template(key: String, profileId: String?) =
                        LoadTemplate("RFQ Load QuoteRequest", listOf(35 to "R", 131 to "x"))
                },
                seedOverride = emptyMap(),
                id = "set-1",
            )
        val one =
            LoadFixtures
                .burstReport(unmatched = 0)
                .copy(label = "Ask for a quote", profileName = "RFQ Load Client", lanes = 5, startedAt = 0, finishedAt = 2_700)
        val two =
            LoadFixtures
                .burstReport(unmatched = 4)
                .copy(label = "Hit the first 2,000", profileName = "RFQ Load Client", lanes = 5)
        val three =
            LoadReport
                .stub(
                    planned.phases[2],
                    LoadStatus.SKIPPED,
                    lanes = 5,
                    template = LoadReport.TemplateInfo("RFQ Load Pass", "AJ", listOf(117), listOf(35), emptyList()),
                    startedAt = 0,
                ).copy(label = "Pass the other 2,000", note = "phase 2 did not pass and the set stops on failure")
        val record =
            LoadRecord(
                id = "set-1",
                label = "RFQ round trip",
                startedAt = 0,
                finishedAt = 63_100,
                phases = listOf(one, two, three),
                set = LoadRecord.SetInfo("rfq-round-trip", OnFailure.STOP),
                seed = mapOf("run" to "b7f2", "desk" to "LDN"),
            )

        val text = HeadlessLoad.setSummary(record, planned, File("/tmp/loads/set-1"), null)

        assertTrue(text.contains("set          RFQ round trip · 3 phases · RFQ Load Client · 5 lanes · memory store, no log"), text)
        assertTrue(text.contains("seed         run=b7f2 · desk=LDN"), text)
        assertTrue(text.contains("policy       stop when a phase does not pass"), text)
        assertTrue(text.contains("1 · Ask for a quote       NOS EUR/USD 1M ×4,000 · 35=D → 8 · 11 · settle 1m"), text)
        assertTrue(text.contains("COMPLETE     4,000 of 4,000 answered · exit 0"), text)
        assertTrue(text.contains("2 · Hit the first 2,000   NOS EUR/USD 1M ×4,000"), text)
        assertTrue(text.contains("UNMATCHED    4 of 4,000 unanswered within 1m · exit 1"), text)
        assertTrue(text.contains("3 · Pass the other 2,000  RFQ Load Pass ×2,000 from 2,001 · 35=AJ → AI · 117 · settle 30s"), text)
        assertTrue(text.contains("SKIPPED      phase 2 did not pass and the set stops on failure"), text)
        assertTrue(text.contains("FAILED       phase 2 · 1 passed, 1 failed, 1 skipped · 63.1s · exit 1"), text)
        assertEquals(1, text.split("records: ").size - 1, "the record path is printed once, not per phase")
        assertTrue(text.contains("records: /tmp/loads/set-1"), text)
    }

    /**
     * **A set of one is a set**, and its header line says so in English: "1 phase".
     *
     * The soak set in the editor's own sketch has one member, and a saved set of one is the natural way to
     * park a burst that gets re-run.
     */
    @Test
    fun `a one-phase set's summary says one phase`() {
        val soak =
            roundTrip.copy(name = "quote-stream-soak", label = "Quote stream soak", phases = listOf(spec("Stream quotes", 400)))
        val planned =
            soak.plan(
                object : LoadSet.Resolver {
                    override fun profile(key: String) = LoadSet.Profile("p", "RFQ Load Client", FixConnectionConfig())

                    override fun template(key: String, profileId: String?) =
                        LoadTemplate("RFQ Load QuoteRequest", listOf(35 to "R", 131 to "x"))
                },
                seedOverride = emptyMap(),
                id = "soak-1",
            )
        val only =
            LoadFixtures
                .burstReport(unmatched = 0)
                .copy(label = "Stream quotes", profileName = "RFQ Load Client", lanes = 1, startedAt = 0, finishedAt = 2_700)
        val record =
            LoadRecord(
                id = "soak-1",
                label = "Quote stream soak",
                startedAt = 0,
                finishedAt = 2_700,
                phases = listOf(only),
                set = LoadRecord.SetInfo("quote-stream-soak", OnFailure.STOP),
                seed = mapOf("run" to "b7f2"),
            )

        val text = HeadlessLoad.setSummary(record, planned, File("/tmp/loads/soak-1"), null)

        assertTrue(text.contains("set          Quote stream soak · 1 phase · RFQ Load Client · 1 lane"), text)
        assertTrue(!text.contains("1 phases"), "a set of one does not say '1 phases': $text")
    }

    /** One <testsuites> with a <testsuite> per phase, and three skipped cases for a phase that never ran. */
    @Test
    fun `a set's JUnit file is one testsuites, and a one-phase record keeps its bare testsuite`() {
        val one = LoadFixtures.burstReport(unmatched = 0).copy(label = "Ask for a quote")
        val two = LoadFixtures.burstReport(unmatched = 4).copy(label = "Hit the first 2,000")
        val three =
            one.copy(
                label = "Pass the other 2,000",
                status = LoadStatus.SKIPPED,
                note = "phase 2 did not pass and the set stops on failure",
            )
        val record =
            LoadRecord(
                id = "set-1",
                label = "RFQ round trip",
                startedAt = 0,
                finishedAt = 63_100,
                phases = listOf(one, two, three),
                set = LoadRecord.SetInfo("rfq-round-trip", OnFailure.STOP),
            )

        val xml = LoadReportCodec.toJUnitXml(record)

        assertTrue(
            xml.contains("""<testsuites name="load set: RFQ round trip" tests="9" failures="1" skipped="5" time="63.100">"""),
            xml,
        )
        assertTrue(xml.contains("""<testsuite name="load: 1 · Ask for a quote""""), xml)
        assertTrue(xml.contains("""classname="load.rfq-round-trip.2""""), xml)
        assertTrue(xml.contains("""<skipped message="phase 2 did not pass and the set stops on failure"/>"""), xml)
        assertTrue(xml.contains("</testsuites>"), xml)

        val single = LoadReportCodec.toJUnitXml(LoadRecord.of(one))
        assertTrue(
            !single.contains("<testsuites"),
            "a one-phase record keeps the shape a 1.17 pipeline reads: $single",
        )
        assertEquals(LoadReportCodec.toJUnitXml(one), single)
    }
}
