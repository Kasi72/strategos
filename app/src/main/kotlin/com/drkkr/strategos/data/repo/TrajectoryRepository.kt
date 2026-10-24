package com.drkkr.strategos.data.repo

import androidx.room.withTransaction
import com.drkkr.strategos.data.db.StrategosDatabase
import com.drkkr.strategos.data.db.entities.QuickCheckFeedbackEntity
import com.drkkr.strategos.data.db.entities.TrajectoryPointEntity
import com.drkkr.strategos.domain.analysis.QuickCheckUpdater
import com.drkkr.strategos.domain.analysis.TrajectoryCalculator
import com.drkkr.strategos.domain.analysis.TrajectorySnapshot
import com.drkkr.strategos.domain.model.Scenario
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** Space 3 state. Everything here is deterministic Kotlin: no LLM call is ever made from this class (invariant I12). */
class TrajectoryRepository(
    private val db: StrategosDatabase,
    private val scenarios: ScenarioRepository,
    private val now: () -> Long = System::currentTimeMillis
) {
    fun observePoints(scenarioId: String): Flow<List<TrajectoryPointEntity>> = db.trajectoryDao().observeForScenario(scenarioId)

    suspend fun points(scenarioId: String): List<TrajectoryPointEntity> = db.trajectoryDao().forScenario(scenarioId)

    /** The latest stored point as a snapshot, or the neutral start (50 / 50, streak 0) when the project has none. */
    suspend fun current(scenarioId: String): TrajectorySnapshot {
        val p = db.trajectoryDao().latestForScenario(scenarioId) ?: return TrajectoryCalculator.buildSnapshot(50.0, 50.0, null, 0, DEFAULT_ARCHETYPE)
        return TrajectoryCalculator.buildSnapshot(p.trustScore, p.tensionScore, p.sigmoidProgress - p.momentumDelta, p.consistencyStreak, p.activeArchetype)
    }

    suspend fun submitQuickCheck(
        scenarioId: String, threadId: String?, recommendedActionLabel: String,
        executionChoice: String, counterpartReaction: String, selfFeeling: String, note: String = "",
        /** The project to update. A caller that owns the live copy (the view model) passes it and a [commit] that saves through it. */
        scenario: Scenario? = scenarios.find(scenarioId),
        commit: (suspend (Scenario) -> Unit)? = null
    ): TrajectorySnapshot {
        val before = current(scenarioId)
        val outcome = QuickCheckUpdater.update(
            scenario, before.trustScore, before.tensionScore, before.consistencyStreak, before.activeArchetype,
            executionChoice, counterpartReaction, selfFeeling
        )
        val at = now()
        val snapshot = TrajectoryCalculator.buildSnapshot(outcome.trust, outcome.tension, before.sigmoidProgress, outcome.streak, outcome.archetype)
        db.withTransaction {
            db.quickCheckDao().insert(
                QuickCheckFeedbackEntity(
                    id = UUID.randomUUID().toString(), scenarioId = scenarioId, threadId = threadId,
                    recommendedActionLabel = recommendedActionLabel, executionChoice = executionChoice,
                    counterpartReaction = counterpartReaction, selfFeeling = selfFeeling, reflectionNote = note,
                    trustDelta = outcome.trustDelta, tensionDelta = outcome.tensionDelta, createdAt = at
                )
            )
            db.trajectoryDao().insert(point(scenarioId, threadId, "QUICK_CHECK", snapshot, at))
        }
        // A newer stored scenario version (SaveResult.Conflict) just skips the trust-baseline write; the point is already kept.
        outcome.scenario?.let { if (commit != null) commit(it) else scenarios.save(it) }
        return snapshot
    }

    /** One finished chat turn: the model's trust/tension deltas and archetype, applied to the latest point. */
    suspend fun recordTurn(scenarioId: String, threadId: String?, trustDelta: Double, tensionDelta: Double, archetype: String?): TrajectorySnapshot {
        val before = current(scenarioId)
        val snapshot = TrajectoryCalculator.buildSnapshot(
            before.trustScore + trustDelta.coerceIn(-20.0, 20.0), before.tensionScore + tensionDelta.coerceIn(-20.0, 20.0),
            before.sigmoidProgress, before.consistencyStreak, archetype?.takeIf { it.isNotBlank() } ?: before.activeArchetype
        )
        db.trajectoryDao().insert(point(scenarioId, threadId, "THREAD_TURN", snapshot, now()))
        return snapshot
    }

    /** A caught trigger (journal insight) nudges momentum up by [JOURNAL_MOMENTUM]; trust and tension are untouched. */
    suspend fun recordJournalShift(scenarioId: String): TrajectoryPointEntity {
        val s = current(scenarioId)
        val p = point(scenarioId, null, "JOURNAL_SHIFT", s.copy(
            sigmoidProgress = (s.sigmoidProgress + JOURNAL_MOMENTUM).coerceAtMost(100.0), momentumDelta = JOURNAL_MOMENTUM
        ), now())
        db.trajectoryDao().insert(p)
        return p
    }

    private fun point(scenarioId: String, threadId: String?, source: String, s: TrajectorySnapshot, at: Long) = TrajectoryPointEntity(
        id = UUID.randomUUID().toString(), scenarioId = scenarioId, threadId = threadId, source = source,
        trustScore = s.trustScore, tensionScore = s.tensionScore, sigmoidProgress = s.sigmoidProgress, momentumDelta = s.momentumDelta,
        consistencyStreak = s.consistencyStreak, activeArchetype = s.activeArchetype, timestamp = at
    )

    companion object { const val DEFAULT_ARCHETYPE = "CHICKEN_GAME"; const val JOURNAL_MOMENTUM = 3.0 }
}
