package com.drkkr.strategos.domain.agent

import com.drkkr.strategos.domain.model.Scenario

/**
 * The read-only Summary page (blueprint §17.5), also the text shared with a clinician. Everyday words only: the situation,
 * the people involved with a role line, the user's options and the last answer.
 */
object PlainSummary {
    fun build(s: Scenario, lastAnswer: String?): String = buildString {
        appendLine(s.title.ifBlank { "Your decision" })
        appendLine()
        if (s.description.isNotBlank()) { appendLine("The situation"); appendLine(s.description.trim()); appendLine() }
        if (s.stakes.isNotBlank()) { appendLine("What a good outcome looks like"); appendLine(s.stakes.trim()); appendLine() }
        val owner = IntakeChecklist.self(s)
        val others = s.stakeholders.filter { it.id != owner?.id }
        if (others.isNotEmpty()) {
            appendLine("People involved")
            others.forEach { appendLine("• ${it.name}: ${roleLine(s, it.id)}") }
            appendLine()
        }
        val mine = owner?.let { s.actionsFor(it.id) }.orEmpty()
        if (mine.isNotEmpty()) {
            appendLine("Your options")
            mine.forEach { appendLine("• ${it.label}") }
            appendLine()
        }
        if (s.constraints.isNotEmpty()) {
            appendLine("Limits you set")
            s.constraints.forEach { appendLine("• ${it.label}") }
            appendLine()
        }
        lastAnswer?.takeIf { it.isNotBlank() }?.let { appendLine("The latest view"); appendLine(it.trim()) }
    }.trim()

    fun roleLine(s: Scenario, id: String): String {
        val p = s.stakeholders.firstOrNull { it.id == id } ?: return "involved"
        val wants = p.goals.firstOrNull()?.trimEnd('.')
        val base = when {
            p.canVeto -> "can stop this"
            p.gameRole == com.drkkr.strategos.domain.model.GameRole.PLAYER -> "makes a choice that matters here"
            else -> "involved"
        }
        return if (wants != null) "$base. Wants: $wants" else base
    }
}
