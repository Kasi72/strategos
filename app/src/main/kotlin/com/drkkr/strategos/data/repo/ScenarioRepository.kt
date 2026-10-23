package com.drkkr.strategos.data.repo

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.drkkr.strategos.data.db.StrategosDatabase
import com.drkkr.strategos.data.db.entities.RecoverableRecordEntity
import com.drkkr.strategos.data.db.entities.ScenarioEntity
import com.drkkr.strategos.data.db.entities.SnapshotEntity
import com.drkkr.strategos.data.storage.RecoverableRecord
import com.drkkr.strategos.domain.model.AnalysisSnapshot
import com.drkkr.strategos.domain.model.Scenario
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

sealed class SaveResult {
    data class Success(val saved: Scenario) : SaveResult()
    data class Conflict(val stored: Scenario) : SaveResult()
    object StorageUnavailable : SaveResult()
}

/** Room-backed. [db] == null means secure storage is unavailable, which behaves like 1.x with a locked store. */
class ScenarioRepository(
    private val db: StrategosDatabase?,
    val storageError: String?
) {
    val isStorageSecure: Boolean = db != null

    // encodeDefaults: updatedAt/createdAt/timestamp default to "now". With encodeDefaults=false the encoder drops a value
    // that equals the default evaluated at encode time (same millisecond), and decoding then re-evaluates "now".
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false; encodeDefaults = true }

    private val _scenarios = MutableStateFlow<List<Scenario>>(emptyList())
    val scenarios: StateFlow<List<Scenario>> get() = _scenarios

    private val _recoverableRecords = MutableStateFlow<List<RecoverableRecord>>(emptyList())
    val recoverableRecords: StateFlow<List<RecoverableRecord>> get() = _recoverableRecords

    suspend fun load() = withContext(Dispatchers.IO) {
        val database = db ?: return@withContext
        refresh(database)
    }

    suspend fun save(scenario: Scenario): SaveResult = withContext(Dispatchers.IO) {
        val database = db ?: return@withContext SaveResult.StorageUnavailable
        // Built once, outside the transaction, so the returned scenario is exactly what is stored.
        val updated = scenario.copy(version = scenario.version + 1, updatedAt = System.currentTimeMillis())
        val outcome = database.withTransaction {
            // Optimistic concurrency: reject if the stored version is newer than the in-memory one.
            val stored = database.scenarioDao().get(scenario.id)?.let { decode(it) }
            if (stored != null && stored.version > scenario.version) {
                SaveResult.Conflict(stored)
            } else {
                database.scenarioDao().upsert(
                    ScenarioEntity(
                        id = updated.id, title = updated.title, lifecycleStage = updated.lifecycleStage.name,
                        version = updated.version, createdAt = updated.createdAt, updatedAt = updated.updatedAt,
                        json = json.encodeToString(updated)
                    )
                )
                SaveResult.Success(updated)
            }
        }
        if (outcome is SaveResult.Success) refresh(database)
        outcome
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val database = db ?: return@withContext
        database.scenarioDao().delete(id)
        refresh(database)
    }

    suspend fun saveSnapshot(snapshot: AnalysisSnapshot) = withContext(Dispatchers.IO) {
        val database = db ?: return@withContext
        try {
            database.snapshotDao().insert(
                SnapshotEntity(snapshot.snapshotId, snapshot.scenarioId, snapshot.timestamp, json.encodeToString(snapshot))
            )
        } catch (e: SQLiteConstraintException) {
            // BLUEPRINT-QUESTION: analyze() saves the snapshot before the scenario row exists for a never-saved scenario.
            // The snapshot table has a foreign key, so it is skipped here (1.x kept it). The snapshot stays in UiState.
        }
    }

    suspend fun loadSnapshots(scenarioId: String): List<AnalysisSnapshot> = withContext(Dispatchers.IO) {
        val database = db ?: return@withContext emptyList()
        database.snapshotDao().forScenario(scenarioId)
            .mapNotNull { runCatching { json.decodeFromString<AnalysisSnapshot>(it.json) }.getOrNull() }
    }

    fun find(id: String) = _scenarios.value.firstOrNull { it.id == id }

    private fun decode(e: ScenarioEntity): Scenario? = runCatching { json.decodeFromString<Scenario>(e.json) }.getOrNull()

    private suspend fun refresh(database: StrategosDatabase) {
        val good = mutableListOf<Scenario>()
        val bad = mutableListOf<RecoverableRecord>()
        for (row in database.scenarioDao().getAll()) {
            val r = runCatching { json.decodeFromString<Scenario>(row.json) }
            if (r.isSuccess) good += r.getOrThrow()
            else bad += RecoverableRecord(row.id, row.json, r.exceptionOrNull()?.message ?: "Unknown decode error")
        }
        val stored = database.recoverableDao().observeAll().first()
            .map { RecoverableRecord(it.id, it.rawJson, it.error) }
        _scenarios.value = good.sortedByDescending { it.updatedAt }
        _recoverableRecords.value = bad + stored.filter { s -> bad.none { it.id == s.id } }
    }
}
