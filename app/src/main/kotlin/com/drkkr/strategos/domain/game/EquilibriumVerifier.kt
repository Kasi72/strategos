package com.drkkr.strategos.domain.game

/**
 * Independent Nash-equilibrium verifier via deviation-gain check.
 *
 *   ε = max_i [ max_{a_i'} u_i(a_i', σ_{-i}) − u_i(σ) ]
 *
 * Lives entirely outside the solvers and is never called by them.
 * Used as the post-hoc verification step: if ε ≤ tolerance the
 * candidate profile is a Nash equilibrium; if ε > 0 it is not.
 */
object EquilibriumVerifier {

    /**
     * Maximum unilateral deviation gain across all players.
     *
     * @param utilities  utilities[playerIndex][profileIndex] — NaN-free
     * @param strategyCounts number of strategies for each player
     * @param mixedProfile  mixedProfile[playerIndex][strategyIndex] = probability (sums to 1)
     * @return maximum gain any player can achieve by deviating unilaterally;
     *         value ≤ 0 certifies a Nash equilibrium
     */
    fun deviationGainResidual(
        utilities: List<DoubleArray>,
        strategyCounts: List<Int>,
        mixedProfile: List<DoubleArray>
    ): Double {
        val n = strategyCounts.size
        require(utilities.size == n) { "utilities.size must equal player count" }
        require(mixedProfile.size == n) { "mixedProfile.size must equal player count" }
        for (i in 0 until n) {
            require(mixedProfile[i].size == strategyCounts[i]) {
                "mixedProfile[$i].size ${mixedProfile[i].size} ≠ strategyCounts[$i] ${strategyCounts[i]}"
            }
        }

        var maxGain = Double.NEGATIVE_INFINITY
        for (player in 0 until n) {
            val current = expectedUtil(utilities[player], strategyCounts, mixedProfile, player, null)
            for (alt in 0 until strategyCounts[player]) {
                val dev = expectedUtil(utilities[player], strategyCounts, mixedProfile, player, alt)
                maxGain = maxOf(maxGain, dev - current)
            }
        }
        return maxGain
    }

    /** True when deviationGainResidual ≤ tolerance. */
    fun isNashEquilibrium(
        utilities: List<DoubleArray>,
        strategyCounts: List<Int>,
        mixedProfile: List<DoubleArray>,
        tolerance: Double = 1e-9
    ): Boolean = deviationGainResidual(utilities, strategyCounts, mixedProfile) <= tolerance

    // ── internals ──────────────────────────────────────────────────────────

    private fun expectedUtil(
        playerUtils: DoubleArray,
        strategyCounts: List<Int>,
        mixedProfile: List<DoubleArray>,
        player: Int,
        fixedAction: Int?
    ): Double {
        val n = strategyCounts.size
        var total = 0.0
        val profile = IntArray(n)

        fun recurse(p: Int, prob: Double) {
            if (p == n) {
                total += prob * playerUtils[profileIndex(profile, strategyCounts)]
                return
            }
            val probs: DoubleArray = if (p == player && fixedAction != null)
                DoubleArray(strategyCounts[p]) { s -> if (s == fixedAction) 1.0 else 0.0 }
            else
                mixedProfile[p]

            for (s in 0 until strategyCounts[p]) {
                if (probs[s] > 0.0) {
                    profile[p] = s
                    recurse(p + 1, prob * probs[s])
                }
            }
        }
        recurse(0, 1.0)
        return total
    }

    /** Row-major flat index matching MatrixSupport.StrategicForm.profileIndex(). */
    fun profileIndex(profile: IntArray, strategyCounts: List<Int>): Int {
        var idx = 0
        var stride = 1
        for (p in strategyCounts.indices.reversed()) {
            idx += profile[p] * stride
            stride *= strategyCounts[p]
        }
        return idx
    }
}
