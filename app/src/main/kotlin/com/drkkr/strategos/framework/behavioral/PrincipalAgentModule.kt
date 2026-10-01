package com.drkkr.strategos.framework.behavioral

import com.drkkr.strategos.domain.framework.*
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.model.StakeholderType

class PrincipalAgentModule : FrameworkModule {
    override val id = "principal-agent"
    override val name = "Principal–Agent & Information Asymmetry"
    override val version = "1.0"
    override val category = "Behavioral"

    override fun canAnalyze(model: Scenario): Applicability {
        val text = "${model.title} ${model.description}".lowercase()
        val kwHits = listOf(
            "delegat", "agent", "principal", "oversight", "monitor", "moral hazard",
            "adverse select", "hidden action", "hidden inform", "incentive", "contract",
            "compensat", "bonus", "performance", "audit", "accountability", "mandat",
            "fiduciary", "trust", "employ", "outsourc", "vendor", "contractor",
            "information asymmetry", "asymmetric", "screen", "signal"
        ).count { text.contains(it) }
        val hasAsymmetry = model.informationAsymmetry
        val hasDelegation = model.stakeholders.size >= 2 &&
            model.stakeholders.any { it.authority >= 7 } &&
            model.stakeholders.any { it.authority <= 4 }
        return when {
            hasAsymmetry && kwHits >= 2 -> Applicability(0.90, listOf("Information asymmetry flag set + delegation language."))
            hasAsymmetry -> Applicability(0.75, listOf("Information asymmetry flag set — P-A dynamics likely."))
            kwHits >= 4 -> Applicability(0.80, listOf("Strong principal-agent language ($kwHits keyword hits)."))
            kwHits >= 2 -> Applicability(0.55, listOf("Delegation / oversight language detected."))
            hasDelegation -> Applicability(0.40, listOf("Authority differential suggests principal-agent structure."))
            else -> Applicability(0.0, listOf("No principal-agent signals detected."))
        }
    }

