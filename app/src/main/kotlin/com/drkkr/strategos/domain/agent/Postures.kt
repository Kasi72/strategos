package com.drkkr.strategos.domain.agent

import com.drkkr.strategos.domain.model.Action
import com.drkkr.strategos.domain.model.PayoffDimension

/**
 * How an option behaves in a repeated relationship. Call 1 tags the user's options repair / boundary / reactive and the
 * other person's receptive / defensive. The tag is kept on the action as `[posture:x]` in its description, so no schema changes.
 */
object Postures {
    const val REPAIR = "repair"
    const val BOUNDARY = "boundary"
    const val REACTIVE = "reactive"
    const val RECEPTIVE = "receptive"
    const val DEFENSIVE = "defensive"
    private val valid = setOf(REPAIR, BOUNDARY, REACTIVE, RECEPTIVE, DEFENSIVE)
    private val tag = Regex("\\[posture:(\\w+)]")

    fun clean(raw: String?): String? = raw?.trim()?.lowercase()?.takeIf { it in valid }
    fun tag(posture: String?): String = clean(posture)?.let { "[posture:$it]" }.orEmpty()
    fun of(a: Action): String? = tag.find(a.description)?.groupValues?.get(1)?.let { clean(it) }
}

/**
 * "Shadow of the future": in a relationship that continues, a reactive move (pleading, chasing, an ultimatum) costs trust later.
 * Deterministic adjustments to the model's outcome scores, applied before they are saved:
 *  - a reactive option's RELATIONSHIPS and LONG_TERM_VALUE scores for the user are capped at 2;
 *  - the other person's defensive option is scored better for them than their receptive one when the user plays reactive,
 *    and the receptive option is scored better when the user plays repair or boundary.
 * Without posture tags on both sides nothing is changed.
 */
object PostureContrast {
    private const val CAP = 2.0
    private const val MARGIN = 1.5
    private val capped = setOf(PayoffDimension.RELATIONSHIPS, PayoffDimension.LONG_TERM_VALUE)

    private class Row(val ownerLabel: String, val otherLabel: String, val owner: MutableList<Double>, val other: MutableList<Double>)

    fun apply(outcomes: List<SketchOutcome>, options: List<SketchOption>, ownerName: String, otherName: String, dims: List<PayoffDimension>): List<SketchOutcome> {
        fun key(s: String) = SketchConverter.norm(s)
        val owner = key(ownerName); val other = key(otherName)
        val posture = options.mapNotNull { o -> Postures.clean(o.posture)?.let { (key(o.owner) to key(o.label)) to it } }.toMap()
        fun p(who: String, label: String) = posture[who to label]
        val ownerTagged = options.any { key(it.owner) == owner && Postures.clean(it.posture) != null }
        if (!ownerTagged || outcomes.isEmpty()) return outcomes

        val rows = outcomes.mapNotNull { o ->
            val ownerLabel: String; val otherLabel: String; val ownerScores: List<Double>; val otherScores: List<Double>
            if (o.whenPicks.size >= 2) { ownerLabel = key(o.whenPicks[0]); otherLabel = key(o.whenPicks[1]); ownerScores = o.forYou; otherScores = o.forThem }
            else {
                val picks = o.picks.entries.associate { key(it.key) to key(it.value) }
                val scores = o.scores.entries.associate { key(it.key) to it.value }
                ownerLabel = picks[owner] ?: return@mapNotNull null; otherLabel = picks[other] ?: return@mapNotNull null
                ownerScores = scores[owner].orEmpty(); otherScores = scores[other].orEmpty()
            }
            Row(ownerLabel, otherLabel, ownerScores.toMutableList(), otherScores.toMutableList())
        }
        if (rows.size != outcomes.size) return outcomes

        for (r in rows) if (p(owner, r.ownerLabel) == Postures.REACTIVE) {
            dims.forEachIndexed { i, d -> if (d in capped && i < r.owner.size) r.owner[i] = minOf(r.owner[i], CAP) }
        }
        fun better(a: MutableList<Double>, than: List<Double>) {
            dims.forEachIndexed { i, d ->
                if (i >= a.size || i >= than.size) return@forEachIndexed
                a[i] = if (d.higherIsBetter) minOf(10.0, maxOf(a[i], than[i] + MARGIN)) else maxOf(0.0, minOf(a[i], than[i] - MARGIN))
            }
        }
        for ((ownerLabel, group) in rows.groupBy { it.ownerLabel }) {
            val receptive = group.firstOrNull { p(other, it.otherLabel) == Postures.RECEPTIVE } ?: continue
            val defensive = group.firstOrNull { p(other, it.otherLabel) == Postures.DEFENSIVE } ?: continue
            if (p(owner, ownerLabel) == Postures.REACTIVE) better(defensive.other, receptive.other) else better(receptive.other, defensive.other)
        }
        return rows.map { r ->
            SketchOutcome(picks = mapOf(owner to r.ownerLabel, other to r.otherLabel), scores = mapOf(owner to r.owner, other to r.other))
        }
    }
}

/** `[bracketed placeholders]` must never reach the screen. */
object Placeholders {
    private val bracket = Regex("\\s*\\[[^\\]]{1,60}]")
    fun has(text: String) = bracket.containsMatchIn(text)
    fun strip(text: String) = text.replace(bracket, "").replace(Regex("\\s{2,}"), " ").replace(Regex("\\s+([,.;:!?])"), "$1").trim()
}
