package com.drkkr.strategos.templates

import com.drkkr.strategos.domain.model.*
import java.util.UUID

object CrisisManagementTemplate {

    fun create(): Scenario {
        val leadershipId = UUID.randomUUID().toString()
        val unionId = UUID.randomUUID().toString()
        val mediaId = UUID.randomUUID().toString()
        val regulatorId = UUID.randomUUID().toString()

        val stakeholders = listOf(
            Stakeholder(leadershipId, "Hospital Leadership", "Executive Management", type = StakeholderType.INSTITUTION,
                authority = 9, influence = 8,
                publicGoals = listOf("Maintain public trust", "Resolve strike threat"),
                privateGoals = listOf("Avoid precedent of concessions under threat", "Protect CEO position"),
                resourcesControlled = listOf("Staffing budget", "Service continuity decisions"),
                canVeto = true, canExit = false, canDelay = true, credibility = 6),
            Stakeholder(unionId, "Nursing Union", "Labour Representative", type = StakeholderType.UNION,
                authority = 5, influence = 8,
                publicGoals = listOf("Achieve 15% pay rise", "Safe staffing ratios"),
                privateGoals = listOf("Demonstrate credibility to members", "Avoid drawn-out dispute"),
                resourcesControlled = listOf("Strike mandate", "Public sympathy", "Media access"),
                canVeto = false, canExit = true, canDelay = true, credibility = 7),
            Stakeholder(mediaId, "News Media", "Public Scrutiny", type = StakeholderType.MEDIA,
                authority = 2, influence = 9,
                publicGoals = listOf("Report on public interest story"),
                privateGoals = listOf("Break exclusive"),
                resourcesControlled = listOf("Framing power", "Public opinion"),
                canVeto = false, canExit = true, canDelay = false, credibility = 5),
            Stakeholder(regulatorId, "Health Regulator", "Oversight Body", type = StakeholderType.REGULATOR,
                authority = 10, influence = 7,
                publicGoals = listOf("Ensure patient safety during dispute"),
                privateGoals = listOf("Avoid political fallout"),
                resourcesControlled = listOf("Minimum service orders", "Regulatory sanctions"),
                canVeto = true, canExit = false, canDelay = false, credibility = 9)
        )

        val aL1 = Action(UUID.randomUUID().toString(), leadershipId, "Offer 8% — public final position",
            timeCost = 1, legalStatus = LegalStatus.PERMITTED, publicVisibility = true, implementationDifficulty = 2,
            likelyReactions = mapOf(unionId to "Likely rejection — below CPI"))
        val aL2 = Action(UUID.randomUUID().toString(), leadershipId, "Open confidential back-channel negotiation",
            timeCost = 3, legalStatus = LegalStatus.PERMITTED, publicVisibility = false, implementationDifficulty = 2)
        val aL3 = Action(UUID.randomUUID().toString(), leadershipId, "Apply for minimum service order",
            timeCost = 5, legalStatus = LegalStatus.PERMITTED, publicVisibility = true, implementationDifficulty = 3,
            likelyReactions = mapOf(unionId to "Strike escalation", mediaId to "Front-page coverage"))
        val aU1 = Action(UUID.randomUUID().toString(), unionId, "Issue 48-hour strike notice",
            timeCost = 2, legalStatus = LegalStatus.PERMITTED, publicVisibility = true, implementationDifficulty = 1,
            likelyReactions = mapOf(leadershipId to "Counter-offer or legal challenge", regulatorId to "Intervene with minimum service notice"))
        val aU2 = Action(UUID.randomUUID().toString(), unionId, "Accept mediation",
            timeCost = 7, legalStatus = LegalStatus.PERMITTED, publicVisibility = true, implementationDifficulty = 1)
        val aU3 = Action(UUID.randomUUID().toString(), unionId, "Work to rule (no overtime)",
            timeCost = 1, legalStatus = LegalStatus.PERMITTED, publicVisibility = false, implementationDifficulty = 1)

        val constraints = listOf(
            Constraint(UUID.randomUUID().toString(), "Patient safety must be maintained throughout", Constraint.Kind.SAFETY),
            Constraint(UUID.randomUUID().toString(), "Industrial action must comply with notice requirements", Constraint.Kind.LEGAL),
            Constraint(UUID.randomUUID().toString(), "No punitive action against individual staff", Constraint.Kind.ETHICAL),
            Constraint(UUID.randomUUID().toString(), "Settlement within approved budget envelope", Constraint.Kind.RESOURCE)
        )

        val beliefs = listOf(
            Belief("Union strike mandate is credible — 78% member vote", 0.90, FactStatus.CONFIRMED),
            Belief("Leadership has headroom to 12% but will not reveal it", 0.70, FactStatus.AI_INFERRED),
            Belief("Regulator will intervene if strike exceeds 48 hours", 0.80, FactStatus.USER_ESTIMATE),
            Belief("Media coverage will increase union leverage", 0.85, FactStatus.USER_ESTIMATE)
        )

        return Scenario(
            id = UUID.randomUUID().toString(),
            title = "Nursing Strike Threat — Brinkmanship",
            description = "Hospital leadership and nursing union are in a credible standoff: union holds a 78% strike mandate and leadership has made a public 'final offer'. Classic Chicken game — mutual escalation (strike + minimum service order) is worst for both, but neither can back down without appearing weak. Face-saving exit design and back-channel communication are the primary levers.",
            status = ScenarioStatus.ACTIVE,
            tags = listOf("chicken", "brinkmanship", "labour", "crisis", "healthcare"),
            decisionOwner = "CEO / Board Chair",
            institution = "Regional Hospital Trust",
            stakes = "Extreme — patient safety risk, media exposure, precedent-setting outcome, staff morale",
            stakeholders = stakeholders,
            actions = listOf(aL1, aL2, aL3, aU1, aU2, aU3),
            constraints = constraints,
            beliefs = beliefs,
            informationAsymmetry = true,
            evidenceSources = listOf(
                EvidenceSource(label = "Schelling (1960) — Strategy of Conflict", kind = EvidenceSource.Kind.OBSERVATIONAL, quality = 5),
                EvidenceSource(label = "Internal pay benchmarking report 2024", kind = EvidenceSource.Kind.INTERNAL_DATA, quality = 3)
            ),
            assumptionLog = listOf(
                AssumptionEntry("Strike mandate is genuine and members will comply", FactStatus.CONFIRMED),
                AssumptionEntry("Leadership's true maximum offer is undisclosed", FactStatus.AI_INFERRED),
                AssumptionEntry("Regulator prefers mediated settlement over forced minimum service", FactStatus.USER_ESTIMATE)
            ),
            weights = mapOf(
                PayoffDimension.SAFETY to 5.0,
                PayoffDimension.REPUTATION to 3.0,
                PayoffDimension.RELATIONSHIPS to 3.0,
                PayoffDimension.ETHICS to 3.0,
                PayoffDimension.LEGALITY to 4.0,
                PayoffDimension.COST to 2.0,
                PayoffDimension.WELLBEING to 3.0,
                PayoffDimension.EFFICIENCY to 1.0,
                PayoffDimension.PUBLIC_TRUST to 4.0,
                PayoffDimension.LONG_TERM_VALUE to 3.0
            )
        )
    }
}
