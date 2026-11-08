package com.drkkr.strategos

import com.drkkr.strategos.domain.game.CoalitionEngine
import com.drkkr.strategos.domain.game.GameClass
import com.drkkr.strategos.domain.game.GameIR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 1: ShapleyResult now includes recruitmentOrder and spoilers. */
class CoalitionSequencerTest {

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

    /**
     * 4-player game where each player contributes a fixed incremental value equal to their index + 1
     * (P1=1, P2=2, P3=3, P4=4). The coalition value = sum of members.
     * Expected Shapley values: each player i has value = (i+1) exactly (additive game).
     * So recruitment order = P4, P3, P2, P1 (descending).
     * No player has negative Shapley, so spoilers should be empty.
     */
    @Test fun TC_COALSEQ_01_recruitmentOrderIsDescendingByShapleyValue() {
        val n = 4
        val ids = listOf("P1", "P2", "P3", "P4")
        // Additive game: coalition value = sum of (player index + 1) for each member
        val utility = Array(n) { playerIdx ->
            val playerValue = (playerIdx + 1).toDouble()
            DoubleArray(1 shl n) { profileIdx ->
                // In a 2-strategy-per-player game, utility = player value regardless of strategy chosen
                playerValue
            }
        }.toList()
        val ir = makeIR(ids, List(n) { 2 }, utility)
        val result = CoalitionEngine.compute(ir) ?: error("compute returned null")

        // Recruitment order should be descending by Shapley value (P4 first, then P3, P2, P1)
        val order = result.recruitmentOrder
        assertEquals(n, order.size)
        // Verify strictly descending Shapley values in the order
        for (i in 0 until order.size - 1) {
            val vi = result.values[order[i]] ?: 0.0
            val vi1 = result.values[order[i + 1]] ?: 0.0
            assertTrue("Order[${i}]=$vi should >= order[${i+1}]=$vi1", vi >= vi1)
        }
        // Highest-value player should be P4
        assertEquals("P4", order.first())
    }

    @Test fun TC_COALSEQ_02_spoilersArePlayersWithNegativeShapleyValue() {
        // 3-player game where P3 has negative marginal contribution:
        // utility matrix: P1 and P2 together = 10, adding P3 hurts (P3 contributes -2 everywhere)
        val n = 3
        val ids = listOf("P1", "P2", "P3")
        // utility[player][profileIdx]: P1 and P2 get 5 each, P3 gets -2 always
        val utility = listOf(
            DoubleArray(8) { 5.0 },   // P1 always 5
            DoubleArray(8) { 5.0 },   // P2 always 5
            DoubleArray(8) { -2.0 }   // P3 always -2
        )
        val ir = makeIR(ids, List(n) { 2 }, utility)
        val result = CoalitionEngine.compute(ir) ?: error("compute returned null")
        assertTrue("P3 should be a spoiler", result.spoilers.contains("P3"))
        assertTrue("P1 should not be a spoiler", !result.spoilers.contains("P1"))
        assertTrue("P2 should not be a spoiler", !result.spoilers.contains("P2"))
    }

    @Test fun TC_COALSEQ_03_noSpoilersWhenAllShapleyValuesNonNegative() {
        // Symmetric 3-player game: everyone contributes equally
        val n = 3
        val ids = listOf("A", "B", "C")
        val utility = List(n) { DoubleArray(8) { 3.0 } }
        val ir = makeIR(ids, List(n) { 2 }, utility)
        val result = CoalitionEngine.compute(ir) ?: error("compute returned null")
        assertTrue("No spoilers expected", result.spoilers.isEmpty())
        assertEquals(n, result.recruitmentOrder.size)
    }
}
