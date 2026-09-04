package com.knapsack.fixtool.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.MessageColorScheme
import com.knapsack.fixtool.service.ExampleWorkspaces
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
        contains =
            listOf(
                "message buffer size",
                "retained messages",
                "memory",
                "auto-sync session to editor",
                "order book size",
                "booked orders",
                "acceptor",
                "venue memory",
                "eviction",
            ),
        owns = { listOf(it.sessionBufferSize, it.orderBookCap, it.autoSyncSessionToEditor) },
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

    // Directly beneath the message buffer, and that placement is the argument. These two bound
    // different things and are constantly mistaken for one number: how much conversation you can
    // scroll back through, and how many orders the venue still knows about. Deriving the second from
    // the first would mean a cancel for order 400 coming back "unknown" because the *grid* had
    // scrolled past it — a venue behaviour nobody configured. Side by side, that they are two
    // questions is visible rather than asserted in a comment.
    SettingsBlock(
        title = "Order book",
        description =
            "How many orders an acceptor remembers per counterparty before the oldest finished ones are " +
                "dropped. A working order is never dropped ahead of a finished one, and every eviction is " +
                "counted beside the book. Applies immediately, to books already open.",
    ) {
        NumberField(draft = draft, setting = NumberSetting.ORDER_BOOK_CAP)
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
        subtitle = "Which workspace your profiles, messages and scenarios come from.",
        contains =
            listOf(
                "workspace",
                "workspace folder",
                "connection profiles file",
                "saved messages file",
                "scenarios directory",
                "git",
                "paths",
                "FIXTOOL_WORKSPACE",
                "run records",
                "runs directory",
                "evidence",
                "retention",
                "history",
            ),
        owns = {
            listOf(
                it.connectionProfilesPath,
                it.savedMessagesPath,
                it.scenariosPath,
                it.runRecordCap,
                it.runRecordsKept,
            )
        },
        content = { StorageContent(it) },
    )

@Composable
private fun StorageContent(context: SettingsContext) {
    val draft = context.draft
    val settings = draft.value

    WorkspaceFolder(context)

    ResetExample(context)

    Environments(context)

    PathOverrides(draft, settings)

    // Records are output, not source: they are written under ~/.fixtool/runs (or a headless run's own
    // --home) and are not given a path of their own. What is worth configuring is how much of a run is
    // kept and for how many runs — the two numbers that decide whether a soak leaves a gigabyte behind.
    SettingsBlock(
        title = "Run records",
        description =
            "Every entry of a run set writes its report and its messages to ~/.fixtool/runs as it lands, so " +
                "a suite that ran overnight can still be read in the morning. The cap never drops a message " +
                "the report points at — it falls on the unbound remainder, and a record that lost anything " +
                "says so.",
    ) {
        NumberField(draft = draft, setting = NumberSetting.RUN_RECORD_CAP)
        NumberField(draft = draft, setting = NumberSetting.RUN_RECORDS_KEPT)
    }
}

/**
 * The three per-store path settings, shown only to whoever already set one.
 *
 * They predate the workspace and still win over it, so they cannot simply be dropped — someone has a
 * scenarios directory pointed at a repository and it must keep working. They are not offered to
 * anyone who has not got one, because the workspace folder is the answer now.
 */
@Composable
private fun PathOverrides(
    draft: SettingsDraft,
    settings: com.knapsack.fixtool.model.AppSettings,
) {
    val overrides =
        listOfNotNull(
            settings.connectionProfilesPath.takeIf { it.isNotBlank() }?.let { "Connection profiles" to it },
            settings.savedMessagesPath.takeIf { it.isNotBlank() }?.let { "Saved messages" to it },
            settings.scenariosPath.takeIf { it.isNotBlank() }?.let { "Scenarios" to it },
        )
    if (overrides.isEmpty()) {
        return
    }
    SettingsBlock(
        title = "Path overrides in effect",
        description =
            "These were set before workspaces existed and still win over the workspace folder above. " +
                "Clear one to hand that store back to the workspace.",
    ) {
        overrides.forEach { (label, path) ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = "$label: $path", fontSize = 11.sp, color = AppTheme.Colors.text)
                SettingsButton(
                    text = "Clear",
                    onClick = {
                        draft.edit {
                            when (label) {
                                "Connection profiles" -> copy(connectionProfilesPath = "")
                                "Saved messages" -> copy(savedMessagesPath = "")
                                else -> copy(scenariosPath = "")
                            }
                        }
                    },
                    modifier = Modifier.testTag("settings-clear-override-$label"),
                )
            }
        }
    }
}

/**
 * The workspace's environments, and the one-off that proposes them.
 *
 * Nothing here is a setting: an environment is workspace data, like a profile. The page shows it
 * because "which environments does this workspace have" is the same question as "where does my work
 * come from", and there is nowhere better to ask it.
 *
 * The extract action is offered only when the saved profiles actually look like a grid — several
 * environments each holding several counterparties. A desk with four unrelated profiles is shown
 * nothing, because for them the answer is no.
 */
