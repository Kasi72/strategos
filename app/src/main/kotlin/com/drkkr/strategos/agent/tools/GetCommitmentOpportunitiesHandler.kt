package com.drkkr.strategos.agent.tools

import com.drkkr.strategos.agent.ToolContext
import com.drkkr.strategos.agent.ToolDispatcher
import com.drkkr.strategos.agent.ToolHandler
import com.drkkr.strategos.agent.ToolOutcome
import com.drkkr.strategos.data.repo.UiKind
import com.drkkr.strategos.domain.agent.CommitmentLadder
import com.drkkr.strategos.domain.agent.IntakeChecklist
import com.drkkr.strategos.domain.game.GameAnalysisResult
import com.drkkr.strategos.domain.game.GameModelService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject

/**
 * Returns commitment ladder steps where publicly binding to an action shifts the equilibrium
 * in the user's favour. Steps are ordered from weakest (VERBAL) to strongest (IRREVERSIBLE).
 */
object GetCommitmentOpportunitiesHandler : ToolHandler {
    override suspend fun handle(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val scenario = ctx.gateway.activeScenario()
            ?: return ToolOutcome(Args.error("no_scenario"), ToolDispatcher.ERROR_LABEL)

        val userPlayer = IntakeChecklist.self(scenario)
            ?: return ToolOutcome(
                buildJsonObject { put("status", "not_applicable"); put("reason", "No decision-owner found in scenario.") },
                "No decision-owner"
            )

        val analysisContext = ctx.analysisCache?.getOrCompute(scenario) ?: GameModelService.analyzeWithContext(scenario)
        val ir = when (val r = analysisContext.result) {
            is GameAnalysisResult.Solved -> r.ir
            is GameAnalysisResult.Partial -> r.ir
            else -> null
        } ?: return ToolOutcome(
            buildJsonObject { put("status", "not_applicable"); put("reason", "No game model available.") },
            "No game model"
        )

        val steps = CommitmentLadder.steps(ir, userPlayer.id, scenario)

        if (steps.isEmpty()) {
            return ToolOutcome(
                buildJsonObject {
                    put("status", "none_found")
                    put("reason", "No commitment opportunity improves your payoff given the current model.")
                },
                "No commitment opportunities found"
            )
        }

        val out = buildJsonObject {
            put("status", "ok")
            putJsonArray("commitment_ladder") {
                steps.forEach { step ->
                    addJsonObject {
                        put("level", step.level.name)
                        put("action", step.action)
                        put("rationale", step.rationale)
                        put("credibility_gain", step.credibilityGain)
                        put("credibility_risk", step.credibilityRisk)
                    }
                }
            }
        }
        return ToolOutcome(out, "Found ${steps.size} commitment step(s)", UiKind.RECOMMENDATION)
    }
}
