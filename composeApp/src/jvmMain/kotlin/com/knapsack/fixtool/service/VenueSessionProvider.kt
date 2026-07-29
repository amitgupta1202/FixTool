package com.knapsack.fixtool.service

import org.slf4j.LoggerFactory
import quickfix.Application
import quickfix.LogFactory
import quickfix.MessageFactory
import quickfix.MessageStoreFactory
import quickfix.Session
import quickfix.SessionID
import quickfix.SessionSettings
import quickfix.mina.SessionConnector
import quickfix.mina.acceptor.DynamicAcceptorSessionProvider

/**
 * **Creates a FIX session for each client that logs on to a venue**, rather than requiring every
 * counterparty to be configured in advance.
 *
 * A real exchange is one endpoint many clients reach; a statically configured acceptor is a
 * point-to-point link wearing an exchange's name. QuickFIX/J's own dynamic provider does the
 * creation, so this class exists for the two things it does not do:
 *
 * 1. **It pins who we are.** The template pattern wildcards the counterparty and the sub/location
 *    IDs, but never our own [SessionID.getSenderCompID] or the BeginString. A logon addressed to
 *    `VENU` when the venue is `VENUE` is still refused, because that is a test result: the client
 *    is misconfigured and would have failed in production too.
 * 2. **It says so when it refuses.** Left to itself the engine logs "Unknown session ID during
 *    logon" and returns, sending no Logout, no Reject, nothing at all — the client hangs until its
 *    own logon timeout and the tool shows no sign that anyone knocked. A tester then cannot tell a
 *    wrong CompID from a wrong port from a firewall. [onRefused] carries the offending SessionID up
 *    to the venue pane, where it is the answer rather than a mystery.
 *
 * Never throws into the caller: [quickfix.mina.acceptor.AcceptorIoHandler] invokes this with no
 * catch around it, and an exception there kills the MINA connection thread rather than one logon.
 */
class VenueSessionProvider(
    settings: SessionSettings,
    template: SessionID,
    application: Application,
    messageStoreFactory: MessageStoreFactory,
    logFactory: LogFactory,
    messageFactory: MessageFactory,
    private val onRefused: (SessionID) -> Unit,
) : DynamicAcceptorSessionProvider(
        settings,
        listOf(DynamicAcceptorSessionProvider.TemplateMapping(anyClientOf(template), template)),
        application,
        messageStoreFactory,
        logFactory,
        messageFactory,
    ) {
    @Synchronized
    override fun getSession(sessionID: SessionID, sessionConnector: SessionConnector?): Session? {
        if (lookupTemplateID(sessionID) == null) {
            logger.warn("Refusing logon for {}: not addressed to this acceptor", sessionID)
            onRefused(sessionID)
            return null
        }
        return try {
            super.getSession(sessionID, sessionConnector)
        } catch (e: Exception) {
            // A store or log file the session cannot open, most likely. Refusing one client beats
            // taking the venue's connection thread down with it.
            logger.error("Could not create a session for {}: {}", sessionID, e.message, e)
            onRefused(sessionID)
            null
        }
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(VenueSessionProvider::class.java)
    }
}

/**
 * The pattern matching **any client addressing this venue**: our BeginString and SenderCompID
 * exactly, everything else a wildcard.
 *
 * Sub and location IDs are wildcarded rather than pinned to the template's (empty) ones because a
 * client that sets TargetSubID is addressing us correctly by every meaning that matters; refusing it
 * over a field the profile never configured would be an accident, not a decision.
 */
private fun anyClientOf(template: SessionID): SessionID =
    SessionID(
        template.beginString,
        template.senderCompID,
        DynamicAcceptorSessionProvider.WILDCARD,
        DynamicAcceptorSessionProvider.WILDCARD,
        DynamicAcceptorSessionProvider.WILDCARD,
        DynamicAcceptorSessionProvider.WILDCARD,
        DynamicAcceptorSessionProvider.WILDCARD,
        null,
    )
