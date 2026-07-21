package com.knapsack.fixtool.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.knapsack.fixtool.model.AppSettings
import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.service.VenueTagScan
import com.knapsack.fixtool.ui.settings.SettingsContext
import com.knapsack.fixtool.ui.settings.SettingsDraft
import com.knapsack.fixtool.ui.settings.SettingsButton
import com.knapsack.fixtool.ui.settings.SettingsField
import com.knapsack.fixtool.ui.settings.SettingsPage
import com.knapsack.fixtool.ui.settings.settingsPages

/**
 * Settings, as a set of pages rather than one column.
 *
 * The pages are cut by who owns the values on them — the venue, this machine, this pair of eyes — so
 * that each can be described in the one sentence under its title. What this replaces was a single
 * scroll of fourteen sections in no order at all, where reaching the automation port meant travelling
 * past the dictionary, validation, buffer size, grid columns, protocol tags, colours, view mode,
 * layout, rejection rules and latency, and where nothing on screen ever said which of those you had
 * changed.
 */
@Composable
fun SettingsDialog(
    currentSettings: AppSettings,
    dictionary: FixDictionary,
    onSave: (AppSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val draft = remember { SettingsDraft(currentSettings) }
    val pages = remember { settingsPages() }
    val fields = remember(dictionary) { dictionary.getAllFields() }

    var query by remember { mutableStateOf("") }
    var openPageId by remember { mutableStateOf(pages.first().id) }
    var showVenueTagRoles by remember { mutableStateOf(false) }
    var venueRolesSaved by remember { mutableStateOf<String?>(null) }
    var confirmingDiscard by remember { mutableStateOf(false) }

    // Scanning the dictionary for venue tags walks every field it has; remembered so it happens when the
    // dictionary or the sidecar changes, rather than on every keystroke in the search box below.
    val venueTagNote = remember(dictionary, venueRolesSaved) { venueRolesSaved ?: VenueTagScan.summary(dictionary) }

    val listed = remember(query, pages) { pages.filter { query.isBlank() || it.matchFor(query) != null } }
    // A search that hides the open page moves to the first that survived it, so the right-hand pane is
    // never blank while matches are sitting in the sidebar.
    val openPage = listed.firstOrNull { it.id == openPageId } ?: listed.firstOrNull()

    val problems = draft.problems
    val requestClose = { if (draft.isDirty) confirmingDiscard = true else onDismiss() }

    Dialog(
        onDismissRequest = requestClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box {
            Surface(
                modifier = Modifier.width(900.dp).height(640.dp),
                shape = RoundedCornerShape(8.dp),
                color = AppTheme.Colors.surface,
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    SettingsHeader(
                        onRestoreDefaults = draft::restoreDefaults,
                        onClose = requestClose,
                    )
                    HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)

                    Row(modifier = Modifier.weight(1f)) {
                        SettingsSidebar(
                            pages = listed,
                            openPage = openPage,
                            draft = draft,
                            query = query,
                            onQueryChange = { query = it },
                            onOpen = { openPageId = it.id },
                        )
                        VerticalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)

                        if (openPage == null) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = "No settings match \"$query\".",
                                    fontSize = 12.sp,
                                    color = AppTheme.Colors.textDisabled,
                                )
                            }
                        } else {
                            SettingsPageBody(
                                page = openPage,
                                context =
                                    SettingsContext(
                                        draft = draft,
                                        dictionary = dictionary,
                                        fields = fields,
                                        openVenueTagRoles = { showVenueTagRoles = true },
                                        venueTagNote = venueTagNote,
                                        venueTagNoteIsFresh = venueRolesSaved != null,
                                    ),
                            )
                        }
                    }

                    HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)
                    SettingsFooter(
                        problems = problems,
                        isDirty = draft.isDirty,
                        onCancel = requestClose,
                        onSave = {
                            onSave(draft.forSaving())
                            onDismiss()
                        },
                    )
                }
            }

            if (confirmingDiscard) {
                DiscardConfirmation(
                    onKeepEditing = { confirmingDiscard = false },
                    onDiscard = onDismiss,
                )
            }
        }
    }

    if (showVenueTagRoles) {
        VenueTagRolesDialog(
            dictionary = dictionary,
            onSaved = { sidecar ->
                // The write is to disk, not to AppSettings — the declaration belongs to the venue's
                // dictionary, not to this machine's preferences, so it does not wait on Save below and
                // is not undone by Cancel. Invalidate so the very next capture uses it.
                dictionary.reloadTagRoles()
                venueRolesSaved = "saved to ${java.io.File(sidecar).name}"
                showVenueTagRoles = false
            },
            onDismiss = { showVenueTagRoles = false },
        )
    }
}

@Composable
private fun SettingsHeader(onRestoreDefaults: () -> Unit, onClose: () -> Unit) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(AppTheme.Colors.background)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "Settings", fontSize = 15.sp, color = AppTheme.Colors.text, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SettingsButton(
                text = "Restore defaults",
                onClick = onRestoreDefaults,
                containerColor = restoreDefaultsButtonColor,
                contentColor = AppTheme.Colors.text,
                modifier = Modifier.testTag("settings-restore-defaults"),
            )
            TooltipIconButton(tooltip = "Close", onClick = onClose, modifier = Modifier.size(24.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = AppTheme.Colors.textSecondary,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
    }
}

