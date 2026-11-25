package com.personal.aicomputer.ai

/** Last-resort token-limit protection: drops the oldest middle messages until under the budget. */
object ContextTrim {
    /** Rough heuristic: ~4 characters per token. */
    fun estimateTokens(chars: Int): Int = chars / 4

    private fun total(l: List<ChatMessage>) = l.sumOf { it.content.length }

    fun trimToBudget(messages: List<ChatMessage>, maxChars: Int): List<ChatMessage> {
        if (total(messages) <= maxChars) return messages
        val system = messages.filter { it.role == Role.SYSTEM }
        val rest = messages.filter { it.role != Role.SYSTEM }.toMutableList()
        // Keep index 0 (the user's goal) and the 2 most recent messages; drop the middle in pairs so
        // user/assistant alternation is preserved.
        while (total(system + rest) > maxChars && rest.size > 3) {
            rest.removeAt(1)
            if (rest.size > 3) rest.removeAt(1)
        }
        var result = system + rest
        if (total(result) > maxChars && result.isNotEmpty()) {
            val longest = result.maxByOrNull { it.content.length }!!
            val excess = total(result) - maxChars
            val newLen = (longest.content.length - excess).coerceAtLeast(500)
            val cut = longest.content.take(newLen / 2) + "\n…[truncated to fit context]…\n" + longest.content.takeLast(newLen / 2)
            result = result.map { if (it === longest) it.copy(content = cut) else it }
        }
        return result
    }
}
