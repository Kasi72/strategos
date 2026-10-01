package com.drkkr.strategos.domain.recommendation

import com.drkkr.strategos.domain.framework.*
import com.drkkr.strategos.domain.model.*
import com.drkkr.strategos.domain.framework.AnalysisEligibilityGate
import com.drkkr.strategos.domain.framework.ExecutiveStatus

// ─── Candidate types ──────────────────────────────────────────────────────────

enum class CandidateType {
    EXECUTABLE_ACTION,      // may appear as Recommended / Safe Fallback / High-Upside / Excluded
    IMPLEMENTATION_GUARDRAIL, // must appear as implementation requirement, never as a competing action
    INFORMATION_TASK,       // data-gathering task
    ANALYTICAL_NOTE         // insight from a framework, not an actionable decision
}

enum class FeasibilityStatus { FEASIBLE, BLOCKED_HARD_CONSTRAINT, BLOCKED_UNKNOWN_CONSTRAINT, INFEASIBLE, UNASSESSED }

data class ScoreBreakdown(
    val frameworkRelevance: Double = 0.0,
    val inputCompleteness: Double = 0.0,
    val evidenceQuality: Double = 0.0,
    val robustness: Double = 0.0,
    val feasibility: Double = 0.0,
    val stakeholderViability: Double = 0.0,
    val ethicalCompliance: Double = 0.0,
    val calculationBasis: String = "Unknown"
)

data class DecisionCandidate(
    val actionId: String?,
    val actionLabel: String,
    val ownerStakeholderId: String?,
    val candidateType: CandidateType,
    val feasibilityStatus: FeasibilityStatus,
    val constraintStatusSummary: ConstraintStatus,
    val calculationStatus: CalculationStatus,
    val scoreBreakdown: ScoreBreakdown?,
    val score: Double,
    val supportingFrameworks: List<String>,
    val assumptions: List<String>,
    val evidenceLinks: List<String>,
    val confidence: Double,
    val blockingConstraints: List<String> = emptyList()
)

// ─── Report level ────────────────────────────────────────────────────────────

enum class ReportLevel { EXECUTIVE, STRATEGIC, TECHNICAL }

// ─── Risk register item (replaces generic red-team prose) ────────────────────

data class RiskRegisterItem(
    val failureMode: String,
    val actor: String,
    val evidenceType: String,   // Evidence-based / Structural / Assumption-dependent / Hypothetical
    val likelihood: String,     // High / Medium / Low
    val impact: String,         // High / Medium / Low
    val earlySignal: String,
    val mitigation: String,
    val owner: String,
    val stopCondition: String
)

// ─── Stakeholder response forecast ───────────────────────────────────────────

data class StakeholderForecast(
    val stakeholderName: String,
    val likelyResponse: String,
    val mainConcern: String,
    val evidenceBasis: String,
    val riskIfIgnored: String,
    val bestEngagement: String
)

// ─── Value-of-information item ────────────────────────────────────────────────

data class VoiItem(
    val missingFact: String,
    val whyItMatters: String,
    val percentageScenariosChanged: Int,
    val costToObtain: String,
    val recommendation: String
)

// ─── Decision pathway ─────────────────────────────────────────────────────────

data class DecisionPathway(
    val label: String,          // "Recommended" / "Safe Fallback" / "High-Upside / Fragile" / "Excluded" / "Deferred"
    val actionId: String?,      // links to Action.id — null only for Deferred/guardrails
    val action: String,
    val rationale: String,
    val strength: Double,       // 0..1 — multi-factor score
    val fragile: Boolean = false,
    val excluded: Boolean = false,
    val deferReason: String? = null,
    val constraintViolations: List<String> = emptyList(),
    val calculationBasis: String = "",
    val ownerName: String = "",
    val scoreBreakdown: ScoreBreakdown? = null
)

// ─── Full Decision Report ─────────────────────────────────────────────────────

