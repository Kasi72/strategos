package com.drkkr.strategos.domain.framework

import com.drkkr.strategos.domain.model.Scenario
import java.util.UUID

class FrameworkRegistry {
    private val modules = mutableListOf<FrameworkModule>()

    fun register(m: FrameworkModule): FrameworkRegistry { modules += m; return this }
    fun all(): List<FrameworkModule> = modules.toList()
    fun modules(): List<FrameworkModule> = modules.toList()

    fun applicableTo(scenario: Scenario, threshold: Double = 0.35): List<Pair<FrameworkModule, Applicability>> =
        modules.map { it to it.canAnalyze(scenario) }
            .filter { it.second.score >= threshold }
            .sortedByDescending { it.second.score }

    /** Run all applicable modules; returns structured ModuleRunResult for every selected module. */
    fun runAll(scenario: Scenario, selectedIds: Set<String>): List<ModuleRunResult> =
        modules.filter { it.id in selectedIds }
            .map { m ->
                val t0 = System.currentTimeMillis()
                try {
                    val app = m.canAnalyze(scenario)
                    if (app.score < 0.05) {
                        ModuleRunResult.skippedNotApplicable(m, app.reasons.firstOrNull() ?: "Not applicable to this scenario.")
                    } else {
                        val result = m.analyze(scenario).copy(applicability = app)
                        ModuleRunResult.success(result, System.currentTimeMillis() - t0)
                    }
                } catch (e: Exception) {
                    ModuleRunResult.failed(m, e, UUID.randomUUID().toString().take(8))
                }
            }

    /** Legacy threshold-based run — returns ModuleRunResult list. */
    fun runAll(scenario: Scenario, threshold: Double = 0.35): List<ModuleRunResult> {
        val ids = applicableTo(scenario, threshold).map { it.first.id }.toSet()
        return runAll(scenario, ids)
    }

    /** Convenience: extract only AnalysisResult from successful runs. */
    fun analysisResultsFrom(runs: List<ModuleRunResult>): List<AnalysisResult> =
        runs.mapNotNull { it.result }
}
