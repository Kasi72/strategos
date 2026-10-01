package com.drkkr.strategos.framework.behavioral

import com.drkkr.strategos.domain.framework.*
import com.drkkr.strategos.domain.model.Scenario

class CommonPoolResourceModule : FrameworkModule {
    override val id = "common-pool-resource"
    override val name = "Common-Pool Resource & Tragedy of Commons"
    override val version = "1.0"
    override val category = "Behavioral"

    override fun canAnalyze(model: Scenario): Applicability {
        val text = "${model.title} ${model.description}".lowercase()
        val kwHits = listOf(
            "common pool", "commons", "shared resource", "overuse", "depletion",
            "tragedy", "overfishing", "overgrazing", "aquifer", "bandwidth",
            "capacity", "congestion", "exhaustible", "rivalry", "non-exclud",
            "hospital bed", "icu", "waiting list", "staff resource", "budget pool",
            "shared infrastructure", "public land", "water", "fishery", "forest"
        ).count { text.contains(it) }
        val n = model.stakeholders.size
        return when {
            kwHits >= 4 -> Applicability(0.88, listOf("Strong common-pool resource language ($kwHits keyword hits)."))
            kwHits >= 2 && n >= 3 -> Applicability(0.72, listOf("Shared resource language + $n actors — CPR dynamics likely."))
            kwHits >= 1 -> Applicability(0.40, listOf("Possible common-pool resource context."))
            n >= 4 && text.contains("resourc") -> Applicability(0.30, listOf("Multi-actor resource scenario — possible CPR."))
            else -> Applicability(0.0, listOf("No common-pool resource signals detected."))
        }
    }

