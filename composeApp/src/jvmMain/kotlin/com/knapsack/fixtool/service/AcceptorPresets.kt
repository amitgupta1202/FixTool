package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.FieldCondition
import com.knapsack.fixtool.model.OrderConstraint
import com.knapsack.fixtool.model.ResponseStep
import com.knapsack.fixtool.model.scenario.Matcher

/**
 * One ready-made venue behaviour, offered by name and inserted as ordinary rules.
 *
 * [rules] is a list because a bundle is the same thing as a single preset with more in it — one menu,
 * one insert path, one set of tests. Nothing about a rule records that it arrived this way.
 */
data class AcceptorPreset(
    val id: String,
    val name: String,
    val group: String,
    /** The second line in the menu: the shape this produces, in the reader's terms. */
    val summary: String,
    val rules: List<AcceptorResponseRule>,
)

/**
 * One message an acceptor can answer a received message with, chosen by hand.
 *
 * [template] carries the same `${req.<tag>}` / `${uuid}` / `${now}` substitutions a rule's step does,
 * and is resolved by the same engine — see [AcceptorResponder.replyTo]. What arrives in the editor is
 * therefore the message the rules engine would have sent, for this message, at this moment.
 */
data class ReplyShape(
    val id: String,
    val name: String,
    /** The second line in the menu: what this says, in FIX. */
    val summary: String,
    /** The MsgType this answers. */
    val answers: String,
    val template: String,
)

/**
 * A [ReplyShape] as offered for one particular received message.
 *
 * [refusal] is why this shape cannot be built from *this* message — the tags it reads that the message
 * does not carry. It is not a rule being cautious: the value would substitute empty, `31=` would go on
 * the wire as a real field with no value, and the client would be blamed for the malformed message.
 * The offer is shown either way, because "Fill needs a price and this is a market order" is the answer
 * the tester came for; it just cannot be clicked.
 */
data class ReplyOffer(
    val shape: ReplyShape,
    val refusal: String? = null,
) {
    val available: Boolean get() = refusal == null
}

/**
 * The acceptor's shipped content: the common order flow, ready to insert.
 *
 * Everything needed to *author* a venue shipped with the rules engine. What did not ship is anything
 * to author it *from* — a fresh acceptor profile has no rules, so standing one up means knowing from
 * memory that an ExecutionReport owes tags 37, 17, 150, 39, 151, 14 and 6, and which of those the
 * incoming order can supply. These are that knowledge, written down and executable.
 *
 * ### The rule every template here obeys
 *
 * **A preset never reads a tag its own trigger does not guarantee.** `${req.44}` against a market
 * order substitutes an empty string, and the parser reads `31=` back as a real field with an empty
 * value — so the venue would send a malformed message and the client would be blamed for it. That is
 * survivable while an author writes and watches one rule; it is not survivable in something that
 * ships. So the fill presets are conditioned on `40 = 2` (a limit order carries a price), the replace
 * preset requires OrderQty to be present, and the cancel presets never read OrderQty at all.
 * `AcceptorPresetsTest` re-asserts it for every preset rather than trusting this list to stay
 * disciplined.
 *
 * ### Why these are code and not a bundled file
 *
 * A file would need loading, validating, versioning, and an answer for the user who edits it. These
 * need none of that, and they are the single source the menu, the control surface and (later) the
 * "Reply With…" action all read.
 *
 * Note that the bundled FX venue example went the other way and IS a file, because a copy of it
 * belongs to the user who opened it. These do not get copied — a preset is applied to a profile and
 * the rules it produces are what gets saved — so there is nothing here for a file to own.
 */
object AcceptorPresets {
    const val GROUP_BUNDLES = "Bundles"
    const val GROUP_ORDER_FLOW = "Order flow — 35=D"
    const val GROUP_CANCEL_REPLACE = "Cancel & replace"
    const val GROUP_REJECTS = "Rejects"

    /** The id of the bundle offered on an empty rule list. */
    const val STARTER_VENUE = "starter-venue"

    /** The id of the FX venue bundle — the one the demo workspace installs. See [FxVenuePreset]. */
    const val FX_VENUE = FxVenuePreset.ID

    // ------------------------------------------------------------------ templates
    //
    // Some of what follows is `internal` rather than `private`, and the reason is [FxVenuePreset]: a
    // second bundle in this module composes these rules **verbatim**, by name. Copying them there
    // instead would be two venues that agree today and drift tomorrow, and the drift would be silent —
    // a preset that stopped matching looks exactly like a venue that is working. Nothing leaves the
    // module; outside it the catalogue is still only [all].
    //
    // Written in the order a reader wants them: what the message is, who it is about, then what it
    // says. The identity block and the timestamp are the same in every ExecutionReport here, so they
    // are added rather than repeated — leaving each template's own line to be only what differs.

