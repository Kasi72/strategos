package com.drkkr.strategos.agent

import com.drkkr.strategos.agent.tools.Args
import com.drkkr.strategos.data.repo.CertificateRepository
import com.drkkr.strategos.data.repo.ChangeSetRepository
import com.drkkr.strategos.data.repo.ConversationRepository
import com.drkkr.strategos.data.repo.MessageRole
import com.drkkr.strategos.data.repo.SettingsRepository
import com.drkkr.strategos.data.repo.TrajectoryRepository
import com.drkkr.strategos.data.repo.UiKind
import com.drkkr.strategos.domain.agent.ApplyResult
import com.drkkr.strategos.domain.agent.BayesianUpdater
import com.drkkr.strategos.domain.agent.CoachFacts
import com.drkkr.strategos.domain.agent.ArgonPrompts
import com.drkkr.strategos.domain.agent.ChangeSetValidator
import com.drkkr.strategos.domain.agent.CoachGuideSketch
import com.drkkr.strategos.domain.agent.CoachReply
import com.drkkr.strategos.domain.agent.GuidanceCodec
import com.drkkr.strategos.domain.agent.EngineDirective
import com.drkkr.strategos.domain.files.FileBlock
import com.drkkr.strategos.domain.agent.EngineDirectiveBuilder
import com.drkkr.strategos.domain.agent.InferredStressTest
import com.drkkr.strategos.domain.agent.LiveDigest
import com.drkkr.strategos.domain.agent.ModelSketch
import com.drkkr.strategos.domain.agent.OpNormalizer
import com.drkkr.strategos.domain.agent.PlainResult
import com.drkkr.strategos.domain.agent.ReportDigest
import com.drkkr.strategos.domain.agent.SketchGaps
import com.drkkr.strategos.domain.agent.SketchConverter
import com.drkkr.strategos.domain.agent.SketchParser
import com.drkkr.strategos.domain.framework.ModuleRunStatus
import com.drkkr.strategos.domain.agent.DomainContextInjector
import com.drkkr.strategos.domain.agent.ArcState
import com.drkkr.strategos.domain.agent.ArcStateMachine
import com.drkkr.strategos.domain.agent.RetrospectiveEngine
import com.drkkr.strategos.domain.game.OpponentModelBuilder
import com.drkkr.strategos.data.repo.OutcomeRepository
import com.drkkr.strategos.data.repo.OpponentProfileRepository
import com.drkkr.strategos.domain.agent.PreRouter
import com.drkkr.strategos.domain.agent.SafetyKeywordScreen
import com.drkkr.strategos.domain.agent.RouterDecision
import com.drkkr.strategos.domain.game.CompileResult
import com.drkkr.strategos.domain.game.ExtractionValidator
import com.drkkr.strategos.domain.game.GameModelCompiler
import com.drkkr.strategos.llm.LlmClient
import com.drkkr.strategos.llm.DebugLog
import com.drkkr.strategos.llm.LlmErrors
import com.drkkr.strategos.llm.LlmRequest
import com.drkkr.strategos.llm.LlmResult
import com.drkkr.strategos.llm.ModelListResult
import com.drkkr.strategos.llm.assistantText
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * SIMPLE PROFILE: one model call per message. Sketch → ops → save → engine → plain result.
 * The same steps run for the first message and for every follow-up; the saved scenario is the memory.
 */
