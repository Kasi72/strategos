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
        val totalProfilesLong = strategyCounts.fold(1L, Long::times)
        if (totalProfilesLong > limits.maxGameProfiles) {
            errors += CompileError.TooManyProfiles(totalProfilesLong, limits.maxGameProfiles)
            return CompileResult.Failure(errors)
        }
        val totalProfiles = totalProfilesLong.toInt()

        // Build utility matrices — NaN for missing cells.
        val utility = Array(playerIds.size) { DoubleArray(totalProfiles) { Double.NaN } }

        for (entry in scenario.payoffs) {
            val pi = playerIds.indexOf(entry.stakeholderId)
            if (pi < 0) continue
            val profileIndices = resolveProfile(entry.actionProfile, playerIds, actionMap) ?: continue
            val idx = profileIndex(profileIndices, strategyCounts)
            utility[pi][idx] = entry.payoff.weighted(weights)
        }

        // Classify BEFORE the fill: NaN cells make the zero-sum check fail naturally, so a sparse table is never mis-classified as
        // ZERO_SUM_2P by its filled-in cells.
        val gameClass = inferClass(scenario, playerIds, strategyCounts, utility)

        // Fill missing cells and track coverage. Partial payoff tables are common in multi-player chat scenarios; hard-failing
        // them blocks all Stage 5 handlers. The fill is NEUTRAL: the player's own mean over its known cells (0.0 sits below every
        // real cell on the weighted 0–10 scale, so it made any option with a gap look dominated; a fixed 5.0 is not neutral on
        // other scales). Filled cells are marked in GameIR.proxy so no dominance claim rests on them. A player with no known cell
        // takes the mean of every known cell (all its cells are then marked, so it gets no dominance claims at all); a table with
        // no payoff at all fails.
        var missing = 0
        val proxy = Array(playerIds.size) { BooleanArray(totalProfiles) }
        val allKnown = utility.flatMap { u -> u.filter { !it.isNaN() } }
        if (allKnown.isEmpty()) return CompileResult.Failure(listOf(CompileError.MissingPayoffProfiles(totalProfiles * playerIds.size, totalProfiles * playerIds.size)))
        for (pi in playerIds.indices) {
            val known = utility[pi].filter { !it.isNaN() }
            val neutral = (known.ifEmpty { allKnown }).average()
            for (idx in 0 until totalProfiles) {
                val v = utility[pi][idx]
                if (v.isNaN()) {
                    utility[pi][idx] = neutral
                    proxy[pi][idx] = true
                    missing++
                } else if (!v.isFinite()) {
                    errors += CompileError.NonFinitePayoff(playerIds[pi], idx)
                }
            }
        }
        if (errors.isNotEmpty()) return CompileResult.Failure(errors)

        val total = totalProfiles * playerIds.size
        val coveragePct = if (missing == 0) 100 else ((total - missing) * 100.0 / total).toInt()

        val prelimIr = GameIR(
            scenarioId = scenario.id,
            playerIds = playerIds,
            actionIds = playerIds.map { actionMap[it]!! },
            strategyCounts = strategyCounts,
            utility = utility.map { it.copyOf() },
            gameClass = gameClass,
            coveragePct = coveragePct,
            proxy = if (missing == 0) emptyList() else proxy.toList()
        )
        return CompileResult.Success(
            // Reactive ("tempting") options are not real candidates; leave them out when projecting an N×M game to 2×2.
            prelimIr.copy(archetype = GameArchetypeDetector.detectProjected(prelimIr, reactiveActionIds(scenario)))
        )
    }

    private fun reactiveActionIds(scenario: Scenario): Set<String> =
        scenario.actions.filter { com.drkkr.strategos.domain.agent.Postures.of(it) == com.drkkr.strategos.domain.agent.Postures.REACTIVE }
            .map { it.id }.toSet()

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
            val totalProfiles = strategyCounts.fold(1L, Long::times).toInt()
            val isZeroSum = (0 until totalProfiles).all { i ->
                kotlin.math.abs(utility[0][i] + utility[1][i]) < 1e-9
            }
            if (isZeroSum) return GameClass.ZERO_SUM_2P
        }
        if (scenario.repeated) return GameClass.REPEATED_FINITE
        return GameClass.FINITE_NORMAL_FORM
    }
}
