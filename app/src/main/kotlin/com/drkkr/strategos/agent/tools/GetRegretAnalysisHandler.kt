package com.drkkr.strategos.agent.tools

import com.drkkr.strategos.agent.ToolContext
import com.drkkr.strategos.agent.ToolDispatcher
import com.drkkr.strategos.agent.ToolHandler
import com.drkkr.strategos.agent.ToolOutcome
import com.drkkr.strategos.domain.game.GameAnalysisResult
import com.drkkr.strategos.domain.game.GameModelService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Returns per-strategy minimax regret table for the user (player 0) in the active 2-player game. */
object GetRegretAnalysisHandler : ToolHandler {
    override suspend fun handle(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val scenario = ctx.gateway.activeScenario()
            ?: return ToolOutcome(Args.error("no_active_scenario"), ToolDispatcher.ERROR_LABEL)

        val analysisContext = ctx.analysisCache?.getOrCompute(scenario) ?: GameModelService.analyzeWithContext(scenario)
        val ir = when (val r = analysisContext.result) {
            is GameAnalysisResult.Solved  -> r.ir
            is GameAnalysisResult.Partial -> r.ir
            else -> null
        } ?: return ToolOutcome(
            buildJsonObject { put("status", "analysis_unavailable"); put("reason", "game not compiled") },
            "Regret analysis unavailable"
        )

        val regret = analysisContext.regretResult ?: return ToolOutcome(
            buildJsonObject { put("status", "not_applicable"); put("reason", "requires 2-player game") },
            "Regret not applicable"
        )

        // Reproduce per-strategy worst-case regret table (MinimaxRegretSolver internal `worst` array)
        val m = ir.strategyCounts.getOrNull(0) ?: 0
        val n = ir.strategyCounts.getOrNull(1) ?: 0
        val u0 = ir.utility.getOrNull(0)
        val worstPerStrategy: List<Double> = if (u0 != null && m > 0 && n > 0) {
            (0 until m).map { s ->
                (0 until n).maxOf { t ->
                    val bestForT = (0 until m).maxOf { sp -> u0[sp * n + t] }
                    bestForT - u0[s * n + t]
                }
            }
        } else emptyList()

        // Map strategy index → action label for player 0
        val p0Id = ir.playerIds.getOrNull(0)
        val p0ActionIds = ir.actionIds.getOrNull(0) ?: emptyList()
        val actionLabelMap = scenario.actionsFor(p0Id ?: "").associateBy { it.id }

        val bestActionLabel = p0ActionIds.getOrNull(regret.strategy)
            ?.let { actionLabelMap[it]?.label ?: it }

        val out = buildJsonObject {
            put("status", "ok")
            put("best_action", bestActionLabel)
            put("worst_case_regret", regret.worstCaseRegret)
            put("regret_gap", regret.regretGap)
            putJsonArray("per_strategy_regret") {
                worstPerStrategy.forEachIndexed { idx, r ->
                    val actionId = p0ActionIds.getOrNull(idx)
                    val label = actionId?.let { actionLabelMap[it]?.label ?: it } ?: "strategy_$idx"
                    addJsonObject {
                        put("action", label)
                        put("worst_case_regret", r)
                        put("is_minimax_optimal", idx == regret.strategy)
                    }
                }
            }
        }
        return ToolOutcome(out, "Regret analysis ready")
    }
}
