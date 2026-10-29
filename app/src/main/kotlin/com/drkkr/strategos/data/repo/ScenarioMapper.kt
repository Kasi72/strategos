package com.drkkr.strategos.data.repo

import com.drkkr.strategos.data.db.entities.ScenarioEntity
import com.drkkr.strategos.domain.model.Scenario
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** The one place a Scenario becomes a row. */
object ScenarioMapper {
    // encodeDefaults: see ScenarioRepository. Timestamps default to "now" and must always be written.
    val json = Json { ignoreUnknownKeys = true; prettyPrint = false; encodeDefaults = true }

    fun toEntity(s: Scenario) = ScenarioEntity(
        id = s.id, title = s.title, lifecycleStage = s.lifecycleStage.name, version = s.version,
        createdAt = s.createdAt, updatedAt = s.updatedAt, json = json.encodeToString(s)
    )
}
