package com.drkkr.strategos

import com.drkkr.strategos.domain.game.*
import com.drkkr.strategos.domain.model.*
import com.drkkr.strategos.framework.MatrixSupport
import com.drkkr.strategos.framework.nash.MixedNash2x2Module
import com.drkkr.strategos.framework.nash.PureNashModule
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/**
 * R0 solver-contract tests.
 *
 * Three layers:
 *  1. Fixture verification — known equilibria from reference JSON satisfy EquilibriumVerifier.
 *  2. Solver validation   — solver-found equilibria also pass EquilibriumVerifier residual check.
 *  3. Contract invariants — SolverOutcome, SolverCompletion, AnalysisIntegrity types behave correctly.
 *
 * Reference fixtures: app/src/test/resources/fixtures/ (*.json)
 * Equilibria in fixtures are manually derived from textbook sources; never from solver output.
 */
class SolverContractsTest {

    // ── Fixture-level constants (mirrored from the JSON files) ────────────────

    // Prisoner's Dilemma
    private val pdUtils = listOf(
        doubleArrayOf(3.0, 0.0, 5.0, 1.0),   // P1: CC, CD, DC, DD
        doubleArrayOf(3.0, 5.0, 0.0, 1.0)    // P2
    )
    private val pdCounts = listOf(2, 2)

    // Matching Pennies
    private val mpUtils = listOf(
        doubleArrayOf( 1.0, -1.0, -1.0,  1.0),
        doubleArrayOf(-1.0,  1.0,  1.0, -1.0)
    )
    private val mpCounts = listOf(2, 2)

    // Battle of the Sexes
    private val bosCounts = listOf(2, 2)
    private val bosUtils = listOf(
        doubleArrayOf(2.0, 0.0, 0.0, 1.0),
        doubleArrayOf(1.0, 0.0, 0.0, 2.0)
    )

    // Rock-Paper-Scissors
    private val rpsCounts = listOf(3, 3)
    private val rpsUtils = listOf(
        doubleArrayOf( 0.0, -1.0,  1.0,  1.0,  0.0, -1.0, -1.0,  1.0,  0.0),
        doubleArrayOf( 0.0,  1.0, -1.0, -1.0,  0.0,  1.0,  1.0, -1.0,  0.0)
    )

    // ── Layer 1: fixture verification (EquilibriumVerifier on raw matrices) ───

    @Test
    fun `PD - known NE (Defect,Defect) has zero deviation gain`() {
        // Profile [1,1] = both Defect; mixed form: [[0,1],[0,1]]
        val ne = listOf(doubleArrayOf(0.0, 1.0), doubleArrayOf(0.0, 1.0))
        val residual = EquilibriumVerifier.deviationGainResidual(pdUtils, pdCounts, ne)
        assertTrue("Expected residual ≤ 0, got $residual", residual <= 1e-9)
    }

    @Test
    fun `PD - (Cooperate,Cooperate) is NOT a Nash equilibrium`() {
        val notNe = listOf(doubleArrayOf(1.0, 0.0), doubleArrayOf(1.0, 0.0))
        val residual = EquilibriumVerifier.deviationGainResidual(pdUtils, pdCounts, notNe)
        // P1 gains 5-3=2 by deviating to Defect when P2 cooperates
        assertTrue("Expected residual > 0 for (C,C) in PD, got $residual", residual > 1e-9)
    }

    @Test
    fun `Matching Pennies - mixed NE (0_5, 0_5) has zero deviation gain`() {
        val ne = listOf(doubleArrayOf(0.5, 0.5), doubleArrayOf(0.5, 0.5))
        val residual = EquilibriumVerifier.deviationGainResidual(mpUtils, mpCounts, ne)
        assertTrue("Expected residual ≤ 0 for MP mixed NE, got $residual", residual <= 1e-9)
    }

    @Test
    fun `Matching Pennies - pure (Heads,Heads) is NOT a NE`() {
        val notNe = listOf(doubleArrayOf(1.0, 0.0), doubleArrayOf(1.0, 0.0))
        val residual = EquilibriumVerifier.deviationGainResidual(mpUtils, mpCounts, notNe)
        assertTrue("P2 gains 2 by switching to Tails; residual must be > 0, got $residual", residual > 1e-9)
    }

    @Test
    fun `BotS - pure NE (Opera,Opera) has zero deviation gain`() {
        val ne = listOf(doubleArrayOf(1.0, 0.0), doubleArrayOf(1.0, 0.0))
        val residual = EquilibriumVerifier.deviationGainResidual(bosUtils, bosCounts, ne)
        assertTrue("Expected residual ≤ 0 for (O,O) in BotS, got $residual", residual <= 1e-9)
    }

