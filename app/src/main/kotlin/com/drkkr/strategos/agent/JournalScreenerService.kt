package com.drkkr.strategos.agent

import com.drkkr.strategos.data.db.entities.JournalInsightPostNoteEntity
import com.drkkr.strategos.data.repo.JournalEntryView
import com.drkkr.strategos.data.repo.JournalRepository
import com.drkkr.strategos.data.repo.ScenarioRepository
import com.drkkr.strategos.data.repo.TrajectoryRepository
import com.drkkr.strategos.domain.agent.ArgonPrompts
import com.drkkr.strategos.domain.agent.SafetyKeywordScreen
import com.drkkr.strategos.domain.agent.ShortIds
import com.drkkr.strategos.llm.LlmClient
import com.drkkr.strategos.llm.LlmRequest
import com.drkkr.strategos.llm.LlmResult
import com.drkkr.strategos.llm.assistantText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Silent screener for Space 2. The entry is already saved when this runs; nothing here can lose it or show an error.
 * Any failure (offline, 429, bad JSON) leaves the entry PENDING_SCREEN for [retryPendingScreens]. It never logs entry text.
 */
class JournalScreenerService(
    private val llm: LlmClient,
    private val journal: JournalRepository,
    private val scenarios: ScenarioRepository,
    private val trajectory: TrajectoryRepository,
    private val settings: suspend () -> Pair<Boolean, String>,    // (AI on, model)
    /** Caller-supplied so tests can use a test dispatcher; production passes `CoroutineScope(Dispatchers.IO + SupervisorJob())`. */
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis
) {
    @Serializable
    private data class Reply(
        @SerialName("mood_tag") val moodTag: String? = null,
        val impacts: List<Impact> = emptyList()
    )

    @Serializable
    private data class Impact(
        @SerialName("scenario_id") val scenarioId: String = "",
        val significance: String = "",
        @SerialName("habit_or_trigger") val habitOrTrigger: String = "",
        @SerialName("insight_headline") val insightHeadline: String = "",
        @SerialName("practical_approach") val practicalApproach: String = "",
        @SerialName("do_this") val doThis: String = "",
        @SerialName("dont_do_this") val dontDoThis: String = "",
        @SerialName("target_dimension") val targetDimension: String? = null,
        @SerialName("weight_delta") val weightDelta: Double = 0.0
    )

    fun screenAsync(entryId: String): Job = scope.launch { screen(entryId) }

    /** True when the entry ended up SCREENED. */
    suspend fun screen(entryId: String): Boolean {
        val (aiOn, model) = settings()
        if (!aiOn) return false                                         // AI off: zero network calls
        val entry = journal.entry(entryId) ?: return false
        // Phase G.5 (#2): a provably-trivial entry — empty, a single word, an emoji, or a bare mood tag — carries
        // nothing the screener could link to a project, so skip the model call and mark it screened. A crisis signal is
        // NEVER trivial: SafetyKeywordScreen.check() forces a screen so this cost optimization can never gate safety.
        // (Crisis is already caught locally at record time in JournalRepository, so this is defense in depth.)
        if (isTrivial(entry.text) && !SafetyKeywordScreen.check(entry.text)) {
            journal.markScreened(entry.id, entry.moodTag)
            return true
        }
        return try {
            val r = llm.create(
                LlmRequest(
                    model = model, input = input(entry), tools = null, maxOutputTokens = MAX_OUTPUT_TOKENS,
                    purpose = "JOURNAL", jsonObjectMode = true
                )
            )
            val reply = (r as? LlmResult.Ok)?.let { parse(it.output.assistantText()) } ?: return false
            apply(entry, reply)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /** Up to [JournalRepository.MAX_RETRIES] queued entries, oldest first. Returns how many were screened. */
    suspend fun retryPendingScreens(): Int = journal.pendingScreens().count { screen(it.id) }

    // Phase G.5 (#1): ordered so the provider's prefix cache covers the two STATIC leading messages — the screener
    // prompt (identical every call) and the active-projects roster (stable while the user's projects don't change) —
    // while the one VARIABLE part, the entry text, comes last so it never sits inside the cached prefix.
    private fun input(entry: JournalEntryView) = buildJsonArray {
        add(buildJsonObject { put("role", "system"); put("content", ArgonPrompts.JOURNAL_SCREENER_PROMPT) })
        add(buildJsonObject { put("role", "system"); put("content", "active_projects:\n" + rosterJson()) })
        add(buildJsonObject { put("role", "user"); put("content", buildJsonObject { put("entry", entry.text) }.toString()) })
    }

    /** The active-projects roster shown to the screener, with the fields it needs to tell projects apart. Stable across entries. */
    private fun rosterJson(): String = buildJsonArray {
        for (s in scenarios.scenarios.value.take(MAX_PROJECTS)) add(buildJsonObject {
            put("scenario_id", ShortIds.short(s.id)); put("title", s.title); put("stakes", s.stakes.take(120))
            put("players", s.players().take(2).joinToString(", ") { it.name })
        })
    }.toString()

    /** Phase G.5 (#2): fewer than two letter-bearing words — empty, a lone word, an emoji, a bare mood tag — is nothing to screen. */
    private fun isTrivial(text: String): Boolean =
        text.trim().split(Regex("\\s+")).count { w -> w.any(Char::isLetter) } < MIN_SCREEN_WORDS

    private fun parse(text: String): Reply? {
        val start = text.indexOf('{'); val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSON.decodeFromString(Reply.serializer(), text.substring(start, end + 1)) }.getOrNull()
    }

    private suspend fun apply(entry: JournalEntryView, reply: Reply) {
        val ids = scenarios.scenarios.value.map { it.id }
        for (i in reply.impacts) {
            if (i.significance.uppercase() !in SIGNIFICANT) continue
            val sid = ShortIds.expand(i.scenarioId, ids)?.takeIf { it in ids } ?: continue
            if (i.doThis.isBlank() && i.dontDoThis.isBlank()) continue
            journal.addPostNote(
                JournalInsightPostNoteEntity(
                    id = UUID.randomUUID().toString(), journalEntryId = entry.id, scenarioId = sid, significance = i.significance.uppercase(),
                    habitOrTrigger = i.habitOrTrigger, insightHeadline = headline(i.insightHeadline), practicalApproach = i.practicalApproach,
                    doThis = i.doThis, dontDoThis = i.dontDoThis, targetDimension = i.targetDimension,
                    weightDelta = i.weightDelta.coerceIn(-2.0, 2.0), createdAt = now()
                )
            )
            trajectory.recordJournalShift(sid)
        }
        journal.markScreened(entry.id, reply.moodTag?.uppercase()?.takeIf { it in MOODS } ?: entry.moodTag)
    }

    private fun headline(h: String) = if (h.startsWith(PREFIX, ignoreCase = true)) h else "$PREFIX ${h.trim()}"

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
        private val SIGNIFICANT = setOf("HIGH", "MODERATE")
        private val MOODS = setOf("CALM", "ANXIOUS", "FRUSTRATED", "HOPEFUL", "DRAINED", "NEUTRAL")
        private const val PREFIX = "Based on your journal entry:"
        private const val MAX_OUTPUT_TOKENS = 600
        private const val MAX_PROJECTS = 8
        private const val MIN_SCREEN_WORDS = 2
    }
}
