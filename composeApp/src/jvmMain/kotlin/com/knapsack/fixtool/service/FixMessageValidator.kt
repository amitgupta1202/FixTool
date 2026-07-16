package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.service.FixMessageHelper.toQuickFixMessage
import com.knapsack.fixtool.service.compare.WireArithmetic
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
 *
 * But a message that **carries** a frame is held to it — in [ValidationResult.warnings], never in the
 * verdict. POST /validate and `fixtool_validate` are handed captured frames to judge, and a corrupted
 * capture answered with a bare `isValid: true` told the operator a message a real FIX engine would
 * discard as garbled is well-formed. The arithmetic cannot tell stale framing (an edited frame whose
 * 9/10 FixTool will recompute at send) from corruption, which is exactly why it is a warning that says
 * both, and not a failure.
 */
object FixMessageValidator {
    fun validate(rawMessage: String, dictionary: FixDictionary?): ValidationResult {
        if (dictionary == null || !dictionary.isLoaded()) {
            return ValidationResult(isValid = false, errors = listOf("No data dictionary configured for validation"))
        }

        val warnings = frameWarnings(rawMessage)
        return try {
            val dataDictionary =
                dictionary.getDataDictionary()
                    ?: return ValidationResult(isValid = false, errors = listOf("Data dictionary not available"))

            // Parse without frame validation, then check the body: bodyOnly skips the header/trailer
            // rules the draft cannot satisfy yet.
            val quickFixMessage = rawMessage.toQuickFixMessage(dataDictionary, validate = false)
            dataDictionary.validate(quickFixMessage, true)
            ValidationResult(isValid = true, errors = emptyList(), warnings = warnings)
        } catch (e: Exception) {
            logger.debug("Message failed validation: {}", e.message)
            ValidationResult(isValid = false, errors = listOf("Validation error: ${e.message}"), warnings = warnings)
        }
    }

    /**
     * What the message's own frame says about the bytes as read — empty for a draft, which carries no
     * frame to be held to. The arithmetic is [WireArithmetic], the same sums WirePaste refuses on.
     */
    private fun frameWarnings(rawMessage: String): List<String> {
        val fields = FixMessageHelper.parseFixMessage(rawMessage)
        if (fields.firstOrNull()?.first != 8) return emptyList()
        val checksumAt = fields.indexOfFirst { it.first == 10 }
        if (checksumAt <= 0) return emptyList()
        val warnings = mutableListOf<String>()
        WireArithmetic.checksum(fields, checksumAt)?.let { (stated, computed) ->
            if (stated != computed) {
                warnings += "the frame disagrees with its bytes: CheckSum(10) says ${"%03d".format(stated)} and " +
                    "these bytes sum to ${"%03d".format(computed)} — stale framing (FixTool recomputes 9 and 10 " +
                    "at send), or a capture that did not survive its journey. The content verdict is unaffected."
            }
        }
        WireArithmetic.bodyLength(fields, checksumAt)?.let { (stated, computed) ->
            if (stated != computed) {
                warnings += "the frame disagrees with its bytes: BodyLength(9) says $stated bytes and these " +
                    "bytes are $computed — stale framing (FixTool recomputes 9 and 10 at send), or a capture " +
                    "that did not survive its journey. The content verdict is unaffected."
            }
        }
        return warnings
    }

    private val logger = LoggerFactory.getLogger(FixMessageValidator::class.java)

    data class ValidationResult(
        val isValid: Boolean,
        val errors: List<String> = emptyList(),
        /** What the message's own frame says about the bytes — never part of the content verdict. */
        val warnings: List<String> = emptyList(),
    )
}