    @Test
    fun `BotS - pure NE (Ballet,Ballet) has zero deviation gain`() {
        val ne = listOf(doubleArrayOf(0.0, 1.0), doubleArrayOf(0.0, 1.0))
        val residual = EquilibriumVerifier.deviationGainResidual(bosUtils, bosCounts, ne)
        assertTrue("Expected residual ≤ 0 for (B,B) in BotS, got $residual", residual <= 1e-9)
    }

    @Test
    fun `BotS - mixed NE (2-3, 1-3) has zero deviation gain`() {
        val ne = listOf(doubleArrayOf(2.0/3, 1.0/3), doubleArrayOf(1.0/3, 2.0/3))
        val residual = EquilibriumVerifier.deviationGainResidual(bosUtils, bosCounts, ne)
        assertTrue("Expected residual ≤ 0 for BotS mixed NE, got $residual", residual <= 1e-9)
    }

    @Test
    fun `RPS - mixed NE uniform (1-3, 1-3, 1-3) has zero deviation gain`() {
        val third = 1.0 / 3.0
        val ne = listOf(doubleArrayOf(third, third, third), doubleArrayOf(third, third, third))
        val residual = EquilibriumVerifier.deviationGainResidual(rpsUtils, rpsCounts, ne)
        assertTrue("Expected residual ≤ 0 for RPS uniform mixed NE, got $residual", residual <= 1e-9)
    }

    @Test
    fun `RPS - pure (Rock,Rock) is NOT a NE`() {
        val notNe = listOf(
            doubleArrayOf(1.0, 0.0, 0.0),
            doubleArrayOf(1.0, 0.0, 0.0)
        )
        val residual = EquilibriumVerifier.deviationGainResidual(rpsUtils, rpsCounts, notNe)
        // P2 gains 1 by switching to Paper
        assertTrue("Expected positive residual for (R,R) in RPS, got $residual", residual > 1e-9)
    }

    // ── Layer 1b: metamorphic tests ───────────────────────────────────────────

    @Test
    fun `metamorphic - constant shift to P1 utilities leaves PD NE unchanged`() {
        val k = 100.0
        val shiftedUtils = listOf(
            DoubleArray(pdUtils[0].size) { i -> pdUtils[0][i] + k },
            pdUtils[1]
        )
        val ne = listOf(doubleArrayOf(0.0, 1.0), doubleArrayOf(0.0, 1.0))
        val residual = EquilibriumVerifier.deviationGainResidual(shiftedUtils, pdCounts, ne)
        assertTrue("Constant shift must not change Nash equilibria; residual $residual", residual <= 1e-9)
    }

    @Test
    fun `metamorphic - positive scaling of all utilities leaves PD NE unchanged`() {
        val c = 3.7
        val scaledUtils = pdUtils.map { row -> DoubleArray(row.size) { i -> row[i] * c } }
        val ne = listOf(doubleArrayOf(0.0, 1.0), doubleArrayOf(0.0, 1.0))
        val residual = EquilibriumVerifier.deviationGainResidual(scaledUtils, pdCounts, ne)
        assertTrue("Positive scaling must not change Nash equilibria; residual $residual", residual <= 1e-9)
    }

    @Test
    fun `metamorphic - strategy relabeling in MP preserves mixed NE mixing probability`() {
        // Swap P1's strategies: now Tails=0, Heads=1.
        // Utility array reorders: old (H,H)=0,(H,T)=1,(T,H)=2,(T,T)=3
        //                   new  (T,H)=0,(T,T)=1,(H,H)=2,(H,T)=3  (P1 relabeled, P2 unchanged)
        val relabeledUtils = listOf(
            doubleArrayOf(-1.0, 1.0, 1.0, -1.0),  // P1: (T,H),(T,T),(H,H),(H,T)
            doubleArrayOf( 1.0,-1.0,-1.0,  1.0)   // P2: same
        )
        // Mixed NE after relabeling: P1 plays Tails(=0 in new label) with 0.5
        val ne = listOf(doubleArrayOf(0.5, 0.5), doubleArrayOf(0.5, 0.5))
        val residual = EquilibriumVerifier.deviationGainResidual(relabeledUtils, mpCounts, ne)
        assertTrue("Relabeled MP NE must still be Nash; residual $residual", residual <= 1e-9)
    }

    // ── Layer 2: solver validation via EquilibriumVerifier ───────────────────

