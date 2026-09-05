package com.knapsack.fixtool.ui

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionConfig.MessageLogKind
import com.knapsack.fixtool.model.FixConnectionConfig.MessageStoreKind
import com.knapsack.fixtool.model.FixConnectionProfile
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * **The store and log rows say what the profile says, and the hint says what the pair will do.**
 *
 * The one thing the hint has to get right is the refusal: a memory store on a profile whose Reset on
 * Logon is off is refused at connect, and reading that here beats reading it in an error toast after
 * the click.
 */
class ConnectionPanelStoreTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun profile(store: MessageStoreKind, log: MessageLogKind, resetOnLogon: Boolean) =
        FixConnectionProfile(
            id = "p-1",
            name = "LOADGEN",
            config =
                FixConnectionConfig(
                    senderCompID = "LOADGEN",
                    targetCompID = "VENUE",
                    host = "localhost",
                    port = "9876",
                    resetOnLogon = resetOnLogon,
                    messageStore = store,
                    messageLog = log,
                ),
        )

    private fun open(profile: FixConnectionProfile, onSave: (FixConnectionProfile) -> Unit = {}) {
        composeTestRule.setContent {
            ConnectionPanel(
                profiles = listOf(profile),
                sessions = emptyList(),
                onConnect = { _, _ -> },
                onDisconnect = { },
                onSaveProfile = onSave,
                onDeleteProfile = { },
                onCloneProfile = { it },
                onGetProfileSession = { null },
                onClose = { },
            )
        }
        composeTestRule.onNodeWithText("Select profile...").performClick()
        composeTestRule.onNodeWithText("LOADGEN").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Toggle Advanced").performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    fun `the rows reflect the loaded profile`() {
        open(profile(MessageStoreKind.MEMORY, MessageLogKind.NONE, resetOnLogon = true))

        composeTestRule.onNodeWithTag("store-memory").assertIsSelected()
        composeTestRule.onNodeWithTag("log-none").assertIsSelected()
        composeTestRule.onNodeWithTag("store-hint").assertTextContains("start at 1", substring = true)
    }

    @Test
    fun `choosing Memory with Reset on Logon off shows the refusal in the config's own words`() {
        val loaded = profile(MessageStoreKind.FILE, MessageLogKind.FILE, resetOnLogon = false)
        open(loaded)

        composeTestRule.onNodeWithTag("store-memory").performClick()
        composeTestRule.waitForIdle()

        val expected = loaded.config.copy(messageStore = MessageStoreKind.MEMORY).storeProblem()
        composeTestRule.onNodeWithTag("store-hint").assertTextContains(assertNotNull(expected))
    }

    @Test
    fun `saving writes the two choices into the profile`() {
        var saved: FixConnectionProfile? = null
        open(profile(MessageStoreKind.FILE, MessageLogKind.FILE, resetOnLogon = true)) { saved = it }

        composeTestRule.onNodeWithTag("store-memory").performClick()
        composeTestRule.onNodeWithTag("log-none").performClick()
        composeTestRule.onNodeWithContentDescription("Save Profile").performClick()

        val config = assertNotNull(saved).config
        assertEquals(MessageStoreKind.MEMORY, config.messageStore)
        assertEquals(MessageLogKind.NONE, config.messageLog)
    }
}
