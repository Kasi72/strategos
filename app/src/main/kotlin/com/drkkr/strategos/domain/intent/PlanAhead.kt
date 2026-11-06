package com.drkkr.strategos.domain.intent

import com.drkkr.strategos.domain.agent.IntakeChecklist
import com.drkkr.strategos.domain.agent.Postures
import com.drkkr.strategos.domain.human.Channel
import com.drkkr.strategos.domain.human.Delivery
import com.drkkr.strategos.domain.human.DeliveryPlanner
import com.drkkr.strategos.domain.human.EthicsGate
import com.drkkr.strategos.domain.human.HumanContext
import com.drkkr.strategos.domain.human.RelationshipDomains
import com.drkkr.strategos.domain.human.ResponseModel
import com.drkkr.strategos.domain.human.TacticCatalog
import com.drkkr.strategos.domain.human.TacticId
import com.drkkr.strategos.domain.human.TacticKey
import com.drkkr.strategos.domain.human.Timing
import com.drkkr.strategos.domain.human.power.PowerPlan
import com.drkkr.strategos.domain.human.power.StrategicId
import com.drkkr.strategos.domain.model.Action
import com.drkkr.strategos.domain.model.Scenario
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** One branch of the plan: if they reply this way, your best next move. Both are option labels from the model. */
data class PlanBranch(val ifThey: String, val thenYou: String)

/** The close: a lock-in tactic's fixed coach hint ([id] for tests and the log; the coach hears only [hint]). */
data class PlanClose(val id: TacticKey, val hint: String)

/**
 * The `plan_ahead` block (BLUEPRINT §6.1): `{first, branches:[{if_they, then_you}], close}`. [first] is the engine's pick with its
 * delivery's timing and channel (the tactics are already in the directive's delivery). No probability anywhere.
 */
data class PlanAheadBlock(
    val first: String,
    val branches: List<PlanBranch>,
    val timing: Timing? = null,
    val channel: Channel? = null,
    val close: PlanClose? = null
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("first", buildJsonObject {
            put("move", first)
            timing?.let { put("timing", it.name.lowercase()) }
            channel?.let { put("channel", it.name.lowercase()) }
        })
        putJsonArray("branches") {
            branches.forEach { b -> add(buildJsonObject { put("if_they", b.ifThey); put("then_you", b.thenYou) }) }
        }
        close?.let { put("close", it.hint) }
    }
}

/**
 * ID4.1: three exchanges, deterministic, on the humanised view (BLUEPRINT §6.1).
 * 1. You: the engine's pick ([RouteInput.decide]); its delivery is added by [finish] once the directive has it.
 * 2. They: their two most likely replies ([ResponseModel.replies]), their own options only; one option = one branch.
 * 3. You, per branch: the highest humanised payoff against that reply, among the moves that are not reactive, not past the
 *    walk-away ([ExclusionFirst]) and not dominated.
 * 4. Close: a lock-in tactic ([close]): Power moves only from the Power plan, which already passed the Power gate.
 */
object PlanAhead {
    /** At most this many branches (their two most likely replies). */
    const val MAX_BRANCHES = 2

    /** The lock-in tactics the close is chosen from (BLUEPRINT §6.1). */
    val LOCK_IN = listOf(TacticId.PUT_IT_IN_WRITING, TacticId.STOP_AT_THE_MARK, TacticId.FUTURE_COMMITMENT)

    /**
     * The Power moves that close (hold the line at the end / set the clock). Only ever taken from [PowerPlan.deliveryMoves], so
     * a Power close has passed [com.drkkr.strategos.domain.human.power.PowerGate] and beaten the best benign move.
     */
    val POWER_CLOSE = listOf(StrategicId.HOLD_YOUR_NUMBER, StrategicId.CONTROL_THE_CLOCK)

    /** Steps 1–3 (the router's handler). [IntentResult.None] when there is no pick or no reply to branch on. */
    fun run(input: RouteInput): IntentResult {
        val view = input.view
        val ownerId = IntakeChecklist.self(view)?.id ?: return IntentResult.None
        val pick = input.decide(view)?.let { Compare.match(view, ownerId, it) } ?: return IntentResult.None
        val branches = branches(view, ownerId, pick, input.beta, input.dominated)
        if (branches.isEmpty()) return IntentResult.None
        return IntentResult.PlanAhead(PlanAheadBlock(first = pick.label, branches = branches))
    }

