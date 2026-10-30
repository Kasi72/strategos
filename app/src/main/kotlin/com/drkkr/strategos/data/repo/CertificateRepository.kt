package com.drkkr.strategos.data.repo

import com.drkkr.strategos.data.db.dao.DecisionCertificateDao
import com.drkkr.strategos.data.db.dao.GameModelDao
import com.drkkr.strategos.data.db.entities.DecisionCertificateEntity
import com.drkkr.strategos.data.db.entities.GameModelEntity
import com.drkkr.strategos.domain.agent.EngineDirective
import com.drkkr.strategos.domain.game.EquilibriumVerifier
import com.drkkr.strategos.domain.game.GameAnalysisResult
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.UUID

class CertificateRepository(
    private val gameModelDao: GameModelDao,
    private val certificateDao: DecisionCertificateDao
) {

    suspend fun persist(
        scenarioId: String,
        scenarioVersion: Int,
        gameResult: GameAnalysisResult.Solved,
        directive: EngineDirective,
        now: Long = System.currentTimeMillis()
    ) {
        val ir = gameResult.ir
        val outcome = gameResult.outcome

        val gmId = UUID.randomUUID().toString()
        gameModelDao.upsert(
            GameModelEntity(
                id = gmId,
                scenarioId = scenarioId,
                scenarioVersion = scenarioVersion,
                gameClass = ir.gameClass.name,
                playerIdsJson = Json.encodeToString(ListSerializer(String.serializer()), ir.playerIds),
                strategyCountsJson = Json.encodeToString(ListSerializer(Int.serializer()), ir.strategyCounts),
                utilityJson = Json.encodeToString(
                    ListSerializer(ListSerializer(Double.serializer())),
                    ir.utility.map { it.toList() }
                ),
                coveragePct = ir.coveragePct,
                decisionState = gameResult.decisionState.name,
                createdAt = now,
                updatedAt = now
            )
        )

        val eq = outcome.equilibria.first()
        val isPure = eq.pureProfile != null
        val recommended: Map<String, String> = if (isPure && eq.pureProfile != null) {
            ir.playerIds.mapIndexed { i, pid ->
                pid to (ir.actionIds.getOrNull(i)?.getOrElse(eq.pureProfile[i]) { "" } ?: "")
            }.toMap()
        } else emptyMap()

        val residual = EquilibriumVerifier.deviationGainResidual(
            ir.utility, ir.strategyCounts, eq.mixedStrategies
        )

        certificateDao.insert(
            DecisionCertificateEntity(
                id = UUID.randomUUID().toString(),
                scenarioId = scenarioId,
                gameModelId = gmId,
                equilibriumType = if (isPure) "PURE_NASH" else "MIXED_NASH",
                recommendedActionJson = Json.encodeToString(
                    MapSerializer(String.serializer(), String.serializer()), recommended
                ),
                deviationGainResidual = residual,
                integrity = gameResult.integrity.name,
                rationale = directive.recommendedWhy,
                createdAt = now
            )
        )
    }
}
