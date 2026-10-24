package com.drkkr.strategos.domain.analysis

import kotlin.math.exp
import kotlin.math.roundToInt

data class TrajectorySnapshot(
    val trustScore: Double,                    // 0..100
    val tensionScore: Double,                  // 0..100
    val sigmoidProgress: Double,               // 0..100 logistic S(t)
    val momentumDelta: Double,                 // change since the previous point, -25..+25
    val consistencyStreak: Int,                // consecutive non-reactive moves
    val activeArchetype: String,
    val positiveProjection: List<Double>,      // 5 future points if the user follows the Do's
    val negativeProjection: List<Double>,      // 5 future points if reactive habits repeat
    val phaseLabel: String
)

object TrajectoryCalculator {
    private const val STEPS = 5

    /**
     * S(x) = 100 / (1 + exp(-0.09 x)), x = (trust - 50) * 0.65 - (tension - 50) * 0.55 + min(streak * 3.5, 21).
     * Inputs are clamped, so the result is always within 0..100.
     */
    fun computeSigmoid(trustScore: Double, tensionScore: Double, consistencyStreak: Int): Double {
        val trust = trustScore.finiteOr(50.0).coerceIn(0.0, 100.0)
        val tension = tensionScore.finiteOr(50.0).coerceIn(0.0, 100.0)
        val streakBonus = (consistencyStreak.coerceAtLeast(0) * 3.5).coerceAtMost(21.0)
        val netHealth = (trust - 50.0) * 0.65 - (tension - 50.0) * 0.55 + streakBonus
        val raw = 100.0 / (1.0 + exp(-0.09 * netHealth))
        return (raw * 10.0).roundToInt() / 10.0
    }

    fun buildSnapshot(
        trustScore: Double, tensionScore: Double, previousSigmoid: Double?, consistencyStreak: Int, activeArchetype: String
    ): TrajectorySnapshot {
        val trust = trustScore.finiteOr(50.0).coerceIn(0.0, 100.0)
        val tension = tensionScore.finiteOr(50.0).coerceIn(0.0, 100.0)
        val streak = consistencyStreak.coerceAtLeast(0)
        val sigmoid = computeSigmoid(trust, tension, streak)
        val momentum = previousSigmoid?.let { (sigmoid - it).coerceIn(-25.0, 25.0) } ?: 0.0
        return TrajectorySnapshot(
            trustScore = trust, tensionScore = tension, sigmoidProgress = sigmoid, momentumDelta = momentum,
            consistencyStreak = streak, activeArchetype = activeArchetype,
            positiveProjection = project(trust, tension, streak, dTrust = 5.0, dTension = -6.0, positive = true),
            negativeProjection = project(trust, tension, streak, dTrust = -6.0, dTension = 8.0, positive = false),
            phaseLabel = phaseLabel(sigmoid, tension)
        )
    }

    fun phaseLabel(sigmoid: Double, tension: Double): String = when {
        sigmoid < 35.0 || tension >= 70.0 -> "Fragile / De-escalate First"
        sigmoid < 55.0 -> "Early Friction / Holding Ground"
        sigmoid < 75.0 -> "Tipping Point / Building Momentum"
        else -> "Compounding Trust & Stability"
    }

    private fun project(trust: Double, tension: Double, streak: Int, dTrust: Double, dTension: Double, positive: Boolean): List<Double> {
        var t = trust; var e = tension; var s = streak
        return List(STEPS) {
            t = (t + dTrust).coerceIn(0.0, 100.0); e = (e + dTension).coerceIn(0.0, 100.0)
            s = if (positive) s + 1 else 0
            computeSigmoid(t, e, s)
        }
    }

    private fun Double.finiteOr(d: Double) = if (isNaN() || isInfinite()) d else this
}
