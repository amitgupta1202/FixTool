// Compose UI: dense composable calls read best on one line, and a composable is a declarative *tree* — its
// length is the shape of what it draws, not a complexity it is hiding. The two rules added here are the same
// bargain the two already there were: `LongMethod` fires on every non-trivial @Composable in this project, and
// `TooManyFunctions` counts the private ones a file is *supposed* to be split into.
@file:Suppress("MaxLineLength", "LongParameterList", "LongMethod", "TooManyFunctions")

package com.knapsack.fixtool.ui.diff

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.TagStatus
import com.knapsack.fixtool.service.ExpectationEvaluator
import com.knapsack.fixtool.service.ScenarioReconcile
import com.knapsack.fixtool.service.compare.ChunkKind
import com.knapsack.fixtool.service.compare.EntrySource
import com.knapsack.fixtool.service.compare.ReferenceMessage
import com.knapsack.fixtool.ui.SlimField
import com.knapsack.fixtool.service.compare.WirePaste
import com.knapsack.fixtool.service.compare.ReferenceOption
import com.knapsack.fixtool.ui.AppTheme
import com.knapsack.fixtool.ui.AppTooltip
import com.knapsack.fixtool.ui.MATCHER_TYPES
import com.knapsack.fixtool.ui.MatcherEditor
import com.knapsack.fixtool.ui.SlimButton
import com.knapsack.fixtool.ui.SlimLabeled
import com.knapsack.fixtool.ui.SlimTagPicker
import com.knapsack.fixtool.ui.VarBadge
import com.knapsack.fixtool.ui.VariableChipData
import com.knapsack.fixtool.ui.VariablesStrip
import com.knapsack.fixtool.ui.varColorMap

/**
 * **A failed step as a diff you can edit.** The one surface in the app that authors an assertion.
 *
 * Left: the expectation, every row directly editable — the matcher chip *is* the value column. Right: the
 * message the reference slot holds, read-only, in the venue's own wire order. The gutter between them carries
 * the applies, and every one of them is an operation the engine already implements, wearing a better control.
 *
 * It renders a [ReconcileSession] and decides nothing:
 *
 * - **the pairing** — which row faces which field — comes off `Chunk.pairs`, so the two columns cannot come
 *   to disagree about what is opposite what;
 * - **the offers** come off `offersFor`, so the gutter cannot draw a button the engine would refuse;
 * - **the verdict** comes off [com.knapsack.fixtool.service.compare.Verdict], so the headline cannot count
 *   the rows differently from the view it replaces;
 * - **the entries** come off `GroupOverlay`, built once from the dictionary and fed to both sides.
 *
 * What is left for this file is drawing, and the drawing is the point: a party that arrived out of order is
 * *one violet thing that crossed*, not six red rows, and no amount of correct engine output says that.
 */
@Composable
fun DiffSurface(
    session: ReconcileSession,
    modifier: Modifier = Modifier,
    crumb: String = "",
    onSave: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    /** Save, and run the scenario again — the third click of the daily loop, and the one that proves the fix. */
    onSaveAndRerun: (() -> Unit)? = null,
    /** A run is in flight. The shared run slot allows exactly one, so the button says so rather than failing. */
    runInFlight: Boolean = false,
    /** The tag the author clicked in the message viewer — the body scrolls to its row. */
    focusTag: Int? = null,
    /** The swap menu's five entries, decided by the host (S11). Empty = no menu (the standalone harness). */
    referenceOptions: List<ReferenceOption> = emptyList(),
    onSelectReference: (ReferenceOption.Kind) -> Unit = {},
    /** Bytes the author pasted, already **read** — a reading the message disproves never reaches here. */
    onPasteWire: (WirePaste) -> Unit = {},
    /** The slot is armed: the next grid row the author clicks binds it (S8). */
    armed: Boolean = false,
    onDisarm: () -> Unit = {},
    /** This step was authored or repaired against a paste, and the badge says so wherever it shows (S3, S4). */
    pastedOrigin: Boolean = false,
    /** "Step 3" for a `mintedAtStepId`, when the host can say — the strip's chips carry it in their tooltip. */
    mintedAtLabel: (String?) -> String? = { null },
) {
    val model = session.model
    var pasting by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    var dragging by remember { mutableStateOf<Dragging?>(null) }
    // Whether the SURFACE holds the focus, or a value field has taken it. The bare keys hang on this: see
    // `onBareKey`, where the mechanism that looked sufficient turned out not to be.
    var focused by remember { mutableStateOf(false) }

    // The surface holds focus so the bare keys work at all; a click on any row takes it back from a value
    // field, which is what makes ↑/↓ and n/p work again the moment the author stops typing.
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .border(1.dp, AppTheme.Colors.border)
                .focusRequester(focusRequester)
                .onFocusChanged { focused = it.isFocused }
                .focusable()
                .onPreviewKeyEvent { session.onModifiedKey(it) }
                .onKeyEvent { session.onBareKey(it, focused, dragging != null) { dragging = null } }
                .testTag("diff-surface"),
    ) {
        DiffHeader(
            session = session,
            model = model,
            crumb = crumb,
            onSave = onSave,
            onCancel = onCancel,
            onSaveAndRerun = onSaveAndRerun,
            runInFlight = runInFlight,
            referenceOptions = referenceOptions,
            pastedOrigin = pastedOrigin,
            onSelectReference = { kind ->
                if (kind == ReferenceOption.Kind.PASTE) pasting = true else onSelectReference(kind)
            },
        )
        // Armed: the author has left this surface to click a grid row, and the slot is waiting for it. The
        // state is the ViewModel's, because the grid cannot see this document (S8) — the banner merely says so.
        if (armed) ArmedSlotBanner(onDisarm)
        if (pasting) {
            PasteSheet(
                onUse = { paste ->
                    onPasteWire(paste)
                    pasting = false
                },
                onCancel = { pasting = false },
            )
        }
        // The fix plan: computed once per (draft, reference) — the two things it is a function of, and both
        // cheap to compare — so the verdict bar can say "± fix N…" without re-walking the diff on every
        // recomposition. Keying on the model would deep-compare its whole line tree per frame of a drag.
        val fixable = remember(session.draft, session.reference) { session.fixPlan() }
        var fixing by remember { mutableStateOf(false) }
        VerdictLine(
            model,
            session.reference.provenance,
            open = session.draft.mode == MatchMode.OPEN,
            fixable = if (fixing) 0 else fixable.size,
            onFix = { fixing = true },
        )
        if (fixing) FixPlanSheet(session) { fixing = false }
        // The engine knows exactly why it is not offering a move, and it used to keep that to itself. An
        // author looking at a group full of red rows with no re-order on offer concludes — reasonably — that
        // re-ordering was never built. That is what happened. Now it says.
        model.withheldMove?.let { WhyNoMove(it) }
        // A move the engine refused. The drag says it at the cursor; `alt+↑/↓` has no cursor, so it says it
        // here — and either way it is the engine's sentence, which names the assertion the move would have
        // quietly re-aimed. A refused action says why. It does not simply fail to happen.
        session.refusal?.let { RefusedMove(it) { session.clearRefusal() } }
        // The run's scope, when the slot holds a run's own message. Clicking a chip highlights every row
        // that references the name or carries its value — the fastest answer to "where did ${id0} go".
        val scope = session.reference.variables
        if (scope.isNotEmpty()) {
            VariablesStrip(
                chips =
                    scope.map { v ->
                        VariableChipData(
                            name = v.name,
                            value = v.value,
                            tooltip =
                                buildString {
                                    append("\${${v.name}} = ${v.value}")
                                    mintedAtLabel(v.mintedAtStepId)?.let { append("\nminted by $it") }
                                    append("\nclick to highlight rows that reference or carry it")
                                },
                        )
                    },
                colors = varColorMap(scope.map { it.name }),
                highlighted = session.highlightedVariable,
                onToggle = { name ->
                    session.highlightedVariable = if (session.highlightedVariable == name) null else name
                },
                modifier = Modifier.padding(horizontal = ROW_PADDING, vertical = 2.dp),
            )
        }
        ColumnHeaders(session, session.reference.label)
        DiffBody(session, model, focusTag, dragging, focusRequester) { dragging = it }
        DiffFooter(session)
    }
}

