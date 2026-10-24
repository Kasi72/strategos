package com.drkkr.strategos.llm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class FunctionCall(val name: String, val arguments: String, val callId: String)

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** Items with type == "function_call". Items missing a name or call_id are ignored. */
fun JsonArray.functionCalls(): List<FunctionCall> = mapNotNull { item ->
    val o = item as? JsonObject ?: return@mapNotNull null
    if (o.str("type") != "function_call") return@mapNotNull null
    FunctionCall(
        name = o.str("name") ?: return@mapNotNull null,
        arguments = o.str("arguments") ?: "{}",
        callId = o.str("call_id") ?: return@mapNotNull null
    )
}

private val THINK_BLOCK = Regex("^\\s*<think>[\\s\\S]*?</think>\\s*")

/** Text of every "message" item: its content parts of type "output_text", joined with a newline. A leading `<think>` block is dropped. */
fun JsonArray.assistantText(): String = mapNotNull { item ->
    val o = item as? JsonObject ?: return@mapNotNull null
    if (o.str("type") != "message") return@mapNotNull null
    (o["content"] as? JsonArray)?.mapNotNull { part ->
        val p = part as? JsonObject ?: return@mapNotNull null
        if (p.str("type") == "output_text") p.str("text") else null
    }
}.flatten().joinToString("\n").replaceFirst(THINK_BLOCK, "")

fun functionCallOutput(callId: String, output: JsonObject): JsonObject = JsonObject(
    mapOf(
        "type" to JsonPrimitive("function_call_output"),
        "call_id" to JsonPrimitive(callId),
        "output" to JsonPrimitive(output.toString())
    )
)

/** Every field is optional and defaults to 0. */
fun JsonObject?.toUsage(): LlmUsage {
    if (this == null) return LlmUsage.ZERO
    fun int(o: JsonObject?, key: String): Int = (o?.get(key) as? JsonPrimitive)?.intOrNull ?: 0
    return LlmUsage(
        input = int(this, "input_tokens"),
        cached = int(this["input_tokens_details"] as? JsonObject, "cached_tokens"),
        output = int(this, "output_tokens"),
        reasoning = int(this["output_tokens_details"] as? JsonObject, "reasoning_tokens")
    )
}
