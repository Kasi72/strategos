package com.drkkr.strategos.agent.tools

import com.drkkr.strategos.agent.ToolContext
import com.drkkr.strategos.agent.ToolDispatcher
import com.drkkr.strategos.agent.ToolHandler
import com.drkkr.strategos.agent.ToolOutcome
import com.drkkr.strategos.data.repo.UiKind
import com.drkkr.strategos.domain.agent.ChangeOp
import com.drkkr.strategos.domain.agent.OpSource
import com.drkkr.strategos.domain.agent.OpType
import com.drkkr.strategos.domain.agent.ScenarioFieldsPatch
import com.drkkr.strategos.domain.game.BeliefState
import com.drkkr.strategos.domain.game.BeliefStateTracker
import com.drkkr.strategos.domain.game.EpistemicDepthSolver
import com.drkkr.strategos.domain.game.GameAnalysisResult
import com.drkkr.strategos.domain.game.GameModelService
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Returns the current Bayesian belief state over game archetypes and the level-k epistemic
 * best response for the active scenario.
 *
 * Persistence: loads prior BeliefState from scenario.beliefStateJson, applies incremental
 * Bayesian update, then persists the posterior back via a SET_SCENARIO_FIELDS ChangeSet.
 * In Guided mode (autoApply available), the update is applied immediately so the next
 * get_belief_state call starts from the posterior. In Expert mode, the ChangeSet is left
 * PENDING for user review — belief state still improves if the user accepts it.
 */
object GetBeliefStateHandler : ToolHandler {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun handle(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val scenario = ctx.gateway.activeScenario()
            ?: return ToolOutcome(Args.error("no_scenario"), ToolDispatcher.ERROR_LABEL)

        val analysisContext = GameModelService.analyzeWithContext(scenario)
        val ir = when (val r = analysisContext.result) {
            is GameAnalysisResult.Solved  -> r.ir
            is GameAnalysisResult.Partial -> r.ir
            else -> null
        } ?: return ToolOutcome(
            buildJsonObject { put("status", "not_applicable"); put("reason", "No game model available.") },
            "No game model"
        )

        // Load persisted belief state or start from uniform prior
        val priorState: BeliefState = scenario.beliefStateJson
            ?.let { runCatching { json.decodeFromString(BeliefState.serializer(), it) }.getOrNull() }
            ?: BeliefStateTracker.uniformPrior()

        // Incremental Bayesian update with current IR's cooperation signal
        val coopRate = BeliefStateTracker.coopRateFromIR(ir)
        val updatedState = BeliefStateTracker.update(priorState, coopRate)

        // Persist updated state back via ChangeSet
        val newJson = json.encodeToString(BeliefState.serializer(), updatedState)
        val op = ChangeOp(
            type = OpType.SET_SCENARIO_FIELDS,
            rationale = "belief_state_update",
            source = OpSource.AI_INFERRED,
            scenarioFields = ScenarioFieldsPatch(beliefStateJson = newJson)
        )
        val opsJson = ChangeOp.json.encodeToString(ListSerializer(ChangeOp.serializer()), listOf(op))
        val entity = ctx.changeSets.create(
            scenarioId = ctx.scenarioId, conversationId = ctx.conversationId,
            summary = "Belief state update", opsJson = opsJson,
            refIdsJson = "{}", baseScenarioVersion = scenario.version
        )
        ctx.autoApply?.invoke(entity.id)

        val epistemicResult = analysisContext.epistemicResult
            ?: EpistemicDepthSolver.solve(ir, analysisContext.opponentModel)

        val out = buildJsonObject {
            put("status", "ok")
            put("observations_incorporated", if (scenario.beliefStateJson != null) "incremental" else "prior_initialized")
            putJsonObject("belief_state") {
                put("most_likely_archetype", updatedState.mostLikely.name)
                put("entropy", updatedState.entropy)
                putJsonObject("archetype_probabilities") {
                    updatedState.archetypeProbabilities.entries
                        .sortedByDescending { it.value }
                        .take(5)
                        .forEach { (k, v) -> put(k.name, v) }
                }
            }
            if (epistemicResult != null) {
                putJsonObject("epistemic_advice") {
                    put("opponent_level", epistemicResult.opponentLevel)
                    put("your_best_response", epistemicResult.yourBestResponse)
                    put("reasoning", epistemicResult.reasoning)
                }
            } else {
                put("epistemic_advice", null as String?)
                put("epistemic_note", "Level-k advice requires a 2-player game.")
            }
        }
        return ToolOutcome(out, "Belief state updated", UiKind.RECOMMENDATION)
    }
}
