package com.drkkr.strategos.domain.agent

import com.drkkr.strategos.domain.model.FactStatus
import com.drkkr.strategos.domain.model.Provenance
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.model.provenance

/** What a single fact in the Memory section is: a want, a fear, a role, a limit, or a free note. */
enum class BrainFactKind { WANT, FEAR, ROLE, LIMIT, NOTE }

/**
 * One editable line in the Overview's Memory. [memoryId] is non-null only for free user notes held in
 * the memory table (editable/removable directly); scenario-derived facts carry null and are changed
 * through the review flow. [reviewed] marks a fact the app first guessed but the user has since kept.
 */
data class BrainFact(
    val text: String,
    val kind: BrainFactKind,
    val provenance: Provenance,
    val memoryId: String? = null,
    val reviewed: Boolean = false
)

/** A person the project knows about, with their wants, fears and role. "You" sorts first. */
data class PersonMemory(
    val stakeholderId: String,
    val name: String,
    val initial: String,
    val isSelf: Boolean,
    val facts: List<BrainFact>
)

/** The plain-mono line on the model strip: "4 people · 6 options · 18/18 outcomes". */
data class ModelCounts(val people: Int, val options: Int, val outcomesFilled: Int, val outcomesExpected: Int)

/**
 * Everything the coach uses about one project, grouped for the Overview's "What the app knows".
 * Pure read model over the saved [Scenario] (plus free [notes] the repository folds in). No android,
 * no persistence: the repository builds it and the screen renders it.
 */
data class ProjectBrain(
    val people: List<PersonMemory>,
    val limits: List<BrainFact>,
    val notes: List<BrainFact>,
    val counts: ModelCounts,
    val inferredCount: Int,   // still to review — drives the amber "N facts inferred by AI" banner
    val reviewedCount: Int,   // first guessed by the app, since kept by the user
    val inferredTotal: Int    // reviewedCount + inferredCount — the model strip's "N of M reviewed"
) {
    companion object {
        fun from(s: Scenario): ProjectBrain {
            val people = s.stakeholders.map { st ->
                val prov = st.factStatus.provenance()
                val reviewed = wasReviewed(st.factStatus, st.provenanceNote)
                val facts = buildList {
                    wants(st).forEach { add(BrainFact(it, BrainFactKind.WANT, prov, reviewed = reviewed)) }
                    st.fears.filter { it.isNotBlank() }.forEach { add(BrainFact(it, BrainFactKind.FEAR, prov, reviewed = reviewed)) }
                    st.role.takeIf { it.isNotBlank() }?.let { add(BrainFact(it, BrainFactKind.ROLE, prov, reviewed = reviewed)) }
                }
                val self = st.name.equals("You", ignoreCase = true) || st.id == s.decisionOwner || st.name == s.decisionOwner
                PersonMemory(st.id, st.name, st.name.trim().take(1).uppercase(), self, facts)
            }.sortedByDescending { it.isSelf }

            val limits = s.constraints.map {
                BrainFact(it.label, BrainFactKind.LIMIT, it.factStatus.provenance(), reviewed = wasReviewed(it.factStatus, it.provenanceNote))
            }

            val cov = PayoffCoverage.of(s)
            val inferred = AiReview.items(s).size
            val reviewed = reviewedScenarioFacts(s)
            return ProjectBrain(
                people = people, limits = limits, notes = emptyList(),
                counts = ModelCounts(s.stakeholders.size, s.actions.size, cov.filled, cov.expected),
                inferredCount = inferred, reviewedCount = reviewed, inferredTotal = inferred + reviewed
            )
        }

        private fun wants(st: com.drkkr.strategos.domain.model.Stakeholder): List<String> =
            (st.goals + st.publicGoals + st.privateGoals).map { it.trim() }.filter { it.isNotBlank() }.distinct()

        /** A fact the app first guessed (keeps its rationale note) but the user has since promoted off AI_INFERRED. */
        private fun wasReviewed(status: FactStatus, provenanceNote: String): Boolean =
            status != FactStatus.AI_INFERRED && provenanceNote.isNotBlank()

        private fun reviewedScenarioFacts(s: Scenario): Int =
            s.stakeholders.count { !it.name.equals("You", ignoreCase = true) && wasReviewed(it.factStatus, it.provenanceNote) } +
                s.actions.count { wasReviewed(it.factStatus, it.provenanceNote) } +
                s.constraints.count { wasReviewed(it.factStatus, it.provenanceNote) } +
                s.payoffs.count { wasReviewed(it.factStatus, it.provenanceNote) } +
                s.beliefs.count { it.status != FactStatus.AI_INFERRED && it.note.isNotBlank() }
    }
}
