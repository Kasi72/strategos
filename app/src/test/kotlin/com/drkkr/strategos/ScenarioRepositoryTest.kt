package com.drkkr.strategos

import androidx.test.core.app.ApplicationProvider
import com.drkkr.strategos.data.db.DbFactory
import com.drkkr.strategos.data.db.StrategosDatabase
import com.drkkr.strategos.data.db.entities.ScenarioEntity
import com.drkkr.strategos.data.repo.SaveResult
import com.drkkr.strategos.data.repo.ScenarioRepository
import com.drkkr.strategos.domain.model.AnalysisSnapshot
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
class ScenarioRepositoryTest {
    private lateinit var db: StrategosDatabase
    private lateinit var repo: ScenarioRepository

    @Before fun setUp() {
        db = DbFactory.inMemoryForTests(ApplicationProvider.getApplicationContext())
        repo = ScenarioRepository(db, null)
    }
    @After fun tearDown() { db.close() }

    @Test
    fun TC_DB_04_saveIncrementsVersionAndUpdatedAt() = runBlocking {
        val first = (repo.save(Fx.pd()) as SaveResult.Success).saved
        assertEquals(Fx.pd().version + 1, first.version)
        Thread.sleep(5)
        val second = (repo.save(first) as SaveResult.Success).saved
        assertEquals(first.version + 1, second.version)
        assertTrue(second.updatedAt > first.updatedAt)
        assertEquals(second, repo.find("pd"))
    }

    @Test
    fun TC_DB_03_saveWithOlderVersionIsAConflict() = runBlocking {
        val v2 = (repo.save(Fx.pd()) as SaveResult.Success).saved
        repo.save(v2)
        val stale = Fx.pd()                                  // version 1 < stored version 3
        val result = repo.save(stale)
        assertTrue(result is SaveResult.Conflict)
        assertEquals(3, (result as SaveResult.Conflict).stored.version)
    }

    @Test
    fun TC_DB_05_corruptRowIsRecoverableNotAScenario() = runBlocking {
        repo.save(Fx.pd())
        db.scenarioDao().upsert(ScenarioEntity("bad", "Broken", "DRAFT", 1, 1L, 1L, "{ not json"))
        repo.load()
        assertEquals(listOf("pd"), repo.scenarios.value.map { it.id })
        val rec = repo.recoverableRecords.value.single()
        assertEquals("bad", rec.id)
        assertEquals("{ not json", rec.rawJson)
    }

    @Test
    fun deleteRemovesScenario() = runBlocking {
        repo.save(Fx.pd())
        repo.delete("pd")
        assertTrue(repo.scenarios.value.isEmpty())
    }

    @Test
    fun snapshotsRoundTripNewestFirst() = runBlocking {
        val s = (repo.save(Fx.pd()) as SaveResult.Success).saved
        val a = AnalysisSnapshot(scenarioId = s.id, scenarioVersion = 1, scenarioHash = "h", selectedModuleIds = listOf("m"),
            executiveStatus = "X", candidateCount = 0, overallConfidence = 0.0, reportHash = "r", scenarioJson = "{}", timestamp = 1L)
        val b = a.copy(snapshotId = "second", timestamp = 2L)
        repo.saveSnapshot(a); repo.saveSnapshot(b)
        assertEquals(listOf("second", a.snapshotId), repo.loadSnapshots(s.id).map { it.snapshotId })
    }

    @Test
    fun snapshotForUnsavedScenarioDoesNotCrash() = runBlocking {
        val orphan = AnalysisSnapshot(scenarioId = "never-saved", scenarioVersion = 1, scenarioHash = "h", selectedModuleIds = emptyList(),
            executiveStatus = "X", candidateCount = 0, overallConfidence = 0.0, reportHash = "r", scenarioJson = "{}")
        repo.saveSnapshot(orphan)
        assertTrue(repo.loadSnapshots("never-saved").isEmpty())
    }

    @Test
    fun nullDatabaseMeansStorageUnavailable() = runBlocking {
        val none = ScenarioRepository(null, "locked")
        assertFalse(none.isStorageSecure)
        assertEquals("locked", none.storageError)
        assertTrue(none.save(Fx.pd()) is SaveResult.StorageUnavailable)
    }
}
