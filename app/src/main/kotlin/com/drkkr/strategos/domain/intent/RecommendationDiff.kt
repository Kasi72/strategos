package com.drkkr.strategos.domain.intent

import com.drkkr.strategos.domain.agent.ChangeOp
import com.drkkr.strategos.domain.agent.ChangeOpDescriber
import com.drkkr.strategos.domain.agent.OpType
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.negotiation.DealFrame
import com.drkkr.strategos.domain.negotiation.WalkAwayMask
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add

/**
 * Intent Dispatch ID2.3 (BLUEPRINT §4.3): the advice changed since last turn, and the facts this turn saved that came with it.
 * Built in Kotlin only: `because` is [ChangeOpDescriber]'s line for this turn's accepted ops, never the AI's words (I-D7).
 */
data class RecommendationDiff(val from: String, val to: String, val because: List<String>) {
    fun toJson(): JsonObject = buildJsonObject {
        put("from", from)
        put("to", to)
        putJsonArray("because") { because.forEach { add(it) } }
    }

    companion object {
        const val MAX_BECAUSE = 2

        private fun same(a: String, b: String) = a.trim().equals(b.trim(), ignoreCase = true)

        /**
         * The diff, or null when there is nothing to say: not a `standard` turn (a what-if saves nothing), no changes saved this
         * turn, no previous pick, the same pick, or a previous pick that is no longer one of the user's options. That last case
         * is a reworded option (Call 1 rewords freely), not a change of advice, so it is never reported as one.
         *
         * [before] is the scenario the ops were applied to (for their names); [after] is the saved scenario now.
         */
        fun of(
            intent: Intent, previousMove: String?, pick: String?, accepted: List<ChangeOp>, before: Scenario, after: Scenario,
            ownerId: String?, deal: DealFrame? = null
        ): RecommendationDiff? {
            if (intent != Intent.STANDARD || accepted.isEmpty() || previousMove.isNullOrBlank() || pick.isNullOrBlank()) return null
            // Labels are compared as the engine's view shows them (the walk-away masked), whichever side was masked already.
            val from = WalkAwayMask.text(previousMove, deal)
            val to = WalkAwayMask.text(pick, deal)
            if (same(from, to)) return null
            if (after.actions.none { it.stakeholderId == ownerId && same(WalkAwayMask.text(it.label, deal), from) }) return null
            // Facts first; a payoff line ("Set outcome for …") only says the scores moved, so it comes last.
            val ordered = accepted.filter { it.type != OpType.SET_PAYOFF && it.type != OpType.SET_SCENARIO_FIELDS } +
                accepted.filter { it.type == OpType.SET_PAYOFF }
            val because = ordered.map { op -> WalkAwayMask.text(ChangeOpDescriber.describe(op, before, accepted), deal) }
                .distinct().take(MAX_BECAUSE)
            return RecommendationDiff(from, to, because)
        }
    }
}
