package com.knapsack.fixtool.ui.settings

import androidx.compose.runtime.Composable

fun tagsPage(): SettingsPage =
    SettingsPage(
        id = "tags",
        title = "Tags",
        subtitle = "Which tags earn a column, and which ones the engine owns rather than you.",
        contains =
            listOf(
                "grid view columns", "columns", "protocol tags", "hide protocol tags", "session tags",
                "ClOrdID", "header tags",
            ),
        owns = { listOf(it.gridViewColumns, it.hideProtocolTags, it.protocolTags) },
        content = { TagsContent(it) },
    )

@Composable
private fun TagsContent(context: SettingsContext) {
    val draft = context.draft
    val settings = draft.value
    val nameOf: (Int) -> String = { tag -> context.dictionary.getFieldName(tag) ?: "Unknown" }

    SettingsBlock(
        title = "Grid view columns",
        description = "Top-level tags given their own column in the message grid, in the order added.",
    ) {
        TagListEditor(
            selected = settings.gridViewColumns,
            fields = context.fields,
            nameOf = nameOf,
            onChange = { picked -> draft.edit { copy(gridViewColumns = picked) } },
            emptyNote = "No extra columns — the grid shows its built-in ones only.",
            testTagPrefix = "settings-grid-columns",
        )
    }

    SettingsBlock(
        title = "Protocol tags",
        description = "Tags the FIX engine manages rather than you, so message details can fold them away.",
    ) {
        SettingsCheckbox(
            label = "Hide protocol tags by default",
            description = "Start every message detail view with these tags collapsed",
            checked = settings.hideProtocolTags,
            onCheckedChange = { draft.edit { copy(hideProtocolTags = it) } },
        )
        TagListEditor(
            selected = settings.protocolTags.sorted(),
            fields = context.fields,
            nameOf = nameOf,
            onChange = { picked -> draft.edit { copy(protocolTags = picked.toSet()) } },
            emptyNote = "No tags marked as protocol — every tag shows as a field you set.",
            testTagPrefix = "settings-protocol-tags",
        )
    }
}
