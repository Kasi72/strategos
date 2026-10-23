package com.drkkr.strategos.agent

import com.drkkr.strategos.data.repo.ConversationRepository
import com.drkkr.strategos.data.repo.MessageRole
import com.drkkr.strategos.data.repo.SettingsRepository
import com.drkkr.strategos.domain.agent.TokenEstimator
import com.drkkr.strategos.llm.LlmClient
import com.drkkr.strategos.llm.LlmRequest
import com.drkkr.strategos.llm.LlmResult
import com.drkkr.strategos.llm.assistantText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Folds old history into the rolling summary once it outgrows the token budget (blueprint §10.4). Failures change nothing. */
class ConversationSummarizer(
    private val llm: LlmClient,
    private val conversations: ConversationRepository,
    private val settings: SettingsRepository
) {
    private val historyRoles = setOf(MessageRole.USER.name, MessageRole.ASSISTANT.name, MessageRole.EVENT.name)

    suspend fun maybeSummarize(conversationId: String) {
        try {
            val conversation = conversations.get(conversationId) ?: return
            val history = conversations.messages(conversationId)
                .filter { it.seq > conversation.summarizedThroughSeq && it.role in historyRoles }
            val sizes = history.map { TokenEstimator.estimate(it.text) }
            var remaining = sizes.sum()
            if (remaining <= AgentConfig.HISTORY_TOKEN_BUDGET) return

            var count = 0
            while (count < history.size - 1 && remaining > AgentConfig.HISTORY_TOKEN_TARGET) {
                remaining -= sizes[count]
                count++
            }
            val selected = history.take(count)
            if (selected.isEmpty()) return

            val transcript = selected.joinToString("\n") { "${it.role}: ${it.text}" }
            val input = buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", INSTRUCTION) })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", "Previous summary:\n${conversation.rollingSummary.ifBlank { "(none)" }}\n\nMessages:\n$transcript")
                })
            }
            val r = llm.create(LlmRequest(settings.model(), input, tools = null, purpose = "SUMMARY", conversationId = conversationId))
            val text = (r as? LlmResult.Ok)?.output?.assistantText()?.takeIf { it.isNotBlank() } ?: return
            conversations.saveSummary(conversationId, text, selected.last().seq)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Retried after the next turn.
        }
    }

    companion object {
        const val INSTRUCTION = "Update the running summary of a decision-support conversation. Max 250 words. " +
            "Keep the user's stated facts verbatim in quotes. List open questions. Do not add anything not in the messages."
    }
}
