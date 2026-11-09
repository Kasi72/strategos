package com.drkkr.strategos.data.repo

import com.drkkr.strategos.data.db.StrategosDatabase
import com.drkkr.strategos.data.db.entities.OutcomeRecordEntity
import com.drkkr.strategos.domain.flywheel.OutcomeRecord
import java.util.UUID

class OutcomeRepository(db: StrategosDatabase) {
    private val dao = db.outcomeRecordDao()

    suspend fun record(scenarioId: String, record: OutcomeRecord) {
        dao.insert(OutcomeRecordEntity(
            id = UUID.randomUUID().toString(),
            scenarioId = scenarioId,
            recommendedMove = record.recommendedMove,
            actualMove = record.actualMove,
            outcomeRating = record.outcomeRating,
            opponentReaction = record.opponentReaction,
            archetypeName = record.archetypeName,
            recordedAt = record.recordedAt
        ))
    }

    suspend fun forScenario(scenarioId: String): List<OutcomeRecord> =
        dao.forScenario(scenarioId).map { it.toDomain() }

    suspend fun recentForScenario(scenarioId: String, limit: Int = 20): List<OutcomeRecord> =
        dao.recentForScenario(scenarioId, limit).map { it.toDomain() }

    suspend fun recentAcrossScenarios(excludeScenarioId: String, limit: Int = 30): List<OutcomeRecord> =
        dao.recentAcrossScenarios(excludeScenarioId, limit).map { it.toDomain() }

    private fun OutcomeRecordEntity.toDomain() = OutcomeRecord(
        scenarioId = scenarioId,
        recommendedMove = recommendedMove,
        actualMove = actualMove,
        outcomeRating = outcomeRating,
        opponentReaction = opponentReaction,
        archetypeName = archetypeName,
        recordedAt = recordedAt
    )
}
