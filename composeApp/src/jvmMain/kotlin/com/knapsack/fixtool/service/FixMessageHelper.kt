package com.knapsack.fixtool.service

import quickfix.DataDictionary
import quickfix.Message

object FixMessageHelper {
    /**
     * Parses a raw FIX message string (with | delimiters) into a QuickFIX Message
     * Uses QuickFIX/J's native fromString method with the configured data dictionary
     */
    fun String.toQuickFixMessage(
        dataDictionary: DataDictionary,
    ): Message = Message(this.toWireFixMessage(), dataDictionary, false)

    fun String.toQuickFixMessage(): Message = Message(this.toWireFixMessage(), false)

    fun String.toWireFixMessage() = this.replace('|', '\u0001')

    fun String.toRawFixMessage() = this.replace('\u0001', '|')

    fun Message.toRawFixMessage() = this.toString().replace('\u0001', '|')
}
