package com.drkkr.strategos.domain.game

/**
 * Analysis-quality state machine for a decision under game-theoretic evaluation.
 *
 * Distinct from [com.drkkr.strategos.domain.model.LifecycleStage], which tracks
 * governance progress. This state tracks how much confidence the engine has earned
 * in its equilibrium result.
 *
 * Legal transitions:
 *   EXPLORATORY -> CONDITIONAL  (game model compiles; at least one equilibrium found)
 *   CONDITIONAL -> VERIFIED     (EquilibriumVerifier residual <= threshold for all equilibria)
 *   VERIFIED    -> CONDITIONAL  (payoffs or actions updated; re-verification required)
 *   any         -> EXPLORATORY  (model invalidated: structure change, missing payoffs)
 */
enum class DecisionState {
    /** No validated game model; analysis is speculative or unavailable. */
    EXPLORATORY,
    /** Game model compiled; equilibrium found but not independently verified. */
    CONDITIONAL,
    /** All reported equilibria pass EquilibriumVerifier deviation-gain check. */
    VERIFIED;

    fun canTransitionTo(target: DecisionState): Boolean = when (this) {
        EXPLORATORY  -> target == CONDITIONAL
        CONDITIONAL  -> target == VERIFIED || target == EXPLORATORY
        VERIFIED     -> target == CONDITIONAL || target == EXPLORATORY
    }
}

/** Result of attempting a state transition. */
sealed interface TransitionResult {
    data class Allowed(val from: DecisionState, val to: DecisionState) : TransitionResult
    data class Rejected(val from: DecisionState, val to: DecisionState, val reason: String) : TransitionResult
}

/**
 * Manages a [DecisionState] with guarded transitions and audit trail.
 *
 * Not thread-safe; callers must synchronize if accessed from multiple coroutines.
 */
class DecisionStateMachine(initial: DecisionState = DecisionState.EXPLORATORY) {

    var state: DecisionState = initial
        private set

    private val _history = mutableListOf<StateTransitionRecord>()
    val history: List<StateTransitionRecord> get() = _history.toList()

    fun transition(target: DecisionState, reason: String = ""): TransitionResult {
        val from = state
        if (!from.canTransitionTo(target)) {
            return TransitionResult.Rejected(from, target,
                "Transition $from -> $target not allowed. $reason".trim())
        }
        state = target
        _history += StateTransitionRecord(from, target, reason, System.currentTimeMillis())
        return TransitionResult.Allowed(from, target)
    }

    /** Reset to EXPLORATORY unconditionally (model invalidated). */
    fun invalidate(reason: String = "model invalidated") {
        val from = state
        state = DecisionState.EXPLORATORY
        _history += StateTransitionRecord(from, DecisionState.EXPLORATORY, reason, System.currentTimeMillis())
    }
}

data class StateTransitionRecord(
    val from: DecisionState,
    val to: DecisionState,
    val reason: String,
    val timestampMs: Long
)