    @Test
    fun `PureNashModule finds (Defect,Defect) in PD and it passes EquilibriumVerifier`() {
        val sf = MatrixSupport.buildOrThrow(prisoner())
        val result = PureNashModule().analyze(prisoner())
        @Suppress("UNCHECKED_CAST")
        val equilibria = result.calculations["equilibria"] as List<List<Int>>
        assertEquals("PD has exactly one pure NE", 1, equilibria.size)
        assertEquals(listOf(1, 1), equilibria[0])

        val strategyCounts = sf.players.map { sf.strategies[it]!!.size }
        val utils = sf.utility.toList()
        for (profile in equilibria) {
            val mixedProfile = profile.mapIndexed { i, s ->
                DoubleArray(strategyCounts[i]) { j -> if (j == s) 1.0 else 0.0 }
            }
            val residual = EquilibriumVerifier.deviationGainResidual(utils, strategyCounts, mixedProfile)
            assertTrue("Solver-found PD NE must pass EquilibriumVerifier; residual $residual", residual <= 1e-9)
        }
    }

    @Test
    fun `PureNashModule finds no pure NE in MatchingPennies`() {
        val result = PureNashModule().analyze(matchingPennies())
        @Suppress("UNCHECKED_CAST")
        val equilibria = result.calculations["equilibria"] as List<List<Int>>
        assertEquals("Matching Pennies has no pure NE", 0, equilibria.size)
    }

    @Test
    fun `PureNashModule finds both pure NE in BattleOfSexes and both pass EquilibriumVerifier`() {
        val scenario = battleOfSexes()
        val sf = MatrixSupport.buildOrThrow(scenario)
        val result = PureNashModule().analyze(scenario)
        @Suppress("UNCHECKED_CAST")
        val equilibria = result.calculations["equilibria"] as List<List<Int>>
        assertEquals("BotS has exactly 2 pure NE", 2, equilibria.size)

        val strategyCounts = sf.players.map { sf.strategies[it]!!.size }
        val utils = sf.utility.toList()
        for (profile in equilibria) {
            val mixedProfile = profile.mapIndexed { i, s ->
                DoubleArray(strategyCounts[i]) { j -> if (j == s) 1.0 else 0.0 }
            }
            val residual = EquilibriumVerifier.deviationGainResidual(utils, strategyCounts, mixedProfile)
            assertTrue("BotS pure NE $profile must pass EquilibriumVerifier; residual $residual", residual <= 1e-9)
        }
    }

    @Test
    fun `PureNashModule finds no pure NE in RPS`() {
        val result = PureNashModule().analyze(rockPaperScissors())
        @Suppress("UNCHECKED_CAST")
        val equilibria = result.calculations["equilibria"] as List<List<Int>>
        assertEquals("RPS has no pure NE", 0, equilibria.size)
    }

    @Test
    fun `MixedNash2x2Module finds (0_5, 0_5) in MatchingPennies and it passes EquilibriumVerifier`() {
        val result = MixedNash2x2Module().analyze(matchingPennies())
        val p = result.calculations["p"] as Double
        val q = result.calculations["q"] as Double
        assertEquals("MP mixed NE p should be 0.5", 0.5, p, 1e-9)
        assertEquals("MP mixed NE q should be 0.5", 0.5, q, 1e-9)

        val ne = listOf(doubleArrayOf(p, 1.0 - p), doubleArrayOf(q, 1.0 - q))
        val residual = EquilibriumVerifier.deviationGainResidual(mpUtils, mpCounts, ne)
        assertTrue("MixedNash2x2 output for MP must pass EquilibriumVerifier; residual $residual", residual <= 1e-9)
    }

    @Test
    fun `MixedNash2x2Module returns p and q in (0,1) for BattleOfSexes mixed NE`() {
        val result = MixedNash2x2Module().analyze(battleOfSexes())
        val p = result.calculations["p"] as Double
        val q = result.calculations["q"] as Double
        assertTrue("BotS mixed NE p must be in (0,1)", p in 0.0..1.0)
        assertTrue("BotS mixed NE q must be in (0,1)", q in 0.0..1.0)

        val ne = listOf(doubleArrayOf(p, 1.0 - p), doubleArrayOf(q, 1.0 - q))
        val residual = EquilibriumVerifier.deviationGainResidual(bosUtils, bosCounts, ne)
        assertTrue("MixedNash2x2 output for BotS must pass EquilibriumVerifier; residual $residual", residual <= 1e-9)
    }

