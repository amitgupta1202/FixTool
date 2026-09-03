package com.knapsack.fixtool.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import com.knapsack.fixtool.model.AppSettings
import com.knapsack.fixtool.model.FixDictionary

/**
 * Everything a page needs to draw itself, handed down once instead of threaded through each control.
 */
@Stable
class SettingsContext(
    val draft: SettingsDraft,
    val dictionary: FixDictionary,
    /** The loaded dictionary's fields, scanned once for every tag picker on every page. */
    val fields: List<Pair<Int, String>>,
    val openVenueTagRoles: () -> Unit,
    /** What the venue tag roles sidecar currently says, or what was just written to it. */
    val venueTagNote: String,
    val venueTagNoteIsFresh: Boolean,
    /** The open project workspace, which the Storage page reports rather than edits. */
    val workspaceFolder: String = "",
    /** The open workspace is the installation's own directory, so there is nothing to close. */
    val workspaceIsDefault: Boolean = true,
    val onOpenWorkspace: () -> Unit = {},
    val onCloseWorkspace: () -> Unit = {},
)

/**
 * One page of Settings.
 *
 * Pages are cut by **who owns the values on them** — the venue, this machine, this pair of eyes — which
 * is the same seam the app already drew when venue tag roles went to a sidecar beside the dictionary
 * instead of into `AppSettings`. Cutting by owner is what makes [subtitle] writable at all: a page whose
 * settings share an owner can be described in one sentence, and a page that cannot be described in one
 * sentence is holding two pages' worth of things.
 */
@Stable
class SettingsPage(
    val id: String,
    val title: String,
    /** Who owns the values on this page — one sentence, shown under the title. */
    val subtitle: String,
    /** The individual settings here, in the words someone would type looking for them. */
    val contains: List<String>,
    /**
     * The values this page shows, so the sidebar can mark it when one of them has been edited.
     *
     * A hand-listed set of fields, and so the same shape of thing that let Restore Defaults go stale —
     * but the stakes are a different order. This list sits in the same file as the controls that render
     * these fields, a few lines away rather than seventeen hundred, and forgetting an entry costs a dot
     * beside a page name, not a setting that fails to save.
     */
    val owns: (AppSettings) -> List<Any?>,
    val content: @Composable (SettingsContext) -> Unit,
) {
    fun isEdited(draft: SettingsDraft): Boolean = owns(draft.value) != owns(draft.original)

    /** The setting name that answers this query, or null when the page has no answer for it. */
    fun matchFor(query: String): String? {
        if (query.isBlank()) return null
        if (title.contains(query, ignoreCase = true)) return title
        return contains.firstOrNull { it.contains(query, ignoreCase = true) }
            ?: subtitle.takeIf { it.contains(query, ignoreCase = true) }
    }
}

/**
 * Every page, in the order they are offered.
 *
 * Protocol first because a wrong dictionary makes every other page describe the wrong thing; Developer
 * last because most people never need it.
 */
fun settingsPages(): List<SettingsPage> =
    listOf(
        protocolPage(),
        tagsPage(),
        appearancePage(),
        sessionsPage(),
        storagePage(),
        latencyPage(),
        developerPage(),
    )