// ------------------------------------------------------------------------------------------- the header

@Composable
private fun DiffHeader(
    session: ReconcileSession,
    model: DiffModel,
    crumb: String,
    onSave: (() -> Unit)?,
    onCancel: (() -> Unit)?,
    onSaveAndRerun: (() -> Unit)? = null,
    runInFlight: Boolean = false,
    referenceOptions: List<ReferenceOption> = emptyList(),
    pastedOrigin: Boolean = false,
    onSelectReference: (ReferenceOption.Kind) -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxWidth().background(AppTheme.Colors.surfaceHeader).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (crumb.isNotBlank()) {
                Text(crumb, color = AppTheme.Colors.textSecondary, fontSize = 11.sp, modifier = Modifier.padding(end = 8.dp))
            }
            // The mode chip. It is an EDIT — staged, undoable, saved — and not a read-only preview: with only
            // STRICT and OPEN registered, a preview you cannot save is strictly worse than an edit you can
            // undo. The semantics is DERIVED from the mode, so the chip and the scenario cannot disagree. It is
            // the one *control* in this row, so it is drawn as one — a standing fill, a hover highlight and a
            // hand cursor the outline-only badges beside it lack — never as another read-only badge (U1).
            val strict = session.draft.mode == MatchMode.STRICT
            ModeChip(
                label = session.semantics.label,
                onToggle = { session.apply(EditOp.setMode(if (strict) MatchMode.OPEN else MatchMode.STRICT)) },
            )
            ReferenceChip(session.reference.label, referenceOptions, onSelectReference)
            // The badge that outlives the paste. It has to: the golden is NOT re-pointed at pasted bytes (V4),
            // so a step repaired against a paste opens red against its own captured example ever after — and
            // this is the only thing on screen that explains why. It follows the step, not the slot.
            if (pastedOrigin) Chip("pasted", AppTheme.Colors.warning, testTag = "diff-pasted-origin")
            // **The chip is the headline in two words, and it is read first.** It used to say `failed`, drawn
            // on `needsAttention` alone with no idea what was on the right — so a step being *authored* against
            // a picked message, one that has never run, was painted FAILED; and verify-generalizes, whose whole
            // answer is "over-specified", was painted FAILED too. The word is licensed by the slot, and by the
            // same function the headline comes from, so the two cannot come to disagree.
            val provenance = session.reference.provenance
            model.verdict.chipAgainst(provenance)?.let { word ->
                val venuesOwn = model.verdict.accusesTheVenue(provenance)
                Chip(
                    label = word,
                    color = if (venuesOwn) AppTheme.Colors.error else AppTheme.Colors.warning,
                    tinted = true,
                    testTag = "diff-failed",
                )
            }
        }
        Spacer(Modifier.width(0.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            // The visible half of `n`/`p`. A keystroke nothing on screen mentions is a keystroke nobody finds,
            // and these call exactly the functions the keys call — one decider, two doors.
            SlimButton(
                "↑ prev",
                onClick = session::prevChunk,
                color = AppTheme.Colors.textSecondary,
                enabled = model.diffChunks.isNotEmpty(),
                modifier = Modifier.padding(end = 6.dp).testTag("diff-prev-chunk"),
            )
            SlimButton(
                "↓ next diff",
                onClick = session::nextChunk,
                color = AppTheme.Colors.textSecondary,
                enabled = model.diffChunks.isNotEmpty(),
                modifier = Modifier.padding(end = 6.dp).testTag("diff-next-chunk"),
            )
            SlimButton(
                "⟲ undo",
                onClick = session::undo,
                color = AppTheme.Colors.textSecondary,
                enabled = session.canUndo,
                modifier = Modifier.padding(end = 6.dp).testTag("diff-undo"),
            )
            SlimButton(
                "⟳ redo",
                onClick = session::redo,
                color = AppTheme.Colors.textSecondary,
                enabled = session.canRedo,
                modifier = Modifier.padding(end = 6.dp).testTag("diff-redo"),
            )
            Box(Modifier.weight(1f))
            // Shape churn is the common case and it is tedious, not interesting. This takes the reorder, the
            // tags the venue added and the ones it stopped sending — and never a value mismatch, because
            // those are the rows that mean something.
            if (model.canAcceptShape) {
                SlimButton(
                    "Accept all shape changes",
                    onClick = { session.apply(EditOp.acceptAllShape(session.reference.view, session.dictionary)) },
                    color = AppTheme.Colors.textSecondary,
                    modifier = Modifier.padding(end = 6.dp).testTag("diff-accept-shape"),
                )
            }
            SlimButton(
                "Re-seed from reference",
                onClick = { session.apply(EditOp.reseed(session.reference.view, session.dictionary)) },
                color = AppTheme.Colors.textSecondary,
                modifier = Modifier.padding(end = 6.dp).testTag("diff-reseed"),
            )
            onCancel?.let {
                SlimButton(
                    "Cancel",
                    onClick = it,
                    color = AppTheme.Colors.textSecondary,
                    modifier = Modifier.padding(end = 6.dp).testTag("diff-cancel"),
                )
            }
            onSave?.let {
                SlimButton(
                    "Save",
                    onClick = it,
                    color = AppTheme.Colors.primary,
                    enabled = session.isDirty,
                    modifier = Modifier.padding(end = 6.dp).testTag("diff-save"),
                )
            }
            onSaveAndRerun?.let {
                // Enabled on a clean step too: re-running a step you have not touched is how you find out
                // whether the venue has settled down, and that is a question worth being able to ask.
                SlimButton(
                    if (runInFlight) "Running…" else "Save & re-run",
                    onClick = it,
                    color = AppTheme.Colors.success,
                    enabled = !runInFlight,
                    modifier = Modifier.testTag("diff-save-rerun"),
                )
            }
        }
    }
}

