package com.drkkr.strategos.templates

import com.drkkr.strategos.domain.model.*
import java.util.UUID

private fun mp(
    safety: Double = 0.0, ethics: Double = 0.0, legality: Double = 0.0,
    cost: Double = 0.0, reputation: Double = 0.0, relationships: Double = 0.0,
    wellbeing: Double = 0.0, efficiency: Double = 0.0,
    publicTrust: Double = 0.0, longTermValue: Double = 0.0
) = MultiPayoff(mapOf(
    PayoffDimension.SAFETY to safety, PayoffDimension.ETHICS to ethics,
    PayoffDimension.LEGALITY to legality, PayoffDimension.COST to cost,
    PayoffDimension.REPUTATION to reputation, PayoffDimension.RELATIONSHIPS to relationships,
    PayoffDimension.WELLBEING to wellbeing, PayoffDimension.EFFICIENCY to efficiency,
    PayoffDimension.PUBLIC_TRUST to publicTrust, PayoffDimension.LONG_TERM_VALUE to longTermValue
))

/**
 * Domain template: Institutional Leadership & Healthcare — Psychiatry sub-template.
 * Seeds a Scenario with representative stakeholders, actions, constraints, and weights
 * relevant to psychiatric department governance. All data is illustrative only.
 */
object InstitutionalHealthcareTemplate {

    enum class SubTemplate(val label: String) {
        PSYCHIATRY("Psychiatry Department"),
        GENERAL_MEDICINE("General Medicine"),
        HEALTHCARE_ADMIN("Healthcare Administration"),
        CUSTOM("Custom Healthcare")
    }

    fun create(sub: SubTemplate = SubTemplate.PSYCHIATRY): Scenario {
        val id = UUID.randomUUID().toString()
        return when (sub) {
            SubTemplate.PSYCHIATRY -> psychiatryScenario(id)
            else -> genericHealthcareScenario(id, sub.label)
        }
    }

