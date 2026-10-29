package com.drkkr.strategos.data.repo

import com.drkkr.strategos.agent.AgentConfig
import com.drkkr.strategos.data.db.StrategosDatabase
import com.drkkr.strategos.data.db.entities.JournalEntryEntity
import com.drkkr.strategos.data.db.entities.JournalInsightPostNoteEntity
import com.drkkr.strategos.data.secure.TextCipher
import com.drkkr.strategos.domain.agent.SafetyKeywordScreen
import com.drkkr.strategos.domain.model.FactStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

data class JournalRecordResult(val entryId: String, val ack: String, val safetyTriggered: Boolean)

/** A journal entry with its text decrypted for display. Never log or persist this. */
data class JournalEntryView(
    val id: String, val linkedScenarioId: String?, val text: String, val moodTag: String, val syncStatus: String, val createdAt: Long
)

/** A suggestion to link an existing journal entry to a project (the Journal tab's chip). */
data class LinkSuggestion(val entryId: String, val text: String, val moodTag: String, val createdAt: Long, val scenarioId: String)

/** Space 2. Saving is one local write and never waits for the model (invariant I11). */
class JournalRepository(
    private val db: StrategosDatabase,
    private val cipher: TextCipher,
    private val memory: MemoryRepository,
    private val conversations: ConversationRepository,
    /** AI on means the entry is queued for the background screener; off means it stays RECORDED and no call is ever made. */
    private val aiEnabled: suspend () -> Boolean = { false },
    private val now: () -> Long = System::currentTimeMillis
) {
    private val dao get() = db.journalDao()

    suspend fun recordEntry(rawText: String, moodTag: String, scenarioId: String?): JournalRecordResult {
        val crisis = SafetyKeywordScreen.check(rawText)
        val id = UUID.randomUUID().toString()
        dao.insertEntry(
            JournalEntryEntity(
                id = id, linkedScenarioId = scenarioId, encryptedText = cipher.encrypt(rawText),
                moodTag = moodTag.ifBlank { "NEUTRAL" }, recordedAck = ACK,
                syncStatus = if (aiEnabled()) "PENDING_SCREEN" else "RECORDED", createdAt = now()
            )
        )
        return JournalRecordResult(id, ACK, crisis)
    }

    fun observeEntries(scenarioId: String? = null): Flow<List<JournalEntryView>> =
        dao.observeEntries(scenarioId).map { rows -> rows.map(::view) }

    /** The newest entries linked to a project, for the coach's background. Empty when nothing is linked. */
    suspend fun linkedEntries(scenarioId: String, limit: Int): List<JournalEntryView> =
        dao.allEntries().asSequence().filter { it.linkedScenarioId == scenarioId }.take(limit).map(::view).filter { it.text.isNotBlank() }.toList()

    suspend fun entry(id: String): JournalEntryView? = dao.getEntry(id)?.let(::view)

    suspend fun pendingScreens(limit: Int = MAX_RETRIES): List<JournalEntryView> = dao.pendingScreenEntries().take(limit).map(::view)

    suspend fun markScreened(id: String, moodTag: String) = dao.updateEntryStatus(id, "SCREENED", moodTag)

    suspend fun addPostNote(note: JournalInsightPostNoteEntity) = dao.insertPostNote(note)

    fun observeActivePostNotes(scenarioId: String): Flow<List<JournalInsightPostNoteEntity>> = dao.observeActivePostNotes(scenarioId)

    suspend fun activePostNotes(scenarioId: String): List<JournalInsightPostNoteEntity> = dao.activePostNotes(scenarioId)

    suspend fun dismissPostNote(id: String) = dao.dismissPostNote(id)

    /**
     * Entries the screener found relevant to a project but that the user has not linked there yet —
     * the Journal tab's "Link this entry to <project>?" chips. One per entry; already-linked ones drop out.
     */
    suspend fun linkSuggestions(scenarioId: String): List<LinkSuggestion> =
        dao.activePostNotes(scenarioId).map { it.journalEntryId }.distinct().mapNotNull { eid ->
            val e = entry(eid) ?: return@mapNotNull null
            if (e.linkedScenarioId == scenarioId) null
            else LinkSuggestion(eid, e.text, e.moodTag, e.createdAt, scenarioId)
        }

    /** "Link": attach an existing entry to a project (user-confirmed; the app never links silently). */
    suspend fun link(entryId: String, scenarioId: String) = dao.setLinkedScenario(entryId, scenarioId)

    /** "Not this": drop the suggestion to link this entry to the project, leaving the entry where it is. */
    suspend fun dismissLink(entryId: String, scenarioId: String) =
        dao.activePostNotes(scenarioId).filter { it.journalEntryId == entryId }.forEach { dao.dismissPostNote(it.id) }

    /**
     * "Apply to Playbook": marks the note applied, remembers the advice as a preference, and leaves an EVENT note in the
     * project's active thread so the next turn sees it. The weight change in `target_dimension` is not applied here.
     */
    // BLUEPRINT-QUESTION: §6.3 lists three steps and none changes the scenario's payoff weights, though R5 says journal insights should.
    suspend fun applyPostNote(id: String): Boolean {
        val note = dao.getPostNote(id) ?: return false
        dao.markPostNoteApplied(id)
        memory.add(note.scenarioId, MemoryKind.PREFERENCE, "${note.habitOrTrigger}: DO ${note.doThis} | AVOID ${note.dontDoThis}", FactStatus.USER_ESTIMATE)
        val thread = conversations.getOrCreateActive(note.scenarioId, AgentConfig.DEFAULT_MODEL, AgentConfig.ENGINE_VERSION)
        conversations.append(thread.id, UUID.randomUUID().toString(), MessageRole.EVENT, "Playbook updated: ${note.insightHeadline}")
        return true
    }

    private fun view(e: JournalEntryEntity) = JournalEntryView(
        e.id, e.linkedScenarioId, runCatching { cipher.decrypt(e.encryptedText) }.getOrDefault(""), e.moodTag, e.syncStatus, e.createdAt
    )

    companion object {
        const val ACK = "Recorded privately 🍃"
        const val MAX_RETRIES = 3
    }
}