@Composable
private fun SettingsSidebar(
    pages: List<SettingsPage>,
    openPage: SettingsPage?,
    draft: SettingsDraft,
    query: String,
    onQueryChange: (String) -> Unit,
    onOpen: (SettingsPage) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .width(210.dp)
                .fillMaxHeight()
                .background(AppTheme.Colors.surfaceHeader)
                .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SettingsField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = "Search settings",
            modifier = Modifier.fillMaxWidth().testTag("settings-search"),
        )

        pages.forEach { page ->
            val isOpen = page.id == openPage?.id
            val matched = page.matchFor(query)
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isOpen) AppTheme.Colors.surface else Color.Transparent)
                        .clickable { onOpen(page) }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .testTag("settings-page-${page.id}"),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = page.title,
                        fontSize = 12.sp,
                        color = if (isOpen) AppTheme.Colors.primary else AppTheme.Colors.text,
                        modifier = Modifier.weight(1f),
                    )
                    // A page holding an edit says so, so that Save is never a leap and Cancel is never
                    // a surprise. There is no other way to see what a draft has touched.
                    if (page.isEdited(draft)) {
                        Box(
                            modifier =
                                Modifier
                                    .size(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(AppTheme.Colors.primary),
                        )
                    }
                }
                // While searching, each page says which of its settings answered the query — otherwise
                // a filtered list only tells you where not to look.
                if (matched != null && !matched.equals(page.title, ignoreCase = true)) {
                    Text(text = matched, fontSize = 10.sp, color = AppTheme.Colors.textDisabled, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun SettingsPageBody(page: SettingsPage, context: SettingsContext) {
    val scrollState = rememberScrollState()
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(text = page.title, fontSize = 14.sp, color = AppTheme.Colors.text, fontWeight = FontWeight.Medium)
            Text(text = page.subtitle, fontSize = 11.sp, color = AppTheme.Colors.textSecondary, lineHeight = 15.sp)
        }
        HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)

        Box(modifier = Modifier.weight(1f)) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                page.content(context)
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(scrollState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 2.dp),
            )
        }
    }
}

@Composable
private fun SettingsFooter(
    problems: List<String>,
    isDirty: Boolean,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(AppTheme.Colors.surface)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text =
                when {
                    problems.isNotEmpty() ->
                        "⚠ " + problems.first() + if (problems.size > 1) " (and ${problems.size - 1} more)" else ""
                    isDirty -> "Unsaved changes"
                    else -> ""
                },
            fontSize = 11.sp,
            color = if (problems.isNotEmpty()) AppTheme.Colors.error else AppTheme.Colors.textDisabled,
            modifier = Modifier.weight(1f).testTag("settings-status"),
        )
        SettingsButton(
            text = "Cancel",
            onClick = onCancel,
            containerColor = cancelButtonColor,
            contentColor = AppTheme.Colors.textSecondary,
            modifier = Modifier.width(90.dp).testTag("settings-cancel"),
        )
        // Save is refused while anything is out of range, rather than storing a corrected value that
        // nobody asked for and nothing on screen would have reported.
        SettingsButton(
            text = "Save",
            onClick = onSave,
            enabled = problems.isEmpty(),
            modifier = Modifier.width(90.dp).testTag("settings-save"),
        )
    }
}

/**
 * The guard on the way out.
 *
 * Escape and a click outside both reach [Dialog]'s dismiss request, which used to discard every edit in
 * a fourteen-section form without a word.
 */
@Composable
private fun BoxScope.DiscardConfirmation(onKeepEditing: () -> Unit, onDiscard: () -> Unit) {
    Box(
        modifier =
            Modifier
                .matchParentSize()
                .background(scrimColor)
                .clickable(onClick = onKeepEditing),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = AppTheme.Colors.background,
            modifier = Modifier.width(360.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = "Discard changes?", fontSize = 13.sp, color = AppTheme.Colors.text, fontWeight = FontWeight.Medium)
                Text(
                    text = "The settings you edited have not been saved.",
                    fontSize = 11.sp,
                    color = AppTheme.Colors.textSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
                    SettingsButton(
                        text = "Keep editing",
                        onClick = onKeepEditing,
                        containerColor = cancelButtonColor,
                        contentColor = AppTheme.Colors.text,
                    )
                    SettingsButton(
                        text = "Discard",
                        onClick = onDiscard,
                        containerColor = AppTheme.Colors.error,
                        contentColor = AppTheme.Colors.background,
                        modifier = Modifier.testTag("settings-discard"),
                    )
                }
            }
        }
    }
}

private val restoreDefaultsButtonColor = Color(0xFF4A4A4A)
private val cancelButtonColor = Color(0xFF3A3A3A)
private val scrimColor = Color(0xAA000000)
