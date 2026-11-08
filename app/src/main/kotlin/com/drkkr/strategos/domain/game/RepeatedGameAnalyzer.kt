package com.drkkr.strategos.domain.game

/**
 * Folk theorem threshold analysis for infinitely repeated games.
 *
 * For a 2×2 Prisoner's Dilemma with payoffs T > R > P > S, cooperation is
 * sustainable as a subgame-perfect Nash equilibrium (via grim trigger) iff
 * the discount factor δ ≥ δ* = (T - R) / (T - P).
 *
 * This object accepts a [GameIR] with archetype PRISONERS_DILEMMA or any
 * symmetric 2×2 normal-form game, plus the scenario's discountFactor, and
 * returns a human-readable analysis.
 */
object RepeatedGameAnalyzer {

    data class FolkThresholdResult(
        /** Minimum discount factor needed to sustain cooperation. */
        val deltaThreshold: Double,
        /** The scenario's actual discount factor (null if not supplied). */
        val deltaActual: Double?,
        /** True iff deltaActual != null && deltaActual >= deltaThreshold. */
        val cooperationSustainable: Boolean,
        /** Plain-language summary for the coach to use. */
        val summary: String
    )

    /**
     * Compute the folk theorem cooperation threshold for a 2×2 PD-structured game.
     *
     * Payoff labelling (player 0's row-major utility[0]):
     *   index 0 → (Cooperate, Cooperate) = R
     *   index 1 → (Cooperate, Defect)    = S
     *   index 2 → (Defect,    Cooperate) = T
     *   index 3 → (Defect,    Defect)    = P
     *
     * Returns null if the game is not 2×2 or the PD ordering is not satisfied.
     */
    fun analyze(ir: GameIR, discountFactor: Double?): FolkThresholdResult? {
        if (ir.playerCount != 2 || ir.strategyCounts != listOf(2, 2)) return null
        val u = ir.utility[0]
        // row 0 = cooperate (action 0 for each player)
        val r = u[ir.profileIndex(intArrayOf(0, 0))]  // R: both cooperate
        val s = u[ir.profileIndex(intArrayOf(0, 1))]  // S: I cooperate, they defect
        val t = u[ir.profileIndex(intArrayOf(1, 0))]  // T: I defect, they cooperate
        val p = u[ir.profileIndex(intArrayOf(1, 1))]  // P: both defect
        if (!(t > r && r > p && p > s)) return null   // must be PD ordering

        val denom = t - p
        if (denom < 1e-9) return null  // degenerate
        val threshold = (t - r) / denom

        val sustainable = discountFactor != null && discountFactor >= threshold
        val summary = if (discountFactor == null) {
            "Cooperation is theoretically sustainable when the shadow of the future is long enough " +
                "(discount factor δ ≥ ${formatPct(threshold)}). " +
                "Provide a discount factor to get a definitive verdict."
        } else if (sustainable) {
            "With δ = ${formatPct(discountFactor)} ≥ ${formatPct(threshold)}, " +
                "cooperation is a subgame-perfect equilibrium under grim trigger. " +
                "The shadow of the future is long enough to deter defection."
        } else {
            "With δ = ${formatPct(discountFactor)} < ${formatPct(threshold)}, " +
                "defection cannot be deterred by the folk theorem alone. " +
                "Consider external enforcement, reputation mechanisms, or shorter interaction gaps."
        }
        return FolkThresholdResult(threshold, discountFactor, sustainable, summary)
    }

    private fun formatPct(v: Double) = "%.3f".format(v)
}
