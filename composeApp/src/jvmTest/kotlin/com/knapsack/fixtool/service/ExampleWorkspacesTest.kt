package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.AcceptorLatencyConfig
import com.knapsack.fixtool.model.FixConnectionConfig
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The bundled examples, and what a copy of one must contain.
 *
 * These are the tests that let the demo builders be deleted. Everything they used to guarantee by
 * being code — the venue carrying the FX preset's rules, the ids a bundled scenario names, the two
 * clients pointing at the venue's port — is now a property of a file, and a file has no compiler.
 */
class ExampleWorkspacesTest {
    private val fxVenue = assertNotNull(ExampleWorkspaces.byId(ExampleWorkspaces.FX_VENUE), "fx-venue is not in the build")

    private fun openInTemp(): File {
        val location = Files.createTempDirectory("example-open").toFile()
        return ExampleWorkspaces
            .open(ExampleWorkspaces.FX_VENUE, "FX Venue", location, now = 1_700_000_000_000L)
            .getOrThrow()
    }

    @Test
    fun `the index lists the FX venue first and the RFQ venue after it`() {
        assertEquals(listOf(ExampleWorkspaces.FX_VENUE, ExampleWorkspaces.RFQ_VENUE), ExampleWorkspaces.all().map { it.id })
    }

    @Test
    fun `every file the manifest names is in the build`() {
        fxVenue.files.forEach { relative ->
            assertNotNull(
                ExampleWorkspaces::class.java.getResourceAsStream("/examples/${ExampleWorkspaces.FX_VENUE}/$relative"),
                "manifest names '$relative', which is not in the build",
            )
        }
    }

    /**
     * The pin the handover asked for, and the reason it has to exist.
     *
     * A response rule carries no name of its own, so nothing in the bundled file says "these came
     * from the FX venue preset". Only this test says it. The expected value is the preset put through
     * the same insert the preset menu uses, because that is what decides the ORDER the rules end up
     * in, and order is what a first-match acceptor answers on.
     */
    @Test
    fun `the venue carries the FX preset's rules, all of them, in the order the preset menu places them`() {
        val venue = profilesIn(openInTemp()).first { it.id == "demo-profile-venue" }
        val preset = assertNotNull(AcceptorPresets.byId(FxVenuePreset.ID))
        assertEquals(AcceptorPresets.insert(emptyList(), preset).rules, venue.config.acceptorResponseRules)
        assertEquals(preset.rules.size, venue.config.acceptorResponseRules.size)
        assertEquals(preset.rules.toSet(), venue.config.acceptorResponseRules.toSet())
    }

    @Test
    fun `the venue is an acceptor open to any client, on the demo port`() {
        val venue = profilesIn(openInTemp()).first { it.id == "demo-profile-venue" }
        assertEquals("DEMO_SERVER", venue.config.senderCompID)
        assertEquals("*", venue.config.targetCompID)
        assertEquals("19876", venue.config.socketAcceptPort)
    }

    @Test
    fun `two clients point at the venue, and reconnect fast enough to win the startup race`() {
        val clients = profilesIn(openInTemp()).filter { it.id.startsWith("demo-profile-DEMO_CLIENT") }
        assertEquals(listOf("Demo Client 1", "Demo Client 2"), clients.map { it.name })
        clients.forEach {
            assertEquals("DEMO_SERVER", it.config.targetCompID)
            assertEquals("19876", it.config.port)
            assertEquals("5", it.config.reconnectInterval)
        }
    }

    @Test
    fun `the ids a bundled scenario names are the ids the profiles carry`() {
        val workspace = openInTemp()
        val ids = profilesIn(workspace).map { it.id }.toSet()
        assertEquals(setOf("demo-profile-venue", "demo-profile-DEMO_CLIENT1", "demo-profile-DEMO_CLIENT2"), ids)
    }

    @Test
    fun `the templates come across, tagged to the clients that send them`() {
        val workspace = openInTemp()
        val messages = SavedMessagesService(customPath = File(workspace, "saved_messages.json").absolutePath)
        val forClientOne = messages.loadMessagesForProfile("demo-profile-DEMO_CLIENT1")
        assertTrue(forClientOne.any { it.id == "demo-fx-market-buy-eurusd" }, "the market buy template is missing")
        assertTrue(forClientOne.any { it.id == "demo-session-probe" }, "the session probe template is missing")
    }

    @Test
    fun `both scenarios come across and parse`() {
        val scenarios = ScenarioService(customDir = File(openInTemp(), "scenarios").absolutePath).list()
        assertEquals(
            setOf("demo-scenario-eurusd-lifecycle", "demo-scenario-session-probe"),
            scenarios.map { it.id }.toSet(),
        )
    }