    fun branches(view: Scenario, ownerId: String, pick: Action, beta: Double, dominated: Set<String>): List<PlanBranch> {
        val out = ExclusionFirst.excluded(view) + dominated
        val valid = view.actionsFor(ownerId).filter { it.id !in out && Postures.of(it) != Postures.REACTIVE }
        return ResponseModel.replies(view, ownerId, pick.id, beta).take(MAX_BRANCHES).mapNotNull { reply ->
            bestResponse(view, ownerId, reply, valid)?.let { PlanBranch(ifThey = reply.label, thenYou = it.label) }
        }
    }

    /** Your highest weighted payoff against [reply] among [valid]; ties keep the option order. Null when no cell is known. */
    private fun bestResponse(view: Scenario, ownerId: String, reply: Action, valid: List<Action>): Action? =
        valid.mapNotNull { a ->
            view.payoffs.firstOrNull { it.stakeholderId == ownerId && it.actionProfile[ownerId] == a.id && it.actionProfile[reply.stakeholderId] == reply.id }
                ?.let { a to it.payoff.weighted(view.weights) }
        }.maxByOrNull { it.second }?.first

    /**
     * Step 4. A Power close first when the Power plan has one ([POWER_CLOSE], in that plan's order). Otherwise the existing
     * planner's lock-in candidates (gate and own condition) resolved as it resolves a delivery; STOP_AT_THE_MARK, whose
     * condition the planner never sets, applies here when a branch is their yes (a receptive reply): lock in what you got.
     * Null when nothing passes (nothing is written that a code path didn't produce).
     */
    fun close(
        raw: Scenario, hc: HumanContext?, pick: Action, support: Boolean, beta: Double, powerPlan: PowerPlan?,
        receptiveBranch: Boolean, powerHint: (StrategicId) -> String?
    ): PlanClose? {
        powerPlan?.deliveryMoves?.firstOrNull { it.id in POWER_CLOSE }?.let { t -> powerHint(t.id)?.let { return PlanClose(t.id, it) } }
        hc ?: return null
        val domain = RelationshipDomains.of(hc.relationship, null)
        if (EthicsGate.nothingAllowed(hc, domain, support)) return null
        val lockIn = DeliveryPlanner.candidates(raw, hc, pick, support, beta).filter { it.first.id in LOCK_IN }
        DeliveryPlanner.resolve(lockIn).firstOrNull()?.first?.let { return PlanClose(it.id, it.coachHint) }
        if (!receptiveBranch) return null
        val stop = TacticCatalog.offered.firstOrNull { it.id == TacticId.STOP_AT_THE_MARK } ?: return null
        return if (EthicsGate.allowed(stop, hc, domain, support)) PlanClose(stop.id, stop.coachHint) else null
    }

    /**
     * The block for the move the directive tells the user to make ([moveToImplement]: the pick, or its safer fallback when the pick
     * is FRAGILE), with that move's delivery and the close. The branches are rebuilt when the move is not the block's first.
     * Null when there is no such move or no branch (the plan never describes a move the engine didn't give).
     */
    fun finish(
        block: PlanAheadBlock, view: Scenario, moveToImplement: String?, beta: Double, dominated: Set<String>,
        delivery: Delivery?, close: PlanClose?
    ): PlanAheadBlock? {
        moveToImplement ?: return null
        val base = if (moveToImplement.equals(block.first, ignoreCase = true)) block else {
            val ownerId = IntakeChecklist.self(view)?.id ?: return null
            val move = view.actionsFor(ownerId).firstOrNull { it.label.equals(moveToImplement, ignoreCase = true) } ?: return null
            PlanAheadBlock(move.label, branches(view, ownerId, move, beta, dominated).ifEmpty { return null })
        }
        return base.copy(timing = delivery?.timing, channel = delivery?.channel, close = close)
    }

    /** True when one of [block]'s branches is a receptive reply of theirs in [view]. */
    fun hasReceptiveBranch(view: Scenario, block: PlanAheadBlock): Boolean =
        block.branches.any { b -> view.actions.any { it.label == b.ifThey && Postures.of(it) == Postures.RECEPTIVE } }
}
