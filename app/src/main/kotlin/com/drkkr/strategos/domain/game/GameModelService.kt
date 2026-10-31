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
 * Orchestrates compile → IEDS pruning → solver selection → solve → state machine advancement.
 *
 * Pipeline:
 *  1. Compile [Scenario] → [GameIR] (includes archetype detection).
 *  2. Run [IteratedDominancePruner] to eliminate strictly dominated strategies.
 *  3. Select solver:
 *       • 2-player any-size  → [MixedNashNxMSolver] (support enumeration, finds all mixed NE)
 *       • n-player           → [PureNashSolver]
 *  4. Solve; verify residuals; advance [DecisionStateMachine].
 *
 * Integrity:
 *   SolverOutcome.Solved + residual ≤ threshold  → COMPLETE  (state VERIFIED)
 *   SolverOutcome.Solved + residual > threshold   → INCOMPLETE (state CONDITIONAL)
 *   SolverOutcome.Incomplete                      → INCOMPLETE or BLOCKED
 *   SolverOutcome.Unsupported / InvalidModel      → BLOCKED
 *   CompileResult.Failure                         → BLOCKED
 */
object GameModelService {

    private const val RESIDUAL_THRESHOLD = 1e-9

    data class AnalysisContext(
        val result: GameAnalysisResult,
        val pruneResult: IteratedDominancePruner.PruneResult?,
        /** Non-null when the scenario had a [SequenceNode] game tree; carries the SPE path and root payoffs. */
        val biResult: BackwardInductionSolver.Result? = null,
        /** Non-null when Bayesian priors were learned and applied to the compiled GameIR. */
        val opponentModel: OpponentModel? = null,
        /** Non-null for 3–8 player games; Shapley values and coalition stability. */
        val coalitionResult: CoalitionEngine.ShapleyResult? = null,
        /** Non-null when the scenario looks like a mechanism design problem. */
        val mechanismHint: MechanismAdvisor.MechanismHint? = null
    )

    fun analyze(scenario: Scenario, limits: SolverLimits = SolverLimits.ON_DEVICE): GameAnalysisResult =
        analyzeWithContext(scenario, limits).result

    fun analyzeWithContext(
        scenario: Scenario,
        limits: SolverLimits = SolverLimits.ON_DEVICE
    ): AnalysisContext {
        val machine = DecisionStateMachine(DecisionState.EXPLORATORY)

        val rawIr = when (val r = GameModelCompiler.compile(scenario, limits = limits)) {
            is CompileResult.Success -> r.ir
            is CompileResult.Failure -> return AnalysisContext(
                GameAnalysisResult.Failed(
                    reason = "Compile failed: ${r.errors.joinToString("; ")}",
                    decisionState = machine.state,
                    integrity = AnalysisIntegrity.BLOCKED
                ),
                null
            )
        }

        // Step 2: backward induction for extensive-form games (when a game tree is present)
        val biResult = scenario.gameTree?.let { root ->
            BackwardInductionSolver.solve(root, scenario.weights)
        }

        // Step 2b: Bayesian payoff adjustment — re-apply latest learned priors to the compiled IR
        val opponentModel = OpponentModelBuilder.fromScenario(scenario)
        val adjustedIr = BayesianPayoffAdjuster.adjust(rawIr, scenario)

        // Step 3: IEDS — prune strictly dominated strategies (on adjusted IR)
        val pruneResult = IteratedDominancePruner.prune(adjustedIr)
        val ir = pruneResult.ir

        // Step 4: solver selection
        val solver: GameSolver = if (ir.playerCount == 2) MixedNashNxMSolver else PureNashSolver

        val outcome = solver.solve(ir, limits)
        val analysisResult = when (outcome) {
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

        val coalitionResult = if (ir.playerCount >= 3) CoalitionEngine.compute(ir) else null
        val mechanismHint = MechanismAdvisor.detect(scenario)
        return AnalysisContext(analysisResult, pruneResult, biResult, opponentModel, coalitionResult, mechanismHint)
    }
}
