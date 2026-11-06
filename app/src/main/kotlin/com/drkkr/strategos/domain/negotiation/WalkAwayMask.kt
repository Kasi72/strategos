package com.drkkr.strategos.domain.negotiation

import com.drkkr.strategos.domain.agent.IntakeChecklist
import com.drkkr.strategos.domain.model.Scenario
import kotlin.math.abs

/**
 * Negotiation Numbers, CP-N decision (B): the walk-away and the fallback never reach the coach, or the engine's own text, as a
 * figure to say. Call 1 often writes the walk-away into an option ("Tell Karthik plainly that $45,000 is your hard ceiling"); the
 * engine's pick stays, only the number goes: "your ceiling" (buyer) / "your floor" (seller). The fallback is masked ("your other
 * option") only when walking away is NOT credible (a weak fallback reveals weakness); a real, strong alternative is legitimate
 * leverage and may be named (CP-N, ng6).
 * The analysis view and outgoing text only; nothing saved is changed.
 */
object WalkAwayMask {
    /** A figure within ±1% of the walk-away or the fallback is that figure. */
    const val CLOSE = 0.01
    const val CEILING = "your ceiling"
    const val FLOOR = "your floor"
    const val OTHER_OPTION = "your other option"

    fun near(v: Double, ref: Double): Boolean = abs(v - ref) <= CLOSE * abs(ref)

    /** True when [a] must stay private: the walk-away, or a fallback that is not credible (same unit, no conflicting currency, within ±1%). */
    fun secret(a: Amount, frame: DealFrame): Boolean {
        val walk = frame.walkAway ?: return false
        if (!frame.compatible(a)) return false
        return near(a.value, walk.value) || weakFallback(frame)?.let { near(a.value, it.value) } == true
    }

    /** The fallback, only when walking away is not credible; a credible (or unknown) one may be said. */
    private fun weakFallback(frame: DealFrame): Amount? = frame.fallback.takeIf { frame.walkAwayCredible == false }

    /** "that your ceiling is your hard ceiling" (what masking makes of "that $45,000 is your hard ceiling") -> "that you have a firm ceiling". */
    private val doubled = Regex("""(?i)\byour (ceiling|floor)(?: a (?:year|month))? is your (?:\w+ )?\1\b""")

    fun text(t: String, frame: DealFrame?): String {
        val walk = frame?.walkAway ?: return t
        val role = if (frame.direction == Direction.LOWER_IS_BETTER) "ceiling" else "floor"
        fun mark(a: Amount): String? = when {
            !frame.compatible(a) -> null
            near(a.value, walk.value) -> WALK
            weakFallback(frame)?.let { near(a.value, it.value) } == true -> OTHER
            else -> null
        }
        // ID1.7: a range is one figure here ("around 40–45k" with a 40k walk-away): either end private masks the whole range.
        val ranged = rangeFigure.replace(t) { m ->
            val cur = m.groupValues[1]; val suffix = m.groupValues[5].ifEmpty { m.groupValues[3] }
            val ends = listOf(m.groupValues[2] + m.groupValues[3].ifEmpty { m.groupValues[5] }, m.groupValues[4] + suffix)
                .mapNotNull { DealNumbers.parse(cur + it) }
            val marks = ends.mapNotNull(::mark)
            when { WALK in marks -> WALK; OTHER in marks -> OTHER; else -> m.value }
        }
        val marked = DealNumbers.replaceFigures(ranged) { a -> mark(a) }
        if (marked == t) return t
        return firm(capitalised(phrase(marked, role)))
    }

    private const val WALK = "\u0001"
    private const val OTHER = "\u0002"
    /** Put before text the mask wrote, so a sentence that now starts with it gets a capital (and removed after). */
    private const val NEW = "\u0003"
    private const val PERIOD = """(?: (?:a|per) (?:year|month))?"""
    private const val ADJ = """(?:hard|firm|absolute|strict|final|walk-away|walkaway|bottom) """
    private const val NOUN = """(?:ceiling|floor|limit|max|maximum|minimum|min|cap|budget|line)"""
    private val rangeFigure = Regex(
        """(?:([₹$€£])\s*)?(?<![\d.,])(\d[\d,]*(?:\.\d+)?)\s*(k|thousand|lakhs?|lacs?|l|crores?|cr|millions?|mn|m)?\s*(?:-|–|—|to)\s*[₹$€£]?\s*(\d[\d,]*(?:\.\d+)?)\s*(k|thousand|lakhs?|lacs?|l|crores?|cr|millions?|mn|m)?(?![a-z\d])""",
        RegexOption.IGNORE_CASE
    )

