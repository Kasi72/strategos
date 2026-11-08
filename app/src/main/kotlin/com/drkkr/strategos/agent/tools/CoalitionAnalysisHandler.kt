package com.drkkr.strategos.agent.tools

import com.drkkr.strategos.agent.ToolContext
import com.drkkr.strategos.agent.ToolDispatcher
import com.drkkr.strategos.agent.ToolHandler
import com.drkkr.strategos.agent.ToolOutcome
import com.drkkr.strategos.data.repo.UiKind
import com.drkkr.strategos.domain.game.GameModelService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Runs Shapley-value coalition analysis via [GameModelService]. Returns not_applicable for < 3 or > 8 players. */
object CoalitionAnalysisHandler : ToolHandler {
    override suspend fun handle(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val scenario = ctx.gateway.activeScenario()
            ?: return ToolOutcome(Args.error("no_scenario"), ToolDispatcher.ERROR_LABEL)

        val analysisContext = GameModelService.analyzeWithContext(scenario)
        val coalition = analysisContext.coalitionResult ?: run {
            val playerCount = scenario.players().size
            return ToolOutcome(
                buildJsonObject {
                    put("status", "not_applicable")
                    put("reason", "Coalition analysis requires 3–8 players; this scenario has $playerCount.")
                },
                "Coalition analysis not applicable"
            )
        }

        fun idToName(id: String) = scenario.stakeholders.firstOrNull { it.id == id }?.name ?: id
        val out = buildJsonObject {
            put("status", "ok")
            put("core_stable", coalition.coreStable)
            put("summary", coalition.summary)
            putJsonObject("shapley_values") {
                coalition.values.forEach { (id, v) -> put(idToName(id), v) }
            }
            if (coalition.recruitmentOrder.isNotEmpty()) {
                putJsonArray("recruitment_order") { coalition.recruitmentOrder.forEach { add(JsonPrimitive(idToName(it))) } }
            }
            if (coalition.spoilers.isNotEmpty()) {
                putJsonArray("spoilers") { coalition.spoilers.forEach { add(JsonPrimitive(idToName(it))) } }
            }
        }
        return ToolOutcome(out, "Computed Shapley values for ${coalition.values.size} players", UiKind.RECOMMENDATION)
    }
}
