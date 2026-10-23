package com.drkkr.strategos.llm

import com.drkkr.strategos.agent.AgentConfig
import com.drkkr.strategos.data.db.dao.LlmCallDao
import com.drkkr.strategos.data.db.entities.LlmCallEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * xAI Responses API client. Stateless (`store:false`). The key is read on every call and never kept in a field.
 * Nothing from a request or response body is logged or kept on an error (it may echo user text).
 */
class XaiResponsesClient(
    private val keyProvider: suspend () -> String?,
    private val callLog: LlmCallDao? = null,
    private val baseUrl: String = AgentConfig.BASE_URL,
    private val http: OkHttpClient = defaultHttp(),
    private val pause: suspend (Long) -> Unit = { delay(it) }
) : LlmClient {

    override suspend fun create(req: LlmRequest): LlmResult {
        val key = keyProvider()?.takeIf { it.isNotBlank() } ?: return LlmResult.Unauthorized(401)
        val body = buildBody(req).toString().toRequestBody(JSON)

        var serverErrors = 0
        var rateLimits = 0
        while (true) {
            val started = System.currentTimeMillis()
            val outcome = try {
                http.newCall(
                    Request.Builder().url("$baseUrl/responses")
                        .header("Authorization", "Bearer $key").header("Content-Type", "application/json")
                        .post(body).build()
                ).await().use { r -> readResponse(r, System.currentTimeMillis() - started) }
            } catch (e: IOException) {
                Attempt(LlmResult.Network(networkKind(e)), 0, networkKind(e), LlmUsage.ZERO, null)
            }
            log(req, outcome, System.currentTimeMillis() - started)

            when (val r = outcome.result) {
                is LlmResult.RateLimited -> {
                    val wait = r.retryAfterSec
                    if (wait != null && wait <= MAX_RETRY_AFTER_SEC && rateLimits < MAX_RETRIES) {
                        rateLimits++; pause(wait * 1000L); continue
                    }
                    return r
                }
                is LlmResult.HttpError -> {
                    if (r.status in 500..599 && serverErrors < MAX_RETRIES) {
                        pause(BACKOFF_MS[serverErrors]); serverErrors++; continue
                    }
                    return r
                }
                else -> return r
            }
        }
    }

    override suspend fun listModels(apiKey: String?): ModelListResult {
        val key = (apiKey ?: keyProvider())?.takeIf { it.isNotBlank() } ?: return ModelListResult.Failed("no_key")
        return try {
            http.newCall(Request.Builder().url("$baseUrl/models").header("Authorization", "Bearer $key").get().build())
                .await().use { r ->
                    when {
                        r.code == 401 || r.code == 403 -> ModelListResult.Failed("unauthorized")
                        !r.isSuccessful -> ModelListResult.Failed("http_${r.code}")
                        else -> parseModels(r.body?.string())?.let { ModelListResult.Ok(it) } ?: ModelListResult.Failed("unavailable")
                    }
                }
        } catch (e: IOException) {
            ModelListResult.Failed(networkKind(e))
        }
    }

    /** `GET /models` -> `data[]` with `id` and optional `pricing.{prompt,completion}` in USD per token (spike S7, Groq). */
    private fun parseModels(text: String?): List<ModelInfo>? {
        if (text == null) return null
        return try {
            val list = (Json.parseToJsonElement(text).jsonObject["data"] as? JsonArray) ?: return null
            list.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val pricing = o["pricing"] as? JsonObject
                fun perM(k: String) = (pricing?.get(k) as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.times(1_000_000.0)
                ModelInfo(id, perM("prompt"), perM("completion"))
            }.takeIf { it.isNotEmpty() }
        } catch (e: Exception) { null }
    }

    private fun buildBody(req: LlmRequest): JsonObject = buildJsonObject {
        put("model", req.model)
        put("input", req.input)
        // The provider rejects tool_choice "none" (spike S4). Omitting `tools` forces a text-only reply; echoed
        // function_call / function_call_output items in `input` are still accepted without it (verified on Groq).
        val textOnly = req.toolChoice == "none" && !AgentConfig.TOOL_CHOICE_NONE_SUPPORTED
        if (req.tools != null && !textOnly) {
            put("tools", req.tools)
            put("tool_choice", req.toolChoice)
        }
        put("parallel_tool_calls", false)
        put("store", false)
        put("max_output_tokens", req.maxOutputTokens)
        put("temperature", req.temperature)
    }

    private class Attempt(val result: LlmResult, val status: Int, val errorCode: String?, val usage: LlmUsage, val unused: Unit?)

    private fun readResponse(r: Response, latencyMs: Long): Attempt {
        val status = r.code
        when {
            status == 401 || status == 403 -> return Attempt(LlmResult.Unauthorized(status), status, "unauthorized", LlmUsage.ZERO, null)
            status == 429 -> {
                val after = r.header("Retry-After")?.trim()?.toIntOrNull()
                return Attempt(LlmResult.RateLimited(after), status, "rate_limited", LlmUsage.ZERO, null)
            }
            !r.isSuccessful -> {
                val code = errorCode(r.body?.string())          // only the code survives; the rest of the body is dropped
                return Attempt(LlmResult.HttpError(status, code), status, code, LlmUsage.ZERO, null)
            }
        }
        val root = try { Json.parseToJsonElement(r.body?.string() ?: "").jsonObject } catch (e: Exception) { null }
        val output = root?.get("output") as? JsonArray
            ?: return Attempt(LlmResult.HttpError(status, "bad_response"), status, "bad_response", LlmUsage.ZERO, null)
        val usage = (root["usage"] as? JsonObject).toUsage()
        val id = (root["id"] as? JsonPrimitive)?.contentOrNull ?: ""
        return Attempt(LlmResult.Ok(id, output, usage, latencyMs), status, null, usage, null)
    }

    private fun errorCode(text: String?): String? {
        if (text == null) return null
        return try {
            ((Json.parseToJsonElement(text).jsonObject["error"] as? JsonObject)?.get("code") as? JsonPrimitive)?.contentOrNull
        } catch (e: Exception) { null }
    }

    private fun networkKind(e: IOException) = when (e) {
        is SocketTimeoutException -> "timeout"
        is UnknownHostException, is ConnectException -> "offline"
        else -> "io"
    }

    private suspend fun log(req: LlmRequest, a: Attempt, latencyMs: Long) {
        val dao = callLog ?: return
        try {
            dao.insert(
                LlmCallEntity(
                    id = UUID.randomUUID().toString(), conversationId = req.conversationId, turnId = req.turnId,
                    purpose = req.purpose, model = req.model, inputTokens = a.usage.input, cachedTokens = a.usage.cached,
                    outputTokens = a.usage.output, reasoningTokens = a.usage.reasoning, latencyMs = latencyMs,
                    httpStatus = a.status, errorCode = a.errorCode, createdAt = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) { /* logging must never break a turn */ }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) { cont.resume(response) }
        })
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private const val MAX_RETRIES = 2
        private const val MAX_RETRY_AFTER_SEC = 10
        private val BACKOFF_MS = longArrayOf(1_000, 4_000)

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS).callTimeout(150, TimeUnit.SECONDS)
            .build()
    }
}
