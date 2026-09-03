package com.knapsack.fixtool.service

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

/**
 * The examples that ship with the app, and the copy that turns one into a workspace of your own.
 *
 * ### Why the FX venue is data now
 *
 * It used to be three Kotlin builders that minted profiles, templates and scenarios at Start and
 * deleted everything demo-prefixed at Stop. That worked, and it had two costs. The install went into
 * **the user's own** files, so the demo and their work shared one profiles list and one scenarios
 * directory, and Stop had to find its own things again by id prefix to take them back out. And a
 * viewer who wanted to keep a piece of it had nothing to keep: the scenario existed only for as long
 * as the demo was installed.
 *
 * An example is now a directory that gets **copied** into a workspace with a name the user chooses.
 * Nothing to uninstall, because nothing was mixed in. What they change is theirs, and what they keep
 * they keep. The counter-argument the old builders carried in their own doc comment — that a file
 * would need loading, validating and versioning — is answered by the copy being an ordinary
 * workspace: it is read by the same services that read every other one, so it needs no second
 * loader, and a user who breaks theirs opens the example again under a new name.
 */
object ExampleWorkspaces {
    private val logger = LoggerFactory.getLogger(ExampleWorkspaces::class.java)

    private const val ROOT = "/examples"

    /** The bundled FX venue: the demo, as data. */
    const val FX_VENUE = "fx-venue"

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Index(
        val examples: List<String> = emptyList(),
    )

    @Serializable
    data class Example(
        val id: String,
        val displayName: String,
        val summary: String = "",
        val defaultWorkspaceName: String = displayName,
        val files: List<String> = emptyList(),
    )

    /** Every bundled example, in the order the index lists them. */
    fun all(): List<Example> =
        readResource("$ROOT/index.json")
            ?.let { json.decodeFromString<Index>(it).examples }
            .orEmpty()
            .mapNotNull { byId(it) }

    fun byId(id: String): Example? =
        readResource("$ROOT/$id/manifest.json")?.let { manifest ->
            runCatching { json.decodeFromString<Example>(manifest) }
                .onFailure { logger.error("Example '$id' has an unreadable manifest", it) }
                .getOrNull()
        }

    /** Where a new workspace goes unless the user browses elsewhere. */
    fun defaultLocation(): File = WorkspacePaths.home.workspaces

    /**
     * Copies [exampleId] into `<location>/<slug of name>` and returns the new workspace directory.
     *
     * The FIX version reaches the copied profiles rather than the app: an example is a set of sessions,
     * and which FIX version they speak is a property of those sessions, not of the machine opening them.
     *
     * Refuses a directory that already holds a workspace, because the alternative is overwriting
     * someone's edited copy of the example with the pristine one and calling it "open".
     */
    fun open(
        exampleId: String,
        name: String,
        location: File,
        now: Long = System.currentTimeMillis(),
    ): Result<File> {
        val example =
            byId(exampleId)
                ?: return Result.failure(IllegalArgumentException("no bundled example '$exampleId'"))
        val target = File(location, slug(name))
        if (target.exists() && target.listFiles().orEmpty().isNotEmpty()) {
            return Result.failure(IllegalStateException("'${target.absolutePath}' already holds a workspace"))
        }
        return runCatching {
            example.files.forEach { relative ->
                val body =
                    readResource("$ROOT/$exampleId/$relative")
                        ?: error("example '$exampleId' names '$relative', which is not in the build")
                val out = File(target, relative)
                out.parentFile?.mkdirs()
                out.writeText(body)
            }
            stampTimes(target, now)
            writeGitignore(target)
            logger.info("Opened example '{}' as '{}' in {}", exampleId, name, target.absolutePath)
            target
        }
    }

    /**
     * Gives the copy real timestamps.
     *
     * The bundled file carries `createdAt: 0`, which is how the export keeps a machine's clock out of
     * the repository. A workspace wants the truth, and the truth is that these profiles were made now.
     *
     * The FIX version is deliberately NOT rewritten. It used to be, from a field in the dialog, and
     * that field was theatre: a loaded data dictionary overrides a profile's beginString at connect
     * time (`FixConnectionManager.determineBeginString`), and one is essentially always loaded. The
     * copy keeps the version the bundle carries, and Settings -> Protocol is where the wire version is
     * actually decided.
     */
    private fun stampTimes(
        workspace: File,
        now: Long,
    ) {
        val profiles = ConnectionProfileService(customPath = File(workspace, "connection_profiles.json").absolutePath)
        val updated =
            profiles.loadProfiles().map { profile ->
                profile.copy(createdAt = now, lastUsedAt = now)
            }
        profiles.saveProfiles(updated)
    }

    /**
     * Creates an empty workspace: a directory, and the .gitignore every workspace wants.
     *
     * The other half of Open, and the reason New exists at all. Before this the only way to a clean
     * workspace was to make a folder in Finder and browse to it, which is a thing the app should be
     * able to do for you.
     */
    fun createEmpty(
        name: String,
        location: File,
    ): Result<File> {
        val target = File(location, slug(name))
        if (target.exists() && target.listFiles().orEmpty().isNotEmpty()) {
            return Result.failure(IllegalStateException("'${target.absolutePath}' already holds a workspace"))
        }
        return runCatching {
            require(target.isDirectory || target.mkdirs()) { "could not create '${target.absolutePath}'" }
            writeGitignore(target)
            logger.info("Created empty workspace in {}", target.absolutePath)
            target
        }
    }

    /**
     * A workspace is meant to be committable, so it arrives knowing what must not be.
     *
     * `secrets.json` holds the logon passwords, and the rest is machine output: QuickFIX/J's sequence
     * store and message log, and the run records. Written once, at copy time, and never touched
     * again — a user who wants to track their store has only to delete a line.
     */
    private fun writeGitignore(workspace: File) {
        val file = File(workspace, ".gitignore")
        if (file.exists()) {
            return
        }
        file.writeText(
            """
            # Logon passwords. The rest of this workspace is meant to be shared; this file is not.
            secrets.json

            # QuickFIX/J's sequence-number store and message log, and this machine's run records.
            store/
            log/
            logs/
            runs/
            """.trimIndent() + "\n",
        )
    }

    /**
     * A directory name from what the user typed.
     *
     * Kept legible rather than escaped: a workspace is a folder someone will open in Finder and commit
     * to a repository, so `FX Venue` becomes `fx-venue` and not `FX%20Venue`.
     */
    fun slug(name: String): String {
        val slug =
            name
                .lowercase()
                .map { if (it.isLetterOrDigit()) it else '-' }
                .joinToString("")
                .split('-')
                .filter { it.isNotEmpty() }
                .joinToString("-")
        return slug.ifBlank { "workspace" }
    }

    private fun readResource(path: String): String? =
        ExampleWorkspaces::class.java.getResourceAsStream(path)?.use { it.readBytes().decodeToString() }
}
