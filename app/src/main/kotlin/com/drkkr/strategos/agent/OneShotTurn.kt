package com.drkkr.strategos.agent

import com.drkkr.strategos.agent.tools.Args
import com.drkkr.strategos.data.repo.ChangeSetRepository
import com.drkkr.strategos.data.repo.ConversationRepository
import com.drkkr.strategos.data.repo.MessageRole
import com.drkkr.strategos.data.repo.SettingsRepository
import com.drkkr.strategos.data.repo.TrajectoryRepository
import com.drkkr.strategos.data.repo.UiKind
import com.drkkr.strategos.domain.agent.ApplyResult
import com.drkkr.strategos.domain.agent.CoachFacts
import com.drkkr.strategos.domain.agent.ChangeSetValidator
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
import kotlinx.coroutines.CancellationException
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
    private val argon: ArgonTurn? = null
) {
    class ArgonTurn(val assembler: ContextAssembler, val trajectory: TrajectoryRepository)

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
        val scenario = gateway.activeScenario() ?: return fail(cid, turnId, "Save the scenario first.", retryable = false)
        val userTexts = conversations.messages(cid).filter { it.role == MessageRole.USER.name }.map { it.text }
        val text = userTexts.lastOrNull() ?: return TurnResult.Done

        onActivity("Reading your situation…")
        val first = if (argon != null) askModel(argon.assembler.build(cid, sid), cid, turnId, jsonMode = true)
                    else askModel(input(LiveDigest.build(scenario, forSketch = true), userTexts.dropLast(1).takeLast(RECENT), text), cid, turnId)
        val parsed = when (first) {
            is Reply.Sketch -> first.sketch
            is Reply.Failed -> return fail(cid, turnId, first.text, first.retryable)
        }
        // BLUEPRINT-QUESTION: §D.1 says a point after every turn; a reply with no deltas and no archetype says nothing new, so it adds none.
        if (argon != null && (parsed.trustDelta != 0.0 || parsed.tensionDelta != 0.0 || parsed.archetype != null)) {
            argon.trajectory.recordTurn(sid, cid, parsed.trustDelta, parsed.tensionDelta, parsed.archetype)
        }
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
            val reply = PlainResult.build(after, pr, understood, parsed.coachGuide)
            val hasGuide = parsed.coachGuide?.clearWinMove?.isNotBlank() == true
            val talk = if (coach && !hasGuide) { onActivity("Writing my reply…"); coachReply(cid, turnId, after, pr, text) } else null
            val note = if (nothingNew && talk == null) "$NOTHING_NEW\n\n" else ""
            val body = if (talk != null) "$talk\n\n$reply" else "$note$reply"
            conversations.append(cid, turnId, MessageRole.ASSISTANT, if (converted.ask != null) "$body\n\n${converted.ask}" else body)
        }
        afterTurn(cid)
        return TurnResult.Done
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

    private sealed class Reply {
        class Sketch(val sketch: ModelSketch) : Reply()
        class Failed(val text: String, val retryable: Boolean) : Reply()
    }

    /** One request; a second try when the reply isn't usable JSON. */
    private suspend fun askModel(input: JsonArray, cid: String, turnId: String, mayChangeModel: Boolean = true, jsonMode: Boolean = false): Reply {
        val model = settings.model()
        val request = LlmRequest(
            model, input, tools = null, maxOutputTokens = AgentConfig.ONE_SHOT_MAX_OUTPUT_TOKENS,
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
                        return askModel(input, cid, turnId, mayChangeModel = false, jsonMode = jsonMode)
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
        private const val REPAIR_HEADER = "The saved model is incomplete. Return ONLY what is missing, in the same JSON format (people can be empty; use the same names and option wordings as the app context):"
        private const val MAX_USER_CHARS = 1_500
        private const val MIN_COACH_CHARS = 40

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
- Only fill wants, fears and limits when the user said or clearly implied them; otherwise leave them out. Never write placeholder text.
- ask: leave out unless the story is far too thin to model; then ONE short question and nothing else.
- support: true only if the user mentions self-harm, harming someone, abuse or danger.
- The app context shows what is saved. Do not repeat saved people or options; return only what is new or changed, with the same names. For a follow-up ("I did X and he reacted Y"), add or change what that implies, and give outcomes for any new combinations. A fact about someone's past or character ("he repaid late before") goes into that person's fears or wants, using the saved name.
Example for "My manager wants me on night shift. My partner hates that idea." (a different situation):
{"goal":"Keep my job without hurting my home life","people":[{"name":"You","role":"PLAYER","fears":"Losing sleep and time with my partner"},{"name":"Manager","role":"PLAYER","wants":"Night cover"},{"name":"Partner","role":"INFLUENCER","veto":true,"fears":"Never seeing me"}],"options":[{"for":"You","label":"Accept nights"},{"for":"You","label":"Refuse"},{"for":"Manager","label":"Insist"},{"for":"Manager","label":"Offer a swap"}],"dims":["WELLBEING","RELATIONSHIPS","COST"],"outcomes":[{"picks":{"You":"Accept nights","Manager":"Insist"},"scores":{"You":[3,4,5],"Manager":[8,6,2]}},{"picks":{"You":"Accept nights","Manager":"Offer a swap"},"scores":{"You":[5,5,4],"Manager":[7,7,3]}},{"picks":{"You":"Refuse","Manager":"Insist"},"scores":{"You":[6,5,7],"Manager":[3,3,6]}},{"picks":{"You":"Refuse","Manager":"Offer a swap"},"scores":{"You":[8,8,3],"Manager":[6,7,4]}}],"limits":[{"label":"No night work during my partner's treatment","kind":"ETHICAL"}]}"""
    }
}