class OneShotTurn(
    private val llm: LlmClient,
    private val conversations: ConversationRepository,
    private val changeSets: ChangeSetRepository,
    private val settings: SettingsRepository,
    private val gateway: ScenarioGateway,
    /** Applies a saved PENDING change set through the normal path (commit, undo stack, history). */
    private val autoApply: suspend (changeSetId: String) -> ApplyResult?,
    private val onSupport: () -> Unit = {},
    private val afterTurn: suspend (conversationId: String) -> Unit = {},
    /** COMPANION REPLY: after the engine runs, one more model call writes a warm answer from the engine's result. */
    private val coach: Boolean = false,
    /** Argon: prefix-cached request, JSON mode, coach guide in the answer, and one trajectory point per turn. Null keeps the v2.0 flow. */
    private val argon: ArgonTurn? = null,
    /** R4: writes a GameModelEntity + DecisionCertificateEntity when the solver reaches VERIFIED integrity. */
    private val certificates: CertificateRepository? = null,
    /** Phase 5a: flywheel outcome records for retrospective calibration. */
    private val outcomes: OutcomeRepository? = null,
    /** Phase 5b: cross-scenario opponent profiles, keyed by name. */
    private val opponentProfiles: OpponentProfileRepository? = null,
    /** ID4.2: the user's other projects as the matcher sees them (loaded by the controller from the repositories). */
    private val otherProjects: suspend (currentScenarioId: String) -> List<com.drkkr.strategos.domain.intent.ProjectFeatures> = { emptyList() }
) {
    class ArgonTurn(val assembler: ContextAssembler, val trajectory: TrajectoryRepository, val journal: com.drkkr.strategos.data.repo.JournalRepository? = null)

    suspend fun run(
        conversationId: String, scenarioId: String, trigger: TurnTrigger, onActivity: (String) -> Unit
    ): TurnResult {
        val turnId = UUID.randomUUID().toString()
        if (trigger is TurnTrigger.Event) return TurnResult.Done           // no review step in this flow, so no app events
        if (trigger is TurnTrigger.User) conversations.append(conversationId, turnId, MessageRole.USER, trigger.text)
        return try {
            withTimeoutOrNull(AgentConfig.MAX_TURN_MS) { turn(conversationId, scenarioId, turnId, onActivity) }
                ?: fail(conversationId, turnId, AgentOrchestrator.TIMEOUT_TEXT, retryable = true)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { conversations.append(conversationId, turnId, MessageRole.EVENT, AgentOrchestrator.STOPPED_TEXT) }
            throw e
        }
    }

    private class Applied(val result: ApplyResult, val accepted: List<com.drkkr.strategos.domain.agent.ChangeOp>)

    private suspend fun turn(cid: String, sid: String, turnId: String, onActivity: (String) -> Unit): TurnResult {
        val loaded = gateway.activeScenario() ?: return fail(cid, turnId, "Save the scenario first.", retryable = false)
        val rawTexts = conversations.messages(cid).filter { it.role == MessageRole.USER.name }.map { it.text }
        // A shared file rides inside the message as a block. Extraction (call 1) reads the whole message through the assembler;
        // everything else in this turn works from the person's own words plus one line naming the file, and the coach gets the
        // file itself from background(), so a long file never crowds out what they actually said.
        val fullText = rawTexts.lastOrNull() ?: return TurnResult.Done
        val hasFile = FileBlock.present(fullText)
        val userTexts = rawTexts.map(FileBlock::note)
        val text = userTexts.last()
        if (hasFile) showTimeline(cid, turnId, fullText)
        // Phase F.5: read before this turn's saveLastReport overwrites it, so the directive can name what it told you last time.
        val previousReportJson = conversations.get(cid)?.lastReportDigestJson

        // Quick mode: skip extraction (Call 1) entirely when the model is already populated.
        if (settings.quickMode() && loaded.actions.isNotEmpty() && argon != null) {
            onActivity("Running the analysis on your device…")
            val pr = gateway.analyzeAndPublish()
            val after = gateway.activeScenario() ?: loaded
            val body = if (pr?.report != null)
                engineGroundedReply(cid, turnId, sid, after, pr, emptyList(), text, onActivity, previousReportJson, domain = null, quickMode = true)
            else
                pr?.let(PlainResult::needsMore) ?: COULD_NOT_READ
            conversations.append(cid, turnId, MessageRole.ASSISTANT, body)
            afterTurn(cid)
            return TurnResult.Done
        }

        // Pre-routing (Argon path only): ~80-token call that classifies the message BEFORE the full
        // 3,000-token extraction. Followup questions and too-thin inputs are caught here and routed
        // directly, skipping Call 1 and saving ~90 % of that call's cost on simple turns.
        // Safety first (I-H5): a message the on-device screen flags never takes a shortcut; it goes through full extraction
        // so the support route below sees it. "thin" is only for an empty thread: mid-thread, a short "ok" or a doubt is
        // companion mode's to handle (it keeps the move), not a "what's the situation?" restart.
        // A file is always new material: it goes to full extraction, never the follow-up or "too thin" shortcut.
        if (argon != null && !hasFile && !SafetyKeywordScreen.check(text)) {
            val priorReply = conversations.messages(cid).lastOrNull { it.role == MessageRole.ASSISTANT.name }?.text
            val decision = preRoute(cid, turnId, text)
            if (decision != null) {
                when {
                    decision.route == "followup" && !priorReply.isNullOrBlank() -> {
                        onActivity("Answering your question…")
                        // CP-N: the same deal frame as the extraction path's follow-up, so the walk-away stays masked here too.
                        val deal = com.drkkr.strategos.domain.negotiation.DealFrame.build(loaded.negotiationContext, loaded.humanContext, text, recent = userTexts.takeLast(6).joinToString("\n"))
                        answerFollowUp(cid, turnId, text, priorReply, previousReportJson, deal)?.let { answer ->
                            conversations.append(cid, turnId, MessageRole.ASSISTANT, answer)
                            afterTurn(cid)
                            return TurnResult.Done
                        }
                        // answerFollowUp returned null: fall through to full extraction.
                    }
                    // Only on an empty thread (no reply yet, nothing saved), so it can ask at most once before the first answer.
                    decision.route == "thin" && decision.clarify.isNotEmpty() && priorReply.isNullOrBlank() && loaded.actions.isEmpty() -> {
                        conversations.append(cid, turnId, MessageRole.ASSISTANT, buildClarify(decision.clarify))
                        afterTurn(cid)
                        return TurnResult.Done
                    }
                }
            }
        }

        // Task 6: read current arc state; inject hints for ACTED/OUTCOME_KNOWN into Call 1 so extraction focuses on deltas.
        val currentArcState = if (argon != null) {
            conversations.get(cid)?.arcState
                ?.let { runCatching { ArcState.valueOf(it) }.getOrDefault(ArcState.EXPLORING) }
                ?: ArcState.EXPLORING
        } else ArcState.EXPLORING

        onActivity("Reading your situation…")
        // Argon call 1 is extraction only: small, fast and nearly deterministic. The advice comes from call 2, after the engine.
        // Task 6: for ACTED/OUTCOME_KNOWN states, inject a header telling Call 1 to focus on deltas, not a full re-extraction.
        val call1Input = if (argon != null) argon.assembler.build(cid, sid).let { base ->
            when (currentArcState) {
                ArcState.ACTED         -> appendUserMessage(base, ARC_STATE_ACTED_HEADER)
                ArcState.OUTCOME_KNOWN -> appendUserMessage(base, ARC_STATE_OUTCOME_HEADER)
                else                   -> base
            }
        } else null
        val first = if (argon != null) askModel(call1Input!!, cid, turnId, jsonMode = true, maxTokens = EXTRACTION_MAX_TOKENS, temperature = EXTRACTION_TEMPERATURE)
                    else askModel(input(LiveDigest.build(loaded, forSketch = true), everythingSaid(userTexts.dropLast(1)), fullText), cid, turnId)
        val parsed = when (first) {
            is Reply.Sketch -> com.drkkr.strategos.domain.intent.PlanAheadGuard.realIntent(first.sketch)
            is Reply.Failed -> return fail(cid, turnId, first.text, first.retryable)
        }
        // Power Mode: the fixed decline line leads EVERY reply this turn when the user asked for something manipulative (CP-P2b).
        val decline = com.drkkr.strategos.domain.human.power.DeclineNotes.of(com.drkkr.strategos.domain.agent.SketchHuman.context(parsed)?.userAsksFor)
        fun declined(t: String) = decline?.let { "$it\n\n$t" } ?: t
        // Phase G.2: a message that only asks about the PREVIOUS reply ("what is that?", "why?") is answered directly
        // from that reply, never by re-running the engine into a near-duplicate answer. Routed before the trajectory and
        // Bayesian updates below, so asking a question never moves trust/tension or the learned priors.
        // Intent Dispatch: a turn that names an intent (what if, compare, …) goes to the engine, not the follow-up shortcut.
        if (argon != null && parsed.messageKind == "followup_question" && parsed.intentKind == com.drkkr.strategos.domain.intent.Intent.STANDARD) {
            val prior = conversations.messages(cid).lastOrNull { it.role == MessageRole.ASSISTANT.name }?.text
            if (!prior.isNullOrBlank()) {
                onActivity("Answering your question…")
                val deal = com.drkkr.strategos.domain.negotiation.DealFrame.build(loaded.negotiationContext, loaded.humanContext, text, recent = userTexts.takeLast(6).joinToString("\n"))
                answerFollowUp(cid, turnId, text, prior, previousReportJson, deal)?.let { answer ->
                    conversations.append(cid, turnId, MessageRole.ASSISTANT, declined(answer))
                    afterTurn(cid)
                    return TurnResult.Done
                }
                // The answer call failed or came back unusable: fall through to the normal pipeline below.
            }
        }
        // BLUEPRINT-QUESTION: §D.1 says a point after every turn; a reply with no deltas and no archetype says nothing new, so it adds none.
        // I-D3: a hypothetical turn (what if, compare, their reaction, plan ahead) on an existing model saves nothing: not the
        // scenario, not the trajectory. Its moves run on a copy (the what_if sketch's ops, or else the main sketch's).
        val hypothetical = parsed.intentKind in HYPOTHETICAL_INTENTS && loaded.actions.isNotEmpty()
        // Companion mode: thinking aloud with nothing new keeps the saved model as it is (no reworded options, no trust or tension
        // drift), so the engine gives the same move and the coach says to stick with it (coach rule 34).
        val holdCourse = argon != null && !hypothetical && holdsCourse(parsed.messageKind, parsed.intentKind, loaded.actions.isNotEmpty())
        if (holdCourse) DebugLog.d { "HOLD_COURSE thinking aloud: nothing saved this turn" }
        if (argon != null && !hypothetical && !holdCourse && (parsed.trustDelta != 0.0 || parsed.tensionDelta != 0.0 || parsed.archetype != null)) {
            argon.trajectory.recordTurn(sid, cid, parsed.trustDelta, parsed.tensionDelta, parsed.archetype, parsed.backstoryOnly)
        }
        // Phase F.6: the learned priors update here, before the ops are built, so this turn's SketchConverter.toOps and
        // the engine that reads the saved scenario afterward both see the same just-updated numbers in one commit.
        val scenario = if (argon != null && !holdCourse) BayesianUpdater.applyToScenario(loaded, parsed.userLastMovePosture, parsed.counterpartObservedReaction) else loaded

        // Task 6: advance arc state from the user's message and persist if changed. A hypothetical ("what if I told him…")
        // or thinking aloud (companion mode saves nothing) is not something the user did, so neither moves the arc.
        if (argon != null && !hypothetical && !holdCourse) {
            val next = ArcStateMachine.advance(currentArcState, text)
            if (next != currentArcState) conversations.saveArcState(cid, next.name)
            if (next == ArcState.OUTCOME_KNOWN) {
                val opponentName = scenario.players().drop(1).firstOrNull()?.name
                val baseModel = OpponentModelBuilder.fromScenario(scenario)
                    ?: opponentName?.let { n -> opponentProfiles?.get(n) }
                if (baseModel != null && opponentName != null) {
                    val recentOutcomes = outcomes?.recentForScenario(sid) ?: emptyList()
                    val calibration = RetrospectiveEngine.run(baseModel, recentOutcomes)
                    if (calibration != null) {
                        opponentProfiles?.upsert(opponentName, calibration.updatedModel, calibration.sampleSize)
                        conversations.append(cid, turnId, MessageRole.EVENT, calibration.summary)
                    }
                }
            }
        }

        val learned = if (argon != null) tacticLearningFor(cid, scenario, parsed.counterpartObservedReaction) else null
        val converted = SketchConverter.toOps(parsed, scenario, learned = learned)
        val hypotheticalOps = if (!hypothetical) emptyList() else
            parsed.whatIf?.let { w -> runCatching { SketchConverter.toOps(w, scenario).ops }.getOrNull() }?.takeIf { it.isNotEmpty() } ?: converted.ops
        DebugLog.d { "TURN user=\"$text\" -> ops=${converted.ops.map { it.type }} ask=${converted.ask} hasModel=${scenario.actions.isNotEmpty()}" }
        if (converted.support) onSupport()
        // A follow-up that only restates what is already saved has nothing to add. With a model in place that is not a
        // failure: run the analysis again and say so (seen on a phone: the same error repeated on every follow-up).
        val hasModel = scenario.actions.isNotEmpty()
        var nothingNew = false
        val understood = mutableListOf<String>()
        // ID2.3: what this turn saved, for the "advice changed because" lines (Kotlin's own descriptions of the ops).
        val turnOps = mutableListOf<com.drkkr.strategos.domain.agent.ChangeOp>()
        if (holdCourse) {
            nothingNew = true
        } else if (hypothetical) {
            nothingNew = true                                                    // I-D3: nothing from this turn is saved
            DebugLog.d { "HYPOTHETICAL ${parsed.intentKind.word}: ${hypotheticalOps.size} ops on a copy, none saved" }
        } else if (converted.ops.isEmpty()) {
            if (converted.ask != null || !hasModel) {
                conversations.append(cid, turnId, MessageRole.ASSISTANT, declined(converted.ask ?: COULD_NOT_READ))
                return TurnResult.Done
            }
            nothingNew = true
        } else {
            val applied = apply(cid, sid, scenario, converted.ops)
            if (applied == null || applied.result.applied == 0) {
                if (!hasModel) return fail(cid, turnId, COULD_NOT_READ, retryable = true)
                nothingNew = true
            } else {
                understood += PlainResult.understood(applied.accepted, scenario)
                turnOps += applied.accepted
            }
        }

        // Safety first (I-H5). The model flagged support, or the radar heard coercive control in a close relationship: the engine, the
        // tactics and the watch-for list stay out of it. A short, warm reply points to someone they trust and to the support card.
        if (argon != null) {
            val saved = gateway.activeScenario() ?: scenario
            val radar = com.drkkr.strategos.domain.human.ManipulationRadar.scan(
                saved.humanContext, com.drkkr.strategos.domain.human.RelationshipDomains.of(saved.humanContext?.relationship, null)
            )
            if (converted.support || radar.routeToSupport) {
                onSupport()
                conversations.append(cid, turnId, MessageRole.ASSISTANT, safetyReply(cid, turnId, text))
                afterTurn(cid)
                return TurnResult.Done
            }
        }

        // Phase H.1: structural validation — if the compiled IR violates archetype invariants (e.g. PD payoff ordering),
        // retry Call 1 exactly once with the violation strings injected as feedback. Max 1 retry to avoid loops.
        if (!nothingNew && argon != null) {
            gateway.activeScenario()?.let { saved ->
                val compileResult = GameModelCompiler.compile(saved)
                if (compileResult is CompileResult.Success) {
                    val violations = when (val v = ExtractionValidator.validate(compileResult.ir)) {
                        is ExtractionValidator.ValidationResult.Invalid -> v.violations
                        is ExtractionValidator.ValidationResult.Valid   -> emptyList()
                    }
                    if (violations.isNotEmpty()) {
                        val correctionHint = "Your extraction produced a structurally inconsistent model. " +
                            "Correct these issues before continuing:\n" +
                            violations.joinToString("\n") { "• $it" } +
                            "\nReturn corrected JSON only."
                        val retryInput = appendUserMessage(argon.assembler.build(cid, sid), correctionHint)
                        val retried = askModel(retryInput, cid, turnId, jsonMode = true, maxTokens = EXTRACTION_MAX_TOKENS, temperature = EXTRACTION_TEMPERATURE)
                        if (retried is Reply.Sketch) {
                            val more = SketchConverter.toOps(retried.sketch, saved)
                            if (more.ops.isNotEmpty()) apply(cid, sid, saved, more.ops)?.let {
                                understood += PlainResult.understood(it.accepted, saved)
                                turnOps += it.accepted
                            }
                        }
                    }
                }
            }
        }

        // Negotiations: before answering, ask up to two plain-words questions (what you'd do if it falls through, the lowest you'd
        // accept, the best you'd hope for) when those facts are still unknown. Asked once per thread and optional: the next
        // message goes through whatever the user answers, or skips. The saved negotiation context is filled from the replies.
        if (argon != null) {
            val earlier = conversations.messages(cid).filter { it.role == MessageRole.ASSISTANT.name }.map { it.text }
            val recentUser = conversations.messages(cid).filter { it.role == MessageRole.USER.name }.takeLast(6).joinToString(" ") { it.text }
            val qs = com.drkkr.strategos.domain.agent.NegotiationQuestions.beforeAnswering(
                recentUser, gateway.activeScenario()?.negotiationContext, earlier
            )
            if (qs.isNotEmpty()) {
                conversations.append(cid, turnId, MessageRole.ASSISTANT, declined(buildClarify(qs.map { it.text }) + "\n(Answer whichever you like, or say \"skip\" and I'll work with what I have.)"))
                afterTurn(cid)
                return TurnResult.Done
            }
        }

        // Intent Dispatch (decided at CP-I1): Kotlin decides whether there is enough to recommend (the engine's readiness and the D3
        // gap rule, in engineGroundedReply). Call 1's `enough_to_recommend` is logged only; it no longer holds the answer back.
        DebugLog.d { "ENOUGH_TO_RECOMMEND call1=${parsed.enoughToRecommend} (logged only, not gating)" }

        // One targeted follow-up request when options or outcome scores are missing (best effort: a failure here still
        // leaves a saved model and an honest answer).
        gateway.activeScenario()?.takeIf { !nothingNew }?.let { saved ->
            SketchGaps.describe(saved)?.let { gaps ->
                onActivity("Filling in what's missing…")
                val repair = askModel(input(LiveDigest.build(saved, forSketch = true), emptyList(), "$REPAIR_HEADER\n$gaps"), cid, turnId)
                if (repair is Reply.Sketch) {
                    val more = SketchConverter.toOps(repair.sketch, saved, humanFields = false)
                    if (more.ops.isNotEmpty()) apply(cid, sid, saved, more.ops)?.let { understood += PlainResult.understood(it.accepted, saved) }
                }
            }
        }

        // Task 5: confidence-gated second pass. When Call 1 is thin (LOW confidence) but the user wants an answer,
        // run a focused mini-extraction on what the other side actually wants — avoids asking more questions.
        // Never on a turn that saves nothing (thinking aloud, a hypothetical, a restatement): the second pass would save ops.
        if (argon != null && !nothingNew && parsed.confidenceLevel == "LOW" && parsed.enoughToRecommend != false) {
            gateway.activeScenario()?.let { saved ->
                onActivity("Checking the other side's perspective…")
                val secondPassInput = appendUserMessage(argon.assembler.build(cid, sid), SECOND_PASS_HEADER)
                val secondPass = askModel(secondPassInput, cid, turnId, jsonMode = true, maxTokens = SECOND_PASS_MAX_TOKENS, temperature = EXTRACTION_TEMPERATURE)
                if (secondPass is Reply.Sketch) {
                    val more = SketchConverter.toOps(secondPass.sketch, saved)
                    if (more.ops.isNotEmpty()) apply(cid, sid, saved, more.ops)?.let {
                        understood += PlainResult.understood(it.accepted, saved)
                        turnOps += it.accepted
                    }
                }
            }
        }

        onActivity("Running the analysis on your device…")
        val started = System.currentTimeMillis()
        val recentUserTextForSpec = userTexts.takeLast(6).joinToString(" ")
        // U7: fire engine pre-compute in parallel with analyzeAndPublish for follow-up turns (scenario unchanged)
        val (pr, speculativeCtxDeferred) = coroutineScope {
            val specDeferred = async(Dispatchers.Default) {
                runCatching { com.drkkr.strategos.domain.game.GameModelService.analyzeWithContext(scenario, contextText = recentUserTextForSpec) }.getOrNull()
            }
            Pair(gateway.analyzeAndPublish(), specDeferred)
        }
        val after = gateway.activeScenario() ?: scenario
        if (pr?.report == null) {
            conversations.append(cid, turnId, MessageRole.ASSISTANT, pr?.let(PlainResult::needsMore) ?: COULD_NOT_READ)
        } else {
            val digest = ReportDigest.build(pr, after)
            conversations.saveLastReport(cid, digest.toString(), after.version)
            val ran = pr.moduleRuns.count { it.status == ModuleRunStatus.SUCCESS }
            conversations.append(
                cid, turnId, MessageRole.TOOL_RESULT, text = "Ran $ran frameworks in ${System.currentTimeMillis() - started} ms on device",
                toolName = "run_analysis", callId = turnId, payloadJson = digest.toString(), uiKind = UiKind.RECOMMENDATION
            ).also { conversations.setRefId(it.id, it.id) }
            var guide: CoachGuideSketch? = null
            var cardFacts: CardFacts? = null
            val engineBody = if (argon != null) {
                engineGroundedReply(cid, turnId, sid, after, pr, understood, text, onActivity, previousReportJson, domain = parsed.domain, onGuide = { guide = it }, support = converted.support,
                    userAsksFor = com.drkkr.strategos.domain.agent.SketchHuman.context(parsed)?.userAsksFor,
                    // ID2.2: this turn's intent; the hypothetical is converted here and only ever run on copies (I-D3).
                    intent = parsed.intentKind, intentDetail = parsed.intentDetail, turnOps = turnOps, turnBefore = scenario,
                    whatIfOps = if (converted.support) emptyList() else hypotheticalOps, sketch = parsed, holdCourse = holdCourse,
                    speculativeCtxDeferred = speculativeCtxDeferred,
                    onCardFacts = { cardFacts = it })
            } else {
                val reply = PlainResult.build(after, pr, understood)
                val talk = if (coach) { onActivity("Writing my reply…"); coachReply(cid, turnId, after, pr, text) } else null
                val note = if (nothingNew && talk == null) "$NOTHING_NEW\n\n" else ""
                if (talk != null) "$talk\n\n$reply" else "$note$reply"
            }
            // Power Mode: the fixed decline line, word for word, above whatever the reply is (the coach is told not to restate it).
            val body = declined(engineBody)
            // The structured Do's/Don'ts ride along on the reply's payload so the Overview can aggregate them later.
            val assistant = conversations.append(
                cid, turnId, MessageRole.ASSISTANT, if (converted.ask != null) "$body\n\n${converted.ask}" else body,
                payloadJson = guide?.let { GuidanceCodec.encode(it) }
            )
            if (guide != null) conversations.setRefId(assistant.id, assistant.id)
            cardFacts?.let { showCards(cid, turnId, assistant.text, it, understood) }
        }
        afterTurn(cid)
        return TurnResult.Done
    }

    /** What this turn already worked out that a card can show: Kotlin's question, the other person's name, the ranked moves, the deal frame. */
    private class CardFacts(
        val question: com.drkkr.strategos.domain.intent.NextQuestion?, val otherName: String?,
        val top: List<com.drkkr.strategos.domain.intent.TopOption>?, val deal: com.drkkr.strategos.domain.negotiation.DealFrame?
    )

    /**
     * The cards under a reply (quick answers, the ranked options, what was just picked up). Built on the device from this turn's
     * own results and saved as A2UI messages; no model call, and nothing here ever goes into a prompt (tool results stay out of
     * the context). The walk-away figure is masked in them exactly as it is in the reply.
     */
    private suspend fun showCards(cid: String, turnId: String, shownReply: String, facts: CardFacts, understood: List<String>) {
        val pickedUp = understood.map { com.drkkr.strategos.domain.negotiation.WalkAwayMask.text(it, facts.deal) }
        com.drkkr.strategos.domain.a2ui.TurnCards.underReply(shownReply, facts.question, facts.otherName, facts.top, pickedUp).forEach { saveCard(cid, turnId, it) }
    }

    /** A shared conversation's timeline, shown under the message that carried the file, once per message. */
    private suspend fun showTimeline(cid: String, turnId: String, fullText: String) {
        val file = FileBlock.files(fullText).firstOrNull() ?: return
        val card = com.drkkr.strategos.domain.a2ui.TurnCards.timeline(file) ?: return
        val all = conversations.messages(cid)
        val lastUser = all.lastOrNull { it.role == MessageRole.USER.name }?.seq ?: return
        if (all.any { it.seq > lastUser && it.uiKind == UiKind.SURFACE.name && it.toolName == card.kind }) return      // a retried turn
        saveCard(cid, turnId, card)
    }

    private suspend fun saveCard(cid: String, turnId: String, card: com.drkkr.strategos.domain.a2ui.TurnCard) {
        conversations.append(cid, turnId, MessageRole.TOOL_RESULT, text = "Card", toolName = card.kind, callId = turnId,
            payloadJson = com.drkkr.strategos.domain.a2ui.SurfaceCodec.encode(card.messages), uiKind = UiKind.SURFACE)
    }

    /**
     * Engine, then interpret. The stress test and the engine's pick are computed here in Kotlin; the second model call may only
     * put that pick into words, and its echo of the pick is checked. If it cannot (or the engine has no pick yet), the app's own
     * deterministic text is shown instead, so the answer never contradicts the engine.
     */
    private suspend fun engineGroundedReply(
        cid: String, turnId: String, sid: String, s: com.drkkr.strategos.domain.model.Scenario, pr: com.drkkr.strategos.domain.analysis.PipelineResult,
        understood: List<String>, text: String, onActivity: (String) -> Unit, previousReportJson: String? = null,
        domain: String? = null,
        onGuide: (CoachGuideSketch) -> Unit = {},
        quickMode: Boolean = false,
        support: Boolean = false,
        /** Power Mode: this turn's manipulative request, if any (read from this turn's sketch, never a saved one). */
        userAsksFor: String? = null,
        /** Intent Dispatch: what the user asked for this turn (never stored), its detail, and the hypothetical's ops. */
        intent: com.drkkr.strategos.domain.intent.Intent = com.drkkr.strategos.domain.intent.Intent.STANDARD,
        intentDetail: com.drkkr.strategos.domain.agent.IntentDetail? = null,
        whatIfOps: List<com.drkkr.strategos.domain.agent.ChangeOp> = emptyList(),
        /** ID2.3: the ops this turn saved from the user's message, and the scenario they were applied to. */
        turnOps: List<com.drkkr.strategos.domain.agent.ChangeOp> = emptyList(),
        turnBefore: com.drkkr.strategos.domain.model.Scenario = s,
        /** This turn's Call 1 reply, read only for which option revisits a ruled-out move (StageGuard). */
        sketch: com.drkkr.strategos.domain.agent.ModelSketch? = null,
        /** Companion mode: thinking aloud, nothing saved; the coach restates the move (coach rule 34). */
        holdCourse: Boolean = false,
        /** U7: speculative engine pre-compute started in parallel with analyzeAndPublish. Null in quick-mode and support paths. */
        speculativeCtxDeferred: kotlinx.coroutines.Deferred<com.drkkr.strategos.domain.game.GameModelService.AnalysisContext?>? = null,
        /** What the cards under the reply are built from (see [showCards]); called once the directive is final. */
        onCardFacts: (CardFacts) -> Unit = {}
    ): String {
        onActivity("Checking how solid the pick is…")
        // The engine reads the model as a continuing relationship, with trust and tension from the trajectory (nothing is saved from this view).
        val snap = argon?.trajectory?.current(sid)
        val raw = EngineDirectiveBuilder.analysisView(s, snap?.trustScore, snap?.tensionScore)
        // Negotiation Numbers: the walk-away as a limit inside the vote (PLAN §3.3). In memory only; null frame = the same instance (I-N1).
        val recentForDeal = conversations.messages(cid).filter { it.role == MessageRole.USER.name }.takeLast(6).joinToString("\n") { it.text }
        val deal = com.drkkr.strategos.domain.negotiation.DealFrame.build(raw.negotiationContext, raw.humanContext, text, recent = recentForDeal)
        // CP-N: the user's option labels carry no walk-away or fallback figure in the view ("your ceiling", "your other option").
        val negotiated = com.drkkr.strategos.domain.intent.TurnChain.negotiated(raw, deal)
        // ID1.6: each side's fallback moves the refusal cells (after the label checks, on the Kotlin values). In memory only.
        // The pick is computed with and without it; the fixed line is added only when it changed the pick.
        val lock = settings.powerLock()
        val fallback = withContext(Dispatchers.Default) {
            com.drkkr.strategos.domain.human.FallbackShift.effect(negotiated, deal, support, lock,
                com.drkkr.strategos.domain.human.HumanSensitivity.pick(com.drkkr.strategos.domain.analysis.AnalysisPipeline()))
        }
        // Human Layer: the other person's payoffs as a real person would feel them. In memory only, like the lines above.
        val view = com.drkkr.strategos.domain.human.HumanizedView.apply(fallback.view)
        // In memory only: the last few things the user said, so text-based detectors (mechanism design) see more than the empty frame.
        val recentUserText = conversations.messages(cid).filter { it.role == MessageRole.USER.name }.takeLast(6).joinToString(" ") { it.text }
        val (viewResult, stress, gameContext) = withContext(Dispatchers.Default) {
            coroutineScope {
                val viewResultDeferred = async { com.drkkr.strategos.domain.analysis.AnalysisPipeline().run(view) }
                val stressDeferred = async { runCatching { InferredStressTest.run(view, pipeline = com.drkkr.strategos.domain.analysis.AnalysisPipeline()) }.getOrNull() }
                val gameContextDeferred = async {
                    // U7: use speculative result when available; fallback to fresh analysis with timeout
                    val spec = speculativeCtxDeferred?.let {
                        withTimeoutOrNull(ENGINE_ANALYSIS_TIMEOUT_MS) { it.await() }
                    }
                    spec ?: withTimeoutOrNull(ENGINE_ANALYSIS_TIMEOUT_MS) {
                        com.drkkr.strategos.domain.game.GameModelService.analyzeWithContext(view, contextText = recentUserText)
                    } ?: com.drkkr.strategos.domain.game.GameModelService.AnalysisContext(
                        result = com.drkkr.strategos.domain.game.GameAnalysisResult.Partial(
                            ir = null, partialOutcome = null,
                            reason = "Engine analysis timed out",
                            decisionState = com.drkkr.strategos.domain.game.DecisionState.EXPLORATORY,
                            integrity = com.drkkr.strategos.domain.game.AnalysisIntegrity.INCOMPLETE
                        ),
                        pruneResult = null
                    )
                }
                Triple(viewResultDeferred.await(), stressDeferred.await(), gameContextDeferred.await())
            }
        }
        // ID2.2: exactly one handler for the turn's intent, on copies of the view; none on a support turn (I-D4).
        // ID3.7: dominance is pruned among the moves left after the walk-away and reactive exclusion, so a valid move dominated
        // only by an excluded one survives. The solver's own run above is unchanged.
        val exclusionFirst = com.drkkr.strategos.domain.intent.ExclusionFirst.filtered(view)
        val pruneAfterExclusion = if (exclusionFirst === view) gameContext.pruneResult else withContext(Dispatchers.Default) {
            runCatching { com.drkkr.strategos.domain.game.GameModelService.pruneOnly(exclusionFirst) }.getOrNull()
        }
        // The user's moves the pruner eliminated (never offered as an option by an intent block).
        val ownerId = com.drkkr.strategos.domain.agent.IntakeChecklist.self(view)?.id
        val dominatedIds = pruneAfterExclusion?.eliminated?.get(ownerId).orEmpty().toSet()
        val intentResult = if (support || intent == com.drkkr.strategos.domain.intent.Intent.STANDARD) com.drkkr.strategos.domain.intent.IntentResult.None
        else withContext(Dispatchers.Default) {
            runCatching {
                // A variant goes through exactly this turn's steps and the directive's own guards (I-D4).
                val prepare = com.drkkr.strategos.domain.intent.TurnChain.prepare(deal, fallback.sides)
                val decide = com.drkkr.strategos.domain.intent.TurnChain.decide(deal, com.drkkr.strategos.domain.agent.StatedFloor.from(view.negotiationContext?.reservationPoint, text))
                com.drkkr.strategos.domain.intent.IntentRouter().route(com.drkkr.strategos.domain.intent.RouteInput(
                    intent, view, whatIfOps, intentDetail, base = raw, deal = deal, sides = fallback.sides, prepare = prepare, decide = decide,
                    dominated = dominatedIds, beta = com.drkkr.strategos.domain.human.ResponseModel.betaFrom(gameContext.opponentModel)))
            }.getOrDefault(com.drkkr.strategos.domain.intent.IntentResult.None)
        }
        DebugLog.d { "INTENT ${intent.word} -> ${intentResult::class.simpleName}" }
        // I-H6: a pick that only exists because of one of the Human Layer's constants is reported as fragile.
        val humanFragile = withContext(Dispatchers.Default) {
            runCatching { com.drkkr.strategos.domain.human.HumanSensitivity.pickChanges(negotiated, com.drkkr.strategos.domain.analysis.AnalysisPipeline(), fallback.sides) }.getOrDefault(false)
        }
        val gameResult = gameContext.result
        val previousMove = EngineDirectiveBuilder.previousRecommendedMove(previousReportJson)
        val solvedIr = (gameResult as? com.drkkr.strategos.domain.game.GameAnalysisResult.Solved)?.ir
        val gtPattern = EngineDirectiveBuilder.archetypeLabel(solvedIr?.archetype?.name)
            ?: solvedIr?.takeIf { it.archetype == com.drkkr.strategos.domain.game.GameArchetype.GENERIC }?.let {
                val dims = it.strategyCounts.joinToString("×")
                "${it.playerCount}-player game ($dims)"
            }
        val dominated = pruneAfterExclusion?.let { pruneRes ->
            pruneRes.eliminated.flatMap { (pid, acts) ->
                val name = view.stakeholders.firstOrNull { it.id == pid }?.name ?: pid
                acts.map { act ->
                    val label = view.actions.firstOrNull { it.id == act }?.label ?: act
                    "$name: $label (dominated)"
                }
            }
        }.orEmpty()
        val extensiveFormPath = gameContext.biResult?.equilibriumPath.orEmpty()
        val opponentRationality = gameContext.opponentModel?.coachNote()
        val shapleyValues = gameContext.coalitionResult?.values
        val mechanismHint = gameContext.mechanismHint?.let { "${it.type.name.lowercase()}: ${it.insight}" }
        // E5/E6/E10: cross-turn analysis from previousReportJson.
        val prevJson = previousReportJson?.let {
            try { kotlinx.serialization.json.Json.parseToJsonElement(it).jsonObject } catch (e: Exception) { null }
        }
        val prevEntropy = prevJson?.get("belief_state")?.jsonObject?.get("entropy")?.jsonPrimitive?.doubleOrNull
        val prevRegret = prevJson?.get("regret_strategy")?.jsonObject?.get("worst_case_regret")?.jsonPrimitive?.doubleOrNull
        val prevAsymmetryScore = prevJson?.get("info_asymmetry_score")?.jsonPrimitive?.doubleOrNull
        val maxEntropy = Math.log(11.0) / Math.log(2.0)
        val currEntropy = gameContext.beliefState?.entropy
        val epistemicRatchet = if (prevEntropy != null && currEntropy != null) {
            val prevConf = 1.0 - prevEntropy / maxEntropy
            val currConf = 1.0 - currEntropy / maxEntropy
            val delta = currConf - prevConf
            com.drkkr.strategos.domain.game.EpistemicRatchet(prevConf, currConf, delta,
                when { delta > 0.05 -> "improving"; delta < -0.05 -> "degrading"; else -> "stable" })
        } else null
        val currRegret = gameContext.regretResult?.worstCaseRegret
        val regretArc = if (prevRegret != null && currRegret != null) {
            com.drkkr.strategos.domain.game.RegretArc(prevRegret, currRegret,
                when { currRegret < prevRegret * 0.95 -> "improving"; currRegret > prevRegret * 1.05 -> "worsening"; else -> "stable" })
        } else null
        // E7: accumulate self-model labels per project.
        // F4: on app restart the in-memory accumulator is empty; restore from previous directive JSON.
        val prevLabels = prevJson
            ?.get("meta_strategy_profile")?.jsonObject
            ?.get("labels")
            ?.let { it as? kotlinx.serialization.json.JsonArray }
            ?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
        if (prevLabels != null) {
            val current = strategyLabelAccumulator.getOrPut(cid) { ArrayDeque() }
            if (current.isEmpty()) {
                prevLabels.forEach { current.add(it) }
                while (current.size > 20) current.removeFirst()
            }
        }
        val selfLabel = gameContext.selfModel?.inferredStrategyLabel
        if (selfLabel != null) {
            strategyLabelAccumulator.getOrPut(cid) { ArrayDeque() }.run {
                add(selfLabel); while (size > 20) removeFirst()
            }
        }
        val metaStrategyProfile = strategyLabelAccumulator[cid]?.let { labels ->
            if (labels.size >= 2) {
                val freq = labels.groupingBy { it }.eachCount()
                val dominant = freq.maxByOrNull { it.value }?.key
                val diversity = freq.size.toDouble() / labels.size
                com.drkkr.strategos.domain.game.MetaStrategyProfile(labels.toList(), dominant, diversity)
            } else null
        }
        val currAsymmetryScore = gameContext.infoAsymmetryScore
        val metatransparency = if (prevAsymmetryScore != null) {
            val derivative = currAsymmetryScore - prevAsymmetryScore
            com.drkkr.strategos.domain.game.MetatransparencyResult(prevAsymmetryScore, currAsymmetryScore, derivative,
                when { derivative < -0.05 -> "becoming_more_transparent"; derivative > 0.05 -> "becoming_less_transparent"; else -> "stable" })
        } else null
        val baseDirective = EngineDirectiveBuilder.build(view, viewResult, stress, previousMove, gameResult.integrity, gtPattern, dominated, extensiveFormPath, opponentRationality, shapleyValues, mechanismHint,
            statedFloor = com.drkkr.strategos.domain.agent.StatedFloor.from(view.negotiationContext?.reservationPoint, text), humanFragile = humanFragile, opponentModel = gameContext.opponentModel, deal = deal,
            // ID1.4: the checks read the AI's labels before the Human Layer moves them (the walk-away view, so excluded moves don't count).
            labelIssues = com.drkkr.strategos.domain.game.LabelChecks.check(negotiated),
            archetype = solvedIr?.archetype,
            fallbackLine = fallback.line,
            regretResult = gameContext.regretResult,
            beliefState = gameContext.beliefState,
            epistemicAdvice = gameContext.epistemicResult,
            selfModelAdvice = gameContext.selfModel?.let { "They likely see your approach as ${it.inferredStrategyLabel}" },
            bayesianNEUsed = gameContext.bayesianNE != null,
            extractionConfidence = gameContext.extractionConfidence,
            alternativeInterpretations = gameContext.alternativeInterpretations,
            focalSelection = gameContext.focalSelection,
            strategicNarrative = gameContext.strategicNarrative,
            sensitivityResult = gameContext.sensitivityResult,
            cvoiResult = gameContext.cvoiResult,
            bayesianCompletion = gameContext.bayesianCompletion,
            icSynthesis = gameContext.icSynthesis,
            epistemicRatchet = epistemicRatchet,
            regretArc = regretArc,
            metaStrategyProfile = metaStrategyProfile,
            surplusResult = gameContext.surplusResult,
            commitmentLadder = gameContext.commitmentLadder,
            metatransparency = metatransparency,
            infoAsymmetryScore = currAsymmetryScore)
        // Red-team call: short devil's-advocate challenge, run before interpret so the coach sees it.
        val challenge = if (baseDirective.ready) {
            withTimeoutOrNull(RED_TEAM_TIMEOUT_MS) { redTeamChallenge(cid, turnId, baseDirective) }
        } else null
        val maskedRaw = com.drkkr.strategos.domain.negotiation.WalkAwayMask.apply(raw, deal)
        val delivered = withDelivery(
            if (challenge != null) baseDirective.copy(redTeamChallenge = challenge) else baseDirective, maskedRaw, gameContext.opponentModel, support, text
        )
        // Power Mode: hardball moves join the delivery when they pay (I-P3); the decline note is set in any mode.
        val powerOn = settings.isPowerMode(cid)
        val (powered, powerPlan) = withPower(delivered, maskedRaw, powerOn, lock, support, snap?.trustScore)
        // ID4.1: the plan's first move gets the directive's delivery, and a lock-in close (Power moves only from the Power plan).
        val planAhead = (intentResult as? com.drkkr.strategos.domain.intent.IntentResult.PlanAhead)?.block?.takeIf { powered.ready && !support }?.let { block ->
            runCatching {
                val pickAction = ownerId?.let { o -> maskedRaw.actionsFor(o).firstOrNull { it.label.equals(powered.moveToImplement, ignoreCase = true) } }
                val close = pickAction?.let { a ->
                    com.drkkr.strategos.domain.intent.PlanAhead.close(maskedRaw, maskedRaw.humanContext, a, support,
                        com.drkkr.strategos.domain.human.ResponseModel.betaFrom(gameContext.opponentModel), powerPlan,
                        com.drkkr.strategos.domain.intent.PlanAhead.hasReceptiveBranch(view, block)) { id ->
                        val hc = maskedRaw.humanContext ?: return@close null
                        com.drkkr.strategos.domain.human.power.StrategicCatalog.tactics.firstOrNull { it.id == id }?.hint(maskedRaw, hc)
                    }
                }
                com.drkkr.strategos.domain.intent.PlanAhead.finish(block, view, powered.moveToImplement,
                    com.drkkr.strategos.domain.human.ResponseModel.betaFrom(gameContext.opponentModel), dominatedIds, powered.delivery, close)
            }.getOrNull()
        }
        // ID4.2: another project that shares a person or a pattern, at most once per conversation per project.
        val crossProject = if (!powered.ready || support) null else runCatching {
            val others = otherProjects(s.id)
            val shown = others.filter { settings.crossProjectShown(cid, it.id) }.map { it.id }.toSet()
            com.drkkr.strategos.domain.intent.CrossProjectMatcher.match(com.drkkr.strategos.domain.intent.CrossProjectMatcher.features(s), others, shown)
                .also { m -> DebugLog.d { "CROSS_PROJECT others=${others.size} shown=${shown.size} match=${m?.strength ?: "none"}" } }
        }.onFailure { DebugLog.d { "CROSS_PROJECT failed: ${it::class.simpleName}" } }.getOrNull()
        // ID2.3: the advice changed since last turn, on a standard turn that saved changes (built in Kotlin from the ops).
        val diff = if (!powered.ready) null else com.drkkr.strategos.domain.intent.RecommendationDiff.of(
            intent, previousMove, powered.recommendedMove, turnOps, turnBefore, s, com.drkkr.strategos.domain.agent.IntakeChecklist.self(s)?.id, deal)
        // ID2.4: one question at most (I-D6), from Kotlin's gap list; the fallback gaps count first when their answer would flip the pick.
        val earlierAssistant = conversations.messages(cid).filter { it.role == MessageRole.ASSISTANT.name }.map { it.text }
        val nextQuestion = if (support) null else withContext(Dispatchers.Default) {
            runCatching {
                val gapPick = com.drkkr.strategos.domain.human.HumanSensitivity.pick(com.drkkr.strategos.domain.analysis.AnalysisPipeline())
                val flips = buildSet {
                    if (com.drkkr.strategos.domain.human.FallbackShift.unknownFallbackMatters(negotiated, deal, support, lock, gapPick)) add(com.drkkr.strategos.domain.intent.IdentifyGaps.YOUR_FALLBACK)
                    if (com.drkkr.strategos.domain.human.FallbackShift.unknownTheirFallbackMatters(negotiated, deal, support, lock, gapPick)) add(com.drkkr.strategos.domain.intent.IdentifyGaps.THEIR_FALLBACK)
                }
                com.drkkr.strategos.domain.intent.IdentifyGaps.next(negotiated, com.drkkr.strategos.domain.agent.IntakeChecklist.gaps(s), stress, deal, flips, earlierAssistant,
                    advised = setOfNotNull(powered.recommendedMove, powered.moveToImplement))
            }.getOrNull()
        }
        // D3: a negotiation move that carries a figure while no walk-away is known asks first (the walk-away), instead of advising.
        val walkAwayAsk = com.drkkr.strategos.domain.intent.IdentifyGaps.walkAwayQuestion()
        if (powered.ready && !support && earlierAssistant.none { it.contains(walkAwayAsk.text) } &&
            com.drkkr.strategos.domain.intent.IdentifyGaps.askFirst(negotiated, deal, powered.recommendedMove)) {
            DebugLog.d { "ASK_FIRST D3: a figure in the pick and no walk-away" }
            return buildClarify(listOf(walkAwayAsk.text))
        }
        // A move that raises the stakes: never a clear win when waiting was not weighed, or when it was ruled out before and nothing changed.
        val revisited = if (!powered.ready) null else com.drkkr.strategos.domain.intent.StageGuard.revisited(sketch, s, powered.moveToImplement)
        val oneWayOnly = powered.ready && com.drkkr.strategos.domain.intent.StageGuard.oneWayOnly(s)
        val nowOverWait = powered.ready && com.drkkr.strategos.domain.intent.StageGuard.nowOverWait(s, powered.moveToImplement)
        if (revisited != null || oneWayOnly || nowOverWait) DebugLog.d { "STAGE_GUARD oneWayOnly=$oneWayOnly nowOverWait=$nowOverWait revisited=${revisited != null} changed=${revisited?.changed != null}" }
        val directive = powered.copy(declineNote = com.drkkr.strategos.domain.human.power.DeclineNotes.of(userAsksFor), powerMode = powerOn && powered.ready,
            changedSinceLast = diff, nextQuestion = nextQuestion.takeIf { powered.ready },
            whatIf = (intentResult as? com.drkkr.strategos.domain.intent.IntentResult.WhatIf)?.block?.takeIf { powered.ready },
            compare = (intentResult as? com.drkkr.strategos.domain.intent.IntentResult.Compare)?.block?.takeIf { powered.ready },
            theirReaction = (intentResult as? com.drkkr.strategos.domain.intent.IntentResult.TheirReaction)?.block?.takeIf { powered.ready },
            planAhead = planAhead, crossProject = crossProject,
            // ID3.5: on every engine turn with a pick.
            confidence = if (!powered.ready) null else com.drkkr.strategos.domain.intent.ConfidenceSummary.of(
                powered.confidenceLevel, powered.stressVerdict == "FRAGILE", stress, powered.labelIssues, nextQuestion,
                view.players().firstOrNull { it.id != ownerId }?.name, deal,
                oneWayOnly = oneWayOnly, revisitedUnchanged = revisited != null && revisited.changed == null, nowOverWait = nowOverWait),
            revisited = revisited,
            holdCourse = holdCourse && powered.ready,
            // ID3.3: asked for, or on its own when the pick is FRAGILE or the labels favoured one option everywhere.
            topOptions = if (!powered.ready || support) null else (intentResult as? com.drkkr.strategos.domain.intent.IntentResult.TopOptionsList)?.options
                ?: if (com.drkkr.strategos.domain.intent.TopOptions.wanted(intent, powered.stressVerdict == "FRAGILE", powered.autoTopOptions))
                    com.drkkr.strategos.domain.intent.TopOptions.of(view, powered.recommendedMove, dominatedIds).takeIf { it.size >= 2 } else null)
        // Cards never appear on a support turn. The question is Kotlin's own (asked even when there is no pick yet).
        if (!support) onCardFacts(CardFacts(nextQuestion, view.players().firstOrNull { it.id != ownerId }?.name, directive.topOptions, deal))
        // Power Mode: the Kotlin-built Power Read card, saved before the reply so it shows above it (blueprint §6).
        // No card at all on a support turn or under the lock (pm6.1; isPowerMode already reads the lock).
        if (powerOn && directive.ready && !support) {
            val built = com.drkkr.strategos.domain.human.power.PowerRead.build(maskedRaw, powerPlan ?: com.drkkr.strategos.domain.human.power.PowerPlan())
            val earlierCards = conversations.messages(cid).filter { it.uiKind == UiKind.POWER_READ.name }
                .mapNotNull { m -> m.payloadJson?.let(com.drkkr.strategos.domain.human.power.PowerReadCodec::decode) }
            val fallbackMatters = withContext(Dispatchers.Default) {
                runCatching {
                    com.drkkr.strategos.domain.human.FallbackShift.unknownFallbackMatters(negotiated, deal, support, lock,
                        com.drkkr.strategos.domain.human.HumanSensitivity.pick(com.drkkr.strategos.domain.analysis.AnalysisPipeline()))
                }.getOrDefault(false)
            }
            val read = built.copy(ask = com.drkkr.strategos.domain.human.power.PowerCardGate.ask(built, s, fallbackMatters, earlierCards.flatMap { it.ask }.toSet()))
            val shown = com.drkkr.strategos.domain.human.power.PowerCardGate.show(read, earlierCards.lastOrNull())
            DebugLog.d { "POWER_CARD shown=$shown ask=${read.ask}" }
            if (shown) conversations.append(cid, turnId, MessageRole.TOOL_RESULT, text = "Power Read", toolName = "power_read", callId = turnId,
                payloadJson = com.drkkr.strategos.domain.human.power.PowerReadCodec.encode(read), uiKind = UiKind.POWER_READ)
        }
        // A held move is remembered, so the next turn can ask how it went (cleared when they report a reaction).
        if (intent !in HYPOTHETICAL_INTENTS && directive.delivery?.timing == com.drkkr.strategos.domain.human.Timing.AFTER_COOLING && directive.moveToImplement != null &&
            s.humanContext?.pendingCooling != directive.moveToImplement) {
            apply(cid, sid, s, listOf(com.drkkr.strategos.domain.agent.ChangeOp(
                com.drkkr.strategos.domain.agent.OpType.SET_SCENARIO_FIELDS, rationale = "A reminder to ask how it went",
                source = com.drkkr.strategos.domain.agent.OpSource.AI_INFERRED,
                scenarioFields = com.drkkr.strategos.domain.agent.ScenarioFieldsPatch(humanContext = com.drkkr.strategos.domain.human.HumanContext(pendingCooling = directive.moveToImplement))
            )))
        }
        if (gameResult is com.drkkr.strategos.domain.game.GameAnalysisResult.Solved) {
            certificates?.persist(s.id, s.version, gameResult, directive)
        }
        DebugLog.d { "DIRECTIVE ${directive.toJson()}" }
        // Phase G.4: the static "Engine: <move> · <pattern> · Robust" badge is no longer appended to the chat reply --
        // it read as boilerplate noise. The move still leads the coach text, and the coach conveys any fragility in
        // plain words (ENGINE_COACH_PROMPT rule 4). badge() is kept for tests and any future UI chip.
        if (!directive.ready) {
            // No confident pick yet. Still answer THIS message in the coach's own words; the fixed template ignores what was said.
            DebugLog.d { "FALLBACK engine has no pick (directive not ready); asking the coach to reflect on the message" }
            onActivity("Writing my reply…")
            // ID2.4: Kotlin decides readiness; with a gap to ask about, ask it (one question) instead of reflecting.
            // The limits question never stands in for the advice; with only that gap left, the coach reflects on the message below.
            if (com.drkkr.strategos.domain.intent.IdentifyGaps.asksInsteadOfAdvice(nextQuestion)) return buildClarify(listOf(nextQuestion!!.text))
            // Negotiation Numbers: the engine's own text and the reflection never offer an option that crosses the walk-away.
            val within = com.drkkr.strategos.domain.negotiation.NegotiationView.withinWalkAway(pr, deal)
            return reflect(cid, turnId, s, within, text)?.takeIf { com.drkkr.strategos.domain.negotiation.DealCheck.usable(listOf(it), deal) }
                ?: com.drkkr.strategos.domain.negotiation.WalkAwayMask.text(PlainResult.build(s, within, understood), deal)
        }
        // Quick mode: skip interpret (Call 2) and return the engine badge only.
        if (quickMode) return directive.badge()
        onActivity("Writing my reply…")
        // Domain context: pattern-matched anchors injected into Call 2 so the coach can cite them.
        val domainContext = DomainContextInjector.build(domain, text)
        // Scale interpret tokens by stakes tier: high-stakes decisions deserve a fuller answer.
        val interpretTokens = when (directive.stakesTier) {
            com.drkkr.strategos.domain.agent.StakesTier.HIGH -> 800
            com.drkkr.strategos.domain.agent.StakesTier.LOW -> 400
            else -> INTERPRET_MAX_TOKENS
        }
        val reply = withTimeoutOrNull(INTERPRET_TIMEOUT_MS) {
            interpret(cid, turnId, directive, text, maxTokens = interpretTokens, deal = deal,
                band = com.drkkr.strategos.domain.negotiation.DealCheck.band(view.negotiationContext, deal), domainContext = domainContext)
        }
        return if (reply != null) {
            // ID4.2: shown once per conversation per matched project, counted when a reply that could carry it is shown.
            directive.crossProject?.let { runCatching { settings.setCrossProjectShown(cid, it.projectId) } }
            // The pill is built here from the engine's own directive (Kotlin), so it can never disagree with the advice.
            var g = reply.guide().copy(pill = pillFor(directive), watchFor = directive.watchFor.map { "${it.sign} ${it.protect}" },
                tactics = directive.delivery?.tactics.orEmpty().map { it.id.name })
            // One optional, plain-words question about the negotiation (fallback, lowest acceptable, ideal...), never repeated in this thread.
            val earlier = conversations.messages(cid).filter { it.role == MessageRole.ASSISTANT.name }.map { it.text }
            // ID2.4: the gap question is the closing line, and the only question (I-D6); otherwise the optional negotiation one.
            // ID4.3: the cross-project sentence is in the reply even when the coach left it out (fixed template, Kotlin's own data).
            directive.crossProject?.let { x -> g = g.copy(whatHappened = com.drkkr.strategos.domain.intent.CrossProjectMatcher.ensureIn(g.whatHappened, x)) }
            if (directive.nextQuestion != null) g = g.copy(followUp = directive.nextQuestion.text)
            else com.drkkr.strategos.domain.agent.NegotiationQuestions.next(recentUserText, s.negotiationContext, earlier)
                ?.let { q -> g = g.copy(followUp = com.drkkr.strategos.domain.agent.NegotiationQuestions.appendTo(g.followUp, q)) }
            onGuide(g); PlainResult.coach(g, planAhead = directive.planAhead != null,
                sure = directive.confidence == null || directive.confidence.tier == com.drkkr.strategos.domain.intent.ConfidenceTier.HIGH)
        }
        else {
            // A reply that did not echo the engine's pick is never shown (TC: aReplyThatIgnoresTheEnginePickIsNotShown), so the engine's own text stands.
            DebugLog.d { "FALLBACK structured reply unusable (not parsed, did not echo the engine's pick, or broke the walk-away); showing the engine's text" }
            com.drkkr.strategos.domain.negotiation.WalkAwayMask.text(PlainResult.build(s, com.drkkr.strategos.domain.negotiation.NegotiationView.withinWalkAway(pr, deal), understood), deal)
        }
    }

    /** A warm, specific answer to what the person just said, written from the engine's facts. Null if the model can't deliver one. */
    private suspend fun reflect(cid: String, turnId: String, s: com.drkkr.strategos.domain.model.Scenario, pr: com.drkkr.strategos.domain.analysis.PipelineResult, text: String): String? =
        coachReply(cid, turnId, s, pr, text) ?: run { delay(RETRY_PAUSE_MS); coachReply(cid, turnId, s, pr, text) }

    /**
     * Human Layer: how to DELIVER the engine's pick. The move itself is never changed (I-H1); the plan is null without a human
     * context, and empty (so nothing is sent) when there is a safety need, a vulnerable counterpart or coercive behaviour.
     */
    private fun withDelivery(
        d: EngineDirective, raw: com.drkkr.strategos.domain.model.Scenario,
        opponent: com.drkkr.strategos.domain.game.OpponentModel?, support: Boolean, userText: String
    ): EngineDirective {
        val hc = raw.humanContext ?: return d
        val d = d.copy(pendingCooling = hc.pendingCooling)
        if (!d.ready) return d
        val owner = com.drkkr.strategos.domain.agent.IntakeChecklist.self(raw) ?: return d
        val mine = raw.actionsFor(owner.id)
        val move = mine.firstOrNull { it.label.equals(d.moveToImplement, ignoreCase = true) } ?: return d
        val second = listOfNotNull(d.complementaryMove, d.safeFallbackMove)
            .firstNotNullOfOrNull { l -> mine.firstOrNull { it.label.equals(l, ignoreCase = true) && it.id != move.id } }
        val plan = com.drkkr.strategos.domain.human.DeliveryPlanner.plan(raw, hc, move, second, support, com.drkkr.strategos.domain.human.ResponseModel.betaFrom(opponent),
            userSaysNow = com.drkkr.strategos.domain.human.HotStateGate.userSaysNow(userText))
        val radar = com.drkkr.strategos.domain.human.ManipulationRadar.scan(hc, com.drkkr.strategos.domain.human.RelationshipDomains.of(hc.relationship, null))
        return d.copy(delivery = plan.takeIf { !it.isEmpty }, watchFor = radar.watchFor)
    }

    /**
     * Power Mode (blueprint §4): plans the hardball moves and merges up to two into the delivery, ranked against the benign ones by
     * value (a benign move's Δp × the user's receptive-minus-defensive spread; a hardball move's EV), at most three in all. The
     * engine's pick is never changed (I-H1). Returns the plan for the Power Read card.
     */
    private fun withPower(
        d: EngineDirective, raw: com.drkkr.strategos.domain.model.Scenario, powerOn: Boolean, powerLock: Boolean, support: Boolean, trust: Double?
    ): kotlin.Pair<EngineDirective, com.drkkr.strategos.domain.human.power.PowerPlan?> {
        if (!powerOn || !d.ready) return d to null
        val hc = raw.humanContext
        val owner = com.drkkr.strategos.domain.agent.IntakeChecklist.self(raw)
        val move = owner?.let { o -> raw.actionsFor(o.id).firstOrNull { it.label.equals(d.moveToImplement, ignoreCase = true) } }
        val spread = move?.let { com.drkkr.strategos.domain.human.power.BenignEquivalent.spread(raw, it.id) }
        val benign = if (spread == null) emptyList() else d.delivery?.tactics.orEmpty().map { it to com.drkkr.strategos.domain.human.power.BenignEquivalent.of(it.deltaP, spread) }
        val domain = com.drkkr.strategos.domain.human.RelationshipDomains.of(hc?.relationship, null, raw.negotiationContext)
        // The plan is always made when Power Mode is on, so the card always has its moves or the one reason there are none (pm6.1).
        // Without the engine's move no tactic can apply to it, so no candidates (the planner then says NOTHING_APPLIES).
        val plan = com.drkkr.strategos.domain.human.power.PowerPlanner.plan(raw, domain, powerOn, support, powerLock, trust,
            benignBest = benign.maxOfOrNull { it.second } ?: 0.0, move = move,
            tactics = if (move == null) emptyList() else com.drkkr.strategos.domain.human.power.StrategicCatalog.tactics)
        // Hardball joins the coach's delivery only for the engine's own move (I-H1).
        if (plan.deliveryMoves.isEmpty() || hc == null || move == null) return d to plan
        val hard = plan.deliveryMoves.map { t ->
            com.drkkr.strategos.domain.human.PlannedTactic(t.id, t.hint(raw, hc), 0.0) to (plan.ev[t.id] ?: 0.0)
        }
        val kept = (benign + hard).sortedByDescending { it.second }.take(MAX_DELIVERY_TACTICS).map { it.first }
        val ids = kept.map { it.id }.toSet()
        val base = d.delivery ?: com.drkkr.strategos.domain.human.Delivery()
        val delivery = base.copy(
            tactics = kept,
            // a benign move that was outranked takes its own extras with it
            defaultOffer = base.defaultOffer.takeIf { com.drkkr.strategos.domain.human.TacticId.DEFAULT_WITH_DEADLINE in ids },
            optionsToOffer = base.optionsToOffer.takeIf { com.drkkr.strategos.domain.human.TacticId.TWO_GOOD_OPTIONS in ids }.orEmpty()
        )
        return d.copy(delivery = delivery) to plan
    }

    /**
     * The user just reported how the last reply landed (softened / withdrew or escalated): teach the priors of the delivery tactics
     * that reply used. Counted once per reply (see [com.drkkr.strategos.domain.human.HumanContext.learnedFromMessage]).
     */
    private suspend fun tacticLearningFor(cid: String, scenario: com.drkkr.strategos.domain.model.Scenario, reaction: String?): com.drkkr.strategos.domain.human.TacticLearning.Learned? {
        if (reaction != "softened" && reaction != "withdrew_or_escalated") return null
        val last = conversations.messages(cid).lastOrNull { it.role == MessageRole.ASSISTANT.name && it.payloadJson != null } ?: return null
        if (scenario.humanContext?.learnedFromMessage == last.id) return null
        val used = com.drkkr.strategos.domain.agent.GuidanceCodec.decode(last.payloadJson!!)?.tactics.orEmpty()
        val update = com.drkkr.strategos.domain.human.TacticLearning.update(scenario.humanContext?.tacticSuccess.orEmpty(), used, reaction)
        return update.takeIf { it.isNotEmpty() }?.let { com.drkkr.strategos.domain.human.TacticLearning.Learned(last.id, it) }
    }

    /** The safety-first reply: one short call that never sees an engine move, checked by [SafetyReply]; the fixed warm text if it fails. */
    private suspend fun safetyReply(cid: String, turnId: String, text: String): String {
        val input = buildJsonArray {
            add(buildJsonObject { put("role", "system"); put("content", ArgonPrompts.SAFETY_REPLY_PROMPT) })
            add(buildJsonObject { put("role", "user"); put("content", "THE PERSON SAYS:\n${whole(text)}") })
        }
        val request = LlmRequest(
            settings.model(), input, tools = null, maxOutputTokens = SAFETY_MAX_TOKENS, temperature = INTERPRET_TEMPERATURE,
            purpose = "SAFETY", conversationId = cid, turnId = turnId, jsonObjectMode = false
        )
        repeat(2) { attempt ->
            val result = llm.create(request)
            if (result is LlmResult.Ok) {
                val reply = result.output.assistantText().trim()
                if (com.drkkr.strategos.domain.agent.SafetyReply.acceptable(reply)) return reply
                DebugLog.d { "SAFETY reply refused (attempt ${attempt + 1}); it gave strategy, was empty, or named something" }
            } else if (attempt == 0) delay(RETRY_PAUSE_MS)
        }
        return com.drkkr.strategos.domain.agent.SafetyReply.FALLBACK
    }

    private fun pillFor(d: EngineDirective): String = listOfNotNull(
        d.moveToImplement?.takeIf { it.isNotBlank() },
        d.dynamicLabel,
        if (d.stressVerdict == "FRAGILE") "Fragile" else "Robust"
    ).joinToString(" · ")


    /**
     * What the person has told the coach elsewhere in this project: their linked journal entries, the takeaways drawn
     * from them, and what the other topics are about. Without it, call 2 only knows the engine facts and this thread.
     */
    private suspend fun background(cid: String): String {
        val sid = conversations.get(cid)?.scenarioId ?: return ""
        val journal = argon?.journal
        val entries = journal?.linkedEntries(sid, BACKGROUND_ENTRIES).orEmpty()
        val notes = journal?.activePostNotes(sid).orEmpty().filter { !it.applied }.take(BACKGROUND_ENTRIES)
        val topics = conversations.listThreads(sid).filter { it.id != cid }.take(BACKGROUND_TOPICS).mapNotNull { t ->
            val said = conversations.messages(t.id).lastOrNull { it.role == MessageRole.USER.name }?.text ?: return@mapNotNull null
            "- ${t.title}: ${FileBlock.note(said).take(BACKGROUND_LINE_CHARS)}"
        }
        val files = sharedFiles(cid)
        if (entries.isEmpty() && notes.isEmpty() && topics.isEmpty()) return files
        return files + buildString {
            append("BACKGROUND FROM THIS PROJECT (use it only where it bears on what they say; if they ask about their journal or another topic, answer from it):\n")
            if (entries.isNotEmpty()) append("JOURNAL ENTRIES THEY LINKED (newest first):\n").append(entries.joinToString("\n") { "- " + it.text.take(BACKGROUND_LINE_CHARS) }).append('\n')
            if (notes.isNotEmpty()) append("TAKEAWAYS FROM THEIR JOURNAL:\n").append(notes.joinToString("\n") { "- ${it.insightHeadline}" }).append('\n')
            if (topics.isNotEmpty()) append("OTHER TOPICS IN THIS PROJECT:\n").append(topics.joinToString("\n")).append('\n')
            append('\n')
        }
    }

    /**
     * The files the person shared in this conversation, newest first, as the briefs prepared on the device. They are read from the
     * saved messages on every turn, so the coach can still answer from a file long after the message that carried it has been
     * folded into the rolling summary.
     */
    private suspend fun sharedFiles(cid: String): String {
        val files = conversations.messages(cid).filter { it.role == MessageRole.USER.name }.flatMap { FileBlock.files(it.text) }
            .asReversed().distinctBy { it.name }.take(COACH_FILES)
        if (files.isEmpty()) return ""
        return buildString {
            append("FILES THEY SHARED (prepared on their phone; use what bears on their question, quote figures and dates exactly, and say so if the part they ask about is not shown):\n")
            files.forEach { f -> append("FILE \"").append(f.name).append("\" (").append(f.label).append(", ").append(f.facts).append("):\n").append(fileExcerpt(f.body, COACH_FILE_CHARS)).append("\n") }
            append('\n')
        }
    }

    /**
     * This thread so far: the person's last few messages and the coach's previous reply, so call 2 can build on what was
     * already said and what the person has done since, instead of restating the same advice when the engine's pick repeats.
     */
    private suspend fun thread(cid: String): String {
        val all = conversations.messages(cid).filter { it.role == MessageRole.USER.name || it.role == MessageRole.ASSISTANT.name }.dropLast(1)
        val summary = conversations.get(cid)?.rollingSummary?.takeIf { it.isNotBlank() }
        val said = everythingSaid(all.filter { it.role == MessageRole.USER.name }.map { FileBlock.note(it.text) })
        val replies = all.filter { it.role == MessageRole.ASSISTANT.name }
        val previous = replies.lastOrNull()?.text
        // The moves already suggested in this thread (any age), so a long thread keeps moving forward instead of recycling advice.
        val movesTried = replies.takeLast(THREAD_MOVES_WINDOW).mapNotNull { r ->
            r.text.lineSequence().firstNotNullOfOrNull { l -> PlainResult.MOVE_LABELS.firstOrNull { l.startsWith("$it:") }?.let { l.removePrefix("$it:") } }?.trim()?.take(THREAD_MOVE_CHARS)
        }.distinct()
        if (summary == null && said.isEmpty() && previous == null) return ""
        return buildString {
            append("THIS CONVERSATION SO FAR:\n")
            if (summary != null) append("SUMMARY OF THE EARLIER PART (oldest first, facts they shared):\n").append(newestPart(summary, THREAD_SUMMARY_CHARS)).append('\n')
            if (movesTried.isNotEmpty()) append("MOVES YOU ALREADY SUGGESTED (do not just repeat them):\n").append(movesTried.joinToString("\n") { "- $it" }).append('\n')
            if (said.isNotEmpty()) append(SAID_HEADER).append(said.joinToString("\n") { "- $it" }).append('\n')
            if (previous != null) append("YOUR PREVIOUS REPLY TO THEM (opening only):\n").append(previous.take(THREAD_PREVIOUS_CHARS)).append('\n')
            append('\n')
        }
    }

    /** Summary from the prior negotiation round (if this thread has one), formatted for the coach. */
    private suspend fun priorRound(cid: String): String {
        val priorId = conversations.get(cid)?.priorRoundConversationId ?: return ""
        val summary = conversations.get(priorId)?.rollingSummary?.takeIf { it.isNotBlank() } ?: return ""
        return "PRIOR NEGOTIATION ROUND (context from the previous round — note how this round builds on or differs from it):\n${summary.take(THREAD_SUMMARY_CHARS)}\n\n"
    }

    /** Call 2. Two tries: the reply must echo the engine's move exactly, or it is not used. */
    private suspend fun interpret(
        cid: String, turnId: String, directive: EngineDirective, text: String, maxTokens: Int = INTERPRET_MAX_TOKENS,
        deal: com.drkkr.strategos.domain.negotiation.DealFrame? = null,
        band: com.drkkr.strategos.domain.negotiation.DealCheck.PrivateBand? = null,
        domainContext: String? = null
    ): CoachReply? {
        fun dealOk(r: CoachReply) = com.drkkr.strategos.domain.negotiation.DealCheck.usable(r, deal) && com.drkkr.strategos.domain.negotiation.DealCheck.usableWithoutFrame(r, band)
        val priorRoundCtx = priorRound(cid)
        val domainBlock = if (!domainContext.isNullOrBlank()) "$domainContext\n\n" else ""
        val input = buildJsonArray {
            add(buildJsonObject { put("role", "system"); put("content", ArgonPrompts.ENGINE_COACH_PROMPT) })
            add(buildJsonObject {
                put("role", "user")
                put("content", background(cid) + priorRoundCtx + thread(cid) + domainBlock + "THE PERSON SAYS:\n${whole(text)}\n\nENGINE DIRECTIVE:\n${directive.toJson()}")
            })
        }
        var request = LlmRequest(
            settings.model(), input, tools = null, maxOutputTokens = maxTokens, temperature = INTERPRET_TEMPERATURE,
            purpose = "COACH", conversationId = cid, turnId = turnId, jsonObjectMode = true
        )
        // A reply with a [bracketed placeholder] gets one retry; if it still has one, the brackets are stripped here so they never show.
        var usable: CoachReply? = null
        repeat(2) { attempt ->
            val result = llm.create(request)
            if (result !is LlmResult.Ok) {
                // A dropped connection or a busy provider is not a bad reply: wait a moment and ask once more before using the plain text.
                if (attempt == 0) { delay(RETRY_PAUSE_MS); return@repeat }
                return usable?.stripped()
            }
            val r = result
            val reply = CoachReply.parse(r.output.assistantText())
            // Negotiation Numbers: a reply whose advice crosses the walk-away, or tells them the walk-away or the fallback, is not used either.
            if (reply != null && reply.implements(directive.moveToImplement) && !reply.namesSomethingBanned() &&
                dealOk(reply)) {
                if (!reply.hasPlaceholder()) return reply
                usable = reply
            }
            DebugLog.d { "INTERPRET not used (attempt ${attempt + 1}): parsed=${reply != null} echoed=${reply?.engineMove} wanted=${directive.moveToImplement} placeholder=${reply?.hasPlaceholder()} dealOk=${reply?.let { dealOk(it) }}" }
            // A walk-away leak is told back to the coach before its retry: the same request again would leak the same way,
            // because the person's own message carries the figure.
            if (attempt == 0 && reply != null && !dealOk(reply)) request = request.copy(input = withNote(input, DEAL_RETRY_NOTE))
        }
        return usable?.stripped()
    }

    /**
     * The companion's words: a second request that gets the engine's result and the person's message and answers them.
     * Null on any failure or an unusable reply, so the app-written view below it still stands on its own.
     */
    private suspend fun coachReply(cid: String, turnId: String, s: com.drkkr.strategos.domain.model.Scenario, pr: com.drkkr.strategos.domain.analysis.PipelineResult, text: String): String? {
        val earlier = everythingSaid(conversations.messages(cid).filter { it.role == MessageRole.USER.name }.map { FileBlock.note(it.text) }.dropLast(1))
        val input = buildJsonArray {
            add(buildJsonObject { put("role", "system"); put("content", COACH_PROMPT) })
            add(buildJsonObject {
                put("role", "user")
                put("content", buildString {
                    append(background(cid))
                    append("WHAT THE ANALYSIS SHOWS:\n").append(CoachFacts.build(s, pr)).append('\n')
                    if (earlier.isNotEmpty()) append(SAID_HEADER).append(earlier.joinToString("\n") { "- $it" }).append('\n')
                    append("THE PERSON SAYS:\n").append(whole(text))
                })
            })
        }
        val request = LlmRequest(settings.model(), input, tools = null, maxOutputTokens = AgentConfig.COACH_MAX_OUTPUT_TOKENS, purpose = "COACH", conversationId = cid, turnId = turnId)
        val r = llm.create(request) as? LlmResult.Ok ?: return null
        val words = r.output.assistantText().trim()
        // A JSON object, or nothing, means the model answered the wrong task; use the plain view instead.
        return words.takeIf { it.length >= MIN_COACH_CHARS && !it.startsWith("{") }
    }

    /**
     * Phase G.2: answers a question about the assistant's own previous reply, in plain words, without re-running the
     * engine. Returns null on any failure or an unusable (empty/JSON) reply, so the caller falls back to the normal pipeline.
     */
    private suspend fun answerFollowUp(
        cid: String, turnId: String, text: String, priorReply: String, reportJson: String?,
        deal: com.drkkr.strategos.domain.negotiation.DealFrame? = null
    ): String? {
        // CP-N: the prior reply and the digest go in masked; an answer that gives the walk-away or the fallback as a line to say is
        // retried once, and if it still does, every flagged sentence is removed and "Keep your walk-away to yourself." added (CP-P3).
        val prior = com.drkkr.strategos.domain.negotiation.WalkAwayMask.text(priorReply, deal)
        val digest = reportJson?.let { com.drkkr.strategos.domain.negotiation.WalkAwayMask.text(it, deal) }
        val first = followUpCall(cid, turnId, text, prior, digest) ?: return null
        if (com.drkkr.strategos.domain.negotiation.DealCheck.usableAnswer(first, deal)) return first
        DebugLog.d { "FOLLOWUP answer gave the walk-away or fallback as a line; retrying once" }
        val second = followUpCall(cid, turnId, text, prior, digest) ?: return com.drkkr.strategos.domain.negotiation.DealCheck.sanitizeAnswer(first, deal)
        return if (com.drkkr.strategos.domain.negotiation.DealCheck.usableAnswer(second, deal)) second
            else com.drkkr.strategos.domain.negotiation.WalkAwayMask.text(com.drkkr.strategos.domain.negotiation.DealCheck.sanitizeAnswer(second, deal), deal)
    }

    private suspend fun followUpCall(cid: String, turnId: String, text: String, priorReply: String, reportJson: String?): String? {
        val input = buildJsonArray {
            add(buildJsonObject { put("role", "system"); put("content", ArgonPrompts.ANSWER_FOLLOWUP_PROMPT) })
            add(buildJsonObject {
                put("role", "user")
                put("content", buildString {
                    append(background(cid))
                    val said = everythingSaid(conversations.messages(cid).filter { it.role == MessageRole.USER.name }.map { FileBlock.note(it.text) }.dropLast(1))
                    if (said.isNotEmpty()) append(SAID_HEADER).append(said.joinToString("\n") { "- $it" }).append("\n\n")
                    append("THE PERSON ASKS:\n").append(whole(text)).append("\n\n")
                    append("YOUR PREVIOUS REPLY:\n").append(priorReply.take(MAX_PRIOR_CHARS))
                    if (!reportJson.isNullOrBlank()) append("\n\nANALYSIS BEHIND IT (for your reference only, never quote scores):\n").append(reportJson.take(MAX_DIGEST_CHARS))
                })
            })
        }
        val request = LlmRequest(
            settings.model(), input, tools = null, maxOutputTokens = INTERPRET_MAX_TOKENS, temperature = INTERPRET_TEMPERATURE,
            purpose = "COACH", conversationId = cid, turnId = turnId
        )
        val r = llm.create(request) as? LlmResult.Ok ?: return null
        val words = r.output.assistantText().trim()
        // Nothing, or a JSON blob, means the model answered the wrong task; fall back to the normal pipeline.
        return words.takeIf { it.length >= MIN_FOLLOWUP_CHARS && !it.startsWith("{") }
    }

    /**
     * Red-team call: one short LLM call that plays devil's advocate against the directive's recommended move.
     * Returns a single plain-text sentence (the strongest counterargument), or null on any failure.
     * Budget: RED_TEAM_MAX_TOKENS tokens. The caller wraps this in withTimeoutOrNull(RED_TEAM_TIMEOUT_MS).
     */
    private suspend fun redTeamChallenge(cid: String, turnId: String, directive: EngineDirective): String? {
        val input = buildJsonArray {
            add(buildJsonObject { put("role", "system"); put("content", RED_TEAM_PROMPT) })
            add(buildJsonObject {
                put("role", "user")
                put("content", "ENGINE DIRECTIVE:\n${directive.toJson()}")
            })
        }
        val request = LlmRequest(
            settings.model(), input, tools = null, maxOutputTokens = RED_TEAM_MAX_TOKENS,
            temperature = RED_TEAM_TEMPERATURE,
            purpose = "RED_TEAM", conversationId = cid, turnId = turnId
        )
        val r = llm.create(request) as? LlmResult.Ok ?: return null
        val text = r.output.assistantText().trim()
        return text.takeIf { it.length in MIN_RED_TEAM_CHARS..MAX_RED_TEAM_CHARS && !it.startsWith("{") }
    }

    /**
     * Pre-routing call: ~80 output tokens, temperature 0. Returns a [RouterDecision] or null on any failure.
     * Null is treated as "proceed" so the main extraction always runs as fallback.
     */
    private suspend fun preRoute(cid: String, turnId: String, text: String): RouterDecision? {
        val input = buildJsonArray {
            add(buildJsonObject { put("role", "system"); put("content", PreRouter.PROMPT) })
            add(buildJsonObject { put("role", "user"); put("content", whole(text)) })
        }
        val request = LlmRequest(
            settings.model(), input, tools = null, maxOutputTokens = PreRouter.MAX_TOKENS,
            temperature = PreRouter.TEMPERATURE, purpose = "PRE_ROUTE", conversationId = cid, turnId = turnId
        )
        val r = llm.create(request) as? LlmResult.Ok ?: return null
        return PreRouter.tryParse(r.output.assistantText())
    }

    /** Phase G.3: the ask-first reply — a short lead the controller can also detect, then the 1-2 questions. */
    private fun buildClarify(questions: List<String>): String {
        val qs = questions.map { it.trim() }.filter { it.isNotEmpty() }.take(1)     // I-D6: one question per turn
        return if (qs.size == 1) "$CLARIFY_LEAD ${qs[0]}"
        else CLARIFY_LEAD + qs.joinToString("") { "\n• $it" }
    }

    private sealed class Reply {
        class Sketch(val sketch: ModelSketch) : Reply()
        class Failed(val text: String, val retryable: Boolean) : Reply()
    }

    /** One request; a second try when the reply isn't usable JSON. */
    private suspend fun askModel(
        input: JsonArray, cid: String, turnId: String, mayChangeModel: Boolean = true, jsonMode: Boolean = false,
        maxTokens: Int = AgentConfig.ONE_SHOT_MAX_OUTPUT_TOKENS, temperature: Double = AgentConfig.TEMPERATURE
    ): Reply {
        val model = settings.model()
        val request = LlmRequest(
            model, input, tools = null, maxOutputTokens = maxTokens, temperature = temperature,
            purpose = "TURN", conversationId = cid, turnId = turnId, jsonObjectMode = jsonMode
        )
        repeat(2) {
            when (val r = llm.create(request)) {
                is LlmResult.Ok -> {
                    val reply = r.output.assistantText()
                    val parsed = SketchParser.tryParse(reply)
                    parsed.getOrNull()?.let { return Reply.Sketch(it) }
                    DebugLog.d { "UNPARSEABLE reply (attempt ${it + 1}), ${reply.length} chars, output items=${r.output.size}, why: ${parsed.exceptionOrNull()?.message?.take(300)}" }
                }
                is LlmResult.Unauthorized -> {
                    settings.setKeyInvalid(true)                  // the Talk tab then offers "Update key" (§11.5.1)
                    return Reply.Failed(LlmErrors.userMessage(r), retryable = false)
                }
                is LlmResult.HttpError -> {
                    // G3: the chosen model was retired. Switch to one that exists, tell the user, retry once.
                    val gone = r.status == 404 || r.code == "model_not_found"
                    val fallback = if (gone && mayChangeModel) fallbackModel(model) else null
                    if (fallback != null) {
                        settings.setModel(fallback)
                        conversations.append(cid, turnId, MessageRole.EVENT, "The model you chose isn't available anymore. Switched to $fallback. Change it in Settings.")
                        return askModel(input, cid, turnId, mayChangeModel = false, jsonMode = jsonMode, maxTokens = maxTokens, temperature = temperature)
                    }
                    return Reply.Failed(LlmErrors.userMessage(r), retryable = true)
                }
                else -> return Reply.Failed(LlmErrors.userMessage(r), retryable = true)
            }
        }
        return Reply.Failed(COULD_NOT_READ, retryable = true)
    }

    /** First of [AgentConfig.MODEL_OPTIONS] that the live list still has, else the list's first model; never [current]. */
    private suspend fun fallbackModel(current: String): String? {
        val live = (llm.listModels() as? ModelListResult.Ok)?.models?.map { it.id }
            ?.filter { id -> AiSettingsController.EXCLUDED.none { id.contains(it, ignoreCase = true) } }
        val candidates = live ?: AgentConfig.MODEL_OPTIONS
        return AgentConfig.MODEL_OPTIONS.firstOrNull { it in candidates && it != current } ?: candidates.firstOrNull { it != current }
    }

    /** Normalise, validate, save as a change set and apply it through the normal path. Null when nothing could be saved. */
    private suspend fun apply(cid: String, sid: String, scenario: com.drkkr.strategos.domain.model.Scenario, rawOps: List<com.drkkr.strategos.domain.agent.ChangeOp>): Applied? {
        val validated = ChangeSetValidator.validate(scenario, OpNormalizer.normalize(rawOps, scenario))
        if (validated.accepted.isEmpty()) return null
        val accepted = validated.accepted.map { it.op }
        val changeSet = changeSets.create(
            scenarioId = sid, conversationId = cid, summary = "From your message",
            opsJson = Args.encodeOps(accepted),
            refIdsJson = Json.encodeToString(MapSerializer(String.serializer(), String.serializer()), validated.refIds),
            baseScenarioVersion = scenario.version
        )
        return autoApply(changeSet.id)?.let { Applied(it, accepted) }
    }

    private suspend fun fail(cid: String, turnId: String, text: String, retryable: Boolean): TurnResult {
        conversations.append(cid, turnId, MessageRole.ERROR, text)
        return TurnResult.Failed(text, retryable)
    }

    private fun appendUserMessage(base: JsonArray, text: String): JsonArray = buildJsonArray {
        base.forEach { add(it) }
        add(buildJsonObject { put("role", "user"); put("content", text) })
    }

    private fun input(digest: JsonObject, earlier: List<String>, text: String): JsonArray = buildJsonArray {
        add(buildJsonObject { put("role", "system"); put("content", PROMPT) })
        add(buildJsonObject {
            put("role", "user")
            put("content", buildString {
                append("APP CONTEXT (what is already saved):\n").append(digest).append('\n')
                if (earlier.isNotEmpty()) append(SAID_HEADER).append(earlier.joinToString("\n") { "- $it" }).append('\n')
                append("USER SAYS:\n").append(text)
            })
        })
    }

    companion object {
        /** U6: max wait for analyzeWithContext before falling back to incomplete context. */
        private const val ENGINE_ANALYSIS_TIMEOUT_MS = 2_500L
        /** E7: rolling window of strategy labels observed per project, keyed by project id. */
        private val strategyLabelAccumulator = java.util.concurrent.ConcurrentHashMap<String, ArrayDeque<String>>()
        /** Benign and hardball delivery moves together (Power Mode, STEPS PM4.1). */
        private const val MAX_DELIVERY_TACTICS = 3
        private const val RETRY_PAUSE_MS = 1_500L
        /** Everything the person said earlier in a thread goes to the coach in full, newest kept first up to this total; older still is in the summary. */
        private const val THREAD_USER_BUDGET = 10_000
        const val SAID_HEADER = "EVERYTHING THEY SAID EARLIER IN THIS CONVERSATION (oldest first, in their words):\n"
        private const val THREAD_PREVIOUS_CHARS = 600
        private const val THREAD_MOVES_WINDOW = 8
        private const val THREAD_MOVE_CHARS = 140
        private const val THREAD_SUMMARY_CHARS = 2_400
        private const val BACKGROUND_ENTRIES = 2
        private const val BACKGROUND_TOPICS = 2
        private const val BACKGROUND_LINE_CHARS = 200
        /** How many shared files the coach sees, and how much of each (about 600 tokens). */
        private const val COACH_FILES = 2
        private const val COACH_FILE_CHARS = 2_400
        // BLUEPRINT-QUESTION: 900 was sized for Groq's 8,000 tokens/minute cap (LEAN PROFILE). On DeepSeek it cut the sketch JSON mid-array,
        // so every substantive first message failed with "Couldn't reach the coach" (found by the emulator E2E run). 3,000 fits a full sketch.
        private const val EXTRACTION_MAX_TOKENS = 3_000
        private const val SAFETY_MAX_TOKENS = 250
        private const val EXTRACTION_TEMPERATURE = 0.1
        private const val INTERPRET_MAX_TOKENS = 600
        private const val INTERPRET_TEMPERATURE = 0.3
        private const val INTERPRET_TIMEOUT_MS = 15_000L
        /** Sent with the coach's retry after its reply gave away the walk-away (DealCheck); the person's own message carries the figure. */
        const val DEAL_RETRY_NOTE = "YOUR LAST REPLY WAS NOT USED: it gave away the walk-away. Keep `walk_away_private` private in every advice field and in `exact_words`: never write that figure, never tell them to state or name it, and never a figure worse for the user than it. Say \"I have a firm limit\" instead, and keep the same engine_move."

        /**
         * The newest [max] characters of an oldest-first summary, starting at a sentence or line, so a long thread keeps its latest
         * facts (a long test thread, 7 Nov: a 3,039-character summary cut to its first 1,800 lost the newest facts).
         */
        /** A "thinking_aloud" message on a standard turn, with options already saved, saves nothing and keeps the move. */
        internal fun holdsCourse(messageKind: String?, intent: com.drkkr.strategos.domain.intent.Intent, hasModel: Boolean): Boolean =
            hasModel && messageKind == "thinking_aloud" && intent == com.drkkr.strategos.domain.intent.Intent.STANDARD

        /**
         * A file brief cut to [max] characters for the coach: the top (what the file is, who, when, how it unfolded) and the
         * bottom (the newest entries or last rows), each ending on a whole line. The middle is what goes.
         */
        internal fun fileExcerpt(body: String, max: Int): String {
            if (body.length <= max) return body
            val top = body.take((max * 0.45).toInt()).substringBeforeLast('\n')
            val bottom = body.takeLast((max * 0.55).toInt() - 40).substringAfter('\n')
            return "$top\n… part of the file is not shown here …\n$bottom"
        }

        /**
         * The person's message whole. Only a paste past [MAX_USER_CHARS] is shortened, and then its start and its end are kept:
         * a pasted chat runs oldest to newest, so the end holds what was settled last.
         */
        internal fun whole(text: String, max: Int = MAX_USER_CHARS): String {
            if (text.length <= max) return text
            val top = text.take(max / 5).substringBeforeLast('\n')
            val bottom = text.takeLast(max - max / 5 - 80).substringAfter('\n')
            return "$top\n… (the middle of this long message is not shown) …\n$bottom"
        }

        /** Every earlier message, in full and oldest first; when they pass [budget] in total, the oldest ones go first. */
        internal fun everythingSaid(texts: List<String>, budget: Int = THREAD_USER_BUDGET): List<String> {
            val kept = ArrayList<String>()
            var used = 0
            for (t in texts.asReversed()) {
                val w = t.trim().takeIf { it.isNotEmpty() } ?: continue
                if (kept.isNotEmpty() && used + w.length > budget) break
                val piece = whole(w, budget)
                kept += piece; used += piece.length
            }
            return kept.asReversed()
        }

        internal fun newestPart(summary: String, max: Int): String {
            if (summary.length <= max) return summary
            // The summarizer's FACTS section (players, goal, hard limits; never rewritten) leads the summary and is always kept;
            // the newest part of the rest fills what is left.
            val facts = Regex("""(?s)^\s*FACTS\b.*?(?=\n\s*SITUATION\b)""").find(summary)?.value?.trimEnd()?.takeIf { it.length <= max / 3 }
            if (facts != null) return facts + "\n" + newestPart(summary.substring(facts.length).trimStart(), max - facts.length - 1)
            val tail = summary.takeLast(max)
            val start = Regex("""(?<=[.!?\n])\s+""").find(tail)?.range?.last?.plus(1)?.takeIf { it < tail.length / 3 } ?: 0
            return "…" + tail.substring(start).trimStart()
        }

        /** [input] with [note] put first in its last (user) message, so the directive stays the message's end. */
        internal fun withNote(input: JsonArray, note: String): JsonArray {
            val last = input.last().jsonObject
            val content = last.getValue("content").jsonPrimitive.content
            return JsonArray(input.dropLast(1) + buildJsonObject { put("role", "user"); put("content", "$note\n\n$content") })
        }
        private const val RED_TEAM_TIMEOUT_MS = 5_000L
        private const val RED_TEAM_MAX_TOKENS = 120
        private const val RED_TEAM_TEMPERATURE = 0.5
        private const val MIN_RED_TEAM_CHARS = 20
        private const val MAX_RED_TEAM_CHARS = 300
        private const val REPAIR_HEADER = "The saved model is incomplete. Return ONLY what is missing, in the same JSON format (people can be empty; use the same names and option wordings as the app context):"
        // Task 5: confidence-gated second pass.
        private const val SECOND_PASS_MAX_TOKENS = 800
        const val SECOND_PASS_HEADER = "The model looks thin. Focus only on the other person's perspective: what do THEY actually want from this situation, what are they afraid of, and what options do they have? Return ONLY new or corrected JSON — same format, same names."
        // Task 6: arc-state extraction hints injected into Call 1 for ACTED and OUTCOME_KNOWN states.
        private const val ARC_STATE_ACTED_HEADER = "The person has just taken an action. Focus extraction on what changed: the action they took, any reaction observed, and any new information about the other side's position. Skip re-extracting what hasn't changed."
        private const val ARC_STATE_OUTCOME_HEADER = "The person is reporting an outcome. Extract only the final result: what actually happened, how the other side responded, and what was learned. This is a retrospective — focus on outcome facts, not future options."
        /** A guard against an absurd paste only: every real message, a long pasted chat included, goes to the model whole. */
        internal const val MAX_USER_CHARS = 60_000
        private const val MIN_COACH_CHARS = 40
        // Phase G.2/G.3 (clarification router + ask-first).
        private const val MAX_PRIOR_CHARS = 1_200
        private const val MAX_DIGEST_CHARS = 1_500
        private const val MIN_FOLLOWUP_CHARS = 20
        const val CLARIFY_LEAD = "Before I call it, let me get this right —"
        /** I-D3: turns whose moves only ever run on a copy. */
        private val HYPOTHETICAL_INTENTS = setOf(
            com.drkkr.strategos.domain.intent.Intent.WHAT_IF, com.drkkr.strategos.domain.intent.Intent.COMPARE,
            com.drkkr.strategos.domain.intent.Intent.THEIR_REACTION, com.drkkr.strategos.domain.intent.Intent.PLAN_AHEAD)

        /** COMPANION REPLY prompt (~250 tokens). The words come from the model; every fact comes from the engine's result. */
        const val COACH_PROMPT = """You are Strategos, a calm, warm companion who helps a person think through a hard personal decision. You are not a therapist and you do not diagnose.
You get WHAT THE ANALYSIS SHOWS (from the app's game-theory engine; part of it rests on guesses) and what the person said.
Write 120-170 words of plain prose. No lists, headings, scores or percentages. Speak to "you".
1) Answer their actual question, or reflect what they shared, in one or two sentences.
2) Connect it to the analysis: which option looks best and why, what could go wrong, who matters most.
3) Suggest one or two small, concrete things they could do or say in the next few days, about their own behaviour.
4) End with one gentle question, or invite them to share how it goes.
Use only facts given to you; never invent events or feelings. Say "may" and "could", never promise an outcome.
If they sound overwhelmed, acknowledge it kindly and suggest leaning on someone they trust."""

        const val NOTHING_NEW = "There was nothing new in that to add to the model, so this is the same view as before. Tell me who did or said what, or what changed, and I'll update it."
        const val COULD_NOT_READ = "I couldn't turn that into options. Try telling me who is involved and what you could do."

        /** Red-team system prompt (~120 tokens). Returns ONE plain sentence — the strongest counterargument. */
        const val RED_TEAM_PROMPT = """You are a devil's advocate. You get an ENGINE DIRECTIVE (game-theory analysis). In ONE plain sentence, state the strongest reason the recommended move could backfire or be wrong. No hedging, no restating the move, no score references. If you see no real risk, reply: none."""

        /** The whole prompt (~800 tokens). The reply is one JSON object; the app does everything else. */
        const val PROMPT = """You turn a person's situation into a small decision model. Reply with ONE JSON object and nothing else.
Keys: goal, people (name, role PLAYER or INFLUENCER, veto, wants, fears), options (for, label), dims, outcomes (picks, scores), limits (label, kind), ask, support.
Rules:
- List EVERY person the user mentions (partner, parents, boss, friends...), plus "You". Never skip anyone.
- "You" is the user, a PLAYER. Make the ONE other person whose choice matters most a PLAYER too (3 players at most). Everyone else is an INFLUENCER; set veto true if they can stop it.
- Give EVERY PLAYER (including the other person) 2-3 options, one of them waiting or doing nothing. Influencers have no options.
- dims: pick 3 of SAFETY, ETHICS, LEGALITY, COST, REPUTATION, RELATIONSHIPS, WELLBEING, EFFICIENCY, PUBLIC_TRUST, LONG_TERM_VALUE. If the app context lists dims, use exactly those.
- outcomes: one entry for EVERY combination of the players' options. In scores, give each player one label per dim, in order: "++" much better, "+" better, "0" the same as today, "-" worse, "--" much worse, for how that combination leaves THEM. Judge each player from their own side — a player may gain where another loses. For COST, better means it costs them less. Never duplicate a row.
- limits: things that must not happen. kind is LEGAL, ETHICAL, SAFETY, POLICY, PHYSICAL or RESOURCE.
- Only fill wants, fears and limits when the user said them. Never invent them for people the user only mentions (a relative, a doctor, a friend). Never write placeholder text.
- ask: leave out unless the story is far too thin to model; then ONE short question and nothing else.
- support: true only if the CURRENT message shows self-harm, harming someone, abuse or danger now. Past panic attacks or therapy are not a reason.
- The app context shows what is saved. Do not repeat saved people or options; return only what is new or changed, with the same names. For a follow-up ("I did X and he reacted Y"), add or change what that implies, and give outcomes for any new combinations. A fact about someone's past or character ("he repaid late before") goes into that person's fears or wants, using the saved name.
Example for "My manager wants me on night shift. My partner hates that idea." (a different situation):
{"goal":"Keep my job without hurting my home life","people":[{"name":"You","role":"PLAYER","fears":"Losing sleep and time with my partner"},{"name":"Manager","role":"PLAYER","wants":"Night cover"},{"name":"Partner","role":"INFLUENCER","veto":true,"fears":"Never seeing me"}],"options":[{"for":"You","label":"Accept nights"},{"for":"You","label":"Refuse"},{"for":"Manager","label":"Insist"},{"for":"Manager","label":"Offer a swap"}],"dims":["WELLBEING","RELATIONSHIPS","COST"],"outcomes":[{"picks":{"You":"Accept nights","Manager":"Insist"},"scores":{"You":["-","-","0"],"Manager":["++","+","++"]}},{"picks":{"You":"Accept nights","Manager":"Offer a swap"},"scores":{"You":["0","0","+"],"Manager":["+","+","+"]}},{"picks":{"You":"Refuse","Manager":"Insist"},"scores":{"You":["+","0","-"],"Manager":["-","-","-"]}},{"picks":{"You":"Refuse","Manager":"Offer a swap"},"scores":{"You":["++","++","+"],"Manager":["+","+","+"]}}],"limits":[{"label":"No night work during my partner's treatment","kind":"ETHICAL"}]}"""
    }
}
