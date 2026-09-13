package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.service.IssueReport
import com.knapsack.fixtool.service.MessageIssue

/** The line's height collapsed: one row of 11sp text, which is the whole of what it may take unasked. */
private val LINE_HEIGHT = 26.dp

/** One item of the open list. Six of them is as tall as the list grows before it scrolls inside itself. */
private val ITEM_HEIGHT = 22.dp
private const val ITEMS_BEFORE_SCROLL = 6

/**
 * **One problem as the line draws it**: how bad, where, what, and what a click does.
 *
 * @param target where it is, as the editor names that place: "54 Side", "Step 3"
 * @param action the verb a click performs when it is not "go there": "Add" for a field that is missing
 * @param onClick null for an item that has nowhere to go
 */
internal data class IssueLineItem(
    val severity: MessageIssue.Severity,
    val target: String?,
    val text: String,
    val action: String? = null,
    val onClick: (() -> Unit)? = null,
)

/**
 * **What is wrong, in one row, and all of it on request.**
 *
 * The editor used to list every problem above its field grid, a line each, with no cap: sentence-length
 * messages in a dock a fifth of the window wide wrapped to several lines apiece and pushed the grid the
 * problems were about off the bottom of the panel. Collapsed, this is one row whatever it holds: the counts,
 * the worst problem, and how many more. Open, it is a list that grows to six rows and then scrolls inside
 * itself, and each row goes to the field or step it is about.
 *
 * The counts are by severity and never a single label for the lot, so a list holding a warning is not titled
 * "warnings" while it also holds an error.
 *
 * @param onDismiss withdraws what an action reported, where there is something to withdraw
 */
@Composable
@Suppress("LongParameterList")
internal fun IssuesLine(
    headline: String?,
    items: List<IssueLineItem>,
    expanded: Boolean,
    onToggle: () -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
) {
    if (items.isEmpty() && headline == null) return
    val errors = items.count { it.severity == MessageIssue.Severity.ERROR }
    val ground = if (errors > 0) AppTheme.Colors.notificationErrorBackground else AppTheme.Colors.diagnosisBackground

    Column(modifier = modifier.fillMaxWidth().background(ground).testTag(tag)) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(LINE_HEIGHT)
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 8.dp)
                    .testTag("$tag-toggle"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (errors > 0) Count(MessageIssue.Severity.ERROR, errors, "$tag-errors")
            if (items.size > errors) Count(MessageIssue.Severity.WARNING, items.size - errors, "$tag-warnings")
            Text(
                text = firstLine(headline, items.firstOrNull().takeIf { !expanded }),
                color = AppTheme.Colors.text,
                fontSize = 11.sp,
                fontWeight = if (headline != null) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).testTag("$tag-first"),
            )
            if (items.size > 1 || expanded) {
                Text(
                    text = if (expanded) "▴" else "+${items.size - 1} ▾",
                    color = AppTheme.Colors.textSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
            onDismiss?.let { DismissButton(it, "$tag-dismiss") }
        }
        if (expanded) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = ITEM_HEIGHT * ITEMS_BEFORE_SCROLL)
                        .verticalScroll(rememberScrollState()),
            ) {
                items.forEachIndexed { index, item -> Item(item, "$tag-item-$index") }
            }
        }
    }
}

/** The collapsed line's words: the headline, and the worst problem after it while the list is shut. */
private fun firstLine(
    headline: String?,
    first: IssueLineItem?,
): String =
    listOfNotNull(
        headline,
        first?.let { item -> listOfNotNull(item.target, item.text).joinToString(" — ") },
    ).joinToString(": ")

@Composable
private fun DismissButton(
    onDismiss: () -> Unit,
    tag: String,
) {
    TooltipIconButton(tooltip = "Dismiss", onClick = onDismiss, modifier = Modifier.size(18.dp).testTag(tag)) {
        Icon(
            Icons.Default.Close,
            contentDescription = "Dismiss",
            tint = AppTheme.Colors.textSecondary,
            modifier = Modifier.size(12.dp),
        )
    }
}

/** "3" beside the glyph of its severity, in that severity's colour. */
@Composable
private fun Count(
    severity: MessageIssue.Severity,
    count: Int,
    tag: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        SeverityGlyph(severity)
        Text(
            "$count",
            color = severity.colour,
            fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            modifier = Modifier.testTag(tag),
        )
    }
}

@Composable
private fun Item(
    item: IssueLineItem,
    tag: String,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = ITEM_HEIGHT)
                .let { if (item.onClick != null) it.clickable(onClick = item.onClick) else it }
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SeverityGlyph(item.severity)
        item.target?.let { target ->
            Text(
                target,
                color = AppTheme.Colors.tagNumber,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                modifier =
                    Modifier
                        .background(AppTheme.Colors.border, RoundedCornerShape(2.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
        Text(
            item.text,
            color = AppTheme.Colors.text,
            fontSize = 10.5.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        item.action?.let { Text(it, color = AppTheme.Colors.primary, fontSize = 10.5.sp, maxLines = 1) }
    }
}

@Composable
private fun SeverityGlyph(severity: MessageIssue.Severity) {
    Icon(
        imageVector = if (severity == MessageIssue.Severity.ERROR) Icons.Default.Error else Icons.Default.Warning,
        contentDescription = severity.name.lowercase(),
        tint = severity.colour,
        modifier = Modifier.size(12.dp),
    )
}

internal val MessageIssue.Severity.colour: Color
    get() = if (this == MessageIssue.Severity.ERROR) AppTheme.Colors.error else AppTheme.Colors.warning

/**
 * The count a badge has room for: the worse severity only — "3 errors", or "1 warning" when there are no
 * errors — because the line beside the grid carries both.
 */
internal fun IssueReport.badgeWords(): String? =
    when {
        errors > 0 -> "$errors error${if (errors == 1) "" else "s"}"
        warnings > 0 -> "$warnings warning${if (warnings == 1) "" else "s"}"
        else -> null
    }

/** The worst problem on each row that has one, which is what a row's mark is drawn in. */
internal fun List<MessageIssue>.worstByRow(): Map<Int, MessageIssue.Severity> =
    mapNotNull { issue -> issue.row?.let { it to issue.severity } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, severities) -> severities.min() }

/**
 * A row's mark in a field grid: a 3dp bar down its left edge, in the colour of the worst problem on it.
 * Drawn rather than laid out, so a row with a problem is not a bar's width narrower than the row above it.
 */
internal fun Modifier.issueMark(severity: MessageIssue.Severity?): Modifier =
    if (severity == null) {
        this
    } else {
        drawBehind {
            drawRect(color = severity.colour, topLeft = Offset.Zero, size = Size(3.dp.toPx(), size.height))
        }
    }
