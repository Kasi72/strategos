package com.drkkr.strategos.ui.calm.journal

import com.drkkr.strategos.data.repo.JournalEntryView
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The five feelings on the composer (board 08) and the stored tags they map to. */
enum class Mood(val label: String, val tag: String) {
    CALM("Calm", "CALM"), UNEASY("Uneasy", "ANXIOUS"), ANGRY("Angry", "FRUSTRATED"), HOPEFUL("Hopeful", "HOPEFUL"), TIRED("Tired", "DRAINED");

    companion object {
        fun fromTag(tag: String): Mood? = values().firstOrNull { it.tag == tag }
    }
}

data class JournalEntryUi(
    val id: String,
    val day: LocalDate,
    val groupLabel: String,
    val timeLabel: String,
    val mood: Mood?,
    val text: String,
    val linkedProjectId: String? = null,
    val linkedProjectName: String? = null,
    val suggestedProjectId: String? = null,
    val suggestedProjectName: String? = null
)

data class JournalUiState(
    val entries: List<JournalEntryUi> = emptyList(),
    val today: LocalDate = LocalDate.now(),
    val loading: Boolean = true,
    val savedToast: Boolean = false
) {
    val isEmpty: Boolean get() = !loading && entries.isEmpty()
    val writtenDays: Set<LocalDate> get() = entries.map { it.day }.toSet()
    val linkedDays: Set<LocalDate> get() = entries.filter { it.linkedProjectId != null }.map { it.day }.toSet()
}

data class JournalActions(
    val onSave: (text: String, mood: Mood?) -> Unit = { _, _ -> },
    val onLink: (entryId: String, projectId: String) -> Unit = { _, _ -> },
    val onDismissSuggestion: (entryId: String, projectId: String) -> Unit = { _, _ -> },
    val onOpenProject: (String) -> Unit = {},
    val onToastShown: () -> Unit = {}
)

/** Pure helpers for the Journal: grouping, the week strip and the month grid. */
object JournalUi {

    val STARTERS = listOf("Something I can’t say out loud", "What happened today", "What I’m afraid of")

    fun build(
        entries: List<JournalEntryView>, titles: Map<String, String>,
        suggestions: Map<String, String>,     // entryId -> suggested project id
        now: Long, zone: ZoneId = ZoneId.systemDefault()
    ): List<JournalEntryUi> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return entries.sortedByDescending { it.createdAt }.map { e ->
            val at = Instant.ofEpochMilli(e.createdAt).atZone(zone)
            val sid = suggestions[e.id]?.takeIf { e.linkedScenarioId == null && it in titles }
            JournalEntryUi(
                id = e.id, day = at.toLocalDate(), groupLabel = groupLabel(at.toLocalDate(), today),
                timeLabel = at.format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())).lowercase(),
                mood = Mood.fromTag(e.moodTag), text = e.text,
                linkedProjectId = e.linkedScenarioId?.takeIf { it in titles }, linkedProjectName = e.linkedScenarioId?.let { titles[it] },
                suggestedProjectId = sid, suggestedProjectName = sid?.let { titles[it] }
            )
        }
    }

    fun groupLabel(day: LocalDate, today: LocalDate): String = when {
        day == today -> "Today"
        day == today.minusDays(1) -> "Yesterday"
        day.year == today.year -> day.format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()))
        else -> day.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault()))
    }

    /** Monday-to-Sunday week containing [today]. */
    fun week(today: LocalDate): List<LocalDate> {
        val monday = today.with(DayOfWeek.MONDAY)
        return (0..6).map { monday.plusDays(it.toLong()) }
    }

    /** Month grid cells, Monday first: nulls pad the days before the 1st. */
    fun monthCells(month: YearMonth): List<LocalDate?> {
        val lead = month.atDay(1).dayOfWeek.value - 1
        return List(lead) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
    }

    fun entriesCountLabel(n: Int) = if (n == 1) "1 entry" else "$n entries"
}
