package com.drkkr.strategos.domain.game

import com.drkkr.strategos.domain.agent.Postures
import com.drkkr.strategos.domain.agent.IntakeChecklist
import com.drkkr.strategos.domain.model.FactStatus
import com.drkkr.strategos.domain.model.PayoffDimension
import com.drkkr.strategos.domain.model.Scenario

/** What the label checks found (Intent Dispatch BLUEPRINT §3.3). Values are never changed: sometimes the reading is real. */
sealed class LabelIssue {
    /** REFUSAL_BEATS_ACCEPTANCE: on [dim], the user's option [actionId] is labelled better for them when the other side refuses than when it accepts. */
    data class RefusalBeatsAcceptance(val actionId: String, val dim: PayoffDimension) : LabelIssue()
    /** LABEL_DOMINANCE: the AI labelled the user's option [actionId] at least as good as every other option everywhere, and better than each somewhere. */
    data class LabelDominance(val actionId: String) : LabelIssue()
}

/**
 * Two checks on the AI's own payoff labels, for the user's options only. Only AI-written cells count (a figure the user gave
 * or confirmed is theirs, not the AI's reading). Pure.
 */
object LabelChecks {

    fun check(view: Scenario): List<LabelIssue> {
        val you = IntakeChecklist.self(view) ?: return emptyList()
        val other = view.players().firstOrNull { it.id != you.id } ?: return emptyList()
        val mine = view.actionsFor(you.id)
        val theirs = view.actionsFor(other.id)
        if (mine.isEmpty() || theirs.isEmpty()) return emptyList()
        /** The user's AI-written payoff for (my option, their option), or null when missing or not the AI's. */
        fun cell(a: String, b: String) = view.payoffs.firstOrNull {
            it.stakeholderId == you.id && it.actionProfile[you.id] == a && it.actionProfile[other.id] == b && it.factStatus == FactStatus.AI_INFERRED
        }?.payoff?.values
        fun better(x: Double, y: Double, d: PayoffDimension) = if (d.higherIsBetter) x > y else x < y

        val issues = mutableListOf<LabelIssue>()
        val receptive = theirs.firstOrNull { Postures.of(it) == Postures.RECEPTIVE }
        val defensive = theirs.firstOrNull { Postures.of(it) == Postures.DEFENSIVE }
        if (receptive != null && defensive != null) for (a in mine) {
            val yes = cell(a.id, receptive.id) ?: continue
            val no = cell(a.id, defensive.id) ?: continue
            for (d in PayoffDimension.entries) {
                val y = yes[d] ?: continue; val n = no[d] ?: continue
                if (better(n, y, d)) issues += LabelIssue.RefusalBeatsAcceptance(a.id, d)
            }
        }

        if (mine.size >= 2) {
            val table = mine.associate { a -> a.id to theirs.map { b -> cell(a.id, b.id) } }
            val complete = table.filterValues { cols -> cols.all { it != null } }.mapValues { (_, cols) -> cols.map { it!! } }
            if (complete.size == mine.size) for ((a, colsA) in complete) {
                val dominatesAll = complete.filterKeys { it != a }.all { (_, colsB) ->
                    var strictly = false
                    val weakly = colsA.indices.all { j ->
                        (colsA[j].keys + colsB[j].keys).all { d ->
                            val x = colsA[j][d] ?: return@all false; val y = colsB[j][d] ?: return@all false
                            if (better(x, y, d)) strictly = true
                            !better(y, x, d)
                        }
                    }
                    weakly && strictly
                }
                if (dominatesAll) issues += LabelIssue.LabelDominance(a)
            }
        }
        return issues
    }
}
