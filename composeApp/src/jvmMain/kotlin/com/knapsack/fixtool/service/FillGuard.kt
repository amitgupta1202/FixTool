package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.OrderEvent
import com.knapsack.fixtool.model.TAG_EXEC_TYPE
import java.time.LocalDateTime

/**
 * **Whether a fill a reply queued has been overtaken by the time it is due.**
 *
 * A rule's steps are scheduled when its trigger arrives, so a fill queued 250ms out cannot know that a cancel
 * will arrive at 100ms and be accepted. A client told its order is canceled must not then be told it filled.
 * So each fill step asks the book as it falls due: has the venue accepted a cancel or a replace of this order
 * since the trigger came in? The acceptance is something the book holds by then, recorded from the wire like
 * everything else in it.
 *
 * Never asked of a reply that itself accepts a cancel or a replace, because the acceptance it would find is its
 * own and its later steps are the rest of that answer. An ExecType written as an expression is neither a fill nor
 * an acceptance, so a step that computes its 150 is never withdrawn.
 */
internal object FillGuard {
    /** ExecTypes that report a fill: Trade, and the Partial fill and Fill of FIX before 4.3. */
    private val FILL_EXEC_TYPES = setOf("F", "1", "2")

    /** ExecTypes that say the venue has accepted a cancel or a replace: pending or done, of either. */
    private val ACCEPTANCE_EXEC_TYPES = setOf("6", "4", "E", "5")

    /** The answer for a step nothing can overtake. */
    private val NEVER_WITHDRAWN: () -> Boolean = { false }

    /**
     * For each step of [rule]'s reply, a question to ask as that step falls due: true when it reports a fill of
     * the order [orderKey] names and [events] for that order show the venue accepted a cancel or a replace after
     * [since]. The templates are read once here, for the whole reply, since this runs on the callback thread.
     */
    fun forReply(
        rule: AcceptorResponseRule,
        orderKey: String?,
        since: LocalDateTime,
        events: (String) -> List<OrderEvent>,
    ): (Int) -> () -> Boolean {
        val execTypes = rule.sequence().map { execTypeOf(it.template) }
        val key = orderKey?.takeUnless { execTypes.any { it in ACCEPTANCE_EXEC_TYPES } } ?: return { NEVER_WITHDRAWN }
        val overtaken = {
            events(key).any { event ->
                event.sent && event.execType in ACCEPTANCE_EXEC_TYPES && event.at.isAfter(since)
            }
        }
        return { step -> if (execTypes.getOrNull(step) in FILL_EXEC_TYPES) overtaken else NEVER_WITHDRAWN }
    }

    /** The ExecType (150) a reply template writes, as written. */
    private fun execTypeOf(template: String): String? =
        FixMessageHelper
            .parseFixMessage(template)
            .firstOrNull { (tag, _) -> tag == TAG_EXEC_TYPE }
            ?.second
            ?.trim()
}
