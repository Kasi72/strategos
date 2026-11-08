package com.drkkr.strategos.domain.agent

import com.drkkr.strategos.domain.model.FactStatus
import com.drkkr.strategos.domain.model.Scenario

/**
 * Maps information asymmetry between players: who knows more, what they know, and how to exploit or
 * mitigate the gap.
 */
object InfoAsymmetryMapper {

    enum class InfoAdvantage { USER_ADVANTAGE, OPPONENT_ADVANTAGE, BALANCED, UNKNOWN }

    data class InfoGap(
        val topic: String,
        val knownBy: String,
        val unknownBy: String,
        val exploitHint: String
    )

    data class InfoAsymmetryMap(
        val advantage: InfoAdvantage,
        val gaps: List<InfoGap>,
        val summary: String
    )

    /**
     * @param scenario the current scenario
     * @param userPlayerId the player id representing the user
     */
    fun map(scenario: Scenario, userPlayerId: String): InfoAsymmetryMap {
        val user = scenario.stakeholders.firstOrNull { it.id == userPlayerId }
        val opponent = scenario.players().firstOrNull { it.id != userPlayerId }

        if (user == null || opponent == null) {
            return InfoAsymmetryMap(
                advantage = InfoAdvantage.UNKNOWN,
                gaps = emptyList(),
                summary = "Insufficient stakeholder data to map information asymmetry."
            )
        }

        val gaps = mutableListOf<InfoGap>()

        // User knows things opponent may not (from user's informationAvailable)
        user.informationAvailable.forEach { info ->
            if (!opponent.informationAvailable.any { it.contains(info, ignoreCase = true) }) {
                gaps += InfoGap(
                    topic = info,
                    knownBy = user.name,
                    unknownBy = opponent.name,
                    exploitHint = "You know '$info' and they likely don't. Use this to frame the first offer."
                )
            }
        }

        // Opponent knows things user may not (from opponent's informationAvailable — AI_INFERRED only)
        opponent.informationAvailable.forEach { info ->
            if (!user.informationAvailable.any { it.contains(info, ignoreCase = true) }) {
                gaps += InfoGap(
                    topic = info,
                    knownBy = opponent.name,
                    unknownBy = user.name,
                    exploitHint = "They may know '$info' that you don't. Ask a targeted question to surface it."
                )
            }
        }

        // Beliefs flagged as UNKNOWN or AI_INFERRED indicate gaps
        scenario.beliefs.filter { it.status == FactStatus.UNKNOWN || it.status == FactStatus.AI_INFERRED }
            .forEach { belief ->
                gaps += InfoGap(
                    topic = belief.label,
                    knownBy = "uncertain",
                    unknownBy = user.name,
                    exploitHint = "The belief '${belief.label}' (p=${belief.probability}) is uncertain; " +
                            "gathering evidence here would sharpen the analysis."
                )
            }

        val userGaps = gaps.count { it.knownBy == user.name }
        val oppGaps = gaps.count { it.knownBy == opponent.name }

        val advantage = when {
            userGaps > oppGaps + 1 -> InfoAdvantage.USER_ADVANTAGE
            oppGaps > userGaps + 1 -> InfoAdvantage.OPPONENT_ADVANTAGE
            gaps.isEmpty() -> InfoAdvantage.BALANCED
            else -> InfoAdvantage.BALANCED
        }

        val summary = when (advantage) {
            InfoAdvantage.USER_ADVANTAGE ->
                "You have an information advantage over ${opponent.name} on ${userGaps} topic(s). " +
                        "Consider anchoring early to exploit this."
            InfoAdvantage.OPPONENT_ADVANTAGE ->
                "${opponent.name} likely has more information than you on ${oppGaps} topic(s). " +
                        "Ask targeted questions before committing."
            InfoAdvantage.BALANCED ->
                if (gaps.isEmpty()) "Information appears balanced between both sides."
                else "Information is roughly balanced but ${gaps.size} gap(s) exist that could shift the outcome."
            InfoAdvantage.UNKNOWN -> "Insufficient data to assess information asymmetry."
        }

        return InfoAsymmetryMap(advantage = advantage, gaps = gaps, summary = summary)
    }
}
