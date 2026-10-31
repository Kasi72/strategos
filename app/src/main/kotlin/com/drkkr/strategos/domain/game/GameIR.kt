package com.drkkr.strategos.domain.game

/**
 * Intermediate representation of a validated normal-form game.
 *
 * Produced by [GameModelCompiler] from a [com.drkkr.strategos.domain.model.Scenario].
 * Consumed by the solver layer. Immutable once constructed.
 *
 * Structure guarantee: every field is non-empty, all profile cells are finite,
 * and [playerIds].size == [strategyCounts].size == [utility].size.
 */
data class GameIR(
    val scenarioId: String,
    val playerIds: List<String>,
    val actionIds: List<List<String>>,
    val strategyCounts: List<Int>,
    /** utility[playerIndex][profileIndex]; row-major, identical to MatrixSupport convention. */
    val utility: List<DoubleArray>,
    val gameClass: GameClass,
    val coveragePct: Int,
    /** Named archetype detected by [GameArchetypeDetector]; [GameArchetype.GENERIC] when no pattern matched. */
    val archetype: GameArchetype = GameArchetype.GENERIC
) {
    val playerCount: Int get() = playerIds.size
    val profileCount: Int get() = strategyCounts.fold(1, Int::times)

    /** Row-major profile index; matches EquilibriumVerifier.profileIndex(). */
    fun profileIndex(profile: IntArray): Int {
        var idx = 0; var stride = 1
        for (p in strategyCounts.indices.reversed()) {
            idx += profile[p] * stride
            stride *= strategyCounts[p]
        }
        return idx
    }
}

/** Typed errors surfaced by [GameModelCompiler]. */
sealed interface CompileError {
    data class TooFewPlayers(val found: Int) : CompileError
    data class PlayerHasNoActions(val playerId: String) : CompileError
    data class TooManyPlayers(val found: Int, val limit: Int) : CompileError
    data class TooManyActions(val playerId: String, val found: Int, val limit: Int) : CompileError
    data class MissingPayoffProfiles(val missing: Int, val total: Int) : CompileError
    data class NonFinitePayoff(val playerId: String, val profileIndex: Int) : CompileError
    data class DuplicateActionId(val actionId: String, val playerId: String) : CompileError
}

/** Result of [GameModelCompiler.compile]. */
sealed interface CompileResult {
    data class Success(val ir: GameIR) : CompileResult
    data class Failure(val errors: List<CompileError>) : CompileResult
}
