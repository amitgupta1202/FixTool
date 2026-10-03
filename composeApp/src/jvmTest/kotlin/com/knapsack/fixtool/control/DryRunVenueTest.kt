package com.knapsack.fixtool.control

import com.knapsack.fixtool.model.Counterparty
import com.knapsack.fixtool.model.PartyRole
import com.knapsack.fixtool.model.StepAddress
import com.knapsack.fixtool.service.RelayTrigger
import org.junit.Test
import kotlin.test.assertEquals

/**
 * **The venue a dry run assumes, asked who an address reaches.** It answers through the same seam as the running
 * venue, so it has to reach the same counterparties, which `LiveRelayVenueTest` pins for the running one.
 */
class DryRunVenueTest {
    /**
     * A member carved out of a responder family, by an exact entry or a longer prefix, plays the role the carve-out
     * gives it. Counted as a responder anyway, a requester carved out of `FIDLR*` was shown its own RFQ.
     */
    @Test
    fun `the responders are the counterparties whose role is responder, not every one a responder family covers`() {
        val carved =
            listOf(
                Counterparty("FIDLR*", PartyRole.RESPONDER.word),
                Counterparty("FIDLR9", PartyRole.REQUESTER.word),
                Counterparty("FIDLRX*", PartyRole.REQUESTER.word),
            )
        val online = setOf("FIDLR1", "FIDLR9", "FIDLRX1")
        val venue = DryRunVenue(carved, from = "FIDLR9", rfqWord = null, rfq = DryRunVenue.AssumedRfq(online = online))
        val trigger = RelayTrigger(null, "FIDLR9", "FIDLR9", "R", emptyMap(), rfqId = null)

        // Named as a rule names it. Reaching for StepAddress.Responders before anything has read the vocabulary
        // initialises it with a null in place of that address, for every test after this one.
        val reached = venue.resolve(StepAddress.parse("responders")!!, trigger)

        assertEquals(listOf("FIDLR1"), reached.recipients.map { it.compId }, "FIDLR9 and FIDLRX1 are requesters")
    }
}
