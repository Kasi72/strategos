package com.drkkr.strategos.fixtures

import com.drkkr.strategos.data.migration.LegacySource
import com.drkkr.strategos.data.storage.LoadResult
import com.drkkr.strategos.domain.model.AnalysisSnapshot

class FakeLegacySource(
    private val result: LoadResult,
    private val snapshots: Map<String, List<AnalysisSnapshot>> = emptyMap()
) : LegacySource {
    override fun loadAll(): LoadResult = result
    override fun loadSnapshots(scenarioId: String): List<AnalysisSnapshot> = snapshots[scenarioId].orEmpty()
}
