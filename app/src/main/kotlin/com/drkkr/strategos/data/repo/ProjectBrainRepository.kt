package com.drkkr.strategos.data.repo

import com.drkkr.strategos.data.db.entities.MemoryItemEntity
import com.drkkr.strategos.domain.agent.AiReview
import com.drkkr.strategos.domain.agent.BrainFact
import com.drkkr.strategos.domain.agent.BrainFactKind
import com.drkkr.strategos.domain.agent.CrossProjectPeople
import com.drkkr.strategos.domain.agent.InfluenceGraph
import com.drkkr.strategos.domain.agent.PersonCarryOver
import com.drkkr.strategos.domain.agent.ProjectBrain
import com.drkkr.strategos.domain.agent.ReviewAction
import com.drkkr.strategos.domain.agent.ReviewItem
import com.drkkr.strategos.domain.model.FactStatus
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.model.provenance
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * The read model behind the Overview's "What the app knows", over the saved scenario (people, wants,
 * fears, limits, model counts) plus the free notes the user adds. Every fact carries USER | INFERRED.
 * Writes are user-initiated, so they go straight through [ScenarioRepository] (not a ChangeSet, which
 * is only for the LLM) and through [MemoryRepository] for free notes.
 */
class ProjectBrainRepository(
    private val scenarios: ScenarioRepository,
    private val memory: MemoryRepository
) {
    /** The current brain, or null when the project is not loaded. */
    suspend fun brain(scenarioId: String): ProjectBrain? {
        val s = scenarios.find(scenarioId) ?: return null
        return s.toBrain(memory.open(scenarioId))
    }

    /** Live brain: re-emits when the scenario or the project's notes change. */
    fun observe(scenarioId: String): Flow<ProjectBrain?> =
        combine(scenarios.scenarios, memory.observeOpen(scenarioId)) { list, notes ->
            list.firstOrNull { it.id == scenarioId }?.toBrain(notes)
        }

    /** The inferred values still awaiting review (the amber banner's list). */
    fun reviewItems(scenarioId: String): List<ReviewItem> =
        scenarios.find(scenarioId)?.let { AiReview.items(it) } ?: emptyList()

    /** The "Who pulls on whom" map: nodes and weighted, labelled, inferred-flagged edges. */
    fun influence(scenarioId: String): InfluenceGraph? =
        scenarios.find(scenarioId)?.let { InfluenceGraph.from(it) }

    /** People this new project names that another project already knows — offer to bring them over. */
    fun carryOverSuggestions(scenarioId: String): List<PersonCarryOver> {
        val target = scenarios.find(scenarioId) ?: return emptyList()
        return CrossProjectPeople.suggestions(target, scenarios.scenarios.value)
    }

    /** Bring one shared person's knowledge over, flagged for review (suggest, don't merge). */
    suspend fun bringOverPerson(targetId: String, sourceId: String, name: String): Boolean {
        val target = scenarios.find(targetId) ?: return false
        val source = scenarios.find(sourceId) ?: return false
        return scenarios.save(CrossProjectPeople.bringOver(target, source, name)) is SaveResult.Success
    }

    /** "Add something the app should know": a free, user-stated fact. Returns its memory id. */
    suspend fun addFact(scenarioId: String, text: String): String =
        memory.add(scenarioId, MemoryKind.FACT, text.trim(), FactStatus.USER_ESTIMATE)

    suspend fun editNote(memoryId: String, text: String) = memory.setText(memoryId, text)

    suspend fun removeNote(memoryId: String) = memory.delete(memoryId)

    /** Keep / confirm / remove one inferred value; the result is saved as the user's own. */
    suspend fun review(scenarioId: String, item: ReviewItem, action: ReviewAction): Boolean {
        val s = scenarios.find(scenarioId) ?: return false
        val after = when (action) {
            ReviewAction.KEEP -> AiReview.keepAsMine(s, item)
            ReviewAction.CONFIRM -> AiReview.confirm(s, item)
            ReviewAction.REMOVE -> AiReview.remove(s, item)
        }
        return scenarios.save(after) is SaveResult.Success
    }

    private fun Scenario.toBrain(notes: List<MemoryItemEntity>): ProjectBrain =
        ProjectBrain.from(this).copy(notes = notes.filter { it.isUserFact() }.map { it.toNote() })

    private fun MemoryItemEntity.isUserFact(): Boolean =
        kind == MemoryKind.FACT.name && sourceMessageId == null

    private fun MemoryItemEntity.toNote(): BrainFact {
        val status = runCatching { FactStatus.valueOf(factStatus) }.getOrDefault(FactStatus.USER_ESTIMATE)
        return BrainFact(text = text, kind = BrainFactKind.NOTE, provenance = status.provenance(), memoryId = id)
    }
}
