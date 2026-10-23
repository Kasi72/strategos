package com.drkkr.strategos

import androidx.test.core.app.ApplicationProvider
import com.drkkr.strategos.data.db.DbFactory
import com.drkkr.strategos.data.db.StrategosDatabase
import com.drkkr.strategos.data.migration.LegacyStorageMigrator
import com.drkkr.strategos.data.repo.ScenarioRepository
import com.drkkr.strategos.data.repo.SettingsRepository
import com.drkkr.strategos.data.storage.LoadResult
import com.drkkr.strategos.data.storage.RecoverableRecord
import com.drkkr.strategos.domain.model.AnalysisSnapshot
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.fixtures.FakeLegacySource
import com.drkkr.strategos.fixtures.Fx
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LegacyStorageMigratorTest {
    private lateinit var db: StrategosDatabase
    private lateinit var settings: SettingsRepository

    @Before fun setUp() {
        db = DbFactory.inMemoryForTests(ApplicationProvider.getApplicationContext())
        settings = SettingsRepository(db)
    }
    @After fun tearDown() { db.close() }

    private fun scenario(id: String, version: Int = 4) = Fx.pd().copy(id = id, title = "S $id", version = version, createdAt = 111L, updatedAt = 222L)
    private fun snap(scenarioId: String, id: String) = AnalysisSnapshot(
        snapshotId = id, scenarioId = scenarioId, scenarioVersion = 4, scenarioHash = "h", selectedModuleIds = listOf("m"),
        executiveStatus = "X", candidateCount = 1, overallConfidence = 0.5, reportHash = "r", scenarioJson = "{}", timestamp = 333L
    )

    private fun source(): FakeLegacySource {
        val scenarios: List<Scenario> = listOf(scenario("a"), scenario("b"), scenario("c"))
        return FakeLegacySource(
            LoadResult(scenarios, listOf(RecoverableRecord("bad", "{ broken", "decode error"))),
            mapOf("a" to listOf(snap("a", "s1")), "b" to listOf(snap("b", "s2")))
        )
    }

    @Test
    fun TC_MIG_01_migratesEverythingAndSetsFlag() = runBlocking {
        val report = LegacyStorageMigrator(db, settings) { source() }.migrateIfNeeded()
        assertEquals(3, report.scenarios); assertEquals(2, report.snapshots); assertEquals(1, report.recoverable)
        assertFalse(report.skipped)
        assertEquals("1", settings.get("legacy.migrated"))

        val repo = ScenarioRepository(db, null).also { it.load() }
        assertEquals(setOf("a", "b", "c"), repo.scenarios.value.map { it.id }.toSet())
        val a = repo.find("a")!!
        assertEquals(4, a.version); assertEquals(111L, a.createdAt); assertEquals(222L, a.updatedAt)
        assertEquals(listOf("s1"), repo.loadSnapshots("a").map { it.snapshotId })
        assertEquals(listOf("bad"), repo.recoverableRecords.value.map { it.id })
    }

    @Test
    fun TC_MIG_02_secondRunIsSkippedWithNoDuplicates() = runBlocking {
        val migrator = LegacyStorageMigrator(db, settings) { source() }
        migrator.migrateIfNeeded()
        val second = migrator.migrateIfNeeded()
        assertTrue(second.skipped)
        assertEquals(3, db.scenarioDao().getAll().size)
    }

    @Test
    fun TC_MIG_03_throwingFactorySetsFlagAndDoesNotCrash() = runBlocking {
        val report = LegacyStorageMigrator(db, settings) { throw IllegalStateException("keystore reset") }.migrateIfNeeded()
        assertEquals(0, report.scenarios)
        assertEquals("1", settings.get("legacy.migrated"))
    }

    @Test
    fun missingLegacyStoreSetsFlag() = runBlocking {
        LegacyStorageMigrator(db, settings) { null }.migrateIfNeeded()
        assertEquals("1", settings.get("legacy.migrated"))
    }
}