@Composable
private fun Chip(label: String, color: Color, tinted: Boolean = false, testTag: String = "", onClick: (() -> Unit)? = null) {
    val box =
        Modifier
            .padding(end = 6.dp)
            .border(1.dp, color)
            .background(if (tinted) AppTheme.Colors.notificationErrorBackground else Color.Transparent)
            .padding(horizontal = 6.dp, vertical = 1.dp)
    Box(modifier = (if (onClick != null) box.clickable(onClick = onClick) else box).testTag(testTag)) {
        Text(label.uppercase(), color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * **The mode toggle — STRICT ⇄ OPEN — and the one *control* in the header's chip row.** Drawn by the shared
 * [Chip] it was indistinguishable from the static `pasted`/verdict badges beside it: nothing said the mode
 * could be changed, or what the two words meant. So this is deliberately not a badge — it carries a standing
 * fill and a hover highlight the outline-only badges do not have, a hand cursor, and a [TooltipArea] that spells
 * out the choice. No caret: it is a direct toggle, not a menu. The label stays visible; the tooltip explains.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModeChip(label: String, onToggle: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val color = AppTheme.Colors.warning
    val shape = RoundedCornerShape(4.dp)
    TooltipArea(
        tooltip = {
            Text(
                MODE_TOOLTIP,
                modifier =
                    Modifier
                        .shadow(4.dp, shape)
                        .background(AppTheme.Colors.border, shape)
                        .widthIn(max = 340.dp)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                color = AppTheme.Colors.text,
                fontSize = 11.sp,
            )
        },
        delayMillis = 400,
        tooltipPlacement =
            TooltipPlacement.ComponentRect(
                anchor = Alignment.BottomCenter,
                alignment = Alignment.BottomCenter,
                offset = DpOffset(0.dp, 4.dp),
            ),
    ) {
        // A standing fill (badges are outline-only) plus a brighter hover tint and a hand cursor: three cues
        // the static chips lack, so this reads as a toggle before it is clicked, not after.
        Box(
            modifier =
                Modifier
                    .padding(end = 6.dp)
                    .border(1.dp, color)
                    .background(color.copy(alpha = if (hovered) 0.28f else 0.14f))
                    .hoverable(interaction)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 6.dp, vertical = 1.dp)
                    .testTag("diff-mode"),
        ) {
            Text(label.uppercase(), color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// Shared verbatim with the plain viewer's mode toggle, so the two surfaces explain the choice the same way.
private const val MODE_TOOLTIP =
    "Match mode. STRICT: the reply must carry the same tags, the same number of times, in the same order. " +
        "OPEN: the listed assertions must appear in order; any extra tag the reply adds is ignored. Click to switch."

/**
 * **Shape versus behaviour** — the one line a reader must never have to work out for themselves, and it is
 * not counted here. [com.knapsack.fixtool.service.compare.Verdict] counts it, for this surface and the one it
 * replaces, so the two can never come to disagree about how many rows are red.
 */
@Composable
private fun VerdictLine(
    model: DiffModel,
    provenance: ReferenceMessage.Provenance,
    open: Boolean,
    /** How many rows the fix plan could widen — 0 draws no affordance, including while the sheet is open. */
    fixable: Int = 0,
    onFix: () -> Unit = {},
) {
    val v = model.verdict
    // Red is a claim, and it may only be made about the message the step is actually ABOUT. Against a message
    // the author bound by hand, rows that do not hold are amber: something to look at, not an accusation.
    val accuses = v.accusesTheVenue(provenance)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    when {
                        accuses -> AppTheme.Colors.notificationErrorBackground
                        v.needsAttention -> AppTheme.Colors.notificationInfoBackground
                        else -> AppTheme.Colors.surfaceVariant
                    },
                ).padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            // Against a SECOND_INSTANCE a red row is an over-specified assertion, not a venue regression; against
            // a picked or pasted message it is neither, and FixTool does not know which. The sentence has to say
            // which, or the author goes hunting a bug that does not exist.
            text = v.headlineAgainst(provenance),
            color =
                when {
                    accuses -> AppTheme.Colors.error
                    v.needsAttention -> AppTheme.Colors.warning
                    v.assertsNothing -> AppTheme.Colors.warning
                    else -> AppTheme.Colors.success
                },
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            maxLines = 1,
            modifier = Modifier.testTag("diff-summary"),
        )
        val parts = v.partsUnder(open)
        if (parts.isNotEmpty()) {
            Text(" │ ", color = AppTheme.Colors.textDisabled, fontSize = 11.sp, maxLines = 1)
            Text(parts.joinToString(" · "), color = AppTheme.Colors.textSecondary, fontSize = 11.sp, maxLines = 1)
            Text(" │ ", color = AppTheme.Colors.textDisabled, fontSize = 11.sp, maxLines = 1)
            // It is a BANNER: one line, always. The tail ellipsizes rather than wrapping the whole bar to
            // two ragged lines — the counts to its left are the part that must never be pushed off.
            Text(
                text = v.shapeVersusBehaviour,
                color = if (v.values > 0) AppTheme.Colors.error else AppTheme.Colors.textSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).testTag("diff-shape-or-behaviour"),
            )
        }
        if (fixable > 0) {
            AppTooltip(
                "Fix plan — widen the numeric and temporal bands until the reply fits. Previewed row by row " +
                    "before anything is staged; enum-coded fields, moved entries and references are never touched",
            ) {
                SlimButton(
                    "± fix $fixable…",
                    onClick = onFix,
                    color = AppTheme.Colors.warning,
                    modifier = Modifier.padding(start = 8.dp).testTag("diff-fix-plan"),
                )
            }
        }
    }
}

/**
 * **The fix plan, previewed.** Every row the plan would touch, what it asserts today, what it would assert
 * instead, and the engine's sentence for why — before one edit is staged. The knob narrows the policy: blank
 * asks each row for the smallest band covering its own gap (always green afterwards); a value applies one
 * tolerance to every numeric row, and a row the value does not reach is **marked red in the preview**, not
 * dropped from it — a plan that hides the rows it cannot fix is a plan that lies about what "apply" does.
 * Applying stages the whole plan as one edit: one line in the footer, one ⌘Z.
 */
@Composable
private fun FixPlanSheet(session: ReconcileSession, onClose: () -> Unit) {
    var toleranceText by remember { mutableStateOf("") }
    val uniform = toleranceText.trim().toDoubleOrNull()?.takeIf { it >= 0 }
    val mode =
        if (uniform != null) {
            ScenarioReconcile.FixTolerance.Uniform(uniform)
        } else {
            ScenarioReconcile.FixTolerance.CoverBoth
        }
    // Keyed on what the plan is a function of — the draft, the reference, the knob — not on the model,
    // whose deep equality would run on every keystroke into the tolerance field.
    val plan = remember(session.draft, session.reference, mode) { session.fixPlan(mode) }
    val stillRed = plan.count { !it.repairs }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(AppTheme.Colors.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag("diff-fix-sheet"),
    ) {
        Text(
            "FIX PLAN — WIDEN THE BANDS UNTIL THE REPLY FITS · PREVIEWED HERE, STAGED AS ONE EDIT",
            color = AppTheme.Colors.textSecondary,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            SlimLabeled("numeric tolerance ±") {
                SlimField(
                    value = toleranceText,
                    onValueChange = { toleranceText = it },
                    monospace = true,
                    modifier = Modifier.width(90.dp).testTag("diff-fix-tolerance"),
                )
            }
            Text(
                "blank = the smallest band covering each row's own gap",
                color = AppTheme.Colors.textSecondary,
                fontSize = 9.sp,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        plan.forEach { fix ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp).testTag("diff-fix-row-${fix.tag}"),
            ) {
                Text("${fix.tag}", color = AppTheme.Colors.tagNumber, fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.width(44.dp))
                Text(fix.name, color = AppTheme.Colors.fieldName, fontSize = 10.sp, maxLines = 1, modifier = Modifier.width(120.dp))
                Text(ExpectationEvaluator.describe(fix.current), color = AppTheme.Colors.textSecondary, fontFamily = FontFamily.Monospace, fontSize = 10.sp, maxLines = 1)
                Text("  →  ", color = AppTheme.Colors.textDisabled, fontSize = 10.sp)
                Text(
                    ExpectationEvaluator.describe(fix.proposed),
                    color = if (fix.repairs) AppTheme.Colors.success else AppTheme.Colors.error,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    maxLines = 1,
                )
                Text(
                    "  ${fix.reason}",
                    color = AppTheme.Colors.textSecondary,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 6.dp),
                )
            }
        }
        if (plan.isEmpty()) {
            Text(
                "Nothing left that widening would fix — the remaining failures are shape changes or value " +
                    "regressions, which this plan deliberately does not touch.",
                color = AppTheme.Colors.warning,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            SlimButton("Cancel", onClick = onClose, color = AppTheme.Colors.textSecondary)
            SlimButton(
                "Apply ${plan.size}",
                onClick = {
                    session.apply(EditOp.fixPlan(plan))
                    onClose()
                },
                enabled = plan.isNotEmpty(),
                color = AppTheme.Colors.primary,
                modifier = Modifier.padding(start = 6.dp).testTag("diff-fix-apply"),
            )
            if (stillRed > 0) {
                Text(
                    "  $stillRed ${if (stillRed == 1) "row drifts" else "rows drift"} beyond ± ${toleranceText.trim()} and would stay red",
                    color = AppTheme.Colors.warning,
                    fontSize = 10.sp,
                    modifier = Modifier.testTag("diff-fix-still-red"),
                )
            }
        }
    }
}

@Composable
private fun WhyNoMove(why: String) {
    Row(modifier = Modifier.fillMaxWidth().background(AppTheme.Colors.notificationInfoBackground).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text("⇄  ", color = AppTheme.Colors.warning, fontSize = 11.sp)
        Text(why, color = AppTheme.Colors.warning, fontSize = 11.sp, modifier = Modifier.testTag("diff-no-move"))
    }
}

/**
 * A move the engine refused, in the engine's own words — the sentence `EditOp` used to compute and throw
 * away, because its apply returned `null` for *"refused"* and *"nothing changed"* alike (M1).
 */
@Composable
private fun RefusedMove(why: String, onDismiss: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(AppTheme.Colors.notificationErrorBackground)
                .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text("✕  ", color = AppTheme.Colors.error, fontSize = 11.sp)
        Text(why, color = AppTheme.Colors.error, fontSize = 11.sp, modifier = Modifier.weight(1f).testTag("diff-refused-move"))
        SlimButton("esc", onClick = onDismiss, color = AppTheme.Colors.textSecondary, modifier = Modifier.padding(start = 6.dp))
    }
}

/**
 * The right column's heading is **the slot's own label**, and it used to be `RECEIVED — …` whatever was in it.
 * *Received* is a claim about how the bytes got here, and it is false of a golden and of a paste — the two
 * slots authoring uses. The label says what the message is; the only thing this adds is that it is the venue's
 * order and not ours, which is true of all five.
 */
@Composable
private fun ColumnHeaders(session: ReconcileSession, referenceLabel: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().background(AppTheme.Colors.surfaceHeader).padding(horizontal = 12.dp, vertical = 3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(LEFT_WEIGHT)) {
            Header("EXPECTATION (EDITABLE)", Modifier)
            AddAbsentTag(session)
        }
        Spacer(Modifier.width(GUTTER))
        Header("${referenceLabel.uppercase()} — WIRE ORDER", Modifier.weight(1f))
    }
}

