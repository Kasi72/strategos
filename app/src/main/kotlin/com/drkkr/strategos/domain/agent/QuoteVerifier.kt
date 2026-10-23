package com.drkkr.strategos.domain.agent

/** Checks that a quote the agent attributes to the user really appears in the user's own messages (blueprint §8.11). */
object QuoteVerifier {
    private const val MIN_LENGTH = 8
    private const val EDGE_CHARS = "\"'“”‘’.,;:!?"
    private val whitespace = Regex("\\s+")

    /** [userTexts] must hold USER messages only, never EVENT or assistant text. */
    fun isVerified(quote: String?, userTexts: List<String>): Boolean {
        if (quote == null) return false
        val q = normalize(quote)
        if (q.length < MIN_LENGTH) return false
        return userTexts.any { normalize(it).contains(q) }
    }

    fun normalize(s: String): String =
        s.lowercase().replace(whitespace, " ").trim().trim { it in EDGE_CHARS || it.isWhitespace() }
}
