package com.drkkr.strategos.domain.agent

import kotlinx.serialization.json.Json

/** A standing "what to do / what to avoid" line on the Overview. */
enum class GuidanceKind { DO, DONT }

/** Where a line came from: a conversation thread, or an applied journal takeaway. */
enum class GuidanceSource { THREAD, JOURNAL }

/**
 * One line of standing guidance. [refId] jumps to where it came from: the coach reply (thread) or the
 * journal entry. [sourceLabel] is the thread title or "your journal"; the screen prefixes "From ".
 */
data class GuidanceItem(
    val text: String,
    val kind: GuidanceKind,
    val source: GuidanceSource,
    val sourceLabel: String,
    val refId: String?,
    val at: Long
)

/**
 * The project's consolidated Do's and Don'ts, merged across every thread's latest coach reply and the
 * journal takeaways the user applied, de-duplicated by text (newest wins), each kind newest-first.
 */
data class StandingGuidance(val dos: List<GuidanceItem>, val donts: List<GuidanceItem>) {
    val isEmpty: Boolean get() = dos.isEmpty() && donts.isEmpty()

    companion object {
        val EMPTY = StandingGuidance(emptyList(), emptyList())

        fun of(items: List<GuidanceItem>): StandingGuidance {
            val deduped = items
                .groupBy { it.kind to normalize(it.text) }
                .values.map { group -> group.maxByOrNull { it.at }!! }
            return StandingGuidance(
                dos = deduped.filter { it.kind == GuidanceKind.DO }.sortedByDescending { it.at },
                donts = deduped.filter { it.kind == GuidanceKind.DONT }.sortedByDescending { it.at }
            )
        }

        private val SPACES = Regex("\\s+")
        private fun normalize(t: String) = t.lowercase().trim().trimEnd('.', '!', '?', ' ').replace(SPACES, " ")
    }
}

/** Stores/reads the structured coach guide on a message payload so the Overview can aggregate it later. */
object GuidanceCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(g: CoachGuideSketch): String = json.encodeToString(CoachGuideSketch.serializer(), g)
    fun decode(s: String): CoachGuideSketch? = runCatching { json.decodeFromString(CoachGuideSketch.serializer(), s) }.getOrNull()
}
