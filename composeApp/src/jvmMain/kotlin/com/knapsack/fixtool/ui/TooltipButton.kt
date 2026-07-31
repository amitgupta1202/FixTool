package com.knapsack.fixtool.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
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
            if (shouldShowTooltip) {
                Text(
                    text = tooltip,
                    modifier =
                        Modifier
                            .shadow(4.dp, tooltipShape)
                            .background(AppTheme.Colors.border, tooltipShape)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    color = AppTheme.Colors.text,
                    fontSize = 11.sp,
                )
            }
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
        tooltip = {
            Text(
                text = text,
                modifier =
                    Modifier
                        .shadow(4.dp, tooltipShape)
                        .background(AppTheme.Colors.border, tooltipShape)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .widthIn(max = maxWidth),
                color = AppTheme.Colors.text,
                fontSize = 11.sp,
                fontFamily = if (monospace) androidx.compose.ui.text.font.FontFamily.Monospace else null,
            )
        },
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
        tooltip = {
            Text(
                text = tooltip,
                modifier =
                    Modifier
                        .shadow(4.dp, tooltipShape)
                        .background(AppTheme.Colors.border, tooltipShape)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                color = AppTheme.Colors.text,
                fontSize = 11.sp,
            )
        },
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

// Shape constants
private val tooltipShape = RoundedCornerShape(4.dp)
