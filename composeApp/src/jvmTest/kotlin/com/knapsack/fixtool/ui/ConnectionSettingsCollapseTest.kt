package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.ResponseStep
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * Folding Connection Settings must not fold away the reason the panel is unusable.
 *
 * The section holds the CompIDs and the port, and `Connect` is disabled until they are filled in.
 * Collapsing it to give the auto-response rules the panel's length therefore introduced a way to sit
 * in front of a dead Connect button with its cause hidden inside the very thing that was folded. The
 * summary line is the answer, and it is what these pin.
 */
class ConnectionSettingsCollapseTest {
    @get:Rule
    val composeTestRule = createComposeRule()
    private val outDir = File("build/scenario-screenshots").absoluteFile

    private fun acceptor(sender: String = "VTACC", port: String = "19915") =
        FixConnectionProfile(
            name = "ACC",
            config =
                FixConnectionConfig(
                    connectionType = FixConnectionConfig.ConnectionType.ACCEPTOR,
                    senderCompID = sender,
                    targetCompID = "VTCLI",
                    socketAcceptPort = port,
                    beginString = "FIX.4.4",
                    acceptorResponseRules =
                        listOf(
                            AcceptorResponseRule(
                                whenMsgType = "D",
                                steps = listOf(ResponseStep(template = "35=8|150=0|39=0|11=\${req.11}|")),
                            ),
                        ),
                ),
        )

    private fun renderCollapsed(profile: FixConnectionProfile) {
        composeTestRule.setContent {
            Box(modifier = Modifier.size(360.dp, 900.dp).background(AppTheme.Colors.background)) {
                ConnectionPanel(
                    profiles = listOf(profile),
                    sessions = emptyList(),
                    onConnect = { _, _ -> },
                    onDisconnect = {},
                    onSaveProfile = {},
                    onDeleteProfile = {},
                    onCloneProfile = { it },
                    onGetProfileSession = { null },
                    onClose = {},
                    // The control-surface door, which is also the only way a test can put a profile on the form.
                    selectionRequest = profile.name,
                )
            }
        }
        composeTestRule.onNodeWithText("Connection Settings").performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    fun `collapsed, the section still says who this profile is and where it listens`() {
        renderCollapsed(acceptor())

        composeTestRule.onNodeWithText("VTACC → VTCLI · listening on 19915").assertExists()
        // And the section really is folded — a field from inside it is gone.
        composeTestRule.onNodeWithText("SenderCompID").assertDoesNotExist()
        snapshot("connection_settings_collapsed.png")
    }

    /**
     * The case the summary exists for: a profile that cannot connect, folded. The gap is named `?`
     * rather than omitted, so what is missing is what draws the eye.
     */
    @Test
    fun `a profile that cannot connect shows its gap rather than hiding it`() {
        renderCollapsed(acceptor(sender = "", port = ""))

        composeTestRule.onNodeWithText("? → VTCLI · listening on ?").assertExists()
        snapshot("connection_settings_collapsed_invalid.png")
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun snapshot(name: String) {
        try {
            outDir.mkdirs()
            ImageIO.write(composeTestRule.onRoot().captureToImage().toAwtImage(), "png", File(outDir, name))
        } catch (e: Exception) {
            println("[ConnectionSettingsCollapseTest] snapshot '$name' skipped: ${e.message}")
        }
    }
}
