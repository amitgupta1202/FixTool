package com.knapsack.fixtool.viewmodel

import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.NotificationType
import com.knapsack.fixtool.model.SavedFixField
import com.knapsack.fixtool.model.SavedFixMessage
import org.junit.After
import org.junit.Before
import org.junit.Test
import quickfix.Message
import quickfix.field.ClOrdID
import quickfix.field.MsgType
import quickfix.field.SenderCompID
import quickfix.field.TargetCompID
import java.io.File
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for session selection and editor profile synchronization.
 *
 * These tests verify the fix for the bug where selecting a session tab would
 * correctly route messages to that session, but the message editor dropdown
 * would show a different (incorrect) profile.
 *
 * Root cause: setActiveSession() was not updating _selectedEditorProfile
 * to match the newly selected session.
 */
class SessionEditorProfileSyncTest {
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File

    @Before
    fun setup() {
        // Create a temporary directory for test files (isolated from production)
        testDir =
            File.createTempFile("fixtool-test", "").apply {
                delete() // Delete the file
                mkdirs() // Create as directory
            }

        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
    }

    @After
    fun cleanup() {
        testDir.deleteRecursively()
    }

    private fun createTestMessage(clOrdId: String): FixMessage {
        val msg = Message()
        msg.header.setField(MsgType("D"))
        msg.header.setField(SenderCompID("SENDER1"))
        msg.header.setField(TargetCompID("TARGET1"))
        msg.setField(ClOrdID(clOrdId))

        return FixMessage(
            timestamp = LocalDateTime.now(),
            direction = FixMessage.Direction.INCOMING,
            rawMessage = msg.toString(),
            messageType = "D",
            quickfixMessage = msg,
        )
    }

    // ========================================
    // setActiveSession() Tests
    // ========================================

    @Test
    fun testSetActiveSessionUpdatesSelectedEditorProfile() {
        // Given: Two sessions with associated profiles
        val (profile1, session1) = viewModel.createSessionWithProfileForTest("Profile A")
        val (profile2, session2) = viewModel.createSessionWithProfileForTest("Profile B")

        // Initially no session is selected
        assertEquals(-1, viewModel.activeSessionIndex)
        assertNull(viewModel.selectedEditorProfile.value)

        // When: Select session 0 (Profile A)
        viewModel.setActiveSession(0)

        // Then: Both activeSession and selectedEditorProfile should be synced
        assertEquals(0, viewModel.activeSessionIndex)
        assertEquals(session1, viewModel.activeSession)
        assertEquals(profile1, viewModel.selectedEditorProfile.value)
        assertEquals("Profile A", viewModel.selectedEditorProfile.value?.name)

        // When: Select session 1 (Profile B)
        viewModel.setActiveSession(1)

        // Then: Both should update to Profile B
        assertEquals(1, viewModel.activeSessionIndex)
        assertEquals(session2, viewModel.activeSession)
        assertEquals(profile2, viewModel.selectedEditorProfile.value)
        assertEquals("Profile B", viewModel.selectedEditorProfile.value?.name)
    }

    @Test
    fun testSetActiveSessionToInvalidIndexClearsEditorProfile() {
        // Given: A session with associated profile is selected
        val (profile1, _) = viewModel.createSessionWithProfileForTest("Profile A")
        viewModel.setActiveSession(0)
        assertEquals(profile1, viewModel.selectedEditorProfile.value)

        // When: Select invalid session index (-1)
        viewModel.setActiveSession(-1)

        // Then: Both activeSession and selectedEditorProfile should be cleared
        assertEquals(-1, viewModel.activeSessionIndex)
        assertNull(viewModel.activeSession)
        assertNull(viewModel.selectedEditorProfile.value)
    }

    @Test
    fun testRapidSessionSwitchingMaintainsProfileSync() {
        // Given: Multiple sessions with profiles
        val (profile1, _) = viewModel.createSessionWithProfileForTest("Profile 1")
        val (profile2, _) = viewModel.createSessionWithProfileForTest("Profile 2")
        val (profile3, _) = viewModel.createSessionWithProfileForTest("Profile 3")

        // When: Rapidly switch between sessions
        viewModel.setActiveSession(0)
        assertEquals(profile1, viewModel.selectedEditorProfile.value)

        viewModel.setActiveSession(2)
        assertEquals(profile3, viewModel.selectedEditorProfile.value)

        viewModel.setActiveSession(1)
        assertEquals(profile2, viewModel.selectedEditorProfile.value)

        viewModel.setActiveSession(0)
        assertEquals(profile1, viewModel.selectedEditorProfile.value)

        // Then: Profile should always match the selected session
        assertEquals(0, viewModel.activeSessionIndex)
        assertEquals("Profile 1", viewModel.selectedEditorProfile.value?.name)
    }

