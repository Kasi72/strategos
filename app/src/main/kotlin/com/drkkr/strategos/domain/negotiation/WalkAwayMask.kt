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
        return firm(DealNumbers.replaceFigures(t) { a ->
            when {
                !frame.compatible(a) -> null
                near(a.value, walk.value) -> if (frame.direction == Direction.LOWER_IS_BETTER) CEILING else FLOOR
                weakFallback(frame)?.let { near(a.value, it.value) } == true -> OTHER_OPTION
                else -> null
            }
        })
    }

    private fun firm(t: String) = doubled.replace(t) { m -> "you have a firm ${m.groupValues[1].lowercase()}" }

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
