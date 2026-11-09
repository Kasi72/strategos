package com.drkkr.strategos.domain.game

/** Result of minimax regret analysis on a 2-player matrix game. */
data class RegretResult(
    /** Index into player 0's strategy list that minimises worst-case regret. */
    val strategy: Int,
    /** Worst-case regret (in payoff units) for the minimax-regret strategy. */
    val worstCaseRegret: Double,
    /** Gap between the best and second-best minimax regret values; larger = more decisive. */
    val regretGap: Double
)

/**
 * Solves minimax regret for the user (player 0) in a 2-player normal-form game.
 *
 * Returns null for games with ≠ 2 players, empty strategy sets, or under-sized utility arrays.
 * Operates on [GameIR.utility][0] only; does not read android.* or androidx.*.
 */
object MinimaxRegretSolver {

    fun solve(ir: GameIR): RegretResult? {
        if (ir.playerCount != 2) return null
        val m = ir.strategyCounts.getOrNull(0)?.takeIf { it > 0 } ?: return null
        val n = ir.strategyCounts.getOrNull(1)?.takeIf { it > 0 } ?: return null
        val u0 = ir.utility.getOrNull(0)?.takeIf { it.size >= m * n } ?: return null

        // For each of player-0's strategies, compute worst-case regret over all opponent strategies.
        val worst = DoubleArray(m) { s ->
            (0 until n).maxOf { t ->
                val bestForT = (0 until m).maxOf { sp -> u0[sp * n + t] }
                bestForT - u0[s * n + t]
            }
        }

        val minRegret = worst.minOrNull() ?: return null
        val strategy = worst.indexOfFirst { it == minRegret }
        val sorted = worst.sorted()
        val gap = if (sorted.size >= 2) sorted[1] - sorted[0] else 0.0

        return RegretResult(strategy = strategy, worstCaseRegret = minRegret, regretGap = gap)
    }
}
