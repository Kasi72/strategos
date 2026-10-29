package com.drkkr.strategos.domain.analysis

import com.drkkr.strategos.domain.framework.AnalysisResult
import com.drkkr.strategos.domain.framework.FrameworkRegistry
import com.drkkr.strategos.domain.framework.ModelValidator
import com.drkkr.strategos.domain.framework.ModelValidityReport
import com.drkkr.strategos.domain.framework.ModuleRunResult
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.recommendation.DecisionReport
import com.drkkr.strategos.domain.recommendation.RecommendationEngine
import com.drkkr.strategos.framework.AnalysisPlan
import com.drkkr.strategos.framework.FrameworkSelector
import com.drkkr.strategos.framework.behavioral.*
import com.drkkr.strategos.framework.dominant.DominantStrategyModule
import com.drkkr.strategos.framework.eu.ExpectedUtilityModule
import com.drkkr.strategos.framework.minimax.MinimaxModule
import com.drkkr.strategos.framework.nash.MixedNash2x2Module
import com.drkkr.strategos.framework.nash.PureNashModule
import com.drkkr.strategos.framework.pareto.ParetoModule
import com.drkkr.strategos.framework.sensitivity.SensitivityModule
import com.drkkr.strategos.framework.strategic.*
import com.drkkr.strategos.framework.tree.BackwardInductionModule

data class PipelineResult(
    val validity: ModelValidityReport,
    val plan: AnalysisPlan?,
    val moduleRuns: List<ModuleRunResult>,
    val results: List<AnalysisResult>,
    val report: DecisionReport?,         // null when validity.blocksAnalysis
    val provenance: ProvenanceStats = ProvenanceStats(0, 0, 0, 0, 0, 0),
    val gate: ProvenanceGate.Outcome = ProvenanceGate.Outcome.NONE
)

/** The whole analysis in one pure, synchronous call. Callers wrap it in Dispatchers.Default. */
class AnalysisPipeline(
    private val registry: FrameworkRegistry = defaultRegistry(),
    private val engine: RecommendationEngine = RecommendationEngine()
) {
    fun run(scenario: Scenario): PipelineResult {
        val validity = ModelValidator.validate(scenario)
        if (validity.blocksAnalysis) return PipelineResult(validity, null, emptyList(), emptyList(), null)

        val plan = FrameworkSelector.classify(scenario, registry.modules())
        val moduleRuns = registry.runAll(scenario, plan.selected().map { it.moduleId }.toSet())
        val results = registry.analysisResultsFrom(moduleRuns)
        val raw = engine.combine(scenario, results).copy(analysisPlan = plan)
        val stats = ProvenanceStats.of(scenario)
        val (report, gate) = ProvenanceGate.apply(raw, stats)
        return PipelineResult(validity, plan, moduleRuns, results, report, stats, gate)
    }

    companion object {
        fun defaultRegistry(): FrameworkRegistry = FrameworkRegistry()
            .register(DominantStrategyModule())
            .register(PureNashModule())
            .register(MixedNash2x2Module())
            .register(ExpectedUtilityModule())
            .register(MinimaxModule())
            .register(BackwardInductionModule())
            .register(ParetoModule())
            .register(SensitivityModule())
            .register(PrisonersDilemmaModule())
            .register(StagHuntModule())
            .register(VirtualBargainingModule())
            .register(UltimatumModule())
            .register(TrustGameModule())
            .register(PublicGoodsModule())
            .register(LevelKModule())
            .register(IteratedDominanceModule())
            .register(AuctionModule())
            .register(ChickenGameModule())
            .register(PrincipalAgentModule())
            .register(CommonPoolResourceModule())
            .register(RedTeamModule())
            .register(RobustDecisionFrontierModule())
            .register(ValueOfInformationModule())
            .register(BehavioralResponseModule())
            .register(CoalitionAnalysisModule())
            .register(SignallingModule())
            .register(StackelbergModule())
            .register(ScenarioEnsembleModule())
            .register(BayesianUpdatingModule())
            .register(DynamicSimulationModule())
            .register(StakeholderTrustDynamicsModule())
            .register(RelationalRepairModule())
    }
}