    /**
     * The copy keeps the bundle's version, and the dialog no longer pretends to ask.
     *
     * A FIX version field used to be on the way in, and it was theatre: a loaded data dictionary
     * overrides a profile's beginString at connect time, and one is essentially always loaded, so
     * picking 4.2 produced a 4.4 session. Settings -> Protocol is where the wire version is decided.
     */
    @Test
    fun `the copy speaks what the bundle says, and nothing rewrites it on the way in`() {
        profilesIn(openInTemp()).forEach {
            assertEquals("FIX.4.4", it.config.beginString)
            assertEquals(null, it.config.applVerID)
        }
    }

    @Test
    fun `a copy is stamped with real times, and the bundle is not`() {
        profilesIn(openInTemp()).forEach { assertEquals(1_700_000_000_000L, it.createdAt) }
        val bundled = ExampleWorkspaces::class.java.getResourceAsStream("/examples/fx-venue/connection_profiles.json")!!
        assertTrue(bundled.use { it.readBytes().decodeToString() }.contains("\"createdAt\": 0"))
    }

    @Test
    fun `the bundle carries no password and no path off this machine`() {
        fxVenue.files.forEach { relative ->
            val body =
                ExampleWorkspaces::class.java
                    .getResourceAsStream("/examples/fx-venue/$relative")!!
                    .use { it.readBytes().decodeToString() }
            assertFalse(body.contains("\"password\""), "$relative carries a password field")
            assertFalse(body.contains("/Users/"), "$relative carries an absolute path from a developer's machine")
            assertFalse(body.contains("/home/"), "$relative carries an absolute path from a developer's machine")
            assertFalse(body.contains("fileStorePath"), "$relative pins the sequence store outside the workspace")
            assertFalse(body.contains("fileLogPath"), "$relative pins the session log outside the workspace")
        }
    }

    /**
     * Open is idempotent, because it is called Open.
     *
     * It used to mint `fx-venue-2` on the second call, which meant opening the example again silently
     * abandoned whatever the first copy had become — the edited rules, the captured scenarios. Opening
     * a workspace you already have must give you that workspace.
     */
    @Test
    fun `opening an example twice returns the copy you already have, edits and all`() {
        val location = Files.createTempDirectory("example-twice").toFile()
        val first = ExampleWorkspaces.open(ExampleWorkspaces.FX_VENUE, "FX Venue", location).getOrThrow()
        File(first, "scenarios/mine.json").writeText("{}")

        val second = ExampleWorkspaces.open(ExampleWorkspaces.FX_VENUE, "FX Venue", location).getOrThrow()

        assertEquals(first, second)
        assertTrue(File(second, "scenarios/mine.json").isFile, "the second open overwrote work in the first")
        assertEquals(listOf("fx-venue"), location.list()!!.toList(), "a second copy was made beside the first")
    }

    @Test
    fun `an empty folder where the example would go is filled rather than treated as taken`() {
        val location = Files.createTempDirectory("example-empty").toFile()
        File(location, "fx-venue").mkdirs()

        val opened = ExampleWorkspaces.open(ExampleWorkspaces.FX_VENUE, "FX Venue", location).getOrThrow()

        assertTrue(File(opened, "connection_profiles.json").isFile)
    }

    @Test
    fun `a name becomes the folder the example lands in`() {
        val example = assertNotNull(ExampleWorkspaces.byId(ExampleWorkspaces.FX_VENUE))
        assertEquals("fx-venue", ExampleWorkspaces.slug(example.defaultWorkspaceName))
    }

    @Test
    fun `an unknown example is a failure, not an empty workspace`() {
        val location = Files.createTempDirectory("example-unknown").toFile()
        val result = ExampleWorkspaces.open("no-such-example", "Whatever", location)
        assertTrue(result.isFailure)
        assertFalse(File(location, "whatever").exists())
    }

    // ---------------------------------------------------------------- a workspace of your own

    @Test
    fun `a new workspace is an empty folder that knows what must not be committed`() {
        val location = Files.createTempDirectory("new-workspace").toFile()
        val created = ExampleWorkspaces.createEmpty("My Venue", location).getOrThrow()

        assertEquals(File(location, "my-venue"), created)
        assertTrue(File(created, ".gitignore").isFile)
        assertEquals(listOf(".gitignore"), created.list()!!.toList(), "a new workspace starts with no content")
    }

