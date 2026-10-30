package com.drkkr.strategos.domain.game

/**
 * Contract every on-device game solver must satisfy.
 *
 * Solvers are pure functions: same [GameIR] → same [SolverOutcome].
 * They never read Android APIs, never spawn threads, never allocate beyond [SolverLimits].
 */
interface GameSolver {
    fun capabilities(): SolverCapabilities
    fun solve(ir: GameIR, limits: SolverLimits = SolverLimits.ON_DEVICE): SolverOutcome
}
