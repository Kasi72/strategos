package com.drkkr.strategos.domain.game

import com.drkkr.strategos.domain.model.GameRole
import com.drkkr.strategos.domain.model.Scenario
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

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
        val mechanismHint: MechanismAdvisor.MechanismHint? = null,
        /** Payoff-ordering violations detected by [ExtractionValidator]; empty when IR is consistent. */
        val validationWarnings: List<String> = emptyList(),
        /** Non-null for repeated/infinite games with PD structure; folk theorem cooperation threshold. */
        val folkResult: RepeatedGameAnalyzer.FolkThresholdResult? = null,
        /** Non-null when multiple equilibria exist; identifies the focal point and selection rationale. */
        val focalSelection: FocalEquilibriumSelector.FocalSelection? = null,
        /** Structured narrative for the coach and UI; non-null whenever a solved equilibrium exists. */
        val strategicNarrative: StrategicNarrative? = null,
        /** Payoff gaps the extraction layer flagged; non-empty means the user should be prompted. */
        val missingPayoffs: List<com.drkkr.strategos.domain.model.MissingPayoffQuery> = emptyList(),
        /** Minimax regret result for the user (player 0) in a 2-player game; null for n≠2 or unsolved games. */
        val regretResult: RegretResult? = null,
        /** Phase 3: Bayesian belief state over game archetypes derived from the IR's archetype and payoffs. */
        val beliefState: BeliefState? = null,
        /** Phase 3: level-k epistemic best response given the opponent model's epsilon. */
        val epistemicResult: EpistemicResult? = null,
        /** Phase 4: information asymmetry score in [0,1]; drives BayesianNESolver selection. */
        val infoAsymmetryScore: Double = 0.0,
        /** Phase 4: what the opponent likely believes about the user's strategy pattern. */
        val selfModel: com.drkkr.strategos.domain.agent.SelfModel? = null,
        /** Phase 4: Bayesian NE result when infoAsymmetryScore > BayesianNESolver.THRESHOLD. */
        val bayesianNE: BayesianNESolver.BayesianNEResult? = null,
        /** U9: agreement ratio across cooperative/neutral/adversarial opponent priors (0–1; 1 = all variants agree). */
        val extractionConfidence: Float = 1.0f,
        /** U9: interpretations under non-neutral opponent priors that disagree with the neutral recommendation. */
        val alternativeInterpretations: List<String> = emptyList(),
        /** E1: equilibrium sensitivity — min payoff perturbation to flip the recommendation. */
        val sensitivityResult: EquilibriumSensitivityAnalyzer.SensitivityResult? = null,
        /** E2: counterfactual value of information for proxy-filled payoff cells. */
        val cvoiResult: CounterfactualValueAnalyzer.CvoiResult? = null,
        /** E3: robust strategy across sampled proxy-cell completions. */
        val bayesianCompletion: BayesianGameCompletion.BayesianCompletionResult? = null,
        /** E4: IC mechanism synthesis — binding rule and monitoring note. */
        val icSynthesis: MechanismAdvisor.IcSynthesis? = null,
        /** E8: Pareto surplus map — gap between Nash and optimal joint utility. */
        val surplusResult: ParetoSurplusAnalyzer.SurplusResult? = null,
        /** E9: commitment ladder — earliest binding action on the equilibrium path. */
        val commitmentLadder: CommitmentLadderAnalyzer.CommitmentLadder? = null
    )

    suspend fun analyze(scenario: Scenario, limits: SolverLimits = SolverLimits.ON_DEVICE): GameAnalysisResult =
        analyzeWithContext(scenario, limits).result

    /**
     * Compiles [scenario]; if the only problem is that some players have no extracted actions (typically a third
     * party the extraction named but gave no options), they are set aside as influencers for the matrix and the
     * rest is compiled. They stay in the scenario, so nothing is lost for the other analyses.
     */
    private fun compileSkippingActionless(scenario: Scenario, limits: SolverLimits): CompileResult {
        val first = GameModelCompiler.compile(scenario, limits = limits)
        if (first !is CompileResult.Failure) return first
        val actionless = first.errors.filterIsInstance<CompileError.PlayerHasNoActions>().map { it.playerId }.toSet()
        if (actionless.isEmpty() || actionless.size != first.errors.size) return first
        val reduced = scenario.copy(stakeholders = scenario.stakeholders.map {
            if (it.id in actionless) it.copy(gameRole = GameRole.INFLUENCER) else it
        })
        return GameModelCompiler.compile(reduced, limits = limits).let { if (it is CompileResult.Success) it else first }
    }

    /**
     * Only the dominance prune of [analyzeWithContext] (same compile, same Bayesian adjustment), without the solvers. Not
     * suspending, for pure callers such as [com.drkkr.strategos.domain.intent.ExclusionFirst]. Null when the model does not compile.
     */
    fun pruneOnly(scenario: Scenario, limits: SolverLimits = SolverLimits.ON_DEVICE): IteratedDominancePruner.PruneResult? {
        val ir = (compileSkippingActionless(scenario, limits) as? CompileResult.Success)?.ir ?: return null
        return IteratedDominancePruner.prune(BayesianPayoffAdjuster.adjust(ir, scenario))
    }

    suspend fun analyzeWithContext(
        scenario: Scenario,
        limits: SolverLimits = SolverLimits.ON_DEVICE,
        contextText: String = ""
    ): AnalysisContext {
        val machine = DecisionStateMachine(DecisionState.EXPLORATORY)

        val rawIr = when (val r = compileSkippingActionless(scenario, limits)) {
            is CompileResult.Success -> r.ir
            // The mechanism hint reads the text, not the matrix, so a failed compile must not hide it.
            is CompileResult.Failure -> return AnalysisContext(
                GameAnalysisResult.Failed(
                    reason = "Compile failed: ${r.errors.joinToString("; ")}",
                    decisionState = machine.state,
                    integrity = AnalysisIntegrity.BLOCKED
                ),
                null,
                mechanismHint = MechanismAdvisor.detect(scenario, contextText)
            )
        }

        // Validate payoff-ordering invariants (soft — never blocks solving)
        val validationWarnings = when (val v = ExtractionValidator.validate(rawIr)) {
            is ExtractionValidator.ValidationResult.Valid    -> emptyList()
            is ExtractionValidator.ValidationResult.Invalid -> v.violations
        }

        // Phase 1 (parallel): backward induction on the game tree runs concurrently with
        // the Bayesian adjustment + IEDS prune chain, since they share no state.
        val (biResult, pruneResult, opponentModel) = coroutineScope {
            val biDef = async(Dispatchers.Default) {
                scenario.gameTree?.let { root -> BackwardInductionSolver.solve(root, scenario.weights) }
            }
            val opModel = OpponentModelBuilder.fromScenario(scenario)
            val adjIr = BayesianPayoffAdjuster.adjust(rawIr, scenario)
            val prune = IteratedDominancePruner.prune(adjIr)
            Triple(biDef.await(), prune, opModel)
        }
        val ir = pruneResult.ir

        // Step 4: solver selection
        // EVOLUTIONARY → ReplicatorDynamicsSolver (RK4 replicator dynamics, ESS candidates).
        // BAYESIAN     → BayesianGameSolver (wraps NxM + signalling risk).
        // 2×2          → MixedNash2x2Solver (analytic indifference equations, faster, exact).
        // 2-player     → MixedNashNxMSolver (support enumeration, finds all mixed NE).
        // n≥3          → MixedNashNPlayerSolver (pure-NE enumeration + IBR for mixed NE).
        val solver: GameSolver = when {
            ir.gameClass == GameClass.EVOLUTIONARY                    -> ReplicatorDynamicsSolver
            ir.gameClass == GameClass.BAYESIAN                        -> BayesianGameSolver
            ir.playerCount == 2 && ir.strategyCounts.all { it == 2 } -> MixedNash2x2Solver
            ir.playerCount == 2                                       -> MixedNashNxMSolver
            else                                                      -> MixedNashNPlayerSolver
        }

        // Phase 2 (parallel): folk-theorem analysis, solver, coalition analysis, and mechanism
        // detection are all independent of each other once the pruned IR is known.
        data class Phase2(
            val folkResult: RepeatedGameAnalyzer.FolkThresholdResult?,
            val outcome: SolverOutcome,
            val coalitionResult: CoalitionEngine.ShapleyResult?,
            val mechanismHint: MechanismAdvisor.MechanismHint?,
            val regretResult: RegretResult?,
            val beliefState: BeliefState?,
            val epistemicResult: EpistemicResult?,
            val infoAsymmetryScore: Double,
            val bayesianNE: BayesianNESolver.BayesianNEResult?
        )
        val p2 = coroutineScope {
            val folkDef   = async(Dispatchers.Default) {
                if (scenario.repeated || ir.gameClass == GameClass.REPEATED_INFINITE)
                    withContext(Dispatchers.Default) { RepeatedGameAnalyzer.analyze(ir, scenario.discountFactor) }
                else null
            }
            val solveDef  = async(Dispatchers.Default) { solver.solve(ir, limits) }
            val coalDef   = async(Dispatchers.Default) { if (ir.playerCount >= 3) CoalitionEngine.compute(ir) else null }
            val mechDef   = async(Dispatchers.Default) { MechanismAdvisor.detect(scenario, contextText, ir.archetype) }
            val regretDef = async(Dispatchers.Default) { MinimaxRegretSolver.solve(ir) }
            val beliefDef = async(Dispatchers.Default) {
                val coopRate = BeliefStateTracker.coopRateFromIR(ir)
                BeliefStateTracker.update(BeliefStateTracker.uniformPrior(), coopRate)
            }
            val epistemicDef = async(Dispatchers.Default) { EpistemicDepthSolver.solve(ir, opponentModel) }
            val asymmetryScore = BayesianNESolver.computeAsymmetryScore(ir)
            val bayesianNEDef = async(Dispatchers.Default) { BayesianNESolver.solve(ir, opponentModel, asymmetryScore) }
            Phase2(folkDef.await(), solveDef.await(), coalDef.await(), mechDef.await(), regretDef.await(), beliefDef.await(), epistemicDef.await(), asymmetryScore, bayesianNEDef.await())
        }
        val folkResult    = p2.folkResult
        val outcome       = p2.outcome
        val coalitionResult = p2.coalitionResult
        val mechanismHint  = p2.mechanismHint

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

        // Focal equilibrium selection — only meaningful when multiple equilibria were found.
        val equilibria = when (val r = analysisResult) {
            is GameAnalysisResult.Solved  -> r.outcome.equilibria
            is GameAnalysisResult.Partial -> (r.partialOutcome as? SolverOutcome.Solved)?.equilibria ?: emptyList()
            else                          -> emptyList()
        }
        val focalSelection = if (equilibria.size > 1) FocalEquilibriumSelector.select(equilibria, ir) else null

        // Structured narrative surfaced to the coach.
        val strategicNarrative = if (equilibria.isNotEmpty()) {
            val focal = focalSelection?.equilibrium ?: equilibria.first()
            val eqType = when {
                focal.pureProfile != null -> "pure NE"
                else                      -> "mixed NE"
            }
            val sensitivityNote = folkResult?.summary
            StrategicNarrative(
                equilibriumType     = eqType,
                selectionCriterion  = focalSelection?.criterion
                    ?: FocalEquilibriumSelector.SelectionCriterion.ARBITRARY,
                isParetoOptimal     = focalSelection?.isParetoOptimal ?: true,
                riskDominanceMargin = focalSelection?.riskDominanceMargin,
                sensitivityNote     = sensitivityNote,
                warningSignals      = validationWarnings
            )
        } else null

        // U9: multi-hypothesis extraction confidence.
        // Create 3 scenario variants with different opponent priors and check if IEDS agrees on surviving user actions.
        val (extractionConfidence, alternativeInterpretations) = computeExtractionConfidence(rawIr, scenario)

        // Phase3: single-turn analyzers (E1-E4, E8-E9).
        val sensitivityResult = EquilibriumSensitivityAnalyzer.analyze(ir, equilibria)
        val cvoiResult = CounterfactualValueAnalyzer.analyze(ir, equilibria)
        val bayesianCompletion = BayesianGameCompletion.analyze(ir)
        val icSynthesis = mechanismHint?.let { MechanismAdvisor.synthesize(it, ir) }
        val surplusResult = ParetoSurplusAnalyzer.analyze(ir, focalSelection?.equilibrium ?: equilibria.firstOrNull())
        val commitmentLadder = CommitmentLadderAnalyzer.analyze(biResult?.equilibriumPath.orEmpty())

        return AnalysisContext(
            result               = analysisResult,
            pruneResult          = pruneResult,
            biResult             = biResult,
            opponentModel        = opponentModel,
            coalitionResult      = coalitionResult,
            mechanismHint        = mechanismHint,
            validationWarnings   = validationWarnings,
            folkResult           = folkResult,
            focalSelection       = focalSelection,
            strategicNarrative   = strategicNarrative,
            regretResult         = p2.regretResult,
            beliefState          = p2.beliefState,
            epistemicResult      = p2.epistemicResult,
            infoAsymmetryScore   = p2.infoAsymmetryScore,
            bayesianNE           = p2.bayesianNE,
            extractionConfidence = extractionConfidence,
            alternativeInterpretations = alternativeInterpretations,
            sensitivityResult = sensitivityResult,
            cvoiResult = cvoiResult,
            bayesianCompletion = bayesianCompletion,
            icSynthesis = icSynthesis,
            surplusResult = surplusResult,
            commitmentLadder = commitmentLadder
        )
    }

    /**
     * U9: Creates 3 scenario variants (cooperative/neutral/adversarial opponent priors), runs IEDS on each,
     * and measures how often the surviving user action set agrees with the neutral variant.
     * Returns (confidence, alternativeInterpretations).
     */
    private fun computeExtractionConfidence(rawIr: GameIR, scenario: Scenario): Pair<Float, List<String>> {
        if (rawIr.playerCount != 2) return Pair(1.0f, emptyList())
        val players = scenario.players()
        if (players.size < 2) return Pair(1.0f, emptyList())
        val oppId = players[1].id
        val oppStakeholder = scenario.stakeholders.firstOrNull { it.id == oppId } ?: return Pair(1.0f, emptyList())

        data class Variant(val label: String, val stakeholder: com.drkkr.strategos.domain.model.Stakeholder)
        val variants = listOf(
            Variant("If opponent is cooperative", oppStakeholder.copy(probSoftensOnRepair = 0.8, probRespectsBoundary = 0.8, probEscalatesOnReactive = 0.1)),
            Variant("If opponent is neutral", oppStakeholder),
            Variant("If opponent is adversarial", oppStakeholder.copy(probSoftensOnRepair = 0.2, probRespectsBoundary = 0.3, probEscalatesOnReactive = 0.8))
        )

        val recommendations = variants.mapNotNull { (label, opp) ->
            val variantScenario = scenario.copy(stakeholders = scenario.stakeholders.map { if (it.id == oppId) opp else it })
            val adjIr = BayesianPayoffAdjuster.adjust(rawIr, variantScenario)
            val surviving = IteratedDominancePruner.prune(adjIr).ir.actionIds.getOrNull(0)?.toSet()
            surviving?.let { label to it }
        }

        if (recommendations.isEmpty()) return Pair(1.0f, emptyList())
        val neutralSurviving = recommendations.firstOrNull { it.first.contains("neutral") }?.second
            ?: recommendations.first().second

        val agreeing = recommendations.count { it.second == neutralSurviving }
        val confidence = agreeing.toFloat() / recommendations.size.toFloat()
        val alternatives = recommendations.filter { it.second != neutralSurviving }.map { it.first }

        return Pair(confidence, alternatives)
    }
}
