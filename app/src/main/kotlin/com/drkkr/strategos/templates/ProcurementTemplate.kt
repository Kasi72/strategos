package com.drkkr.strategos.templates

import com.drkkr.strategos.domain.model.*
import java.util.UUID

object ProcurementTemplate {

    fun create(): Scenario {
        val committeeId = UUID.randomUUID().toString()
        val vendorAId = UUID.randomUUID().toString()
        val vendorBId = UUID.randomUUID().toString()
        val clinicalLeadId = UUID.randomUUID().toString()

        val stakeholders = listOf(
            Stakeholder(committeeId, "Procurement Committee", "Principal / Buyer", type = StakeholderType.COMMITTEE,
                authority = 10, influence = 8,
                publicGoals = listOf("Select best-value vendor", "Comply with tender rules"),
                privateGoals = listOf("Avoid post-award problems", "Minimize implementation risk"),
                resourcesControlled = listOf("Contract award authority", "Evaluation criteria"),
                canVeto = true, canExit = false, canDelay = true, credibility = 8),
            Stakeholder(vendorAId, "Vendor A (Incumbent)", "Agent / Supplier", type = StakeholderType.VENDOR,
                authority = 3, influence = 6,
                publicGoals = listOf("Win contract renewal"),
                privateGoals = listOf("Maintain margin — conceal true cost structure", "Lock in 5-year deal"),
                resourcesControlled = listOf("Existing implementation knowledge", "Support relationships"),
                canVeto = false, canExit = true, canDelay = false, credibility = 5),
            Stakeholder(vendorBId, "Vendor B (Challenger)", "Agent / Supplier", type = StakeholderType.VENDOR,
                authority = 3, influence = 5,
                publicGoals = listOf("Win new contract — demonstrate capability"),
                privateGoals = listOf("Underbid to win, recover margin in change orders"),
                resourcesControlled = listOf("Lower headline price", "New technology offering"),
                canVeto = false, canExit = true, canDelay = false, credibility = 4),
            Stakeholder(clinicalLeadId, "Clinical Lead", "End-User Representative", type = StakeholderType.INDIVIDUAL,
                authority = 5, influence = 7,
                publicGoals = listOf("Best clinical functionality"),
                privateGoals = listOf("Preserve existing workflows", "Minimize change burden"),
                resourcesControlled = listOf("Clinical sign-off", "User adoption"),
                canVeto = false, canExit = false, canDelay = true, credibility = 7)
        )

        val aC1 = Action(UUID.randomUUID().toString(), committeeId, "Award on lowest price",
            timeCost = 1, legalStatus = LegalStatus.PERMITTED, implementationDifficulty = 1,
            likelyReactions = mapOf(clinicalLeadId to "Resistance if functionality inferior"))
        val aC2 = Action(UUID.randomUUID().toString(), committeeId, "Award on weighted quality-price score",
            timeCost = 14, legalStatus = LegalStatus.PERMITTED, implementationDifficulty = 3)
        val aC3 = Action(UUID.randomUUID().toString(), committeeId, "Require pilot/proof of concept",
            timeCost = 60, legalStatus = LegalStatus.PERMITTED, implementationDifficulty = 3,
            likelyReactions = mapOf(vendorBId to "May reveal implementation gap"))
        val aA1 = Action(UUID.randomUUID().toString(), vendorAId, "Submit standard bid with incumbent premium",
            timeCost = 7, legalStatus = LegalStatus.PERMITTED, implementationDifficulty = 1)
        val aA2 = Action(UUID.randomUUID().toString(), vendorAId, "Offer reference site visits and track record",
            timeCost = 14, legalStatus = LegalStatus.PERMITTED, implementationDifficulty = 2)
        val aB1 = Action(UUID.randomUUID().toString(), vendorBId, "Submit aggressive low-price bid",
            timeCost = 7, legalStatus = LegalStatus.PERMITTED, implementationDifficulty = 1)
        val aB2 = Action(UUID.randomUUID().toString(), vendorBId, "Offer co-design and joint implementation",
            timeCost = 21, legalStatus = LegalStatus.PERMITTED, implementationDifficulty = 3)
        val aCl1 = Action(UUID.randomUUID().toString(), clinicalLeadId, "Endorse incumbent based on familiarity",
            timeCost = 1, legalStatus = LegalStatus.PERMITTED, implementationDifficulty = 1)
        val aCl2 = Action(UUID.randomUUID().toString(), clinicalLeadId, "Request functional demonstration",
            timeCost = 5, legalStatus = LegalStatus.PERMITTED, implementationDifficulty = 2)

        val constraints = listOf(
            Constraint(UUID.randomUUID().toString(), "Must comply with public procurement regulations", Constraint.Kind.LEGAL),
            Constraint(UUID.randomUUID().toString(), "Contract value within approved capital budget", Constraint.Kind.RESOURCE),
            Constraint(UUID.randomUUID().toString(), "Clinical safety requirements must be met", Constraint.Kind.SAFETY),
            Constraint(UUID.randomUUID().toString(), "No post-award negotiation of material terms", Constraint.Kind.LEGAL)
        )

        val beliefs = listOf(
            Belief("Vendor B's low price conceals material implementation costs", 0.65, FactStatus.AI_INFERRED),
            Belief("Incumbent has overstated upgrade complexity to deter switching", 0.55, FactStatus.AI_INFERRED),
            Belief("Clinical lead will block implementation if not consulted", 0.80, FactStatus.USER_ESTIMATE),
            Belief("A pilot requirement will disqualify Vendor B", 0.60, FactStatus.USER_ESTIMATE)
        )

        return Scenario(
            id = UUID.randomUUID().toString(),
            title = "IT System Procurement — Vendor Selection",
            description = "Hospital procurement committee must select a clinical information system vendor. Classic principal-agent problem with adverse selection: vendors have private information about true total cost of ownership, implementation risk, and capability. Incumbent uses relationship capital; challenger uses low headline price. Committee must design evaluation process to reveal true quality.",
            status = ScenarioStatus.DRAFT,
            tags = listOf("procurement", "principal-agent", "adverse-selection", "vendor", "IT"),
            decisionOwner = "Chief Information Officer",
            institution = "Regional Hospital",
            stakes = "High — 5-year contract, £2.4M total value, clinical workflow impact",
            stakeholders = stakeholders,
            actions = listOf(aC1, aC2, aC3, aA1, aA2, aB1, aB2, aCl1, aCl2),
            constraints = constraints,
            beliefs = beliefs,
            informationAsymmetry = true,
            evidenceSources = listOf(
                EvidenceSource(label = "Akerlof (1970) — market for lemons", kind = EvidenceSource.Kind.OBSERVATIONAL, quality = 5),
                EvidenceSource(label = "Internal IT system requirements specification", kind = EvidenceSource.Kind.INTERNAL_DATA, quality = 4),
                EvidenceSource(label = "NHS Digital procurement guidance 2023", kind = EvidenceSource.Kind.POLICY_DOCUMENT, quality = 4)
            ),
            assumptionLog = listOf(
                AssumptionEntry("Both vendors have capacity to deliver", FactStatus.UNKNOWN),
                AssumptionEntry("Committee evaluation criteria are not yet public", FactStatus.CONFIRMED),
                AssumptionEntry("Clinical lead endorsement is de facto required for successful implementation", FactStatus.USER_ESTIMATE)
            ),
            weights = Scenario.defaultWeights()
        )
    }
}
