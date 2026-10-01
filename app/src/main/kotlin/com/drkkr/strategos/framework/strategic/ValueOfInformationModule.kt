package com.drkkr.strategos.framework.strategic

import com.drkkr.strategos.domain.framework.*
import com.drkkr.strategos.domain.model.*

class ValueOfInformationModule : FrameworkModule {
    override val id = "value-of-information"
    override val name = "Value of Information"
    override val version = "1.0"
    override val category = "Uncertainty"

    override fun canAnalyze(model: Scenario): Applicability {
        val hasUncertainty = model.beliefs.isNotEmpty() || model.assumptionLog.isNotEmpty()
        if (!hasUncertainty) return Applicability(0.0, listOf("No beliefs or assumptions registered — add uncertainty data to enable VoI analysis."))
        return Applicability(
            0.82,
            listOf(
                "Ranks open questions by: P(wrong) × decision impact × (1 / collection cost).",
                "Identifies when deferring a decision to collect evidence is more valuable than immediate action.",
                "Applies to all beliefs with probability < 70% or assumption status = UNKNOWN / AI_INFERRED."
            )
        )
    }

    override fun analyze(model: Scenario): AnalysisResult {
        val findings = mutableListOf<Finding>()
        data class VoIItem(
            val rank: Int,
            val question: String,
            val probWrong: Double,
            val impact: Double,
            val cost: String,
            val obtainable: Boolean,
            val voiScore: Double
        )
        val items = mutableListOf<VoIItem>()

        // ── Score beliefs ────────────────────────────────────────────────────
        for (b in model.beliefs) {
            val probWrong = 1.0 - b.probability
            if (probWrong < 0.30) continue   // skip confident beliefs
            val impact = estimateBeliefImpact(b, model)
            val cost = costLabel(b.status)
            val obtainable = b.status != FactStatus.UNKNOWN
            items += VoIItem(0, "Verify belief: \"${b.label}\" (confidence ${(b.probability * 100).toInt()}%)",
                probWrong, impact, cost, obtainable, probWrong * impact)
        }

        // ── Score assumption log ─────────────────────────────────────────────
        for (a in model.assumptionLog) {
            val probWrong = when (a.status) {
                FactStatus.UNKNOWN -> 0.55
                FactStatus.AI_INFERRED -> 0.40
                FactStatus.USER_ESTIMATE -> 0.25
                FactStatus.CONFIRMED -> continue
            }
            val impact = 0.65  // moderate default — assumptions affect all actions
            items += VoIItem(0, "Confirm assumption: \"${a.label}\" (status: ${a.status})",
                probWrong, impact, costLabel(a.status), true, probWrong * impact)
        }

        // ── Score missing evidence ───────────────────────────────────────────
        if (model.evidenceSources.isEmpty()) {
            items += VoIItem(0, "Obtain at least one evidence source — current analysis has no empirical grounding",
                0.7, 0.8, "Low — search existing literature or internal data", true, 0.56)
        } else {
            val lowQuality = model.evidenceSources.filter { it.quality <= 2 }
            if (lowQuality.isNotEmpty()) {
                items += VoIItem(0, "Upgrade evidence quality — ${lowQuality.size} source(s) rated ≤2/5: ${lowQuality.joinToString { it.label }}",
                    0.4, 0.5, "Medium — expert review or additional data collection", true, 0.20)
            }
        }

        // ── Rank by VoI score ─────────────────────────────────────────────────
        val ranked = items.sortedByDescending { it.voiScore }
            .mapIndexed { i, item -> item.copy(rank = i + 1) }

        if (ranked.isEmpty()) {
            findings += Finding(
                "Evidence quality sufficient — immediate decision is defensible",
                "All registered beliefs have probability ≥ 70% and no UNKNOWN/AI_INFERRED assumptions. Proceed with analysis.",
                Finding.Severity.INFO
            )
        } else {
            val shouldDefer = ranked.any { it.voiScore > 0.35 }
            if (shouldDefer) {
                val critical = ranked.filter { it.voiScore > 0.35 }
                findings += Finding(
                    "Deferral recommended: collecting evidence is more valuable than choosing now",
                    "The following ${critical.size} information gap(s) have VoI score > 0.35 — meaning if these beliefs are wrong, the recommendation changes materially:\n" +
                    critical.joinToString("\n") { "• ${it.question} [VoI: ${String.format("%.2f", it.voiScore)}]" } +
                    "\n\nCollecting this evidence is more valuable than choosing between the top actions now.",
                    Finding.Severity.WARNING
                )
            }

            val top = ranked.take(6)
            findings += Finding(
                "Highest-value information to obtain (ranked by decision impact)",
                top.joinToString("\n\n") { item ->
                    "${item.rank}. ${item.question}" +
                    "\n   VoI score: ${String.format("%.2f", item.voiScore)} | P(wrong): ${(item.probWrong * 100).toInt()}% | Impact: ${(item.impact * 100).toInt()}%" +
                    "\n   Collection cost: ${item.cost}" +
                    "\n   Obtainable: ${if (item.obtainable) "Yes" else "Difficult — may require primary research"}"
                },
                Finding.Severity.NOTE
            )

            if (ranked.size > 6) {
                findings += Finding(
                    "${ranked.size - 6} additional lower-priority information gaps",
                    ranked.drop(6).joinToString("\n") { "• [VoI ${String.format("%.2f", it.voiScore)}] ${it.question}" },
                    Finding.Severity.INFO
                )
            }
        }

        // ── Missing stakeholder data ─────────────────────────────────────────
        val missingProfiles = model.stakeholders.filter {
            it.publicGoals.isEmpty() && it.privateGoals.isEmpty() && it.resourcesControlled.isEmpty()
        }
        if (missingProfiles.isNotEmpty()) {
            findings += Finding(
                "${missingProfiles.size} stakeholder(s) have incomplete profiles",
                "Incomplete profiles reduce the reliability of game-theoretic and behavioural analysis:\n" +
                missingProfiles.joinToString("\n") { "• ${it.name}: missing goals, private incentives, and resources controlled" },
                Finding.Severity.WARNING
            )
        }

        val shouldDefer = ranked.any { it.voiScore > 0.35 }
        return AnalysisResult(
            moduleId = id, moduleName = name,
            applicability = canAnalyze(model),
            findings = findings, recommendedActions = emptyList(),
            assumptions = listOf(
                Assumption("VoI score = P(belief wrong) × decision impact. Threshold for deferral: VoI > 0.35 on any single gap."),
                Assumption("Decision impact estimated from belief probability, stakeholder and action mentions, and evidence coverage."),
                Assumption("Collection cost rated Low/Medium/High based on fact status — not financial cost, but effort to verify.")
            ),
            calculations = mapOf(
                "totalBeliefs" to model.beliefs.size,
                "totalAssumptions" to model.assumptionLog.size,
                "totalEvidenceSources" to model.evidenceSources.size,
                "voiItemCount" to ranked.size,
                "deferralRecommended" to shouldDefer,
                "topVoIScore" to (ranked.firstOrNull()?.voiScore ?: 0.0)
            ),
            confidence = 0.68,
            visualizations = emptyList(),
            explanation = if (shouldDefer)
                "DEFERRAL RECOMMENDED — ${ranked.count { it.voiScore > 0.35 }} critical gap(s) found. Collecting evidence is more valuable than choosing now."
            else if (ranked.isEmpty())
                "Evidence quality is sufficient for defensible analysis. No high-VoI gaps identified."
            else
                "${ranked.size} information gap(s) identified. VoI scores suggest current evidence supports analysis but improvements would increase confidence."
        )
    }

    private fun estimateBeliefImpact(b: Belief, model: Scenario): Double {
        val stakeholderMentions = model.stakeholders.count { s ->
            b.label.contains(s.name, ignoreCase = true)
        }
        val actionMentions = model.actions.count { a ->
            b.label.contains(a.label, ignoreCase = true)
        }
        return (0.45 + stakeholderMentions * 0.12 + actionMentions * 0.08).coerceIn(0.30, 0.92)
    }

    private fun costLabel(status: FactStatus) = when (status) {
        FactStatus.CONFIRMED -> "Already confirmed — no collection needed"
        FactStatus.USER_ESTIMATE -> "Low — direct stakeholder inquiry or internal document review"
        FactStatus.AI_INFERRED -> "Medium — interview, data pull, or expert consultation"
        FactStatus.UNKNOWN -> "High — may require primary research, FOI request, or external expertise"
    }
}
