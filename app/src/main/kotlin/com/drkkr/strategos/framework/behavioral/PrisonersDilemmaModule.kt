package com.drkkr.strategos.framework.behavioral

import com.drkkr.strategos.domain.framework.*
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.framework.MatrixSupport
import kotlin.math.abs

class PrisonersDilemmaModule : FrameworkModule {
    override val id = "prisoners-dilemma"
    override val name = "Prisoner's Dilemma"
    override val version = "1.0"
    override val category = "Behavioral"

    override fun canAnalyze(model: Scenario): Applicability {
        val pdScore = ScenarioClassifier.detectPD(model)
        if (pdScore > 0.5) return Applicability(pdScore, listOf("Payoff matrix satisfies T>R>P>S — classic Prisoner's Dilemma structure."))
        val sf = MatrixSupport.build(model) ?: return Applicability(0.0, listOf("No payoff matrix available."))
        if (sf.players.size != 2) return Applicability(0.0, listOf("Prisoner's Dilemma requires exactly 2 players."))
        if (sf.strategies.values.any { it.size != 2 }) return Applicability(0.0, listOf("Requires exactly 2 actions per player."))
        return Applicability(0.35, listOf("2-player 2-action game — partial PD applicability. Payoff structure not strictly T>R>P>S."))
    }

