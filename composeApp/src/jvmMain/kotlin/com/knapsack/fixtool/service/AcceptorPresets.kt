package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.FieldCondition
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
 * "Reply With…" action all read. Same call [com.knapsack.fixtool.service.demo.DemoTemplatesProvider]
 * made for the demo server's messages.
 */
object AcceptorPresets {
    const val GROUP_BUNDLES = "Bundles"
    const val GROUP_ORDER_FLOW = "Order flow — 35=D"
    const val GROUP_CANCEL_REPLACE = "Cancel & replace"
    const val GROUP_REJECTS = "Rejects"

    /** The id of the bundle offered on an empty rule list. */
    const val STARTER_VENUE = "starter-venue"

    // ------------------------------------------------------------------ templates
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
    private fun executionReport(vararg fields: String): String =
        (listOf("35=8", "37=\${req.uuid}", "17=\${uuid}") + fields + "60=\${now}").joinToString("|")

    /** The fields an ExecutionReport echoes from the order it is about. */
    private const val ORDER_ECHO = "11=\${req.11}|55=\${req.55}|54=\${req.54}|38=\${req.38}"

    /** The same echo for a cancel or replace, which name the order they supersede. */
    private const val CANCEL_ECHO = "11=\${req.11}|41=\${req.41}|55=\${req.55}|54=\${req.54}"

    private val ACK =
        executionReport("150=0", "39=0", ORDER_ECHO, "14=0", "151=\${req.38}", "6=0")

    private val FILL =
        executionReport(
            "150=F", "39=2", ORDER_ECHO,
            "14=\${req.38}", "151=0", "31=\${req.44}", "32=\${req.38}", "6=\${req.44}",
        )

    // Integer halves, taken the same way twice, so CumQty + LeavesQty is OrderQty at every step for
    // any quantity — including an odd one, where 1001/2 and 1001-1001/2 are 500 and 501.
    private val PARTIAL_FILL =
        executionReport(
            "150=F", "39=1", ORDER_ECHO,
            "14=\${req.38 / 2}", "151=\${req.38 - req.38 / 2}",
            "31=\${req.44}", "32=\${req.38 / 2}", "6=\${req.44}",
        )

    private val FILL_REMAINDER =
        executionReport(
            "150=F", "39=2", ORDER_ECHO,
            "14=\${req.38}", "151=0",
            "31=\${req.44}", "32=\${req.38 - req.38 / 2}", "6=\${req.44}",
        )

    private val ORDER_REJECT =
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

    // ------------------------------------------------------------------ the catalogue

    private fun condition(tag: Int, matcher: Matcher) = FieldCondition(tag, MatcherCodec.matcherToJson(matcher))

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

    private val replaceAccepted =
        AcceptorResponseRule(
            whenMsgType = "G",
            // OrderQty is optional on a replace and this reply reads it, so the trigger requires it.
            conditions = listOf(condition(38, Matcher.Presence)),
            steps = listOf(ResponseStep(REPLACED)),
        )

    private val unsupportedMessage =
        AcceptorResponseRule(whenMsgType = "H", steps = listOf(ResponseStep(BUSINESS_REJECT)))

    /**
     * Every preset, in menu order.
     *
     * The bundle is first because it is the answer to the question an empty rule list poses. Its rules
     * are the four that make a coherent venue and, in this order, do not shadow one another: the
     * conditioned limit-order rule has to precede the unconditioned one, or first-match-wins would
     * never reach it.
     */
    val all: List<AcceptorPreset> =
        listOf(
            AcceptorPreset(
                id = STARTER_VENUE,
                name = "Starter venue",
                group = GROUP_BUNDLES,
                summary = "4 rules · D → ack + fill, F, G",
                rules = listOf(ackThenFill, orderAck, cancelAccepted, replaceAccepted),
            ),
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
                id = "cancel-accepted",
                name = "Cancel accepted",
                group = GROUP_CANCEL_REPLACE,
                summary = "35=F → pending, then canceled",
                rules = listOf(cancelAccepted),
            ),
            AcceptorPreset(
                id = "cancel-rejected",
                name = "Cancel rejected — unknown order",
                group = GROUP_CANCEL_REPLACE,
                summary = "35=F → 35=9 · 102=1 (UNKNOWN_ORDER)",
                rules = listOf(cancelRejected),
            ),
            AcceptorPreset(
                id = "replace-accepted",
                name = "Replace accepted",
                group = GROUP_CANCEL_REPLACE,
                summary = "35=G → 150=5 (REPLACED)",
                rules = listOf(replaceAccepted),
            ),
            AcceptorPreset(
                id = "unsupported-message",
                name = "Unsupported message",
                group = GROUP_REJECTS,
                summary = "35=H → 35=j · 380=3 (UNSUPPORTED)",
                rules = listOf(unsupportedMessage),
            ),
        )

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
            ReplyShape("order-reject", "Reject the order", "150=8 39=8 · 103=3 — with a reason in 58", "D", ORDER_REJECT),
            ReplyShape("pending-cancel", "Pending cancel", "150=6 39=6 — the cancel is accepted, not done", "F", PENDING_CANCEL),
            ReplyShape("canceled", "Canceled", "150=4 39=4 — the order is gone", "F", CANCELED),
            ReplyShape("cancel-reject", "Reject the cancel", "35=9 · 102=1 — unknown order", "F", CANCEL_REJECT),
            ReplyShape("replaced", "Replaced", "150=5 39=0 — the replacement is live", "G", REPLACED),
            ReplyShape("business-reject", "Business reject", "35=j · 380=3 — not supported here", "H", BUSINESS_REJECT),
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
        if (rule.trigger().isEmpty()) return rules.size
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
