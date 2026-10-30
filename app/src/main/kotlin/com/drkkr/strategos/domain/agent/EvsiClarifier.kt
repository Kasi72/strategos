package com.drkkr.strategos.domain.agent

import com.drkkr.strategos.domain.model.Scenario

/**
 * One clarification request surfaced when payoff data is missing.
 *
 * The [question] is ready to show to the user or pass to the coach LLM.
 * [remainingCount] includes this profile: it counts down to 0 as the user fills gaps.
 */
data class Clarification(
    /** Plain-English question to ask, e.g. "What happens if Alice accepts and Bob declines?" */
    val question: String,
    /** Short label for the profile, e.g. "Alice: accept / Bob: decline". */
    val profileSummary: String,
    /** Total number of still-missing profiles, including this one. */
    val remainingCount: Int
)

/**
 * EVSI-lite clarifier: picks the single most informative missing payoff profile and
 * turns it into a [Clarification].
 *
 * "Most informative" heuristic: the missing profile whose action for the focal player
 * (the scenario owner) has the highest sum of existing payoffs for that player across
 * all profiles where they play that same action. This favours clarifying outcomes near
 * the region the engine already finds promising — exactly where additional information
 * would most change (or confirm) the recommendation.
 *
 * Fallback: when no focal player is identified, returns the first missing profile.
 * Returns null when all profiles are already filled.
 */
object EvsiClarifier {

    fun clarify(scenario: Scenario): Clarification? {
        val cov = PayoffCoverage.of(scenario)
        if (cov.missingProfiles.isEmpty()) return null

        val owner = IntakeChecklist.self(scenario)
        val top = rankTop(scenario, cov.missingProfiles, owner?.id)

        val label = { id: String -> scenario.actions.firstOrNull { it.id == id }?.label.orEmpty() }
        val named = { id: String -> scenario.stakeholders.firstOrNull { it.id == id }?.name.orEmpty() }

        val parts = top.entries.map { (pid, aid) -> "${named(pid)} does \"${label(aid)}\"" }
        val profileSummary = top.entries.joinToString(" / ") { (pid, aid) -> "${named(pid)}: ${label(aid)}" }
        val question = "What would happen if ${parts.joinToString(" and ")}? " +
            "Please rate the outcome for each person (0–10)."

        return Clarification(
            question = question,
            profileSummary = profileSummary,
            remainingCount = cov.missingProfiles.size
        )
    }

    private fun rankTop(
        scenario: Scenario,
        missing: List<Map<String, String>>,
        ownerId: String?
    ): Map<String, String> {
        if (ownerId == null) return missing.first()
        return missing.maxByOrNull { profile ->
            val ownerAction = profile[ownerId] ?: return@maxByOrNull 0.0
            scenario.payoffs
                .filter { it.stakeholderId == ownerId && it.actionProfile[ownerId] == ownerAction }
                .sumOf { it.payoff.weighted(scenario.weights) }
        } ?: missing.first()
    }
}
