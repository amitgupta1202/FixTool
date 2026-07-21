package com.knapsack.fixtool.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.TagRole
import com.knapsack.fixtool.model.TagRoleOverlay
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The roles editor. Its whole reason for existing is that the sidecar is unwritable in practice — you
 * would have to know the mechanism exists before you could look for it — so the assertions here are about
 * **what the author is shown**, not about styling.
 */
class VenueTagRolesDialogTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder()

    private var n = 0

    private fun venueDictionary(rolesJson: String? = null): FixDictionaryAdapter {
        val stem = "venue-ui-${n++}"
        val xml = temp.newFile("$stem.xml")
        xml.writeText(
            """
            <fix major="4" minor="4" type="FIX">
              <header><field name="BeginString" required="Y"/></header>
              <trailer><field name="CheckSum" required="Y"/></trailer>
              <messages>
                <message name="QuoteRequest" msgtype="R" msgcat="app">
                  <field name="QuoteReqID" required="N"/>
                </message>
              </messages>
              <fields>
                <field number="8" name="BeginString" type="STRING"/>
                <field number="10" name="CheckSum" type="STRING"/>
                <field number="35" name="MsgType" type="STRING"/>
                <field number="131" name="QuoteReqID" type="STRING"/>
                <field number="20013" name="LegQuoteReqID" type="STRING"/>
                <field number="20050" name="Note1" type="STRING"/>
              </fields>
            </fix>
            """.trimIndent(),
        )
        rolesJson?.let { temp.newFile("$stem.xml.roles.json").writeText(it) }
        return FixDictionaryAdapter.fromFile(xml)
    }

    @Test
    fun `the venue's id-shaped tags are on screen, named`() {
        composeTestRule.setContent {
            VenueTagRolesDialog(dictionary = venueDictionary(), onSaved = {}, onDismiss = {})
        }

        composeTestRule.onNodeWithTag("venue-tag-row-20013").assertIsDisplayed()
        composeTestRule.onNodeWithText("LegQuoteReqID").assertIsDisplayed()
    }

    /**
     * A tier is an ordering, never a filter: a venue correlation id whose name does not end in `ID` is
     * exactly what a filter would lose, so the rest must be reachable rather than merely absent.
     */
    @Test
    fun `everything else is one click away, not gone`() {
        composeTestRule.setContent {
            VenueTagRolesDialog(dictionary = venueDictionary(), onSaved = {}, onDismiss = {})
        }

        composeTestRule.onNodeWithTag("venue-tag-row-20050").assertDoesNotExist()
        composeTestRule.onNodeWithTag("venue-tag-roles-show-all").performClick()
        composeTestRule.onNodeWithTag("venue-tag-row-20050").assertIsDisplayed()
    }

    /** An answer the author cannot see is an answer they cannot revise, so declared tags come back. */
    @Test
    fun `an existing declaration is shown, not silently re-asked`() {
        composeTestRule.setContent {
            VenueTagRolesDialog(
                dictionary = venueDictionary("""{"20050":"CLIENT_MINTED_ID"}"""),
                onSaved = {},
                onDismiss = {},
            )
        }

        // 20050 is `Note1` — not id-shaped, so it is only on screen because it was DECLARED.
        composeTestRule.onNodeWithTag("venue-tag-row-20050").assertIsDisplayed()
        composeTestRule.onNodeWithText("we mint it — fresh per run").assertIsDisplayed()
    }

    @Test
    fun `saving writes the sidecar beside the dictionary and keeps prior declarations`() {
        val dictionary = venueDictionary("""{"20050":"CLIENT_MINTED_ID"}""")
        var saved: String? = null
        composeTestRule.setContent {
            VenueTagRolesDialog(dictionary = dictionary, onSaved = { saved = it }, onDismiss = {})
        }

        composeTestRule.onNodeWithText("Save").performClick()
        composeTestRule.waitForIdle()

        assertTrue(saved != null, "the editor must report where it wrote")
        val overlay = TagRoleOverlay.read(java.io.File(saved!!).readText())
        assertEquals(setOf(TagRole.CLIENT_MINTED_ID), overlay.rolesOf(20050))
    }

    /**
     * A bundled dictionary is extracted to a temp file, so a sidecar written beside it would not survive a
     * restart — and it has no venue tags to declare anyway. Say that rather than showing an empty list.
     */
    @Test
    fun `a dictionary with no file says so instead of offering an empty list`() {
        composeTestRule.setContent {
            VenueTagRolesDialog(dictionary = null, onSaved = {}, onDismiss = {})
        }

        composeTestRule.onNodeWithText("No dictionary file is loaded", substring = true).assertIsDisplayed()
    }
}
