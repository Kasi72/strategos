package com.drkkr.strategos

import androidx.test.core.app.ApplicationProvider
import com.drkkr.strategos.data.db.DbFactory
import com.drkkr.strategos.data.db.StrategosDatabase
import com.drkkr.strategos.data.repo.ScenarioRepository
import com.drkkr.strategos.data.repo.TrajectoryRepository
import com.drkkr.strategos.fixtures.FakeLlmClient
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
class TrajectoryRepositoryTest {
    private lateinit var db: StrategosDatabase

    @Before fun setUp() { db = DbFactory.inMemoryForTests(ApplicationProvider.getApplicationContext()) }
    @After fun tearDown() { db.close() }

    @Test
    fun TC_QC_05_repositoryPersistsQuickCheckAndTrajectoryPoint() = runBlocking {
        val fake = FakeLlmClient()                                    // nothing queued: any call would throw
        val scenarios = ScenarioRepository(db, null).also { it.load() }
        val s = (scenarios.save(Fx.loanFamily()) as com.drkkr.strategos.data.repo.SaveResult.Success).saved
        val repo = TrajectoryRepository(db, scenarios)

        val snap = repo.submitQuickCheck(s.id, null, "Pause 24h", "DID_AS_SUGGESTED", "DE_ESCALATED", "GROUNDED", "Went well")

        assertEquals(58.0, snap.trustScore, 0.0); assertEquals(1, snap.consistencyStreak)
        assertEquals(1, db.quickCheckDao().forScenario(s.id).size)
        val points = repo.points(s.id)
        assertEquals(listOf("QUICK_CHECK"), points.map { it.source })
        assertEquals(snap.sigmoidProgress, repo.current(s.id).sigmoidProgress, 0.0)
        assertEquals(0.58, scenarios.find(s.id)!!.stakeholders.first { it.id == "bro" }.trustBaseline!!, 1e-9)
        assertTrue(fake.requests.isEmpty())
    }

    @Test
    fun recordTurnAppliesDeltasAndArchetype() = runBlocking {
        val scenarios = ScenarioRepository(db, null).also { it.load() }
        val s = (scenarios.save(Fx.loanFamily()) as com.drkkr.strategos.data.repo.SaveResult.Success).saved
        val repo = TrajectoryRepository(db, scenarios)
        val snap = repo.recordTurn(s.id, null, 6.0, -8.0, "STAG_HUNT")
        assertEquals(56.0, snap.trustScore, 0.0); assertEquals(42.0, snap.tensionScore, 0.0)
        assertEquals("STAG_HUNT", repo.current(s.id).activeArchetype)
        assertEquals("THREAD_TURN", repo.points(s.id).single().source)
    }
}
