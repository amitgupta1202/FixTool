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
 * **The empty session area is the demo's front door.**
 *
 * The demo workspace exists for the person looking at this exact screen — a fresh install with nothing
 * to connect to — so the button that installs it lives here, not at the bottom of a profile form they
 * have not opened yet. Once the workspace is installed the button withdraws: its profiles are ordinary
 * rows in Quick Connect, and a second Start could only fail.
 *
 * Shared by the TABS and SPLIT layouts, which used to carry two copies of the same sentence.
 */
@Composable
fun NoSessionsPlaceholder(
    demoWorkspaceInstalled: Boolean = false,
    /** Installs the demo workspace at its default FIX version. Null hides the button. */
    onStartDemoWorkspace: (() -> Unit)? = null,
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
                    if (demoWorkspaceInstalled) {
                        "Reconnect a profile from Quick Connect in the toolbar, or open the connection panel."
                    } else {
                        "Connect a saved profile, or install the demo workspace: an FX venue, two clients, " +
                            "message templates and a scenario that runs green."
                    },
                color = AppTheme.Colors.textDisabled,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onStartDemoWorkspace != null && !demoWorkspaceInstalled) {
                    SlimButton(
                        text = "Start demo workspace",
                        onClick = onStartDemoWorkspace,
                        color = AppTheme.Colors.primary,
                        modifier = Modifier.testTag("empty-start-demo"),
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
