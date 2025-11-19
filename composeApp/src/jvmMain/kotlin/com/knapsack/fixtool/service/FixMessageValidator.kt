package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.service.FixMessageHelper.toQuickFixMessage
import org.slf4j.LoggerFactory

/**
 * Service for validating FIX messages using QuickFIX/J DataDictionary via FixDictionary adapter
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

            // Use validate=true to match the validation used during message sending
            // This ensures Validate button and Send button use the same validation logic
            val quickFixMessage = rawMessage.toQuickFixMessage(dataDictionary, validate = true)
            ValidationResult(isValid = true, errors = emptyList())
        } catch (e: Exception) {
            logger.error("Failed to validate fields: {}", e.message, e)
            ValidationResult(isValid = false, errors = listOf("Validation error: ${e.message}"))
        }
    }

    private val logger = LoggerFactory.getLogger(FixMessageValidator::class.java)

    data class ValidationResult(
        val isValid: Boolean,
        val errors: List<String> = emptyList(),
    )
}
