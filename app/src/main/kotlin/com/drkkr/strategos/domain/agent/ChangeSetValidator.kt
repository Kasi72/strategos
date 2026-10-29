package com.drkkr.strategos.domain.agent

import com.drkkr.strategos.domain.model.GameRole
import com.drkkr.strategos.domain.model.PayoffDimension
import com.drkkr.strategos.domain.model.Scenario
import java.util.UUID

data class IndexedOp(val index: Int, val op: ChangeOp)
data class OpRejection(val index: Int, val reason: String)
data class ValidatedChangeSet(val accepted: List<IndexedOp>, val rejected: List<OpRejection>, val refIds: Map<String, String>)

/** Validates agent change ops against a scenario (blueprint §8.9). Never throws. */
object ChangeSetValidator {
    const val MAX_OPS = 40
    const val MAX_RATIONALE = 500
    const val MAX_NAME = 80
    const val MAX_LABEL = 120
    const val MAX_MATRIX_CELLS = 200
    const val MAX_PLAYERS = 3     // §7.4 modelling rules: 2 players whenever possible, 3 at most
    private val REF = Regex("[a-z0-9_]{2,40}")

    fun validate(scenario: Scenario, ops: List<ChangeOp>, newId: () -> String = { UUID.randomUUID().toString() }): ValidatedChangeSet {
        if (ops.size > MAX_OPS) {
            return ValidatedChangeSet(emptyList(), ops.indices.map { OpRejection(it, "too_many_ops") }, emptyMap())
        }
        val ws = Workspace(scenario, mutableMapOf(), batchMode = true)
        val accepted = mutableListOf<IndexedOp>()
        val rejected = mutableListOf<OpRejection>()
        ops.forEachIndexed { i, op ->
            val reason = try { ws.check(op) } catch (e: Exception) { "invalid_op" }
            if (reason == null) {
                ws.commit(op, newId)
                accepted += IndexedOp(i, op)
            } else {
                rejected += OpRejection(i, reason)
            }
        }
        return ValidatedChangeSet(accepted, rejected, ws.refIds.toMap())
    }

    /**
     * Re-checks one op against the current working scenario at apply time (§8.10 step 2).
     * Refs already applied resolve through [refIds]; returns the rejection reason or null.
     * [dims] is the dimension set established earlier in the same apply, if any.
     */
    fun recheck(scenario: Scenario, op: ChangeOp, refIds: Map<String, String>, dims: Set<PayoffDimension>? = null): String? =
        try {
            Workspace(scenario, refIds.toMutableMap(), batchMode = false, establishedDims = dims).check(op)
        } catch (e: Exception) {
            "invalid_op"
        }

