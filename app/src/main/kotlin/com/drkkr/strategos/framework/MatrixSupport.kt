package com.drkkr.strategos.framework

import com.drkkr.strategos.domain.framework.CalculationStatus
import com.drkkr.strategos.domain.model.MultiPayoff
import com.drkkr.strategos.domain.model.PayoffDimension
import com.drkkr.strategos.domain.model.Scenario

/**
 * Signals that a module cannot run because the scenario lacks the structured
 * input it needs (not an error). The registry maps this to a clean SKIP rather
 * than a FAILED result, so the user sees "not enough input" instead of a crash.
 */
class InsufficientInputException(message: String) : Exception(message)

/**
 * Utilities converting Scenario payoff entries into per-player scalar matrices.
 * Assumes normal-form two-or-more player games; expects each PayoffEntry to
 * carry a full actionProfile (map of every stakeholderId -> chosen actionId).
 */
object MatrixSupport {

    data class MatrixResult(
        val form: StrategicForm,
        val filledProfiles: Int,
        val totalProfiles: Int,
        val calculationStatus: CalculationStatus
    ) {
        val coveragePct: Int get() = if (totalProfiles == 0) 0 else (filledProfiles * 100 / totalProfiles)
        val coverageLabel: String get() = "$filledProfiles of $totalProfiles profiles complete; $coveragePct% coverage"
    }

    data class StrategicForm(
        val players: List<String>,               // stakeholder ids
        val strategies: Map<String, List<String>>, // sid -> list of action ids in order
        /** utility[playerIndex][profileIndex]; NaN = missing (never zero-filled). */
        val utility: Array<DoubleArray>
    ) {
        fun profileIndex(profile: List<Int>): Int {
            var idx = 0; var mul = 1
            for (i in players.indices.reversed()) {
                idx += profile[i] * mul
                mul *= strategies[players[i]]!!.size
            }
            return idx
        }
        fun profileCount(): Int = strategies.values.fold(1) { a, s -> a * s.size }
        fun profileFor(index: Int): List<Int> {
            val res = IntArray(players.size)
            var rem = index
            for (i in players.indices.reversed()) {
                val size = strategies[players[i]]!!.size
                res[i] = rem % size
                rem /= size
            }
            return res.toList()
        }
    }

    /** Full build — returns null if any required field is missing (stakeholders, actions, payoffs). */
    fun build(scenario: Scenario, weights: Map<PayoffDimension, Double> = scenario.weights): StrategicForm? =
        buildChecked(scenario, weights)?.form

    /**
     * Like [build] but throws [InsufficientInputException] instead of returning
     * null, so modules can one-line the guard: `val sf = MatrixSupport.buildOrThrow(model)`.
     * The registry converts the exception into a SKIPPED_INSUFFICIENT_INPUT result.
     */
    fun buildOrThrow(scenario: Scenario, weights: Map<PayoffDimension, Double> = scenario.weights): StrategicForm =
        build(scenario, weights) ?: throw InsufficientInputException(
            "This framework needs a payoff matrix: at least 2 stakeholders, one or more actions per stakeholder, and at least one payoff entry."
        )

    /**
     * Build with coverage reporting. Missing payoff profiles remain NaN — never zero-filled.
     * Callers must check calculationStatus before trusting equilibrium results.
     */
    fun buildChecked(scenario: Scenario, weights: Map<PayoffDimension, Double> = scenario.weights): MatrixResult? {
        if (scenario.stakeholders.size < 2 || scenario.payoffs.isEmpty()) return null
        val players = scenario.stakeholders.map { it.id }
        val strategies = players.associateWith { sid -> scenario.actionsFor(sid).map { it.id } }
        if (strategies.any { it.value.isEmpty() }) return null
        val count = strategies.values.fold(1) { a, s -> a * s.size }
        // Total expected cells = count profiles × players
        val totalPlayerCells = count * players.size
        val utility = Array(players.size) { DoubleArray(count) { Double.NaN } }

        for (entry in scenario.payoffs) {
            val profileOrNull = players.map { entry.actionProfile[it] }
            if (profileOrNull.any { it == null }) continue
            val profile = profileOrNull.requireNoNulls()
            val idxList = profile.mapIndexed { i, aid ->
                val idx = strategies[players[i]]!!.indexOf(aid)
                if (idx < 0) return@mapIndexed -1 else idx
            }
            if (idxList.any { it < 0 }) continue
            val idx = idxList.foldIndexed(0) { i, acc, v -> acc * strategies[players[i]]!!.size + v }
            val pIndex = players.indexOf(entry.stakeholderId)
            if (pIndex < 0) continue
            // Only write if not already set (first definition wins; NaN = missing)
            if (utility[pIndex][idx].isNaN()) {
                utility[pIndex][idx] = entry.payoff.weighted(weights)
            }
        }

        val filledCells = utility.sumOf { row -> row.count { !it.isNaN() } }
        val status = when {
            filledCells == 0 -> CalculationStatus.NOT_RUN
            filledCells == totalPlayerCells -> CalculationStatus.CALCULATED
            else -> CalculationStatus.PARTIALLY_CALCULATED
        }

        return MatrixResult(
            form = StrategicForm(players, strategies, utility),
            filledProfiles = filledCells,
            totalProfiles = totalPlayerCells,
            calculationStatus = status
        )
    }
}
