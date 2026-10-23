package com.drkkr.strategos.domain.agent

import com.drkkr.strategos.domain.model.Scenario

/**
 * LEAN PROFILE: small models write change ops loosely (seen live on gpt-oss-20b). This repairs the predictable slips
 * before [ChangeSetValidator] runs, which stays strict:
 * - `label` used for a new person's name → name;
 * - add_* without `ref` → a ref made from the name or label;
 * - add_action with the owner in `target_id` → `stakeholder`;
 * - people and options referred to by name instead of id → the matching ref (this batch) or id (the scenario);
 * - `source: user_stated` without a quote → `ai_inferred`, so provenance stays honest (I3).
 */
object OpNormalizer {
    private val nonRef = Regex("[^a-z0-9_]+")

    fun normalize(ops: List<ChangeOp>, s: Scenario): List<ChangeOp> {
        val taken = ops.mapNotNull { it.ref }.toMutableSet()
        fun newRef(base: String?): String {
            val slug = base.orEmpty().lowercase().replace(nonRef, "_").trim('_').take(36).ifEmpty { "item" }.padEnd(2, '_')
            var ref = slug
            var n = 2
            while (ref in taken) ref = "${slug}_${n++}"
            taken += ref
            return ref
        }

        val withRefs = ops.map { raw ->
            var op = raw
            if (op.type == OpType.ADD_STAKEHOLDER && op.name.isNullOrBlank() && !op.label.isNullOrBlank()) op = op.copy(name = op.label)
            if (op.type == OpType.ADD_ACTION && op.stakeholder == null && op.targetId != null) op = op.copy(stakeholder = op.targetId, targetId = null)
            if (op.type.isAdd && op.ref.isNullOrBlank()) op = op.copy(ref = newRef(op.name ?: op.label))
            if (op.source == OpSource.USER_STATED && op.quote.isNullOrBlank()) op = op.copy(source = OpSource.AI_INFERRED)
            op
        }

        // Names → refs (this batch) or ids (scenario). Exact ids and refs are left alone.
        val people = withRefs.filter { it.type == OpType.ADD_STAKEHOLDER && it.name != null }.associate { it.name!!.lowercase() to it.ref!! } +
            s.stakeholders.associate { it.name.lowercase() to it.id }.filterKeys { k -> withRefs.none { it.type == OpType.ADD_STAKEHOLDER && it.name?.lowercase() == k } }
        val options = withRefs.filter { it.type == OpType.ADD_ACTION && it.label != null }.associate { it.label!!.lowercase() to it.ref!! } +
            s.actions.associate { it.label.lowercase() to it.id }.filterKeys { k -> withRefs.none { it.type == OpType.ADD_ACTION && it.label?.lowercase() == k } }
        val known = s.stakeholders.map { it.id }.toSet() + s.actions.map { it.id } + s.constraints.map { it.id } + taken
        fun person(token: String?) = if (token == null || token in known) token else people[token.lowercase()] ?: token
        fun option(token: String?) = if (token == null || token in known) token else options[token.lowercase()] ?: token

        return withRefs.map { op ->
            op.copy(
                stakeholder = person(op.stakeholder),
                targetId = when (op.type) {
                    OpType.UPDATE_STAKEHOLDER, OpType.REMOVE_STAKEHOLDER -> person(op.targetId)
                    OpType.UPDATE_ACTION, OpType.REMOVE_ACTION -> option(op.targetId)
                    else -> op.targetId
                },
                profile = op.profile?.map { ProfilePick(person(it.stakeholder)!!, option(it.action)!!) }
            )
        }
    }
}
