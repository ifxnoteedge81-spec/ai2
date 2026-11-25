package com.personal.aicomputer.ai

import com.personal.aicomputer.security.Redactor
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object HttpSupport {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun mapHttpError(provider: String, code: Int, body: String, retryAfterHeader: String?): AiException {
        val detail = Redactor.redact(body).replace('\n', ' ').take(300)
        return when {
            code == 401 || code == 403 -> AiException.Auth(provider, code)
            code == 429 -> AiException.RateLimited(provider, retryAfterHeader?.trim()?.toLongOrNull()?.times(1000))
            code == 408 || code >= 500 -> AiException.Server(provider, code)
            else -> AiException.BadRequest(provider, "HTTP $code $detail")
        }
    }
}

suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(AiException.Network(e))
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
}
