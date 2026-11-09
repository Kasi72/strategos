package com.drkkr.strategos.domain.game

import kotlinx.serialization.Serializable

/**
 * Bayesian prior over [GameArchetype] values, updated per observed move profile.
 *
 * Prior is uniform over all 11 archetypes. Update rule: each archetype's likelihood
 * is weighted by how well the observed cooperation rate matches its characteristic
 * cooperation tendency, then renormalized.
 */
@Serializable
data class BeliefState(
    val archetypeProbabilities: Map<GameArchetype, Double>
) {
    /** Most probable archetype (MAP estimate). */
    val mostLikely: GameArchetype get() = archetypeProbabilities.maxByOrNull { it.value }?.key ?: GameArchetype.GENERIC

    /** Entropy of the distribution: 0 = certain, log2(11) ≈ 3.46 = maximally uncertain. */
    val entropy: Double get() = archetypeProbabilities.values
        .filter { it > 0.0 }
        .sumOf { p -> -p * Math.log(p) / Math.log(2.0) }
}

object BeliefStateTracker {

    /**
     * Cooperation tendency for each archetype: probability that a rational player cooperates
     * in this game type. Used as the likelihood function for Bayesian updates.
     */
    private val cooperationTendency: Map<GameArchetype, Double> = mapOf(
        GameArchetype.PRISONERS_DILEMMA  to 0.20,
        GameArchetype.CHICKEN            to 0.40,
        GameArchetype.STAG_HUNT          to 0.70,
        GameArchetype.BATTLE_OF_SEXES    to 0.55,
        GameArchetype.ZERO_SUM           to 0.05,
        GameArchetype.PURE_COORDINATION  to 0.90,
        GameArchetype.HARMONY            to 0.95,
        GameArchetype.DEADLOCK           to 0.10,
        GameArchetype.LEADER             to 0.60,
        GameArchetype.HERO               to 0.50,
        GameArchetype.GENERIC            to 0.50
    )

    /** Uniform prior over all archetypes. */
    fun uniformPrior(): BeliefState = BeliefState(
        GameArchetype.entries.associateWith { 1.0 / GameArchetype.entries.size }
    )

    /**
     * Bayesian update given an observed cooperation rate in [0,1].
     *
     * Likelihood model: Gaussian(mean = cooperationTendency[a], sigma = 0.25) evaluated at [observedCoopRate].
     * This avoids zero-probability collapse on any single observation.
     */
    fun update(prior: BeliefState, observedCoopRate: Double): BeliefState {
        val sigma = 0.25
        val raw = prior.archetypeProbabilities.mapValues { (archetype, priorP) ->
            val mu = cooperationTendency[archetype] ?: 0.5
            val diff = observedCoopRate - mu
            val likelihood = Math.exp(-0.5 * (diff / sigma) * (diff / sigma))
            priorP * likelihood
        }
        val total = raw.values.sum().takeIf { it > 0.0 } ?: 1.0
        return BeliefState(raw.mapValues { (_, v) -> v / total })
    }

    /**
     * Sequential update over a list of cooperation observations (one per turn).
     * Equivalent to folding [update] over the observations, starting from [prior].
     */
    fun updateAll(prior: BeliefState, coopRates: List<Double>): BeliefState =
        coopRates.fold(prior) { state, rate -> update(state, rate) }

    /**
     * Derive a cooperation rate from a [GameIR]'s archetype and payoff structure.
     * Used by [GameModelService] when no explicit turn history is available.
     */
    fun coopRateFromIR(ir: GameIR): Double {
        val base = cooperationTendency[ir.archetype] ?: 0.5
        // Adjust slightly toward 0.5 when payoff variance is high (more uncertainty)
        val payoffs = ir.utility.firstOrNull() ?: return base
        if (payoffs.isEmpty()) return base
        val mean = payoffs.average()
        val variance = payoffs.map { (it - mean) * (it - mean) }.average()
        val adjustmentScale = 0.1
        val adjustment = if (variance > 1.0) adjustmentScale * (0.5 - base) else 0.0
        return (base + adjustment).coerceIn(0.0, 1.0)
    }
}
