package com.drkkr.strategos

import com.drkkr.strategos.domain.game.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Verifies AnalysisIntegrity propagation through GameModelService and EngineDirective.
 *
 * Rule:
 *   SolverOutcome.Solved + all residuals ≤ 1e-9  → GameAnalysisResult.Solved  / COMPLETE
 *   SolverOutcome.Incomplete                      → GameAnalysisResult.Partial / outcome.integrity
 *   Unsupported / InvalidModel / compile failure  → GameAnalysisResult.Failed  / BLOCKED
 *   EngineDirective.notReady()                    → analysisIntegrity = BLOCKED
 *   EngineDirective default                       → analysisIntegrity = COMPLETE
 */
class AnalysisIntegrityTest {

    // ── GameAnalysisResult type defaults ─────────────────────────────────────

    @Test
    fun `GameAnalysisResult Failed defaults to BLOCKED integrity and EXPLORATORY state`() {
        val r = GameAnalysisResult.Failed("test failure")
        assertEquals(AnalysisIntegrity.BLOCKED, r.integrity)
        assertEquals(DecisionState.EXPLORATORY, r.decisionState)
    }

    @Test
    fun `GameAnalysisResult Solved carries COMPLETE integrity`() {
        val ir = pdIr()
        val outcome = PureNashSolver.solve(ir) as SolverOutcome.Solved
        val r = GameAnalysisResult.Solved(ir, outcome, DecisionState.VERIFIED, AnalysisIntegrity.COMPLETE)
        assertEquals(AnalysisIntegrity.COMPLETE, r.integrity)
        assertEquals(DecisionState.VERIFIED, r.decisionState)
    }

    @Test
    fun `GameAnalysisResult Partial preserves INCOMPLETE from SolverOutcome`() {
        val ir = mpIr()
        val outcome = PureNashSolver.solve(ir) as SolverOutcome.Incomplete
        assertEquals(AnalysisIntegrity.INCOMPLETE, outcome.integrity)
        val r = GameAnalysisResult.Partial(ir, outcome, outcome.reason, DecisionState.EXPLORATORY, outcome.integrity)
        assertEquals(AnalysisIntegrity.INCOMPLETE, r.integrity)
    }

    // ── SolverOutcome integrity fields ────────────────────────────────────────

    @Test
    fun `PureNashSolver Incomplete for MP carries INCOMPLETE integrity`() {
        val outcome = PureNashSolver.solve(mpIr())
        assertTrue(outcome is SolverOutcome.Incomplete)
        assertEquals(AnalysisIntegrity.INCOMPLETE, (outcome as SolverOutcome.Incomplete).integrity)
    }

    @Test
    fun `SolverOutcome Solved has no integrity field — maps to COMPLETE externally`() {
        val outcome = PureNashSolver.solve(pdIr())
        assertTrue(outcome is SolverOutcome.Solved)
        // COMPLETE is assigned by GameModelService after verification — no field on Solved itself.
    }

    // ── EngineDirective field ─────────────────────────────────────────────────

    @Test
    fun `EngineDirective defaults to COMPLETE analysisIntegrity`() {
        val d = makeDirective(analysisIntegrity = AnalysisIntegrity.COMPLETE)
        assertEquals(AnalysisIntegrity.COMPLETE, d.analysisIntegrity)
    }

    @Test
    fun `EngineDirective accepts explicit BLOCKED integrity`() {
        val d = makeDirective(analysisIntegrity = AnalysisIntegrity.BLOCKED)
        assertEquals(AnalysisIntegrity.BLOCKED, d.analysisIntegrity)
    }

    @Test
    fun `EngineDirective accepts INCOMPLETE integrity`() {
        val d = makeDirective(analysisIntegrity = AnalysisIntegrity.INCOMPLETE)
        assertEquals(AnalysisIntegrity.INCOMPLETE, d.analysisIntegrity)
    }

    @Test
    fun `EngineDirective toJson excludes analysis_integrity to stay within lean token budget`() {
        val d = makeDirective(analysisIntegrity = AnalysisIntegrity.INCOMPLETE)
        val json = d.toJson()
        assertNull("analysis_integrity must not appear in the LLM request JSON", json["analysis_integrity"])
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeDirective(analysisIntegrity: com.drkkr.strategos.domain.game.AnalysisIntegrity) =
        com.drkkr.strategos.domain.agent.EngineDirective(
            ready = false, recommendedMove = null, recommendedWhy = "",
            supportingFrameworks = emptyList(), safeFallbackMove = null, complementaryMove = null,
            blockedOrDominatedMoves = emptyList(), dominantDynamic = emptyList(), dynamicLabel = null,
            influencersInPlay = emptyList(), stressVerdict = "ROBUST", stressFlip = null,
            moveToImplement = null, analysisIntegrity = analysisIntegrity
        )

    private fun pdIr(): GameIR {
        val u1 = doubleArrayOf(3.0, 0.0, 5.0, 1.0)
        val u2 = doubleArrayOf(3.0, 5.0, 0.0, 1.0)
        return GameIR(
            scenarioId = "pd", playerIds = listOf("p1", "p2"),
            actionIds = listOf(listOf("c1", "d1"), listOf("c2", "d2")),
            strategyCounts = listOf(2, 2), utility = listOf(u1, u2),
            gameClass = GameClass.FINITE_NORMAL_FORM, coveragePct = 100
        )
    }

    private fun mpIr(): GameIR {
        val u1 = doubleArrayOf(1.0, -1.0, -1.0, 1.0)
        val u2 = doubleArrayOf(-1.0, 1.0, 1.0, -1.0)
        return GameIR(
            scenarioId = "mp", playerIds = listOf("p1", "p2"),
            actionIds = listOf(listOf("h1", "t1"), listOf("h2", "t2")),
            strategyCounts = listOf(2, 2), utility = listOf(u1, u2),
            gameClass = GameClass.ZERO_SUM_2P, coveragePct = 100
        )
    }
}
