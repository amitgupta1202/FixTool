package com.knapsack.fixtool.service.demo

import com.knapsack.fixtool.model.scenario.BindScope
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.MatchPredicate
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioStep

/**
 * **One scenario that is green on a fresh install**, shipped as code the way the demo templates are.
 *
 * The point is reproducibility. A video whose steps a viewer cannot repeat is a demo; a video whose
 * every step is a saved scenario anyone can run is a product. So this is not a sample to read — it is
 * the flow the presenter runs, and it goes green in the first minute after Start.
 *
 * ### Why it is code and not a bundled file
 *
 * Same call [DemoTemplatesProvider] and `AcceptorPresets` made: a file would need loading, validating,
 * versioning and an answer for the user who edits it. This needs none of that, and it is deleted on
 * Stop along with everything else demo-prefixed.
 */
object DemoScenarioProvider {
    /** Prefix for demo scenario ids, so cleanup can find them and user scenarios are never touched. */
    const val DEMO_SCENARIO_PREFIX = "demo-scenario-"

    const val LIFECYCLE_ID = "${DEMO_SCENARIO_PREFIX}eurusd-lifecycle"

    /**
     * **A fixed ClOrdID, not `${uuid}`** — and that is the teaching, not an oversight.
     *
     * It is what makes the `ClearOrderBook` setup step load-bearing. Run this twice without it and the
     * venue answers the second run out of the first run's memory: the duplicate rule fires and the
     * order is rejected where it was acknowledged a moment ago. The scenario has not changed, the venue
     * has not changed, and the run is red — which is the defect `ScenarioOrderBookIntegrationTest` was
     * written for. A fresh install ships with the fix already in the setup, where anyone opening the
     * scenario can see it.
     */
    private const val ORDER_ID = "DEMO-LIFECYCLE-1"

    /** The client leg. Named by profile, which is the pane title the runner resolves. */
    private val client = DemoServerManager.clientName(0)

    /** The venue leg — the per-client pane, which is the only session that owns a book to clear. */
    private val venuePane = DemoServerManager.venuePaneFor(DemoServerManager.DEMO_CLIENTS.first())

    fun scenarios(): List<Scenario> = listOf(lifecycle())

    fun scenarioIds(): List<String> = listOf(LIFECYCLE_ID)

    fun isDemoScenario(scenarioId: String): Boolean = scenarioId.startsWith(DEMO_SCENARIO_PREFIX)

    /**
     * A limit order acknowledged, half-filled, filled, and then a cancel that arrives too late.
     *
     * ### Why a limit order and not a market one
     *
     * The venue's market prices **jitter by design**, so an expectation carrying a price would be red
     * on the second run. A limit order fills at `${req.44}` — its own price — so this flow is the one
     * whose every asserted field is deterministic, and it can therefore assert the price too. That is
     * worth having: "filled at the price I asked for" is the assertion a viewer understands instantly.
     *
     * ### Why the assertions are hand-written
     *
     * Not a captured golden. A golden seeded off one run pins the ExecIDs, the timestamps and the
     * venue's own OrderID, and would need repairing before it could pass a second time. These name the
     * handful of fields the flow is actually about.
     *
     * ### Why the cancel is expected to fail
     *
     * By the time the cancel goes out the order is filled, and the honest answer to "cancel a completed
     * order" is `102=0` — *too late to cancel* — not `102=1`, *unknown order*. Those send a client's
     * error handling down two different paths, and the difference is exactly what the order-state book
     * bought. Ending the demo on it shows a venue that knows what it is holding.
     */
    private fun lifecycle(): Scenario =
        Scenario(
            id = LIFECYCLE_ID,
            name = "EUR/USD order lifecycle",
            // THIS_RUN so an expectation cannot bind a reply from the previous run and pass on it.
            binding = BindScope.THIS_RUN,
            setup =
                listOf(
                    ScenarioStep.ClearMessages(client),
                    ScenarioStep.ClearOrderBook(venuePane),
                ),
            steps =
                listOf(
                    ScenarioStep.Send(
                        "35=D|11=$ORDER_ID|55=EUR/USD|54=1|38=1000000|40=2|44=1.08950|60=\${now}",
                        session = client,
                    ),
                    // Three reports, consumed in the order they arrive — the runner advances a cursor
                    // past each matched message, so these do not need a predicate to tell them apart.
                    //
                    // **The rows within each one are in ascending tag order, and that is required, not
                    // tidy.** An expectation is evaluated as a *subsequence* of the reply: each row is
                    // paired with the next occurrence after the row before it. List 150 ahead of 39 and
                    // the 39 row finds nothing left to pair with, and reports MOVED — the venue is fine
                    // and the expectation describes a message nobody sent. A flat FieldMap goes on the
                    // wire in tag order, so ascending is that order.
                    expectReport(39 to "0", 150 to "0", 151 to "1000000"),
                    expectReport(14 to "500000", 31 to "1.08950", 39 to "1", 150 to "F", 151 to "500000"),
                    expectReport(14 to "1000000", 31 to "1.08950", 39 to "2", 150 to "F", 151 to "0"),
                    ScenarioStep.Send(
                        "35=F|11=DEMO-CANCEL-1|41=$ORDER_ID|55=EUR/USD|54=1|60=\${now}",
                        session = client,
                    ),
                    ScenarioStep.Expect(
                        session = client,
                        match = MatchPredicate(messageType = "9"),
                        timeoutMs = 10_000,
                        expectation =
                            Expectation(
                                messageType = "9",
                                // Ascending, for the subsequence reason above.
                                fields =
                                    listOf(
                                        FieldExpectation(41, Matcher.Exact(ORDER_ID)),
                                        FieldExpectation(102, Matcher.Exact("0")),
                                    ),
                            ),
                    ),
                ),
        )

    /** One ExecutionReport the client must receive, asserted on the fields the flow is about. */
    private fun expectReport(vararg fields: Pair<Int, String>): ScenarioStep.Expect =
        ScenarioStep.Expect(
            session = client,
            match = MatchPredicate(messageType = "8"),
            timeoutMs = 10_000,
            expectation =
                Expectation(
                    messageType = "8",
                    fields = fields.map { (tag, value) -> FieldExpectation(tag, Matcher.Exact(value)) },
                ),
        )
}
