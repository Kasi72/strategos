package com.drkkr.strategos.data.repo

import android.content.Context
import com.drkkr.strategos.data.storage.RecoverableRecord
import com.drkkr.strategos.data.storage.ScenarioStorage
import com.drkkr.strategos.data.storage.StorageUnavailableException
import com.drkkr.strategos.domain.model.AnalysisSnapshot
import com.drkkr.strategos.domain.model.Scenario
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

sealed class SaveResult {
    data class Success(val saved: Scenario) : SaveResult()
    data class Conflict(val stored: Scenario) : SaveResult()
    object StorageUnavailable : SaveResult()
}

class ScenarioRepository(context: Context) {

    private val storage: ScenarioStorage?
    val isStorageSecure: Boolean
    val storageError: String?

    private val _scenarios = MutableStateFlow<List<Scenario>>(emptyList())
    val scenarios: StateFlow<List<Scenario>> get() = _scenarios

    private val _recoverableRecords = MutableStateFlow<List<RecoverableRecord>>(emptyList())
    val recoverableRecords: StateFlow<List<RecoverableRecord>> get() = _recoverableRecords

    init {
        var stor: ScenarioStorage? = null
        var err: String? = null
        try {
            stor = ScenarioStorage(context)
        } catch (e: StorageUnavailableException) {
            err = e.message
        }
        storage = stor
        isStorageSecure = stor != null
        storageError = err
    }

    suspend fun load() = withContext(Dispatchers.IO) {
        val s = storage ?: return@withContext
        val result = s.loadAll()
        _scenarios.value = result.scenarios
        _recoverableRecords.value = result.recoverableRecords
    }

    suspend fun save(scenario: Scenario): SaveResult = withContext(Dispatchers.IO) {
        val stor = storage ?: return@withContext SaveResult.StorageUnavailable
        // Optimistic concurrency: reject if stored version is newer than in-memory version
        val stored = stor.loadAll().scenarios.firstOrNull { it.id == scenario.id }
        if (stored != null && stored.version > scenario.version) {
            return@withContext SaveResult.Conflict(stored)
        }
        val updated = scenario.copy(
            version = scenario.version + 1,
            updatedAt = System.currentTimeMillis()
        )
        stor.save(updated)
        val result = stor.loadAll()
        _scenarios.value = result.scenarios
        _recoverableRecords.value = result.recoverableRecords
        SaveResult.Success(updated)
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val stor = storage ?: return@withContext
        stor.delete(id)
        val result = stor.loadAll()
        _scenarios.value = result.scenarios
    }

    suspend fun saveSnapshot(snapshot: AnalysisSnapshot) = withContext(Dispatchers.IO) {
        storage?.saveSnapshot(snapshot)
    }

    suspend fun loadSnapshots(scenarioId: String): List<AnalysisSnapshot> = withContext(Dispatchers.IO) {
        storage?.loadSnapshots(scenarioId) ?: emptyList()
    }

    fun find(id: String) = _scenarios.value.firstOrNull { it.id == id }

    // ── Anthropic API key (encrypted storage) ─────────────────────────────────
    fun apiKey(): String? = storage?.loadApiKey()
    fun hasApiKey(): Boolean = !apiKey().isNullOrBlank()
    fun setApiKey(key: String) { storage?.saveApiKey(key) }
    fun clearApiKey() { storage?.clearApiKey() }
}