data class DecisionReport(
    val scenario: Scenario,
    val moduleResults: List<AnalysisResult>,
    val analysisPlan: com.drkkr.strategos.framework.AnalysisPlan? = null,
    // Legacy fields kept for UI backward compat
    val combinedRecommendations: List<LensRecommendation>,
    val sensitiveAssumptions: List<String>,
    val missingInfo: List<MissingInfoItem>,
    val frameworkDisagreements: List<String>,
    val defensiblePathway: String,
    val overallConfidence: Double,
    // New fields
    val confidenceProfile: ConfidenceProfile? = null,
    val pathways: List<DecisionPathway> = emptyList(),
    val stakeholderForecasts: List<StakeholderForecast> = emptyList(),
    val riskRegister: List<RiskRegisterItem> = emptyList(),
    val voiItems: List<VoiItem> = emptyList(),
    val candidates: List<DecisionCandidate> = emptyList(),
    val decisionOwnerName: String = "",
    val matrixCoverage: String = "",  // e.g. "12 of 24 profiles complete; 50% coverage"
    val executiveStatus: ExecutiveStatus = ExecutiveStatus.EXPLORATORY_ONLY,
    val executiveStatusReason: com.drkkr.strategos.domain.framework.ExecutiveStatusReason? = null
)

data class LensRecommendation(
    val lens: RecommendedAction.Lens,
    val lensLabel: String,
    val stakeholderId: String?,
    val action: String,
    val rationale: String,
    val contingency: String? = null,
    val supportingModules: List<String>
)

data class MissingInfoItem(val description: String, val expectedImpact: String)

// ─── Engine ───────────────────────────────────────────────────────────────────

class RecommendationEngine {

    fun combine(scenario: Scenario, results: List<AnalysisResult>): DecisionReport {
        if (results.isEmpty()) return emptyReport(scenario)
        val decisionOwnerName = scenario.stakeholders
            .firstOrNull { it.id == scenario.decisionOwner || it.name == scenario.decisionOwner }
            ?.name ?: scenario.decisionOwner

        // Legacy lens aggregation (kept for backward compat UI cards)
        val allRecs = results.flatMap { r -> r.recommendedActions.map { r.moduleId to it } }
        val byLens = allRecs.groupBy { it.second.lens }
        val combined = byLens.map { (lens, pairs) ->
            val dominant = pairs.groupBy { "${it.second.forStakeholderId}|${it.second.label.lowercase().trim()}" }
                .maxBy { it.value.size }
            LensRecommendation(
                lens = lens,
                lensLabel = lens.label(),
                stakeholderId = dominant.value.first().second.forStakeholderId,
                action = dominant.value.first().second.label,
                rationale = dominant.value.joinToString("; ") { it.second.rationale }.take(300),
                contingency = dominant.value.firstOrNull { it.second.contingency != null }?.second?.contingency,
                supportingModules = dominant.value.map { it.first }
            )
        }

        // ── Canonical pipeline ────────────────────────────────────────────
        val candidates = buildCandidates(scenario, results)
        val pathways = buildPathways(scenario, results, combined)

        // ── Stakeholder forecasts ─────────────────────────────────────────
        val recommendedActionId = pathways.firstOrNull { it.label == "Recommended" }?.actionId
        val forecasts = buildForecasts(scenario, recommendedActionId)

        // ── VOI items (from VOI module results) ───────────────────────────
        val voiItems = extractVoiItems(results)

        // ── Risk register (from red-team findings) ────────────────────────
        val riskRegister = extractRiskRegister(results)

        // ── Confidence profile ────────────────────────────────────────────
        val profile = ConfidenceCalculator.compute(scenario, results)

        val disagreements = detectDisagreements(combined)
        val sensitive = results.flatMap { it.assumptions }.map { it.label }.distinct().take(8)
        val overallConf = profile.overall()

        val defensible = buildDefensiblePathway(combined, scenario)

        val statusReason = AnalysisEligibilityGate.determineWithReason(scenario, results, candidates)

        return DecisionReport(
            scenario = scenario,
            moduleResults = results,
            combinedRecommendations = combined,
            sensitiveAssumptions = sensitive,
            missingInfo = identifyMissing(scenario),
            frameworkDisagreements = disagreements,
            defensiblePathway = defensible,
            overallConfidence = overallConf,
            confidenceProfile = profile,
            pathways = pathways,
            stakeholderForecasts = forecasts,
            riskRegister = riskRegister,
            voiItems = voiItems,
            candidates = candidates,
            decisionOwnerName = decisionOwnerName,
            executiveStatus = statusReason.status,
            executiveStatusReason = statusReason
        )
    }

