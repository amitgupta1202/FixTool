package com.knapsack.fixtool.service

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
    fun `the index lists the FX venue`() {
        assertEquals(listOf(ExampleWorkspaces.FX_VENUE), ExampleWorkspaces.all().map { it.id })
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

    @Test
    fun `opening onto an existing workspace is refused rather than overwriting it`() {
        val location = Files.createTempDirectory("example-clash").toFile()
        ExampleWorkspaces.open(ExampleWorkspaces.FX_VENUE, "FX Venue", location).getOrThrow()
        val second = ExampleWorkspaces.open(ExampleWorkspaces.FX_VENUE, "FX Venue", location)
        assertTrue(second.isFailure)
        assertTrue(second.exceptionOrNull()!!.message!!.contains("already holds a workspace"))
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

    private fun profilesIn(workspace: File) =
        ConnectionProfileService(customPath = File(workspace, "connection_profiles.json").absolutePath).loadProfiles()
}