    /**
     * `${req.uuid}` is the OrderID and `${uuid}` the ExecID, and the difference is load-bearing: the
     * first is fixed when the trigger arrives and is therefore the *same* across every step of a
     * sequence, the second resolves as each step is sent. An ack and its fill carrying different
     * OrderIDs would be two unrelated orders to any client tracking tag 37.
     */
    internal fun executionReport(vararg fields: String): String =
        (listOf("35=8", "37=\${req.uuid}", "17=\${uuid}") + fields + "60=\${now}").joinToString("|")

    /** The fields an ExecutionReport echoes from the order it is about. */
    internal const val ORDER_ECHO = "11=\${req.11}|55=\${req.55}|54=\${req.54}|38=\${req.38}"

    /** The same echo for a cancel or replace, which name the order they supersede. */
    private const val CANCEL_ECHO = "11=\${req.11}|41=\${req.41}|55=\${req.55}|54=\${req.54}"

    internal val ACK =
        executionReport("150=0", "39=0", ORDER_ECHO, "14=0", "151=\${req.38}", "6=0")

    private val FILL =
        executionReport(
            "150=F", "39=2", ORDER_ECHO,
            "14=\${req.38}", "151=0", "31=\${req.44}", "32=\${req.38}", "6=\${req.44}",
        )

    // Integer halves, taken the same way twice, so CumQty + LeavesQty is OrderQty at every step for
    // any quantity — including an odd one, where 1001/2 and 1001-1001/2 are 500 and 501.
    internal val PARTIAL_FILL =
        executionReport(
            "150=F", "39=1", ORDER_ECHO,
            "14=\${req.38 / 2}", "151=\${req.38 - req.38 / 2}",
            "31=\${req.44}", "32=\${req.38 / 2}", "6=\${req.44}",
        )

    internal val FILL_REMAINDER =
        executionReport(
            "150=F", "39=2", ORDER_ECHO,
            "14=\${req.38}", "151=0",
            "31=\${req.44}", "32=\${req.38 - req.38 / 2}", "6=\${req.44}",
        )

    internal val ORDER_REJECT =
        executionReport(
            "150=8", "39=8", ORDER_ECHO,
            "14=0", "151=0", "6=0", "103=3", "58=Order exceeds the venue size limit",
        )

    private val PENDING_CANCEL =
        executionReport("150=6", "39=6", CANCEL_ECHO, "14=0", "151=0", "6=0")

    private val CANCELED =
        executionReport("150=4", "39=4", CANCEL_ECHO, "14=0", "151=0", "6=0")

    private val REPLACED =
        executionReport(
            "150=5", "39=0", CANCEL_ECHO, "38=\${req.38}",
            "14=0", "151=\${req.38}", "6=0",
        )

    // 37=NONE is the convention for an OrderID a venue cannot supply, which is exactly the case a
    // cancel reject is reporting. 434=1 says the reject answers a cancel request; 102=1 says why.
    private val CANCEL_REJECT =
        "35=9|37=NONE|11=\${req.11}|41=\${req.41}|39=8|434=1|102=1|58=Unknown order|60=\${now}"

    private const val BUSINESS_REJECT =
        "35=j|372=H|380=3|58=OrderStatusRequest is not supported by this venue"

    // 103=6 is OrdRejReason *Duplicate Order*, which is the whole of what this rule found out: the
    // ClOrdID naming this order is one the venue is already using for another.
    private val DUPLICATE_REJECT =
        executionReport(
            "150=8",
            "39=8",
            ORDER_ECHO,
            "14=0",
            "151=0",
            "6=0",
            "103=6",
            "58=ClOrdID is already in use on this session",
        )

    // 380=1 is BusinessRejectReason *Unknown ID*, and 379 says which id — the ClOrdID the client
    // asked about. A venue that supports status requests but has never heard of this order says
    // exactly this, and it is a different venue from the one below that supports none of them.
    private const val UNKNOWN_ORDER_REJECT =
        "35=j|372=H|379=\${req.11}|380=1|58=Unknown order"

    // ------------------------------------------------------------------ templates that read the book
    //
    // Everything below reads `${order.…}`, and each one is truthful in a way its stateless sibling
    // above cannot be. The difference is not decoration:
    //
    //   ack-partial-fill    14=${req.38 / 2}                       half the order, always, every time
    //   this one            14=${order.cumQty + order.leavesQty/2} half of what is actually left
    //
    // Two stateless partials in a row report the same 14= twice and a client tracking CumQty watches
    // the second fill undo the first. These accumulate, because each step reads the book *after* the
    // step before it reached the wire.
    //
    // They are strictly more fragile in exchange, which is why the stateless ones stay: on a venue
    // whose book was just cleared, or one pointed at a client mid-session, these do not fire at all.

