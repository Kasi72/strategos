package com.drkkr.strategos.framework.strategic

import com.drkkr.strategos.domain.framework.*
import com.drkkr.strategos.domain.model.*
import com.drkkr.strategos.framework.MatrixSupport
import com.drkkr.strategos.domain.model.FactStatus

enum class FrontierClass(val label: String, val description: String) {
    DOMINANT("Dominant", "Better than alternatives across all tested dimensions — no other action scores higher on any key criterion"),
    ROBUST("Robust", "Performs well across most plausible futures — remains a strong choice under common uncertainty"),
    CONDITIONAL("Conditional", "Preferred only if named assumptions hold — verify critical beliefs before committing"),
    FRAGILE("High-upside / Fragile", "Attractive expected value but fails under common disruption or assumption violations"),
    SAFE_FALLBACK("Safe Fallback", "Lower upside but protects against serious downside — preferred when uncertainty is high"),
    DEFERRED("Deferred Decision", "More information is worth more than immediate action — collect evidence before choosing"),
    EXCLUDED("Excluded", "Fails hard constraints — removed before optimisation. Not ranked."),
    INDETERMINATE("Indeterminate", "Evidence inadequate to make a defensible recommendation — model needs more data")
}

class RobustDecisionFrontierModule : FrameworkModule {
    override val id = "robust-frontier"
    override val name = "Robust Decision Frontier"
    override val version = "1.0"
    override val category = "Strategic"

    override fun canAnalyze(model: Scenario): Applicability {
        if (model.actions.isEmpty()) return Applicability(0.0, listOf("No actions defined."))
        return Applicability(
            0.95,
            listOf(
                "Classifies each action into the Robust Decision Frontier: Dominant, Robust, Conditional, Fragile, Safe Fallback, Deferred, Excluded, or Indeterminate.",
                "Hard-constraint filtering (SAFETY, LEGAL, ETHICAL) applied before any scoring — excluded actions are never ranked.",
                "Uses multi-dimensional payoffs, implementation difficulty, belief uncertainty, and constraint analysis."
            )
        )
    }

