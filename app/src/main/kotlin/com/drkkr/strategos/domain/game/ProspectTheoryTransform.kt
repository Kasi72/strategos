package com.drkkr.strategos.domain.game

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign

/**
 * Transforms a [GameIR]'s utility matrix using a prospect-theory value function,
 * replacing expected-payoff maximisation with expected-utility under loss aversion.
 *
 * The Kahneman-Tversky value function:
 *   v(x) = x^α               if x ≥ 0  (gains)
 *   v(x) = -λ · (-x)^β       if x < 0  (losses)
 *
 * Standard parameters: α = β = 0.88, λ = 2.25.
 *
 * Usage: call [transform] before passing the [GameIR] to a solver. The result
 * is a new [GameIR] with rescaled utilities; all structural guarantees (finite,
 * non-NaN) are preserved. The [GameIR.gameClass] may change (e.g. a zero-sum
 * game may no longer be zero-sum after transformation).
 */
object ProspectTheoryTransform {

    data class Config(
        /** Gain curvature (diminishing sensitivity). Typical: 0.88. Range (0, 1]. */
        val alpha: Double = 0.88,
        /** Loss curvature. Typical: 0.88. Range (0, 1]. */
        val beta: Double  = 0.88,
        /** Loss-aversion coefficient. λ > 1 implies losses loom larger. Typical: 2.25. */
        val lambda: Double = 2.25,
        /** Reference point per player (defaults to 0 for all players). */
        val referencePoints: List<Double> = emptyList()
    ) {
        init {
            // alpha ≤ 0 or beta ≤ 0 causes 0.0.pow(negative) = Infinity in valueFunction,
            // which corrupts the utility matrix and produces NaN mixed strategies.
            require(alpha > 0.0) { "alpha must be positive, got $alpha" }
            require(beta > 0.0)  { "beta must be positive, got $beta" }
            require(lambda > 0.0) { "lambda must be positive, got $lambda" }
        }

        companion object {
            /**
             * Returns a [Config] with standard K-T parameters and reference points
             * set to the midpoint of the given payoff range.
             * Useful when the scenario's payoff scale is not 0–10.
             *
             * @param payoffMin minimum payoff across all players and profiles.
             * @param payoffMax maximum payoff across all players and profiles.
             * @param playerCount number of players; determines length of referencePoints.
             */
            fun fromScale(payoffMin: Double, payoffMax: Double, playerCount: Int): Config {
                val mid = (payoffMin + payoffMax) / 2.0
                return Config(
                    alpha = 0.88,
                    beta  = 0.88,
                    lambda = 2.25,
                    referencePoints = List(playerCount) { mid }
                )
            }
        }
    }

    val DEFAULT = Config()

    /** Human Layer (Nudge Ch.1): no curvature on a 0-10 scale, losses count about twice. Used with [referenceValue]. */
    val HUMAN_LAYER = Config(alpha = 1.0, beta = 1.0, lambda = 2.0)

    /**
     * Returns a new [GameIR] with utilities transformed by the prospect-theory
     * value function. The [GameIR.gameClass] is recomputed to reflect the change.
     */
    fun transform(ir: GameIR, config: Config = DEFAULT): GameIR {
        val refPoints = List(ir.playerCount) { p ->
            config.referencePoints.getOrElse(p) { 0.0 }
        }

        val newUtility = ir.utility.mapIndexed { p, payoffs ->
            val ref = refPoints[p]
            DoubleArray(payoffs.size) { i -> valueFunction(payoffs[i] - ref, config) }
        }

        // Recompute game class: after prospect-theory transform zero-sum may not hold.
        val isZeroSum = ir.playerCount == 2 && (0 until ir.profileCount).all { i ->
            abs(newUtility[0][i] + newUtility[1][i]) < 1e-9
        }
        val newClass = when {
            isZeroSum -> GameClass.ZERO_SUM_2P
            else      -> ir.gameClass.takeUnless { it == GameClass.ZERO_SUM_2P }
                ?: GameClass.FINITE_NORMAL_FORM
        }

        return ir.copy(utility = newUtility, gameClass = newClass)
    }

    fun valueFunction(x: Double, config: Config = DEFAULT): Double =
        if (x >= 0) x.pow(config.alpha)
        else -config.lambda * abs(x).pow(config.beta)

    /**
     * A value measured from a reference point but kept on the original scale: a gain over [reference] is unchanged,
     * a loss below it counts [Config.lambda] times. Exactly [x] when [x] equals [reference].
     */
    fun referenceValue(x: Double, reference: Double, config: Config = HUMAN_LAYER): Double =
        reference + valueFunction(x - reference, config)
}
