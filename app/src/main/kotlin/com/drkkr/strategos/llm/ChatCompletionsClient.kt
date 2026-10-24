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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Chat Completions client (DeepSeek). The rest of the app speaks Responses-shaped items, so this class converts
 * `input` to `messages` and the reply back to `output` items (a "message" with `output_text`, and `function_call` items).
 * Stateless. The key is read on every call and never kept. Nothing from a request or response body is logged or kept.
 */
class ChatCompletionsClient(
    private val keyProvider: suspend () -> String?,
    private val callLog: LlmCallDao? = null,
    private val baseUrl: String = AgentConfig.BASE_URL,
    private val http: OkHttpClient = XaiResponsesClient.defaultHttp(),
    private val pause: suspend (Long) -> Unit = { delay(it) }
) : LlmClient {

    override suspend fun create(req: LlmRequest): LlmResult = execute(req, keyOverride = null)

    override suspend fun testKey(key: String, model: String): LlmResult = execute(
        LlmRequest(model, buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", "ping") }) }, tools = null,
            maxOutputTokens = TEST_MAX_OUTPUT_TOKENS, purpose = "TEST"),
        keyOverride = key
    )

    private suspend fun execute(req: LlmRequest, keyOverride: String?): LlmResult {
        val key = (keyOverride ?: keyProvider())?.takeIf { it.isNotBlank() } ?: return LlmResult.Unauthorized(401)
        val body = buildBody(req).toString().toRequestBody(JSON)
        var serverErrors = 0
        var rateLimits = 0
        while (true) {
            val started = System.currentTimeMillis()
            val outcome = try {
                http.newCall(
                    Request.Builder().url("$baseUrl/chat/completions")
                        .header("Authorization", "Bearer $key").header("Content-Type", "application/json")
                        .post(body).build()
                ).await().use { r -> readResponse(r, System.currentTimeMillis() - started) }
            } catch (e: IOException) {
                Attempt(LlmResult.Network(networkKind(e)), 0, networkKind(e), LlmUsage.ZERO)
            }
            log(req, outcome, System.currentTimeMillis() - started)
            when (val r = outcome.result) {
                is LlmResult.RateLimited -> {
                    // DeepSeek limits concurrent requests, not tokens, so a short wait and one more try is enough.
                    val wait = r.retryAfterSec ?: DEFAULT_RATE_WAIT_SEC
                    if (wait <= MAX_RETRY_AFTER_SEC && rateLimits < MAX_RETRIES) { rateLimits++; pause(wait * 1000L); continue }
                    return r
                }
                is LlmResult.HttpError -> {
                    if (r.status in 500..599 && serverErrors < MAX_RETRIES) { pause(BACKOFF_MS[serverErrors]); serverErrors++; continue }
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

    /** `GET /models` -> `data[].id`. No prices in the list; [PriceTable] supplies them. */
    private fun parseModels(text: String?): List<ModelInfo>? {
        if (text == null) return null
        return try {
            (Json.parseToJsonElement(text).jsonObject["data"] as? JsonArray)
                ?.mapNotNull { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull?.let { id -> ModelInfo(id) } }
                ?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) { null }
    }

    // ── Request ───────────────────────────────────────────────────────────────

    private fun buildBody(req: LlmRequest): JsonObject = buildJsonObject {
        put("model", req.model)
        put("messages", toMessages(req.input))
        val textOnly = req.toolChoice == "none"
        if (req.tools != null && !textOnly) {
            put("tools", toChatTools(req.tools))
            put("tool_choice", req.toolChoice)
        }
        put("max_tokens", req.maxOutputTokens)
        // Thinking is on by default and would make every reply slow and costly; the sketch needs none of it.
        put("thinking", buildJsonObject { put("type", "disabled") })
        put("temperature", req.temperature)
    }

    private fun text(o: JsonObject, k: String): String? = (o[k] as? JsonPrimitive)?.contentOrNull

    /** Responses `input` items -> chat `messages`. Consecutive function_call items become one assistant message. */
    internal fun toMessages(input: JsonArray): JsonArray = buildJsonArray {
        var pending: MutableList<JsonObject>? = null
        fun flush() {
            val calls = pending ?: return
            add(buildJsonObject {
                put("role", "assistant"); put("content", JsonNull)
                put("tool_calls", JsonArray(calls))
            })
            pending = null
        }
        for (item in input) {
            val o = item as? JsonObject ?: continue
            when (text(o, "type")) {
                "function_call" -> {
                    val list = pending ?: mutableListOf<JsonObject>().also { pending = it }
                    list += buildJsonObject {
                        put("id", text(o, "call_id") ?: ""); put("type", "function")
                        put("function", buildJsonObject { put("name", text(o, "name") ?: ""); put("arguments", text(o, "arguments") ?: "{}") })
                    }
                }
                "function_call_output" -> {
                    flush()
                    add(buildJsonObject { put("role", "tool"); put("tool_call_id", text(o, "call_id") ?: ""); put("content", text(o, "output") ?: "") })
                }
                "message", null -> {
                    flush()
                    add(buildJsonObject { put("role", text(o, "role") ?: "user"); put("content", contentText(o["content"])) })
                }
                else -> Unit
            }
        }
        flush()
    }

    private fun contentText(c: JsonElement?): String = when (c) {
        is JsonPrimitive -> c.contentOrNull.orEmpty()
        is JsonArray -> c.mapNotNull { ((it as? JsonObject)?.let { p -> text(p, "text") }) }.joinToString("\n")
        else -> ""
    }

    private fun toChatTools(tools: JsonArray): JsonArray = buildJsonArray {
        for (t in tools) {
            val o = t as? JsonObject ?: continue
            if (o["function"] != null) { add(o); continue }
            add(buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject { o.forEach { (k, v) -> if (k != "type") put(k, v) } })
            })
        }
    }

    // ── Response ──────────────────────────────────────────────────────────────

    private class Attempt(val result: LlmResult, val status: Int, val errorCode: String?, val usage: LlmUsage)

    private fun readResponse(r: Response, latencyMs: Long): Attempt {
        val status = r.code
        when {
            status == 401 || status == 403 -> return Attempt(LlmResult.Unauthorized(status), status, "unauthorized", LlmUsage.ZERO)
            status == 429 -> {
                val after = r.header("Retry-After")?.trim()?.toIntOrNull()
                return Attempt(LlmResult.RateLimited(after), status, "rate_limited", LlmUsage.ZERO)
            }
            status == 402 -> return Attempt(LlmResult.HttpError(402, "insufficient_balance"), status, "insufficient_balance", LlmUsage.ZERO)
            !r.isSuccessful -> {
                val code = errorCode(r.body?.string())
                return Attempt(LlmResult.HttpError(status, code), status, code, LlmUsage.ZERO)
            }
        }
        val root = try { Json.parseToJsonElement(r.body?.string() ?: "").jsonObject } catch (e: Exception) { null }
        val message = ((root?.get("choices") as? JsonArray)?.firstOrNull() as? JsonObject)?.get("message") as? JsonObject
            ?: return Attempt(LlmResult.HttpError(status, "bad_response"), status, "bad_response", LlmUsage.ZERO)
        val output = buildJsonArray {
            val content = text(message, "content").orEmpty()
            if (content.isNotEmpty()) add(buildJsonObject {
                put("type", "message"); put("role", "assistant")
                put("content", buildJsonArray { add(buildJsonObject { put("type", "output_text"); put("text", content) }) })
            })
            (message["tool_calls"] as? JsonArray)?.forEach { c ->
                val call = c as? JsonObject ?: return@forEach
                val fn = call["function"] as? JsonObject ?: return@forEach
                add(buildJsonObject {
                    put("type", "function_call"); put("name", text(fn, "name") ?: ""); put("arguments", text(fn, "arguments") ?: "{}")
                    put("call_id", text(call, "id") ?: UUID.randomUUID().toString())
                })
            }
        }
        val usage = toUsage(root["usage"] as? JsonObject)
        return Attempt(LlmResult.Ok(text(root, "id").orEmpty(), output, usage, latencyMs), status, null, usage)
    }

    private fun toUsage(u: JsonObject?): LlmUsage {
        if (u == null) return LlmUsage.ZERO
        fun int(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.intOrNull ?: 0
        return LlmUsage(
            input = int(u, "prompt_tokens"), cached = int(u, "prompt_cache_hit_tokens"), output = int(u, "completion_tokens"),
            reasoning = int(u["completion_tokens_details"] as? JsonObject, "reasoning_tokens")
        )
    }

    private fun errorCode(body: String?): String? {
        if (body == null) return null
        return try {
            val e = Json.parseToJsonElement(body).jsonObject["error"] as? JsonObject
            e?.let { text(it, "code") ?: text(it, "type") }
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
        private const val TEST_MAX_OUTPUT_TOKENS = 32
        private const val MAX_RETRY_AFTER_SEC = 10
        private const val DEFAULT_RATE_WAIT_SEC = 2
        private val BACKOFF_MS = longArrayOf(1_000, 4_000)
    }
}