    /**
     * An ExecutionReport about an order the venue is already holding.
     *
     * `37=${order.orderId}` rather than `${req.uuid}` is the whole point, and it is the defect named
     * at the top of the proposal: acknowledging an order and then filling it by hand draws two ids
     * for one order, and a client tracking tag 37 sees two unrelated orders. Read from the book, the
     * fill carries the id the ack already gave the client.
     *
     * Whether a *replacement* inherits its predecessor's id is a different question and deliberately
     * the author's — see decision 3a and [replaceAcceptedSameId].
     */
    private fun bookedReport(vararg fields: String): String =
        (listOf("35=8", "37=\${order.orderId}", "17=\${uuid}") + fields + "60=\${now}").joinToString("|")

    /** Echoed from the book rather than the request, so a reply is about the order and not the message. */
    private const val BOOK_ECHO = "11=\${order.clOrdId}|55=\${order.symbol}|54=\${order.side}|38=\${order.orderQty}"

    // Integer halves of what is *left*, taken the same way twice, so CumQty + LeavesQty is OrderQty
    // at every step for any remainder — including an odd one.
    private val PARTIAL_OF_REMAINDER =
        bookedReport(
            "150=F",
            "39=1",
            BOOK_ECHO,
            "14=\${order.cumQty + order.leavesQty / 2}",
            "151=\${order.leavesQty - order.leavesQty / 2}",
            "32=\${order.leavesQty / 2}",
            "31=\${order.price}",
            "6=\${order.price}",
        )

    private val FILL_WHAT_IS_LEFT =
        bookedReport(
            "150=F",
            "39=2",
            BOOK_ECHO,
            "14=\${order.orderQty}",
            "151=0",
            "32=\${order.leavesQty}",
            "31=\${order.price}",
            "6=\${order.price}",
        )

    /**
     * `150=I` is ExecType *Order Status*, which is exactly what an unsolicited state dump is — and
     * every field of it is a fact the book already holds. It needed no new primitive at all, which is
     * the clearest evidence that decision 1 was the right shape: the book is worth having because
     * rules can *read* it, not because it acts.
     */
    private val ORDER_STATUS =
        bookedReport(
            "150=I",
            "11=\${req.11}",
            "39=\${order.ordStatus}",
            "14=\${order.cumQty}",
            "151=\${order.leavesQty}",
            "55=\${order.symbol}",
            "54=\${order.side}",
            "38=\${order.orderQty}",
        )

    /**
     * The cancel reject a **finished** order earns: `102=0`, *Too late to cancel*.
     *
     * The distinction the unconditional reject cannot draw. `102=1` says "I have never heard of this
     * order", and saying that about an order the venue filled thirty seconds ago is a worse answer
     * than accepting the cancel would have been — it sends the client's error handling down the path
     * for a *lost* order rather than a *completed* one, which are different bugs to chase.
     *
     * Reads the book for both halves it can: the OrderID the client already has, in place of the
     * `37=NONE` a venue uses when it genuinely cannot supply one, and the order's real OrdStatus
     * rather than a hardcoded `39=8`.
     */
    private const val CANCEL_TOO_LATE =
        "35=9|37=\${order.orderId}|11=\${req.11}|41=\${req.41}|39=\${order.ordStatus}|434=1|102=0" +
            "|58=Too late to cancel|60=\${now}"

    /** A replacement that keeps the chain's OrderID, where [REPLACED] mints a new one. Decision 3a. */
    private val REPLACED_SAME_ID =
        bookedReport(
            "150=5",
            "39=0",
            CANCEL_ECHO,
            "38=\${req.38}",
            "14=\${order.cumQty}",
            "151=\${req.38}",
            "6=0",
        )

    // ------------------------------------------------------------------ the catalogue

    internal fun condition(tag: Int, matcher: Matcher) = FieldCondition(tag, MatcherCodec.matcherToJson(matcher))

    /** OrdType is Limit — which is what makes `${req.44}` safe to read. */
    private fun limitOrder() = listOf(condition(40, Matcher.Exact("2")))

    private val orderAck =
        AcceptorResponseRule(whenMsgType = "D", steps = listOf(ResponseStep(ACK)))

    private val ackThenFill =
        AcceptorResponseRule(
            whenMsgType = "D",
            conditions = limitOrder(),
            steps = listOf(ResponseStep(ACK), ResponseStep(FILL, delayMillis = 250)),
        )