/**
 * **The one assertion no row can host: "this tag appears nowhere."** Everything else the surface offers
 * hangs off an existing line — the gutter's `«` needs a field the reply carried, the chips need a row — but
 * a tag in *neither* column has no line to hang off, so the affordance lives beside the column it authors
 * into. Any tag is allowed: the live re-judge answers immediately, and an `absent` the reference contradicts
 * goes red on the spot, which is more honest than a picker that pretends to know what the author meant.
 */
@Composable
private fun AddAbsentTag(session: ReconcileSession) {
    var adding by remember { mutableStateOf(false) }
    var tag by remember { mutableStateOf(0) }
    if (!adding) {
        AppTooltip("Assert a tag appears nowhere in this message — for a tag in neither column. The new row is judged immediately") {
            Text(
                "+ assert a tag…",
                color = AppTheme.Colors.textSecondary,
                fontSize = 9.sp,
                modifier =
                    Modifier
                        .padding(start = 10.dp)
                        .clickable {
                            tag = 0
                            adding = true
                        }.testTag("diff-add-tag"),
            )
        }
        return
    }
    val fields = remember(session.dictionary) { session.dictionary?.getAllFields() ?: emptyList() }
    SlimTagPicker(
        tag = tag,
        fields = fields,
        onPick = { tag = it },
        modifier = Modifier.width(150.dp).padding(start = 10.dp),
        fieldTestTag = "diff-add-tag-field",
    )
    SlimButton(
        "assert absent",
        onClick = {
            session.apply(EditOp.insertAbsent(tag))
            adding = false
        },
        enabled = tag > 0,
        color = AppTheme.Colors.primary,
        modifier = Modifier.padding(start = 6.dp).testTag("diff-add-tag-confirm"),
    )
    SlimButton(
        "cancel",
        onClick = { adding = false },
        color = AppTheme.Colors.textSecondary,
        modifier = Modifier.padding(start = 4.dp).testTag("diff-add-tag-cancel"),
    )
}

