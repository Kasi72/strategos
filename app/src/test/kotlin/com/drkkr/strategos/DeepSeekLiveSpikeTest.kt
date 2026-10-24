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

    /**
     * S4: the real coach prompt on real stories, through the same parse and convert code the app uses. This is the check
     * that was missing when a phone run failed on a reply shape the hand-written fakes never produced.
     */
    @Test
    fun coachRepliesParseAndConvert() = runBlocking {
        Assume.assumeTrue("DEEPSEEK_API_KEY (or XAI_API_KEY) not set; live spike skipped", key != null)
        val stories = listOf(
            "My brother wants me to co-sign a big loan for his shop. My wife is strongly against it and my parents take his side.",
            "My partner went silent after I asked when we will move in together. I texted five times and now they seem cold.",
            "My co-founder wants to give an investor 30 percent. I think that is too much and we argued in front of the team."
        )
        val failures = mutableListOf<String>()
        for ((i, story) in stories.withIndex()) {
            val r = client.create(
                LlmRequest(
                    model = AgentConfig.DEFAULT_MODEL,
                    input = buildJsonArray {
                        add(buildJsonObject { put("role", "system"); put("content", com.drkkr.strategos.domain.agent.ArgonPrompts.EXTRACTION_PROMPT) })
                        add(buildJsonObject { put("role", "user"); put("content", "USER SAYS:\n$story") })
                    },
                    tools = null, maxOutputTokens = 900, temperature = 0.1, jsonObjectMode = true, purpose = "SPIKE"
                )
            )
            val text = (r as? LlmResult.Ok)?.output?.assistantText()
            if (text == null) { failures += "story $i: ${r::class.simpleName}"; continue }
            val parsed = com.drkkr.strategos.domain.agent.SketchParser.tryParse(text)
            val sketch = parsed.getOrNull()
            if (sketch == null) { failures += "story $i: unparseable (${parsed.exceptionOrNull()?.message?.take(200)}) reply=${text.take(1500)}"; continue }
            val ops = com.drkkr.strategos.domain.agent.SketchConverter.toOps(sketch, com.drkkr.strategos.domain.model.Scenario(title = "t")).ops
            val actions = ops.count { it.type == com.drkkr.strategos.domain.agent.OpType.ADD_ACTION }
            val payoffs = ops.count { it.type == com.drkkr.strategos.domain.agent.OpType.SET_PAYOFF }
            // Extraction should give 3 options for the user + 2 for the other side, and all 6 outcome pairs (12 payoff rows).
            val ok = actions == 5 && payoffs >= 10
            say("S4_story_$i", if (ok) "PASS" else "FAIL", "ops=${ops.size} actions=$actions payoffs=$payoffs archetype=${sketch.archetype} chars=${text.length}")
            if (!ok) failures += "story $i: ops=${ops.size} actions=$actions payoffs=$payoffs reply=${text.take(1800)}"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
