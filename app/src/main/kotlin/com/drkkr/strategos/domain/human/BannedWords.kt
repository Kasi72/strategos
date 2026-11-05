package com.drkkr.strategos.domain.human

/**
 * Words that must never reach the user in chat: the names of laws, books and authors, and the internal names of the delivery
 * tactics. The coach is told the plain-language hint only; this is the check behind that promise (used on the catalog's own
 * hints now, and on the coach's reply). The plain word "nudge" is fine: people say it.
 */
object BannedWords {
    private val phrases = Regex("""(?i)48\s+laws|\blaws?\s+\d+|laws of power|greene|thaler|sunstein""")
    private val tacticNames: Regex by lazy { Regex(TacticId.values().joinToString("|") { Regex.escape(it.name) }, RegexOption.IGNORE_CASE) }

    fun containedIn(text: String): Boolean = phrases.containsMatchIn(text) || tacticNames.containsMatchIn(text)
}