@Composable
private fun Header(text: String, modifier: Modifier) {
    // Informational column labels (which side is editable, whose wire order the right shows), not disabled
    // chrome — so textSecondary, above the WCAG-AA floor textDisabled sits under at this size (U2).
    Text(text, color = AppTheme.Colors.textSecondary, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, modifier = modifier)
}

// --------------------------------------------------------------------------------------------- the body

// Wide enough for three offers side by side («, ±, ×) — at 56dp the third was clipped away, which is not
// a smaller button, it is a repair the author cannot see exists.
internal val GUTTER = 84.dp
internal val ROW_PADDING = 12.dp

// The left column is the EDITABLE one — a chip, a value field, sometimes a tolerance — and the right is read-
// only text that can be ellipsized without losing anything. Splitting the width evenly starved the side that
// has controls in it, and at a narrow window the matcher editor's own labels wrapped one character per line.
// (The tag/name column widths and the read-only value cell are shared with the viewer — see DiffBodyParts.)
internal const val LEFT_WEIGHT = 1.25f

/**
 * A **lazy** list, and it has to be: the row-level deep link scrolls to the row the author clicked in the
 * message viewer, and a `Column(verticalScroll)` has no idea where its rows are. It is also what makes a
 * 200-field market-data snapshot cheap to draw, and what `n`/`p` steer.
 *
 * (It is only *possible* because the diff is a document of its own. Inside the step editor's detail pane —
 * itself a `verticalScroll` — a lazy list is measured with infinite height, which is not a layout, it is a
 * crash waiting for a long message.)
 *
 * The list is overlaid, in one `Box`, by the three things that cannot live inside a lazy item because they
 * span several of them: the **violet crossing connector** between moved entries, the insertion line a drag
 * previews its landing with, and the tooltip that answers *would every row pass here?* before the mouse is
 * released.
 */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.DiffBody(
    session: ReconcileSession,
    model: DiffModel,
    focusTag: Int?,
    dragging: Dragging?,
    focusRequester: FocusRequester,
    onDrag: (Dragging?) -> Unit,
) {
    val items = model.items
    val listState = rememberLazyListState()
    val density = LocalDensity.current

    // The tag the author clicked, in the viewer, on the message that failed. Land on it.
    LaunchedEffect(focusTag, items.size) {
        if (focusTag == null) return@LaunchedEffect
        val at = items.indexOfFirst { it is DiffItem.Line && it.line.row.tag == focusTag }
        if (at >= 0) listState.scrollToItem(at)
    }

    // Keep the selection on screen when the KEYBOARD moved it — and only then. Scrolling because the author
    // clicked a row they can already see would yank the page out from under them.
    LaunchedEffect(session.selection) {
        val at = model.indexOf(session.selection) ?: return@LaunchedEffect
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == at }) listState.scrollToItem(at)
    }

    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(items.size) { index ->
                val item = items[index]
                val selected = session.selection != null && item.selection == session.selection
                val select = {
                    session.selection = item.selection
                    runCatching { focusRequester.requestFocus() } // back from a value field: the bare keys work again
                    Unit
                }
                // Rebuilt on every recomposition, and read through `rememberUpdatedState` inside the handle —
                // a `pointerInput` block captures its lambdas once, and a captured `dragging` would be the one
                // from the frame the gesture started on, for the whole of the gesture.
                val handlers =
                    DragHandlers(
                        start = {
                            val origin = item.selection
                            val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                            if (origin != null && info != null) {
                                session.clearRefusal()
                                val y = info.offset + info.size / 2f
                                onDrag(Dragging(origin, y).aimedAt(session, model, listState, y))
                            }
                        },
                        by = { dy -> dragging?.let { onDrag(it.aimedAt(session, model, listState, it.pointerY + dy)) } },
                        // **The drop is the only thing that applies, and it applies what the engine approved.**
                        // The tooltip already asked; this is the same op, put to the same validator. A refusal
                        // sets `session.refusal`, the draft is untouched, and the row snaps back — which is
                        // simply what happens when nothing changed.
                        drop = {
                            dragging?.landing?.let { session.apply(it.op) }
                            onDrag(null)
                        },
                        cancel = { onDrag(null) },
                    )
                when (item) {
                    is DiffItem.Band -> EntryBand(session, model, item, selected, select, handlers)
                    is DiffItem.Line -> DiffRow(session, item.line, item.depth, selected, select, handlers)
                }
            }
        }
        Canvas(modifier = Modifier.matchParentSize()) {
            drawMoveConnectors(model, listState, density)
            dragging?.let { drawDropLine(it, density) }
        }
        dragging?.let { DragTooltip(it, density) }
    }
}