    // ── Canonical decision pipeline ───────────────────────────────────────────

    /** Build DecisionCandidate list from framework results via actionId resolution. */
    fun buildCandidates(scenario: Scenario, results: List<AnalysisResult>): List<DecisionCandidate> {
        val hardConstraintKinds = setOf(Constraint.Kind.SAFETY, Constraint.Kind.LEGAL, Constraint.Kind.ETHICAL, Constraint.Kind.USER_HARD)
        val hardConstraints = scenario.constraints.filter { it.kind in hardConstraintKinds }

        // Accumulate scores per actionId
        data class Accum(
            val action: Action,
            var rawScore: Double,
            var count: Int,
            val frameworks: MutableList<String>,
            val assumptions: MutableList<String>,
            var worstStatus: CalculationStatus,
            var breakdown: ScoreBreakdown?
        )

        val accumMap = mutableMapOf<String, Accum>()

        for (result in results) {
            for (rec in result.recommendedActions) {
                // Only process EXECUTABLE_ACTION-type recs — those linked to real actions
                val linkedAction: Action? = when {
                    rec.actionId != null ->
                        scenario.actions.firstOrNull { it.id == rec.actionId }
                    else ->
                        // Fallback: exact label match only (not contains)
                        scenario.actions.firstOrNull { it.label.equals(rec.label, ignoreCase = true) }
                }
                if (linkedAction == null) continue  // guardrail/note — skip action ranking

                val existing = accumMap[linkedAction.id]
                val w = result.confidence
                val frameworkRelevance = result.applicability.score
                val inputCompleteness = scenario.completenessScore() / 100.0
                val evidenceQuality = (scenario.evidenceQuality() / 100.0)
                val robustness = when (result.calculationStatus) {
                    CalculationStatus.CALCULATED -> 0.85
                    CalculationStatus.PARTIALLY_CALCULATED -> 0.60
                    else -> 0.35
                }
                val feasibility = (5 - linkedAction.implementationDifficulty).coerceAtLeast(0) / 4.0
                val stakeholderViability = if (scenario.stakeholders.any { it.canVeto && it.credibility < 4 }) 0.6 else 0.85
                val ethicalCompliance = if (linkedAction.legalStatus == LegalStatus.PROHIBITED) 0.0 else 1.0
                val factor = frameworkRelevance * inputCompleteness * evidenceQuality *
                    robustness * feasibility * stakeholderViability * ethicalCompliance
                val contribution = factor * w

                val bd = ScoreBreakdown(
                    frameworkRelevance = frameworkRelevance,
                    inputCompleteness = inputCompleteness,
                    evidenceQuality = evidenceQuality,
                    robustness = robustness,
                    feasibility = feasibility,
                    stakeholderViability = stakeholderViability,
                    ethicalCompliance = ethicalCompliance,
                    calculationBasis = if (scenario.evidenceSources.isEmpty()) "Proxy (no evidence)" else result.calculationStatus.name
                )

                if (existing == null) {
                    accumMap[linkedAction.id] = Accum(
                        linkedAction, contribution, 1,
                        mutableListOf(result.moduleName),
                        result.assumptions.map { it.label }.toMutableList(),
                        result.calculationStatus, bd
                    )
                } else {
                    existing.rawScore += contribution
                    existing.count += 1
                    existing.frameworks += result.moduleName
                    if (result.calculationStatus.ordinal > existing.worstStatus.ordinal) existing.worstStatus = result.calculationStatus
                }
            }
        }

        if (accumMap.isEmpty()) return emptyList()

        val maxRaw = accumMap.values.maxOf { it.rawScore }.coerceAtLeast(1e-9)

        return accumMap.values.map { acc ->
            val score = (acc.rawScore / maxRaw).coerceIn(0.0, 1.0)
            val action = acc.action

            // Determine constraint status from scenario.constraintAssessments
            val assessments = scenario.constraintAssessments.filter { it.actionId == action.id }
            val hardAssessments = assessments.filter { a ->
                scenario.constraints.firstOrNull { it.id == a.constraintId }?.kind in hardConstraintKinds
            }

            val constraintSummary = when {
                action.legalStatus == LegalStatus.PROHIBITED -> ConstraintStatus.VIOLATED
                hardAssessments.any { it.status == ConstraintStatus.VIOLATED } -> ConstraintStatus.VIOLATED
                hardAssessments.any { it.status == ConstraintStatus.UNKNOWN } -> ConstraintStatus.UNKNOWN
                hardAssessments.all { it.status == ConstraintStatus.SATISFIED } && hardAssessments.isNotEmpty() ->
                    ConstraintStatus.SATISFIED
                hardConstraints.isNotEmpty() && assessments.isEmpty() -> ConstraintStatus.UNKNOWN
                else -> if (action.legalStatus == LegalStatus.UNCLEAR) ConstraintStatus.UNKNOWN else ConstraintStatus.SATISFIED
            }

            val blockingConstraints = mutableListOf<String>()
            if (action.legalStatus == LegalStatus.PROHIBITED) blockingConstraints += "legalStatus=PROHIBITED"
            hardAssessments.filter { it.status == ConstraintStatus.VIOLATED }
                .forEach { a -> blockingConstraints += "constraint:${a.constraintId}=VIOLATED" }

            val feasibilityStatus = when {
                constraintSummary == ConstraintStatus.VIOLATED -> FeasibilityStatus.BLOCKED_HARD_CONSTRAINT
                constraintSummary == ConstraintStatus.UNKNOWN && (!action.reversible || action.implementationDifficulty >= 4) ->
                    FeasibilityStatus.BLOCKED_UNKNOWN_CONSTRAINT
                else -> FeasibilityStatus.FEASIBLE
            }

            DecisionCandidate(
                actionId = action.id,
                actionLabel = action.label,
                ownerStakeholderId = action.stakeholderId,
                candidateType = CandidateType.EXECUTABLE_ACTION,
                feasibilityStatus = feasibilityStatus,
                constraintStatusSummary = constraintSummary,
                calculationStatus = acc.worstStatus,
                scoreBreakdown = acc.breakdown,
                score = score,
                supportingFrameworks = acc.frameworks.distinct(),
                assumptions = acc.assumptions.distinct().take(5),
                evidenceLinks = scenario.evidenceSources.map { it.label }.take(3),
                confidence = (score * 0.5 + (scenario.evidenceQuality() / 100.0) * 0.5),
                blockingConstraints = blockingConstraints
            )
        }.sortedByDescending { it.score }
    }

