package com.drkkr.strategos

import com.drkkr.strategos.agent.AgentConfig
import com.drkkr.strategos.agent.EchoStrategy
import com.drkkr.strategos.llm.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import androidx.test.core.app.ApplicationProvider
import com.drkkr.strategos.agent.AgentAssets
import com.drkkr.strategos.agent.CounselController
import com.drkkr.strategos.agent.CounselDeps
import com.drkkr.strategos.data.db.DbFactory
import com.drkkr.strategos.data.db.entities.ScenarioEntity
import com.drkkr.strategos.data.db.entities.SettingEntity
import com.drkkr.strategos.data.repo.ChangeSetRepository
import com.drkkr.strategos.data.repo.ChangeSetStatus
import com.drkkr.strategos.data.repo.ConversationRepository
import com.drkkr.strategos.data.repo.MemoryRepository
import com.drkkr.strategos.data.repo.MessageRole
import com.drkkr.strategos.data.repo.SettingsRepository
import com.drkkr.strategos.data.repo.UiMode
import com.drkkr.strategos.domain.agent.Decision
import com.drkkr.strategos.domain.agent.OpDecision
import com.drkkr.strategos.templates.InstitutionalHealthcareTemplate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Manual live spike (blueprint §9.5, TEST_CASES §11). Skipped unless XAI_API_KEY is in the environment:
 *   set -a; source .env; set +a; ./gradlew :app:testDebugUnitTest --tests "*XaiLiveSpikeTest*"
 * Prints one `SPIKE|<check>|<result>|<detail>` line per check. It prints statuses, field names and token counts only,
 * never the key and never model output text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class XaiLiveSpikeTest {
    // The provider is Groq (user decision 2026-10-23). The key may be in GROQ_API_KEY or, as in the dev .env, XAI_API_KEY.
    private val key = System.getenv("GROQ_API_KEY")?.takeIf { it.isNotBlank() } ?: System.getenv("XAI_API_KEY")
    private val base = System.getenv("LLM_BASE_URL")?.takeIf { it.isNotBlank() } ?: AgentConfig.BASE_URL
    private val model = System.getenv("LLM_MODEL")?.takeIf { it.isNotBlank() } ?: AgentConfig.DEFAULT_MODEL
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
        Assume.assumeTrue("GROQ_API_KEY / XAI_API_KEY not set", !key.isNullOrBlank())

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

        // S4 tool_choice none (the client turns it into a text-only request; the provider rejects the literal value)
        val s4 = client.create(LlmRequest(model, buildJsonArray { add(user("Call the echo tool with text \"ping\".")) }, echoTool, toolChoice = "none", maxOutputTokens = 600))
        say("S4", if (s4 is LlmResult.Ok && s4.output.functionCalls().isEmpty() && s4.output.assistantText().isNotBlank()) "PASS" else "FAIL", describe(s4))

        // S5 usage field names (parsed by OutputItems.toUsage; non-zero input and output means the names are right)
        val usage = (s1 as? LlmResult.Ok)?.usage
        say("S5", if (usage != null && usage.input > 0 && usage.output > 0) "PASS" else "FAIL", "usage=$usage")

        // S6 model ids
        for (id in AgentConfig.MODEL_OPTIONS) {
            val r = client.create(LlmRequest(id, buildJsonArray { add(user("Reply with the single word OK.")) }, null, maxOutputTokens = 400))
            say("S6-$id", if (r is LlmResult.Ok) "PASS" else "FAIL", describe(r))
        }

        // S7 model list endpoints: status, top-level keys and the field names of the first entry
        val http = OkHttpClient()
        for (path in listOf("models")) {
            try {
                http.newCall(Request.Builder().url("$base/$path").header("Authorization", "Bearer $key").build()).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
                    val list = (root?.get("models") ?: root?.get("data")) as? JsonArray
                    val first = list?.firstOrNull() as? JsonObject
                    val ids = list?.mapNotNull { (it as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull }.orEmpty()
                    val priceFields = first?.filter { it.key.contains("price") }?.entries?.joinToString(";") { "${it.key}=${it.value}" }.orEmpty()
                    say("S7-$path", if (resp.isSuccessful && ids.isNotEmpty()) "PASS" else "FAIL",
                        "status=${resp.code} topKeys=${root?.keys} entryFields=${first?.keys} count=${ids.size} hasDefault=${AgentConfig.DEFAULT_MODEL in ids} priceFields=[$priceFields]")
                }
            } catch (e: Exception) {
                say("S7-$path", "FAIL", e.javaClass.simpleName)
            }
        }
    }

    /** S8: the account's rate limits, read from Groq's x-ratelimit-* response headers on one tiny call. Prints numbers only. */
    @Test
    fun limits() = runBlocking {
        Assume.assumeTrue("GROQ_API_KEY / XAI_API_KEY not set", !key.isNullOrBlank())
        val body = buildJsonObject {
            put("model", model); put("store", false); put("max_output_tokens", 50)
            put("input", buildJsonArray { add(user("Reply OK.")) })
        }.toString()
        val req = Request.Builder().url("$base/responses").header("Authorization", "Bearer $key")
            .post(okhttp3.RequestBody.create("application/json".toMediaType(), body)).build()
        OkHttpClient().newCall(req).execute().use { resp ->
            val limits = resp.headers.names().filter { it.lowercase().startsWith("x-ratelimit") }.sorted()
                .joinToString(" ") { "$it=${resp.header(it)}" }
            say("S8-limits", if (resp.isSuccessful) "PASS" else "FAIL", "status=${resp.code} $limits")
        }
    }

    /** Diagnostic: which part of a Counsel request does the provider reject? Synthetic input only, so error text is safe to print. */
    @Test
    fun diagnoseRequests() = runBlocking {
        Assume.assumeTrue("GROQ_API_KEY / XAI_API_KEY not set", !key.isNullOrBlank())
        val dir = listOf("src/main/assets/agent", "app/src/main/assets/agent").map { java.io.File(it) }.first { it.isDirectory }
        val prompt = java.io.File(dir, "system_prompt.md").readText()
        val all = Json.parseToJsonElement(java.io.File(dir, "tools.json").readText()).jsonArray
        val http = OkHttpClient()
        fun send(label: String, tools: JsonArray?, reasoning: Boolean) {
            val body = buildJsonObject {
                put("model", model); put("store", false); put("max_output_tokens", 300); put("parallel_tool_calls", false)
                put("input", buildJsonArray { add(buildJsonObject { put("role", "system"); put("content", prompt) }); add(user("Hello, I need help with a decision.")) })
                if (tools != null) { put("tools", tools); put("tool_choice", "auto") }
                if (reasoning) put("reasoning", buildJsonObject { put("effort", "low") })
            }.toString()
            val req = Request.Builder().url("$base/responses").header("Authorization", "Bearer $key")
                .post(okhttp3.RequestBody.create("application/json".toMediaType(), body)).build()
            http.newCall(req).execute().use { resp ->
                val err = runCatching { Json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject["error"]?.jsonObject }.getOrNull()
                say("DIAG-$label", if (resp.isSuccessful) "PASS" else "FAIL",
                    "status=${resp.code} type=${err?.get("type")} code=${err?.get("code")} param=${err?.get("param")} msg=${err?.get("message")?.toString()?.take(240)}")
            }
            Thread.sleep(4_000)
        }
        send("no-tools+reasoning", null, true)
        send("all-tools+reasoning", all, true)
        send("all-tools", all, false)
        val guided = JsonArray(all.filter { it.jsonObject["name"]?.jsonPrimitive?.content in AgentConfig.GUIDED_TOOLS })
        send("guided-tools+reasoning", guided, true)
        for (tool in all) send("only-" + tool.jsonObject["name"]!!.jsonPrimitive.content, JsonArray(listOf(tool)), true)
    }

    /** Diagnostic: replay a Guided and an Expert turn with the real prompt, tools and live context. Synthetic text only. */
    @Test
    fun diagnoseTurns() = runBlocking {
        Assume.assumeTrue("GROQ_API_KEY / XAI_API_KEY not set", !key.isNullOrBlank())
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val db = DbFactory.inMemoryForTests(app)
        try {
            val assets = AgentAssets(app)
            val http = OkHttpClient()
            suspend fun replay(label: String, mode: UiMode, scenario: com.drkkr.strategos.domain.model.Scenario, text: String, parallel: Boolean) {
                db.scenarioDao().upsert(ScenarioEntity(scenario.id, "t", "DRAFT", 1, 1L, 1L, "{}"))
                val settings = SettingsRepository(db).apply { setUiMode(mode) }
                val conversations = ConversationRepository(db)
                val cid = conversations.getOrCreateActive(scenario.id, "m", "v").id
                conversations.clear(cid)
                conversations.append(cid, "t", MessageRole.USER, text)
                val input = com.drkkr.strategos.agent.ContextAssembler({ assets.systemPrompt }, conversations, ChangeSetRepository(db),
                    MemoryRepository(db), settings, { scenario }).build(cid, scenario.id)
                val tools = if (mode == UiMode.GUIDED) JsonArray(assets.tools.filter { it.jsonObject["name"]?.jsonPrimitive?.content in AgentConfig.GUIDED_TOOLS }) else assets.tools
                val body = buildJsonObject {
                    put("model", model); put("store", false); put("max_output_tokens", AgentConfig.MAX_OUTPUT_TOKENS)
                    put("parallel_tool_calls", parallel); put("input", input); put("tools", tools); put("tool_choice", "auto")
                    put("reasoning", buildJsonObject { put("effort", "low") })
                }.toString()
                val req = Request.Builder().url("$base/responses").header("Authorization", "Bearer $key")
                    .post(okhttp3.RequestBody.create("application/json".toMediaType(), body)).build()
                http.newCall(req).execute().use { resp ->
                    val root = runCatching { Json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject }.getOrNull()
                    val err = root?.get("error") as? JsonObject
                    if (err != null) say("TURN-$label", "FAIL", "status=${resp.code} msg=${err["message"]?.toString()?.take(700)}")
                    else {
                        val out = root?.get("output")?.jsonArray ?: JsonArray(emptyList())
                        val calls = out.functionCalls().joinToString(" | ") { "${it.name}(${it.arguments.take(500)})" }
                        say("TURN-$label", "PASS", "status=${resp.code} calls=[$calls] text=${out.assistantText().take(200)}")
                    }
                }
                Thread.sleep(12_000)
            }
            val story = "My brother wants me to co-sign a big loan for his new shop. My wife is strongly against it. I don't know what to do."
            val blank = com.drkkr.strategos.domain.model.Scenario(title = "Loan for my brother")
            replay("guided-serial", UiMode.GUIDED, blank, story, parallel = false)
            replay("guided-parallel", UiMode.GUIDED, blank, story, parallel = true)
            val loan = ShortIdsTest.loan().let { s -> s.copy(payoffs = s.payoffs.take(6)) }
            replay("expert-fill", UiMode.EXPERT, loan, "Fill in the remaining outcomes with your best estimates, marked as AI estimates, then run the analysis.", parallel = false)
        } finally {
            db.close()
        }
    }

    /** Diagnostic: the raw one-shot reply for the synthetic loan story (safe to print). */
    @Test
    fun diagnoseSketch() = runBlocking {
        Assume.assumeTrue("GROQ_API_KEY / XAI_API_KEY not set", !key.isNullOrBlank())
        val story = "My brother wants me to co-sign a big loan for his new shop. My wife is strongly against it. My parents always take his side. I don't know what to do."
        val input = buildJsonArray {
            add(buildJsonObject { put("role", "system"); put("content", com.drkkr.strategos.agent.OneShotTurn.PROMPT) })
            add(user("APP CONTEXT (what is already saved):\n{\"title\":\"Loan\"}\nUSER SAYS:\n$story"))
        }
        val r = client.create(LlmRequest(model, input, null, maxOutputTokens = AgentConfig.ONE_SHOT_MAX_OUTPUT_TOKENS))
        if (r is LlmResult.Ok) say("SKETCH-RAW", "INFO", r.output.assistantText().replace("\n", " "))
        else say("SKETCH-RAW", "FAIL", describe(r))
    }

    /**
     * Phase 6 accept (manual, env-gated), SIMPLE PROFILE: the loan story, then two follow-ups, through the real
     * CounselController (one-shot flow) against the real model. Prints counts, token usage and the app's plain answer
     * (the story is synthetic, so the text is safe to print).
     */
    @Test
    fun counselEndToEnd() = runBlocking {
        Assume.assumeTrue("GROQ_API_KEY / XAI_API_KEY not set", !key.isNullOrBlank())
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val db = DbFactory.inMemoryForTests(app)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val story = "My brother wants me to co-sign a big loan for his new shop. My wife is strongly against it. My parents always take his side. I don't know what to do."
            val scenario = com.drkkr.strategos.domain.model.Scenario(title = "Loan for my brother", description = story)
            db.scenarioDao().upsert(ScenarioEntity(scenario.id, scenario.title, "DRAFT", scenario.version, 1L, 1L, "{}"))
            val settings = SettingsRepository(db).apply {
                setAiEnabled(true); setConsentVersion(AgentConfig.CONSENT_VERSION); setUiMode(UiMode.GUIDED); setModel(model)
            }
            db.settingDao().put(SettingEntity(SettingsRepository.SECRET_XAI_KEY, "placeholder-not-the-key"))
            val conversations = ConversationRepository(db)
            val gateway = CommittingGateway(scenario)
            val controller = CounselController(scope, CounselDeps(
                llm = XaiResponsesClient(keyProvider = { key }, callLog = db.llmCallDao(), baseUrl = base),
                systemPrompt = { "" }, tools = { kotlinx.serialization.json.JsonArray(emptyList()) }, agentVersion = { "2.0.0" },
                conversations = conversations, changeSets = ChangeSetRepository(db), memory = MemoryRepository(db), settings = settings,
                whatIfs = db.whatIfDao(), hasKey = { true }, oneShot = true
            ), gateway)
            controller.bindScenario(scenario.id).join()
            val cid = controller.state.value.conversationId!!
            var seenCalls = 0
            var failures = 0

            suspend fun turn(step: String, text: String) {
                controller.send(text)?.join()
                controller.awaitIdle()
                val s = gateway.inner.scenario!!
                val cov = com.drkkr.strategos.domain.agent.PayoffCoverage.of(s)
                val llm = db.llmCallDao().since(0)
                val usage = llm.drop(seenCalls).joinToString(";") { "${it.httpStatus}${it.errorCode?.let { c -> "($c)" } ?: ""}:in${it.inputTokens}/out${it.outputTokens}" }
                seenCalls = llm.size
                val error = controller.state.value.error
                if (error != null) failures++
                say("E2E-$step", if (error == null) "PASS" else "FAIL",
                    "error=$error people=${s.stakeholders.size} players=${s.players().size} options=${s.actions.size} outcomes=${cov.filled}/${cov.expected} " +
                        "limits=${s.constraints.size} version=${s.version} calls=[$usage]")
                conversations.messages(cid).lastOrNull { it.role == MessageRole.ASSISTANT.name || it.role == MessageRole.ERROR.name }
                    ?.let { say("E2E-$step-REPLY", "INFO", it.text.replace("\n", " / ")) }
                kotlinx.coroutines.delay(15_000)    // let the 8k tokens/minute bucket refill
            }

            turn("1-story", story)
            turn("2-followup", "I decided to wait and told him. He said he will ask our parents for help instead.")
            turn("3-followup", "My wife says she could accept a smaller fixed gift, but nothing in our name.")
            say("E2E-SUMMARY", "INFO", "failedTurns=$failures commits=${gateway.commits} undo=${controller.canUndo}")
            assertTrue("the first message did not produce a model", gateway.commits >= 1)
        } finally {
            scope.cancel()
            db.close()
        }
    }
}
