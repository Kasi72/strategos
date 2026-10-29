package com.drkkr.strategos.domain.analysis

/** A milestone the trajectory went through, as a numbered pin + line under the momentum chart. */
data class MomentumEvent(val label: String, val at: Long)

/** One recorded quick-check, reduced to what the event line needs. */
data class CheckRecord(val execution: String, val reaction: String, val at: Long, val note: String = "")

/** A trajectory milestone, reduced to what the event line needs (source + time). */
data class MomentumPoint(val source: String, val at: Long, val note: String = "")

/** The Quick-check the Overview offers when something has happened since the last one. */
data class PendingCheck(val question: String, val counterpartName: String?)

/**
 * Turns the deterministic trajectory record into the Overview's momentum pins and the pending
 * Quick-check question. Pure Kotlin (invariant I12: no LLM here); the repository maps DB rows in.
 */
object MomentumEvents {

    const val INITIAL = "INITIAL"
    const val THREAD_TURN = "THREAD_TURN"
    const val QUICK_CHECK = "QUICK_CHECK"
    const val JOURNAL_SHIFT = "JOURNAL_SHIFT"

    /** Ordered pins (oldest first): the start, each quick-check, and each journal shift — the deliberate moments. */
    fun of(points: List<MomentumPoint>, checks: List<CheckRecord>): List<MomentumEvent> {
        val checksByTime = checks.sortedBy { it.at }
        var next = 0
        return buildList {
            points.sortedBy { it.at }.forEach { p ->
                when (p.source) {
                    INITIAL -> add(MomentumEvent("You described the situation", p.at))
                    JOURNAL_SHIFT -> add(MomentumEvent("A journal note shifted things", p.at))
                    QUICK_CHECK -> {
                        val c = checksByTime.getOrNull(next)?.also { next++ }
                        add(MomentumEvent(c?.let(::checkLabel) ?: "You checked in on how it went", p.at))
                    }
                    // THREAD_TURN points are the everyday back-and-forth; they are not pinned.
                }
            }
        }
    }

    /** "You did as suggested; it eased." — a plain line from the quick-check's coded answers. */
    fun checkLabel(c: CheckRecord): String {
        val did = when (c.execution.uppercase()) {
            "DID_AS_SUGGESTED" -> "You did as suggested"
            "TRIED_VARIATION" -> "You tried a variation"
            "SKIPPED_REACTIVE" -> "You reacted instead"
            else -> "You checked in"
        }
        val landed = c.reaction.uppercase().let {
            when {
                it.contains("DE_ESCALATED") || it.contains("DEESCALATED") || it.contains("WARMER") -> "it eased"
                it.contains("ESCALATED") -> "it got tenser"
                it.contains("COLD") -> "it cooled"
                else -> "it held about the same"
            }
        }
        return "$did; $landed"
    }

    /**
     * A check is pending once a conversation turn has happened that a later quick-check has not yet
     * caught up with. The question names the counterpart when we know them.
     */
    fun pendingCheck(points: List<MomentumPoint>, counterpartName: String?): PendingCheck? {
        val lastTurn = points.filter { it.source == THREAD_TURN }.maxByOrNull { it.at } ?: return null
        val lastCheck = points.filter { it.source == QUICK_CHECK }.maxByOrNull { it.at }
        if (lastCheck != null && lastCheck.at >= lastTurn.at) return null
        val who = counterpartName?.takeIf { it.isNotBlank() && !it.equals("You", ignoreCase = true) }
        val question = if (who != null) "How did the last talk with $who go?" else "How did the last talk go?"
        return PendingCheck(question, who)
    }
}
