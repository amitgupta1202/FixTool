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

    /** Where a counterparty is, as distinct from who it is. See [com.knapsack.fixtool.model.Environment]. */
    val environments: File get() = File(root, "environments.json")

    /** Logon passwords, kept out of the file a workspace is shared as. */
    val secrets: File get() = File(root, "secrets.json")
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
         * Where this installation keeps its **preferences** — the dictionary paths, the window layout,
         * the latency thresholds, the rail's sort order.
         *
         * Separate from [current] because those are properties of the person at the keyboard, not of
         * the project they have open. Opening a second workspace must not hand them a fresh install
         * with no dictionary loaded and every panel back in its default place.
         */
        @Volatile
        var home: WorkspacePaths = of(null)
            private set

        /**
         * The workspace **open right now** — the profiles, saved messages, scenarios, run records and
         * QuickFIX/J session store.
         *
         * A settable global rather than a constructor argument, because the thing it replaces was
         * `System.getProperty("user.home")`: read wherever it was needed, including from a
         * [com.knapsack.fixtool.model.FixConnectionConfig] default that no injection reaches. Every
         * service still takes its own explicit override first, so a test that passes one is unaffected
         * by whatever this says.
         *
         * Defaults to [home], which is why an install that never opens a second workspace behaves
         * exactly as it did before any of this existed.
         */
        @Volatile
        var current: WorkspacePaths = home
            private set

        /**
         * Relocates the whole installation, preferences included: the `FIXTOOL_WORKSPACE` env var and
         * `fixtool run --home`. Null or blank puts it back to `~/.fixtool`.
         */
        fun use(root: String?) {
            home = of(root)
            current = home
        }

        /** Puts back a state taken from [home] and [current], for a caller restoring what it moved. */
        fun use(paths: WorkspacePaths) {
            home = paths
            current = paths
        }

        /**
         * Opens a project workspace, leaving preferences where they are.
         *
         * This is what "Open example" and "Open workspace" do. Null goes back to keeping project data
         * in [home], which is the state a fresh install is in.
         */
        fun open(root: String?) {
            current = if (root.isNullOrBlank()) home else of(root)
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
