package com.personal.aicomputer.ai

import com.personal.aicomputer.security.AppLog
import com.personal.aicomputer.storage.SettingsStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

/**
 * Unified AI Model Gateway + router.
 *
 * Order of attempts: the selected provider/model, the user's OpenRouter fallback models, then the other
 * provider (if it has a key and a model, and auto-fallback is on). Retryable errors (rate limit, 5xx,
 * network) are retried with backoff before moving on. Auth/bad-request errors move on immediately.
 * Requests go straight from the device to the provider; there is no proxy.
 */
class AiGateway(
    private val providers: Map<String, AiProvider>,
    private val settings: SettingsStore,
    private val keys: KeyProvider,
    private val log: AppLog,
    private val contextBudgetChars: () -> Int,
    private val isOnline: () -> Boolean,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    data class Candidate(val providerId: String, val model: String)

    private fun hasKey(providerId: String): Boolean = when (providerId) {
        "gemini" -> !keys.get(KeyNames.GEMINI).isNullOrBlank()
        "openrouter" -> !keys.get(KeyNames.OPENROUTER).isNullOrBlank()
        else -> false
    }

    fun candidates(): List<Candidate> {
        val primary = settings.provider.value
        val other = if (primary == "gemini") "openrouter" else "gemini"
        val out = ArrayList<Candidate>()
        fun add(p: String, model: String) {
            if (model.isNotBlank() && providers.containsKey(p) && hasKey(p) && out.none { it.providerId == p && it.model == model }) out += Candidate(p, model.trim())
        }
        add(primary, settings.modelFor(primary))
        if (primary == "openrouter") {
            settings.openRouterFallbacks.value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { add("openrouter", it) }
        }
        if (settings.autoFallback.value) {
            add(other, settings.modelFor(other))
            if (other == "openrouter") {
                settings.openRouterFallbacks.value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { add("openrouter", it) }
            }
        }
        return out
    }

    fun describeSelection(): String {
        val p = settings.provider.value
        val m = settings.modelFor(p).ifBlank { "(no model selected)" }
        return "${providers[p]?.displayName ?: p} · $m"
    }

    suspend fun complete(
        messages: List<ChatMessage>,
        jsonMode: Boolean = false,
        temperature: Double = 0.3,
        maxOutputTokens: Int? = null,
    ): AiResponse {
        if (!isOnline()) throw AiException.Offline()
        val cands = candidates()
        if (cands.isEmpty()) throw missingSetup()
        val trimmed = ContextTrim.trimToBudget(messages, contextBudgetChars())
        val failures = ArrayList<String>()
        var last: AiException? = null

        for (c in cands) {
            val provider = providers.getValue(c.providerId)
            var attempt = 0
            while (true) {
                try {
                    val resp = provider.complete(AiRequest(trimmed, c.model, temperature, jsonMode, maxOutputTokens))
                    settings.recordUsage(c.providerId, resp.promptTokens, resp.completionTokens)
                    if (failures.isNotEmpty()) log.i("AI", "Succeeded with ${provider.displayName}/${c.model} after fallback")
                    return resp
                } catch (e: AiException) {
                    last = e
                    log.w("AI", "${provider.displayName}/${c.model} attempt ${attempt + 1} failed: ${e.message}")
                    if (e.retryable && attempt < 2) {
                        val wait = (e as? AiException.RateLimited)?.retryAfterMs?.coerceAtMost(20_000) ?: (1000L shl attempt)
                        attempt++
                        sleep(wait)
                        continue
                    }
                    failures += "${provider.displayName}/${c.model}: ${e.message}"
                    break
                }
            }
        }
        if (failures.size == 1 && last != null) throw last
        throw AiException.AllFailed("All AI models failed:\n" + failures.joinToString("\n") { "• $it" })
    }

    /** Streams from the first usable candidate only (no mid-stream fallback, to avoid mixed output). */
    fun stream(messages: List<ChatMessage>, temperature: Double = 0.6): Flow<String> {
        if (!isOnline()) throw AiException.Offline()
        val c = candidates().firstOrNull() ?: throw missingSetup()
        val trimmed = ContextTrim.trimToBudget(messages, contextBudgetChars())
        return providers.getValue(c.providerId).stream(AiRequest(trimmed, c.model, temperature))
    }

    private fun missingSetup(): AiException {
        val p = settings.provider.value
        val name = providers[p]?.displayName ?: p
        return if (!hasKey(p)) AiException.MissingKey(name)
        else AiException.BadRequest(name, "no model selected. Open AI Model Settings and pick a model.")
    }
}
