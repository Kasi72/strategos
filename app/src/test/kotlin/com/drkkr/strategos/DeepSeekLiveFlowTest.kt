package com.drkkr.strategos

import androidx.test.core.app.ApplicationProvider
import com.drkkr.strategos.agent.*
import com.drkkr.strategos.data.db.DbFactory
import com.drkkr.strategos.data.db.StrategosDatabase
import com.drkkr.strategos.data.db.entities.SettingEntity
import com.drkkr.strategos.data.repo.*
import com.drkkr.strategos.data.secure.XorBase64TestCipher
import com.drkkr.strategos.domain.agent.CoachReply
import com.drkkr.strategos.domain.agent.SketchParser
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.llm.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Records every reply so a run can show what call 1 extracted and what call 2 said. */
class RecordingLlm(private val inner: LlmClient) : LlmClient {
    class Call(val purpose: String, val text: String)
    val calls = mutableListOf<Call>()
    override suspend fun create(req: LlmRequest): LlmResult =
        inner.create(req).also { r -> if (r is LlmResult.Ok) calls += Call(req.purpose, r.output.assistantText()) }
    override suspend fun listModels(apiKey: String?) = inner.listModels(apiKey)
    override suspend fun testKey(key: String, model: String) = inner.testKey(key, model)
}

