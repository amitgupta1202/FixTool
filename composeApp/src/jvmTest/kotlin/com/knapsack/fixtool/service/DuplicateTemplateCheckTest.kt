package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.SavedFixField
import com.knapsack.fixtool.model.SavedFixMessage
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration tests for duplicate template name checking
 * Uses isolated test directory - NEVER touches real .fixtool directory
 */
class DuplicateTemplateCheckTest {
    private lateinit var service: SavedMessagesService
    private lateinit var testDir: File
    private lateinit var testFile: File

    @Before
    fun setup() {
        // Create a completely isolated temporary directory for test files
        testDir =
            File.createTempFile("fixtool-test", "").apply {
                delete() // Delete the file
                mkdirs() // Create as directory
            }

        // Create test file path in isolated directory
        testFile = File(testDir, "saved_messages.json")

        // Create service with custom path pointing to isolated test directory
        service = SavedMessagesService(customPath = testFile.absolutePath)
    }

    @After
    fun cleanup() {
        // Clean up isolated test directory - never touches real .fixtool
        testDir.deleteRecursively()
    }

    @Test
    fun testCannotSaveNewTemplateWithDuplicateName() {
        val profileId = "test-profile"

        // Save first template
        val template1 =
            SavedFixMessage(
                name = "OrderTemplate",
                profileId = profileId,
                fields = listOf(SavedFixField(tag = "35", value = "D")),
            )
        service.saveMessage(profileId, template1).getOrThrow()

        // Load all templates to check for duplicates
        val existingTemplates = service.loadMessagesForProfile(profileId)

        // Simulate duplicate check (as done in UI)
        val isDuplicate =
            existingTemplates.any {
                it.name.trim().equals("OrderTemplate".trim(), ignoreCase = true)
            }

        assertTrue(isDuplicate, "Should detect duplicate when trying to save template with same name")
    }

    @Test
    fun testCanUpdateExistingTemplateWithSameName() {
        val profileId = "test-profile"
        val templateId = "template-123"

        // Save initial template
        val template =
            SavedFixMessage(
                id = templateId,
                name = "OrderTemplate",
                profileId = profileId,
                fields = listOf(SavedFixField(tag = "35", value = "D")),
            )
        service.saveMessage(profileId, template).getOrThrow()

        // Load templates
        val existingTemplates = service.loadMessagesForProfile(profileId)

        // Simulate duplicate check for UPDATE (should exclude self by ID)
        val isDuplicateForUpdate =
            existingTemplates.any {
                it.name.trim().equals("OrderTemplate".trim(), ignoreCase = true) &&
                    it.id != templateId
            }

        assertFalse(isDuplicateForUpdate, "Should allow updating template with its own name")

        // Update template with same name
        val updatedTemplate =
            SavedFixMessage(
                id = templateId,
                name = "OrderTemplate",
                profileId = profileId,
                fields =
                    listOf(
                        SavedFixField(tag = "35", value = "D"),
                        SavedFixField(tag = "49", value = "SENDER"),
                    ),
            )
        val result = service.saveMessage(profileId, updatedTemplate).getOrThrow()

        assertEquals(1, result.size, "Should still have only one template")
        assertEquals("OrderTemplate", result[0].name)
        assertEquals(2, result[0].fields.size)
    }

    @Test
    fun testCannotSaveAsNewWithSameName() {
        val profileId = "test-profile"
        val templateId = "template-123"

        // Save initial template
        val template =
            SavedFixMessage(
                id = templateId,
                name = "OrderTemplate",
                profileId = profileId,
                fields = listOf(SavedFixField(tag = "35", value = "D")),
            )
        service.saveMessage(profileId, template).getOrThrow()

        // Load templates
        val existingTemplates = service.loadMessagesForProfile(profileId)

        // Simulate duplicate check for "Save as New" (should check ALL templates, including current)
        val isDuplicateForSaveAsNew =
            existingTemplates.any {
                it.name.trim().equals("OrderTemplate".trim(), ignoreCase = true)
            }

        assertTrue(isDuplicateForSaveAsNew, "Should block 'Save as New' with same name as existing template")
    }

