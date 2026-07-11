package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.ScenarioStep
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
}
