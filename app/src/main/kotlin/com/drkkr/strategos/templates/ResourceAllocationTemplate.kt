package com.drkkr.strategos.templates

import com.drkkr.strategos.domain.model.*
import java.util.UUID

object ResourceAllocationTemplate {

    fun create(): Scenario {
        val deptAId = UUID.randomUUID().toString()
        val deptBId = UUID.randomUUID().toString()
        val deptCId = UUID.randomUUID().toString()
        val financeId = UUID.randomUUID().toString()

        val stakeholders = listOf(
            Stakeholder(deptAId, "Department A", "Clinical Operations", type = StakeholderType.DEPARTMENT,
                authority = 6, influence = 7,
                publicGoals = listOf("Expand ICU capacity"),
                privateGoals = listOf("Protect existing budget allocation"),
                resourcesControlled = listOf("20 ICU beds", "Clinical staff"),
                canVeto = false, canExit = false, canDelay = true),
            Stakeholder(deptBId, "Department B", "Emergency Medicine", type = StakeholderType.DEPARTMENT,
                authority = 6, influence = 6,
                publicGoals = listOf("Reduce ED boarding time"),
                privateGoals = listOf("Acquire dedicated step-down unit"),
                resourcesControlled = listOf("ED throughput data", "Discharge protocols"),
                canVeto = false, canExit = false, canDelay = true),
            Stakeholder(deptCId, "Department C", "Elective Surgery", type = StakeholderType.DEPARTMENT,
                authority = 5, influence = 5,
                publicGoals = listOf("Maintain surgical volume"),
                privateGoals = listOf("Protect theatre time allocation"),
                resourcesControlled = listOf("4 operating theatres"),
                canVeto = false, canExit = true, canDelay = false),
            Stakeholder(financeId, "Finance Committee", "Budget Governance", type = StakeholderType.COMMITTEE,
                authority = 10, influence = 9,
                publicGoals = listOf("Allocate within budget", "Maximize patient throughput per dollar"),
                privateGoals = listOf("Minimize political conflict"),
                resourcesControlled = listOf("Annual budget pool", "Capital expenditure approval"),
                canVeto = true, canExit = false, canDelay = true, credibility = 8)
        )

        val aA1 = Action(UUID.randomUUID().toString(), deptAId, "Submit standard budget request", timeCost = 7, implementationDifficulty = 1)
        val aA2 = Action(UUID.randomUUID().toString(), deptAId, "Form coalition with Dept B", timeCost = 14, implementationDifficulty = 2)
        val aB1 = Action(UUID.randomUUID().toString(), deptBId, "Submit standard budget request", timeCost = 7, implementationDifficulty = 1)
        val aB2 = Action(UUID.randomUUID().toString(), deptBId, "Present utilization data evidence", timeCost = 5, implementationDifficulty = 2)
        val aC1 = Action(UUID.randomUUID().toString(), deptCId, "Submit standard budget request", timeCost = 7, implementationDifficulty = 1)
        val aF1 = Action(UUID.randomUUID().toString(), financeId, "Equal proportional allocation", timeCost = 1, implementationDifficulty = 1)
        val aF2 = Action(UUID.randomUUID().toString(), financeId, "Evidence-based needs allocation", timeCost = 21, implementationDifficulty = 3)
        val aF3 = Action(UUID.randomUUID().toString(), financeId, "Defer decision pending audit", timeCost = 60, implementationDifficulty = 2)

        val constraints = listOf(
            Constraint(UUID.randomUUID().toString(), "Total allocation cannot exceed approved budget", Constraint.Kind.RESOURCE),
            Constraint(UUID.randomUUID().toString(), "No reduction in emergency capacity", Constraint.Kind.SAFETY),
            Constraint(UUID.randomUUID().toString(), "Procurement must follow public tender rules", Constraint.Kind.LEGAL)
        )

        val beliefs = listOf(
            Belief("Dept A and B will form a coalition if uncoordinated", 0.65, FactStatus.AI_INFERRED),
            Belief("Finance will use utilization data if provided credibly", 0.75, FactStatus.USER_ESTIMATE),
            Belief("Deferral will trigger external review", 0.55, FactStatus.USER_ESTIMATE)
        )

        return Scenario(
            id = UUID.randomUUID().toString(),
            title = "Annual Departmental Budget Allocation",
            description = "Three hospital departments compete for a limited shared budget pool. Finance committee must allocate funds. Classic common-pool resource dynamic: each department has incentive to request more than their fair share, but collectively over-requesting depletes the shared resource and triggers either deferral or external audit.",
            status = ScenarioStatus.ACTIVE,
            tags = listOf("budget", "resource-allocation", "commons", "hospital"),
            decisionOwner = "Finance Committee Chair",
            institution = "Regional Hospital",
            stakes = "High — budget decisions affect patient capacity, staff morale, and service delivery for 12 months",
            stakeholders = stakeholders,
            actions = listOf(aA1, aA2, aB1, aB2, aC1, aF1, aF2, aF3),
            constraints = constraints,
            beliefs = beliefs,
            informationAsymmetry = true,
            evidenceSources = listOf(
                EvidenceSource(label = "Departmental utilization reports FY2024", kind = EvidenceSource.Kind.INTERNAL_DATA, quality = 3),
                EvidenceSource(label = "Ostrom (1990) — governing the commons", kind = EvidenceSource.Kind.OBSERVATIONAL, quality = 4)
            ),
            assumptionLog = listOf(
                AssumptionEntry("Budget pool is fixed and non-expandable this cycle", FactStatus.CONFIRMED),
                AssumptionEntry("Departmental requests are partially inflated", FactStatus.AI_INFERRED)
            ),
            weights = Scenario.defaultWeights()
        )
    }
}