    @Test
    fun testDuplicateCheckIsCaseInsensitive() {
        val profileId = "test-profile"

        // Save template with lowercase name
        val template1 =
            SavedFixMessage(
                name = "ordertemplate",
                profileId = profileId,
                fields = listOf(SavedFixField(tag = "35", value = "D")),
            )
        service.saveMessage(profileId, template1).getOrThrow()

        // Load templates
        val existingTemplates = service.loadMessagesForProfile(profileId)

        // Try to save with different case
        val isDuplicateUpperCase =
            existingTemplates.any {
                it.name.trim().equals("ORDERTEMPLATE".trim(), ignoreCase = true)
            }
        val isDuplicateMixedCase =
            existingTemplates.any {
                it.name.trim().equals("OrderTemplate".trim(), ignoreCase = true)
            }

        assertTrue(isDuplicateUpperCase, "Should detect duplicate with uppercase name")
        assertTrue(isDuplicateMixedCase, "Should detect duplicate with mixed case name")
    }

    @Test
    fun testDuplicateCheckTrimsWhitespace() {
        val profileId = "test-profile"

        // Save template with no whitespace
        val template1 =
            SavedFixMessage(
                name = "OrderTemplate",
                profileId = profileId,
                fields = listOf(SavedFixField(tag = "35", value = "D")),
            )
        service.saveMessage(profileId, template1).getOrThrow()

        // Load templates
        val existingTemplates = service.loadMessagesForProfile(profileId)

        // Try to save with leading/trailing whitespace
        val isDuplicateWithSpaces =
            existingTemplates.any {
                it.name.trim().equals("  OrderTemplate  ".trim(), ignoreCase = true)
            }

        assertTrue(isDuplicateWithSpaces, "Should detect duplicate even with whitespace differences")
    }

    @Test
    fun testCanRenameTemplateToNewName() {
        val profileId = "test-profile"
        val templateId = "template-123"

        // Save initial template
        val template =
            SavedFixMessage(
                id = templateId,
                name = "OrderTemplate",
                profileId = profileId,
                fields = listOf(SavedFixField(tag = "35", value = "D")),
            )
        service.saveMessage(profileId, template).getOrThrow()

        // Load templates
        val existingTemplates = service.loadMessagesForProfile(profileId)

        // Check if new name is duplicate (should not be)
        val isDuplicateForUpdate =
            existingTemplates.any {
                it.name.trim().equals("RenamedTemplate".trim(), ignoreCase = true) &&
                    it.id != templateId
            }

        assertFalse(isDuplicateForUpdate, "Should allow renaming to a new unique name")

        // Rename template
        val renamedTemplate =
            SavedFixMessage(
                id = templateId,
                name = "RenamedTemplate",
                profileId = profileId,
                fields = listOf(SavedFixField(tag = "35", value = "D")),
            )
        val result = service.saveMessage(profileId, renamedTemplate).getOrThrow()

        assertEquals(1, result.size)
        assertEquals("RenamedTemplate", result[0].name)
    }

    @Test
    fun testCannotRenameToExistingTemplateName() {
        val profileId = "test-profile"

        // Save two templates
        val template1 =
            SavedFixMessage(
                id = "template-1",
                name = "OrderTemplate",
                profileId = profileId,
                fields = listOf(SavedFixField(tag = "35", value = "D")),
            )
        val template2 =
            SavedFixMessage(
                id = "template-2",
                name = "QuoteTemplate",
                profileId = profileId,
                fields = listOf(SavedFixField(tag = "35", value = "R")),
            )
        service.saveMessage(profileId, template1).getOrThrow()
        service.saveMessage(profileId, template2).getOrThrow()

        // Load templates
        val existingTemplates = service.loadMessagesForProfile(profileId)

        // Try to rename template2 to "OrderTemplate" (which already exists)
        val isDuplicateForUpdate =
            existingTemplates.any {
                it.name.trim().equals("OrderTemplate".trim(), ignoreCase = true) &&
                    it.id != "template-2"
            }

        assertTrue(isDuplicateForUpdate, "Should block renaming to an existing template name")
    }

