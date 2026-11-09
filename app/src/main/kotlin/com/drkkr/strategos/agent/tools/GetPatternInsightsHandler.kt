package com.drkkr.strategos.agent.tools

import com.drkkr.strategos.agent.ToolContext
import com.drkkr.strategos.agent.ToolDispatcher
import com.drkkr.strategos.agent.ToolHandler
import com.drkkr.strategos.agent.ToolOutcome
import com.drkkr.strategos.data.repo.UiKind
import com.drkkr.strategos.domain.flywheel.PatternAnalyzer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Returns cross-scenario heuristics mined from past outcome records. */
object GetPatternInsightsHandler : ToolHandler {
    override suspend fun handle(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val outcomes = ctx.outcomes
            ?: return ToolOutcome(Args.error("outcomes_unavailable"), ToolDispatcher.ERROR_LABEL)
        val records = outcomes.forScenario(ctx.scenarioId)
        val analysis = PatternAnalyzer.analyze(records)
            ?: return ToolOutcome(
                buildJsonObject {
                    put("status", "insufficient_data")
                    put("minimum_records", PatternAnalyzer.MIN_RECORDS)
                    put("current_records", records.size)
                },
                "Not enough data for pattern analysis"
            )
        val out = buildJsonObject {
            put("status", "ok")
            put("record_count", records.size)
            putJsonArray("heuristics") {
                analysis.heuristics.forEach { h ->
                    addJsonObject {
                        put("condition", h.condition)
                        put("success_rate", h.successRate)
                        put("sample_size", h.sampleSize)
                    }
                }
            }
        }
        return ToolOutcome(out, "Pattern insights ready", UiKind.RECOMMENDATION)
    }
}
