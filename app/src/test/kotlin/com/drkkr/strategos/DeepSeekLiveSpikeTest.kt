package com.drkkr.strategos

import com.drkkr.strategos.agent.AgentConfig
import com.drkkr.strategos.llm.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * Manual live spike (Argon blueprint §10 A.4). Skipped unless DEEPSEEK_API_KEY is in the environment:
 *   set -a; source .env; set +a; ./gradlew :app:testDebugUnitTest --tests "*DeepSeekLiveSpikeTest*" --rerun-tasks
 * Prints `SPIKE|<check>|<result>|<detail>` lines with statuses, token counts and latency only, never the key or model text.
 */
class DeepSeekLiveSpikeTest {
    // XAI_API_KEY is the fallback name (execution prompt CP-DS1), for a dev .env that still uses it.
    private val key = System.getenv("DEEPSEEK_API_KEY")?.takeIf { it.isNotBlank() } ?: System.getenv("XAI_API_KEY")?.takeIf { it.isNotBlank() }
    private val client by lazy { ChatCompletionsClient(keyProvider = { key }) }

    private fun say(check: String, result: String, detail: String = "") = println("SPIKE|$check|$result|$detail")

    // A long, fixed prefix so DeepSeek's automatic prefix cache has something to hit on the second call.
    private val prefix = "You are a terse assistant. " + "Answer in JSON. ".repeat(200)

    private fun request(userText: String, json: Boolean) = LlmRequest(
        model = AgentConfig.DEFAULT_MODEL,
        input = buildJsonArray {
            add(buildJsonObject { put("role", "system"); put("content", prefix) })
            add(buildJsonObject { put("role", "user"); put("content", userText) })
        },
        tools = null, maxOutputTokens = 60, jsonObjectMode = json, purpose = "SPIKE"
    )

    @Test
    fun spike() = runBlocking {
        Assume.assumeTrue("DEEPSEEK_API_KEY (or XAI_API_KEY) not set; live spike skipped", key != null)

        val first = client.create(request("Reply with {\"ok\":true}", json = true))
        val jsonOk = first is LlmResult.Ok && runCatching { Json.parseToJsonElement(first.output.assistantText()).jsonObject }.isSuccess
        say("S1_json_mode", if (jsonOk) "PASS" else "FAIL", first::class.simpleName.orEmpty())

        val second = client.create(request("Reply with {\"ok\":true}", json = true))
        val hit = (second as? LlmResult.Ok)?.usage?.cached ?: 0
        say("S2_prefix_cache", if (hit > 0) "PASS" else "INFO", "prompt_cache_hit_tokens=$hit input=${(second as? LlmResult.Ok)?.usage?.input}")

        val screener = client.create(request("Tired after a long commute today", json = true))
        say("S3_screener_latency_ms", if (screener is LlmResult.Ok) "PASS" else "FAIL", "${(screener as? LlmResult.Ok)?.latencyMs}")

        assertTrue("json mode must return parseable JSON", jsonOk)
    }
}