    private val ackPartialFill =
        AcceptorResponseRule(
            whenMsgType = "D",
            conditions = limitOrder(),
            steps =
                listOf(
                    ResponseStep(ACK),
                    ResponseStep(PARTIAL_FILL, delayMillis = 250),
                    ResponseStep(FILL_REMAINDER, delayMillis = 250),
                ),
        )

    private val orderRejectOverSize =
        AcceptorResponseRule(
            whenMsgType = "D",
            // A venue rejects *some* orders, and a size limit is a real venue rule that needs no
            // naming convention to demonstrate — unlike "reject symbol XYZ", which needs the author
            // to know the convention before the rule means anything.
            conditions = listOf(condition(38, Matcher.Range(min = 1_000_000.0, minInclusive = false))),
            steps = listOf(ResponseStep(ORDER_REJECT)),
        )

    private val cancelAccepted =
        AcceptorResponseRule(
            whenMsgType = "F",
            steps = listOf(ResponseStep(PENDING_CANCEL), ResponseStep(CANCELED, delayMillis = 150)),
        )

    private val cancelRejected =
        AcceptorResponseRule(whenMsgType = "F", steps = listOf(ResponseStep(CANCEL_REJECT)))

    internal val replaceAccepted =
        AcceptorResponseRule(
            whenMsgType = "G",
            // OrderQty is optional on a replace and this reply reads it, so the trigger requires it.
            conditions = listOf(condition(38, Matcher.Presence)),
            steps = listOf(ResponseStep(REPLACED)),
        )

    private val unsupportedMessage =
        AcceptorResponseRule(whenMsgType = "H", steps = listOf(ResponseStep(BUSINESS_REJECT)))

    // ------------------------------------------------------------------ rules that ask the book
    //
    // Everything below carries a `whenOrder`, and each one is a venue behaviour that could not be
    // written before the book existed — not because the reply needed state, but because the *trigger*
    // did. They sit beside the stateless rules above rather than replacing them: those are the ones
    // that work with no history, which is the state a tester is in for the first five minutes of every
    // session, and a preset that silently needs state is a preset that silently does nothing.
    //
    // Note what none of them do: read the book to *build* a reply. That is slice C. These only ask.

    /**
     * The case the whole feature exists for. A cancel for an order that was never placed is answered
     * the way a real venue answers it, **without** taking every legitimate cancel down with it — which
     * is exactly what the unconditional reject above cannot avoid doing.
     */
    internal val cancelRejectedUnknown =
        AcceptorResponseRule(
            whenMsgType = "F",
            whenOrder = OrderConstraint.UNKNOWN,
            steps = listOf(ResponseStep(CANCEL_REJECT)),
        )

    /** Its other half: a cancel for an order the venue is actually holding gets accepted. */
    internal val cancelAcceptedWorking =
        AcceptorResponseRule(
            whenMsgType = "F",
            whenOrder = OrderConstraint.WORKING,
            steps = listOf(ResponseStep(PENDING_CANCEL), ResponseStep(CANCELED, delayMillis = 150)),
        )

    /**
     * The same acceptance for an order the venue has but has not answered yet — **decision 4's race,
     * given somewhere to land.**
     *
     * A cancel arriving 2ms after the order, before the ack has left the dispatch thread, reads
     * `pending`. That is the case `pending` was added to the vocabulary for, and without a rule for it
     * the cancel matches nothing and the venue says *nothing at all* — which is a worse answer than
     * any wrong one, because a client waiting on silence has no error path to take.
     */
    internal val cancelAcceptedPending =
        AcceptorResponseRule(
            whenMsgType = "F",
            whenOrder = OrderConstraint.PENDING,
            steps = listOf(ResponseStep(PENDING_CANCEL), ResponseStep(CANCELED, delayMillis = 150)),
        )

    /** A cancel for an order that has already finished — rejected as too late, not as unknown. */
    internal val cancelTooLate =
        AcceptorResponseRule(
            whenMsgType = "F",
            whenOrder = OrderConstraint.DONE,
            steps = listOf(ResponseStep(CANCEL_TOO_LATE)),
        )

    /**
     * A ClOrdID the venue is already using — **two rules, because "already in use" is two states**.
     *
     * `working` is the ordinary duplicate: the original was acknowledged and is live. `pending` is the
     * one a burst produces, where the same id arrives twice before the venue has answered either. Both
     * are duplicates and the client's error handling for them is the same, but the vocabulary has no
     * word for "either", and inventing one to save a card would cost the four words their whole point
     * (decision 4). A duplicate of a *finished* order is deliberately not here: plenty of venues accept
     * that, and the author who wants it rejected adds the same rule with `done`.
     *
     * Neither can be written as "the book already holds this ClOrdID", because by the time any rule is
     * asked the book holds it either way — the arrival of the order booked it. What separates a
     * duplicate from a new order is what the venue held *before* it, which is what `whenOrder` reads
     * (decision 4a).
     */
    internal val duplicateWorking =
        AcceptorResponseRule(
            whenMsgType = "D",
            whenOrder = OrderConstraint.WORKING,
            steps = listOf(ResponseStep(DUPLICATE_REJECT)),
        )

