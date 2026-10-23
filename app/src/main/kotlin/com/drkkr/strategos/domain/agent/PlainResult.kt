package com.drkkr.strategos.domain.agent

import com.drkkr.strategos.domain.analysis.PipelineResult
import com.drkkr.strategos.domain.framework.ExecutiveStatus
import com.drkkr.strategos.domain.model.GameRole
import com.drkkr.strategos.domain.model.Scenario

/**
 * SIMPLE PROFILE: the answer the user reads, written by the app from the engine's report (no second model call, nothing
 * the model could invent). Everyday words only: no scores, percentages, framework names or status labels.
 */
object PlainResult {
    private const val MAX_UNDERSTOOD = 8
    private const val MAX_OPTIONS = 3
    private const val MAX_WATCH = 2
    const val FOOTER = "This is a best guess based on what you told me. Tell me what happened or what's different, and I'll update it."

    fun build(s: Scenario, pr: PipelineResult, understood: List<String>): String {
        val report = pr.report ?: return needsMore(pr)
        return buildString {
            if (understood.isNotEmpty()) {
                appendLine("What I understood")
                understood.take(MAX_UNDERSTOOD).forEach { appendLine("• $it") }
                appendLine()
            }
            appendLine("Here's what I see")
            append(statusLine(report.executiveStatus))
            if (pr.provenance.payoffsAi > 0) append(" Some of this rests on my own guesses about how each person would feel.")
            appendLine()
            appendLine()

            val open = report.pathways.filter { !it.excluded && it.actionId != null }
            val ordered = open.filter { it.label == "Recommended" } + open.filter { it.label == "Safe Fallback" } +
                open.filter { it.label != "Recommended" && it.label != "Safe Fallback" }
            if (ordered.isNotEmpty()) {
                appendLine("Options to consider")
                ordered.take(MAX_OPTIONS).forEachIndexed { i, p ->
                    val tag = when (p.label) {
                        "Recommended" -> "best fit right now"
                        "Safe Fallback" -> "the safer fallback"
                        else -> "also possible"
                    }
                    val owner = p.ownerName.takeIf { it.isNotBlank() && !it.equals("You", ignoreCase = true) }?.let { " ($it)" }.orEmpty()
                    val shaky = if (p.fragile) ", but it could change easily" else ""
                    appendLine("${i + 1}. ${p.action}$owner: $tag$shaky")
                }
            }
            report.pathways.filter { it.excluded }.take(MAX_OPTIONS).forEach { p ->
                val why = p.constraintViolations.firstOrNull()?.let { ": it would break \"$it\"" }.orEmpty()
                appendLine("Not advised: ${p.action}$why")
            }

            // Other options the decision owner has, from the saved model (the engine only ranks a few).
            val owner = ownerOf(s)
            val ranked = open.mapNotNull { it.actionId }.toSet()
            val others = owner?.let { o -> s.actionsFor(o.id).filter { it.id !in ranked }.map { it.label } }.orEmpty()
            if (others.isNotEmpty() && ordered.isNotEmpty()) appendLine("Other options you have: ${others.joinToString(", ")}.")

            val watch = watchFor(s, owner?.id)
            if (watch.isNotEmpty()) {
                appendLine()
                appendLine("What to watch for")
                watch.forEach { appendLine("• $it") }
            }
            appendLine()
            append(FOOTER)
        }.trim()
    }

    private fun ownerOf(s: Scenario) = IntakeChecklist.self(s)

    /** From the saved facts, not the engine's forecast text: who can stop this, and what people worry about. */
    private fun watchFor(s: Scenario, ownerId: String?): List<String> = buildList {
        s.stakeholders.filter { it.id != ownerId }.forEach { p ->
            when {
                p.canVeto -> add("${p.name} can stop this, so keep them on board.")
                p.fears.isNotEmpty() -> add("${p.name} worries about: ${p.fears.first().trimEnd('.')}.")
            }
        }
    }.take(MAX_WATCH + 1)

    /** What was just added, in everyday words: people, options and limits. Outcome scores stay out of sight. */
    fun understood(ops: List<ChangeOp>, before: Scenario): List<String> {
        val nameOfRef = ops.filter { it.type == OpType.ADD_STAKEHOLDER && it.ref != null }.associate { it.ref!! to (it.name ?: "") }
        val nameOfId = before.stakeholders.associate { it.id to it.name }
        fun owner(token: String?) = token?.let { nameOfRef[it] ?: nameOfId[it] }.orEmpty()
        val lines = mutableListOf<String>()
        ops.filter { it.type == OpType.ADD_STAKEHOLDER && !it.name.isNullOrBlank() && !it.name.equals("You", ignoreCase = true) }.forEach { p ->
            lines += when {
                p.gameRole == GameRole.PLAYER -> "${p.name}: makes a choice that matters here"
                p.canVeto == true -> "${p.name}: can stop this"
                else -> "${p.name}: involved"
            }
        }
        ops.filter { it.type == OpType.ADD_ACTION && !it.label.isNullOrBlank() }.groupBy { owner(it.stakeholder) }.forEach { (who, list) ->
            val names = list.joinToString(", ") { it.label!! }
            lines += if (who.equals("You", ignoreCase = true) || who.isEmpty()) "Your options: $names" else "$who could: $names"
        }
        ops.filter { it.type == OpType.ADD_CONSTRAINT && !it.label.isNullOrBlank() }.forEach { lines += "A limit: ${it.label}" }
        return lines
    }

    /** The model was saved but the engine can't run yet. Says what is missing in plain words. */
    fun needsMore(pr: PipelineResult): String {
        val missing = pr.validity.issues.firstOrNull()?.message
        return buildString {
            append("I've saved what you told me, but I need a little more before I can compare the options.")
            if (missing != null) append(" $missing")
            append(" Tell me more about who is involved and what each person could do.")
        }
    }

    fun statusLine(status: ExecutiveStatus): String = when (status) {
        ExecutiveStatus.PROCEED -> "The leading option looks workable."
        ExecutiveStatus.CONDITIONAL_TRIAL -> "The leading option looks workable if you start small and check how it goes."
        ExecutiveStatus.BLOCKED_CONSTRAINT -> "At least one option is ruled out by a limit you set."
        else -> "This is an early view. The options are close, or I'm missing some facts, so treat it as a starting point."
    }
}
