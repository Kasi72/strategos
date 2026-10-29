package com.drkkr.strategos.agent.tools

import com.drkkr.strategos.agent.ToolContext
import com.drkkr.strategos.agent.ToolDispatcher
import com.drkkr.strategos.agent.ToolHandler
import com.drkkr.strategos.agent.ToolOutcome
import com.drkkr.strategos.domain.agent.ScenarioDigest
import kotlinx.serialization.json.JsonObject

/** §8.1 */
object GetScenarioHandler : ToolHandler {
    override suspend fun handle(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val s = ctx.gateway.activeScenario() ?: return ToolOutcome(Args.error("no_scenario"), ToolDispatcher.ERROR_LABEL)
        return ToolOutcome(ScenarioDigest.build(s), "Read the model")
    }
}
