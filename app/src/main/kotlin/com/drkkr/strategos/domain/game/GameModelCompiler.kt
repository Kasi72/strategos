package com.drkkr.strategos.domain.game

import com.drkkr.strategos.domain.model.GameRole
import com.drkkr.strategos.domain.model.PayoffDimension
import com.drkkr.strategos.domain.model.Scenario

/**
 * Compiles a [Scenario] into a [GameIR].
 *
 * Does NOT invoke any solver. Validates structure and payoff coverage,
 * then builds the row-major utility matrices.
 *
 * Hard limits mirror [SolverLimits.ON_DEVICE].
 */
object GameModelCompiler {

    fun compile(
        scenario: Scenario,
        weights: Map<PayoffDimension, Double> = scenario.weights,
        limits: SolverLimits = SolverLimits.ON_DEVICE
    ): CompileResult {
        val errors = mutableListOf<CompileError>()

        val players = scenario.stakeholders.filter { it.gameRole == GameRole.PLAYER }

        if (players.size < 2) {
            errors += CompileError.TooFewPlayers(players.size)
        }
        if (players.size > limits.maxPlayers) {
            errors += CompileError.TooManyPlayers(players.size, limits.maxPlayers)
        }
        if (errors.isNotEmpty()) return CompileResult.Failure(errors)

        val playerIds = players.map { it.id }
        val actionMap: Map<String, List<String>> = playerIds.associateWith { pid ->
            scenario.actions.filter { it.stakeholderId == pid }.map { it.id }
        }

        for ((pid, acts) in actionMap) {
            if (acts.isEmpty()) errors += CompileError.PlayerHasNoActions(pid)
            else if (acts.size > limits.maxActionsPerPlayer)
                errors += CompileError.TooManyActions(pid, acts.size, limits.maxActionsPerPlayer)
            val seen = mutableSetOf<String>()
            for (aid in acts) {
                if (!seen.add(aid)) errors += CompileError.DuplicateActionId(aid, pid)
            }
        }
        if (errors.isNotEmpty()) return CompileResult.Failure(errors)

        val strategyCounts = playerIds.map { actionMap[it]!!.size }
        val totalProfiles = strategyCounts.fold(1, Int::times)

        // Build utility matrices — NaN for missing cells.
        val utility = Array(playerIds.size) { DoubleArray(totalProfiles) { Double.NaN } }

        for (entry in scenario.payoffs) {
            val pi = playerIds.indexOf(entry.stakeholderId)
            if (pi < 0) continue
            val profileIndices = resolveProfile(entry.actionProfile, playerIds, actionMap) ?: continue
            val idx = profileIndex(profileIndices, strategyCounts)
            utility[pi][idx] = entry.payoff.weighted(weights)
        }

        // Validate coverage and finiteness.
        var missing = 0
        for (pi in playerIds.indices) {
            for (idx in 0 until totalProfiles) {
                val v = utility[pi][idx]
                if (v.isNaN()) {
                    missing++
                } else if (!v.isFinite()) {
                    errors += CompileError.NonFinitePayoff(playerIds[pi], idx)
                }
            }
        }
        if (missing > 0) errors += CompileError.MissingPayoffProfiles(missing, totalProfiles * playerIds.size)
        if (errors.isNotEmpty()) return CompileResult.Failure(errors)

        val gameClass = inferClass(scenario, playerIds, strategyCounts, utility)
        val coveragePct = 100

        val prelimIr = GameIR(
            scenarioId = scenario.id,
            playerIds = playerIds,
            actionIds = playerIds.map { actionMap[it]!! },
            strategyCounts = strategyCounts,
            utility = utility.map { it },
            gameClass = gameClass,
            coveragePct = coveragePct
        )
        return CompileResult.Success(
            prelimIr.copy(archetype = GameArchetypeDetector.detect(prelimIr))
        )
    }

    private fun resolveProfile(
        actionProfile: Map<String, String>,
        playerIds: List<String>,
        actionMap: Map<String, List<String>>
    ): IntArray? {
        val indices = IntArray(playerIds.size)
        for (p in playerIds.indices) {
            val aid = actionProfile[playerIds[p]] ?: return null
            val si = actionMap[playerIds[p]]!!.indexOf(aid)
            if (si < 0) return null
            indices[p] = si
        }
        return indices
    }

    private fun profileIndex(profile: IntArray, strategyCounts: List<Int>): Int {
        var idx = 0; var stride = 1
        for (p in strategyCounts.indices.reversed()) {
            idx += profile[p] * stride
            stride *= strategyCounts[p]
        }
        return idx
    }

    private fun inferClass(
        scenario: Scenario,
        playerIds: List<String>,
        strategyCounts: List<Int>,
        utility: Array<DoubleArray>
    ): GameClass {
        if (playerIds.size == 2) {
            val totalProfiles = strategyCounts.fold(1, Int::times)
            val isZeroSum = (0 until totalProfiles).all { i ->
                kotlin.math.abs(utility[0][i] + utility[1][i]) < 1e-9
            }
            if (isZeroSum) return GameClass.ZERO_SUM_2P
        }
        if (scenario.repeated) return GameClass.REPEATED_FINITE
        return GameClass.FINITE_NORMAL_FORM
    }
}
