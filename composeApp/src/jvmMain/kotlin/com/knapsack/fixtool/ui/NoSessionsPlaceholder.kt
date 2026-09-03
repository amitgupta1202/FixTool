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
 * **The empty session area is the bundled example's front door.**
 *
 * The example exists for the person looking at this exact screen — a fresh install with nothing to
 * connect to — so the shortcut to it lives here, not at the bottom of a profile form they have not
 * opened yet. It names the example rather than inventing a verb for it: this is the same thing Quick
 * Connect offers under Open workspace, reached in one click instead of three.
 *
 * Once a workspace is open the button withdraws, because its profiles are ordinary rows in Quick
 * Connect and the way to another workspace is that menu.
 *
 * Shared by the TABS and SPLIT layouts, which used to carry two copies of the same sentence.
 */
@Composable
fun NoSessionsPlaceholder(
    /** A project workspace is open, so the offer to open an example withdraws. */
    workspaceOpen: Boolean = false,
    /** Offers the bundled example, through the dialog that names and places the copy. Null hides the button. */
    onOpenExample: (() -> Unit)? = null,
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
                    if (workspaceOpen) {
                        "Reconnect a profile from Quick Connect in the toolbar, or open the connection panel."
                    } else {
                        "Connect a saved profile, or open the bundled FX Venue example: a venue, two clients, " +
                            "message templates and scenarios that run green, copied into a workspace of your own."
                    },
                color = AppTheme.Colors.textDisabled,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onOpenExample != null && !workspaceOpen) {
                    SlimButton(
                        text = "Open FX Venue example",
                        onClick = onOpenExample,
                        color = AppTheme.Colors.primary,
                        modifier = Modifier.testTag("empty-open-example"),
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
