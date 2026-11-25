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

/** Direct client for the Google Gemini (Generative Language) REST API. No proxy server involved. */
class GeminiProvider(
    private val http: OkHttpClient,
    private val keys: KeyProvider,
) : AiProvider {
    override val id = "gemini"
    override val displayName = "Google Gemini"

    private val base = "https://generativelanguage.googleapis.com/v1beta"
    private val json = "application/json; charset=utf-8".toMediaType()

    private fun key(): String = keys.get(KeyNames.GEMINI)?.takeIf { it.isNotBlank() } ?: throw AiException.MissingKey(displayName)

    private fun modelPath(model: String) = if (model.startsWith("models/")) model else "models/$model"

    internal fun buildBody(req: AiRequest): JSONObject {
        val system = req.messages.filter { it.role == Role.SYSTEM }.joinToString("\n\n") { it.content }
        val contents = JSONArray()
        for (m in req.messages) {
            if (m.role == Role.SYSTEM) continue
            contents.put(
                JSONObject()
                    .put("role", if (m.role == Role.USER) "user" else "model")
                    .put("parts", JSONArray().put(JSONObject().put("text", m.content))),
            )
        }
        val gen = JSONObject().put("temperature", req.temperature)
        if (req.jsonMode) gen.put("responseMimeType", "application/json")
        req.maxOutputTokens?.let { gen.put("maxOutputTokens", it) }
        val body = JSONObject().put("contents", contents).put("generationConfig", gen)
        if (system.isNotBlank()) {
            body.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
        }
        return body
    }

    internal fun extractText(obj: JSONObject): String {
        val sb = StringBuilder()
        val cands = obj.optJSONArray("candidates") ?: return ""
        if (cands.length() == 0) return ""
        val parts = cands.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return ""
        for (i in 0 until parts.length()) {
            val p = parts.optJSONObject(i) ?: continue
            if (p.optBoolean("thought", false)) continue
            sb.append(p.optString("text", ""))
        }
        return sb.toString()
    }

    private fun request(url: String, body: JSONObject?): Request {
        val b = Request.Builder().url(url).header("x-goog-api-key", key())
        return if (body != null) b.post(body.toString().toRequestBody(json)).build() else b.get().build()
    }

    override suspend fun complete(req: AiRequest): AiResponse {
        val request = request("$base/${modelPath(req.model)}:generateContent", buildBody(req))
        val (code, text, retryAfter) = withContext(Dispatchers.IO) { executeBlockingAsync(request) }
        if (code !in 200..299) throw HttpSupport.mapHttpError(displayName, code, text, retryAfter)
        val obj = try { JSONObject(text) } catch (e: JSONException) { throw AiException.BadRequest(displayName, "unreadable response") }
        val out = extractText(obj)
        if (out.isBlank()) {
            val block = obj.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
            if (block.isNotEmpty()) throw AiException.BadRequest(displayName, "prompt blocked ($block)")
            throw AiException.Empty(displayName)
        }
        val usage = obj.optJSONObject("usageMetadata")
        return AiResponse(
            text = out,
            model = req.model,
            provider = id,
            promptTokens = usage?.optInt("promptTokenCount", -1)?.takeIf { it >= 0 },
            completionTokens = usage?.optInt("candidatesTokenCount", -1)?.takeIf { it >= 0 },
        )
    }

    private fun executeBlockingAsync(request: Request): Triple<Int, String, String?> {
        try {
            http.newCall(request).execute().use { r ->
                return Triple(r.code, r.body?.string().orEmpty(), r.header("Retry-After"))
            }
        } catch (e: IOException) {
            throw AiException.Network(e)
        }
    }

    override fun stream(req: AiRequest): Flow<String> = flow {
        val request = request("$base/${modelPath(req.model)}:streamGenerateContent?alt=sse", buildBody(req))
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
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload.isEmpty()) continue
                    val piece = try { extractText(JSONObject(payload)) } catch (_: JSONException) { "" }
                    if (piece.isNotEmpty()) emit(piece)
                }
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                throw AiException.Network(e)
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun listModels(): List<ModelInfo> {
        val request = request("$base/models?pageSize=200", null)
        val (code, text, retryAfter) = withContext(Dispatchers.IO) { executeBlockingAsync(request) }
        if (code !in 200..299) throw HttpSupport.mapHttpError(displayName, code, text, retryAfter)
        val arr = JSONObject(text).optJSONArray("models") ?: return emptyList()
        val out = ArrayList<ModelInfo>()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val methods = m.optJSONArray("supportedGenerationMethods")
            var ok = false
            if (methods != null) for (j in 0 until methods.length()) if (methods.optString(j) == "generateContent") ok = true
            if (!ok) continue
            val id = m.optString("name").removePrefix("models/")
            out += ModelInfo(id, m.optString("displayName", id), this.id, free = false, contextLength = m.optInt("inputTokenLimit", -1).takeIf { it > 0 })
        }
        return out.sortedBy { it.id }
    }
}
