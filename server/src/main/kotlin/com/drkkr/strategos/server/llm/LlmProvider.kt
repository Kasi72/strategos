package com.drkkr.strategos.server.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class ChatMessage(val role: String, val content: String)

/** One chat call. [purpose] is for logs and tests only ("EXTRACT", "COACH", "SUMMARY", "JOURNAL"); it is never sent. */
data class ChatRequest(
    val messages: List<ChatMessage>,
    val jsonMode: Boolean = false,
    val maxTokens: Int = 600,
    val temperature: Double = 0.3,
    val purpose: String = "",
)

sealed interface ChatResult {
    data class Ok(val text: String, val promptCacheHitTokens: Int? = null) : ChatResult
    data class Failed(val status: Int?, val message: String, val retryable: Boolean) : ChatResult
}

/**
 * The only door to a language model (SDD §1, LLM-agnostic). The pipeline, memory and engine see this interface and nothing else,
 * so swapping DeepSeek for another provider is configuration, not code.
 */
interface LlmProvider {
    val name: String
    val model: String
    suspend fun chat(request: ChatRequest): ChatResult
}

/**
 * Any OpenAI-compatible `/chat/completions` endpoint (DeepSeek, OpenAI, Groq, Together, a local Ollama...).
 * The caller keeps the system message first and unchanged across turns, so DeepSeek's prefix cache keeps hitting.
 */
class OpenAiCompatibleProvider(
    override val name: String,
    private val baseUrl: String,
    private val apiKey: String,
    override val model: String,
    private val http: OkHttpClient = defaultClient,
) : LlmProvider {

    override suspend fun chat(request: ChatRequest): ChatResult = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("model", model)
            putJsonArray("messages") { request.messages.forEach { m -> addJsonObject { put("role", m.role); put("content", m.content) } } }
            put("max_tokens", request.maxTokens)
            put("temperature", request.temperature)
            if (request.jsonMode) putJsonObject("response_format") { put("type", "json_object") }
        }.toString()
        val call = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toRequestBody(JSON))
            .build()
        runCatching {
            http.newCall(call).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    // The provider's error text can echo the request; only the status and a short reason are kept.
                    return@use ChatResult.Failed(r.code, "HTTP ${r.code}", retryable = r.code == 429 || r.code >= 500)
                }
                val root = Json.parseToJsonElement(text).jsonObject
                val content = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")
                    ?.jsonPrimitive?.contentOrNull
                    ?: return@use ChatResult.Failed(r.code, "empty reply", retryable = true)
                val hit = root["usage"]?.jsonObject?.get("prompt_cache_hit_tokens")?.jsonPrimitive?.intOrNull
                ChatResult.Ok(content, hit)
            }
        }.getOrElse { ChatResult.Failed(null, it::class.simpleName ?: "network error", retryable = true) }
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build()
    }
}

/** Day-1 default (SDD §1): DeepSeek's `deepseek-chat`. */
object DeepSeekProvider {
    const val BASE_URL = "https://api.deepseek.com"
    const val MODEL = "deepseek-chat"
    fun create(apiKey: String, model: String = MODEL, baseUrl: String = BASE_URL): LlmProvider =
        OpenAiCompatibleProvider("deepseek", baseUrl, apiKey, model)
}

/**
 * Holds the active provider and swaps it at runtime (PUT /api/settings/llm) without a restart.
 * Everything else asks the router, never a concrete provider.
 */
class LlmRouter(initial: LlmProvider?) : LlmProvider {
    @Volatile private var current: LlmProvider? = initial
    /** The API key, in memory only: never written to disk or logged (CLAUDE.md). */
    @Volatile var keyInMemory: String? = null
    fun swap(provider: LlmProvider?) { current = provider }
    fun active(): LlmProvider? = current
    override val name: String get() = current?.name ?: "none"
    override val model: String get() = current?.model ?: ""
    override suspend fun chat(request: ChatRequest): ChatResult =
        current?.chat(request) ?: ChatResult.Failed(null, "No AI provider is set. Add a key in Settings.", retryable = false)

    companion object {
        /** Builds a provider from settings: "deepseek" (default) or any OpenAI-compatible base URL. */
        fun build(provider: String, apiKey: String?, model: String?, baseUrl: String?): LlmProvider? {
            if (apiKey.isNullOrBlank()) return null
            return when (provider.lowercase()) {
                "deepseek" -> DeepSeekProvider.create(apiKey, model?.takeIf { it.isNotBlank() } ?: DeepSeekProvider.MODEL,
                    baseUrl?.takeIf { it.isNotBlank() } ?: DeepSeekProvider.BASE_URL)
                else -> OpenAiCompatibleProvider(provider, requireNotNull(baseUrl?.takeIf { it.isNotBlank() }) { "baseUrl is required for $provider" },
                    apiKey, requireNotNull(model?.takeIf { it.isNotBlank() }) { "model is required for $provider" })
            }
        }
    }
}
