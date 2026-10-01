package com.drkkr.strategos.framework.strategic

import com.drkkr.strategos.domain.framework.*
import com.drkkr.strategos.domain.model.Scenario

/**
 * Adversarial analysis: generates the strongest case against every leading recommendation,
 * identifies likely resistors, sabotage modes, failure scenarios, and mitigation strategies.
 * Phase 3 expansion: structured failure mode taxonomy across 5 categories.
 */
class RedTeamModule : FrameworkModule {
    override val id = "red-team"
    override val name = "Red Team / Adversarial Analysis"
    override val version = "1.0"
    override val category = "Decision Quality"

    override fun canAnalyze(model: Scenario): Applicability {
        // Applies to any scenario with ≥2 stakeholders — adversarial analysis is always relevant
        if (model.stakeholders.size < 2) return Applicability(0.0, listOf("Red team requires ≥2 stakeholders."))
        if (model.actions.isEmpty()) return Applicability(0.30, listOf("No actions defined — limited red-team scope."))
        return Applicability(0.85, listOf(
            "Adversarial analysis applies to all multi-stakeholder decisions.",
            "Generates strongest counter-arguments, likely resistance, and failure modes."
        ))
    }

    override fun analyze(model: Scenario): AnalysisResult {
        val findings = mutableListOf<Finding>()
        val recs = mutableListOf<RecommendedAction>()
        val n = model.stakeholders.size
        // 1. Structural resistance analysis
        val vetoPlayers = model.stakeholders.filter { it.canVeto }
        val exitCapableActors = model.stakeholders.filter { it.canExit }
        val delayCapableActors = model.stakeholders.filter { it.canDelay }

        if (vetoPlayers.isNotEmpty()) {
            findings += Finding(
                "Veto players: ${vetoPlayers.joinToString { it.name }} can block any recommendation",
                "Tsebelis (2002): the number and ideological range of veto players determines policy changeability. Every listed veto player can unilaterally block any proposed action — even if all other stakeholders support it. Recommendations that do not secure veto-player buy-in are not implementable regardless of analytical optimality.",
                Finding.Severity.CRITICAL
            )
        }

        if (exitCapableActors.isNotEmpty()) {
            findings += Finding(
                "Exit risk: ${exitCapableActors.joinToString { it.name }} may withdraw from the arrangement",
                "Stakeholders who can exit without catastrophic loss to themselves will do so if the proposed outcome is worse than their outside option. If they exit, the entire coalition or arrangement may unravel. Red-team check: is the proposed recommendation viable if these actors defect?",
                Finding.Severity.WARNING
            )
        }

        if (delayCapableActors.isNotEmpty()) {
            findings += Finding(
                "Delay risk: ${delayCapableActors.joinToString { it.name }} can use procedural delay",
                "Even without formal veto rights, actors can use procedural, bureaucratic, or regulatory delay to derail time-sensitive recommendations. If the window for action is short or costs of delay are high, these actors have disproportionate leverage.",
                Finding.Severity.WARNING
            )
        }

        // 2. Information asymmetry adversarial risks
        if (model.informationAsymmetry) {
            findings += Finding(
                "Information asymmetry: adversarial information manipulation is possible",
                "When stakeholders have private information, they can strategically share or withhold it to influence the analysis. Red-team scenarios:\n• Dominant actor provides selectively favourable data to support their preferred outcome.\n• Agents mis-report performance metrics to influence monitoring decisions.\n• External parties misrepresent capabilities or constraints during negotiation.\n• Recommendation is built on data that the supplier of that data has an incentive to distort.",
                Finding.Severity.WARNING
            )
        }

        // 3. Implementation failure modes
        findings += Finding(
            "Implementation bottleneck: even approved decisions often fail at execution",
            "Classic failure modes:\n• Resource unavailability post-approval (budget cut, staff turnover, competing priorities)\n• Key individual dependency — decision depends on one person who leaves or resists\n• Coordination failure — multiple parties must act simultaneously, none acting first\n• Specification gap — recommendation is ambiguous enough that each actor implements their own interpretation\n• Time erosion — delays shift the context enough that the original recommendation is no longer appropriate",
            Finding.Severity.WARNING
        )

        // 4. Unintended consequences
        findings += Finding(
            "Second-order effects: who else is affected beyond the named stakeholders?",
            "Red-team challenge: identify at least three groups not in the current model who will be affected by the recommended action:\n• Downstream recipients of services (patients, clients, public)\n• Future stakeholders not yet identified (successors, new entrants)\n• Adjacent systems that will adapt their behaviour in response\nSecond-order effects frequently dominate first-order intended effects in complex institutional settings.",
            Finding.Severity.NOTE
        )

        // 5. Constraint violation risks
        val safetyConstraints = model.constraints.filter { it.kind == com.drkkr.strategos.domain.model.Constraint.Kind.SAFETY }
        val legalConstraints = model.constraints.filter { it.kind == com.drkkr.strategos.domain.model.Constraint.Kind.LEGAL }
        val ethicsConstraints = model.constraints.filter { it.kind == com.drkkr.strategos.domain.model.Constraint.Kind.ETHICAL }

        if (safetyConstraints.isNotEmpty()) {
            findings += Finding(
                "${safetyConstraints.size} safety constraint(s) require active monitoring during implementation",
                "Safety constraints: ${safetyConstraints.joinToString { it.label }}. Red-team: what implementation failure mode would cause these to be violated? Specify who owns safety monitoring and what triggers an implementation halt.",
                Finding.Severity.CRITICAL
            )
        }

        if (legalConstraints.isNotEmpty()) {
            findings += Finding(
                "${legalConstraints.size} legal constraint(s) — litigation / regulatory challenge risk",
                "Any stakeholder who opposes the recommendation may challenge it through legal or regulatory channels. Red-team: which actor has the most to gain from a successful challenge, and is the proposed action fully compliant with: ${legalConstraints.joinToString { it.label }}?",
                Finding.Severity.WARNING
            )
        }

        if (ethicsConstraints.isNotEmpty()) {
            findings += Finding(
                "${ethicsConstraints.size} ethics constraint(s) — reputational and professional risk",
                "Ethics constraints: ${ethicsConstraints.joinToString { it.label }}. Red-team: could any party plausibly characterise the recommendation as violating professional or ethical norms? Even if the characterisation is wrong, reputational damage from the framing can be real and costly.",
                Finding.Severity.WARNING
            )
        }

        // 6. Cognitive / process risks
        findings += Finding(
            "Process risks: groupthink, escalation of commitment, sunk-cost reasoning",
            "Red-team challenges to the decision process itself:\n• Groupthink: did the analysis consider minority stakeholder positions seriously, or dismiss them early?\n• Escalation of commitment: is this recommendation partly driven by prior investment in a direction rather than current evidence?\n• Confirmation bias: were disconfirming data sources as thoroughly analysed as confirming ones?\n• Anchoring: is the recommendation anchored on the first option considered rather than the best option after full analysis?",
            Finding.Severity.NOTE
        )

        // 7. Threshold analysis
        findings += Finding(
            "Threshold question: what single assumption, if wrong, most changes the recommendation?",
            "Red-team stress test: identify the one assumption in the analysis that (a) most affects the recommendation and (b) is most likely to be wrong. This is the highest-value information to collect before committing to the recommended action. Recommendations that survive failure of their single most important assumption are robust; those that collapse on one failure are fragile.",
            Finding.Severity.NOTE
        )

        // Recommendations
        recs += RecommendedAction(null, "Secure veto-player buy-in before announcing recommendation",
            "Any recommendation that faces formal veto must be pre-cleared with veto players before public announcement. Announce after alignment, not before — reversing a publicly announced position costs more than delaying announcement.",
            lens = RecommendedAction.Lens.RISK_AVERSE)

        recs += RecommendedAction(null, "Name a single implementation owner with decision rights",
            "Distributed implementation responsibility is the primary implementation failure mode. Name one person or body who owns execution, has authority to resolve resource conflicts, and reports progress against defined milestones. Committees implement slowly and inconsistently.",
            lens = RecommendedAction.Lens.LOW_REGRET)

        recs += RecommendedAction(null, "Define stop conditions before starting",
            "Pre-specify at least three conditions that would trigger a halt or reversal. 'Stop if: [safety threshold breached], [key stakeholder exits], [evidence contradicts core assumption].' Defined stop conditions prevent escalation of commitment and provide political cover for reversals when needed.",
            lens = RecommendedAction.Lens.ETHICAL_LEGAL)

        recs += RecommendedAction(null, "Commission a structured pre-mortem",
            "Before implementation: have the team write a one-page 'it is 12 months from now and the decision has failed — what happened?' Surfaces implementation risks that forward-looking analysis misses. Klein (2007): pre-mortems identify 30% more failure modes than standard risk analysis.",
            lens = RecommendedAction.Lens.LONG_TERM)

        val highInfluenceLowAlignedActors = model.stakeholders
            .filter { it.influence >= 6 && (it.goals.isNotEmpty() || it.privateGoals.isNotEmpty()) }

        if (highInfluenceLowAlignedActors.isNotEmpty()) {
            recs += RecommendedAction(null,
                "Engage high-influence actors early: ${highInfluenceLowAlignedActors.take(3).joinToString { it.name }}",
                "High-influence actors who are not yet aligned represent the primary strategic risk. Engage them before the recommendation is public to understand their objections and explore whether modifications can reduce opposition without destroying the core value of the recommendation.",
                lens = RecommendedAction.Lens.RELATIONSHIP)
        }

        // ── STRUCTURED FAILURE MODE TAXONOMY ──────────────────────────────────

        // Category A: Execution failures
        val executionFailures = buildList {
            add("Resource depletion: budget is approved but funds are diverted by competing priorities before disbursement.")
            add("Key-person dependency: the responsible person leaves, is reassigned, or becomes unavailable.")
            add("Timeline compression: scope is unchanged but deadline shortens, forcing quality shortcuts.")
            add("Specification ambiguity: each implementing party interprets instructions differently, producing inconsistent outputs.")
            if (model.actions.any { it.implementationDifficulty >= 4 })
                add("High-difficulty action(s) flagged: implementation complexity ≥4/5. Assume at least one critical step will encounter unexpected resistance.")
            if (model.actions.any { !it.reversible })
                add("Irreversible action(s) present: execution error cannot be undone — dry-run or staged rollout is mandatory.")
        }
        findings += Finding(
            "Execution failure modes (${executionFailures.size} identified)",
            "Category A — Execution:\n${executionFailures.joinToString("\n") { "• $it" }}",
            Finding.Severity.WARNING
        )

        // Category B: Stakeholder failures
        val stakeholderFailures = buildList {
            add("Principal-agent divergence: implementing agents pursue private goals incompatible with the principal's intent.")
            add("Legitimacy withdrawal: a previously supportive stakeholder publicly withdraws endorsement mid-implementation.")
            if (highInfluenceLowAlignedActors.isNotEmpty())
                add("High-influence opposition: ${highInfluenceLowAlignedActors.take(2).joinToString { it.name }} can mobilise resistance faster than implementation proceeds.")
            if (vetoPlayers.size >= 2)
                add("Veto coalition: ${vetoPlayers.take(2).joinToString { it.name }} coordinate opposition — individually resistible, jointly decisive.")
            add("Stakeholder fatigue: prolonged implementation erodes support from actors who were initially neutral.")
        }
        findings += Finding(
            "Stakeholder failure modes (${stakeholderFailures.size} identified)",
            "Category B — Stakeholder:\n${stakeholderFailures.joinToString("\n") { "• $it" }}",
            Finding.Severity.WARNING
        )

        // Category C: External shocks
        val externalShocks = buildList {
            add("Regulatory shift: new regulation or policy guidance invalidates a core assumption of the recommendation.")
            add("Technology disruption: a new tool or platform makes the chosen approach obsolete before completion.")
            add("Political leadership change: a key sponsor exits the organisation or is reassigned.")
            add("Macro shock: economic, epidemiological, or social events consume the attention and resources of all stakeholders.")
            if (model.timeHorizon > 6)
                add("Extended time horizon (${model.timeHorizon} rounds): probability of at least one major external shock is elevated. Build explicit scenario branches for shock response.")
        }
        findings += Finding(
            "External shock scenarios (${externalShocks.size} identified)",
            "Category C — External:\n${externalShocks.joinToString("\n") { "• $it" }}",
            Finding.Severity.NOTE
        )

        // Category D: Assumption failures
        val assumptionFailures = buildList {
            if (model.assumptionLog.isEmpty())
                add("No explicit assumptions logged. Every analysis rests on implicit assumptions — failure to name them prevents monitoring for violation.")
            add("False consensus: the group believes all members share a key assumption when they do not.")
            add("Scope creep assumption: the recommendation assumes a bounded problem but stakeholders keep adding requirements.")
            add("Baseline assumption violation: the assumed current state is already incorrect at the time of implementation.")
            if (model.informationAsymmetry)
                add("Asymmetric information assumed manageable: if the information gap is wider than estimated, the entire analytical framework may be invalid.")
        }
        findings += Finding(
            "Assumption failure modes (${assumptionFailures.size} identified)",
            "Category D — Assumptions:\n${assumptionFailures.joinToString("\n") { "• $it" }}",
            Finding.Severity.NOTE
        )

        // Category E: Adversarial playbook
        val adversarialPlaybook = buildList {
            add("Salami slicing: opponents make incremental concessions publicly while blocking implementation details privately.")
            add("Information flooding: opponents generate excessive data/reports to overwhelm the decision-maker's capacity to evaluate.")
            add("Procedural delay weaponisation: opponents use every available formal process (review, audit, consultation) to slow implementation past the political window.")
            add("Narrative capture: opponents reframe the recommendation in negative terms before it can be explained — first mover in public narrative wins.")
            if (model.stakeholders.any { it.type.name == "MEDIA" || it.type.name == "POLITICAL_ACTOR" })
                add("Media/political actor present: adversarial narrative can be amplified through external channels — monitor public framing from day one.")
            add("Coalition fragmentation: opponents target the weakest link in the supporting coalition to create visible defection that triggers bandwagon exit.")
        }
        findings += Finding(
            "Adversarial playbook (${adversarialPlaybook.size} tactics)",
            "Category E — Adversarial:\n${adversarialPlaybook.joinToString("\n") { "• $it" }}",
            Finding.Severity.CRITICAL
        )

        // Mitigation for adversarial playbook
        recs += RecommendedAction(null,
            "Build early-warning tripwires for each failure category",
            "For each failure mode category: name a specific observable indicator that signals the failure is beginning. Assign a person to monitor each indicator. Respond when the tripwire is hit — not after.",
            lens = RecommendedAction.Lens.RISK_AVERSE)

        recs += RecommendedAction(null,
            "Establish public narrative in the first 48 hours of implementation",
            "Adversarial playbook tactic #4 (narrative capture) is only effective if implementation goes public without a clear framing. Prepare a one-paragraph plain-language summary of the recommendation for immediate release when implementation begins. Own the narrative before opponents can define it.",
            lens = RecommendedAction.Lens.RELATIONSHIP)

        return AnalysisResult(
            moduleId = id, moduleName = name,
            applicability = canAnalyze(model),
            findings = findings, recommendedActions = recs,
            assumptions = listOf(
                Assumption("Red team analysis assumes adversarial intent is possible, not that it is certain — findings reflect structural risk, not prediction."),
                Assumption("Veto player analysis follows Tsebelis (2002): formal veto is decisive; informal influence is partially captured in authority and influence scores."),
                Assumption("Implementation failure rate baseline: >50% of approved institutional decisions face significant implementation failure within 24 months (World Bank 2014 review).")
            ),
            calculations = mapOf(
                "vetoPlayerCount" to vetoPlayers.size,
                "exitRiskCount" to exitCapableActors.size,
                "delayRiskCount" to delayCapableActors.size,
                "safetyConstraintCount" to safetyConstraints.size,
                "legalConstraintCount" to legalConstraints.size,
                "stakeholderCount" to n
            ),
            confidence = 0.80,
            visualizations = emptyList(),
            explanation = "Red Team adversarial analysis: structural resistance (veto, exit, delay), information manipulation risks, implementation failure modes, unintended consequences, constraint violation scenarios, and process biases. Recommendations: secure veto buy-in, name implementation owner, define stop conditions, run pre-mortem."
        )
    }
}