    // ── Multi-factor pathway scoring (uses DecisionCandidate pipeline) ────────

    private fun buildPathways(
        scenario: Scenario,
        results: List<AnalysisResult>,
        lensRecs: List<LensRecommendation>
    ): List<DecisionPathway> {
        val allCandidates = buildCandidates(scenario, results)
        if (allCandidates.isEmpty()) {
            if (scenario.completenessScore() < 40) {
                return listOf(DecisionPathway(
                    label = "Deferred", actionId = null,
                    action = "Collect information first",
                    rationale = "Model completeness ${scenario.completenessScore()}% — enter payoffs, beliefs, and stakeholder details before committing.",
                    strength = 0.0, deferReason = "Insufficient model data for ranking.",
                    calculationBasis = "Not run"
                ))
            }
            return emptyList()
        }

        val pathways = mutableListOf<DecisionPathway>()
        val evidenceEmpty = scenario.evidenceSources.isEmpty()

        fun ownerName(candidate: DecisionCandidate) =
            scenario.stakeholders.firstOrNull { it.id == candidate.ownerStakeholderId }?.name ?: ""

        fun calcBasis(c: DecisionCandidate) = when {
            evidenceEmpty -> "Proxy — no evidence sources"
            c.calculationStatus == CalculationStatus.CALCULATED -> "Calculated"
            c.calculationStatus == CalculationStatus.PARTIALLY_CALCULATED -> "Partially calculated"
            else -> "Proxy estimate"
        }

        fun rationale(c: DecisionCandidate, label: String): String = buildString {
            append("$label.")
            val caveats = mutableListOf<String>()
            if (evidenceEmpty) caveats += "No evidence sources recorded — results are structural proxy estimates only."
            if (c.constraintStatusSummary == ConstraintStatus.UNKNOWN) caveats += "One or more hard constraints are unassessed — verify compliance before committing."
            if (caveats.isNotEmpty()) {
                append(" ")
                append(caveats.joinToString(" "))
            }
        }

        // Excluded: violated hard constraints
        val violatedCandidates = allCandidates.filter {
            it.feasibilityStatus == FeasibilityStatus.BLOCKED_HARD_CONSTRAINT
        }
        for (c in violatedCandidates) {
            pathways += DecisionPathway(
                label = "Excluded", actionId = c.actionId,
                action = c.actionLabel,
                rationale = "Blocked: ${c.blockingConstraints.joinToString(", ")}. Cannot be ranked.",
                strength = 0.0, excluded = true,
                constraintViolations = c.blockingConstraints,
                calculationBasis = "Hard rule",
                ownerName = ownerName(c), scoreBreakdown = null
            )
        }

        val feasible = allCandidates.filter {
            it.feasibilityStatus == FeasibilityStatus.FEASIBLE ||
            it.feasibilityStatus == FeasibilityStatus.BLOCKED_UNKNOWN_CONSTRAINT
        }
        if (feasible.isEmpty()) {
            pathways += DecisionPathway(
                label = "Deferred", actionId = null,
                action = "All candidate actions excluded or constraint-blocked",
                rationale = "No feasible candidates remain. Review constraints and legal status of defined actions.",
                strength = 0.0, deferReason = "Add compliant actions or resolve constraint assessments.",
                calculationBasis = "Hard rule"
            )
            return pathways
        }

        // Recommended: highest-scoring fully feasible, or if none, highest with unknown constraint
        val best = feasible.firstOrNull { it.feasibilityStatus == FeasibilityStatus.FEASIBLE }
            ?: feasible.first()
        pathways += DecisionPathway(
            label = "Recommended", actionId = best.actionId,
            action = best.actionLabel,
            rationale = rationale(best, "Preferred under recorded assumptions"),
            strength = best.score,
            fragile = scenario.actions.firstOrNull { it.id == best.actionId }?.let {
                !it.reversible || it.implementationDifficulty >= 4
            } ?: false,
            constraintViolations = best.blockingConstraints,
            calculationBasis = calcBasis(best),
            ownerName = ownerName(best), scoreBreakdown = best.scoreBreakdown
        )

        // Safe Fallback: reversible, difficulty ≤ 2, not best, feasible
        val safeFallback = feasible.firstOrNull { c ->
            c.actionId != best.actionId &&
            c.feasibilityStatus == FeasibilityStatus.FEASIBLE &&
            scenario.actions.firstOrNull { it.id == c.actionId }?.let { it.reversible && it.implementationDifficulty <= 2 } == true
        }
        safeFallback?.let { c ->
            pathways += DecisionPathway(
                label = "Safe Fallback", actionId = c.actionId,
                action = c.actionLabel,
                rationale = "Reversible, lower difficulty. Preferred under high uncertainty.",
                strength = c.score,
                calculationBasis = calcBasis(c), ownerName = ownerName(c), scoreBreakdown = c.scoreBreakdown
            )
        }

        // High-Upside / Fragile: irreversible or difficulty ≥ 4, not best, feasible
        val fragile = feasible.firstOrNull { c ->
            c.actionId != best.actionId &&
            c.actionId != safeFallback?.actionId &&
            scenario.actions.firstOrNull { it.id == c.actionId }?.let { !it.reversible || it.implementationDifficulty >= 4 } == true
        }
        fragile?.let { c ->
            pathways += DecisionPathway(
                label = "High-Upside / Fragile", actionId = c.actionId,
                action = c.actionLabel,
                rationale = "Higher potential upside but irreversible or high difficulty. Verify assumptions before committing.",
                strength = c.score, fragile = true,
                calculationBasis = calcBasis(c), ownerName = ownerName(c), scoreBreakdown = c.scoreBreakdown
            )
        }

        if (scenario.completenessScore() < 40) {
            pathways += DecisionPathway(
                label = "Deferred", actionId = null,
                action = "Collect information first",
                rationale = "Model completeness ${scenario.completenessScore()}% — information gathering may have higher value than immediate action.",
                strength = 0.0, deferReason = "Enter payoffs, beliefs, and stakeholder details.",
                calculationBasis = "Proxy"
            )
        }

        return pathways
    }

