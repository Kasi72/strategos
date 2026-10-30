package com.drkkr.strategos.domain.game

/**
 * Finds all pure-strategy Nash equilibria in a normal-form game.
 *
 * For each action profile, a player is at a best response if no unilateral
 * deviation strictly improves their payoff. A profile is a pure NE iff every
 * player is simultaneously at a best response.
 *
 * Complexity: O(n * A^n) where n = players, A = max actions. Within
 * [SolverLimits.ON_DEVICE] this stays under 40 000 profile checks.
 *
 * Returns [SolverOutcome.Solved] when at least one pure NE exists and the
 * search was complete. Returns [SolverOutcome.Incomplete] when the game has
 * no pure NE (mixed equilibria may exist). Never returns
 * [SolverOutcome.Solved] with empty equilibria list.
 */
object PureNashSolver : GameSolver {

    override fun capabilities() = SolverCapabilities(
        id = "pure-nash-v1",
        supportedClasses = setOf(
            GameClass.FINITE_NORMAL_FORM,
            GameClass.ZERO_SUM_2P,
            GameClass.REPEATED_FINITE
        ),
        maxPlayers = SolverLimits.ON_DEVICE.maxPlayers,
        maxActionsPerPlayer = SolverLimits.ON_DEVICE.maxActionsPerPlayer,
        supportsMixedStrategies = false,
        supportsUncertainty = false
    )

    override fun solve(ir: GameIR, limits: SolverLimits): SolverOutcome {
        val violations = validate(ir, limits)
        if (violations.isNotEmpty()) return SolverOutcome.InvalidModel(violations)

        val t0 = System.currentTimeMillis()
        val equilibria = mutableListOf<EquilibriumProfile>()
        val profile = IntArray(ir.playerCount)

        fun search(player: Int) {
            if (System.currentTimeMillis() - t0 > limits.maxRuntimeMs) return
            if (player == ir.playerCount) {
                if (isPureNash(ir, profile)) {
                    equilibria += EquilibriumProfile(
                        mixedStrategies = profile.mapIndexed { p, a ->
                            DoubleArray(ir.strategyCounts[p]) { if (it == a) 1.0 else 0.0 }
                        },
                        pureProfile = profile.toList()
                    )
                }
                return
            }
            for (a in 0 until ir.strategyCounts[player]) {
                profile[player] = a
                search(player + 1)
            }
        }
        search(0)

        val elapsed = System.currentTimeMillis() - t0
        val timedOut = elapsed >= limits.maxRuntimeMs

        val diagnostics = SolverDiagnostics(
            method = "pure-strategy best-response check",
            solverVersion = "1.0",
            converged = !timedOut,
            residual = if (equilibria.isNotEmpty()) 0.0 else null,
            elapsedMs = elapsed,
            limitations = if (timedOut) listOf("Search timed out; enumeration may be incomplete") else emptyList(),
            completion = when {
                timedOut -> SolverCompletion.TIMEOUT
                else     -> SolverCompletion.COMPLETE
            }
        )

        if (timedOut) {
            return SolverOutcome.Incomplete(equilibria, "Timed out before full enumeration", diagnostics)
        }
        if (equilibria.isEmpty()) {
            return SolverOutcome.Incomplete(
                partialEquilibria = emptyList(),
                reason = "No pure-strategy Nash equilibrium exists; a mixed equilibrium may be available",
                diagnostics = diagnostics.copy(completion = SolverCompletion.PARTIAL),
                integrity = AnalysisIntegrity.INCOMPLETE
            )
        }
        return SolverOutcome.Solved(equilibria, diagnostics)
    }

    private fun isPureNash(ir: GameIR, profile: IntArray): Boolean {
        for (p in 0 until ir.playerCount) {
            val current = ir.utility[p][ir.profileIndex(profile)]
            for (alt in 0 until ir.strategyCounts[p]) {
                if (alt == profile[p]) continue
                val devProfile = profile.copyOf()
                devProfile[p] = alt
                if (ir.utility[p][ir.profileIndex(devProfile)] > current) return false
            }
        }
        return true
    }

    private fun validate(ir: GameIR, limits: SolverLimits): List<String> = buildList {
        if (ir.playerCount < 2) add("Need at least 2 players, got ${ir.playerCount}")
        if (ir.playerCount > limits.maxPlayers) add("Player count ${ir.playerCount} exceeds limit ${limits.maxPlayers}")
        ir.strategyCounts.forEachIndexed { i, c ->
            if (c > limits.maxActionsPerPlayer) add("Player $i has $c actions, limit is ${limits.maxActionsPerPlayer}")
        }
        if (ir.profileCount > limits.maxGameProfiles) add("Profile count ${ir.profileCount} exceeds limit ${limits.maxGameProfiles}")
    }
}