    @Test
    fun testDuplicateCheckIsGlobalAcrossAllTemplates() {
        val profileId = "test-profile"

        // Save template
        val template1 =
            SavedFixMessage(
                name = "OrderTemplate",
                profileId = profileId,
                userTags = setOf("user1"),
                fields = listOf(SavedFixField(tag = "35", value = "D")),
            )
        service.saveMessage(profileId, template1).getOrThrow()

        // Load ALL templates (not filtered by profile)
        val allMessages = service.loadMessagesForProfile(profileId)

        // Try to create template with same name (should be blocked globally)
        val isDuplicate =
            allMessages.any {
                it.name.trim().equals("OrderTemplate".trim(), ignoreCase = true)
            }

        assertTrue(isDuplicate, "Duplicate check should be global across all users")
    }

    @Test
    fun testMigrationRemovesDuplicates() {
        val profileId = "test-profile"

        // Manually create file with duplicates (simulating legacy data)
        val duplicates =
            listOf(
                SavedFixMessage(
                    id = "dup-1",
                    name = "OrderTemplate",
                    profileId = profileId,
                    fields = listOf(SavedFixField(tag = "35", value = "D")),
                ),
                SavedFixMessage(
                    id = "dup-2",
                    name = "ordertemplate", // Same name, different case
                    profileId = profileId,
                    fields = listOf(SavedFixField(tag = "35", value = "D")),
                ),
                SavedFixMessage(
                    id = "unique-3",
                    name = "QuoteTemplate",
                    profileId = profileId,
                    fields = listOf(SavedFixField(tag = "35", value = "R")),
                ),
            )

        // Save all duplicates
        duplicates.forEach { service.saveMessage(profileId, it).getOrThrow() }

        // Create new service instance to trigger migration (using same test file)
        val newService = SavedMessagesService(customPath = testFile.absolutePath)
        val loadedMessages = newService.loadMessagesForProfile(profileId)

        // Should only have 2 templates (duplicates removed)
        assertEquals(2, loadedMessages.size, "Migration should remove duplicate templates")

        // First occurrence should be kept
        assertTrue(loadedMessages.any { it.name == "OrderTemplate" }, "First occurrence should be kept")
        assertTrue(loadedMessages.any { it.name == "QuoteTemplate" }, "Unique template should be kept")
    }

    @Test
    fun testMultipleTemplatesWithDifferentNames() {
        val profileId = "test-profile"

        val templates =
            listOf(
                SavedFixMessage(
                    name = "OrderTemplate",
                    profileId = profileId,
                    fields = listOf(SavedFixField(tag = "35", value = "D")),
                ),
                SavedFixMessage(
                    name = "QuoteTemplate",
                    profileId = profileId,
                    fields = listOf(SavedFixField(tag = "35", value = "R")),
                ),
                SavedFixMessage(
                    name = "ExecutionTemplate",
                    profileId = profileId,
                    fields = listOf(SavedFixField(tag = "35", value = "8")),
                ),
            )

        templates.forEach { service.saveMessage(profileId, it).getOrThrow() }
        val result = service.loadMessagesForProfile(profileId)

        assertEquals(3, result.size, "Should allow multiple templates with different names")
        assertTrue(result.any { it.name == "OrderTemplate" })
        assertTrue(result.any { it.name == "QuoteTemplate" })
        assertTrue(result.any { it.name == "ExecutionTemplate" })
    }
}
