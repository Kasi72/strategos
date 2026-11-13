package com.drkkr.strategos.domain.a2ui

import com.drkkr.strategos.domain.files.AttachedFile
import com.drkkr.strategos.domain.intent.IdentifyGaps
import com.drkkr.strategos.domain.intent.NextQuestion
import com.drkkr.strategos.domain.intent.TopOption
import com.drkkr.strategos.domain.intent.TopOptions
import kotlinx.serialization.json.JsonArray

/** A card the app decided to show, and the short name it is stored under. */
data class TurnCard(val kind: String, val messages: JsonArray)

/**
 * The cards the app shows on its own, with no model involved: each is built here from what this turn already produced
 * (the engine's question, its ranked options, what was just saved, the file that was read). Pure; every word is either
 * a fixed template or text the turn already holds.
 */
object TurnCards {
    const val QUICK_ANSWERS = "quick_answers"
    const val OPTIONS = "options"
    const val PICKED_UP = "picked_up"
    const val TIMELINE = "timeline"

    /** At most this many cards under one reply, so the reply stays the main thing on screen. */
    const val MAX_UNDER_REPLY = 2
    private const val MAX_LINES = 5
    private const val MAX_STEPS = 8

    /** Role names as the user reads them. */
    val ROLE_LABELS: Map<String, String> = mapOf(
        TopOptions.BEST to "Best balance", TopOptions.UPSIDE to "Most to gain", TopOptions.INSURANCE to "Safest"
    )

    /**
     * The cards for under a reply, most useful first. [shownReply] is the text the user will actually read: the quick
     * answers only appear when their question is in it, and the options only when every one of them is named in it,
     * so a card never offers something the reply did not.
     */
    fun underReply(shownReply: String, question: NextQuestion?, otherName: String?, top: List<TopOption>?, pickedUp: List<String>): List<TurnCard> {
        val out = mutableListOf<TurnCard>()
        if (question != null && shownReply.contains(question.text)) quickAnswers(question, otherName)?.let { out += it }
        val named = top?.takeIf { it.size >= 2 && it.all { o -> shownReply.contains(o.move, ignoreCase = true) } }
        (named?.let(::options) ?: pickedUp(pickedUp))?.let { out += it }
        return out.take(MAX_UNDER_REPLY)
    }

    /** Tappable answers to the engine's closing question, for the questions that have a short answer. */
    fun quickAnswers(q: NextQuestion, otherName: String?): TurnCard? {
        val name = otherName?.takeIf { it.isNotBlank() } ?: return null
        val answers: List<Pair<String, String>> = when (q.id) {
            IdentifyGaps.THEIR_REPLY -> listOf(
                "Usually engages" to "$name usually engages when I push on something like this.",
                "Usually digs in" to "$name usually digs in when I push on something like this.",
                "It varies" to "It varies: sometimes $name engages, sometimes $name digs in."
            )
            IdentifyGaps.DECIDER -> listOf(
                "$name decides" to "$name makes the final call.",
                "Someone else" to "Someone else makes the final call, not $name.",
                "Not sure" to "I'm not sure who makes the final call."
            )
            else -> return null
        }
        val w = SurfaceWriter(QUICK_ANSWERS)
        val buttons = answers.map { (label, text) -> w.button(label, UiAction(A2ui.REPLY, mapOf("text" to text))) }
        w.column(listOf(w.text("Quick answer", "caption"), w.row(buttons)), id = A2ui.ROOT)
        return TurnCard(QUICK_ANSWERS, w.messages())
    }

    /** The engine's ranked moves as tappable rows. The reply already explains each; a tap asks for more on one. */
    fun options(top: List<TopOption>): TurnCard? {
        val shown = top.filter { it.move.isNotBlank() }.take(3)
        if (shown.size < 2) return null
        val w = SurfaceWriter(OPTIONS)
        val rows = shown.map { o ->
            w.option(ROLE_LABELS[o.role] ?: "Option", o.move, action = UiAction(A2ui.REPLY, mapOf("text" to "Tell me more about this option: ${o.move}")))
        }
        w.card(w.column(listOf(w.text("Your options", "h3")) + rows), id = A2ui.ROOT)
        return TurnCard(OPTIONS, w.messages())
    }

    /** What this turn added to the app's picture (people, options), with a way to check or fix it. */
    fun pickedUp(lines: List<String>): TurnCard? {
        val shown = lines.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (shown.isEmpty()) return null
        val w = SurfaceWriter(PICKED_UP)
        val body = shown.take(MAX_LINES).map { w.text(it) }
        val more = if (shown.size > MAX_LINES) listOf(w.text("and ${shown.size - MAX_LINES} more", "caption")) else emptyList()
        val check = w.button("Check or fix", UiAction(A2ui.OPEN_OVERVIEW))
        w.card(w.column(listOf(w.text("What I picked up", "h3")) + body + more + check), id = A2ui.ROOT)
        return TurnCard(PICKED_UP, w.messages())
    }

    private val PHASE = Regex("""^- (.+): (\d+)(?: \((.+)\))?$""")

    /**
     * How a shared conversation unfolded, from the brief the file reader already wrote (its "HOW IT UNFOLDED" and
     * "LONG SILENCES" lists). Null for a file that has no such timeline.
     */
    fun timeline(file: AttachedFile): TurnCard? {
        val phases = section(file.body, "HOW IT UNFOLDED:").mapNotNull { PHASE.matchEntire(it) }
        if (phases.size < 2) return null
        val noun = if (file.facts.contains("message", ignoreCase = true)) "messages" else "entries"
        val w = SurfaceWriter(TIMELINE)
        val steps = phases.takeLast(MAX_STEPS).map { m ->
            val n = m.groupValues[2]
            w.step(m.groupValues[1], if (n == "1") "1 ${noun.dropLast(1).replace("entrie", "entry")}" else "$n $noun", m.groupValues[3].replace(", ", " · "))
        }
        val silences = section(file.body, "LONG SILENCES:").map { it.removePrefix("- ").trim() }.filter { it.isNotEmpty() }
        val quiet = if (silences.isEmpty()) emptyList() else listOf(w.divider(), w.text("Long silences", "caption")) + silences.take(3).map { w.text(it) }
        w.card(w.column(listOf(w.text("How it unfolded", "h3"), w.text(file.name, "caption")) + steps + quiet), id = A2ui.ROOT)
        return TurnCard(TIMELINE, w.messages())
    }

    /** The "- " lines that directly follow [heading] in a brief. */
    private fun section(body: String, heading: String): List<String> =
        body.lines().dropWhile { it.trim() != heading }.drop(1).takeWhile { it.startsWith("- ") }
}
