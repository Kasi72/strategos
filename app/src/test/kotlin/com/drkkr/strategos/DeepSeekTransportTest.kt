package com.drkkr.strategos

import com.drkkr.strategos.agent.AgentConfig
import com.drkkr.strategos.llm.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DeepSeekTransportTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun client() = ChatCompletionsClient(
        keyProvider = { "sk-test" }, baseUrl = server.url("/v1").toString().trimEnd('/'),
        http = OkHttpClient.Builder().retryOnConnectionFailure(false).build(), pause = { }
    )

    private fun request(model: String = "deepseek-chat", json: Boolean = false) = LlmRequest(
        model = model, input = buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", "hi") }) },
        tools = null, jsonObjectMode = json
    )

    private fun reply(usage: String) = MockResponse().setResponseCode(200).setBody(
        """{"id":"c1","choices":[{"message":{"role":"assistant","content":"hi"}}],"usage":$usage}"""
    )

    @Test
    fun TC_DS_01_defaultConfigIsDeepSeek() {
        assertEquals("https://api.deepseek.com/v1", AgentConfig.BASE_URL)
        assertEquals("deepseek-chat", AgentConfig.DEFAULT_MODEL)
        assertTrue(AgentConfig.MODEL_OPTIONS.containsAll(listOf("deepseek-chat", "deepseek-reasoner")))
        assertEquals(2_800, AgentConfig.MAX_REQUEST_TOKEN_BUDGET)
    }

    @Test
    fun TC_DS_02_chatCompletionsParsesCacheHitTokens() = runBlocking {
        server.enqueue(reply("""{"prompt_tokens":1000,"prompt_cache_hit_tokens":800,"completion_tokens":150}"""))
        val r = client().create(request()) as LlmResult.Ok
        assertEquals(1000, r.usage.input)
        assertEquals(800, r.usage.cached)
        assertEquals(150, r.usage.output)
    }

    @Test
    fun TC_DS_02b_fallsBackToPromptTokensDetails() = runBlocking {
        server.enqueue(reply("""{"prompt_tokens":10,"completion_tokens":1,"prompt_tokens_details":{"cached_tokens":6},"completion_tokens_details":{"reasoning_tokens":3}}"""))
        val r = client().create(request()) as LlmResult.Ok
        assertEquals(6, r.usage.cached)
        assertEquals(3, r.usage.reasoning)
    }

    @Test
    fun TC_DS_03_jsonObjectModeHeaderAndBody() = runBlocking {
        server.enqueue(reply("{}")); server.enqueue(reply("{}")); server.enqueue(reply("{}"))
        client().create(request(json = true))
        val on = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(buildJsonObject { put("type", "json_object") }, on["response_format"])
        client().create(request(json = false))
        assertNull(Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["response_format"])
        client().create(request(model = "deepseek-reasoner", json = true))       // the reasoner takes neither field
        val reasoner = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertNull(reasoner["response_format"]); assertNull(reasoner["temperature"])
    }

    @Test
    fun TC_DS_04_reasonerStripsThinkTags() {
        val text = "<think>\nLet me analyze the chicken game...\n</think>\n{\"title\":\"Weekend Plan\"}"
        val output = buildJsonArray {
            add(buildJsonObject {
                put("type", "message")
                put("content", buildJsonArray { add(buildJsonObject { put("type", "output_text"); put("text", text) }) })
            })
        }
        assertEquals("{\"title\":\"Weekend Plan\"}", output.assistantText())
    }

    @Test
    fun TC_DS_05_priceTableDeepSeekAndLegacy() {
        // 500k uncached * 0.27 + 500k cached * 0.07 + 100k out * 1.10 = 0.135 + 0.035 + 0.11
        assertEquals(0.28, PriceTable.estimateUsd("deepseek-chat", 1_000_000, 500_000, 100_000)!!, 1e-9)
        assertNotNull(PriceTable.estimateUsd("deepseek-reasoner", 1_000, 0, 1_000))
        assertNotNull(PriceTable.estimateUsd("openai/gpt-oss-20b", 1_000, 0, 1_000))
    }
}
