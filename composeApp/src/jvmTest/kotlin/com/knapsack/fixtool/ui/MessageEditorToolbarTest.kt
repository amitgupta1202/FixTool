package com.knapsack.fixtool.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.knapsack.fixtool.model.FixDictionary
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The editor's toolbar, which had no test at all.**
 *
 * That is how 1,094 lines of it could be replaced with the suite staying green — and it is why the two
 * copies of every button had drifted unnoticed: the row's Validate said "Validate Message against Data
 * Dictionary" where the hidden overflow copy said "Validate", and one said "Requires FIX data dictionary"
 * where the other said "Validation disabled". Nothing was watching.
 *
 * So what is asserted here is what the toolbar promises: the three sending actions carry their words, Send
 * never folds, the two dialogs open whether or not their buttons are drawn, and Validate reports a count
 * rather than tinting itself.
 */
class MessageEditorToolbarTest {
    @get:Rule
    val rule = createComposeRule()

    /**
     * The bundled FIX 4.4, because Validate is refused without a dictionary — correctly, and that refusal
     * is asserted separately. `createDefault()` is an adapter with no dictionary at all.
     */
    private val loadedDictionary = FixDictionary.fromResource()

    private val order =
        listOf(
            FixField(tag = "35", value = "D"),
            FixField(tag = "11", value = "ORD-1"),
            FixField(tag = "55", value = "EUR/USD"),
        )

    /**
     * @param validationFinds what `onValidate` answers with, so the badge can be driven from a test
     */
    private fun render(
        width: Int = 900,
        fields: List<FixField> = order,
        dictionary: FixDictionary = loadedDictionary,
        validationFinds: List<String> = emptyList(),
        onSend: (List<FixField>) -> Unit = {},
        onLoad: ((List<FixField>) -> Unit)? = {},
        onSaveMessage: ((String, List<FixField>, String, Set<String>) -> Unit)? = { _, _, _, _ -> },
    ) {
        rule.setContent {
            var live by remember { mutableStateOf(fields) }
            Box(Modifier.width(width.dp)) {
                MessageEditorPanel(
                    sessions = emptyList(),
                    selectedSession = null,
                    dictionary = dictionary,
                    fields = live,
                    selectedFieldIndex = 0,
                    onFieldUpdate = { i, f -> live = live.toMutableList().also { it[i] = f } },
                    onFieldAdd = { live = live + FixField() },
                    onFieldDelete = {},
                    onFieldMoveUp = {},
                    onFieldMoveDown = {},
                    onFieldSelect = { _, _, _ -> },
                    onClearFields = {},
                    onClose = {},
                    onSend = onSend,
                    onSendToAll = {},
                    onLoad = onLoad,
                    onValidate = { validationFinds },
                    validationErrors = emptyList(),
                    onClearValidationErrors = {},
                    onSaveMessage = onSaveMessage,
                )
            }
        }
        rule.waitForIdle()
    }

    /**
     * **The three things a person came to the editor to do are words**, the way Run and Quick Connect are
     * words on the toolbar. Field editing stays glyphs: those five act on the selected row and get pressed
     * in bursts while the eye is on the field list rather than on the button.
     */
    @Test
    fun `a wide toolbar gives Send, Send to all and Load run their words`() {
        render(width = 1000)

        rule.onNodeWithTag("editor-send").assertTextContains("Send")
        rule.onNodeWithTag("editor-send-all").assertTextContains("Send to all", substring = true)
        rule.onNodeWithTag("editor-load").assertTextContains("Load run")
        // And the field buttons are glyphs at every width.
        rule.onNodeWithTag("editor-add-field").assertIsDisplayed()
        rule.onNodeWithTag("editor-move-up").assertIsDisplayed()
        rule.onNodeWithTag("editor-overflow").assertDoesNotExist()
    }

    /**
     * **Send never folds, and the view toggles fold first.**
     *
     * The fold order is the note's table, declared rather than positional. A toolbar that folded Send would
     * be an editor you cannot send from, at exactly the width where the panel is narrow because you have
     * given the room to the message grid you are about to send.
     */
    @Test
    fun `a narrow toolbar folds the view toggles first and never Send`() {
        render(width = 300)

        rule.onNodeWithTag("editor-send").assertIsDisplayed()
        rule.onNodeWithTag("editor-overflow").assertIsDisplayed()
        rule.onNodeWithTag("editor-indent").assertDoesNotExist()

        // Folded is not gone: the ⋯ carries the word, and a pressed toggle keeps its state as a tick.
        rule.onNodeWithTag("editor-overflow").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("editor-indent-menu").assertIsDisplayed()
    }

