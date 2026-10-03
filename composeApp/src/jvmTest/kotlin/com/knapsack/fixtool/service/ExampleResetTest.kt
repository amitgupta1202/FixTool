package com.knapsack.fixtool.service

import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Getting back to a working example, without losing what you did to the broken one.
 *
 * The demo this replaced reset for free: Stop deleted everything and Start reinstalled the shipped
 * state. Making Open idempotent took that away, and an example whose whole job is to work is the one
 * workspace that specifically needs a way back.
 */
class ExampleResetTest {
    private val location = Files.createTempDirectory("example-reset").toFile()

    private fun openExample(name: String = "FX Venue"): File =
        ExampleWorkspaces.open(ExampleWorkspaces.FX_VENUE, name, location).getOrThrow()

    // ---------------------------------------------------------------- provenance

    @Test
    fun `a copy records which example it came from`() {
        val workspace = openExample()

        assertEquals(ExampleWorkspaces.FX_VENUE, File(workspace, ExampleWorkspaces.ORIGIN_FILE).readText().trim())
        assertEquals(ExampleWorkspaces.FX_VENUE, ExampleWorkspaces.exampleAt(workspace)?.id)
    }

    /**
     * The reason provenance is a file and not the folder's path.
     *
     * `New workspace… -> "FX Venue"` lands exactly where Open would have put a copy. Deciding by path
     * would have had the app tell you your own workspace was a copy of the example, and offer to reset
     * it — your work would have survived the rename, but the claim would have been false.
     */
    @Test
    fun `a workspace you made yourself is not a copy, even named the same and in the same place`() {
        val mine = ExampleWorkspaces.createEmpty("FX Venue", location).getOrThrow()

        assertEquals(File(location, "fx-venue"), mine, "the fixture is only meaningful if the paths collide")
        assertNull(ExampleWorkspaces.exampleAt(mine))
    }

    @Test
    fun `a copy still knows its origin after being moved`() {
        val workspace = openExample()
        val moved = File(location, "somewhere-else")
        assertTrue(workspace.renameTo(moved))

        assertEquals(ExampleWorkspaces.FX_VENUE, ExampleWorkspaces.exampleAt(moved)?.id)
    }

    /**
     * A folder with no origin cannot be told from a workspace of your own, so opening the example does not claim
     * it. Copies used to be stamped here, but origin files shipped in the same release as the examples, so no
     * released build made a copy without one.
     */
    @Test
    fun `a folder with no origin is not stamped when the example is opened again`() {
        val workspace = openExample()
        assertTrue(File(workspace, ExampleWorkspaces.ORIGIN_FILE).delete())
        assertNull(ExampleWorkspaces.exampleAt(workspace), "the fixture must start with no origin")

        val opened = openExample()

        assertNull(ExampleWorkspaces.exampleAt(workspace), "a folder with no origin was claimed as the example")
        assertEquals(File(location, "fx-venue-2"), opened)
    }

    @Test
    fun `an origin naming an example that no longer ships is no origin at all`() {
        val workspace = openExample()
        File(workspace, ExampleWorkspaces.ORIGIN_FILE).writeText("removed-in-a-later-release\n")

        assertNull(ExampleWorkspaces.exampleAt(workspace))
    }

    // ---------------------------------------------------------------- resetting

    @Test
    fun `reset lays down the shipped example again`() {
        val workspace = openExample()
        File(workspace, "connection_profiles.json").writeText("""{"profiles":[]}""")

        val reset = ExampleReset.run(ExampleWorkspaces.FX_VENUE, workspace, now = 1_700_000_000_000L).getOrThrow()

        assertEquals(workspace, reset.workspace, "the fresh copy takes the folder the broken one had")
        val profiles = ConnectionProfileService(customPath = File(reset.workspace, "connection_profiles.json").absolutePath)
        assertEquals(4, profiles.loadProfiles().size, "the shipped profiles are back")
    }

    /** Reset must never be the button that quietly ate an afternoon's rule edits. */
    @Test
    fun `reset moves the old copy aside rather than deleting it`() {
        val workspace = openExample()
        File(workspace, "scenarios/mine.json").writeText("""{"kept":true}""")

        val reset = ExampleReset.run(ExampleWorkspaces.FX_VENUE, workspace, now = 1_700_000_000_000L).getOrThrow()

        val aside = assertNotNullAside(reset.movedAside)
        assertTrue(aside.isDirectory, "the old copy is gone entirely")
        assertEquals("""{"kept":true}""", File(aside, "scenarios/mine.json").readText())
        assertTrue(aside.name.startsWith("fx-venue-before-reset-"), "the folder left behind should say what it is")
    }

    @Test
    fun `the fresh copy records its origin, so it can be reset again`() {
        val workspace = openExample()
        val reset = ExampleReset.run(ExampleWorkspaces.FX_VENUE, workspace).getOrThrow()

        assertEquals(ExampleWorkspaces.FX_VENUE, ExampleWorkspaces.exampleAt(reset.workspace)?.id)
    }

    /** A copy moved somewhere of the user's choosing is reset where it is, under the name it has. */
    @Test
    fun `reset lays the fresh copy down in the moved copy's own folder`() {
        val moved = File(location, "FX Venue")
        assertTrue(openExample().renameTo(moved))

        val reset = ExampleReset.run(ExampleWorkspaces.FX_VENUE, moved, now = 1_700_000_000_000L).getOrThrow()

        assertEquals(moved, reset.workspace, "the fresh copy went somewhere other than the folder being reset")
        assertEquals(ExampleWorkspaces.FX_VENUE, ExampleWorkspaces.exampleAt(moved)?.id)
        assertFalse(File(location, "fx-venue").exists(), "a slug of the folder's name was laid down beside it")
    }

    /** The slug of a moved copy's name can be a folder of the user's own, and Reset must not open or stamp it. */
    @Test
    fun `resetting a moved copy leaves a workspace of yours at the example's name alone`() {
        val moved = File(location, "FX Venue")
        assertTrue(openExample().renameTo(moved))
        val mine = ExampleWorkspaces.createEmpty("FX Venue", location).getOrThrow()
        File(mine, "connection_profiles.json").writeText("[]")

        val reset = ExampleReset.run(ExampleWorkspaces.FX_VENUE, moved).getOrThrow()

        assertEquals(moved, reset.workspace)
        assertNull(ExampleWorkspaces.exampleAt(mine), "a workspace of the user's own was stamped as the example")
        assertEquals("[]", File(mine, "connection_profiles.json").readText())
    }

    @Test
    fun `resetting a folder with nothing in it moves nothing aside`() {
        val empty = File(location, "empty-one").apply { mkdirs() }

        val reset = ExampleReset.run(ExampleWorkspaces.FX_VENUE, empty).getOrThrow()

        assertNull(reset.movedAside)
        assertTrue(File(reset.workspace, "connection_profiles.json").isFile)
    }

    @Test
    fun `an unknown example cannot be reset to`() {
        val workspace = openExample()
        assertTrue(ExampleReset.run("no-such-example", workspace).isFailure)
    }

    private fun assertNotNullAside(file: File?): File {
        assertTrue(file != null, "reset should have moved the old copy aside")
        return file!!
    }
}
