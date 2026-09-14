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

    /** The bundled RFQ venue: a request-for-quote desk, and the first real use case for a load run. */
    const val RFQ_VENUE = "rfq-venue"

    /** The bundled equity venue: the first whose book still holds your order a minute later. */
    const val EQUITY_VENUE = "equity-venue"

    /** The bundled crypto venue: 24/7, and the one that refuses rather than crosses a post-only order. */
    const val CRYPTO_VENUE = "crypto-venue"

    /** The bundled fixed-income RFQ desk: the same negotiation as the FX one, in a bond desk's vocabulary. */
    const val FI_RFQ_VENUE = "fi-rfq-venue"

    /**
     * Written into a copy, naming the example it came from.
     *
     * Provenance, not content. A copy is otherwise identical to any workspace you made yourself, and
     * the alternative was working out where it came from by comparing its path against where Open puts
     * things — which cannot tell a copy apart from a workspace you happened to name "FX Venue", and
     * would have had the app assert something false about your own work.
     */
    const val ORIGIN_FILE = ".fixtool-origin"

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

    /**
     * The dictionary [exampleId] names for itself, or null when it names none.
     *
     * Read from the build rather than from a copy, because it answers for the copies laid down before examples
     * named one: see [DictionaryChoice.resolve].
     */
    fun dictionaryOf(exampleId: String): WorkspaceDictionary? =
        readResource("$ROOT/$exampleId/${WorkspaceDictionary.FILE}")?.let { text ->
            runCatching { WorkspaceDictionary.parse(text).dictionary }
                .onFailure { logger.error("Example '$exampleId' has an unreadable ${WorkspaceDictionary.FILE}", it) }
                .getOrNull()
        }

    /** Where a new workspace goes unless the user browses elsewhere. */
    fun defaultLocation(): File = WorkspacePaths.home.workspaces

    /**
     * Lays every bundled example down as a folder in [location], leaving alone any folder already there.
     *
     * **An example is a folder, not a button.** The app used to list each example in the workspace
     * switcher and as a button on the empty session area, copying it out only when clicked. That made an
     * example a second kind of workspace with a verb of its own, and it scaled one button per example:
     * at five, the empty state's row of buttons no longer fit, and the folders a user went looking for
     * in `workspaces/` were not there until they had clicked. So the app lays them down at start, where
     * Open workspace already starts browsing, and opening one is opening a folder.
     *
     * **A folder that exists is never touched** — not rewritten, not given an origin file. It may be a
     * copy with the user's edits, or a workspace of their own that happens to share the name, and
     * neither is ours to change. A deleted one comes back at the next start, which is also how to get a
     * pristine one without Reset.
     *
     * Returns the folders it created.
     */
    fun layDownMissing(
        location: File,
        now: Long = System.currentTimeMillis(),
    ): List<File> =
        all().mapNotNull { example ->
            val target = File(location, slug(example.defaultWorkspaceName))
            if (target.exists() && (!target.isDirectory || target.listFiles().orEmpty().isNotEmpty())) {
                return@mapNotNull null
            }
            open(example.id, example.defaultWorkspaceName, location, now)
                .onFailure { logger.error("Could not lay down example '{}' in {}", example.id, location, it) }
                .getOrNull()
        }

    /**
     * Opens [exampleId] at `<location>/<slug of name>`, copying it out of the build the first time.
     *
     * **Idempotent, because it is called Open.** Opening a workspace you already have must give you
     * that workspace — with your edits, your captured scenarios, your rule changes — and not a pristine
     * clone beside it. It used to mint `fx-venue-2`, `fx-venue-3` and so on, which meant the second
     * Open silently abandoned the first one's contents. The copy happens only when there is nothing
     * there to open.
     *
     * A fresh one is a different intent with a different answer: rename or delete the folder, the same
     * as for any other workspace.
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
        if (target.isDirectory && target.listFiles().orEmpty().isNotEmpty()) {
            logger.info("Example '{}' is already at {}; opening it rather than copying again", exampleId, target)
            // A copy made before origin files existed gets one now, so Reset finds it without anyone
            // having to migrate anything.
            val origin = File(target, ORIGIN_FILE)
            if (!origin.isFile) {
                runCatching { origin.writeText(exampleId + "\n") }
                    .onFailure { logger.warn("Could not record the origin of {}", target, it) }
            }
            return Result.success(target)
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
            File(target, ORIGIN_FILE).writeText(exampleId + "\n")
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
     * The example [workspace] is a copy of, or null.
     *
     * Read from the workspace's own [ORIGIN_FILE] rather than worked out from its path. A path told
     * you only "it is where Open puts things", which a workspace you made and called "FX Venue" also
     * satisfies; and it stopped being true the moment anyone moved the folder.
     */
    fun exampleAt(workspace: File): Example? {
        val origin = File(workspace, ORIGIN_FILE)
        if (!origin.isFile) {
            return null
        }
        val id = runCatching { origin.readText().trim() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return byId(id)
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
