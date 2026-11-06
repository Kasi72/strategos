package com.drkkr.strategos.domain.human

import com.drkkr.strategos.domain.agent.IntakeChecklist
import com.drkkr.strategos.domain.agent.Postures
import com.drkkr.strategos.domain.model.PayoffDimension
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.negotiation.DealFrame

/**
 * Intent Dispatch ID1.6 (BLUEPRINT §3.5, Kasi's request of 6 Nov 2026): each side's fallback changes the scores.
 *
 * A fallback is what a side gets if the other refuses, so only the **refusal cells** move (the counterpart's DEFENSIVE reply).
 * A strong fallback for you raises your payoff there, so a move that risks a no costs you less; a weak one (or none) lowers it.
 * Their fallback does the same to THEIR payoff in those cells, which moves [ResponseModel]'s pushback odds. The solver does the
 * rest; there is no list of "bold" or "safe" moves.
 *
 * Inputs: the user's words (`yourAlternative` / `theirAlternative`), or, for your side, the deal frame's `walkAwayCredible`
 * when it is known (the same rule as the Power catalog). Unknown means no shift: never guessed. In memory only, like
 * [HumanizedView]; nothing here is saved.
 */
object FallbackShift {
    enum class Strength { STRONG, WEAK }

    /** Shown only when the shift changed the pick (fixed text: never the word BATNA, never a figure). */
    const val STRONG_LINE = "You have a solid fallback, so a firmer move costs you less if they say no."
    const val WEAK_LINE = "Your fallback is thin, so a no would cost you more; the safer move comes first."
    const val THEIR_STRONG_LINE = "They have a solid alternative, so a softer ask is less likely to be refused."
    const val THEIR_WEAK_LINE = "They have little else to turn to, so a firmer ask is less risky."
    /** ID2.4: the key-gap question when your fallback is unknown and it would change the pick. */
    const val QUESTION = "If this falls through, what would you do instead?"

    /** Who has which fallback this turn. Both null = no shift. */
    data class Sides(val yours: Strength?, val theirs: Strength?) {
        val none: Boolean get() = yours == null && theirs == null
    }

    /** The outcome for the turn: the view to analyse, and the fixed lines when the shift changed the pick. */
    data class Effect(val view: Scenario, val sides: Sides, val pickChanged: Boolean, val lines: List<String>) {
        /** The lines as one piece of the why, or null when there are none. */
        val line: String? get() = lines.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    private fun word(w: String?): Strength? = when (w) { "strong" -> Strength.STRONG; "weak", "none" -> Strength.WEAK; else -> null }

    /**
     * A side's confirmed word, or (CP-I1b) its pending word when the user wrote that side's fallback in their own words: the
     * negotiation block's `batna` for yours, `counterpartBatna` for theirs (the rule `StrategicCatalog.realFallback` uses).
     */
    private fun value(view: Scenario, key: String, confirmed: String?, ownWords: String?): String? =
        confirmed ?: view.humanContext?.pending?.get(key)?.takeIf { ownWords?.isNotBlank() == true }

    /** Your side: the deal frame decides when it knows (credible = strong); else your own word. */
    fun yours(view: Scenario, deal: DealFrame?): Strength? = when (deal?.walkAwayCredible) {
        true -> Strength.STRONG
        false -> Strength.WEAK
        null -> word(value(view, "yourAlternative", view.humanContext?.yourAlternative, view.negotiationContext?.batna))
    }

    fun theirs(view: Scenario): Strength? =
        word(value(view, "theirAlternative", view.humanContext?.theirAlternative, view.negotiationContext?.counterpartBatna))

    /**
     * The sides that apply this turn, or both null when the shift is off: a close relationship (in a marriage a "strong
     * alternative" means leaving), a support or safety turn, or Power Mode's lock.
     */
    fun sides(view: Scenario, deal: DealFrame?, support: Boolean, powerLock: Boolean): Sides {
        val ctx = view.humanContext ?: return Sides(null, null)
        if (support || powerLock) return Sides(null, null)
        if (RelationshipDomains.of(ctx.relationship, null, view.negotiationContext).isClose) return Sides(null, null)
        return Sides(yours(view, deal), theirs(view))
    }

