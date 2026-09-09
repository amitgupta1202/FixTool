package com.knapsack.fixtool.perf

import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.ResponseStep
import com.knapsack.fixtool.service.AcceptorResponder
import com.knapsack.fixtool.service.ShorthandTemplateExpander
import com.knapsack.fixtool.service.load.CompiledTemplate
import org.junit.Test
import quickfix.Message
import kotlin.test.assertTrue

/**
 * **What one venue reply costs on the single dispatch thread, and whether a reply prototype would help.**
 *
 * `AcceptorDispatch` owns one thread, and every reply this venue sends is built on it: the resolve chain
 * (`${req.131}`, `${uuid}`, `${random:…}`, `${utcnow+30s}`) and then `AcceptorResponder.buildMessage`,
 * which parses the resolved line into a `quickfix.Message` through the same manual parser the ingest path
 * uses. One thread times one per-reply cost is the venue's reply ceiling, so this measures the cost and
 * asks the obvious follow-on question: the load runner does not parse per message, it clones a prototype
 * and sets the fields that vary (`CompiledTemplate.LanePrototype.render`). Should the acceptor do the
 * same?
 *
 * The third arm answers it by hand, with the best case a prototype scheme could reach: parse once, then
 * clone and `setString` the eight tags that move. It is deliberately **not** a production path: it is
 * here so the decision is made against a number rather than an intuition.
 *
 * The reply used to be far worse and no longer is: a single `${utcnow+1min}` reached the Kotlin script
 * engine, cost about 60ms per reply to compile, and held the RFQ venue to fourteen replies a second.
 * `resolveAtSendTime` renders every pure generator natively now, so nothing in this template touches the
 * engine and the numbers below are what remains.
 *
 * **Measured on this laptop, and the answer is no.**
 *
 * ```
 * plan()     once per trigger            6,288 B    11,302 ns
 * render()   the resolve chain          11,792 B    23,404 ns
 * build()    render, then parse         15,518 B    28,909 ns
 * a prototype: clone + 8 setString       3,557 B     6,171 ns
 * ```
 *
 * A prototype is 4.7x cheaper, and it is still not worth building:
 *
 * - **The parse is not the cost.** It is 3,730 B of the reply's 15,518 B, and 5.5µs of the 28.9µs above.
 *   The rest is the resolve chain's regex passes, so the change everyone reaches for first, stopping the
 *   parse per reply, addresses under a quarter of the problem. Read the allocation column for that split: a
 *   second run of this test put `render` a microsecond *above* `build`, which is what laptop timings do
 *   and what allocation counts never do.
 * - **The ceiling is already far above the need.** 28.9µs on one thread is about 34,600 replies a second.
 *   The load runner's own largest shape is a 4,000 burst, and its rate shapes are hundreds a second, so
 *   the reply path has roughly eight times the headroom of the fastest thing that can ask for it.
 * - **What it would cost.** Classifying a rule's template into fixed and varying slots the way
 *   `CompiledTemplate` does, a prototype cache keyed by template and invalidated whenever a rule is
 *   edited, and a third answer to what `${req.<tag>}` means, on the path that decides what a venue puts
 *   on the wire. That is a lot of new ways to send the wrong message in exchange for headroom nobody is
 *   using.
 *
 * If a rule's reply ever does become the constraint, the number to attack is `render()`, not the parse,
 * and the cheap version is guarding each resolve pass with the `contains` check `resolveAtSendTime`
 * already does before it touches a regex.
 */
class AcceptorReplyBenchmarkTest {
    private val dictionary = FixDictionaryAdapter.fromResource()

    /** The RFQ venue's own quote, as `RfqVenuePreset` writes it. */
    private val quoteTemplate =
        listOf(
            "35=S",
            "131=\${req.131}",
            "117=\${uuid}",
            "55=EUR/USD",
            "132=\${random:1.08490:1.08510:5}",
            "133=\${random:1.08510:1.08530:5}",
            "134=\${req.38}",
            "135=\${req.38}",
            "15=USD",
            "62=\${utcnow+30s}",
            "60=\${utcnow}",
        ).joinToString("|")

    private val rule =
        AcceptorResponseRule(
            whenMsgType = "R",
            steps = listOf(ResponseStep(quoteTemplate)),
        )

    private val request: Message =
        AcceptorResponder.buildMessage("35=R|131=RFQ-1|55=EUR/USD|38=1000000|54=1|", dictionary)

    /** The tags of [quoteTemplate] that carry a different value on every reply. */
    private val varying = listOf(131, 117, 132, 133, 134, 135, 62, 60)

    private val uuidGenerator = ShorthandTemplateExpander.generatorOf("uuid")!!
    private val nowGenerator = ShorthandTemplateExpander.generatorOf("utcnow")!!
    private val validGenerator = ShorthandTemplateExpander.generatorOf("utcnow+30s")!!
    private val bidGenerator = ShorthandTemplateExpander.generatorOf("random:1.08490:1.08510:5")!!
    private val offerGenerator = ShorthandTemplateExpander.generatorOf("random:1.08510:1.08530:5")!!

    @Test
    fun `what one reply costs, and what a prototype would save`() {
        val plan = { AcceptorResponder.plan(rule, request, dictionary = dictionary) }
        val planned = plan().single()

        val prototype = planned.build()
        val fromPrototype = {
            val message = prototype.clone() as Message
            message.setString(131, request.getString(131))
            message.setString(117, CompiledTemplate.generate(uuidGenerator))
            message.setString(132, CompiledTemplate.generate(bidGenerator))
            message.setString(133, CompiledTemplate.generate(offerGenerator))
            message.setString(134, request.getString(38))
            message.setString(135, request.getString(38))
            message.setString(62, CompiledTemplate.generate(validGenerator))
            message.setString(60, CompiledTemplate.generate(nowGenerator))
            message
        }

        println("\n┌─ One RFQ quote, on the acceptor's single dispatch thread")
        val planning = Bench.measure("plan()    once per trigger", ops = 200) { plan() }
        val rendering = Bench.measure("render()   the resolve chain", ops = 200) { planned.render() }
        val building = Bench.measure("build()    render, then parse", ops = 200) { planned.build() }
        val cloning = Bench.measure("a prototype: clone + 8 setString", ops = 200) { fromPrototype() }
        listOf(planning, rendering, building, cloning).forEach { println("│  " + it.render()) }
        // Attributed by allocation, not by subtracting the timings: `render` and `build` are within noise
        // of each other on a busy laptop, and one run of this test printed the parse as costing minus a
        // microsecond. Allocation is a count and does not move.
        val parseShare = building.bytesPerOp - rendering.bytesPerOp
        println(
            (
                "└─ the parse is %,d B of the reply's %,d B · one thread holds about %,d replies/s · " +
                    "a prototype would leave %,d B\n"
            ).format(parseShare, building.bytesPerOp, 1_000_000_000L / building.nanosPerOp, cloning.bytesPerOp),
        )

        // The prototype must be a real alternative, not a faster way to send something else: same tags,
        // same count. What it cannot do is change per reply, which is the argument in the report.
        val built = planned.build()
        val cloned = fromPrototype()
        varying.forEach { tag ->
            assertTrue(cloned.isSetField(tag), "the prototype arm must set every varying tag, missing $tag")
        }
        assertTrue(
            built.header.getString(35) == cloned.header.getString(35),
            "both arms must produce the same message type",
        )
    }
}
