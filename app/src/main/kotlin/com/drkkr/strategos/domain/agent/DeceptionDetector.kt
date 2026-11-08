package com.drkkr.strategos.domain.agent

import com.drkkr.strategos.domain.game.GameIR
import com.drkkr.strategos.domain.game.OpponentModel
import com.drkkr.strategos.domain.game.PureNashSolver
import com.drkkr.strategos.domain.game.SolverLimits
import com.drkkr.strategos.domain.game.SolverOutcome

/**
 * Compares the opponent's equilibrium action to their observed action.
 * Returns a [DeceptionSignal] when divergence exceeds epsilon, or null when the opponent is acting rationally.
 */
object DeceptionDetector {

    enum class DeceptionKind { BLUFFING, PRIVATE_INFO, IRRATIONAL }

    data class DeceptionSignal(
        val kind: DeceptionKind,
        val opponentPlayerId: String,
        val equilibriumActionIndex: Int,
        val observedActionIndex: Int,
        val explanation: String
    )

    /**
     * @param ir the game model
     * @param opponentModel opponent priors; uses epsilon as the divergence threshold
     * @param observedActionIndex the action index the opponent was actually observed taking
     * @return null when the opponent's observed action is consistent with an equilibrium (within epsilon), or when
     *         the solver cannot find a pure NE
     */
    fun detect(
        ir: GameIR,
        opponentModel: OpponentModel,
        observedActionIndex: Int
    ): DeceptionSignal? {
        val oppIdx = ir.playerIds.indexOf(opponentModel.playerId)
        if (oppIdx < 0) return null

        val outcome = PureNashSolver.solve(ir, SolverLimits.ON_DEVICE)
        val equilibria = when (outcome) {
            is SolverOutcome.Solved -> outcome.equilibria
            is SolverOutcome.Incomplete -> outcome.partialEquilibria
            else -> return null
        }
        if (equilibria.isEmpty()) return null

        // Collect all equilibrium action indices for the opponent
        val eqActionIndices = equilibria
            .mapNotNull { it.pureProfile?.getOrNull(oppIdx) }
            .toSet()

        if (eqActionIndices.isEmpty()) return null

        // No divergence — opponent is playing an equilibrium action
        if (observedActionIndex in eqActionIndices) return null

        // Classify the divergence
        val kind = classifyDivergence(ir, oppIdx, observedActionIndex, eqActionIndices)

        val explanation = when (kind) {
            DeceptionKind.BLUFFING ->
                "The opponent played action ${observedActionIndex + 1} instead of their equilibrium action. " +
                        "This deviation can benefit them in some profiles, suggesting strategic misdirection."
            DeceptionKind.PRIVATE_INFO ->
                "The opponent's observed action is not in any equilibrium but is not strictly dominated. " +
                        "They may be responding to information not captured in the current model."
            DeceptionKind.IRRATIONAL ->
                "The opponent's observed action is strictly dominated for them in all equilibria, " +
                        "suggesting an irrational or erroneous move."
        }

        return DeceptionSignal(
            kind = kind,
            opponentPlayerId = opponentModel.playerId,
            equilibriumActionIndex = eqActionIndices.first(),
            observedActionIndex = observedActionIndex,
            explanation = explanation
        )
    }

    private fun classifyDivergence(
        ir: GameIR,
        oppIdx: Int,
        observedActionIndex: Int,
        eqActionIndices: Set<Int>
    ): DeceptionKind {
        val profileCount = ir.profileCount
        val profile = IntArray(ir.playerCount)

        // Check if the observed action is strictly dominated
        var strictlyDominated = true
        var benefitsOppInSomeProfile = false

        for (eqAction in eqActionIndices) {
            for (p in 0 until profileCount) {
                // reconstruct profile from index
                val reconstructed = reconstructProfile(ir, p)
                reconstructed[oppIdx] = observedActionIndex
                val obsPayoff = ir.utility[oppIdx][ir.profileIndex(reconstructed)]

                reconstructed[oppIdx] = eqAction
                val eqPayoff = ir.utility[oppIdx][ir.profileIndex(reconstructed)]

                if (obsPayoff >= eqPayoff) {
                    strictlyDominated = false
                }
                if (obsPayoff > eqPayoff) {
                    benefitsOppInSomeProfile = true
                }
            }
        }

        return when {
            strictlyDominated -> DeceptionKind.IRRATIONAL
            benefitsOppInSomeProfile -> DeceptionKind.BLUFFING
            else -> DeceptionKind.PRIVATE_INFO
        }
    }

    private fun reconstructProfile(ir: GameIR, profileIndex: Int): IntArray {
        val profile = IntArray(ir.playerCount)
        var remaining = profileIndex
        for (p in ir.playerCount - 1 downTo 0) {
            profile[p] = remaining % ir.strategyCounts[p]
            remaining /= ir.strategyCounts[p]
        }
        return profile
    }
}