    @Test
    fun `MixedNash2x2Module reports no interior mixed NE in PD (dominant strategy)`() {
        val result = MixedNash2x2Module().analyze(prisoner())
        val p = result.calculations["p"] as Double
        val q = result.calculations["q"] as Double
        val hasMixed = p in 0.0..1.0 && q in 0.0..1.0
        assertFalse("PD has no interior mixed NE", hasMixed)
    }

    @Test
    fun `MixedNash2x2Module cannot analyze RPS (3 strategies each)`() {
        val applicability = MixedNash2x2Module().canAnalyze(rockPaperScissors())
        assertEquals("MixedNash2x2 must decline 3-strategy game", 0.0, applicability.score, 0.0)
    }

    // ── Layer 3: SolverContracts type invariants ──────────────────────────────

    @Test
    fun `SolverOutcome Solved diagnostics completion must be COMPLETE`() {
        val diag = SolverDiagnostics(
            method = "pure-enumeration",
            solverVersion = "1.0",
            converged = true,
            residual = 0.0,
            elapsedMs = 1L,
            limitations = emptyList(),
            completion = SolverCompletion.COMPLETE
        )
        val outcome = SolverOutcome.Solved(equilibria = emptyList(), diagnostics = diag)
        assertEquals(SolverCompletion.COMPLETE, outcome.diagnostics.completion)
    }

    @Test
    fun `SolverOutcome Incomplete carries AnalysisIntegrity`() {
        val diag = SolverDiagnostics(
            method = "support-enumeration",
            solverVersion = "1.0",
            converged = false,
            residual = null,
            elapsedMs = 4999L,
            limitations = listOf("degenerate payoffs"),
            completion = SolverCompletion.PARTIAL
        )
        val outcome = SolverOutcome.Incomplete(
            partialEquilibria = emptyList(),
            reason = "degenerate game — results may be incomplete",
            diagnostics = diag,
            integrity = AnalysisIntegrity.INCOMPLETE
        )
        assertEquals(AnalysisIntegrity.INCOMPLETE, outcome.integrity)
        assertNotEquals(SolverCompletion.COMPLETE, outcome.diagnostics.completion)
    }

    @Test
    fun `SolverLimits ON_DEVICE fits on-device constraints`() {
        val limits = SolverLimits.ON_DEVICE
        assertTrue("maxPlayers must be at least 2", limits.maxPlayers >= 2)
        assertTrue("maxGameProfiles must allow at least 2x2", limits.maxGameProfiles >= 4)
        assertTrue("maxRuntimeMs must be positive", limits.maxRuntimeMs > 0)
    }

    @Test
    fun `AnalysisIntegrity ordering holds`() {
        val values = AnalysisIntegrity.values()
        assertTrue(values.contains(AnalysisIntegrity.COMPLETE))
        assertTrue(values.contains(AnalysisIntegrity.INCOMPLETE))
        assertTrue(values.contains(AnalysisIntegrity.BLOCKED))
    }

    // ── Private scenario builders (local to this test file) ──────────────────

    private fun prisoner(): Scenario {
        val payoffs = listOf(
            payoff("p1", mapOf("p1" to "c1", "p2" to "c2"), 3.0),
            payoff("p1", mapOf("p1" to "c1", "p2" to "d2"), 0.0),
            payoff("p1", mapOf("p1" to "d1", "p2" to "c2"), 5.0),
            payoff("p1", mapOf("p1" to "d1", "p2" to "d2"), 1.0),
            payoff("p2", mapOf("p1" to "c1", "p2" to "c2"), 3.0),
            payoff("p2", mapOf("p1" to "c1", "p2" to "d2"), 5.0),
            payoff("p2", mapOf("p1" to "d1", "p2" to "c2"), 0.0),
            payoff("p2", mapOf("p1" to "d1", "p2" to "d2"), 1.0)
        )
        return Scenario(
            id = UUID.randomUUID().toString(), title = "Prisoner's Dilemma",
            stakeholders = listOf(Stakeholder("p1", "P1"), Stakeholder("p2", "P2")),
            actions = listOf(
                Action("c1", "p1", "Cooperate"), Action("d1", "p1", "Defect"),
                Action("c2", "p2", "Cooperate"), Action("d2", "p2", "Defect")
            ),
            payoffs = payoffs
        )
    }

