package com.drkkr.strategos.data.repo

import androidx.room.withTransaction
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
