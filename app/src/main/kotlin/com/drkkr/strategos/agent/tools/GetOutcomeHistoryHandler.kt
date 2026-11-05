package com.drkkr.strategos.agent.tools

import com.drkkr.strategos.agent.ToolContext
import com.drkkr.strategos.agent.ToolDispatcher
import com.drkkr.strategos.agent.ToolHandler
import com.drkkr.strategos.agent.ToolOutcome
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Returns recent outcome records for the active scenario so the agent can reason about past decisions. */
object GetOutcomeHistoryHandler : ToolHandler {
    override suspend fun handle(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val outcomes = ctx.outcomes
            ?: return ToolOutcome(Args.error("outcomes_unavailable"), ToolDispatcher.ERROR_LABEL)
        val limit = Args.double(args, "limit")?.toInt()?.coerceIn(1, 50) ?: 10
        val records = outcomes.recentForScenario(ctx.scenarioId, limit)
        val out = buildJsonObject {
            put("status", "ok")
            put("count", records.size)
            putJsonArray("records") {
                records.forEach { r ->
                    addJsonObject {
                        put("recommended_move", r.recommendedMove)
                        put("actual_move", r.actualMove)
                        put("outcome_rating", r.outcomeRating)
                        r.opponentReaction?.let { put("opponent_reaction", it) }
                        r.archetypeName?.let { put("archetype_name", it) }
                        put("recorded_at", r.recordedAt)
                    }
                }
            }
        }
        return ToolOutcome(out, "Loaded ${records.size} outcome records")
    }
}
