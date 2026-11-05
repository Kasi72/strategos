package com.drkkr.strategos.domain.agent

import com.drkkr.strategos.domain.human.BannedWords

/**
 * The reply when the user may be unsafe (the model flagged support, or the radar saw coercive control in a close relationship).
 * It is warm and short: acknowledge, suggest someone they trust, point to the support card. It is NEVER strategy. Advising the user
 * to confront, stand firm with, withhold from, or test the other person (or to leave suddenly, record, check a phone, involve their family or employer, threaten, or take the children) can escalate a dangerous situation, so a reply that does
 * is refused and the fixed [FALLBACK] is shown instead.
 */
object SafetyReply {
    const val FALLBACK = "That sounds really hard, and I'm glad you told me. You don't have to sort this out alone: please talk to someone you trust about what's going on. If you ever feel unsafe, the support card has numbers you can call right now."

    private val forbidden = Regex(
        "(?i)stand(?:ing)? (?:firm|your ground)|confront|don'?t apologi[sz]e|do not apologi[sz]e|won'?t keep guessing|withhold|" +
            "hold (?:the|your) (?:line|boundary)|\\btest(?:ing)? (?:him|her|them|his|their)\\b|ultimatum|stop asking|" +
            "leave tonight|pack your bags|\\brecord(?:ing)? (?:him|her|them)\\b|secretly record|check(?:ing)? (?:his|her|their) phone|" +
            "tell (?:his|her|their) family|\\bthreaten\\b|call (?:his|her|their) boss|take the kids"
    )

    fun acceptable(text: String?): Boolean {
        val t = text?.trim().orEmpty()
        return t.isNotEmpty() && t.length <= 700 && !t.startsWith("{") && !forbidden.containsMatchIn(t) && !BannedWords.containedIn(t)
    }
}
