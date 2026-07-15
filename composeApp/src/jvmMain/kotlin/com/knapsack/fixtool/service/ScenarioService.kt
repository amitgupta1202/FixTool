package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.withIds
import com.knapsack.fixtool.util.AtomicFiles
import com.knapsack.fixtool.util.NotifyingLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * Directory store for [Scenario]s — one JSON file per scenario at `<dir>/<id>.json` (default
 * `~/.fixtool/scenarios`). Chosen over a single collection file so scenarios stay diffable/PR-able,
 * one edit rewrites one small file, and a corrupt write loses one scenario rather than all of them.
 * Writes are crash-safe via [AtomicFiles].
 */
class ScenarioService(
    private val onError: ((String) -> Unit)? = null,
    customDir: String = "",
) {
    private val logger = NotifyingLogger(ScenarioService::class.java, onError)
    private val lock = Any()
    private val prettyJson = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private val dir: File =
        if (customDir.isNotBlank()) {
            File(customDir)
        } else {
            File(System.getProperty("user.home"), ".fixtool/scenarios")
        }

    /** Where scenarios live on disk — one JSON per scenario, made for diffing/sharing/PR-ing. */
    val directory: File get() = dir

    /** All saved scenarios, sorted by name; unreadable files are skipped, not fatal. */
    fun list(): List<Scenario> =
        synchronized(lock) {
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
            files.mapNotNull { loadFile(it) }.sortedBy { it.name.lowercase() }
        }

    /** Scenarios visible for a profile: those with no profile tag (global) or tagged with it. */
    fun listForProfile(profileId: String?): List<Scenario> =
        list().filter { profileId == null || it.userTags.isEmpty() || profileId in it.userTags }

    fun load(id: String): Scenario? = synchronized(lock) { loadFile(fileFor(id)) }

    /**
     * Notified after any write, so that the thing rendering the scenario list cannot go stale behind a door it
     * does not own.
     *
     * The Scenarios rail shows what is on disk, and there are four ways for that to change: the editor tab,
     * capture review, the control surface's `POST /scenarios`, and `fixtool_save_scenario`. Only the first two
     * go through the ViewModel. Ask each caller to remember to refresh and the two that do not will be found
     * by a user, saving a scenario over MCP and watching the rail go on showing the old one.
     */
    var onChanged: (() -> Unit)? = null

    /**
     * Saving is what makes a step's identity permanent. A blank id never reaches disk: [withIds] assigns
     * one to anything the author added by hand since the file was read, so the next run can address it.
     */
    @Suppress("TooGenericExceptionCaught")
    fun save(scenario: Scenario): Boolean =
        synchronized(lock) {
            try {
                val content = prettyJson.encodeToString(JsonObject.serializer(), ScenarioCodec.toJson(scenario.withIds()))
                AtomicFiles.writeAtomically(fileFor(scenario.id), content)
                true
            } catch (e: Exception) {
                logger.error("Failed to save scenario ${scenario.id}: ${e.message}", e, notifyUser = true)
                false
            }
        }.also { if (it) onChanged?.invoke() }

    fun delete(id: String): Boolean =
        synchronized(lock) {
            val file = fileFor(id)
            if (file.exists()) file.delete() else false
        }.also { if (it) onChanged?.invoke() }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    /**
     * A scenario that cannot be read is **said out loud**, not dropped — but **missing is not corrupt**.
     *
     * `notifyUser = false` meant a file the codec refused simply vanished from the rail — no banner, no row,
     * no explanation, and the author's scenario apparently deleted itself. That was survivable while the only
     * cause was a corrupt file. It is not survivable now: the codec refuses, by design, every scenario written
     * by the group-path model, and an upgrade that silently empties a user's scenario list while telling them
     * nothing is the worst possible way to deliver that message.
     *
     * A file that is **not there at all** is a different thing, and it must stay quiet. `load(id)` of an id that
     * has been deleted — or a bad request carrying a blank id (`.json`) — is the caller asking for something
     * that is not present, not a file we failed to read; shouting an error toast about it treats a benign lookup
     * as a corruption. So a `FileNotFoundException` returns null silently, and only a file that **exists and will
     * not parse** is said out loud. (Filed since Phase 2 and re-noted every phase since; fixed in Phase 8.)
     */
    private fun loadFile(file: File): Scenario? =
        try {
            ScenarioCodec.fromJson(Json.parseToJsonElement(file.readText()).jsonObject)
        } catch (e: java.io.FileNotFoundException) {
            null // missing ≠ corrupt: the id is not on disk, which is not something to shout about
        } catch (e: Exception) {
            logger.error("Cannot load scenario '${file.name}': ${e.message}", e, notifyUser = true)
            null
        }

    private fun fileFor(id: String): File = File(dir, sanitize(id) + ".json")

    /** Keep ids to a filesystem-safe charset to prevent path traversal / odd filenames. */
    private fun sanitize(id: String): String = id.replace(Regex("[^A-Za-z0-9_-]"), "_")
}
