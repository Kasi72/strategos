package com.drkkr.strategos.domain.game

import com.drkkr.strategos.domain.model.Scenario

/** Result of a full compile → solve → verify pipeline for one scenario. */
sealed interface GameAnalysisResult {
    val decisionState: DecisionState
    val integrity: AnalysisIntegrity

    data class Solved(
        val ir: GameIR,
        val outcome: SolverOutcome.Solved,
        override val decisionState: DecisionState,
        override val integrity: AnalysisIntegrity
    ) : GameAnalysisResult

    data class Partial(
        val ir: GameIR?,
        val partialOutcome: SolverOutcome?,
        val reason: String,
        override val decisionState: DecisionState,
        override val integrity: AnalysisIntegrity
    ) : GameAnalysisResult

    data class Failed(
        val reason: String,
        override val decisionState: DecisionState = DecisionState.EXPLORATORY,
        override val integrity: AnalysisIntegrity = AnalysisIntegrity.BLOCKED
    ) : GameAnalysisResult
}

/**
 * Orchestrates compile → solver selection → solve → state machine advancement
 * for a [Scenario], returning a [GameAnalysisResult] with a settled [AnalysisIntegrity].
 *
 * Solver selection: [MixedNash2x2Solver] when the game is 2-player 2×2;
 * [PureNashSolver] otherwise.
 *
 * Integrity assignment:
 *   SolverOutcome.Solved + all residuals ≤ threshold  → COMPLETE  (state VERIFIED)
 *   SolverOutcome.Solved + residual > threshold        → INCOMPLETE (state CONDITIONAL)
 *   SolverOutcome.Incomplete                           → outcome.integrity (INCOMPLETE or BLOCKED)
 *   SolverOutcome.Unsupported / InvalidModel           → BLOCKED
 *   CompileResult.Failure                              → BLOCKED
 */
object GameModelService {

    private const val RESIDUAL_THRESHOLD = 1e-9

    fun analyze(scenario: Scenario, limits: SolverLimits = SolverLimits.ON_DEVICE): GameAnalysisResult {
        val machine = DecisionStateMachine(DecisionState.EXPLORATORY)

        val ir = when (val r = GameModelCompiler.compile(scenario, limits = limits)) {
            is CompileResult.Success -> r.ir
            is CompileResult.Failure -> return GameAnalysisResult.Failed(
                reason = "Compile failed: ${r.errors.joinToString("; ")}",
                decisionState = machine.state,
                integrity = AnalysisIntegrity.BLOCKED
            )
        }

        val solver: GameSolver = if (ir.playerCount == 2 && ir.strategyCounts.all { it == 2 })
            MixedNash2x2Solver else PureNashSolver

        return when (val outcome = solver.solve(ir, limits)) {
            is SolverOutcome.Solved -> {
                machine.transition(DecisionState.CONDITIONAL, "solver found ${outcome.equilibria.size} equilibria")
                val maxResidual = outcome.equilibria.maxOfOrNull { eq ->
                    EquilibriumVerifier.deviationGainResidual(ir.utility, ir.strategyCounts, eq.mixedStrategies)
                } ?: 0.0
                if (maxResidual <= RESIDUAL_THRESHOLD) {
                    machine.transition(DecisionState.VERIFIED, "all equilibria verified, residual=$maxResidual")
                    GameAnalysisResult.Solved(ir, outcome, machine.state, AnalysisIntegrity.COMPLETE)
                } else {
                    GameAnalysisResult.Partial(
                        ir, outcome,
                        "Residual $maxResidual exceeds threshold $RESIDUAL_THRESHOLD",
                        machine.state, AnalysisIntegrity.INCOMPLETE
                    )
                }
            }
            is SolverOutcome.Incomplete -> GameAnalysisResult.Partial(
                ir, outcome, outcome.reason, machine.state, outcome.integrity
            )
            is SolverOutcome.Unsupported -> GameAnalysisResult.Failed(
                reason = "No solver supports this game: ${outcome.reason}",
                decisionState = machine.state,
                integrity = AnalysisIntegrity.BLOCKED
            )
            is SolverOutcome.InvalidModel -> GameAnalysisResult.Failed(
                reason = "Invalid model: ${outcome.violations.joinToString("; ")}",
                decisionState = machine.state,
                integrity = AnalysisIntegrity.BLOCKED
            )
        }
    }
}
