package com.drkkr.strategos.agent

import com.drkkr.strategos.domain.game.GameModelService
import com.drkkr.strategos.domain.model.Scenario
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Turn-scoped cache for [GameModelService.analyzeWithContext].
 *
 * Without this, every tool handler in a single agent turn calls [GameModelService.analyzeWithContext]
 * independently on the same [Scenario], running the full game-theory solver ~15 times per turn.
 * This cache reduces that to exactly one engine run per turn.
 *
 * Usage: call [getOrCompute] from any tool handler instead of [GameModelService.analyzeWithContext] directly.
 * Call [clear] at the start of each new turn.
 */
class TurnAnalysisCache {
    private val mutex = Mutex()
    private var entry: Pair<Scenario, GameModelService.AnalysisContext>? = null

    /** Returns the cached result if the scenario is the same instance; otherwise runs the engine and caches. */
    suspend fun getOrCompute(scenario: Scenario): GameModelService.AnalysisContext = mutex.withLock {
        val cached = entry
        if (cached != null && cached.first === scenario) return@withLock cached.second
        val result = GameModelService.analyzeWithContext(scenario)
        entry = scenario to result
        result
    }

    /** Call at the start of each turn so a new scenario version triggers a fresh engine run. */
    fun clear() {
        entry = null
    }
}