    // ── Stakeholder response forecasts ────────────────────────────────────

    private fun buildForecasts(scenario: Scenario, recommendedActionId: String?): List<StakeholderForecast> {
        val recommendedAction = scenario.actions.firstOrNull { it.id == recommendedActionId }
        return scenario.stakeholders.map { s ->
            val concern = when {
                s.fears.isNotEmpty() -> s.fears.first()
                s.privateGoals.isNotEmpty() -> "Private goal: ${s.privateGoals.first()}"
                s.canVeto -> "Veto rights — any perceived loss of authority"
                s.canExit -> "Exit option may be exercised if dissatisfied"
                else -> "Unclear — no behavioral assumptions entered"
            }

            // Use action-specific reaction if available for the recommended action
            val actionReaction = recommendedAction?.likelyReactions?.get(s.id)

            val likelyResponse = when {
                actionReaction != null -> actionReaction
                s.behavioralAssumptions.isNotEmpty() -> s.behavioralAssumptions.first().text
                s.credibility >= 7 && s.authority >= 6 -> "Active cooperation — high credibility and authority"
                s.canVeto && s.authority >= 7 -> "Conditional support — veto holder with high authority"
                s.canVeto -> "Possible resistance — holds veto rights"
                s.influence >= 7 && s.authority < 4 -> "Informal coalition building — high influence, limited formal authority"
                s.canDelay -> "Likely delay — has procedural delay capability"
                else -> "Passive compliance likely — limited formal power"
            }

            val evidenceBasis = when {
                actionReaction != null -> "User-entered reaction for recommended action"
                s.behavioralAssumptions.any { it.status == FactStatus.CONFIRMED } -> "Confirmed by user"
                s.behavioralAssumptions.any { it.status == FactStatus.USER_ESTIMATE } -> "User estimate"
                s.behavioralAssumptions.isNotEmpty() -> "Model inference — no confirmed evidence"
                else -> "No data — structural heuristic only"
            }

            val riskIfIgnored = when {
                s.canVeto -> "Procedural block — veto stops implementation"
                s.canExit -> "Coalition loss — exit weakens support base"
                s.influence >= 7 -> "Informal opposition — narrative and reputational risk"
                s.canDelay -> "Timeline slippage — procedural delay"
                else -> "Low risk if ignored"
            }

            val bestEngagement = when {
                s.goals.isNotEmpty() -> "Align with stated goal: ${s.goals.first()}"
                s.fears.isNotEmpty() -> "Address primary fear: ${s.fears.first()}"
                s.canVeto -> "Secure commitment before announcement — veto holder must be on board"
                s.coalitionsWith.isNotEmpty() -> "Engage via coalition partners first"
                else -> "Direct bilateral conversation — no coalition leverage identified"
            }

            StakeholderForecast(s.name, likelyResponse, concern, evidenceBasis, riskIfIgnored, bestEngagement)
        }
    }