    // ========================================
    // setActiveSessionByObject() Tests
    // ========================================

    @Test
    fun testSetActiveSessionByObjectUpdatesSelectedEditorProfile() {
        // Given: Two sessions with associated profiles
        val (profile1, session1) = viewModel.createSessionWithProfileForTest("Profile A")
        val (profile2, session2) = viewModel.createSessionWithProfileForTest("Profile B")

        // When: Select session1 by object
        viewModel.setActiveSessionByObject(session1)

        // Then: selectedEditorProfile should match
        assertEquals(session1, viewModel.activeSession)
        assertEquals(profile1, viewModel.selectedEditorProfile.value)

        // When: Select session2 by object
        viewModel.setActiveSessionByObject(session2)

        // Then: selectedEditorProfile should update
        assertEquals(session2, viewModel.activeSession)
        assertEquals(profile2, viewModel.selectedEditorProfile.value)
    }

    @Test
    fun testSetActiveSessionByObjectToNullClearsEditorProfile() {
        // Given: A session is selected
        val (profile1, session1) = viewModel.createSessionWithProfileForTest("Profile A")
        viewModel.setActiveSessionByObject(session1)
        assertEquals(profile1, viewModel.selectedEditorProfile.value)

        // When: Set active session to null
        viewModel.setActiveSessionByObject(null)

        // Then: Both should be cleared
        assertNull(viewModel.activeSession)
        assertNull(viewModel.selectedEditorProfile.value)
    }

    // ========================================
    // selectMessage() Tests
    // ========================================

    @Test
    fun testSelectMessageAutoSwitchesSessionAndUpdatesEditorProfile() {
        // Given: Two sessions with profiles, each containing different messages
        val (profile1, session1) = viewModel.createSessionWithProfileForTest("Profile A")
        val (profile2, session2) = viewModel.createSessionWithProfileForTest("Profile B")

        // Add message to session1
        val message1 = createTestMessage("ORDER_A")
        session1.addMessage(message1)
        session1.flushMessageQueue() // Flush to make message available in messages list

        // Add message to session2
        val message2 = createTestMessage("ORDER_B")
        session2.addMessage(message2)
        session2.flushMessageQueue() // Flush to make message available in messages list

        // Select session 0 initially
        viewModel.setActiveSession(0)
        assertEquals(profile1, viewModel.selectedEditorProfile.value)

        // When: Select a message from session2 (should auto-switch)
        viewModel.selectMessage(message2)

        // Then: Both session and profile should switch to session2/profile2
        assertEquals(1, viewModel.activeSessionIndex)
        assertEquals(session2, viewModel.activeSession)
        assertEquals(profile2, viewModel.selectedEditorProfile.value)
        assertEquals("Profile B", viewModel.selectedEditorProfile.value?.name)
    }

    @Test
    fun testSelectMessageFromSameSessionDoesNotChangeProfile() {
        // Given: Session with profile selected
        val (profile1, session1) = viewModel.createSessionWithProfileForTest("Profile A")
        viewModel.createSessionWithProfileForTest("Profile B")

        val message1 = createTestMessage("ORDER_A")
        session1.addMessage(message1)
        session1.flushMessageQueue() // Flush to make message available in messages list

        viewModel.setActiveSession(0)
        assertEquals(profile1, viewModel.selectedEditorProfile.value)

        // When: Select a message from the same session
        viewModel.selectMessage(message1)

        // Then: Profile should remain the same
        assertEquals(0, viewModel.activeSessionIndex)
        assertEquals(profile1, viewModel.selectedEditorProfile.value)
    }

    // ========================================
    // Session without Profile Tests
    // ========================================

