package com.knapsack.fixtool.service

import java.io.File

/**
 * The one directory everything stored hangs off.
 *
 * Eleven places used to spell `File(System.getProperty("user.home"), ".fixtool/<thing>")` for
 * themselves, and the headless runner then rebuilt each of those paths a second time by string
 * concatenation off its `--home`. So "where does FixTool keep things" had eleven answers that only
 * happened to agree, and moving them all somewhere else meant finding all eleven.
 *
 * This names the root once and derives the rest. Nothing here creates a directory: the services that
 * own each file still do that when they write, which keeps a workspace that is only being read from
 * growing empty folders.
 *
 * ### Resolution order
 *
 * An explicit root wins (the headless runner's `--home`, or a test's temp dir), then
 * [ENV_VAR], then `~/.fixtool`. The env var is the hook a script needs: a build box driving FixTool
 * against config checked in beside the code should not have to pass `--home` to every invocation, and
 * a second workspace on one machine should not need a second user account.
 */
class WorkspacePaths(
    val root: File,
) {
    val appSettings: File get() = File(root, "app_settings.json")
    val connectionProfiles: File get() = File(root, "connection_profiles.json")
    val savedMessages: File get() = File(root, "saved_messages.json")
    val scenarioViewState: File get() = File(root, "scenario_view.json")
    val layout: File get() = File(root, "layout.json")
    val scenarios: File get() = File(root, "scenarios")
    val runs: File get() = File(root, "runs")
    val sets: File get() = File(root, "sets")

    /** QuickFIX/J's own two directories: the sequence-number store and its message log. */
    val sessionStore: File get() = File(root, "store")
    val sessionLog: File get() = File(root, "log")

    /** The application log, which is not QuickFIX/J's [sessionLog] and never has been. */
    val logs: File get() = File(root, "logs")

    /** Where an opened example or a named workspace is copied to. */
    val workspaces: File get() = File(root, "workspaces")

    override fun toString(): String = root.absolutePath

    companion object {
        const val ENV_VAR = "FIXTOOL_WORKSPACE"
        const val DEFAULT_DIR_NAME = ".fixtool"

        /**
         * The workspace this process is using.
         *
         * A settable global rather than a constructor argument, because the thing it replaces was
         * `System.getProperty("user.home")`: read wherever it was needed, including from a
         * [com.knapsack.fixtool.model.FixConnectionConfig] default that no injection reaches. Every
         * service still takes its own explicit override first, so a test that passes one is unaffected
         * by whatever this says.
         */
        @Volatile
        var current: WorkspacePaths = of(null)
            private set

        /** Points this process at [root], or back at the default when it is null or blank. */
        fun use(root: String?) {
            current = of(root)
        }

        /** Puts back a workspace taken from [current], for a caller that has to restore what it moved. */
        fun use(paths: WorkspacePaths) {
            current = paths
        }

        fun of(root: String?): WorkspacePaths =
            WorkspacePaths(
                resolveRoot(
                    explicit = root,
                    env = System.getenv(ENV_VAR),
                    home = System.getProperty("user.home") ?: ".",
                ),
            )

        /**
         * The root, from the three things that can name it.
         *
         * Blank is treated as absent throughout, because the existing services already take `""` to
         * mean "you decide" and an unset env var read through a shell arrives as empty rather than null.
         */
        fun resolveRoot(
            explicit: String?,
            env: String?,
            home: String,
        ): File {
            val named = explicit?.takeIf { it.isNotBlank() } ?: env?.takeIf { it.isNotBlank() }
            return named?.let { expandHome(it, home) } ?: File(home, DEFAULT_DIR_NAME)
        }

        /**
         * Expands a leading `~`, which a path typed into a settings field or written into a CI config
         * carries far more often than one that has been through a shell.
         */
        private fun expandHome(
            path: String,
            home: String,
        ): File =
            when {
                path == "~" -> File(home)
                path.startsWith("~/") -> File(home, path.removePrefix("~/"))
                else -> File(path)
            }
    }
}
