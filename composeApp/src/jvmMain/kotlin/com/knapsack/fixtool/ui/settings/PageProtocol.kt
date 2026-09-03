package com.knapsack.fixtool.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.RejectionRule
import com.knapsack.fixtool.ui.AppTheme
import com.knapsack.fixtool.ui.SlimDropdown
import com.knapsack.fixtool.ui.TooltipIconButton

fun protocolPage(): SettingsPage =
    SettingsPage(
        id = "protocol",
        title = "Protocol",
        subtitle = "What the wire means here — owned by the venue, not by this machine.",
        contains =
            listOf(
                "data dictionary", "bundled dictionary", "FIX version", "transport dictionary", "FIXT11",
                "venue tag roles", "validation", "QuickFIX/J", "rejection rules", "reject",
            ),
        owns = {
            listOf(
                it.useBundledDictionary,
                it.defaultFixVersion,
                it.defaultDataDictionary,
                it.defaultTransportDictionary,
                it.validateFieldsOutOfOrder,
                it.validateFieldsHaveValues,
                it.validateUserDefinedFields,
                it.validateIncomingMessage,
                it.rejectionRules,
            )
        },
        content = { ProtocolContent(it) },
    )

@Composable
private fun ProtocolContent(context: SettingsContext) {
    val draft = context.draft
    val settings = draft.value

    SettingsBlock(
        title = "Data dictionary",
        description = "The XML that gives every tag its name, type and place. Applied on restart.",
    ) {
        SettingsCheckbox(
            label = "Use bundled dictionary",
            description = "Parse with the dictionary shipped inside FixTool for the FIX version below",
            checked = settings.useBundledDictionary,
            onCheckedChange = { draft.edit { copy(useBundledDictionary = it) } },
        )

        if (settings.useBundledDictionary) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppTheme.Spacing.medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "FIX version", fontSize = 12.sp, color = AppTheme.Colors.text, modifier = Modifier.width(150.dp))
                SlimDropdown(
                    value = settings.defaultFixVersion,
                    options = FixVersion.entries.toList(),
                    onValueChange = { picked -> picked?.let { v -> draft.edit { copy(defaultFixVersion = v) } } },
                    displayText = { it.displayName },
                    placeholder = "Select FIX version",
                    modifier = Modifier.width(220.dp),
                )
            }
            if (settings.defaultFixVersion.isFix50Plus) {
                Text(
                    text = "FIX 5.0+ carries session messages over FIXT.1.1 with ApplVerID=${settings.defaultFixVersion.applVerID}",
                    fontSize = 11.sp,
                    color = AppTheme.Colors.info,
                )
            }
        } else {
            PathField(
                value = settings.defaultDataDictionary,
                onValueChange = { draft.edit { copy(defaultDataDictionary = it) } },
                placeholder = "Path to a data dictionary XML",
                kind = PathKind.EXISTING_FILE,
                chooserTitle = "Select data dictionary file",
                fileExtension = "xml",
                emptyNote = "No dictionary configured — tags will show as numbers without names.",
                detail = { file ->
                    runCatching { FixDictionaryAdapter.detectVersionFromFile(file) }.getOrNull()?.displayName
                },
            )

            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Transport dictionary — required by FIX 5.0+ for session messages such as Logon",
                fontSize = 11.sp,
                color = AppTheme.Colors.textDisabled,
            )
            PathField(
                value = settings.defaultTransportDictionary,
                onValueChange = { draft.edit { copy(defaultTransportDictionary = it) } },
                placeholder = "Path to FIXT11.xml",
                kind = PathKind.EXISTING_FILE,
                chooserTitle = "Select transport dictionary file (FIXT11.xml)",
                fileExtension = "xml",
                emptyNote = "Optional below FIX 5.0.",
                startNear = { settings.defaultDataDictionary },
            )
        }
    }

    // Venue tag roles: the one thing a FIX dictionary cannot record — who MINTS a value. Reached from
    // here rather than raised when a dictionary loads, because every existing user configured theirs
    // long ago and that moment never comes again.
    SettingsBlock(
        title = "Venue tag roles",
        description =
            "Which tags in this dictionary carry an identifier somebody mints per run — the venue's own, " +
                "and the standard ones FixTool has no answer for. Saved beside the dictionary, not in " +
                "these settings — the declaration belongs to the venue, so it does not wait on Save.",
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsButton(
                text = "Venue tag roles…",
                onClick = context.venueTags.open,
                containerColor = AppTheme.Colors.surface,
                contentColor = AppTheme.Colors.text,
                modifier = Modifier.testTag("open-venue-tag-roles"),
            )
            Text(
                text = context.venueTags.note,
                fontSize = 11.sp,
                color = if (context.venueTags.noteIsFresh) AppTheme.Colors.primary else AppTheme.Colors.textSecondary,
            )
        }
    }

    SettingsBlock(
        title = "QuickFIX/J validation",
        description = "How strictly the engine reads what arrives. All off is the flexible default.",
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsCheckbox(
                label = "Fields out of order",
                description = "Reject messages whose fields are not in dictionary order",
                checked = settings.validateFieldsOutOfOrder,
                onCheckedChange = { draft.edit { copy(validateFieldsOutOfOrder = it) } },
                modifier = Modifier.weight(1f),
            )
            SettingsCheckbox(
                label = "Fields have values",
                description = "Reject messages carrying an empty field value",
                checked = settings.validateFieldsHaveValues,
                onCheckedChange = { draft.edit { copy(validateFieldsHaveValues = it) } },
                modifier = Modifier.weight(1f),
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsCheckbox(
                label = "User-defined fields",
                description = "Reject messages carrying custom fields the dictionary does not declare",
                checked = settings.validateUserDefinedFields,
                onCheckedChange = { draft.edit { copy(validateUserDefinedFields = it) } },
                modifier = Modifier.weight(1f),
            )
            SettingsCheckbox(
                label = "Incoming messages",
                description = "Run full validation over everything received",
                checked = settings.validateIncomingMessage,
                onCheckedChange = { draft.edit { copy(validateIncomingMessage = it) } },
                modifier = Modifier.weight(1f),
            )
        }
    }

    SettingsBlock(
        title = "Rejection rules",
        description = "Which messages this venue uses to say no. Matched messages are coloured as rejections.",
    ) {
        settings.rejectionRules.forEachIndexed { index, rule ->
            RejectionRuleRow(
                rule = rule,
                onChange = { changed ->
                    draft.edit {
                        copy(rejectionRules = rejectionRules.mapIndexed { i, existing -> if (i == index) changed else existing })
                    }
                },
                onRemove = {
                    draft.edit { copy(rejectionRules = rejectionRules.filterIndexed { i, _ -> i != index }) }
                },
            )
        }
        SettingsButton(
            text = "+ Add rejection rule",
            onClick = { draft.edit { copy(rejectionRules = rejectionRules + RejectionRule(messageType = "")) } },
            containerColor = AppTheme.Colors.surface,
            contentColor = AppTheme.Colors.primary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun RejectionRuleRow(
    rule: RejectionRule,
    onChange: (RejectionRule) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(AppTheme.Colors.surface, RoundedCornerShape(4.dp))
                .border(1.dp, AppTheme.Separators.color, RoundedCornerShape(4.dp))
                .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        LabelledField(
            label = "Message type (35)",
            value = rule.messageType,
            onValueChange = { onChange(rule.copy(messageType = it)) },
            placeholder = "3, j, 9, AZ",
            modifier = Modifier.weight(1f),
        )
        LabelledField(
            label = "Also tag (optional)",
            value = rule.additionalTag?.toString() ?: "",
            onValueChange = { typed -> onChange(rule.copy(additionalTag = typed.toIntOrNull())) },
            placeholder = "905",
            modifier = Modifier.weight(1f),
        )
        LabelledField(
            label = "With value (optional)",
            value = rule.additionalValue ?: "",
            onValueChange = { typed -> onChange(rule.copy(additionalValue = typed.ifBlank { null })) },
            placeholder = "3",
            modifier = Modifier.weight(1f),
        )
        TooltipIconButton(tooltip = "Remove rule", onClick = onRemove, modifier = Modifier.size(28.dp)) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Remove",
                tint = AppTheme.Colors.textSecondary,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

@Composable
private fun LabelledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(text = label, fontSize = 10.sp, color = AppTheme.Colors.textDisabled)
        SettingsField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
