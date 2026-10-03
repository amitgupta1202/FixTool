package com.knapsack.fixtool.model

import org.junit.After
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The FIX version a dictionary file is read as, from its root element.
 *
 * QuickFIX/J treats a root with no `type` attribute as FIX, and every bundled `FIX40.xml` to `FIX50SP2.xml`
 * has none. Detection used to require `type="FIX"`, so a workspace copy of any of them came out as FIX 4.4
 * whatever its major and minor said, and a FIX 5.0 file did not get the FIXT header set. These pin the
 * detection against copies of the bundled files, the shape a venue's file most often starts from.
 */
class DictionaryVersionDetectionTest {
    private val dir: File = Files.createTempDirectory("fixtool-dict-version").toFile()

    @After
    fun cleanup() {
        dir.deleteRecursively()
    }

    /** A copy of the bundled [resource], optionally with its root element rewritten. */
    private fun copyOf(resource: String, root: String? = null): File {
        val text = javaClass.getResourceAsStream("/dictionaries/$resource")!!.use { it.reader().readText() }
        val written = if (root == null) text else text.replaceFirst(Regex("<fix[^>]*>"), root)
        return File(dir, resource).apply { writeText(written) }
    }

    @Test
    fun `a dictionary with no type attribute is read by its major and minor`() {
        val expected =
            mapOf(
                "FIX40.xml" to FixVersion.FIX_4_0,
                "FIX41.xml" to FixVersion.FIX_4_1,
                "FIX42.xml" to FixVersion.FIX_4_2,
                "FIX43.xml" to FixVersion.FIX_4_3,
                "FIX44.xml" to FixVersion.FIX_4_4,
            )
        val detected = expected.keys.associateWith { FixDictionaryAdapter.detectVersionFromFile(copyOf(it)) }

        assertEquals(expected, detected)
        assertEquals(FixVersion.FIX_4_2, FixDictionaryAdapter.fromFile(File(dir, "FIX42.xml")).fixVersion)
    }

    @Test
    fun `a FIX 5 dictionary with no type attribute is read as FIX 5, with the FIXT header`() {
        val sp2 = copyOf("FIX50SP2.xml", root = """<fix major="5" minor="0" servicepack="2">""")

        assertEquals(FixVersion.FIX_5_0_SP2, FixDictionaryAdapter.detectVersionFromFile(sp2))
        val adapter = FixDictionaryAdapter.fromFile(sp2)
        assertTrue(adapter.isFix50Plus(), "a FIX 5.0 file is a FIXT dictionary")
        assertTrue(1128 in adapter.getHeaderTags(), "ApplVerID belongs in the header, not the body")
    }

    @Test
    fun `a blank type is read the same as a missing one`() {
        val file = copyOf("FIX42.xml", root = """<fix type="" major="4" minor="2">""")

        assertEquals(FixVersion.FIX_4_2, FixDictionaryAdapter.detectVersionFromFile(file))
    }
}
