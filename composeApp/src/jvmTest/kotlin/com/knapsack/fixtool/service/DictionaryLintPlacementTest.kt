package com.knapsack.fixtool.service

import com.knapsack.fixtool.service.FixMessageHelper.toQuickFixMessageManual
import org.junit.Test
import quickfix.Field
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **The lint says where a tag goes, and it reads that from the walk the parse builds by.**
 *
 * It had a third reading of group structure, which ended a group at the first tag the group's dictionary does
 * not define. The parse keeps such a tag in its entry when the entry goes on after it (see [GroupWalk]), so a
 * send of three allocations each carrying `9110` warned that `9110, 79, 80, 15, 37, 11` "are sent as plain
 * top-level fields" while the wire carried every one of them inside its group.
 */
class DictionaryLintPlacementTest {
    private val dictionary = NestedGroupFixtures.dictionary
    private val envelope = setOf(8, 35, 10)

    @Test
    fun `a tag kept inside an allocation is named where it is sent, and the fields around it are not named`() {
        val fields = FixMessageHelper.parseFixMessage(NestedGroupFixtures.allocationsAheadOfTheDictionary.wire)

        val unknown = DictionaryLint.unknownTags(fields, dictionary)

        assertEquals(listOf(15, 9110), unknown, "15 is the side's and not an allocation's, 9110 is nobody's")
        val text = DictionaryLint.describe(unknown, fields, dictionary)
        assertTrue("15 Currency in NoAllocs (78)" in text, text)
        assertTrue("9110 in NoAllocs (78)" in text, text)
        assertFalse("top-level" in text || "top level" in text, text)
    }

    @Test
    fun `a tag the message does not define is still placed at the top level`() {
        val fields = listOf(35 to "AE", 571 to "T", 150 to "F", 32 to "100")

        val unknown = DictionaryLint.unknownTags(fields, dictionary)

        assertEquals(listOf(150), unknown)
        val text = DictionaryLint.describe(unknown, fields, dictionary)
        assertTrue("150 ExecType at the top level" in text, text)
    }

    @Test
    fun `the lint places every field where the parse builds it`() {
        NestedGroupFixtures.all.forEach { fixture ->
            val fields = FixMessageHelper.parseFixMessage(fixture.wire)
            val message =
                fixture.wire.toQuickFixMessageManual(dictionary.getDataDictionary()!!, adapter = dictionary)
            val parsedTopLevel = mutableSetOf<Int>()
            message.iterator().forEach { parsedTopLevel += (it as Field<*>).tag }

            val walkedTopLevel =
                FixStructure.walk(fields, dictionary).filter { it.groupTag == null }.map { it.tag } - envelope

            assertEquals(parsedTopLevel, walkedTopLevel.toSet(), fixture.name)
        }
    }

    @Test
    fun `an issue on a tag inside a group sits on its own row and says which group`() {
        val wire = FixMessageHelper.parseFixMessage(NestedGroupFixtures.allocationsAheadOfTheDictionary.wire)
        val rows = wire.mapIndexed { row, (tag, value) -> MessageIssues.Field(row, tag, value) }

        val issues = MessageIssues.check(rows, dictionary).filter { "not defined" in it.text }

        val currency = issues.single { it.tag == 15 }
        assertEquals(14, currency.row, "the side's own 15=EUR at row 10 is defined, the allocation's at 14 is not")
        assertTrue("NoAllocs (78)" in currency.text, currency.text)
        assertFalse("top-level" in currency.text, currency.text)
        assertEquals(setOf(15, 9110), issues.map { it.tag }.toSet(), "79, 80, 37 and 11 are where they belong")
    }
}
