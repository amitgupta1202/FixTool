package com.knapsack.fixtool.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.*

// Global tooltip state to ensure only one tooltip is visible at a time
private object TooltipState {
    var activeTooltipId: String? by mutableStateOf(null)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TooltipIconButton(
    tooltip: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val buttonId = remember { UUID.randomUUID().toString() }
    var showTooltip by remember { mutableStateOf(false) }

    // Manage tooltip visibility based on hover and global state
    LaunchedEffect(isHovered) {
        if (isHovered) {
            // Wait for the delay before showing tooltip
            delay(600)
            // Set this button as the active tooltip
            TooltipState.activeTooltipId = buttonId
            showTooltip = true
        } else {
            // Clear tooltip immediately when hover ends
            showTooltip = false
            if (TooltipState.activeTooltipId == buttonId) {
                TooltipState.activeTooltipId = null
            }
        }
    }

    // Check if this tooltip should be displayed
    val shouldShowTooltip = showTooltip && TooltipState.activeTooltipId == buttonId

    TooltipArea(
        tooltip = {
            if (shouldShowTooltip) TooltipBubble(text = tooltip)
        },
        delayMillis = 0, // We handle delay manually now
        // The hover watch belongs OUT here, on the tooltip's own box, not on the button's modifier. Inside,
        // `Modifier.size(16.dp).hoverable(…)` puts the hover node OUTSIDE Material3's
        // `minimumInteractiveComponentSize`, so it measures that node's 48dp — a hit area four times the
        // button, overhanging its neighbours, which is how a row of dense buttons came to eat each other's
        // clicks. Out here the box is the size the button was laid out at, so what is clickable is what is
        // drawn. See DenseIconRowClickTest and TooltipButtonHitAreaTest.
        modifier = Modifier.hoverable(interactionSource),
        tooltipPlacement =
            TooltipPlacement.ComponentRect(
                anchor = Alignment.BottomCenter,
                alignment = Alignment.BottomCenter,
                offset = DpOffset(0.dp, 4.dp),
            ),
    ) {
        IconButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            colors =
                IconButtonDefaults.iconButtonColors(
                    containerColor = if (isHovered) AppTheme.Colors.border else Color.Transparent,
                ),
            interactionSource = interactionSource,
        ) {
            content()
        }
    }
}

/**
 * **One pressed look for every toggle in the app.**
 *
 * The audit behind `docs/mockups/pane-grammar.html` found five state languages among the toggles: two said
 * on by swapping their glyph, four by tinting it, one by changing its own label, one by filling its
 * background, and one was not a toggle at all while looking like one. Each reads on its own. Side by side
 * they disagree about what *on* means, and a tinted glyph says nothing at all to a reader who has never
 * seen the other state.
 *
 * On is a ground, a hairline, a full-strength glyph and a 2dp underline in the info blue — the stripe tab's
 * pressed look, one size down. Off is a dim glyph on nothing. Disabled is dimmer still, with the reason in
 * the tooltip rather than a button that does nothing when pressed.
 *
 * **The ground is the border colour, not the surface colour.** Learned in the toolbar build, where a
 * surface-coloured fill on a surface-coloured bar was invisible: every bar a toggle sits in is already
 * `surface` or `surfaceHeader`, so the pressed ground has to be the lighter one. The layout segments and
 * the old funnel button both do it this way.
 *
 * The state reaches three readers, because three ask differently: the ground and the bar for an eye,
 * `selected` for a test, and `stateDescription` for a screen reader. And the tooltip is the **noun alone** —
 * "Wrap lines", not "Wrap: On (click to unwrap)" — because the button now says on by looking on, which is
 * what frees the tooltip to name the thing instead of explaining the mechanism.
 */
@Composable
@Suppress("LongParameterList")
fun ToggleIconButton(
    on: Boolean,
    /** The noun. The pressed look carries the state, so this never says "On" or "click to". */
    tooltip: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** Said instead of the noun when the toggle is refused, so a dark control carries its reason. */
    disabledReason: String? = null,
    size: Dp = 24.dp,
    glyph: Dp = 16.dp,
    tag: String? = null,
) {
    val tint =
        when {
            !enabled -> AppTheme.Colors.textDisabled
            on -> AppTheme.Colors.text
            else -> AppTheme.Colors.textSecondary
        }
    val hover = if (!enabled && disabledReason != null) disabledReason else tooltip
    AppTooltip(text = hover) {
        Box(
            modifier =
                modifier
                    .size(size)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (on && enabled) AppTheme.Colors.border else Color.Transparent)
                    .pressedUnderline(on && enabled)
                    // `toggleable` rather than `clickable`, because it is what publishes a ToggleableState:
                    // a tinted glyph told a test nothing at all, and `assertIsOn` is the assertion a toggle
                    // should answer. `selected` goes on as well, which is what the stripe tabs publish.
                    .toggleable(value = on, enabled = enabled, onValueChange = { onClick() })
                    .semantics {
                        contentDescription = hover
                        selected = on
                        stateDescription = if (on) "on" else "off"
                        if (!enabled) disabled()
                    }.then(if (tag != null) Modifier.testTag(tag) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(glyph))
        }
    }
}

