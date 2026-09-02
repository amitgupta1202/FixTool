package com.knapsack.fixtool.service.demo

import com.knapsack.fixtool.model.AcceptorLatencyConfig
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.service.AcceptorResponder
import com.knapsack.fixtool.service.ScenarioCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **What pressing Start actually installs.**
 *
 * The demo used to be a server, and the only way to find out whether it worked was to press the button
 * and look. It is a workspace now — profiles, templates, a scenario — which means the shape of it can
 * simply be asked for, and the questions worth asking are the ones a user would notice being wrong:
 * a venue that refuses its own clients, a template the cleanup list has never heard of, a scenario
 * naming a session that will not exist.
 */
class DemoWorkspaceTest {
    private val profiles = DemoServerManager.demoProfiles(FixVersion.FIX_4_4, port = 19876)

    private val venue = profiles.first()

    // ---------------------------------------------------------------- the profiles

    /** Venue first: the caller connects them in this order, and an acceptor must bind before a client dials. */
    @Test
    fun `the workspace is one venue and two clients, venue first`() {
        assertEquals(3, profiles.size)
        assertEquals(DemoServerManager.VENUE_NAME, venue.name)
        assertEquals(FixConnectionConfig.ConnectionType.ACCEPTOR, venue.config.connectionType)
        assertEquals(
            listOf("Demo Client 1", "Demo Client 2"),
            profiles.drop(1).map { it.name },
        )
        assertTrue(
            profiles.drop(1).all { it.config.connectionType == FixConnectionConfig.ConnectionType.INITIATOR },
        )
    }

    /**
     * The wildcard is what makes one venue answer many clients — including a client with no profile at
     * all, which is how the `/verify` recipe connects and why dropping from four demo clients to two
     * costs nothing.
     */
    @Test
    fun `the venue accepts any client`() {
        assertTrue(venue.config.acceptsAnyClient(), "the demo venue must be open to any CompID")
        assertEquals(DemoServerManager.VENUE_COMP_ID, venue.config.senderCompID, "SenderCompID is never wildcarded")
    }

    /** Every client addresses the venue, on the venue's port. */
    @Test
    fun `the clients are pointed at the venue`() {
        profiles.drop(1).forEach { client ->
            assertEquals(DemoServerManager.VENUE_COMP_ID, client.config.targetCompID)
            assertEquals(venue.config.socketAcceptPort, client.config.port)
        }
    }

    /**
     * Five seconds, not the default thirty. The venue and its clients come up together, and a client
     * losing that race by milliseconds would otherwise sit dark for half a minute.
     */
    @Test
    fun `the demo clients retry quickly, because they race the venue's bind`() {
        profiles.drop(1).forEach { client ->
            assertEquals("5", client.config.reconnectInterval)
        }
    }

    /** The venue carries the FX bundle itself, placed — not a hand-ordered copy of it. */
    @Test
    fun `the venue carries the FX bundle, in a reachable order`() {
        val rules = venue.config.acceptorResponseRules

        assertEquals(21, rules.size)
        rules.indices.forEach { index ->
            assertTrue(
                AcceptorResponder.shadowingRule(rules, index) == null,
                "rule ${index + 1} of the demo venue can never fire",
            )
        }
        assertTrue(rules.any { it.whenMsgType == "R" }, "a venue that cannot quote is not an FX venue")
    }

    /** #36 on camera, and replies that do not land suspiciously instantly. */
    @Test
    fun `the venue has a plausible latency`() {
        val latency = venue.config.acceptorLatency

        assertEquals(AcceptorLatencyConfig.Mode.RANDOM_RANGE, latency.mode)
        assertTrue(latency.isActive())
        assertEquals(40L, latency.minMillis)
        assertEquals(80L, latency.maxMillis)
        assertEquals(null, latency.validationError())
    }

    @Test
    fun `the FIX version reaches the profiles`() {
        DemoServerManager.demoProfiles(FixVersion.FIX_4_2, port = 19876).forEach {
            assertEquals("FIX.4.2", it.config.beginString)
        }
    }

    /** The port is overridable so the whole workspace can be driven off 19876 by a test. */
    @Test
    fun `the port reaches both sides`() {
        val onAnother = DemoServerManager.demoProfiles(FixVersion.FIX_4_4, port = 19999)

        assertEquals("19999", onAnother.first().config.socketAcceptPort)
        assertTrue(onAnother.drop(1).all { it.config.port == "19999" })
    }

    /** Everything demo-owned is prefixed, which is the whole of how Stop knows what is safe to delete. */
    @Test
    fun `every demo profile is recognisable as one`() {
        profiles.forEach { assertTrue(DemoServerManager.isDemoProfile(it.id), "${it.id} is not demo-prefixed") }
        assertEquals(profiles.map { it.id }.toSet(), DemoServerManager.getDemoProfileIds().toSet())
    }

    // ---------------------------------------------------------------- the templates

