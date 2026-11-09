package com.drkkr.strategos.agent.tools

import com.drkkr.strategos.agent.ToolContext
import com.drkkr.strategos.agent.ToolDispatcher
import com.drkkr.strategos.agent.ToolHandler
import com.drkkr.strategos.agent.ToolOutcome
import com.drkkr.strategos.domain.agent.RetrospectiveEngine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Runs inverse utility inference on outcome history to estimate the opponent's PayoffDimension weights. */
object InferOpponentUtilityHandler : ToolHandler {
    override suspend fun handle(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val outcomes = ctx.outcomes
            ?: return ToolOutcome(Args.error("no_outcome_store"), ToolDispatcher.ERROR_LABEL)

        val limit = args["limit"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() } ?: 20
        val records = runCatching { outcomes.forScenario(ctx.scenarioId) }.getOrDefault(emptyList())
            .takeLast(limit)

        val weights = RetrospectiveEngine.inferOpponentUtility(records)
        if (weights == null) {
            val out = buildJsonObject {
                put("status", "not_enough_data")
                put("detail", "Need at least 3 outcomes with a recorded opponent action. Have ${records.count { it.observedOpponentActionIndex != null }}.")
            }
            return ToolOutcome(out, "Opponent utility — not enough data")
        }

        val confidence = when {
            records.count { it.observedOpponentActionIndex != null } >= 10 -> "HIGH"
            records.count { it.observedOpponentActionIndex != null } >= 5 -> "MEDIUM"
            else -> "LOW"
        }

        val out = buildJsonObject {
            put("status", "ok")
            put("sample_size", records.count { it.observedOpponentActionIndex != null })
            put("confidence", confidence)
            putJsonObject("opponent_weights") {
                weights.entries
                    .sortedByDescending { it.value }
                    .forEach { (dim, w) -> put(dim.name, (w * 100).toInt() / 100.0) }
            }
        }
        return ToolOutcome(out, "Opponent utility inferred")
    }
}