    // ── Extract VOI items from VOI module ─────────────────────────────────

    private fun extractVoiItems(results: List<AnalysisResult>): List<VoiItem> {
        val voiResult = results.firstOrNull { it.moduleId == "value-of-information" } ?: return emptyList()
        return voiResult.findings.take(3).map { f ->
            VoiItem(
                missingFact = f.title,
                whyItMatters = f.detail.take(200),
                percentageScenariosChanged = (voiResult.calculations["scenarioChangeRate"] as? Double)?.let {
                    (it * 100).toInt()
                } ?: 0,
                costToObtain = "Structured confirmation meeting or targeted research",
                recommendation = if (f.severity == Finding.Severity.CRITICAL || f.severity == Finding.Severity.WARNING)
                    "Collect before committing to irreversible action"
                else
                    "Collect before finalising strategy"
            )
        }
    }

    // ── Extract risk register from red-team findings ──────────────────────

    private fun extractRiskRegister(results: List<AnalysisResult>): List<RiskRegisterItem> {
        val redTeam = results.firstOrNull { it.moduleId == "red-team" } ?: return emptyList()
        return redTeam.findings.map { f ->
            val isStructural = f.severity == Finding.Severity.CRITICAL
            RiskRegisterItem(
                failureMode = f.title,
                actor = redTeam.calculations["primaryBlocker"]?.toString() ?: "Unidentified",
                evidenceType = if (isStructural) "Structural" else "Assumption-dependent",
                likelihood = when (f.severity) {
                    Finding.Severity.CRITICAL -> "High"
                    Finding.Severity.WARNING -> "Medium"
                    else -> "Low"
                },
                impact = if (isStructural) "High" else "Medium",
                earlySignal = "Monitor stakeholder position shifts and coalition changes",
                mitigation = f.detail.take(150),
                owner = "Decision owner",
                stopCondition = "Reverse if early signal confirmed within 30 days"
            )
        }
    }

