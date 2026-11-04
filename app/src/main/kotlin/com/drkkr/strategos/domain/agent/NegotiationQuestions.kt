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

    private val keywords = setOf(
        "negotiat", "salary", "offer", "contract", "deal", "agreement", "counter", "settlement", "terms",
        "price", "pricing", "bid", "raise", "renew", "wage", "haggle", "quote", "proposal"
    )

    fun looksLikeNegotiation(text: String): Boolean = text.lowercase().let { t -> keywords.any { t.contains(it) } }

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
