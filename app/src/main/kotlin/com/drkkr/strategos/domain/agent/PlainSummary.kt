package com.drkkr.strategos.domain.agent

import com.drkkr.strategos.domain.model.FactStatus
import com.drkkr.strategos.domain.model.Scenario

/**
 * The read-only Summary page (blueprint §17.5), also the text shared with a clinician. Everyday words only: the situation,
 * the people involved with a role line, the user's options and the last answer.
 */
object PlainSummary {
    private const val VIEW_HEADING = "Here's what I see"

    fun build(s: Scenario, lastAnswer: String?): String = buildString {
        appendLine(s.title.ifBlank { "Your decision" })
        appendLine()
        if (s.description.isNotBlank()) { appendLine("The situation"); appendLine(s.description.trim()); appendLine() }
        if (s.stakes.isNotBlank()) { appendLine("What a good outcome looks like"); appendLine(s.stakes.trim()); appendLine() }
        val owner = IntakeChecklist.self(s)
        val others = s.stakeholders.filter { it.id != owner?.id }
        if (others.isNotEmpty()) {
            appendLine("People involved")
            others.forEach { appendLine(if (it.canVeto) "• Needs ${it.name}'s willingness to work${wantsOf(it)}" else "• ${it.name}: ${roleLine(s, it.id)}") }
            appendLine()
        }
        val mine = owner?.let { s.actionsFor(it.id) }.orEmpty()
        if (mine.isNotEmpty()) {
            appendLine("Your options")
            mine.forEach { appendLine("• ${it.label}") }
            appendLine()
        }
        // "Limits you set": only ones the person stated or confirmed, never the model's proposals.
        val limits = s.constraints.filter { it.factStatus != FactStatus.AI_INFERRED }
        if (limits.isNotEmpty()) {
            appendLine("Limits you set")
            limits.forEach { appendLine("• ${it.label}") }
            appendLine()
        }
        // The answer starts with "What I understood", which the sections above already cover; keep it from "Here's what I see".
        lastAnswer?.takeIf { it.isNotBlank() }?.let { a ->
            val from = a.indexOf(VIEW_HEADING).takeIf { it >= 0 } ?: 0
            appendLine("The latest view"); appendLine(a.substring(from).trim())
        }
    }.trim()

    private fun wantsOf(p: com.drkkr.strategos.domain.model.Stakeholder): String =
        p.goals.firstOrNull()?.takeIf { p.factStatus != FactStatus.AI_INFERRED }?.let { ". Wants: ${it.trimEnd('.')}" }.orEmpty()

    fun roleLine(s: Scenario, id: String): String {
        val p = s.stakeholders.firstOrNull { it.id == id } ?: return "involved"
        val wants = p.goals.firstOrNull()?.takeIf { p.factStatus != FactStatus.AI_INFERRED }?.trimEnd('.')
        val base = when {
            p.gameRole == com.drkkr.strategos.domain.model.GameRole.PLAYER -> "makes a choice that matters here"
            else -> "involved"
        }
        return if (wants != null) "$base. Wants: $wants" else base
    }
}
