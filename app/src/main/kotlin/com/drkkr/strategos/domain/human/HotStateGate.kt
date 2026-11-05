package com.drkkr.strategos.domain.human

import com.drkkr.strategos.domain.agent.Postures
import com.drkkr.strategos.domain.model.Action

/**
 * Protects the cold Planner from the hot Doer (Nudge Ch.2; the empathy gap between a hot and a cold state). When the USER is
 * hot (upset or furious) and the move is a rare, emotional, hard-to-take-back one (immediate, irreversible, or a public
 * boundary), the move is held until they have slept on it. The gate changes WHEN, never WHETHER, and the user can always say
 * "I'm doing it now". It never applies when there is a safety need.
 * Mirror: when the COUNTERPART is hot and the move needs their yes, the ask waits for when they are calm.
 */
object HotStateGate {
    private val decided = Regex("(?i)(\\bsend(?:ing)?\\b|\\btell(?:ing)?\\b).{0,40}\\b(?:now|tonight|anyway)\\b|\\b(?:now|tonight)\\b.{0,40}\\b(?:send|tell)|already decided")

    /** True when the message says they will send or say it now or tonight, or have already decided. Their choice wins. */
    fun userSaysNow(text: String): Boolean = decided.containsMatchIn(text)

    private val asks = setOf("asks_them_to_give_up", "open_ended", "sets_default", "two_choices", "future_commitment", "their_interest", "appeals_to_fairness_or_gratitude", "corrects_them")

    /** A move that needs the other person to say yes (anything but pulling back). */
    private fun needsTheirYes(move: Action): Boolean {
        val tags = DeliveryTags.of(move)
        if ("withdraws" in tags) return false
        return Postures.of(move) == Postures.REPAIR || tags.any { it in asks }
    }

    fun timing(ctx: HumanContext?, move: Action?, support: Boolean, userSaysNow: Boolean): Timing {
        if (ctx == null || move == null || support || userSaysNow) return Timing.NOW
        val tags = DeliveryTags.of(move)
        val confrontation = "immediate" in tags || "irreversible" in tags || (Postures.of(move) == Postures.BOUNDARY && "public" in tags)
        if ((ctx.yourStateLevel ?: 0.0) >= 2.0 && confrontation) return Timing.AFTER_COOLING
        if ((ctx.theirStateLevel ?: 0.0) >= 2.0 && needsTheirYes(move)) return Timing.WHEN_CALM
        return Timing.NOW
    }
}
