package com.drkkr.strategos.domain.game

import kotlin.math.abs

/**
 * Selects a single focal equilibrium from a set of Nash equilibria using the
 * Harsanyi-Selten refinement hierarchy:
 *
 *   1. Payoff dominance — a Pareto-superior NE exists (weakly better for all, strict for one).
 *   2. Risk dominance — Harsanyi-Selten product criterion for 2-player games:
 *      (u_i(e1,e1) - u_i(e2,e1)) * (u_j(e1,e1) - u_j(e1,e2))
 *      vs the same product at e2.  Higher product wins.
 *   3. Schelling focal — symmetric pure NE (identical action labels for both players).
 *   4. Arbitrary — first equilibrium returned.
 */
object FocalEquilibriumSelector {

    enum class SelectionCriterion {
        PAYOFF_DOMINANT,
        RISK_DOMINANT,
        SCHELLING_FOCAL,
        ARBITRARY
    }

    data class FocalSelection(
        val equilibrium: EquilibriumProfile,
        val criterion: SelectionCriterion,
        val riskDominanceMargin: Double?,   // positive = how much stronger than runner-up
        val isParetoOptimal: Boolean
    )

    /**
     * @param equilibria non-empty list of verified equilibria
     * @param ir the GameIR used to compute payoffs (pre-pruning IR for label lookup)
     */
    fun select(equilibria: List<EquilibriumProfile>, ir: GameIR): FocalSelection {
        require(equilibria.isNotEmpty()) { "Cannot select from empty equilibrium list" }

        if (equilibria.size == 1) {
            return FocalSelection(equilibria.first(), SelectionCriterion.ARBITRARY, null, true)
        }

        // ── 1. Payoff dominance ──────────────────────────────────────────────
        val payoffDominant = equilibria.firstOrNull { candidate ->
            equilibria.all { other ->
                if (candidate === other) return@all true
                val candPayoffs = expectedPayoffs(candidate, ir)
                val otherPayoffs = expectedPayoffs(other, ir)
                var strictOnce = false
                val weaklyBetter = candPayoffs.indices.all { p ->
                    if (candPayoffs[p] > otherPayoffs[p]) strictOnce = true
                    candPayoffs[p] >= otherPayoffs[p]
                }
                weaklyBetter && strictOnce
            }
        }
        if (payoffDominant != null) {
            return FocalSelection(payoffDominant, SelectionCriterion.PAYOFF_DOMINANT, null, true)
        }

        // ── 2. Risk dominance (Harsanyi-Selten, 2-player pure NE only) ───────
        if (ir.playerCount == 2 && equilibria.size == 2) {
            val e1 = equilibria[0]
            val e2 = equilibria[1]
            val e1Pure = e1.pureProfile
            val e2Pure = e2.pureProfile
            if (e1Pure != null && e2Pure != null) {
                val a1 = e1Pure[0]; val b1 = e1Pure[1]  // e1: (a1, b1)
                val a2 = e2Pure[0]; val b2 = e2Pure[1]  // e2: (a2, b2)

                // Product criterion for e1
                val loss1P0 = ir.utility[0][ir.profileIndex(intArrayOf(a1, b1))] -
                              ir.utility[0][ir.profileIndex(intArrayOf(a2, b1))]
                val loss1P1 = ir.utility[1][ir.profileIndex(intArrayOf(a1, b1))] -
                              ir.utility[1][ir.profileIndex(intArrayOf(a1, b2))]
                val product1 = loss1P0 * loss1P1

                // Product criterion for e2
                val loss2P0 = ir.utility[0][ir.profileIndex(intArrayOf(a2, b2))] -
                              ir.utility[0][ir.profileIndex(intArrayOf(a1, b2))]
                val loss2P1 = ir.utility[1][ir.profileIndex(intArrayOf(a2, b2))] -
                              ir.utility[1][ir.profileIndex(intArrayOf(a2, b1))]
                val product2 = loss2P0 * loss2P1

                if (abs(product1 - product2) > 1e-9) {
                    return if (product1 > product2) {
                        FocalSelection(e1, SelectionCriterion.RISK_DOMINANT, product1 - product2, false)
                    } else {
                        FocalSelection(e2, SelectionCriterion.RISK_DOMINANT, product2 - product1, false)
                    }
                }
            }
        }

        // ── 3. Schelling focal — symmetric pure NE ───────────────────────────
        val symmetric = equilibria.firstOrNull { eq ->
            val p = eq.pureProfile ?: return@firstOrNull false
            if (ir.playerCount < 2) return@firstOrNull false
            ir.actionIds[0].getOrNull(p[0]) != null &&
            ir.actionIds[1].getOrNull(p[1]) != null &&
            ir.actionIds[0][p[0]].equals(ir.actionIds[1][p[1]], ignoreCase = true)
        }
        if (symmetric != null) {
            return FocalSelection(symmetric, SelectionCriterion.SCHELLING_FOCAL, null, false)
        }

        // ── 4. Fallback ───────────────────────────────────────────────────────
        return FocalSelection(equilibria.first(), SelectionCriterion.ARBITRARY, null, false)
    }

    /** Expected payoff vector at a given equilibrium under the IR's utility. */
    private fun expectedPayoffs(eq: EquilibriumProfile, ir: GameIR): DoubleArray {
        val payoffs = DoubleArray(ir.playerCount)
        val profile = IntArray(ir.playerCount)

        fun recurse(p: Int, prob: Double) {
            if (prob < 1e-15) return
            if (p == ir.playerCount) {
                val idx = ir.profileIndex(profile)
                for (pl in 0 until ir.playerCount) payoffs[pl] += prob * ir.utility[pl][idx]
                return
            }
            for (a in 0 until ir.strategyCounts[p]) {
                val pA = eq.mixedStrategies[p][a]
                if (pA > 1e-15) {
                    profile[p] = a
                    recurse(p + 1, prob * pA)
                }
            }
        }
        recurse(0, 1.0)
        return payoffs
    }
}
