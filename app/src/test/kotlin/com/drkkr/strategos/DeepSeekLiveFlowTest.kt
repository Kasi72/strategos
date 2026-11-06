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

    /**
     * pm6.1, rule 20: their_vulnerable describes the counterpart themself, not the people they look after. "His parents need him
     * nearby" must leave their_vulnerable at none. Writes the last Call 1 output to EXTRACTION_FIXTURE_OUT when set (to record the
     * offline fixture `human/spouseParentsNearby.json`).
     */
    @Test
    fun spouseWhoseParentsNeedHim_isNotReadAsVulnerable() = runBlocking {
        Assume.assumeTrue("DEEPSEEK_API_KEY not set; live flow skipped", key != null)
        val r = rig(key!!, "move", "The move")
        try {
            r.say("My husband Ravi wants to move to his hometown and I don't. We've argued about it for weeks.")
            r.say("He says his parents need him nearby. I think that's an excuse. What's my strongest move?")
            val turns = r.llm.calls.filter { it.purpose == "TURN" }
            assertTrue("two extraction calls", turns.size >= 2)
            turns.forEach { t ->
                val v = SketchParser.parse(t.text)?.theirVulnerable
                assertTrue("their_vulnerable=$v in:\n${t.text}", v == null || v == "none")
            }
            val saved = r.gateway.activeScenario()?.humanContext?.theirVulnerable
            assertTrue("saved their_vulnerable=$saved", saved == null || saved == "none")
            System.getenv("EXTRACTION_FIXTURE_OUT")?.takeIf { it.isNotBlank() }?.let { java.io.File(it).writeText(turns.last().text) }
            println("FLOW|PASS|their_vulnerable=$saved")
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
        // Power Mode (PM4.4): FLOW_POWER_MODE=1 turns it on for this conversation before the first message.
        val powerMode = System.getenv("FLOW_POWER_MODE") == "1"
        if (powerMode) SettingsRepository(r.db).setPowerMode(r.controller.state.value.conversationId!!, true)
        val out = StringBuilder(if (powerMode) "POWER MODE: on\n\n" else "")
        try {
            var powerReadsBefore = 0
            for ((i, m) in messages.withIndex()) {
                val callsBefore = r.llm.calls.size
                val dirsBefore = r.directives.size
                val savedBefore = r.gateway.activeScenario()
                val shown = r.say(m)
                val st = r.controller.state.value
                val turnCalls = r.llm.calls.drop(callsBefore)
                out.append("===== MESSAGE ${i + 1} (${m.length} chars) =====\n")
                out.append("CALLS: " + turnCalls.joinToString(" | ") { c -> "${c.purpose} in=${c.inTokens} out=${c.outTokens}/${c.maxOut} chars=${c.text.length} parses=${if (c.purpose == "TURN") SketchParser.tryParse(c.text).let { t -> if (t.isSuccess) "true" else "false WHY: " + t.exceptionOrNull()?.message?.take(240) } else "-"}" } + "\n")
                out.append("error=${st.error} support=${st.supportVisible} trajectory=${st.trajectory?.let { "trust ${it.trustScore.toInt()} tension ${it.tensionScore.toInt()} | ${it.band}" }} calls=${turnCalls.map { it.purpose }}\n\n")

                // Optional FLOW_RAW_TURN_DIR: Call 1's raw reply per message, to record as a test fixture (ID1.2, TC_ID_RUB_11).
                System.getenv("FLOW_RAW_TURN_DIR")?.takeIf { it.isNotBlank() }?.let { dir ->
                    turnCalls.filter { it.purpose == "TURN" }.forEachIndexed { k, c ->
                        java.io.File(dir, if (k == 0) "turn-${i + 1}.json" else "turn-${i + 1}-repair.json").apply { parentFile?.mkdirs() }.writeText(c.text)
                    }
                }
                out.append("(a) CALL 1 extracted options\n")
                val sketch = turnCalls.firstOrNull { it.purpose == "TURN" }?.let { SketchParser.parse(it.text) }
                if (sketch == null) out.append("  (no parseable extraction)\n") else {
                    sketch.options.groupBy { it.owner }.forEach { (who, opts) -> out.append("  $who: ${opts.joinToString(" | ") { "${it.label} <${it.posture}>" }}\n") }
                    out.append("  archetype=${sketch.archetype} trust_delta=${sketch.trustDelta} tension_delta=${sketch.tensionDelta} backstory_only=${sketch.backstoryOnly} support=${sketch.support}\n")
                    // Intent Dispatch: the turn's intent, and Call 1's readiness flag (logged only since ID2.4; Kotlin decides)
                    out.append("  [ID2] intent=${sketch.intentKind.word} detail=${sketch.intentDetail} what_if=${sketch.whatIf != null} call1_enough_to_recommend=${sketch.enoughToRecommend} (not gating)\n")
                    // Human Layer fields, raw (what the model wrote) and then as Kotlin reads them (closed vocabularies only).
                    out.append("  HUMAN raw: relationship=${sketch.relationship} power=${sketch.power} status_quo=${sketch.statusQuo} status_quo_for_them=${sketch.statusQuoForThem} deadline=${sketch.deadline} their_vulnerable=${sketch.theirVulnerable} your_state=${sketch.yourState} their_state=${sketch.theirState} their_tactics=${sketch.theirTactics} last_attempt_tags=${sketch.lastAttemptTags} steps=${sketch.stepsForThemToSayYes} goal_is=${sketch.goalIs}\n")
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
                    // Intent Dispatch ID1 (CP-I1): the label checks on the saved labels, and how many payoffs were written as numbers.
                    r.gateway.activeScenario()?.let { sc ->
                        val issues = com.drkkr.strategos.domain.game.LabelChecks.check(sc).map { i -> when (i) {
                            is com.drkkr.strategos.domain.game.LabelIssue.LabelDominance -> "LABEL_DOMINANCE(${sc.actions.firstOrNull { it.id == i.actionId }?.label})"
                            is com.drkkr.strategos.domain.game.LabelIssue.RefusalBeatsAcceptance -> "REFUSAL_BEATS_ACCEPTANCE(${sc.actions.firstOrNull { it.id == i.actionId }?.label}, ${i.dim})"
                        } }
                        val numeric = sc.payoffs.count { com.drkkr.strategos.domain.game.RubricPayoffs.AI_SCORED_NUMERIC in it.provenanceNote }
                        out.append("  [ID1] label_issues=${issues.ifEmpty { listOf("none") }} numeric_payoffs=$numeric/${sc.payoffs.size} confidence=${str("confidence_level")}\n")
                        // ID1.6: the fallback inputs, and whether the fixed line (the shift changed the pick) is in the why.
                        val why = str("recommended_why").orEmpty()
                        val shiftLine = with(com.drkkr.strategos.domain.human.FallbackShift) { listOf(STRONG_LINE, WEAK_LINE, THEIR_STRONG_LINE, THEIR_WEAK_LINE).filter { it in why } }.ifEmpty { false }
                        out.append("  [ID2] changed_since_last=${d["changed_since_last"] ?: "-"} next_question=${str("next_question") ?: "-"}\n")
                        out.append("  [ID1.6] your_alternative=${sc.humanContext?.yourAlternative} their_alternative=${sc.humanContext?.theirAlternative} pending=${sc.humanContext?.pending?.filterKeys { it.endsWith("Alternative") }} batna_written=${sc.negotiationContext?.batna?.isNotBlank() == true}/${sc.negotiationContext?.counterpartBatna?.isNotBlank() == true} sides_without_frame=${com.drkkr.strategos.domain.human.FallbackShift.yours(sc, null)}/${com.drkkr.strategos.domain.human.FallbackShift.theirs(sc)} walk_away_credible=${(d["deal"] as? kotlinx.serialization.json.JsonObject)?.get("walk_away_credible")} fallback_shift_changed_pick=$shiftLine\n")
                    }
                    out.append("  DELIVERY (what the coach was given): ${d["delivery"] ?: "none"}\n")
                    r.gateway.activeScenario()?.let { sc ->
                        val hc = sc.humanContext
                        val owner = com.drkkr.strategos.domain.agent.IntakeChecklist.self(sc)
                        val rec = owner?.let { o -> sc.actionsFor(o.id).firstOrNull { it.label.equals(str("move_to_implement"), ignoreCase = true) } }
                        if (hc != null && rec != null) {
                            out.append("  PLANNER TABLE for \"${rec.label}\" (tags ${com.drkkr.strategos.domain.human.DeliveryTags.of(rec)}, domain ${com.drkkr.strategos.domain.human.RelationshipDomains.of(hc.relationship, null)}):\n")
                            out.append("    tactic | gate | precondition | delta p\n")
                            val raw = com.drkkr.strategos.domain.agent.EngineDirectiveBuilder.analysisView(sc, null, null)
                            com.drkkr.strategos.domain.human.DeliveryPlanner.diagnose(raw, hc, rec, false).forEach { row ->
                                out.append("    ${row.id} | ${if (row.gatePass) "pass" else "FAIL"} | ${if (row.preconditionPass) "pass" else "no"} | ${when { !(row.gatePass && row.preconditionPass) -> "-"; row.preconditionOnly -> "precondition-only"; else -> "%.3f".format(row.deltaP ?: 0.0) }}\n")
                            }
                        }
                    }
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
                // ID3.6: (i) INTENT: the intent read, the handler that ran, and each new block's JSON (from the directive the coach got)
                out.append("\n(i) INTENT\n")
                out.append("  intent=${sketch?.intentKind?.word ?: "-"} handler=${sketch?.intentKind?.takeIf { it != com.drkkr.strategos.domain.intent.Intent.STANDARD }?.word ?: "none"}\n")
                val turnDirective = r.directives.drop(dirsBefore).lastOrNull()?.let { Json.parseToJsonElement(it).jsonObject }
                // I-D3 check: nothing from the hypothetical is saved (its own options/people never appear in the saved scenario)
                val savedAfter = r.gateway.activeScenario()
                val hypo = sketch?.whatIf
                val leaked = if (hypo == null || savedAfter == null) emptyList() else
                    hypo.options.map { it.label }.filter { l -> savedAfter.actions.any { it.label.equals(l, true) } && savedBefore?.actions?.none { it.label.equals(l, true) } != false } +
                    hypo.people.map { it.name }.filter { n -> savedAfter.stakeholders.any { it.name.equals(n, true) } && savedBefore?.stakeholders?.none { it.name.equals(n, true) } != false }
                out.append("  saved_changed=${savedBefore != savedAfter} what_if_leaked=${leaked.ifEmpty { "none" }}\n")
                for (k in listOf("changed_since_last", "next_question", "what_if", "compare", "top_options", "their_reaction", "confidence"))
                    out.append("  $k=${turnDirective?.get(k) ?: "-"}\n")
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
                    // Dominance audit: coverage, which cells were proxy-filled (0.0), round-1 vs iterated dominance, and overlap with the directive's moves.
                    (com.drkkr.strategos.domain.game.GameModelCompiler.compile(saved) as? com.drkkr.strategos.domain.game.CompileResult.Success)?.ir?.let { ir ->
                        val label = { id: String -> saved.actions.firstOrNull { it.id == id }?.label ?: id }
                        val who = { id: String -> saved.stakeholders.firstOrNull { it.id == id }?.name ?: id }
                        val proxyCells = ir.utility.sumOf { u -> u.count { it == 0.0 } }
                        out.append("  DOMINANCE AUDIT: coveragePct=${ir.coveragePct} cellsAt0.0=$proxyCells of ${ir.utility.sumOf { it.size }} players=${ir.playerCount} strategies=${ir.strategyCounts}\n")
                        if (ir.playerCount == 2) {
                            for (p in 0..1) {
                                val q = 1 - p
                                fun pay(a: Int, b: Int) = ir.utility[p][ir.profileIndex(if (p == 0) intArrayOf(a, b) else intArrayOf(b, a))]
                                for (a in 0 until ir.strategyCounts[p]) out.append("    ${who(ir.playerIds[p])} | ${label(ir.actionIds[p][a]).take(60)} | " +
                                    (0 until ir.strategyCounts[q]).joinToString(" ") { b -> "%.2f".format(pay(a, b)) } + "\n")
                                val round1 = (0 until ir.strategyCounts[p]).filter { a -> (0 until ir.strategyCounts[p]).any { b -> b != a && (0 until ir.strategyCounts[q]).all { c -> pay(b, c) > pay(a, c) } } }
                                out.append("    round-1 dominated for ${who(ir.playerIds[p])}: ${round1.map { label(ir.actionIds[p][it]).take(50) }}\n")
                            }
                        }
                        val pr = com.drkkr.strategos.domain.game.IteratedDominancePruner.prune(ir)
                        out.append("  iterated (rounds=${pr.rounds}): ${pr.eliminated.filterValues { it.isNotEmpty() }.map { (pid, ids) -> "${who(pid)}: ${ids.map { label(it).take(50) }}" }}\n")
                        val moves = listOfNotNull(d?.let { (it["recommended_move"] as? JsonPrimitive)?.contentOrNull }, d?.let { (it["safe_fallback_move"] as? JsonPrimitive)?.contentOrNull }, d?.let { (it["complementary_move"] as? JsonPrimitive)?.contentOrNull }, d?.let { (it["move_to_implement"] as? JsonPrimitive)?.contentOrNull })
                        val listed = d?.get("dominated_strategies")?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
                        out.append("  OVERLAP directive move listed as dominated: ${moves.distinct().filter { m -> listed.any { it.contains("$m (dominated)") } }.ifEmpty { listOf("none") }}\n")
                    }
                }

                // Negotiation Numbers: the frame this turn's engine built (same inputs as OneShotTurn), what it ruled out, and the reply's figures.
                out.append("\n(n) NEGOTIATION\n")
                if (saved == null) out.append("  (no saved scenario)\n") else {
                    val frame = com.drkkr.strategos.domain.negotiation.DealFrame.build(saved.negotiationContext, saved.humanContext, m, recent = messages.take(i + 1).takeLast(6).joinToString("\n"))
                    if (frame == null) out.append("  frame: none (no context, no readable walk-away, or no direction)\n") else {
                        fun f(a: com.drkkr.strategos.domain.negotiation.Amount?) = a?.let { com.drkkr.strategos.domain.negotiation.DealNumbers.format(it) } ?: "-"
                        out.append("  frame: direction=${frame.direction} walkAway=${f(frame.walkAway)} target=${f(frame.target)} fallback=${f(frame.fallback)} theirLimit=${f(frame.theirLimit)}\n")
                        out.append("  zone=${frame.zone?.let { "[${f(it.low)}, ${f(it.high)}]" } ?: "-"} noZone=${frame.noZone} landing=${f(frame.landing)} openAt=${f(frame.openAt)} acceptIf=${f(frame.acceptIf)} credible=${frame.walkAwayCredible} notes=${frame.notes}\n")
                        val owner = com.drkkr.strategos.domain.agent.IntakeChecklist.self(saved)
                        val excluded = owner?.let { o -> saved.actionsFor(o.id).filter { com.drkkr.strategos.domain.negotiation.NegotiationView.crosses(it.label, frame) }.map { it.label } }.orEmpty()
                        out.append("  excluded by the walk-away: ${excluded.ifEmpty { listOf("none") }}\n")
                    }
                    out.append("  deal block sent to the coach: ${d?.get("deal") ?: "none"}\n")
                    val figs = com.drkkr.strategos.domain.negotiation.DealNumbers.figures(shown).map { com.drkkr.strategos.domain.negotiation.DealNumbers.format(it) }
                    out.append("  figures in what the user saw: ${figs.ifEmpty { listOf("none") }}\n")
                    // The same check the app runs: the coach's advice fields only (what_happened and the warnings may name their offer).
                    val coachReply = turnCalls.lastOrNull { it.purpose == "COACH" }?.let { CoachReply.parse(it.text) }
                    out.append("  walk-away/fallback check (the app's runtime check, on the coach's advice fields): ${when {
                        frame == null -> "no frame"
                        coachReply == null -> "no coach reply"
                        com.drkkr.strategos.domain.negotiation.DealCheck.usable(coachReply, frame) -> "clean"
                        else -> "FLAGGED (the reply would not be shown)"
                    }}\n")
                }

                // Power Mode: the decline note sent to the coach, and the Power Read card saved this turn (Kotlin-built).
                out.append("\n(p) POWER MODE\n")
                out.append("  power_mode in directive: ${d?.get("power_mode") ?: "-"} | decline_note: ${(d?.get("decline_note") as? JsonPrimitive)?.contentOrNull ?: "none"}\n")
                out.append("  delivery tactics: ${d?.get("delivery")?.jsonObject?.get("tactics") ?: "none"}\n")
                val cidNow = r.controller.state.value.conversationId!!
                val reads = ConversationRepository(r.db).messages(cidNow).filter { it.uiKind == com.drkkr.strategos.data.repo.UiKind.POWER_READ.name }
                val read = reads.lastOrNull()?.takeIf { reads.size > powerReadsBefore }?.payloadJson
                out.append("  POWER READ: ${read ?: "none this turn"}\n")
                powerReadsBefore = reads.size

                out.append("\n(c) CALL 2 translation\n")
                val reply = turnCalls.lastOrNull { it.purpose == "COACH" }?.let { CoachReply.parse(it.text) }
                if (reply == null) out.append("  (no usable interpret reply; the app showed its own engine text)\n") else {
                    val want = d?.get("move_to_implement")?.jsonPrimitive?.contentOrNull
                    out.append("  echoed engine_move: ${reply.engineMove}   matches engine pick: ${reply.implements(want)}\n")
                    out.append("  clear_win_move: ${reply.clearWinMove}\n  exact_words: ${reply.exactWords}\n")
                }
                out.append("\n--- what the user saw (${shown.length} chars) ---\n$shown\n")
                out.append("BANNED-WORD CHECK on what the user saw: ${if (com.drkkr.strategos.domain.human.BannedWords.containedIn(shown)) "FAIL - names a law, book, author or tactic" else "clean"}\n\n")
                java.io.File(outFile!!).writeText(out.toString())
            }
        } finally { DebugLog.sink = null; r.scope.cancel(); r.db.close() }
    }
}
