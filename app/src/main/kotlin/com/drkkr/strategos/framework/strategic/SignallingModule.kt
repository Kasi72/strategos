package com.drkkr.strategos.framework.strategic

import com.drkkr.strategos.domain.framework.*
import com.drkkr.strategos.domain.model.*

class SignallingModule : FrameworkModule {
    override val id = "signalling"
    override val name = "Signalling & Credibility Analysis"
    override val version = "1.0"
    override val category = "Strategic"

    override fun canAnalyze(model: Scenario): Applicability {
        val hasAsymmetry = model.informationAsymmetry || model.stakeholders.any { it.credibility < 5 }
        if (!hasAsymmetry) return Applicability(
            0.30,
            listOf(
                "No information asymmetry flag set and all stakeholders have credibility ≥ 5.",
                "Enable for scenarios where one party has private type information (expert/novice, committed/uncommitted, capable/incapable)."
            )
        )
        return Applicability(
            0.84,
            listOf(
                "Applies Spence (1973) signalling theory: identifies when costly signals are needed to overcome adverse selection.",
                "Applies Crawford-Sobel (1982) cheap talk: determines when verbal statements alone are credible.",
                "Identifies separating vs pooling equilibria and the minimum signal cost that prevents mimicry by low-type actors.",
                "Covers reputation-building, commitment devices, and credibility repair strategies."
            )
        )
    }