    // ── Legacy helpers ────────────────────────────────────────────────────

    private fun detectDisagreements(recs: List<LensRecommendation>): List<String> {
        val actions = recs.map { it.action }.distinct()
        return if (actions.size > 1)
            listOf("Frameworks disagree: ${recs.joinToString("; ") { "${it.lensLabel}→${it.action}" }}")
        else emptyList()
    }

    private fun identifyMissing(scenario: Scenario): List<MissingInfoItem> {
        val items = mutableListOf<MissingInfoItem>()
        if (scenario.beliefs.isEmpty()) items += MissingInfoItem("No probability beliefs entered", "Could shift expected utility rankings")
        if (scenario.payoffs.isEmpty()) items += MissingInfoItem("No payoffs defined", "Cannot run any quantitative framework")
        val unknownBeliefs = scenario.beliefs.filter { it.status == FactStatus.UNKNOWN }
        if (unknownBeliefs.isNotEmpty()) items += MissingInfoItem(
            "Unknown beliefs: ${unknownBeliefs.joinToString { it.label }}", "High sensitivity potential")
        val unvetoConfirmed = scenario.stakeholders.filter { it.canVeto && it.behavioralAssumptions.none { a -> a.status == FactStatus.CONFIRMED } }
        if (unvetoConfirmed.isNotEmpty()) items += MissingInfoItem(
            "Veto-holder commitments unconfirmed: ${unvetoConfirmed.joinToString { it.name }}",
            "Implementation may be blocked without confirmed support")
        return items
    }

