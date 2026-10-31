package com.drkkr.strategos.domain.game

import com.drkkr.strategos.domain.model.Scenario

/**
 * Applies the latest learned Bayesian priors from [Scenario] to the compiled [GameIR],
 * so even payoffs saved in earlier turns benefit from what the system has since learned
 * about the opponent's likely responses.
 *
 * The priors (probSoftensOnRepair, probRespectsBoundary, probEscalatesOnReactive) are stored
 * on the opponent [Stakeholder]. They express conditional probabilities of the opponent's
 * behavioral response given the user's action posture. [BayesianPayoffAdjustment] in
 * Postures.kt already applies these at sketch time; this adjuster re-applies at solver time
 * so converged priors always feed the current solve, even for old payoff data.
 *
 * Implementation:
 *   For each profile, identify the user action's posture tag and the opponent action's posture tag.
 *   Scale the user's utility by (priorProb / uniformProb) so actions with more likely opponent
 *   responses are weighted up and those with less likely responses are weighted down.
 *   The adjustment is bounded to [MIN_SCALE, MAX_SCALE] to avoid degenerate matrices.
 *
 * No-op when:
 *   - The game has more than 2 players (priors are per-dyad only).
 *   - The opponent has no priors (all null → uniform, so scale = 1.0 everywhere).
 *   - No action has a known posture tag.
 */
object BayesianPayoffAdjuster {

    private const val MIN_SCALE = 0.5
    private const val MAX_SCALE = 2.0
    private const val NEUTRAL = 0.5

    // Posture tag constants; must match Postures.kt without importing agent package.
    private const val REPAIR = "repair"
    private const val BOUNDARY = "boundary"
    private const val REACTIVE = "reactive"
    private const val RECEPTIVE = "receptive"
    private const val DEFENSIVE = "defensive"
    private val TAG = Regex("\\[posture:(\\w+)]")

    fun adjust(ir: GameIR, scenario: Scenario): GameIR {
        if (ir.playerCount != 2) return ir

        // Find user (player index 0) and opponent (player index 1) in the scenario
        val actionById = scenario.actions.associateBy { it.id }
        val players = scenario.players()
        if (players.size < 2) return ir

        val userPlayerId = ir.playerIds[0]
        val oppPlayerId = ir.playerIds[1]
        val oppStakeholder = scenario.stakeholders.firstOrNull { it.id == oppPlayerId } ?: return ir

        // Priors from the opponent stakeholder
        val pSoftens = oppStakeholder.probSoftensOnRepair ?: return ir   // no priors → skip
        val pBoundary = oppStakeholder.probRespectsBoundary ?: NEUTRAL
        val pEscalates = oppStakeholder.probEscalatesOnReactive ?: NEUTRAL

        // Posture of each user action (player 0)
        val userPostures = ir.actionIds[0].map { id ->
            TAG.find(actionById[id]?.description.orEmpty())?.groupValues?.get(1)
        }
        // Posture of each opponent action (player 1)
        val oppPostures = ir.actionIds[1].map { id ->
            TAG.find(actionById[id]?.description.orEmpty())?.groupValues?.get(1)
        }

        val nOpp = ir.strategyCounts[1]
        val newUserUtil = DoubleArray(ir.utility[0].size)
        System.arraycopy(ir.utility[0], 0, newUserUtil, 0, newUserUtil.size)

        for (uIdx in ir.strategyCounts[0] - 1 downTo 0) {
            val userPosture = userPostures.getOrNull(uIdx)
            for (oIdx in nOpp - 1 downTo 0) {
                val oppPosture = oppPostures.getOrNull(oIdx)
                val profileIdx = ir.profileIndex(intArrayOf(uIdx, oIdx))

                // P(opponent plays this action | user plays this posture)
                val pOppAction = priorProb(userPosture, oppPosture, pSoftens, pBoundary, pEscalates, nOpp)
                val uniform = 1.0 / nOpp
                val scale = (pOppAction / uniform).coerceIn(MIN_SCALE, MAX_SCALE)
                newUserUtil[profileIdx] *= scale
            }
        }

        val newUtility = ir.utility.toMutableList()
        newUtility[0] = newUserUtil
        return ir.copy(utility = newUtility)
    }

    /** P(opponent plays oppPosture | user plays userPosture) given priors, distributed across nOpp actions. */
    private fun priorProb(
        userPosture: String?,
        oppPosture: String?,
        pSoftens: Double,
        pBoundary: Double,
        pEscalates: Double,
        nOpp: Int
    ): Double {
        val pReceptive = when (userPosture) {
            REPAIR -> pSoftens
            BOUNDARY -> pBoundary
            REACTIVE -> 1.0 - pEscalates   // if pEscalates high → opponent is DEFENSIVE
            else -> NEUTRAL
        }
        val pDefensive = 1.0 - pReceptive

        // Distribute mass across opponent actions by posture
        val nOtherActions = (nOpp - 1).coerceAtLeast(1)
        return when (oppPosture) {
            RECEPTIVE -> pReceptive
            DEFENSIVE -> pDefensive
            else -> (pReceptive + pDefensive) / (nOpp.toDouble())   // uniform for untagged
        }
    }
}
