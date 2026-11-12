package com.drkkr.strategos.domain.game

import kotlin.random.Random

/**
 * E3: Bayesian game completion — sample proxy-cell completions and find the most robust strategy for player 0.
 * Gives confidence that the recommendation survives payoff uncertainty.
 */
object BayesianGameCompletion {

    data class BayesianCompletionResult(
        /** Index into player 0's strategy list that is best in the most completions. */
        val robustStrategyIndex: Int,
        /** Fraction of completions in which this strategy was optimal (0–1). */
        val robustFrequency: Double,
        val note: String
    )

    private const val SAMPLES = 60

    fun analyze(ir: GameIR): BayesianCompletionResult? {
        if (ir.proxy.isEmpty() || ir.playerCount < 2) return null
        val allU = ir.utility.flatMap { it.toList() }
        val minU = allU.minOrNull() ?: return null
        val maxU = allU.maxOrNull() ?: return null
        if (maxU <= minU) return null
        val n0 = ir.strategyCounts[0]
        val n1 = ir.strategyCounts.getOrElse(1) { 1 }
        val winCounts = IntArray(n0)
        val rng = Random(42L)

        repeat(SAMPLES) {
            val u0 = DoubleArray(ir.profileCount) { k ->
                if (ir.proxy.isNotEmpty() && k < ir.proxy[0].size && ir.proxy[0][k])
                    minU + rng.nextDouble() * (maxU - minU)
                else ir.utility[0][k]
            }
            // Best response for player 0 against a uniform opponent mix
            var bestIdx = 0; var bestEv = Double.NEGATIVE_INFINITY
            for (i in 0 until n0) {
                var ev = 0.0
                for (j in 0 until n1) ev += u0[ir.profileIndex(intArrayOf(i, j))]
                ev /= n1
                if (ev > bestEv) { bestEv = ev; bestIdx = i }
            }
            winCounts[bestIdx]++
        }

        val robustIdx = winCounts.indices.maxByOrNull { winCounts[it] } ?: 0
        val freq = winCounts[robustIdx].toDouble() / SAMPLES
        val note = "Strategy $robustIdx is robust in ${(freq * 100).toInt()}% of proxy-cell completions"
        return BayesianCompletionResult(robustIdx, freq, note)
    }
}
