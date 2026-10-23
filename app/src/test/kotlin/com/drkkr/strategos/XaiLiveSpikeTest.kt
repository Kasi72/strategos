package com.drkkr.strategos

import com.drkkr.strategos.agent.EchoStrategy
import com.drkkr.strategos.llm.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume
import org.junit.Test

/**
 * Manual live spike (blueprint §9.5, TEST_CASES §11). Skipped unless XAI_API_KEY is in the environment:
 *   set -a; source .env; set +a; ./gradlew :app:testDebugUnitTest --tests "*XaiLiveSpikeTest*"
 * Prints one `SPIKE|<check>|<result>|<detail>` line per check. It prints statuses, field names and token counts only,
 * never the key and never model output text.
 */
class XaiLiveSpikeTest {
    private val key = System.getenv("XAI_API_KEY")
    private val base = System.getenv("XAI_BASE_URL")?.takeIf { it.isNotBlank() } ?: "https://api.x.ai/v1"
    private val model = System.getenv("XAI_MODEL")?.takeIf { it.isNotBlank() } ?: "grok-4.7"
    private val client by lazy { XaiResponsesClient(keyProvider = { key }, baseUrl = base) }

    private fun say(check: String, result: String, detail: String = "") = println("SPIKE|$check|$result|$detail")

    private fun user(text: String) = buildJsonObject { put("role", "user"); put("content", text) }

    private val echoTool = buildJsonArray {
        add(buildJsonObject {
            put("type", "function"); put("name", "echo")
            put("description", "Returns the given text.")
            put("parameters", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject { put("text", buildJsonObject { put("type", "string") }) })
                put("required", buildJsonArray { add("text") })
            })
        })
    }

    private fun describe(r: LlmResult): String = when (r) {
        is LlmResult.Ok -> "ok calls=${r.output.functionCalls().size} textLen=${r.output.assistantText().length} usage=${r.usage}"
        is LlmResult.Unauthorized -> "unauthorized ${r.status}"
        is LlmResult.RateLimited -> "rate_limited ${r.retryAfterSec}"
        is LlmResult.HttpError -> "http ${r.status} code=${r.code}"
        is LlmResult.Network -> "network ${r.kind}"
    }

    @Test
    fun spike() = runBlocking {
        Assume.assumeTrue("XAI_API_KEY not set", !key.isNullOrBlank())

        // S1 plain call, store:false
        val s1 = client.create(LlmRequest(model, buildJsonArray { add(user("Reply with the single word OK.")) }, null, maxOutputTokens = 400))
        say("S1", if (s1 is LlmResult.Ok && s1.output.assistantText().isNotBlank()) "PASS" else "FAIL", describe(s1))

        // S2 tool round trip
        val input1 = buildJsonArray { add(user("Call the echo tool with text \"ping\". Do not answer in plain text before calling it.")) }
        val first = client.create(LlmRequest(model, input1, echoTool, maxOutputTokens = 800))
        say("S2-first", if (first is LlmResult.Ok && first.output.functionCalls().isNotEmpty()) "PASS" else "FAIL", describe(first))
        if (first is LlmResult.Ok && first.output.functionCalls().isNotEmpty()) {
            val call = first.output.functionCalls().first()
            val itemTypes = first.output.mapNotNull { (it as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull }
            say("S2-output-item-types", "INFO", itemTypes.joinToString(","))
            fun items(strategy: EchoStrategy): List<JsonElement> = when (strategy) {
                EchoStrategy.VERBATIM -> first.output.toList()
                EchoStrategy.STRIP_IDS -> first.output.map { e -> if (e is JsonObject) JsonObject(e - "id") else e }
                EchoStrategy.CALLS_ONLY -> first.output.filter { (it as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "function_call" }
            }
            var winner: EchoStrategy? = null
            for (strategy in EchoStrategy.values()) {
                val input2 = buildJsonArray {
                    input1.forEach { add(it) }
                    items(strategy).forEach { add(it) }
                    add(functionCallOutput(call.callId, buildJsonObject { put("echo", "ping") }))
                }
                val r = client.create(LlmRequest(model, input2, echoTool, maxOutputTokens = 800))
                val pass = r is LlmResult.Ok && r.output.assistantText().isNotBlank()
                say("S2-$strategy", if (pass) "PASS" else "FAIL", describe(r))
                if (pass && winner == null) winner = strategy
            }
            say("S2", if (winner != null) "PASS" else "FAIL", "first working echo strategy = $winner")
        }

        // S3 role:"system" message in the middle of the input
        val s3 = client.create(LlmRequest(model, buildJsonArray {
            add(user("Reply with the single word OK."))
            add(buildJsonObject { put("role", "system"); put("content", "Live context: the user is in Guided mode.") })
            add(user("Reply with the single word OK again."))
        }, null, maxOutputTokens = 400))
        say("S3", if (s3 is LlmResult.Ok) "PASS" else "FAIL", describe(s3))

        // S4 tool_choice none
        val s4 = client.create(LlmRequest(model, buildJsonArray { add(user("Call the echo tool with text \"ping\".")) }, echoTool, toolChoice = "none", maxOutputTokens = 600))
        say("S4", if (s4 is LlmResult.Ok && s4.output.functionCalls().isEmpty() && s4.output.assistantText().isNotBlank()) "PASS" else "FAIL", describe(s4))

        // S5 usage field names (parsed by OutputItems.toUsage; non-zero input and output means the names are right)
        val usage = (s1 as? LlmResult.Ok)?.usage
        say("S5", if (usage != null && usage.input > 0 && usage.output > 0) "PASS" else "FAIL", "usage=$usage")

        // S6 model ids
        for (id in listOf("grok-4.7", "grok-4.3")) {
            val r = client.create(LlmRequest(id, buildJsonArray { add(user("Reply with the single word OK.")) }, null, maxOutputTokens = 400))
            say("S6-$id", if (r is LlmResult.Ok) "PASS" else "FAIL", describe(r))
        }

        // S7 model list endpoints: status, top-level keys and the field names of the first entry
        val http = OkHttpClient()
        for (path in listOf("language-models", "models")) {
            try {
                http.newCall(Request.Builder().url("$base/$path").header("Authorization", "Bearer $key").build()).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
                    val list = (root?.get("models") ?: root?.get("data")) as? JsonArray
                    val first = list?.firstOrNull() as? JsonObject
                    val ids = list?.mapNotNull { (it as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull }.orEmpty()
                    val priceFields = first?.filter { it.key.contains("price") }?.entries?.joinToString(";") { "${it.key}=${it.value}" }.orEmpty()
                    say("S7-$path", if (resp.isSuccessful && ids.isNotEmpty()) "PASS" else "FAIL",
                        "status=${resp.code} topKeys=${root?.keys} entryFields=${first?.keys} count=${ids.size} hasGrok47=${"grok-4.7" in ids} hasGrok43=${"grok-4.3" in ids} priceFields=[$priceFields]")
                }
            } catch (e: Exception) {
                say("S7-$path", "FAIL", e.javaClass.simpleName)
            }
        }
    }
}
