package com.drkkr.strategos.data.repo

import com.drkkr.strategos.data.db.StrategosDatabase
import com.drkkr.strategos.data.db.entities.MemoryItemEntity
import com.drkkr.strategos.domain.model.FactStatus
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class MemoryRepository(private val db: StrategosDatabase) {

    fun observeOpen(scenarioId: String): Flow<List<MemoryItemEntity>> = db.memoryDao().observeOpen(scenarioId)

    suspend fun open(scenarioId: String): List<MemoryItemEntity> = db.memoryDao().open(scenarioId)

    suspend fun add(scenarioId: String, kind: MemoryKind, text: String, status: FactStatus, sourceMessageId: String? = null): String {
        val id = UUID.randomUUID().toString()
        db.memoryDao().insert(
            MemoryItemEntity(id, scenarioId, kind.name, text, status.name, sourceMessageId, System.currentTimeMillis())
        )
        return id
    }

    /** Returns how many items were resolved. */
    suspend fun resolve(scenarioId: String, ids: List<String>): Int =
        if (ids.isEmpty()) 0 else db.memoryDao().resolve(scenarioId, ids, System.currentTimeMillis())

    suspend fun setStatus(id: String, status: FactStatus) = db.memoryDao().setStatus(id, status.name)

    suspend fun delete(id: String) = db.memoryDao().delete(id)
}
