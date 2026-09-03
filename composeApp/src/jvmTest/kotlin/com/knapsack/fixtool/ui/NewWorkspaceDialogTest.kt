package com.knapsack.fixtool.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals

/**
 * Two questions, both pre-answered, and one honest note.
 *
 * The note matters as much as the fields: a FIX version used to be asked for here and the answer was
 * ignored, because a loaded dictionary overrides a profile's BeginString at connect time. Saying what
 * the sessions will speak, and where that is decided, is the truthful replacement for a control that
 * did nothing.
 */
class NewWorkspaceDialogTest {
    @get:Rule
    val rule = createComposeRule()

    private val location = Files.createTempDirectory("new-workspace-dialog").toFile()

    @Test
    fun `the defaults are answers, so create is reachable without typing`() {
        var created: Pair<String, File>? = null
        rule.setContent {
            NewWorkspaceDialog(
                defaultLocation = location,
                onDismiss = { },
                onCreate = { name, where -> created = name to where },
            )
        }

        rule.onNodeWithTag("new-workspace-create").performClick()
        assertEquals("Workspace" to location, created)
    }

    @Test
    fun `the target folder is shown before anything is written`() {
        rule.setContent {
            NewWorkspaceDialog(defaultLocation = location, onDismiss = { }, onCreate = { _, _ -> })
        }

        rule.onNodeWithTag("new-workspace-name").performTextClearance()
        rule.onNodeWithTag("new-workspace-name").performTextInput("My Venue")
        rule.onNodeWithText("Goes in ${File(location, "my-venue").absolutePath}").assertExists()
    }

    @Test
    fun `a blank name cannot be created, because it would name the folder nothing`() {
        var created = false
        rule.setContent {
            NewWorkspaceDialog(defaultLocation = location, onDismiss = { }, onCreate = { _, _ -> created = true })
        }

        rule.onNodeWithTag("new-workspace-name").performTextClearance()
        rule.onNodeWithTag("new-workspace-create").performClick()
        assertEquals(false, created)
    }

    @Test
    fun `the dialog says what the sessions will speak, rather than pretending to ask`() {
        rule.setContent {
            NewWorkspaceDialog(
                defaultLocation = location,
                wireVersionNote = "Sessions will speak FIX 4.4, from the dictionary in Settings -> Protocol.",
                onDismiss = { },
                onCreate = { _, _ -> },
            )
        }

        rule.onNodeWithTag("new-workspace-wire-version").assertExists()
        rule.onNodeWithText("Sessions will speak FIX 4.4, from the dictionary in Settings -> Protocol.").assertExists()
    }

    @Test
    fun `with nothing to say about the wire version the note is absent, not blank`() {
        rule.setContent {
            NewWorkspaceDialog(defaultLocation = location, onDismiss = { }, onCreate = { _, _ -> })
        }

        rule.onNodeWithTag("new-workspace-wire-version").assertDoesNotExist()
    }
}
