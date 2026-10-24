package com.drkkr.strategos.agent

import com.drkkr.strategos.data.db.entities.MessageEntity
import com.drkkr.strategos.data.repo.ChangeSetRepository
import com.drkkr.strategos.data.repo.ConversationRepository
import com.drkkr.strategos.data.repo.JournalRepository
import com.drkkr.strategos.data.repo.MemoryKind
import com.drkkr.strategos.data.repo.TrajectoryRepository
import com.drkkr.strategos.domain.agent.TokenEstimator
import com.drkkr.strategos.data.repo.MemoryRepository
import com.drkkr.strategos.data.repo.MessageRole
import com.drkkr.strategos.data.repo.SettingsRepository
import com.drkkr.strategos.domain.agent.IntakeChecklist
import com.drkkr.strategos.domain.agent.LanguageDetector
import com.drkkr.strategos.domain.agent.LiveDigest
import com.drkkr.strategos.domain.agent.ShortIds
import com.drkkr.strategos.domain.model.Scenario
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.LocalDate

/**
 * Builds the model input for one request (blueprint §10.3). The order is fixed so the prefix stays cache-stable:
 * system prompt, rolling summary, older history, live app context, newest USER/EVENT message.
 */
class ContextAssembler(
    private val systemPrompt: () -> String,
    private val conversations: ConversationRepository,
    private val changeSets: ChangeSetRepository,
    private val memory: MemoryRepository,
    private val settings: SettingsRepository,
    private val scenarioFor: (scenarioId: String) -> Scenario?,
    private val today: () -> LocalDate = LocalDate::now,
    /** Argon: with [trajectory] set the request uses the 4-layer prefix-cached order and the token budget. */
    private val journal: JournalRepository? = null,
    private val trajectory: TrajectoryRepository? = null
) {
    // BLUEPRINT-QUESTION: §10.3 tracks the last-sent version on CounselController; it lives here because the assembler
    // is the only reader. Defaults to "changed" on the first turn of each conversation.
    private val lastSentVersion = mutableMapOf<String, Int>()

    suspend fun build(conversationId: String, scenarioId: String): JsonArray =
        if (trajectory != null) buildLayered(conversationId, scenarioId, trajectory) else buildLegacy(conversationId, scenarioId)

    /**
     * Argon order, most static first so the provider's prefix cache keeps hitting:
     * 1 system prompt, 2 project + trajectory + journal notes + sibling threads, 3 rolling summary + last turns, 4 newest message.
     * Stays within [AgentConfig.MAX_REQUEST_TOKEN_BUDGET] by dropping the oldest history first, then the summary, then siblings.
     */
    private suspend fun buildLayered(conversationId: String, scenarioId: String, traj: TrajectoryRepository): JsonArray {
        val conversation = conversations.get(conversationId)
        val all = conversations.messages(conversationId)
        val newest = all.lastOrNull { it.role == MessageRole.USER.name || it.role == MessageRole.EVENT.name }
        var turns = all.filter { it.seq > (conversation?.summarizedThroughSeq ?: 0) && it.id != newest?.id }
            .mapNotNull(::toInput).takeLast(RECENT_MESSAGES)
        var summary = conversation?.rollingSummary?.takeIf { it.isNotBlank() }
        val base = liveContextObject(conversationId, scenarioId, scenarioFor(scenarioId), newest, conversation?.let { it.lastReportDigestJson to it.lastReportScenarioVersion })
        val snap = traj.current(scenarioId)
        val notes = journal?.activePostNotes(scenarioId).orEmpty().filter { !it.applied }.take(MAX_POST_NOTES)
        var siblings = conversations.listThreads(scenarioId).filter { it.id != conversationId }.take(MAX_SIBLINGS).map { t ->
            val gist = t.rollingSummary.takeIf { it.isNotBlank() }
                ?: conversations.messages(t.id).lastOrNull { it.role == MessageRole.USER.name }?.text.orEmpty()
            t.title to gist.take(SIBLING_CHARS)
        }
        fun assemble() = buildJsonArray {
            add(message("system", systemPrompt()))
            add(message(AgentConfig.CONTEXT_ROLE, "$HEADER\n```json\n" + buildJsonObject {
                base.forEach { (k, v) -> put(k, v) }
                putJsonObject("trajectory") {
                    put("trust", snap.trustScore); put("tension", snap.tensionScore); put("sigmoid_progress", snap.sigmoidProgress)
                    put("streak", snap.consistencyStreak); put("phase_label", snap.phaseLabel)
                }
                putJsonArray("active_journal_post_notes") {
                    notes.forEach { n -> addJsonObject { put("habit_or_trigger", n.habitOrTrigger); put("do_this", n.doThis); put("dont_do_this", n.dontDoThis) } }
                }
                putJsonArray("sibling_thread_summaries") { siblings.forEach { (title, gist) -> addJsonObject { put("title", title); put("summary", gist) } } }
            } + "\n```"))
            summary?.let { add(message("system", "Summary of earlier conversation:\n$it")) }
            turns.forEach { add(it) }
            newest?.let { toInput(it) }?.let { add(it) }
        }
        fun tokens(a: JsonArray) = a.sumOf { TokenEstimator.estimate(content(it)) }
        var out = assemble()
        while (tokens(out) > AgentConfig.MAX_REQUEST_TOKEN_BUDGET) {
            when {
                turns.isNotEmpty() -> turns = turns.drop(1)
                summary != null -> summary = null
                siblings.isNotEmpty() -> siblings = siblings.dropLast(1)
                else -> break
            }
            out = assemble()
        }
        return out
    }

    private fun content(o: JsonElement) = ((o as JsonObject)["content"] as? JsonPrimitive)?.content.orEmpty()

    private suspend fun buildLegacy(conversationId: String, scenarioId: String): JsonArray {
        val conversation = conversations.get(conversationId)
        val all = conversations.messages(conversationId)
        val newest = all.lastOrNull { it.role == MessageRole.USER.name || it.role == MessageRole.EVENT.name }
        val history = all.filter { it.seq > (conversation?.summarizedThroughSeq ?: 0) && it.id != newest?.id }
            .mapNotNull(::toInput)
        val scenario = scenarioFor(scenarioId)

        return buildJsonArray {
            add(message("system", systemPrompt()))
            conversation?.rollingSummary?.takeIf { it.isNotBlank() }?.let {
                add(message("system", "Summary of earlier conversation:\n$it"))
            }
            history.forEach { add(it) }
            add(message(AgentConfig.CONTEXT_ROLE, "$HEADER\n```json\n" + liveContextObject(conversationId, scenarioId, scenario, newest, conversation?.let {
                it.lastReportDigestJson to it.lastReportScenarioVersion
            }) + "\n```"))
            newest?.let { toInput(it) }?.let { add(it) }
        }
    }

    private suspend fun liveContextObject(
        conversationId: String, scenarioId: String, scenario: Scenario?, newest: MessageEntity?,
        lastReport: Pair<String?, Int?>?
    ): JsonObject {
        val changed = scenario == null || lastSentVersion[conversationId] != scenario.version
        scenario?.let { lastSentVersion[conversationId] = it.version }
        val reportJson = lastReport?.first?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
        val uiMode = settings.uiMode().name.lowercase()
        val pending = changeSets.pending(scenarioId)
        val openMemory = memory.open(scenarioId)
        val items = if (settings.includeMemory()) openMemory else null
        val limitsAnswered = openMemory.any { it.kind == MemoryKind.FACT.name && it.text.contains(IntakeChecklist.LIMITS_NONE_TAG) }
        val gaps = scenario?.let { IntakeChecklist.gaps(it, limitsAnswered).take(MAX_GAPS) }.orEmpty()

        val context = buildJsonObject {
            put("now", today().toString())
            put("app_language_hint", newest?.text?.takeIf { it.isNotBlank() }?.let(LanguageDetector::detect) ?: scenario?.language ?: "en")
            put("ui_mode", uiMode)
            putJsonArray("intake_gaps") {
                gaps.forEach { g -> addJsonObject { put("id", g.id); put("about", g.about); put("hint", g.hint) } }
            }
            put("scenario", scenario?.let(LiveDigest::build) ?: JsonNull)
            put("scenario_changed_since_last_turn", changed)
            putJsonArray("pending_change_sets") {
                pending.forEach { cs ->
                    addJsonObject {
                        put("id", ShortIds.short(cs.id)); put("summary", cs.summary)
                        put("op_count", runCatching { Json.parseToJsonElement(cs.opsJson).jsonArray.size }.getOrDefault(0))
                    }
                }
            }
            if (items != null) {
                putJsonObject("memory") {
                    putJsonArray("facts") {
                        items.filter { it.kind == MemoryKind.FACT.name }.takeLast(MAX_MEMORY).forEach { m ->
                            addJsonObject { put("id", ShortIds.short(m.id)); put("text", m.text.take(120)); put("status", m.factStatus) }
                        }
                    }
                    for ((key, kind) in listOf("open_questions" to MemoryKind.OPEN_QUESTION, "preferences" to MemoryKind.PREFERENCE, "decision_notes" to MemoryKind.DECISION_NOTE)) {
                        putJsonArray(key) {
                            items.filter { it.kind == kind.name }.takeLast(MAX_MEMORY).forEach { m ->
                                addJsonObject { put("id", ShortIds.short(m.id)); put("text", m.text.take(120)) }
                            }
                        }
                    }
                }
            }
            put("last_analysis", reportJson?.let(::analysisLine) ?: JsonNull)
            put("last_analysis_stale", reportJson != null && scenario != null && lastReport?.second != scenario.version)
        }
        return context
    }

    /** LEAN PROFILE: one line from the stored ReportDigest; the full digest stays in the RECOMMENDATION card. */
    private fun analysisLine(report: JsonElement): JsonObject {
        val o = report as? JsonObject ?: return JsonObject(emptyMap())
        val recommended = (o["pathways"] as? JsonArray)?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { (it["label"] as? JsonPrimitive)?.content == "Recommended" }?.get("action")
        val share = (o["provenance"] as? JsonObject)?.get("ai_share")
        return buildJsonObject {
            o["executive_status"]?.let { put("status", it) }
            recommended?.let { put("recommended", it) }
            share?.let { put("ai_share", it) }
        }
    }

    private fun toInput(m: MessageEntity): JsonObject? = when (m.role) {
        MessageRole.USER.name -> message("user", m.text)
        MessageRole.ASSISTANT.name -> message("assistant", m.text)
        MessageRole.EVENT.name -> message("user", EVENT_PREFIX + m.text)
        // LEAN PROFILE: a turn that ends on a card has no assistant text, so the agent's own question or recap goes
        // into history; otherwise it can't see what it already asked.
        MessageRole.TOOL_CALL.name -> askedText(m.payloadJson)?.let { message("assistant", it) }
        else -> null    // TOOL_RESULT and ERROR: the live context carries current state
    }

    private fun askedText(argsJson: String?): String? {
        val args = argsJson?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return null
        fun str(e: JsonElement?) = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
        (args["ask"] as? JsonObject)?.let { return str(it["text"]) }
        (args["questions"] as? JsonArray)?.mapNotNull { str((it as? JsonObject)?.get("text")) }?.takeIf { it.isNotEmpty() }?.let { return it.joinToString("\n") }
        (args["bullets"] as? JsonArray)?.mapNotNull(::str)?.takeIf { it.isNotEmpty() }?.let { return "Recap: " + it.joinToString("; ") }
        return null
    }

    private fun message(role: String, content: String) = buildJsonObject { put("role", role); put("content", content) }

    companion object {
        const val HEADER = "STRATEGOS APP CONTEXT (generated by the app, authoritative, not written by the user)"
        const val EVENT_PREFIX = "[App event — not typed by the user] "
        const val MAX_GAPS = 5
        const val MAX_MEMORY = 5
        const val RECENT_MESSAGES = 6        // last 3 user/assistant turns
        const val MAX_POST_NOTES = 2
        const val MAX_SIBLINGS = 3
        const val SIBLING_CHARS = 140
    }
}
