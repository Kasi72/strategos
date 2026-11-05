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
    class Call(val purpose: String, val text: String, val maxOut: Int = 0, val outTokens: Int = 0, val inTokens: Int = 0)
    val calls = mutableListOf<Call>()
    override suspend fun create(req: LlmRequest): LlmResult =
        inner.create(req).also { r -> if (r is LlmResult.Ok) calls += Call(req.purpose, r.output.assistantText(), req.maxOutputTokens, r.usage.output, r.usage.input) }
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

    private suspend fun rig(key: String, scenarioId: String, title: String, negotiation: com.drkkr.strategos.domain.model.NegotiationContext? = null): Rig {
        val db = DbFactory.inMemoryForTests(ApplicationProvider.getApplicationContext())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val scenarios = ScenarioRepository(db, null).also { it.load() }
        val saved = (scenarios.save(Scenario(id = scenarioId, title = title, negotiationContext = negotiation)) as SaveResult.Success).saved
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
        // Optional FLOW_NEGOTIATION_FILE: JSON object with NegotiationContext fields, seeded as if the prep wizard was filled in.
        val nc = System.getenv("FLOW_NEGOTIATION_FILE")?.takeIf { it.isNotBlank() }?.let {
            Json { ignoreUnknownKeys = true }.decodeFromString(com.drkkr.strategos.domain.model.NegotiationContext.serializer(), java.io.File(it).readText())
        }
        val r = rig(key!!, "s1", "New scenario", nc)
        val out = StringBuilder()
        try {
            for ((i, m) in messages.withIndex()) {
                val callsBefore = r.llm.calls.size
                val dirsBefore = r.directives.size
                val shown = r.say(m)
                val st = r.controller.state.value
                val turnCalls = r.llm.calls.drop(callsBefore)
                out.append("===== MESSAGE ${i + 1} (${m.length} chars) =====\n")
                out.append("CALLS: " + turnCalls.joinToString(" | ") { c -> "${c.purpose} in=${c.inTokens} out=${c.outTokens}/${c.maxOut} chars=${c.text.length} parses=${if (c.purpose == "TURN") SketchParser.tryParse(c.text).isSuccess else "-"}" } + "\n")
                out.append("error=${st.error} support=${st.supportVisible} trajectory=${st.trajectory?.let { "trust ${it.trustScore.toInt()} tension ${it.tensionScore.toInt()} | ${it.band}" }} calls=${turnCalls.map { it.purpose }}\n\n")

                out.append("(a) CALL 1 extracted options\n")
                val sketch = turnCalls.firstOrNull { it.purpose == "TURN" }?.let { SketchParser.parse(it.text) }
                if (sketch == null) out.append("  (no parseable extraction)\n") else {
                    sketch.options.groupBy { it.owner }.forEach { (who, opts) -> out.append("  $who: ${opts.joinToString(" | ") { "${it.label} <${it.posture}>" }}\n") }
                    out.append("  archetype=${sketch.archetype} trust_delta=${sketch.trustDelta} tension_delta=${sketch.tensionDelta} backstory_only=${sketch.backstoryOnly} support=${sketch.support}\n")
                    // Human Layer fields, raw (what the model wrote) and then as Kotlin reads them (closed vocabularies only).
                    out.append("  HUMAN raw: relationship=${sketch.relationship} power=${sketch.power} status_quo=${sketch.statusQuo} status_quo_for_them=${sketch.statusQuoForThem} deadline=${sketch.deadline} their_vulnerable=${sketch.theirVulnerable} your_state=${sketch.yourState} their_state=${sketch.theirState} their_tactics=${sketch.theirTactics} steps=${sketch.stepsForThemToSayYes} goal_is=${sketch.goalIs}\n")
                    out.append("  HUMAN option_tags raw: ${sketch.optionTags}\n")
                    out.append("  HUMAN read: ${com.drkkr.strategos.domain.agent.SketchHuman.context(sketch)}\n  HUMAN tags read: ${com.drkkr.strategos.domain.agent.SketchHuman.optionTags(sketch)}\n")
                    r.gateway.activeScenario()?.let { sc ->
                        out.append("  HUMAN SAVED: ${sc.humanContext}\n  HUMAN SAVED can_exit: ${sc.stakeholders.filter { it.gameRole == com.drkkr.strategos.domain.model.GameRole.PLAYER }.joinToString { "${it.name}=${it.canExit}/auth${it.authority}" }}\n")
                    }
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
                    out.append("  known_stakeholder_facts: ${d["known_stakeholder_facts"]?.jsonArray?.joinToString(" | ") { it.jsonPrimitive.content }}\n")
                    out.append("  bayesian_learning_note: ${str("bayesian_learning_note")}\n")
                    // R5 fields: absent keys are printed as "-" so a run shows which engine features actually fired.
                    out.append("  [R5] game_theoretic_pattern=${str("game_theoretic_pattern") ?: "-"} | dynamic_archetype=${str("dynamic_archetype") ?: "-"} | confidence=${str("confidence_level") ?: "-"} | stakes=${str("stakes_tier") ?: "-"}\n")
                    out.append("  [R5] dominated_strategies=${d["dominated_strategies"] ?: "-"} | extensive_form_path=${d["extensive_form_path"] ?: "-"} | opponent_rationality=${str("opponent_rationality") ?: "-"}\n")
                    out.append("  [R5] shapley_values=${d["shapley_values"] ?: "-"} | mechanism_hint=${str("mechanism_hint") ?: "-"} | info_asymmetry=${str("info_asymmetry") ?: "-"}\n")
                    out.append("  [R5] red_team_challenge=${str("red_team_challenge") ?: "-"} | clarification=${str("clarification_question") ?: "-"}\n")
                }

                // Re-run the game-model stage on the scenario as saved after this turn, to show why R5 fields are or are not produced.
                out.append("\n(b2) GAME MODEL (compile -> IEDS -> solve)\n")
                val saved = r.gateway.activeScenario()
                if (saved == null) out.append("  (no saved scenario)\n") else {
                    val ctx = com.drkkr.strategos.domain.game.GameModelService.analyzeWithContext(saved)
                    out.append("  stakeholders=${saved.stakeholders.size} actions=${saved.actions.size}\n")
                    out.append("  negotiationContext=${saved.negotiationContext ?: "-"}\n")
                    out.append("  result=${ctx.result::class.simpleName} integrity=${ctx.result.integrity}\n")
                    (ctx.result as? com.drkkr.strategos.domain.game.GameAnalysisResult.Failed)?.let { out.append("  reason=${it.reason.take(400)}\n") }
                    (ctx.result as? com.drkkr.strategos.domain.game.GameAnalysisResult.Solved)?.let { out.append("  archetype=${it.ir.archetype}\n") }
                    out.append("  coalition=${ctx.coalitionResult?.values ?: "-"} mechanism=${ctx.mechanismHint?.type ?: "-"}\n")
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
