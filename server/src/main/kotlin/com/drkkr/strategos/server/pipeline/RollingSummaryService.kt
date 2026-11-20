package com.drkkr.strategos.server.pipeline

import com.drkkr.strategos.server.llm.ChatMessage
import com.drkkr.strategos.server.llm.ChatRequest
import com.drkkr.strategos.server.llm.ChatResult
import com.drkkr.strategos.server.llm.LlmProvider
import com.drkkr.strategos.server.persistence.ThreadRepository
import com.drkkr.strategos.server.persistence.TurnRepository

/**
 * Every [EVERY_TURNS] turns, folds the turns since the last summary into the thread's four-part digest (SDD §3.2), so a thread of
 * 200 turns still fits the budget. On any failure the old summary stays; the next run picks up from the same place.
 */
class RollingSummaryService(
    private val llm: LlmProvider,
    private val threads: ThreadRepository,
    private val turns: TurnRepository,
) {
    suspend fun update(threadId: String): String? {
        val thread = threads.get(threadId) ?: return null
        val fresh = turns.forThread(threadId).filter { it.turnIndex >= thread.summarizedThrough }
        if (fresh.isEmpty()) return thread.rollingSummary
        val content = buildString {
            append("PREVIOUS SUMMARY:\n").append(thread.rollingSummary.ifBlank { "(none yet)" }).append("\n\nNEW TURNS (oldest first):\n")
            fresh.forEach { t ->
                append("- [turn ${t.turnIndex}] Person: ").append(t.userMessage.take(1_500).replace('\n', ' ')).append('\n')
                append("  Strategos: ").append(t.assistantReply.take(400).replace('\n', ' ')).append('\n')
            }
        }
        val r = llm.chat(ChatRequest(listOf(ChatMessage("system", PROMPT), ChatMessage("user", content)),
            maxTokens = 500, temperature = 0.1, purpose = "SUMMARY")) as? ChatResult.Ok ?: return null
        val summary = r.text.trim().takeIf { s -> SECTIONS.all { s.contains("$it:") } } ?: return null
        threads.saveSummary(threadId, summary, fresh.last().turnIndex + 1)
        return summary
    }

    companion object {
        const val EVERY_TURNS = 6
        val SECTIONS = listOf("SITUATION", "ENGINE_ADVISED", "USER_TRIED", "OPEN")
        const val PROMPT = """You keep the running summary of one coaching thread. Merge the previous summary with the new turns.
Write exactly four short sections, each starting on its own line with its label:
SITUATION: who is involved, what they want and fear, and what is settled (agreed, confirmed or already done). Keep dates.
ENGINE_ADVISED: the moves Strategos advised, newest last.
USER_TRIED: what the person actually did and how the other side reacted.
OPEN: what is still undecided or unknown.
Facts only, in plain words, at most 180 words in all. Never invent anything. When something changed, keep only the newest state and say when it changed."""
    }
}
