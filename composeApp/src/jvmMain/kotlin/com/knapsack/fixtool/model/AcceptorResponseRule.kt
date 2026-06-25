package com.knapsack.fixtool.model

import kotlinx.serialization.Serializable

/**
 * A single acceptor auto-response rule. When FixTool runs as an acceptor and an incoming
 * application message matches [whenMsgType] (and every entry of [whenFields], by exact value),
 * the first matching rule's [responseTemplate] is resolved and sent back to the counterparty.
 *
 * The response template is a raw FIX message (pipe- or SOH-delimited, app fields only — QuickFIX
 * stamps the session header/trailer) supporting three substitutions:
 *  - `${req.<tag>}` — the value of `<tag>` from the incoming (request) message
 *  - `${uuid}`      — a fresh random id (e.g. for OrderID/ExecID)
 *  - `${now}`       — the current UTC transact time (yyyyMMdd-HH:mm:ss.SSS)
 *
 * Example: `35=8|150=0|39=0|37=${uuid}|11=${req.11}|55=${req.55}|38=${req.38}`.
 */
@Serializable
data class AcceptorResponseRule(
    val whenMsgType: String,
    val whenFields: Map<String, String> = emptyMap(),
    val responseTemplate: String,
)
