package com.knapsack.fixtool.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.Counterparty
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.ResponseStep
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **A venue's counterparties survive every way the connection panel moves a profile.**
 *
 * The panel keeps one piece of state per field and copies fields by name at six places — load, save, connect,
 * clone, reset, and the editor itself. A field missing from any one of them is a list that vanishes on that path
 * and nowhere else, which is exactly how the acceptor rules were once lost on the first Save. So each path is
 * driven here, through the panel, and the list is read off what the panel hands back.
 */
class CounterpartiesEditorTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val declared =
        listOf(
            Counterparty("FIBUY1", "requester"),
            Counterparty("FIDLR1", "responder"),
        )

    private fun venueProfile(counterparties: List<Counterparty> = declared) =
        FixConnectionProfile(
            id = "venue-1",
            name = "RFQ VENUE",
            config =
                FixConnectionConfig(
                    connectionType = FixConnectionConfig.ConnectionType.ACCEPTOR,
                    senderCompID = "FIRFQ",
                    targetCompID = FixConnectionConfig.ANY_CLIENT,
                    port = "19880",
                    socketAcceptPort = "19880",
                    beginString = "FIX.4.4",
                    heartBtInt = "30",
                    counterparties = counterparties,
                    rfqExpirySeconds = 60,
                ),
        )

    private var saved: FixConnectionProfile? = null
    private var connected: FixConnectionProfile? = null
    private var deleted: String? = null

    private fun open(
        profile: FixConnectionProfile,
        onClone: (FixConnectionProfile) -> FixConnectionProfile = { it },
    ) {
        composeTestRule.setContent {
            ConnectionPanel(
                profiles = listOf(profile),
                sessions = emptyList(),
                onConnect = { _, p -> connected = p },
                onDisconnect = { },
                onSaveProfile = { saved = it },
                onDeleteProfile = { deleted = it },
                onCloneProfile = onClone,
                onGetProfileSession = { null },
                onClose = { },
            )
        }
        composeTestRule.onNodeWithText("Select profile...").performClick()
        composeTestRule.onNodeWithText(profile.name).performClick()
        composeTestRule.waitForIdle()
    }

    private fun save() {
        composeTestRule.onNodeWithContentDescription("Save profile").performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    fun `a loaded venue's list is saved as it was, beside the fields the panel does not edit`() {
        open(venueProfile())

        save()

        val config = assertNotNull(saved).config
        assertEquals(declared, config.counterparties)
        assertEquals(60, config.rfqExpirySeconds, "the RFQ expiry is loaded with the list and saved with it")
    }

    /** How long an RFQ stays open when its request does not say: the field beside the parties, loaded and saved. */
    @Test
    fun `the RFQ expiry is edited beside the counterparties, and blank saves as never`() {
        open(venueProfile())
        composeTestRule.onNodeWithTag("rfq-expiry-seconds").performScrollTo().performTextClearance()
        composeTestRule.onNodeWithTag("rfq-expiry-seconds").performTextInput("45s")
        composeTestRule.waitForIdle()
        save()
        assertEquals(45, assertNotNull(saved).config.rfqExpirySeconds, "digits only: a trailing unit is not a number")

        composeTestRule.onNodeWithTag("rfq-expiry-seconds").performScrollTo().performTextClearance()
        composeTestRule.waitForIdle()
        save()
        assertNull(assertNotNull(saved).config.rfqExpirySeconds)
    }

    @Test
    fun `a clone keeps the RFQ expiry`() {
        open(venueProfile()) { original -> original.copy(id = "clone", name = "RFQ VENUE (Copy)") }
        composeTestRule.onNodeWithContentDescription("Clone profile").performClick()
        composeTestRule.waitForIdle()
        save()
        assertEquals(60, assertNotNull(saved).config.rfqExpirySeconds)
    }

    @Test
    fun `a counterparty added in the panel is saved and connected with`() {
        open(venueProfile())

        composeTestRule.onNodeWithTag("counterparty-add").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("counterparty-compid-2").performScrollTo().performTextInput("FIDLR2")
        composeTestRule.waitForIdle()

        save()
        val expected = declared + Counterparty("FIDLR2", "responder")
        assertEquals(expected, assertNotNull(saved).config.counterparties)

        composeTestRule.onNodeWithText("Start listening").performClick()
        composeTestRule.waitForIdle()
        assertEquals(expected, assertNotNull(connected).config.counterparties, "the live venue must get the list on screen")
    }

    @Test
    fun `a clone's list is the one the form shows and saves`() {
        val cloneList = listOf(Counterparty("FIBUY9", "requester"))
        open(venueProfile()) { original ->
            original.copy(id = "clone", name = "RFQ VENUE (Copy)", config = original.config.copy(counterparties = cloneList))
        }

        composeTestRule.onNodeWithContentDescription("Clone profile").performClick()
        composeTestRule.waitForIdle()
        save()

        assertEquals(cloneList, assertNotNull(saved).config.counterparties)
    }

    @Test
    fun `deleting the loaded profile empties the list, so the next profile does not inherit it`() {
        open(venueProfile())

        composeTestRule.onNodeWithTag("connection-delete-profile").performClick()
        composeTestRule.onNodeWithTag("connection-delete-profile-confirm").performClick()
        composeTestRule.waitForIdle()
        assertEquals("venue-1", deleted)

        save()
        assertTrue(assertNotNull(saved).config.counterparties.isEmpty())
    }

    @Test
    fun `an initiator is offered rules, which it answers by, and never counterparties, which only a venue has`() {
        val dealer =
            FixConnectionProfile(
                id = "dealer-1",
                name = "DEALER",
                config =
                    FixConnectionConfig(
                        connectionType = FixConnectionConfig.ConnectionType.INITIATOR,
                        senderCompID = "FIDLR1",
                        targetCompID = "FIRFQ",
                        host = "localhost",
                        port = "19880",
                        acceptorResponseRules =
                            listOf(AcceptorResponseRule(whenMsgType = "R", steps = listOf(ResponseStep("35=S|131=\${req.131}|")))),
                    ),
            )
        open(dealer)

        composeTestRule.onNodeWithText("Auto-Responses").assertExists()
        composeTestRule.onNodeWithTag("counterparties-section").assertDoesNotExist()
    }

    // ------------------------------------------------------------------ what each row is told

    @Test
    fun `each row says what the venue will make of it`() {
        val problems =
            counterpartyProblems(
                listOf(
                    Counterparty("", "requester"),
                    Counterparty("*", "responder"),
                    Counterparty("FI*DLR", "responder"),
                    Counterparty("FIDLR1", "dealer"),
                    Counterparty("FIBUY1", "requester"),
                    Counterparty("FIBUY1", "responder"),
                    Counterparty("FIDLRLG*", "responder"),
                ),
            )

        assertEquals("no CompID, so this covers nobody", problems[0])
        assertTrue(problems[1]!!.contains("covers every CompID"))
        assertTrue(problems[2]!!.contains("names that CompID literally"))
        assertTrue(problems[3]!!.contains("'dealer' is not a role"))
        assertNull(problems[4])
        assertEquals("FIBUY1 is listed above already, and that entry decides its role", problems[5])
        assertNull(problems[6], "a family is a counterparty like any other")
    }

    @Test
    fun `the summary counts roles and families`() {
        assertEquals(
            "1 requester · 2 responders (1 family)",
            counterpartySummary(declared + Counterparty("FIDLRLG*", "responder")),
        )
    }
}