    /**
     * **The cleanup list covers what is installed** — a mismatch here leaks a template into the user's
     * saved messages that nothing will ever remove, which is precisely the promise the demo makes.
     *
     * The old list was hand-maintained beside the builder and had already drifted once.
     */
    @Test
    fun `every template installed is a template the cleanup removes`() {
        val installed = DemoTemplatesProvider.createDemoTemplates(setOf("demo-profile-DEMO_CLIENT1")).map { it.id }

        assertEquals(installed.toSet(), DemoTemplatesProvider.getDemoTemplateIds().toSet())
        installed.forEach { assertTrue(DemoTemplatesProvider.isDemoTemplate(it), "$it is not demo-prefixed") }
        assertEquals(installed.size, installed.distinct().size, "two templates cannot share an id")
    }

    /**
     * The quote request is the message a **real** client sends: the symbol inside NoRelatedSym. It was
     * flat before, which is not a conformant FIX 4.4 QuoteRequest and only worked because the old
     * server read the flat tag first.
     */
    @Test
    fun `the quote request templates carry the symbol inside NoRelatedSym`() {
        val quotes =
            DemoTemplatesProvider.createDemoTemplates(emptySet()).filter { it.name.startsWith("FX Quote Request") }

        assertEquals(3, quotes.size, "one per priced pair")
        quotes.forEach { template ->
            val tags = template.fields.map { it.tag }
            assertTrue(tags.contains("146"), "${template.name} has no NoRelatedSym")
            assertTrue(
                tags.indexOf("146") < tags.indexOf("55"),
                "${template.name} puts the symbol before the group that should contain it",
            )
        }
    }

    /** A template the venue answers with silence teaches nothing; the replace rules read OrderQty. */
    @Test
    fun `the replace template carries the OrderQty the venue's rules require`() {
        val replace = DemoTemplatesProvider.createDemoTemplates(emptySet()).single { it.name.contains("Replace") }

        assertTrue(replace.fields.any { it.tag == "38" }, "a replace with no 38 matches no rule")
        assertTrue(replace.fields.any { it.tag == "41" }, "a replace has to name the order it supersedes")
    }

    // ---------------------------------------------------------------- the scenario

    /** It has to survive the round trip through the store, or Start installs something unloadable. */
    @Test
    fun `the bundled scenario round-trips through the codec`() {
        val original = DemoScenarioProvider.scenarios().single()

        val onDisk = Json.parseToJsonElement(ScenarioCodec.toJson(original).toString()).jsonObject
        val back = ScenarioCodec.fromJson(onDisk)

        assertEquals(original.name, back.name)
        assertEquals(original.steps.size, back.steps.size)
        assertEquals(original.setup.size, back.setup.size)
    }

    /**
     * **Every session the scenario names is one the workspace creates**, or preflight refuses the run
     * and a fresh install's one bundled scenario is red on first press.
     */
    @Test
    fun `the bundled scenario names only sessions the workspace brings up`() {
        val scenario = DemoScenarioProvider.scenarios().single()
        val available =
            profiles.map { it.name }.toSet() +
                DemoServerManager.DEMO_CLIENTS.map { DemoServerManager.venuePaneFor(it) }

        (scenario.setup + scenario.steps + scenario.teardown).forEach { step ->
            val session = step.session
            assertNotNull(session, "a bundled scenario must name its sessions rather than rely on the active one")
            assertTrue(session in available, "step names '$session', which the demo never creates")
        }
    }

    /**
     * The clear has to target the venue's per-client pane. Only a session FixTool hosts as a venue owns
     * a book, and preflight refuses the step by name anywhere else — so aiming it at the client profile
     * would fail every run rather than reset anything.
     */
    @Test
    fun `the order-book reset targets the venue side`() {
        val clear =
            DemoScenarioProvider
                .scenarios()
                .single()
                .setup
                .filterIsInstance<ScenarioStep.ClearOrderBook>()
                .single()

        assertEquals(DemoServerManager.venuePaneFor("DEMO_CLIENT1"), clear.session)
    }

    /**
     * **No expectation may assert a jittered price.** The venue's quotes and market fills differ every
     * time by design, so an assertion on one is red on the second run. The bundled flow uses a limit
     * order for exactly this reason — it fills at its own price — and this pins that reasoning.
     */
    @Test
    fun `the bundled scenario asserts only deterministic values`() {
        val scenario = DemoScenarioProvider.scenarios().single()
        val sent = scenario.steps.filterIsInstance<ScenarioStep.Send>()

        assertTrue(
            sent.none { it.raw.contains("40=1") },
            "a market order's fill price jitters, so no bundled assertion can be written against it",
        )
        assertTrue(sent.any { it.raw.contains("40=2") && it.raw.contains("44=") }, "the flow is a limit order")
    }

    @Test
    fun `every demo scenario is recognisable as one`() {
        DemoScenarioProvider.scenarios().forEach {
            assertTrue(DemoScenarioProvider.isDemoScenario(it.id), "${it.id} is not demo-prefixed")
        }
        assertEquals(
            DemoScenarioProvider.scenarios().map { it.id }.toSet(),
            DemoScenarioProvider.scenarioIds().toSet(),
        )
    }
}
