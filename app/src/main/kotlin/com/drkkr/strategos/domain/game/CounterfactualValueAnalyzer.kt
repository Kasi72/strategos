package com.drkkr.strategos.domain.game

/** E2: Counterfactual Value of Information — expected utility impact of resolving each proxy-filled payoff cell. */
object CounterfactualValueAnalyzer {

    data class CvoiEntry(val playerIndex: Int, val profileIndex: Int, val cvoi: Double)

    data class CvoiResult(
        /** Top cells sorted by CVOI descending. */
        val entries: List<CvoiEntry>,
        /** Highest CVOI value among all proxy cells. */
        val maxCvoi: Double,
        /** Number of cells with CVOI above half the maximum — indicates how spread the uncertainty is. */
        val highValueCells: Int
    )

    fun analyze(ir: GameIR, equilibria: List<EquilibriumProfile>): CvoiResult? {
        if (ir.proxy.isEmpty()) return null
        val pureProfile = equilibria.firstOrNull { it.pureProfile != null }?.pureProfile
        val eqProfileIdx = pureProfile?.let { ir.profileIndex(it.toIntArray()) }
        val entries = mutableListOf<CvoiEntry>()
        for (p in 0 until ir.playerCount) {
            if (p >= ir.proxy.size) continue
            val uArr = ir.utility[p]
            val minU = uArr.minOrNull() ?: continue
            val maxU = uArr.maxOrNull() ?: continue
            val range = maxU - minU
            if (range < 0.01) continue
            for (k in 0 until ir.profileCount) {
                if (!ir.proxy[p][k]) continue
                // CVOI = utility range * relevance weight (1.0 on equilibrium path, 0.3 elsewhere)
                val weight = if (k == eqProfileIdx) 1.0 else 0.3
                val cvoi = range * weight
                if (cvoi > 0.01) entries.add(CvoiEntry(p, k, cvoi))
            }
        }
        if (entries.isEmpty()) return null
        val sorted = entries.sortedByDescending { it.cvoi }
        val maxCvoi = sorted.first().cvoi
        val highValueCells = entries.count { it.cvoi > maxCvoi * 0.5 }
        return CvoiResult(sorted.take(5), maxCvoi, highValueCells)
    }
}
