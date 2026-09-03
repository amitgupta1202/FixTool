package com.knapsack.fixtool.service

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals

/**
 * Where FixTool keeps things, and the order the three answers are asked in.
 *
 * [WorkspacePaths.resolveRoot] takes its env var and home as arguments rather than reading them,
 * because a test that had to set `FIXTOOL_WORKSPACE` for real would be setting it for every other
 * test in the JVM too.
 */
class WorkspacePathsTest {
    private val home = "/home/someone"

    @Test
    fun `nothing named means the home default`() {
        assertEquals(File("$home/.fixtool"), WorkspacePaths.resolveRoot(explicit = null, env = null, home = home))
    }

    @Test
    fun `blank counts as nothing named`() {
        assertEquals(File("$home/.fixtool"), WorkspacePaths.resolveRoot(explicit = "", env = "   ", home = home))
    }

    @Test
    fun `the env var moves the workspace`() {
        assertEquals(File("/srv/ci/fixtool"), WorkspacePaths.resolveRoot(explicit = null, env = "/srv/ci/fixtool", home = home))
    }

    @Test
    fun `an explicit root beats the env var`() {
        assertEquals(File("/tmp/run"), WorkspacePaths.resolveRoot(explicit = "/tmp/run", env = "/srv/ci/fixtool", home = home))
    }

    @Test
    fun `a leading tilde expands, because a config file is not a shell`() {
        assertEquals(File("$home/work/venue"), WorkspacePaths.resolveRoot(explicit = "~/work/venue", env = null, home = home))
        assertEquals(File(home), WorkspacePaths.resolveRoot(explicit = "~", env = null, home = home))
    }

    @Test
    fun `a tilde inside a path is a directory name, not a home`() {
        assertEquals(File("/srv/a~b"), WorkspacePaths.resolveRoot(explicit = "/srv/a~b", env = null, home = home))
    }

    @Test
    fun `every stored thing hangs off the one root`() {
        val paths = WorkspacePaths(File("/ws"))
        assertEquals(File("/ws/app_settings.json"), paths.appSettings)
        assertEquals(File("/ws/connection_profiles.json"), paths.connectionProfiles)
        assertEquals(File("/ws/saved_messages.json"), paths.savedMessages)
        assertEquals(File("/ws/environments.json"), paths.environments)
        assertEquals(File("/ws/secrets.json"), paths.secrets)
        assertEquals(File("/ws/scenario_view.json"), paths.scenarioViewState)
        assertEquals(File("/ws/layout.json"), paths.layout)
        assertEquals(File("/ws/scenarios"), paths.scenarios)
        assertEquals(File("/ws/runs"), paths.runs)
        assertEquals(File("/ws/sets"), paths.sets)
        assertEquals(File("/ws/store"), paths.sessionStore)
        assertEquals(File("/ws/log"), paths.sessionLog)
        assertEquals(File("/ws/logs"), paths.logs)
        assertEquals(File("/ws/workspaces"), paths.workspaces)
    }

    @Test
    fun `the default workspace is the one an existing install already has`() {
        assertEquals(
            File(System.getProperty("user.home"), ".fixtool"),
            WorkspacePaths.resolveRoot(explicit = null, env = null, home = System.getProperty("user.home")),
        )
    }

    @Test
    fun `naming a root points this process at it, and it can be put back`() {
        val before = WorkspacePaths.current
        try {
            WorkspacePaths.use("/tmp/elsewhere")
            assertEquals(File("/tmp/elsewhere"), WorkspacePaths.current.root)
            assertEquals(File("/tmp/elsewhere/scenarios"), WorkspacePaths.current.scenarios)
        } finally {
            WorkspacePaths.use(before)
        }
        assertEquals(before.root, WorkspacePaths.current.root)
    }
}
