package com.knapsack.fixtool.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * **The empty session area, and the three things someone looking at it might want.**
 *
 * Connect something they already have, open a workspace from somewhere else, or — on a genuinely
 * fresh install — take the bundled example. The last of those is offered on **no profiles**, not on
 * "no workspace open": someone sitting on Default with eleven saved profiles and nothing connected is
 * not a fresh install, and offering them the example is noise in the one place that should be a
 * signpost.
 *
 * The example is named rather than given a verb of its own, because it is the same thing the
 * workspace switcher offers under Open — reached in one click instead of three.
 *
 * Shared by the TABS and SPLIT layouts, which used to carry two copies of the same sentence.
 */
@Composable
fun NoSessionsPlaceholder(
    /** There are saved profiles, so this is not a fresh install and the example withdraws. */
    hasProfiles: Boolean = false,
    /** Copies the bundled example into a workspace and opens it. Null hides the button. */
    onOpenExample: (() -> Unit)? = null,
    /** Browses to a workspace folder. Null hides the button. */
    onOpenWorkspace: (() -> Unit)? = null,
    /** Opens (never toggles) the connection panel. Null hides the button. */
    onOpenConnectionPanel: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.widthIn(max = 420.dp).padding(16.dp),
        ) {
            Text(
                text = "No active sessions",
                color = AppTheme.Colors.textDisabled,
                fontSize = 14.sp,
            )
            Text(
                text =
                    if (hasProfiles) {
                        "Reconnect a profile from Quick Connect in the toolbar, or open another workspace."
                    } else {
                        "Open a workspace, or take the bundled FX Venue example: a venue, two clients, " +
                            "message templates and scenarios that run green, copied into a workspace of your own."
                    },
                color = AppTheme.Colors.textDisabled,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onOpenExample != null && !hasProfiles) {
                    SlimButton(
                        text = "Open FX Venue example",
                        onClick = onOpenExample,
                        color = AppTheme.Colors.primary,
                        modifier = Modifier.testTag("empty-open-example"),
                    )
                }
                if (onOpenWorkspace != null) {
                    SlimButton(
                        text = "Open workspace…",
                        onClick = onOpenWorkspace,
                        modifier = Modifier.testTag("empty-open-workspace"),
                    )
                }
                if (onOpenConnectionPanel != null) {
                    SlimButton(
                        text = "Open connection panel",
                        onClick = onOpenConnectionPanel,
                        modifier = Modifier.testTag("empty-open-connection"),
                    )
                }
            }
        }
    }
}
