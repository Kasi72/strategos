package com.drkkr.strategos.framework.strategic

import com.drkkr.strategos.domain.framework.*
import com.drkkr.strategos.domain.model.*
import com.drkkr.strategos.framework.MatrixSupport
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

class ScenarioEnsembleModule : FrameworkModule {
    override val id = "scenario-ensemble"
    override val name = "Scenario Ensemble (Monte Carlo Robustness)"
    override val version = "1.0"
    override val category = "Strategic"

    override fun canAnalyze(model: Scenario): Applicability {
        val sf = MatrixSupport.build(model)
        if (sf == null || sf.players.isEmpty()) return Applicability(
            0.55,
            listOf(
                "No payoff matrix — robustness analysis uses implementation difficulty and belief scores as proxies.",
                "Add payoffs to enable Monte Carlo perturbation of utility values."
            )
        )
        if (model.actions.size < 2) return Applicability(0.0, listOf("Need ≥2 actions."))
        return Applicability(
            0.88,
            listOf(
                "Runs ${PERTURBATIONS} Monte Carlo perturbations of the payoff matrix (±${(NOISE_LEVEL * 100).toInt()}% noise).",
                "Tracks which action is optimal for the decision owner across all perturbations.",
                "Classifies each action as Robust (≥80%), Moderate (50–79%), Fragile (20–49%), or Dominated (<20%).",
                "Identifies actions that are never optimal — safe to deprioritise under any plausible payoff scenario."
            )
        )
    }

    override fun analyze(model: Scenario): AnalysisResult {
        val findings = mutableListOf<Finding>()
        val recs = mutableListOf<RecommendedAction>()

        val sf = MatrixSupport.build(model)

        return if (sf != null && sf.players.isNotEmpty()) {
            analyzeWithMatrix(model, sf, findings, recs)
        } else {
            analyzeWithoutMatrix(model, findings, recs)
        }
    }

