package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.LayoutState
import com.knapsack.fixtool.util.AtomicFiles
import com.knapsack.fixtool.util.NotifyingLogger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persists the workbench layout — panel sizes, which panels are open, the bottom-dock heights — to
 * `~/.fixtool/layout.json` (or a test dir). Sibling of `app_settings.json` and `scenario_view.json`, and
 * deliberately separate from settings: the layout is machine-written as the user drags and toggles, not a
 * user-editable preference on a settings page.
 *
 * It holds nothing that cannot be regenerated, so the rule is the same as its siblings: a missing, truncated,
 * or hand-mangled file loads as defaults; a failed write is logged and swallowed. The user loses a remembered
 * size, never the app.
 */
class LayoutStateService(
    private val onError: ((String) -> Unit)? = null,
    customPath: String = "",
) {
    private val logger = NotifyingLogger(LayoutStateService::class.java, onError)

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
            File(System.getProperty("user.home"), ".fixtool/layout.json")
        }

    init {
        file.parentFile?.mkdirs()
    }

    /** The stored layout, or defaults if the file is absent or unreadable — never an exception. */
    fun load(): LayoutState =
        try {
            if (!file.exists()) {
                LayoutState()
            } else {
                json.decodeFromString<LayoutState>(file.readText())
            }
        } catch (e: Exception) {
            logger.warn("layout.json is unreadable (${e.message}); using defaults")
            LayoutState()
        }

    /** Writes the layout atomically; a failure is logged, not raised — a lost size is not a crash. */
    fun save(state: LayoutState) {
        try {
            file.parentFile?.mkdirs()
            AtomicFiles.writeAtomically(file, json.encodeToString(state))
        } catch (e: Exception) {
            logger.error("Failed to save layout state: ${e.message}", e)
        }
    }
}
