package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.withIds
import com.knapsack.fixtool.util.AtomicFiles
import com.knapsack.fixtool.util.NotifyingLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * Directory store for [Scenario]s — one JSON file per scenario, named for the scenario so a git-tracked
 * store reads legibly: `<slug-of-name>--<short-id>.json`, e.g. `book-a-trade--3f2a9c1b.json` (default dir
 * `~/.fixtool/scenarios`). Chosen over a single collection file so scenarios stay diffable/PR-able, one edit
 * rewrites one small file, and a corrupt write loses one scenario rather than all. Writes are crash-safe via
 * [AtomicFiles].
 *
 * **The id inside the file is the identity; the filename is derived and cosmetic.** A rename rewrites the file
 * under a new name (dropping the old one, so git sees a rename), and every id -> file lookup resolves by the
 * id a file *carries*, not by its name — with a legacy `<id>.json` fallback so a store written before the slug
 * scheme, or a corrupt `<id>.json` that must still be shouted about, is found.
 */
class ScenarioService(
    private val onError: ((String) -> Unit)? = null,
    customDir: String = "",
    // The one clock createdAt is minted from — injectable so a test can pin the birth time. Production
    // reads the wall clock, exactly as SavedFixMessage and FixConnectionProfile do for their own createdAt.
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val logger = NotifyingLogger(ScenarioService::class.java, onError)
    private val lock = Any()
    private val prettyJson = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private val dir: File =
        if (customDir.isNotBlank()) {
            File(customDir)
        } else {
            WorkspacePaths.current.scenarios
        }

    /** Where scenarios live on disk — one JSON per scenario, made for diffing/sharing/PR-ing. */
    val directory: File get() = dir

    // id -> backing file, keyed by the id INSIDE each file (not its name). Rebuilt on demand, dropped on every
    // write; a lookup that misses rebuilds once, so a file dropped in by hand or over MCP is still found.
    // Guarded by [lock], like every other field here.
    private var indexCache: Map<String, File>? = null

    /** All saved scenarios, sorted by name; unreadable files are skipped, not fatal. */
    fun list(): List<Scenario> =
        synchronized(lock) {
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
            files.mapNotNull { loadFile(it) }.sortedBy { it.name.lowercase() }
        }

    /** Scenarios visible for a profile: those with no profile tag (global) or tagged with it. */
    fun listForProfile(profileId: String?): List<Scenario> =
        list().filter { profileId == null || it.userTags.isEmpty() || profileId in it.userTags }

    fun load(id: String): Scenario? = synchronized(lock) { fileForId(id)?.let { loadFile(it) } }

    /**
     * When the scenario's file last changed, epoch millis — or null for a scenario not on disk (a draft).
     * The rail's meta line reads it; it is the one honest date the tool has without a schema change, and it
     * is exactly what an author means by "the one from yesterday".
     */
    fun modifiedAt(id: String): Long? = synchronized(lock) { fileForId(id)?.lastModified()?.takeIf { it > 0L } }

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
                val withIds = scenario.withIds()
                val previous = fileForId(withIds.id)
                // The one place createdAt is minted: the first save of a genuinely new scenario (no file yet,
                // no stamp yet). A scenario already on disk keeps what it had — null for a file older than the
                // field, which is therefore **never rewritten to add the key** (it stays byte-identical, and
                // the rail sorts it by mtime). This covers every creation path — new, capture, duplicate,
                // remap — with nothing to remember at each of them.
                val scen = if (previous == null && withIds.createdAt == null) withIds.copy(createdAt = clock()) else withIds
                val target = File(dir, chooseFileName(scen))
                val content = prettyJson.encodeToString(JsonObject.serializer(), ScenarioCodec.toJson(scen))
                AtomicFiles.writeAtomically(target, content)
                // A rename (the name, hence the slug, changed) would otherwise leave the old file behind. Drop
                // it, so the store holds one file per scenario and git sees a rename, not an orphan beside it.
                if (previous != null && previous.absolutePath != target.absolutePath && previous.exists()) {
                    previous.delete()
                }
                invalidateIndex()
                true
            } catch (e: Exception) {
                logger.error("Failed to save scenario ${scenario.id}: ${e.message}", e, notifyUser = true)
                false
            }
        }.also { if (it) onChanged?.invoke() }

    fun delete(id: String): Boolean =
        synchronized(lock) {
            val file = fileForId(id) ?: return@synchronized false
            (file.exists() && file.delete()).also { if (it) invalidateIndex() }
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

    /**
     * The file backing [id]: the indexed match (by the id a file *carries*), or the legacy `<id>.json` path
     * when a store predates the slug scheme — or when a file exists but will not parse, so `load(id)` of a
     * corrupt `<id>.json` still finds it and is said out loud rather than silently missing.
     */
    private fun fileForId(id: String): File? {
        index()[id]?.takeIf { it.isFile }?.let { return it }
        // Cache missed, or the file it named has since moved (a hand edit, a git pull): rescan once, then
        // fall back to the legacy `<id>.json` path.
        invalidateIndex()
        return index()[id]?.takeIf { it.isFile } ?: File(dir, sanitize(id) + ".json").takeIf { it.isFile }
    }

    private fun index(): Map<String, File> = indexCache ?: buildIndex().also { indexCache = it }

    private fun invalidateIndex() {
        indexCache = null
    }

    private fun buildIndex(): Map<String, File> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyMap()
        val map = LinkedHashMap<String, File>()
        // Sorted for determinism: if two files somehow claim one id, the same one wins every run.
        files.sortedBy { it.name }.forEach { f -> readId(f)?.let { map.putIfAbsent(it, f) } }
        return map
    }

    /** Just the `id` field — a file may carry a shape the codec rejects yet still name its own id. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun readId(file: File): String? =
        try {
            (Json.parseToJsonElement(file.readText()).jsonObject["id"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }

    /**
     * `book-a-trade--3f2a9c1b.json`: a git-legible slug of the name, then a short id suffix so two scenarios
     * that share a name still get distinct, stable filenames. Falls back to the full id where a short suffix
     * would collide with a *different* scenario already on disk (only reachable for non-UUID ids).
     */
    private fun chooseFileName(scenario: Scenario): String {
        val base = "${slugify(scenario.name)}--${shortId(scenario.id)}.json"
        val target = File(dir, base)
        val ownedByAnother = target.isFile && readId(target).let { it != null && it != scenario.id }
        return if (ownedByAnother) "${slugify(scenario.name)}--${sanitize(scenario.id)}.json" else base
    }

    /** Lower-case ASCII, runs of non-alphanumerics collapsed to one dash, capped — the readable half of a name. */
    private fun slugify(name: String): String {
        val slug =
            buildString { for (c in name.lowercase()) append(if (c in 'a'..'z' || c in '0'..'9') c else '-') }
                .replace(Regex("-+"), "-")
                .trim('-')
                .take(60)
        return slug.ifBlank { "scenario" }
    }

    /** The disambiguating suffix: the first 8 filesystem-safe chars of the id (a UUID's first block). */
    private fun shortId(id: String): String = sanitize(id).take(8).ifBlank { "id" }

    /** Keep ids to a filesystem-safe charset to prevent path traversal / odd filenames. */
    private fun sanitize(id: String): String = id.replace(Regex("[^A-Za-z0-9_-]"), "_")
}