    @Test
    fun `creating onto an existing workspace is refused rather than merging into it`() {
        val location = Files.createTempDirectory("new-workspace-clash").toFile()
        ExampleWorkspaces.open(ExampleWorkspaces.FX_VENUE, "Taken", location).getOrThrow()
        val second = ExampleWorkspaces.createEmpty("Taken", location)
        assertTrue(second.isFailure)
        assertTrue(second.exceptionOrNull()!!.message!!.contains("already holds a workspace"))
    }

    @Test
    fun `the example ships no secrets file, so a copy starts with no credentials`() {
        assertFalse(fxVenue.files.any { it.contains("secrets") }, "the manifest names a secrets file")
        assertFalse(File(openInTemp(), "secrets.json").exists(), "a fresh copy has a secrets file")
    }

    @Test
    fun `a new workspace arrives knowing what must not be committed`() {
        val gitignore = File(openInTemp(), ".gitignore")
        assertTrue(gitignore.isFile, "a workspace meant for a repo arrived without a .gitignore")
        val body = gitignore.readText()
        listOf("secrets.json", "store/", "log/", "runs/").forEach {
            assertTrue(body.contains(it), ".gitignore does not cover $it")
        }
    }

    @Test
    fun `a name becomes a folder someone can read`() {
        assertEquals("fx-venue", ExampleWorkspaces.slug("FX Venue"))
        assertEquals("my-venue-2", ExampleWorkspaces.slug("  My Venue (2)  "))
        assertEquals("workspace", ExampleWorkspaces.slug("   "))
        assertEquals("workspace", ExampleWorkspaces.slug("///"))
        assertEquals("a-b", ExampleWorkspaces.slug("a/b"))
    }

    // ---------------------------------------------------------------- the RFQ venue

    private val rfqVenue = assertNotNull(ExampleWorkspaces.byId(ExampleWorkspaces.RFQ_VENUE), "rfq-venue is not in the build")

    private fun openRfqInTemp(): File {
        val location = Files.createTempDirectory("rfq-example-open").toFile()
        return ExampleWorkspaces
            .open(ExampleWorkspaces.RFQ_VENUE, "RFQ Venue", location, now = 1_700_000_000_000L)
            .getOrThrow()
    }

    @Test
    fun `every file the RFQ manifest names is in the build`() {
        rfqVenue.files.forEach { relative ->
            assertNotNull(
                ExampleWorkspaces::class.java.getResourceAsStream("/examples/${ExampleWorkspaces.RFQ_VENUE}/$relative"),
                "manifest names '$relative', which is not in the build",
            )
        }
    }

    /** The same pin as the FX venue's: the bundle carries exactly the preset's rules in the menu's order. */
    @Test
    fun `the RFQ venue carries the RFQ preset's rules, all of them, in the order the preset menu places them`() {
        val venue = profilesIn(openRfqInTemp()).first { it.id == "rfq-profile-venue" }
        val preset = assertNotNull(AcceptorPresets.byId(RfqVenuePreset.ID))
        assertEquals(AcceptorPresets.insert(emptyList(), preset).rules, venue.config.acceptorResponseRules)
        assertEquals(preset.rules.size, venue.config.acceptorResponseRules.size)
        assertEquals(preset.rules.toSet(), venue.config.acceptorResponseRules.toSet())
    }

    /** Its own port, so both examples can be described without sharing a number. No injected latency. */
    @Test
    fun `the RFQ venue is an acceptor open to any client, on its own port, with no injected latency`() {
        val venue = profilesIn(openRfqInTemp()).first { it.id == "rfq-profile-venue" }
        assertEquals("RFQ_SERVER", venue.config.senderCompID)
        assertEquals("*", venue.config.targetCompID)
        assertEquals("19877", venue.config.socketAcceptPort)
        assertEquals(AcceptorLatencyConfig.Mode.NONE, venue.config.acceptorLatency.mode)
    }

    @Test
    fun `two RFQ clients point at the venue, and reconnect fast enough to win the startup race`() {
        val clients = profilesIn(openRfqInTemp()).filter { it.id.startsWith("rfq-profile-RFQ_CLIENT") }
        assertEquals(listOf("RFQ Client 1", "RFQ Client 2"), clients.map { it.name })
        clients.forEach {
            assertEquals("RFQ_SERVER", it.config.targetCompID)
            assertEquals("19877", it.config.port)
            assertEquals("5", it.config.reconnectInterval)
            assertTrue(it.config.resetOnLogon)
        }
    }