    /** The marked figures written as words that fit where they stand: after "your"/"my", after an article, before a noun, alone. */
    private fun phrase(t: String, role: String): String {
        val w = WALK; val o = OTHER
        var x = t
        fun re(p: String) = Regex(p, RegexOption.IGNORE_CASE)
        // "$40k is my floor" -> "I have a firm floor"; "$45,000 is your hard ceiling" -> "you have a firm ceiling"
        x = re("""$w$PERIOD is (your|my) (?:$ADJ)?($NOUN)\b""").replace(x) { m ->
            (if (m.groupValues[1].equals("my", true)) "${NEW}I have" else "${NEW}you have") + " a firm ${m.groupValues[2].lowercase()}"
        }
        // "my floor is $40k" -> "my floor is firm"
        x = re("""\b(your|my) ((?:$ADJ)?$NOUN) is $w$PERIOD""").replace(x) { m -> "${m.groupValues[1]} ${m.groupValues[2]} is firm" }
        // "name your $45,000 ceiling" -> "name your ceiling"; "stick to my 40k" -> "stick to my floor"
        x = re("""\b(your|my|our) $w$PERIOD(?= (?:$ADJ)?$NOUN\b)""").replace(x) { m -> m.groupValues[1] }
        x = re("""\b(your|my|our) $w$PERIOD""").replace(x) { m -> "${m.groupValues[1]} $role" }
        // "the 40k floor" -> "a firm floor"; "the $45,000" -> "your ceiling"
        x = re("""\b(?:the|a|an) $w$PERIOD (?:$ADJ)?($NOUN)\b""").replace(x) { m -> "a firm ${m.groupValues[1].lowercase()}" }
        x = re("""\b(?:the|a|an) $w$PERIOD""").replace(x) { "your $role" }
        // "Hold 100k floor" -> "Hold your floor"; anything left -> "your ceiling" / "your floor"
        x = re("""$w$PERIOD(?= (?:$ADJ)?$NOUN\b)""").replace(x) { "${NEW}your" }
        x = re("""$w$PERIOD""").replace(x) { "${NEW}your $role" }
        // the fallback: "my $30k" -> "my other option"; otherwise "your other option"
        x = re("""\b(your|my) $o""").replace(x) { m -> "${m.groupValues[1]} other option" }
        x = re("""\b(?:the|a|an) $o""").replace(x) { "your other option" }
        return x.replace(o, "${NEW}$OTHER_OPTION")
    }

    /** A sentence that now starts with the mask's words gets a capital letter. */
    private fun capitalised(t: String): String =
        Regex("""(^|[.!?]\s+)$NEW(\p{Ll})""").replace(t) { m -> m.groupValues[1] + m.groupValues[2].uppercase() }.replace(NEW, "")

    /** "the your floor floor" (what masking makes of "Name the 100k floor") -> "a firm floor"; a bare "your floor floor" -> "your floor". */
    private val doubledNoun = Regex("""(?i)\b(?:(the|a|an|my) )?your (ceiling|floor) \2\b""")
    /** Last safety net (ID1.7): a determiner the mask doubled ("your your") is said once. */
    private val doubledDeterminer = Regex("""(?i)\b(your|my) (?:your|my)\b""")

    private fun firm(t: String) = doubledDeterminer.replace(doubledNoun.replace(doubled.replace(t) { m -> "you have a firm ${m.groupValues[1].lowercase()}" }) { m ->
        if (m.groupValues[1].isNotEmpty()) "a firm ${m.groupValues[2].lowercase()}" else "your ${m.groupValues[2].lowercase()}"
    }) { m -> m.groupValues[1] }

    /** The user's option labels, masked. Null frame, or nothing to mask = the same instance (I-N1). */
    fun apply(view: Scenario, frame: DealFrame?): Scenario {
        if (frame?.walkAway == null) return view
        val owner = IntakeChecklist.self(view) ?: return view
        var changed = false
        val actions = view.actions.map { a ->
            if (a.stakeholderId != owner.id) return@map a
            val masked = text(a.label, frame)
            if (masked != a.label) { changed = true; a.copy(label = masked) } else a
        }
        return if (changed) view.copy(actions = actions) else view
    }
}
