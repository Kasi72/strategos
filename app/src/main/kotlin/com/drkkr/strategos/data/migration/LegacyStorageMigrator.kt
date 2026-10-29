package com.drkkr.strategos.data.migration

import androidx.room.withTransaction
import com.drkkr.strategos.data.db.StrategosDatabase
import com.drkkr.strategos.data.db.entities.RecoverableRecordEntity
import com.drkkr.strategos.data.db.entities.SnapshotEntity
import com.drkkr.strategos.data.repo.ScenarioMapper
import com.drkkr.strategos.data.repo.SettingsRepository
import com.drkkr.strategos.data.storage.LoadResult
import com.drkkr.strategos.domain.model.AnalysisSnapshot
import kotlinx.serialization.encodeToString

interface LegacySource {
    fun loadAll(): LoadResult
    fun loadSnapshots(scenarioId: String): List<AnalysisSnapshot>
}

data class MigrationReport(val scenarios: Int, val snapshots: Int, val recoverable: Int, val skipped: Boolean)

/** One-time copy from the 1.x EncryptedSharedPreferences store into Room. The legacy store is NOT deleted in 2.0. */
class LegacyStorageMigrator(
    private val db: StrategosDatabase,
    private val settings: SettingsRepository,
    private val sourceFactory: () -> LegacySource?
) {
    suspend fun migrateIfNeeded(): MigrationReport {
        if (settings.legacyMigrated()) return MigrationReport(0, 0, 0, skipped = true)

        val source = try { sourceFactory() } catch (e: Exception) { null }
        if (source == null) {                       // no legacy data, or the store could not be opened
            settings.setLegacyMigrated()
            return MigrationReport(0, 0, 0, skipped = false)
        }

        val legacy = source.loadAll()
        val snapshots = legacy.scenarios.flatMap { s -> source.loadSnapshots(s.id) }

        db.withTransaction {
            legacy.scenarios.forEach { db.scenarioDao().upsert(ScenarioMapper.toEntity(it)) }
            snapshots.forEach {
                db.snapshotDao().insert(SnapshotEntity(it.snapshotId, it.scenarioId, it.timestamp, ScenarioMapper.json.encodeToString(it)))
            }
            db.recoverableDao().insertAll(legacy.recoverableRecords.map { RecoverableRecordEntity(it.id, it.rawJson, it.error) })
        }

        // Only mark done if everything arrived; otherwise the next start tries again.
        if (db.scenarioDao().getAll().size >= legacy.scenarios.size) settings.setLegacyMigrated()
        return MigrationReport(legacy.scenarios.size, snapshots.size, legacy.recoverableRecords.size, skipped = false)
    }
}