    override fun analyze(model: Scenario): AnalysisResult {
        val findings = mutableListOf<Finding>()
        val recs = mutableListOf<RecommendedAction>()

        // Classify each stakeholder as potential sender or receiver
        val senders = model.stakeholders.filter { it.credibility < 6 || model.informationAsymmetry }
        val receivers = model.stakeholders.filter { it.credibility >= 6 || it.authority >= 6 }

        // ── 1. Cheap talk credibility (Crawford-Sobel) ───────────────────────
        val ownerId = model.stakeholders.firstOrNull {
            it.name.contains(model.decisionOwner, ignoreCase = true) ||
            it.role.contains(model.decisionOwner, ignoreCase = true)
        }

        for (sender in senders.take(4)) {
            if (sender.id == ownerId?.id) continue
            val potentialReceivers = receivers.filter { it.id != sender.id }

            val interestAlignment = estimateInterestAlignment(sender, potentialReceivers.firstOrNull(), model)
            val cheapTalkCredible = interestAlignment > 0.5 && sender.credibility >= 4

            findings += Finding(
                "${sender.name}: cheap talk ${if (cheapTalkCredible) "CREDIBLE" else "NOT credible"} (interest alignment ${(interestAlignment * 100).toInt()}%)",
                buildString {
                    append("Crawford-Sobel (1982) cheap talk theorem: verbal commitments are only credible when sender and receiver have sufficiently aligned interests.\n\n")
                    append("• ${sender.name} credibility: ${sender.credibility}/10\n")
                    append("• Estimated interest alignment with decision authority: ${(interestAlignment * 100).toInt()}%\n")
                    if (cheapTalkCredible) {
                        append("→ Verbal assurances, memos, and commitments from ${sender.name} are likely believed at face value. No costly signal required.")
                    } else {
                        append("→ Cheap talk is not credible here. ${sender.name} has incentive to misrepresent, so receivers will discount verbal commitments.")
                        append("\n   Credibility gap: ${sender.name} needs a costly signal — one that a low-type actor could not profitably mimic.")
                    }
                },
                if (cheapTalkCredible) Finding.Severity.INFO else Finding.Severity.WARNING
            )

            if (!cheapTalkCredible) {
                // Separating equilibrium: recommend a costly signal
                val signals = recommendCostlySignals(sender, model)
                if (signals.isNotEmpty()) {
                    recs += RecommendedAction(
                        sender.id,
                        signals.first(),
                        "Separating signal for ${sender.name}: costly enough to deter low-type mimicry, observable by decision authorities. Spence (1973) separating equilibrium: signal cost must exceed benefit of misrepresentation for the low type.",
                        lens = RecommendedAction.Lens.RELATIONSHIP
                    )
                    if (signals.size > 1) {
                        findings += Finding(
                            "${sender.name}: available separating signals",
                            "Ranked by observability and cost efficiency:\n${signals.mapIndexed { i, s -> "${i+1}. $s" }.joinToString("\n")}",
                            Finding.Severity.NOTE
                        )
                    }
                }
            }
        }

        // ── 2. Separating vs pooling equilibrium analysis ────────────────────
        val lowCredibilityActors = model.stakeholders.filter { it.credibility <= 3 }
        val highCredibilityActors = model.stakeholders.filter { it.credibility >= 7 }

        if (lowCredibilityActors.isNotEmpty() && highCredibilityActors.isNotEmpty()) {
            val poolingRisk = lowCredibilityActors.any { lc ->
                highCredibilityActors.any { hc ->
                    // Same type/role = pooling risk (low type mimics high type)
                    lc.type == hc.type || lc.role.contains(hc.role.take(10), ignoreCase = true)
                }
            }

            if (poolingRisk) {
                findings += Finding(
                    "Pooling equilibrium risk: low-credibility actors may mimic high-credibility ones",
                    buildString {
                        append("Low-credibility stakeholders: ${lowCredibilityActors.joinToString(", ") { "${it.name} (${it.credibility}/10)" }}\n")
                        append("High-credibility stakeholders: ${highCredibilityActors.joinToString(", ") { "${it.name} (${it.credibility}/10)" }}\n\n")
                        append("Pooling equilibrium occurs when low types cannot be distinguished from high types — eroding the value of high-type signals. ")
                        append("Decision-makers should demand signals that are prohibitively costly for low-credibility actors (e.g., public commitments, performance bonds, third-party verification).")
                    },
                    Finding.Severity.WARNING
                )
            }
        }

        // ── 3. Reputation and commitment devices ─────────────────────────────
        val reputationRisks = model.stakeholders.filter {
            it.credibility in 4..6 && it.authority >= 5
        }
        if (reputationRisks.isNotEmpty()) {
            findings += Finding(
                "Moderate-credibility actors with significant authority: reputation is at stake",
                buildString {
                    append("Stakeholders in the 4–6 credibility range with authority ≥ 5 are at a credibility inflection point:\n")
                    for (s in reputationRisks) {
                        append("• ${s.name} [credibility ${s.credibility}/10, authority ${s.authority}/10]: ")
                        append("commitment devices (public pledges, contractual penalties, third-party escrow) would lock in cooperative behaviour and signal type.\n")
                    }
                    append("\nCommitment value: by making defection costly to themselves, these actors make their cooperation credible (Schelling 1960).")
                },
                Finding.Severity.NOTE
            )
        }

        // ── 4. Information asymmetry structural summary ───────────────────────
        if (model.informationAsymmetry) {
            val unknownBeliefs = model.beliefs.count { it.status == FactStatus.UNKNOWN || it.status == FactStatus.AI_INFERRED }
            findings += Finding(
                "Structural information asymmetry — ${unknownBeliefs} belief(s) are unknown to at least one party",
                buildString {
                    append("Scenarios with information asymmetry face three equilibrium outcomes:\n")
                    append("• Separating equilibrium: each type sends a distinct signal — private information is revealed at a cost\n")
                    append("• Pooling equilibrium: all types send the same signal — private information stays hidden but high types lose signalling value\n")
                    append("• Semi-separating: mixed strategies; receiver learns partial information\n\n")
                    append("Design recommendation: create mechanisms that make revelation incentive-compatible — either through costly signals (Spence) or direct revelation mechanisms (Myerson 1979).")
                },
                Finding.Severity.NOTE
            )
        }

        return AnalysisResult(
            moduleId = id, moduleName = name,
            applicability = canAnalyze(model),
            findings = findings, recommendedActions = recs,
            assumptions = listOf(
                Assumption("Interest alignment estimated from stakeholder type compatibility, coalition membership, and shared goals."),
                Assumption("Cheap talk credibility threshold: interest alignment > 50% AND sender credibility ≥ 4 (Crawford-Sobel 1982)."),
                Assumption("Separating signal cost must exceed the benefit of misrepresentation for the low type (Spence 1973). Specific cost depends on domain."),
                Assumption("Pooling equilibrium risk assessed from stakeholder type overlap — direct interview or behavioural data would improve accuracy."),
                Assumption("Commitment device value modelled after Schelling (1960): pre-commitment raises the cost of defection and makes cooperation credible.")
            ),
            calculations = mapOf(
                "sendersIdentified" to senders.size,
                "receiversIdentified" to receivers.size,
                "lowCredibilityActors" to lowCredibilityActors.size,
                "highCredibilityActors" to highCredibilityActors.size,
                "informationAsymmetryFlag" to model.informationAsymmetry
            ),
            confidence = if (model.informationAsymmetry) 0.72 else 0.55,
            visualizations = emptyList(),
            explanation = "Signalling analysis: ${senders.size} sender(s) identified, ${lowCredibilityActors.size} low-credibility actor(s). ${if (model.informationAsymmetry) "Structural information asymmetry present — separating/pooling equilibria analysis applied." else "Credibility gaps may reduce the effectiveness of verbal commitments."}"
        )
    }

