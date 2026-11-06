package com.drkkr.strategos.domain.agent

import com.drkkr.strategos.domain.model.NegotiationContext

/**
 * The plain-language questions that fill [NegotiationContext] through the conversation, replacing the manual prep form.
 * Nobody has to know the textbook terms (BATNA, reservation point, anchor): each question says what it means.
 * They are optional. Each is asked at most once per thread and one per reply, and the user may ignore them.
 */
object NegotiationQuestions {
    enum class Field { BATNA, RESERVATION, ASPIRATION, OPENING, COUNTERPART }
    data class Question(val field: Field, val text: String)

    /** In the order they help most: the fallback and the floor first. */
    val ALL = listOf(
        Question(Field.BATNA, "If this doesn't work out, what would you do instead?"),
        Question(Field.RESERVATION, "What's the lowest outcome you'd still say yes to?"),
        Question(Field.ASPIRATION, "What would a really good result look like for you?"),
        Question(Field.OPENING, "What are you planning to ask for first?"),
        Question(Field.COUNTERPART, "If you walked away, what do you think they would do?")
    )

    // Whole words / stems only: "bid" must not match "forbidden", and "raise" alone (as in raising a child) is not a negotiation.
    private val negotiationWords = Regex(
        """(?i)\b(negotiat\w*|salary|salaries|offer\w*|contract\w*|settlement|pric(?:e|es|ing)|haggl\w*|quote[sd]?|wages?|pay raise|a raise|the raise|bids?|bidding|counter-?offer)\b"""
    )

    fun looksLikeNegotiation(text: String): Boolean = negotiationWords.containsMatchIn(text)

    /** The three facts that matter most; with two or more missing, the agent asks before answering. */
    private val CORE = setOf(Field.BATNA, Field.RESERVATION, Field.ASPIRATION)

    private fun missing(nc: NegotiationContext?, f: Field): Boolean = when (f) {
        Field.BATNA -> nc?.batna.isNullOrBlank()
        Field.RESERVATION -> nc?.reservationPoint.isNullOrBlank()
        Field.ASPIRATION -> nc?.aspirationPoint.isNullOrBlank()
        Field.OPENING -> nc?.openingAnchor.isNullOrBlank()
        Field.COUNTERPART -> nc?.counterpartBatna.isNullOrBlank()
    }

    /** The next question to ask, or null: not a negotiation, nothing missing, or every missing one was already asked in this thread. */
    fun next(userText: String, nc: NegotiationContext?, earlierAssistantText: List<String>): Question? {
        if (!looksLikeNegotiation(userText)) return null
        return ALL.firstOrNull { q -> missing(nc, q.field) && earlierAssistantText.none { it.contains(q.text) } }
    }

    /**
     * One plain question to ask BEFORE answering (Intent Dispatch I-D6: one question per turn; it was up to two): only for a
     * negotiation, only when at least two of the three core facts (fallback, lowest acceptable, ideal) are still unknown, and only
     * once per thread, so the user can always skip it and still get an answer.
     */
    fun beforeAnswering(userText: String, nc: NegotiationContext?, earlierAssistantText: List<String>): List<Question> {
        if (!looksLikeNegotiation(userText)) return emptyList()
        if (ALL.any { q -> earlierAssistantText.any { it.contains(q.text) } }) return emptyList()
        val unknown = ALL.filter { it.field in CORE && missing(nc, it.field) }
        return if (unknown.size >= 2) unknown.take(1) else emptyList()
    }

    /** Adds [q] to the coach's closing line as one more sentence, marked optional. */
    fun appendTo(followUp: String, q: Question): String =
        listOf(followUp.trim(), "${q.text} (Only if you'd like to say.)").filter { it.isNotEmpty() }.joinToString(" ")

    /** Merges what the user just said into what is saved; a blank new value never erases a saved one. */
    fun merge(old: NegotiationContext?, new: NegotiationContext): NegotiationContext {
        val o = old ?: NegotiationContext()
        fun pick(n: String, v: String) = n.trim().ifBlank { v }
        return NegotiationContext(
            batna = pick(new.batna, o.batna), reservationPoint = pick(new.reservationPoint, o.reservationPoint),
            aspirationPoint = pick(new.aspirationPoint, o.aspirationPoint), openingAnchor = pick(new.openingAnchor, o.openingAnchor),
            zopaNote = pick(new.zopaNote, o.zopaNote), counterpartBatna = pick(new.counterpartBatna, o.counterpartBatna),
            counterpartReservation = pick(new.counterpartReservation, o.counterpartReservation)
        )
    }
}