    private fun buildDefensiblePathway(recs: List<LensRecommendation>, scenario: Scenario): String {
        val safety = recs.firstOrNull { it.lens == RecommendedAction.Lens.ETHICAL_LEGAL }
        val expected = recs.firstOrNull { it.lens == RecommendedAction.Lens.EXPECTED }
        val riskAverse = recs.firstOrNull { it.lens == RecommendedAction.Lens.RISK_AVERSE }
        val evidenceEmpty = scenario.evidenceSources.isEmpty()
        val hasUnknownConstraints = scenario.constraints.any { c ->
            c.kind in setOf(Constraint.Kind.SAFETY, Constraint.Kind.LEGAL, Constraint.Kind.ETHICAL, Constraint.Kind.USER_HARD) &&
            scenario.constraintAssessments.none { a -> a.constraintId == c.id }
        }
        return buildString {
            if (evidenceEmpty || hasUnknownConstraints) {
                append("⚠ CONDITIONAL ANALYSIS — not suitable for commitment:\n")
                if (evidenceEmpty) append("  • No evidence sources — all outputs are structural proxy estimates.\n")
                if (hasUnknownConstraints) append("  • Hard constraint(s) not yet assessed — verify before ranking.\n")
                append("\n")
            } else {
                append("Preferred under the recorded assumptions:\n\n")
            }
            if (safety != null) append("• Ethical/legal anchor: ${safety.action}\n  ${safety.rationale}\n\n")
            if (expected != null) append("• Best-expected action: ${expected.action}\n  ${expected.rationale}\n\n")
            if (riskAverse != null && riskAverse.action != expected?.action)
                append("• Risk-averse alternative: ${riskAverse.action}\n  ${riskAverse.rationale}\n\n")
            val first = recs.firstOrNull()
            if (first?.contingency != null) append("• Contingency: ${first.contingency}\n")
            val hardConstraints = scenario.constraints.filter {
                it.kind in setOf(Constraint.Kind.SAFETY, Constraint.Kind.LEGAL, Constraint.Kind.ETHICAL, Constraint.Kind.USER_HARD)
            }
            if (hardConstraints.isNotEmpty()) {
                append("\nDeclared hard constraints (${hardConstraints.size}): ${hardConstraints.joinToString(", ") { it.label }}.\n")
                if (hasUnknownConstraints) {
                    append("Status: not all assessed — blocked pending verification.\n")
                } else {
                    append("Status: all assessed.\n")
                }
            }
        }
    }

    private fun emptyReport(scenario: Scenario): DecisionReport {
        val emptyCandidates = buildCandidates(scenario, emptyList())
        val statusReason = AnalysisEligibilityGate.determineWithReason(scenario, emptyList(), emptyCandidates)
        return DecisionReport(
        scenario = scenario, moduleResults = emptyList(), analysisPlan = null,
        combinedRecommendations = emptyList(), sensitiveAssumptions = emptyList(),
        missingInfo = identifyMissing(scenario), frameworkDisagreements = emptyList(),
        defensiblePathway = "No analysis results available. Run analysis first.",
        overallConfidence = 0.0,
        executiveStatus = statusReason.status,
        executiveStatusReason = statusReason,
        confidenceProfile = ConfidenceProfile(
            evidenceQuality = scenario.evidenceQuality() / 100.0,
            modelCompleteness = scenario.completenessScore() / 100.0,
            analyticalRobustness = 0.0, implementationReadiness = 0.0,
            explanations = mapOf(
                "evidenceQuality" to if (scenario.evidenceSources.isEmpty()) "No evidence sources" else "Not run",
                "modelCompleteness" to "Model completeness ${scenario.completenessScore()}%",
                "analyticalRobustness" to "Not run — no analysis results",
                "implementationReadiness" to "Not assessed"
            )
        ),
        pathways = if (scenario.completenessScore() < 40) listOf(
            DecisionPathway("Deferred", null, "Collect information first",
                "Model completeness ${scenario.completenessScore()}% — enter payoffs, beliefs, and stakeholder details first.",
                0.0, deferReason = "Run analysis after adding required model data.", calculationBasis = "Not run")
        ) else emptyList(),
        candidates = emptyCandidates,
        decisionOwnerName = scenario.decisionOwner
        )
    }
}

private fun RecommendedAction.Lens.label() = when (this) {
    RecommendedAction.Lens.EXPECTED -> "Best Expected"
    RecommendedAction.Lens.RISK_AVERSE -> "Risk-Averse"
    RecommendedAction.Lens.ETHICAL_LEGAL -> "Ethical/Legal"
    RecommendedAction.Lens.RELATIONSHIP -> "Relationship"
    RecommendedAction.Lens.LONG_TERM -> "Long-Term"
    RecommendedAction.Lens.LOW_REGRET -> "Low-Regret"
}
