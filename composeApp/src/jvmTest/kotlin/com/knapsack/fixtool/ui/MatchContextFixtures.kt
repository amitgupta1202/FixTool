package com.knapsack.fixtool.ui

import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixMessage
import quickfix.field.ClOrdID
import quickfix.field.OrderQty
import quickfix.field.OrdType
import quickfix.field.PartyID
import quickfix.field.PartyIDSource
import quickfix.field.PartyRole
import quickfix.field.Price
import quickfix.field.Side
import quickfix.field.Symbol
import quickfix.field.TransactTime
import java.time.LocalDateTime

/**
 * Shared test fixtures for the match-context detail-search feature: a real FIX44 dictionary (so
 * field names and enum values resolve) and a NewOrderSingle carrying a 3-entry NoPartyIDs group.
 */
internal object MatchContextFixtures {
    fun dictionary(): FixDictionary = FixDictionaryAdapter.fromResource()

    /** NewOrderSingle with three parties: ExecutingFirm, ClearingFirm, ContraFirm. */
    fun multiPartyOrder(): FixMessage {
        val order = quickfix.fix44.NewOrderSingle()
        order.header.setString(quickfix.field.BeginString.FIELD, "FIX.4.4")
        order.header.setString(quickfix.field.MsgType.FIELD, "D")
        order.header.setString(quickfix.field.SenderCompID.FIELD, "BUYSIDE")
        order.header.setString(quickfix.field.TargetCompID.FIELD, "BROKER")
        order.header.setInt(quickfix.field.MsgSeqNum.FIELD, 42)
        order.header.setString(quickfix.field.SendingTime.FIELD, "20260625-09:30:00")

        order.setString(ClOrdID.FIELD, "ORD-10042")
        order.setString(Symbol.FIELD, "VOD.L")
        order.setChar(Side.FIELD, '1') // Buy
        order.setDouble(OrderQty.FIELD, 250_000.0)
        order.setChar(OrdType.FIELD, '2') // Limit
        order.setDouble(Price.FIELD, 72.55)
        order.setString(TransactTime.FIELD, "20260625-09:30:00")

        order.addGroup(party("BARCGB22", role = 1)) // ExecutingFirm
        order.addGroup(party("CITIUS33", role = 4)) // ClearingFirm
        order.addGroup(party("CHASUS33", role = 17)) // ContraFirm

        return FixMessage(
            timestamp = LocalDateTime.of(2026, 6, 25, 9, 30, 0),
            direction = FixMessage.Direction.OUTGOING,
            rawMessage = order.toString().replace("\u0001", "|"),
            messageType = "D",
            quickfixMessage = order,
        )
    }

    private fun party(id: String, role: Int): quickfix.fix44.NewOrderSingle.NoPartyIDs =
        quickfix.fix44.NewOrderSingle.NoPartyIDs().apply {
            setString(PartyID.FIELD, id)
            setChar(PartyIDSource.FIELD, 'D') // Proprietary / custom code
            setInt(PartyRole.FIELD, role)
        }
}
