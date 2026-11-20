package com.drkkr.strategos.server.pipeline

import com.drkkr.strategos.domain.agent.IntakeChecklist
import com.drkkr.strategos.domain.agent.Postures
import com.drkkr.strategos.domain.model.Scenario
import kotlinx.serialization.Serializable

@Serializable
data class ScoredOption(val label: String, val posture: String?, val net: Double)

/**
 * SDD §1 Stage 2: payoffs are Kotlin's, never the model's. Call 1 only labels each cell per dimension ("++" much better …
 * "--" much worse, ArgonPrompts rule 4). SketchParser turns those labels into 0–10 numbers, PostureContrast applies the
 * shadow-of-the-future penalty to reactive moves (RELATIONSHIPS and LONG_TERM_VALUE capped), and the scenario stores the
 * result. This reads that stored, deterministic result back as one net score per option for the person (the scenario's
 * weighted sum, averaged over the other side's replies), for the X-Ray and the SDD checks.
 */
object DeterministicPayoffCalculator {
    fun netPayoffs(s: Scenario): List<ScoredOption> {
        val self = IntakeChecklist.self(s) ?: return emptyList()
        val mine = s.actionsFor(self.id)
        return mine.map { a ->
            val rows = s.payoffs.filter { it.stakeholderId == self.id && it.actionProfile[self.id] == a.id }
            val net = if (rows.isEmpty()) 0.0 else rows.sumOf { it.payoff.weighted(s.weights) } / rows.size
            ScoredOption(a.label, Postures.of(a), net)
        }.sortedByDescending { it.net }
    }
}
