package com.drkkr.strategos.domain.game

/** Game-theoretic model classes solvers may support. */
enum class GameClass {
    FINITE_NORMAL_FORM,
    ZERO_SUM_2P,
    COOPERATIVE,
    DYNAMIC,
    BAYESIAN,
    REPEATED_FINITE,
    UNKNOWN
}

/** What a solver declares it can handle. Checked before invocation. */
data class SolverCapabilities(
    val id: String,
    val supportedClasses: Set<GameClass>,
    val maxPlayers: Int,
    val maxActionsPerPlayer: Int,
    val supportsMixedStrategies: Boolean,
    val supportsUncertainty: Boolean
)

/** Hard resource caps. Solver must never exceed these. */
data class SolverLimits(
    val maxPlayers: Int,
    val maxActionsPerPlayer: Int,
    val maxGameProfiles: Int,
    val maxRuntimeMs: Long,
    val maxWorkingMemoryBytes: Long
) {
    companion object {
        val ON_DEVICE = SolverLimits(
            maxPlayers = 4,
            maxActionsPerPlayer = 10,
            maxGameProfiles = 10_000,
            maxRuntimeMs = 5_000L,
            maxWorkingMemoryBytes = 32L * 1024 * 1024
        )
    }
}

/** How completely a solver run finished. */
enum class SolverCompletion {
    /** All equilibria found; provably complete for this game class. */
    COMPLETE,
    /** Some equilibria may be missing (degenerate, timeout, support limit). */
    PARTIAL,
    /** Stopped before exhausting the search space. */
    TIMEOUT,
    /** Game class not handled by this solver. */
    UNSUPPORTED,
    /** Numerical issue prevented convergence. */
    NUMERICAL_FAILURE
}

/** Whether incomplete analysis affects a downstream recommendation. */
enum class AnalysisIntegrity {
    COMPLETE,
    INCOMPLETE,
    BLOCKED
}

/** Diagnostic metadata from one solver invocation. */
data class SolverDiagnostics(
    val method: String,
    val solverVersion: String,
    val converged: Boolean,
    val residual: Double?,
    val elapsedMs: Long,
    val limitations: List<String>,
    val completion: SolverCompletion
)

/**
 * One equilibrium point expressed as a mixed strategy per player.
 * For a pure strategy NE, each player's distribution is a unit vector;
 * pureProfile records the strategy indices for convenient downstream use.
 */
data class EquilibriumProfile(
    val mixedStrategies: List<DoubleArray>,
    val pureProfile: List<Int>? = null
)

/**
 * Result of running a solver.
 * Contract: Solved must never be returned when the solver stopped early,
 * failed to converge, or received an unsupported structure.
 */
sealed interface SolverOutcome {
    /** Full solution. diagnostics.completion must be COMPLETE. */
    data class Solved(
        val equilibria: List<EquilibriumProfile>,
        val diagnostics: SolverDiagnostics
    ) : SolverOutcome

    /** Partial; some equilibria may be missing. */
    data class Incomplete(
        val partialEquilibria: List<EquilibriumProfile>,
        val reason: String,
        val diagnostics: SolverDiagnostics,
        val integrity: AnalysisIntegrity = AnalysisIntegrity.INCOMPLETE
    ) : SolverOutcome

    /** This solver cannot handle the game. Not an error. */
    data class Unsupported(val reason: String) : SolverOutcome

    /** Input model is structurally invalid. */
    data class InvalidModel(val violations: List<String>) : SolverOutcome
}
