package com.drkkr.strategos.agent

import com.drkkr.strategos.data.repo.JournalRepository
import com.drkkr.strategos.data.repo.OutcomeRepository
import com.drkkr.strategos.domain.flywheel.OutcomeRecord
import com.drkkr.strategos.domain.game.CounterfactualAnalyzer
import com.drkkr.strategos.domain.game.GameAnalysisResult
import com.drkkr.strategos.domain.game.GameModelService
import com.drkkr.strategos.domain.model.Scenario

/**
 * Synergy Upgrade 5: auto-trigger counterfactual coaching on OutcomeRecord submission.
 *
 * Saves the record, runs CounterfactualAnalyzer on the active scenario, then writes a
 * "lesson learned" Journal entry linked to that scenario.  Every step after the initial
 * save is best-effort: failures are swallowed so a broken analysis never blocks submission.
 */
class CounterfactualCoachService(
    private val outcomes: OutcomeRepository,
    private val journal: JournalRepository,
) {

    /**
     * Persist [record] under [scenarioId] and, if [scenario] is supplied and solvable,
     * write a counterfactual lesson to the journal.
     *
     * Never throws.
     */
    suspend fun submitAndCoach(scenarioId: String, record: OutcomeRecord, scenario: Scenario?) {
        // Step 1 — always persist first; nothing else runs until this succeeds.
        runCatching { outcomes.record(scenarioId, record) }.onFailure { return }

        // Step 2 — counterfactual analysis (best-effort).
        val lesson = runCatching {
            buildLesson(record, scenario)
        }.getOrNull() ?: basicLesson(record)

        // Step 3 — journal write (best-effort).
        runCatching { journal.recordEntry(lesson, INSIGHT_TAG, scenarioId) }
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private suspend fun buildLesson(record: OutcomeRecord, scenario: Scenario?): String {
        if (scenario == null) return basicLesson(record)

        val ctx = GameModelService.analyzeWithContext(scenario)
        val (ir, baseline) = when (val r = ctx.result) {
            is GameAnalysisResult.Solved -> r.ir to r.outcome
            else -> return basicLesson(record)
        }

        val playerId = ir.playerIds.firstOrNull() ?: return basicLesson(record)
        val analysis = CounterfactualAnalyzer.analyze(ir, baseline, playerId)

        val best = analysis.results.maxByOrNull { it.counterfactualPayoff }
        val recommended = analysis.recommendations[playerId]

        return buildString {
            append("Outcome ${record.outcomeRating}/5. ")
            if (record.followed) {
                append("You followed the recommended \"${record.recommendedMove}\". ")
                if (best != null && best.deviationGain > 0.05) {
                    append("Best alternative was \"${best.forcedActionId}\" (+${fmt(best.deviationGain)} payoff). ")
                    append("Consider it if circumstances change.")
                } else {
                    append("Analysis confirms this was optimal — stay consistent.")
                }
            } else {
                append("Recommended \"${record.recommendedMove}\", you chose \"${record.actualMove}\". ")
                if (recommended != null) {
                    val bestResult = analysis.results.firstOrNull { it.forcedActionId == recommended }
                    if (bestResult != null && bestResult.deviationGain > 0) {
                        append("\"${recommended}\" offers +${fmt(bestResult.deviationGain)} gain. ")
                    } else {
                        append("\"${recommended}\" was the stronger play. ")
                    }
                    append("Consider \"${recommended}\" next time.")
                } else {
                    append("Review options before deviating from the recommendation.")
                }
            }
        }
    }

    private fun basicLesson(record: OutcomeRecord): String {
        val chosen = if (record.followed) "followed recommendation" else "deviated from recommendation"
        return "Outcome ${record.outcomeRating}/5 — $chosen (\"${record.actualMove}\"). " +
                "No counterfactual analysis available for this game type."
    }

    companion object {
        /** Mood tag used for system-generated lesson entries. */
        const val INSIGHT_TAG = "INSIGHT"

        private fun fmt(d: Double): String = "%.2f".format(d)
    }
}
