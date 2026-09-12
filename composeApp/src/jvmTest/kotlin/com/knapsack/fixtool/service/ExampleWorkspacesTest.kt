package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.AcceptorLatencyConfig
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.load.LoadPlan
import com.knapsack.fixtool.model.load.LoadSet
import com.knapsack.fixtool.model.load.LoadTemplate
import com.knapsack.fixtool.model.load.OnFailure
import com.knapsack.fixtool.service.load.LoadSetStore
import com.knapsack.fixtool.service.load.LoadTemplates
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
        assertEquals(
            setOf(
                "demo-profile-venue",
                "demo-profile-DEMO_CLIENT1",
                "demo-profile-DEMO_CLIENT2",
                "demo-profile-FX_LOAD",
            ),
            ids,
        )
    }

    @Test
    fun `the templates come across, tagged to the clients and the load client that send them`() {
        val workspace = openInTemp()
        val messages = SavedMessagesService(customPath = File(workspace, "saved_messages.json").absolutePath)
        val forClientOne = messages.loadMessagesForProfile("demo-profile-DEMO_CLIENT1").map { it.id }
        assertTrue("demo-fx-market-buy-eurusd" in forClientOne, "the market buy template is missing")
        assertTrue("demo-session-probe" in forClientOne, "the session probe template is missing")
        assertTrue("demo-fx-load-new-order" !in forClientOne, "a load template is offered to a single-session client")
        val forLoad = messages.loadMessagesForProfile("demo-profile-FX_LOAD").map { it.id }
        assertEquals(
            setOf("demo-fx-load-new-order", "demo-fx-load-cancel-unknown", "demo-fx-load-order-status"),
            forLoad.toSet(),
        )
    }

    /**
     * The same five lanes on the same store as the RFQ example's, and for the same reasons: it is #42's
     * setting and the one `fixtool load` wants, and a memory store needs Reset on Logon.
     */
    @Test
    fun `the FX load client is five lanes on a memory store with no log, and Reset on Logon on`() {
        val load = profilesIn(openInTemp()).first { it.id == "demo-profile-FX_LOAD" }
        assertEquals("FX Load Client", load.name)
        assertEquals("FXLG{n}", load.config.senderCompID)
        assertEquals("DEMO_SERVER", load.config.targetCompID)
        assertEquals("19876", load.config.port)
        assertEquals(5, load.config.sessionCount)
        assertEquals(FixConnectionConfig.MessageStoreKind.MEMORY, load.config.messageStore)
        assertEquals(FixConnectionConfig.MessageLogKind.NONE, load.config.messageLog)
        assertTrue(load.config.resetOnLogon)
        assertEquals(null, load.config.storeProblem(), "the bundled load client would be refused at connect")
    }

    /**
     * **The status phase asks after the orders the order phase placed, by the id the venue minted.**
     *
     * The ClOrdID is ours, so both phases build it from the same seed and the same index and the third
     * can name an order the first placed. The OrderID is the venue's own `${req.uuid}`, which nothing on
     * the client side can derive, so it is captured off the ExecutionReport that opened the order — the
     * same bargain the RFQ example strikes with the QuoteID, and the reason phase 1 carries a `capture`
     * block at all.
     *
     * The cancel phase names ids **nothing ever sent**, which is what makes its refusal the one the set
     * expects: `FXC-` for its own ClOrdID and `FXX-` for the order it claims to be cancelling, neither of
     * which the order phase's `FXO-` ids can collide with on any lane.
     */
    @Test
    fun `the FX load templates ask after the orders they placed, and cancel ones nothing ever sent`() {
        val workspace = openInTemp()
        val messages = SavedMessagesService(customPath = File(workspace, "saved_messages.json").absolutePath)
        val byId = messages.loadMessagesForProfile("demo-profile-FX_LOAD").associateBy { it.id }
        fun value(id: String, tag: String) = byId.getValue(id).fields.first { it.tag == tag }.value
        assertEquals("FXO-\${run}-\${messageIndex}", value("demo-fx-load-new-order", "11"))
        assertEquals("1", value("demo-fx-load-new-order", "40"), "a market order, so the venue fills it and is done")

        assertEquals("H", value("demo-fx-load-order-status", "35"))
        assertEquals(
            value("demo-fx-load-new-order", "11"),
            value("demo-fx-load-order-status", "11"),
            "the status request names the order by the ClOrdID the order phase sent, index for index",
        )
        assertEquals("\${orderId}", value("demo-fx-load-order-status", "37"), "the id is the venue's, not ours")

        assertEquals("F", value("demo-fx-load-cancel-unknown", "35"))
        assertEquals("FXC-\${run}-\${messageIndex}", value("demo-fx-load-cancel-unknown", "11"))
        assertEquals(
            "FXX-\${run}-\${messageIndex}",
            value("demo-fx-load-cancel-unknown", "41"),
            "the order being cancelled is one no phase ever placed, which is the whole point of the phase",
        )
    }

    /**
     * **The shipped set, pinned whole**, as the RFQ example's is and for the same reason: a phase whose
     * template reads a name no earlier phase captures is refused at plan time, and this is where that
     * refusal shows up before a user meets it.
     *
     * **The counts are not arbitrary.** This venue draws its fill price with a Kotlin expression, and the
     * script engine compiles one expression at a time for the whole process at tens of milliseconds each,
     * so every order it accepts costs the venue's dispatch thread that much. The two phases the venue
     * answers by pure substitution — a cancel it refuses and a status it reads out of its book — are
     * eight times the size of the one that makes it price something, and the report shows the difference.
     */
    @Test
    fun `the FX example ships a three-phase set that plans without a refusal`() {
        val workspace = openInTemp()
        val store = LoadSetStore(File(workspace, "load-sets").absolutePath)
        val set = assertNotNull(store.load("fx-order-book"), "the shipped load set did not come across")

        assertEquals("FX order book", set.label)
        assertEquals(listOf("Order", "Unknown", "Status"), set.phases.map { it.label })
        assertEquals(mapOf("run" to "\${uuid:4}"), set.seed, "one seed, rendered once and frozen for the set")
        assertEquals(OnFailure.STOP, set.onFailure, "there is no point asking after orders that were never placed")
        assertEquals(FixConnectionConfig.MessageStoreKind.MEMORY, set.storeAndLog?.store)
        assertEquals(FixConnectionConfig.MessageLogKind.NONE, set.storeAndLog?.log)

        assertEquals(mapOf("orderId" to 37), set.phases[0].capture, "phase 3 reads what phase 1 keeps")
        assertEquals(listOf("8", "9", "8"), set.phases.map { it.match?.replyType })
        // Message n goes to lane (n - 1) % lanes, so every phase counting from 1 puts index n on the same
        // lane in all three. That is what makes the status request reach the session whose book holds the
        // order: the venue keeps one book per counterparty, and a lane is a counterparty.
        assertEquals(listOf(1, 1, 1), set.phases.map { it.indexFrom })

        val resolver = exampleResolver(workspace)
        assertEquals(
            emptyList(),
            set.problems(resolve = resolver, surface = LoadPlan.Surface.CLI),
            "the set this example ships would be refused before it ran",
        )
        val planned = set.plan(resolver, emptyMap(), id = "example-check")
        assertEquals(3, planned.phases.size)
        assertEquals(
            listOf(250L, 2_000L, 250L),
            planned.phases.map { it.requested },
            "the phases the venue answers natively are the ones it can be asked at volume",
        )
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
        assertEquals(setOf("rfq-load-quote-request", "rfq-load-quote-response", "rfq-load-pass"), forLoad.toSet())
    }

    /**
     * **The load templates address quotes they were told about, not quotes they guessed.**
     *
     * The venue's QuoteID is opaque and its price is drawn, so neither can be derived from the request
     * the way the first slice of this example derived both. What replaced the derivation is the first
     * phase's `capture`, and these are the names it writes: a phase reading `${quoteId}` that no phase
     * captures is refused before the run starts, so this pin and the set's own `capture` block are two
     * halves of one claim.
     */
    @Test
    fun `the RFQ load templates read the quote the first phase captured`() {
        val workspace = openRfqInTemp()
        val messages = SavedMessagesService(customPath = File(workspace, "saved_messages.json").absolutePath)
        val byId = messages.loadMessagesForProfile("rfq-profile-RFQ_LOAD").associateBy { it.id }
        fun value(id: String, tag: String) = byId.getValue(id).fields.first { it.tag == tag }.value
        assertEquals("RFQ-\${run}-\${messageIndex}", value("rfq-load-quote-request", "131"))
        assertEquals("\${quoteId}", value("rfq-load-quote-response", "117"), "the id is the venue's, not ours")
        assertEquals("RFQ-\${run}-\${messageIndex}", value("rfq-load-quote-response", "11"))
        assertEquals("\${offer}", value("rfq-load-quote-response", "44"), "the hit is at the price we were quoted")
        // The third phase passes the quotes the second one did not hit, so it reads the same captured
        // ids and matches on 117, because a QuoteStatusReport carries no ClOrdID.
        assertEquals("AJ", value("rfq-load-pass", "35"))
        assertEquals("6", value("rfq-load-pass", "694"))
        assertEquals("\${quoteId}", value("rfq-load-pass", "117"))
        assertEquals("P-RFQ-\${run}-\${messageIndex}", value("rfq-load-pass", "693"))
    }

    /**
     * **The shipped set, pinned whole**, because it is the one artefact of this example that nothing
     * else in the build exercises: a phase whose template reads a name no earlier phase captures is
     * refused at plan time, and this is where that refusal would show up before a user met it.
     */
    @Test
    fun `the RFQ example ships a three-phase set that plans without a refusal`() {
        val workspace = openRfqInTemp()
        val store = LoadSetStore(File(workspace, "load-sets").absolutePath)
        val set = assertNotNull(store.load("rfq-round-trip"), "the shipped load set did not come across")

        assertEquals("RFQ round trip", set.label)
        assertEquals(listOf("Quote", "Hit", "Pass"), set.phases.map { it.label })
        assertEquals(mapOf("run" to "\${uuid:4}"), set.seed, "one seed, rendered once and frozen for the set")
        assertEquals(OnFailure.STOP, set.onFailure, "there is no point hitting quotes that were never sent")
        assertEquals(FixConnectionConfig.MessageStoreKind.MEMORY, set.storeAndLog?.store)
        assertEquals(FixConnectionConfig.MessageLogKind.NONE, set.storeAndLog?.log)

        // Phase 1 captures what phases 2 and 3 read. Named here because the templates above read them.
        assertEquals(mapOf("quoteId" to 117, "offer" to 133), set.phases[0].capture)
        assertEquals(2001, set.phases[2].indexFrom, "the pass answers the quotes the hits left alone")
        assertEquals(listOf("S", "8", "AI"), set.phases.map { it.match?.replyType })

        val resolver = exampleResolver(workspace)
        assertEquals(
            emptyList(),
            set.problems(resolve = resolver, surface = LoadPlan.Surface.CLI),
            "the set this example ships would be refused before it ran",
        )
        val planned = set.plan(resolver, emptyMap(), id = "example-check")
        assertEquals(3, planned.phases.size)
        assertEquals(8_000L, planned.phases.sumOf { it.requested }, "4,000 quotes, 2,000 hits, 2,000 passes")
    }

    /** The workspace's own profiles and templates, as a set resolves them. */
    private fun exampleResolver(workspace: File): LoadSet.Resolver {
        val profiles = ConnectionProfileService(customPath = File(workspace, "connection_profiles.json").absolutePath)
        val messages = SavedMessagesService(customPath = File(workspace, "saved_messages.json").absolutePath)
        return object : LoadSet.Resolver {
            override fun profile(key: String): LoadSet.Profile? =
                profiles
                    .loadProfiles()
                    .firstOrNull { it.id == key || it.name == key }
                    ?.let { LoadSet.Profile(it.id, it.name, it.config) }

            override fun template(key: String, profileId: String?): LoadTemplate? =
                messages
                    .loadMessagesForProfile(profileId.orEmpty())
                    .firstOrNull { it.id == key || it.name == key }
                    ?.let { LoadTemplates.of(it) }
        }
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
