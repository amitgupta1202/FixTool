package com.knapsack.fixtool.service.load

import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.load.LoadMatch
import com.knapsack.fixtool.model.load.LoadPhaseSpec
import com.knapsack.fixtool.model.load.LoadPlan
import com.knapsack.fixtool.model.load.LoadSet
import com.knapsack.fixtool.model.load.LoadShape
import com.knapsack.fixtool.model.load.LoadTemplate
import com.knapsack.fixtool.model.load.OnFailure
import com.knapsack.fixtool.model.load.StoreAndLogOverride
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The set on disk: what it round-trips as, what it refuses and in whose voice, and the one thing that
 * happens exactly once per run rather than once per phase.
 */
class LoadSetTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File.createTempFile("fixtool-load-sets", "").also { it.delete() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private val quoteRequest = LoadTemplate("RFQ Load QuoteRequest", listOf(35 to "R", 131 to "RFQ-\${run}-\${messageIndex}", 55 to "EUR/USD"))
    private val quoteResponse =
        LoadTemplate(
            "RFQ Load QuoteResponse",
            listOf(35 to "AJ", 693 to "AJ-\${run}-\${messageIndex}", 694 to "1", 11 to "RFQ-\${run}-\${messageIndex}"),
        )

    private fun config(resetOnLogon: Boolean = true) =
        FixConnectionConfig(
            senderCompID = "RFQLG{n}",
            targetCompID = "RFQ_SERVER",
            connectionType = FixConnectionConfig.ConnectionType.INITIATOR,
            sessionCount = 5,
            resetOnLogon = resetOnLogon,
        )

    /** A resolver over a fixed handful of names, which is what the CLI and the dialog each build for real. */
    private inner class Fake(
        private val templates: Map<String, LoadTemplate> =
            mapOf("RFQ Load QuoteRequest" to quoteRequest, "RFQ Load QuoteResponse" to quoteResponse),
        private val resetOnLogon: Boolean = true,
    ) : LoadSet.Resolver {
        override fun profile(key: String): LoadSet.Profile? =
            if (key == "RFQ Load Client") LoadSet.Profile("rfq-profile-RFQ_LOAD", "RFQ Load Client", config(resetOnLogon)) else null

        override fun template(key: String, profileId: String?): LoadTemplate? = templates[key]
    }

    private fun set(
        phases: List<LoadPhaseSpec> = twoPhases,
        seed: Map<String, String> = mapOf("run" to "\${uuid:4}"),
        onFailure: OnFailure = OnFailure.STOP,
    ) = LoadSet(
        name = "rfq-round-trip",
        label = "RFQ round trip",
        seed = seed,
        storeAndLog = StoreAndLogOverride.FOR_LOAD,
        onFailure = onFailure,
        phases = phases,
    )

    private val twoPhases =
        listOf(
            LoadPhaseSpec(
                label = "Ask for a quote",
                template = "RFQ Load QuoteRequest",
                profile = "RFQ Load Client",
                match = LoadMatch(131, 131, "S"),
                shape = LoadShape.Burst(4_000),
            ),
            LoadPhaseSpec(
                label = "Hit the first 2,000",
                template = "RFQ Load QuoteResponse",
                profile = "RFQ Load Client",
                match = LoadMatch(11, 11, "8"),
                shape = LoadShape.Burst(2_000),
                indexFrom = 2_001,
                settleMs = 30_000,
            ),
        )

    @Test
    fun `a set written is the set read back, and the file is named by its slug`() {
        val store = LoadSetStore(dir.absolutePath)
        val original = set()

        assertTrue(store.save(original))

        assertEquals(original, store.load("rfq-round-trip"))
        assertEquals(original, store.load("RFQ round trip"), "the slug of the name finds the file")
        assertEquals(listOf("rfq-round-trip"), store.list().map { it.name })
        assertTrue(File(dir, "rfq-round-trip.json").isFile)
        val json = Json.parseToJsonElement(File(dir, "rfq-round-trip.json").readText()).jsonObject
        assertEquals(LoadSet.SCHEMA, json["schema"]!!.toString().toInt())
        assertTrue(store.delete("rfq-round-trip"))
        assertNull(store.load("rfq-round-trip"))
    }

    @Test
    fun `an unreadable file is skipped rather than emptying the list`() {
        val store = LoadSetStore(dir.absolutePath)
        store.save(set())
        dir.mkdirs()
        File(dir, "broken.json").writeText("{ not json")

        assertEquals(listOf("rfq-round-trip"), store.list().map { it.name })
    }

