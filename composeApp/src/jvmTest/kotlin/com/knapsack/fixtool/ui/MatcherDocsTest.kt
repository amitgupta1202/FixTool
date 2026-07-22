package com.knapsack.fixtool.ui

import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * **Every matcher type is documented, in every reference that claims to list them all.**
 *
 * `range` and `notEqual` shipped and were named in none of them for months. That is not a cosmetic gap:
 * `syntax.md` is served verbatim by `GET /syntax` and the `fixtool_syntax` MCP tool, so it is *the* thing
 * an agent reads before writing an expectation — a type missing from it is a type no agent will ever use,
 * however well it works. `help.html` is the same promise to a human, and `AUTOMATION.md` to whoever is
 * driving the HTTP API by hand.
 *
 * The same shape as the settings suite's "no setting is invisible": the code owns the list, and a doc that
 * has fallen behind it fails here rather than being discovered by someone who needed the type.
 */
class MatcherDocsTest {
    private val references =
        listOf(
            "composeApp/src/jvmMain/resources/syntax.md",
            "composeApp/src/jvmMain/resources/help.html",
            "docs/AUTOMATION.md",
        )

    @Test
    fun `every matcher type the editor offers is named in every matcher reference`() {
        for (path in references) {
            val text = repoFile(path).readText()
            val missing = MATCHER_TYPES.filterNot { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(text) }
            assertTrue(
                missing.isEmpty(),
                "$path documents no $missing — an agent reading it will never use a type it cannot see",
            )
        }
    }

    /**
     * The three the *field's type* decides, and the reason each reference has to say so: they do not fail
     * on a wrong value, they fail on every value a text field can hold. An author who reads only the table
     * has no way to know that `numeric` on a ClOrdID is a row that can never go green.
     */
    @Test
    fun `every matcher reference warns which types the field's type rules out`() {
        for (path in references) {
            val text = repoFile(path).readText()
            assertTrue(
                "enum-coded" in text,
                "$path never warns against a tolerance over an enum-coded int — the one mistake that " +
                    "parses cleanly, reads like a tolerance, and silently accepts other meanings",
            )
        }
    }

    private fun repoFile(path: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, path)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("cannot find $path from ${System.getProperty("user.dir")} — this test proves nothing unless it can")
    }
}
