package com.drkkr.strategos.agent

import com.drkkr.strategos.data.db.entities.MessageEntity
import com.drkkr.strategos.data.repo.ChangeSetRepository
import com.drkkr.strategos.data.repo.ConversationRepository
import com.drkkr.strategos.data.repo.MemoryKind
import com.drkkr.strategos.data.repo.MemoryRepository
import com.drkkr.strategos.data.repo.MessageRole
import com.drkkr.strategos.data.repo.SettingsRepository
import com.drkkr.strategos.domain.agent.IntakeChecklist
import com.drkkr.strategos.domain.agent.LanguageDetector
import com.drkkr.strategos.domain.agent.ScenarioDigest
import com.drkkr.strategos.domain.model.Scenario
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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
    private val today: () -> LocalDate = LocalDate::now
) {
    // BLUEPRINT-QUESTION: §10.3 tracks the last-sent version on CounselController; it lives here because the assembler
    // is the only reader. Defaults to "changed" on the first turn of each conversation.
    private val lastSentVersion = mutableMapOf<String, Int>()

    suspend fun build(conversationId: String, scenarioId: String): JsonArray {
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
            add(message(AgentConfig.CONTEXT_ROLE, liveContext(conversationId, scenarioId, scenario, newest, conversation?.let {
                it.lastReportDigestJson to it.lastReportScenarioVersion
            })))
            newest?.let { toInput(it) }?.let { add(it) }
        }
    }

    private suspend fun liveContext(
        conversationId: String, scenarioId: String, scenario: Scenario?, newest: MessageEntity?,
        lastReport: Pair<String?, Int?>?
    ): String {
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
            put("scenario", scenario?.let(ScenarioDigest::build) ?: JsonNull)
            put("scenario_changed_since_last_turn", changed)
            putJsonArray("pending_change_sets") {
                pending.forEach { cs ->
                    addJsonObject {
                        put("id", cs.id); put("summary", cs.summary)
                        put("op_count", runCatching { Json.parseToJsonElement(cs.opsJson).jsonArray.size }.getOrDefault(0))
                    }
                }
            }
            if (items != null) {
                putJsonObject("memory") {
                    putJsonArray("facts") {
                        items.filter { it.kind == MemoryKind.FACT.name }.forEach { m ->
                            addJsonObject { put("id", m.id); put("text", m.text); put("status", m.factStatus) }
                        }
                    }
                    for ((key, kind) in listOf("open_questions" to MemoryKind.OPEN_QUESTION, "preferences" to MemoryKind.PREFERENCE, "decision_notes" to MemoryKind.DECISION_NOTE)) {
                        putJsonArray(key) {
                            items.filter { it.kind == kind.name }.forEach { m -> addJsonObject { put("id", m.id); put("text", m.text) } }
                        }
                    }
                }
            }
            put("last_analysis", reportJson ?: JsonNull)
            put("last_analysis_stale", reportJson != null && scenario != null && lastReport?.second != scenario.version)
        }
        return "$HEADER\n```json\n$context\n```"
    }

    private fun toInput(m: MessageEntity): JsonObject? = when (m.role) {
        MessageRole.USER.name -> message("user", m.text)
        MessageRole.ASSISTANT.name -> message("assistant", m.text)
        MessageRole.EVENT.name -> message("user", EVENT_PREFIX + m.text)
        else -> null    // TOOL_CALL, TOOL_RESULT and ERROR: the live context carries current state
    }

    private fun message(role: String, content: String) = buildJsonObject { put("role", role); put("content", content) }

    companion object {
        const val HEADER = "STRATEGOS APP CONTEXT (generated by the app, authoritative, not written by the user)"
        const val EVENT_PREFIX = "[App event — not typed by the user] "
        const val MAX_GAPS = 5
    }
}
