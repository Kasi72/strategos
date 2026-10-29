package com.drkkr.strategos.fixtures

import com.drkkr.strategos.llm.*
import kotlinx.serialization.json.*

/**
 * Plays the model in the two-call flow. Extraction calls (purpose TURN) are answered from [extraction]; interpret calls
 * (purpose COACH) read the ENGINE DIRECTIVE out of the request and answer with a reply that implements its `move_to_implement`,
 * or with [wrongMove] to simulate a model that ignores the engine.
 */
class EngineEchoLlm(private val extraction: List<String>, private val wrongMove: String? = null) : LlmClient {
    val requests = mutableListOf<LlmRequest>()
    var lastDirective: JsonObject? = null
    private var turn = 0

    override suspend fun create(req: LlmRequest): LlmResult {
        requests += req
        if (req.purpose != "COACH") return FakeLlmClient.textReply(extraction[minOf(turn++, extraction.lastIndex)])
        val user = req.input.last().jsonObject.getValue("content").jsonPrimitive.content
        val directive = Json.parseToJsonElement(user.substringAfter("ENGINE DIRECTIVE:\n")).jsonObject
        lastDirective = directive
        val move = wrongMove ?: directive.getValue("move_to_implement").jsonPrimitive.content
        return FakeLlmClient.textReply(buildJsonObject {
            put("engine_move", move)
            put("what_happened", "They felt cornered. The pull is to chase.")
            put("clear_win_move", "Do this: $move")
            put("exact_words", "Let's talk tomorrow.")
            put("do_this", buildJsonArray { add("Give space") })
            put("dont_do_this", buildJsonArray { add("Don't double-text") })
        }.toString())
    }

    override suspend fun listModels(apiKey: String?) = ModelListResult.Failed("not scripted")
    override suspend fun testKey(key: String, model: String) = FakeLlmClient.textReply("pong")
}
