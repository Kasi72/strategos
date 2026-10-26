package com.drkkr.strategos.data.repo

import androidx.room.withTransaction
import com.drkkr.strategos.agent.AgentConfig
import com.drkkr.strategos.data.db.StrategosDatabase
import com.drkkr.strategos.data.db.entities.ConversationEntity
import com.drkkr.strategos.data.db.entities.MessageEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class ConversationRepository(private val db: StrategosDatabase) {

    suspend fun getOrCreateActive(scenarioId: String, modelId: String, agentVersion: String): ConversationEntity =
        db.withTransaction {
            db.conversationDao().activeFor(scenarioId) ?: run {
                val now = System.currentTimeMillis()
                ConversationEntity(
                    id = UUID.randomUUID().toString(), scenarioId = scenarioId, status = "ACTIVE",
                    modelId = modelId, agentVersion = agentVersion, createdAt = now, updatedAt = now
                ).also { db.conversationDao().upsert(it) }
            }
        }

    suspend fun listThreads(scenarioId: String): List<ConversationEntity> = db.conversationDao().listThreadsForScenario(scenarioId)

    fun observeThreads(scenarioId: String): Flow<List<ConversationEntity>> = db.conversationDao().observeThreadsForScenario(scenarioId)

    suspend fun createNamedThread(
        scenarioId: String, title: String, topicTag: String = "GENERAL",
        modelId: String = AgentConfig.DEFAULT_MODEL, agentVersion: String = AgentConfig.ENGINE_VERSION
    ): ConversationEntity {
        val now = System.currentTimeMillis()
        return ConversationEntity(
            id = UUID.randomUUID().toString(), scenarioId = scenarioId, status = "ACTIVE", modelId = modelId,
            agentVersion = agentVersion, createdAt = now, updatedAt = now,
            title = title.trim().ifEmpty { "Main Thread" }, topicTag = topicTag.trim().ifEmpty { "GENERAL" }
        ).also { db.conversationDao().upsert(it) }
    }

    suspend fun renameThread(id: String, title: String, topicTag: String) =
        db.conversationDao().renameThread(id, title.trim().ifEmpty { "Main Thread" }, topicTag.trim().ifEmpty { "GENERAL" }, System.currentTimeMillis())

    suspend fun setPinned(id: String, pinned: Boolean) {
        db.conversationDao().get(id)?.let { db.conversationDao().upsert(it.copy(isPinned = pinned)) }
    }

    /** Makes the thread the most recently updated one, so `getOrCreateActive` returns it. Null if it does not exist. */
    suspend fun switchThread(id: String): ConversationEntity? = db.withTransaction {
        val t = db.conversationDao().get(id) ?: return@withTransaction null
        val newest = db.conversationDao().listThreadsForScenario(t.scenarioId).maxOfOrNull { it.updatedAt } ?: 0L
        t.copy(updatedAt = maxOf(System.currentTimeMillis(), newest + 1)).also { db.conversationDao().upsert(it) }   // +1: a tie would not win
    }

    /**
     * Total visible (USER/ASSISTANT) messages across every thread of a project.
     * The Overview chat button reads "Start chat" while this is 0, "Continue chat" otherwise.
     */
    suspend fun messageCount(scenarioId: String): Int = db.messageDao().countVisibleForScenario(scenarioId)

    /**
     * Where the chat button resumes: the most-recent thread if it has a visible message, otherwise the
     * original "Main" thread (the earliest created). Null when the project has no thread yet, so the
     * caller creates Main on the first send. Shared with the thread chips so the two never disagree.
     */
    suspend fun landingThread(scenarioId: String): ConversationEntity? {
        val threads = db.conversationDao().listThreadsForScenario(scenarioId)
        if (threads.isEmpty()) return null
        val newest = threads.first()   // pinned desc, then updated_at desc
        if (db.messageDao().lastVisible(newest.id) != null) return newest
        return threads.minByOrNull { it.createdAt } ?: newest
    }

    /**
     * One row per thread for the Overview's Conversations section: the thread's last visible line
     * (raw; the screen truncates it) with its time, and whether it is the thread the chat button
     * would open. Pinned-then-recent order, same as [listThreads].
     */
    suspend fun threadSummaries(scenarioId: String): List<ThreadSummary> {
        val openId = landingThread(scenarioId)?.id
        return db.conversationDao().listThreadsForScenario(scenarioId).map { t ->
            val last = db.messageDao().lastVisible(t.id)
            ThreadSummary(
                id = t.id, title = t.title, lastLine = last?.text ?: "",
                lastAt = last?.createdAt ?: t.updatedAt, isOpenNow = t.id == openId
            )
        }
    }

    suspend fun deleteAll() = db.conversationDao().deleteAll()

    suspend fun get(id: String): ConversationEntity? = db.conversationDao().get(id)

    fun observeMessages(conversationId: String): Flow<List<MessageEntity>> = db.messageDao().observe(conversationId)

    suspend fun messages(conversationId: String): List<MessageEntity> = db.messageDao().all(conversationId)

    /** seq = max(seq) + 1, computed inside one transaction so concurrent appends never collide. */
    suspend fun append(
        conversationId: String, turnId: String, role: MessageRole, text: String = "",
        toolName: String? = null, callId: String? = null, payloadJson: String? = null,
        uiKind: UiKind = UiKind.NONE, refId: String? = null
    ): MessageEntity = db.withTransaction {
        val now = System.currentTimeMillis()
        val msg = MessageEntity(
            id = UUID.randomUUID().toString(), conversationId = conversationId,
            seq = db.messageDao().maxSeq(conversationId) + 1, turnId = turnId, role = role.name, text = text,
            toolName = toolName, callId = callId, payloadJson = payloadJson, uiKind = uiKind.name, refId = refId,
            createdAt = now
        )
        db.messageDao().insert(msg)
        db.conversationDao().get(conversationId)?.let { db.conversationDao().upsert(it.copy(updatedAt = now)) }
        msg
    }

    suspend fun setRefId(messageId: String, refId: String) = db.messageDao().setRefId(messageId, refId)

    /** USER messages only (used to verify quotes). */
    suspend fun userTexts(conversationId: String): List<String> =
        messages(conversationId).filter { it.role == MessageRole.USER.name }.map { it.text }

    suspend fun saveSummary(conversationId: String, summary: String, throughSeq: Int) {
        db.conversationDao().get(conversationId)?.let {
            db.conversationDao().upsert(it.copy(rollingSummary = summary, summarizedThroughSeq = throughSeq, updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun saveLastReport(conversationId: String, digestJson: String, scenarioVersion: Int) {
        db.conversationDao().get(conversationId)?.let {
            db.conversationDao().upsert(it.copy(lastReportDigestJson = digestJson, lastReportScenarioVersion = scenarioVersion, updatedAt = System.currentTimeMillis()))
        }
    }

    /** Deletes messages and resets the summary. Memory items are kept. */
    suspend fun clear(conversationId: String) = db.withTransaction {
        db.messageDao().deleteAll(conversationId)
        db.conversationDao().get(conversationId)?.let {
            db.conversationDao().upsert(
                it.copy(rollingSummary = "", summarizedThroughSeq = 0, lastReportDigestJson = null,
                    lastReportScenarioVersion = null, updatedAt = System.currentTimeMillis())
            )
        }
    }
}
