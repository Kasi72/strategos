package com.drkkr.strategos.domain.human

import com.drkkr.strategos.domain.agent.IntakeChecklist
import com.drkkr.strategos.domain.agent.Postures
import com.drkkr.strategos.domain.game.ProspectTheoryTransform
import com.drkkr.strategos.domain.model.PayoffDimension
import com.drkkr.strategos.domain.model.Scenario

/**
 * The counterpart's payoffs as a Human would FEEL them, for the analysis only (invariant I-H1). Today's engine treats the other
 * person as someone who calmly takes whichever reply scores best; the two books are about why real people don't. This rewrites
 * ONLY the counterpart's rows, ONLY on dimensions already in those rows, and the result is never saved.
 *
 * Reads (agreed after the first live replay): `theirPower`, `deadline`, `statusQuoForThem`, the relationship (for the domain)
 * and each option's own `[f:]` tags. It does not read the soft fields (setting, dependencies, alternatives) or `their_state`.
 * Everything is "in the direction that is better for them": up for a higher-is-better dimension, down for COST.
 *
 *  1. Loss aversion (Nudge Ch.1): an ask that makes them give something up (`asks_them_to_give_up`) is felt from the status quo;
 *     what they would lose counts [HumanConstants.lossAversion] times. `future_commitment` halves the extra (Save More Tomorrow).
 *  2. Face (48 Laws L1, L9): correcting a higher-status person (`corrects_them`, they_higher) costs face; a public one the full
 *     [HumanConstants.faceCost], a private one a third of it. `credits_them` removes it.
 *  3. Self-interest vs gratitude (L13): appealing to fairness or gratitude costs, except in close relationships where it is a
 *     legitimate appeal; leading with their interest helps everywhere.
 *  4. Inertia (Nudge Ch.1, 5): an open-ended ask with no deadline makes doing nothing more attractive; a default, two concrete choices or a deadline cancel it.
 *  6. Giving first, an outside yardstick, naming what the role makes possible, and stepping back each add a small [HumanConstants] shift
 *     to their receptive reply (they make the matching tactics measurable).
 *  5. Setting (Nudge Ch.3, L1/L38): a `public` move hurts a request to break from a group and helps a commitment that should stick
 *     (only when there is no face cost). Uses the option's own `public` tag.
 *
 * Not applied: friction per extra step (Nudge Ch.3) needs `steps_for_them_to_say_yes`, which is not one of the agreed inputs.
 * One cell never moves more than [HumanConstants.cellCap] in total, and values stay within 0-10.
 */
object HumanizedView {

    fun apply(view: Scenario, c: HumanConstants = HumanConstants.DEFAULT): Scenario {
        val ctx = view.humanContext ?: return view
        val owner = IntakeChecklist.self(view) ?: return view
        val other = view.players().firstOrNull { it.id != owner.id } ?: return view
        val mine = view.actionsFor(owner.id)
        val theirs = view.actionsFor(other.id)
        val receptive = theirs.filter { Postures.of(it) == Postures.RECEPTIVE }.map { it.id }.toSet()
        val defensive = theirs.filter { Postures.of(it) == Postures.DEFENSIVE }.map { it.id }.toSet()
        if (receptive.isEmpty() && defensive.isEmpty()) return view            // without the two replies there is nothing to compare

        val domain = RelationshipDomains.of(ctx.relationship, null)
        val theyHigher = ctx.theirPower == "they_higher"
        val prospect = ProspectTheoryTransform.Config(alpha = 1.0, beta = 1.0, lambda = c.lossAversion)

        // The status quo: what they do when we do the least (an open-ended ask, else a boundary, else the first option).
        val quo = mine.firstOrNull { "open_ended" in DeliveryTags.of(it) } ?: mine.firstOrNull { Postures.of(it) == Postures.BOUNDARY } ?: mine.firstOrNull()
        val reference: Map<PayoffDimension, Double>? = quo?.let { q ->
            view.payoffs.firstOrNull { it.stakeholderId == other.id && it.actionProfile[owner.id] == q.id && it.actionProfile[other.id] in defensive }?.payoff?.values
        }
        val byId = mine.associateBy { it.id }
        var changed = false

        val payoffs = view.payoffs.map { e ->
            if (e.stakeholderId != other.id) return@map e
            val move = byId[e.actionProfile[owner.id]] ?: return@map e
            val reply = e.actionProfile[other.id] ?: return@map e
            val tags = DeliveryTags.of(move)
            val values = e.payoff.values
            val shift = mutableMapOf<PayoffDimension, Double>()                // better-for-them points
            fun add(d: PayoffDimension, x: Double) { shift[d] = (shift[d] ?: 0.0) + x }
            fun addAll(x: Double) = values.keys.forEach { add(it, x) }

            if (reply in receptive) {
                // 1. loss aversion, measured from the status quo
                if ("asks_them_to_give_up" in tags && reference != null && ctx.statusQuoForThem != "neutral") {
                    val halve = if ("future_commitment" in tags) 0.5 else 1.0
                    for ((d, x) in values) {
                        val ref = reference[d] ?: continue
                        val sign = if (d.higherIsBetter) 1.0 else -1.0
                        val felt = ProspectTheoryTransform.referenceValue(sign * x, sign * ref, prospect)
                        add(d, (felt - sign * x) * halve)                      // zero for a gain; negative for a loss
                    }
                }
                // 2. face
                val faceCost = "corrects_them" in tags && theyHigher && "credits_them" !in tags
                if (faceCost) {
                    val cost = if ("public" in tags) c.faceCost else c.faceCost * c.privateFaceShare
                    val target = listOf(PayoffDimension.REPUTATION, PayoffDimension.RELATIONSHIPS).firstOrNull { it in values }
                    if (target != null) add(target, -cost)
                    else values.keys.filter { it.higherIsBetter }.let { dims -> dims.forEach { add(it, -cost / dims.size) } }
                }
                // 3. self-interest vs gratitude
                if ("appeals_to_fairness_or_gratitude" in tags && !domain.isClose) addAll(-c.interestShift)
                if ("their_interest" in tags) addAll(c.interestShift)
                // 6. small, bounded effects of delivering the move in a way that is easier to say yes to
                if ("gives_first" in tags) addAll(c.giveFirstShift)
                if ("outside_yardstick" in tags) addAll(c.yardstickShift)
                if ("names_leverage" in tags) addAll(c.leverageShift)
                if ("withdraws" in tags) addAll(c.stepBackShift)
                // 5. setting
                if ("public" in tags) {
                    if (ctx.goalIs == "break_from_group") addAll(-c.settingShift)
                    else if (ctx.goalIs == "lasting_commitment" && !faceCost) addAll(c.commitShift)
                }
            }
            if (reply in defensive) {
                // 4. inertia
                if ("open_ended" in tags && ctx.deadline == "none" && "sets_default" !in tags && "two_choices" !in tags) addAll(c.inertia)
            }

            if (shift.values.all { it == 0.0 }) return@map e
            changed = true
            e.copy(payoff = e.payoff.copy(values = values.mapValues { (d, x) ->
                val sign = if (d.higherIsBetter) 1.0 else -1.0
                val capped = (shift[d] ?: 0.0).coerceIn(-c.cellCap, c.cellCap)
                (x + sign * capped).coerceIn(0.0, 10.0)
            }))
        }
        return if (changed) view.copy(payoffs = payoffs) else view
    }
}
