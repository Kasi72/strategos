package com.drkkr.strategos.ui.calm.talk

import com.drkkr.strategos.agent.ChatItem
import com.drkkr.strategos.data.repo.RevealMath
import java.util.concurrent.TimeUnit

/** The engine's label under a coach reply: "Wait · Mutual trust · Robust". Tapping it opens the X-ray. */
data class EngineTagUi(val move: String, val archetype: String, val robust: Boolean) {
    val robustness: String get() = if (robust) "Robust" else "Fragile"
}

/** One coach reply split the way the card lays it out: one lead, a body, an optional list, one closing question. */
/** The whole reply as plain text for the clipboard: no emphasis markers, bullets kept. */
fun CoachText.plain(): String = listOfNotNull(
    lead.takeIf { it.isNotEmpty() }, body.takeIf { it.isNotEmpty() },
    bullets.takeIf { it.isNotEmpty() }?.joinToString("\n") { "• $it" }, closing.takeIf { it.isNotEmpty() }
).joinToString("\n\n").replace("**", "")

data class CoachText(val lead: String, val body: String, val bullets: List<String>, val closing: String)

/** A row in the conversation stream. */
sealed class TalkTurn {
    abstract val id: String
    data class User(override val id: String, val text: String, val queued: Boolean = false) : TalkTurn()
    data class Coach(override val id: String, val text: CoachText, val tag: EngineTagUi?) : TalkTurn()
    /** A clarifying question with tappable answers; [answered] once the user has replied. */
    data class Ask(override val id: String, val question: String, val options: List<String>, val answered: Boolean) : TalkTurn()
    data class Note(override val id: String, val text: String) : TalkTurn()
    data class Support(override val id: String, val text: String) : TalkTurn()
}

data class TopicUi(val id: String, val name: String, val lastLine: String, val whenLabel: String, val isOpen: Boolean)

/** Why the coach can't answer right now. */
enum class TalkProblem { NONE, KEY_REJECTED, FAILED, NO_KEY, AI_OFF }

data class TalkUiState(
    val projectName: String = "",
    val topics: List<TopicUi> = emptyList(),
    val turns: List<TalkTurn> = emptyList(),
    val thinking: Boolean = false,
    val activity: String? = null,
    val problem: TalkProblem = TalkProblem.NONE,
    val offline: Boolean = false,
    val reveal: RevealMath = RevealMath.ON_TAP,
    val canRetry: Boolean = false,
    val loading: Boolean = true
)

data class TalkActions(
    val onBack: () -> Unit = {},
    val onSend: (String) -> Unit = {},
    val onStop: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onSelectTopic: (String) -> Unit = {},
    val onNewTopic: (String) -> Unit = {},
    val onOpenXRay: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onOpenMemory: () -> Unit = {}
)

/** Pure mapping from stored chat items to what the Talk screen draws, kept out of the composables for testing. */
object TalkUi {

    val TOPIC_STARTERS = listOf("What to say to my parents", "The money side", "If he says no", "How I'm feeling about it")

    private val BULLET = Regex("^\\s*([-•*])\\s+")

    fun turns(items: List<ChatItem>, queued: List<String> = emptyList()): List<TalkTurn> {
        val out = mutableListOf<TalkTurn>()
        for (item in items) when (item) {
            is ChatItem.UserText -> out += TalkTurn.User(item.id, item.text)
            is ChatItem.AssistantText -> out += TalkTurn.Coach(item.id, split(item.text), item.pill?.let(::tag))
            is ChatItem.Questions -> item.questions.firstOrNull()?.let { q ->
                out += TalkTurn.Ask(item.id, q.text, q.options, item.answered)
            }
            is ChatItem.EventNote -> out += TalkTurn.Note(item.id, item.text)
            else -> Unit   // activity, change sets and the expert cards stay out of the calm conversation
        }
        queued.forEachIndexed { i, t -> out += TalkTurn.User("queued-$i", t, queued = true) }
        return out
    }

    /** "Wait · Mutual trust · Robust" → a tag; null when the pill isn't the three-part form. */
    fun tag(pill: String): EngineTagUi? {
        val parts = pill.split("·").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val robust = !parts.last().equals("Fragile", ignoreCase = true)
        val core = if (parts.last().equals("Robust", true) || parts.last().equals("Fragile", true)) parts.dropLast(1) else parts
        return when {
            core.size >= 2 -> EngineTagUi(core.first(), core.drop(1).joinToString(" · "), robust)
            core.size == 1 -> EngineTagUi(core.first(), "", robust)
            else -> null
        }
    }

    /** Splits a reply into lead / body / list / closing question without inventing any words. */
    fun split(raw: String): CoachText {
        val paras = raw.trim().split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
        if (paras.isEmpty()) return CoachText("", "", emptyList(), "")
        val bullets = mutableListOf<String>()
        val prose = mutableListOf<String>()
        for (p in paras) {
            val lines = p.lines()
            // An intro line followed by bullets in one paragraph splits into prose + list, so the intro can lead.
            val (b, rest) = lines.partition { BULLET.containsMatchIn(it) }
            b.forEach { bullets += it.replace(BULLET, "").trim() }
            if (rest.isNotEmpty()) prose += rest.joinToString("\n").trim()
        }
        if (prose.isEmpty()) return CoachText("", "", bullets, "")
        var closing = ""
        if (prose.size >= 3 && prose.last().trimEnd().endsWith("?")) closing = prose.removeAt(prose.lastIndex)
        var lead = ""
        if (prose.size >= 2 && prose.first().length <= 160) lead = prose.removeAt(0)
        else if (prose.size == 1 && closing.isEmpty() && bullets.isEmpty()) {
            val first = prose.first().split(Regex("(?<=[.!?])\\s"), limit = 2)
            if (first.size == 2 && first[0].length in 12..160 && first[1].length >= 40) { lead = first[0]; prose[0] = first[1] }
        }
        return CoachText(lead, prose.joinToString("\n\n"), bullets, closing)
    }

    /** "2h", "1d", "Oct 22": a compact last-activity label for the topics list. */
    fun whenLabel(at: Long, now: Long): String {
        val mins = TimeUnit.MILLISECONDS.toMinutes(now - at)
        return when {
            mins < 1 -> "now"
            mins < 60 -> "${mins}m"
            mins < 24 * 60 -> "${mins / 60}h"
            mins < 7 * 24 * 60 -> "${mins / (24 * 60)}d"
            else -> java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(java.util.Date(at))
        }
    }
}
