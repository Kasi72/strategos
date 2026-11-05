package com.drkkr.strategos.domain.human

import com.drkkr.strategos.domain.model.Action

/** One plain line for the X-ray sheet, and where to read more about the idea behind it. Never names a tactic, a law or a book. */
data class Lens(val line: String, val topic: String, val url: String)

/**
 * The strongest single effect the Human Layer sees in how the move would land, in one plain sentence. Built in Kotlin from the same
 * constants and tags as [HumanizedView], so the line and the engine's numbers can never disagree. It reads the move's own tags plus
 * the habits in the user's last attempt (what they tend to do), like the planner does.
 */
object HumanLens {
    private fun wiki(page: String) = "https://en.wikipedia.org/wiki/$page"

    fun of(hc: HumanContext?, move: Action?, c: HumanConstants = HumanConstants.DEFAULT): Lens? {
        if (hc == null || move == null) return null
        val tags = DeliveryTags.of(DeliveryPlanner.carrying(hc, move))
        val domain = RelationshipDomains.of(hc.relationship, null)
        val theyHigher = hc.theirPower == "they_higher"
        val faceCost = "corrects_them" in tags && theyHigher && "credits_them" !in tags
        val found = mutableListOf<Pair<Double, Lens>>()
        fun add(strength: Double, line: String, topic: String, page: String) { found += strength to Lens(line, topic, wiki(page)) }

        if ("asks_them_to_give_up" in tags && hc.statusQuoForThem != "neutral") {
            if ("future_commitment" in tags) add((c.lossAversion - 1) * 0.5, "They'd be giving something up, and a later start softens it.", "Loss aversion", "Loss_aversion")
            else add(c.lossAversion - 1, "They'd be giving something up, so a later start helps.", "Loss aversion", "Loss_aversion")
        }
        if (faceCost) {
            if ("public" in tags) add(c.faceCost, "Correcting them in front of others costs them face; say it privately and credit their thinking.", "Face (social concept)", "Face_(sociological_concept)")
            else add(c.faceCost * c.privateFaceShare, "Even a private correction stings; credit their thinking first.", "Face (social concept)", "Face_(sociological_concept)")
        }
        if ("appeals_to_fairness_or_gratitude" in tags && !domain.isClose) add(c.interestShift, "Leading with what they gain lands better than what they owe you.", "Framing effect", "Framing_effect_(psychology)")
        if ("their_interest" in tags) add(c.interestShift, "Leading with what they gain makes a yes easier.", "Framing effect", "Framing_effect_(psychology)")
        if ("open_ended" in tags && hc.deadline == "none" && "sets_default" !in tags && "two_choices" !in tags)
            add(c.inertia, "An open-ended ask lets doing nothing win; say what happens, and by when.", "Default effect", "Default_effect")
        if ("public" in tags && hc.goalIs == "break_from_group") add(c.settingShift, "Asking them to break from their group in public makes yes harder.", "Conformity", "Conformity")
        if ("public" in tags && hc.goalIs == "lasting_commitment" && !faceCost) add(c.commitShift, "Saying it openly helps a commitment stick.", "Conformity", "Conformity")
        if ("gives_first" in tags) add(c.giveFirstShift, "Giving something first makes a yes easier.", "Reciprocity", "Reciprocity_(social_psychology)")
        if ("withdraws" in tags) add(c.stepBackShift, "Stepping back lets them come to you.", "Reciprocity", "Reciprocity_(social_psychology)")
        return found.maxByOrNull { it.first }?.second
    }
}