    internal val duplicatePending =
        AcceptorResponseRule(
            whenMsgType = "D",
            whenOrder = OrderConstraint.PENDING,
            steps = listOf(ResponseStep(DUPLICATE_REJECT)),
        )

    /**
     * "Where is my order?" answered for an order the venue does not have.
     *
     * Distinct from [unsupportedMessage], which is a venue that does not answer status requests at
     * all — the same MsgType, two entirely different venues, and conditioning the one into the other
     * would have left neither available. This is the half of the pair slice B can write; the other
     * half — the same request answered *from* the book for an order it holds — needs `${order.…}` and
     * lands with slice C.
     */
    internal val statusRequestUnknown =
        AcceptorResponseRule(
            whenMsgType = "H",
            whenOrder = OrderConstraint.UNKNOWN,
            steps = listOf(ResponseStep(UNKNOWN_ORDER_REJECT)),
        )

    // ------------------------------------------------------------------ rules that read the book

    /**
     * **Fills that accumulate**, and the demonstration that a sequence's steps see each other.
     *
     * Each step reads the book *after* the one before it reached the wire, so a 1000-share order
     * fills 500, then 250, then 125 — every report consistent with the last, which two stateless
     * partials in a row can never be.
     *
     * Note what conditions it: **nothing**. A `35=D` is the message that *creates* the order, so by
     * the time step two is built the venue is holding it — even though `whenOrder` a moment earlier
     * would have read `unknown` (decision 4a). That is why [AcceptorResponseRule.willHaveAnOrder]
     * counts a birth-type trigger as a guarantee; without it this rule could not be written at all.
     *
     * Step one is the ordinary stateless ack, because there is nothing in the book to read yet — it
     * is the message that puts something there.
     */
    private val ackThenAccumulatingFills =
        AcceptorResponseRule(
            whenMsgType = "D",
            conditions = limitOrder(),
            steps =
                listOf(
                    ResponseStep(ACK),
                    ResponseStep(PARTIAL_OF_REMAINDER, delayMillis = 250),
                    ResponseStep(PARTIAL_OF_REMAINDER, delayMillis = 250),
                    ResponseStep(FILL_WHAT_IS_LEFT, delayMillis = 250),
                ),
        )

    /**
     * "Where is my order?" answered *from* the book — the other half of [statusRequestUnknown].
     *
     * Between them a venue answers the question the way a venue does, and neither needed a primitive
     * that did not already exist.
     *
     * **Two rules, and the state that is missing is the point.** `working` and `done` are the two the
     * venue can answer: both mean it has already reported on the order, so it has an OrderID and a
     * CumQty to quote. `pending` means the venue is holding the order and has told the client
     * *nothing* — there is no id to put in `37` yet — so a rule conditioned that way would refuse at
     * send time rather than reply. Leaving it out is the structural version of the same refusal, and
     * a venue that wants to answer a pending order has to decide what to say about an order it has
     * never named, which is its author's call and not a preset's.
     *
     * Found by live verification: the first version shipped `working` alone, and a status request for
     * a filled order fell through to the unknown-order reject — the venue disowning an order it was
     * holding.
     */
    internal val statusRequestWorking =
        AcceptorResponseRule(
            whenMsgType = "H",
            whenOrder = OrderConstraint.WORKING,
            steps = listOf(ResponseStep(ORDER_STATUS)),
        )

    internal val statusRequestDone =
        AcceptorResponseRule(
            whenMsgType = "H",
            whenOrder = OrderConstraint.DONE,
            steps = listOf(ResponseStep(ORDER_STATUS)),
        )

    /**
     * The replace that keeps one OrderID for the life of the chain.
     *
     * Beside [replaceAccepted], which mints a new one, because **venues disagree and both are venues
     * someone needs to simulate** — plenty keep the id, several crypto exchanges and some futures
     * venues issue a new one on every replace. Decision 3a is that the fold records whichever the
     * author's template sent and cannot tell them apart; these two presets are that decision made
     * choosable rather than assumed.
     */
    internal val replaceAcceptedSameId =
        AcceptorResponseRule(
            whenMsgType = "G",
            whenOrder = OrderConstraint.WORKING,
            conditions = listOf(condition(38, Matcher.Presence)),
            steps = listOf(ResponseStep(REPLACED_SAME_ID)),
        )

