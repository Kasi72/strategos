package com.drkkr.strategos.domain.intent

import com.drkkr.strategos.domain.agent.IntakeChecklist
import com.drkkr.strategos.domain.agent.Postures
import com.drkkr.strategos.domain.model.Action
import com.drkkr.strategos.domain.model.ConstraintStatus
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.negotiation.NegotiationView
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put

/** One entry of the `top_options` block: a role, the move, and the role's fixed tradeoff line. */
data class TopOption(val role: String, val move: String, val tradeoff: String)

/**
 * ID3.3 (BLUEPRINT §5.3): up to three distinct moves in three roles, on the humanised view. Never a dominated, reactive or
 * walk-away-crossing move; with fewer valid moves, fewer entries and no filler. The maximin is computed here; the framework code
 * is not touched. Pure.
 */
object TopOptions {
    const val BEST = "best"
    const val UPSIDE = "upside"
    const val INSURANCE = "insurance"

    val TRADEOFFS: Map<String, String> = mapOf(
        BEST to "The engine's pick: the best balance across the ways they might react.",
        UPSIDE to "The most to gain if they go along.",
        INSURANCE to "The safest if they push back: the least to lose."
    )

    fun toJson(options: List<TopOption>): JsonArray = buildJsonArray {
        options.forEach { o -> addJsonObject { put("role", o.role); put("move", o.move); put("tradeoff", o.tradeoff) } }
    }

    /** It runs for intent `top_options`, and on its own when the pick is FRAGILE or the labels favoured one option everywhere. */
    fun wanted(intent: Intent, fragile: Boolean, autoTopOptions: Boolean): Boolean = intent == Intent.TOP_OPTIONS || fragile || autoTopOptions

    /**
     * [view] is the humanised view; [best] the engine's pick (its label); [dominated] the pruner's eliminated action ids (the pruner
     * already claims no dominance through a proxy-filled cell).
     */
    fun of(view: Scenario, best: String?, dominated: Set<String>): List<TopOption> {
        val owner = IntakeChecklist.self(view) ?: return emptyList()
        val other = view.players().firstOrNull { it.id != owner.id } ?: return emptyList()
        val receptive = view.actionsFor(other.id).firstOrNull { Postures.of(it) == Postures.RECEPTIVE }?.id
        val replies = view.actionsFor(other.id).map { it.id }
        val crossing = view.constraintAssessments.filter { it.constraintId == NegotiationView.CONSTRAINT_ID && it.status == ConstraintStatus.VIOLATED }
            .map { it.actionId }.toSet()
        fun score(a: Action, reply: String) = view.payoffs.firstOrNull {
            it.stakeholderId == owner.id && it.actionProfile[owner.id] == a.id && it.actionProfile[other.id] == reply
        }?.payoff?.weighted(view.weights)
        val valid = view.actionsFor(owner.id).filter { a ->
            a.id !in dominated && a.id !in crossing && Postures.of(a) != Postures.REACTIVE
        }
        val pick = view.actionsFor(owner.id).firstOrNull { it.label.equals(best, ignoreCase = true) }
        val out = mutableListOf<TopOption>()
        val used = mutableSetOf<String>()
        if (pick != null) { out += TopOption(BEST, pick.label, TRADEOFFS.getValue(BEST)); used += pick.id }
        val upside = receptive?.let { r -> valid.filter { it.id !in used }.mapNotNull { a -> score(a, r)?.let { a to it } }.maxByOrNull { it.second }?.first }
        if (upside != null) { out += TopOption(UPSIDE, upside.label, TRADEOFFS.getValue(UPSIDE)); used += upside.id }
        val insurance = valid.filter { it.id !in used }.mapNotNull { a ->
            val worst = replies.map { r -> score(a, r) ?: return@mapNotNull null }.minOrNull() ?: return@mapNotNull null
            a to worst
        }.maxByOrNull { it.second }?.first
        if (insurance != null) out += TopOption(INSURANCE, insurance.label, TRADEOFFS.getValue(INSURANCE))
        return out
    }
}
