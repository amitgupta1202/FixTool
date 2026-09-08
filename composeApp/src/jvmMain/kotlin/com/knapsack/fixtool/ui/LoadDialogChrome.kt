package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * **The chrome the two load dialogs share**, so the run dialog and the run-configurations editor are one
 * application rather than two screens that happen to open from the same menu.
 *
 * The Load run dialog was given sections, rules, a right-aligned label column, sentence hints and a pinned
 * footer. The sets editor was built before any of that and drew its own smaller versions of the same
 * things: nine-point grey group heads, a 104.dp label column, a "fix" stripe with a different marker width.
 * A phase of a set is edited by [LoadRunDialogContent] itself, so the two idioms met inside one window and
 * disagreed. There is one copy of each piece here, and both dialogs use it.
 *
 * Nothing here holds state or knows what either dialog is for. It is layout and type, and the words are
 * always the caller's.
 */

/** The gutter every section, fold row and note line shares, so their contents line up down one edge. */
internal val GUTTER = 16.dp

/** Rows inside a section are a row apart, not a hairline apart. */
internal val SECTION_ROW_GAP = 9.dp

/** Wide enough for "Wait for replies", which is what the 92.dp column could not hold. */
internal val LABEL_COLUMN = 118.dp

internal val SECTION_TITLE = AppTheme.Type.body.copy(fontWeight = FontWeight.SemiBold)

/** A link on a fold row: a shade under body, because it labels the row rather than being read along it. */
private val LINK = AppTheme.Type.body.copy(fontSize = 11.sp)

/** Three lines of prose want the leading the body style does not carry: 11.sp at 1.45. */
private val NOTE = AppTheme.Type.body.copy(fontSize = 11.sp, lineHeight = 15.95.sp)

private val CHIP = RoundedCornerShape(8.dp)

private const val EMPHASIS = "**"

/**
 * **A section: a block of rows with a rule above it and a gutter around it.**
 *
 * The words and the folding landed without the chrome, which left the dialog reading as the one continuous
 * list it started as: nothing drew the boundary a section is. A 1.dp rule above, a [GUTTER] either side and
 * [SECTION_ROW_GAP] between rows is the whole of it, and it is what makes four sections look like four.
 *
 * [rule] is null for the blocks that have nothing to be separated from above: the first section, whose top
 * edge is the lead sentence or the name row, and an open fold's body, whose top edge is the fold row.
 */
@Composable
internal fun Section(rule: String?, title: String? = null, purpose: String = "", content: @Composable () -> Unit) {
    if (rule != null) Rule(rule)
    Column(
        verticalArrangement = Arrangement.spacedBy(SECTION_ROW_GAP),
        modifier = Modifier.fillMaxWidth().padding(start = GUTTER, top = 10.dp, end = GUTTER, bottom = 12.dp),
    ) {
        if (title != null) SectionHead(title, purpose)
        content()
    }
}

/** The line between one section and the next, tagged so a test can say it is there. */
@Composable
internal fun Rule(tag: String) {
    HorizontalDivider(
        modifier = Modifier.testTag(tag),
        color = AppTheme.Separators.color,
        thickness = AppTheme.Separators.dividerThickness,
    )
}

/**
 * **A section head, with a rank the three grey nine-point labels never had.**
 *
 * The name at body size in weight, and beside it what the section is for in the dim colour, on the same
 * baseline. Ten rows at one weight under three labels that read as more grey is what made the dialog feel
 * like thirty rows.
 */
@Composable
internal fun SectionHead(title: String, purpose: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        val baseline = Modifier.alignByBaseline()
        Text(title, color = AppTheme.Colors.text, style = SECTION_TITLE, modifier = baseline)
        Text(purpose, color = AppTheme.Colors.textDisabled, style = AppTheme.Type.body, modifier = baseline)
    }
}

/**
 * **A row: a right-aligned label in its own column, and everything the row says stacked beside it.**
 *
 * Right-aligned at [LABEL_COLUMN] so every label ends at the same edge, one gap from the control it names,
 * rather than starting at the same edge and trailing off at ten different ones. The 4.dp on top drops the
 * label onto the first control's baseline, and stacking the value column is what puts a refusal under its
 * own cause.
 */