    /**
     * Every preset, in menu order.
     *
     * The bundle is first because it is the answer to the question an empty rule list poses. Its rules
     * are the ones that make a coherent venue and, in this order, do not shadow one another: the
     * conditioned limit-order rule has to precede the unconditioned one, or first-match-wins would
     * never reach it.
     *
     * ### Why the starter venue answers a cancel four ways
     *
     * It used to answer with one unconditional rule, and that rule was **the defect issue #35 was
     * filed about**: a cancel for an order nobody ever placed came back "canceled". Three slices of
     * order state shipped with that still true out of the box, because the conditioned rules sat in
     * the menu waiting to be found. A starter bundle is the only place most people will ever read a
     * venue's rules, so it is the only honest teaching surface — and it was teaching the wrong thing.
     *
     * All four states, because leaving one out means a cancel that matches nothing and a venue that
     * says **nothing at all**, which is worse than any wrong answer: a client waiting on silence has
     * no error path to take. `done` in particular is not an edge case here — this bundle fills limit
     * orders 250ms after acking them, so by the time a human types a cancel the order is finished,
     * and `done` is the state a cancel most often finds.
     *
     * The order below is deliberately the reverse of how it reads on screen: [insert] places each
     * conditioned rule above the first rule for its MsgType, so listing them backwards is what
     * makes the card list read `unknown, pending, working, done`. A test pins that, so the
     * arrangement cannot drift into nonsense unnoticed.
     */
    /**
     * **`by lazy`, and it is load-bearing.** [FxVenuePreset] composes this object's rules by name, and
     * this list holds that bundle — a cycle, and an eager `val` here resolves it in exactly one
     * direction. Touch `AcceptorPresets` first and all is well; touch `FxVenuePreset` first and its own
     * initialiser reaches back here, which runs *this* list, which reads a `preset` that is still being
     * built — a null in the catalogue, or a StackOverflowError, decided by whichever class the JVM
     * happened to load first. Deferring the list means neither object is ever observed half-built.
     *
     * The rule that keeps it safe: **nothing eagerly initialised in this object may read [all]**.
     */
    val all: List<AcceptorPreset> by lazy {
        listOf(
            AcceptorPreset(
                id = STARTER_VENUE,
                name = "Starter venue",
                group = GROUP_BUNDLES,
                summary = "7 rules · D → ack + fill · F answered from the book · G",
                rules =
                    listOf(
                        ackThenFill,
                        orderAck,
                        cancelTooLate,
                        cancelAcceptedWorking,
                        cancelAcceptedPending,
                        cancelRejectedUnknown,
                        replaceAccepted,
                    ),
            ),
            FxVenuePreset.preset,
            AcceptorPreset(
                id = "order-ack",
                name = "Order acknowledged",
                group = GROUP_ORDER_FLOW,
                summary = "35=8 · 150=0 (NEW) 39=0 (NEW)",
                rules = listOf(orderAck),
            ),
            AcceptorPreset(
                id = "ack-then-fill",
                name = "Acknowledged, then filled",
                group = GROUP_ORDER_FLOW,
                summary = "2 steps · +0ms, +250ms",
                rules = listOf(ackThenFill),
            ),
            AcceptorPreset(
                id = "ack-partial-fill",
                name = "Acknowledged, partial, then filled",
                group = GROUP_ORDER_FLOW,
                summary = "3 steps · +0ms, +250ms, +250ms",
                rules = listOf(ackPartialFill),
            ),
            AcceptorPreset(
                id = "order-reject-size",
                name = "Order rejected — over size limit",
                group = GROUP_ORDER_FLOW,
                summary = "when 38 > 1000000 · 39=8 (REJECTED)",
                rules = listOf(orderRejectOverSize),
            ),
            AcceptorPreset(
                id = "ack-accumulating-fills",
                name = "Acknowledged, then filled from the book",
                group = GROUP_ORDER_FLOW,
                summary = "4 steps · each fill reads what the last one left",
                rules = listOf(ackThenAccumulatingFills),
            ),
            AcceptorPreset(
                id = "duplicate-clordid",
                name = "Duplicate ClOrdID rejected",
                group = GROUP_ORDER_FLOW,
                summary = "when the id is already live · 39=8 · 103=6 (DUPLICATE)",
                rules = listOf(duplicateWorking, duplicatePending),
            ),
            AcceptorPreset(
                id = "cancel-accepted",
                name = "Cancel accepted — always",
                group = GROUP_CANCEL_REPLACE,
                summary = "35=F → pending, then canceled, whatever the book holds",
                rules = listOf(cancelAccepted),
            ),
            AcceptorPreset(
                id = "cancel-accepted-working",
                name = "Cancel accepted — the order is working",
                group = GROUP_CANCEL_REPLACE,
                summary = "when the book holds it live · pending, then canceled",
                rules = listOf(cancelAcceptedWorking),
            ),
            // Renamed rather than replaced. Beside the conditioned rule below it, "Cancel rejected —
            // unknown order" was a name this one had never earned: it rejects every cancel, including
            // the legitimate ones, which is the very thing the issue opens by complaining about.
            AcceptorPreset(
                id = "cancel-rejected",
                name = "Cancel rejected — always",
                group = GROUP_CANCEL_REPLACE,
                summary = "35=F → 35=9 · 102=1, whatever the book holds",
                rules = listOf(cancelRejected),
            ),
            AcceptorPreset(
                id = "cancel-rejected-unknown",
                name = "Cancel rejected — unknown order",
                group = GROUP_CANCEL_REPLACE,
                summary = "when the book has never seen it · 35=9 · 102=1 (UNKNOWN_ORDER)",
                rules = listOf(cancelRejectedUnknown),
            ),
            AcceptorPreset(
                id = "replace-accepted",
                name = "Replace accepted — a new OrderID",
                group = GROUP_CANCEL_REPLACE,
                summary = "35=G → 150=5 · 37 is minted fresh",
                rules = listOf(replaceAccepted),
            ),
            AcceptorPreset(
                id = "replace-accepted-same-id",
                name = "Replace accepted — the chain keeps its OrderID",
                group = GROUP_CANCEL_REPLACE,
                summary = "35=G → 150=5 · 37 is the one the client already has",
                rules = listOf(replaceAcceptedSameId),
            ),
            AcceptorPreset(
                id = "unsupported-message",
                name = "Unsupported message",
                group = GROUP_REJECTS,
                summary = "35=H → 35=j · 380=3 (UNSUPPORTED)",
                rules = listOf(unsupportedMessage),
            ),
            AcceptorPreset(
                id = "status-request-unknown",
                name = "Status request — unknown order",
                group = GROUP_REJECTS,
                summary = "when the book has never seen it · 35=j · 380=1 (UNKNOWN_ID)",
                rules = listOf(statusRequestUnknown),
            ),
            AcceptorPreset(
                id = "status-request-working",
                name = "Status request — answered from the book",
                group = GROUP_ORDER_FLOW,
                summary = "2 rules · working and done · 150=I with the real quantities",
                rules = listOf(statusRequestWorking, statusRequestDone),
            ),
        )
    }

