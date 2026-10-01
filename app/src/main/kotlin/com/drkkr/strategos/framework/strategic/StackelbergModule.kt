package com.drkkr.strategos.framework.strategic

import com.drkkr.strategos.domain.framework.*
import com.drkkr.strategos.domain.model.*
import com.drkkr.strategos.framework.MatrixSupport

class StackelbergModule : FrameworkModule {
    override val id = "stackelberg"
    override val name = "Stackelberg First-Mover Analysis"
    override val version = "1.0"
    override val category = "Strategic"

    override fun canAnalyze(model: Scenario): Applicability {
        val sf = MatrixSupport.build(model) ?: return Applicability(0.0, listOf("No payoff matrix."))
        if (sf.players.size != 2) return Applicability(0.0, listOf("Stackelberg requires exactly 2 players."))
        val leaderActions = sf.strategies[sf.players[0]]?.size ?: 0
        val followerActions = sf.strategies[sf.players[1]]?.size ?: 0
        if (leaderActions < 2 || followerActions < 2) return Applicability(0.0, listOf("Each player needs ≥2 actions."))

        val isSequential = model.gameTree != null || model.decisionOwner.isNotBlank()
        return Applicability(
            if (isSequential) 0.78 else 0.55,
            listOf(
                "Computes Stackelberg equilibrium: leader commits first, follower best-responds.",
                "Compares leader payoff under Stackelberg vs Nash equilibrium — quantifies first-mover advantage or disadvantage.",
                "Identifies whether commitment power benefits or harms the leader (the commitment trap).",
                "Applies to any scenario where the decision owner acts first and others respond."
            )
        )
    }