    private fun psychiatryScenario(id: String): Scenario {
        val headId = UUID.randomUUID().toString()
        val srStaffId = UUID.randomUUID().toString()
        val adminId = UUID.randomUUID().toString()
        val patientAdvocateId = UUID.randomUUID().toString()

        val stakeholders = listOf(
            Stakeholder(headId, "Dept. Head (Psychiatry)", "Department Leader", authority = 10,
                incentives = listOf("Maintain quality of care", "Institutional standing", "Staff wellbeing"),
                goals = listOf("Resolve conflict without compromising patient safety", "Preserve team cohesion")),
            Stakeholder(srStaffId, "Senior Consultant", "Senior Medical Staff", authority = 7,
                incentives = listOf("Clinical autonomy", "Collegial respect"),
                goals = listOf("Influence treatment protocols", "Career protection")),
            Stakeholder(adminId, "Hospital Administration", "Executive Management", authority = 9,
                incentives = listOf("Cost efficiency", "Regulatory compliance", "Reputation"),
                goals = listOf("Avoid adverse outcomes", "Budget adherence")),
            Stakeholder(patientAdvocateId, "Patient Advocate / Rights Body", "Oversight", authority = 6,
                incentives = listOf("Patient rights", "Transparency"),
                goals = listOf("Ensure ethical care", "Access to information"))
        )

        val aHead1 = Action(UUID.randomUUID().toString(), headId, "Convene formal review committee", reversible = true)
        val aHead2 = Action(UUID.randomUUID().toString(), headId, "Informal mediation (one-to-one)", reversible = true)
        val aHead3 = Action(UUID.randomUUID().toString(), headId, "Escalate to ethics board", reversible = false)
        val aSr1 = Action(UUID.randomUUID().toString(), srStaffId, "Cooperate fully", reversible = true)
        val aSr2 = Action(UUID.randomUUID().toString(), srStaffId, "Partial cooperation with conditions", reversible = true)
        val aSr3 = Action(UUID.randomUUID().toString(), srStaffId, "Formal grievance filing", reversible = false)
        val aAdmin1 = Action(UUID.randomUUID().toString(), adminId, "Support department resolution", reversible = true)
        val aAdmin2 = Action(UUID.randomUUID().toString(), adminId, "Mandate external mediator", reversible = true)
        val aPatient1 = Action(UUID.randomUUID().toString(), patientAdvocateId, "Monitor and report", reversible = true)

        val constraints = listOf(
            Constraint(UUID.randomUUID().toString(), "Patient safety is non-negotiable", Constraint.Kind.SAFETY, "Any action that compromises active patient treatment is forbidden."),
            Constraint(UUID.randomUUID().toString(), "Comply with medical council regulations", Constraint.Kind.LEGAL),
            Constraint(UUID.randomUUID().toString(), "No retaliatory actions", Constraint.Kind.ETHICAL),
            Constraint(UUID.randomUUID().toString(), "Confidentiality of patient information", Constraint.Kind.LEGAL)
        )

        val weights = mapOf(
            PayoffDimension.SAFETY to 4.0,
            PayoffDimension.ETHICS to 4.0,
            PayoffDimension.LEGALITY to 4.0,
            PayoffDimension.WELLBEING to 3.0,
            PayoffDimension.RELATIONSHIPS to 3.0,
            PayoffDimension.REPUTATION to 2.5,
            PayoffDimension.PUBLIC_TRUST to 2.5,
            PayoffDimension.LONG_TERM_VALUE to 2.0,
            PayoffDimension.COST to 1.0,
            PayoffDimension.EFFICIENCY to 1.5
        )

        val beliefs = listOf(
            Belief("Senior consultant will cooperate if approached confidentially", 0.65, FactStatus.USER_ESTIMATE),
            Belief("Admin will support if liability risk is low", 0.80, FactStatus.USER_ESTIMATE),
            Belief("Ethics board referral triggers mandatory external review", 0.90, FactStatus.CONFIRMED)
        )

        val assumptions = listOf(
            AssumptionEntry("No active patient safety incident", FactStatus.CONFIRMED),
            AssumptionEntry("Power dynamics between head and senior consultant are the key leverage point", FactStatus.AI_INFERRED),
            AssumptionEntry("Payoffs estimated — not from formal survey", FactStatus.USER_ESTIMATE)
        )

        val headActions = listOf(aHead1.id, aHead2.id, aHead3.id)
        val srActions = listOf(aSr1.id, aSr2.id, aSr3.id)
        val adminActions = listOf(aAdmin1.id, aAdmin2.id)
        val patientActions = listOf(aPatient1.id)
        val payoffs = buildList {
            for ((hi, haid) in headActions.withIndex()) {
                for ((si, said) in srActions.withIndex()) {
                    for ((ai, aaid) in adminActions.withIndex()) {
                        for ((_, paid) in patientActions.withIndex()) {
                            val profile = mapOf(headId to haid, srStaffId to said, adminId to aaid, patientAdvocateId to paid)
                            val coop = (2 - hi) + (2 - si) + (1 - ai)  // 0-5: higher=more cooperative
                            val formal = hi + ai                          // 0-3: higher=more formal/escalated
                            add(PayoffEntry(headId, profile, mp(
                                safety = 5.0 + coop * 0.8, ethics = 5.0 + coop * 0.6,
                                legality = 6.0 + formal * 0.5, cost = 7.0 - hi * 1.0 - ai * 1.5,
                                reputation = 5.0 + coop * 0.6, relationships = 8.0 - si * 1.5 - hi * 0.5,
                                wellbeing = 5.0 + coop * 0.4, efficiency = 6.0 - hi * 0.5 - ai * 0.5,
                                publicTrust = 5.0 + coop * 0.5, longTermValue = 5.0 + coop * 0.6
                            )))
                            add(PayoffEntry(srStaffId, profile, mp(
                                safety = 6.0 + (2 - si) * 0.5, ethics = 6.0 - si * 0.5,
                                legality = 5.0 + formal * 0.5 + (2 - si).toDouble(), cost = 7.0 - si * 0.5,
                                reputation = 7.0 - si * 2.0, relationships = 8.0 - hi * 1.0 - si * 1.5,
                                wellbeing = 8.0 - si * 2.0 - hi * 0.5, efficiency = 6.0 - si * 0.5,
                                publicTrust = 5.0 + (2 - si) * 0.5, longTermValue = 6.0 - si * 1.5
                            )))
                            add(PayoffEntry(adminId, profile, mp(
                                safety = 6.0 + coop * 0.5, ethics = 6.0 + (1 - ai) * 0.5,
                                legality = 7.0 + formal * 0.5, cost = 8.0 - ai * 3.0 - (if (hi == 2) 2.0 else 0.0),
                                reputation = 6.0 + coop * 0.5, relationships = 7.0 - ai * 1.0 - si * 0.5,
                                wellbeing = 6.0 + (2 - si) * 0.3, efficiency = 7.0 - ai * 2.0 - hi * 0.3,
                                publicTrust = 6.0 + coop * 0.4, longTermValue = 6.0 + coop * 0.5
                            )))
                            add(PayoffEntry(patientAdvocateId, profile, mp(
                                safety = 6.0 + coop * 0.6, ethics = 6.0 + coop * 0.5,
                                legality = 7.0 + formal * 0.4, cost = 7.0,
                                reputation = 6.0 + coop * 0.3, relationships = 6.0 + (2 - si) * 0.5,
                                wellbeing = 6.0 + coop * 0.4, efficiency = 5.0 + coop * 0.3,
                                publicTrust = 7.0 + coop * 0.6, longTermValue = 6.0 + coop * 0.5
                            )))
                        }
                    }
                }
            }
        }

        return Scenario(
            id = id,
            title = "Psychiatric Dept. Leadership: Conflict Resolution",
            description = "Institutional leadership scenario involving conflicting interests between departmental leadership, senior clinical staff, administration, and patient advocacy. Illustrates multi-stakeholder decision analysis in a high-stakes healthcare context.",
            language = "en",
            stakeholders = stakeholders,
            actions = listOf(aHead1, aHead2, aHead3, aSr1, aSr2, aSr3, aAdmin1, aAdmin2, aPatient1),
            payoffs = payoffs,
            constraints = constraints,
            weights = weights,
            beliefs = beliefs,
            assumptionLog = assumptions,
            timeHorizon = 6,
            repeated = true
        )
    }

    private fun genericHealthcareScenario(id: String, label: String): Scenario = Scenario(
        id = id, title = "$label Decision Analysis", description = "Template for $label institutional decisions.",
        language = "en"
    )
}