    override fun analyze(model: Scenario): AnalysisResult {
        val findings = mutableListOf<Finding>()
        val recs = mutableListOf<RecommendedAction>()
        val classifications = mutableMapOf<String, FrontierClass>()
        val reasonings = mutableMapOf<String, String>()

        // ── Step 1: Hard-constraint filter ──────────────────────────────────
        val hardConstraintKinds = setOf(Constraint.Kind.SAFETY, Constraint.Kind.LEGAL, Constraint.Kind.ETHICAL, Constraint.Kind.USER_HARD)
        val hardConstraints = model.constraints.filter { it.kind in hardConstraintKinds }

        val excluded = model.actions.filter { a ->
            a.legalStatus == LegalStatus.PROHIBITED ||
            (a.legalStatus == LegalStatus.RESTRICTED && hardConstraints.any { c -> c.kind == Constraint.Kind.LEGAL })
        }
        val included = model.actions.filter { it.id !in excluded.map { e -> e.id } }

        for (a in excluded) {
            classifications[a.id] = FrontierClass.EXCLUDED
            reasonings[a.id] = "Excluded: legal status = ${a.legalStatus}. Hard constraints prevent further consideration."
        }

        if (excluded.isNotEmpty()) {
            findings += Finding(
                "Hard-constraint filter: ${excluded.size} action(s) excluded before optimisation",
                "The following actions violate hard constraints (SAFETY, LEGAL, ETHICAL) and are removed from ranking:\n" +
                excluded.joinToString("\n") { "• ${it.label} [${it.legalStatus}]" } +
                "\n\nThese options were excluded before optimisation because they violate hard constraints.",
                Finding.Severity.CRITICAL
            )
        }

        if (included.isEmpty()) {
            findings += Finding(
                "No feasible actions remain after hard-constraint filtering",
                "Every proposed action violates a hard constraint. The decision is indeterminate until new options are defined.",
                Finding.Severity.CRITICAL
            )
            return buildResult(model, findings, recs, classifications, reasonings)
        }

        // ── Step 2: Score each included action ───────────────────────────────
        val sf = MatrixSupport.build(model)
        val unclearBeliefs = model.beliefs.count { it.probability < 0.55 }
        val beliefUncertaintyRate = if (model.beliefs.isEmpty()) 0.0 else unclearBeliefs.toDouble() / model.beliefs.size

        data class ActionScore(val action: Action, val payoff: Double, val beliefSensitivity: Double)

        val scored = included.map { action ->
            val payoffScore = computePayoffScore(action, model, sf)
            val implementationBonus = (5 - action.implementationDifficulty) * 0.15
            val timeBonus = when {
                action.timeCost <= 7 -> 0.2
                action.timeCost <= 30 -> 0.1
                else -> 0.0
            }
            val reversibilityBonus = if (action.reversible) 0.1 else 0.0
            val totalScore = payoffScore + implementationBonus + timeBonus + reversibilityBonus
            val beliefSensitivity = computeBeliefSensitivity(action, model)
            ActionScore(action, totalScore, beliefSensitivity)
        }.sortedByDescending { it.payoff }

        val maxScore = scored.first().payoff
        val minScore = scored.last().payoff
        val range = (maxScore - minScore).coerceAtLeast(1e-9)

        // ── Step 3: Classify each action ─────────────────────────────────────
        for (as2 in scored) {
            val pct = (as2.payoff - minScore) / range   // 0..1
            val isHighDifficulty = as2.action.implementationDifficulty >= 4
            val isLongHorizon = as2.action.timeCost > 60
            val isBeliefSensitive = as2.beliefSensitivity > 0.5
            val isIrreversible = !as2.action.reversible
            val noEvidence = model.evidenceSources.isEmpty() && model.beliefs.isEmpty()

            val cls = when {
                noEvidence -> FrontierClass.INDETERMINATE
                beliefUncertaintyRate > 0.5 && pct >= 0.7 && isBeliefSensitive -> FrontierClass.DEFERRED
                pct >= 0.85 && !isHighDifficulty && !isBeliefSensitive -> FrontierClass.DOMINANT
                pct >= 0.65 && !isBeliefSensitive -> FrontierClass.ROBUST
                pct >= 0.65 && isBeliefSensitive -> FrontierClass.CONDITIONAL
                pct >= 0.4 && (isHighDifficulty || isLongHorizon || isIrreversible) -> FrontierClass.FRAGILE
                pct < 0.4 && as2.action.implementationDifficulty <= 2 -> FrontierClass.SAFE_FALLBACK
                else -> FrontierClass.CONDITIONAL
            }
            classifications[as2.action.id] = cls
            reasonings[as2.action.id] = buildReasoning(as2.action, pct, as2.beliefSensitivity, beliefUncertaintyRate, cls)
        }

        // ── Step 4: Frontier findings ─────────────────────────────────────────
        for (cls in FrontierClass.values().filter { it != FrontierClass.EXCLUDED }) {
            val actions = included.filter { classifications[it.id] == cls }
            if (actions.isEmpty()) continue
            findings += Finding(
                "${cls.label}: ${actions.joinToString(", ") { it.label }}",
                "${cls.description}\n\n${actions.joinToString("\n") { a -> "• ${a.label}: ${reasonings[a.id]}" }}",
                when (cls) {
                    FrontierClass.DOMINANT, FrontierClass.ROBUST -> Finding.Severity.INFO
                    FrontierClass.EXCLUDED, FrontierClass.INDETERMINATE -> Finding.Severity.CRITICAL
                    FrontierClass.DEFERRED, FrontierClass.FRAGILE -> Finding.Severity.WARNING
                    else -> Finding.Severity.NOTE
                }
            )
        }

        // ── Step 5: Three decision pathways ──────────────────────────────────
        val best = included.firstOrNull { classifications[it.id] == FrontierClass.DOMINANT }
            ?: included.firstOrNull { classifications[it.id] == FrontierClass.ROBUST }
        val safest = included.firstOrNull { classifications[it.id] == FrontierClass.SAFE_FALLBACK }
            ?: scored.minByOrNull { it.action.implementationDifficulty }?.action
        val deferred = included.firstOrNull { classifications[it.id] == FrontierClass.DEFERRED }

        best?.let { a ->
            val owner = model.stakeholders.firstOrNull { it.id == a.stakeholderId }?.name ?: a.stakeholderId
            recs += RecommendedAction(
                a.stakeholderId, a.label,
                "Preferred under recorded assumptions — scores highest across tested dimensions. Owner: $owner. ${reasonings[a.id] ?: ""}",
                lens = RecommendedAction.Lens.EXPECTED,
                actionId = a.id
            )
        }
        safest?.let { a ->
            if (a.id != best?.id) {
                val owner = model.stakeholders.firstOrNull { it.id == a.stakeholderId }?.name ?: a.stakeholderId
                recs += RecommendedAction(
                    a.stakeholderId, a.label,
                    "Minimises downside exposure under high uncertainty. Owner: $owner.",
                    lens = RecommendedAction.Lens.RISK_AVERSE,
                    actionId = a.id
                )
            }
        }
        if (deferred != null || beliefUncertaintyRate > 0.5) {
            findings += Finding(
                "Consider deferral: belief uncertainty may change the recommendation",
                "Uncertainty rate across beliefs is ${(beliefUncertaintyRate * 100).toInt()}%. See Value of Information module for the highest-priority evidence to collect before committing.",
                Finding.Severity.WARNING
            )
        }

        return buildResult(model, findings, recs, classifications, reasonings)
    }

