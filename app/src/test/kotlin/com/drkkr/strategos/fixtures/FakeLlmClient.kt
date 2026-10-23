package com.drkkr.strategos.fixtures

import com.drkkr.strategos.llm.*
import kotlinx.serialization.json.*

/** Scripted LlmClient: returns queued results in order and records every request it receives. */
class FakeLlmClient : LlmClient {
    private val queue = ArrayDeque<LlmResult>()
    val requests = mutableListOf<LlmRequest>()
    var models: ModelListResult = ModelListResult.Failed("not scripted")

    fun enqueue(result: LlmResult): FakeLlmClient { queue.addLast(result); return this }

    override suspend fun create(req: LlmRequest): LlmResult {
        requests += req
        return queue.removeFirstOrNull() ?: error("FakeLlmClient: no scripted result for request #${requests.size}")
    }

    override suspend fun listModels(apiKey: String?): ModelListResult = models

    companion object {
        private val usage = LlmUsage(100, 0, 20, 0)

        fun textReply(text: String, id: String = "resp") = LlmResult.Ok(
            id, buildJsonArray {
                add(buildJsonObject {
                    put("type", "message"); put("role", "assistant")
                    put("content", buildJsonArray { add(buildJsonObject { put("type", "output_text"); put("text", text) }) })
                })
            }, usage, 5
        )

        fun toolCall(name: String, argsJson: String = "{}", callId: String = "call_1", id: String = "resp") = LlmResult.Ok(
            id, buildJsonArray {
                add(buildJsonObject {
                    put("type", "function_call"); put("id", "fc_$callId"); put("name", name)
                    put("arguments", argsJson); put("call_id", callId)
                })
            }, usage, 5
        )

        fun error401() = LlmResult.Unauthorized(401)
        fun error429(retryAfter: Int? = null) = LlmResult.RateLimited(retryAfter)
        fun networkDown() = LlmResult.Network("offline")
    }
}
