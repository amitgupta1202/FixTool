package com.knapsack.fixtool.service.compare

import com.knapsack.fixtool.service.ExpectationEvaluator
import org.junit.Test
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **The engine never reads the overlay.** This is a structural invariant, not a convention, so it is
 * checked structurally.
 *
 * Assertion is positional: the *k*-th row for a tag asserts the *k*-th occurrence of it, and nothing else.
 * [GroupOverlay] exists for *presentation* and for *bounding a move* — it is derived from a dictionary the
 * runner may not even have loaded, and it can fall back to a guess. An `ExpectationEvaluator` that consulted
 * it would judge a message by a structure the model does not have, and the two answers would drift the
 * moment a venue's dictionary did: the very seam — two components each deciding what an entry contains —
 * that produced the defect the sequence model was built to end.
 *
 * The check has two halves because they catch different things. The **class file** proves there is no real
 * dependency: a call, a field, a type reference. The **source** proves the name is not there at all, which
 * is what catches the first step towards one — an import compiles away to nothing, so the bytecode would
 * happily say the invariant holds while the code was being wired up to break it.
 */
class GroupOverlayArchitectureTest {
    private val forbidden = "GroupOverlay"

    @Test
    fun `ExpectationEvaluator carries no dependency on the group overlay`() {
        val stream = ExpectationEvaluator.javaClass.getResourceAsStream("ExpectationEvaluator.class")
        val bytes = stream?.readBytes() ?: error("cannot read the class file — this test proves nothing unless it can")

        // The constant pool holds the name of every class the code touches, as plain UTF-8.
        val constants = String(bytes, Charsets.ISO_8859_1)

        // A positive control, so the absence below means something. If the pool did not name the classes the
        // engine really does use, it would not name a forbidden one either, and this test would pass for ever
        // while proving nothing at all.
        assertTrue("Matcher" in constants, "the constant pool must name what the engine touches, or it names nothing")
        assertTrue("ScenarioReconcile" !in constants, "and the engine does not depend on the reconcile ops either")
        assertFalse(
            forbidden in constants,
            "ExpectationEvaluator has taken a dependency on $forbidden — assertion is positional, and a " +
                "pairing that consults a dictionary-derived structure is a second decider about what an " +
                "assertion refers to.",
        )
    }

    @Test
    fun `and its source does not so much as name it`() {
        val source = sourceOf("com/knapsack/fixtool/service/ExpectationEvaluator.kt")

        assertFalse(
            forbidden in source.readText(),
            "${source.name} mentions $forbidden. An import compiles away to nothing, so the class-file half of " +
                "this test would not have caught it — and the next commit is the one that uses it.",
        )
    }

    /** The overlay is allowed to know about the engine's world; it is the direction that matters. */
    @Test
    fun `the dependency runs the other way, and that is fine`() {
        val overlay = sourceOf("com/knapsack/fixtool/service/compare/GroupOverlay.kt").readText()

        assertTrue(
            "ScenarioReconcile" in overlay,
            "the overlay falls back to the row-order heuristic where the dictionary is silent — if it has " +
                "stopped doing so, the fallback has been lost and a venue's custom group no longer brackets",
        )
    }

    private fun sourceOf(path: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "composeApp/src/jvmMain/kotlin/$path")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("cannot find $path from ${System.getProperty("user.dir")} — this test proves nothing unless it can")
    }
}