    override fun analyze(model: Scenario): AnalysisResult {
        val sf = MatrixSupport.build(model)!!
        val findings = mutableListOf<Finding>()
        val recs = mutableListOf<RecommendedAction>()

        val leaderId = sf.players[0]
        val followerId = sf.players[1]
        val leaderName = model.stakeholders.firstOrNull { it.id == leaderId }?.name ?: leaderId
        val followerName = model.stakeholders.firstOrNull { it.id == followerId }?.name ?: followerId

        val leaderStrategies = sf.strategies[leaderId] ?: return emptyResult()
        val followerStrategies = sf.strategies[followerId] ?: return emptyResult()
        val nL = leaderStrategies.size
        val nF = followerStrategies.size

        // ── Step 1: Follower best-response for each leader action ─────────────
        val followerBR = IntArray(nL) { i ->
            (0 until nF).maxByOrNull { j ->
                sf.utility[1][sf.profileIndex(listOf(i, j))]
            } ?: 0
        }

        // ── Step 2: Leader optimal given follower BR ───────────────────────────
        val stackelbergLeaderIdx = (0 until nL).maxByOrNull { i ->
            sf.utility[0][sf.profileIndex(listOf(i, followerBR[i]))]
        } ?: 0
        val stackelbergFollowerIdx = followerBR[stackelbergLeaderIdx]

        val stackPayoffLeader = sf.utility[0][sf.profileIndex(listOf(stackelbergLeaderIdx, stackelbergFollowerIdx))]
        val stackPayoffFollower = sf.utility[1][sf.profileIndex(listOf(stackelbergLeaderIdx, stackelbergFollowerIdx))]

        // ── Step 3: Nash equilibrium (pure, best-response iteration) ──────────
        var nashLeaderIdx = 0
        var nashFollowerIdx = 0
        var nashFound = false
        outer@ for (i in 0 until nL) {
            for (j in 0 until nF) {
                val lBest = (0 until nL).all { i2 ->
                    sf.utility[0][sf.profileIndex(listOf(i, j))] >=
                    sf.utility[0][sf.profileIndex(listOf(i2, j))]
                }
                val fBest = (0 until nF).all { j2 ->
                    sf.utility[1][sf.profileIndex(listOf(i, j))] >=
                    sf.utility[1][sf.profileIndex(listOf(i, j2))]
                }
                if (lBest && fBest) {
                    nashLeaderIdx = i
                    nashFollowerIdx = j
                    nashFound = true
                    break@outer
                }
            }
        }

        val nashPayoffLeader = if (nashFound)
            sf.utility[0][sf.profileIndex(listOf(nashLeaderIdx, nashFollowerIdx))] else Double.NaN
        val nashPayoffFollower = if (nashFound)
            sf.utility[1][sf.profileIndex(listOf(nashLeaderIdx, nashFollowerIdx))] else Double.NaN

        // ── Step 4: First-mover advantage computation ──────────────────────────
        val firstMoverAdvantage = if (!nashPayoffLeader.isNaN()) stackPayoffLeader - nashPayoffLeader else Double.NaN
        val hasFirstMoverAdvantage = !firstMoverAdvantage.isNaN() && firstMoverAdvantage > 0.01
        val hasCommitmentTrap = !firstMoverAdvantage.isNaN() && firstMoverAdvantage < -0.01

        // ── Findings ───────────────────────────────────────────────────────────
        findings += Finding(
            "Stackelberg equilibrium: $leaderName chooses '${leaderStrategies[stackelbergLeaderIdx]}', $followerName best-responds with '${followerStrategies[stackelbergFollowerIdx]}'",
            buildString {
                append("Leader ($leaderName) moves first and commits to '${leaderStrategies[stackelbergLeaderIdx]}'.\n")
                append("Follower ($followerName) observes this and best-responds with '${followerStrategies[stackelbergFollowerIdx]}'.\n\n")
                append("Payoffs at Stackelberg equilibrium:\n")
                append("  $leaderName: %.2f\n".format(stackPayoffLeader))
                append("  $followerName: %.2f\n\n".format(stackPayoffFollower))
                append("Follower best-responses to each leader action:\n")
                for (i in 0 until nL) {
                    val brPayoffF = sf.utility[1][sf.profileIndex(listOf(i, followerBR[i]))]
                    val brPayoffL = sf.utility[0][sf.profileIndex(listOf(i, followerBR[i]))]
                    append("  If $leaderName plays '${leaderStrategies[i]}' → $followerName plays '${followerStrategies[followerBR[i]]}' (L: %.2f, F: %.2f)\n".format(brPayoffL, brPayoffF))
                }
            },
            Finding.Severity.INFO
        )

        if (nashFound) {
            findings += Finding(
                "Nash equilibrium (simultaneous): ${leaderStrategies[nashLeaderIdx]} / ${followerStrategies[nashFollowerIdx]}",
                "Under simultaneous play: $leaderName = '${leaderStrategies[nashLeaderIdx]}' (payoff %.2f), $followerName = '${followerStrategies[nashFollowerIdx]}' (payoff %.2f).".format(nashPayoffLeader, nashPayoffFollower),
                Finding.Severity.NOTE
            )
        }

        when {
            hasFirstMoverAdvantage -> {
                findings += Finding(
                    "FIRST-MOVER ADVANTAGE: +%.2f payoff from moving first vs simultaneous play".format(firstMoverAdvantage),
                    buildString {
                        append("$leaderName gains %.2f by committing first (Stackelberg: %.2f vs Nash: %.2f).\n\n".format(firstMoverAdvantage, stackPayoffLeader, nashPayoffLeader))
                        append("Mechanism: commitment restricts $followerName's choices by revealing the leader's strategy. ")
                        append("$followerName's best response to '${leaderStrategies[stackelbergLeaderIdx]}' benefits the leader more than the Nash equilibrium outcome.\n\n")
                        append("Recommendation: commit early and visibly to '${leaderStrategies[stackelbergLeaderIdx]}'. Make the commitment credible and irreversible — reversibility undermines the Stackelberg advantage.")
                    },
                    Finding.Severity.INFO
                )
                recs += RecommendedAction(
                    leaderId,
                    "Commit early to: ${leaderStrategies[stackelbergLeaderIdx]}",
                    "Stackelberg first-mover advantage of +%.2f. Commitment must be observable and credible — announce publicly, take irreversible preparatory actions, or contractually bind the choice before $followerName responds.".format(firstMoverAdvantage),
                    lens = RecommendedAction.Lens.EXPECTED
                )
            }
            hasCommitmentTrap -> {
                findings += Finding(
                    "COMMITMENT TRAP: moving first REDUCES leader payoff by %.2f".format(-firstMoverAdvantage),
                    buildString {
                        append("$leaderName is worse off moving first (Stackelberg: %.2f vs Nash: %.2f).\n\n".format(stackPayoffLeader, nashPayoffLeader))
                        append("This is the commitment trap: revealing your strategy enables $followerName to exploit it. ")
                        append("Moving last (or maintaining ambiguity) is strategically superior.\n\n")
                        append("Recommendation: delay commitment, maintain strategic ambiguity, or seek a simultaneous-move mechanism.")
                    },
                    Finding.Severity.WARNING
                )
                recs += RecommendedAction(
                    leaderId,
                    "Delay commitment — avoid announcing strategy to ${followerName} first",
                    "Commitment trap: moving first costs %.2f vs simultaneous play. Maintain strategic ambiguity or negotiate simultaneous-reveal mechanisms.".format(-firstMoverAdvantage),
                    lens = RecommendedAction.Lens.RISK_AVERSE
                )
            }
            else -> {
                findings += Finding(
                    "No first-mover advantage: Stackelberg and Nash payoffs are equivalent",
                    "Move order does not affect the leader's payoff in this game. The equilibrium is the same under both simultaneous and sequential play.",
                    Finding.Severity.INFO
                )
            }
        }

        // ── Follower response map ──────────────────────────────────────────────
        val optimalFollowerLevers = mutableListOf<String>()
        for (i in 0 until nL) {
            val worstFollowerPayoff = (0 until nF).minOf { j -> sf.utility[1][sf.profileIndex(listOf(i, j))] }
            val bestFollowerPayoff = (0 until nF).maxOf { j -> sf.utility[1][sf.profileIndex(listOf(i, j))] }
            if (bestFollowerPayoff - worstFollowerPayoff > 0.5) {
                optimalFollowerLevers += "${followerName}'s best response to '${leaderStrategies[i]}' gives them %.1f vs %.1f (worst case) — they will predictably play '${followerStrategies[followerBR[i]]}'".format(bestFollowerPayoff, worstFollowerPayoff)
            }
        }
        if (optimalFollowerLevers.isNotEmpty()) {
            findings += Finding(
                "$followerName's predictable best responses — exploit these",
                optimalFollowerLevers.joinToString("\n") { "• $it" },
                Finding.Severity.NOTE
            )
        }

        return AnalysisResult(
            moduleId = id, moduleName = name,
            applicability = canAnalyze(model),
            findings = findings, recommendedActions = recs,
            assumptions = listOf(
                Assumption("Player 0 is the Stackelberg leader (decision owner); Player 1 is the follower."),
                Assumption("Leader's commitment is assumed observable and credible to the follower."),
                Assumption("Follower plays pure strategy best response — mixed strategies not computed here."),
                Assumption("Nash equilibrium is the pure-strategy NE; if none exists, NaN is used in the comparison."),
                Assumption("First-mover advantage = Stackelberg leader payoff − Nash equilibrium leader payoff.")
            ),
            calculations = mapOf(
                "stackelbergLeaderAction" to leaderStrategies[stackelbergLeaderIdx],
                "stackelbergFollowerAction" to followerStrategies[stackelbergFollowerIdx],
                "stackelbergLeaderPayoff" to stackPayoffLeader,
                "stackelbergFollowerPayoff" to stackPayoffFollower,
                "nashLeaderPayoff" to (if (nashFound) nashPayoffLeader else "N/A"),
                "firstMoverAdvantage" to (if (!firstMoverAdvantage.isNaN()) firstMoverAdvantage else "N/A"),
                "nashFound" to nashFound
            ),
            confidence = 0.80,
            visualizations = emptyList(),
            explanation = buildString {
                append("Stackelberg: $leaderName → '${leaderStrategies[stackelbergLeaderIdx]}' (payoff %.2f), $followerName best-responds → '${followerStrategies[stackelbergFollowerIdx]}'. ".format(stackPayoffLeader))
                when {
                    hasFirstMoverAdvantage -> append("First-mover ADVANTAGE: +%.2f vs Nash.".format(firstMoverAdvantage))
                    hasCommitmentTrap -> append("Commitment TRAP: moving first costs %.2f vs Nash.".format(-firstMoverAdvantage))
                    else -> append("No first-mover advantage — move order is irrelevant here.")
                }
            }
        )
    }

    private fun emptyResult() = AnalysisResult(
        moduleId = id, moduleName = name,
        applicability = Applicability(0.0, listOf("Insufficient data.")),
        findings = emptyList(), recommendedActions = emptyList(),
        assumptions = emptyList(), calculations = emptyMap(),
        confidence = 0.0, visualizations = emptyList(),
        explanation = "Cannot compute Stackelberg equilibrium — insufficient payoff data."
    )
}
