package com.drkkr.strategos.data.repo

import com.drkkr.strategos.data.db.StrategosDatabase
import com.drkkr.strategos.data.db.entities.ChangeSetEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class ChangeSetRepository(private val db: StrategosDatabase) {

    suspend fun create(
        scenarioId: String, conversationId: String, summary: String, opsJson: String, refIdsJson: String,
        baseScenarioVersion: Int, id: String = UUID.randomUUID().toString()
    ): ChangeSetEntity = ChangeSetEntity(
        id = id, scenarioId = scenarioId, conversationId = conversationId, status = ChangeSetStatus.PENDING.name,
        summary = summary, opsJson = opsJson, refIdsJson = refIdsJson, baseScenarioVersion = baseScenarioVersion,
        createdAt = System.currentTimeMillis()
    ).also { db.changeSetDao().insert(it) }

    suspend fun get(id: String): ChangeSetEntity? = db.changeSetDao().get(id)

    suspend fun pending(scenarioId: String): List<ChangeSetEntity> = db.changeSetDao().pending(scenarioId)

    suspend fun markDecided(id: String, status: ChangeSetStatus, decisionsJson: String?, applyReportJson: String?, appliedVersion: Int?) {
        val cur = db.changeSetDao().get(id) ?: return
        db.changeSetDao().update(
            cur.copy(
                status = status.name, decisionsJson = decisionsJson, applyReportJson = applyReportJson,
                appliedScenarioVersion = appliedVersion, decidedAt = System.currentTimeMillis()
            )
        )
    }

    fun observe(conversationId: String): Flow<List<ChangeSetEntity>> = db.changeSetDao().observeForConversation(conversationId)
}
