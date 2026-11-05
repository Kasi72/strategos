package com.drkkr.strategos.domain.negotiation

import com.drkkr.strategos.domain.agent.CoachReply

/**
 * Negotiation Numbers (PLAN §3.4, widened at CP-N): the Kotlin check on the coach's reply, next to the banned-word check. The
 * masking ([WalkAwayMask]) keeps the figures out of what the coach is given; this is the safety net. A reply is unusable (and the
 * engine's own, masked text is shown instead) when:
 *  - its advice carries a figure on the wrong side of the walk-away;
 *  - `exact_words` (said to the other side) carries the walk-away at all, or a fallback that is not credible (a real, strong
 *    alternative may be named: it is leverage, CP-N ng6);
 *  - `clear_win_move`, `do_this` or `follow_up` carries one in a sentence with a speech cue ("tell", "write", "email", ...) or in
 *    quoted speech. Private advice ("don't go above $45,000") stays allowed;
 *  - any advice field (or a follow-up answer) advises revealing the walk-away even without the figure ("say the number once"),
 *    unless the sentence is negated ("don't name your ceiling", "keep it private") (ng7).
 *
 * Only the ADVICE is read: `what_happened` describes the situation (often their offer) and `dont_do_this` warns against the very
 * moves that cross the line (coach rule 3), so reading them would reject every honest reply.
 */
object DealCheck {
    /**
     * A figure counts as a price only within this factor of the walk-away (1/5× to 5×), so "Thursday at 5" or "give it 2 days"
     * are never read as an offer of 5 or 2. A source-free engineering bound, like the other named constants.
     */
    const val PRICE_RANGE = 5.0
    private val speech = Regex("""(?i)\b(?:tell|told|say|said|writ|wrote|text|messag|e-?mail|offer|counter|propos|quot)""")
    private val quoted = Regex(""""[^"]*"|“[^”]*”""")
    // ng7: advice to reveal the walk-away even without its figure ("say the number once"); negated advice is allowed.
    private val revealVerb = Regex("""(?i)\b(?:say|says|saying|tell|tells|telling|name|naming|share|sharing|reveal|revealing|state|stating|give|giving|mention|mentioning)\b""")
    private val walkAwayWords = Regex("""(?i)\bthe number\b|\b(?:your|my|our) (?:budget|ceiling|floor|limit|bottom[- ]line|walk[- ]?away|max(?:imum)?|min(?:imum)?|number)\b""")
    private val negated = Regex("""(?i)\b(?:don'?t|do not|never|not|no need|avoid|without)\b|\bkeep\b.*\b(?:private|to yourself|secret|quiet)\b""")
    private val sentence = Regex("""(?<=[.!?;\n])\s+""")

    fun usable(reply: CoachReply, frame: DealFrame?): Boolean {
        if (frame?.walkAway == null) return true
        val advice = listOf(reply.clearWinMove, reply.exactWords, reply.followUp) + reply.doThis
        if (advice.any { crosses(it, frame) }) return false
        if (hasSecret(reply.exactWords, frame)) return false
        if (advice.any { advisesReveal(it) }) return false
        return (listOf(reply.clearWinMove, reply.followUp) + reply.doThis).none { revealsAsSpeech(it, frame) }
    }

    /** Free advice text (the reflection when the engine has no pick): nothing on the wrong side, nothing secret given as speech. */
    fun usable(advice: List<String>, frame: DealFrame?): Boolean =
        frame?.walkAway == null || advice.none { crosses(it, frame) || revealsAsSpeech(it, frame) || advisesReveal(it) }

    /** An answer to a follow-up question explains the situation, like `what_happened`: only the speech rule applies. */
    fun usableAnswer(text: String, frame: DealFrame?): Boolean = frame?.walkAway == null || !(revealsAsSpeech(text, frame) || advisesReveal(text))

    /** A sentence that tells the user to say, share or name their walk-away ("say the number once"), and is not negated. */
    private fun advisesReveal(text: String): Boolean =
        text.split(sentence).any { s -> revealVerb.containsMatchIn(s) && walkAwayWords.containsMatchIn(s) && !negated.containsMatchIn(s) }

    private fun crosses(text: String, frame: DealFrame): Boolean {
        val walk = frame.walkAway ?: return false
        return text.split(sentence).any { s ->
            DealNumbers.figures(s).any { frame.compatible(it) && isPrice(it.value, walk.value) && frame.worse(it.value, walk.value) }
        }
    }

    private fun hasSecret(text: String, frame: DealFrame) = DealNumbers.figures(text).any { WalkAwayMask.secret(it, frame) }

    private fun revealsAsSpeech(text: String, frame: DealFrame): Boolean =
        quoted.findAll(text).any { hasSecret(it.value, frame) } ||
            text.split(sentence).any { s -> speech.containsMatchIn(s) && hasSecret(s, frame) }

    private fun isPrice(v: Double, walk: Double): Boolean =
        walk <= 0.0 || (v >= walk / PRICE_RANGE && v <= walk * PRICE_RANGE)
}
