package com.drkkr.strategos.agent.tools

import com.drkkr.strategos.agent.InvalidArguments
import com.drkkr.strategos.agent.ToolContext
import com.drkkr.strategos.agent.ToolDispatcher
import com.drkkr.strategos.agent.ToolHandler
import com.drkkr.strategos.agent.ToolOutcome
import com.drkkr.strategos.data.repo.UiKind
import com.drkkr.strategos.domain.agent.InferredStressTest
import com.drkkr.strategos.domain.agent.StressVerdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** §8.7 */
object StressTestInferredHandler : ToolHandler {
    override suspend fun handle(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val perturbation = Args.double(args, "perturbation") ?: 0.25
        if (perturbation !in 0.1..0.5) throw InvalidArguments("perturbation must be between 0.1 and 0.5")
        val s = ctx.gateway.activeScenario() ?: return ToolOutcome(Args.error("no_scenario"), ToolDispatcher.ERROR_LABEL)

        val result = withContext(Dispatchers.Default) { InferredStressTest.run(s, perturbation, ctx.pipeline) }
        val label = when (result.verdict) {
            StressVerdict.NOT_APPLICABLE -> "No AI estimates to stress-test"
            else -> "Stress-tested AI estimates (${result.runs} runs)"
        }
        return ToolOutcome(result.toJson(), label, UiKind.STRESS_TEST)
    }
}