/**
 * The entry header — `NoPartyIDs · entry 1 — FIRMA · 1 ExecutingFirm`, and its counterpart on the right.
 *
 * The label is not built here. [com.knapsack.fixtool.service.compare.GroupOverlay] computes it, from the
 * dictionary, for both sides — so the expectation's side and the message's side cannot come to describe the
 * same entry differently, which is the seam that produced the defect the sequence model was built to end.
 */
@Composable
private fun EntryBand(
    session: ReconcileSession,
    model: DiffModel,
    band: DiffItem.Band,
    selected: Boolean,
    onSelect: () -> Unit,
    handlers: DragHandlers,
) {
    val offerOrder = band.first
    val border = if (band.moved) DiffPalette.movedBorder else DiffPalette.entryBorder(band.hue)
    val fill =
        when {
            selected -> DiffPalette.selectedRow
            band.moved -> DiffPalette.movedBand
            else -> DiffPalette.entryBand(band.hue)
        }
    val siblings = model.overlay.siblingsOf(band.entry)
    val slot = siblings.indexOf(band.entry)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    fill,
                ).selectable(onSelect)
                .padding(start = (band.depth * 10).dp)
                .padding(horizontal = ROW_PADDING, vertical = 2.dp)
                .testTag(if (band.moved) "moved-band" else "entry-band"),
    ) {
        Row(modifier = Modifier.weight(LEFT_WEIGHT), verticalAlignment = Alignment.CenterVertically) {
            // The band's grip drags the whole entry — the unit a venue actually moves. Every row of it
            // travels, which is what makes the occurrence mapping survive the crossing (D1).
            DragHandle(handlers, testTag = "entry-handle-${band.entry.rows.first}", modifier = Modifier.padding(end = 3.dp))
            Text("▏", color = border, fontSize = 11.sp)
            Text(
                band.entry.label.ifBlank {
                    "entry ${band.entry.entryIndex + 1}"
                },
                color = AppTheme.Colors.textSecondary,
                fontSize = 10.sp,
                modifier = Modifier.padding(start = 4.dp).testTag("entry-label"),
            )
            // The dictionary did not know this group, so its boundaries are a guess from the row order — and
            // the reader is entitled to know that before they drag anything by them.
            if (band.entry.source == EntrySource.HEURISTIC) {
                Text("  guessed", color = AppTheme.Colors.warning, fontSize = 9.sp, modifier = Modifier.testTag("entry-guessed"))
            }
            Box(Modifier.weight(1f))
            // ↑/↓ ship now: the surface this replaces has them, and a replacement that can do less is not one.
            // The drag arrives in Phase 4; both route through the same engine validator.
            EntryArrow("↑", enabled = slot > 0, testTag = "entry-up") { session.apply(EditOp.moveEntry(model.overlay, band.entry.rows, slot - 1)) }
            EntryArrow("↓", enabled = slot in 0 until siblings.lastIndex, testTag = "entry-down") {
                session.apply(
                    EditOp.moveEntry(
                        model.overlay,
                        band.entry.rows,
                        slot + 1,
                    ),
                )
            }
        }
        Box(modifier = Modifier.width(GUTTER), contentAlignment = Alignment.Center) {
            if (offerOrder && model.acceptOrder != null) {
                AppTooltip("Accept the venue's new order — the expectation's entries take the order the reply carries") {
                    Box(
                        modifier =
                            Modifier
                                .border(1.dp, DiffPalette.moved)
                                .background(AppTheme.Colors.surface)
                                .clickable {
                                    session.apply(model.acceptOrder)
                                }.padding(horizontal = 4.dp)
                                .testTag("accept-new-order"),
                    ) {
                        Text("⇄", color = DiffPalette.moved, fontSize = 11.sp)
                    }
                }
            }
        }
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text("▏", color = border, fontSize = 11.sp)
            Text(band.rightLabel ?: "—", color = AppTheme.Colors.textSecondary, fontSize = 10.sp, modifier = Modifier.padding(start = 4.dp))
            if (band.moved) {
                Box(Modifier.weight(1f))
                Text(
                    "⇅ moved — same tags, same values, different position",
                    color = DiffPalette.moved,
                    fontSize = 9.sp,
                    modifier = Modifier.testTag("moved-note"),
                )
            }
        }
    }
}

@Composable
private fun EntryArrow(glyph: String, enabled: Boolean, testTag: String, onClick: () -> Unit) {
    AppTooltip(if (glyph == "↑") "Move this entry up one slot" else "Move this entry down one slot") {
        SlimButton(
            glyph,
            onClick = onClick,
            color = AppTheme.Colors.textSecondary,
            enabled = enabled,
            modifier = Modifier.padding(start = 2.dp).testTag(testTag),
        )
    }
}

// ---------------------------------------------------------------------------------------------- a row

@Composable
private fun DiffRow(
    session: ReconcileSession,
    line: DiffLine,
    depth: Int,
    selected: Boolean,
    onSelect: () -> Unit,
    handlers: DragHandlers,
) {
    // The strip's highlight: a border in the variable's own color, on top of whatever tint the row earns —
    // the highlight says "this line is about ${id0}", it must not repaint what the line IS.
    val highlightColor =
        if (session.highlights(line)) {
            varColorMap(session.reference.variables.map { it.name })[session.highlightedVariable]
        } else {
            null
        }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(if (selected) DiffPalette.selectedRow else rowTint(line))
                .then(if (highlightColor != null) Modifier.border(1.dp, highlightColor) else Modifier)
                .selectable(onSelect)
                .padding(horizontal = ROW_PADDING, vertical = 2.dp)
                .testTag(if (highlightColor != null) "diff-row-highlighted" else "diff-row"),
    ) {
        Box(modifier = Modifier.weight(LEFT_WEIGHT)) { LeftCell(session, line, depth, handlers) }
        Box(modifier = Modifier.width(GUTTER), contentAlignment = Alignment.Center) { Gutter(session, line) }
        Box(modifier = Modifier.weight(1f)) { RightCell(line) }
    }
}