/**
 * Live end-to-end runs of the Talk flow (controller, context, DeepSeek call 1, engine, DeepSeek call 2, answer) on the JVM.
 * Skipped without DEEPSEEK_API_KEY. The replay writes what happened at each step to FLOW_OUT_FILE.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeepSeekLiveFlowTest {
    private val key = System.getenv("DEEPSEEK_API_KEY")?.takeIf { it.isNotBlank() }

    private class Rig(val db: StrategosDatabase, val scope: CoroutineScope, val llm: RecordingLlm, val controller: CounselController,
                      val trajectory: TrajectoryRepository, val gateway: CommittingGateway) {
        val directives = mutableListOf<String>()
    }

    private suspend fun rig(key: String, scenarioId: String, title: String): Rig {
        val db = DbFactory.inMemoryForTests(ApplicationProvider.getApplicationContext())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val scenarios = ScenarioRepository(db, null).also { it.load() }
        val saved = (scenarios.save(Scenario(id = scenarioId, title = title)) as SaveResult.Success).saved
        val conversations = ConversationRepository(db)
        val settings = SettingsRepository(db).apply { setAiEnabled(true); setConsentVersion(AgentConfig.CONSENT_VERSION) }
        db.settingDao().put(SettingEntity(SettingsRepository.SECRET_XAI_KEY, "ciphertext"))
        val trajectory = TrajectoryRepository(db, scenarios)
        val journal = JournalRepository(db, XorBase64TestCipher(), MemoryRepository(db), conversations)
        val llm = RecordingLlm(ChatCompletionsClient(keyProvider = { key }))
        val gateway = CommittingGateway(saved)
        val controller = CounselController(scope, CounselDeps(
            llm = llm, systemPrompt = { "unused" }, tools = { JsonArray(emptyList()) }, agentVersion = { "2.0.0" },
            conversations = conversations, changeSets = ChangeSetRepository(db), memory = MemoryRepository(db),
            settings = settings, whatIfs = db.whatIfDao(), hasKey = { true }, oneShot = true, coach = true,
            trajectory = trajectory, journal = journal
        ), gateway)
        controller.bindScenario(scenarioId).join()
        return Rig(db, scope, llm, controller, trajectory, gateway).also { r ->
            DebugLog.sink = { m -> if (m.startsWith("DIRECTIVE ")) r.directives += m.removePrefix("DIRECTIVE ") }
        }
    }

    private suspend fun Rig.say(text: String): String {
        controller.send(text)!!.join(); controller.awaitIdle()
        // Read from the database: the UI state is updated by a flow that can lag a moment behind.
        return ConversationRepository(db).messages(controller.state.value.conversationId!!).lastOrNull { it.role == MessageRole.ASSISTANT.name }?.text.orEmpty()
    }

    @Test
    fun firstMessageGivesAnEngineGroundedCoachAnswerThenAFollowUpWorks() = runBlocking {
        Assume.assumeTrue("DEEPSEEK_API_KEY not set; live flow skipped", key != null)
        val r = rig(key!!, "loan", "Loan")
        try {
            val first = r.say("My brother wants me to co-sign a big loan for his new shop. My wife is strongly against it. My parents always take his side.")
            assertNull("turn error: ${r.controller.state.value.error}", r.controller.state.value.error)
            assertTrue("no coach guide in:\n$first", first.contains("Clear-win move:"))
            assertTrue("no engine badge in:\n$first", first.lines().last().startsWith("Engine: "))
            assertFalse(first.contains("Options to consider"))
            assertEquals("one extraction call and one interpret call", listOf("TURN", "COACH"), r.llm.calls.map { it.purpose }.take(2))
            val second = r.say("I decided to wait and told him. He said he will ask our parents.")
            assertNull("follow-up error: ${r.controller.state.value.error}", r.controller.state.value.error)
            assertTrue("follow-up has no guide:\n$second", second.contains("Clear-win move:"))
            println("FLOW|PASS|first=${first.length} chars|second=${second.length} chars")
        } finally { DebugLog.sink = null; r.scope.cancel(); r.db.close() }
    }

    /** Replays a JSON array of messages from FLOW_MESSAGES_FILE and writes, for each: (a) call 1 options, (b) the engine's picks, (c) call 2's translation. */
    @Test
    fun replayMessagesFromFile() = runBlocking {
        val inFile = System.getenv("FLOW_MESSAGES_FILE")?.takeIf { it.isNotBlank() }
        val outFile = System.getenv("FLOW_OUT_FILE")?.takeIf { it.isNotBlank() }
        Assume.assumeTrue("FLOW_MESSAGES_FILE / FLOW_OUT_FILE / DEEPSEEK_API_KEY not set", key != null && inFile != null && outFile != null)
        val messages = Json.parseToJsonElement(java.io.File(inFile!!).readText()).jsonArray.map { it.jsonPrimitive.content }
        val r = rig(key!!, "s1", "New scenario")
        val out = StringBuilder()
        try {
            for ((i, m) in messages.withIndex()) {
                val callsBefore = r.llm.calls.size
                val dirsBefore = r.directives.size
                val shown = r.say(m)
                val st = r.controller.state.value
                val turnCalls = r.llm.calls.drop(callsBefore)
                out.append("===== MESSAGE ${i + 1} (${m.length} chars) =====\n")
                out.append("error=${st.error} support=${st.supportVisible} trajectory=${st.trajectory?.let { "trust ${it.trustScore.toInt()} tension ${it.tensionScore.toInt()} | ${it.band}" }} calls=${turnCalls.map { it.purpose }}\n\n")

                out.append("(a) CALL 1 extracted options\n")
                val sketch = turnCalls.firstOrNull { it.purpose == "TURN" }?.let { SketchParser.parse(it.text) }
                if (sketch == null) out.append("  (no parseable extraction)\n") else {
                    sketch.options.groupBy { it.owner }.forEach { (who, opts) -> out.append("  $who: ${opts.joinToString(" | ") { "${it.label} <${it.posture}>" }}\n") }
                    out.append("  archetype=${sketch.archetype} trust_delta=${sketch.trustDelta} tension_delta=${sketch.tensionDelta} backstory_only=${sketch.backstoryOnly} support=${sketch.support}\n")
                }

                out.append("\n(b) ENGINE directive\n")
                val d = r.directives.drop(dirsBefore).lastOrNull()?.let { Json.parseToJsonElement(it).jsonObject }
                if (d == null) out.append("  (no directive: the engine had nothing to rank yet)\n") else {
                    fun str(k: String) = (d[k] as? JsonPrimitive)?.contentOrNull
                    out.append("  RECOMMENDED: ${str("recommended_move")}   (why: ${str("recommended_why")})\n")
                    out.append("  supporting: ${d["supporting_frameworks"]?.jsonArray?.joinToString { it.jsonPrimitive.content }}\n")
                    out.append("  SAFE FALLBACK: ${str("safe_fallback_move")}\n  COMPLEMENTARY: ${str("complementary_move")}   influencers: ${d["influencers_in_play"]}\n")
                    d["blocked_or_dominated_moves"]?.jsonArray?.forEach { b ->
                        val o = b.jsonObject
                        out.append("  DOMINATED/BLOCKED: ${o["move"]?.jsonPrimitive?.content}   [${o["reason"]?.jsonPrimitive?.content}]   triggers: ${(o["triggers"] as? JsonPrimitive)?.contentOrNull}\n")
                    }
                    out.append("  dominant dynamic: ${d["dominant_dynamic"]?.jsonArray?.joinToString(" // ") { it.jsonPrimitive.content }}\n")
                    out.append("  stress: ${str("stress_verdict")} ${str("stress_flip") ?: ""}\n")
                    out.append("  move_to_implement: ${str("move_to_implement")}\n")
                }

                out.append("\n(c) CALL 2 translation\n")
                val reply = turnCalls.lastOrNull { it.purpose == "COACH" }?.let { CoachReply.parse(it.text) }
                if (reply == null) out.append("  (no usable interpret reply; the app showed its own engine text)\n") else {
                    val want = d?.get("move_to_implement")?.jsonPrimitive?.contentOrNull
                    out.append("  echoed engine_move: ${reply.engineMove}   matches engine pick: ${reply.implements(want)}\n")
                    out.append("  clear_win_move: ${reply.clearWinMove}\n  exact_words: ${reply.exactWords}\n")
                }
                out.append("\n--- what the user saw (${shown.length} chars) ---\n$shown\n\n")
                java.io.File(outFile!!).writeText(out.toString())
            }
        } finally { DebugLog.sink = null; r.scope.cancel(); r.db.close() }
    }
}
