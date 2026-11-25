package com.personal.aicomputer.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/** Direct client for the OpenRouter (OpenAI-compatible) API. */
class OpenRouterProvider(
    private val http: OkHttpClient,
    private val keys: KeyProvider,
) : AiProvider {
    override val id = "openrouter"
    override val displayName = "OpenRouter"

    private val base = "https://openrouter.ai/api/v1"
    private val json = "application/json; charset=utf-8".toMediaType()

    private fun key(): String = keys.get(KeyNames.OPENROUTER)?.takeIf { it.isNotBlank() } ?: throw AiException.MissingKey(displayName)

    internal fun buildBody(req: AiRequest, stream: Boolean): JSONObject {
        val msgs = JSONArray()
        for (m in req.messages) {
            msgs.put(
                JSONObject()
                    .put("role", when (m.role) { Role.SYSTEM -> "system"; Role.USER -> "user"; Role.ASSISTANT -> "assistant" })
                    .put("content", m.content),
            )
        }
        // response_format is deliberately not sent: many free models reject it. JSON is requested in the prompt.
        val body = JSONObject().put("model", req.model).put("messages", msgs).put("temperature", req.temperature)
        req.maxOutputTokens?.let { body.put("max_tokens", it) }
        if (stream) body.put("stream", true)
        return body
    }

    private fun builder(url: String, auth: Boolean = true): Request.Builder {
        val b = Request.Builder().url(url)
            .header("X-Title", "Personal AI Computer")
            .header("HTTP-Referer", "https://github.com/personal-ai-computer")
        if (auth) b.header("Authorization", "Bearer " + key())
        return b
    }

    private fun executeBlocking(request: Request): Triple<Int, String, String?> {
        try {
            http.newCall(request).execute().use { r -> return Triple(r.code, r.body?.string().orEmpty(), r.header("Retry-After")) }
        } catch (e: IOException) {
            throw AiException.Network(e)
        }
    }

    override suspend fun complete(req: AiRequest): AiResponse {
        val request = builder("$base/chat/completions").post(buildBody(req, false).toString().toRequestBody(json)).build()
        val (code, text, retryAfter) = withContext(Dispatchers.IO) { executeBlocking(request) }
        if (code !in 200..299) throw HttpSupport.mapHttpError(displayName, code, text, retryAfter)
        val obj = try { JSONObject(text) } catch (e: JSONException) { throw AiException.BadRequest(displayName, "unreadable response") }
        // OpenRouter can answer HTTP 200 with an error object for upstream failures.
        obj.optJSONObject("error")?.let { err ->
            val c = err.optInt("code", 0)
            throw if (c == 429) AiException.RateLimited(displayName, null)
            else if (c >= 500 || c == 408 || c == 502 || c == 503) AiException.Server(displayName, c)
            else AiException.BadRequest(displayName, err.optString("message", "error").take(300))
        }
        val content = obj.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content", "").orEmpty()
        if (content.isBlank()) throw AiException.Empty(displayName)
        val usage = obj.optJSONObject("usage")
        return AiResponse(
            text = content,
            model = obj.optString("model", req.model),
            provider = id,
            promptTokens = usage?.optInt("prompt_tokens", -1)?.takeIf { it >= 0 },
            completionTokens = usage?.optInt("completion_tokens", -1)?.takeIf { it >= 0 },
        )
    }

    override fun stream(req: AiRequest): Flow<String> = flow {
        val request = builder("$base/chat/completions").post(buildBody(req, true).toString().toRequestBody(json)).build()
        val call = http.newCall(request)
        currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        val response = try { call.execute() } catch (e: IOException) { throw AiException.Network(e) }
        response.use { r ->
            if (!r.isSuccessful) throw HttpSupport.mapHttpError(displayName, r.code, r.body?.string().orEmpty(), r.header("Retry-After"))
            val source = r.body?.source() ?: throw AiException.Empty(displayName)
            try {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue // ": OPENROUTER PROCESSING" keep-alives start with ':'
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") break
                    if (payload.isEmpty()) continue
                    val piece = try {
                        val o = JSONObject(payload)
                        o.optJSONObject("error")?.let { throw AiException.BadRequest(displayName, it.optString("message", "stream error").take(300)) }
                        o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")?.optString("content", "").orEmpty()
                    } catch (_: JSONException) { "" }
                    if (piece.isNotEmpty()) emit(piece)
                }
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                throw AiException.Network(e)
            }
        }
    }.flowOn(Dispatchers.IO)

    /** Lists models (the endpoint is public). A model is "free" when its prompt and completion prices are zero or its id ends with :free. */
    override suspend fun listModels(): List<ModelInfo> {
        val request = builder("$base/models", auth = false).get().build()
        val (code, text, retryAfter) = withContext(Dispatchers.IO) { executeBlocking(request) }
        if (code !in 200..299) throw HttpSupport.mapHttpError(displayName, code, text, retryAfter)
        val arr = JSONObject(text).optJSONArray("data") ?: return emptyList()
        val out = ArrayList<ModelInfo>()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val mid = m.optString("id")
            if (mid.isEmpty()) continue
            val pricing = m.optJSONObject("pricing")
            val free = mid.endsWith(":free") ||
                (pricing != null && pricing.optString("prompt") == "0" && pricing.optString("completion") == "0")
            out += ModelInfo(mid, m.optString("name", mid), id, free, m.optInt("context_length", -1).takeIf { it > 0 })
        }
        return out.sortedBy { it.id }
    }
}
