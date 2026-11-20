package com.drkkr.strategos.server.pipeline

import com.drkkr.strategos.domain.agent.ArgonPrompts
import com.drkkr.strategos.server.llm.ChatMessage
import com.drkkr.strategos.server.llm.ChatRequest
import com.drkkr.strategos.server.llm.ChatResult
import com.drkkr.strategos.server.llm.LlmProvider
import com.drkkr.strategos.server.memory.Episode
import com.drkkr.strategos.server.memory.FactInput
import com.drkkr.strategos.server.memory.MemoryGroups
import com.drkkr.strategos.server.memory.MemoryService
import com.drkkr.strategos.server.persistence.JournalRepository
import com.drkkr.strategos.server.persistence.PostNote
import com.drkkr.strategos.server.persistence.ProjectRepository
import kotlinx.serialization.json.*

/**
 * The silent reflection screener (SDD §4.1, app's JOURNAL_SCREENER_PROMPT): runs after "Recorded privately." has gone back.
 * A significant trigger or habit becomes a Do / Don't post-note on the projects it touches; ordinary venting changes nothing.
 * The note is also written to the person's journal namespace in memory, so patterns build up across the month.
 */
class JournalScreener(
    private val llm: LlmProvider,
    private val projects: ProjectRepository,
    private val journal: JournalRepository,
    private val memory: MemoryService,
    private val userId: String,
) {
    suspend fun screen(entryId: String): List<PostNote> {
        val entry = journal.get(entryId) ?: return emptyList()
        val active = projects.list()
        if (active.isEmpty()) return emptyList()
        val input = buildJsonObject {
            put("journal_entry", entry.content)
            putJsonArray("active_projects") {
                active.forEach { p -> addJsonObject { put("scenario_id", p.id); put("title", p.title); put("domain", p.domainType.name); put("description", p.scenario.description.take(300)) } }
            }
        }.toString()
        val r = llm.chat(ChatRequest(listOf(ChatMessage("system", ArgonPrompts.JOURNAL_SCREENER_PROMPT), ChatMessage("user", input)),
            jsonMode = true, maxTokens = 700, temperature = 0.2, purpose = "JOURNAL")) as? ChatResult.Ok ?: return emptyList()
        val root = runCatching { Json.parseToJsonElement(r.text).jsonObject }.getOrNull() ?: return emptyList()
        val notes = root["impacts"]?.jsonArray.orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str("scenario_id") ?: return@mapNotNull null
            // A short or partial id is matched to one project only; anything ambiguous is dropped.
            val project = active.singleOrNull { it.id == id } ?: active.filter { it.id.startsWith(id) }.singleOrNull() ?: return@mapNotNull null
            val doThis = o.str("do_this") ?: return@mapNotNull null
            val dont = o.str("dont_do_this") ?: return@mapNotNull null
            journal.addPostNote(project.id, entry.id, o.str("habit_or_trigger") ?: o.str("insight_headline") ?: "From your journal", doThis, dont)
        }
        journal.markScreened(entry.id, notes.map { it.projectId }.distinct(), notes.firstOrNull()?.doThis, notes.firstOrNull()?.dontDoThis, notes.isNotEmpty())
        if (notes.isNotEmpty()) {
            runCatching {
                memory.ingest(Episode(MemoryGroups.journal(userId), "journal_${entry.id}", "Reflection pattern: ${notes.first().habitOrTrigger}",
                    "Strategos private journal (screened)", entry.createdAt,
                    notes.map { FactInput("You", "pattern: ${it.habitOrTrigger}", "Do: ${it.doThis} Don't: ${it.dontDoThis}") }))
            }
        }
        return notes
    }

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
}