    /**
     * The example is load-ready out of the box: five lanes on a memory store with no message log, which is
     * #42's setting and the one `fixtool load` wants. Memory needs Reset on Logon, so that is pinned too.
     */
    @Test
    fun `the RFQ load client is five lanes on a memory store with no log, and Reset on Logon on`() {
        val load = profilesIn(openRfqInTemp()).first { it.id == "rfq-profile-RFQ_LOAD" }
        assertEquals("RFQ Load Client", load.name)
        assertEquals("RFQLG{n}", load.config.senderCompID)
        assertEquals(5, load.config.sessionCount)
        assertEquals(FixConnectionConfig.MessageStoreKind.MEMORY, load.config.messageStore)
        assertEquals(FixConnectionConfig.MessageLogKind.NONE, load.config.messageLog)
        assertTrue(load.config.resetOnLogon)
        assertEquals(null, load.config.storeProblem(), "the bundled load client would be refused at connect")
    }

    @Test
    fun `the RFQ templates come across, tagged to the clients and the load client that send them`() {
        val workspace = openRfqInTemp()
        val messages = SavedMessagesService(customPath = File(workspace, "saved_messages.json").absolutePath)
        val forClientOne = messages.loadMessagesForProfile("rfq-profile-RFQ_CLIENT1").map { it.id }
        assertTrue("rfq-quote-request-eurusd" in forClientOne, "the quote request template is missing")
        assertTrue("rfq-lift-last-quote" in forClientOne, "the lift template is missing")
        assertTrue("rfq-load-quote-request" !in forClientOne, "a load template is offered to a single-session client")
        val forLoad = messages.loadMessagesForProfile("rfq-profile-RFQ_LOAD").map { it.id }
        assertEquals(setOf("rfq-load-quote-request", "rfq-load-quote-response"), forLoad.toSet())
    }

    /** What the load run's two phases rely on: both templates vary from the same `run` seed. */
    @Test
    fun `the RFQ load templates address the same quotes from the same seed`() {
        val workspace = openRfqInTemp()
        val messages = SavedMessagesService(customPath = File(workspace, "saved_messages.json").absolutePath)
        val byId = messages.loadMessagesForProfile("rfq-profile-RFQ_LOAD").associateBy { it.id }
        fun value(id: String, tag: String) = byId.getValue(id).fields.first { it.tag == tag }.value
        assertEquals("RFQ-\${run}-\${messageIndex}", value("rfq-load-quote-request", "131"))
        assertEquals("Q-RFQ-\${run}-\${messageIndex}", value("rfq-load-quote-response", "117"))
        assertEquals("RFQ-\${run}-\${messageIndex}", value("rfq-load-quote-response", "11"))
        assertEquals("1.09010", value("rfq-load-quote-response", "44"), "the lift is at the EUR/USD offer the venue quotes")
    }

    @Test
    fun `both RFQ scenarios come across and parse`() {
        val scenarios = ScenarioService(customDir = File(openRfqInTemp(), "scenarios").absolutePath).list()
        assertEquals(
            setOf("rfq-scenario-book-a-trade", "rfq-scenario-pass-and-counter"),
            scenarios.map { it.id }.toSet(),
        )
    }

    @Test
    fun `the RFQ bundle carries no password, no path off this machine, and stamps no clock`() {
        rfqVenue.files.forEach { relative ->
            val body =
                ExampleWorkspaces::class.java
                    .getResourceAsStream("/examples/rfq-venue/$relative")!!
                    .use { it.readBytes().decodeToString() }
            assertFalse(body.contains("\"password\""), "$relative carries a password field")
            assertFalse(body.contains("/Users/"), "$relative carries an absolute path from a developer's machine")
            assertFalse(body.contains("/home/"), "$relative carries an absolute path from a developer's machine")
            assertFalse(body.contains("fileStorePath"), "$relative pins the sequence store outside the workspace")
            assertFalse(body.contains("fileLogPath"), "$relative pins the session log outside the workspace")
        }
        val bundled = ExampleWorkspaces::class.java.getResourceAsStream("/examples/rfq-venue/connection_profiles.json")!!
        assertTrue(bundled.use { it.readBytes().decodeToString() }.contains("\"createdAt\": 0"))
        profilesIn(openRfqInTemp()).forEach { assertEquals(1_700_000_000_000L, it.createdAt) }
    }

    @Test
    fun `the two examples land in different folders`() {
        assertEquals("fx-venue", ExampleWorkspaces.slug(fxVenue.defaultWorkspaceName))
        assertEquals("rfq-venue", ExampleWorkspaces.slug(rfqVenue.defaultWorkspaceName))
    }

    private fun profilesIn(workspace: File) =
        ConnectionProfileService(customPath = File(workspace, "connection_profiles.json").absolutePath).loadProfiles()
}
