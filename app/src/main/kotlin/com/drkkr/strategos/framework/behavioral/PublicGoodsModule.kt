package com.drkkr.strategos.framework.behavioral

import com.drkkr.strategos.domain.framework.*
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.framework.MatrixSupport

class PublicGoodsModule : FrameworkModule {
    override val id = "public-goods"
    override val name = "Public Goods Game"
    override val version = "1.0"
    override val category = "Behavioral"

    override fun canAnalyze(model: Scenario): Applicability {
        val n = model.stakeholders.size
        val text = "${model.title} ${model.description}".lowercase()
        val kwHits = listOf("contribut", "public", "collective", "pool", "common", "cooperat", "commons", "shared", "fund", "resource", "free-rid", "freerider").count { text.contains(it) }
        if (n >= 3 && kwHits >= 2) return Applicability((0.55 + kwHits * 0.04).coerceAtMost(0.88), listOf("$n players + public goods language ($kwHits keyword hits). Collective action / free-rider dynamics likely."))
        if (n >= 3 && kwHits == 1) return Applicability(0.38, listOf("$n players with some collective-action language."))
        if (n >= 4 && kwHits == 0) return Applicability(0.28, listOf("Large group ($n players) may involve public goods dynamics."))
        return Applicability(0.0, listOf("Public goods game requires ≥3 players with collective action context."))
    }

