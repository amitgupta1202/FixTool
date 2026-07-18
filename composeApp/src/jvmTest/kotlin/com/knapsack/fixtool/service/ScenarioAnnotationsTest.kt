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

    /**
     * A `bindAs` mints from the venue's side of the wire: the Expect wears the filled badge, and a later
     * send's `${qr}` is a working reference — not a never-minted warning.
     */
    @Test
    fun `a bindAs capture is a mint — badged on the Expect, and never a warning`() {
        val steps = listOf(
            ScenarioStep.Expect(
                expectation = Expectation(
                    fields = listOf(FieldExpectation(131, Matcher.Presence, bindAs = "qr")),
                    messageType = "R",
                ),
            ),
            ScenarioStep.Send("35=S|131=\${qr}|", "A"),
        )
        val vars = ScenarioAnnotations.annotate(steps)
        assertEquals(listOf("qr"), vars[0].minted)
        assertEquals(listOf("qr"), vars[1].referenced)
        assertEquals(emptyList(), ScenarioAnnotations.unminted(steps))
    }

    /**
     * The badges say what THIS step does with a name; [ScenarioAnnotations.sites] is the other half —
     * who the counterpart is. That is the question a reader actually has, because it is the one that
     * decides whether a step is safe to delete or mute.
     */
    @Test
    fun `sites names both ends of a correlation`() {
        val steps = listOf(
            ScenarioStep.Send("35=D|11=\${id0 = UUID.randomUUID()}|", "A"),
            expect(FieldExpectation(11, Matcher.Reference("\${id0}"))),
            ScenarioStep.Send("35=F|41=\${id0}|", "A"),
        )
        val sites = ScenarioAnnotations.sites(steps).getValue("id0")
        assertEquals(listOf(0), sites.mintedAt)
        assertEquals(emptyList(), sites.capturedAt)
        assertEquals(listOf(1, 2), sites.referencedAt)
        assertEquals(false, sites.allWritesMuted)
    }

    /**
     * The two ways a variable gets a value are different acts, and the badges now say so: a Send mints
     * a value we chose, an Expect's `bindAs` captures one the venue chose. Same name, opposite origin.
     */
    @Test
    fun `sites separates a captured write from a minted one`() {
        val steps = listOf(
            ScenarioStep.Expect(
                expectation = Expectation(fields = listOf(FieldExpectation(131, Matcher.Presence, bindAs = "qr")), messageType = "R"),
            ),
            ScenarioStep.Send("35=S|131=\${qr}|", "A"),
        )
        val sites = ScenarioAnnotations.sites(steps).getValue("qr")
        assertEquals(emptyList(), sites.mintedAt)
        assertEquals(listOf(0), sites.capturedAt)
        assertEquals(listOf(0), sites.writtenAt)
    }

    /** A step's writes are all one kind, so the badge row needs one flag — and an Expect that captures nothing does not get it. */
    @Test
    fun `annotate marks a capturing step's writes as reply-side, and only a capturing step's`() {
        val steps = listOf(
            ScenarioStep.Send("35=D|11=\${id0 = UUID.randomUUID()}|", "A"),
            ScenarioStep.Expect(
                expectation = Expectation(fields = listOf(FieldExpectation(131, Matcher.Presence, bindAs = "qr")), messageType = "R"),
            ),
            expect(FieldExpectation(11, Matcher.Reference("\${id0}"))),
        )
        val vars = ScenarioAnnotations.annotate(steps)
        assertEquals(false, vars[0].fromReply)
        assertEquals(true, vars[1].fromReply)
        assertEquals(false, vars[2].fromReply)
    }

    /**
     * A name whose every write is parked does not get written on a run, so a live reference to it ships
     * the literal — the same judgement the variables strip makes, available to the badge that sits on
     * the referencing step.
     */
    @Test
    fun `sites flags a name whose only write is muted`() {
        val steps = listOf(
            ScenarioStep.Send("35=D|11=\${id0 = UUID.randomUUID()}|", "A", muted = true),
            ScenarioStep.Send("35=F|41=\${id0}|", "A"),
        )
        assertEquals(true, ScenarioAnnotations.sites(steps).getValue("id0").allWritesMuted)
    }

    /** One live write is enough: a second, parked write of the same name does not make it a hazard. */
    @Test
    fun `sites does not flag a name that keeps one live write`() {
        val steps = listOf(
            ScenarioStep.Send("35=D|11=\${id0 = UUID.randomUUID()}|", "A", muted = true),
            ScenarioStep.Send("35=D|11=\${id0 = UUID.randomUUID()}|", "A"),
            ScenarioStep.Send("35=F|41=\${id0}|", "A"),
        )
        val sites = ScenarioAnnotations.sites(steps).getValue("id0")
        assertEquals(listOf(0, 1), sites.mintedAt)
        assertEquals(false, sites.allWritesMuted)
    }

    /** Both kinds of write count as writes, and `writtenAt` puts them back in step order. */
    @Test
    fun `writtenAt merges mints and captures in step order`() {
        val steps = listOf(
            ScenarioStep.Expect(
                expectation = Expectation(fields = listOf(FieldExpectation(11, Matcher.Presence, bindAs = "id0")), messageType = "8"),
            ),
            ScenarioStep.Send("35=D|11=\${id0 = UUID.randomUUID()}|", "A"),
        )
        val sites = ScenarioAnnotations.sites(steps).getValue("id0")
        assertEquals(listOf(1), sites.mintedAt)
        assertEquals(listOf(0), sites.capturedAt)
        assertEquals(listOf(0, 1), sites.writtenAt)
    }

    /** A never-written name still gets an entry — with no write, which is what the warning wording needs. */
    @Test
    fun `sites keeps a never-written reference, with no writing step`() {
        val steps = listOf(ScenarioStep.Send("35=D|41=\${idO}|", "A"))
        val sites = ScenarioAnnotations.sites(steps).getValue("idO")
        assertEquals(emptyList(), sites.writtenAt)
        assertEquals(listOf(0), sites.referencedAt)
    }
}
