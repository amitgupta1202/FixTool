package com.knapsack.fixtool.model

/**
 * **Who one step of a rule's reply goes to.**
 *
 * Absent means [Sender], so every rule written before relaying means exactly what it meant. Every other
 * address names a part of the RFQ the trigger belongs to, which is what lets a venue carry a negotiation
 * between two parties instead of answering each one itself. See `docs/rfq-relay-proposal.md`, decision 2.
 *
 * Carried on disk as the [word], never as an enum (see [PartyRole] for why).
 */
sealed interface StepAddress {
    val word: String

    /** Whoever sent the trigger. */
    data object Sender : StepAddress {
        override val word = "sender"
    }

    /** The counterparty that opened the RFQ this message belongs to. */
    data object Requester : StepAddress {
        override val word = "requester"
    }

    /** The responder whose quote the trigger names. */
    data object Quoter : StepAddress {
        override val word = "quoter"
    }

    /** The responder holding the best other live quote on the side that traded. */
    data object Cover : StepAddress {
        override val word = "cover"
    }

    /** Every responder holding a live quote, except the quoter and the cover. */
    data object Others : StepAddress {
        override val word = "others"
    }

    /** Every responder holding a live quote. */
    data object Quoted : StepAddress {
        override val word = "quoted"
    }

    /** Every responder the RFQ was sent to, quoted or not. */
    data object Asked : StepAddress {
        override val word = "asked"
    }

    /** Every counterparty the venue declares a responder, as it stands when the rule fires. */
    data object Responders : StepAddress {
        override val word = "responders"
    }

    /** One named counterparty. */
    data class CompId(
        val compId: String,
    ) : StepAddress {
        override val word get() = "$COMP_ID_PREFIX$compId"
    }

    /** True for every address but [Sender]: the step leaves the conversation the trigger arrived on. */
    val relays: Boolean get() = this != Sender

    /** True when the address names a part of an RFQ, which only an RFQ the venue holds can resolve. */
    val needsAnRfq: Boolean get() = this in RFQ_PARTIES

    /** True when the address names responders through a quote, which only a trigger naming one can resolve. */
    val needsAQuote: Boolean get() = this == Quoter || this == Cover || this == Others

    companion object {
        const val COMP_ID_PREFIX = "compId:"

        private val FIXED = listOf(Sender, Requester, Quoter, Cover, Others, Quoted, Asked, Responders)

        private val RFQ_PARTIES: Set<StepAddress> = setOf(Requester, Quoter, Cover, Others, Quoted, Asked)

        /** The vocabulary, as an author would type it, for saying what an unrecognised word could have been. */
        val words: List<String> get() = FIXED.map { it.word } + "${COMP_ID_PREFIX}<CompID>"

        /**
         * The address [text] names: [Sender] for null or blank, null for anything that is not an address.
         *
         * `compId:` with nothing after it is not an address — a step addressed to nobody in particular would
         * go nowhere and look configured.
         */
        fun parse(text: String?): StepAddress? {
            val trimmed = text?.trim()
            if (trimmed.isNullOrEmpty()) return Sender
            if (trimmed.startsWith(COMP_ID_PREFIX)) {
                return trimmed.removePrefix(COMP_ID_PREFIX).trim().takeIf { it.isNotEmpty() }?.let { CompId(it) }
            }
            return FIXED.firstOrNull { it.word == trimmed }
        }
    }
}