    override fun analyze(model: Scenario): AnalysisResult {
        val sf = MatrixSupport.build(model)
        val n = model.stakeholders.size
        val findings = mutableListOf<Finding>()

        var estimatedMPCR: Double? = null

        if (sf != null && sf.players.size >= 3) {
            val allCoopProfile = List(sf.players.size) { 0 }
            val allDefectProfile = List(sf.players.size) { 1.coerceAtMost((sf.strategies[sf.players[it]] ?: listOf()).size - 1) }

            val allCoopIdx = sf.profileIndex(allCoopProfile)
            val allDefectIdx = sf.profileIndex(allDefectProfile)

            val cooperateGain = sf.utility[0][allCoopIdx] - sf.utility[0][allDefectIdx]
            val minPayoff = sf.utility[0].minOrNull() ?: 0.0
            val maxPayoff = sf.utility[0].maxOrNull() ?: 1.0

            if (maxPayoff - minPayoff > 1e-6) {
                estimatedMPCR = cooperateGain / (maxPayoff - minPayoff)
            }

            if (cooperateGain > 0) {
                findings += Finding(
                    "Full cooperation is collectively optimal",
                    "All-cooperate payoff per player: %.2f vs all-defect: %.2f. Net gain from universal cooperation: %.2f per player. ${if (estimatedMPCR != null) "Estimated MPCR ≈ %.2f.".format(estimatedMPCR) else ""}".format(
                        sf.utility[0][allCoopIdx], sf.utility[0][allDefectIdx], cooperateGain),
                    Finding.Severity.INFO
                )
            }

            // Free-rider check: player 0 defects while others cooperate
            val freeRiderActions = (sf.players.indices).map { pi ->
                if (pi == 0) 1.coerceAtMost((sf.strategies[sf.players[pi]] ?: listOf()).size - 1) else 0
            }
            val freeRiderIdx = sf.profileIndex(freeRiderActions)
            val freeRiderGain = sf.utility[0][freeRiderIdx] - sf.utility[0][allCoopIdx]

            if (freeRiderGain > 0) {
                findings += Finding(
                    "Free-rider incentive confirmed: gain = %.2f".format(freeRiderGain),
                    "Defecting while others cooperate yields %.2f (vs %.2f cooperating). Individual rationality predicts all-defect Nash equilibrium despite collective optimum of %.2f per player.".format(
                        sf.utility[0][freeRiderIdx], sf.utility[0][allCoopIdx], sf.utility[0][allCoopIdx]),
                    Finding.Severity.CRITICAL
                )
            }
        }

        findings += Finding(
            "Experimental baseline: 40–60% initial cooperation (Ledyard 1995)",
            "Without enforcement, public goods contribution rates start at 40–60% and decay toward zero by round 10. One-shot contributions average ~40% of endowment. Decay is faster in larger groups.",
            Finding.Severity.NOTE
        )

        findings += Finding(
            "Punishment restores cooperation (Fehr & Gächter 2000)",
            "Costly punishment rights raise average contributions to 85–95% and sustain cooperation across rounds. Even the credible threat of punishment (without actual use) significantly shifts equilibrium.\n• Partner treatment (repeated, fixed group): cooperation sustained long-term; punishment used sparingly once reputation built.\n• Stranger treatment (random rematching): punishment used more frequently each round; cooperation maintained but at higher cost. Fixed-partner groups achieve higher cooperation at lower punishment cost.",
            Finding.Severity.INFO
        )

        findings += Finding(
            "Provision-Point Mechanism: threshold + money-back guarantee (Rondeau et al. 1999, Rose et al. 2002)",
            "The Provision-Point Mechanism (PPM) solves the public goods problem in one-shot settings without costly punishment:\n• A threshold T is announced (e.g., minimum funding needed to build the good).\n• Contributions are collected; if total ≥ T, the good is provided.\n• If total < T, all contributions are refunded (money-back guarantee).\n• Result: threshold acts as a coordination device — contributors can safely give knowing they won't lose money if the project fails.\n• Rose et al. (2002): PPM achieves 60–80% provision rates in lab settings — far above standard VCM baselines.\n• Best for: crowdfunding, infrastructure decisions, one-time collective purchases where repetition and punishment are not available.",
            Finding.Severity.INFO
        )

        val maxAuthority = model.stakeholders.maxOfOrNull { it.authority } ?: 0
        val minAuthority = model.stakeholders.minOfOrNull { it.authority } ?: 0
        val authorityRange = maxAuthority - minAuthority
        if (authorityRange > 3) {
            val highAuth = model.stakeholders.maxByOrNull { it.authority }?.name ?: "high-authority member"
            findings += Finding(
                "Heterogeneous authority (range = $authorityRange)",
                "High-authority members (e.g. $highAuth) often face stronger contribution expectations. Unequal burdens without explicit norms breed resentment and reduce group cooperation.",
                Finding.Severity.WARNING
            )
        }

        if (n > 8) {
            findings += Finding(
                "Large group ($n players): coordination costs rise",
                "Cooperation rates decline with group size due to reduced identifiability and monitoring difficulty. Consider subgroup structures or designated coordinators.",
                Finding.Severity.WARNING
            )
        }

        val recs = mutableListOf<RecommendedAction>()

        recs += RecommendedAction(null, "Consider Provision-Point Mechanism for one-shot settings",
            "Announce a contribution threshold T. If total contributions ≥ T, the good is provided; if < T, all contributions refunded. Money-back guarantee eliminates fear of wasted contributions, achieving 60–80% provision rates without punishment (Rose et al. 2002). Ideal when repetition and peer punishment are infeasible.",
            lens = RecommendedAction.Lens.LOW_REGRET)

        recs += RecommendedAction(null, "Implement peer-visible accountability",
            "Transparent contribution reporting (dashboard, public scoreboard) raises cooperation rates by 30–50% in field studies. Identifiability alone is a powerful prosocial nudge — low cost, high impact.",
            lens = RecommendedAction.Lens.RELATIONSHIP)

        recs += RecommendedAction(null, "Define graduated contribution norms",
            "Explicit minimum and aspirational contribution thresholds per participant prevent race-to-the-bottom without costly enforcement. Graduated norms are more stable than binary contribute/free-ride framing.",
            lens = RecommendedAction.Lens.ETHICAL_LEGAL)

        recs += RecommendedAction(null, "Frame contribution as shared identity",
            "Groups who understand contribution as expressing collective identity (not personal sacrifice) sustain cooperation longer and more robustly than groups given purely economic framing.",
            lens = RecommendedAction.Lens.LONG_TERM)

        if (n > 5) {
            recs += RecommendedAction(null, "Consider subgroup coalition structures",
            "In groups of $n, nested subgroup cooperation (with cross-group matching) outperforms single-pool public goods games. Smaller accountability units improve monitoring and reciprocity.",
            lens = RecommendedAction.Lens.EXPECTED)
        } else {
            recs += RecommendedAction(null, "Use sequential conditional commitment",
            "Each player publicly commits before the next moves. Sequential commitment enables cooperation at lower punishment cost in groups of $n.",
            lens = RecommendedAction.Lens.LOW_REGRET)
        }

        return AnalysisResult(
            moduleId = id, moduleName = name,
            applicability = canAnalyze(model),
            findings = findings, recommendedActions = recs,
            assumptions = listOf(
                Assumption("Action 0 = Contribute, action 1 = Free-ride per player."),
                Assumption("MPCR (Marginal Per Capita Return): ratio of per-player gain from full cooperation relative to payoff range."),
                Assumption("Behavioral baselines from Ledyard (1995) and Fehr & Gächter (2000)."),
                Assumption("Provision-Point Mechanism (PPM) baselines from Rose et al. (2002) and Rondeau et al. (1999)."),
                Assumption("Partner/stranger treatment effects from Fehr & Gächter (2000): partner = fixed group, stranger = random rematching.")
            ),
            calculations = mapOf("playerCount" to n, "estimatedMPCR" to (estimatedMPCR ?: "N/A"), "authorityRange" to authorityRange),
            confidence = 0.60,
            visualizations = emptyList(),
            explanation = "Public goods game with $n players. Individual free-rider incentives conflict with collective optimum. Three most empirically supported levers: peer accountability, punishment rights, and identity-based framing."
        )
    }
}