    override fun analyze(model: Scenario): AnalysisResult {
        val text = "${model.title} ${model.description}".lowercase()
        val findings = mutableListOf<Finding>()
        val recs = mutableListOf<RecommendedAction>()

        val hasMoralHazard = text.contains("moral hazard") || text.contains("hidden action") ||
            (model.informationAsymmetry && (text.contains("insur") || text.contains("monitor")))
        val hasAdverseSelection = text.contains("adverse select") || text.contains("hidden type") ||
            text.contains("lemon") || text.contains("screen")
        val hasSignalling = text.contains("signal") || text.contains("credential") ||
            text.contains("certif") || text.contains("degree") || text.contains("reputation")
        val hasMultiplePrincipals = model.stakeholders.count { it.authority >= 7 } > 1

        findings += Finding(
            "Principal-Agent problem: goal divergence under information asymmetry",
            "A principal (high authority, delegates) and one or more agents (lower authority, execute) have misaligned objectives. The agent has private information or takes unobservable actions. Without appropriate incentive design, agents will pursue their own interests at the principal's expense — this is not a moral failing, it is a structural inevitability.",
            Finding.Severity.CRITICAL
        )

        if (hasMoralHazard) {
            findings += Finding(
                "Moral hazard: agent's effort is unobservable after contract (Jensen & Meckling 1976)",
                "Moral hazard arises when the agent's effort or behaviour cannot be monitored by the principal. Once an insurance policy, employment contract, or delegation agreement is in place, the agent has reduced incentive to exert full effort because the principal bears some of the downside.\n• Solutions: output-based pay (performance bonuses), monitoring, audit, co-insurance, deductibles, repeated contracting with reputation effects.\n• Key tradeoff: stronger incentive contracts impose more risk on agents (efficiency vs insurance).",
                Finding.Severity.WARNING
            )
            recs += RecommendedAction(null, "Implement output-based incentives with monitoring",
                "Align agent incentives by tying compensation to observable outcomes. Pair with periodic audits or dashboards. Set targets that agents cannot game without genuinely improving performance. Pure monitoring without output incentives is typically less effective.",
                lens = RecommendedAction.Lens.EXPECTED)
        }

        if (hasAdverseSelection) {
            findings += Finding(
                "Adverse selection: hidden type creates selection bias before contracting (Akerlof 1970)",
                "Adverse selection occurs when one party has private information about their own type before the contract is formed. In insurance: sicker people self-select into coverage. In hiring: lower-skill candidates mimic high-skill signals. In procurement: contractors with hidden cost overruns submit winning low bids.\n• Solutions: screening mechanisms (tests, probationary periods, tiered offerings), self-selection menus where different types voluntarily reveal themselves.",
                Finding.Severity.WARNING
            )
            recs += RecommendedAction(null, "Design a self-selection screening mechanism",
                "Offer a menu of options where different agent types rationally choose different items, revealing their private information. Example: high-effort workers prefer performance pay; low-effort workers prefer fixed salaries. The choice itself is the signal.",
                lens = RecommendedAction.Lens.LONG_TERM)
        }

        if (hasSignalling) {
            findings += Finding(
                "Signalling: costly signals can credibly communicate private quality (Spence 1973)",
                "When a principal cannot directly observe agent quality, agents with genuine high quality will invest in costly signals (credentials, degrees, certifications, published track record). The signal works only if it is cheaper for high-quality types to acquire — otherwise low-quality types mimic it.\n• Education as signalling: even if education teaches nothing useful, it credibly separates high from low ability if it is harder for low-ability types to complete.\n• Institutional credentials, audit reports, peer reviews, and track records serve this function in organisational contexts.",
                Finding.Severity.INFO
            )
        }

        findings += Finding(
            "Incentive design principle: least-cost incentive alignment",
            "The optimal contract minimises agency costs (effort provision, monitoring, risk premium) while achieving the desired outcome. Holmström & Milgrom (1987): when tasks are multi-dimensional and some dimensions are unobservable, incentivising observable dimensions can distort the unobservable ones ('teaching to the test'). Design incentives holistically — partial incentive coverage creates partial gaming.",
            Finding.Severity.NOTE
        )

        if (hasMultiplePrincipals) {
            findings += Finding(
                "Multiple principals: conflicting mandates reduce agent compliance (Bernheim & Whinston 1986)",
                "When an agent serves multiple principals with different objectives, each principal free-rides on the others' oversight efforts. The agent can play principals against each other, selectively reporting to whichever principal favours the agent's preferred action. Unified principal authority or clearly delineated jurisdictions reduce this risk.",
                Finding.Severity.WARNING
            )
        }

        findings += Finding(
            "Commitment and repeated contracting",
            "In repeated principal-agent relationships, reputation becomes a contracting device. Agents who defect lose future contract value (Shapiro & Stiglitz 1984). Long-term relationships with credible threat of termination can substitute for costly monitoring — relevant when the relationship is ongoing and agent's outside options are limited.",
            Finding.Severity.INFO
        )

        recs += RecommendedAction(null, "Clarify observable performance metrics before delegating",
            "Define which outcomes are observable, measurable, and attributable to the agent before signing any contract or delegation agreement. Unobservable dimensions will not be incentivised — plan for this.",
            lens = RecommendedAction.Lens.LOW_REGRET)

        recs += RecommendedAction(null, "Build in reputation/track-record requirements",
            "Require probationary periods, pilot phases, or staged delegation. Agent reputation built through observable early performance reduces adverse selection and moral hazard for subsequent high-stakes tasks.",
            lens = RecommendedAction.Lens.LONG_TERM)

        return AnalysisResult(
            moduleId = id, moduleName = name,
            applicability = canAnalyze(model),
            findings = findings, recommendedActions = recs,
            assumptions = listOf(
                Assumption("Principal-agent framework assumes parties have different objectives and information — goal alignment by assumption invalidates the model."),
                Assumption("Moral hazard solution set assumes output is at least partially observable; if completely hidden, monitoring and output pay are both ineffective."),
                Assumption("Signalling equilibria require the signal to be cheaper for high-quality types — if costs are symmetric, separation collapses.")
            ),
            calculations = mapOf(
                "hasMoralHazard" to hasMoralHazard,
                "hasAdverseSelection" to hasAdverseSelection,
                "hasSignalling" to hasSignalling,
                "hasMultiplePrincipals" to hasMultiplePrincipals,
                "informationAsymmetryFlagged" to model.informationAsymmetry
            ),
            confidence = if (model.informationAsymmetry) 0.85 else 0.65,
            visualizations = emptyList(),
            explanation = "Principal-Agent analysis: information asymmetry between delegating principals and executing agents creates moral hazard, adverse selection, and/or signalling problems. Key references: Jensen & Meckling (1976), Akerlof (1970), Spence (1973), Holmström & Milgrom (1987)."
        )
    }
}
