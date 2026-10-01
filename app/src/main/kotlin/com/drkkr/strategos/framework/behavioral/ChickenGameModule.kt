package com.drkkr.strategos.framework.behavioral

import com.drkkr.strategos.domain.framework.*
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.framework.MatrixSupport

class ChickenGameModule : FrameworkModule {
    override val id = "chicken-game"
    override val name = "Chicken / Brinkmanship"
    override val version = "1.0"
    override val category = "Behavioral"

    override fun canAnalyze(model: Scenario): Applicability {
        val text = "${model.title} ${model.description}".lowercase()
        val kwHits = listOf(
            "escalat", "standoff", "brinkmanship", "chicken", "swerve", "back down",
            "dare", "threat", "bluff", "commitment", "nuclear", "arms", "face-sav",
            "collision", "blink first", "credib", "deterrence", "veto", "ultimatum"
        ).count { text.contains(it) }
        val sf = MatrixSupport.build(model)
        val structuralScore = if (sf != null && sf.players.size == 2) detectChicken(sf) else 0.0
        return when {
            structuralScore > 0.5 -> Applicability(0.88, listOf("2×2 payoff structure matches Chicken game (swerve-crash pattern)."))
            kwHits >= 3 -> Applicability(0.75, listOf("Strong brinkmanship / escalation language ($kwHits keyword hits)."))
            kwHits >= 1 -> Applicability(0.45, listOf("Possible Chicken / standoff context ($kwHits keyword hits)."))
            else -> Applicability(0.0, listOf("No Chicken game signals detected."))
        }
    }

