package com.knapsack.fixtool.ui

import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * **Two ways to ask before something is lost, and never a third.**
 *
 * The audit found twenty-two actions that destroy something behind one unguarded click. What is asserted
 * here is the shape of the answer: the question is asked *in place*, the row it is about is still there
 * around it, Cancel leaves everything alone, and nothing is ever drawn over the window.
 */
class LosingSomethingTest {
    @get:Rule
    val rule = createComposeRule()

    private fun render(onConfirm: () -> Unit = {}) {
        rule.setContent {
            var armed by remember { mutableStateOf(false) }
            InlineConfirm(
                armed = armed,
                onConfirm = {
                    armed = false
                    onConfirm()
                },
                onCancel = { armed = false },
                tag = "confirm",
            ) {
                Text("Delete profile", modifier = Modifier.testTag("delete").clickable { armed = true })
            }
        }
        rule.waitForIdle()
    }

    /** Until it is asked, the row draws its own controls and nothing else. */
    @Test
    fun `the row shows its own controls while nothing is being asked`() {
        render()

        rule.onNodeWithTag("delete").assertIsDisplayed()
        rule.onNodeWithTag("confirm").assertDoesNotExist()
        rule.onNodeWithTag("confirm-cancel").assertDoesNotExist()
    }

    /**
     * **The question takes the place of the control that asked it.**
     *
     * In the row, never over the window: the thing being deleted is still on screen around the question,
     * which is the whole reason this is not a dialog. A modal would cover the one piece of evidence that
     * tells you whether you picked the right row.
     */
    @Test
    fun `asking replaces the row's controls with Delete and Cancel, in place`() {
        render()

        rule.onNodeWithTag("delete").performClick()
        rule.waitForIdle()

        rule.onNodeWithTag("confirm").assertIsDisplayed()
        rule.onNodeWithTag("confirm-cancel").assertIsDisplayed()
        rule.onNodeWithTag("delete").assertDoesNotExist()
    }

    @Test
    fun `the second click is the answer`() {
        var deleted = 0
        render(onConfirm = { deleted++ })

        rule.onNodeWithTag("delete").performClick()
        rule.waitForIdle()
        assertEquals(0, deleted, "asking is not doing")

        rule.onNodeWithTag("confirm").performClick()
        rule.waitForIdle()
        assertEquals(1, deleted)
    }

    /** And Cancel puts the row back with nothing lost. */
    @Test
    fun `Cancel restores the row and destroys nothing`() {
        var deleted = 0
        render(onConfirm = { deleted++ })

        rule.onNodeWithTag("delete").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("confirm-cancel").performClick()
        rule.waitForIdle()

        assertEquals(0, deleted)
        rule.onNodeWithTag("delete").assertIsDisplayed()
        rule.onNodeWithTag("confirm").assertDoesNotExist()
    }

    /**
     * **An armed control starts disarmed, and its clock is keyed.**
     *
     * A key that moves is a question about something that has changed underneath it — an armed Close all
     * whose pane count moved, an armed Delete on a row that scrolled away. The countdown restarts rather
     * than leaving the old question standing over a new subject.
     */
    @Test
    fun `rememberArmed starts disarmed and forgets when its subject changes`() {
        var subject by mutableStateOf("profile-a")
        rule.setContent {
            val armed = rememberArmed(subject)
            Text(
                if (armed.value) "armed" else "idle",
                modifier = Modifier.testTag("state").clickable { armed.value = true },
            )
        }

        rule.onNodeWithTag("state").assertTextEquals("idle")
        rule.onNodeWithTag("state").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("state").assertTextEquals("armed")

        // The row it was about is gone; the question goes with it.
        rule.runOnIdle { subject = "profile-b" }
        rule.waitForIdle()
        rule.onNodeWithTag("state").assertTextEquals("idle")
    }
}
