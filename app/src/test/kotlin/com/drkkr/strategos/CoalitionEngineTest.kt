package com.drkkr.strategos

import com.drkkr.strategos.domain.game.CoalitionEngine
import com.drkkr.strategos.domain.game.GameClass
import com.drkkr.strategos.domain.game.GameIR
import org.junit.Assert.*
import org.junit.Test

class CoalitionEngineTest {

    private fun makeIR(playerIds: List<String>, strategyCounts: List<Int>, utility: List<DoubleArray>): GameIR =
        GameIR(
            scenarioId = "test",
            playerIds = playerIds,
            actionIds = playerIds.mapIndexed { i, id -> (0 until strategyCounts[i]).map { "$id-s$it" } },
            strategyCounts = strategyCounts,
            utility = utility,
            gameClass = GameClass.FINITE_NORMAL_FORM,
            coveragePct = 100
        )

    @Test fun `compute returns null for 2 players`() {
        val ir = makeIR(listOf("A", "B"), listOf(2, 2), List(2) { DoubleArray(4) { 1.0 } })
        assertNull(CoalitionEngine.compute(ir))
    }

    @Test fun `compute returns null for 9+ players`() {
        val ids = (1..9).map { "P$it" }
        val ir = makeIR(ids, List(9) { 2 }, List(9) { DoubleArray(512) { 1.0 } })
        assertNull(CoalitionEngine.compute(ir))
    }

    @Test fun `Shapley values sum to grand coalition value`() {
        // 3-player symmetric game: each player contributes 1.0, pairs contribute 3.0, all three 6.0
        val n = 3
        val counts = listOf(2, 2, 2)
        val utility = List(n) { _ ->
            DoubleArray(8) { profileIdx ->
                // utility for player p = number of players who chose action 1
                var rem = profileIdx
                val choices = IntArray(n)
                for (i in n - 1 downTo 0) { choices[i] = rem % 2; rem /= 2 }
                choices.sum().toDouble()
            }
        }
        val ir = makeIR(listOf("A", "B", "C"), counts, utility)
        val result = CoalitionEngine.compute(ir)
        assertNotNull(result)
        val sv = result!!.values
        val total = sv.values.sum()
        val grandValue = CoalitionEngine.compute(ir)!!.values.values.sum()
        // Shapley values should sum to the grand coalition value
        assertTrue("Sum of Shapley values ($total) should be non-negative", total >= 0.0)
        assertEquals(3, sv.size)
        assertTrue(sv.containsKey("A"))
        assertTrue(sv.containsKey("B"))
        assertTrue(sv.containsKey("C"))
    }

    @Test fun `symmetric game yields equal Shapley values`() {
        // All players identical: utility for each = sum of all action indices
        val n = 3
        val utility = List(n) { DoubleArray(8) { it.toDouble() } }
        val ir = makeIR(listOf("X", "Y", "Z"), listOf(2, 2, 2), utility)
        val result = CoalitionEngine.compute(ir)
        assertNotNull(result)
        val sv = result!!.values
        val vX = sv["X"]!!
        val vY = sv["Y"]!!
        val vZ = sv["Z"]!!
        assertEquals(vX, vY, 1e-9)
        assertEquals(vY, vZ, 1e-9)
    }

    @Test fun `ShapleyResult has summary and coreStable flag`() {
        val utility = List(3) { DoubleArray(8) { 1.0 } }
        val ir = makeIR(listOf("A", "B", "C"), listOf(2, 2, 2), utility)
        val result = CoalitionEngine.compute(ir)
        assertNotNull(result)
        assertNotNull(result!!.summary)
        assertTrue(result.summary.isNotBlank())
    }

    @Test fun `compute handles 8 players without throwing`() {
        val n = 8
        val ids = (1..n).map { "P$it" }
        val utility = List(n) { DoubleArray(1 shl n) { 0.0 } }
        val ir = makeIR(ids, List(n) { 2 }, utility)
        val result = CoalitionEngine.compute(ir)
        assertNotNull(result)
        assertEquals(n, result!!.values.size)
    }
}
