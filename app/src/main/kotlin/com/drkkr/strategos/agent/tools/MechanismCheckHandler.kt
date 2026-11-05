package com.drkkr.strategos.agent.tools

import com.drkkr.strategos.agent.ToolContext
import com.drkkr.strategos.agent.ToolDispatcher
import com.drkkr.strategos.agent.ToolHandler
import com.drkkr.strategos.agent.ToolOutcome
import com.drkkr.strategos.data.repo.UiKind
import com.drkkr.strategos.domain.game.MechanismAdvisor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Detects mechanism design structure (auction, matching, contract, voting, allocation) via [MechanismAdvisor]. */
object MechanismCheckHandler : ToolHandler {
    override suspend fun handle(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val scenario = ctx.gateway.activeScenario()
            ?: return ToolOutcome(Args.error("no_scenario"), ToolDispatcher.ERROR_LABEL)

        val hint = MechanismAdvisor.detect(scenario)
        if (hint == null) {
            return ToolOutcome(
                buildJsonObject {
                    put("status", "none")
                    put("message", "No mechanism design structure detected in this scenario.")
                },
                "No mechanism detected"
            )
        }

        val out = buildJsonObject {
            put("status", "detected")
            put("type", hint.type.name)
            put("insight", hint.insight)
            put("recommendation", hint.recommendation)
        }
        return ToolOutcome(out, "Detected ${hint.type.name.lowercase()} mechanism", UiKind.RECOMMENDATION)
    }
}
