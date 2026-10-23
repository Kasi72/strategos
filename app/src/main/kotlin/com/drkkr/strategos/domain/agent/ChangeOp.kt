package com.drkkr.strategos.domain.agent

import com.drkkr.strategos.domain.model.Constraint
import com.drkkr.strategos.domain.model.GameRole
import com.drkkr.strategos.domain.model.LegalStatus
import com.drkkr.strategos.domain.model.PayoffDimension
import com.drkkr.strategos.domain.model.StakeholderType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Kotlin mirror of agent/schemas/change_op.yaml (blueprint §7.7). */
@Serializable
data class ChangeOp(
    @SerialName("type") val type: OpType,
    @SerialName("ref") val ref: String? = null,
    @SerialName("target_id") val targetId: String? = null,
    @SerialName("stakeholder") val stakeholder: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("role") val role: String? = null,
    @SerialName("stakeholder_type") val stakeholderType: StakeholderType? = null,
    @SerialName("game_role") val gameRole: GameRole? = null,
    @SerialName("authority") val authority: Int? = null,
    @SerialName("influence") val influence: Int? = null,
    @SerialName("credibility") val credibility: Int? = null,
    @SerialName("can_veto") val canVeto: Boolean? = null,
    @SerialName("can_exit") val canExit: Boolean? = null,
    @SerialName("can_delay") val canDelay: Boolean? = null,
    @SerialName("goals") val goals: List<String>? = null,
    @SerialName("fears") val fears: List<String>? = null,
    @SerialName("incentives") val incentives: List<String>? = null,
    @SerialName("label") val label: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("reversible") val reversible: Boolean? = null,
    @SerialName("legal_status") val legalStatus: LegalStatus? = null,
    @SerialName("difficulty") val difficulty: Int? = null,
    @SerialName("cost") val cost: Double? = null,
    @SerialName("time_cost_days") val timeCostDays: Int? = null,
    @SerialName("profile") val profile: List<ProfilePick>? = null,
    @SerialName("values") val values: List<DimValue>? = null,
    @SerialName("constraint_kind") val constraintKind: OpConstraintKind? = null,
    @SerialName("probability") val probability: Double? = null,
    @SerialName("scenario_fields") val scenarioFields: ScenarioFieldsPatch? = null,
    @SerialName("rationale") val rationale: String? = null,
    @SerialName("source") val source: OpSource? = null,
    @SerialName("quote") val quote: String? = null
) {
    companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
enum class OpType {
    @SerialName("set_scenario_fields") SET_SCENARIO_FIELDS,
    @SerialName("add_stakeholder") ADD_STAKEHOLDER,
    @SerialName("update_stakeholder") UPDATE_STAKEHOLDER,
    @SerialName("remove_stakeholder") REMOVE_STAKEHOLDER,
    @SerialName("add_action") ADD_ACTION,
    @SerialName("update_action") UPDATE_ACTION,
    @SerialName("remove_action") REMOVE_ACTION,
    @SerialName("set_payoff") SET_PAYOFF,
    @SerialName("add_constraint") ADD_CONSTRAINT,
    @SerialName("add_belief") ADD_BELIEF,
    @SerialName("add_assumption") ADD_ASSUMPTION;

    val isAdd: Boolean get() = this in setOf(ADD_STAKEHOLDER, ADD_ACTION, ADD_CONSTRAINT, ADD_BELIEF, ADD_ASSUMPTION)
    val isUpdateOrRemove: Boolean get() = this in setOf(UPDATE_STAKEHOLDER, REMOVE_STAKEHOLDER, UPDATE_ACTION, REMOVE_ACTION)
}

@Serializable
enum class OpSource { @SerialName("ai_inferred") AI_INFERRED, @SerialName("user_stated") USER_STATED }

/** The schema's constraint kinds. USER_HARD is deliberately absent so the agent can't express it (I4). */
@Serializable
enum class OpConstraintKind(val kind: Constraint.Kind) {
    LEGAL(Constraint.Kind.LEGAL), ETHICAL(Constraint.Kind.ETHICAL), SAFETY(Constraint.Kind.SAFETY),
    POLICY(Constraint.Kind.POLICY), PHYSICAL(Constraint.Kind.PHYSICAL), RESOURCE(Constraint.Kind.RESOURCE)
}

@Serializable
data class ProfilePick(val stakeholder: String, val action: String)

@Serializable
data class DimValue(val dimension: PayoffDimension, val value: Double)

@Serializable
data class ScenarioFieldsPatch(
    @SerialName("title") val title: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("decision_owner") val decisionOwner: String? = null,
    @SerialName("stakes") val stakes: String? = null,
    @SerialName("time_horizon_label") val timeHorizonLabel: String? = null,
    @SerialName("institution") val institution: String? = null,
    @SerialName("location") val location: String? = null,
    @SerialName("repeated") val repeated: Boolean? = null,
    @SerialName("information_asymmetry") val informationAsymmetry: Boolean? = null,
    @SerialName("zero_sum") val zeroSum: Boolean? = null
)

@Serializable
enum class Decision { ACCEPT, REJECT, EDIT }

@Serializable
data class OpDecision(val index: Int, val decision: Decision, val editedOp: ChangeOp? = null)
