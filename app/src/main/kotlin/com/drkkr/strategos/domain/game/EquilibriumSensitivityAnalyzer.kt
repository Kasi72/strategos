package com.drkkr.strategos.domain.game

/** E1: Minimum payoff perturbation needed to flip the equilibrium recommendation. */
object EquilibriumSensitivityAnalyzer {

    data class SensitivityResult(
        /** Minimum absolute payoff change that would cause the equilibrium to flip. Null when no pure NE exists. */
        val flipMargin: Double?,
        /** True when flipMargin > 1.0 — robust to typical payoff estimation error. */
        val isRobust: Boolean,
        val note: String
    )

    fun analyze(ir: GameIR, equilibria: List<EquilibriumProfile>): SensitivityResult? {
        if (ir.playerCount != 2) return null
        val pureProfile = equilibria.firstOrNull { it.pureProfile != null }?.pureProfile ?: return null
        if (pureProfile.size < 2) return null
        val n0 = ir.strategyCounts[0]; val n1 = ir.strategyCounts[1]
        val e0 = pureProfile[0]; val e1 = pureProfile[1]
        var minGap = Double.MAX_VALUE

        // Player 0 flip margin: for each opponent strategy j, how much headroom does i0 have over the best deviation?
        for (j in 0 until n1) {
            val eqU = ir.utility[0][ir.profileIndex(intArrayOf(e0, j))]
            val bestDev = (0 until n0).filter { it != e0 }.maxOfOrNull { i ->
                ir.utility[0][ir.profileIndex(intArrayOf(i, j))]
            } ?: continue
            minGap = minOf(minGap, eqU - bestDev)
        }
        // Player 1 flip margin: for each user strategy i, how much headroom does j1 have?
        for (i in 0 until n0) {
            val eqU = ir.utility[1][ir.profileIndex(intArrayOf(i, e1))]
            val bestDev = (0 until n1).filter { it != e1 }.maxOfOrNull { j ->
                ir.utility[1][ir.profileIndex(intArrayOf(i, j))]
            } ?: continue
            minGap = minOf(minGap, eqU - bestDev)
        }

        val margin = if (minGap == Double.MAX_VALUE) null else minGap
        val isRobust = margin != null && margin > 1.0
        val note = when {
            margin == null    -> "Sensitivity unavailable — no pure-strategy equilibrium found"
            margin < 0.0      -> "Equilibrium at risk — current payoff estimates show a margin of ${String.format("%.2f", margin)}"
            isRobust          -> "Equilibrium robust — payoffs must shift by ${String.format("%.2f", margin)} to flip"
            else              -> "Equilibrium sensitive — a shift of ${String.format("%.2f", margin)} in any payoff could change the recommendation"
        }
        return SensitivityResult(margin, isRobust, note)
    }
}
