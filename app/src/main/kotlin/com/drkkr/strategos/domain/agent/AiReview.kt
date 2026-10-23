package com.drkkr.strategos.domain.agent

import com.drkkr.strategos.domain.model.FactStatus
import com.drkkr.strategos.domain.model.Scenario

enum class ReviewKind { PERSON, OPTION, LIMIT, OUTCOME, BELIEF }

/** One AI-inferred value the user can review (blueprint §11.6). [key] identifies it inside the scenario. */
data class ReviewItem(val kind: ReviewKind, val key: String, val title: String, val note: String)

/** Review of AI_INFERRED values: list them, keep as the user's estimate, confirm, or remove. Pure; the caller saves the result. */
object AiReview {

    fun items(s: Scenario): List<ReviewItem> = buildList {
        s.stakeholders.filter { it.factStatus == FactStatus.AI_INFERRED }.forEach { add(ReviewItem(ReviewKind.PERSON, it.id, it.name, it.provenanceNote)) }
        s.actions.filter { it.factStatus == FactStatus.AI_INFERRED }.forEach { a ->
            add(ReviewItem(ReviewKind.OPTION, a.id, "${nameOf(s, a.stakeholderId)}: ${a.label}", a.provenanceNote))
        }
        s.constraints.filter { it.factStatus == FactStatus.AI_INFERRED }.forEach { add(ReviewItem(ReviewKind.LIMIT, it.id, it.label, it.provenanceNote)) }
        s.payoffs.filter { it.factStatus == FactStatus.AI_INFERRED }.forEach { p ->
            val combo = p.actionProfile.values.joinToString(" + ") { id -> s.actions.firstOrNull { it.id == id }?.label ?: "?" }
            val scores = p.payoff.values.entries.joinToString(", ") { (d, v) -> "${d.label} ${v.toInt()}" }
            add(ReviewItem(ReviewKind.OUTCOME, outcomeKey(p.stakeholderId, p.actionProfile), "${nameOf(s, p.stakeholderId)} if $combo", scores))
        }
        s.beliefs.filter { it.status == FactStatus.AI_INFERRED }.forEach { add(ReviewItem(ReviewKind.BELIEF, it.label, it.label, "${(it.probability * 100).toInt()}% likely")) }
    }

    fun keepAsMine(s: Scenario, item: ReviewItem): Scenario = setStatus(s, item, FactStatus.USER_ESTIMATE)
    fun confirm(s: Scenario, item: ReviewItem): Scenario = setStatus(s, item, FactStatus.CONFIRMED)

    /** "Accept all as my estimates". */
    fun acceptAll(s: Scenario): Scenario = items(s).fold(s) { acc, item -> keepAsMine(acc, item) }

    fun remove(s: Scenario, item: ReviewItem): Scenario = when (item.kind) {
        ReviewKind.PERSON -> s.copy(
            stakeholders = s.stakeholders.filter { it.id != item.key }.map {
                it.copy(coalitionsWith = it.coalitionsWith - item.key, trustRelationships = it.trustRelationships - item.key)
            },
            actions = s.actions.filter { it.stakeholderId != item.key },
            payoffs = s.payoffs.filter { it.stakeholderId != item.key && item.key !in it.actionProfile.keys },
            constraintAssessments = s.constraintAssessments.filter { a -> s.actions.firstOrNull { it.id == a.actionId }?.stakeholderId != item.key }
        )
        ReviewKind.OPTION -> s.copy(
            actions = s.actions.filter { it.id != item.key },
            payoffs = s.payoffs.filter { item.key !in it.actionProfile.values },
            constraintAssessments = s.constraintAssessments.filter { it.actionId != item.key }
        )
        ReviewKind.LIMIT -> s.copy(constraints = s.constraints.filter { it.id != item.key }, constraintAssessments = s.constraintAssessments.filter { it.constraintId != item.key })
        ReviewKind.OUTCOME -> s.copy(payoffs = s.payoffs.filter { outcomeKey(it.stakeholderId, it.actionProfile) != item.key })
        ReviewKind.BELIEF -> s.copy(beliefs = s.beliefs.filter { it.label != item.key })
    }

    private fun setStatus(s: Scenario, item: ReviewItem, status: FactStatus): Scenario = when (item.kind) {
        ReviewKind.PERSON -> s.copy(stakeholders = s.stakeholders.map { if (it.id == item.key) it.copy(factStatus = status) else it })
        ReviewKind.OPTION -> s.copy(actions = s.actions.map { if (it.id == item.key) it.copy(factStatus = status) else it })
        ReviewKind.LIMIT -> s.copy(constraints = s.constraints.map { if (it.id == item.key) it.copy(factStatus = status) else it })
        ReviewKind.OUTCOME -> s.copy(payoffs = s.payoffs.map { if (outcomeKey(it.stakeholderId, it.actionProfile) == item.key) it.copy(factStatus = status) else it })
        ReviewKind.BELIEF -> s.copy(beliefs = s.beliefs.map { if (it.label == item.key) it.copy(status = status) else it })
    }

    private fun outcomeKey(stakeholderId: String, profile: Map<String, String>) =
        stakeholderId + "|" + profile.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }

    private fun nameOf(s: Scenario, id: String) = s.stakeholders.firstOrNull { it.id == id }?.name ?: "?"
}
