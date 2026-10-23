package com.drkkr.strategos.llm

import com.drkkr.strategos.agent.AgentConfig
import kotlinx.serialization.json.JsonArray

data class LlmRequest(
    val model: String,
    val input: JsonArray,
    val tools: JsonArray?,
    val toolChoice: String = "auto",
    val maxOutputTokens: Int = AgentConfig.MAX_OUTPUT_TOKENS,
    val temperature: Double = AgentConfig.TEMPERATURE
)

data class LlmUsage(val input: Int, val cached: Int, val output: Int, val reasoning: Int) {
    companion object { val ZERO = LlmUsage(0, 0, 0, 0) }
}

sealed class LlmResult {
    data class Ok(val responseId: String, val output: JsonArray, val usage: LlmUsage, val latencyMs: Long) : LlmResult()
    data class Unauthorized(val status: Int) : LlmResult()
    data class RateLimited(val retryAfterSec: Int?) : LlmResult()
    data class HttpError(val status: Int, val code: String?) : LlmResult()   // body NOT kept (may echo content)
    data class Network(val kind: String) : LlmResult()                      // "timeout" | "offline" | "io"
}

data class ModelInfo(val id: String, val inputPricePerM: Double? = null, val outputPricePerM: Double? = null)

sealed class ModelListResult {
    data class Ok(val models: List<ModelInfo>) : ModelListResult()
    data class Failed(val reason: String) : ModelListResult()
}

interface LlmClient {
    suspend fun create(req: LlmRequest): LlmResult
    suspend fun listModels(apiKey: String? = null): ModelListResult   // null = use the stored key
}
