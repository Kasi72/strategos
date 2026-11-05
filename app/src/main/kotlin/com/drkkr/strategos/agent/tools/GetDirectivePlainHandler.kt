package com.drkkr.strategos.agent.tools

import com.drkkr.strategos.agent.ToolContext
import com.drkkr.strategos.agent.ToolDispatcher
import com.drkkr.strategos.agent.ToolHandler
import com.drkkr.strategos.agent.ToolOutcome
import com.drkkr.strategos.data.repo.UiKind
import com.drkkr.strategos.domain.agent.EngineDirectiveBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Runs the full analysis pipeline on the active scenario and returns the engine directive as plain JSON. */
object GetDirectivePlainHandler : ToolHandler {
    override suspend fun handle(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val scenario = ctx.gateway.activeScenario()
            ?: return ToolOutcome(Args.error("no_scenario"), ToolDispatcher.ERROR_LABEL)
        val pr = ctx.gateway.analyzeAndPublish()
            ?: return ToolOutcome(Args.error("analysis_failed"), ToolDispatcher.ERROR_LABEL)
        val directive = EngineDirectiveBuilder.build(scenario, pr, stress = null)
        val out = buildJsonObject {
            put("status", "ok")
            put("badge", directive.badge())
            put("directive", directive.toJson().toString())
        }
        return ToolOutcome(out, "Engine directive ready", UiKind.RECOMMENDATION)
    }
}
