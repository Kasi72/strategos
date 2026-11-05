package com.drkkr.strategos.domain.negotiation

import com.drkkr.strategos.domain.agent.StatedFloor
import com.drkkr.strategos.domain.human.HumanContext
import com.drkkr.strategos.domain.model.NegotiationContext

/** Is a bigger figure better for the user (seller, salary) or a smaller one (buyer, tenant)? */
enum class Direction { HIGHER_IS_BETTER, LOWER_IS_BETTER }

/** The figures where both sides can agree: from the user's walk-away to the other side's limit, low end first. */
data class Zone(val low: Amount, val high: Amount)

/**
 * Negotiation Numbers (PLAN §3.2): the user's negotiation figures, read in one unit and one currency, with what follows from them.
 * Computed in Kotlin from the saved [NegotiationContext] and the Human Layer's confirmed values; nothing here is model-written,
 * and the 0–10 outcome scores are never touched. Null whenever direction, unit or currency is unclear (never a guessed number).
 */
data class DealFrame(
    val direction: Direction,
    val walkAway: Amount?,
    val target: Amount?,
    val fallback: Amount?,
    val theirLimit: Amount?,
    val theirFallback: Amount?,
    val zone: Zone?,
    val landing: Amount?,
    val openAt: Amount?,
    /** The stopping rule (48 Laws, L47): accept at or beyond this. "At least" for a seller, "at most" for a buyer. */
    val acceptIf: Amount?,
    /** True when the fallback is at least as good as the walk-away; null when the fallback has no figure. */
    val walkAwayCredible: Boolean?,
    /** Their limit is known and lies on the wrong side of the walk-away: a deal may not be possible. */
    val noZone: Boolean,
    val notes: List<String>
) {
    val acceptIfAtLeast: Amount? get() = acceptIf.takeIf { direction == Direction.HIGHER_IS_BETTER }
    val acceptIfAtMost: Amount? get() = acceptIf.takeIf { direction == Direction.LOWER_IS_BETTER }

    /** True when [a] is worse for the user than [than] (below it for a seller, above it for a buyer). */
    fun worse(a: Double, than: Double): Boolean = if (direction == Direction.HIGHER_IS_BETTER) a < than else a > than

    /** True when [a] can be compared with this frame's figures: same unit, and no conflicting currency. */
    fun compatible(a: Amount): Boolean = walkAway != null && a.unit == walkAway.unit && (a.currency == null || walkAway.currency == null || a.currency == walkAway.currency)

    companion object {
        /** Landing moves 10% of the zone's width toward whoever has the strong alternative (confirmed values only). */
        const val LANDING_SHIFT = 0.10
        /** A target more than 25% beyond their limit is not a credible opening; open 10% beyond their limit instead. */
        const val OPEN_BEYOND_LIMIT = 0.25
        const val OPEN_AT_LIMIT_MARGIN = 0.10

        private val HIGHER_RELATIONSHIPS = setOf("manager", "client")
        private val LOWER_RELATIONSHIPS = setOf("vendor", "landlord")
        private val stated = Regex("""(?i)\b(?:${StatedFloor.NEED_PHRASES})\s*(?:is\s*)?(\D{0,12}?\d[\d,]*(?:\.\d+)?\s*[a-z%]*)""")

        fun build(nc: NegotiationContext?, ctx: HumanContext?, message: String): DealFrame? {
            if (nc == null) return null
            val savedWalk = DealNumbers.parse(nc.reservationPoint) ?: return null
            val notes = mutableListOf<String>()

            // Consistency: every figure must share the walk-away's unit, and one currency (the first one named, walk-away first).
            var currency = savedWalk.currency
            fun keep(raw: String, name: String): Amount? {
                val a = DealNumbers.parse(raw) ?: return null
                if (a.unit != savedWalk.unit) {
                    notes += "your $name is ${unitWord(a.unit)}, your walk-away ${unitWord(savedWalk.unit)}"
                    return null
                }
                if (a.currency != null && currency != null && a.currency != currency) {
                    notes += "your $name and your walk-away are in different currencies (${a.currency}, $currency)"
                    return null
                }
                if (currency == null) currency = a.currency
                return a
            }
            val target = keep(nc.aspirationPoint, "target")
            val fallback = keep(nc.batna, "fallback")
            val theirLimit = keep(nc.counterpartReservation, "estimate of their limit")
            val theirFallback = keep(nc.counterpartBatna, "estimate of their fallback")
            fun cur(a: Amount?) = a?.copy(currency = if (a.unit == Amount.Unit.AMOUNT) currency else null)

            val direction = when {
                target != null && target.value > savedWalk.value -> Direction.HIGHER_IS_BETTER
                target != null && target.value < savedWalk.value -> Direction.LOWER_IS_BETTER
                ctx?.relationship in HIGHER_RELATIONSHIPS -> Direction.HIGHER_IS_BETTER
                ctx?.relationship in LOWER_RELATIONSHIPS -> Direction.LOWER_IS_BETTER
                else -> return null
            }
            val higher = direction == Direction.HIGHER_IS_BETTER

            // "I need at least X" in this message (a seller's words): the stricter of the saved and the stated walk-away wins.
            var walk = savedWalk.value
            if (higher) {
                stated.findAll(message).mapNotNull { DealNumbers.parse(it.groupValues[1]) }
                    .filter { it.unit == savedWalk.unit && (it.currency == null || currency == null || it.currency == currency) }
                    .maxOfOrNull { it.value }?.let { if (it > walk) walk = it }
            }
            fun amt(v: Double) = Amount(v, savedWalk.unit, currency.takeIf { savedWalk.unit == Amount.Unit.AMOUNT })
            fun worse(a: Double, than: Double) = if (higher) a < than else a > than

            val zone = theirLimit?.let { lim ->
                if (worse(lim.value, walk)) null
                else if (higher) Zone(amt(walk), amt(lim.value)) else Zone(amt(lim.value), amt(walk))
            }
            val noZone = theirLimit != null && zone == null
            if (noZone) notes += "no overlap at your walk-away"

            val landing = zone?.let { z ->
                val width = z.high.value - z.low.value
                val toward = if (higher) 1.0 else -1.0
                val yoursStrong = ctx?.yourAlternative == "strong"
                val theirsStrong = ctx?.theirAlternative == "strong"
                val shift = when {
                    yoursStrong && !theirsStrong -> toward * LANDING_SHIFT * width
                    theirsStrong && !yoursStrong -> -toward * LANDING_SHIFT * width
                    else -> 0.0
                }
                amt(round((z.low.value + z.high.value) / 2 + shift))
            }

            val openAt = target?.let { t ->
                val lim = theirLimit?.value
                val farBeyond = lim != null && (if (higher) t.value > lim * (1 + OPEN_BEYOND_LIMIT) else t.value < lim * (1 - OPEN_BEYOND_LIMIT))
                val nearLimit = lim?.let { round(if (higher) it * (1 + OPEN_AT_LIMIT_MARGIN) else it * (1 - OPEN_AT_LIMIT_MARGIN)) }
                // Never open on the wrong side of the walk-away (possible when there is no zone): then the target stands.
                if (farBeyond && nearLimit != null && !worse(nearLimit, walk)) amt(nearLimit) else cur(t)
            }

            val credible = fallback?.let { !worse(it.value, walk) }

            return DealFrame(
                direction = direction, walkAway = amt(walk), target = cur(target), fallback = cur(fallback),
                theirLimit = cur(theirLimit), theirFallback = cur(theirFallback), zone = zone, landing = landing, openAt = openAt,
                acceptIf = cur(target), walkAwayCredible = credible, noZone = noZone, notes = notes
            )
        }

        private fun unitWord(u: Amount.Unit) = if (u == Amount.Unit.PERCENT) "a percentage" else "an amount"
        private fun round(v: Double) = Math.round(v * 100) / 100.0
    }
}