    /**
     * **A folded Save still opens its dialog.**
     *
     * The dialog used to be three hundred lines nested inside the Save button's own `onClick`, so it
     * existed only while that button was drawn — and Save is one of the first things the bar folds. This is
     * the case the lift-out exists for.
     */
    @Test
    fun `Save opens its dialog from the overflow when the bar has folded it`() {
        render(width = 300)

        rule.onNodeWithTag("editor-overflow").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("editor-save-template-menu").assertIsDisplayed().performClick()
        rule.waitForIdle()

        rule.onNodeWithTag("editor-save-template-dialog").assertIsDisplayed()
        rule.onNodeWithText("Save message template").assertExists()
    }

    /**
     * **Validate reports a count, beside Send, rather than tinting its own glyph.**
     *
     * A colour can say "passed" and cannot say "three errors", which is why the tint was a control
     * pretending to be a toggle. And there is no badge at all until Validate is pressed: a verdict nobody
     * asked for is furniture.
     */
    @Test
    fun `the badge says nothing until Validate is pressed, then says how many`() {
        render(validationFinds = listOf("35 is required", "11 is required", "55 is unknown"))

        rule.onNodeWithTag("editor-validation-badge").assertDoesNotExist()

        rule.onNodeWithTag("editor-validate").performClick()
        rule.waitForIdle()

        rule.onNodeWithTag("editor-validation-badge").assertTextContains("3 errors")
    }

    @Test
    fun `a message that validates clean says Valid`() {
        render(validationFinds = emptyList())

        rule.onNodeWithTag("editor-validate").performClick()
        rule.waitForIdle()

        rule.onNodeWithTag("editor-validation-badge").assertTextContains("Valid")
    }

    /**
     * **And the verdict goes when the message changes**, because it was a verdict about a message that no
     * longer exists. This is the half a boolean got wrong too, and the reason the state has three values
     * rather than two.
     */
    @Test
    fun `editing a field withdraws the verdict`() {
        render(validationFinds = emptyList())

        rule.onNodeWithTag("editor-validate").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("editor-validation-badge").assertTextContains("Valid")

        rule.onNodeWithTag("editor-add-field").performClick()
        rule.waitForIdle()

        rule.onNodeWithTag("editor-validation-badge").assertDoesNotExist()
    }

    /**
     * **A refused Send says which of its three refusals it is.**
     *
     * Nothing to send, nothing to send it on, or no MsgType. They were three branches inside the button's
     * own click handler, reachable only by pressing a button that then answered in a banner; the reason is
     * on the control now, where somebody hovering a dark button is asking.
     */
    @Test
    fun `Send is refused by name when there is no session`() {
        var sent = 0
        render(onSend = { sent++ })

        rule.onNodeWithTag("editor-send").assertIsNotEnabled()
        rule.onNodeWithTag("editor-send").assertContentDescriptionContains("not logged on", substring = true)
        rule.onNodeWithTag("editor-send").performClick()
        rule.waitForIdle()

        assertEquals(0, sent, "a refused Send sends nothing")
    }

    /** Load run needs a MsgType and a correlation tag, and says so rather than going dark in silence. */
    @Test
    fun `Load run is refused by name when the message has no correlation tag`() {
        render(fields = listOf(FixField(tag = "35", value = "D")))

        rule.onNodeWithTag("editor-load").assertIsNotEnabled()
        rule.onNodeWithTag("editor-load").assertContentDescriptionContains("correlation tag", substring = true)
    }

    /** Every action is either drawn or in the ⋯, at every width — nothing is reachable at one and gone at another. */
    @Test
    fun `no action disappears at any width`() {
        val everyAction =
            listOf(
                "editor-send",
                "editor-send-all",
                "editor-load",
                "editor-validate",
                "editor-open-template",
                "editor-save-template",
                "editor-add-field",
                "editor-delete-field",
                "editor-move-up",
                "editor-move-down",
                "editor-clear-fields",
                "editor-indent",
                "editor-description",
            )
        render(width = 260)

        rule.onNodeWithTag("editor-overflow").performClick()
        rule.waitForIdle()

        val reachable =
            everyAction.count { tag ->
                rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() ||
                    rule.onAllNodesWithTag("$tag-menu").fetchSemanticsNodes().isNotEmpty()
            }
        // Open template is absent with no saved messages, so twelve of the thirteen are offered here.
        assertTrue(reachable >= 12, "only $reachable of ${everyAction.size} actions were reachable at 260dp")
    }
}
