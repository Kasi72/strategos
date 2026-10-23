package com.drkkr.strategos.domain.agent

import com.drkkr.strategos.domain.model.Action
import com.drkkr.strategos.domain.model.AssumptionEntry
import com.drkkr.strategos.domain.model.Belief
import com.drkkr.strategos.domain.model.ChangeRecord
import com.drkkr.strategos.domain.model.Constraint
import com.drkkr.strategos.domain.model.FactStatus
import com.drkkr.strategos.domain.model.GameRole
import com.drkkr.strategos.domain.model.LegalStatus
import com.drkkr.strategos.domain.model.MultiPayoff
import com.drkkr.strategos.domain.model.PayoffEntry
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.model.Stakeholder
import com.drkkr.strategos.domain.model.StakeholderType

data class ApplyResult(val scenario: Scenario, val applied: Int, val skipped: List<Pair<Int, String>>)

/** Applies a reviewed change set to a scenario (blueprint §8.10). Agent output never becomes CONFIRMED (I3). */
object ChangeSetApplier {
    const val CHANGED_BY = "strategos-counsel"

    fun apply(
        base: Scenario, ops: List<ChangeOp>, decisions: List<OpDecision>, refIds: Map<String, String>,
        userTexts: List<String>, changeSetSummary: String
    ): ApplyResult {
        val byIndex = decisions.associateBy { it.index }
        val skippedRefs = mutableSetOf<String>()
        val skipped = mutableListOf<Pair<Int, String>>()
        var working = base
        var applied = 0

        ops.forEachIndexed { i, proposed ->
            val decision = byIndex[i]
            // BLUEPRINT-QUESTION: an op with no decision is treated as rejected.
            val op = when (decision?.decision) {
                Decision.ACCEPT -> proposed
                Decision.EDIT -> decision.editedOp
                Decision.REJECT, null -> null
            }
            val reason = when {
                op == null -> if (decision?.decision == Decision.EDIT) "edit_missing" else "rejected"
                referencedRefs(op).any { it in skippedRefs } -> "depends_on_skipped"
                else -> ChangeSetValidator.recheck(working, op, refIds)
            }
            if (reason != null || op == null) {
                proposed.ref?.let { if (proposed.type.isAdd) skippedRefs += it }
                skipped += i to (reason ?: "rejected")
                return@forEachIndexed
            }
            val verified = op.source == OpSource.USER_STATED && QuoteVerifier.isVerified(op.quote, userTexts)
            val status = if (decision?.decision == Decision.EDIT || verified) FactStatus.USER_ESTIMATE else FactStatus.AI_INFERRED
            val note = op.rationale.orEmpty() + if (verified) " — \"${op.quote}\"" else ""
            working = applyOne(working, op, refIds, status, note)
            applied++
        }

        val record = ChangeRecord(
            changedBy = CHANGED_BY,
            summary = "Applied AI change set: $changeSetSummary ($applied of ${ops.size})",
            previousVersion = base.version,
            previousLifecycleStage = base.lifecycleStage,
            reason = "User applied proposed changes"
        )
        return ApplyResult(working.copy(changeHistory = working.changeHistory + record), applied, skipped)
    }

    /** Refs this op points at (not the one it defines). */
    private fun referencedRefs(op: ChangeOp): List<String> =
        listOfNotNull(op.stakeholder, op.targetId) + op.profile.orEmpty().flatMap { listOf(it.stakeholder, it.action) }

