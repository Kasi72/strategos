package com.drkkr.strategos.domain.agent

import com.drkkr.strategos.domain.game.GameArchetype
import com.drkkr.strategos.domain.model.Scenario

/**
 * Post-solve scenario consistency checks.
 * Returns a warning string when a structural mismatch is detected, null otherwise.
 * Three deterministic checks — no inference, no false positives from ambiguous data.
 *
 * Pure domain — no android.* or androidx.* imports.
 */
object ExtractionVerifier {

    /**
     * @param scenario the extracted scenario
     * @param archetype detected game archetype, null for generic games
     * @param stressVerdict "ROBUST" or "FRAGILE"
     * @param payoffsFilled how many payoff cells are filled (non-estimated)
     * @param payoffsExpected total payoff cells in the game matrix
     */
    fun verify(
        scenario: Scenario,
        archetype: GameArchetype?,
        stressVerdict: String,
        payoffsFilled: Int,
        payoffsExpected: Int
    ): String? {
        // Check 1: zero-sum analysis on a repeated/ongoing relationship
        if (archetype == GameArchetype.ZERO_SUM && scenario.repeated) {
            return "Zero-sum analysis applied to an ongoing relationship. " +
                "Long-term payoffs may be missing — consider whether cooperation has value over time."
        }

        // Check 2: fragile pick with majority of payoffs missing
        if (stressVerdict == "FRAGILE" && payoffsExpected > 0 && payoffsFilled < payoffsExpected / 2) {
            return "Pick is fragile and over half the payoffs are estimates. " +
                "Verify this recommendation before acting on it."
        }

        // Check 3: prisoner's dilemma with no adversarial framing (all actions cooperative)
        if (archetype == GameArchetype.PRISONERS_DILEMMA) {
            val allPlayersHaveOnlyCooperativeLabels = scenario.stakeholders
                .filter { it.gameRole != null }
                .all { player ->
                    scenario.actionsFor(player.id).all { action ->
                        COOPERATIVE_KEYWORDS.any { kw -> action.label.contains(kw, ignoreCase = true) }
                    }
                }
            if (allPlayersHaveOnlyCooperativeLabels && scenario.stakeholders.size >= 2) {
                return "Prisoner's Dilemma detected, but all action labels appear cooperative. " +
                    "If the conflict is real, re-label one option as the defection move so the analysis is accurate."
            }
        }

        return null
    }

    private val COOPERATIVE_KEYWORDS = listOf(
        "cooperate", "collaborate", "agree", "accept", "help", "support", "share", "trust", "partner"
    )
}