    override fun analyze(model: Scenario): AnalysisResult {
        val n = model.stakeholders.size
        val text = "${model.title} ${model.description}".lowercase()
        val findings = mutableListOf<Finding>()
        val recs = mutableListOf<RecommendedAction>()

        findings += Finding(
            "Tragedy of the Commons: individual rationality destroys shared resource (Hardin 1968)",
            "When a resource is rival (one user's consumption reduces availability for others) but non-excludable (difficult to prevent access), rational individual exploitation leads to collective depletion. Each of $n actors has an incentive to use more than their share — the cost of overuse is shared, but the benefit is private.",
            Finding.Severity.CRITICAL
        )

        findings += Finding(
            "Ostrom's alternative: self-governing institutions can prevent tragedy (Ostrom 1990)",
            "Elinor Ostrom (Nobel Prize 2009) demonstrated that neither privatisation nor state regulation is necessary to manage commons. Self-governing communities with clear rules, monitoring, graduated sanctions, and conflict resolution mechanisms consistently prevent depletion:\n1. Clearly defined resource boundaries\n2. Rules proportional to local conditions\n3. Collective choice — users participate in rule modification\n4. Monitoring of users and the resource\n5. Graduated sanctions\n6. Conflict resolution mechanisms\n7. External recognition of self-governance rights",
            Finding.Severity.INFO
        )

        if (n <= 15) {
            findings += Finding(
                "Small group advantage: cooperation more likely in groups of $n",
                "Ostrom's evidence: smaller groups with repeated interaction, face-to-face contact, and reputational stakes successfully self-govern commons far more often than large anonymous groups. With only $n actors, face-to-face governance with agreed rules is feasible.",
                Finding.Severity.INFO
            )
        } else {
            findings += Finding(
                "Large group ($n actors): nested governance likely needed",
                "Groups above ~15–20 actors rarely self-govern commons effectively without nested subgroup structure. Ostrom: polycentric governance (multiple overlapping institutions at different scales) outperforms both top-down regulation and pure privatisation for large-group commons.",
                Finding.Severity.WARNING
            )
        }

        findings += Finding(
            "Three governance regimes: open access, private property, managed commons",
            "• Open access (no rules): inevitable depletion — Hardin's original scenario.\n• Full privatisation: prevents overuse but raises equity, transaction cost, and excludability concerns; works only when property rights are definable and enforceable.\n• Managed commons (Ostrom): requires clear boundaries, participation, monitoring, sanctions, and conflict resolution. Often outperforms both alternatives on efficiency and equity combined.",
            Finding.Severity.NOTE
        )

        val hasCapacityConstraint = model.constraints.any {
            it.kind == com.drkkr.strategos.domain.model.Constraint.Kind.RESOURCE ||
            it.label.contains("capacity", ignoreCase = true) ||
            it.label.contains("budget", ignoreCase = true)
        }

        if (hasCapacityConstraint) {
            findings += Finding(
                "Hard capacity constraint active — resource ceiling is binding",
                "One or more capacity constraints are specified. Users who collectively exceed this ceiling impose real costs on all others. The constraint makes CPR governance urgent — without allocation rules, demand will exceed supply and the constraint will be violated.",
                Finding.Severity.WARNING
            )
        }

        // Domain-specific tailored finding
        val domainFinding = when {
            text.contains("hospital") || text.contains("icu") || text.contains("bed") || text.contains("healthcare") || text.contains("patient") ->
                Triple(
                    "Healthcare commons: ICU/bed allocation is time-critical and life-critical",
                    "Hospital common-pool resources (ICU beds, ventilators, OR slots, specialist time) have three critical differences from classical commons:\n" +
                    "1. Urgency: allocation decisions are real-time — traditional governance rules must be codified in advance (e.g., SOFA scores, triage protocols).\n" +
                    "2. Life-or-death stakes: 'depletion' has irreversible human consequences — graduated sanctions are replaced by clinical protocols and rationing ethics frameworks.\n" +
                    "3. Regulatory environment: clinical resource allocation is legally and ethically governed (NICE, CMS, clinical ethics committees).\n\n" +
                    "Recommendation: establish formal rationing protocols before capacity crisis, not during. The Ostrom rules apply, but operationalised as triage and allocation algorithms rather than community negotiation.",
                    Finding.Severity.CRITICAL
                )
            text.contains("fish") || text.contains("aquifer") || text.contains("water") || text.contains("ocean") || text.contains("marine") ->
                Triple(
                    "Natural resource commons: biological depletion is irreversible below recovery threshold",
                    "Natural resource commons (fisheries, aquifers, forests) face a non-linear threshold: once the resource stock falls below a recovery threshold, restoration may be impossible even with zero extraction.\n\n" +
                    "Critical threshold management:\n• Define a minimum viable stock level — extraction rules must prevent crossing this threshold regardless of short-term economic incentives.\n• Tradeable quotas (ITQs) have shown 30–50% efficiency improvements in fisheries vs command-and-control regulation.\n• Adaptive management: extraction quotas should update annually based on stock monitoring data.\n\n" +
                    "Ostrom's design principle 2 ('rules proportional to local conditions') is especially important here — international fisheries agreements have failed when uniform quotas ignored local ecosystem differences.",
                    Finding.Severity.WARNING
                )
            text.contains("bandwidth") || text.contains("spectrum") || text.contains("network") || text.contains("internet") || text.contains("congestion") ->
                Triple(
                    "Digital commons: congestion pricing and technical governance mechanisms",
                    "Digital network resources (bandwidth, spectrum, compute) differ from physical commons:\n• Excludability is technically feasible — congestion pricing (TCP/IP's implicit pricing, cellular QoS) can prevent tragedy without Ostrom governance.\n• Depletion is temporary, not permanent — bandwidth refreshes each second, unlike fish stocks.\n\n" +
                    "Governance mechanisms: Quality of Service (QoS) tiers, dynamic spectrum allocation, congestion pricing (Pigou tax), and technical protocols (TCP slow-start) have successfully managed digital commons at scale without formal self-governance institutions.",
                    Finding.Severity.NOTE
                )
            text.contains("budget") || text.contains("staffing") || text.contains("workforce") || text.contains("talent") || text.contains("shared service") ->
                Triple(
                    "Organisational commons: shared budget/staff pools and internal market failures",
                    "Organisational shared resources (shared services, budget pools, specialist staff) exhibit CPR dynamics:\n• Teams over-request shared resources, knowing unused budget or slack returns to the pool.\n• Tragedy manifests as chronic under-staffing of shared services, contested allocation of specialists, and budget hoarding.\n\n" +
                    "Effective internal governance: charge-back accounting (teams pay for shared services they consume), transparent usage dashboards, and cross-functional allocation committees with rotating membership (Ostrom principle 3: collective choice by users).",
                    Finding.Severity.INFO
                )
            else -> null
        }
        if (domainFinding != null) {
            findings += Finding(domainFinding.first, domainFinding.second, domainFinding.third)
        }

        findings += Finding(
            "Communication and monitoring are the cheapest CPR interventions",
            "Faysse (2005) meta-analysis: communication alone (without formal enforcement) reduced overuse by 40–60% in experimental commons. Combined with low-cost monitoring (usage tracking, peer observation), outcomes approach socially optimal allocation at a fraction of regulatory cost.",
            Finding.Severity.INFO
        )

        recs += RecommendedAction(null, "Define allocation rules collectively with all $n users",
            "Draft usage quotas or time-based allocation with all stakeholders present. Ostrom's principle: rules made by users are more likely to be obeyed than externally imposed rules. Document rules, make them visible, and establish who monitors compliance.",
            lens = RecommendedAction.Lens.LONG_TERM)

        recs += RecommendedAction(null, "Implement graduated sanctions for overuse",
            "Small initial penalties (warning, reprimand) with escalation for repeat violations. Graduated sanctions are more effective than zero-tolerance or severe penalties because they maintain cooperation while deterring defection. Ostrom: graduated sanctions preserve relationship quality.",
            lens = RecommendedAction.Lens.ETHICAL_LEGAL)

        recs += RecommendedAction(null, "Establish transparent usage monitoring",
            "Usage dashboards, peer-observable consumption records, or periodic reporting. Monitoring does not require a central authority — peer monitoring in small groups is equally effective and dramatically cheaper. Key: monitoring must be credible, not just nominal.",
            lens = RecommendedAction.Lens.RELATIONSHIP)

        if (n >= 5) {
            recs += RecommendedAction(null, "Consider quota-trading or weighted allocation by need",
            "If usage needs differ across actors, tradeable quotas or need-weighted allocation (e.g., severity-weighted hospital bed allocation) can achieve efficiency without equal-share rules that ignore heterogeneity.",
            lens = RecommendedAction.Lens.EXPECTED)
        }

        return AnalysisResult(
            moduleId = id, moduleName = name,
            applicability = canAnalyze(model),
            findings = findings, recommendedActions = recs,
            assumptions = listOf(
                Assumption("CPR framework applies when resource is rival (subtractable) and non-excludable or hard to exclude."),
                Assumption("Ostrom governance principles derived from field cases; laboratory experiments confirm core findings but smaller in magnitude."),
                Assumption("Small group advantage applies below ~15 actors; larger groups need nested or hierarchical governance.")
            ),
            calculations = mapOf("playerCount" to n, "hasCapacityConstraint" to hasCapacityConstraint),
            confidence = 0.72,
            visualizations = emptyList(),
            explanation = "Common-Pool Resource analysis. Hardin (1968) tragedy of the commons vs Ostrom (1990) self-governance alternative. Seven Ostrom principles for sustainable commons management. Key lever: collective rules + monitoring + graduated sanctions."
        )
    }
}
