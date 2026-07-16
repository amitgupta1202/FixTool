package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.MatchPredicate
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.model.scenario.TagValue
import org.junit.Test
import kotlin.test.assertEquals

class ScenarioAnnotationsTest {
    private fun expect(vararg fields: FieldExpectation): ScenarioStep.Expect =
        ScenarioStep.Expect(expectation = Expectation(fields = fields.toList(), messageType = "8"))

    @Test
    fun `annotates mints and correlation references across steps`() {
        val steps = listOf(
            // mints id0; the now-expression must NOT count as a variable
            ScenarioStep.Send("35=D|11=\${id0 = UUID.randomUUID()}|60=\${LocalDateTime.now().format(f)}|", "A"),
            // echo checked by reference -> references id0
            expect(FieldExpectation(11, Matcher.Reference("\${id0}"))),
            // re-send referencing id0, minting id1
            ScenarioStep.Send("35=D|11=\${id1 = UUID.randomUUID()}|41=\${id0}|", "B"),
            // non-reference matchers reference nothing
            expect(FieldExpectation(60, Matcher.Presence)),
        )

        val vars = ScenarioAnnotations.annotate(steps)

        assertEquals(listOf("id0"), vars[0].minted)
        assertEquals(emptyList(), vars[0].referenced)
        assertEquals(listOf("id0"), vars[1].referenced)
        assertEquals(listOf("id1"), vars[2].minted)
        assertEquals(listOf("id0"), vars[2].referenced)
        assertEquals(ScenarioAnnotations.StepVars(emptyList(), emptyList()), vars[3])
    }

    @Test
    fun `expression-engine calls that look like variables are not badges`() {
        val steps = listOf(ScenarioStep.Send("35=D|11=\${orderId}|", "A")) // never minted anywhere
        assertEquals(emptyList(), ScenarioAnnotations.annotate(steps)[0].referenced)
    }

    /**
     * The bind predicate's `${id0}` is a correlation — the capture seeds `11=${id0}` precisely so the
     * step binds THIS run's reply — and it used to be invisible here: a step that binds by a minted id
     * but does not assert the echo showed no badge, so the flow view denied a correlation that was
     * doing all the work.
     */
    @Test
    fun `a bind constraint's reference is a badge, on Expect and on Wait`() {
        val steps = listOf(
            ScenarioStep.Send("35=D|11=\${id0 = UUID.randomUUID()}|", "A"),
            ScenarioStep.Expect(
                match = MatchPredicate(messageType = "8", fields = listOf(TagValue(11, "\${id0}"))),
                expectation = Expectation(fields = listOf(FieldExpectation(39, Matcher.Presence)), messageType = "8"),
            ),
            ScenarioStep.Wait(match = MatchPredicate(fields = listOf(TagValue(11, "\${id0}")))),
        )
        val vars = ScenarioAnnotations.annotate(steps)
        assertEquals(listOf("id0"), vars[1].referenced)
        assertEquals(listOf("id0"), vars[2].referenced)
    }

    /**
     * A bare `${name}` nothing mints is left LITERAL on the wire by the engine — a deliberate stance
     * (an error would make every non-variable expression a gamble) that turns a typo into a silent one.
     * [ScenarioAnnotations.unminted] is the authoring-time warning for it: bare names only, wherever a
     * reference can live (a Send's raw, a Reference matcher, a bind predicate), never engine calls,
     * never names that ARE minted.
     */
    @Test
    fun `unminted finds typo'd bare names everywhere a reference lives, and nothing else`() {
        val steps = listOf(
            // Typo'd bare name + an engine call that must not be flagged.
            ScenarioStep.Send("35=D|11=\${id0 = UUID.randomUUID()}|41=\${idO}|60=\${LocalDateTime.now()}|", "A"),
            // A Reference matcher on a name nothing mints.
            expect(FieldExpectation(11, Matcher.Reference("\${idX}"))),
            // A bind constraint on a name nothing mints — and a working reference that must not be flagged.
            ScenarioStep.Expect(
                match = MatchPredicate(messageType = "8", fields = listOf(TagValue(11, "\${idY}"), TagValue(41, "\${id0}"))),
                expectation = Expectation(fields = emptyList(), messageType = "8"),
            ),
        )
        assertEquals(listOf("idO", "idX", "idY"), ScenarioAnnotations.unminted(steps))
    }

    @Test
    fun `a scenario whose references all resolve has no unminted names`() {
        val steps = listOf(
            ScenarioStep.Send("35=D|11=\${id0 = UUID.randomUUID()}|", "A"),
            expect(FieldExpectation(11, Matcher.Reference("\${id0}"))),
        )
        assertEquals(emptyList(), ScenarioAnnotations.unminted(steps))
    }
}
