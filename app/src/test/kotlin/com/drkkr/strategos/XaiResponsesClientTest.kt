package com.drkkr.strategos

import com.drkkr.strategos.data.db.dao.LlmCallDao
import com.drkkr.strategos.data.db.entities.LlmCallEntity
import com.drkkr.strategos.llm.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class XaiResponsesClientTest {
    private lateinit var server: MockWebServer
    private val pauses = mutableListOf<Long>()
    private val logged = mutableListOf<LlmCallEntity>()
    private val dao = object : LlmCallDao {
        override suspend fun insert(e: LlmCallEntity) { logged += e }
        override suspend fun since(since: Long): List<LlmCallEntity> = logged
    }
    private val okBody = """{"id":"resp_1","output":[{"type":"message","content":[{"type":"output_text","text":"hi"}]}],
        "usage":{"input_tokens":10,"output_tokens":5,"input_tokens_details":{"cached_tokens":4},"output_tokens_details":{"reasoning_tokens":2}}}"""

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun client(key: String? = "xai-secret-key") = XaiResponsesClient(
        keyProvider = { key }, callLog = dao, baseUrl = server.url("/v1").toString().trimEnd('/'),
        http = OkHttpClient.Builder().retryOnConnectionFailure(false).build(), pause = { pauses += it }
    )

    private fun request(tools: JsonArray? = buildJsonArray { }) = LlmRequest(
        model = "grok-4.7", input = buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", "SECRET USER TEXT") }) },
        tools = tools, conversationId = "c1", turnId = "t1"
    )

    private fun ok() = MockResponse().setResponseCode(200).setBody(okBody)

    @Test
    fun TC_XAI_01_requestShapeAndHeaders() = runBlocking {
        server.enqueue(ok())
        val r = client().create(request())
        assertTrue(r is LlmResult.Ok)
        val rec = server.takeRequest()
        assertEquals("/v1/responses", rec.path)
        assertEquals("Bearer xai-secret-key", rec.getHeader("Authorization"))
        val body = Json.parseToJsonElement(rec.body.readUtf8()).jsonObject
        assertEquals(false, body["store"]!!.jsonPrimitive.boolean)
        assertEquals(false, body["parallel_tool_calls"]!!.jsonPrimitive.boolean)
        assertEquals("grok-4.7", body["model"]!!.jsonPrimitive.content)
        assertEquals("auto", body["tool_choice"]!!.jsonPrimitive.content)
        assertNotNull(body["tools"])
    }

    @Test
    fun TC_XAI_02_noToolsMeansNoToolFields() = runBlocking {
        server.enqueue(ok())
        client().create(request(tools = null))
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertNull(body["tools"]); assertNull(body["tool_choice"])
    }

    @Test
    fun okResultCarriesOutputAndUsage() = runBlocking {
        server.enqueue(ok())
        val r = client().create(request()) as LlmResult.Ok
        assertEquals("resp_1", r.responseId)
        assertEquals("hi", r.output.assistantText())
        assertEquals(LlmUsage(10, 4, 5, 2), r.usage)
    }

    @Test
    fun TC_XAI_06_unauthorizedMakesExactlyOneRequest() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key SECRET USER TEXT"}}"""))
        assertEquals(LlmResult.Unauthorized(401), client().create(request()))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun missingKeyIsUnauthorizedWithoutARequest() = runBlocking {
        assertEquals(LlmResult.Unauthorized(401), client(key = null).create(request()))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun TC_XAI_07_rateLimitWithShortRetryAfterIsRetriedOnce() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "1"))
        server.enqueue(ok())
        assertTrue(client().create(request()) is LlmResult.Ok)
        assertEquals(2, server.requestCount)
        assertEquals(listOf(1000L), pauses)
    }

    @Test
    fun TC_XAI_08_rateLimitWithLongRetryAfterIsNotRetried() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "60"))
        assertEquals(LlmResult.RateLimited(60), client().create(request()))
        assertEquals(1, server.requestCount)
        assertTrue(pauses.isEmpty())
    }

    @Test
    fun TC_XAI_09_serverErrorsRetriedTwiceThenReported() = runBlocking {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(500)) }
        assertEquals(LlmResult.HttpError(500, null), client().create(request()))
        assertEquals(3, server.requestCount)
        assertEquals(listOf(1000L, 4000L), pauses)
    }

    @Test
    fun serverErrorThenSuccess() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503)); server.enqueue(ok())
        assertTrue(client().create(request()) is LlmResult.Ok)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun TC_XAI_10_disconnectIsNetworkIo() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertEquals(LlmResult.Network("io"), client().create(request()))
    }

    @Test
    fun TC_XAI_11_badRequestKeepsOnlyTheCode() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"code":"model_not_found","message":"echo SECRET USER TEXT"}}"""))
        val r = client().create(request())
        assertEquals(LlmResult.HttpError(400, "model_not_found"), r)
        assertFalse(r.toString().contains("SECRET USER TEXT"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun TC_XAI_12_everyAttemptIsLoggedWithoutContent() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500)); server.enqueue(ok())
        client().create(request())
        assertEquals(2, logged.size)
        assertEquals(listOf(500, 200), logged.map { it.httpStatus })
        val good = logged.last()
        assertEquals(10, good.inputTokens); assertEquals(4, good.cachedTokens); assertEquals(5, good.outputTokens); assertEquals(2, good.reasoningTokens)
        assertEquals("c1", good.conversationId); assertEquals("t1", good.turnId); assertEquals("grok-4.7", good.model)
        val dump = logged.joinToString { it.toString() }
        assertFalse(dump.contains("SECRET USER TEXT") || dump.contains("xai-secret-key"))
    }

    @Test
    fun malformedSuccessBodyIsBadResponse() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))
        assertEquals(LlmResult.HttpError(200, "bad_response"), client().create(request()))
    }

    @Test
    fun listModelsReadsIdsAndFallsBack() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[{"id":"grok-4.7"},{"id":"grok-4.3"}]}"""))
        val r = client().listModels() as ModelListResult.Ok
        assertEquals(listOf("grok-4.7", "grok-4.3"), r.models.map { it.id })
        assertEquals("/v1/language-models", server.takeRequest().path)
        assertEquals("/v1/models", server.takeRequest().path)
    }
}
