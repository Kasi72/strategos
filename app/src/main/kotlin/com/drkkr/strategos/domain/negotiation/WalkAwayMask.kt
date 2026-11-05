package com.drkkr.strategos.domain.negotiation

import com.drkkr.strategos.domain.agent.IntakeChecklist
import com.drkkr.strategos.domain.model.Scenario
import kotlin.math.abs

/**
 * Negotiation Numbers, CP-N decision (B): the walk-away and the fallback never reach the coach, or the engine's own text, as a
 * figure to say. Call 1 often writes the walk-away into an option ("Tell Karthik plainly that $45,000 is your hard ceiling"); the
 * engine's pick stays, only the number goes: "your ceiling" (buyer) / "your floor" (seller), and the fallback is "your other option".
 * The analysis view and outgoing text only; nothing saved is changed.
 */
object WalkAwayMask {
    /** A figure within ±1% of the walk-away or the fallback is that figure. */
    const val CLOSE = 0.01
    const val CEILING = "your ceiling"
    const val FLOOR = "your floor"
    const val OTHER_OPTION = "your other option"

    fun near(v: Double, ref: Double): Boolean = abs(v - ref) <= CLOSE * abs(ref)

    /** True when [a] is the walk-away or the fallback (same unit, no conflicting currency, within ±1%). */
    fun secret(a: Amount, frame: DealFrame): Boolean {
        val walk = frame.walkAway ?: return false
        if (!frame.compatible(a)) return false
        return near(a.value, walk.value) || frame.fallback?.let { near(a.value, it.value) } == true
    }

    fun text(t: String, frame: DealFrame?): String {
        val walk = frame?.walkAway ?: return t
        return DealNumbers.replaceFigures(t) { a ->
            when {
                !frame.compatible(a) -> null
                near(a.value, walk.value) -> if (frame.direction == Direction.LOWER_IS_BETTER) CEILING else FLOOR
                frame.fallback?.let { near(a.value, it.value) } == true -> OTHER_OPTION
                else -> null
            }
        }
    }

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
