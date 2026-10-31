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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
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
    private val certificates: CertificateRepository? = null
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
        val userTexts = conversations.messages(cid).filter { it.role == MessageRole.USER.name }.map { it.text }
        val text = userTexts.lastOrNull() ?: return TurnResult.Done
        // Phase F.5: read before this turn's saveLastReport overwrites it, so the directive can name what it told you last time.
        val previousReportJson = conversations.get(cid)?.lastReportDigestJson

        onActivity("Reading your situation…")
        // Argon call 1 is extraction only: small, fast and nearly deterministic. The advice comes from call 2, after the engine.
        val first = if (argon != null) askModel(argon.assembler.build(cid, sid), cid, turnId, jsonMode = true, maxTokens = EXTRACTION_MAX_TOKENS, temperature = EXTRACTION_TEMPERATURE)
                    else askModel(input(LiveDigest.build(loaded, forSketch = true), userTexts.dropLast(1).takeLast(RECENT), text), cid, turnId)
        val parsed = when (first) {
            is Reply.Sketch -> first.sketch
            is Reply.Failed -> return fail(cid, turnId, first.text, first.retryable)
        }
        // Phase G.2: a message that only asks about the PREVIOUS reply ("what is that?", "why?") is answered directly
        // from that reply, never by re-running the engine into a near-duplicate answer. Routed before the trajectory and
        // Bayesian updates below, so asking a question never moves trust/tension or the learned priors.
        if (argon != null && parsed.messageKind == "followup_question") {
            val prior = conversations.messages(cid).lastOrNull { it.role == MessageRole.ASSISTANT.name }?.text
            if (!prior.isNullOrBlank()) {
                onActivity("Answering your question…")
                answerFollowUp(cid, turnId, text, prior, previousReportJson)?.let { answer ->
                    conversations.append(cid, turnId, MessageRole.ASSISTANT, answer)
                    afterTurn(cid)
                    return TurnResult.Done
                }
                // The answer call failed or came back unusable: fall through to the normal pipeline below.
            }
        }
        // BLUEPRINT-QUESTION: §D.1 says a point after every turn; a reply with no deltas and no archetype says nothing new, so it adds none.
        if (argon != null && (parsed.trustDelta != 0.0 || parsed.tensionDelta != 0.0 || parsed.archetype != null)) {
            argon.trajectory.recordTurn(sid, cid, parsed.trustDelta, parsed.tensionDelta, parsed.archetype, parsed.backstoryOnly)
        }
        // Phase F.6: the learned priors update here, before the ops are built, so this turn's SketchConverter.toOps and
        // the engine that reads the saved scenario afterward both see the same just-updated numbers in one commit.
        val scenario = if (argon != null) BayesianUpdater.applyToScenario(loaded, parsed.userLastMovePosture, parsed.counterpartObservedReaction) else loaded
        val converted = SketchConverter.toOps(parsed, scenario)
        DebugLog.d { "TURN user=\"$text\" -> ops=${converted.ops.map { it.type }} ask=${converted.ask} hasModel=${scenario.actions.isNotEmpty()}" }
        if (converted.support) onSupport()
        // A follow-up that only restates what is already saved has nothing to add. With a model in place that is not a
        // failure: run the analysis again and say so (seen on a phone: the same error repeated on every follow-up).
        val hasModel = scenario.actions.isNotEmpty()
        var nothingNew = false
        val understood = mutableListOf<String>()
        if (converted.ops.isEmpty()) {
            if (converted.ask != null || !hasModel) {
                conversations.append(cid, turnId, MessageRole.ASSISTANT, converted.ask ?: COULD_NOT_READ)
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
            }
        }

        // Phase G.3 (ask first, then recommend): when Call 1 judges the input still too thin to rank honestly, ask the
        // user 1-2 targeted questions to strengthen the model instead of ranking guessed outcomes. The ops extracted
        // above are already saved, so each answer builds on the last. Bounded: after CLARIFY_MAX asks in a row we go
        // ahead and recommend with what we have, so the user is never stuck in a loop of questions.
        if (argon != null && parsed.enoughToRecommend == false && parsed.clarifyingQuestions.isNotEmpty()) {
            val recentAssistant = conversations.messages(cid).filter { it.role == MessageRole.ASSISTANT.name }.map { it.text }.takeLast(CLARIFY_MAX)
            val askedTooMuch = recentAssistant.size >= CLARIFY_MAX && recentAssistant.all { it.startsWith(CLARIFY_LEAD) }
            if (!askedTooMuch) {
                conversations.append(cid, turnId, MessageRole.ASSISTANT, buildClarify(parsed.clarifyingQuestions))
                afterTurn(cid)
                return TurnResult.Done
            }
        }

        // One targeted follow-up request when options or outcome scores are missing (best effort: a failure here still
        // leaves a saved model and an honest answer).
        gateway.activeScenario()?.takeIf { !nothingNew }?.let { saved ->
            SketchGaps.describe(saved)?.let { gaps ->
                onActivity("Filling in what's missing…")
                val repair = askModel(input(LiveDigest.build(saved, forSketch = true), emptyList(), "$REPAIR_HEADER\n$gaps"), cid, turnId)
                if (repair is Reply.Sketch) {
                    val more = SketchConverter.toOps(repair.sketch, saved)
                    if (more.ops.isNotEmpty()) apply(cid, sid, saved, more.ops)?.let { understood += PlainResult.understood(it.accepted, saved) }
                }
            }
        }

        onActivity("Running the analysis on your device…")
        val started = System.currentTimeMillis()
        val pr = gateway.analyzeAndPublish()
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
            val body = if (argon != null) {
                engineGroundedReply(cid, turnId, sid, after, pr, understood, text, onActivity, previousReportJson) { guide = it }
            } else {
                val reply = PlainResult.build(after, pr, understood)
                val talk = if (coach) { onActivity("Writing my reply…"); coachReply(cid, turnId, after, pr, text) } else null
                val note = if (nothingNew && talk == null) "$NOTHING_NEW\n\n" else ""
                if (talk != null) "$talk\n\n$reply" else "$note$reply"
            }
            // The structured Do's/Don'ts ride along on the reply's payload so the Overview can aggregate them later.
            val assistant = conversations.append(
                cid, turnId, MessageRole.ASSISTANT, if (converted.ask != null) "$body\n\n${converted.ask}" else body,
                payloadJson = guide?.let { GuidanceCodec.encode(it) }
            )
            if (guide != null) conversations.setRefId(assistant.id, assistant.id)
        }
        afterTurn(cid)
        return TurnResult.Done
    }

    /**
     * Engine, then interpret. The stress test and the engine's pick are computed here in Kotlin; the second model call may only
     * put that pick into words, and its echo of the pick is checked. If it cannot (or the engine has no pick yet), the app's own
     * deterministic text is shown instead, so the answer never contradicts the engine.
     */
    private suspend fun engineGroundedReply(
        cid: String, turnId: String, sid: String, s: com.drkkr.strategos.domain.model.Scenario, pr: com.drkkr.strategos.domain.analysis.PipelineResult,
        understood: List<String>, text: String, onActivity: (String) -> Unit, previousReportJson: String? = null,
        onGuide: (CoachGuideSketch) -> Unit = {}
    ): String {
        onActivity("Checking how solid the pick is…")
        // The engine reads the model as a continuing relationship, with trust and tension from the trajectory (nothing is saved from this view).
        val snap = argon?.trajectory?.current(sid)
        val view = EngineDirectiveBuilder.analysisView(s, snap?.trustScore, snap?.tensionScore)
        val (viewResult, stress, gameContext) = withContext(Dispatchers.Default) {
            val pipeline = com.drkkr.strategos.domain.analysis.AnalysisPipeline()
            Triple(
                pipeline.run(view),
                runCatching { InferredStressTest.run(view, pipeline = pipeline) }.getOrNull(),
                com.drkkr.strategos.domain.game.GameModelService.analyzeWithContext(view)
            )
        }
        val gameResult = gameContext.result
        val previousMove = EngineDirectiveBuilder.previousRecommendedMove(previousReportJson)
        val gtPattern = EngineDirectiveBuilder.archetypeLabel(
            (gameResult as? com.drkkr.strategos.domain.game.GameAnalysisResult.Solved)?.ir?.archetype?.name
        )
        val dominated = gameContext.pruneResult?.let { pr ->
            pr.eliminated.flatMap { (pid, acts) ->
                val name = view.stakeholders.firstOrNull { it.id == pid }?.name ?: pid
                acts.map { act -> "$name: $act (dominated)" }
            }
        }.orEmpty()
        val directive = EngineDirectiveBuilder.build(view, viewResult, stress, previousMove, gameResult.integrity, gtPattern, dominated)
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
            return reflect(cid, turnId, s, pr, text) ?: PlainResult.build(s, pr, understood)
        }
        onActivity("Writing my reply…")
        val reply = interpret(cid, turnId, directive, text)
        return if (reply != null) {
            // The pill is built here from the engine's own directive (Kotlin), so it can never disagree with the advice.
            val g = reply.guide().copy(pill = pillFor(directive))
            onGuide(g); PlainResult.coach(g)
        }
        else {
            // A reply that did not echo the engine's pick is never shown (TC: aReplyThatIgnoresTheEnginePickIsNotShown), so the engine's own text stands.
            DebugLog.d { "FALLBACK structured reply unusable (not parsed, or did not echo the engine's pick); showing the engine's text" }
            PlainResult.build(s, pr, understood)
        }
    }

    /** A warm, specific answer to what the person just said, written from the engine's facts. Null if the model can't deliver one. */
    private suspend fun reflect(cid: String, turnId: String, s: com.drkkr.strategos.domain.model.Scenario, pr: com.drkkr.strategos.domain.analysis.PipelineResult, text: String): String? =
        coachReply(cid, turnId, s, pr, text) ?: run { delay(RETRY_PAUSE_MS); coachReply(cid, turnId, s, pr, text) }

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
            "- ${t.title}: ${said.take(BACKGROUND_LINE_CHARS)}"
        }
        if (entries.isEmpty() && notes.isEmpty() && topics.isEmpty()) return ""
        return buildString {
            append("BACKGROUND FROM THIS PROJECT (use it only where it bears on what they say; if they ask about their journal or another topic, answer from it):\n")
            if (entries.isNotEmpty()) append("JOURNAL ENTRIES THEY LINKED (newest first):\n").append(entries.joinToString("\n") { "- " + it.text.take(BACKGROUND_LINE_CHARS) }).append('\n')
            if (notes.isNotEmpty()) append("TAKEAWAYS FROM THEIR JOURNAL:\n").append(notes.joinToString("\n") { "- ${it.insightHeadline}" }).append('\n')
            if (topics.isNotEmpty()) append("OTHER TOPICS IN THIS PROJECT:\n").append(topics.joinToString("\n")).append('\n')
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
        val said = all.filter { it.role == MessageRole.USER.name }.takeLast(THREAD_USER_MESSAGES)
        val replies = all.filter { it.role == MessageRole.ASSISTANT.name }
        val previous = replies.lastOrNull()?.text
        // The moves already suggested in this thread (any age), so a long thread keeps moving forward instead of recycling advice.
        val movesTried = replies.takeLast(THREAD_MOVES_WINDOW).mapNotNull { r ->
            r.text.lineSequence().firstOrNull { it.startsWith("Clear-win move:") }?.removePrefix("Clear-win move:")?.trim()?.take(THREAD_MOVE_CHARS)
        }.distinct()
        if (summary == null && said.isEmpty() && previous == null) return ""
        return buildString {
            append("THIS CONVERSATION SO FAR:\n")
            if (summary != null) append("SUMMARY OF THE EARLIER PART (oldest first, facts they shared):\n").append(summary.take(THREAD_SUMMARY_CHARS)).append('\n')
            if (movesTried.isNotEmpty()) append("MOVES YOU ALREADY SUGGESTED (do not just repeat them):\n").append(movesTried.joinToString("\n") { "- $it" }).append('\n')
            said.forEach { append("- They said recently: ").append(it.text.take(THREAD_USER_CHARS)).append('\n') }
            if (previous != null) append("YOUR PREVIOUS REPLY TO THEM (opening only):\n").append(previous.take(THREAD_PREVIOUS_CHARS)).append('\n')
            append('\n')
        }
    }

    /** Call 2. Two tries: the reply must echo the engine's move exactly, or it is not used. */
    private suspend fun interpret(cid: String, turnId: String, directive: EngineDirective, text: String): CoachReply? {
        val input = buildJsonArray {
            add(buildJsonObject { put("role", "system"); put("content", ArgonPrompts.ENGINE_COACH_PROMPT) })
            add(buildJsonObject {
                put("role", "user")
                put("content", background(cid) + thread(cid) + "THE PERSON SAYS:\n${text.take(MAX_USER_CHARS)}\n\nENGINE DIRECTIVE:\n${directive.toJson()}")
            })
        }
        val request = LlmRequest(
            settings.model(), input, tools = null, maxOutputTokens = INTERPRET_MAX_TOKENS, temperature = INTERPRET_TEMPERATURE,
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
            if (reply != null && reply.implements(directive.moveToImplement)) {
                if (!reply.hasPlaceholder()) return reply
                usable = reply
            }
            DebugLog.d { "INTERPRET not used (attempt ${attempt + 1}): parsed=${reply != null} echoed=${reply?.engineMove} wanted=${directive.moveToImplement} placeholder=${reply?.hasPlaceholder()}" }
        }
        return usable?.stripped()
    }

    /**
     * The companion's words: a second request that gets the engine's result and the person's message and answers them.
     * Null on any failure or an unusable reply, so the app-written view below it still stands on its own.
     */
    private suspend fun coachReply(cid: String, turnId: String, s: com.drkkr.strategos.domain.model.Scenario, pr: com.drkkr.strategos.domain.analysis.PipelineResult, text: String): String? {
        val earlier = conversations.messages(cid).filter { it.role == MessageRole.USER.name }.map { it.text }.dropLast(1).takeLast(RECENT)
        val input = buildJsonArray {
            add(buildJsonObject { put("role", "system"); put("content", COACH_PROMPT) })
            add(buildJsonObject {
                put("role", "user")
                put("content", buildString {
                    append(background(cid))
                    append("WHAT THE ANALYSIS SHOWS:\n").append(CoachFacts.build(s, pr)).append('\n')
                    if (earlier.isNotEmpty()) append("EARLIER MESSAGES:\n").append(earlier.joinToString("\n") { "- " + it.take(200) }).append('\n')
                    append("THE PERSON SAYS:\n").append(text.take(MAX_USER_CHARS))
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
    private suspend fun answerFollowUp(cid: String, turnId: String, text: String, priorReply: String, reportJson: String?): String? {
        val input = buildJsonArray {
            add(buildJsonObject { put("role", "system"); put("content", ArgonPrompts.ANSWER_FOLLOWUP_PROMPT) })
            add(buildJsonObject {
                put("role", "user")
                put("content", buildString {
                    append(background(cid))
                    append("THE PERSON ASKS:\n").append(text.take(MAX_USER_CHARS)).append("\n\n")
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

    /** Phase G.3: the ask-first reply — a short lead the controller can also detect, then the 1-2 questions. */
    private fun buildClarify(questions: List<String>): String {
        val qs = questions.map { it.trim() }.filter { it.isNotEmpty() }.take(2)
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

    private fun input(digest: JsonObject, earlier: List<String>, text: String): JsonArray = buildJsonArray {
        add(buildJsonObject { put("role", "system"); put("content", PROMPT) })
        add(buildJsonObject {
            put("role", "user")
            put("content", buildString {
                append("APP CONTEXT (what is already saved):\n").append(digest).append('\n')
                if (earlier.isNotEmpty()) append("EARLIER MESSAGES:\n").append(earlier.joinToString("\n") { "- " + it.take(200) }).append('\n')
                append("USER SAYS:\n").append(text)
            })
        })
    }

    companion object {
        private const val RECENT = 3
        private const val RETRY_PAUSE_MS = 1_500L
        private const val THREAD_USER_MESSAGES = 2
        private const val THREAD_PREVIOUS_CHARS = 420
        private const val THREAD_MOVES_WINDOW = 6
        private const val THREAD_MOVE_CHARS = 140
        private const val THREAD_SUMMARY_CHARS = 1_200
        private const val THREAD_USER_CHARS = 300
        private const val BACKGROUND_ENTRIES = 2
        private const val BACKGROUND_TOPICS = 2
        private const val BACKGROUND_LINE_CHARS = 200
        // BLUEPRINT-QUESTION: 900 was sized for Groq's 8,000 tokens/minute cap (LEAN PROFILE). On DeepSeek it cut the sketch JSON mid-array,
        // so every substantive first message failed with "Couldn't reach the coach" (found by the emulator E2E run). 3,000 fits a full sketch.
        private const val EXTRACTION_MAX_TOKENS = 3_000
        private const val EXTRACTION_TEMPERATURE = 0.1
        private const val INTERPRET_MAX_TOKENS = 600
        private const val INTERPRET_TEMPERATURE = 0.3
        private const val REPAIR_HEADER = "The saved model is incomplete. Return ONLY what is missing, in the same JSON format (people can be empty; use the same names and option wordings as the app context):"
        private const val MAX_USER_CHARS = 1_500
        private const val MIN_COACH_CHARS = 40
        // Phase G.2/G.3 (clarification router + ask-first).
        private const val MAX_PRIOR_CHARS = 1_200
        private const val MAX_DIGEST_CHARS = 1_500
        private const val MIN_FOLLOWUP_CHARS = 20
        private const val CLARIFY_MAX = 2
        const val CLARIFY_LEAD = "Before I call it, let me get this right —"

        /** COMPANION REPLY prompt (~250 tokens). The words come from the model; every fact comes from the engine's result. */
        const val COACH_PROMPT = """You are Strategos, a calm, warm companion who helps a person think through a hard personal decision. You are not a therapist and you do not diagnose.
You get WHAT THE ANALYSIS SHOWS (from the app's game-theory engine; part of it rests on guesses) and what the person said.
Write 120-170 words of plain prose. No lists, headings, scores or percentages. Speak to "you".
1) Answer their actual question, or reflect what they shared, in one or two sentences.
2) Connect it to the analysis: which option looks best and why, what could go wrong, who matters most.
3) Suggest one or two small, concrete things they could do or say in the next few days, about their own behaviour.
4) End with one gentle question, or invite them to share how it goes.
Use only facts given to you; never invent events or feelings. Say "may" and "could", never promise an outcome.
If they sound overwhelmed, acknowledge it kindly and suggest leaning on their clinician or someone they trust."""

        const val NOTHING_NEW = "There was nothing new in that to add to the model, so this is the same view as before. Tell me who did or said what, or what changed, and I'll update it."
        const val COULD_NOT_READ = "I couldn't turn that into options. Try telling me who is involved and what you could do."

        /** The whole prompt (~800 tokens). The reply is one JSON object; the app does everything else. */
        const val PROMPT = """You turn a person's situation into a small decision model. Reply with ONE JSON object and nothing else.
Keys: goal, people (name, role PLAYER or INFLUENCER, veto, wants, fears), options (for, label), dims, outcomes (picks, scores), limits (label, kind), ask, support.
Rules:
- List EVERY person the user mentions (partner, parents, boss, friends...), plus "You". Never skip anyone.
- "You" is the user, a PLAYER. Make the ONE other person whose choice matters most a PLAYER too (3 players at most). Everyone else is an INFLUENCER; set veto true if they can stop it.
- Give EVERY PLAYER (including the other person) 2-3 options, one of them waiting or doing nothing. Influencers have no options.
- dims: pick 3 of SAFETY, ETHICS, LEGALITY, COST, REPUTATION, RELATIONSHIPS, WELLBEING, EFFICIENCY, PUBLIC_TRUST, LONG_TERM_VALUE (COST 10 = very costly). If the app context lists dims, use exactly those.
- outcomes: one entry for EVERY combination of the players' options. In scores, give each player 3 numbers 0-10 (one per dim, in dims order) for how that combination is for them.
- limits: things that must not happen. kind is LEGAL, ETHICAL, SAFETY, POLICY, PHYSICAL or RESOURCE.
- Only fill wants, fears and limits when the user said them. Never invent them for people the user only mentions (a relative, a doctor, a friend). Never write placeholder text.
- ask: leave out unless the story is far too thin to model; then ONE short question and nothing else.
- support: true only if the CURRENT message shows self-harm, harming someone, abuse or danger now. Past panic attacks or therapy are not a reason.
- The app context shows what is saved. Do not repeat saved people or options; return only what is new or changed, with the same names. For a follow-up ("I did X and he reacted Y"), add or change what that implies, and give outcomes for any new combinations. A fact about someone's past or character ("he repaid late before") goes into that person's fears or wants, using the saved name.
Example for "My manager wants me on night shift. My partner hates that idea." (a different situation):
{"goal":"Keep my job without hurting my home life","people":[{"name":"You","role":"PLAYER","fears":"Losing sleep and time with my partner"},{"name":"Manager","role":"PLAYER","wants":"Night cover"},{"name":"Partner","role":"INFLUENCER","veto":true,"fears":"Never seeing me"}],"options":[{"for":"You","label":"Accept nights"},{"for":"You","label":"Refuse"},{"for":"Manager","label":"Insist"},{"for":"Manager","label":"Offer a swap"}],"dims":["WELLBEING","RELATIONSHIPS","COST"],"outcomes":[{"picks":{"You":"Accept nights","Manager":"Insist"},"scores":{"You":[3,4,5],"Manager":[8,6,2]}},{"picks":{"You":"Accept nights","Manager":"Offer a swap"},"scores":{"You":[5,5,4],"Manager":[7,7,3]}},{"picks":{"You":"Refuse","Manager":"Insist"},"scores":{"You":[6,5,7],"Manager":[3,3,6]}},{"picks":{"You":"Refuse","Manager":"Offer a swap"},"scores":{"You":[8,8,3],"Manager":[6,7,4]}}],"limits":[{"label":"No night work during my partner's treatment","kind":"ETHICAL"}]}"""
    }
}
