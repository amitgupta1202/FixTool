package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.ScenarioViewState
import com.knapsack.fixtool.util.AtomicFiles
import com.knapsack.fixtool.util.NotifyingLogger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persists the rail's local view chrome — sort mode, favourite ids, collapsed sections — to
 * `~/.fixtool/scenario_view.json` (or a test dir). Sibling of `app_settings.json`, deliberately **outside**
 * the scenarios directory, which is the shared/diffable corpus this file must never join.
 *
 * It holds nothing that cannot be regenerated, so the one hard rule is: **never throw into the rail.** A
 * missing, truncated, or hand-mangled file loads as defaults; a failed write is logged and swallowed. The
 * author loses a preference, never the app.
 */
class ScenarioViewStateService(
    private val onError: ((String) -> Unit)? = null,
    customPath: String = "",
) {
    private val logger = NotifyingLogger(ScenarioViewStateService::class.java, onError)

    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    private val file: File =
        if (customPath.isNotBlank()) {
            File(customPath)
        } else {
            WorkspacePaths.home.scenarioViewState
        }

    init {
        file.parentFile?.mkdirs()
    }

    /** The stored view state, or defaults if the file is absent or unreadable — never an exception. */
    fun load(): ScenarioViewState =
        try {
            if (!file.exists()) {
                ScenarioViewState()
            } else {
                json.decodeFromString<ScenarioViewState>(file.readText())
            }
        } catch (e: Exception) {
            logger.warn("scenario_view.json is unreadable (${e.message}); using defaults")
            ScenarioViewState()
        }

    /** Writes the view state atomically; a failure is logged, not raised — a lost preference is not a crash. */
    fun save(state: ScenarioViewState) {
        try {
            file.parentFile?.mkdirs()
            AtomicFiles.writeAtomically(file, json.encodeToString(state))
        } catch (e: Exception) {
            logger.error("Failed to save scenario view state: ${e.message}", e)
        }
    }
}
