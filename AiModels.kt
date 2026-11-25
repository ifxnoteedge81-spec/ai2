package com.personal.aicomputer.ai

import kotlinx.coroutines.flow.Flow

enum class Role { SYSTEM, USER, ASSISTANT }

data class ChatMessage(val role: Role, val content: String)

data class AiRequest(
    val messages: List<ChatMessage>,
    val model: String,
    val temperature: Double = 0.4,
    val jsonMode: Boolean = false,
    val maxOutputTokens: Int? = null,
)

data class AiResponse(
    val text: String,
    val model: String,
    val provider: String,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
)

data class ModelInfo(
    val id: String,
    val name: String,
    val provider: String,
    val free: Boolean,
    val contextLength: Int? = null,
)

/** Typed provider errors so the router can decide between retrying, falling back or surfacing the message. */
sealed class AiException(message: String, val retryable: Boolean, cause: Throwable? = null) : Exception(message, cause) {
    class MissingKey(provider: String) :
        AiException("No API key is set for $provider. Open AI Model Settings and add your own key.", false)

    class Auth(provider: String, code: Int) :
        AiException("$provider rejected the API key (HTTP $code). Check the key and its permissions.", false)

    class RateLimited(provider: String, val retryAfterMs: Long?) :
        AiException("$provider rate limit or quota reached. Free models can be rate-limited or unavailable at any time.", true)

    class Server(provider: String, code: Int) :
        AiException("$provider had a server problem (HTTP $code).", true)

    class Network(cause: Throwable) :
        AiException("Network problem: ${cause.message ?: cause.javaClass.simpleName}. Check your internet connection.", true, cause)

    class BadRequest(provider: String, detail: String) :
        AiException("$provider rejected the request: $detail", false)

    class Empty(provider: String) :
        AiException("$provider returned an empty response.", true)

    class Offline :
        AiException("You are offline. Cloud AI tasks need internet; local files, terminal and scripts still work.", false)

    class AllFailed(summary: String) :
        AiException(summary, false)
}

interface KeyProvider {
    fun get(name: String): String?
}

object KeyNames {
    const val GEMINI = "gemini_api_key"
    const val OPENROUTER = "openrouter_api_key"
}

interface AiProvider {
    val id: String
    val displayName: String
    suspend fun complete(req: AiRequest): AiResponse
    fun stream(req: AiRequest): Flow<String>
    suspend fun listModels(): List<ModelInfo>
}
