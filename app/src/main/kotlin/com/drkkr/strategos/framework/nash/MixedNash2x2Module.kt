package com.drkkr.strategos.framework.nash

import com.drkkr.strategos.domain.framework.*
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.framework.MatrixSupport

/**
 * Closed-form mixed Nash for 2-player, 2-strategy games. For general games,
 * a future module will use support enumeration or Lemke–Howson.
 */
class MixedNash2x2Module : FrameworkModule {
    override val id = "mixed-nash-2x2"
    override val name = "Mixed Nash (2×2)"
    override val version = "1.0"
    override val category = "Strategic form"

    override fun canAnalyze(model: Scenario): Applicability {
        val sf = MatrixSupport.build(model)
        if (sf == null || sf.players.size != 2) return Applicability(0.0, listOf("Requires exactly 2 players"))
        val sizes = sf.strategies.values.map { it.size }
        return if (sizes.all { it == 2 }) Applicability(0.9, listOf("Closed-form indifference solution available."))
        else Applicability(0.0, listOf("Only 2×2 games handled in this version"))
    }

    override fun analyze(model: Scenario): AnalysisResult {
        val sf = MatrixSupport.buildOrThrow(model)
        val u = sf.utility
        // Rows: P1 strategy 0/1; Cols: P2 strategy 0/1. Flat index = row*2 + col.
        val a = u[0][0]; val b = u[0][1]; val c = u[0][2]; val d = u[0][3]
        val e = u[1][0]; val f = u[1][1]; val g = u[1][2]; val h = u[1][3]
        // P1 mixes with prob p on strat 0; P2 mixes with prob q on strat 0.
        // Indifference: q*a + (1-q)*b = q*c + (1-q)*d
        //              => q(a - c - b + d) = d - b
        val denomQ = a - c - b + d
        val denomP = e - f - g + h
        val q = if (denomQ != 0.0) (d - b) / denomQ else Double.NaN
        val p = if (denomP != 0.0) (h - g) / denomP else Double.NaN

        val hasMixed = p in 0.0..1.0 && q in 0.0..1.0
        val n0 = sf.players[0].let { nameOf(model, it) }
        val n1 = sf.players[1].let { nameOf(model, it) }
        val l0 = labelOfIdx(model, sf.players[0], 0)
        val l1 = labelOfIdx(model, sf.players[1], 0)

        val findings = mutableListOf<Finding>()

        if (hasMixed) {
            findings += Finding(
                "Mixed Nash equilibrium: $n0 plays $l0 with p=%.3f, $n1 plays $l1 with q=%.3f".format(p, q),
                "Each player randomizes to keep the opponent indifferent between their strategies.",
                Finding.Severity.INFO
            )

            findings += Finding(
                "Equilibrium in beliefs (correct interpretation of MSE)",
                "A mixed Nash equilibrium is best understood as an equilibrium in BELIEFS rather than literal randomization: $n0 plays $l0 exactly p=%.3f of the time because $n1's belief about this frequency makes $n1 indifferent — and vice versa. Neither player needs an internal randomisation device; the equilibrium is sustained by correct mutual beliefs about each other's frequencies. (Spaniel 2011 / Aumann 1974)".format(p),
                Finding.Severity.NOTE
            )

            // Win-Stay Lose-Shift heuristic (Camerer 2003)
            findings += Finding(
                "Win-Stay, Lose-Shift heuristic predicts similar frequencies (Camerer 2003)",
                "Behavioral players in zero-sum / mixed-strategy games tend to follow Win-Stay, Lose-Shift (WSLS): repeat the action that yielded a win last round; switch if it yielded a loss. Over many rounds, WSLS converges toward the Nash mixing frequencies — not because players compute the equilibrium, but because reinforcement learning drives them toward it. Camerer (2003) finds this heuristic describes human play better than deliberate mixed-strategy optimization.\n• Implication: in repeated play, WSLS players reach the same long-run frequency distribution as Nash — treat (p=%.3f, q=%.3f) as the attractor even for behaviorally motivated players.".format(p, q),
                Finding.Severity.NOTE
            )

            findings += Finding(
                "Penalty-kick analogy (Spaniel 2011): partial derivative reveals directional play",
                "When strike probability varies with skill (parameter λ), ∂q/∂λ and ∂p/∂λ have opposite signs: as a striker's probability of scoring Left increases, the rational goalie dives Left more — so the striker should kick RIGHT more. This first-partial-derivative reasoning captures one degree of iterated knowledge and explains directional strategy adjustments without full equilibrium computation.",
                Finding.Severity.INFO
            )
        } else {
            findings += Finding("No mixed Nash in interior", "Indifference has no valid interior solution — game has only pure Nash equilibria or is degenerate.", Finding.Severity.NOTE)
        }

        val recs = if (hasMixed) listOf(
            RecommendedAction(sf.players[0], "Randomize with p=${"%.3f".format(p)} on $l0",
                "Keeps opponent indifferent between their strategies. In repeated play, Win-Stay Lose-Shift heuristic (Camerer 2003) will converge to this frequency without deliberate randomization."),
            RecommendedAction(sf.players[1], "Randomize with q=${"%.3f".format(q)} on $l1",
                "Keeps $n0 indifferent. If opponent uses WSLS heuristic, they will naturally converge to this mixing rate over repeated rounds.")
        ) else emptyList()

        return AnalysisResult(
            id, name, canAnalyze(model), findings, recs,
            assumptions = listOf(
                Assumption("Interior mixed equilibrium exists iff denominators non-zero and probabilities in [0,1]."),
                Assumption("Equilibrium-in-beliefs interpretation: MSE reflects correct mutual frequency beliefs, not literal randomization."),
                Assumption("Win-Stay Lose-Shift baseline: Camerer (2003) — behavioral convergence result holds in zero-sum and mixed-motive games with repeated play.")
            ),
            calculations = mapOf("p" to p, "q" to q),
            confidence = if (hasMixed) 0.8 else 0.3,
            visualizations = listOf(VizSpec(VizSpec.Kind.TEXT, "Mixed equilibrium", mapOf("p" to p, "q" to q))),
            explanation = if (hasMixed) "Interior mixed Nash equilibrium (p,q)=(%.3f, %.3f). Equilibrium in beliefs: each player's mixing rate makes the opponent indifferent. Behavioral players reach same frequencies via Win-Stay Lose-Shift (Camerer 2003).".format(p, q)
            else "No interior mixed equilibrium — pure equilibria dominate or game is degenerate."
        )
    }

    private fun nameOf(s: Scenario, sid: String) = s.stakeholders.first { it.id == sid }.name
    private fun labelOfIdx(s: Scenario, sid: String, idx: Int): String = s.actionsFor(sid)[idx].label
}
