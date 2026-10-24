package com.drkkr.strategos.framework.behavioral

import com.drkkr.strategos.domain.framework.*
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.framework.MatrixSupport
import kotlin.math.max
import kotlin.math.min

class VirtualBargainingModule : FrameworkModule {
    override val id = "virtual-bargaining"
    override val name = "Virtual Bargaining (Nash Bargaining)"
    override val version = "1.0"
    override val category = "Behavioral"

    override fun canAnalyze(model: Scenario): Applicability {
        val sf = MatrixSupport.build(model) ?: return Applicability(0.0, listOf("No payoff matrix available."))
        if (sf.players.size != 2) return Applicability(0.0, listOf("Nash bargaining requires exactly 2 players."))
        val text = "${model.title} ${model.description}".lowercase()
        val kwHits = listOf("negotiat", "bargain", "agreement", "settle", "deal", "compromise", "offer", "propose", "split", "fair", "resolve").count { text.contains(it) }
        val score = (0.55 + kwHits * 0.04).coerceAtMost(0.85)
        return Applicability(score, listOf("2-player game with ${sf.profileCount()} action profiles. Virtual bargaining models what rational agents would agree on if explicit communication were possible (Misyak & Chater 2014)."))
    }

    override fun analyze(model: Scenario): AnalysisResult {
        val sf = MatrixSupport.buildOrThrow(model)
        val p0 = sf.players[0]
        val p1 = sf.players[1]
        val count = sf.profileCount()

        val d0 = (0 until count).minOfOrNull { sf.utility[0][it] } ?: 0.0
        val d1 = (0 until count).minOfOrNull { sf.utility[1][it] } ?: 0.0

        var bestNashProduct = Double.NEGATIVE_INFINITY
        var nbsIndex = 0
        for (idx in 0 until count) {
            val u0 = sf.utility[0][idx]
            val u1 = sf.utility[1][idx]
            if (u0 < d0 || u1 < d1) continue
            val nashProd = (u0 - d0) * (u1 - d1)
            if (nashProd > bestNashProduct) { bestNashProduct = nashProd; nbsIndex = idx }
        }

        var bestSW = Double.NEGATIVE_INFINITY
        var swIndex = 0
        for (idx in 0 until count) {
            val sw = sf.utility[0][idx] + sf.utility[1][idx]
            if (sw > bestSW) { bestSW = sw; swIndex = idx }
        }

        val nbsProfile = sf.profileFor(nbsIndex)
        val swProfile = sf.profileFor(swIndex)
        val nbs0 = sf.utility[0][nbsIndex]
        val nbs1 = sf.utility[1][nbsIndex]

        val n0 = model.stakeholders.first { it.id == p0 }.name
        val n1 = model.stakeholders.first { it.id == p1 }.name
        val nbsA0 = model.actionsFor(p0).getOrNull(nbsProfile[0])?.label ?: "?"
        val nbsA1 = model.actionsFor(p1).getOrNull(nbsProfile[1])?.label ?: "?"
        val swA0 = model.actionsFor(p0).getOrNull(swProfile[0])?.label ?: "?"
        val swA1 = model.actionsFor(p1).getOrNull(swProfile[1])?.label ?: "?"

        val gain0 = nbs0 - d0
        val gain1 = nbs1 - d1
        val gainAsymmetry = if (max(gain0, gain1) > 1e-6) min(gain0, gain1) / max(gain0, gain1) else 1.0

        val findings = mutableListOf<Finding>()

        findings += Finding(
            "Nash Bargaining Solution: $nbsA0 / $nbsA1",
            "$n0 receives %.2f (gain from disagreement: +%.2f), $n1 receives %.2f (gain: +%.2f). Nash product: %.3f. Virtual bargaining predicts rational agents converge here without explicit communication.".format(nbs0, gain0, nbs1, gain1, bestNashProduct),
            Finding.Severity.INFO
        )

        if (nbsIndex == swIndex) {
            findings += Finding(
                "NBS and social welfare optimum coincide — strong convergence",
                "The jointly rational outcome ($nbsA0, $nbsA1) also maximises total welfare (%.2f). Both individually and collectively rational.".format(bestSW),
                Finding.Severity.INFO
            )
        } else {
            findings += Finding(
                "Social welfare optimum: $swA0 / $swA1 (total = %.2f)".format(bestSW),
                "NBS total welfare: %.2f. Utilitarian maximum differs — one party gains disproportionately at that optimum. NBS is more individually equitable.".format(nbs0 + nbs1),
                Finding.Severity.NOTE
            )
        }

        if (gainAsymmetry < 0.6) {
            findings += Finding(
                "Asymmetric NBS gains (gain ratio = %.2f)".format(gainAsymmetry),
                "One party's gain from the NBS is %.0f%% of the other's. Consider Kalai-Smorodinsky equal-proportional solution or explicit fairness norm if equity is a concern.".format(gainAsymmetry * 100),
                Finding.Severity.WARNING
            )
        }

        findings += Finding(
            "Disagreement point: $n0=%.2f, $n1=%.2f".format(d0, d1),
            "NBS is defined relative to the worst-case (disagreement) payoffs. Both players receive strictly more than their disagreement point — rational to accept.",
            Finding.Severity.NOTE
        )

        val recs = listOf(
            RecommendedAction(p0, nbsA0,
                "Nash Bargaining Solution for $n0. Maximises joint surplus beyond disagreement point. Both parties benefit relative to their worst case (d₀=%.2f).".format(d0),
                lens = RecommendedAction.Lens.RELATIONSHIP),
            RecommendedAction(p1, nbsA1,
                "Nash Bargaining Solution for $n1. Agreeing yields %.2f vs disagreement payoff of %.2f (+%.2f).".format(nbs1, d1, gain1),
                lens = RecommendedAction.Lens.RELATIONSHIP),
            RecommendedAction(null, "Frame proposal using the Nash product logic",
                "Presenting the NBS framing to both parties — showing how each gains relative to their walk-away point — reduces anchoring on positional demands and accelerates agreement.",
                lens = RecommendedAction.Lens.LONG_TERM)
        )

        return AnalysisResult(
            moduleId = id, moduleName = name,
            applicability = canAnalyze(model),
            findings = findings, recommendedActions = recs,
            assumptions = listOf(
                Assumption("Disagreement point = each player's minimum payoff across all action profiles."),
                Assumption("Nash Bargaining Solution (Nash 1950): maximises (u₀−d₀)×(u₁−d₁)."),
                Assumption("Virtual bargaining (Misyak & Chater, Royal Society 2014): players simulate what they would agree to under costless communication and act accordingly.")
            ),
            calculations = mapOf("d0" to d0, "d1" to d1, "nbs0" to nbs0, "nbs1" to nbs1, "nashProduct" to bestNashProduct, "socialWelfare" to bestSW, "gainAsymmetry" to gainAsymmetry),
            confidence = 0.78,
            visualizations = emptyList(),
            explanation = "Virtual bargaining theory (Misyak & Chater 2014): agents reason about what they would jointly agree to, converging on the Nash Bargaining Solution ($nbsA0, $nbsA1) — providing a uniquely rational reference point for negotiation."
        )
    }
}
