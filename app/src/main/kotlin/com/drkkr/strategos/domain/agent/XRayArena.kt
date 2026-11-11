package com.drkkr.strategos.domain.agent

import com.drkkr.strategos.domain.model.GameRole
import com.drkkr.strategos.domain.model.Provenance
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.model.Stakeholder
import com.drkkr.strategos.domain.model.provenance

/** One labelled fact on an arena card, e.g. "Floor" / "27L". [inferred] shows the Inferred badge. */
data class ArenaLine(val label: String, val value: String, val inferred: Boolean = false)

/** A person standing in the arena: you or the counterpart. */
data class ArenaPerson(val name: String, val role: String, val lines: List<ArenaLine>)

/** The third platform: whoever pulls hardest on the decision from outside. [sway] is "low", "medium" or "high". */
data class ArenaInfluencer(val name: String, val role: String, val relation: String, val sway: String, val inferred: Boolean)

/**
 * The X-ray's first slide: who is in the game. A pure read model over the saved scenario, so every word on
 * the slide is something the user typed or the app already inferred (and is badged as such).
 * [trust] and [tension] are 0..100, null when never measured.
 */
data class XRayArena(
    val you: ArenaPerson,
    val them: ArenaPerson,
    val influencer: ArenaInfluencer?,
    val trust: Int?,
    val tension: Int?
)

object XRayArenaBuilder {
    /** [trust] / [tension] are the project's current 0..100 readings; trust falls back to the counterpart's own baseline. */
    fun from(s: Scenario, trust: Double? = null, tension: Double? = null): XRayArena? {
        val me = IntakeChecklist.self(s) ?: s.players().firstOrNull() ?: return null
        val him = XRayPriors.counterpart(s) ?: return null
        val deal = s.negotiationContext
        val mine = buildList {
            deal?.reservationPoint?.takeIf { it.isNotBlank() }?.let { add(ArenaLine("Floor", it.trim())) }
            deal?.aspirationPoint?.takeIf { it.isNotBlank() }?.let { add(ArenaLine("Target", it.trim())) }
            if (isEmpty()) me.goals.firstOrNull { it.isNotBlank() }?.let { add(ArenaLine("You want", it.trim())) }
        }
        val guessed = him.inferred()
        val theirs = buildList {
            him.goals.firstOrNull { it.isNotBlank() }?.let { add(ArenaLine("Wants", it.trim(), guessed)) }
            him.fears.firstOrNull { it.isNotBlank() }?.let { add(ArenaLine("Worried about", it.trim(), guessed)) }
        }
        val edges = InfluenceGraph.from(s).edges
        val third = s.stakeholders.filter { it.id != me.id && it.id != him.id }
            .maxWithOrNull(compareBy<Stakeholder> { it.gameRole == GameRole.INFLUENCER }.thenBy { maxOf(it.authority, it.influence) })
            ?.let { st ->
                val edge = edges.firstOrNull { it.fromId == st.id && it.toId == me.id }
                val w = edge?.weight ?: 0.0
                ArenaInfluencer(st.name, st.role.ifBlank { st.approvalRole }, edge?.label ?: "has a stake",
                    if (w >= 0.7) "high" else if (w >= 0.4) "medium" else "low", st.inferred())
            }
        return XRayArena(
            ArenaPerson("You", me.role, mine), ArenaPerson(him.name, him.role, theirs), third,
            (trust ?: him.trustBaseline?.times(100))?.let { Math.round(it).toInt().coerceIn(0, 100) },
            tension?.let { Math.round(it).toInt().coerceIn(0, 100) }
        )
    }

    private fun Stakeholder.inferred() = factStatus.provenance() == Provenance.INFERRED
}
