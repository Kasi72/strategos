package com.drkkr.strategos.agent

import com.drkkr.strategos.data.repo.ConversationRepository
import com.drkkr.strategos.data.repo.MessageRole
import com.drkkr.strategos.data.repo.SettingsRepository
import com.drkkr.strategos.data.repo.UiKind
import com.drkkr.strategos.data.repo.UiMode
import com.drkkr.strategos.llm.LlmClient
import com.drkkr.strategos.llm.LlmErrors
import com.drkkr.strategos.llm.LlmRequest
import com.drkkr.strategos.llm.LlmResult
import com.drkkr.strategos.llm.assistantText
import com.drkkr.strategos.llm.functionCallOutput
import com.drkkr.strategos.llm.functionCalls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

sealed class TurnTrigger {
    data class User(val text: String) : TurnTrigger()
    data class Event(val text: String) : TurnTrigger()
    // BLUEPRINT-QUESTION: not in §10.2. Re-runs from the last USER/EVENT message without appending it again (retryLastTurn, TC_ORCH_08).
    data object Retry : TurnTrigger()
}

sealed class TurnResult {
    data object Done : TurnResult()
    data class Failed(val userMessage: String, val retryable: Boolean) : TurnResult()
    data object Cancelled : TurnResult()
}

/**
 * Runs one agent turn: the tool loop of blueprint §10.2.
 * The LLM client writes the llm_call rows itself, so there is no separate call log here.
 */
