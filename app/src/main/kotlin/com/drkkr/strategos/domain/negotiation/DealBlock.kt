package com.drkkr.strategos.domain.negotiation

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Negotiation Numbers (PLAN §3.4): what the coach gets from the [DealFrame]. Only figures it may USE (the opening, the stopping
 * rule, the likely landing and the zone), as formatted strings with the currency. The walk-away goes only as
 * `walk_away_private`, for checking, and the fallback's figure is never sent at all.
 */
data class DealBlock(
    val openAt: Amount?,
    val acceptIfAtLeast: Amount?,
    val acceptIfAtMost: Amount?,
    val landing: Amount?,
    val zone: Zone?,
    val walkAwayCredible: Boolean?,
    val noZone: Boolean,
    val notes: List<String>,
    val walkAwayPrivate: Amount?,
    /** Aggressive first offer: target ± 15% buffer in the direction of higher/lower. Null when target is unknown. */
    val anchorTarget: Amount? = null
) {
    fun toJson(): JsonObject = buildJsonObject {
        openAt?.let { put("open_at", DealNumbers.format(it)) }
        anchorTarget?.let { put("anchor_target", DealNumbers.format(it)) }
        acceptIfAtLeast?.let { put("accept_if_at_least", DealNumbers.format(it)) }
        acceptIfAtMost?.let { put("accept_if_at_most", DealNumbers.format(it)) }
        landing?.let { put("landing", DealNumbers.format(it)) }
        zone?.let { put("zone_low", DealNumbers.format(it.low)); put("zone_high", DealNumbers.format(it.high)) }
        walkAwayCredible?.let { put("walk_away_credible", it) }
        if (noZone) put("no_zone", true)
        if (notes.isNotEmpty()) putJsonArray("notes") { notes.forEach { add(JsonPrimitive(it)) } }
        walkAwayPrivate?.let { put("walk_away_private", "${DealNumbers.format(it)} $PRIVATE_LABEL") }
    }

    companion object {
        const val PRIVATE_LABEL = "(for checking only: never say it to them)"
        private const val ANCHOR_BUFFER = 0.15

        fun from(f: DealFrame): DealBlock {
            val anchorTarget = f.target?.let { t ->
                val buffered = when (f.direction) {
                    Direction.HIGHER_IS_BETTER -> Math.round(t.value * (1 + ANCHOR_BUFFER) * 100) / 100.0
                    Direction.LOWER_IS_BETTER  -> Math.round(t.value * (1 - ANCHOR_BUFFER) * 100) / 100.0
                }
                Amount(buffered, t.unit, t.currency)
            }
            return DealBlock(
                openAt = f.openAt, acceptIfAtLeast = f.acceptIfAtLeast, acceptIfAtMost = f.acceptIfAtMost,
                landing = f.landing, zone = f.zone, walkAwayCredible = f.walkAwayCredible, noZone = f.noZone,
                notes = f.notes, walkAwayPrivate = f.walkAway, anchorTarget = anchorTarget
            )
        }
    }
}