    @Test
    fun testSetActiveSessionForSessionWithoutProfileClearsEditorProfile() {
        // Given: One session with profile, one without
        val (profile1, _) = viewModel.createSessionWithProfileForTest("Profile A")
        viewModel.createSessionForTest("Session Without Profile") // No profile mapping

        // Select session with profile
        viewModel.setActiveSession(0)
        assertEquals(profile1, viewModel.selectedEditorProfile.value)

        // When: Select session without profile (index 1)
        viewModel.setActiveSession(1)

        // Then: Session should be selected, but profile should be null
        assertEquals(1, viewModel.activeSessionIndex)
        assertNotNull(viewModel.activeSession)
        assertNull(viewModel.selectedEditorProfile.value)
    }

    // ========================================
    // Bug Scenario Regression Tests
    // ========================================

    @Test
    fun testBugScenario_TabSwitchBeforeSend_ProfileMatchesSession() {
        // This test reproduces the original bug scenario:
        // 1. User has multiple sessions open
        // 2. User clicks on a different session tab
        // 3. User sends a message
        // Bug: Message goes to correct session, but editor shows wrong profile

        // Given: Two sessions with profiles
        val (profile1, session1) = viewModel.createSessionWithProfileForTest("Profile A")
        val (profile2, session2) = viewModel.createSessionWithProfileForTest("Profile B")

        // Initially select Profile A
        viewModel.setActiveSession(0)
        assertEquals(profile1, viewModel.selectedEditorProfile.value)
        assertEquals(session1, viewModel.activeSession)

        // When: User clicks on Profile B's tab
        viewModel.setActiveSession(1)

        // Then: CRITICAL - selectedEditorProfile MUST match the activeSession
        // This was the bug - selectedEditorProfile was NOT being updated
        assertEquals(session2, viewModel.activeSession)
        assertEquals(
            profile2,
            viewModel.selectedEditorProfile.value,
            "selectedEditorProfile must match activeSession after tab switch",
        )
        assertEquals(
            "Profile B",
            viewModel.selectedEditorProfile.value?.name,
            "Editor should show Profile B after switching to its tab",
        )

        // The message editor UI uses selectedEditorProfile, so it should now show Profile B
        // The sendMessage() uses activeSession, which is also session2/Profile B
        // Both are now in sync - the bug is fixed!
    }

    @Test
    fun testBugScenario_MessageSelectionAutoSwitch_ProfileMatchesSession() {
        // Variant of the bug: selecting a message from another session
        // should also sync the editor profile

        // Given: Two sessions with messages
        val (profile1, session1) = viewModel.createSessionWithProfileForTest("Profile A")
        val (profile2, session2) = viewModel.createSessionWithProfileForTest("Profile B")

        val messageInSessionB = createTestMessage("ORDER_B")
        session2.addMessage(messageInSessionB)
        session2.flushMessageQueue() // Flush to make message available in messages list

        // Start on session 1
        viewModel.setActiveSession(0)
        assertEquals(profile1, viewModel.selectedEditorProfile.value)

        // When: User clicks on a message that's in session 2
        viewModel.selectMessage(messageInSessionB)

        // Then: Both session and profile should switch
        assertEquals(session2, viewModel.activeSession)
        assertEquals(
            profile2,
            viewModel.selectedEditorProfile.value,
            "selectedEditorProfile must sync when message selection auto-switches session",
        )
    }

    @Test
    fun testConsistencyBetweenActiveSessionAndEditorProfile() {
        // Verify that activeSession and selectedEditorProfile are always consistent
        // after any operation that changes the session

        val (profile1, session1) = viewModel.createSessionWithProfileForTest("Profile 1")
        val (profile2, session2) = viewModel.createSessionWithProfileForTest("Profile 2")
        val (profile3, session3) = viewModel.createSessionWithProfileForTest("Profile 3")

        // Test setActiveSession consistency
        for (i in 0..2) {
            viewModel.setActiveSession(i)
            val expectedProfile =
                when (i) {
                    0 -> profile1
                    1 -> profile2
                    2 -> profile3
                    else -> null
                }
            assertEquals(
                expectedProfile,
                viewModel.selectedEditorProfile.value,
                "setActiveSession($i): selectedEditorProfile should match",
            )
        }

        // Test setActiveSessionByObject consistency
        listOf(session1 to profile1, session2 to profile2, session3 to profile3).forEach { (session, profile) ->
            viewModel.setActiveSessionByObject(session)
            assertEquals(
                profile,
                viewModel.selectedEditorProfile.value,
                "setActiveSessionByObject(${session.title}): selectedEditorProfile should match",
            )
        }
    }