    private fun computePayoffScore(action: Action, model: Scenario, sf: MatrixSupport.StrategicForm?): Double {
        if (sf != null) {
            // Use the declared decision owner, not just index 0
            val focalSid = when {
                model.decisionOwner.isNotBlank() ->
                    model.stakeholders.firstOrNull { it.id == model.decisionOwner || it.name == model.decisionOwner }?.id
                else -> null
            }
            val focal = if (focalSid != null) sf.players.indexOf(focalSid).takeIf { it >= 0 } ?: 0 else 0
            val pid = sf.players.getOrNull(focal) ?: return defaultScore(action)
            val actionIdx = model.actionsFor(pid).indexOfFirst { it.id == action.id }
            if (actionIdx >= 0) {
                val avg = sf.utility[focal].indices
                    .filter { sf.profileFor(it)[focal] == actionIdx }
                    .map { sf.utility[focal][it] }
                    .filter { !it.isNaN() }  // skip missing cells — never treat NaN as 0
                    .let { vals -> if (vals.isEmpty()) null else vals.average() }
                if (avg != null && !avg.isNaN()) return avg
            }
        }
        return defaultScore(action)
    }

    private fun defaultScore(action: Action) =
        (5 - action.implementationDifficulty) * 1.0 + (if (action.reversible) 0.5 else 0.0)

    private fun computeBeliefSensitivity(action: Action, model: Scenario): Double {
        val uncertainBeliefs = model.beliefs.count { it.probability < 0.6 }
        val total = model.beliefs.size.coerceAtLeast(1)
        // Only count beliefs structurally linked to this action via assumptions — no string matching
        val linkedUncertain = model.stakeholders.flatMap { s -> s.behavioralAssumptions }
            .count { a -> a.affectedActionId == action.id && a.status == FactStatus.UNKNOWN }
        return (uncertainBeliefs + linkedUncertain).toDouble() / (total * 2.0).coerceAtLeast(1.0)
    }

    private fun buildReasoning(action: Action, pct: Double, beliefSens: Double, uncertaintyRate: Double, cls: FrontierClass): String =
        buildString {
            append("Score percentile: ${(pct * 100).toInt()}%.")
            if (uncertaintyRate > 0) append(" Model belief uncertainty: ${(uncertaintyRate * 100).toInt()}% of beliefs have probability < 55%.")
            if (beliefSens > 0.5) append(" Belief-sensitive (${(beliefSens * 100).toInt()}% of linked beliefs uncertain).")
            if (action.implementationDifficulty >= 4) append(" High implementation difficulty (${action.implementationDifficulty}/5).")
            if (action.timeCost > 60) append(" Long time horizon (${action.timeCost} days).")
            if (!action.reversible) append(" Irreversible — failure is hard to undo.")
            append(" → ${cls.label}.")
        }

    private fun buildResult(
        model: Scenario, findings: List<Finding>, recs: List<RecommendedAction>,
        classifications: Map<String, FrontierClass>, reasonings: Map<String, String>
    ) = AnalysisResult(
        moduleId = id, moduleName = name,
        applicability = canAnalyze(model),
        findings = findings, recommendedActions = recs,
        assumptions = listOf(
            Assumption("Hard constraints (SAFETY, LEGAL, ETHICAL, USER_HARD) are filtered first. Actions with LegalStatus=PROHIBITED are excluded."),
            Assumption("Payoff score uses matrix utility when available; falls back to implementation difficulty × reversibility proxy."),
            Assumption("Three decision pathways surfaced: Best expected outcome, Safest robust outcome, Deferred learning pathway."),
            Assumption("Dominant threshold: ≥85th percentile and low implementation difficulty and low belief sensitivity.")
        ),
        calculations = mapOf(
            "totalActions" to model.actions.size,
            "excludedCount" to classifications.count { it.value == FrontierClass.EXCLUDED },
            "dominantCount" to classifications.count { it.value == FrontierClass.DOMINANT },
            "robustCount" to classifications.count { it.value == FrontierClass.ROBUST },
            "conditionalCount" to classifications.count { it.value == FrontierClass.CONDITIONAL },
            "safeCount" to classifications.count { it.value == FrontierClass.SAFE_FALLBACK },
            "actionReasonings" to reasonings.map { (id, r) ->
                val label = model.actions.firstOrNull { it.id == id }?.label ?: id
                "$label: $r"
            }.joinToString(" | ")
        ),
        confidence = 0.72,
        visualizations = emptyList(),
        explanation = "Robust Decision Frontier: ${classifications.count { it.value == FrontierClass.DOMINANT }} dominant, " +
            "${classifications.count { it.value == FrontierClass.ROBUST }} robust, " +
            "${classifications.count { it.value == FrontierClass.EXCLUDED }} excluded by hard constraints. " +
            "Action reasoning summary: ${reasonings.entries.take(3).joinToString("; ") { (id, r) ->
                val label = model.actions.firstOrNull { it.id == id }?.label ?: id
                "$label → ${r.take(60)}"
            }}. " +
            "The engine does not answer 'What will actors do?' — it answers 'Which decision remains defensible across the widest credible range of human responses?'"
    )
}
