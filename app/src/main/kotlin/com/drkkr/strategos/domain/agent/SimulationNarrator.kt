package com.drkkr.strategos.domain.agent

import com.drkkr.strategos.domain.game.GameIR
import com.drkkr.strategos.domain.game.OpponentModel
import com.drkkr.strategos.domain.game.PureNashSolver
import com.drkkr.strategos.domain.game.SolverLimits
import com.drkkr.strategos.domain.game.SolverOutcome

/**
 * Narrates a step-by-step simulation of a conversation from the current state.
 * Uses the game model to predict each player's likely move per round.
 */
object SimulationNarrator {

    data class SimulationStep(
        val round: Int,
        val playerActions: Map<String, String>,   // playerId -> action label
        val payoffs: Map<String, Double>,          // playerId -> payoff
        val note: String
    )

    data class SimulationResult(
        val steps: List<SimulationStep>,
        val summary: String
    )

    /**
     * @param ir the game model
     * @param opponentModel optional opponent priors (affects epsilon-based noise in action prediction)
     * @param rounds number of rounds to simulate (capped at 5)
     */
    fun simulate(
        ir: GameIR,
        opponentModel: OpponentModel? = null,
        rounds: Int = 3
    ): SimulationResult? {
        if (ir.playerCount < 2) return null

        val cappedRounds = rounds.coerceIn(1, 5)
        val outcome = PureNashSolver.solve(ir, SolverLimits.ON_DEVICE)
        val equilibria = when (outcome) {
            is SolverOutcome.Solved -> outcome.equilibria
            is SolverOutcome.Incomplete -> outcome.partialEquilibria
            else -> return SimulationResult(
                emptyList(),
                "No equilibrium found; simulation cannot project moves reliably."
            )
        }

        if (equilibria.isEmpty()) {
            return SimulationResult(
                emptyList(),
                "No pure-strategy equilibrium; mixed strategies apply — exact move sequence is indeterminate."
            )
        }

        val eqProfile = equilibria.first().pureProfile ?: return null

        val steps = (1..cappedRounds).map { round ->
            val playerActions = ir.playerIds.mapIndexed { idx, pid ->
                val actionIdx = eqProfile.getOrElse(idx) { 0 }
                val actionLabel = ir.actionIds.getOrNull(idx)?.getOrNull(actionIdx) ?: "action_${actionIdx + 1}"
                pid to actionLabel
            }.toMap()

            val payoffs = ir.playerIds.mapIndexed { idx, pid ->
                val payoff = ir.utility[idx][ir.profileIndex(eqProfile.toIntArray())]
                pid to payoff
            }.toMap()

            SimulationStep(
                round = round,
                playerActions = playerActions,
                payoffs = payoffs,
                note = if (round == 1) "Equilibrium play — both sides take their best response." else "Pattern holds."
            )
        }

        val summary = buildString {
            append("Over $cappedRounds round(s), both sides converge on the equilibrium: ")
            ir.playerIds.forEachIndexed { idx, pid ->
                val actionIdx = eqProfile.getOrElse(idx) { 0 }
                val label = ir.actionIds.getOrNull(idx)?.getOrNull(actionIdx) ?: "action_${actionIdx + 1}"
                if (idx > 0) append(", ")
                append("$pid → $label")
            }
            append(".")
        }

        return SimulationResult(steps, summary)
    }
}
