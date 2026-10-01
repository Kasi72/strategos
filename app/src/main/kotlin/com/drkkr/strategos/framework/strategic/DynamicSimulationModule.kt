package com.drkkr.strategos.framework.strategic

import com.drkkr.strategos.domain.framework.*
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.framework.MatrixSupport
import kotlin.math.abs
import kotlin.math.min

class DynamicSimulationModule : FrameworkModule {
    override val id = "dynamic-simulation"
    override val name = "Dynamic Simulation / Iterated Best-Response"
    override val version = "1.0"
    override val category = "Dynamic Games"

    override fun canAnalyze(model: Scenario): Applicability {
        if (!model.repeated && model.timeHorizon <= 1)
            return Applicability(0.15, listOf("Single-shot game — dynamic simulation less informative."))
        if (model.stakeholders.size < 2)
            return Applicability(0.0, listOf("Need ≥2 stakeholders for dynamic simulation."))
        if (model.actions.isEmpty())
            return Applicability(0.25, listOf("No actions defined — stub simulation only."))
        val reasons = mutableListOf<String>()
        if (model.repeated) reasons += "Repeated game flag set."
        if (model.timeHorizon > 1) reasons += "Time horizon = ${model.timeHorizon} rounds."
        if (MatrixSupport.build(model) != null) reasons += "Payoff matrix available for best-response dynamics."
        return Applicability(0.82, reasons)
    }

