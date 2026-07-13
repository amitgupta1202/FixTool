package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.model.scenario.TemporalKind
import org.junit.Test
import quickfix.Message
import java.time.Instant
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Defects the full-implementation review found outside the reconcile view. */
class ReviewFindingsTest {
    private val dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)
    private val soh = '\u0001'

    private fun msg(raw: String, dir: FixMessage.Direction, second: Int) =
        FixMessage(
            timestamp = LocalDateTime.of(2026, 6, 30, 10, 0, second),
            direction = dir,
            rawMessage = raw,
            quickfixMessage = Message(),
            wireRaw = raw.replace('|', soh),
        )

    /**
     * Capture must correlate an echoed id **by tag**, not by raw value.
     *
     * `refByValue[capturedValue]` rewrote the matcher of every seeded row whose captured value merely *equalled*
     * a sent correlation id — across every tag in the message. A tester whose NewOrderSingle carries the utterly
     * ordinary `11=1` therefore got `Side(54)=1`, `OrdStatus(39)=1` and `ExecType(150)=1` all rewritten to
     * `Reference("${id0}")`. On replay `${id0}` is a fresh uuid, so the scenario asserts that Side equals a
     * uuid and is permanently red for a reason pointing at nothing the author wrote.
     *
     * The bind constraints three lines below already filter by `tag in ID_TAGS`. The matcher correlation did not.
     */
    @Test
    fun `capture correlates an echoed id by tag, not by any field that happens to share its value`() {
        val order = msg("8=FIX.4.4|35=D|11=1|55=EUR/USD|54=1|38=1000000|40=1|", FixMessage.Direction.OUTGOING, 1)
        val fill = msg("8=FIX.4.4|35=8|11=1|39=1|150=1|54=1|14=1000000|", FixMessage.Direction.INCOMING, 2)

        val scenario =
            ScenarioCapture.capture(
                id = "i",
                name = "n",
                profile = null,
                sessions = listOf(ScenarioCapture.CapturedSession("S", listOf(order, fill))),
                dictionary = dictionary,
            )
        val expect = scenario.steps.filterIsInstance<ScenarioStep.Expect>().single()
        val rows = expect.expectation.fields

        fun matcherFor(tag: Int): Matcher = rows.single { it.tag == tag }.matcher

        // The ClOrdID echo is the correlation, and it is wired.
        assertTrue(matcherFor(11) is Matcher.Reference, "the echoed ClOrdID must be a reference")

        // Nothing else is. These merely happen to be the string "1".
        for (tag in listOf(54, 39, 150)) {
            assertTrue(
                matcherFor(tag) !is Matcher.Reference,
                "tag $tag shares the ClOrdID's value by coincidence and must NOT become a reference: " +
                    "${matcherFor(tag)}",
            )
        }
        assertEquals(Matcher.Exact("1"), matcherFor(54), "Side is a value, not an id")
    }

    /**
     * A `UTCTIMEONLY` field seeded `~now ±60s` could never parse, so the row was hard-wired to fail.
     *
     * The seeder maps UTCTIMEONLY / TZTIMESTAMP / TIME to `Temporal(NOW_WITHIN_TOLERANCE)`, and the evaluator's
     * only patterns required a `yyyyMMdd-` prefix. So the matcher returned false on the very message it was
     * captured from, and on every run after that, with "~now ±60s" beside the correct value. The only repairs
     * the UI offers on a temporal row are Loosen and Drop — so the field silently stopped being checked. A red
     * that leads straight to deleted coverage.
     */
    @Test
    fun `a time-only value satisfies a temporal matcher, rather than failing for ever`() {
        val now = Instant.parse("2026-07-13T11:02:44Z")
        val temporal = Matcher.Temporal(TemporalKind.NOW_WITHIN_TOLERANCE, toleranceSeconds = 60)

        for (value in listOf("11:02:44", "11:02:44.123", "11:03:20")) {
            val results =
                ExpectationEvaluator.evaluate(
                    FixMessageView.ofFields(listOf(273 to value)),
                    Expectation(listOf(FieldExpectation(273, temporal))),
                    now = { now },
                )
            assertTrue(results.single().passed, "a time-only value within tolerance must pass: $value -> $results")
        }

        // ...and it is still a real check: a time-only value well outside the window fails.
        val stale =
            ExpectationEvaluator.evaluate(
                FixMessageView.ofFields(listOf(273 to "09:00:00")),
                Expectation(listOf(FieldExpectation(273, temporal))),
                now = { now },
            )
        assertTrue(!stale.single().passed, "two hours out is not 'now ±60s'")
    }

    /** A TZ-offset timestamp is a real FIX type too, and it must parse. */
    @Test
    fun `an offset-bearing timestamp satisfies a temporal matcher`() {
        val now = Instant.parse("2026-07-13T11:02:44Z")
        val temporal = Matcher.Temporal(TemporalKind.NOW_WITHIN_TOLERANCE, toleranceSeconds = 60)

        val results =
            ExpectationEvaluator.evaluate(
                FixMessageView.ofFields(listOf(60 to "20260713-12:02:44+01:00")), // the same instant, in +01:00
                Expectation(listOf(FieldExpectation(60, temporal))),
                now = { now },
            )
        assertTrue(results.single().passed, "an offset timestamp is the same moment: $results")
    }

    /**
     * The bind predicate and the assertion engine must answer "what is tag T?" the same way.
     *
     * The predicate read `FixMessage.valueOfTag`, which walks QuickFIX's parsed field maps and is blind to any
     * tag inside a repeating group — it answers null for one. The engine reads the wire. Two deciders for one
     * question, and the one the runner uses to decide WHICH MESSAGE a step binds to was the weaker.
     */
    @Test
    fun `a bind constraint on a grouped tag sees the same message the engine does`() {
        val wire = "8=FIX.4.4|35=8|11=ORD-1|453=1|448=FIRMA|447=D|452=1|"
        val message = msg(wire, FixMessage.Direction.INCOMING, 1)

        val view = FixMessageView.of(message)
        assertTrue(view != null)
        assertEquals(
            "FIRMA",
            view!!.fields().firstOrNull { it.first == 448 }?.second,
            "the engine sees the grouped PartyID",
        )
        // The QuickFIX-parsed view is the one that could not. (Left as a characterisation: the runner no
        // longer asks it.)
        assertNotEquals(
            "FIRMA",
            message.valueOfTag(448) ?: "<null>",
            "valueOfTag is group-blind — which is exactly why the runner must not use it",
        )
    }
}
