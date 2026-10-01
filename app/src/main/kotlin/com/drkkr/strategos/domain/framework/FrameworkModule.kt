package com.drkkr.strategos.domain.framework

import com.drkkr.strategos.domain.model.Scenario

data class Applicability(val score: Double, val reasons: List<String>)

/** How a result was derived — shown to user on every analysis output */
enum class CalculationStatus {
    CALCULATED,          // full user-entered data, formal derivation
    PARTIALLY_CALCULATED,// incomplete data but usable
    PROXY_ESTIMATE,      // heuristic assumptions or surrogate values
    NOT_RUN,             // insufficient data
    EXCLUDED             // violates a hard constraint
}

/** How a framework was selected for this scenario */
enum class FrameworkCategory {
    CORE,         // directly required by scenario structure
    SUPPORTING,   // useful secondary perspective
    EXPLORATORY,  // potentially useful but dependent on weak assumptions
    EXCLUDED      // insufficient data or poor fit
}

/**
 * Four-signal confidence profile replacing a single percentage.
 * Each signal is 0.0–1.0.
 */
data class ConfidenceProfile(
    val evidenceQuality: Double,         // quality of evidence sources
    val modelCompleteness: Double,       // payoffs, beliefs, constraints completeness
    val analyticalRobustness: Double,    // option survives tested scenarios
    val implementationReadiness: Double, // veto-holder commitments, feasibility
    val explanations: Map<String, String> = emptyMap()  // signal name -> explanation
) {
    fun overall(): Double =
        (evidenceQuality * 0.25 + modelCompleteness * 0.30 +
         analyticalRobustness * 0.30 + implementationReadiness * 0.15)

    fun label(): String = when {
        overall() >= 0.75 -> "High"
        overall() >= 0.55 -> "Conditional"
        overall() >= 0.35 -> "Limited"
        else -> "Insufficient"
    }
}

data class Finding(
    val title: String,
    val detail: String,
    val severity: Severity = Severity.INFO
) {
    enum class Severity { INFO, NOTE, WARNING, CRITICAL }
}

data class Assumption(val label: String, val note: String = "")

data class VizSpec(
    val kind: Kind,
    val title: String,
    val data: Map<String, Any?> = emptyMap()
) {
    enum class Kind { MATRIX, TABLE, TREE, BAR, RADAR, PARETO, TEXT }
}

data class AnalysisResult(
    val moduleId: String,
    val moduleName: String,
    val applicability: Applicability,
    val findings: List<Finding>,
    val recommendedActions: List<RecommendedAction>,
    val assumptions: List<Assumption>,
    val calculations: Map<String, Any?>,
    val confidence: Double, // 0..1 — kept for backward compat; prefer confidenceProfile
    val visualizations: List<VizSpec>,
    val explanation: String,
    val calculationStatus: CalculationStatus = CalculationStatus.PROXY_ESTIMATE,
    val confidenceProfile: ConfidenceProfile? = null,
    val frameworkCategory: FrameworkCategory = FrameworkCategory.SUPPORTING
)

data class RecommendedAction(
    val forStakeholderId: String?,
    val label: String,
    val rationale: String,
    val contingency: String? = null,
    val lens: Lens = Lens.EXPECTED,
    val actionId: String? = null
) {
    enum class Lens { EXPECTED, RISK_AVERSE, ETHICAL_LEGAL, RELATIONSHIP, LONG_TERM, LOW_REGRET }
}

interface FrameworkModule {
    val id: String
    val name: String
    val version: String
    val category: String

    fun canAnalyze(model: Scenario): Applicability
    fun analyze(model: Scenario): AnalysisResult
    fun explain(result: AnalysisResult): String = result.explanation
}
