package com.drkkr.strategos.agent.tools

import com.drkkr.strategos.agent.ToolContext
import com.drkkr.strategos.agent.ToolDispatcher
import com.drkkr.strategos.agent.ToolHandler
import com.drkkr.strategos.agent.ToolOutcome
import com.drkkr.strategos.data.repo.UiKind
import com.drkkr.strategos.domain.agent.EngineDirectiveBuilder
import com.drkkr.strategos.domain.agent.PayoffCoverage
import com.drkkr.strategos.domain.game.GameModelService
import com.drkkr.strategos.domain.game.MinimaxRegretSolver
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Re-solves the scenario and returns a refreshed [EngineDirective] with minimax regret data.
 *
 * The LLM never edits the scenario directly; payoff adjustments arrive via [ProposeChangesHandler]
 * which creates a [ChangeSet]. This handler only re-analyses the saved scenario and surfaces
 * the updated regret picture so the coach can explain trade-offs to the user.
 */
object CalibratePayoffsHandler : ToolHandler {

    override suspend fun handle(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val scenario = ctx.gateway.activeScenario()
            ?: return ToolOutcome(Args.error("no_scenario"), ToolDispatcher.ERROR_LABEL)

        val cov = PayoffCoverage.of(scenario)
        if (cov.filled == 0) {
            return ToolOutcome(
                buildJsonObject { put("error", "no_payoffs"); put("hint", "Use propose_changes to add payoff estimates first.") },
                ToolDispatcher.ERROR_LABEL
            )
        }

        val gameContext = GameModelService.analyzeWithContext(scenario)
        val ir = when (val r = gameContext.result) {
            is com.drkkr.strategos.domain.game.GameAnalysisResult.Solved  -> r.ir
            is com.drkkr.strategos.domain.game.GameAnalysisResult.Partial -> r.ir
            else -> null
        }

        val regretResult = ir?.let { MinimaxRegretSolver.solve(it) } ?: gameContext.regretResult

        val pipelineResult = ctx.gateway.analyzeAndPublish()
            ?: return ToolOutcome(Args.error("analysis_failed"), ToolDispatcher.ERROR_LABEL)

        val directive = EngineDirectiveBuilder.build(
            s = scenario,
            pr = pipelineResult,
            stress = null,
            regretResult = regretResult
        )

        val out = buildJsonObject {
            put("directive", directive.toJson())
            if (regretResult != null) {
                putJsonObject("regret_analysis") {
                    put("strategy_index", regretResult.strategy)
                    put("worst_case_regret", regretResult.worstCaseRegret)
                    put("regret_gap", regretResult.regretGap)
                    put("payoffs_filled", cov.filled)
                    put("payoffs_expected", cov.expected)
                }
            } else {
                put("regret_analysis", null as String?)
                put("note", "Minimax regret requires a 2-player game with at least one payoff per cell.")
            }
        }

        return ToolOutcome(out, "Payoffs calibrated", UiKind.RECOMMENDATION)
    }
}
