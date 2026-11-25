package com.personal.aicomputer.security

/** Masks anything that looks like an API key or bearer token before text reaches logs or the UI. */
object Redactor {
    private val patterns = listOf(
        Regex("AIza[0-9A-Za-z_\\-]{20,}"),
        Regex("sk-or-[A-Za-z0-9_\\-]{10,}"),
        Regex("sk-[A-Za-z0-9_\\-]{20,}"),
        Regex("(?i)(bearer\\s+)[A-Za-z0-9._\\-]{12,}"),
        Regex("(?i)(x-goog-api-key\\s*[:=]\\s*)\\S+"),
        Regex("(?i)([?&]key=)[^&\\s]+"),
    )

    fun redact(text: String): String {
        var out = text
        for (p in patterns) {
            out = p.replace(out) { m ->
                val prefix = if (m.groupValues.size > 1) m.groupValues[1] else ""
                "$prefix***REDACTED***"
            }
        }
        return out
    }
}
