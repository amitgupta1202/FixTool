package com.knapsack.fixtool.viewmodel

import com.knapsack.fixtool.model.FixMessage
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
}