    fun byId(id: String): AcceptorPreset? = all.firstOrNull { it.id == id }

    /** Preset ids, for naming what an unknown one could have meant. */
    val ids: List<String> get() = all.map { it.id }

    // ------------------------------------------------------------------ replying by hand

    /**
     * The same templates, offered one message at a time — what "Reply With…" answers a received
     * message with.
     *
     * **A shape is a step, where a preset is a rule.** A rule carries a trigger and may reply with a
     * sequence, because it has to decide for messages that have not arrived yet. Replying by hand has
     * the message in front of it and sends one thing, so the trigger is the author's own choice of
     * message and the sequence is them choosing twice. Every template here is one a preset already
     * plays, referenced rather than re-typed: a fill that drifted from the fill the rules engine sends
     * would make the manual path a different venue from the automatic one.
     *
     * [answers] is the MsgType this replies to, and is the whole of what filters the menu. The
     * conditions the *presets* carry (`40 = 2` on the fill ones, OrderQty on the replace) have no
     * counterpart here: a trigger has to be conservative because it is answering messages it has not
     * seen, while this can simply look at the message and say what it cannot read — see
     * [AcceptorResponder.offersFor].
     */
    val replyShapes: List<ReplyShape> =
        listOf(
            ReplyShape("ack", "Acknowledge", "150=0 (NEW) 39=0 — the order is live", "D", ACK),
            ReplyShape("fill", "Fill", "150=F 39=2 — the whole quantity, at the order's price", "D", FILL),
            ReplyShape("partial-fill", "Partial fill", "150=F 39=1 — half now, half still working", "D", PARTIAL_FILL),
            ReplyShape("fill-remainder", "Fill the remainder", "150=F 39=2 — the half a partial left", "D", FILL_REMAINDER),
            // The two that read the book, and the reason #40 needed slice C. Replying by hand is
            // exactly where the identity defect bit: an ack draws one OrderID and a fill sent a minute
            // later draws another, and the client is watching two unrelated orders. These carry the id
            // the client already has, and fill what is actually left rather than what a stateless
            // template guessed. Refused, greyed out with the reason, when the venue holds no order —
            // see AcceptorResponder.offersFor.
            ReplyShape(
                "partial-of-remainder",
                "Partial fill — half of what is left",
                "150=F 39=1 · reads the book · 37 is the id the client has",
                "D",
                PARTIAL_OF_REMAINDER,
            ),
            ReplyShape(
                "fill-what-is-left",
                "Fill what is left",
                "150=F 39=2 · reads the book · 37 is the id the client has",
                "D",
                FILL_WHAT_IS_LEFT,
            ),
            ReplyShape("order-reject", "Reject the order", "150=8 39=8 · 103=3 — with a reason in 58", "D", ORDER_REJECT),
            ReplyShape("pending-cancel", "Pending cancel", "150=6 39=6 — the cancel is accepted, not done", "F", PENDING_CANCEL),
            ReplyShape("canceled", "Canceled", "150=4 39=4 — the order is gone", "F", CANCELED),
            ReplyShape("cancel-reject", "Reject the cancel", "35=9 · 102=1 — unknown order", "F", CANCEL_REJECT),
            ReplyShape("replaced", "Replaced", "150=5 39=0 — the replacement is live", "G", REPLACED),
            ReplyShape("business-reject", "Business reject", "35=j · 380=3 — not supported here", "H", BUSINESS_REJECT),
            ReplyShape(
                "order-status",
                "Order status",
                "150=I · reads the book · where the order actually is",
                "H",
                ORDER_STATUS,
            ),
        )