@Composable
internal fun FormRow(label: String, content: @Composable () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            label,
            color = AppTheme.Colors.textSecondary,
            style = AppTheme.Type.body,
            textAlign = TextAlign.Right,
            modifier = Modifier.width(LABEL_COLUMN).padding(top = 4.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) { content() }
    }
}

/**
 * A field's own micro-label: `=`, `→`, a tag's name beside the field that holds its number.
 *
 * Not an explainer, which is why this is still the small monospace face. Every sentence that *explains* a
 * row is a [Hint] instead: the sans body face, one sentence, with the facts a shade stronger.
 */
@Composable
internal fun Sub(text: String, color: Color = AppTheme.Colors.textDisabled) {
    if (text.isNotEmpty()) Text(text, color = color, style = AppTheme.Type.meta)
}

/**
 * **A row's one sentence, in the same face as the labels.**
 *
 * The dialogs used to carry monospace grey explainers written as field lists ("per message 131 · fixed
 * 35, 146, 55"), which read as debug output and told nobody what the row meant for this run. A hint is a
 * sentence, and the one or two facts inside it that matter are marked `**like this**` and drawn a shade
 * stronger, as the mockup's `.hint b` does.
 */
@Composable
internal fun Hint(text: String, color: Color = AppTheme.Colors.textDisabled, tag: String? = null) {
    if (text.isEmpty()) return
    Text(
        hintText(text, color, AppTheme.Colors.textSecondary),
        style = AppTheme.Type.body,
        modifier = if (tag == null) Modifier else Modifier.testTag(tag),
    )
}

/** The `**fact**` markers resolved to [strong], everything else to [dim]. Split out so a test can read it. */
internal fun hintText(raw: String, dim: Color, strong: Color): AnnotatedString =
    buildAnnotatedString {
        raw.split(EMPHASIS).forEachIndexed { index, part ->
            if (part.isEmpty()) return@forEachIndexed
            withStyle(SpanStyle(color = if (index % 2 == 1) strong else dim)) { append(part) }
        }
    }

/**
 * **A folded section that says what it holds.**
 *
 * A caret, the section's name in weight, the state of everything inside it as one line, and "change" to
 * open it. Nothing is hidden by folding: the reader who never opens this one still knows the run matches
 * on 131 and seeds `run`. [changed] replaces the summary when a refusal inside forced the fold open,
 * because "why am I looking at this" is then the more useful line.
 *
 * The whole row is one rule-topped band in the section gutter, and the link is pinned to the far edge
 * rather than left to land wherever the summary stops, which is what made a fold read as another row.
 */
@Composable
@Suppress("LongParameterList")
internal fun FoldRow(
    open: Boolean,
    title: String,
    summary: String,
    changed: String?,
    fixes: Int,
    tag: String,
    rule: String,
    onToggle: () -> Unit,
) {
    Rule(rule)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .testTag(tag)
                .padding(horizontal = GUTTER, vertical = 8.dp),
    ) {
        // A slot rather than an intrinsic width, so the two folds' titles start in the same place whichever
        // way each caret is pointing.
        Text(
            if (open) "▾" else "▸",
            color = AppTheme.Colors.textDisabled,
            style = AppTheme.Type.meta,
            modifier = Modifier.width(10.dp),
        )
        Text(title, color = AppTheme.Colors.text, style = SECTION_TITLE)
        if (fixes > 0) {
            Text(
                "· ${fixes.let { if (it == 1) "1 fix" else "$it fixes" }}",
                color = AppTheme.Colors.error,
                style = AppTheme.Type.meta,
                modifier = Modifier.testTag("$tag-fixes"),
            )
        }
        Text(
            changed ?: summary,
            color = if (changed != null) AppTheme.Colors.warning else AppTheme.Colors.textDisabled,
            style = AppTheme.Type.body,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).testTag("$tag-summary"),
        )
        Text(
            if (open) "close" else "change",
            color = AppTheme.Colors.info,
            style = LINK,
            modifier = Modifier.clickable(onClick = onToggle).testTag("$tag-change"),
        )
    }
}

