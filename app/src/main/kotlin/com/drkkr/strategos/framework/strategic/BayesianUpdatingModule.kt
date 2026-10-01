package com.drkkr.strategos.framework.strategic

import com.drkkr.strategos.domain.framework.*
import com.drkkr.strategos.domain.model.Scenario
import kotlin.math.abs
import kotlin.math.ln

class BayesianUpdatingModule : FrameworkModule {
    override val id = "bayesian-updating"
    override val name = "Bayesian Belief Updating"
    override val version = "1.0"
    override val category = "Decision Quality"

    override fun canAnalyze(model: Scenario): Applicability {
        if (model.beliefs.isEmpty() && model.evidenceSources.isEmpty())
            return Applicability(0.10, listOf("No beliefs or evidence sources — Bayesian updating has nothing to update."))
        if (model.beliefs.isEmpty())
            return Applicability(0.40, listOf("Evidence sources present but no explicit beliefs — partial analysis possible."))
        return Applicability(0.88, listOf(
            "${model.beliefs.size} belief(s) defined, ${model.evidenceSources.size} evidence source(s).",
            "Bayesian updating applies: P(H|E) = P(E|H)·P(H) / P(E)."
        ))
    }

    override fun analyze(model: Scenario): AnalysisResult {
        val findings = mutableListOf<Finding>()
        val recs = mutableListOf<RecommendedAction>()
        val calcs = mutableMapOf<String, Any>()

        val beliefCount = model.beliefs.size
        val evidenceCount = model.evidenceSources.size

        // beliefs: List<Belief> with probability: Double (0.0–1.0)
        val priors: Map<String, Double> = model.beliefs.associate { b ->
            b.label to b.probability.coerceIn(0.001, 0.999)
        }

        // evidenceSources: List<EvidenceSource> with quality: Int (1–5)
        val meanEvidenceQuality = if (model.evidenceSources.isEmpty()) 0.5
            else model.evidenceSources.map { it.quality / 5.0 }.average()

        calcs["beliefCount"] = beliefCount
        calcs["evidenceSourceCount"] = evidenceCount
        calcs["meanEvidenceQuality"] = meanEvidenceQuality

        if (priors.isEmpty()) {
            findings += Finding(
                "No beliefs found with probability values",
                "Add Belief entries with probability values (0.0–1.0) to enable Bayesian computation.",
                Finding.Severity.WARNING
            )
        } else {
            val posteriors = mutableMapOf<String, Double>()
            val updates = mutableListOf<String>()

            priors.forEach { (hyp, prior) ->
                val pEgivenH = meanEvidenceQuality.coerceIn(0.05, 0.95)
                val pEgivenNotH = (1.0 - pEgivenH).coerceIn(0.05, 0.95)
                val pE = pEgivenH * prior + pEgivenNotH * (1.0 - prior)
                val posterior = (pEgivenH * prior / pE).coerceIn(0.001, 0.999)
                posteriors[hyp] = posterior

                val shift = posterior - prior
                val direction = when {
                    abs(shift) < 0.02 -> "negligible shift"
                    shift > 0 -> "↑ strengthened by +${"%.2f".format(shift)}"
                    else -> "↓ weakened by ${"%.2f".format(abs(shift))}"
                }
                updates += "$hyp: prior=${"%.2f".format(prior)} → posterior=${"%.2f".format(posterior)} ($direction)"
            }

            calcs["posteriors"] = posteriors.entries.joinToString(", ") { "${it.key}=${it.value}" }

            findings += Finding(
                "Bayesian update: ${priors.size} belief(s) revised given evidence quality ${"%.0f".format(meanEvidenceQuality * 100)}%",
                "P(H|E) = P(E|H)·P(H) / P(E). P(E|H) proxied from evidence source quality ratings (1–5).\n\n${updates.joinToString("\n")}",
                Finding.Severity.INFO
            )

            val lowConfidence = posteriors.filter { it.value < 0.3 || it.value > 0.7 }
            if (lowConfidence.isEmpty()) {
                findings += Finding(
                    "All posteriors in moderate range (0.30–0.70) — uncertainty persists",
                    "No hypothesis has been strongly confirmed or refuted. Use expected utility or minimax regret rather than treating any hypothesis as settled.",
                    Finding.Severity.NOTE
                )
            } else {
                lowConfidence.forEach { (hyp, post) ->
                    val label = if (post > 0.7) "strongly supported (P=${"%.2f".format(post)})" else "strongly challenged (P=${"%.2f".format(post)})"
                    findings += Finding(
                        "Hypothesis '$hyp' $label after update",
                        if (post > 0.7) "Evidence supports proceeding if this is the critical hypothesis for the recommended action."
                        else "Evidence challenges this belief — reconsider recommendations that depend on it.",
                        if (post < 0.3) Finding.Severity.WARNING else Finding.Severity.INFO
                    )
                }
            }

            // Entropy reduction
            val entropyReduction = priors.entries.sumOf { (hyp, prior) ->
                val post = posteriors[hyp] ?: prior
                val hPrior = if (prior in 0.001..0.999) -prior * ln(prior) - (1 - prior) * ln(1 - prior) else 0.0
                val hPost = if (post in 0.001..0.999) -post * ln(post) - (1 - post) * ln(1 - post) else 0.0
                (hPrior - hPost).coerceAtLeast(0.0)
            }
            calcs["entropyReduction"] = entropyReduction

            if (entropyReduction < 0.05) {
                findings += Finding(
                    "Evidence has low information value — sources do not sharply discriminate hypotheses",
                    "Entropy reduction < 0.05 nats. Collect targeted evidence that distinguishes competing hypotheses. See Value of Information module.",
                    Finding.Severity.WARNING
                )
            }
        }

        if (evidenceCount == 0) {
            findings += Finding(
                "No evidence sources — beliefs are unanchored priors",
                "Without evidence sources all beliefs are pure prior assumptions. Collect empirical grounding before committing to a recommendation.",
                Finding.Severity.WARNING
            )
        } else if (meanEvidenceQuality < 0.5) {
            findings += Finding(
                "Mean evidence quality below 50% — low-reliability information environment",
                "Evidence sources average quality ${"%.0f".format(meanEvidenceQuality * 100)}%. Prioritise collecting higher-quality sources before finalising the recommendation.",
                Finding.Severity.WARNING
            )
        }

        if (model.repeated) {
            findings += Finding(
                "Repeated game: posterior beliefs from round T become priors for round T+1",
                "Systematic updaters outperform intuitive updaters in repeated strategic settings (Camerer 2003). Schedule belief updates after each observable round outcome.",
                Finding.Severity.NOTE
            )
            recs += RecommendedAction(null,
                "Implement formal after-action belief revision protocol",
                "After each decision round: log what was predicted, what was observed, and update beliefs using the current posterior as the new prior.",
                lens = RecommendedAction.Lens.LONG_TERM)
        }

        recs += RecommendedAction(null,
            "Identify the single highest-value observation to collect next",
            "Collect the evidence that would most change the posterior for the hypothesis most critical to the recommendation. Do not collect evidence uniformly across all hypotheses.",
            lens = RecommendedAction.Lens.LOW_REGRET)

        if (model.informationAsymmetry) {
            recs += RecommendedAction(null,
                "Verify information sources are incentive-compatible before updating",
                "Under asymmetry rational agents misreport. Use independent verification or incentive-compatible elicitation (scoring rules, audits) before updating beliefs on stakeholder-reported data.",
                lens = RecommendedAction.Lens.RISK_AVERSE)
        }

        return AnalysisResult(
            moduleId = id, moduleName = name,
            applicability = canAnalyze(model),
            findings = findings, recommendedActions = recs,
            assumptions = listOf(
                Assumption("Evidence quality proxied from EvidenceSource.quality (1–5 scale); true likelihood ratios require domain-specific elicitation."),
                Assumption("P(E|¬H) = 1 - P(E|H): evidence is assumed equally discriminating in both directions."),
                Assumption("Bayesian framework assumes coherent probability beliefs; ordinal rankings produce directional guidance only.")
            ),
            calculations = calcs,
            confidence = if (priors.isEmpty()) 0.30 else 0.72,
            visualizations = emptyList(),
            explanation = "Bayesian belief updating: P(H|E) = P(E|H)·P(H)/P(E) applied to all beliefs using mean evidence source quality as P(E|H) proxy. Reports posterior shifts, entropy reduction, and information value."
        )
    }
}
