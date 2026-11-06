package com.drkkr.strategos.domain.game

import com.drkkr.strategos.domain.model.PayoffDimension
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * The number each label stands for, on the existing 0–10 scale (Intent Dispatch BLUEPRINT §3.2). "Same as today" is the scale's
 * own neutral; the other steps sit evenly around it, two each side, inside the 0–10 range.
 */
data class RubricConstants(
    val muchBetter: Double = 8.5,
    val better: Double = 6.5,
    val same: Double = 5.0,
    val worse: Double = 3.5,
    val muchWorse: Double = 1.5
) {
    /** A source note per field (the pm6.1 rule: no unexplained constant). */
    val notes: Map<String, String> get() = mapOf(
        "muchBetter" to "two steps above neutral on the extraction scale (\"5 = neutral / status quo\"), 1.5 below its top",
        "better" to "one step (1.5) above neutral",
        "same" to "the 0–10 scale's own neutral: the extraction prompt's \"5 = neutral / status quo\"",
        "worse" to "one step (1.5) below neutral",
        "muchWorse" to "two steps below neutral, 1.5 above the scale's floor"
    )

    fun of(level: RubricPayoffs.Level): Double = when (level) {
        RubricPayoffs.Level.MUCH_BETTER -> muchBetter
        RubricPayoffs.Level.BETTER -> better
        RubricPayoffs.Level.SAME -> same
        RubricPayoffs.Level.WORSE -> worse
        RubricPayoffs.Level.MUCH_WORSE -> muchWorse
    }

    /** The same steps with their distance from neutral multiplied by [factor] (ID1.5 sensitivity: 0.5 halves, 2.0 doubles). */
    fun spaced(factor: Double): RubricConstants {
        fun s(v: Double) = (same + (v - same) * factor).coerceIn(0.0, 10.0)
        return RubricConstants(s(muchBetter), s(better), same, s(worse), s(muchWorse))
    }

    companion object { val DEFAULT = RubricConstants() }
}

/** Labels → payoff numbers. Pure; the sketch reader calls it once the dimensions of the turn are known. */
object RubricPayoffs {
    /** Marks a payoff whose cells came as numbers (an older reply or prompt cache), so the stats can tell them apart. */
    const val AI_SCORED_NUMERIC = "AI_SCORED_NUMERIC"

    enum class Level(val step: Int) { MUCH_BETTER(2), BETTER(1), SAME(0), WORSE(-1), MUCH_WORSE(-2) }

    sealed class Cell {
        data class Label(val level: Level) : Cell()
        data class Numeric(val value: Double) : Cell()
        object Missing : Cell()
    }

    private val dashes = Regex("[\\u2010-\\u2015\\u2212]")
    private val words = mapOf(
        "++" to Level.MUCH_BETTER, "+" to Level.BETTER, "0" to Level.SAME, "-" to Level.WORSE, "--" to Level.MUCH_WORSE,
        "much_better" to Level.MUCH_BETTER, "better" to Level.BETTER, "same" to Level.SAME, "worse" to Level.WORSE, "much_worse" to Level.MUCH_WORSE
    )

    /** The label in a cell, or null (a number, or anything else). A bare JSON number is never a label, even 0. */
    fun level(raw: JsonElement): Level? {
        val p = raw as? JsonPrimitive ?: return null
        if (!p.isString) return null
        return words[p.contentOrNull.orEmpty().trim().lowercase().replace(dashes, "-")]
    }

    fun cell(raw: JsonElement): Cell {
        level(raw)?.let { return Cell.Label(it) }
        val p = raw as? JsonPrimitive ?: return Cell.Missing
        val n = if (p.isString) p.contentOrNull?.trim()?.toDoubleOrNull() else p.doubleOrNull
        return n?.takeIf { it.isFinite() }?.let { Cell.Numeric(it) } ?: Cell.Missing
    }

    /** The stored value: "much better for them" on COST (`higherIsBetter = false`) is a low cost, so it is flipped. */
    fun value(level: Level, dim: PayoffDimension, k: RubricConstants = RubricConstants.DEFAULT): Double =
        k.of(level).let { if (dim.higherIsBetter) it else 10.0 - it }

    /** One player's cells in [dims] order; null when a cell is missing or the list is short (that entry is then missing). */
    class Row(val values: List<Double>, val numeric: Boolean)

    fun row(raw: List<JsonElement>, dims: List<PayoffDimension>, k: RubricConstants = RubricConstants.DEFAULT): Row? {
        if (raw.size < dims.size) return null
        var numeric = false
        val values = dims.mapIndexed { i, d ->
            when (val c = cell(raw[i])) {
                is Cell.Label -> value(c.level, d, k)
                is Cell.Numeric -> { numeric = true; c.value.coerceIn(0.0, 10.0) }
                Cell.Missing -> return null
            }
        }
        return Row(values, numeric)
    }
}
