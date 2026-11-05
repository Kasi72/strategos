package com.drkkr.strategos.agent.tools

import com.drkkr.strategos.agent.ToolContext
import com.drkkr.strategos.agent.ToolDispatcher
import com.drkkr.strategos.agent.ToolHandler
import com.drkkr.strategos.agent.ToolOutcome
import com.drkkr.strategos.domain.flywheel.PatternSynthesizer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Synthesises a behavioural profile for a named opponent from past outcome records. */
object GetOpponentProfileHandler : ToolHandler {
    override suspend fun handle(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val outcomes = ctx.outcomes
            ?: return ToolOutcome(Args.error("outcomes_unavailable"), ToolDispatcher.ERROR_LABEL)
        val opponentId = Args.requireString(args, "opponent_id")
        val records = outcomes.forScenario(ctx.scenarioId)
        val pattern = PatternSynthesizer.synthesize(records, opponentId)
            ?: return ToolOutcome(
                buildJsonObject { put("status", "insufficient_data"); put("opponent_id", opponentId) },
                "Insufficient data for opponent profile"
            )
        val out = buildJsonObject {
            put("status", "ok")
            put("opponent_id", pattern.opponentId)
            pattern.dominantArchetype?.let { put("dominant_archetype", it) }
            put("avg_rating", pattern.avgRating)
            put("follow_rate", pattern.followRate)
            put("sample_size", pattern.sampleSize)
            put("insight", pattern.insight)
        }
        return ToolOutcome(out, "Opponent profile ready")
    }
}
