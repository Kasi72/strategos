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
    @SerialName("dont_do_this") val dontDoThis: List<String> = emptyList()
) {
    fun guide() = CoachGuideSketch(whatHappened, clearWinMove, exactWords, doThis, dontDoThis)

    private fun all() = listOf(whatHappened, clearWinMove, exactWords) + doThis + dontDoThis
    fun hasPlaceholder() = all().any(Placeholders::has)

    /** The same reply with any `[bracketed placeholder]` removed. */
    fun stripped() = copy(
        whatHappened = Placeholders.strip(whatHappened), clearWinMove = Placeholders.strip(clearWinMove), exactWords = Placeholders.strip(exactWords),
        doThis = doThis.map(Placeholders::strip).filter { it.isNotBlank() }, dontDoThis = dontDoThis.map(Placeholders::strip).filter { it.isNotBlank() }
    )

    /** True when the reply says it implements [move] (case, spacing and hyphen style ignored). */
    fun implements(move: String?): Boolean = move != null && engineMove != null && norm(engineMove) == norm(move)

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
