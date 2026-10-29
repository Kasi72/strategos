package com.drkkr.strategos.domain.agent

import com.drkkr.strategos.domain.model.GameRole
import com.drkkr.strategos.domain.model.Provenance
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.model.Stakeholder
import com.drkkr.strategos.domain.model.provenance

/** A person on the "Who pulls on whom" map. [inferred] draws the circle dashed. */
data class InfluenceNode(
    val id: String,
    val name: String,
    val initial: String,
    val isSelf: Boolean,
    val inferred: Boolean
)

/** A pull between two people. [weight] 0..1 is line thickness (sway); [inferred] draws it dashed. */
data class InfluenceEdge(
    val fromId: String,
    val toId: String,
    val weight: Double,
    val label: String,
    val inferred: Boolean
)

/** The stakeholder map: who has a stake and how strongly they pull. Pure read model over the scenario. */
data class InfluenceGraph(val nodes: List<InfluenceNode>, val edges: List<InfluenceEdge>) {
    companion object {
        /** A relation with no recorded authority or influence still draws a faint line so the tie is visible. */
        private const val MIN_WEIGHT = 0.2

        fun from(s: Scenario): InfluenceGraph {
            val self = IntakeChecklist.self(s) ?: s.players().firstOrNull()
            val nodes = s.stakeholders.map {
                InfluenceNode(it.id, it.name, it.name.trim().take(1).uppercase(), it.id == self?.id, it.factStatus.provenance() == Provenance.INFERRED)
            }
            val edges = buildList {
                // Everyone else pulls on the decision-maker ("You").
                if (self != null) s.stakeholders.filter { it.id != self.id }.forEach { st ->
                    add(InfluenceEdge(st.id, self.id, sway(st), relation(st, self), st.factStatus.provenance() == Provenance.INFERRED))
                }
                // Declared alliances between two people, once per pair.
                val seen = HashSet<String>()
                s.stakeholders.forEach { a ->
                    a.coalitionsWith.forEach { bId ->
                        val key = listOf(a.id, bId).sorted().joinToString("|")
                        val b = s.stakeholders.firstOrNull { it.id == bId }
                        if (b != null && seen.add(key)) {
                            add(InfluenceEdge(a.id, b.id, maxOf(sway(a), sway(b)), "allied", a.inferred() || b.inferred()))
                        }
                    }
                }
            }
            return InfluenceGraph(nodes, edges)
        }

        private fun sway(st: Stakeholder): Double = (maxOf(st.authority, st.influence) / 10.0).coerceIn(MIN_WEIGHT, 1.0)

        private fun Stakeholder.inferred() = factStatus.provenance() == Provenance.INFERRED

        private fun relation(st: Stakeholder, self: Stakeholder): String = when {
            st.id in self.coalitionsWith || st.id in self.trustRelationships || self.id in st.trustRelationships -> "on your side"
            st.gameRole == GameRole.PLAYER -> "wants their way"
            st.canVeto -> "can block this"
            st.gameRole == GameRole.INFLUENCER -> "can sway this"
            else -> "has a stake"
        }
    }
}