/**
 * A refusal, on a stripe, with the marker that says so.
 *
 * The marker column is a fixed width rather than an intrinsic one, so two refusals on screen at once start
 * their text in the same place. It never wraps: at 22.dp the old "note" marker broke into "not" over "e"
 * and doubled the stripe's height, which is why the note is a [NoteLine] with an icon now and nothing but
 * "fix" comes through here.
 */
@Composable
internal fun Notice(text: String, tint: Color, marker: String, tag: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 2.dp)
                .background(AppTheme.Colors.surfaceVariant, RoundedCornerShape(2.dp))
                .padding(start = 6.dp, top = 3.dp, end = 6.dp, bottom = 3.dp),
    ) {
        Box(Modifier.width(2.dp).height(14.dp).background(tint))
        Text(
            marker,
            color = tint,
            style = AppTheme.Type.meta,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.width(30.dp),
        )
        Text(text, color = tint, style = AppTheme.Type.body, modifier = Modifier.weight(1f).testTag(tag))
    }
}

/** The refusal inset, in the one colour and with the one marker a refusal ever wears. */
@Composable
internal fun RefusalNotice(text: String, tag: String) {
    Notice(text, AppTheme.Colors.error, "fix", tag)
}

/**
 * **The far-end note: one line, and a marker that cannot wrap.**
 *
 * A circled "i" rather than the word "note", which is the whole fix: one character in a 14.dp circle is
 * always one line, where a four-letter word in a 22.dp column was two.
 *
 * A band in the section gutter under its own rule, not a stripe: it is the last thing the dialog says
 * before the footer, and a stripe there read as one more refusal.
 */
@Composable
internal fun NoteLine(text: String, tag: String) {
    Rule("load-rule-note")
    Row(
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = GUTTER, vertical = 8.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .padding(top = 1.dp)
                    .size(14.dp)
                    .border(1.dp, AppTheme.Colors.warning, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "i",
                color = AppTheme.Colors.warning,
                style = AppTheme.Type.meta,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.testTag("$tag-marker"),
            )
        }
        Text(
            text,
            color = AppTheme.Colors.warning,
            style = NOTE,
            modifier = Modifier.weight(1f).testTag(tag),
        )
    }
}

/**
 * A preset, a seed action or a phase action: a word, a border, and an on state.
 *
 * [modifier] is the seam for a target bigger than the word inside it. The three glyphs over the saved-set
 * list are `+`, `⧉` and `−`, and a chip that hugs a one-character word is a nine-point hit target.
 */
@Composable
internal fun Chip(label: String, on: Boolean, tag: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .background(if (on) AppTheme.Colors.selectionPrimary else AppTheme.Colors.surface, CHIP)
                .border(1.dp, if (on) AppTheme.Colors.primary else AppTheme.Colors.border, CHIP)
                .clickable(onClick = onClick)
                .testTag(tag),
    ) {
        Text(
            label,
            color = if (on) AppTheme.Colors.text else AppTheme.Colors.textSecondary,
            style = AppTheme.Type.meta,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * **The pinned footer: why the button is off, then the actions.** Never inside the scroll region.
 *
 * [left] is the pinned-why slot and takes the width, so the actions land on the far edge whatever the
 * sentence says and however long it is. Both dialogs' footers and the phase editor's are this row with
 * their own words and their own buttons in it.
 */
@Composable
internal fun DialogFooter(left: @Composable () -> Unit, actions: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().background(AppTheme.Colors.surface)) {
        HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Box(modifier = Modifier.weight(1f)) { left() }
            actions()
        }
    }
}

/** `Cmd-Enter` on a Mac and `Ctrl+Enter` everywhere else. The app ships on both. */
internal val RUN_KEYS: String =
    if (System
            .getProperty("os.name")
            .orEmpty()
            .lowercase()
            .contains("mac")
    ) {
        "⌘↵"
    } else {
        "Ctrl+↵"
    }
