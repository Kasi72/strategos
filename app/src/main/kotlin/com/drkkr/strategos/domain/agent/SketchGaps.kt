package com.drkkr.strategos.domain.agent

import com.drkkr.strategos.domain.model.Scenario

/**
 * SIMPLE PROFILE: what the saved model still lacks before the engine can compare options. When there are gaps the app
 * asks the model once more for exactly those (a small, targeted request) instead of guessing.
 */
object SketchGaps {
    private const val MAX_COMBINATIONS = 18

    /** Plain-text description of the gaps for the repair request, or null when the model is complete enough. */
    fun describe(s: Scenario): String? {
        val players = s.players()
        if (players.size < 2) return null                       // a thin story is the model's `ask`, not a gap
        val lines = mutableListOf<String>()
        players.filter { s.actionsFor(it.id).size < 2 }.forEach { lines += "${it.name} has fewer than 2 options: give ${it.name} 2-3 options." }
        if (lines.isEmpty()) {
            val cov = PayoffCoverage.of(s)
            if (cov.missingProfiles.isNotEmpty()) {
                val label = { id: String -> s.actions.firstOrNull { it.id == id }?.label.orEmpty() }
                val named = { id: String -> s.stakeholders.firstOrNull { it.id == id }?.name.orEmpty() }
                lines += "Outcome scores are missing for these combinations (give scores for every player in each):"
                cov.missingProfiles.take(MAX_COMBINATIONS).forEach { profile ->
                    lines += "- " + profile.entries.joinToString(" / ") { (pid, aid) -> "${named(pid)}: ${label(aid)}" }
                }
            }
        } else {
            lines += "Then give outcomes for EVERY combination of the players' options."
        }
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }
}