    @Test
    fun `the seed's generators render once, and every phase carries the same rendered value`() {
        val planned = set().plan(Fake(), seedOverride = emptyMap(), id = "2026-09-08T10-12-04-rfq-round-trip")

        val run = assertNotNull(planned.seed["run"])
        assertEquals(4, run.length, "\${uuid:4} rendered to four hex characters: $run")
        assertTrue(run.all { it.isLetterOrDigit() }, run)
        assertEquals(listOf(run, run), planned.phases.map { it.seed["run"] }, "one seed, shared, so phase 2 addresses phase 1")
        assertEquals(listOf("2026-09-08T10-12-04-rfq-round-trip"), planned.phases.map { it.id }.distinct(), "one record for the set")
    }

    @Test
    fun `a literal seed renders to itself, and the command line's override wins`() {
        val planned =
            set(seed = mapOf("run" to "\${uuid:4}", "desk" to "LDN"))
                .plan(Fake(), seedOverride = mapOf("run" to "b4412"), id = "id")

        assertEquals(mapOf("run" to "b4412", "desk" to "LDN"), planned.seed)
    }

    @Test
    fun `a phase's indexFrom, settle and match reach its plan and nothing else does`() {
        val planned = set().plan(Fake(), seedOverride = mapOf("run" to "b7f2"), id = "id")

        assertEquals(listOf(1, 2_001), planned.phases.map { it.indexFrom })
        assertEquals(listOf(LoadPlan.DEFAULT_SETTLE_MS, 30_000L), planned.phases.map { it.settleMs })
        assertEquals(listOf(LoadMatch(131, 131, "S"), LoadMatch(11, 11, "8")), planned.phases.map { it.match })
        assertEquals(listOf(4_000L, 4_000L), planned.phases.map { it.indexTo }, "phase 2 addresses 2,001 to 4,000, which phase 1 minted")
        assertEquals(listOf(StoreAndLogOverride.FOR_LOAD, StoreAndLogOverride.FOR_LOAD), planned.phases.map { it.storeAndLog })
    }

    @Test
    fun `an absent match is inferred from the template, per phase`() {
        val phases = twoPhases.map { it.copy(match = null) }
        val planned = set(phases).plan(Fake(), seedOverride = mapOf("run" to "b7f2"), id = "id")

        assertEquals(listOf(131, 11), planned.phases.map { it.match.requestTag }, "the first correlation tag each template carries")
    }

    @Test
    fun `a set of no phases is refused before anything else is looked at`() {
        val problems = set(phases = emptyList()).problems(Fake(), LoadPlan.Surface.CLI)

        assertEquals(1, problems.size, problems.toString())
        assertEquals("A set needs a phase. Add one under Phases.", problems.single().sentence)
        assertNull(problems.single().phase)
    }

    @Test
    fun `a refusal about a phase is spoken in that phase's voice`() {
        val problems = set(seed = emptyMap()).problems(Fake(), LoadPlan.Surface.CLI)

        assertEquals(2, problems.size, problems.toString())
        assertEquals(listOf(1, 2), problems.map { it.phase })
        assertEquals(
            "Phase 1 · Ask for a quote: The template reads \${run} and nothing seeds it. " +
                "Pass --seed run=… on the command line, or capture it in an earlier phase of a set.",
            problems.first().describe("Ask for a quote"),
        )
    }

    @Test
    fun `a template or profile no name answers to is named with its phase`() {
        val noTemplate = set().problems(Fake(templates = mapOf("RFQ Load QuoteRequest" to quoteRequest)), LoadPlan.Surface.CLI)
        assertEquals(1, noTemplate.size, noTemplate.toString())
        assertEquals(2, noTemplate.single().phase)
        assertTrue(noTemplate.single().sentence.startsWith("no template 'RFQ Load QuoteResponse'"), noTemplate.toString())

        val phases = twoPhases.mapIndexed { i, p -> if (i == 1) p.copy(profile = "NOPE") else p }
        val noProfile = set(phases).problems(Fake(), LoadPlan.Surface.CLI)
        assertEquals(listOf(2), noProfile.map { it.phase })
        assertEquals("no saved connection profile named 'NOPE'.", noProfile.single().sentence)
    }

    @Test
    fun `two phases with one label are the set's own refusal, not a phase's`() {
        val phases = twoPhases.map { it.copy(label = "Ask for a quote") }
        val problems = set(phases).problems(Fake(), LoadPlan.Surface.CLI)

        assertEquals(1, problems.size, problems.toString())
        assertNull(problems.single().phase)
        assertTrue(problems.single().sentence.contains("Two phases are both called 'Ask for a quote'"), problems.toString())
    }

    /**
     * The whole reason the store lives on the set: six phases on one profile must not print the same
     * sentence six times.
     */
    @Test
    fun `the store is refused once for the set, however many phases share the profile`() {
        val problems = set().problems(Fake(resetOnLogon = false), LoadPlan.Surface.CLI)

        assertEquals(1, problems.size, problems.toString())
        assertNull(problems.single().phase)
        assertTrue(problems.single().sentence.contains("Reset on Logon"), problems.single().sentence)
    }

