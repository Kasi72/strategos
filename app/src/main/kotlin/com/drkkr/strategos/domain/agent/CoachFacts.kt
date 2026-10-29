package com.drkkr.strategos.domain.agent

import com.drkkr.strategos.domain.analysis.PipelineResult
import com.drkkr.strategos.domain.model.Scenario

/**
 * COMPANION REPLY: the engine's result as a few plain lines for the model to talk from. No scores, no framework names,
 * nothing the engine did not produce. The model is told to use only these facts.
 */
object CoachFacts {
    private const val MAX_OPTIONS = 3
    private const val MAX_PEOPLE = 5

    fun build(s: Scenario, pr: PipelineResult): String {
        val report = pr.report ?: return "The analysis could not run yet; not enough is known."
        val owner = IntakeChecklist.self(s)
        return buildString {
            if (s.stakes.isNotBlank()) appendLine("Goal: ${s.stakes}")
            val cov = PayoffCoverage.of(s)
            if (cov.filled == 0) {
                appendLine("The options cannot be compared yet; how each person would feel about each outcome is unknown.")
            } else {
                appendLine("Overall: ${PlainResult.statusLine(report.executiveStatus)}")
                val open = report.pathways.filter { !it.excluded && it.actionId != null }
                val ordered = open.filter { it.label == "Recommended" } + open.filter { it.label == "Safe Fallback" } +
                    open.filter { it.label != "Recommended" && it.label != "Safe Fallback" }
                ordered.take(MAX_OPTIONS).forEach { p ->
                    val tag = when (p.label) { "Recommended" -> "leading option"; "Safe Fallback" -> "safer fallback"; else -> "also possible" }
                    val who = p.ownerName.takeIf { it.isNotBlank() && !it.equals("You", ignoreCase = true) }?.let { " (for $it)" }.orEmpty()
                    appendLine("- $tag: ${p.action}$who${if (p.fragile) "; could change easily" else ""}")
                }
                report.pathways.filter { it.excluded }.take(MAX_OPTIONS).forEach { p ->
                    appendLine("- ruled out: ${p.action}${p.constraintViolations.firstOrNull()?.let { " (breaks \"$it\")" }.orEmpty()}")
                }
                if (pr.provenance.payoffsAi > 0) appendLine("Some scores are the app's own guesses about how each person feels.")
                if (cov.filled < cov.expected) appendLine("Some combinations are still missing.")
            }
            s.stakeholders.filter { it.id != owner?.id }.take(MAX_PEOPLE).forEach { p ->
                val bits = buildList {
                    if (p.canVeto) add("can stop this")
                    p.fears.firstOrNull()?.let { add("worries about: ${it.trimEnd('.')}") }
                    p.goals.firstOrNull()?.let { add("wants: ${it.trimEnd('.')}") }
                }
                if (bits.isNotEmpty()) appendLine("- ${p.name} ${bits.joinToString("; ")}")
            }
            owner?.let { o -> s.actionsFor(o.id).map { it.label }.takeIf { it.isNotEmpty() }?.let { appendLine("The user's options: ${it.joinToString("; ")}") } }
        }.trim()
    }
}