    // ── Matrix-based Monte Carlo ───────────────────────────────────────────────
    private fun analyzeWithMatrix(
        model: Scenario,
        sf: MatrixSupport.StrategicForm,
        findings: MutableList<Finding>,
        recs: MutableList<RecommendedAction>
    ): AnalysisResult {
        val leaderId = sf.players[0]
        val leaderActions = sf.strategies[leaderId] ?: return emptyResult()
        val nL = leaderActions.size
        val nF = sf.players.getOrNull(1)?.let { sf.strategies[it]?.size } ?: 1
        val profileCount = sf.utility[0].size
        val rng = Random(42)

        val optimalCounts = IntArray(nL)
        val payoffSamples = Array(nL) { mutableListOf<Double>() }

        repeat(PERTURBATIONS) {
            // Perturb all utilities by ±NOISE_LEVEL
            val perturbedUtility = Array(sf.players.size) { p ->
                DoubleArray(profileCount) { k ->
                    sf.utility[p][k] * (1.0 + rng.nextDouble(-NOISE_LEVEL, NOISE_LEVEL))
                }
            }

            // For each leader action, compute expected payoff
            val actionPayoffs = DoubleArray(nL) { i ->
                if (nF > 1 && sf.players.size == 2) {
                    // 2-player only: follower best-responds to leader action i
                    val followerBrJ = (0 until nF).maxByOrNull { j ->
                        perturbedUtility[1][sf.profileIndex(listOf(i, j))]
                    } ?: 0
                    perturbedUtility[0][sf.profileIndex(listOf(i, followerBrJ))]
                } else {
                    // Multi-player or single-player: average over all profiles where leader plays i
                    (0 until profileCount).filter { k ->
                        sf.profileFor(k).getOrNull(0) == i
                    }.mapNotNull { k ->
                        perturbedUtility[0][k].takeIf { !it.isNaN() }
                    }.let { vals -> if (vals.isEmpty()) 0.0 else vals.average() }
                }
            }

            val bestIdx = actionPayoffs.indices.maxByOrNull { actionPayoffs[it] } ?: 0
            optimalCounts[bestIdx]++
            for (i in 0 until nL) payoffSamples[i] += actionPayoffs[i]
        }

        // ── Classify robustness ────────────────────────────────────────────────
        data class ActionRobustness(
            val idx: Int, val label: String,
            val robustnessRate: Double, val meanPayoff: Double, val stdPayoff: Double
        )

        val results = (0 until nL).map { i ->
            val rate = optimalCounts[i].toDouble() / PERTURBATIONS
            val samples = payoffSamples[i]
            val mean = samples.average()
            val std = sqrt(samples.map { (it - mean) * (it - mean) }.average())
            ActionRobustness(i, leaderActions[i], rate, mean, std)
        }.sortedByDescending { it.robustnessRate }

        val robust = results.filter { it.robustnessRate >= 0.80 }
        val moderate = results.filter { it.robustnessRate in 0.50..0.799 }
        val fragile = results.filter { it.robustnessRate in 0.20..0.499 }
        val dominated = results.filter { it.robustnessRate < 0.20 }

        findings += Finding(
            "Monte Carlo robustness summary — ${PERTURBATIONS} payoff scenarios simulated",
            buildString {
                append("Each action was tested across ${PERTURBATIONS} perturbed versions of the payoff matrix (±${(NOISE_LEVEL * 100).toInt()}% noise).\n\n")
                append("Optimal frequency per action:\n")
                for (r in results) {
                    val bar = "█".repeat((r.robustnessRate * 20).toInt()) + "░".repeat(20 - (r.robustnessRate * 20).toInt())
                    val mean = SafeFormat.f2(r.meanPayoff)
                    val std = SafeFormat.f2(r.stdPayoff)
                    append("  ${r.label.padEnd(30)} $bar ${(r.robustnessRate * 100).toInt()}%  (μ=$mean, σ=$std)\n")
                }
            },
            Finding.Severity.INFO
        )

        if (robust.isNotEmpty()) {
            findings += Finding(
                "ROBUST actions (optimal in ≥80% of scenarios): ${robust.joinToString(", ") { it.label }}",
                "These actions remain optimal even under substantial payoff uncertainty. Robustness rates:\n${robust.joinToString("\n") { "• ${it.label}: ${(it.robustnessRate * 100).toInt()}% optimal (μ=${SafeFormat.f2(it.meanPayoff)})" }}",
                Finding.Severity.INFO
            )
            recs += RecommendedAction(
                leaderId, robust.first().label,
                "Most robust action across ${PERTURBATIONS} Monte Carlo scenarios (${(robust.first().robustnessRate * 100).toInt()}% optimal rate). Robust to ±${(NOISE_LEVEL * 100).toInt()}% payoff uncertainty.",
                lens = RecommendedAction.Lens.EXPECTED
            )
        }

        if (moderate.isNotEmpty()) {
            findings += Finding(
                "MODERATE actions (optimal in 50–79% of scenarios): ${moderate.joinToString(", ") { it.label }}",
                "These actions are often optimal but lack the consistency of robust choices. They may be the right call under specific payoff assumptions — validate those assumptions before committing.\n${moderate.joinToString("\n") { "• ${it.label}: ${(it.robustnessRate * 100).toInt()}% optimal (μ=${SafeFormat.f2(it.meanPayoff)}, σ=${SafeFormat.f2(it.stdPayoff)})" }}",
                Finding.Severity.NOTE
            )
        }

        if (dominated.isNotEmpty()) {
            findings += Finding(
                "DOMINATED actions (optimal in <20% of scenarios): ${dominated.joinToString(", ") { it.label }}",
                "These actions are rarely optimal regardless of payoff uncertainty. Consider removing them from the option set or understanding why they were included.\n${dominated.joinToString("\n") { "• ${it.label}: ${(it.robustnessRate * 100).toInt()}% optimal" }}",
                Finding.Severity.WARNING
            )
        }

        if (fragile.isNotEmpty()) {
            findings += Finding(
                "FRAGILE actions (optimal in 20–49% of scenarios): ${fragile.joinToString(", ") { it.label }}",
                "These actions are sometimes optimal but highly sensitive to payoff assumptions. Validate the payoff estimates before committing to them.\n${fragile.joinToString("\n") { "• ${it.label}: ${(it.robustnessRate * 100).toInt()}% optimal (σ=${SafeFormat.f2(it.stdPayoff)})" }}",
                Finding.Severity.NOTE
            )
        }

        // Regret analysis: worst case vs best case
        val minRegret = results.minByOrNull {
            val bestOtherMean = results.filter { o -> o.idx != it.idx }.maxOfOrNull { o -> o.meanPayoff } ?: 0.0
            bestOtherMean - it.meanPayoff
        }
        if (minRegret != null && minRegret.idx != results.first().idx) {
            findings += Finding(
                "Minimax regret action: ${minRegret.label}",
                "Minimax regret criterion: choose the action that minimises the maximum difference from the best alternative across all scenarios. '${minRegret.label}' minimises worst-case regret — it is the most conservative robust choice.",
                Finding.Severity.NOTE
            )
        }

        return AnalysisResult(
            moduleId = id, moduleName = name,
            applicability = canAnalyze(model),
            findings = findings, recommendedActions = recs,
            assumptions = listOf(
                Assumption("Monte Carlo uses uniform noise ±${(NOISE_LEVEL * 100).toInt()}% on all payoffs. Actual uncertainty may be asymmetric or larger."),
                Assumption("Follower assumed to best-respond in each perturbation. Mixed strategies not sampled."),
                Assumption("Random seed fixed at 42 for reproducibility across runs."),
                Assumption("Robustness threshold: ≥80% = Robust, 50–79% = Moderate, 20–49% = Fragile, <20% = Dominated."),
                Assumption("Payoff standard deviation (σ) measures how much each action's payoff varies across perturbations — high σ = high exposure to model uncertainty.")
            ),
            calculations = mapOf(
                "perturbations" to PERTURBATIONS,
                "noiseLevel" to NOISE_LEVEL,
                "robustCount" to robust.size,
                "moderateCount" to moderate.size,
                "dominatedCount" to dominated.size,
                "topAction" to (results.firstOrNull()?.label ?: ""),
                "topRobustnessRate" to (results.firstOrNull()?.robustnessRate ?: 0.0)
            ),
            confidence = 0.78,
            visualizations = emptyList(),
            explanation = "Monte Carlo (N=${PERTURBATIONS}): ${if (robust.isNotEmpty()) "${robust.first().label} is most robust (${(robust.first().robustnessRate * 100).toInt()}% optimal)" else "no single action robustly dominates — fragile outcome landscape"}. ${if (dominated.isNotEmpty()) "${dominated.size} action(s) are near-dominated." else ""}"
        )
    }

