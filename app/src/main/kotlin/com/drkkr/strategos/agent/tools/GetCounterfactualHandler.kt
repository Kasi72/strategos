package com.drkkr.strategos.agent.tools

import com.drkkr.strategos.agent.ToolContext
import com.drkkr.strategos.agent.ToolDispatcher
import com.drkkr.strategos.agent.ToolHandler
import com.drkkr.strategos.agent.ToolOutcome
import com.drkkr.strategos.domain.game.CounterfactualAnalyzer
import com.drkkr.strategos.domain.game.GameAnalysisResult
import com.drkkr.strategos.domain.game.GameModelService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Runs counterfactual analysis for the active scenario (upgrade 7). */
object GetCounterfactualHandler : ToolHandler {
    override suspend fun handle(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val scenario = ctx.gateway.activeScenario()
            ?: return ToolOutcome(Args.error("no_active_scenario"), ToolDispatcher.ERROR_LABEL)

        val analysisContext = ctx.analysisCache?.getOrCompute(scenario) ?: GameModelService.analyzeWithContext(scenario)
        val (ir, baseline) = when (val r = analysisContext.result) {
            is GameAnalysisResult.Solved -> r.ir to r.outcome
            else -> return ToolOutcome(
                buildJsonObject { put("status", "analysis_unavailable"); put("reason", "game not fully solved") },
                "Counterfactual unavailable"
            )
        }

        val playerId = Args.string(args, "player_id") ?: ir.playerIds.firstOrNull()
            ?: return ToolOutcome(Args.error("no_player_found"), ToolDispatcher.ERROR_LABEL)

        val analysis = CounterfactualAnalyzer.analyze(ir, baseline, playerId)
        if (analysis.results.isEmpty()) return ToolOutcome(
            buildJsonObject { put("status", "no_results"); put("player_id", playerId) },
            "No counterfactual results"
        )

        val out = buildJsonObject {
            put("status", "ok")
            put("player_id", playerId)
            putJsonArray("counterfactual_results") {
                analysis.results.forEach { r ->
                    addJsonObject {
                        put("action_id", r.forcedActionId)
                        put("baseline_payoff", r.baselinePayoff)
                        put("counterfactual_payoff", r.counterfactualPayoff)
                        put("deviation_gain", r.deviationGain)
                        put("better_than_baseline", r.deviationGain > 0)
                    }
                }
            }
            analysis.recommendations[playerId]?.let { put("recommended_action", it) }
        }
        return ToolOutcome(out, "Counterfactual analysis ready")
    }
}