    /**
     * [view] with the refusal cells shifted by [HumanConstants.alternativeShift]. A shifted cell never rises above the same
     * move's RECEPTIVE cell on that dimension (refusal never beats acceptance); one already above it is not raised further.
     * Values stay within 0–10. The same instance when nothing moves.
     */
    fun apply(view: Scenario, sides: Sides, c: HumanConstants = HumanConstants.DEFAULT): Scenario {
        if (sides.none || c.alternativeShift == 0.0) return view
        val owner = IntakeChecklist.self(view) ?: return view
        val other = view.players().firstOrNull { it.id != owner.id } ?: return view
        val theirs = view.actionsFor(other.id)
        val receptive = theirs.firstOrNull { Postures.of(it) == Postures.RECEPTIVE }?.id ?: return view
        val defensive = theirs.filter { Postures.of(it) == Postures.DEFENSIVE }.map { it.id }.toSet()
        if (defensive.isEmpty()) return view
        fun amount(s: Strength?) = when (s) { Strength.STRONG -> c.alternativeShift; Strength.WEAK -> -c.alternativeShift; null -> 0.0 }
        val shiftFor = mapOf(owner.id to amount(sides.yours), other.id to amount(sides.theirs))
        var changed = false
        val payoffs = view.payoffs.map { e ->
            val shift = shiftFor[e.stakeholderId] ?: 0.0
            if (shift == 0.0 || e.actionProfile[other.id] !in defensive) return@map e
            val move = e.actionProfile[owner.id] ?: return@map e
            val yes = view.payoffs.firstOrNull {
                it.stakeholderId == e.stakeholderId && it.actionProfile[owner.id] == move && it.actionProfile[other.id] == receptive
            }?.payoff?.values
            val values = e.payoff.values.mapValues { (d, x) -> shifted(x, shift, d, yes?.get(d)) }
            if (values == e.payoff.values) return@map e
            changed = true
            e.copy(payoff = e.payoff.copy(values = values))
        }
        return if (changed) view.copy(payoffs = payoffs) else view
    }

    /** [x] moved [shift] points in the better-for-the-holder direction, capped at the acceptance value [cap] when raised. */
    private fun shifted(x: Double, shift: Double, d: PayoffDimension, cap: Double?): Double {
        val sign = if (d.higherIsBetter) 1.0 else -1.0
        var better = sign * x + shift                                            // "better" units: higher is always better here
        if (shift > 0 && cap != null) better = minOf(better, maxOf(sign * x, sign * cap))
        return (sign * better).coerceIn(0.0, 10.0)
    }

    /**
     * The shift for the turn, with the pick computed with and without it. When it changed the pick, each side's shift is tried
     * alone: a side whose shift alone changes the pick gets its line; when neither alone does (only both together), both do.
     */
    fun effect(
        view: Scenario, deal: DealFrame?, support: Boolean, powerLock: Boolean, pick: (Scenario) -> String?,
        c: HumanConstants = HumanConstants.DEFAULT
    ): Effect {
        val sides = sides(view, deal, support, powerLock)
        val shifted = apply(view, sides, c)
        if (shifted === view) return Effect(view, sides, false, emptyList())
        return runCatching {
            val before = pick(HumanizedView.apply(view, c))
            if (pick(HumanizedView.apply(shifted, c)) == before) return Effect(shifted, sides, false, emptyList())
            fun alone(s: Sides) = apply(view, s, c).let { v -> v !== view && pick(HumanizedView.apply(v, c)) != before }
            val yourLine = when (sides.yours) { Strength.STRONG -> STRONG_LINE; Strength.WEAK -> WEAK_LINE; null -> null }
            val theirLine = when (sides.theirs) { Strength.STRONG -> THEIR_STRONG_LINE; Strength.WEAK -> THEIR_WEAK_LINE; null -> null }
            val mine = yourLine?.takeIf { alone(Sides(sides.yours, null)) }
            val theirs = theirLine?.takeIf { alone(Sides(null, sides.theirs)) }
            val lines = if (mine == null && theirs == null) listOfNotNull(yourLine, theirLine) else listOfNotNull(mine, theirs)
            Effect(shifted, sides, true, lines)
        }.getOrDefault(Effect(shifted, sides, false, emptyList()))
    }

    /**
     * ID2.4's key gap: in a negotiation where your fallback is unknown, does the pick differ between a strong one and a weak one?
     * Their side is shifted as it would be this turn. False whenever the shift is off for the turn.
     */
    fun unknownFallbackMatters(view: Scenario, deal: DealFrame?, support: Boolean, powerLock: Boolean, pick: (Scenario) -> String?): Boolean {
        val ctx = view.humanContext
        val negotiation = deal != null || RelationshipDomains.of(ctx?.relationship, null, view.negotiationContext) == RelationshipDomain.NEGOTIATION
        if (!negotiation) return false
        val s = if (ctx == null) Sides(null, null) else sides(view, deal, support, powerLock)
        if (ctx == null || s.yours != null) return false
        if (support || powerLock || RelationshipDomains.of(ctx.relationship, null, view.negotiationContext).isClose) return false
        val strong = apply(view, s.copy(yours = Strength.STRONG))
        val weak = apply(view, s.copy(yours = Strength.WEAK))
        return runCatching { pick(HumanizedView.apply(strong)) != pick(HumanizedView.apply(weak)) }.getOrDefault(false)
    }
}
