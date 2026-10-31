package com.drkkr.strategos.domain.game

import com.drkkr.strategos.domain.model.Scenario

object MechanismAdvisor {
    enum class MechanismType { AUCTION, MATCHING, CONTRACT, VOTING, ALLOCATION, NONE }
    data class MechanismHint(val type: MechanismType, val insight: String, val recommendation: String)

    private val AUCTION_KEYWORDS = setOf("auction", "bid", "procurement", "tender", "reverse auction", "sealed bid")
    private val MATCHING_KEYWORDS = setOf("match", "assign", "allocate workers", "pair", "staffing", "placement")
    private val CONTRACT_KEYWORDS = setOf("contract design", "incentive design", "design incentiv", "bonus structure", "compensation design", "align incentiv")
    private val VOTING_KEYWORDS = setOf("voting rule", "election design", "policy vote", "committee decision", "rank choice", "approval voting")
    private val ALLOCATION_KEYWORDS = setOf("fair division", "split resource", "divide asset", "allocate budget", "resource allocation", "apportion")
    private val DESIGN_TRIGGER = setOf("design", "set up rules", "create system", "build mechanism", "structure the", "how should we structure", "what rules")

    fun detect(scenario: Scenario): MechanismHint? {
        val text = "${scenario.title} ${scenario.description}".lowercase()
        val hasDesignTrigger = DESIGN_TRIGGER.any { text.contains(it) }
        val type = when {
            AUCTION_KEYWORDS.any { text.contains(it) } -> MechanismType.AUCTION
            MATCHING_KEYWORDS.any { text.contains(it) } && hasDesignTrigger -> MechanismType.MATCHING
            CONTRACT_KEYWORDS.any { text.contains(it) } -> MechanismType.CONTRACT
            VOTING_KEYWORDS.any { text.contains(it) } -> MechanismType.VOTING
            ALLOCATION_KEYWORDS.any { text.contains(it) } && hasDesignTrigger -> MechanismType.ALLOCATION
            else -> MechanismType.NONE
        }
        if (type == MechanismType.NONE) return null
        return when (type) {
            MechanismType.AUCTION -> MechanismHint(type,
                "This looks like an auction design problem — the goal is to elicit truthful bids and allocate efficiently.",
                "Use a second-price (Vickrey) auction for single items: bidding true value is the dominant strategy. For multiple items, consider VCG mechanisms.")
            MechanismType.MATCHING -> MechanismHint(type,
                "This is a matching problem — stable assignment with two-sided preferences.",
                "Apply Gale-Shapley (deferred acceptance): the proposing side gets their best stable match. Give proposal power to the weaker side if equity matters more than efficiency.")
            MechanismType.CONTRACT -> MechanismHint(type,
                "This is a contract/incentive design problem — the mechanism must make the agent's best strategy align with the principal's goal.",
                "Design for incentive compatibility: the agent's dominant strategy under the contract should be the action you want. Check participation constraints (agent must prefer this to outside options).")
            MechanismType.VOTING -> MechanismHint(type,
                "This is a social choice / voting design problem.",
                "No rule satisfies all Arrow's axioms simultaneously. For small groups, Borda count reduces extreme preferences. For binary choices, majority rule with qualified thresholds prevents minority override.")
            MechanismType.ALLOCATION -> MechanismHint(type,
                "This is a fair division problem — allocating resources across parties with different valuations.",
                "For divisible goods, use proportional allocation or equal-shares. For indivisible goods, consider round-robin draft (envy-free up to one item) or randomized priority.")
            MechanismType.NONE -> null
        }
    }
}
