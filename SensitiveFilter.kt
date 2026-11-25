package com.personal.aicomputer.security

/**
 * Agent memory must never hold passwords, OTPs or card data. This is a heuristic filter, not a guarantee;
 * the UI also tells the user not to store secrets.
 */
object SensitiveFilter {
    private val keywordPattern = Regex(
        "(?i)\\b(password|passwd|passcode|otp|one[- ]time (code|password)|cvv|cvc|secret key|private key|api[ _-]?key|seed phrase|recovery phrase)\\b" +
            "|পাসওয়ার্ড|ওটিপি|গোপন কোড|পিন নম্বর",
    )
    private val cardNumber = Regex("\\b(?:\\d[ -]?){13,19}\\b")
    private val knownKeyShapes = Regex("AIza[0-9A-Za-z_\\-]{20,}|sk-[A-Za-z0-9_\\-]{20,}")

    fun looksSensitive(text: String): Boolean =
        keywordPattern.containsMatchIn(text) || cardNumber.containsMatchIn(text) || knownKeyShapes.containsMatchIn(text)
}