    override fun analyze(model: Scenario): AnalysisResult {
        val sf = MatrixSupport.buildOrThrow(model)
        val p0 = sf.players[0]
        val p1 = sf.players[1]

        val r = sf.utility[0][sf.profileIndex(listOf(0, 0))]
        val s = sf.utility[0][sf.profileIndex(listOf(0, 1))]
        val t = sf.utility[0][sf.profileIndex(listOf(1, 0))]
        val p = sf.utility[0][sf.profileIndex(listOf(1, 1))]

        val isPD = t > r && r > p && p > s && 2 * r > t + s
        val cooperationGap = 2 * r - (t + s)
        val defectionTemptation = t - r
        val deltaThreshold = if (abs(t - p) > 1e-6) (t - r) / (t - p) else 1.0
        val canSustainCooperation = model.repeated && deltaThreshold < 0.9

        val n0 = model.stakeholders.first { it.id == p0 }.name
        val n1 = model.stakeholders.first { it.id == p1 }.name
        val a0Cooperate = model.actionsFor(p0).getOrNull(0)?.label ?: "Cooperate"
        val a0Defect = model.actionsFor(p0).getOrNull(1)?.label ?: "Defect"
        val a1Cooperate = model.actionsFor(p1).getOrNull(0)?.label ?: "Cooperate"
        val a1Defect = model.actionsFor(p1).getOrNull(1)?.label ?: "Defect"

        val findings = mutableListOf<Finding>()

        if (isPD) {
            findings += Finding(
                "Prisoner's Dilemma structure confirmed",
                "T(%.2f) > R(%.2f) > P(%.2f) > S(%.2f). Defection is dominant even though mutual cooperation is Pareto superior. Social welfare gap: %.2f.".format(t, r, p, s, cooperationGap),
                Finding.Severity.CRITICAL
            )
        } else {
            findings += Finding(
                "PD-like tension (partial structure)",
                "R=%.2f, T=%.2f, P=%.2f, S=%.2f. Strict PD conditions not met but cooperation-defection tension present.".format(r, t, p, s),
                Finding.Severity.NOTE
            )
        }

        findings += Finding(
            "Defection temptation: %.2f".format(defectionTemptation),
            "Unilateral deviation from cooperation gains %.2f. The higher this is, the harder cooperation is to sustain without binding commitments.".format(defectionTemptation),
            if (defectionTemptation > (r - p) * 0.5) Finding.Severity.WARNING else Finding.Severity.NOTE
        )

        if (model.repeated) {
            findings += if (canSustainCooperation)
                Finding("Folk theorem: cooperation sustainable via grim trigger",
                    "Minimum discount factor δ* = %.2f. Grim-trigger strategy: cooperate until first defection, then defect forever. Incentive-compatible when δ ≥ δ*. Grim trigger is the harshest credible punishment — eliminates all future gains from the relationship after a single defection.".format(deltaThreshold),
                    Finding.Severity.INFO)
            else
                Finding("Cooperation threshold is demanding",
                    "Requires discount factor δ ≥ %.2f. Verify that both parties value the relationship sufficiently long-term.".format(deltaThreshold),
                    Finding.Severity.WARNING)

            // Tit-for-Tat analysis (Axelrod 1984)
            // TfT δ* comes from the binding "defect once" deviation: T,S,T,S,... vs cooperation R/(1-δ).
            // Indifference: R(1+δ) = T + δS → δ(R-S) = T-R → δ* = (T-R)/(R-S).
            // This is HIGHER than grim trigger's (T-R)/(T-P) whenever T+S > R+P (typical in PD).
            val tftDeltaThreshold = if (abs(r - s) > 1e-6) (t - r) / (r - s) else 1.0
            findings += Finding(
                "Tit-for-Tat: forgiving alternative to grim trigger (Axelrod 1984)",
                "Tit-for-Tat strategy: cooperate on round 1, then copy opponent's last action each subsequent round.\n• TfT δ* = (T−R)/(R−S) = %.2f — typically HIGHER than grim trigger's δ* = (T−R)/(T−P) = %.2f, because \"defect once\" (yielding T,S,T,S,...) is the binding deviation and is harder to deter than \"always defect\".\n• Key difference: Tit-for-Tat allows recovery from a single defection (opponent cooperates again → you cooperate again). Grim trigger never forgives.\n• Tit-for-Tat is more forgiving and robust to trembles or mistakes. Grim trigger is simpler and maximally punishing.\n• Axelrod (1984) tournament: Tit-for-Tat won among strategies submitted by game theorists and computer scientists — beating all more complex strategies.".format(tftDeltaThreshold, deltaThreshold),
                Finding.Severity.INFO
            )

            if (canSustainCooperation) {
                findings += Finding(
                    "Backward induction in finitely repeated PD: cooperation unravels",
                    "If both players know the exact number of rounds, backward induction from the final round collapses cooperation: defect in round T (last), both know this so defect in T-1, etc. Result: defect every round even with high δ. Cooperation in finitely repeated PD requires uncertainty about the last round or reputation effects.",
                    Finding.Severity.WARNING
                )
            }
        } else {
            findings += Finding(
                "One-shot game: Nash equilibrium is ($a0Defect, $a1Defect)",
                "Without repetition, defection strictly dominates for both players. External enforcement (contract, regulator, escrow) required to achieve cooperation.",
                Finding.Severity.WARNING
            )
        }

        val recs = mutableListOf<RecommendedAction>()
        if (canSustainCooperation) {
            recs += RecommendedAction(p0, a0Cooperate,
                "Repeated game enables cooperation. Grim trigger: cooperate while $n1 cooperates, defect permanently after any deviation. δ* = %.2f. Alternatively, Tit-for-Tat: cooperate round 1, mirror opponent thereafter — allows recovery from mistakes.".format(deltaThreshold),
                contingency = "Grim trigger: if $n1 defects, switch to $a0Defect permanently. Tit-for-Tat: switch to $a0Defect next round, return to $a0Cooperate if $n1 returns to cooperate.",
                lens = RecommendedAction.Lens.LONG_TERM)
            recs += RecommendedAction(p1, a1Cooperate,
                "Same logic applies symmetrically. Tit-for-Tat is recommended when mistakes are possible — its forgiveness allows recovery without permanent breakdown. Both players prefer mutual cooperation (%.2f each) over mutual defection (%.2f each).".format(r, p),
                contingency = "If $n0 defects, switch to $a1Defect next round; return to $a1Cooperate if they return.",
                lens = RecommendedAction.Lens.LONG_TERM)
        } else {
            recs += RecommendedAction(p0, a0Defect,
                "One-shot or low-δ game — defection is dominant strategy. Negotiate binding commitment before cooperating.",
                lens = RecommendedAction.Lens.LOW_REGRET)
            recs += RecommendedAction(null, "Introduce commitment device",
                "Escrow, notarised contract, or regulatory oversight can make cooperation incentive-compatible by eliminating the defection payoff (%.2f advantage).".format(defectionTemptation),
                lens = RecommendedAction.Lens.ETHICAL_LEGAL)
        }

        return AnalysisResult(
            moduleId = id, moduleName = name,
            applicability = canAnalyze(model),
            findings = findings, recommendedActions = recs,
            assumptions = listOf(
                Assumption("Actions ordered: index 0 = Cooperate, index 1 = Defect per player."),
                Assumption("Cooperation threshold formula: δ* = (T−R)/(T−P) from grim-trigger equilibrium."),
                if (model.repeated) Assumption("Infinitely repeated game with constant discount factor.") else Assumption("One-shot interaction.")
            ),
            calculations = mapOf("R" to r, "S" to s, "T" to t, "P" to p, "cooperationGap" to cooperationGap, "deltaThreshold" to deltaThreshold, "isPD" to isPD),
            confidence = if (isPD) 0.90 else 0.52,
            visualizations = emptyList(),
            explanation = if (isPD) "Classic Prisoner's Dilemma confirmed. (Defect, Defect) is the dominant strategy equilibrium — Pareto-inferior to (Cooperate, Cooperate). Cooperation requires repetition, punishment, or commitment."
            else "PD-like conflict without strict dominance. Cooperation is fragile without enforcement."
        )
    }
}