class AgentOrchestrator(
    private val llm: LlmClient,
    private val tools: () -> JsonArray,
    private val dispatcher: ToolDispatcher,
    private val assembler: ContextAssembler,
    private val conversations: ConversationRepository,
    private val settings: SettingsRepository,
    private val afterTurn: suspend (conversationId: String) -> Unit = {},
    private val backgroundScope: CoroutineScope? = null
) {
    suspend fun runTurn(
        conversationId: String, scenarioId: String, trigger: TurnTrigger,
        ctxFactory: suspend (turnId: String) -> ToolContext, onActivity: (String) -> Unit
    ): TurnResult {
        val turnId = UUID.randomUUID().toString()
        when (trigger) {
            is TurnTrigger.User -> conversations.append(conversationId, turnId, MessageRole.USER, trigger.text)
            is TurnTrigger.Event -> conversations.append(conversationId, turnId, MessageRole.EVENT, trigger.text)
            TurnTrigger.Retry -> Unit
        }
        return try {
            withTimeoutOrNull(AgentConfig.MAX_TURN_MS) { loop(conversationId, scenarioId, turnId, ctxFactory, onActivity) }
                ?: fail(conversationId, turnId, TIMEOUT_TEXT, retryable = true)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { conversations.append(conversationId, turnId, MessageRole.EVENT, STOPPED_TEXT) }
            throw e
        }
    }

    private suspend fun loop(
        conversationId: String, scenarioId: String, turnId: String,
        ctxFactory: suspend (String) -> ToolContext, onActivity: (String) -> Unit
    ): TurnResult {
        val model = settings.model()
        val toolList = tools().let { all ->
            if (settings.uiMode() != UiMode.GUIDED) all
            else JsonArray(all.filter { ((it as? JsonObject)?.get("name") as? JsonPrimitive)?.content in AgentConfig.GUIDED_TOOLS })
        }
        val input = assembler.build(conversationId, scenarioId).toMutableList<JsonElement>()
        var toolChoice = "auto"
        var toolsRan = false

        for (round in 0..AgentConfig.MAX_TOOL_ROUNDS) {
            if (round == AgentConfig.MAX_TOOL_ROUNDS) toolChoice = "none"
            val request = LlmRequest(model, JsonArray(input), toolList, toolChoice, purpose = "TURN", conversationId = conversationId, turnId = turnId)
            var r = llm.create(request)
            // LEAN PROFILE: the provider rejects a response whose tool call doesn't match the schema; a second try usually works.
            if (r.isBadToolCall()) r = llm.create(request)
            if (r is LlmResult.Unauthorized) settings.setKeyInvalid(true)
            if (r !is LlmResult.Ok) {
                // The tools already ran and their cards are on screen; a failed wrap-up sentence isn't worth an error.
                if (toolChoice == "none" && toolsRan) break
                return fail(conversationId, turnId, LlmErrors.userMessage(r), retryable = r !is LlmResult.Unauthorized)
            }
            val calls = r.output.functionCalls()
            if (calls.isEmpty() || toolChoice == "none") {
                conversations.append(conversationId, turnId, MessageRole.ASSISTANT, r.output.assistantText().ifBlank { NO_REPLY })
                break
            }
            input += echo(r.output, AgentConfig.ECHO_STRATEGY)
            val ctx = ctxFactory(turnId)
            var endsTurn = false
            var endedByCard = true
            toolsRan = true
            for (c in calls) {
                conversations.append(conversationId, turnId, MessageRole.TOOL_CALL, toolName = c.name, callId = c.callId, payloadJson = c.arguments)
                onActivity(PROGRESS_LABELS[c.name] ?: "Working…")
                val o = dispatcher.dispatch(c.name, c.arguments, ctx)
                val msg = conversations.append(
                    conversationId, turnId, MessageRole.TOOL_RESULT, text = o.activityLabel, toolName = c.name, callId = c.callId,
                    payloadJson = o.output.toString(), uiKind = o.uiKind, refId = o.refId
                )
                // ask_user, run_analysis and the Guided cards point at their own TOOL_RESULT message (§10.2).
                if (o.uiKind != UiKind.NONE && o.refId == null) conversations.setRefId(msg.id, msg.id)
                input += functionCallOutput(c.callId, o.output)
                if (o.endsTurn) {
                    endsTurn = true
                    endedByCard = endedByCard && o.uiKind in CARD_KINDS
                }
            }
            // LEAN PROFILE: a question, recap or support card is itself the reply; skip the extra text-only request.
            if (endsTurn && endedByCard) break
            if (endsTurn) toolChoice = "none"
        }
        backgroundScope?.launch { runCatching { afterTurn(conversationId) } }
        return TurnResult.Done
    }

    private fun LlmResult.isBadToolCall() = this is LlmResult.HttpError && status == 400 && code == "tool_use_failed"

    private suspend fun fail(conversationId: String, turnId: String, text: String, retryable: Boolean): TurnResult {
        conversations.append(conversationId, turnId, MessageRole.ERROR, text)
        return TurnResult.Failed(text, retryable)
    }

    private fun echo(output: JsonArray, strategy: EchoStrategy): List<JsonElement> = when (strategy) {
        EchoStrategy.VERBATIM -> output.toList()
        EchoStrategy.STRIP_IDS -> output.map { item -> (item as? JsonObject)?.let { JsonObject(it - "id") } ?: item }
        EchoStrategy.CALLS_ONLY -> output.filter { ((it as? JsonObject)?.get("type") as? JsonPrimitive)?.content == "function_call" }
    }

    companion object {
        const val STOPPED_TEXT = "Stopped."

        /** Results whose card is the turn's reply (LEAN PROFILE): a question, a recap or the support card. */
        val CARD_KINDS = setOf(UiKind.QUESTIONS, UiKind.UNDERSTANDING, UiKind.SUPPORT)
        const val TIMEOUT_TEXT = "That took too long. Try again."
        const val NO_REPLY = "(no reply)"

        val PROGRESS_LABELS = mapOf(
            "get_scenario" to "Reading the model…",
            "propose_changes" to "Preparing changes…",
            "ask_user" to "Writing a question…",
            "run_analysis" to "Running the analysis on your device…",
            "explain_framework" to "Looking up a framework…",
            "what_if" to "Simulating…",
            "stress_test_inferred" to "Stress-testing AI estimates…",
            "update_memory" to "Updating memory…",
            "show_understanding" to "Writing a recap…",
            "show_support" to "Showing support contacts…"
        )
    }
}
