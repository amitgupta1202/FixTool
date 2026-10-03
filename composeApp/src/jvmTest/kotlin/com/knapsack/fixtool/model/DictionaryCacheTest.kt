package com.knapsack.fixtool.model

import org.junit.After
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A dictionary file edited on disk is read again the next time it is loaded.
 *
 * The adapter cache was keyed on the path alone and nothing cleared it, so after an author fixed a typo in
 * `venue.xml` or added a field to it, Save in Settings reloaded the stale adapter, and a load that had
 * failed kept failing, until a restart. These pin that an edit is seen and a failure is not remembered.
 */
class DictionaryCacheTest {
    private val dir: File = Files.createTempDirectory("fixtool-dict-cache").toFile()

    @After
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun dictionary(extraField: String = ""): String =
        """
        <fix major="4" minor="4">
          <header><field name="BeginString" required="Y"/><field name="MsgType" required="Y"/></header>
          <trailer><field name="CheckSum" required="Y"/></trailer>
          <messages>
            <message name="NewOrderSingle" msgtype="D" msgcat="app"><field name="ClOrdID" required="Y"/></message>
          </messages>
          <fields>
            <field number="8" name="BeginString" type="STRING"/>
            <field number="10" name="CheckSum" type="STRING"/>
            <field number="11" name="ClOrdID" type="STRING"/>
            <field number="35" name="MsgType" type="STRING"/>
            $extraField
          </fields>
        </fix>
        """.trimIndent()

    /** Rewrites [file] and moves its modification time on, so the edit cannot share a clock tick with the last. */
    private fun edit(file: File, text: String) {
        val before = file.lastModified()
        file.writeText(text)
        file.setLastModified(before + 2_000)
    }

    @Test
    fun `an edited dictionary file is read again`() {
        val file = File(dir, "venue.xml").apply { writeText(dictionary()) }
        assertNull(FixDictionaryAdapter.fromFile(file).getFieldName(9001))

        edit(file, dictionary("""<field number="9001" name="VenueDesk" type="STRING"/>"""))

        assertEquals("VenueDesk", FixDictionaryAdapter.fromFile(file).getFieldName(9001), "the edit is seen")
        assertEquals("VenueDesk", FixDictionaryAdapter.fromFiles(file, null).getFieldName(9001))
    }

    @Test
    fun `a dictionary that failed to load is tried again once it is fixed`() {
        val file = File(dir, "venue.xml").apply { writeText(dictionary().replace("</fields>", "</field>")) }
        assertFalse(FixDictionaryAdapter.fromFile(file).isLoaded(), "the typo fails the load")
        assertFalse(FixDictionaryAdapter.fromFiles(file, null).isLoaded())

        edit(file, dictionary())

        assertTrue(FixDictionaryAdapter.fromFile(file).isLoaded(), "the fixed file loads")
        assertTrue(FixDictionaryAdapter.fromFiles(file, null).isLoaded())
    }

    @Test
    fun `an unchanged file is still served from the cache`() {
        val file = File(dir, "venue.xml").apply { writeText(dictionary()) }

        assertTrue(FixDictionaryAdapter.fromFile(file) === FixDictionaryAdapter.fromFile(file))
    }
}
