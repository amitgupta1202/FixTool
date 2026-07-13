package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.service.FixMessageHelper.toQuickFixMessage
import org.slf4j.LoggerFactory

/**
 * Service for validating FIX messages using QuickFIX/J DataDictionary via FixDictionary adapter.
 *
 * What is validated is the message **body** against the dictionary — the part the author writes and
 * can get wrong: unknown tags for the message type, bad enum values, wrong data types, missing
 * required fields, mis-ordered groups.
 *
 * Not the wire frame. What the editor holds is a body, not a framed message: FixTool computes
 * BodyLength(9) and CheckSum(10) at send time, and the session supplies the sequencing header. Handing
 * that draft to QuickFIX/J's frame parser — which demands 8, 9, 35 as the first three tags — failed
 * *every* draft with "Header fields out of order", so the editor's linter was permanently lit for a
 * reason that had nothing to do with the message.
 */
object FixMessageValidator {
    fun validate(rawMessage: String, dictionary: FixDictionary?): ValidationResult {
        if (dictionary == null || !dictionary.isLoaded()) {
            return ValidationResult(isValid = false, errors = listOf("No data dictionary configured for validation"))
        }

        return try {
            val dataDictionary =
                dictionary.getDataDictionary()
                    ?: return ValidationResult(isValid = false, errors = listOf("Data dictionary not available"))

            // Parse without frame validation, then check the body: bodyOnly skips the header/trailer
            // rules the draft cannot satisfy yet.
            val quickFixMessage = rawMessage.toQuickFixMessage(dataDictionary, validate = false)
            dataDictionary.validate(quickFixMessage, true)
            ValidationResult(isValid = true, errors = emptyList())
        } catch (e: Exception) {
            logger.debug("Message failed validation: {}", e.message)
            ValidationResult(isValid = false, errors = listOf("Validation error: ${e.message}"))
        }
    }

    private val logger = LoggerFactory.getLogger(FixMessageValidator::class.java)

    data class ValidationResult(
        val isValid: Boolean,
        val errors: List<String> = emptyList(),
    )
}
