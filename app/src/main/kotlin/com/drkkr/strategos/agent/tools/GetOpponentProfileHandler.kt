package com.drkkr.strategos.agent.tools

import com.drkkr.strategos.agent.ToolContext
import com.drkkr.strategos.agent.ToolDispatcher
import com.drkkr.strategos.agent.ToolHandler
import com.drkkr.strategos.agent.ToolOutcome
import com.drkkr.strategos.domain.flywheel.PatternSynthesizer
import com.drkkr.strategos.domain.flywheel.RetroDeceptionAuditor
import com.drkkr.strategos.domain.game.GameAnalysisResult
import com.drkkr.strategos.domain.game.GameModelService
import com.drkkr.strategos.domain.game.OpponentModel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

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

        val deceptionAnnotations = run {
            val scenario = ctx.gateway.activeScenario() ?: return@run emptyList()
            val analysisContext = ctx.analysisCache?.getOrCompute(scenario) ?: GameModelService.analyzeWithContext(scenario)
            val ir = when (val r = analysisContext.result) {
                is GameAnalysisResult.Solved  -> r.ir
                is GameAnalysisResult.Partial -> r.ir
                else -> null
            } ?: return@run emptyList()
            val model = OpponentModel(playerId = opponentId, epsilon = 0.1, discountFactor = 0.9)
            RetroDeceptionAuditor.audit(ir, model, records)
        }

        val out = buildJsonObject {
            put("status", "ok")
            put("opponent_id", pattern.opponentId)
            pattern.dominantArchetype?.let { put("dominant_archetype", it) }
            put("avg_rating", pattern.avgRating)
            put("follow_rate", pattern.followRate)
            put("sample_size", pattern.sampleSize)
            put("insight", pattern.insight)
            put("deception_count", deceptionAnnotations.size)
            if (deceptionAnnotations.isNotEmpty()) {
                putJsonArray("deception_history") {
                    deceptionAnnotations.forEach { a ->
                        addJsonObject {
                            put("kind", a.kind.name)
                            put("equilibrium_action_index", a.equilibriumActionIndex)
                            put("observed_action_index", a.observedActionIndex)
                            put("explanation", a.explanation)
                        }
                    }
                }
            }
        }
        return ToolOutcome(out, "Opponent profile ready")
    }
}
