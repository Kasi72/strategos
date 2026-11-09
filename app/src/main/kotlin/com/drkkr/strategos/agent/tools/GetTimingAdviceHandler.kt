package com.drkkr.strategos.agent.tools

import com.drkkr.strategos.agent.ToolContext
import com.drkkr.strategos.agent.ToolDispatcher
import com.drkkr.strategos.agent.ToolHandler
import com.drkkr.strategos.agent.ToolOutcome
import com.drkkr.strategos.domain.agent.TimingAdvisor
import com.drkkr.strategos.domain.game.GameModelService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Returns timing advice from TimingAdvisor for the active scenario. */
object GetTimingAdviceHandler : ToolHandler {
    override suspend fun handle(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val scenario = ctx.gateway.activeScenario()
            ?: return ToolOutcome(Args.error("no_active_scenario"), ToolDispatcher.ERROR_LABEL)

        val analysisContext = GameModelService.analyzeWithContext(scenario)
        val opponentModel = analysisContext.opponentModel

        val advice = TimingAdvisor.advise(scenario, opponentModel)
        val note = TimingAdvisor.note(advice, opponentModel)

        val df = opponentModel?.discountFactor ?: scenario.discountFactor

        val out = buildJsonObject {
            put("status", "ok")
            put("advice", advice.name)
            put("note", note)
            df?.let { put("discount_factor", it) }
        }
        return ToolOutcome(out, "Timing advice ready")
    }
}
