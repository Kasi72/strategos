package com.drkkr.strategos.framework.behavioral

import com.drkkr.strategos.domain.framework.*
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.framework.MatrixSupport
import kotlin.math.abs

class UltimatumModule : FrameworkModule {
    override val id = "ultimatum"
    override val name = "Ultimatum Game (Fairness)"
    override val version = "1.0"
    override val category = "Behavioral"

    override fun canAnalyze(model: Scenario): Applicability {
        val n = model.stakeholders.size
        val text = "${model.title} ${model.description}".lowercase()
        val kwHits = listOf("split", "offer", "reject", "accept", "propose", "fair", "ultimatum", "allocation", "divide", "unequal", "threshold", "share").count { text.contains(it) }
        if (n == 2 && kwHits >= 2) return Applicability((0.55 + kwHits * 0.04).coerceAtMost(0.88), listOf("2-player scenario with fairness/allocation language ($kwHits keyword hits). Ultimatum game dynamics likely."))
        if (n == 2 && kwHits == 1) return Applicability(0.40, listOf("2-player scenario with limited fairness language. Ultimatum module may partially apply."))
        return Applicability(0.0, listOf("Ultimatum game requires 2 players and fairness/allocation context."))
    }

    override fun analyze(model: Scenario): AnalysisResult {
        val n = model.stakeholders.size
        val sf = MatrixSupport.build(model)
        val p0 = model.stakeholders.getOrNull(0) ?: return emptyResult()
        val p1 = model.stakeholders.getOrNull(1) ?: return emptyResult()

        val (proposer, responder) = if (p0.authority >= p1.authority) p0 to p1 else p1 to p0

        val findings = mutableListOf<Finding>()

        if (sf != null && sf.players.size == 2) {
            val piIdx = sf.players.indexOf(proposer.id)
            val riIdx = sf.players.indexOf(responder.id)
            if (piIdx >= 0 && riIdx >= 0) {
                data class ProfilePayoffs(val profileIdx: Int, val proposerU: Double, val responderU: Double)
                val profiles = (0 until sf.profileCount()).map { idx ->
                    ProfilePayoffs(idx, sf.utility[piIdx][idx], sf.utility[riIdx][idx])
                }

                val maxTotal = profiles.maxOfOrNull { it.proposerU + it.responderU } ?: 1.0
                if (maxTotal > 1e-6) {
                    // Most proposer-favourable profile
                    val greediest = profiles.maxByOrNull { it.proposerU }
                    greediest?.let { pp ->
                        val total = pp.proposerU + pp.responderU
                        val proposerShare = if (total > 1e-6) pp.proposerU / total else 0.5
                        val profile = sf.profileFor(pp.profileIdx)
                        val actionLabel = model.actionsFor(proposer.id).getOrNull(profile[piIdx])?.label ?: "?"
                        val rejectionRisk = rejectionRiskLabel(proposerShare)
                        findings += Finding(
                            "Proposer-optimal offer: $actionLabel — rejection risk: $rejectionRisk",
                            "${proposer.name} captures %.1f%% of value (%.2f), ${responder.name} receives %.1f%% (%.2f). $rejectionRisk based on cross-cultural ultimatum data.".format(
                                proposerShare * 100, pp.proposerU, (1 - proposerShare) * 100, pp.responderU),
                            if (proposerShare > 0.65) Finding.Severity.WARNING else Finding.Severity.NOTE
                        )
                    }

                    // Most equitable profile
                    val fairest = profiles.minByOrNull { pp ->
                        val total = pp.proposerU + pp.responderU
                        abs(if (total > 1e-6) pp.proposerU / total - 0.5 else 0.0)
                    }
                    fairest?.let { pp ->
                        val total = pp.proposerU + pp.responderU
                        val proposerShare = if (total > 1e-6) pp.proposerU / total else 0.5
                        val profile = sf.profileFor(pp.profileIdx)
                        val actionLabel = model.actionsFor(proposer.id).getOrNull(profile[piIdx])?.label ?: "?"
                        findings += Finding(
                            "Most equitable outcome: $actionLabel (%.1f%% / %.1f%% split)".format(proposerShare * 100, (1 - proposerShare) * 100),
                            "${proposer.name}: %.2f, ${responder.name}: %.2f. Closest to equal division — acceptance probability >90%%.".format(pp.proposerU, pp.responderU),
                            Finding.Severity.INFO
                        )
                    }
                }
            }
        }

        findings += Finding(
            "Behavioral rejection thresholds (Henrich et al. 2001)",
            "Cross-cultural evidence from 15 societies: offers below 20% of total are rejected 50–80% of the time; 20–30% face 20–40% rejection; 30–40% face <20%; 40–50% accepted >90%. Proposers who ignore this incur systematic losses.",
            Finding.Severity.NOTE
        )

        val authorityGap = abs(p0.authority - p1.authority)
        if (authorityGap > 3) {
            findings += Finding(
                "High authority asymmetry (gap = $authorityGap)",
                "${proposer.name} holds structural power advantage, but ${responder.name} retains veto right. Power asymmetry often increases responders' sensitivity to fairness violations — perceived unfairness triggers rejection even when offers are economically reasonable.",
                Finding.Severity.WARNING
            )
        }

        if (n > 2) {
            findings += Finding(
                "Multi-party ultimatum: $n players change the strategic structure",
                "Classic ultimatum theory is 2-player. With $n parties the responder can seek competing offers — reducing the proposer's take-it-or-leave-it power.\n\n" +
                "Key structural shifts:\n" +
                "• Proposer competition: multiple proposers bid up the responder's share (converges toward equal split as n grows).\n" +
                "• Outside options: responder's credible alternatives reduce their rejection threshold — offers below 30% may still be accepted if outside options are worse.\n" +
                "• Coalition formation: sub-groups can coordinate to reject jointly, creating collective bargaining dynamics beyond simple bilateral ultimatum.\n\n" +
                "Empirical note: lab experiments with 3+ proposers show responder acceptance rates rise significantly — the classic 'reject 20%' finding weakens when responders have alternatives. Adjust offer strategy accordingly.",
                Finding.Severity.NOTE
            )
        }

        val recs = listOf(
            RecommendedAction(proposer.id, "Target 35–45% share for responder",
                "Behaviorally optimal offer range: above the rejection threshold (>25%), preserving proposer advantage, and acceptable in >85% of interactions. Adjust upward if relationship continuity matters.",
                lens = RecommendedAction.Lens.RELATIONSHIP),
            RecommendedAction(responder.id, "Pre-commit to a minimum acceptable offer",
                "Define your walk-away threshold before receiving the offer to avoid in-the-moment rationalisation of unfair deals. Communicate this threshold credibly to shift proposer's anchor.",
                lens = RecommendedAction.Lens.RISK_AVERSE),
            RecommendedAction(null, "Reframe as joint surplus rather than division",
                "Presenting the offer as 'we both gain from this' rather than 'I'm giving you X' raises acceptance rates by 15–25% in experimental studies. Emphasise mutual benefit over distributive framing.",
                lens = RecommendedAction.Lens.LONG_TERM)
        )

        return AnalysisResult(
            moduleId = id, moduleName = name,
            applicability = canAnalyze(model),
            findings = findings, recommendedActions = recs,
            assumptions = listOf(
                Assumption("Higher-authority stakeholder assumed to be proposer."),
                Assumption("Rejection thresholds from Henrich et al. (2001) 15-society cross-cultural study."),
                Assumption("One-shot framing; ongoing relationship value warrants more generous offers.")
            ),
            calculations = mapOf("proposerId" to proposer.id, "responderId" to responder.id, "authorityGap" to authorityGap),
            confidence = 0.65,
            visualizations = emptyList(),
            explanation = "Ultimatum game: proposer offers a split; responder accepts or both get nothing. Behavioral evidence shows responders punish perceived unfairness even at personal cost — pure payoff maximisation by proposers systematically fails."
        )
    }

    private fun rejectionRiskLabel(proposerShare: Double): String = when {
        proposerShare > 0.80 -> "Very high (>60% rejection in lab studies)"
        proposerShare > 0.65 -> "High (40–60% rejection)"
        proposerShare > 0.55 -> "Moderate (20–40% rejection)"
        proposerShare > 0.45 -> "Low (<20% rejection)"
        else -> "Very low (<5% rejection)"
    }

    private fun emptyResult() = AnalysisResult(id, name, Applicability(0.0, listOf("Insufficient model data.")), emptyList(), emptyList(), emptyList(), emptyMap(), 0.0, emptyList(), "")
}