    override fun analyze(model: Scenario): AnalysisResult {
        val n = model.stakeholders.size
        val sf = MatrixSupport.build(model)
        val text = "${model.title} ${model.description}".lowercase()
        val findings = mutableListOf<Finding>()
        val recs = mutableListOf<RecommendedAction>()

        // Compute actual mixed Nash equilibrium from payoff matrix when available
        if (sf != null && sf.players.size == 2) {
            val s0 = sf.strategies[sf.players[0]]
            val s1 = sf.strategies[sf.players[1]]
            if (s0 != null && s1 != null && s0.size == 2 && s1.size == 2) {
                val t = sf.utility[0][sf.profileIndex(listOf(1, 0))]  // hold while other swerves
                val r = sf.utility[0][sf.profileIndex(listOf(0, 0))]  // both swerve
                val s = sf.utility[0][sf.profileIndex(listOf(0, 1))]  // swerve while other holds
                val p = sf.utility[0][sf.profileIndex(listOf(1, 1))]  // both hold (crash)
                val denom = (t - r) + (s - p)
                if (t > r && r > s && s > p && denom > 1e-9) {
                    val pHold = (t - r) / denom
                    val pSwerve = 1.0 - pHold
                    val crashProb = pHold * pHold
                    val p0Name = model.stakeholders.firstOrNull { it.id == sf.players[0] }?.name ?: "Player 1"
                    val p1Name = model.stakeholders.firstOrNull { it.id == sf.players[1] }?.name ?: "Player 2"
                    findings += Finding(
                        "Mixed Nash equilibrium (matrix-computed): each player holds with probability ${"%.0f".format(pHold * 100)}%",
                        "Payoffs from matrix — T=%.2f (hold/swerve), R=%.2f (both swerve), S=%.2f (swerve/hold), P=%.2f (both hold/crash)\n\n".format(t, r, s, p) +
                        "Mixed NE formula: p(Hold) = (T−R)/((T−R)+(S−P)) = (%.2f−%.2f)/((%.2f−%.2f)+(%.2f−%.2f)) = %.3f (%.0f%%)\n".format(t, r, t, r, s, p, pHold, pHold * 100) +
                        "p(Swerve) = %.3f (%.0f%%)\n".format(pSwerve, pSwerve * 100) +
                        "Mutual collision probability = p(Hold)² = %.1f%%\n\n".format(crashProb * 100) +
                        "$p0Name and $p1Name each hold firm ${"%.0f".format(pHold * 100)}%% of the time in equilibrium. " +
                        "The paradox of Chicken: as each player's hold payoff rises relative to the crash, collision risk increases quadratically.",
                        if (crashProb > 0.15) Finding.Severity.CRITICAL else Finding.Severity.WARNING
                    )
                }
            }
        }

        // Domain-specific context
        val domainNote = when {
            text.contains("military") || text.contains("nuclear") || text.contains("geopolit") || text.contains("deterrence") ->
                "Military/geopolitical context: face-saving mechanisms — hotlines, back-channels, third-party intermediaries — are critical. Historical evidence: the Cuban Missile Crisis (1962) resolved via back-channel coordination offering both sides a credible face-saving exit. Miscommunication, not lack of resolve, is the primary driver of accidental escalation."
            text.contains("corporate") || text.contains("merger") || text.contains("acquisition") || text.contains("competitor") ->
                "Corporate standoff context: litigation or regulatory escalation is the mutual crash — costly in legal fees, management distraction, and reputation. Business brinkmanship typically resolves through pre-negotiated off-ramps: mediation clauses, BATNA-based settlement windows, or structured negotiation timelines."
            text.contains("union") || text.contains("strike") || text.contains("industrial") || text.contains("labour") ->
                "Labour relations context: strike or lockout is the mutual crash. Third-party arbitration provides a face-saving exit that removes the game from bilateral Chicken by substituting external resolution. Both parties can agree to arbitration without either side appearing to back down."
            text.contains("political") || text.contains("parliament") || text.contains("budget") || text.contains("legislat") ->
                "Political standoff context: government shutdown, constitutional deadlines, or coalition breakdown is the crash. Institutional mechanisms (time limits, forced votes, coalition agreements) often impose resolution. Saving face via 'principled compromise' framing is essential for both parties."
            text.contains("healthcare") || text.contains("hospital") || text.contains("medical") ->
                "Healthcare standoff context: service disruption or patient harm is the unacceptable crash outcome. Clinical governance frameworks and patient safety constraints reduce the parameter space — any equilibrium that risks patient harm is excluded before game-theoretic analysis."
            else -> null
        }
        if (domainNote != null) {
            findings += Finding("Domain context for '${model.title}'", domainNote, Finding.Severity.NOTE)
        }

        findings += Finding(
            "Chicken game structure: two pure NE, both are asymmetric",
            "In Chicken, two pure Nash equilibria exist — one for each player backing down while the other holds firm. Neither (Hold, Hold) nor (Swerve, Swerve) is an equilibrium. The worst outcome for both is mutual escalation (crash). The key strategic question: who backs down first?",
            Finding.Severity.CRITICAL
        )

        findings += Finding(
            "Mixed Nash equilibrium: both randomise to keep opponent indifferent",
            "In the symmetric Chicken MSE, each player randomises between Hold and Swerve. The mixing probability depends on payoffs: the worse mutual escalation is relative to backing down, the more likely each player is to hold firm in equilibrium — paradoxically increasing collision risk.",
            Finding.Severity.WARNING
        )

        findings += Finding(
            "Commitment device: burning bridges can be rational (Schelling 1960)",
            "A player who credibly pre-commits to holding firm — by removing their own option to swerve, making the commitment public, or tying their reputation to the outcome — forces the other player to back down. Thomas Schelling: 'The power to constrain an adversary may depend on the power to bind oneself.' Pre-commitment works only if it is irreversible and observable.",
            Finding.Severity.NOTE
        )

        findings += Finding(
            "Reputation risk: backing down signals weakness for future games",
            "In repeated or multi-scenario contexts, backing down damages credibility for future standoffs. Leaders who concede once face harder future negotiations. This ratchet effect can trap both parties in increasingly dangerous escalation even when both prefer resolution.",
            Finding.Severity.WARNING
        )

        findings += Finding(
            "Face-saving exit: providing a graceful retreat reduces crash risk",
            "Empirical conflict resolution (Schelling 1960, Jervis 1978): offering the opponent a face-saving justification for backing down dramatically increases the probability of de-escalation. Frame the other party's retreat as a principled choice rather than a capitulation. Helps both parties exit without reputation loss.",
            Finding.Severity.INFO
        )

        findings += Finding(
            "Information asymmetry amplifies risk",
            "If each player privately overestimates the other's resolve, both may hold firm expecting the other to back down — producing the collision both want to avoid. Incomplete information about opponent commitment is the primary driver of real-world brinkmanship failures.",
            Finding.Severity.WARNING
        )

        if (model.stakeholders.any { it.canVeto }) {
            findings += Finding(
                "Veto power asymmetry: one party has formal veto rights",
                "When one stakeholder holds formal veto rights, they have a structural commitment device. The non-veto party faces a credibility deficit — their threats to escalate are less credible because backing down costs them less institutionally.",
                Finding.Severity.NOTE
            )
        }

        if (n > 2) {
            findings += Finding(
                "Multi-party Chicken: third-party intervention changes equilibrium",
                "With $n actors, third-party mediators can change the payoff structure by guaranteeing face-saving exits for both sides, making de-escalation individually rational. Coalition formation can also shift the hold/swerve calculus for each pair.",
                Finding.Severity.INFO
            )
        }

        recs += RecommendedAction(null, "Establish back-channel communication",
            "Even in public standoffs, private channels allow face-saving coordination that public positions make impossible. Pre-agree on an exit ramp before escalation peaks. Reduces collision risk without requiring either party to publicly concede.",
            lens = RecommendedAction.Lens.LOW_REGRET)

        recs += RecommendedAction(null, "Offer a face-saving reframing",
            "Design the opponent's retreat so it can be publicly described as a principled position rather than a loss. Label concession as 'strategic patience', 'proportionate response', or 'de-escalation in the wider interest'. Face-saving substantially increases uptake of compromise offers.",
            lens = RecommendedAction.Lens.RELATIONSHIP)

        recs += RecommendedAction(null, "Avoid irreversible public commitments before back-channels open",
            "Pre-commitment strategies (burning bridges) only work if the opponent has time and space to back down. Public commitments made before communication channels are established can trap both parties — producing the crash neither party wants.",
            lens = RecommendedAction.Lens.RISK_AVERSE)

        val modelConstraintCount = model.constraints.count { it.kind == com.drkkr.strategos.domain.model.Constraint.Kind.LEGAL || it.kind == com.drkkr.strategos.domain.model.Constraint.Kind.SAFETY }
        if (modelConstraintCount > 0) {
            findings += Finding(
                "Hard constraints limit hold strategies: $modelConstraintCount legal/safety constraints in model",
                "Actions that produce the 'crash' outcome may violate legal or safety constraints and are therefore excluded regardless of strategic logic. Even if holding firm is strategically optimal, constraint violations override the utility calculation.",
                Finding.Severity.CRITICAL
            )
        }

        return AnalysisResult(
            moduleId = id, moduleName = name,
            applicability = canAnalyze(model),
            findings = findings, recommendedActions = recs,
            assumptions = listOf(
                Assumption("Chicken game: T > R > S > P in standard form — holding firm dominates when opponent backs down, mutual crash is worst for both."),
                Assumption("Commitment credibility requires the commitment to be observable, irreversible, and believed by the opponent."),
                Assumption("Reputation effects most important in repeated play; less relevant in genuinely one-shot decisions.")
            ),
            calculations = mapOf("playerCount" to n, "hasVetoPlayer" to model.stakeholders.any { it.canVeto }),
            confidence = 0.72,
            visualizations = emptyList(),
            explanation = "Chicken / Brinkmanship game. Mutual escalation (crash) is worst for both; the strategic question is credible commitment and face-saving exit design. Schelling (1960): commitment, communication, and face-saving are the three primary levers."
        )
    }

    private fun detectChicken(sf: MatrixSupport.StrategicForm): Double {
        if (sf.players.size != 2) return 0.0
        val s0 = sf.strategies[sf.players[0]] ?: return 0.0
        val s1 = sf.strategies[sf.players[1]] ?: return 0.0
        if (s0.size != 2 || s1.size != 2) return 0.0
        // Chicken: T > R > S > P for player 0
        val t = sf.utility[0][sf.profileIndex(listOf(1, 0))]  // hold while other swerves
        val r = sf.utility[0][sf.profileIndex(listOf(0, 0))]  // both swerve
        val s = sf.utility[0][sf.profileIndex(listOf(0, 1))]  // swerve while other holds
        val p = sf.utility[0][sf.profileIndex(listOf(1, 1))]  // both hold (crash)
        return if (t > r && r > s && s > p) 0.9 else 0.0
    }
}
