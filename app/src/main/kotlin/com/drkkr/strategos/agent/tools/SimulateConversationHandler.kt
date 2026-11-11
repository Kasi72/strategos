package com.drkkr.strategos.agent.tools

import com.drkkr.strategos.agent.ToolContext
import com.drkkr.strategos.agent.ToolDispatcher
import com.drkkr.strategos.agent.ToolHandler
import com.drkkr.strategos.agent.ToolOutcome
import com.drkkr.strategos.data.repo.UiKind
import com.drkkr.strategos.domain.agent.SimulationNarrator
import com.drkkr.strategos.domain.game.GameAnalysisResult
import com.drkkr.strategos.domain.game.GameModelService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.addJsonObject

/** Simulates how a conversation is likely to unfold given the current game model. */
object SimulateConversationHandler : ToolHandler {
    override suspend fun handle(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val scenario = ctx.gateway.activeScenario()
            ?: return ToolOutcome(Args.error("no_scenario"), ToolDispatcher.ERROR_LABEL)

        val rounds = Args.double(args, "rounds")?.toInt()?.coerceIn(1, 5) ?: 3

        val analysisContext = ctx.analysisCache?.getOrCompute(scenario) ?: GameModelService.analyzeWithContext(scenario)
        val ir = when (val r = analysisContext.result) {
            is GameAnalysisResult.Solved -> r.ir
            is GameAnalysisResult.Partial -> r.ir
            else -> null
        } ?: return ToolOutcome(
                buildJsonObject { put("status", "not_applicable"); put("reason", "No game model available.") },
                "Simulation not applicable"
            )

        val opponentModel = analysisContext.opponentModel

        val result = SimulationNarrator.simulate(ir, opponentModel, rounds)
            ?: return ToolOutcome(
                buildJsonObject { put("status", "not_applicable"); put("reason", "Insufficient players or no equilibrium.") },
                "Simulation not applicable"
            )

        fun idToName(id: String) = scenario.stakeholders.firstOrNull { it.id == id }?.name ?: id

        val out = buildJsonObject {
            put("status", "ok")
            put("summary", result.summary)
            putJsonArray("steps") {
                result.steps.forEach { step ->
                    addJsonObject {
                        put("round", step.round)
                        putJsonObject("actions") {
                            step.playerActions.forEach { (pid, action) -> put(idToName(pid), action) }
                        }
                        putJsonObject("payoffs") {
                            step.payoffs.forEach { (pid, v) -> put(idToName(pid), v) }
                        }
                        put("note", step.note)
                    }
                }
            }
        }
        return ToolOutcome(out, "Simulated ${result.steps.size} round(s)", UiKind.RECOMMENDATION)
    }
}