/** The expectation. The matcher chip **is** the value column — there is no separate value cell to disagree. */
@Composable
private fun LeftCell(session: ReconcileSession, line: DiffLine, depth: Int, handlers: DragHandlers) {
    if (line.leftIsGap) {
        Row(modifier = Modifier.fillMaxWidth().background(DiffPalette.gap).padding(start = (depth * 10).dp)) {
            Text("not asserted", color = AppTheme.Colors.textDisabled, fontSize = 10.sp, fontStyle = FontStyle.Italic)
        }
        return
    }
    val row = line.row
    val index = row.index
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = (depth * 10).dp)) {
        // Every row of the expectation is draggable, and every drop is put to the engine. A row that may not
        // go where it was dropped is refused *there*, with the reason at the cursor — not by a handle that
        // was never drawn, which would leave the author to work out for themselves that it could not be done.
        if (index != null) {
            DragHandle(handlers, testTag = "row-handle-$index", modifier = Modifier.padding(end = 3.dp))
        }
        TagCell(row.tag, row.occurrence, line.kind)
        Text(row.name, color = AppTheme.Colors.fieldName, fontSize = 10.sp, modifier = Modifier.width(DIFF_NAME_COL))
        if (index != null && row.matcher != null) {
            val scope = session.reference.variables
            MatcherEditor(
                matcher = row.matcher,
                capturedValue = line.right?.value ?: row.actual ?: "",
                // `reference` is offered exactly when the slot carries a run's scope, and not otherwise.
                // Without one, a dropdown-seeded `${out.D.11}` on a failing row makes that row unjudgeable,
                // drops it out of every count, and the verdict then announces that every assertion would now
                // pass — the ban both hazards earned. With the scope in hand, both are gone: the seed names a
                // real variable (the one this row's value matches, when one does), and the row is judged live
                // against the run's own values, so a wrong pick is a visible red, not a silent green.
                types = if (scope.isEmpty()) DIFF_MATCHERS else MATCHER_TYPES,
                scopeVariables = scope,
                onChange = { session.apply(EditOp.setMatcher(index, row.tag, it)) },
                modifier = Modifier.testTag("matcher-${row.tag}-${row.occurrence}"),
            )
            // A capturing row wears the same filled badge a minting Send does — the ● is "this step
            // writes ${name}", whichever side of the wire the value came from.
            session.draft.fields.getOrNull(index)?.bindAs?.let { name ->
                val colors = varColorMap(scope.map { it.name } + session.draft.fields.mapNotNull { it.bindAs })
                VarBadge(
                    name,
                    colors[name] ?: AppTheme.Colors.primary,
                    minted = true,
                    modifier = Modifier.padding(start = 4.dp).testTag("capture-badge-${row.tag}-${row.occurrence}"),
                )
            }
        }
    }
}

/** The reference. Read-only, in the venue's own wire order, with the dictionary's word for the value beside it. */
@Composable
private fun RightCell(line: DiffLine) {
    val field = line.right
    if (field == null) {
        // **"not sent" is a claim about the venue, and it has to be true.** A row the reply carries in
        // *another position* is TagStatus.MOVED — the engine says so, in those words — and rendering its gap
        // as "not sent" accuses the venue of dropping a field that is sitting two lines away on the same
        // screen. The author then goes hunting a regression that does not exist, which is the failure this
        // area keeps producing. (Found by looking at the picture. No assertion had a word to say about it.)
        val elsewhere = line.row.status == TagStatus.MOVED
        Row(modifier = Modifier.fillMaxWidth().background(DiffPalette.gap)) {
            Text(
                if (elsewhere) "present in the reply — but not in this position" else "not sent",
                color = if (elsewhere) AppTheme.Colors.warning else AppTheme.Colors.textDisabled,
                fontSize = 10.sp,
                fontStyle = FontStyle.Italic,
                modifier = Modifier.testTag(if (elsewhere) "present-elsewhere" else "not-sent"),
            )
        }
        return
    }
    // The value cell is shared with the viewer (DiffBodyParts) — one place draws a field, so the two surfaces
    // cannot come to render the same value two different ways.
    FieldValueCell(field, line.kind, line.unjudged)
}

/** The gutter. It draws what the session offers, and it never invents an offer of its own. */
@Composable
private fun Gutter(session: ReconcileSession, line: DiffLine) {
    // A moved row gets no glyph at all. It *passes* where the engine has it paired — FIRMA's `447` is
    // still `D` — so a tick would be literally true and completely misleading: it would say "this row is
    // fine" inside a band that says the entry it belongs to is in the wrong place. The band carries the
    // meaning, and the gutter does not argue with it.
    if (line.kind == ChunkKind.MOVED) return
    // The status glyph is not displaced by the offers beside it. ✓ (or ◌) is what the row IS; the × that
    // authoring puts on every asserted row is what the author may DO — and trading one for the other would
    // make "you can delete this assertion" read as "this row stopped passing".
    val glyph =
        if (line.unjudged) {
            "◌"
        } else if (line.row.passed && !line.row.unasserted) {
            "✓"
        } else {
            ""
        }
    val colour = if (line.unjudged) AppTheme.Colors.warning else AppTheme.Colors.success.copy(alpha = 0.55f)
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        if (glyph.isNotBlank()) Text(glyph, color = colour, fontSize = 10.sp)
        line.offers.forEach { offer ->
            // The tooltip the offer has carried since it was born, finally drawn: a bare «/±/× is the most
            // consequential click on this surface wearing the least explanation.
            AppTooltip(offer.tooltip) {
                SlimButton(
                    offer.glyph,
                    onClick = { session.apply(offer.op) },
                    color =
                        when (offer.kind) {
                            // Authoring-delete on a row that is not failing is housekeeping, not an alarm —
                            // muted, so a freshly captured step does not read as a column of standing errors.
                            // Error-red stays on the failing rows, where the drop is one of the repairs.
                            OfferKind.DROP ->
                                if (line.row.passed || line.unjudged) AppTheme.Colors.textSecondary else AppTheme.Colors.error
                            OfferKind.ASSERT_ABSENT -> AppTheme.Colors.textSecondary
                            OfferKind.LOOSEN -> AppTheme.Colors.warning
                            else -> AppTheme.Colors.info
                        },
                    modifier = Modifier.testTag("${offer.kind.name.lowercase()}-${line.row.tag}-${line.row.occurrence}"),
                )
            }
        }
    }
}

// ------------------------------------------------------------------------------------------- the footer

