package com.drkkr.strategos

import androidx.test.core.app.ApplicationProvider
import com.drkkr.strategos.data.db.StrategosDatabase
import com.drkkr.strategos.data.db.entities.ScenarioEntity
import com.drkkr.strategos.data.repo.CertificateRepository
import com.drkkr.strategos.domain.agent.EngineDirective
import com.drkkr.strategos.domain.game.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ExecutorService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CertificateRepositoryTest {

    private lateinit var db: StrategosDatabase
    private lateinit var roomExecutor: ExecutorService

    @Before
    fun setUp() = runBlocking {
        val (d, e) = inMemoryDbForTest(ApplicationProvider.getApplicationContext())
        db = d; roomExecutor = e
        db.scenarioDao().upsert(ScenarioEntity("s1", "Test", "DRAFT", 1, 1L, 1L, "{}"))
    }

    @After
    fun tearDown() = closeDbSafely(db, roomExecutor)

    @Test
    fun `persist writes GameModelEntity and DecisionCertificateEntity with COMPLETE integrity`() = runBlocking {
        val ir = pdIr()
        val outcome = PureNashSolver.solve(ir) as SolverOutcome.Solved
        val gameResult = GameAnalysisResult.Solved(ir, outcome, DecisionState.VERIFIED, AnalysisIntegrity.COMPLETE)
        val directive = EngineDirective(
            ready = true, recommendedMove = "defect", recommendedWhy = "test rationale",
            supportingFrameworks = emptyList(), safeFallbackMove = null, complementaryMove = null,
            blockedOrDominatedMoves = emptyList(), dominantDynamic = emptyList(), dynamicLabel = null,
            influencersInPlay = emptyList(), stressVerdict = "ROBUST", stressFlip = null,
            moveToImplement = null, analysisIntegrity = AnalysisIntegrity.COMPLETE
        )

        val repo = CertificateRepository(db.gameModelDao(), db.decisionCertificateDao())
        repo.persist("s1", 1, gameResult, directive, now = 1000L)

        val gm = db.gameModelDao().latestForScenario("s1")
        assertNotNull("GameModelEntity must be written", gm)

        val certs = db.decisionCertificateDao().forScenario("s1")
        assertEquals("Exactly one certificate", 1, certs.size)

        val cert = certs.first()
        assertEquals("integrity is COMPLETE", "COMPLETE", cert.integrity)
        assertEquals("FK links to game model", gm!!.id, cert.gameModelId)
        assertEquals("scenarioId on cert", "s1", cert.scenarioId)
        assertEquals("rationale stored", "test rationale", cert.rationale)
        assertEquals("equilibriumType is PURE_NASH", "PURE_NASH", cert.equilibriumType)
    }

    private fun pdIr(): GameIR {
        val u1 = doubleArrayOf(3.0, 0.0, 5.0, 1.0)
        val u2 = doubleArrayOf(3.0, 5.0, 0.0, 1.0)
        return GameIR(
            scenarioId = "s1", playerIds = listOf("p1", "p2"),
            actionIds = listOf(listOf("c1", "d1"), listOf("c2", "d2")),
            strategyCounts = listOf(2, 2), utility = listOf(u1, u2),
            gameClass = GameClass.FINITE_NORMAL_FORM, coveragePct = 100
        )
    }
}
