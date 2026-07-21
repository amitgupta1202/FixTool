package com.knapsack.fixtool.ui.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.MessageColorScheme
import com.knapsack.fixtool.ui.AppTheme

// The three pages below hold what belongs to this machine and this pair of eyes: nothing here changes
// what a message means, only what it looks like, how much of it is kept, and where it is written.

fun appearancePage(): SettingsPage =
    SettingsPage(
        id = "appearance",
        title = "Appearance",
        subtitle = "What you see when messages arrive.",
        contains =
            listOf(
                "message colour scheme", "message color scheme", "colours", "colors", "default view mode",
                "grid view", "terminal view", "default layout", "horizontal split", "vertical split", "tabs",
            ),
        owns = { listOf(it.messageColorScheme, it.defaultViewMode, it.defaultLayout) },
        content = { AppearanceContent(it) },
    )

@Composable
private fun AppearanceContent(context: SettingsContext) {
    val draft = context.draft
    val settings = draft.value

    SettingsBlock(
        title = "Message colours",
        description = "How the grid tells outgoing from incoming, and either from a rejection.",
    ) {
        SegmentedChoice(
            options =
                listOf(
                    colourChoice("Default", MessageColorScheme.default()),
                    colourChoice("Green/Red", MessageColorScheme.greenRed()),
                    colourChoice("High contrast", MessageColorScheme.highContrast()),
                    colourChoice("Monochrome", MessageColorScheme.monochrome()),
                ),
            selected = settings.messageColorScheme,
            onSelect = { scheme -> draft.edit { copy(messageColorScheme = scheme) } },
        )
    }

    SettingsBlock(title = "Default view mode", description = "How a newly opened session is shown.") {
        SegmentedChoice(
            options = listOf(Choice("grid", "Grid view"), Choice("terminal", "Terminal view")),
            selected = settings.defaultViewMode,
            onSelect = { mode -> draft.edit { copy(defaultViewMode = mode) } },
        )
    }

    SettingsBlock(title = "Default layout", description = "How sessions are arranged when the app starts.") {
        SegmentedChoice(
            options =
                listOf(
                    Choice("horizontal", "Horizontal split"),
                    Choice("vertical", "Vertical split"),
                    Choice("tabs", "Tabs"),
                ),
            selected = settings.defaultLayout,
            onSelect = { layout -> draft.edit { copy(defaultLayout = layout) } },
        )
    }
}

/** A scheme offered with the three colours it actually paints, read from the scheme itself. */
private fun colourChoice(label: String, scheme: MessageColorScheme): Choice<MessageColorScheme> =
    Choice(
        value = scheme,
        label = label,
        swatches = listOf(Color(scheme.outgoingBright), Color(scheme.incomingBright), Color(scheme.rejectionBright)),
    )

fun sessionsPage(): SettingsPage =
    SettingsPage(
        id = "sessions",
        title = "Sessions",
        subtitle = "How a connected session behaves on this machine.",
        contains = listOf("message buffer size", "retained messages", "memory", "auto-sync session to editor"),
        owns = { listOf(it.sessionBufferSize, it.autoSyncSessionToEditor) },
        content = { SessionsContent(it) },
    )

@Composable
private fun SessionsContent(context: SettingsContext) {
    val draft = context.draft

    SettingsBlock(
        title = "Message buffer",
        description = "How many messages a session keeps before the oldest are dropped. Applies to new sessions.",
    ) {
        NumberField(draft = draft, setting = NumberSetting.SESSION_BUFFER)
    }

    SettingsBlock(title = "Editor", description = "How the message editor follows the session you are looking at.") {
        SettingsCheckbox(
            label = "Auto-sync session to editor",
            description = "Switching session tabs selects that session's connection profile in the message editor",
            checked = draft.value.autoSyncSessionToEditor,
            onCheckedChange = { draft.edit { copy(autoSyncSessionToEditor = it) } },
        )
    }
}

fun storagePage(): SettingsPage =
    SettingsPage(
        id = "storage",
        title = "Storage",
        subtitle = "Where your work is written on this machine. All three apply on restart.",
        contains =
            listOf(
                "connection profiles file", "saved messages file", "scenarios directory", "git", "paths",
            ),
        owns = { listOf(it.connectionProfilesPath, it.savedMessagesPath, it.scenariosPath) },
        content = { StorageContent(it) },
    )

@Composable
private fun StorageContent(context: SettingsContext) {
    val draft = context.draft
    val settings = draft.value

    SettingsBlock(title = "Connection profiles") {
        PathField(
            value = settings.connectionProfilesPath,
            onValueChange = { draft.edit { copy(connectionProfilesPath = it) } },
            placeholder = "~/.fixtool/connection_profiles.json",
            kind = PathKind.FILE_TO_WRITE,
            chooserTitle = "Select connection profiles file",
            fileFilter = "JSON Files (*.json)" to "json",
            emptyNote = "Default: ~/.fixtool/connection_profiles.json",
        )
    }

    SettingsBlock(title = "Saved messages") {
        PathField(
            value = settings.savedMessagesPath,
            onValueChange = { draft.edit { copy(savedMessagesPath = it) } },
            placeholder = "~/.fixtool/saved_messages.json",
            kind = PathKind.FILE_TO_WRITE,
            chooserTitle = "Select saved messages file",
            fileFilter = "JSON Files (*.json)" to "json",
            emptyNote = "Default: ~/.fixtool/saved_messages.json",
        )
    }

    SettingsBlock(
        title = "Scenarios",
        description = "One git-friendly JSON per scenario, named for the scenario — point this at a repo to version-track them.",
    ) {
        PathField(
            value = settings.scenariosPath,
            onValueChange = { draft.edit { copy(scenariosPath = it) } },
            placeholder = "~/.fixtool/scenarios",
            kind = PathKind.DIRECTORY,
            chooserTitle = "Select scenarios directory",
            emptyNote = "Default: ~/.fixtool/scenarios",
        )
        Text(
            text = "Scenario files are written one per scenario, so a tracked directory diffs and reviews cleanly.",
            fontSize = 11.sp,
            color = AppTheme.Colors.textDisabled,
        )
    }
}