/** The 2dp info-blue bar along the bottom edge of a pressed toggle, drawn rather than laid out. */
private fun Modifier.pressedUnderline(on: Boolean): Modifier =
    if (!on) {
        this
    } else {
        drawBehind {
            val thickness = TOGGLE_UNDERLINE.toPx()
            drawRect(
                color = AppTheme.Colors.info,
                topLeft = Offset(0f, size.height - thickness),
                size = Size(size.width, thickness),
            )
        }
    }

/** Two device-independent pixels, the same mark a pressed stripe tab wears on its outer edge. */
private val TOGGLE_UNDERLINE = 2.dp

/**
 * The app's tooltip, around anything. The slim controls (SlimButton and friends) carry no tooltip of their
 * own, which left the most consequential glyphs in the diff gutter — `«`, `±`, `×` — meaning whatever the
 * author guessed they meant.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppTooltip(
    text: String,
    modifier: Modifier = Modifier,
    /** For a tooltip whose content is FIX rather than prose — a template reads as one line or not at all. */
    monospace: Boolean = false,
    maxWidth: androidx.compose.ui.unit.Dp = 360.dp,
    content: @Composable () -> Unit,
) {
    TooltipArea(
        tooltip = { TooltipBubble(text = text, monospace = monospace, maxWidth = maxWidth) },
        delayMillis = 500,
        modifier = modifier,
        tooltipPlacement =
            TooltipPlacement.ComponentRect(
                anchor = Alignment.BottomCenter,
                alignment = Alignment.BottomCenter,
                offset = DpOffset(0.dp, 4.dp),
            ),
    ) {
        content()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TooltipFloatingActionButton(
    tooltip: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
    content: @Composable () -> Unit,
) {
    TooltipArea(
        tooltip = { TooltipBubble(text = tooltip) },
        delayMillis = 600,
        tooltipPlacement =
            TooltipPlacement.ComponentRect(
                anchor = Alignment.TopCenter,
                alignment = Alignment.TopCenter,
                offset = DpOffset(0.dp, (-4).dp),
            ),
    ) {
        FloatingActionButton(
            onClick = onClick,
            modifier = modifier,
            containerColor = containerColor,
            contentColor = contentColor,
        ) {
            content()
        }
    }
}

// No local color constants needed - all colors now use AppTheme.Colors

/**
 * **The one bubble every tooltip here draws, and the one place it is kept out of text selection.**
 *
 * A tooltip is a [Text] in a popup, which is its own layout root. Composed under a `SelectionContainer`
 * (the detail pane's field list is one) it inherits the container's selection registrar and is counted
 * among its selectables. The first thing a mouse press on any text in that container does is sort the
 * selectables by position relative to the container, and asking for the popup text's position there is
 * asking Compose to relate two roots that share no ancestor: `IllegalArgumentException: layouts are not
 * part of the same hierarchy`, the press dies, and the click under the pointer never fires. Nobody
 * drag-selects a tooltip, so [DisableSelection] costs nothing and takes the bubble out of the registrar
 * altogether. See TooltipInSelectionContainerTest.
 */
@Composable
private fun TooltipBubble(
    text: String,
    monospace: Boolean = false,
    maxWidth: Dp? = null,
) {
    DisableSelection {
        Text(
            text = text,
            modifier =
                Modifier
                    .shadow(4.dp, tooltipShape)
                    .background(AppTheme.Colors.border, tooltipShape)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .then(if (maxWidth != null) Modifier.widthIn(max = maxWidth) else Modifier),
            color = AppTheme.Colors.text,
            fontSize = 11.sp,
            fontFamily = if (monospace) FontFamily.Monospace else null,
        )
    }
}

// Shape constants
private val tooltipShape = RoundedCornerShape(4.dp)
