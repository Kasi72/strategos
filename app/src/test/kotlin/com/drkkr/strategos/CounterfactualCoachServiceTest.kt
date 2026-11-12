package com.drkkr.strategos

import androidx.test.core.app.ApplicationProvider
import com.drkkr.strategos.agent.CounterfactualCoachService
import com.drkkr.strategos.data.db.entities.ScenarioEntity
import com.drkkr.strategos.data.repo.ConversationRepository
import com.drkkr.strategos.data.repo.JournalRepository
import com.drkkr.strategos.data.repo.MemoryRepository
import com.drkkr.strategos.data.repo.OutcomeRepository
import com.drkkr.strategos.data.secure.XorBase64TestCipher
import com.drkkr.strategos.domain.flywheel.OutcomeRecord
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ExecutorService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CounterfactualCoachServiceTest {

    private lateinit var dbPair: Pair<com.drkkr.strategos.data.db.StrategosDatabase, ExecutorService>
    private lateinit var outcomes: OutcomeRepository
    private lateinit var journal: JournalRepository
    private lateinit var service: CounterfactualCoachService

    @Before fun setUp() {
        dbPair = inMemoryDbForTest(ApplicationProvider.getApplicationContext())
        val db = dbPair.first
        outcomes = OutcomeRepository(db)
        journal = JournalRepository(db, XorBase64TestCipher(), MemoryRepository(db), ConversationRepository(db))
        service = CounterfactualCoachService(outcomes, journal)
    }

    @After fun tearDown() = closeDbSafely(dbPair.first, dbPair.second)

    // Insert a minimal ScenarioEntity so the FK constraint on outcome_record is satisfied.
    private suspend fun seedScenario(id: String = "s1") {
        val now = System.currentTimeMillis()
        dbPair.first.scenarioDao().upsert(
            ScenarioEntity(id = id, title = "test", lifecycleStage = "ACTIVE",
                version = 1, createdAt = now, updatedAt = now, json = "{}")
        )
    }

    private fun record(scenarioId: String = "s1", followed: Boolean = true) = OutcomeRecord(
        scenarioId = scenarioId,
        recommendedMove = "cooperate",
        actualMove = if (followed) "cooperate" else "defect",
        outcomeRating = 4,
        opponentReaction = "neutral",
        archetypeName = "PD",
        recordedAt = System.currentTimeMillis(),
        observedOpponentActionIndex = null
    )

    // Outcome saved even when scenario is null (no game analysis available).
    @Test fun outcomePersistedWithNullScenario() = runTest {
        seedScenario()
        val rec = record()
        service.submitAndCoach("s1", rec, scenario = null)
        val stored = outcomes.forScenario("s1")
        assertNotNull("record must be persisted", stored.firstOrNull())
    }

    // Journal entry written even when scenario is null (basicLesson fallback).
    @Test fun journalWrittenWithNullScenario() = runTest {
        seedScenario()
        service.submitAndCoach("s1", record(), scenario = null)
        val stored = outcomes.forScenario("s1")
        assert(stored.isNotEmpty()) { "outcome must have been saved" }
        assert(stored.first().outcomeRating == 4)
    }

    // If outcome save fails (simulated by closed DB), journal is NOT written.
    // Service must swallow the error and return normally.
    @Test fun pipelineStopsIfOutcomeSaveFails() = runTest {
        val (failDb, failExec) = inMemoryDbForTest(ApplicationProvider.getApplicationContext())
        val failOutcomes = OutcomeRepository(failDb)
        val failJournal = JournalRepository(failDb, XorBase64TestCipher(), MemoryRepository(failDb), ConversationRepository(failDb))
        // Close only the SQLite connection; keep executor alive so Room dispatches the
        // task and propagates the "already closed" error instead of silently dropping it.
        // closeDbSafely shuts down the executor first, causing the continuation to hang.
        runCatching { failDb.close() }

        val failService = CounterfactualCoachService(failOutcomes, failJournal)
        failService.submitAndCoach("s1", record(), scenario = null)
        failExec.shutdown()
    }
}