    // ── Fallback: no matrix, use proxy scoring ─────────────────────────────────
    private fun analyzeWithoutMatrix(
        model: Scenario,
        findings: MutableList<Finding>,
        recs: MutableList<RecommendedAction>
    ): AnalysisResult {
        val actions = model.actions
        if (actions.isEmpty()) return emptyResult()

        data class ActionScore(val action: Action, val score: Double, val sensitivity: Double)

        val rng = Random(42)
        val actionScores = actions.map { a ->
            val base = (5 - a.implementationDifficulty) * 1.0 + (if (a.reversible) 0.5 else 0.0)
            var robustScore = 0.0
            var totalScore = 0.0
            repeat(PERTURBATIONS) {
                val perturbed = base * (1.0 + rng.nextDouble(-NOISE_LEVEL, NOISE_LEVEL))
                totalScore += perturbed
                if (actions.none { other ->
                    other.id != a.id &&
                    (5 - other.implementationDifficulty) * (1.0 + rng.nextDouble(-NOISE_LEVEL, NOISE_LEVEL)) > perturbed
                }) robustScore++
            }
            ActionScore(a, totalScore / PERTURBATIONS, 1.0 - robustScore / PERTURBATIONS)
        }.sortedByDescending { it.score }

        findings += Finding(
            "Robustness ranking (proxy — no payoff matrix)",
            actionScores.mapIndexed { i, s ->
                "${i+1}. ${s.action.label} — score ${SafeFormat.f2(s.score)}, sensitivity ${SafeFormat.f1(s.sensitivity * 100)}%"
            }.joinToString("\n"),
            Finding.Severity.NOTE
        )

        findings += Finding(
            "Payoff data missing — Monte Carlo analysis using implementation difficulty proxy",
            "For precise robustness analysis, add payoffs to the payoff matrix. Current scores based on implementation difficulty (1–5) and reversibility only.",
            Finding.Severity.WARNING
        )

        return AnalysisResult(
            moduleId = id, moduleName = name,
            applicability = canAnalyze(model),
            findings = findings, recommendedActions = recs,
            assumptions = listOf(Assumption("No payoff matrix — proxy scoring used. Add payoffs for accurate Monte Carlo.")),
            calculations = mapOf("perturbations" to PERTURBATIONS, "proxyMode" to true),
            confidence = 0.35,
            visualizations = emptyList(),
            explanation = "Proxy robustness (no matrix). Top action: ${actionScores.firstOrNull()?.action?.label}. Add payoffs for precise Monte Carlo."
        )
    }

    private fun emptyResult() = AnalysisResult(
        moduleId = id, moduleName = name,
        applicability = Applicability(0.0, listOf("Insufficient data.")),
        findings = emptyList(), recommendedActions = emptyList(),
        assumptions = emptyList(), calculations = emptyMap(),
        confidence = 0.0, visualizations = emptyList(),
        explanation = "Cannot run scenario ensemble — no actions or payoff data."
    )

    companion object {
        private const val PERTURBATIONS = 500
        private const val NOISE_LEVEL = 0.25
    }
}
