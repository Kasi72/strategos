package com.drkkr.strategos.domain.agent

import com.drkkr.strategos.domain.model.FactStatus
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.model.Stakeholder

/** An offer to bring over what another project already knows about a shared person. */
data class PersonCarryOver(val name: String, val sourceScenarioId: String, val sourceStakeholderId: String)

/**
 * When a new project names a person another project already knows, offer once to bring that
 * knowledge over — suggest, don't merge (open question #3). Brains stay separate; copied facts land
 * as AI_INFERRED so they show up flagged for review, never silently filed. Pure; the caller saves.
 */
object CrossProjectPeople {

    /** People in [target] who are still blank here but fleshed out in another project. One suggestion per name. */
    fun suggestions(target: Scenario, others: List<Scenario>): List<PersonCarryOver> {
        val sources = others.filter { it.id != target.id }
        return target.stakeholders
            .filter { !it.name.equals("You", ignoreCase = true) && it.isSparse() }
            .mapNotNull { blank ->
                sources.firstNotNullOfOrNull { src ->
                    src.stakeholders.firstOrNull { it.name.equals(blank.name, ignoreCase = true) && !it.isSparse() }
                        ?.let { PersonCarryOver(blank.name, src.id, it.id) }
                }
            }
            .distinctBy { it.name.lowercase() }
    }

    /** Copy the source person's wants/fears/role onto the matching person in [target], flagged for review. */
    fun bringOver(target: Scenario, source: Scenario, name: String): Scenario {
        val from = source.stakeholders.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return target
        return target.copy(stakeholders = target.stakeholders.map { st ->
            if (!st.name.equals(name, ignoreCase = true)) st
            else st.copy(
                goals = (st.goals + from.goals).distinct(),
                privateGoals = (st.privateGoals + from.privateGoals).distinct(),
                fears = (st.fears + from.fears).distinct(),
                role = st.role.ifBlank { from.role },
                factStatus = FactStatus.AI_INFERRED,
                provenanceNote = "Brought over from “${source.title}”"
            )
        })
    }

    private fun Stakeholder.isSparse(): Boolean =
        goals.isEmpty() && privateGoals.isEmpty() && publicGoals.isEmpty() && fears.isEmpty()
}
