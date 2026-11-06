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
    // Negation counts only before the reveal verb ("don't name your ceiling", "never reveal it") or as "keep ... private"; a later
    // "not" ("open by naming your number, not a starting point") does not excuse the advice (CP-P3).
    private val negated = Regex("""(?i)\b(?:don'?t|do not|never|not|no need to|avoid|without)\s+(?:\w+\s+){0,3}?(?:say|tell|name|share|reveal|state|give|mention)|\bkeep\b.*\b(?:private|to yourself|secret|quiet)\b""")
    /** Sentence breaks, including after a closing quote ('...?" If he pushes'). */
    private val sentence = Regex("""(?<=[.!?;\n]["”]?)\s+""")

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

    const val KEEP_PRIVATE = "Keep your walk-away to yourself."

    /**
     * The last resort for a follow-up answer the check still rejects after its retry (CP-P3): every flagged sentence is removed
     * (advice to name, state or reveal the walk-away, a secret figure given as speech), a flagged quoted line is dropped whole, and
     * the bullet "Keep your walk-away to yourself." is always added. Never the masked answer, which kept the advice.
     */
    fun sanitizeAnswer(text: String, frame: DealFrame?): String {
        if (frame?.walkAway == null) return text
        // A flagged quoted line takes its whole sentence with it (so "Say it simply: ..." never dangles).
        val marked = quoted.replace(text) { m -> if (hasSecret(m.value, frame) || advisesReveal(m.value)) "$DROP." else m.value }   // keeps a sentence boundary
        val kept = marked.lines().map { line ->
            line.split(sentence).filter { s -> s.isNotBlank() && DROP !in s && !advisesReveal(s) && !revealsAsSpeech(s, frame) }.joinToString(" ")
        }.filter { it.isNotBlank() }
        return (kept + "• $KEEP_PRIVATE").joinToString("\n").trim()
    }

    private const val DROP = "\u0000"

    /** A sentence that tells the user to say, share or name their walk-away ("say the number once"), and is not negated. */
    private fun advisesReveal(text: String): Boolean =
        text.split(sentence).any { s -> revealVerb.containsMatchIn(s) && walkAwayWords.containsMatchIn(s) && !negated.containsMatchIn(s) }

    // Their figure named as theirs ("thanks for the 6% offer", "they offered me 6%", "6% is below what I can accept") describes their
    // position, it is not advice to take it; "accept the 6% offer" or "settle for 6%" still is.
    private const val FIG = """[$€£₹]?\d[\d,.]*\s*(?:%|percent|per cent|k|thousand|lakhs?|lacs?|crores?|cr|millions?|mn|m)?"""
    private val theirFigure = listOf(
        Regex("""(?i)\b(?:the|their|your|his|her|this|that|an?)\s+$FIG\s+(?:offer|quote|bid|proposal|raise|increase)\b"""),
        Regex("""(?i)\b(?:offered|quoted|proposed|offer of)\s+(?:(?:me|us|you|him|her|them)\s+)?(?:(?:at|a|an|only|just)\s+)*$FIG"""),
        Regex("""(?i)$FIG\s+is\s+(?:well\s+|far\s+)?(?:below|under|less than|short of|too low|not enough)\b""")
    )
    private val takesIt = Regex("""(?i)\b(?:accept|take|settle|agree to|go with|sign|lock in|close at)\w*\s+(?:\w+\s+){0,2}?$FIG""")

    /** [s] with their figures removed when it does not also advise taking a figure. */
    private fun withoutTheirFigures(s: String): String =
        if (takesIt.containsMatchIn(s)) s else theirFigure.fold(s) { acc, r -> r.replace(acc, " ") }

    private fun crosses(text: String, frame: DealFrame): Boolean {
        val walk = frame.walkAway ?: return false
        return text.split(sentence).any { s ->
            DealNumbers.figures(withoutTheirFigures(s)).any { frame.compatible(it) && isPrice(it.value, walk.value) && frame.worse(it.value, walk.value) }
        }
    }

    private fun hasSecret(text: String, frame: DealFrame) = DealNumbers.figures(text).any { WalkAwayMask.secret(it, frame) }

    private fun revealsAsSpeech(text: String, frame: DealFrame): Boolean =
        quoted.findAll(text).any { hasSecret(it.value, frame) } ||
            text.split(sentence).any { s -> speech.containsMatchIn(s) && hasSecret(s, frame) }

    private fun isPrice(v: Double, walk: Double): Boolean =
        walk <= 0.0 || (v >= walk / PRICE_RANGE && v <= walk * PRICE_RANGE)
}
