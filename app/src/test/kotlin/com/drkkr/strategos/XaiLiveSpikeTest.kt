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

    /**
     * Phase 6 accept (manual, env-gated): the healthcare template end to end through the real CounselController and agent
     * assets, in Expert mode: intake → apply → analysis → stress test → what-if. Prints tool names and statuses only.
     */
    @Test
    fun counselEndToEnd() = runBlocking {
        Assume.assumeTrue("GROQ_API_KEY / XAI_API_KEY not set", !key.isNullOrBlank())
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val db = DbFactory.inMemoryForTests(app)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val scenario = InstitutionalHealthcareTemplate.create()
            db.scenarioDao().upsert(ScenarioEntity(scenario.id, scenario.title, "DRAFT", scenario.version, 1L, 1L, "{}"))
            val settings = SettingsRepository(db).apply {
                setAiEnabled(true); setConsentVersion(AgentConfig.CONSENT_VERSION); setUiMode(UiMode.EXPERT); setModel(model)
            }
            db.settingDao().put(SettingEntity(SettingsRepository.SECRET_XAI_KEY, "placeholder-not-the-key"))
            val conversations = ConversationRepository(db)
            val changeSets = ChangeSetRepository(db)
            val assets = AgentAssets(app)
            val gateway = CommittingGateway(scenario)
            val controller = CounselController(scope, CounselDeps(
                llm = XaiResponsesClient(keyProvider = { key }, callLog = db.llmCallDao(), baseUrl = base), systemPrompt = { assets.systemPrompt }, tools = { assets.tools }, agentVersion = { assets.agentVersion },
                conversations = conversations, changeSets = changeSets, memory = MemoryRepository(db), settings = settings,
                whatIfs = db.whatIfDao(), hasKey = { true }
            ), gateway)
            controller.bindScenario(scenario.id).join()
            val cid = controller.state.value.conversationId!!
            val toolsSeen = mutableListOf<String>()
            var seen = 0

            suspend fun report(step: String) {
                controller.awaitIdle()
                val msgs = conversations.messages(cid)
                val calls = msgs.drop(seen).filter { it.role == MessageRole.TOOL_CALL.name }.mapNotNull { it.toolName }
                val errors = msgs.drop(seen).count { it.role == MessageRole.TOOL_RESULT.name && it.payloadJson?.contains("\"error\"") == true }
                seen = msgs.size
                toolsSeen += calls
                say("E2E-$step", if (controller.state.value.error == null) "PASS" else "FAIL",
                    "tools=${calls.joinToString(",")} toolErrors=$errors error=${controller.state.value.error} version=${gateway.inner.scenario!!.version} " +
                        "calls=${db.llmCallDao().since(0).joinToString(";") { "${it.httpStatus}/${it.errorCode}" }}")
            }

            controller.send("I lead this unit and need to decide what to do. First read the current model. " +
                "Then propose adding the state health regulator as an influencer who can delay things.")
            report("intake")
            for (cs in changeSets.observe(cid).first().filter { it.status == ChangeSetStatus.PENDING.name }) {
                val n = com.drkkr.strategos.agent.MessageMapper.decodeOps(cs.opsJson).size
                controller.applyChangeSet(cs.id, (0 until n).map { OpDecision(it, Decision.ACCEPT) }).join()
            }
            report("apply")
            controller.send("Run the analysis now. If any payoffs are AI-inferred, stress-test them before you answer.")
            report("analysis")
            controller.send("What if the patient advocate withdraws support? Use the what-if tool, don't guess.")
            report("whatif")

            val stakeholdersAdded = gateway.inner.scenario!!.stakeholders.size - scenario.stakeholders.size
            say("E2E-SUMMARY", "INFO", "tools=${toolsSeen.distinct().joinToString(",")} stakeholdersAdded=$stakeholdersAdded commits=${gateway.commits}")
            for (needed in listOf("propose_changes", "run_analysis", "what_if")) {
                assertTrue("$needed was never called", needed in toolsSeen)
            }
        } finally {
            scope.cancel()
            db.close()
        }
    }
}
