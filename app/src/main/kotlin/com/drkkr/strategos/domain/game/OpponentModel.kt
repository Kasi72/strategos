package com.drkkr.strategos.domain.game

import com.drkkr.strategos.domain.model.Scenario

/**
 * Rationality profile for one player derived from learned Bayesian priors.
 *
 * epsilon  — estimated probability the player makes a non-optimal move (0 = fully rational,
 *            1 = completely random). Derived from how frequently observed behavior deviates
 *            from the game-theoretically predicted response.
 * discountFactor — how much the player weights future vs. immediate payoffs (0 = myopic, 1 = patient).
 *            Inferred from their propensity to escalate (myopic) vs. soften (patient).
 */
data class OpponentModel(
    val playerId: String,
    /** Epsilon-rationality: noise probability in [0, 1]. */
    val epsilon: Double,
    /** Long-term discount factor in [0, 1]. */
    val discountFactor: Double
) {
    /** One-line description for the Coach prompt. */
    fun coachNote(): String {
        val rationality = when {
            epsilon < 0.15 -> "highly rational"
            epsilon < 0.35 -> "mostly rational"
            epsilon < 0.60 -> "somewhat unpredictable"
            else -> "largely unpredictable"
        }
        val patience = when {
            discountFactor > 0.75 -> "patient (values long-term outcomes)"
            discountFactor > 0.45 -> "moderate time horizon"
            else -> "short-sighted (values immediate outcomes)"
        }
        return "$rationality, $patience"
    }
}

object OpponentModelBuilder {

    private const val NEUTRAL = 0.5
    private const val EPSILON_SCALE = 0.5

    /**
     * Derives an [OpponentModel] for the primary counterpart from the scenario's learned priors.
     * Returns null when the scenario has no opponent or no priors have been learned yet.
     */
    fun fromScenario(scenario: Scenario): OpponentModel? {
        val players = scenario.players()
        if (players.size < 2) return null
        val self = players.first()   // the user is conventionally the first player
        val opponent = players.firstOrNull { it.id != self.id } ?: return null

        // Need at least one learned prior to derive a useful model
        val pSoftens = opponent.probSoftensOnRepair ?: return null
        val pBoundary = opponent.probRespectsBoundary ?: NEUTRAL
        val pEscalates = opponent.probEscalatesOnReactive ?: NEUTRAL

        // Epsilon: high escalation + low softening = more irrational/reactive behavior
        // A fully cooperative opponent (pSoftens=1, pEscalates=0) → epsilon=0
        val irrationality = ((1.0 - pSoftens) + pEscalates + (1.0 - pBoundary)) / 3.0
        val epsilon = (irrationality * EPSILON_SCALE).coerceIn(0.0, 1.0)

        // Discount: patient opponents soften (invest in relationship) and respect boundaries (long-term)
        val discountFactor = ((pSoftens + pBoundary + (1.0 - pEscalates)) / 3.0).coerceIn(0.0, 1.0)

        return OpponentModel(
            playerId = opponent.id,
            epsilon = epsilon,
            discountFactor = discountFactor
        )
    }
}
