package com.drkkr.strategos.domain.negotiation

import com.drkkr.strategos.domain.agent.CoachReply

/**
 * Negotiation Numbers (PLAN §3.4): the Kotlin check on the coach's reply, next to the banned-word check. A reply is unusable
 * (and the engine's own text is shown instead) when its advice carries a figure on the wrong side of the walk-away, or tells
 * the user to say the walk-away or the fallback to the other side.
 *
 * Only the ADVICE is read: `clear_win_move`, `exact_words`, `do_this` and `follow_up`. `what_happened` describes the situation
 * (often their offer) and `dont_do_this` warns against the very moves that cross the line (coach rule 3), so reading them would
 * reject every honest reply.
 */
object DealCheck {
    /**
     * A figure counts as a price only within this factor of the walk-away (1/5× to 5×), so "Thursday at 5" or "give it 2 days"
     * are never read as an offer of 5 or 2. A source-free engineering bound, like the other named constants.
     */
    const val PRICE_RANGE = 5.0
    private const val SAME = 0.5
    private val tell = Regex("""(?i)\b(?:tell|say|offer|counter|propose)""")
    private val sentence = Regex("""(?<=[.!?;\n])\s+""")

    fun usable(reply: CoachReply, frame: DealFrame?): Boolean =
        usable(listOf(reply.clearWinMove, reply.exactWords, reply.followUp) + reply.doThis, frame)

    fun usable(advice: List<String>, frame: DealFrame?): Boolean {
        val walk = frame?.walkAway ?: return true
        val secret = listOfNotNull(walk.value, frame.fallback?.value)
        for (s in advice.flatMap { it.split(sentence) }) {
            val prices = DealNumbers.figures(s).filter { frame.compatible(it) && isPrice(it.value, walk.value) }
            if (prices.any { frame.worse(it.value, walk.value) }) return false
            if (tell.containsMatchIn(s) && prices.any { p -> secret.any { kotlin.math.abs(it - p.value) < SAME } }) return false
        }
        return true
    }

    private fun isPrice(v: Double, walk: Double): Boolean =
        walk <= 0.0 || (v >= walk / PRICE_RANGE && v <= walk * PRICE_RANGE)
}
