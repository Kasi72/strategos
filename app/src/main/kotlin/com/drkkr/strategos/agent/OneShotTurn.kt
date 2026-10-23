package com.drkkr.strategos.agent

import com.drkkr.strategos.agent.tools.Args
import com.drkkr.strategos.data.repo.ChangeSetRepository
import com.drkkr.strategos.data.repo.ConversationRepository
import com.drkkr.strategos.data.repo.MessageRole
import com.drkkr.strategos.data.repo.SettingsRepository
import com.drkkr.strategos.data.repo.UiKind
import com.drkkr.strategos.domain.agent.ApplyResult
import com.drkkr.strategos.domain.agent.ChangeSetValidator
import com.drkkr.strategos.domain.agent.LiveDigest
import com.drkkr.strategos.domain.agent.ModelSketch
import com.drkkr.strategos.domain.agent.OpNormalizer
import com.drkkr.strategos.domain.agent.PlainResult
import com.drkkr.strategos.domain.agent.ReportDigest
import com.drkkr.strategos.domain.agent.SketchConverter
import com.drkkr.strategos.domain.agent.SketchParser
import com.drkkr.strategos.domain.framework.ModuleRunStatus
import com.drkkr.strategos.llm.LlmClient
import com.drkkr.strategos.llm.LlmErrors
import com.drkkr.strategos.llm.LlmRequest
import com.drkkr.strategos.llm.LlmResult
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
    private val afterTurn: suspend (conversationId: String) -> Unit = {}
) {
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

    private suspend fun turn(cid: String, sid: String, turnId: String, onActivity: (String) -> Unit): TurnResult {
        val scenario = gateway.activeScenario() ?: return fail(cid, turnId, "Save the scenario first.", retryable = false)
        val messages = conversations.messages(cid)
        val userTexts = messages.filter { it.role == MessageRole.USER.name }.map { it.text }
        val text = userTexts.lastOrNull() ?: return TurnResult.Done

        onActivity("Reading your situation…")
        val request = LlmRequest(
            settings.model(), input(scenario.let(LiveDigest::build), userTexts.dropLast(1).takeLast(RECENT), text), tools = null,
            maxOutputTokens = AgentConfig.ONE_SHOT_MAX_OUTPUT_TOKENS, purpose = "TURN", conversationId = cid, turnId = turnId
        )
        var sketch: ModelSketch? = null
        repeat(2) {                                       // one retry when the reply isn't usable JSON
            if (sketch != null) return@repeat
            when (val r = llm.create(request)) {
                is LlmResult.Ok -> sketch = SketchParser.parse(r.output.assistantText())
                else -> return fail(cid, turnId, LlmErrors.userMessage(r), retryable = r !is LlmResult.Unauthorized)
            }
        }
        val parsed = sketch ?: return fail(cid, turnId, COULD_NOT_READ, retryable = true)

        val converted = SketchConverter.toOps(parsed, scenario)
        if (converted.support) onSupport()
        if (converted.ops.isEmpty()) {
            conversations.append(cid, turnId, MessageRole.ASSISTANT, converted.ask ?: COULD_NOT_READ)
            return TurnResult.Done
        }

        val ops = OpNormalizer.normalize(converted.ops, scenario)
        val validated = ChangeSetValidator.validate(scenario, ops)
        if (validated.accepted.isEmpty()) return fail(cid, turnId, COULD_NOT_READ, retryable = true)
        val accepted = validated.accepted.map { it.op }
        val changeSet = changeSets.create(
            scenarioId = sid, conversationId = cid, summary = "From your message",
            opsJson = Args.encodeOps(accepted),
            refIdsJson = Json.encodeToString(MapSerializer(String.serializer(), String.serializer()), validated.refIds),
            baseScenarioVersion = scenario.version
        )
        val applied = autoApply(changeSet.id) ?: return fail(cid, turnId, "I couldn't save that. Try again.", retryable = true)
        if (applied.applied == 0) return fail(cid, turnId, COULD_NOT_READ, retryable = true)
        val understood = PlainResult.understood(accepted, scenario)

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
            val reply = PlainResult.build(after, pr, understood)
            conversations.append(cid, turnId, MessageRole.ASSISTANT, if (converted.ask != null) "$reply\n\n${converted.ask}" else reply)
        }
        afterTurn(cid)
        return TurnResult.Done
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
        const val COULD_NOT_READ = "I couldn't turn that into options. Try telling me who is involved and what you could do."

        /** The whole prompt (~600 tokens). Reply is one JSON object; the app does everything else. */
        const val PROMPT = """You turn a person's situation into a small decision model. Reply with ONE JSON object and nothing else.
{"goal":"what a good outcome looks like, short",
 "people":[{"name":"You","role":"PLAYER","veto":false,"wants":"short","fears":"short"}],
 "options":[{"for":"You","label":"short action"}],
 "dims":["WELLBEING","RELATIONSHIPS","COST"],
 "outcomes":[{"picks":{"You":"label","Brother":"label"},"scores":{"You":[0-10 per dim],"Brother":[0-10 per dim]}}],
 "limits":[{"label":"something that must not happen","kind":"ETHICAL"}],
 "ask":"ONLY if the story is too thin to model: one short question",
 "support":false}
Rules:
- "You" is the user and a PLAYER. Add the ONE other person whose choice matters most as PLAYER (3 players at most). Everyone else is INFLUENCER (veto true if they can stop it).
- Each PLAYER gets 2-3 options including waiting. Only players have options.
- dims: 3 of SAFETY, ETHICS, LEGALITY, COST, REPUTATION, RELATIONSHIPS, WELLBEING, EFFICIENCY, PUBLIC_TRUST, LONG_TERM_VALUE. COST 10 = very costly. If the app context already has dims, use exactly those, and give scores in the order listed there.
- outcomes: one entry per combination of the players' options; scores are 0-10 per dim from that person's view, in dims order.
- limits kind: LEGAL, ETHICAL, SAFETY, POLICY, PHYSICAL or RESOURCE.
- App context shows what is saved. Do not repeat people or options already there; return only what is new or changed, using the same names. For a follow-up ("I did X and he reacted Y"), add or change what that implies and give outcomes for any new combinations.
- Use the user's own words. Never invent facts. support true only if the user mentions self-harm, harm to others, abuse or danger."""
    }
}
