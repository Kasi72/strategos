package com.drkkr.strategos.domain.game

/**
 * Level-k cognitive hierarchy solver.
 *
 * Level-0: uniform random over all strategies (non-strategic baseline).
 * Level-k (k ≥ 1): best response to the level-(k-1) mixed strategy of the opponent.
 *
 * The opponent's level is inferred from [OpponentModel.epsilon]:
 *   epsilon ≥ 0.60 → level 0 (unpredictable / non-strategic)
 *   epsilon ∈ [0.35, 0.60) → level 1
 *   epsilon ∈ [0.15, 0.35) → level 2
 *   epsilon < 0.15         → level 3
 *
 * Only meaningful for 2-player games with ≥ 1 strategy per player; returns null otherwise.
 */
data class EpistemicResult(
    /** Inferred cognitive level of the opponent (0–3). */
    val opponentLevel: Int,
    /** Best-response strategy index for player 0 (the user) against the inferred opponent level. */
    val yourBestResponse: Int,
    /** Human-readable explanation of the reasoning. */
    val reasoning: String
)

object EpistemicDepthSolver {

    fun solve(ir: GameIR, opponentModel: OpponentModel?): EpistemicResult? {
        if (ir.playerCount != 2) return null
        val m = ir.strategyCounts.getOrNull(0)?.takeIf { it > 0 } ?: return null
        val n = ir.strategyCounts.getOrNull(1)?.takeIf { it > 0 } ?: return null
        val u0 = ir.utility.getOrNull(0)?.takeIf { it.size >= m * n } ?: return null

        val epsilon = opponentModel?.epsilon ?: 0.5
        val opponentLevel = inferLevel(epsilon)

        // Build the opponent's mixed strategy at their level
        val opponentMix = buildOpponentMix(opponentLevel, ir, m, n)

        // Best response for player 0: argmax over strategies s of sum_t opponentMix[t] * u0[s*n+t]
        val expectedPayoffs = DoubleArray(m) { s ->
            (0 until n).sumOf { t -> opponentMix[t] * u0[s * n + t] }
        }
        val best = expectedPayoffs.indices.maxByOrNull { expectedPayoffs[it] } ?: 0
        val reasoning = buildReasoning(opponentLevel, epsilon, best, expectedPayoffs)

        return EpistemicResult(
            opponentLevel = opponentLevel,
            yourBestResponse = best,
            reasoning = reasoning
        )
    }

    private fun inferLevel(epsilon: Double): Int = when {
        epsilon >= 0.60 -> 0
        epsilon >= 0.35 -> 1
        epsilon >= 0.15 -> 2
        else            -> 3
    }

    /**
     * Returns the opponent's mixed strategy at the given level.
     * Level-0: uniform. Level-k: best-response point mass against level-(k-1).
     */
    private fun buildOpponentMix(level: Int, ir: GameIR, m: Int, n: Int): DoubleArray {
        if (level == 0) return DoubleArray(n) { 1.0 / n }

        val u1 = ir.utility.getOrNull(1)
        // If player-1 utility unavailable, fall back to level-0
        if (u1 == null || u1.size < m * n) return DoubleArray(n) { 1.0 / n }

        // Build player-0 mix at level (level-1) to find opponent's best response
        val p0Mix = buildPlayer0Mix(level - 1, ir, m, n)

        // Opponent (player 1) best-response: argmax over t of sum_s p0Mix[s] * u1[s*n+t]
        val oppPayoffs = DoubleArray(n) { t ->
            (0 until m).sumOf { s -> p0Mix[s] * u1[s * n + t] }
        }
        val best = oppPayoffs.indices.maxByOrNull { oppPayoffs[it] } ?: 0
        return DoubleArray(n) { t -> if (t == best) 1.0 else 0.0 }
    }

    private fun buildPlayer0Mix(level: Int, ir: GameIR, m: Int, n: Int): DoubleArray {
        if (level == 0) return DoubleArray(m) { 1.0 / m }
        val opponentMix = buildOpponentMix(level, ir, m, n)
        val u0 = ir.utility.getOrNull(0) ?: return DoubleArray(m) { 1.0 / m }
        val payoffs = DoubleArray(m) { s ->
            (0 until n).sumOf { t -> opponentMix[t] * u0[s * n + t] }
        }
        val best = payoffs.indices.maxByOrNull { payoffs[it] } ?: 0
        return DoubleArray(m) { s -> if (s == best) 1.0 else 0.0 }
    }

    private fun buildReasoning(
        opponentLevel: Int,
        epsilon: Double,
        bestResponse: Int,
        expectedPayoffs: DoubleArray
    ): String {
        val levelDesc = when (opponentLevel) {
            0 -> "non-strategic (plays randomly)"
            1 -> "level-1 (best-responds to random play)"
            2 -> "level-2 (anticipates level-1 reasoning)"
            else -> "level-3 (anticipates level-2 reasoning)"
        }
        val epsilonPct = "%.0f%%".format(epsilon * 100)
        return "Opponent appears $levelDesc (epsilon=$epsilonPct noise). " +
               "Your best response is strategy index $bestResponse " +
               "(expected payoff ${"%.2f".format(expectedPayoffs[bestResponse])})."
    }
}