    fun shapeById(id: String): ReplyShape? = replyShapes.firstOrNull { it.id == id }

    // ------------------------------------------------------------------ placement

    /**
     * Where [rule] has to go for first-match-wins to actually reach it.
     *
     * Appending is right for a rule with **no** conditions: it answers every message of its type, so
     * nothing after it for that type would have been reachable anyway, and putting it first would take
     * the whole type from the rules already there.
     *
     * A **conditioned** rule goes above the first enabled rule for its MsgType — whichever it is. Above
     * an unconditioned rule because otherwise it can never fire at all. Above a conditioned one for the
     * weaker but more common reason: an author adding a specific behaviour to a type that already has
     * one means the specific one to happen. Live-verified the hard way — adding the size-limit reject
     * to a starter venue placed it *below* the ack-and-fill rule, so a two-million-share limit order
     * filled rather than rejecting. It was reachable (a market order of that size would have reached
     * it) and it was still the wrong answer to the question the author had just asked.
     *
     * Nothing here **claims** the earlier rule overlaps this one — that is not decidable, which is why
     * [AcceptorResponder.shadowingRule] refuses to guess at it. This is a default position, chosen so
     * the common case does what was meant; every rule keeps its up/down arrows.
     *
     * A disabled rule does not displace anything: it is skipped at match time, so it takes nothing.
     */
    fun placementFor(rules: List<AcceptorResponseRule>, rule: AcceptorResponseRule): Int {
        if (rule.isUnconditional()) return rules.size
        val blocking =
            rules.indexOfFirst { earlier ->
                earlier.enabled &&
                    earlier.whenMsgType.isNotBlank() &&
                    earlier.whenMsgType == rule.whenMsgType
            }
        return if (blocking >= 0) blocking else rules.size
    }

    /**
     * [rules] with [preset]'s rules inserted, each where [placementFor] says it can fire.
     *
     * [placedAbove] and [note] describe the first rule that had to displace something, and are null
     * when everything simply appended — the common case, and one that deserves no explanation.
     */
    data class Insertion(
        val rules: List<AcceptorResponseRule>,
        val index: Int,
        /** How many rules the preset contributed — one for most, four for the starter venue. */
        val added: Int,
        val placedAbove: Int? = null,
        val note: String? = null,
    )

    fun insert(rules: List<AcceptorResponseRule>, preset: AcceptorPreset): Insertion {
        var current = rules
        var firstIndex: Int? = null
        var placedAbove: Int? = null
        preset.rules.forEach { rule ->
            val at = placementFor(current, rule)
            if (at < current.size && placedAbove == null) placedAbove = at
            current = current.toMutableList().apply { add(at, rule) }
            if (firstIndex == null) firstIndex = at
        }
        val above = placedAbove
        return Insertion(
            rules = current,
            index = firstIndex ?: current.size,
            added = preset.rules.size,
            placedAbove = above,
            note =
                above?.let {
                    "inserted above rule ${it + 1}, which also answers 35=${preset.rules.first().whenMsgType}"
                },
        )
    }
}