    /** Resolved model state as ops are processed in order. */
    private class Workspace(
        scenario: Scenario,
        val refIds: MutableMap<String, String>,
        private val batchMode: Boolean,
        establishedDims: Set<PayoffDimension>? = null
    ) {
        val roles: MutableMap<String, GameRole> = scenario.stakeholders.associate { it.id to it.gameRole }.toMutableMap()
        val actionOwner: MutableMap<String, String> = scenario.actions.associate { it.id to it.stakeholderId }.toMutableMap()
        // BLUEPRINT-QUESTION: §8.9 reads as "must equal dimensionsInUse AND have 3-6 dims". The templates use all 10, so the
        // 3-6 count is only enforced when a batch establishes the set (no existing payoffs); otherwise the set must match.
        var dims: Set<PayoffDimension>? = establishedDims
            ?: scenario.payoffs.flatMap { it.payoff.values.keys }.toSet().takeIf { it.isNotEmpty() }

        fun resolve(idOrRef: String?): String? = idOrRef?.let { refIds[it] ?: it }

        fun players(): List<String> = roles.filterValues { it == GameRole.PLAYER }.keys.toList()

        fun check(op: ChangeOp): String? {
            if (op.rationale.isNullOrBlank() || op.rationale.length > MAX_RATIONALE) return "rationale_required"
            if (op.source == OpSource.USER_STATED && op.quote.isNullOrBlank()) return "quote_required"
            if (op.type.isAdd) {
                val ref = op.ref ?: return "ref_required"
                if (!REF.matches(ref)) return "bad_ref"
                if (batchMode && ref in refIds) return "bad_ref"
                if (!batchMode && refIds[ref]?.let { it in roles || it in actionOwner } == true) return "bad_ref"
            } else if (op.ref != null && !REF.matches(op.ref)) {
                return "bad_ref"
            }
            outOfRange(op)?.let { return "out_of_range:$it" }

            return when (op.type) {
                OpType.SET_SCENARIO_FIELDS -> null
                OpType.ADD_STAKEHOLDER -> when {
                    op.name.isNullOrBlank() || op.name.length > MAX_NAME -> "name_required"
                    op.gameRole == GameRole.PLAYER && players().size >= MAX_PLAYERS -> "too_many_players"
                    else -> null
                }
                OpType.UPDATE_STAKEHOLDER -> {
                    val id = resolve(op.targetId)
                    when {
                        id !in roles -> "unknown_target"
                        op.gameRole == GameRole.PLAYER && roles[id] != GameRole.PLAYER && players().size >= MAX_PLAYERS -> "too_many_players"
                        else -> null
                    }
                }
                OpType.REMOVE_STAKEHOLDER ->
                    if (resolve(op.targetId) !in roles) "unknown_target" else null
                OpType.UPDATE_ACTION, OpType.REMOVE_ACTION ->
                    if (resolve(op.targetId) !in actionOwner) "unknown_target" else null
                OpType.ADD_ACTION -> when {
                    resolve(op.stakeholder) !in roles -> "unknown_stakeholder"
                    op.label.isNullOrBlank() || op.label.length > MAX_LABEL -> "label_required"
                    else -> null
                }
                OpType.SET_PAYOFF -> checkPayoff(op)
                OpType.ADD_CONSTRAINT ->
                    if (op.label.isNullOrBlank() || op.constraintKind == null) "constraint_incomplete" else null
                // BLUEPRINT-QUESTION: §8.9 gives no rule for a belief or assumption without text; reuse label_required.
                OpType.ADD_BELIEF -> when {
                    op.probability == null -> "probability_required"
                    op.label.isNullOrBlank() -> "label_required"
                    else -> null
                }
                OpType.ADD_ASSUMPTION ->
                    if (op.label.isNullOrBlank() && op.description.isNullOrBlank()) "label_required" else null
            }
        }

        private fun checkPayoff(op: ChangeOp): String? {
            val sid = resolve(op.stakeholder)
            val role = roles[sid] ?: return "unknown_stakeholder"
            if (role != GameRole.PLAYER) return "payoff_for_non_player"
            val players = players()
            val profiles = players.fold(1L) { acc, p -> acc * actionOwner.values.count { it == p }.coerceAtLeast(1) }
            if (profiles * players.size > MAX_MATRIX_CELLS) return "matrix_too_large"

            val picks = op.profile.orEmpty().map { resolve(it.stakeholder) to resolve(it.action) }
            val pickedPlayers = picks.map { it.first }
            if (pickedPlayers.size != players.size || pickedPlayers.toSet() != players.toSet()) return "bad_profile"
            if (picks.any { (s, a) -> actionOwner[a] != s }) return "bad_profile"

            val values = op.values.orEmpty()
            val set = values.map { it.dimension }.toSet()
            val expected = dims
            val mismatch = when {
                set.size != values.size -> true
                expected != null -> set != expected
                else -> set.size !in 3..6
            }
            if (mismatch) {
                val list = (expected ?: set).map { it.name }.sorted().joinToString(",")
                return "dimension_mismatch:[$list]"
            }
            return null
        }

        private fun outOfRange(op: ChangeOp): String? {
            fun bad(v: Number?, lo: Double, hi: Double) = v != null && (v.toDouble() < lo || v.toDouble() > hi || v.toDouble().isNaN())
            return when {
                bad(op.authority, 0.0, 10.0) -> "authority"
                bad(op.influence, 0.0, 10.0) -> "influence"
                bad(op.credibility, 0.0, 10.0) -> "credibility"
                bad(op.difficulty, 1.0, 5.0) -> "difficulty"
                bad(op.timeCostDays, 0.0, Double.MAX_VALUE) -> "time_cost_days"
                bad(op.probability, 0.0, 1.0) -> "probability"
                op.cost?.let { it.isNaN() || it.isInfinite() } == true -> "cost"
                (op.goals?.size ?: 0) > 5 -> "goals"
                (op.fears?.size ?: 0) > 5 -> "fears"
                (op.incentives?.size ?: 0) > 5 -> "incentives"
                op.values.orEmpty().any { bad(it.value, 0.0, 10.0) } -> "values"
                else -> null
            }
        }

        fun commit(op: ChangeOp, newId: () -> String) {
            val ref = op.ref
            if (op.type.isAdd && ref != null) refIds[ref] = newId()
            when (op.type) {
                OpType.ADD_STAKEHOLDER -> roles[refIds.getValue(ref!!)] = op.gameRole ?: GameRole.INFLUENCER
                OpType.UPDATE_STAKEHOLDER -> op.gameRole?.let { roles[resolve(op.targetId)!!] = it }
                OpType.REMOVE_STAKEHOLDER -> {
                    val sid = resolve(op.targetId)!!
                    roles.remove(sid)
                    actionOwner.entries.removeAll { it.value == sid }
                }
                OpType.ADD_ACTION -> actionOwner[refIds.getValue(ref!!)] = resolve(op.stakeholder)!!
                OpType.REMOVE_ACTION -> actionOwner.remove(resolve(op.targetId))
                OpType.SET_PAYOFF -> if (dims == null) dims = op.values.orEmpty().map { it.dimension }.toSet()
                else -> Unit
            }
        }
    }
}
