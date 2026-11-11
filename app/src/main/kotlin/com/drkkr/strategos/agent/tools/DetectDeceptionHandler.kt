package com.drkkr.strategos.agent.tools

import com.drkkr.strategos.agent.InvalidArguments
import com.drkkr.strategos.agent.ToolContext
import com.drkkr.strategos.agent.ToolDispatcher
import com.drkkr.strategos.agent.ToolHandler
import com.drkkr.strategos.agent.ToolOutcome
import com.drkkr.strategos.data.repo.UiKind
import com.drkkr.strategos.domain.agent.DeceptionDetector
import com.drkkr.strategos.domain.game.GameAnalysisResult
import com.drkkr.strategos.domain.game.GameModelService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object DetectDeceptionHandler : ToolHandler {
    override suspend fun handle(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val observedActionIndex = Args.double(args, "observed_action_index")?.toInt()
            ?: throw InvalidArguments("observed_action_index is required")

        val scenario = ctx.gateway.activeScenario()
            ?: return ToolOutcome(Args.error("no_scenario"), ToolDispatcher.ERROR_LABEL)

        val analysisContext = ctx.analysisCache?.getOrCompute(scenario) ?: GameModelService.analyzeWithContext(scenario)
        val ir = when (val r = analysisContext.result) {
            is GameAnalysisResult.Solved -> r.ir
            is GameAnalysisResult.Partial -> r.ir
            else -> null
        } ?: return ToolOutcome(
            buildJsonObject { put("status", "not_applicable"); put("reason", "No game model available.") },
            "No game model"
        )

        val opponentModel = analysisContext.opponentModel
            ?: return ToolOutcome(
                buildJsonObject { put("status", "not_applicable"); put("reason", "No opponent model available; add the other side's name and observed behaviour first.") },
                "No opponent model"
            )

        val signal = DeceptionDetector.detect(ir, opponentModel, observedActionIndex)

        val out = if (signal == null) {
            buildJsonObject {
                put("status", "consistent")
                put("explanation", "The opponent's observed action is consistent with an equilibrium strategy — no deception signal detected.")
            }
        } else {
            buildJsonObject {
                put("status", "signal")
                put("kind", signal.kind.name)
                put("equilibrium_action_index", signal.equilibriumActionIndex)
                put("observed_action_index", signal.observedActionIndex)
                put("explanation", signal.explanation)
            }
        }

        val label = if (signal == null) "No deception signal" else "Deception signal: ${signal.kind.name}"
        return ToolOutcome(out, label, UiKind.RECOMMENDATION)
    }
}
