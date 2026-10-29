package com.drkkr.strategos

import com.drkkr.strategos.llm.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class OutputItemsTest {
    private fun arr(s: String) = Json.parseToJsonElement(s).jsonArray

    @Test
    fun TC_XAI_03_functionCallIsParsed() {
        val out = arr("""[{"type":"function_call","name":"get_scenario","arguments":"{\"a\":1}","call_id":"call_1","id":"fc_1"}]""")
        assertEquals(listOf(FunctionCall("get_scenario", "{\"a\":1}", "call_1")), out.functionCalls())
    }

    @Test
    fun functionCallsIgnoresOtherItemsAndIncompleteOnes() {
        val out = arr("""[{"type":"message","content":[]},{"type":"function_call","arguments":"{}"},{"type":"function_call","name":"x","call_id":"c"}]""")
        assertEquals(listOf(FunctionCall("x", "{}", "c")), out.functionCalls())
    }

    @Test
    fun TC_XAI_04_assistantTextJoinsOutputTextPartsWithNewline() {
        val out = arr("""[{"type":"reasoning"},{"type":"message","content":[{"type":"output_text","text":"One"},{"type":"refusal","text":"no"},{"type":"output_text","text":"Two"}]}]""")
        assertEquals("One\nTwo", out.assistantText())
        assertEquals("", arr("[]").assistantText())
    }

    @Test
    fun TC_XAI_05_missingUsageIsAllZeros() {
        assertEquals(LlmUsage.ZERO, (null as JsonObject?).toUsage())
        assertEquals(LlmUsage.ZERO, buildJsonObject { }.toUsage())
    }

    @Test
    fun usageReadsNestedDetails() {
        val u = Json.parseToJsonElement("""{"input_tokens":100,"output_tokens":40,"input_tokens_details":{"cached_tokens":30},"output_tokens_details":{"reasoning_tokens":12}}""").jsonObject.toUsage()
        assertEquals(LlmUsage(100, 30, 40, 12), u)
    }

    @Test
    fun functionCallOutputStringifiesTheOutputObject() {
        val item = functionCallOutput("call_1", buildJsonObject { put("ok", true) })
        assertEquals("function_call_output", item["type"]!!.jsonPrimitive.content)
        assertEquals("call_1", item["call_id"]!!.jsonPrimitive.content)
        assertEquals("""{"ok":true}""", item["output"]!!.jsonPrimitive.content)
    }

    @Test
    fun priceTableEstimate() {
        // 1M uncached in @0.075 + 1M cached @0.0375 + 1M out @0.30 = 0.4125
        assertEquals(0.4125, PriceTable.estimateUsd("openai/gpt-oss-20b", 2_000_000, 1_000_000, 1_000_000)!!, 1e-9)
        assertNull(PriceTable.estimateUsd("unknown-model", 1, 0, 1))
    }
}