@Composable
private fun DiffFooter(session: ReconcileSession) {
    val staged = session.staged
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().background(AppTheme.Colors.surfaceHeader).padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text("$staged", color = AppTheme.Colors.text, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("diff-staged"))
        Text(
            if (staged == 1) " edit staged" else " edits staged",
            color = AppTheme.Colors.textSecondary,
            fontSize = 11.sp,
        )
        // Verbatim, and it is a promise: the scenario file is not touched until Save.
        Text(
            " · nothing is written to the scenario until you save",
            color = AppTheme.Colors.textSecondary,
            fontSize = 11.sp,
            modifier = Modifier.testTag("diff-promise"),
        )
        if (staged > 0) {
            Text(
                " — " + session.stagedLabels.joinToString(" · "),
                // What the save will write — informational, and read alongside the textSecondary promise beside
                // it, so it matches rather than sitting a contrast step dimmer than its own sibling (U2).
                color = AppTheme.Colors.textSecondary,
                fontSize = 10.sp,
                modifier = Modifier.testTag("diff-staged-labels"),
            )
        }
    }
}

/**
 * **The slot, and the menu that swaps it.** Five sources, and an entry with nothing behind it is drawn
 * **disabled with the reason** rather than hidden — a missing button is a feature the author concludes was
 * never built (ground rule 6; the host decides, see `referenceOptions`).
 */
@Composable
private fun ReferenceChip(
    label: String,
    options: List<ReferenceOption>,
    onSelect: (ReferenceOption.Kind) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Chip(
            label = if (options.isEmpty()) label else "$label ▾",
            color = AppTheme.Colors.info,
            testTag = "diff-reference",
            onClick = if (options.isEmpty()) null else ({ open = true }),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    enabled = option.enabled,
                    onClick = {
                        open = false
                        onSelect(option.kind)
                    },
                    modifier = Modifier.testTag("diff-reference-${option.kind.name.lowercase()}"),
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                (if (option.selected) "✓ " else "") + option.label,
                                color = if (option.enabled) AppTheme.Colors.text else AppTheme.Colors.textDisabled,
                                fontSize = 11.sp,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                option.detail,
                                color = AppTheme.Colors.textDisabled,
                                fontSize = 10.sp,
                            )
                        }
                    },
                )
            }
            Text(
                "SWAPPING THE REFERENCE RE-JUDGES EVERY ROW · TEMPORALS ANCHOR TO THE REFERENCE, NOT THE CLOCK",
                // A consequence the author must read before they swap — informational, so textSecondary. At 8sp
                // textDisabled is ~3:1, below WCAG AA, which is exactly the wrong contrast for a caveat (U2).
                color = AppTheme.Colors.textSecondary,
                fontSize = 8.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

/** The slot is waiting for a click that will happen somewhere else — so the surface says so, and can call it off. */
@Composable
private fun ArmedSlotBanner(onDisarm: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(AppTheme.Colors.notificationInfoBackground)
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .testTag("diff-armed"),
    ) {
        Text("◎  ", color = AppTheme.Colors.info, fontSize = 11.sp)
        Text(
            "Click any row in a session grid and it becomes the reference. A message FixTool has no wire bytes " +
                "for cannot be bound, and it will say so.",
            color = AppTheme.Colors.info,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f),
        )
        SlimButton("cancel", onClick = onDisarm, color = AppTheme.Colors.textSecondary, modifier = Modifier.testTag("diff-disarm"))
    }
}

/**
 * **The paste sheet, and its lint line.**
 *
 * The lint is the whole feature: it says **what was read** and it never guesses. A `|`-rendered paste whose
 * values contain pipes is *told to the user* — the reading is disproved by the message's own checksum, and
 * `Use as reference` is refused, because the right-hand column is what Accept-actual writes into the scenario
 * and a value FixTool guessed wrong would go in as the thing to assert for ever. See [WirePaste].
 */
@Composable
internal fun PasteSheet(onUse: (WirePaste) -> Unit, onCancel: () -> Unit, useLabel: String = "Use as reference") {
    var text by remember { mutableStateOf("") }
    val paste = remember(text) { if (text.isBlank()) null else WirePaste.read(text) }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(AppTheme.Colors.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag("diff-paste-sheet"),
    ) {
        Text(
            "PASTE WIRE — THE LINT SAYS WHAT WAS READ, IT NEVER GUESSES SILENTLY",
            color = AppTheme.Colors.textSecondary,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
        )
        SlimField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("diff-paste-field"),
        )
        paste?.let { LintLine(it) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            SlimButton("Cancel", onClick = onCancel, color = AppTheme.Colors.textSecondary, modifier = Modifier.padding(end = 6.dp))
            SlimButton(
                useLabel,
                onClick = { paste?.let(onUse) },
                enabled = paste?.usable == true,
                color = AppTheme.Colors.primary,
                modifier = Modifier.testTag("diff-paste-use"),
            )
            if (paste?.usable == true) {
                Chip("provenance: pasted", AppTheme.Colors.warning, testTag = "diff-paste-provenance")
            }
        }
    }
}

/** **What was read** — the sentence the whole paste sheet exists to print. It never guesses; see [WirePaste]. */
@Composable
private fun LintLine(read: WirePaste) {
    val colour =
        when (read.verdict) {
            WirePaste.Verdict.READ -> AppTheme.Colors.success
            WirePaste.Verdict.UNVERIFIED -> AppTheme.Colors.warning
            WirePaste.Verdict.REFUSED -> AppTheme.Colors.error
        }
    Column {
        Text(read.lint, color = colour, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp).testTag("diff-paste-lint"))
        read.ignoredPrefix?.let {
            Text(
                "ignored before the first field: \"$it\"",
                // What the reader read but FixTool dropped — informational lint, like the textSecondary anchor
                // note below it; both are things the author needs to see, not disabled chrome (U2).
                color = AppTheme.Colors.textSecondary,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        read.why?.let {
            Text(it, color = AppTheme.Colors.error, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp).testTag("diff-paste-why"))
        }
        if (read.anchorNote.isNotBlank()) {
            Text(
                read.anchorNote,
                color = AppTheme.Colors.textSecondary,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 4.dp).testTag("diff-paste-anchor"),
            )
        }
    }
}

/**
 * The chip's vocabulary: everything the matcher language has, **except `reference`**.
 *
 * A reference row is made at capture, where `${id0}` is bound to something real across steps. Offering it in
 * a dropdown lets an author seed one on a failing OrdStatus row, which makes that row unjudgeable, drops it
 * out of every count, and leaves the verdict announcing that every assertion would now pass. A row that
 * already *is* a reference still says so on its chip; nothing can switch *to* one here.
 */
/** The dropdown with `reference` withheld — the diff's list when the slot carries no run scope. */
private val DIFF_MATCHERS = MATCHER_TYPES.filter { it != "reference" }