    private fun applyOne(s: Scenario, op: ChangeOp, refIds: Map<String, String>, status: FactStatus, note: String): Scenario {
        fun resolve(x: String?): String? = x?.let { refIds[it] ?: it }
        val target = resolve(op.targetId)
        return when (op.type) {
            OpType.SET_SCENARIO_FIELDS -> {
                val f = op.scenarioFields ?: return s
                s.copy(
                    title = f.title ?: s.title, description = f.description ?: s.description,
                    decisionOwner = f.decisionOwner ?: s.decisionOwner, stakes = f.stakes ?: s.stakes,
                    timeHorizonLabel = f.timeHorizonLabel ?: s.timeHorizonLabel, institution = f.institution ?: s.institution,
                    location = f.location ?: s.location, repeated = f.repeated ?: s.repeated,
                    informationAsymmetry = f.informationAsymmetry ?: s.informationAsymmetry, zeroSum = f.zeroSum ?: s.zeroSum
                )
            }
            OpType.ADD_STAKEHOLDER -> s.copy(
                stakeholders = s.stakeholders + Stakeholder(
                    id = refIds.getValue(op.ref!!), name = op.name!!, role = op.role.orEmpty(),
                    type = op.stakeholderType ?: StakeholderType.OTHER,
                    authority = op.authority ?: 0, influence = op.influence ?: 0, credibility = op.credibility ?: 5,
                    canVeto = op.canVeto ?: false, canExit = op.canExit ?: false, canDelay = op.canDelay ?: false,
                    goals = op.goals.orEmpty(), fears = op.fears.orEmpty(), incentives = op.incentives.orEmpty(),
                    notes = op.description.orEmpty(), gameRole = op.gameRole ?: GameRole.INFLUENCER,
                    factStatus = status, provenanceNote = note
                )
            )
            OpType.UPDATE_STAKEHOLDER -> s.copy(stakeholders = s.stakeholders.map { st ->
                if (st.id != target) st else st.copy(
                    name = op.name ?: st.name, role = op.role ?: st.role, type = op.stakeholderType ?: st.type,
                    gameRole = op.gameRole ?: st.gameRole, authority = op.authority ?: st.authority,
                    influence = op.influence ?: st.influence, credibility = op.credibility ?: st.credibility,
                    canVeto = op.canVeto ?: st.canVeto, canExit = op.canExit ?: st.canExit, canDelay = op.canDelay ?: st.canDelay,
                    goals = op.goals ?: st.goals, fears = op.fears ?: st.fears, incentives = op.incentives ?: st.incentives,
                    notes = op.description ?: st.notes, factStatus = status, provenanceNote = note
                )
            })
            OpType.REMOVE_STAKEHOLDER -> {
                val removedActions = s.actions.filter { it.stakeholderId == target }.map { it.id }.toSet()
                s.copy(
                    stakeholders = s.stakeholders.filter { it.id != target }.map {
                        it.copy(coalitionsWith = it.coalitionsWith - target!!, trustRelationships = it.trustRelationships - target)
                    },
                    actions = s.actions.filter { it.stakeholderId != target },
                    payoffs = s.payoffs.filter { it.stakeholderId != target && target !in it.actionProfile.keys },
                    constraintAssessments = s.constraintAssessments.filter { it.actionId !in removedActions }
                )
            }
            OpType.ADD_ACTION -> s.copy(
                actions = s.actions + Action(
                    id = refIds.getValue(op.ref!!), stakeholderId = resolve(op.stakeholder)!!, label = op.label!!,
                    description = op.description.orEmpty(), cost = op.cost ?: 0.0, timeCost = op.timeCostDays ?: 0,
                    reversible = op.reversible ?: true, legalStatus = op.legalStatus ?: LegalStatus.UNCLEAR,
                    implementationDifficulty = op.difficulty ?: 3, factStatus = status, provenanceNote = note
                )
            )
            OpType.UPDATE_ACTION -> s.copy(actions = s.actions.map { a ->
                if (a.id != target) a else a.copy(
                    label = op.label ?: a.label, description = op.description ?: a.description, cost = op.cost ?: a.cost,
                    timeCost = op.timeCostDays ?: a.timeCost, reversible = op.reversible ?: a.reversible,
                    legalStatus = op.legalStatus ?: a.legalStatus, implementationDifficulty = op.difficulty ?: a.implementationDifficulty,
                    factStatus = status, provenanceNote = note
                )
            })
            OpType.REMOVE_ACTION -> s.copy(
                actions = s.actions.filter { it.id != target },
                payoffs = s.payoffs.filter { target !in it.actionProfile.values },
                constraintAssessments = s.constraintAssessments.filter { it.actionId != target }
            )
            OpType.SET_PAYOFF -> {
                val sid = resolve(op.stakeholder)!!
                val profile = op.profile.orEmpty().associate { resolve(it.stakeholder)!! to resolve(it.action)!! }
                val entry = PayoffEntry(sid, profile, MultiPayoff(op.values.orEmpty().associate { it.dimension to it.value }), status, note)
                val i = s.payoffs.indexOfFirst { it.stakeholderId == sid && it.actionProfile == profile }
                s.copy(payoffs = if (i < 0) s.payoffs + entry else s.payoffs.toMutableList().also { it[i] = entry })
            }
            OpType.ADD_CONSTRAINT -> s.copy(
                constraints = s.constraints + Constraint(
                    id = refIds.getValue(op.ref!!), label = op.label!!, kind = op.constraintKind!!.kind,
                    description = op.description.orEmpty(), factStatus = status, provenanceNote = note
                )
            )
            OpType.ADD_BELIEF -> s.copy(beliefs = s.beliefs + Belief(op.label!!, op.probability!!, status, note))
            OpType.ADD_ASSUMPTION -> s.copy(assumptionLog = s.assumptionLog + AssumptionEntry(op.label ?: op.description!!, status, note))
        }
    }
}
