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

    /**
     * [extraText]: recent user messages, passed in memory only (the Talk flow never writes them into the scenario's description).
     * [archetype]: the compiled game's archetype, when known; cooperation-failure archetypes surface a hint without keywords.
     */
    fun detect(scenario: Scenario, extraText: String = "", archetype: GameArchetype? = null): MechanismHint? {
        val parts = buildList {
            add(scenario.title)
            add(scenario.description)
            scenario.stakeholders.forEach { s ->
                add(s.notes)
                s.goals.forEach { add(it) }
            }
            scenario.actions.forEach { add(it.label) }
            add(extraText)
        }
        val text = parts.filter { it.isNotBlank() }.joinToString(" ").lowercase()
        val hasDesignTrigger = DESIGN_TRIGGER.any { text.contains(it) }
        val type = when {
            AUCTION_KEYWORDS.any { text.contains(it) } -> MechanismType.AUCTION
            MATCHING_KEYWORDS.any { text.contains(it) } && hasDesignTrigger -> MechanismType.MATCHING
            CONTRACT_KEYWORDS.any { text.contains(it) } -> MechanismType.CONTRACT
            VOTING_KEYWORDS.any { text.contains(it) } -> MechanismType.VOTING
            ALLOCATION_KEYWORDS.any { text.contains(it) } && hasDesignTrigger -> MechanismType.ALLOCATION
            else -> MechanismType.NONE
        }
        // Item H: cooperation-failure archetypes surface mechanism hint by default even without keywords
        if (type == MechanismType.NONE) {
            if (archetype == GameArchetype.PRISONERS_DILEMMA || archetype == GameArchetype.CHICKEN) {
                return MechanismHint(MechanismType.CONTRACT,
                    "Both parties are trapped in a suboptimal outcome by individually rational choices — this is a cooperation failure.",
                    "Restructure the incentives so that the cooperative move becomes each party's individually rational choice. " +
                        "Concrete options: a binding pre-commitment, a credible monitoring mechanism, or a repeated-interaction pledge that makes future cooperation the dominant strategy.")
            }
            return null
        }
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

    data class IcSynthesis(
        val bindingRule: String,
        val monitoringNote: String
    )

    fun synthesize(hint: MechanismHint, ir: GameIR): IcSynthesis? {
        if (ir.playerCount < 2) return null
        val bindingRule = when (hint.type) {
            MechanismType.AUCTION ->
                "Bidders commit sealed offers before seeing others' bids; the highest bidder pays the second-highest price."
            MechanismType.MATCHING ->
                "Proposals flow from the stronger side; each party tentatively accepts only their current best offer until all are matched."
            MechanismType.CONTRACT ->
                "Payment is contingent on a verifiable outcome; a fixed base plus a performance bonus ties compensation to the desired action."
            MechanismType.VOTING ->
                "Each participant submits a ranked ballot; the aggregation rule is announced in advance and cannot be changed after ballots are cast."
            MechanismType.ALLOCATION ->
                "Each party names a value for each item independently; allocation follows the stated values and no one can revise after seeing others' valuations."
            MechanismType.NONE -> return null
        }
        val monitoringNote = when (hint.type) {
            MechanismType.AUCTION -> "Verify bids are sealed and simultaneous; audit the payment rule after each round."
            MechanismType.MATCHING -> "Track acceptance chains in real time; flag any party that attempts to withdraw a standing offer."
            MechanismType.CONTRACT -> "Tie measurement to an observable metric both parties agree on before signing; set review milestones."
            MechanismType.VOTING -> "Publish the aggregated result before any binding decision; allow a one-round challenge period."
            MechanismType.ALLOCATION -> "Require value statements in writing before the draw; resolve ties by a predetermined random device."
            MechanismType.NONE -> return null
        }
        return IcSynthesis(bindingRule, monitoringNote)
    }
}
