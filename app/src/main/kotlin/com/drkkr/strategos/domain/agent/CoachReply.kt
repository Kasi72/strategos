package com.drkkr.strategos.domain.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Call 2's JSON: the coach guide plus an echo of the move it was told to implement, so the app can check it. */
@Serializable
data class CoachReply(
    @SerialName("engine_move") val engineMove: String? = null,
    @SerialName("what_happened") val whatHappened: String = "",
    @SerialName("clear_win_move") val clearWinMove: String = "",
    @SerialName("exact_words") val exactWords: String = "",
    @SerialName("do_this") val doThis: List<String> = emptyList(),
    @SerialName("dont_do_this") val dontDoThis: List<String> = emptyList(),
    /** Phase G.4: a short, situation-specific closing line (a concrete next step or a real question), replacing the static footer. */
    @SerialName("follow_up") val followUp: String = ""
) {
    fun guide() = CoachGuideSketch(whatHappened, clearWinMove, exactWords, doThis, dontDoThis, followUp)

    private fun all() = listOf(whatHappened, clearWinMove, exactWords) + doThis + dontDoThis
    fun hasPlaceholder() = all().any(Placeholders::has)

    /** The same reply with any `[bracketed placeholder]` removed. */
    fun stripped() = copy(
        whatHappened = Placeholders.strip(whatHappened), clearWinMove = Placeholders.strip(clearWinMove), exactWords = Placeholders.strip(exactWords),
        doThis = doThis.map(Placeholders::strip).filter { it.isNotBlank() }, dontDoThis = dontDoThis.map(Placeholders::strip).filter { it.isNotBlank() }
    )

    /**
     * True when the reply says it implements [move]: case, spacing and hyphen style ignored, and a long move may be
     * lightly reworded (at least 4 words, 80% of the words shared). A different move never passes.
     */
    fun implements(move: String?): Boolean {
        if (move == null || engineMove == null) return false
        val a = norm(engineMove); val b = norm(move)
        if (a == b) return true
        val x = a.split(' ').filter { it.isNotBlank() }.toSet(); val y = b.split(' ').filter { it.isNotBlank() }.toSet()
        return x.size >= 4 && y.size >= 4 && x.intersect(y).size.toDouble() / x.union(y).size >= 0.8
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
        private fun norm(s: String) = SketchConverter.norm(s).trimEnd('.', ' ')

        fun parse(text: String): CoachReply? {
            val start = text.indexOf('{'); val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            return runCatching { json.decodeFromString(serializer(), text.substring(start, end + 1)) }.getOrNull()
                ?.takeIf { it.clearWinMove.isNotBlank() }
        }
    }
}
