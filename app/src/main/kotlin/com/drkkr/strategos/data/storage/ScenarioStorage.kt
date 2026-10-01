package com.drkkr.strategos.data.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.drkkr.strategos.domain.model.AnalysisSnapshot
import com.drkkr.strategos.domain.model.Scenario

private const val PREF_FILE = "strategos_scenarios"
private const val KEY_PREFIX = "scenario_"
private const val KEY_IDS = "ids"
private const val SNAP_PREFIX = "snap_"
private const val SNAP_IDS_PREFIX = "snap_ids_"

class StorageUnavailableException(cause: Throwable) :
    Exception("Secure storage unavailable: ${cause.message}", cause)

data class RecoverableRecord(
    val id: String,
    val rawJson: String?,
    val error: String
)

data class LoadResult(
    val scenarios: List<Scenario>,
    val recoverableRecords: List<RecoverableRecord>
)

class ScenarioStorage(context: Context) {

    private val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

    private val prefs: SharedPreferences = try {
        EncryptedSharedPreferences.create(
            PREF_FILE, masterKeyAlias, context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // No plaintext fallback. Secure storage is required.
        throw StorageUnavailableException(e)
    }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    fun save(scenario: Scenario) {
        val ids = loadIds().toMutableSet().also { it.add(scenario.id) }
        prefs.edit()
            .putString("$KEY_PREFIX${scenario.id}", json.encodeToString(scenario))
            .putStringSet(KEY_IDS, ids)
            .apply()
    }

    fun loadAll(): LoadResult {
        val scenarios = mutableListOf<Scenario>()
        val recoverable = mutableListOf<RecoverableRecord>()
        for (id in loadIds()) {
            val raw = prefs.getString("$KEY_PREFIX$id", null) ?: continue
            val result = runCatching { json.decodeFromString<Scenario>(raw) }
            if (result.isSuccess) {
                scenarios += result.getOrThrow()
            } else {
                // Preserve raw record — do not silently discard
                recoverable += RecoverableRecord(
                    id = id,
                    rawJson = raw,
                    error = result.exceptionOrNull()?.message ?: "Unknown decode error"
                )
            }
        }
        return LoadResult(scenarios.sortedByDescending { it.updatedAt }, recoverable)
    }

    fun delete(id: String) {
        val ids = loadIds().toMutableSet().also { it.remove(id) }
        prefs.edit().remove("$KEY_PREFIX$id").putStringSet(KEY_IDS, ids).apply()
    }

    private fun loadIds(): Set<String> = prefs.getStringSet(KEY_IDS, emptySet()) ?: emptySet()

    fun saveSnapshot(snapshot: AnalysisSnapshot) {
        val snapshotKey = "$SNAP_PREFIX${snapshot.scenarioId}_${snapshot.snapshotId}"
        val idsKey = "$SNAP_IDS_PREFIX${snapshot.scenarioId}"
        val ids = (prefs.getStringSet(idsKey, emptySet()) ?: emptySet()).toMutableSet()
        ids.add(snapshot.snapshotId)
        prefs.edit()
            .putString(snapshotKey, json.encodeToString(snapshot))
            .putStringSet(idsKey, ids)
            .apply()
    }

    fun loadSnapshots(scenarioId: String): List<AnalysisSnapshot> {
        val idsKey = "$SNAP_IDS_PREFIX$scenarioId"
        val ids = prefs.getStringSet(idsKey, emptySet()) ?: emptySet()
        return ids.mapNotNull { id ->
            val raw = prefs.getString("$SNAP_PREFIX${scenarioId}_$id", null) ?: return@mapNotNull null
            runCatching { json.decodeFromString<AnalysisSnapshot>(raw) }.getOrNull()
        }.sortedByDescending { it.timestamp }
    }
}
