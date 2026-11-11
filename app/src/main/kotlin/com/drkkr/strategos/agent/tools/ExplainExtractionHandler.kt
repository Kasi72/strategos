package com.drkkr.strategos.agent.tools

import com.drkkr.strategos.agent.ToolContext
import com.drkkr.strategos.agent.ToolDispatcher
import com.drkkr.strategos.agent.ToolHandler
import com.drkkr.strategos.agent.ToolOutcome
import com.drkkr.strategos.domain.agent.ExtractionVerifier
import com.drkkr.strategos.domain.agent.PayoffCoverage
import com.drkkr.strategos.domain.game.GameAnalysisResult
import com.drkkr.strategos.domain.game.GameModelService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Explains what the extraction layer inferred about the scenario and flags any structural mismatches. */
object ExplainExtractionHandler : ToolHandler {
    override suspend fun handle(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val scenario = ctx.gateway.activeScenario()
            ?: return ToolOutcome(Args.error("no_active_scenario"), ToolDispatcher.ERROR_LABEL)

        val analysisContext = ctx.analysisCache?.getOrCompute(scenario) ?: GameModelService.analyzeWithContext(scenario)
        val ir = when (val r = analysisContext.result) {
            is GameAnalysisResult.Solved  -> r.ir
            is GameAnalysisResult.Partial -> r.ir
            else -> null
        }

        val cov = PayoffCoverage.of(scenario)
        val archetype = ir?.archetype

        // Mirror the stressVerdict logic from EngineDirective build: fragile when payoff coverage is partial
        val stressVerdict = if (cov.filled < cov.expected) "FRAGILE" else "ROBUST"

        val warning = ExtractionVerifier.verify(
            scenario = scenario,
            archetype = archetype,
            stressVerdict = stressVerdict,
            payoffsFilled = cov.filled,
            payoffsExpected = cov.expected
        )

        val playerCount = scenario.players().size
        val actionCounts = scenario.players().map { p -> scenario.actionsFor(p.id).size }

        val out = buildJsonObject {
            put("status", "ok")
            put("players_detected", playerCount)
            put("payoffs_filled", cov.filled)
            put("payoffs_expected", cov.expected)
            put("coverage_pct", if (cov.expected > 0) (cov.filled * 100.0 / cov.expected).toInt() else 0)
            put("stress_verdict", stressVerdict)
            archetype?.let { put("detected_archetype", it.name) }
            putJsonArray("action_counts_per_player") {
                scenario.players().forEachIndexed { idx, p ->
                    addJsonObject {
                        put("player_id", p.id)
                        put("action_count", actionCounts.getOrElse(idx) { 0 })
                    }
                }
            }
            if (cov.missingProfiles.isNotEmpty()) {
                put("missing_profile_count", cov.missingProfiles.size)
            }
            if (warning != null) {
                put("extraction_warning", warning)
            } else {
                put("extraction_ok", true)
            }
            // U9: surface multi-hypothesis confidence when below threshold
            if (analysisContext.extractionConfidence < 1.0f) {
                put("extraction_confidence", analysisContext.extractionConfidence)
            }
            if (analysisContext.extractionConfidence < 0.6f && analysisContext.alternativeInterpretations.isNotEmpty()) {
                putJsonArray("alternative_interpretations") {
                    analysisContext.alternativeInterpretations.take(2).forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                }
            }
        }
        return ToolOutcome(out, "Extraction explained")
    }
}
