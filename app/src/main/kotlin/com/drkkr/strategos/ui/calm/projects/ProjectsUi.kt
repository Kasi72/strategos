package com.drkkr.strategos.ui.calm.projects

import com.drkkr.strategos.domain.analysis.MomentumStatus
import java.util.concurrent.TimeUnit

/** One card in the Projects list (board 04): name, a soft activity line, a momentum word and spark. */
data class ProjectCardUi(
    val id: String,
    val name: String,
    val subtitle: String,
    val status: MomentumStatus,
    val trustSpark: List<Float>,
    val tensionSpark: List<Float>
)

/** Everything the Projects root renders. Sections with no data fall back to their own empty variant. */
data class ProjectsUiState(
    val projects: List<ProjectCardUi> = emptyList(),
    val archivedCount: Int = 0,
    val hasKey: Boolean = true,
    val draft: String = "",
    val loading: Boolean = true
) {
    /** A brand-new user: nothing to show, so offer example starters instead of an empty list. */
    val isFirstRun: Boolean get() = !loading && projects.isEmpty() && archivedCount == 0
    val canSend: Boolean get() = draft.isNotBlank()
}

/** Events the Projects screen raises; the route supplies the implementations. */
data class ProjectsActions(
    val onDraft: (String) -> Unit = {},
    val onSend: () -> Unit = {},
    val onSpeak: () -> Unit = {},
    val onPickExample: (String) -> Unit = {},
    val onOpenProject: (String) -> Unit = {},
    val onRename: (String) -> Unit = {},
    val onArchive: (String) -> Unit = {},
    val onDelete: (String) -> Unit = {},
    val onOpenArchived: () -> Unit = {},
    val onAddKey: () -> Unit = {},
    val onOpenJournal: () -> Unit = {},
    val onOpenSettings: () -> Unit = {}
)

/** Pure helpers for the Projects list, kept out of the view model so they can be unit-tested. */
object ProjectsUi {

    /** The example starters shown to a brand-new user (strings from board 04). */
    val EXAMPLES = listOf(
        "My brother wants me to co-sign a loan for his café.",
        "My co-founder and I keep deadlocking on the same decision.",
        "I need to tell my manager no, and I'm dreading it."
    )

    private val GREETING = Regex("^(hey|hi|hello|hiya)[,!.]?\\s+", RegexOption.IGNORE_CASE)

    /**
     * A short, human project name from a long first message: the first sentence, minus a leading greeting,
     * cut at a word boundary. Display-only (the user can always rename); the stored text is never altered.
     */
    fun displayName(raw: String, max: Int = 48): String {
        val first = raw.trim().lineSequence().firstOrNull().orEmpty()
            .split(Regex("(?<=[.!?])\\s")).firstOrNull().orEmpty()
            .trim().trimEnd('.', '!', '?', ',', ' ')
            .replace(GREETING, "").trim()
        val base = first.ifEmpty { raw.trim() }.replaceFirstChar { it.uppercase() }
        if (base.length <= max) return base.ifEmpty { "Untitled" }
        val cut = base.take(max).substringBeforeLast(' ', base.take(max))
        return cut.trimEnd(',', ' ', '.') + "…"
    }

    /** "Today · 3 topics" — a soft, number-light activity line. */
    fun subtitle(lastActivityAt: Long, now: Long, topicCount: Int): String {
        val topics = if (topicCount == 1) "1 topic" else "$topicCount topics"
        return "${activityLabel(lastActivityAt, now)} · $topics"
    }

    fun activityLabel(at: Long, now: Long): String {
        val days = TimeUnit.MILLISECONDS.toDays(startOfDay(now) - startOfDay(at)).toInt()
        return when {
            days <= 0 -> "Today"
            days == 1 -> "Yesterday"
            days < 7 -> "$days days ago"
            days < 14 -> "Last week"
            else -> "${days / 7} weeks ago"
        }
    }

    private fun startOfDay(t: Long): Long = t - (t % TimeUnit.DAYS.toMillis(1))
}