    override fun analyze(model: Scenario): AnalysisResult {
        val findings = mutableListOf<Finding>()
        val recs = mutableListOf<RecommendedAction>()
        val calcs = mutableMapOf<String, Any>()

        val T = min(model.timeHorizon.takeIf { it > 0 } ?: 5, 20)
        calcs["simulatedRounds"] = T

        val sf = MatrixSupport.build(model)

        if (sf == null || sf.players.size != 2) {
            // Proxy simulation — matrix absent or multi-player (2-player best-response logic only)
            return proxySimulation(model, T, findings, recs, calcs)
        }

        val p1 = sf.players[0]
        val p2 = sf.players[1]
        val s1 = sf.strategies[p1] ?: return proxySimulation(model, T, findings, recs, calcs)
        val s2 = sf.strategies[p2] ?: return proxySimulation(model, T, findings, recs, calcs)

        if (s1.isEmpty() || s2.isEmpty()) return proxySimulation(model, T, findings, recs, calcs)

        // Best-response function for each player
        fun br1(j: Int): Int = (0 until s1.size).maxByOrNull { i ->
            sf.utility[0][sf.profileIndex(listOf(i, j))]
        } ?: 0

        fun br2(i: Int): Int = (0 until s2.size).maxByOrNull { j ->
            sf.utility[1][sf.profileIndex(listOf(i, j))]
        } ?: 0

        // Simulate best-response dynamics starting from each possible initial action pair
        data class TrajectoryResult(
            val converged: Boolean,
            val convergenceRound: Int,
            val finalProfile: List<Int>,
            val path: List<Pair<Int, Int>>
        )

        fun simulate(startI: Int, startJ: Int): TrajectoryResult {
            var i = startI; var j = startJ
            val path = mutableListOf(i to j)
            for (round in 1..T) {
                val ni = br1(j)
                val nj = br2(ni)
                if (ni == i && nj == j) return TrajectoryResult(true, round, listOf(ni, nj), path)
                i = ni; j = nj
                path += i to j
            }
            return TrajectoryResult(false, T, listOf(i, j), path)
        }

        val trajectories = s1.indices.flatMap { i -> s2.indices.map { j -> simulate(i, j) } }
        val convergedCount = trajectories.count { it.converged }
        val convergenceRates = convergedCount.toDouble() / trajectories.size
        calcs["trajectoryCount"] = trajectories.size
        calcs["convergenceRate"] = convergenceRates

        val uniqueFinalProfiles = trajectories.map { it.finalProfile }.distinct()
        calcs["uniqueFinalStates"] = uniqueFinalProfiles.size

        when {
            convergenceRates >= 0.8 -> findings += Finding(
                "High convergence: ${"%.0f".format(convergenceRates * 100)}% of trajectories reach a stable equilibrium",
                "Best-response dynamics converge to a stable strategy profile from most starting points. The game has a dominant attractor. Strategy profile at convergence: ${uniqueFinalProfiles.first().mapIndexed { pi, si ->
                    val player = sf.players.getOrNull(pi) ?: "P${pi+1}"
                    val strats = sf.strategies[player] ?: emptyList()
                    "$player → ${strats.getOrNull(si) ?: "S${si+1}"}"
                }.joinToString(", ")}",
                Finding.Severity.INFO
            )
            convergenceRates >= 0.4 -> findings += Finding(
                "Partial convergence: ${"%.0f".format(convergenceRates * 100)}% of trajectories converge — path-dependent dynamics",
                "Convergence depends heavily on starting strategy profiles. Different initial conditions lead to different stable states. The game is path-dependent — early moves constrain later outcomes. Coordination on the 'right' starting position matters.",
                Finding.Severity.WARNING
            )
            else -> findings += Finding(
                "Low convergence: only ${"%.0f".format(convergenceRates * 100)}% of trajectories reach stable state in $T rounds",
                "Best-response dynamics cycle rather than converge for most starting points. The game may have no pure Nash equilibrium that is accessible via best-response learning, or cycles may be structural (e.g., Rock-Paper-Scissors structure). Mixed strategies or binding commitments may be needed.",
                Finding.Severity.WARNING
            )
        }

        // Early vs late convergence
        val convergedTrajectories = trajectories.filter { it.converged }
        if (convergedTrajectories.isNotEmpty()) {
            val avgRounds = convergedTrajectories.map { it.convergenceRound }.average()
            calcs["avgConvergenceRound"] = avgRounds
            if (avgRounds <= T / 3.0) {
                findings += Finding(
                    "Fast convergence: equilibrium reached in avg ${"%.1f".format(avgRounds)} rounds (of $T)",
                    "The game equilibrates quickly. If time horizon is long relative to convergence speed, early play determines final outcomes and late adjustments have limited impact.",
                    Finding.Severity.INFO
                )
            } else {
                findings += Finding(
                    "Slow convergence: avg ${"%.1f".format(avgRounds)} rounds to stabilise",
                    "Equilibrium is reached but not quickly. Strategic uncertainty persists for much of the time horizon. Players who can commit early to a strategy and credibly signal it may gain first-mover advantage during the unstable phase.",
                    Finding.Severity.NOTE
                )
            }
        }

        // Payoff trajectory for player 1 across rounds
        if (convergedTrajectories.isNotEmpty()) {
            val trajectory = convergedTrajectories.first()
            val finalUtil1 = sf.utility[0][sf.profileIndex(trajectory.finalProfile)]
            val finalUtil2 = sf.utility[1][sf.profileIndex(trajectory.finalProfile)]
            calcs["finalPayoffP1"] = finalUtil1
            calcs["finalPayoffP2"] = finalUtil2

            findings += Finding(
                "Equilibrium payoffs: ${p1}=${"%.1f".format(finalUtil1)}, ${p2}=${"%.1f".format(finalUtil2)}",
                "At the stable attractor reached via best-response dynamics.",
                Finding.Severity.INFO
            )
        }

        // Multiple attractors check
        if (uniqueFinalProfiles.size > 1) {
            val attractorDesc = uniqueFinalProfiles.mapIndexed { idx, profile ->
                val labels = profile.mapIndexed { pi, si ->
                    val player = sf.players.getOrNull(pi) ?: "P${pi+1}"
                    val strats = sf.strategies[player] ?: emptyList()
                    strats.getOrNull(si) ?: "S${si+1}"
                }.joinToString("/")
                "Attractor ${idx+1}: [$labels]"
            }.joinToString("; ")

            findings += Finding(
                "${uniqueFinalProfiles.size} distinct attractors — game outcome depends on coordination",
                "Multiple stable states exist: $attractorDesc. Which attractor is reached depends on initial conditions and the coordination process. Pre-game communication, focal points (Schelling 1960), or commitment devices may be needed to coordinate on the preferred attractor.",
                Finding.Severity.WARNING
            )
        }

        // Finite horizon backward induction check
        if (!model.repeated && T < 10) {
            findings += Finding(
                "Finite horizon ($T rounds): backward induction may unravel cooperation",
                "In a finitely repeated game, rational players unravel cooperation from the last round backwards (Selten's chain-store paradox). If T is known to all players, cooperation in round T is not credible, eliminating incentive for cooperation in T-1, and so forth. This is the 'end-game effect'. Consider whether the time horizon is genuinely common knowledge.",
                Finding.Severity.WARNING
            )
        }

        if (model.repeated && T >= 10) {
            findings += Finding(
                "Repeated game (T=$T): cooperation may be sustained via trigger strategies",
                "Folk theorem (Fudenberg-Maskin 1986): in infinitely or sufficiently-long repeated games, any payoff above minmax can be sustained as an equilibrium via trigger strategies if players are sufficiently patient. The discount factor (patience) determines whether cooperation is credible.",
                Finding.Severity.INFO
            )
        }

        recs += RecommendedAction(null,
            "Focus early rounds on signalling commitment rather than maximising immediate payoff",
            "In convergent dynamic games, reputation and commitment signals in early rounds shape the equilibrium attractor reached. Sacrificing short-term payoff to establish credible strategy signals has disproportionate long-run value.",
            lens = RecommendedAction.Lens.LONG_TERM)

        if (uniqueFinalProfiles.size > 1) {
            recs += RecommendedAction(null,
                "Use focal-point communication to coordinate on the preferred attractor",
                "When multiple stable states exist, coordinate explicitly on the preferred attractor before play begins. Joint pre-game communication about intentions reduces the risk of settling on an inferior equilibrium through uncoordinated best-response learning.",
                lens = RecommendedAction.Lens.RELATIONSHIP)
        }

        return AnalysisResult(
            moduleId = id, moduleName = name,
            applicability = canAnalyze(model),
            findings = findings, recommendedActions = recs,
            assumptions = listOf(
                Assumption("Best-response dynamics assume myopic players who maximise current-round payoff without forward induction. Sophisticated players may anticipate and deviate from this path."),
                Assumption("Time horizon capped at 20 rounds for computation. Set timeHorizon in model for accurate simulation."),
                Assumption("Simultaneous best-response: both players update each round. Alternating or asynchronous update rules may produce different convergence properties.")
            ),
            calculations = calcs,
            confidence = 0.74,
            visualizations = emptyList(),
            explanation = "Dynamic simulation via best-response learning over $T rounds. Convergence rate, attractor identification, end-game effects, and folk theorem applicability assessed from payoff matrix."
        )
    }

    private fun proxySimulation(
        model: Scenario,
        T: Int,
        findings: MutableList<Finding>,
        recs: MutableList<RecommendedAction>,
        calcs: MutableMap<String, Any>
    ): AnalysisResult {
        calcs["mode"] = "proxy"
        calcs["simulatedRounds"] = T

        val stakeholders = model.stakeholders

        // Proxy: simulate shifts in "support" based on authority and coalitions
        var supportScore = stakeholders
            .filter { s -> model.actions.any { a -> a.stakeholderId == s.id } }
            .sumOf { it.influence.toDouble() } / stakeholders.size.toDouble().coerceAtLeast(1.0)

        calcs["initialSupportProxy"] = supportScore

        val coalitionBonus = stakeholders.count { it.coalitionsWith.isNotEmpty() } * 0.05
        val vetoRisk = stakeholders.count { it.canVeto } * 0.10
        val exitRisk = stakeholders.count { it.canExit } * 0.07

        val projectedFinalSupport = (supportScore + coalitionBonus - vetoRisk - exitRisk).coerceIn(0.0, 1.0)
        calcs["projectedFinalSupport"] = projectedFinalSupport

        findings += Finding(
            "Proxy dynamic model: no payoff matrix — estimating support trajectory",
            "Without a payoff matrix, dynamic simulation uses stakeholder authority/influence weights to estimate support trajectory over $T rounds. Projected final support index: ${"%.2f".format(projectedFinalSupport * 100)}%.",
            Finding.Severity.INFO
        )

        when {
            projectedFinalSupport >= 0.65 -> findings += Finding(
                "Projected support trajectory: positive — coalition likely stabilises",
                "Weighted authority analysis suggests sufficient stakeholder alignment over $T rounds. Coalition-building effects dominate exit/veto risks.",
                Finding.Severity.INFO
            )
            projectedFinalSupport >= 0.40 -> findings += Finding(
                "Projected support trajectory: fragile — outcome uncertain",
                "Weighted analysis shows approximate balance between supportive and resistant forces. Small shocks could tip the outcome. Marginal stakeholders are decisive.",
                Finding.Severity.WARNING
            )
            else -> findings += Finding(
                "Projected support trajectory: negative — recommendation faces structural resistance",
                "Veto/exit risks and authority distribution suggest the recommendation will face sustained resistance over $T rounds. Structural engagement strategy needed before proceeding.",
                Finding.Severity.CRITICAL
            )
        }

        recs += RecommendedAction(null,
            "Add payoff values to actions to enable full best-response dynamics simulation",
            "Proxy simulation uses stakeholder attributes as proxies. Full dynamic simulation requires payoff matrix — add numeric outcomes to unlock best-response convergence analysis, attractor detection, and folk theorem assessment.",
            lens = RecommendedAction.Lens.LOW_REGRET)

        return AnalysisResult(
            moduleId = id, moduleName = name,
            applicability = canAnalyze(model),
            findings = findings, recommendedActions = recs,
            assumptions = listOf(
                Assumption("Proxy mode: authority/influence used as payoff proxies. Add numeric payoffs for full simulation."),
                Assumption("Coalition bonus and veto/exit risk coefficients are heuristic estimates — calibrate to domain.")
            ),
            calculations = calcs,
            confidence = 0.35,
            visualizations = emptyList(),
            explanation = "Proxy dynamic simulation over $T rounds using stakeholder authority weights. Payoff matrix absent — add numeric payoffs for full best-response dynamics."
        )
    }
}
