package com.drkkr.strategos.agent.tools

import com.drkkr.strategos.agent.ToolContext
import com.drkkr.strategos.agent.ToolDispatcher
import com.drkkr.strategos.agent.ToolHandler
import com.drkkr.strategos.agent.ToolOutcome
import com.drkkr.strategos.data.repo.UiKind
import com.drkkr.strategos.domain.agent.SelfModelEstimator
import com.drkkr.strategos.domain.game.BayesianNESolver
import com.drkkr.strategos.domain.game.GameAnalysisResult
import com.drkkr.strategos.domain.game.GameModelService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Returns what the opponent likely believes about the user's strategy pattern,
 * plus the Bayesian NE recommendation when information asymmetry is high enough.
 */
object GetSelfModelHandler : ToolHandler {
    override suspend fun handle(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val scenario = ctx.gateway.activeScenario()
            ?: return ToolOutcome(Args.error("no_scenario"), ToolDispatcher.ERROR_LABEL)

        val history = ctx.outcomes?.forScenario(scenario.id) ?: emptyList()
        val selfModel = SelfModelEstimator.estimate(history)

        val analysisContext = ctx.analysisCache?.getOrCompute(scenario) ?: GameModelService.analyzeWithContext(scenario)
        val bayesianNE = analysisContext.bayesianNE
            ?: run {
                val ir = when (val r = analysisContext.result) {
                    is GameAnalysisResult.Solved  -> r.ir
                    is GameAnalysisResult.Partial -> r.ir
                    else -> null
                }
                if (ir != null) {
                    val score = BayesianNESolver.computeAsymmetryScore(ir)
                    BayesianNESolver.solve(ir, analysisContext.opponentModel, score)
                } else null
            }

        val out = buildJsonObject {
            put("status", "ok")
            putJsonObject("self_model") {
                put("inferred_strategy_label", selfModel.inferredStrategyLabel)
                put("confidence", selfModel.confidence)
                put("history_records_used", history.size)
            }
            if (bayesianNE != null) {
                putJsonObject("bayesian_ne") {
                    put("recommended_strategy_index", bayesianNE.strategy)
                    put("reasoning", bayesianNE.reasoning)
                    putJsonObject("type_posteriors") {
                        bayesianNE.typePosteriors.forEach { (k, v) -> put(k, v) }
                    }
                }
            } else {
                put("bayesian_ne", null as String?)
                put("bayesian_ne_note", "Bayesian NE requires info asymmetry score > ${BayesianNESolver.THRESHOLD} and a 2-player game.")
            }
        }
        return ToolOutcome(out, "Self-model computed", UiKind.RECOMMENDATION)
    }
}