@Composable
private fun Environments(context: SettingsContext) {
    val proposal = context.workspace.environmentProposal
    if (context.workspace.environments.isEmpty() && proposal == null) {
        return
    }
    SettingsBlock(
        title = "Environments",
        description =
            "Where a counterparty is, as distinct from who it is. A connection is a counterparty times " +
                "an environment, and the session qualifier is the environment's name — so two " +
                "environments can never share a sequence-number store by accident.",
    ) {
        context.workspace.environments.forEach { environment ->
            Text(
                text = "${environment.name} — ${environment.host.ifBlank { "the profile's own host" }}",
                fontSize = 11.sp,
                color = AppTheme.Colors.text,
                modifier = Modifier.testTag("settings-environment-${environment.name}"),
            )
        }
        if (proposal != null) {
            Text(
                text =
                    "Your saved profiles look like ${proposal.environments.size} environments " +
                        "(${proposal.environments.joinToString { it.name }}) holding " +
                        "${proposal.counterparties.size} counterparties " +
                        "(${proposal.counterparties.joinToString()}).",
                fontSize = 11.sp,
                color = AppTheme.Colors.textSecondary,
            )
            Text(
                text =
                    "Extracting them adds the environments and changes nothing else: " +
                        "${proposal.replaces.joinToString()} keep working exactly as they do now, and " +
                        "Quick Connect starts offering the environments as well.",
                fontSize = 11.sp,
                color = AppTheme.Colors.textDisabled,
            )
            SettingsButton(
                text = "Extract environments",
                onClick = context.workspace.onExtractEnvironments,
                modifier = Modifier.testTag("settings-extract-environments"),
            )
        }
    }
}

/**
 * The one row that replaced three.
 *
 * The connection-profiles file, the saved-messages file and the scenarios directory were each
 * configured separately, which is three chances to point a colleague at two of them. A workspace is
 * the three of them together plus the session store, and moving it is one decision. The three
 * settings are still honoured — see PathOverrides — so nobody's existing configuration changed under
 * them; there is just no longer a way to make a new one by accident.
 */
@Composable
private fun WorkspaceFolder(context: SettingsContext) {
    SettingsBlock(
        title = "Workspace folder",
        description =
            "The profiles, saved messages, scenarios and run records you are working with. Everything " +
                "else — the dictionary, the window layout, these settings — stays with the installation, " +
                "so opening another workspace does not rearrange the app around you.",
    ) {
        Text(
            text = "${context.workspace.name} — ${context.workspace.folder}",
            fontSize = 11.sp,
            color = AppTheme.Colors.text,
            modifier = Modifier.testTag("settings-workspace-folder"),
        )
        Text(
            text =
                if (context.workspace.isDefault) {
                    "Default is the installation's own directory. It is a workspace like any other and it is " +
                        "where everyone starts, so nothing had to move when workspaces arrived — it just got " +
                        "a name. Closing any other workspace comes back here."
                } else {
                    "A workspace of its own. Close it to come back to Default."
                },
            fontSize = 11.sp,
            color = AppTheme.Colors.textDisabled,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SettingsButton(
                text = "Open workspace…",
                onClick = context.workspace.onOpen,
                modifier = Modifier.testTag("settings-open-workspace"),
            )
            SettingsButton(
                text = "Close workspace",
                onClick = context.workspace.onClose,
                enabled = !context.workspace.isDefault,
                modifier = Modifier.testTag("settings-close-workspace"),
            )
        }
        Text(
            text =
                "Set FIXTOOL_WORKSPACE to move the whole installation, settings included — what a build " +
                    "box wants when the profiles and scenarios under test are the ones checked in beside " +
                    "the code. `fixtool run --home` wins over it for one run.",
            fontSize = 11.sp,
            color = AppTheme.Colors.textDisabled,
        )
    }
}

/**
 * Back to the shipped example, for a copy that has been broken.
 *
 * Offered only for a workspace still sitting where Open put it — see [ExampleWorkspaces.exampleAt] —
 * because a workspace someone has moved is theirs. The old copy is renamed, not deleted, which is
 * why this is a button and not a confirmation dialog: there is nothing here to be sure about.
 */
@Composable
private fun ResetExample(context: SettingsContext) {
    if (context.workspace.exampleName.isBlank()) {
        return
    }
    SettingsBlock(
        title = "Reset to the shipped example",
        description =
            "This workspace is your copy of ${context.workspace.exampleName}. Reset lays down the " +
                "shipped one again and renames your current copy rather than deleting it, so anything " +
                "you want out of it is still there.",
    ) {
        SettingsButton(
            text = "Reset ${context.workspace.exampleName}",
            onClick = context.workspace.onResetExample,
            modifier = Modifier.testTag("settings-reset-example"),
        )
    }
}
