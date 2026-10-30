package com.drkkr.strategos

import com.drkkr.strategos.domain.game.*
import com.drkkr.strategos.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class GameModelCompilerTest {

    // ── Happy-path tests ──────────────────────────────────────────────────────

    @Test
    fun `compiles complete 2x2 Prisoner Dilemma`() {
        val result = GameModelCompiler.compile(prisoner())
        assertTrue("PD must compile", result is CompileResult.Success)
        val ir = (result as CompileResult.Success).ir
        assertEquals(2, ir.playerCount)
        assertEquals(listOf(2, 2), ir.strategyCounts)
        assertEquals(4, ir.profileCount)
        assertEquals(100, ir.coveragePct)
    }

    @Test
    fun `PD utility matrices match known payoffs`() {
        val unitWeights = mapOf(PayoffDimension.LONG_TERM_VALUE to 1.0)
        val ir = (GameModelCompiler.compile(prisoner(), weights = unitWeights) as CompileResult.Success).ir
        // Profile [0,0]=CC: P1=3, P2=3
        val cc = ir.profileIndex(intArrayOf(0, 0))
        assertEquals(3.0, ir.utility[0][cc], 1e-9)
        assertEquals(3.0, ir.utility[1][cc], 1e-9)
        // Profile [1,1]=DD: P1=1, P2=1
        val dd = ir.profileIndex(intArrayOf(1, 1))
        assertEquals(1.0, ir.utility[0][dd], 1e-9)
        assertEquals(1.0, ir.utility[1][dd], 1e-9)
    }

    @Test
    fun `infers ZERO_SUM_2P for Matching Pennies`() {
        val ir = (GameModelCompiler.compile(matchingPennies()) as CompileResult.Success).ir
        assertEquals(GameClass.ZERO_SUM_2P, ir.gameClass)
    }

    @Test
    fun `infers FINITE_NORMAL_FORM for PD (non-zero-sum)`() {
        val ir = (GameModelCompiler.compile(prisoner()) as CompileResult.Success).ir
        assertEquals(GameClass.FINITE_NORMAL_FORM, ir.gameClass)
    }

    @Test
    fun `compiles 3x3 Rock-Paper-Scissors`() {
        val result = GameModelCompiler.compile(rockPaperScissors())
        assertTrue(result is CompileResult.Success)
        val ir = (result as CompileResult.Success).ir
        assertEquals(listOf(3, 3), ir.strategyCounts)
        assertEquals(9, ir.profileCount)
        assertEquals(GameClass.ZERO_SUM_2P, ir.gameClass)
    }

    @Test
    fun `profileIndex row-major matches EquilibriumVerifier`() {
        val ir = (GameModelCompiler.compile(prisoner()) as CompileResult.Success).ir
        // [0,1] = P1 cooperate, P2 defect = index 0*2+1 = 1
        assertEquals(1, ir.profileIndex(intArrayOf(0, 1)))
        // [1,0] = P1 defect, P2 cooperate = index 1*2+0 = 2
        assertEquals(2, ir.profileIndex(intArrayOf(1, 0)))
    }

    // ── Error-path tests ─────────────────────────────────────────────────────

    @Test
    fun `fails with TooFewPlayers for single-player scenario`() {
        val s = Scenario(
            id = UUID.randomUUID().toString(), title = "Solo",
            stakeholders = listOf(Stakeholder("p1", "P1")),
            actions = listOf(Action("a1", "p1", "Act")),
            payoffs = emptyList()
        )
        val result = GameModelCompiler.compile(s)
        assertTrue(result is CompileResult.Failure)
        val errs = (result as CompileResult.Failure).errors
        assertTrue(errs.any { it is CompileError.TooFewPlayers })
    }

    @Test
    fun `fails with PlayerHasNoActions when a player has no actions`() {
        val s = Scenario(
            id = UUID.randomUUID().toString(), title = "Empty actions",
            stakeholders = listOf(Stakeholder("p1", "P1"), Stakeholder("p2", "P2")),
            actions = listOf(Action("a1", "p1", "Act")),
            payoffs = emptyList()
        )
        val result = GameModelCompiler.compile(s)
        assertTrue(result is CompileResult.Failure)
        val errs = (result as CompileResult.Failure).errors
        assertTrue(errs.any { it is CompileError.PlayerHasNoActions && it.playerId == "p2" })
    }

    @Test
    fun `fails with MissingPayoffProfiles when payoffs incomplete`() {
        val s = prisoner().copy(payoffs = prisoner().payoffs.take(4))
        val result = GameModelCompiler.compile(s)
        assertTrue(result is CompileResult.Failure)
        val errs = (result as CompileResult.Failure).errors
        assertTrue(errs.any { it is CompileError.MissingPayoffProfiles })
    }

    @Test
    fun `fails with TooManyPlayers when exceeding limits`() {
        val players = (1..5).map { Stakeholder("p$it", "P$it") }
        val actions = players.flatMap { p -> listOf(Action("a${p.id}", p.id, "Act")) }
        val s = Scenario(
            id = UUID.randomUUID().toString(), title = "Too many players",
            stakeholders = players, actions = actions, payoffs = emptyList()
        )
        val smallLimits = SolverLimits(maxPlayers = 2, maxActionsPerPlayer = 10,
            maxGameProfiles = 10_000, maxRuntimeMs = 5_000, maxWorkingMemoryBytes = 32L * 1024 * 1024)
        val result = GameModelCompiler.compile(s, limits = smallLimits)
        assertTrue(result is CompileResult.Failure)
        val errs = (result as CompileResult.Failure).errors
        assertTrue(errs.any { it is CompileError.TooManyPlayers })
    }

    @Test
    fun `fails with TooManyActions when exceeding limits`() {
        val actions = (1..12).map { Action("a$it", "p1", "Act$it") }
        val p2actions = listOf(Action("b1", "p2", "B1"), Action("b2", "p2", "B2"))
        val s = Scenario(
            id = UUID.randomUUID().toString(), title = "Too many actions",
            stakeholders = listOf(Stakeholder("p1", "P1"), Stakeholder("p2", "P2")),
            actions = actions + p2actions, payoffs = emptyList()
        )
        val smallLimits = SolverLimits(maxPlayers = 4, maxActionsPerPlayer = 10,
            maxGameProfiles = 10_000, maxRuntimeMs = 5_000, maxWorkingMemoryBytes = 32L * 1024 * 1024)
        val result = GameModelCompiler.compile(s, limits = smallLimits)
        assertTrue(result is CompileResult.Failure)
        val errs = (result as CompileResult.Failure).errors
        assertTrue(errs.any { it is CompileError.TooManyActions && it.playerId == "p1" })
    }

    // ── Scenario builders ────────────────────────────────────────────────────

    private fun payoff(player: String, profile: Map<String, String>, v: Double) =
        PayoffEntry(player, profile, MultiPayoff(mapOf(PayoffDimension.LONG_TERM_VALUE to v)))

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

    private fun rockPaperScissors(): Scenario {
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
            val (a1, a2) = k; val (u1, u2) = v
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
}