    @Test
    fun `a template carrying no correlation tag is refused with the tags that were looked for`() {
        val typeless = LoadTemplate("Heartbeat", listOf(35 to "0"))
        val problems =
            set(phases = listOf(twoPhases.first().copy(match = null, template = "flat")))
                .problems(Fake(templates = mapOf("flat" to typeless)), LoadPlan.Surface.CLI)

        assertEquals(listOf(1), problems.map { it.phase })
        assertTrue(problems.single().sentence.contains("carries none of the tags a reply is matched on"), problems.toString())
    }

    // -------------------------------------------------------------------------------------------------
    // Captured values
    // -------------------------------------------------------------------------------------------------

    private val hitByCapture =
        LoadTemplate("RFQ Load QuoteResponse", listOf(35 to "AJ", 11 to "H-\${run}-\${messageIndex}", 117 to "\${quoteId}"))

    private fun capturing(capture: Map<String, Int>, second: LoadTemplate = hitByCapture) =
        listOf(
            twoPhases[0].copy(capture = capture),
            twoPhases[1].copy(template = "hit"),
        ).let { phases -> phases to Fake(mapOf("RFQ Load QuoteRequest" to quoteRequest, "hit" to second)) }

    @Test
    fun `a phase captures, and a later phase reads it with nothing else asked of the seed`() {
        val (phases, resolve) = capturing(mapOf("quoteId" to 117, "offer" to 133))

        assertEquals(emptyList(), set(phases).problems(resolve, LoadPlan.Surface.CLI))

        val planned = set(phases).plan(resolve, seedOverride = mapOf("run" to "b7f2"), id = "id")
        assertEquals(mapOf("quoteId" to 117, "offer" to 133), planned.phases[0].capture)
        assertEquals(emptyMap(), planned.phases[1].capture, "a phase that reads a capture need not keep one")
    }

    @Test
    fun `a capture that shadows a seed, a lane name or the message index is refused`() {
        val seed = mapOf("run" to "b7f2", "desk" to "LDN")

        fun refusalFor(name: String): String {
            val (phases, resolve) = capturing(mapOf(name to 117))
            val problems = set(phases, seed = seed).problems(resolve, LoadPlan.Surface.CLI)
            return problems.first { it.phase == 1 }.sentence
        }

        assertEquals("the capture desk is also a seed. Rename one of them.", refusalFor("desk"))
        assertEquals("the capture sessionIndex is also a lane's own name. Rename it.", refusalFor("sessionIndex"))
        assertEquals("the capture messageIndex is the message index. Rename it.", refusalFor("messageIndex"))
        assertTrue(
            refusalFor("2quotes").startsWith("the capture name '2quotes' is not a name a template can read"),
            refusalFor("2quotes"),
        )
    }

    @Test
    fun `two phases capturing one name is refused, naming the phase that claimed it first`() {
        val phases =
            listOf(
                twoPhases[0].copy(capture = mapOf("quoteId" to 117)),
                twoPhases[1].copy(template = "hit", capture = mapOf("quoteId" to 117)),
            )
        val resolve = Fake(mapOf("RFQ Load QuoteRequest" to quoteRequest, "hit" to hitByCapture))

        val problems = set(phases).problems(resolve, LoadPlan.Surface.CLI)

        assertEquals(listOf(2), problems.map { it.phase }, problems.toString())
        assertEquals("the capture quoteId is also captured by phase 1. Rename one of them.", problems.single().sentence)
    }

    /**
     * A capture is readable only from an EARLIER phase, and **one** sentence says so.
     *
     * The seed sentence and the phase sentence were both printed for the same name, which reads as two
     * faults and sends the author to the Seed band for a name that is captured, only too late.
     */
    @Test
    fun `a template reading a capture from its own phase, or a later one, is refused in its own voice`() {
        val sameePhase =
            listOf(
                twoPhases[0].copy(template = "hit", capture = mapOf("quoteId" to 117)),
                twoPhases[1],
            )
        val resolve = Fake(mapOf("hit" to hitByCapture, "RFQ Load QuoteResponse" to quoteResponse))

        val problems = set(sameePhase).problems(resolve, LoadPlan.Surface.CLI)

        assertEquals(
            "Phase 1 · Ask for a quote: the template reads \${quoteId} and no earlier phase captures it. " +
                "Add a capture to a phase before it, or seed it.",
            problems.single().describe("Ask for a quote"),
            problems.toString(),
        )
    }

    @Test
    fun `the phase row says what it keeps`() {
        val spec = twoPhases[0].copy(capture = mapOf("quoteId" to 117, "offer" to 133))

        assertTrue(spec.describe().endsWith("keeps quoteId, offer"), spec.describe())
    }
}
