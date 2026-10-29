package com.drkkr.strategos.domain.agent

import com.drkkr.strategos.domain.model.Scenario

/** One plain line per op for the change-set card (blueprint §11.4). Names come from the scenario or from earlier adds in the batch. */
object ChangeOpDescriber {

    fun describe(op: ChangeOp, scenario: Scenario, batch: List<ChangeOp> = emptyList()): String {
        val stakeholderName = { idOrRef: String? -> nameOf(idOrRef, scenario, batch) }
        return when (op.type) {
            OpType.SET_SCENARIO_FIELDS -> {
                val f = op.scenarioFields
                val changed = listOfNotNull(
                    f?.title?.let { "title" }, f?.description?.let { "description" },
                    f?.decisionOwner?.let { "decision owner" }, f?.stakes?.let { "stakes" },
                    f?.timeHorizonLabel?.let { "time horizon" }, f?.institution?.let { "institution" },
                    f?.location?.let { "location" }, f?.repeated?.let { "repeated" },
                    f?.informationAsymmetry?.let { "information asymmetry" }, f?.zeroSum?.let { "zero-sum" }
                )
                if (changed.isEmpty()) "Update the situation" else "Update the situation: ${changed.joinToString(", ")}"
            }
            OpType.ADD_STAKEHOLDER -> {
                val role = op.gameRole?.name?.lowercase() ?: "influencer"
                "Add ${op.name ?: "someone"} ($role)"
            }
            OpType.UPDATE_STAKEHOLDER -> "Update ${stakeholderName(op.targetId)}"
            OpType.REMOVE_STAKEHOLDER -> "Remove ${stakeholderName(op.targetId)}"
            OpType.ADD_ACTION -> "Add option for ${stakeholderName(op.stakeholder)}: ${op.label ?: ""}".trimEnd(' ', ':')
            OpType.UPDATE_ACTION -> "Update option: ${actionLabel(op.targetId, scenario, batch)}"
            OpType.REMOVE_ACTION -> "Remove option: ${actionLabel(op.targetId, scenario, batch)}"
            OpType.SET_PAYOFF -> {
                val picks = op.profile.orEmpty().joinToString(" / ") { actionLabel(it.action, scenario, batch) }
                "Set outcome for ${stakeholderName(op.stakeholder)}: $picks"
            }
            OpType.ADD_CONSTRAINT -> "Add limit: ${op.label ?: ""}".trimEnd(' ', ':')
            OpType.ADD_BELIEF -> "Add likelihood: ${op.label ?: ""}".trimEnd(' ', ':')
            OpType.ADD_ASSUMPTION -> "Add assumption: ${op.label ?: op.description ?: ""}".trimEnd(' ', ':')
        }
    }

    private fun nameOf(idOrRef: String?, scenario: Scenario, batch: List<ChangeOp>): String =
        scenario.stakeholders.firstOrNull { it.id == idOrRef }?.name
            ?: batch.firstOrNull { it.type == OpType.ADD_STAKEHOLDER && it.ref == idOrRef }?.name
            ?: "someone"

    private fun actionLabel(idOrRef: String?, scenario: Scenario, batch: List<ChangeOp>): String =
        scenario.actions.firstOrNull { it.id == idOrRef }?.label
            ?: batch.firstOrNull { it.type == OpType.ADD_ACTION && it.ref == idOrRef }?.label
            ?: "an option"
}
