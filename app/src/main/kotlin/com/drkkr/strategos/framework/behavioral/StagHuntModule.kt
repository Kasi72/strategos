package com.drkkr.strategos.framework.behavioral

import com.drkkr.strategos.domain.framework.*
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.framework.MatrixSupport
import kotlin.math.abs

class StagHuntModule : FrameworkModule {
    override val id = "stag-hunt"
    override val name = "Stag Hunt (Coordination)"
    override val version = "1.0"
    override val category = "Behavioral"

    override fun canAnalyze(model: Scenario): Applicability {
        val shScore = ScenarioClassifier.detectSH(model)
        if (shScore > 0.5) return Applicability(shScore, listOf("Two pure Nash equilibria with payoff vs risk dominance tension — Stag Hunt structure confirmed."))
        val sf = MatrixSupport.build(model) ?: return Applicability(0.0, listOf("No payoff matrix."))
        if (sf.players.size != 2 || sf.strategies.values.any { it.size != 2 }) return Applicability(0.0, listOf("Requires 2 players, 2 actions each."))
        return Applicability(0.2, listOf("2×2 game checked but no clear Stag Hunt structure detected."))
    }

    override fun analyze(model: Scenario): AnalysisResult {
        val sf = MatrixSupport.build(model)!!
        val p0 = sf.players[0]
        val p1 = sf.players[1]

        val a = sf.utility[0][sf.profileIndex(listOf(0, 0))]
        val b = sf.utility[0][sf.profileIndex(listOf(0, 1))]
        val c = sf.utility[0][sf.profileIndex(listOf(1, 0))]
        val d = sf.utility[0][sf.profileIndex(listOf(1, 1))]

        val e2 = sf.utility[1][sf.profileIndex(listOf(0, 0))]
        val h2 = sf.utility[1][sf.profileIndex(listOf(1, 1))]
        val payoffDominant = a > d && e2 > h2
        val euCoordinate = (a + b) / 2.0
        val euSafe = (c + d) / 2.0
        val coordinationIsRiskDominant = euCoordinate > euSafe

        // Threshold p* where agent is indifferent: p*a + (1-p*)b = p*c + (1-p*)d
        val denom = a - b - c + d
        val pThreshold = if (abs(denom) > 1e-9) ((d - b) / denom).coerceIn(0.0, 1.0) else 0.5

        val n0 = model.stakeholders.first { it.id == p0 }.name
        val n1 = model.stakeholders.first { it.id == p1 }.name
        val a0Coord = model.actionsFor(p0).getOrNull(0)?.label ?: "Coordinate"
        val a0Safe = model.actionsFor(p0).getOrNull(1)?.label ?: "Safe option"
        val a1Coord = model.actionsFor(p1).getOrNull(0)?.label ?: "Coordinate"
        val a1Safe = model.actionsFor(p1).getOrNull(1)?.label ?: "Safe option"

        val findings = mutableListOf<Finding>()

        findings += Finding(
            if (payoffDominant) "Payoff-dominant equilibrium: ($a0Coord, $a1Coord) = %.2f".format(a) else "No clear payoff-dominant equilibrium",
            "Mutual coordination yields %.2f (${n0} + ${n1}). Safe outside option: %.2f. ${if (payoffDominant) "Coordination is Pareto superior." else "Coordination does not dominate safety."}".format(a, d),
            if (payoffDominant) Finding.Severity.INFO else Finding.Severity.NOTE
        )

        findings += Finding(
            if (coordinationIsRiskDominant) "Coordination is also risk-dominant (EU=%.2f > safe EU=%.2f)".format(euCoordinate, euSafe)
            else "Safe strategy ($a0Safe) is risk-dominant (EU=%.2f > coordinate EU=%.2f)".format(euSafe, euCoordinate),
            "Against a 50/50 opponent, coordination gives EU=%.2f, safe gives EU=%.2f. Threshold: coordinate if opponent cooperates with probability p > %.0f%%.".format(euCoordinate, euSafe, pThreshold * 100),
            if (coordinationIsRiskDominant) Finding.Severity.INFO else Finding.Severity.WARNING
        )

        if (!coordinationIsRiskDominant && payoffDominant) {
            findings += Finding(
                "Coordination trap: ($a0Coord, $a1Coord) is blocked by risk aversion",
                "The best joint outcome requires mutual trust (p > %.0f%%) but ($a0Safe, $a1Safe) is individually safer. Classic stag hunt: without pre-commitment, both may settle for the inferior but safer equilibrium.".format(pThreshold * 100),
                Finding.Severity.CRITICAL
            )
        }

        findings += Finding(
            "Minimum trust threshold: %.0f%%".format(pThreshold * 100),
            "If $n0 believes $n1 will coordinate with probability > %.0f%%, coordinating is rational. If beliefs are below this, the safe option dominates. Building beliefs above this threshold is the key intervention.".format(pThreshold * 100),
            Finding.Severity.NOTE
        )

        val recs = mutableListOf<RecommendedAction>()

        if (payoffDominant) {
            recs += RecommendedAction(p0, a0Coord,
                "Coordinate toward the payoff-dominant equilibrium ($a0Coord, $a1Coord = %.2f). First build confidence that $n1 will cooperate (p > %.0f%%).".format(a, pThreshold * 100),
                contingency = "If $n1 shows ambiguity, fall back to $a0Safe (%.2f vs worst case %.2f).".format(d, b),
                lens = RecommendedAction.Lens.RELATIONSHIP)
            recs += RecommendedAction(p1, a1Coord,
                "Mirror coordination intent. $n1 coordinating is individually rational if they believe $n0 will do the same with p > %.0f%%.".format(pThreshold * 100),
                lens = RecommendedAction.Lens.RELATIONSHIP)
        } else {
            recs += RecommendedAction(p0, a0Safe,
                "Without strong trust signals, the safe option ($a0Safe) is risk-dominant. Costly coordination on ($a0Coord, $a1Coord) is only rational if opponent is reliably cooperative.",
                lens = RecommendedAction.Lens.RISK_AVERSE)
        }

        recs += RecommendedAction(null, "Use pre-commitment or cheap talk",
            "Public commitment, explicit communication, or low-cost signalling raises mutual beliefs above the %.0f%% threshold required for coordination. Even cheap talk can shift equilibrium selection in coordination games.".format(pThreshold * 100),
            lens = RecommendedAction.Lens.LONG_TERM)

        // Cooper et al. (1990, 1994) cheap talk and outside option findings
        findings += Finding(
            "One-way cheap talk: coordination jumps from 0% to 53% (Cooper et al. 1994)",
            "Experimental evidence: without communication, most player pairs end up at the risk-dominant (Rabbit, Rabbit) equilibrium (97% in Cooper et al. 1994 baseline). With one player allowed to pre-commit via cheap talk, efficient coordination rose to 53%. Two-way cheap talk raised it further to 91% — stronger than one-way because both parties can confirm intent.",
            Finding.Severity.NOTE
        )

        findings += Finding(
            "Outside option effect: if opt-out payoff > risk-dominant payoff, coordination rises",
            "Cooper et al. (1994): when Player 1 could opt out at a payoff above the (Rabbit, Rabbit) equilibrium, Player 1 rejecting the option signalled strong coordination intent — driving efficient (Stag, Stag) outcomes in 77% of cases. If Player 1's opt-out payoff < (Rabbit, Rabbit), partners revert to safe equilibrium.",
            Finding.Severity.NOTE
        )

        // Belloc et al. (2019) time pressure finding
        findings += Finding(
            "Time pressure nudges coordination: 63% choose stag under pressure vs 52% without (Belloc et al. 2019)",
            "Belloc et al. (2019): when players face time-pressure constraints, the rate choosing the efficient coordinated action rises from ~52% to ~63%. Interpretation: time pressure suppresses deliberative second-guessing and activates intuitive cooperation heuristics. Implication — structured deadlines or urgency framing can serve as a coordination nudge.",
            Finding.Severity.INFO
        )

        recs += RecommendedAction(null, "Set a deadline to activate coordination heuristic",
            "Time pressure raises cooperation from ~52% to ~63% (Belloc et al. 2019). If both players genuinely prefer the efficient outcome, setting a credible short decision window can overcome coordination failure without costly enforcement.",
            lens = RecommendedAction.Lens.LONG_TERM)

        return AnalysisResult(
            moduleId = id, moduleName = name,
            applicability = canAnalyze(model),
            findings = findings, recommendedActions = recs,
            assumptions = listOf(
                Assumption("Actions ordered: index 0 = coordinate (hunt stag), index 1 = safe option (hunt hare)."),
                Assumption("Risk dominance calculated assuming 50/50 prior over opponent's action."),
                Assumption("Threshold p* from indifference condition: p*a + (1−p*)b = p*c + (1−p*)d → p* = (d−b)/(a−b−c+d)."),
                Assumption("Cooper et al. baselines assume symmetric payoff Stag Hunt; results transfer when payoff-dominant NE is unique and risk-dominant differs."),
                Assumption("Belloc et al. (2019) time-pressure finding: magnitude may differ if stakes differ from experimental design.")
            ),
            calculations = mapOf("a" to a, "b" to b, "c" to c, "d" to d, "pThreshold" to pThreshold, "payoffDominant" to payoffDominant, "coordinationIsRiskDominant" to coordinationIsRiskDominant),
            confidence = if (ScenarioClassifier.detectSH(model) > 0.5) 0.85 else 0.40,
            visualizations = emptyList(),
            explanation = "Stag Hunt / coordination game. Two Nash equilibria: ($a0Coord, $a1Coord) is payoff-dominant, ($a0Safe, $a1Safe) is risk-dominant when trust is below %.0f%%. Cheap talk (Cooper et al. 1994) and time pressure (Belloc et al. 2019) are the most evidence-based coordination nudges.".format(pThreshold * 100)
        )
    }
}