    // ========================================
    // moveSession() Profile Mapping Tests
    // ========================================

    @Test
    fun testMoveSessionLeftUpdatesProfileMapping() {
        // Given: Three sessions with profiles at indices 0, 1, 2
        val (profile1, _) = viewModel.createSessionWithProfileForTest("Profile 1")
        val (profile2, _) = viewModel.createSessionWithProfileForTest("Profile 2")
        val (profile3, _) = viewModel.createSessionWithProfileForTest("Profile 3")

        // When: Move session from index 2 to index 0 (move left)
        viewModel.moveSession(2, 0)

        // Then: Selecting each index should show the correct profile
        viewModel.setActiveSession(0)
        assertEquals(profile3, viewModel.selectedEditorProfile.value) // Was at 2, now at 0

        viewModel.setActiveSession(1)
        assertEquals(profile1, viewModel.selectedEditorProfile.value) // Was at 0, now at 1

        viewModel.setActiveSession(2)
        assertEquals(profile2, viewModel.selectedEditorProfile.value) // Was at 1, now at 2
    }

    @Test
    fun testMoveSessionRightUpdatesProfileMapping() {
        // Given: Three sessions with profiles
        val (profile1, _) = viewModel.createSessionWithProfileForTest("Profile 1")
        val (profile2, _) = viewModel.createSessionWithProfileForTest("Profile 2")
        val (profile3, _) = viewModel.createSessionWithProfileForTest("Profile 3")

        // When: Move session from index 0 to index 2 (move right)
        viewModel.moveSession(0, 2)

        // Then: Profile mapping should be updated
        viewModel.setActiveSession(0)
        assertEquals(profile2, viewModel.selectedEditorProfile.value) // Was at 1, now at 0

        viewModel.setActiveSession(1)
        assertEquals(profile3, viewModel.selectedEditorProfile.value) // Was at 2, now at 1

        viewModel.setActiveSession(2)
        assertEquals(profile1, viewModel.selectedEditorProfile.value) // Was at 0, now at 2
    }

    @Test
    fun testMultipleMovesMaintainProfileSync() {
        // Given: Three sessions
        val (profile1, _) = viewModel.createSessionWithProfileForTest("Profile 1")
        val (profile2, _) = viewModel.createSessionWithProfileForTest("Profile 2")
        val (profile3, _) = viewModel.createSessionWithProfileForTest("Profile 3")

        // When: Multiple moves
        viewModel.moveSession(0, 2) // [2,3,1] -> [3,1,2] in original profile order
        viewModel.moveSession(2, 0) // Move back: [1,2,3] -> back to original

        // Then: Should be back to original order
        viewModel.setActiveSession(0)
        assertEquals(profile1, viewModel.selectedEditorProfile.value)

        viewModel.setActiveSession(1)
        assertEquals(profile2, viewModel.selectedEditorProfile.value)

        viewModel.setActiveSession(2)
        assertEquals(profile3, viewModel.selectedEditorProfile.value)
    }

    @Test
    fun testMoveSessionAdjacentSwap() {
        // Given: Two sessions
        val (profile1, _) = viewModel.createSessionWithProfileForTest("Profile 1")
        val (profile2, _) = viewModel.createSessionWithProfileForTest("Profile 2")

        viewModel.setActiveSession(0)
        assertEquals(profile1, viewModel.selectedEditorProfile.value)

        // When: Swap adjacent (move 0 to 1)
        viewModel.moveSession(0, 1)

        // Then: Profiles should swap
        viewModel.setActiveSession(0)
        assertEquals(profile2, viewModel.selectedEditorProfile.value)

        viewModel.setActiveSession(1)
        assertEquals(profile1, viewModel.selectedEditorProfile.value)
    }

    // ========================================
    // loadEditorMessage() Session Selection Tests
    // ========================================

    private fun templateFor(name: String, vararg profiles: FixConnectionProfile): SavedFixMessage =
        SavedFixMessage(
            name = name,
            userTags = profiles.map { it.id }.toSet(),
            fields = listOf(SavedFixField(tag = "35", value = "D")),
        )