    private fun estimateInterestAlignment(sender: Stakeholder, receiver: Stakeholder?, model: Scenario): Double {
        if (receiver == null) return 0.4
        val sharedCoalition = sender.coalitionsWith.contains(receiver.id) ||
            receiver.coalitionsWith.contains(sender.id)
        val sameType = sender.type == receiver.type
        val credibilityRatio = sender.credibility.toDouble() / 10.0
        val base = if (sharedCoalition) 0.7 else if (sameType) 0.55 else 0.35
        // Structural information asymmetry reduces alignment — parties with private info have incentive to misrepresent
        val asymmetryPenalty = if (model.informationAsymmetry) -0.15 else 0.0
        // Repeated game increases alignment — reputation stakes make honesty more valuable
        val repeatedBonus = if (model.repeated) 0.10 else 0.0
        return (base + credibilityRatio * 0.2 + asymmetryPenalty + repeatedBonus).coerceIn(0.1, 0.95)
    }

    private fun recommendCostlySignals(sender: Stakeholder, model: Scenario): List<String> {
        val signals = mutableListOf<String>()
        // Domain-specific signals based on model constraints and context
        val hasLegalConstraint = model.constraints.any { it.kind == Constraint.Kind.LEGAL }
        val hasResourceConstraint = model.constraints.any { it.kind == Constraint.Kind.RESOURCE }
        val text = "${model.title} ${model.description}".lowercase()
        val isHealthcare = text.contains("health") || text.contains("clinic") || text.contains("hospital") || text.contains("patient")
        val isFinance = text.contains("financ") || text.contains("invest") || text.contains("fund") || text.contains("capital")

        when {
            sender.type == StakeholderType.INSTITUTION || sender.authority >= 6 ->
                signals += "Issue a formal public commitment with binding penalty clause — public retraction cost makes defection credible threat"
            sender.type == StakeholderType.VENDOR ->
                signals += "Post a performance bond or escrow — financial stake directly observable by receiver"
            sender.canExit ->
                signals += "Voluntarily restrict exit option: waive withdrawal rights for a defined period — costlessly mimicked only if actor intends to stay"
            else ->
                signals += "Commission independent third-party assessment — observation cost filters uncommitted actors"
        }
        if (hasLegalConstraint) {
            signals += "Execute legally-binding agreement with liquidated damages clause — legal enforceability converts cheap talk into verifiable commitment"
        }
        if (hasResourceConstraint) {
            signals += "Commit specific, auditable resources (budget line, staffing allocation) — resource sinking directly observable and hard to fake"
        }
        if (isHealthcare) {
            signals += "Submit to accreditation or regulatory oversight — third-party clinical standards verification separates genuine capability from claimed capability"
        }
        if (isFinance) {
            signals += "Disclose audited financials or independent valuation — removes information asymmetry about true financial state"
        }
        if (model.repeated) {
            signals += "Establish a track record through small initial commitments — sequence low-stakes cooperation to build mutual credibility before high-stakes decisions"
        }
        signals += "Provide verifiable track record: reference prior cases where commitments were honoured under adversity"
        signals += "Accept outcome-contingent compensation: align payoff with recipient's outcome, eliminating misrepresentation incentive"
        return signals
    }
}