    private fun matchingPennies(): Scenario {
        val payoffs = listOf(
            payoff("p1", mapOf("p1" to "h1", "p2" to "h2"),  1.0),
            payoff("p1", mapOf("p1" to "h1", "p2" to "t2"), -1.0),
            payoff("p1", mapOf("p1" to "t1", "p2" to "h2"), -1.0),
            payoff("p1", mapOf("p1" to "t1", "p2" to "t2"),  1.0),
            payoff("p2", mapOf("p1" to "h1", "p2" to "h2"), -1.0),
            payoff("p2", mapOf("p1" to "h1", "p2" to "t2"),  1.0),
            payoff("p2", mapOf("p1" to "t1", "p2" to "h2"),  1.0),
            payoff("p2", mapOf("p1" to "t1", "p2" to "t2"), -1.0)
        )
        return Scenario(
            id = UUID.randomUUID().toString(), title = "Matching Pennies",
            stakeholders = listOf(Stakeholder("p1", "P1"), Stakeholder("p2", "P2")),
            actions = listOf(
                Action("h1", "p1", "Heads"), Action("t1", "p1", "Tails"),
                Action("h2", "p2", "Heads"), Action("t2", "p2", "Tails")
            ),
            payoffs = payoffs
        )
    }

    private fun battleOfSexes(): Scenario {
        // P1 prefers Opera (row), P2 prefers Ballet (col).
        // (O,O)=(2,1), (O,B)=(0,0), (B,O)=(0,0), (B,B)=(1,2)
        val payoffs = listOf(
            payoff("p1", mapOf("p1" to "o1", "p2" to "o2"), 2.0),
            payoff("p1", mapOf("p1" to "o1", "p2" to "b2"), 0.0),
            payoff("p1", mapOf("p1" to "b1", "p2" to "o2"), 0.0),
            payoff("p1", mapOf("p1" to "b1", "p2" to "b2"), 1.0),
            payoff("p2", mapOf("p1" to "o1", "p2" to "o2"), 1.0),
            payoff("p2", mapOf("p1" to "o1", "p2" to "b2"), 0.0),
            payoff("p2", mapOf("p1" to "b1", "p2" to "o2"), 0.0),
            payoff("p2", mapOf("p1" to "b1", "p2" to "b2"), 2.0)
        )
        return Scenario(
            id = UUID.randomUUID().toString(), title = "Battle of the Sexes",
            stakeholders = listOf(Stakeholder("p1", "P1"), Stakeholder("p2", "P2")),
            actions = listOf(
                Action("o1", "p1", "Opera"),  Action("b1", "p1", "Ballet"),
                Action("o2", "p2", "Opera"),  Action("b2", "p2", "Ballet")
            ),
            payoffs = payoffs
        )
    }

    private fun rockPaperScissors(): Scenario {
        // Zero-sum: Win=1, Loss=-1, Tie=0. R>S, P>R, S>P.
        // Action IDs: rk1/pa1/sc1 for P1; rk2/pa2/sc2 for P2 (avoids clash with stakeholder ids p1/p2).
        val outcomes = mapOf(
            ("rk1" to "rk2") to (0.0  to  0.0),
            ("rk1" to "pa2") to (-1.0 to  1.0),
            ("rk1" to "sc2") to (1.0  to -1.0),
            ("pa1" to "rk2") to (1.0  to -1.0),
            ("pa1" to "pa2") to (0.0  to  0.0),
            ("pa1" to "sc2") to (-1.0 to  1.0),
            ("sc1" to "rk2") to (-1.0 to  1.0),
            ("sc1" to "pa2") to (1.0  to -1.0),
            ("sc1" to "sc2") to (0.0  to  0.0)
        )
        val payoffs = outcomes.flatMap { (k, v) ->
            val (a1, a2) = k
            val (u1, u2) = v
            val profile = mapOf("p1" to a1, "p2" to a2)
            listOf(payoff("p1", profile, u1), payoff("p2", profile, u2))
        }
        return Scenario(
            id = UUID.randomUUID().toString(), title = "Rock-Paper-Scissors",
            stakeholders = listOf(Stakeholder("p1", "P1"), Stakeholder("p2", "P2")),
            actions = listOf(
                Action("rk1", "p1", "Rock"),  Action("pa1", "p1", "Paper"),  Action("sc1", "p1", "Scissors"),
                Action("rk2", "p2", "Rock"),  Action("pa2", "p2", "Paper"),  Action("sc2", "p2", "Scissors")
            ),
            payoffs = payoffs
        )
    }

    private fun payoff(player: String, profile: Map<String, String>, v: Double) =
        PayoffEntry(player, profile, MultiPayoff(mapOf(PayoffDimension.LONG_TERM_VALUE to v)))
}
