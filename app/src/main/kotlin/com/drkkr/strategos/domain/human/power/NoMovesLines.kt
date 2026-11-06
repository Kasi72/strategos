package com.drkkr.strategos.domain.human.power

/**
 * The fixed card line for each [NoMovesReason] (pm6.1). The card shows nothing else when there are no moves, and nothing at all
 * when there is no reason: no default or fallback "why" line exists anywhere. [readAs] is the Call 1 value that triggered a safety
 * reason, shown as "was read as" so a misread is visible and the user can correct it in chat.
 */
object NoMovesLines {
    const val NOTHING_APPLIES = "No hardball move fits what you've described so far."

    private val VULNERABLE = mapOf("child" to "a child", "elder_in_care" to "an elder in care", "unwell" to "unwell", "in_crisis" to "in crisis")
    private val COERCION = mapOf(
        "isolates_you" to "keeping you apart from others",
        "keeps_you_dependent" to "keeping you dependent on them",
        "hot_cold" to "running hot and cold"
    )

    /** The plain words for a Call 1 value ("elder_in_care" -> "an elder in care"); an unknown value is shown as read. */
    fun plain(readAs: String): String = VULNERABLE[readAs] ?: COERCION[readAs] ?: readAs.replace('_', ' ')

    /** The line for [reason]; [name] is the other person as the model names them. Null reason, null line. */
    fun of(reason: NoMovesReason?, readAs: String?, name: String?): String? {
        val who = name?.takeIf { it.isNotBlank() } ?: "the other person"
        return when (reason) {
            null -> null
            NoMovesReason.CLOSE_RELATIONSHIP -> PowerPlanner.CLOSE_DOMAIN_NOTE
            NoMovesReason.THEY_ARE_VULNERABLE -> readAs?.let { "Hardball is off: $who was read as ${plain(it)}." }
            NoMovesReason.COERCION -> readAs?.let { "Hardball is off: $who was read as ${plain(it)}. Protecting your own position comes first." }
            NoMovesReason.NOTHING_APPLIES -> NOTHING_APPLIES
            NoMovesReason.EXPOSED -> "Hardball doesn't pay here: a high chance they'd see through it."
            NoMovesReason.CONTINUES -> "Hardball doesn't pay here: this relationship continues after the deal."
            NoMovesReason.SMALL -> "Hardball doesn't pay here: the gain is small here."
        }
    }
}
