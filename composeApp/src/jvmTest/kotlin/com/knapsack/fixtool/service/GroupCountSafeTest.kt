package com.knapsack.fixtool.service

import quickfix.FieldMap
import quickfix.Group
import quickfix.Message
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `groupCountSafe` must answer without writing. QuickFIX's own `getGroupCount` goes through
 * `getGroups`, whose `computeIfAbsent` inserts an empty list under any tag it is asked about —
 * a mutation of the shared parsed message, from render code, on whatever thread composes. The
 * views draw every tag of every message this way, so the no-write guarantee is load-bearing.
 */
class GroupCountSafeTest {
    private fun groupsMapOf(message: Message): Map<*, *> {
        val field = FieldMap::class.java.getDeclaredField("groups").apply { isAccessible = true }
        return field.get(message) as Map<*, *>
    }

    @Test
    fun `asking about an absent group writes nothing`() {
        val message = Message()

        assertEquals(0, message.groupCountSafe(453))
        assertEquals(0, message.groupCountSafe(9999))

        assertEquals(0, groupsMapOf(message).size, "no entry may appear for a tag that was merely asked about")
    }

    @Test
    fun `a present group is counted exactly`() {
        val message = Message()
        message.addGroup(Group(453, 448).apply { setString(448, "FIRMA") })
        message.addGroup(Group(453, 448).apply { setString(448, "FIRMB") })

        assertEquals(2, message.groupCountSafe(453))
    }

    /** The contrast that motivates the helper — QuickFIX's accessor really does write. */
    @Test
    fun `quickfix getGroupCount inserts an entry for the tag it was asked about`() {
        val message = Message()

        message.getGroupCount(453)

        assertEquals(1, groupsMapOf(message).size, "if this fails, QuickFIX fixed it and groupCountSafe can be retired")
    }
}