    @Test
    fun testLoadTemplateKeepsActiveSessionWhenItBelongsToTheTemplate() {
        // Given: Three profiles the template belongs to, working in the last of them
        val (profileA, _) = viewModel.createSessionWithProfileForTest("Profile A")
        val (profileB, _) = viewModel.createSessionWithProfileForTest("Profile B")
        val (profileC, sessionC) = viewModel.createSessionWithProfileForTest("Profile C")
        viewModel.setActiveSession(2)

        // When: Loading a template associated with all three
        viewModel.loadEditorMessage(templateFor("Order", profileA, profileB, profileC))

        // Then: The session in hand is kept - not re-sorted to the alphabetically first profile
        assertEquals(sessionC, viewModel.activeSession, "A template that fits the active session must not move it")
        assertEquals(profileC, viewModel.selectedEditorProfile.value)
    }

    @Test
    fun testLoadTemplateSwitchesWhenActiveSessionDoesNotBelongToTheTemplate() {
        // Given: Working in a profile the template has nothing to do with
        val (profileA, sessionA) = viewModel.createSessionWithProfileForTest("Profile A")
        val (profileB, _) = viewModel.createSessionWithProfileForTest("Profile B")
        val (_, _) = viewModel.createSessionWithProfileForTest("Profile C")
        viewModel.setActiveSession(2)

        // When: Loading a template associated with the other two
        viewModel.loadEditorMessage(templateFor("Order", profileA, profileB))

        // Then: It still moves to the best of the template's own profiles
        assertEquals(sessionA, viewModel.activeSession)
        assertEquals(profileA, viewModel.selectedEditorProfile.value)
    }

    @Test
    fun testLoadTemplateKeepsTheSlotOfAMultiSessionProfile() {
        // Given: One profile owning two sessions (sessionCount > 1), working in the second
        val (profile, _) = viewModel.createSessionWithProfileForTest("Profile A [1]")
        val secondSession = viewModel.addSessionToProfileForTest(profile, "Profile A [2]")
        viewModel.setActiveSession(1)

        // When: Loading a template associated with that profile
        viewModel.loadEditorMessage(templateFor("Order", profile))

        // Then: It stays in slot 2 rather than snapping back to the profile's first session
        assertEquals(secondSession, viewModel.activeSession, "Loading a template must not snap back to slot 1")
        assertEquals(profile, viewModel.selectedEditorProfile.value)
    }

    @Test
    fun testLoadTemplateForNeverConnectedProfileKeepsItNamedInTheEditor() {
        // Given: A template belonging only to a profile that owns no session
        val (_, _) = viewModel.createSessionWithProfileForTest("Profile A")
        viewModel.setActiveSession(0)
        val neverConnected = viewModel.createProfileWithoutSessionForTest("Profile Z")

        // When: Loading it
        viewModel.loadEditorMessage(templateFor("Order", neverConnected))

        // Then: There is no session to send on, but the editor still names the profile
        assertEquals(
            neverConnected,
            viewModel.selectedEditorProfile.value,
            "The editor must keep naming the profile instead of blanking",
        )
        assertNull(viewModel.activeSession, "A profile with no session must not leave another session active")
        assertEquals(-1, viewModel.activeSessionIndex)
        assertTrue(
            viewModel.notifications.any { it.type == NotificationType.WARNING && "Profile Z" in it.message },
            "The user must be told why nothing was selected",
        )
    }

    @Test
    fun testLoadTemplatePrefersTheProfileThatOwnsASession() {
        // Given: Two associated profiles, the alphabetically first of which was never connected
        val neverConnected = viewModel.createProfileWithoutSessionForTest("Profile A")
        val (withSession, session) = viewModel.createSessionWithProfileForTest("Profile B")
        val (_, _) = viewModel.createSessionWithProfileForTest("Profile C")
        viewModel.setActiveSession(1) // Profile C's session - not one of the template's

        // When: Loading a template associated with both
        viewModel.loadEditorMessage(templateFor("Order", neverConnected, withSession))

        // Then: The one that can actually be sent on wins, despite sorting later by name
        assertEquals(withSession, viewModel.selectedEditorProfile.value)
        assertEquals(session, viewModel.activeSession)
    }
}
